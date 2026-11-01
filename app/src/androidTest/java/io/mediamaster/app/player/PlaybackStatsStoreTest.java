package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.media.VideoItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Uses unique caller-owned databases only; never opens the user's playback statistics singleton.
 */
@RunWith(AndroidJUnit4.class)
public final class PlaybackStatsStoreTest {
  private static final long HALF_LIFE = RecentPlaybackPolicy.HALF_LIFE_MS,
      START = 1_700_000_000_000L;
  private Context context;
  private String databaseName;
  private final ArrayList<PlaybackStatsStore> stores = new ArrayList<>();
  private final ArrayList<CountDownLatch> gates = new ArrayList<>();
  private final ArrayList<ExecutorService> executors = new ArrayList<>();

  @Before
  public void isolateDatabase() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    databaseName = "playback-stats-instrumentation-" + UUID.randomUUID() + ".db";
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
    for (ExecutorService executor : executors) executor.shutdownNow();
    if (context != null && databaseName != null) {
      assertTrue(databaseName.startsWith("playback-stats-instrumentation-"));
      context.deleteDatabase(databaseName);
      assertFalse(context.getDatabasePath(databaseName).exists());
    }
    if (first != null) throw first;
  }

  @Test
  public void recordsSurviveRestartAndSnapshotsKeepQueueOrder() throws Exception {
    VideoItem a = item("a"), b = item("b"), c = item("c");
    PlaybackStatsStore store = open(50);
    store.awaitIdle();
    store.recordPlay(a);
    store.recordPlay(b);
    store.recordPlay(a);
    assertArrayEquals(new long[] {1, 2, 0}, store.snapshot(Arrays.asList(b, a, c)));
    store.awaitIdle();
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertArrayEquals(new long[] {2, 1, 0}, restarted.snapshot(Arrays.asList(a, b, c)));
  }

  @Test
  public void playsBeforeInitialLoadMergeExactlyOnceWithSavedCounts() throws Exception {
    VideoItem a = item("a"), b = item("b"), c = item("c");
    seed(a, 7, 100);
    seed(b, 3, 101);
    ExecutorService executor = executor();
    CountDownLatch gate = block(executor);
    PlaybackStatsStore store = open(50, executor);
    assertFalse(store.isReady());
    store.recordPlay(a);
    store.recordPlay(a);
    store.recordPlay(c);
    assertEquals(2, store.playCount(a));
    gate.countDown();
    store.awaitIdle();
    assertArrayEquals(new long[] {9, 3, 1}, store.snapshot(Arrays.asList(a, b, c)));
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertArrayEquals(new long[] {9, 3, 1}, restarted.snapshot(Arrays.asList(a, b, c)));
  }

  @Test
  public void renameMigratesCountsAndRemovesOldIdentifierAcrossRestart() throws Exception {
    VideoItem before = item("file:before"), after = item("file:after");
    PlaybackStatsStore store = open(50);
    store.awaitIdle();
    store.recordPlay(before);
    store.recordPlay(before);
    store.recordPlay(after);
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(0, store.playCount(before));
    assertEquals(3, store.playCount(after));
    assertEquals(0, rowsFor(before));
    assertEquals(1, rowsFor(after));
    // A MediaStore rename keeps the stable identity and must not merge the row into itself.
    store.migrate(after, after.withName("renamed-display-only.mp4"));
    store.awaitIdle();
    assertEquals(3, store.playCount(after));
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertEquals(0, restarted.playCount(before));
    assertEquals(3, restarted.playCount(after));
  }

  @Test
  public void renameBeforeLoadCombinesSavedCountsAndPendingTargetPlay() throws Exception {
    VideoItem before = item("before"), after = item("after");
    seed(before, 4, 100);
    seed(after, 2, 101);
    ExecutorService executor = executor();
    CountDownLatch gate = block(executor);
    PlaybackStatsStore store = open(50, executor);
    store.migrate(before, after);
    store.recordPlay(after);
    gate.countDown();
    store.awaitIdle();
    assertEquals(0, store.playCount(before));
    assertEquals(7, store.playCount(after));
    assertEquals(0, rowsFor(before));
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertEquals(7, restarted.playCount(after));
  }

  @Test
  public void boundedStoreEvictsOldestAndKeepsRecentlyPlayedRecords() throws Exception {
    VideoItem a = item("a"), b = item("b"), c = item("c"), d = item("d");
    PlaybackStatsStore store = open(3);
    store.awaitIdle();
    store.recordPlay(a);
    store.recordPlay(b);
    store.recordPlay(c);
    store.recordPlay(a);
    store.recordPlay(d);
    store.awaitIdle();
    assertArrayEquals(new long[] {2, 0, 1, 1}, store.snapshot(Arrays.asList(a, b, c, d)));
    assertEquals(3, rowCount());
    close(store);
    PlaybackStatsStore restarted = open(3);
    restarted.awaitIdle();
    assertArrayEquals(new long[] {2, 0, 1, 1}, restarted.snapshot(Arrays.asList(a, b, c, d)));
    assertEquals(3, rowCount());
  }

  @Test
  public void diskRowsContainOnlyHashesCountsAndTimes() throws Exception {
    String marker = "stats-private-sentinel-" + UUID.randomUUID();
    VideoItem video =
        new VideoItem(
            "file:" + marker,
            marker + ".mp4",
            "content://fixture.invalid/" + marker,
            "/generated-fixture/" + marker + ".mp4",
            123,
            12000,
            1,
            1,
            640,
            360);
    PlaybackStatsStore store = open(50);
    store.awaitIdle();
    store.recordPlay(video);
    store.awaitIdle();
    close(store);
    try (SQLiteDatabase db = database();
        Cursor columns = db.rawQuery("PRAGMA table_info(plays)", null)) {
      Set<String> names = new HashSet<>();
      while (columns.moveToNext())
        names.add(columns.getString(columns.getColumnIndexOrThrow("name")));
      assertEquals(
          new HashSet<>(
              Arrays.asList(
                  "media_key", "play_count", "last_played", "recent_score", "score_updated")),
          names);
      try (Cursor row = db.rawQuery("SELECT media_key,play_count,last_played FROM plays", null)) {
        assertTrue(row.moveToFirst());
        assertTrue(row.getString(0).matches("[a-f0-9]{64}"));
        assertEquals(PlaybackStatsStore.key(video), row.getString(0));
        assertEquals(1, row.getLong(1));
        assertTrue(row.getLong(2) > 0);
        assertFalse(row.moveToNext());
      }
    }
    String bytes =
        new String(
            Files.readAllBytes(context.getDatabasePath(databaseName).toPath()),
            StandardCharsets.ISO_8859_1);
    assertFalse(
        "Plain media identity must not be written to the owned database", bytes.contains(marker));
  }

  @Test
  public void countsSaturateWithoutOverflowAndRemainPersistent() throws Exception {
    VideoItem video = item("saturated");
    seed(video, Long.MAX_VALUE - 1, 100);
    PlaybackStatsStore store = open(50);
    store.awaitIdle();
    store.recordPlay(video);
    store.recordPlay(video);
    store.awaitIdle();
    assertEquals(Long.MAX_VALUE, store.playCount(video));
    assertTrue(WeightedShuffle.weight(store.playCount(video)) > 0);
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertEquals(Long.MAX_VALUE, restarted.playCount(video));
  }

  @Test
  public void explicitMediaUrisShareOnlyTheirExactVolumeAndRowIdentity() throws Exception {
    String uri = "content://media/external_primary/video/media/42";
    VideoItem library = item("media:external_primary:42");
    VideoItem external = new VideoItem(uri, "generated.mp4", uri, "", 0, 1000, 0, 0, 640, 360);
    String aggregateUri = "content://media/external/video/media/42";
    VideoItem aggregate =
        new VideoItem(aggregateUri, "generated.mp4", aggregateUri, "", 0, 1000, 0, 0, 640, 360);
    VideoItem custom =
        new VideoItem("provider-specific:42", "generated.mp4", uri, "", 0, 1000, 0, 0, 640, 360);
    assertEquals(PlaybackStatsStore.key(library), PlaybackStatsStore.key(external));
    assertNotEquals(PlaybackStatsStore.key(library), PlaybackStatsStore.key(aggregate));
    assertNotEquals(PlaybackStatsStore.key(library), PlaybackStatsStore.key(custom));
    assertNotEquals(
        PlaybackStatsStore.key(library), PlaybackStatsStore.key(item("media:other-volume:42")));
    PlaybackStatsStore store = open(50);
    store.awaitIdle();
    store.recordPlay(external);
    store.awaitIdle();
    assertEquals(1, store.playCount(library));
    assertEquals(0, store.playCount(aggregate));
    assertEquals(0, store.playCount(custom));
  }

  @Test
  public void failedInitialLoadRecoversWithoutOverwritingSavedCounts() throws Exception {
    VideoItem a = item("a"), b = item("b"), c = item("c");
    seed(a, 9, 100);
    seed(b, 4, 101);
    renameTable("plays", "held_plays");
    PlaybackStatsStore store = open(50);
    store.awaitIdle();
    assertTrue("A storage failure must not keep the UI waiting indefinitely", store.isReady());
    store.recordPlay(a);
    store.recordPlay(c);
    store.awaitIdle();
    assertEquals(1, store.playCount(a));
    renameTable("held_plays", "plays");
    store.recordPlay(a);
    store.awaitIdle();
    assertArrayEquals(new long[] {11, 4, 1}, store.snapshot(Arrays.asList(a, b, c)));
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertArrayEquals(new long[] {11, 4, 1}, restarted.snapshot(Arrays.asList(a, b, c)));
  }

  @Test
  public void readyCallbacksUseMainAndMemoryReadsDoNotWaitForDiskQueue() throws Exception {
    ExecutorService executor = executor();
    PlaybackStatsStore store = open(50, executor);
    store.awaitIdle();
    CountDownLatch gate = block(executor);
    VideoItem video = item("pending-write");
    store.recordPlay(video);
    assertEquals(1, store.playCount(video));
    CountDownLatch callback = new CountDownLatch(1);
    AtomicBoolean ranOnMain = new AtomicBoolean();
    store.whenReady(
        () -> {
          ranOnMain.set(Looper.myLooper() == Looper.getMainLooper());
          callback.countDown();
        });
    assertTrue(callback.await(5, TimeUnit.SECONDS));
    assertTrue(ranOnMain.get());
    gate.countDown();
    store.awaitIdle();
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertEquals(1, restarted.playCount(video));
  }

  @Test
  public void recentFrequencyDecaysAcrossRestartWhileReadsLeaveStoredHistoryUntouched()
      throws Exception {
    AtomicLong clock = new AtomicLong(START);
    VideoItem video = item("recent");
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    for (int i = 0; i < 4; i++) store.recordPlay(video);
    store.awaitIdle();
    assertEquals(4, store.recentScore(video), 0);
    clock.addAndGet(HALF_LIFE);
    for (int i = 0; i < 10; i++) assertEquals(2, store.recentScore(video), 1e-9);
    store.awaitIdle();
    assertEquals("Reading decay never rewrites its persisted anchor", START, scoreAnchor(video));
    assertEquals(4, storedScore(video), 0);
    assertEquals(4, store.playCount(video));
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), clock::get);
    restarted.awaitIdle();
    assertEquals(2, restarted.recentScore(video), 1e-9);
    clock.addAndGet(HALF_LIFE);
    assertEquals(1, restarted.recentScore(video), 1e-9);
    assertEquals(4, restarted.playCount(video));
  }

  @Test
  public void pendingPlaysMergeRecentScoresAtOneReferenceTimeAfterLateLoad() throws Exception {
    VideoItem video = item("late-recent");
    seedRecent(video, 7, 8, START);
    AtomicLong clock = new AtomicLong(START + HALF_LIFE);
    ExecutorService executor = executor();
    CountDownLatch gate = block(executor);
    PlaybackStatsStore store = open(50, executor, clock::get);
    store.recordPlay(video);
    clock.addAndGet(HALF_LIFE);
    store.recordPlay(video);
    gate.countDown();
    store.awaitIdle();
    // Eight old plays become two; the pending plays contribute .5 + 1.
    assertEquals(3.5, store.recentScore(video), 1e-9);
    assertEquals(9, store.playCount(video));
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), clock::get);
    restarted.awaitIdle();
    assertEquals(3.5, restarted.recentScore(video), 1e-9);
    assertEquals(9, restarted.playCount(video));
  }

  @Test
  public void renameCombinesDecayedScoresWithoutRefreshingOldViewingHistory() throws Exception {
    VideoItem before = item("recent-before"), after = item("recent-after");
    seedRecent(before, 4, 4, START);
    seedRecent(after, 8, 8, START + HALF_LIFE);
    AtomicLong clock = new AtomicLong(START + 2 * HALF_LIFE);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(0, store.recentScore(before), 0);
    assertEquals(5, store.recentScore(after), 1e-9);
    assertEquals(12, store.playCount(after));
    close(store);
    clock.addAndGet(HALF_LIFE);
    PlaybackStatsStore restarted = open(50, executor(), clock::get);
    restarted.awaitIdle();
    assertEquals(2.5, restarted.recentScore(after), 1e-9);
    assertEquals(12, restarted.playCount(after));
  }

  @Test
  public void versionOneUpgradePreservesTotalsAndMigratesOnlyOneBoundedAgeEstimate()
      throws Exception {
    VideoItem video = item("legacy-large"), other = item("legacy-small");
    createLegacyDatabase(false);
    seedLegacy(video, Long.MAX_VALUE, START);
    seedLegacy(other, 3, START - HALF_LIFE);
    AtomicLong clock = new AtomicLong(START + HALF_LIFE);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    assertEquals(Long.MAX_VALUE, store.playCount(video));
    assertEquals(31.5, store.recentScore(video), 1e-9);
    assertEquals(.75, store.recentScore(other), 1e-9);
    try (SQLiteDatabase db = database()) {
      assertEquals(2, db.getVersion());
    }
    close(store);
    clock.addAndGet(HALF_LIFE);
    PlaybackStatsStore restarted = open(50, executor(), clock::get);
    restarted.awaitIdle();
    assertEquals(15.75, restarted.recentScore(video), 1e-9);
    assertEquals(Long.MAX_VALUE, restarted.playCount(video));
  }

  @Test
  public void runtimeClockRollbackRepairsAnchorOnceThenResumesDecay() throws Exception {
    AtomicLong clock = new AtomicLong(START + HALF_LIFE);
    VideoItem video = item("rollback");
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    for (int i = 0; i < 4; i++) store.recordPlay(video);
    store.awaitIdle();
    clock.set(START);
    assertEquals(4, store.recentScore(video), 0);
    store.awaitIdle();
    assertEquals(START, scoreAnchor(video));
    clock.addAndGet(HALF_LIFE);
    assertEquals(2, store.recentScore(video), 1e-9);
    store.recordPlay(video);
    store.awaitIdle();
    assertEquals(3, store.recentScore(video), 1e-9);
    assertEquals(5, store.playCount(video));
  }

  @Test
  public void futurePersistedTimestampIsRepairedWithoutWaitingForAnotherPlay() throws Exception {
    VideoItem video = item("far-future");
    seedRecent(video, Long.MAX_VALUE, 63, Long.MAX_VALUE);
    AtomicLong clock = new AtomicLong(START);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    assertEquals(63, store.recentScore(video), 0);
    assertEquals(START, scoreAnchor(video));
    clock.addAndGet(HALF_LIFE);
    assertEquals(31.5, store.recentScore(video), 1e-9);
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), clock::get);
    restarted.awaitIdle();
    assertEquals(31.5, restarted.recentScore(video), 1e-9);
    assertEquals(Long.MAX_VALUE, restarted.playCount(video));
  }

  @Test
  public void saturatedRecentScoreRecoversAndExtremeElapsedTimeCannotOverflow() throws Exception {
    VideoItem video = item("bounded-recent");
    AtomicLong clock = new AtomicLong(START);
    seedRecent(video, Long.MAX_VALUE, 63, START);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    for (int i = 0; i < 80; i++) store.recordPlay(video);
    store.awaitIdle();
    assertEquals(63, storedScore(video), 0);
    assertEquals(Long.MAX_VALUE, store.playCount(video));
    clock.addAndGet(HALF_LIFE);
    assertEquals(31.5, store.recentScore(video), 1e-9);
    clock.set(Long.MAX_VALUE);
    assertEquals(0, store.recentScore(video), 0);
    store.recordPlay(video);
    store.awaitIdle();
    assertEquals(1, store.recentScore(video), 0);
    assertEquals(Long.MAX_VALUE, store.playCount(video));
  }

  @Test
  public void recentSnapshotUsesOneInstantForEveryCandidate() throws Exception {
    VideoItem a = item("same-age-a"), b = item("same-age-b");
    seedRecent(a, 8, 8, START);
    seedRecent(b, 8, 8, START);
    AtomicLong clock = new AtomicLong(START);
    PlaybackStatsStore store = open(50, executor(), () -> clock.getAndAdd(HALF_LIFE));
    store.awaitIdle();
    clock.set(START + 4 * HALF_LIFE);
    double[] scores = store.recentSnapshot(Arrays.asList(a, b));
    assertArrayEquals(new double[] {.5, .5}, scores, 1e-9);
    assertEquals(START + 5 * HALF_LIFE, clock.get());
  }

  @Test
  public void failedSchemaUpgradeRollsBackWithoutClearingLegacyRecords() throws Exception {
    VideoItem video = item("upgrade-protected");
    // A conflicting second column makes the upgrade fail after its first ALTER statement.
    createLegacyDatabase(true);
    seedLegacy(video, 9, START);
    PlaybackStatsStore store = open(50, executor(), () -> START + HALF_LIFE);
    store.awaitIdle();
    store.recordPlay(video);
    store.awaitIdle();
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT play_count FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(video)});
        Cursor columns = db.rawQuery("PRAGMA table_info(plays)", null)) {
      assertEquals(1, db.getVersion());
      assertTrue(row.moveToFirst());
      assertEquals(9, row.getLong(0));
      Set<String> names = new HashSet<>();
      while (columns.moveToNext())
        names.add(columns.getString(columns.getColumnIndexOrThrow("name")));
      assertFalse("The first ALTER must be rolled back too", names.contains("recent_score"));
      assertTrue(names.contains("score_updated"));
    }
  }

  private VideoItem item(String id) {
    return new VideoItem(id, "generated.mp4", "", "", 0, 1000, 0, 0, 640, 360);
  }

  private ExecutorService executor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  private CountDownLatch block(ExecutorService executor) throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    gates.add(release);
    executor.execute(
        () -> {
          started.countDown();
          try {
            if (!release.await(30, TimeUnit.SECONDS))
              throw new AssertionError("Test gate timed out");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    return release;
  }

  private PlaybackStatsStore open(int cap) {
    return open(cap, executor());
  }

  private PlaybackStatsStore open(int cap, ExecutorService executor) {
    PlaybackStatsStore store = new PlaybackStatsStore(context, databaseName, cap, executor);
    stores.add(store);
    return store;
  }

  private PlaybackStatsStore open(int cap, ExecutorService executor, LongSupplier clock) {
    PlaybackStatsStore store = new PlaybackStatsStore(context, databaseName, cap, executor, clock);
    stores.add(store);
    return store;
  }

  private void close(PlaybackStatsStore store) throws Exception {
    store.close();
    stores.remove(store);
  }

  private SQLiteDatabase database() {
    assertTrue(databaseName.startsWith("playback-stats-instrumentation-"));
    return context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null);
  }

  private void seed(VideoItem item, long count, long lastPlayed) throws Exception {
    if (!context.getDatabasePath(databaseName).exists()) {
      PlaybackStatsStore empty = open(50);
      empty.awaitIdle();
      close(empty);
    }
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "INSERT OR REPLACE INTO plays(media_key,play_count,last_played) VALUES(?,?,?)",
          new Object[] {PlaybackStatsStore.key(item), count, lastPlayed});
    }
  }

  private void seedRecent(VideoItem item, long count, double score, long updated) throws Exception {
    seed(item, count, updated);
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "UPDATE plays SET recent_score=?,score_updated=? WHERE media_key=?",
          new Object[] {score, updated, PlaybackStatsStore.key(item)});
    }
  }

  private void createLegacyDatabase(boolean conflict) {
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "CREATE TABLE plays(media_key TEXT PRIMARY KEY NOT NULL,play_count INTEGER NOT NULL,"
              + "last_played INTEGER NOT NULL"
              + (conflict ? ",score_updated INTEGER NOT NULL DEFAULT 0" : "")
              + ") WITHOUT ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played,media_key)");
      db.setVersion(1);
    }
  }

  private void seedLegacy(VideoItem item, long count, long lastPlayed) {
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "INSERT INTO plays(media_key,play_count,last_played) VALUES(?,?,?)",
          new Object[] {PlaybackStatsStore.key(item), count, lastPlayed});
    }
  }

  private long scoreAnchor(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT score_updated FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(row.moveToFirst());
      return row.getLong(0);
    }
  }

  private double storedScore(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT recent_score FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(row.moveToFirst());
      return row.getDouble(0);
    }
  }

  private long rowsFor(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor cursor =
            db.rawQuery(
                "SELECT COUNT(*) FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(cursor.moveToFirst());
      return cursor.getLong(0);
    }
  }

  private long rowCount() {
    try (SQLiteDatabase db = database();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM plays", null)) {
      assertTrue(cursor.moveToFirst());
      return cursor.getLong(0);
    }
  }

  private void renameTable(String from, String to) {
    assertTrue(
        "plays".equals(from) && "held_plays".equals(to)
            || "held_plays".equals(from) && "plays".equals(to));
    try (SQLiteDatabase db = database()) {
      db.execSQL("ALTER TABLE " + from + " RENAME TO " + to);
    }
  }
}
