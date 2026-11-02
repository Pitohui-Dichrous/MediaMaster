package io.mediamaster.app.player;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.mediamaster.app.media.VideoItem;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Private aggregate play counts. UI queries never open the database or read media files. */
public final class PlaybackStatsStore {
  private static final String DATABASE = "playback-stats.db";
  private static final int MAX_RECORDS = 50_000;
  public static final long MAX_STORAGE_BYTES = 5_000_000L;
  private static final long JOURNAL_HEADROOM = 65_536L;
  private static volatile PlaybackStatsStore instance;
  private final Object lock = new Object();
  private final LinkedHashMap<String, Record> counts = new LinkedHashMap<>(16, .75f, true);
  private final Map<String, RecordReference> retained = new HashMap<>();
  private final ReferenceQueue<Record> collectedRecords = new ReferenceQueue<>();
  private final Map<String, Record> pendingRecords = new HashMap<>();
  private final Map<PreparedWeights, Boolean> liveQueues = new WeakHashMap<>();
  private boolean completeInitialIndex;
  private final ArrayList<Runnable> readyCallbacks = new ArrayList<>();
  private final ArrayList<Runnable> capacityListeners = new ArrayList<>();
  private final ArrayList<Runnable> afterCapacity = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io;
  private final Map<String, Entry> dirty = new HashMap<>();
  private final ArrayList<String[]> migrations = new ArrayList<>();
  private final Map<VoteContribution, Boolean> activeContributions = new WeakHashMap<>();
  private Object voteGeneration = new Object();
  private Object identityGeneration = new Object();
  private final ArrayList<Runnable> afterReset = new ArrayList<>();
  private final ArrayList<Consumer<Boolean>> resetCallbacks = new ArrayList<>();
  private final Database database;
  private final int maxRecords;
  private final LongSupplier clock;
  private final long storageBytes;
  private boolean budgetInitialized, capacityPending;
  private int capacityNotice;
  private long maximumPages, pageSize;
  private boolean ready;
  private boolean diskLoaded;
  private boolean resetPending;
  private long lastRecordedAt;

  public static PlaybackStatsStore get(Context context) {
    PlaybackStatsStore result = instance;
    if (result == null) {
      synchronized (PlaybackStatsStore.class) {
        result = instance;
        if (result == null)
          instance = result = new PlaybackStatsStore(context, DATABASE, MAX_RECORDS);
      }
    }
    return result;
  }

  // Separate, caller-owned databases allow instrumentation to avoid touching user statistics.
  PlaybackStatsStore(Context context, String databaseName, int maxRecords) {
    this(context, databaseName, maxRecords, Executors.newSingleThreadExecutor());
  }

  PlaybackStatsStore(Context context, String databaseName, int maxRecords, ExecutorService io) {
    this(context, databaseName, maxRecords, io, System::currentTimeMillis);
  }

  PlaybackStatsStore(
      Context context,
      String databaseName,
      int maxRecords,
      ExecutorService io,
      LongSupplier clock) {
    this(context, databaseName, maxRecords, io, clock, MAX_STORAGE_BYTES);
  }

  PlaybackStatsStore(
      Context context,
      String databaseName,
      int maxRecords,
      ExecutorService io,
      LongSupplier clock,
      long storageBytes) {
    if (maxRecords < 1) throw new IllegalArgumentException("Positive record limit required");
    if (storageBytes < 192_000) throw new IllegalArgumentException("Storage budget too small");
    this.io = io;
    this.maxRecords = maxRecords;
    this.clock = clock;
    this.storageBytes = storageBytes;
    database = new Database(context.getApplicationContext(), databaseName, clock);
    io.execute(this::load);
  }

  /** Foreground owners subscribe; a pending notice is never consumed merely by posting it. */
  public void addCapacityListener(Runnable listener) {
    synchronized (lock) {
      if (!capacityListeners.contains(listener)) capacityListeners.add(listener);
    }
    postCapacityListener(listener);
  }

  public void removeCapacityListener(Runnable listener) {
    synchronized (lock) {
      capacityListeners.remove(listener);
    }
  }

  /** Memory-only consumption. The durable acknowledgment is serialized with database writes. */
  public boolean consumeCapacityNotice() {
    synchronized (lock) {
      if (capacityNotice != 1) return false;
      capacityNotice = 2;
    }
    io.execute(
        () -> {
          try {
            openDatabase().execSQL("UPDATE storage_state SET notice=2 WHERE id=1 AND notice=1");
          } catch (RuntimeException failure) {
            Log.w("PlaybackStatsStore", "Capacity notice acknowledgment could not be saved");
          }
        });
    return true;
  }

  private void postCapacityListener(Runnable listener) {
    main.post(
        () -> {
          synchronized (lock) {
            if (capacityNotice != 1 || !capacityListeners.contains(listener)) return;
          }
          listener.run();
        });
  }

  private void publishCapacityNotice(SQLiteDatabase db) {
    int state = (int) scalar(db, "SELECT notice FROM storage_state WHERE id=1");
    ArrayList<Runnable> listeners;
    synchronized (lock) {
      // An in-flight local acknowledgment must not be undone by a worker read of pending=1.
      if (capacityNotice != 2) capacityNotice = state;
      listeners = new ArrayList<>(capacityListeners);
    }
    for (Runnable listener : listeners) postCapacityListener(listener);
  }

  public boolean isReady() {
    synchronized (lock) {
      return ready;
    }
  }

  /** Always posts completion to the main thread, including when loading has already completed. */
  public void whenReady(Runnable callback) {
    synchronized (lock) {
      if (!ready) {
        readyCallbacks.add(callback);
        return;
      }
    }
    main.post(callback);
  }

  public long playCount(VideoItem item) {
    synchronized (lock) {
      Record record = recordFor(key(item));
      if (!record.loaded) {
        requestHydration(record);
        return record.pending == null ? 0 : record.pending.count;
      }
      return record.entry == null ? 0 : record.entry.count;
    }
  }

  public long[] snapshot(List<VideoItem> videos) {
    long[] result = new long[videos.size()];
    for (int i = 0; i < result.length; i++) result[i] = playCount(videos.get(i));
    return result;
  }

  public double recentScore(VideoItem item) {
    synchronized (lock) {
      Record record = recordFor(key(item));
      if (!record.loaded) {
        requestHydration(record);
        return Double.NaN;
      }
      long now = now();
      Entry before = record.entry;
      Entry corrected = correctFutureAnchor(before, now);
      if (corrected != before) io.execute(this::flushPending);
      return corrected == null
          ? 0
          : RecentPlaybackPolicy.decay(corrected.score, corrected.scoreUpdated, now);
    }
  }

  public double[] recentSnapshot(List<VideoItem> videos) {
    double[] result = new double[videos.size()];
    synchronized (lock) {
      long now = now();
      boolean repair = false;
      for (int i = 0; i < result.length; i++) {
        Record record = recordFor(key(videos.get(i)));
        if (!record.loaded) {
          requestHydration(record);
          result[i] = Double.NaN;
          continue;
        }
        Entry before = record.entry, entry = correctFutureAnchor(before, now);
        repair |= before != entry;
        result[i] =
            entry == null ? 0 : RecentPlaybackPolicy.decay(entry.score, entry.scoreUpdated, now);
      }
      if (repair) io.execute(this::flushPending);
    }
    return result;
  }

  /**
   * Load the complete candidate queue before random sampling. Failure never becomes zero history.
   */
  public void prepareWeights(List<VideoItem> videos, Consumer<PreparedWeights> callback) {
    List<VideoItem> candidates = new ArrayList<>(videos);
    io.execute(
        () -> {
          Record[] records = new Record[candidates.size()];
          for (int i = 0; i < records.length; i++) {
            String key = key(candidates.get(i));
            synchronized (lock) {
              records[i] = recordFor(key);
            }
          }
          PreparedWeights prepared = null;
          if (hydrate(java.util.Arrays.asList(records))) {
            flushPending();
            synchronized (lock) {
              prepared = new PreparedWeights(this, records);
              liveQueues.put(prepared, Boolean.TRUE);
            }
          }
          PreparedWeights result = prepared;
          main.post(() -> callback.accept(result));
        });
  }

  public static final class PreparedWeights {
    private final PlaybackStatsStore owner;
    private final Record[] records;

    private PreparedWeights(PlaybackStatsStore owner, Record[] records) {
      this.owner = owner;
      this.records = records;
    }

    public int size() {
      return records.length;
    }

    public double weight(int index) {
      synchronized (owner.lock) {
        Record record = records[index].resolve();
        long now = owner.now();
        Entry before = record.entry, entry = owner.correctFutureAnchor(before, now);
        if (before != entry) owner.io.execute(owner::flushPending);
        return entry == null ? 1 : weightAt(entry, now);
      }
    }
  }

  int cachedRecordCount() {
    synchronized (lock) {
      return counts.size();
    }
  }

  Object voteGeneration() {
    synchronized (lock) {
      return voteGeneration;
    }
  }

  boolean isCurrent(VoteContribution contribution) {
    synchronized (lock) {
      return contribution.valid
          && contribution.generation == voteGeneration
          && contribution.record.resolve().loaded
          && (contribution.record.resolve().entry != null || contribution.vote == 0);
    }
  }

  boolean canAcceptVote() {
    synchronized (lock) {
      return !resetPending && !capacityPending;
    }
  }

  /** Reindex only after an owned identity change; no persistent alias list or session registry. */
  Object refreshSessionVotes(
      Map<String, VoteContribution> votes, Map<String, String> aliases, Object observed) {
    synchronized (lock) {
      if (resetPending || capacityPending || observed == identityGeneration) return observed;
      Map<String, VoteContribution> updated = new HashMap<>();
      Map<String, String> redirected = new HashMap<>();
      boolean removedDuplicate = false;
      for (Map.Entry<String, VoteContribution> vote : votes.entrySet()) {
        VoteContribution contribution = vote.getValue();
        if (!isCurrent(contribution)) continue;
        redirected.put(vote.getKey(), contribution.key);
        redirected.put(contribution.originKey, contribution.key);
        VoteContribution existing = updated.get(contribution.key);
        if (existing == null) updated.put(contribution.key, contribution);
        else {
          boolean replace =
              contribution.order > existing.order
                  || contribution.order == existing.order
                      && contribution.originKey.compareTo(existing.originKey) > 0;
          withdrawContribution(replace ? existing : contribution, now());
          if (replace) updated.put(contribution.key, contribution);
          removedDuplicate = true;
        }
      }
      votes.clear();
      votes.putAll(updated);
      Map<String, String> movedAliases = new HashMap<>();
      for (Map.Entry<String, String> alias : aliases.entrySet()) {
        String target = redirected.getOrDefault(alias.getValue(), alias.getValue());
        if (updated.containsKey(target)) movedAliases.put(alias.getKey(), target);
      }
      aliases.clear();
      aliases.putAll(movedAliases);
      for (Map.Entry<String, String> redirect : redirected.entrySet())
        if (!redirect.getKey().equals(redirect.getValue()))
          aliases.put(redirect.getKey(), redirect.getValue());
      if (removedDuplicate) io.execute(this::flushPending);
      return identityGeneration;
    }
  }

  private void withdrawContribution(VoteContribution contribution, long now) {
    Entry entry = correctFutureAnchor(knownEntry(contribution.key), now);
    if (entry != null && isCurrent(contribution)) {
      FeedbackMass mass = massAt(entry, now);
      double removal = contributionAt(contribution, mass.exponent, now);
      FeedbackMass reduced =
          mass.withVotes(
              contribution.vote == 1 ? Math.max(0, mass.likes - removal) : mass.likes,
              contribution.vote == -1 ? Math.max(0, mass.dislikes - removal) : mass.dislikes);
      put(
          new Entry(
              entry.key,
              entry.count,
              entry.lastPlayed,
              entry.score,
              entry.scoreUpdated,
              reduced,
              now));
      dirty.put(entry.key, knownEntry(entry.key));
    }
    contribution.valid = false;
    activeContributions.remove(contribution);
  }

  /** Apply/undo one live window's actual mass atomically; no permanent selected state is stored. */
  VoteContribution applyVote(String key, VoteContribution previous, int requested) {
    return applyVote(key, previous, requested, null);
  }

  VoteContribution applyVote(
      String key, VoteContribution previous, int requested, Object expectedGeneration) {
    if (requested < -1 || requested > 1) throw new IllegalArgumentException("Invalid vote");
    synchronized (lock) {
      // The Activity/session precheck is insufficient: a reset can complete between that check
      // and this mutation. Inferred events must be checked atomically against the database state.
      if (expectedGeneration != null && expectedGeneration != voteGeneration) return null;
      if (!canAcceptVote()) return null;
      if (previous != null && isCurrent(previous)) key = previous.key;
      String origin = previous != null && isCurrent(previous) ? previous.originKey : key;
      Record record = recordFor(key);
      if (!record.loaded) {
        requestHydration(record);
        return null;
      }
      long now = now();
      Entry entry = correctFutureAnchor(record.entry, now);
      if (entry == null && requested == 0) {
        // Remember a window's explicit cancellation without recreating an evicted aggregate or
        // spending database capacity on a video with no history. A zero token still follows owned
        // renames and is invalidated by a weight reset, just like a nonzero contribution.
        long changed = Math.max(now, add(lastRecordedAt, 1));
        lastRecordedAt = changed;
        VoteContribution cancelled =
            new VoteContribution(
                key, origin, 0, 0, 0, now, changed, voteGeneration, record.resolve());
        activeContributions.put(cancelled, Boolean.TRUE);
        return cancelled;
      }
      FeedbackMass mass = massAt(entry, now);
      if (previous != null && isCurrent(previous) && previous.key.equals(key)) {
        double removal = contributionAt(previous, mass.exponent, now);
        mass =
            mass.withVotes(
                previous.vote == 1 ? Math.max(0, mass.likes - removal) : mass.likes,
                previous.vote == -1 ? Math.max(0, mass.dislikes - removal) : mass.dislikes);
        previous.valid = false;
        activeContributions.remove(previous);
      }
      double likes = mass.likes, dislikes = mass.dislikes, applied = 0;
      if (requested == 1) {
        likes += mass.unit();
        applied = likes - mass.likes;
      } else if (requested == -1) {
        dislikes += mass.unit();
        applied = dislikes - mass.dislikes;
      }
      FeedbackMass changedMass = mass.withVotes(likes, dislikes);
      applied = FeedbackMass.convert(applied, mass.exponent, now, changedMass.exponent, now);
      long changed = Math.max(now, add(lastRecordedAt, 1));
      lastRecordedAt = changed;
      Entry updated =
          new Entry(
              key,
              entry == null ? 0 : entry.count,
              changed,
              entry == null ? 0 : entry.score,
              entry == null ? now : entry.scoreUpdated,
              changedMass,
              now);
      put(updated);
      dirty.put(key, knownEntry(key));
      io.execute(this::flushPending);
      if (knownEntry(key) != updated) return null;
      VoteContribution contribution =
          new VoteContribution(
              key,
              origin,
              requested,
              applied,
              changedMass.exponent,
              now,
              changed,
              voteGeneration,
              record.resolve());
      activeContributions.put(contribution, Boolean.TRUE);
      return contribution;
    }
  }

  static final class VoteContribution {
    String key;
    final String originKey;
    final int vote;
    final Object generation;
    final long order;
    Record record;
    double amount;
    int exponent;
    long updated;
    boolean valid = true;

    VoteContribution(
        String key,
        String origin,
        int vote,
        double amount,
        int exponent,
        long updated,
        long order,
        Object generation,
        Record record) {
      this.key = key;
      originKey = origin;
      this.vote = vote;
      this.amount = amount;
      this.exponent = exponent;
      this.updated = updated;
      this.generation = generation;
      this.order = order;
      this.record = record;
    }
  }

  /** Compatibility read: cold history is NaN until prepared, never invented as a neutral weight. */
  public double weight(VideoItem item) {
    synchronized (lock) {
      Record record = recordFor(key(item));
      if (!record.loaded) {
        requestHydration(record);
        return Double.NaN;
      }
      long now = now();
      Entry before = record.entry, entry = correctFutureAnchor(before, now);
      if (before != entry) io.execute(this::flushPending);
      return entry == null ? 1 : weightAt(entry, now);
    }
  }

  public double[] weightSnapshot(List<VideoItem> videos) {
    double[] result = new double[videos.size()];
    synchronized (lock) {
      long now = now();
      boolean repair = false;
      for (int i = 0; i < result.length; i++) {
        Record record = recordFor(key(videos.get(i)));
        if (!record.loaded) {
          requestHydration(record);
          result[i] = Double.NaN;
          continue;
        }
        Entry before = record.entry, entry = correctFutureAnchor(before, now);
        repair |= before != entry;
        result[i] = entry == null ? 1 : weightAt(entry, now);
      }
      if (repair) io.execute(this::flushPending);
    }
    return result;
  }

  /**
   * Clear recent penalties and preferences, preserving lifetime totals. The callback is posted to
   * main only after storage has committed (or failed without changing in-memory weights). New
   * viewing/rename interactions wait in order; votes are rejected while reset is unresolved. UI
   * reads never wait on disk.
   */
  public void resetWeights(Consumer<Boolean> callback) {
    synchronized (lock) {
      resetCallbacks.add(callback);
      if (resetPending) return;
      resetPending = true;
      io.execute(this::resetOnIo);
    }
  }

  private void resetOnIo() {
    boolean succeeded = false;
    long anchor = now();
    try {
      flushPending();
      openDatabase();
      synchronized (lock) {
        diskLoaded = true;
        if (!diskLoaded || !dirty.isEmpty() || !migrations.isEmpty() || !pendingRecords.isEmpty())
          throw new IllegalStateException("Pending statistics were not saved");
      }
      SQLiteDatabase db = openDatabase();
      db.beginTransaction();
      try {
        db.execSQL(
            "UPDATE plays SET"
                + " recent_score=0,score_updated=?,exposure=0,likes=0,dislikes=0,preference_updated=?,mass_exponent=0",
            new Object[] {anchor, anchor});
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
      succeeded = true;
    } catch (RuntimeException failure) {
      Log.w("PlaybackStatsStore", "Playback weights could not be reset");
    }
    ArrayList<Consumer<Boolean>> callbacks;
    synchronized (lock) {
      if (succeeded) {
        for (RecordReference reference : retained.values()) {
          Record record = reference.get();
          if (record == null || record.redirect != null || !record.loaded || record.entry == null)
            continue;
          Entry entry = record.entry;
          record.entry =
              new Entry(entry.key, entry.count, entry.lastPlayed, 0, anchor, 0, 0, 0, anchor);
        }
        dirty.clear();
        activeContributions.clear();
        voteGeneration = new Object();
      }
      resetPending = false;
      callbacks = new ArrayList<>(resetCallbacks);
      resetCallbacks.clear();
      ArrayList<Runnable> deferred = new ArrayList<>(afterReset);
      afterReset.clear();
      for (Runnable action : deferred) action.run();
    }
    final boolean result = succeeded;
    for (Consumer<Boolean> callback : callbacks) main.post(() -> callback.accept(result));
  }

  /**
   * The caller records a completed viewing entry; unknown rows must be hydrated before addition.
   */
  public void recordPlay(VideoItem item) {
    synchronized (lock) {
      if (resetPending) {
        afterReset.add(() -> recordPlay(item));
        return;
      }
      if (capacityPending) {
        afterCapacity.add(() -> recordPlay(item));
        return;
      }
      Record record = recordFor(key(item));
      if (!record.loaded) {
        long at = now();
        Entry previous = record.pending;
        if (previous == null) {
          record.pendingFirstAt = at;
          record.pendingCeiling = RecentPlaybackPolicy.MAX_SCORE;
        } else {
          record.pendingCeiling =
              RecentPlaybackPolicy.addPlay(record.pendingCeiling, previous.scoreUpdated, at);
        }
        record.pending =
            new Entry(
                record.key,
                previous == null ? 1 : add(previous.count, 1),
                at,
                previous == null
                    ? 1
                    : RecentPlaybackPolicy.addPlay(previous.score, previous.scoreUpdated, at),
                at,
                massAt(previous, at).addExposure(),
                at);
        pendingRecords.put(record.key, record);
        requestHydration(record);
        return;
      }
      applyView(record, now());
      io.execute(this::flushPending);
    }
  }

  private void applyView(Record record, long at) {
    record = record.resolve();
    Entry previous = correctFutureAnchor(record.entry, at);
    long count = previous == null ? 1 : add(previous.count, 1);
    long recordedAt = Math.max(at, add(lastRecordedAt, 1));
    lastRecordedAt = recordedAt;
    double score =
        previous == null
            ? 1
            : RecentPlaybackPolicy.addPlay(previous.score, previous.scoreUpdated, at);
    Entry entry =
        new Entry(record.key, count, recordedAt, score, at, massAt(previous, at).addExposure(), at);
    put(entry);
    dirty.put(entry.key, entry);
  }

  /** Preserve counts after an app-owned rename changes a provider's otherwise path-based ID. */
  public void migrate(VideoItem original, VideoItem replacement) {
    String before = key(original), after = key(replacement);
    if (before.equals(after)) return;
    synchronized (lock) {
      if (resetPending) {
        afterReset.add(() -> migrate(original, replacement));
        return;
      }
      if (capacityPending) {
        afterCapacity.add(() -> migrate(original, replacement));
        return;
      }
      migrations.add(new String[] {before, after});
      io.execute(this::flushPending);
    }
  }

  private static final String[] COLUMNS = {
    "media_key",
    "play_count",
    "last_played",
    "recent_score",
    "score_updated",
    "exposure",
    "likes",
    "dislikes",
    "preference_updated",
    "mass_exponent"
  };

  private static Entry readEntry(Cursor cursor) {
    return new Entry(
        cursor.getString(0),
        cursor.getLong(1),
        cursor.getLong(2),
        cursor.getDouble(3),
        cursor.getLong(4),
        cursor.getDouble(5),
        cursor.getDouble(6),
        cursor.getDouble(7),
        cursor.getLong(8),
        cursor.getInt(9));
  }

  private void load() {
    ArrayList<Entry> loaded = new ArrayList<>();
    boolean success = false;
    try {
      SQLiteDatabase db = openDatabase();
      try (Cursor cursor =
          db.query(
              "plays",
              COLUMNS,
              null,
              null,
              null,
              null,
              "last_played DESC,media_key DESC",
              Integer.toString(maxRecords + 1))) {
        while (cursor.moveToNext()) loaded.add(readEntry(cursor));
      }
      success = true;
    } catch (RuntimeException failure) {
      Log.w("PlaybackStatsStore", "Playback counts could not be loaded");
    }
    ArrayList<Runnable> callbacks;
    synchronized (lock) {
      completeInitialIndex = success && loaded.size() <= maxRecords;
      int retainedCount = Math.min(maxRecords, loaded.size());
      for (int i = retainedCount - 1; i >= 0; i--) {
        Entry entry = loaded.get(i);
        Record record = rawRecord(entry.key);
        if (!record.loaded) publishLoaded(record, entry);
      }
      diskLoaded = success;
      ready = true;
      callbacks = new ArrayList<>(readyCallbacks);
      readyCallbacks.clear();
    }
    if (success) io.execute(this::flushPending);
    for (Runnable callback : callbacks) main.post(callback);
  }

  private static final class Record {
    final String key;
    Entry entry;
    boolean loaded, loading;
    Record redirect;
    Entry pending;
    double pendingCeiling;
    long pendingFirstAt;

    Record(String key) {
      this.key = key;
    }

    Record resolve() {
      Record result = this;
      while (result.redirect != null) result = result.redirect;
      if (redirect != null) redirect = result;
      return result;
    }
  }

  private static final class RecordReference extends WeakReference<Record> {
    final String key;

    RecordReference(Record record, ReferenceQueue<Record> queue) {
      super(record, queue);
      key = record.key;
    }
  }

  private Record rawRecord(String key) {
    RecordReference expired;
    while ((expired = (RecordReference) collectedRecords.poll()) != null)
      if (retained.get(expired.key) == expired) retained.remove(expired.key);
    RecordReference reference = retained.get(key);
    Record record = reference == null ? null : reference.get();
    if (record == null) {
      record = new Record(key);
      if (dirty.containsKey(key)) {
        record.loaded = true;
        record.entry = dirty.get(key);
      }
      retained.put(key, new RecordReference(record, collectedRecords));
    }
    return record;
  }

  private Record recordFor(String key) {
    Record record = rawRecord(key).resolve();
    if (!record.loaded && completeInitialIndex && ready) publishLoaded(record, null);
    cache(record);
    return record;
  }

  private Entry knownEntry(String key) {
    return recordFor(key).entry;
  }

  private void cache(Record record) {
    counts.put(record.key, record);
    while (counts.size() > maxRecords) {
      counts.remove(counts.keySet().iterator().next());
      completeInitialIndex = false;
    }
  }

  private void put(Entry entry) {
    Record record = rawRecord(entry.key).resolve();
    record.loaded = true;
    record.entry = entry;
    cache(record);
  }

  private void requestHydration(Record record) {
    record = record.resolve();
    if (record.loaded || record.loading) return;
    record.loading = true;
    Record target = record;
    io.execute(
        () -> {
          boolean success = hydrate(java.util.Collections.singletonList(target));
          synchronized (lock) {
            target.loading = false;
          }
          if (success) flushPending();
        });
  }

  /**
   * Runs only on the serial I/O worker. Missing rows are known empty only after a successful read.
   */
  private boolean hydrate(List<Record> requested) {
    LinkedHashMap<String, Record> missing = new LinkedHashMap<>();
    synchronized (lock) {
      for (Record item : requested) {
        Record record = item.resolve();
        if (!record.loaded) missing.put(record.key, record);
      }
    }
    if (missing.isEmpty()) return true;
    Map<String, Entry> loaded = new HashMap<>();
    try {
      SQLiteDatabase db = openDatabase();
      ArrayList<String> keys = new ArrayList<>(missing.keySet());
      for (int offset = 0; offset < keys.size(); offset += 256) {
        List<String> part = keys.subList(offset, Math.min(keys.size(), offset + 256));
        String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
        try (Cursor cursor =
            db.query(
                "plays",
                COLUMNS,
                "media_key IN (" + marks + ")",
                part.toArray(new String[0]),
                null,
                null,
                null)) {
          while (cursor.moveToNext()) {
            Entry entry = readEntry(cursor);
            loaded.put(entry.key, entry);
          }
        }
      }
    } catch (RuntimeException failure) {
      Log.w("PlaybackStatsStore", "Playback history could not be read");
      return false;
    }
    synchronized (lock) {
      diskLoaded = true;
      for (Record record : missing.values())
        if (!record.loaded) publishLoaded(record, loaded.get(record.key));
    }
    return true;
  }

  private void publishLoaded(Record record, Entry entry) {
    record.loaded = true;
    record.entry = dirty.containsKey(record.key) ? dirty.get(record.key) : entry;
    if (record.entry != null) {
      lastRecordedAt = Math.max(lastRecordedAt, record.entry.lastPlayed);
      correctFutureAnchor(record.entry, now());
    }
    cache(record);
    Entry pending = record.pending;
    record.pending = null;
    pendingRecords.remove(record.key);
    if (pending != null) {
      long at = now();
      Entry saved = record.entry;
      if (saved != null
          && (saved.scoreUpdated > record.pendingFirstAt
              || saved.preferenceUpdated > record.pendingFirstAt))
        saved =
            new Entry(
                saved.key,
                saved.count,
                saved.lastPlayed,
                saved.score,
                Math.min(saved.scoreUpdated, record.pendingFirstAt),
                saved.exposure,
                saved.likes,
                saved.dislikes,
                Math.min(saved.preferenceUpdated, record.pendingFirstAt),
                saved.massExponent);
      double ceiling = RecentPlaybackPolicy.decay(record.pendingCeiling, pending.scoreUpdated, at);
      long order = Math.max(at, add(lastRecordedAt, pending.count));
      lastRecordedAt = order;
      Entry merged =
          new Entry(
              record.key,
              saved == null ? pending.count : add(saved.count, pending.count),
              order,
              Math.min(ceiling, mergedScore(saved, pending, at)),
              at,
              massAt(saved, at).merge(massAt(pending, at)),
              at);
      put(merged);
      dirty.put(merged.key, merged);
    }
  }

  private void flushPending() {
    ArrayList<Record> pending;
    synchronized (lock) {
      pending = new ArrayList<>(pendingRecords.values());
    }
    if (!hydrate(pending)) return;
    ArrayList<String[]> queued;
    Map<String, Record> migrationRecords = new HashMap<>();
    synchronized (lock) {
      queued = new ArrayList<>(migrations);
    }
    for (String[] migration : queued) {
      Record before, after;
      synchronized (lock) {
        before = recordFor(migration[0]);
        after = recordFor(migration[1]);
        migrationRecords.put(migration[0], before);
        migrationRecords.put(migration[1], after);
      }
      if (!hydrate(java.util.Arrays.asList(before, after))) return;
    }
    Map<String, Entry> writes = new HashMap<>();
    synchronized (lock) {
      long now = now();
      for (String[] migration : queued) {
        Record beforeRecord = migrationRecords.get(migration[0]).resolve();
        Record afterRecord = migrationRecords.get(migration[1]).resolve();
        if (beforeRecord == afterRecord) {
          afterRecord = new Record(migration[1]);
          afterRecord.loaded = true;
          retained.put(migration[1], new RecordReference(afterRecord, collectedRecords));
        }
        Entry before = correctFutureAnchor(beforeRecord.entry, now);
        if (before == null) {
          // A neutral, session-only cancellation has no aggregate to migrate. Move only its
          // token, retaining the existing behavior of absent database rows and prepared weights.
          boolean movedCancellation = false;
          for (VoteContribution contribution : activeContributions.keySet()) {
            if (contribution.vote == 0
                && contribution.key.equals(migration[0])
                && isCurrent(contribution)) {
              contribution.key = migration[1];
              contribution.record = afterRecord;
              movedCancellation = true;
            }
          }
          if (movedCancellation) identityGeneration = new Object();
          continue;
        }
        counts.remove(migration[0]);
        Entry after = correctFutureAnchor(afterRecord.entry, now);
        FeedbackMass merged = massAt(before, now).merge(massAt(after, now));
        for (VoteContribution contribution : activeContributions.keySet()) {
          if (!contribution.key.equals(migration[0]) && !contribution.key.equals(migration[1]))
            continue;
          contribution.amount = contributionAt(contribution, merged.exponent, now);
          contribution.exponent = merged.exponent;
          contribution.updated = now;
          contribution.key = migration[1];
          contribution.record = afterRecord;
        }
        put(
            new Entry(
                migration[1],
                after == null ? before.count : add(before.count, after.count),
                after == null ? before.lastPlayed : Math.max(before.lastPlayed, after.lastPlayed),
                mergedScore(before, after, now),
                now,
                merged,
                now));
        beforeRecord.redirect = afterRecord;
        beforeRecord.entry = null;
        identityGeneration = new Object();
        dirty.put(migration[0], null);
        dirty.put(migration[1], afterRecord.entry);
      }
      migrations.removeAll(queued);
      writes.putAll(dirty);
    }
    if (writes.isEmpty()) return;
    boolean capacityMode = false;
    Set<String> evicted = new HashSet<>();
    try {
      SQLiteDatabase db = openDatabase();
      for (int attempt = 0; ; attempt++) {
        try {
          writeTransaction(db, writes, evicted);
          break;
        } catch (SQLiteFullException full) {
          // A genuinely full filesystem must not be treated as permission to discard history.
          long freeBytes = new java.io.File(db.getPath()).getParentFile().getUsableSpace();
          long remainingPages = maximumPages - scalar(db, "PRAGMA page_count");
          if (attempt >= 16
              || freeBytes < storageBytes * 2
              || remainingPages > 16 && writes.size() * 320L < remainingPages * pageSize)
            throw full;
          if (!capacityMode) {
            beginCapacity();
            capacityMode = true;
          }
          List<String> more = planEviction(db, writes, evicted, false);
          if (more.isEmpty()) throw full;
          evicted.addAll(more);
        }
      }
      if (!evicted.isEmpty()) {
        publishEvictions(writes, evicted);
        publishCapacityNotice(db);
      }
      synchronized (lock) {
        for (Map.Entry<String, Entry> write : writes.entrySet()) {
          if (dirty.containsKey(write.getKey()) && dirty.get(write.getKey()) == write.getValue())
            dirty.remove(write.getKey());
        }
      }
    } catch (RuntimeException failure) {
      Log.w("PlaybackStatsStore", "Playback counts could not be saved");
    } finally {
      if (capacityMode) endCapacity();
    }
  }

  private void writeTransaction(SQLiteDatabase db, Map<String, Entry> writes, Set<String> evicted) {
    db.beginTransaction();
    RuntimeException original = null;
    try {
      deleteKeys(db, new ArrayList<>(evicted));
      for (Map.Entry<String, Entry> write : writes.entrySet()) {
        if (evicted.contains(write.getKey())) continue;
        Entry entry = write.getValue();
        if (entry == null) db.delete("plays", "media_key=?", new String[] {write.getKey()});
        else
          db.execSQL(
              "INSERT OR REPLACE INTO plays(media_key,play_count,last_played,recent_score,"
                  + "score_updated,exposure,likes,dislikes,preference_updated,mass_exponent)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?)",
              new Object[] {
                entry.key,
                entry.count,
                entry.lastPlayed,
                entry.score,
                entry.scoreUpdated,
                entry.exposure,
                entry.likes,
                entry.dislikes,
                entry.preferenceUpdated,
                entry.massExponent
              });
      }
      if (!evicted.isEmpty())
        db.execSQL("UPDATE storage_state SET notice=1 WHERE id=1 AND notice=0");
      db.setTransactionSuccessful();
    } catch (RuntimeException failure) {
      original = failure;
      throw failure;
    } finally {
      try {
        db.endTransaction();
      } catch (RuntimeException ending) {
        // SQLITE_FULL may have already rolled back the native transaction. Preserve its cause.
        if (original == null) throw ending;
      }
    }
  }

  private static long scalar(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      if (!cursor.moveToFirst()) throw new IllegalStateException("Missing storage metadata");
      return cursor.getLong(0);
    }
  }

  /**
   * Called only on the serial worker. Existing oversized files are repaired before exposing rows.
   */
  private SQLiteDatabase openDatabase() {
    SQLiteDatabase db = database.getWritableDatabase();
    if (budgetInitialized) return db;
    pageSize = scalar(db, "PRAGMA page_size");
    // DELETE rollback journals store at most one old copy of each page, plus eight bytes per
    // page. cache_spill=OFF prevents extra journal headers; reserve 64KiB for alignment/header.
    maximumPages = (storageBytes - JOURNAL_HEADROOM) / (2 * pageSize + 8);
    if (maximumPages < 8) throw new IllegalStateException("Unsupported database page size");
    if (scalar(db, "PRAGMA page_count") > maximumPages) {
      // Compact existing free space first, so an upgrade does not evict history unnecessarily.
      db.execSQL("VACUUM");
      Map<String, Entry> none = new HashMap<>();
      while (scalar(db, "PRAGMA page_count") - scalar(db, "PRAGMA freelist_count")
          > maximumPages - 2) {
        if (!reclaimOldest(db, none, true))
          throw new IllegalStateException("Database cannot fit its storage budget");
      }
      if (scalar(db, "PRAGMA page_count") > maximumPages) db.execSQL("VACUUM");
    }
    long requested = maximumPages * pageSize;
    if (db.setMaximumSize(requested) != requested
        || scalar(db, "PRAGMA max_page_count") != maximumPages)
      throw new IllegalStateException("Database storage limit was not applied");
    budgetInitialized = true;
    publishCapacityNotice(db);
    return db;
  }

  /**
   * Capacity pressure is rare. Defer mutations during its transaction without blocking UI reads;
   * only after commit are live records, pending writes and session votes invalidated together.
   */
  private boolean reclaimOldest(
      SQLiteDatabase db, Map<String, Entry> writes, boolean oversizedUpgrade) {
    beginCapacity();
    try {
      List<String> evicted =
          planEviction(db, writes, java.util.Collections.emptySet(), oversizedUpgrade);
      if (evicted.isEmpty()) return false;
      db.beginTransaction();
      try {
        deleteKeys(db, evicted);
        db.execSQL("UPDATE storage_state SET notice=1 WHERE id=1 AND notice=0");
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
      publishEvictions(writes, new HashSet<>(evicted));
      publishCapacityNotice(db);
      return true;
    } finally {
      endCapacity();
    }
  }

  private List<String> planEviction(
      SQLiteDatabase db, Map<String, Entry> writes, Set<String> already, boolean oversizedUpgrade) {
    Map<String, Entry> candidates = new HashMap<>();
    try (Cursor rows = db.query("plays", COLUMNS, null, null, null, null, null)) {
      while (rows.moveToNext()) {
        Entry entry = readEntry(rows);
        candidates.put(entry.key, entry);
      }
    }
    for (Entry entry : writes.values()) if (entry != null) candidates.put(entry.key, entry);
    synchronized (lock) {
      // Rank an accepted newer operation by its actual time, rather than its stale on-disk time.
      for (Map.Entry<String, Entry> change : dirty.entrySet())
        if (change.getValue() != null) candidates.put(change.getKey(), change.getValue());
    }
    for (String key : already) candidates.remove(key);
    ArrayList<Entry> ordered = new ArrayList<>(candidates.values());
    java.util.Collections.sort(ordered);
    if (ordered.size() < 2) return java.util.Collections.emptyList();
    int remove = Math.max(16, ordered.size() / 10);
    if (oversizedUpgrade) {
      long used = scalar(db, "PRAGMA page_count") - scalar(db, "PRAGMA freelist_count");
      remove =
          Math.max(remove, (int) Math.ceil(ordered.size() * (1d - (maximumPages - 2d) / used)));
    } else {
      // Large coalesced batches are trimmed together; actual SQLite page pressure decides retries.
      long estimatedRows = Math.max(1, (maximumPages - 4) * pageSize / 320);
      remove = (int) Math.max(remove, ordered.size() - estimatedRows);
    }
    remove = Math.min(remove, ordered.size() - 1);
    ArrayList<String> keys = new ArrayList<>(remove);
    for (int i = 0; i < remove; i++) keys.add(ordered.get(i).key);
    return keys;
  }

  private void deleteKeys(SQLiteDatabase db, List<String> keys) {
    for (int offset = 0; offset < keys.size(); offset += 256) {
      List<String> part = keys.subList(offset, Math.min(keys.size(), offset + 256));
      String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
      db.delete("plays", "media_key IN (" + marks + ")", part.toArray(new String[0]));
    }
  }

  private void publishEvictions(Map<String, Entry> writes, Set<String> keys) {
    synchronized (lock) {
      for (String key : keys) {
        writes.remove(key);
        dirty.remove(key);
        RecordReference reference = retained.get(key);
        Record record = reference == null ? null : reference.get();
        if (record != null && record.redirect == null) record.entry = null;
      }
      for (VoteContribution contribution : new ArrayList<>(activeContributions.keySet())) {
        if (keys.contains(contribution.key)) {
          contribution.valid = false;
          activeContributions.remove(contribution);
        }
      }
      identityGeneration = new Object();
    }
  }

  private void beginCapacity() {
    synchronized (lock) {
      capacityPending = true;
    }
  }

  private void endCapacity() {
    synchronized (lock) {
      capacityPending = false;
      ArrayList<Runnable> deferred = new ArrayList<>(afterCapacity);
      afterCapacity.clear();
      for (Runnable action : deferred) action.run();
    }
  }

  private static long add(long left, long right) {
    return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private long now() {
    return Math.max(0, clock.getAsLong());
  }

  /** Re-anchor live contribution tokens with their aggregate after a wall-clock rollback. */
  private Entry correctFutureAnchor(Entry entry, long now) {
    if (entry == null
        || resetPending
        || capacityPending
        || entry.scoreUpdated <= now && entry.preferenceUpdated <= now) return entry;
    if (entry.preferenceUpdated > now) {
      for (VoteContribution contribution : activeContributions.keySet()) {
        if (contribution.valid && contribution.key.equals(entry.key)) {
          contribution.amount =
              FeedbackMass.convert(
                  contribution.amount,
                  contribution.exponent,
                  contribution.updated,
                  entry.massExponent,
                  entry.preferenceUpdated);
          contribution.exponent = entry.massExponent;
          contribution.updated = now;
        }
      }
    }
    Entry repaired =
        new Entry(
            entry.key,
            entry.count,
            entry.lastPlayed,
            entry.score,
            Math.min(entry.scoreUpdated, now),
            entry.exposure,
            entry.likes,
            entry.dislikes,
            Math.min(entry.preferenceUpdated, now),
            entry.massExponent);
    put(repaired);
    dirty.put(entry.key, repaired);
    return repaired;
  }

  private static FeedbackMass massAt(Entry entry, long now) {
    return entry == null
        ? FeedbackMass.of(0, 0, 0, 0)
        : FeedbackMass.of(entry.exposure, entry.likes, entry.dislikes, entry.massExponent)
            .decay(entry.preferenceUpdated, now);
  }

  private static double contributionAt(VoteContribution contribution, int exponent, long now) {
    return FeedbackMass.convert(
        contribution.amount, contribution.exponent, contribution.updated, exponent, now);
  }

  private static double weightAt(Entry entry, long now) {
    return FeedbackWeightPolicy.weight(
        RecentPlaybackPolicy.decay(entry.score, entry.scoreUpdated, now), massAt(entry, now));
  }

  private static double mergedScore(Entry left, Entry right, long now) {
    double a = left == null ? 0 : RecentPlaybackPolicy.decay(left.score, left.scoreUpdated, now);
    double b = right == null ? 0 : RecentPlaybackPolicy.decay(right.score, right.scoreUpdated, now);
    return RecentPlaybackPolicy.sanitizeScore(a + b);
  }

  static String key(VideoItem item) {
    String identity = !item.id.isEmpty() ? item.id : !item.uri.isEmpty() ? item.uri : item.path;
    // ACTION_VIEW may identify the same explicit MediaStore row by URI rather than library ID.
    // Keep the volume component exact; the aggregate "external" alias must not merge volumes.
    if (identity.equals(item.uri)) {
      Uri uri = Uri.parse(item.uri);
      List<String> segments = uri.getPathSegments();
      if ("content".equals(uri.getScheme())
          && "media".equals(uri.getAuthority())
          && segments.size() == 4
          && "video".equals(segments.get(1))
          && "media".equals(segments.get(2))
          && segments.get(3).matches("[0-9]+"))
        identity = "media:" + segments.get(0) + ":" + segments.get(3);
    }
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      char[] hex = new char[bytes.length * 2];
      char[] alphabet = "0123456789abcdef".toCharArray();
      for (int i = 0; i < bytes.length; i++) {
        hex[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
        hex[i * 2 + 1] = alphabet[bytes[i] & 15];
      }
      return new String(hex);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  void awaitIdle() throws Exception {
    io.submit(() -> {}).get(20, TimeUnit.SECONDS);
    // Loading can enqueue one repair flush behind the first barrier.
    io.submit(() -> {}).get(20, TimeUnit.SECONDS);
  }

  void close() throws Exception {
    io.submit(database::close).get(20, TimeUnit.SECONDS);
    io.shutdown();
  }

  private static final class Entry implements Comparable<Entry> {
    final String key;
    final long count, lastPlayed;
    final double score;
    final long scoreUpdated;
    final double exposure, likes, dislikes;
    final long preferenceUpdated;
    final int massExponent;

    Entry(
        String key,
        long count,
        long lastPlayed,
        double score,
        long scoreUpdated,
        double exposure,
        double likes,
        double dislikes,
        long preferenceUpdated) {
      this(
          key,
          count,
          lastPlayed,
          score,
          scoreUpdated,
          exposure,
          likes,
          dislikes,
          preferenceUpdated,
          0);
    }

    Entry(
        String key,
        long count,
        long lastPlayed,
        double score,
        long scoreUpdated,
        double exposure,
        double likes,
        double dislikes,
        long preferenceUpdated,
        int massExponent) {
      this(
          key,
          count,
          lastPlayed,
          score,
          scoreUpdated,
          FeedbackMass.of(exposure, likes, dislikes, massExponent),
          preferenceUpdated);
    }

    Entry(
        String key,
        long count,
        long lastPlayed,
        double score,
        long scoreUpdated,
        FeedbackMass mass,
        long preferenceUpdated) {
      this.key = key;
      this.count = Math.max(0, count);
      this.lastPlayed = Math.max(0, lastPlayed);
      this.score = RecentPlaybackPolicy.sanitizeScore(score);
      this.scoreUpdated = Math.max(0, scoreUpdated);
      exposure = mass.exposure;
      likes = mass.likes;
      dislikes = mass.dislikes;
      massExponent = mass.exponent;
      this.preferenceUpdated = Math.max(0, preferenceUpdated);
    }

    @Override
    public int compareTo(Entry other) {
      int time = Long.compare(lastPlayed, other.lastPlayed);
      return time != 0 ? time : key.compareTo(other.key);
    }
  }

  private static final class Database extends SQLiteOpenHelper {
    private final LongSupplier clock;

    Database(Context context, String name, LongSupplier clock) {
      super(context, name, null, 6);
      this.clock = clock;
      setWriteAheadLoggingEnabled(false);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
      db.disableWriteAheadLogging();
      try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
        if (!mode.moveToFirst() || !"delete".equalsIgnoreCase(mode.getString(0)))
          throw new IllegalStateException("Bounded rollback journal required");
      }
      db.execSQL("PRAGMA cache_spill=OFF");
      db.execSQL("PRAGMA temp_store=MEMORY");
      scalar(db, "PRAGMA journal_size_limit=0");
    }

    private void createStorageState(SQLiteDatabase db) {
      db.execSQL(
          "CREATE TABLE storage_state(id INTEGER PRIMARY KEY CHECK(id=1),"
              + "notice INTEGER NOT NULL DEFAULT 0 CHECK(notice BETWEEN 0 AND 2))");
      db.execSQL("INSERT INTO storage_state(id,notice) VALUES(1,0)");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      db.execSQL(
          "CREATE TABLE plays (media_key TEXT PRIMARY KEY NOT NULL, play_count INTEGER NOT NULL"
              + " CHECK(play_count>=0), last_played INTEGER NOT NULL, recent_score REAL NOT NULL"
              + " DEFAULT 0 CHECK(recent_score>=0 AND recent_score<=63), score_updated INTEGER NOT"
              + " NULL DEFAULT 0, exposure REAL NOT NULL DEFAULT 0 CHECK(exposure>=0), likes REAL"
              + " NOT NULL DEFAULT 0 CHECK(likes>=0), dislikes REAL NOT NULL DEFAULT 0"
              + " CHECK(dislikes>=0), preference_updated INTEGER NOT NULL DEFAULT 0, mass_exponent"
              + " INTEGER NOT NULL DEFAULT 0 CHECK(mass_exponent>=0 AND mass_exponent<=1022))"
              + " WITHOUT ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played, media_key)");
      createStorageState(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      if (oldVersion < 1 || oldVersion > 5 || newVersion != 6)
        throw new IllegalStateException("Unsupported playback statistics schema upgrade");
      if (oldVersion == 5) {
        createStorageState(db);
        return;
      }
      // SQLiteOpenHelper executes this upgrade in a transaction. No history is dropped if it fails.
      if (oldVersion == 1) {
        db.execSQL(
            "ALTER TABLE plays ADD COLUMN recent_score REAL NOT NULL DEFAULT 0"
                + " CHECK(recent_score>=0 AND recent_score<=63)");
        db.execSQL("ALTER TABLE plays ADD COLUMN score_updated INTEGER NOT NULL DEFAULT 0");
        long now = Math.max(0, clock.getAsLong());
        try (Cursor rows = db.rawQuery("SELECT media_key,play_count,last_played FROM plays", null);
            android.database.sqlite.SQLiteStatement update =
                db.compileStatement(
                    "UPDATE plays SET recent_score=?,score_updated=? WHERE media_key=?")) {
          while (rows.moveToNext()) {
            // Version 1 had no event timestamps. This bounded last-play estimate is migrated once;
            // cumulative counts remain intact, but never permanently determine future weights.
            update.bindDouble(
                1, RecentPlaybackPolicy.migrateLegacy(rows.getLong(1), rows.getLong(2), now));
            update.bindLong(2, now);
            update.bindString(3, rows.getString(0));
            update.executeUpdateDelete();
          }
        }
      }
      // v2 had no exposure history. Its existing recent score is a conservative initial
      // exposure estimate. Any unpublished v3 enum is intentionally discarded, not a vote.
      db.execSQL("ALTER TABLE plays RENAME TO plays_previous_weights");
      db.execSQL("DROP INDEX IF EXISTS plays_recent");
      onCreate(db);
      db.execSQL(
          "INSERT INTO plays(media_key,play_count,last_played,recent_score,score_updated,"
              + "exposure,likes,dislikes,preference_updated,mass_exponent) "
              + "SELECT media_key,play_count,last_played,recent_score,score_updated,"
              + (oldVersion >= 4
                  ? "exposure,likes,dislikes,preference_updated,0"
                  : "MAX(0,recent_score),0,0,score_updated,0")
              + " FROM plays_previous_weights");
      db.execSQL("DROP TABLE plays_previous_weights");
    }
  }
}
