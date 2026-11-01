package io.mediamaster.app.player;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.mediamaster.app.media.VideoItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Private aggregate play counts. UI queries never open the database or read media files. */
public final class PlaybackStatsStore {
  private static final String DATABASE = "playback-stats.db";
  private static final int MAX_RECORDS = 50_000;
  private static volatile PlaybackStatsStore instance;
  private final Object lock = new Object();
  private final Map<String, Entry> counts = new HashMap<>();
  private final TreeSet<Entry> oldest = new TreeSet<>();
  private final ArrayList<Runnable> readyCallbacks = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io;
  private final Set<String> dirty = new HashSet<>();
  private final ArrayList<String[]> migrations = new ArrayList<>();
  private final Database database;
  private final int maxRecords;
  private final LongSupplier clock;
  private boolean ready;
  private boolean diskLoaded;
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
    if (maxRecords < 1) throw new IllegalArgumentException("Positive record limit required");
    this.io = io;
    this.maxRecords = maxRecords;
    this.clock = clock;
    database = new Database(context.getApplicationContext(), databaseName, clock);
    io.execute(this::load);
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
    String key = key(item);
    synchronized (lock) {
      Entry entry = counts.get(key);
      return entry == null ? 0 : entry.count;
    }
  }

  /** Counts in the same order as the supplied queue; no paths or filenames are retained. */
  public long[] snapshot(List<VideoItem> videos) {
    String[] keys = new String[videos.size()];
    for (int i = 0; i < keys.length; i++) keys[i] = key(videos.get(i));
    long[] result = new long[keys.length];
    synchronized (lock) {
      for (int i = 0; i < keys.length; i++) {
        Entry entry = counts.get(keys[i]);
        if (entry != null) result[i] = entry.count;
      }
    }
    return result;
  }

  /**
   * Ordinary time decay is evaluated lazily without a timer or writes. A clock rollback is repaired
   * once so a future timestamp cannot pin the score indefinitely.
   */
  public double recentScore(VideoItem item) {
    String key = key(item);
    long now = now();
    synchronized (lock) {
      Entry entry = counts.get(key);
      Entry corrected = correctFutureAnchor(entry, now);
      if (entry != corrected) io.execute(this::flushPending);
      entry = corrected;
      return entry == null ? 0 : RecentPlaybackPolicy.decay(entry.score, entry.scoreUpdated, now);
    }
  }

  /** Every item in a sampling decision uses the same clock instant. */
  public double[] recentSnapshot(List<VideoItem> videos) {
    String[] keys = new String[videos.size()];
    for (int i = 0; i < keys.length; i++) keys[i] = key(videos.get(i));
    double[] result = new double[keys.length];
    long now = now();
    synchronized (lock) {
      boolean correctedAny = false;
      for (int i = 0; i < keys.length; i++) {
        Entry entry = counts.get(keys[i]);
        Entry corrected = correctFutureAnchor(entry, now);
        correctedAny |= entry != corrected;
        entry = corrected;
        if (entry != null)
          result[i] = RecentPlaybackPolicy.decay(entry.score, entry.scoreUpdated, now);
      }
      if (correctedAny) io.execute(this::flushPending);
    }
    return result;
  }

  /** The caller records one actual new playback entry, not a decoder prepare or a resume. */
  public void recordPlay(VideoItem item) {
    String key = key(item);
    synchronized (lock) {
      Entry previous = counts.get(key);
      long count = previous == null ? 1 : add(previous.count, 1);
      long now = now();
      long recordedAt = Math.max(now, add(lastRecordedAt, 1));
      lastRecordedAt = recordedAt;
      double score =
          previous == null
              ? 1
              : RecentPlaybackPolicy.addPlay(previous.score, previous.scoreUpdated, now);
      // The score anchor is the actual wall clock, independent of the monotonic eviction order.
      // A clock correction must not leave future timestamps pinning the recent-play penalty.
      put(new Entry(key, count, recordedAt, score, now));
      dirty.add(key);
      // Queue while holding the same lock as updates: concurrent calls preserve publication order.
      io.execute(this::flushPending);
    }
  }

  /** Preserve counts after an app-owned rename changes a provider's otherwise path-based ID. */
  public void migrate(VideoItem original, VideoItem replacement) {
    String before = key(original), after = key(replacement);
    if (before.equals(after)) return;
    synchronized (lock) {
      migrations.add(new String[] {before, after});
      io.execute(this::flushPending);
    }
  }

  private void load() {
    Map<String, Entry> loaded = new HashMap<>();
    boolean succeeded = false;
    try {
      SQLiteDatabase db = database.getWritableDatabase();
      pruneDatabase(db);
      try (Cursor cursor =
          db.query(
              "plays",
              new String[] {
                "media_key", "play_count", "last_played", "recent_score", "score_updated"
              },
              null,
              null,
              null,
              null,
              null)) {
        while (cursor.moveToNext()) {
          Entry entry =
              new Entry(
                  cursor.getString(0),
                  cursor.getLong(1),
                  cursor.getLong(2),
                  cursor.getDouble(3),
                  cursor.getLong(4));
          loaded.put(entry.key, entry);
        }
      }
      succeeded = true;
    } catch (RuntimeException failure) {
      // Playback stays available if app storage is full or unavailable; never log media identity.
      Log.w("PlaybackStatsStore", "Playback counts could not be loaded");
      loaded.clear();
    }
    ArrayList<Runnable> callbacks;
    boolean repairedFutureAnchor = false;
    synchronized (lock) {
      // Entries already in memory are new plays that arrived during disk loading, not disk totals.
      long mergedClock = lastRecordedAt;
      long now = now();
      for (Entry saved : loaded.values()) mergedClock = Math.max(mergedClock, saved.lastPlayed);
      for (Entry pending : oldest) {
        // These plays happened after the saved session, including after a wall-clock correction.
        mergedClock = add(mergedClock, 1);
        Entry saved = loaded.get(pending.key);
        loaded.put(
            pending.key,
            new Entry(
                pending.key,
                saved == null ? pending.count : add(saved.count, pending.count),
                mergedClock,
                mergedScore(saved, pending, now),
                now));
      }
      counts.clear();
      oldest.clear();
      for (Entry savedEntry : loaded.values()) {
        Entry entry = savedEntry;
        if (entry.scoreUpdated > now) {
          entry = new Entry(entry.key, entry.count, entry.lastPlayed, entry.score, now);
          dirty.add(entry.key);
          repairedFutureAnchor = true;
        }
        put(entry);
        lastRecordedAt = Math.max(lastRecordedAt, entry.lastPlayed);
      }
      ready = true;
      diskLoaded = succeeded;
      callbacks = new ArrayList<>(readyCallbacks);
      readyCallbacks.clear();
    }
    if (succeeded && repairedFutureAnchor) io.execute(this::flushPending);
    for (Runnable callback : callbacks) main.post(callback);
  }

  private void put(Entry entry) {
    Entry previous = counts.put(entry.key, entry);
    if (previous != null) oldest.remove(previous);
    oldest.add(entry);
    while (counts.size() > maxRecords) {
      Entry expired = oldest.pollFirst();
      if (expired != null) counts.remove(expired.key);
    }
  }

  private void flushPending() {
    // If loading failed, memory contains only session deltas. Never overwrite unknown disk totals.
    // A later play retries loading and merges those deltas before any absolute write is allowed.
    if (!diskLoaded) {
      load();
      if (!diskLoaded) return;
    }
    Map<String, Entry> writes = new HashMap<>();
    synchronized (lock) {
      long now = now();
      for (String[] migration : migrations) {
        Entry before = counts.remove(migration[0]);
        if (before == null) continue;
        oldest.remove(before);
        Entry after = counts.get(migration[1]);
        put(
            new Entry(
                migration[1],
                after == null ? before.count : add(before.count, after.count),
                after == null ? before.lastPlayed : Math.max(before.lastPlayed, after.lastPlayed),
                mergedScore(before, after, now),
                now));
        dirty.add(migration[0]);
        dirty.add(migration[1]);
      }
      migrations.clear();
      for (String key : dirty) writes.put(key, counts.get(key));
    }
    if (writes.isEmpty()) return;
    boolean saved = false;
    try {
      SQLiteDatabase db = database.getWritableDatabase();
      db.beginTransaction();
      try {
        for (Map.Entry<String, Entry> write : writes.entrySet()) {
          Entry entry = write.getValue();
          if (entry == null) db.delete("plays", "media_key=?", new String[] {write.getKey()});
          else {
            // Absolute merged totals make queued duplicate writes and retries idempotent.
            db.execSQL(
                "INSERT OR REPLACE INTO"
                    + " plays(media_key,play_count,last_played,recent_score,score_updated)"
                    + " VALUES(?,?,?,?,?)",
                new Object[] {
                  entry.key, entry.count, entry.lastPlayed, entry.score, entry.scoreUpdated
                });
          }
        }
        pruneDatabase(db);
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
      saved = true;
    } catch (RuntimeException failure) {
      Log.w("PlaybackStatsStore", "Playback counts could not be saved");
    }
    if (saved)
      synchronized (lock) {
        for (Map.Entry<String, Entry> write : writes.entrySet()) {
          if (counts.get(write.getKey()) == write.getValue()) dirty.remove(write.getKey());
        }
      }
  }

  private void pruneDatabase(SQLiteDatabase db) {
    db.execSQL(
        "DELETE FROM plays WHERE media_key IN (SELECT media_key FROM plays "
            + "ORDER BY last_played DESC, media_key DESC LIMIT -1 OFFSET ?)",
        new Object[] {maxRecords});
  }

  private static long add(long left, long right) {
    return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private long now() {
    return Math.max(0, clock.getAsLong());
  }

  /** Only a clock rollback repairs storage. Ordinary passage of time remains read-only. */
  private Entry correctFutureAnchor(Entry entry, long now) {
    if (entry == null || entry.scoreUpdated <= now) return entry;
    Entry repaired = new Entry(entry.key, entry.count, entry.lastPlayed, entry.score, now);
    put(repaired);
    dirty.add(entry.key);
    return repaired;
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

    Entry(String key, long count, long lastPlayed, double score, long scoreUpdated) {
      this.key = key;
      this.count = Math.max(0, count);
      this.lastPlayed = Math.max(0, lastPlayed);
      this.score = RecentPlaybackPolicy.sanitizeScore(score);
      this.scoreUpdated = Math.max(0, scoreUpdated);
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
      super(context, name, null, 2);
      this.clock = clock;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      db.execSQL(
          "CREATE TABLE plays (media_key TEXT PRIMARY KEY NOT NULL, play_count INTEGER NOT NULL"
              + " CHECK(play_count>=0), last_played INTEGER NOT NULL,"
              + " recent_score REAL NOT NULL DEFAULT 0 CHECK(recent_score>=0 AND recent_score<=63),"
              + " score_updated INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played, media_key)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      if (oldVersion != 1 || newVersion != 2)
        throw new IllegalStateException("Unsupported playback statistics schema upgrade");
      // SQLiteOpenHelper executes this upgrade in a transaction. No history is dropped if it fails.
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
  }
}
