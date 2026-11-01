package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real SQLite byte-budget checks in disposable databases; no media or user history is opened. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackStatsBudgetTest {
  private static final long START = 1_700_000_000_000L;
  private static final long SMALL_BUDGET = 262_144;
  private Context context;
  private String databaseName;
  private final AtomicLong clock = new AtomicLong(START);
  private final ArrayList<PlaybackStatsStore> stores = new ArrayList<>();
  private final ArrayList<ExecutorService> workers = new ArrayList<>();
  private final Map<PlaybackStatsStore, ExecutorService> workerByStore = new IdentityHashMap<>();
  private final ArrayList<CountDownLatch> gates = new ArrayList<>();

  @Before
  public void isolateDatabase() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    databaseName = "playback-budget-instrumentation-" + UUID.randomUUID() + ".db";
    assertFalse(context.getDatabasePath(databaseName).exists());
  }

  @After
  public void cleanup() throws Exception {
    for (CountDownLatch gate : gates) gate.countDown();
    Exception first = null;
    for (PlaybackStatsStore store : new ArrayList<>(stores)) {
      try {
        close(store);
      } catch (Exception failure) {
        if (first == null) first = failure;
      }
    }
    for (ExecutorService worker : workers) worker.shutdownNow();
    if (context != null && databaseName != null) {
      assertTrue(databaseName.startsWith("playback-budget-instrumentation-"));
      context.deleteDatabase(databaseName);
      assertFalse(context.getDatabasePath(databaseName).exists());
      assertEquals("Test-owned sidecars must not survive cleanup", 0, storageBytes());
    }
    if (first != null) throw first;
  }

  @Test
  public void smallMemoryCacheDoesNotEvictHistoryThatFitsTheByteBudget() throws Exception {
    seedVersionFive(80);
    PlaybackStatsStore store = open(2, SMALL_BUDGET);
    store.awaitIdle();
    assertEquals(80, rowCount());
    assertFalse(consumeNotice(store));
    List<VideoItem> queue = Arrays.asList(item("seed-0"), item("seed-79"), item("seed-0"));
    PlaybackStatsStore.PreparedWeights weights = prepare(store, queue);
    assertTrue(weights.weight(0) > 0 && weights.weight(0) < 1);
    assertEquals(weights.weight(0), weights.weight(2), 0);
    assertTrue(store.cachedRecordCount() <= 2);
    assertEquals(1, persistedCount(queue.get(0)));
    assertEquals(80, persistedCount(queue.get(1)));
    assertBudget(SMALL_BUDGET);
    close(store);
    PlaybackStatsStore restarted = open(2, SMALL_BUDGET);
    restarted.awaitIdle();
    assertEquals(80, rowCount());
    assertFalse(consumeNotice(restarted));
    assertEquals(weights.weight(0), prepare(restarted, queue).weight(0), 0);
    assertBudget(SMALL_BUDGET);
  }

  @Test
  public void oversizedLegacyDatabaseKeepsNewestHistoryWithinTheRealFiveMegabyteBudget()
      throws Exception {
    assertEquals(5_000_000L, PlaybackStatsStore.MAX_STORAGE_BYTES);
    int seeded = 40_000;
    seedVersionFive(seeded);
    assertTrue("Fixture must start above the shipping limit", storageBytes() > 5_000_000L);
    PlaybackStatsStore store = open(8, PlaybackStatsStore.MAX_STORAGE_BYTES);
    store.awaitIdle();
    long retained = rowCount();
    assertTrue("Migration retains useful recent history", retained > 0 && retained < seeded);
    assertEquals(0, rowsFor(item("seed-0")));
    assertEquals(seeded, persistedCount(item("seed-" + (seeded - 1))));
    assertBudget(PlaybackStatsStore.MAX_STORAGE_BYTES);
    // Pending notice survives a process/window replacement until a foreground consumer sees it.
    close(store);
    PlaybackStatsStore restarted = open(8, PlaybackStatsStore.MAX_STORAGE_BYTES);
    restarted.awaitIdle();
    assertEquals(retained, rowCount());
    assertTrue(consumeNotice(restarted));
    assertFalse(consumeNotice(restarted));
    restarted.awaitIdle();
    close(restarted);
    PlaybackStatsStore acknowledged = open(8, PlaybackStatsStore.MAX_STORAGE_BYTES);
    acknowledged.awaitIdle();
    assertFalse(
        "Acknowledged capacity notice stays acknowledged across restart",
        consumeNotice(acknowledged));
    assertEquals(seeded, persistedCount(item("seed-" + (seeded - 1))));
    assertBudget(PlaybackStatsStore.MAX_STORAGE_BYTES);
  }

  @Test
  public void continuedWritesReuseStorageAndKeepNewestRowsWhileSidecarsStayBounded()
      throws Exception {
    PlaybackStatsStore store = open(3, SMALL_BUDGET);
    store.awaitIdle();
    assertFalse(consumeNotice(store));
    int total = 0;
    boolean covered = false;
    try (StorageProbe probe = new StorageProbe()) {
      for (int batch = 0; batch < 40 && (!covered || batch < 20); batch++) {
        writeBatch(store, "rolling-", total, 64);
        total += 64;
        assertBudget(SMALL_BUDGET);
        covered |= rowCount() < total;
      }
      assertTrue("The fixture must actually exercise replacement", covered);
      assertEquals(0, rowsFor(item("rolling-0")));
      for (int i = total - 16; i < total; i++)
        assertEquals(1, persistedCount(item("rolling-" + i)));
      assertTrue("Only the first capacity event becomes a pending reminder", consumeNotice(store));
      store.awaitIdle();
      writeBatch(store, "rolling-", total, 128);
      assertFalse(
          "A full database must not generate a toast for every new row", consumeNotice(store));
      assertBudget(SMALL_BUDGET);
      probe.sample();
      assertTrue(
          "Database plus transaction sidecars exceeded its byte limit: " + probe.peak.get(),
          probe.peak.get() <= SMALL_BUDGET);
    }
  }

  @Test
  public void evictionInvalidatesLiveVotesAndPreparedWeightsWithoutResurrectingOldTotals()
      throws Exception {
    PlaybackStatsStore store = open(2, SMALL_BUDGET);
    store.awaitIdle();
    VideoItem victim = item("live-victim");
    for (int i = 0; i < 5; i++) store.recordPlay(victim);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(victim, 1));
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights weights = prepare(store, Arrays.asList(victim, victim));
    assertEquals(1, session.vote(victim));
    assertTrue(weights.weight(0) < 1);
    int total = 0;
    while (rowsFor(victim) != 0 && total < 4_096) {
      writeBatch(store, "evict-", total, 128);
      total += 128;
    }
    assertEquals("The oldest voted fixture must be replaced", 0, rowsFor(victim));
    assertEquals("A removed contribution cannot remain selected", 0, session.vote(victim));
    assertEquals(1, weights.weight(0), 0);
    assertEquals(1, weights.weight(1), 0);
    assertEquals(0, store.playCount(victim));
    assertTrue(session.setVote(victim, 0));
    store.awaitIdle();
    assertEquals("Canceling an evicted vote must not recreate its aggregate", 0, rowsFor(victim));
    VideoItem renamed = item("evicted-renamed");
    store.migrate(victim, renamed);
    store.awaitIdle();
    assertEquals(0, rowsFor(victim));
    assertEquals(0, rowsFor(renamed));
    store.recordPlay(victim);
    store.awaitIdle();
    assertEquals(
        "A new watch starts a new aggregate, without historic votes or counts",
        1,
        persistedCount(victim));
    assertEquals(1 / Math.sqrt(2), weights.weight(0), 1e-12);
    assertEquals(weights.weight(0), weights.weight(1), 0);
    assertBudget(SMALL_BUDGET);
  }

  @Test
  public void rejectedWritePreservesCommittedRowsAndRetriesItsPendingCountExactlyOnce()
      throws Exception {
    PlaybackStatsStore store = open(3, SMALL_BUDGET);
    store.awaitIdle();
    VideoItem rejected = item("rejected-new-row");
    String key = PlaybackStatsStore.key(rejected);
    assertTrue(key.matches("[a-f0-9]{64}"));
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "CREATE TRIGGER budget_test_reject BEFORE INSERT ON plays WHEN NEW.media_key='"
              + key
              + "' BEGIN SELECT RAISE(ABORT,'owned-budget-test'); END");
    }
    int inserted = 0;
    do {
      writeBatch(store, "committed-", inserted, 128);
      inserted += 128;
    } while (rowCount() == inserted && inserted < 4_096);
    assertTrue(rowCount() < inserted);
    assertTrue(consumeNotice(store));
    store.awaitIdle();
    List<String> before = savedRows();
    store.recordPlay(rejected);
    store.awaitIdle();
    assertEquals("Failed insert cannot commit any attempted eviction", before, savedRows());
    assertEquals(0, rowsFor(rejected));
    assertFalse(consumeNotice(store));
    try (SQLiteDatabase db = database()) {
      db.execSQL("DROP TRIGGER budget_test_reject");
    }
    store.recordPlay(rejected);
    store.awaitIdle();
    assertEquals(
        "The failed view and retry are both preserved without duplicate replay",
        2,
        persistedCount(rejected));
    assertBudget(SMALL_BUDGET);
  }

  @Test
  public void failedInsertAfterCapacityDeletionRollsBackOldRowsAndNoticeTogether()
      throws Exception {
    PlaybackStatsStore store = open(3, SMALL_BUDGET);
    store.awaitIdle();
    SQLiteDatabase owned = configuredDatabase(store);
    // This flag is changed inside the same SQLite transaction as an actual old-row deletion.
    // The rejection cannot fire on the first ordinary INSERT attempt, before capacity cleanup.
    owned.execSQL(
        "CREATE TABLE budget_test_flag(id INTEGER PRIMARY KEY,deleted INTEGER NOT NULL,target_key"
            + " TEXT NOT NULL)");
    owned.execSQL(
        "INSERT INTO budget_test_flag VALUES(1,0,?)",
        new Object[] {PlaybackStatsStore.key(item("unselected"))});
    owned.execSQL(
        "CREATE TRIGGER budget_test_deleted AFTER DELETE ON plays BEGIN "
            + "UPDATE budget_test_flag SET deleted=1 WHERE id=1; END");
    owned.execSQL(
        "CREATE TRIGGER budget_test_reject_after_cleanup BEFORE INSERT ON plays "
            + "WHEN NEW.media_key=(SELECT target_key FROM budget_test_flag WHERE id=1)"
            + " AND (SELECT deleted FROM budget_test_flag WHERE id=1)=1 "
            + "BEGIN SELECT RAISE(ABORT,'owned-budget-after-cleanup'); END");
    // Fill this owned connection to its actual applied page cap, without predicting a row limit
    // or depending on SQLite's page packing. Individual committed rows survive the final FULL.
    int filled = 0;
    boolean full = false;
    VideoItem newest = null;
    try (SQLiteStatement insert =
        owned.compileStatement("INSERT INTO plays VALUES(?,?,?,?,?,?,?,?,?,?)")) {
      for (int i = 0; i < 4_096; i++) {
        VideoItem candidate = item("atomic-row-" + i);
        insert.bindString(1, PlaybackStatsStore.key(candidate));
        insert.bindLong(2, 1);
        insert.bindLong(3, START - 8_192 + i);
        insert.bindDouble(4, 1);
        insert.bindLong(5, START + 1);
        insert.bindDouble(6, 1);
        insert.bindDouble(7, 0);
        insert.bindDouble(8, 0);
        insert.bindLong(9, START + 1);
        insert.bindLong(10, 0);
        try {
          insert.executeInsert();
          filled++;
        } catch (SQLiteFullException expected) {
          full = true;
          newest = candidate;
          break;
        }
      }
    }
    assertTrue("Fixture must reach the real configured page cap", full && filled > 1);
    assertNotNull(newest);
    // Use the very key/row shape whose insert just failed with FULL, rather than guessing which
    // random key could still fit a partially occupied B-tree page. Only its recency becomes new.
    owned.execSQL(
        "UPDATE budget_test_flag SET target_key=? WHERE id=1",
        new Object[] {PlaybackStatsStore.key(newest)});
    assertEquals(filled, rowCount());
    assertFalse(consumeNotice(store));
    assertEquals(0, cleanupFlag());
    List<String> before = savedRows();
    clock.incrementAndGet();
    store.recordPlay(newest);
    store.awaitIdle();
    assertEquals(
        "DELETE and failing INSERT must roll back as one transaction", before, savedRows());
    assertEquals("The deletion trigger's write is rolled back too", 0, cleanupFlag());
    assertEquals(0, rowsFor(newest));
    assertFalse(
        "An unsuccessful replacement must not publish a capacity warning", consumeNotice(store));
    assertBudget(SMALL_BUDGET);
    owned.execSQL("DROP TRIGGER budget_test_reject_after_cleanup");
    // Preparing flushes the retained dirty write, without adding another viewing event.
    prepare(store, Arrays.asList(newest));
    assertEquals("Retry commits the original newest view exactly once", 1, persistedCount(newest));
    assertEquals(1, cleanupFlag());
    assertEquals(0, rowsFor(item("atomic-row-0")));
    assertTrue(consumeNotice(store));
    assertFalse(consumeNotice(store));
    assertBudget(SMALL_BUDGET);
  }

  @Test
  public void capacityListenerRunsOnMainAndResetDoesNotRearmTheSameFullDatabase() throws Exception {
    seedVersionFive(2_000);
    PlaybackStatsStore store = open(3, SMALL_BUDGET);
    CountDownLatch notice = new CountDownLatch(1);
    AtomicBoolean onMain = new AtomicBoolean();
    Runnable listener =
        () -> {
          onMain.set(Looper.myLooper() == Looper.getMainLooper());
          notice.countDown();
        };
    store.addCapacityListener(listener);
    try {
      store.awaitIdle();
      assertTrue(
          "Foreground subscribers receive a pending capacity event",
          notice.await(10, TimeUnit.SECONDS));
      assertTrue(onMain.get());
      assertTrue(consumeNotice(store));
      store.awaitIdle();
      CountDownLatch done = new CountDownLatch(1);
      AtomicBoolean reset = new AtomicBoolean();
      store.resetWeights(
          success -> {
            reset.set(success);
            done.countDown();
          });
      assertTrue(done.await(20, TimeUnit.SECONDS));
      assertTrue(reset.get());
      store.awaitIdle();
      assertFalse(consumeNotice(store));
      assertEquals(2_000, persistedCount(item("seed-1999")));
      assertBudget(SMALL_BUDGET);
    } finally {
      store.removeCapacityListener(listener);
    }
  }

  private PlaybackStatsStore open(int memoryRows, long budget) {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    workers.add(worker);
    PlaybackStatsStore store =
        new PlaybackStatsStore(context, databaseName, memoryRows, worker, clock::get, budget);
    stores.add(store);
    workerByStore.put(store, worker);
    return store;
  }

  private void close(PlaybackStatsStore store) throws Exception {
    store.close();
    stores.remove(store);
    workerByStore.remove(store);
  }

  private boolean consumeNotice(PlaybackStatsStore store) {
    AtomicBoolean result = new AtomicBoolean();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> result.set(store.consumeCapacityNotice()));
    return result.get();
  }

  private PlaybackStatsStore.PreparedWeights prepare(
      PlaybackStatsStore store, List<VideoItem> queue) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<PlaybackStatsStore.PreparedWeights> result = new AtomicReference<>();
    store.prepareWeights(
        queue,
        weights -> {
          result.set(weights);
          done.countDown();
        });
    assertTrue(done.await(20, TimeUnit.SECONDS));
    store.awaitIdle();
    assertNotNull(result.get());
    return result.get();
  }

  private void writeBatch(PlaybackStatsStore store, String prefix, int from, int count)
      throws Exception {
    // Queue one synthetic burst while its owned worker is paused, avoiding thousands of fsyncs.
    ExecutorService worker = workerByStore.get(store);
    assertNotNull(worker);
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    gates.add(release);
    worker.execute(
        () -> {
          started.countDown();
          try {
            if (!release.await(20, TimeUnit.SECONDS))
              throw new AssertionError("Owned test gate timed out");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    try {
      for (int i = from; i < from + count; i++) {
        clock.incrementAndGet();
        store.recordPlay(item(prefix + i));
      }
    } finally {
      release.countDown();
    }
    store.awaitIdle();
  }

  private VideoItem item(String id) {
    return new VideoItem("budget-" + id, "generated.mp4", "", "", 0, 1_000, 0, 0, 640, 360);
  }

  private SQLiteDatabase database() {
    assertTrue(databaseName.startsWith("playback-budget-instrumentation-"));
    return context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null);
  }

  private SQLiteDatabase configuredDatabase(PlaybackStatsStore store) throws Exception {
    assertTrue(databaseName.startsWith("playback-budget-instrumentation-"));
    Field field = PlaybackStatsStore.class.getDeclaredField("database");
    field.setAccessible(true);
    SQLiteDatabase db = ((SQLiteOpenHelper) field.get(store)).getWritableDatabase();
    assertEquals(
        context.getDatabasePath(databaseName).getCanonicalPath(),
        new File(db.getPath()).getCanonicalPath());
    return db;
  }

  private void seedVersionFive(int rows) {
    try (SQLiteDatabase db = database()) {
      try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
        assertTrue(mode.moveToFirst());
        assertEquals("delete", mode.getString(0).toLowerCase(java.util.Locale.ROOT));
      }
      db.execSQL(
          "CREATE TABLE plays(media_key TEXT PRIMARY KEY NOT NULL,play_count INTEGER NOT"
              + " NULL,last_played INTEGER NOT NULL,recent_score REAL NOT NULL DEFAULT"
              + " 0,score_updated INTEGER NOT NULL DEFAULT 0,exposure REAL NOT NULL DEFAULT 0,likes"
              + " REAL NOT NULL DEFAULT 0,dislikes REAL NOT NULL DEFAULT 0,preference_updated"
              + " INTEGER NOT NULL DEFAULT 0,mass_exponent INTEGER NOT NULL DEFAULT 0) WITHOUT"
              + " ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played,media_key)");
      db.setVersion(5);
      db.beginTransaction();
      try (SQLiteStatement insert =
          db.compileStatement("INSERT INTO plays VALUES(?,?,?,?,?,?,?,?,?,?)")) {
        for (int i = 0; i < rows; i++) {
          insert.bindString(1, PlaybackStatsStore.key(item("seed-" + i)));
          insert.bindLong(2, i + 1);
          insert.bindLong(3, START - rows + i);
          insert.bindDouble(4, 2);
          insert.bindLong(5, START);
          insert.bindDouble(6, 7);
          insert.bindDouble(7, 2);
          insert.bindDouble(8, 1);
          insert.bindLong(9, START);
          insert.bindLong(10, 0);
          insert.executeInsert();
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
  }

  private long rowCount() {
    try (SQLiteDatabase db = database();
        Cursor rows = db.rawQuery("SELECT COUNT(*) FROM plays", null)) {
      assertTrue(rows.moveToFirst());
      return rows.getLong(0);
    }
  }

  private long rowsFor(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor rows =
            db.rawQuery(
                "SELECT COUNT(*) FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(rows.moveToFirst());
      return rows.getLong(0);
    }
  }

  private long persistedCount(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor rows =
            db.rawQuery(
                "SELECT play_count FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue("Expected newly retained row: " + item.id, rows.moveToFirst());
      return rows.getLong(0);
    }
  }

  private List<String> savedRows() {
    ArrayList<String> result = new ArrayList<>();
    try (SQLiteDatabase db = database();
        Cursor rows =
            db.rawQuery(
                "SELECT media_key,play_count,last_played FROM plays ORDER BY media_key", null)) {
      while (rows.moveToNext())
        result.add(rows.getString(0) + ":" + rows.getLong(1) + ":" + rows.getLong(2));
    }
    return result;
  }

  private long cleanupFlag() {
    try (SQLiteDatabase db = database();
        Cursor row = db.rawQuery("SELECT deleted FROM budget_test_flag WHERE id=1", null)) {
      assertTrue(row.moveToFirst());
      return row.getLong(0);
    }
  }

  private long storageBytes() {
    File primary = context.getDatabasePath(databaseName);
    long total = primary.length();
    for (String suffix : new String[] {"-wal", "-shm", "-journal"})
      total += new File(primary.getPath() + suffix).length();
    return total;
  }

  private void assertBudget(long budget) {
    assertTrue(
        "Persisted database and sidecars exceed " + budget + " bytes: " + storageBytes(),
        storageBytes() <= budget);
  }

  private final class StorageProbe implements AutoCloseable {
    final AtomicLong peak = new AtomicLong();
    final AtomicBoolean running = new AtomicBoolean(true);
    final Thread sampler;

    StorageProbe() {
      sampler =
          new Thread(
              () -> {
                while (running.get()) {
                  sample();
                  try {
                    Thread.sleep(2);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              },
              "owned-budget-probe");
      sampler.start();
    }

    void sample() {
      long bytes = storageBytes();
      peak.accumulateAndGet(bytes, Math::max);
    }

    @Override
    public void close() throws Exception {
      running.set(false);
      sampler.interrupt();
      sampler.join(5_000);
      assertFalse(sampler.isAlive());
    }
  }
}
