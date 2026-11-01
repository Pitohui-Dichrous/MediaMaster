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
import java.util.List;
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
    PlaybackStatsStore.PreparedWeights existingQueue = prepare(store, Arrays.asList(before, after));
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(3, store.playCount(after));
    assertEquals(existingQueue.weight(0), existingQueue.weight(1), 1e-6);
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
    assertEquals(7, store.playCount(after));
    assertEquals(0, rowsFor(before));
    close(store);
    PlaybackStatsStore restarted = open(50);
    restarted.awaitIdle();
    assertEquals(7, restarted.playCount(after));
  }

  @Test
  public void boundedMemoryCacheKeepsEveryPersistedRecord() throws Exception {
    VideoItem a = item("a"), b = item("b"), c = item("c"), d = item("d");
    PlaybackStatsStore store = open(3);
    store.awaitIdle();
    store.recordPlay(a);
    store.recordPlay(b);
    store.recordPlay(c);
    store.recordPlay(a);
    store.recordPlay(d);
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights prepared = prepare(store, Arrays.asList(a, b, c, d));
    assertEquals(4, prepared.size());
    assertArrayEquals(new long[] {2, 1, 1, 1}, store.snapshot(Arrays.asList(a, b, c, d)));
    assertEquals(3, store.cachedRecordCount());
    assertEquals(4, rowCount());
    close(store);
    PlaybackStatsStore restarted = open(3);
    restarted.awaitIdle();
    PlaybackStatsStore.PreparedWeights reloaded = prepare(restarted, Arrays.asList(a, b, c, d));
    assertEquals(4, reloaded.size());
    assertArrayEquals(new long[] {2, 1, 1, 1}, restarted.snapshot(Arrays.asList(a, b, c, d)));
    assertEquals(3, restarted.cachedRecordCount());
    assertEquals(4, rowCount());
  }

  @Test
  public void pendingColdViewingBurstPreservesCappedScoreDecayAndAllExposure() throws Exception {
    VideoItem video = item("pending-burst");
    seedFeedback(video, 5, 60, 60, 0, 0, START);
    AtomicLong clock = new AtomicLong(START);
    ExecutorService worker = executor();
    CountDownLatch gate = block(worker);
    PlaybackStatsStore store = open(50, worker, clock::get);
    for (int i = 0; i < 100; i++) store.recordPlay(video);
    clock.addAndGet(HALF_LIFE);
    store.recordPlay(video);
    gate.countDown();
    store.awaitIdle();
    assertEquals(106, store.playCount(video));
    assertEquals(32.5, store.recentScore(video), 1e-12);
    assertEquals(81, masses(video)[0], 1e-12);
    close(store);
    PlaybackStatsStore reloaded = open(50, executor(), clock::get);
    reloaded.awaitIdle();
    assertEquals(106, reloaded.playCount(video));
    assertEquals(32.5, reloaded.recentScore(video), 1e-12);
    assertEquals(81, masses(video)[0], 1e-12);
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
                  "media_key",
                  "play_count",
                  "last_played",
                  "recent_score",
                  "score_updated",
                  "exposure",
                  "likes",
                  "dislikes",
                  "preference_updated",
                  "mass_exponent")),
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
    // Recovery hydrates touched records only. The untouched cold row must remain intact.
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT play_count FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(b)})) {
      assertTrue(row.moveToFirst());
      assertEquals(4, row.getLong(0));
    }
    PlaybackStatsStore.PreparedWeights recovered = prepare(store, Arrays.asList(a, b, c));
    assertEquals(3, recovered.size());
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
      assertEquals(6, db.getVersion());
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

  @Test
  public void oneWindowVoteIsIdempotentNewWindowsAccumulateAndOnlyTheirOwnVoteCanBeUndone()
      throws Exception {
    VideoItem video = item("window-vote");
    AtomicLong clock = new AtomicLong(START);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    PlaybackVoteSession first = new PlaybackVoteSession(store);
    assertTrue(first.setVote(video, 1));
    store.awaitIdle();
    assertEquals(1, first.vote(video));
    double firstWeight = store.weight(video);
    double changed = masses(video)[3];
    clock.addAndGet(1000);
    assertTrue(first.setVote(video, 1));
    store.awaitIdle();
    assertEquals(changed, masses(video)[3], 0); // Identical votes do not even write a timestamp.
    clock.set(START);
    PlaybackVoteSession second = new PlaybackVoteSession(store);
    assertEquals(0, second.vote(video));
    assertTrue(second.setVote(video, 1));
    assertTrue(store.weight(video) > firstWeight);
    assertTrue(second.setVote(video, 0));
    assertEquals(firstWeight, store.weight(video), 1e-12);
    assertEquals(1, first.vote(video));
    assertTrue(second.setVote(video, -1));
    assertEquals(1, store.weight(video), 1e-12); // Equal like/dislike mass cancels its effect.
    assertTrue(second.setVote(video, 0));
    store.awaitIdle();
    assertEquals(0, store.playCount(video));
    assertEquals(0, store.recentScore(video), 0);
    assertArrayEquals(new double[] {0, 1, 0, START}, masses(video), 1e-12);
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), () -> START);
    restarted.awaitIdle();
    PlaybackVoteSession third = new PlaybackVoteSession(restarted);
    assertEquals("Selections belong to the old window, not the database", 0, third.vote(video));
    assertEquals(firstWeight, restarted.weight(video), 1e-12);
    assertTrue(third.setVote(video, 1));
    assertTrue(restarted.weight(video) > firstWeight);
  }

  @Test
  public void votesWaitForKnownHistoryAndExplicitActionsRetryFailedReadsSafely() throws Exception {
    VideoItem video = item("unavailable-vote");
    seedFeedback(video, 7, 4, 7, 3, 1, START);
    renameTable("plays", "held_plays");
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertFalse(session.setVote(video, 1));
    store.awaitIdle();
    assertEquals(0, session.vote(video));
    renameTable("held_plays", "plays");
    assertFalse(session.setVote(video, 1)); // This explicit attempt schedules one safe retry.
    store.awaitIdle();
    assertTrue(session.setVote(video, 1));
    store.awaitIdle();
    assertEquals(7, store.playCount(video));
    assertArrayEquals(new double[] {7, 4, 1, START}, masses(video), 1e-12);
  }

  @Test
  public void pendingInitialViewingMergesExposureWithoutLosingHistoricalVotes() throws Exception {
    VideoItem video = item("pending-mass");
    seedFeedback(video, 9, 4, 9, 3, 1, START);
    ExecutorService worker = executor();
    CountDownLatch gate = block(worker);
    PlaybackStatsStore store = open(50, worker, () -> START);
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertFalse(session.setVote(video, 1));
    store.recordPlay(video);
    gate.countDown();
    store.awaitIdle();
    assertTrue(session.setVote(video, 1));
    store.awaitIdle();
    assertEquals(10, store.playCount(video));
    assertEquals(5, store.recentScore(video), 0);
    assertArrayEquals(new double[] {10, 4, 1, START}, masses(video), 1e-12);
  }

  @Test
  public void versionTwoUpgradeUsesOnlyAnExposureEstimateAndPreservesRecentScore()
      throws Exception {
    VideoItem video = item("version-two");
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "CREATE TABLE plays(media_key TEXT PRIMARY KEY NOT NULL,play_count INTEGER NOT"
              + " NULL,last_played INTEGER NOT NULL,recent_score REAL NOT NULL,score_updated"
              + " INTEGER NOT NULL) WITHOUT ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played,media_key)");
      db.execSQL(
          "INSERT INTO plays VALUES(?,?,?,?,?)",
          new Object[] {PlaybackStatsStore.key(video), Long.MAX_VALUE, START, 4, START});
      db.setVersion(2);
    }
    PlaybackStatsStore store = open(50, executor(), () -> START + HALF_LIFE);
    store.awaitIdle();
    assertEquals(Long.MAX_VALUE, store.playCount(video));
    assertEquals(2, store.recentScore(video), 0);
    assertEquals(WeightedShuffle.weight(2), store.weight(video), 0);
    assertArrayEquals(new double[] {4, 0, 0, START}, masses(video), 0);
    try (SQLiteDatabase db = database()) {
      assertEquals(6, db.getVersion());
    }
  }

  @Test
  public void unpublishedEnumSchemaUpgradesNeutrallyInsteadOfInventingVoteHistory()
      throws Exception {
    VideoItem video = item("development-enum");
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "CREATE TABLE plays(media_key TEXT PRIMARY KEY NOT NULL,play_count INTEGER NOT"
              + " NULL,last_played INTEGER NOT NULL,recent_score REAL NOT NULL,score_updated"
              + " INTEGER NOT NULL,feedback INTEGER NOT NULL,feedback_updated INTEGER NOT NULL)"
              + " WITHOUT ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played,media_key)");
      db.execSQL(
          "INSERT INTO plays VALUES(?,?,?,?,?,?,?)",
          new Object[] {PlaybackStatsStore.key(video), 8, START, 4, START, 1, START});
      db.setVersion(3);
    }
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    assertEquals(8, store.playCount(video));
    assertEquals(WeightedShuffle.weight(4), store.weight(video), 0);
    assertEquals(0, new PlaybackVoteSession(store).vote(video));
    assertArrayEquals(new double[] {4, 0, 0, START}, masses(video), 0);
    try (SQLiteDatabase db = database();
        Cursor cols = db.rawQuery("PRAGMA table_info(plays)", null)) {
      assertEquals(6, db.getVersion());
      while (cols.moveToNext()) assertFalse(cols.getString(1).startsWith("feedback"));
    }
  }

  @Test
  public void renamedIdentifiersMergeAllDecayedMassAndStableRenamesKeepTheWindowVote()
      throws Exception {
    VideoItem before = item("mass-before"), after = item("mass-after");
    seedFeedback(before, 3, 3, 4, 2, 0, START - HALF_LIFE);
    seedFeedback(after, 2, 2, 3, 0, 1, START);
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(5, store.playCount(after));
    assertEquals(3.5, store.recentScore(after), 0);
    assertArrayEquals(new double[] {5, 1, 1, START}, masses(after), 0);
    assertEquals(0, rowsFor(before));
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(after, 1));
    store.migrate(after, after.withName("display-only.mp4"));
    store.awaitIdle();
    assertEquals(1, session.vote(after));
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), () -> START);
    restarted.awaitIdle();
    assertEquals(5, restarted.playCount(after));
    assertArrayEquals(new double[] {5, 2, 1, START}, masses(after), 0);
  }

  @Test
  public void resetClearsEveryWeightMassAndWindowSelectionButRetainsLifetimeCounts()
      throws Exception {
    VideoItem a = item("reset-a"), b = item("reset-b");
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    store.recordPlay(a);
    store.recordPlay(a);
    assertTrue(session.setVote(a, 1));
    assertTrue(session.setVote(b, -1));
    assertTrue(reset(store));
    assertArrayEquals(new double[] {1, 1}, store.weightSnapshot(Arrays.asList(a, b)), 0);
    assertEquals(2, store.playCount(a));
    assertEquals(0, session.vote(a));
    assertEquals(0, session.vote(b));
    assertArrayEquals(new double[] {0, 0, 0, START}, masses(a), 0);
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), () -> START + HALF_LIFE);
    restarted.awaitIdle();
    assertArrayEquals(new double[] {1, 1}, restarted.weightSnapshot(Arrays.asList(a, b)), 0);
    assertEquals(2, restarted.playCount(a));
  }

  @Test
  public void resetDuringInitialLoadPreservesNewViewingAndRenameWithoutAcceptingAnAmbiguousVote()
      throws Exception {
    VideoItem before = item("reset-pending-before"), after = item("reset-pending-after");
    seedFeedback(before, 5, 5, 5, 2, 1, START);
    ExecutorService worker = executor();
    CountDownLatch gate = block(worker);
    PlaybackStatsStore store = open(50, worker, () -> START);
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    store.recordPlay(before);
    CountDownLatch resetDone = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    store.resetWeights(
        ok -> {
          success.set(ok);
          resetDone.countDown();
        });
    store.recordPlay(before);
    assertFalse(session.setVote(before, 1));
    store.migrate(before, after);
    gate.countDown();
    assertTrue(resetDone.await(10, TimeUnit.SECONDS));
    assertTrue(success.get());
    store.awaitIdle();
    assertEquals(7, store.playCount(after));
    assertEquals(1, store.recentScore(after), 0);
    assertArrayEquals(new double[] {1, 0, 0, START}, masses(after), 0);
    assertEquals(0, rowsFor(before));
    assertTrue(session.setVote(after, 1));
    store.awaitIdle();
    close(store);
    PlaybackStatsStore restarted = open(50, executor(), () -> START);
    restarted.awaitIdle();
    assertEquals(7, restarted.playCount(after));
    assertArrayEquals(new double[] {1, 1, 0, START}, masses(after), 0);
  }

  @Test
  public void resetFailurePreservesWeightsAndActiveSelectionsUntilStorageRecovers()
      throws Exception {
    VideoItem video = item("reset-failure");
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    store.recordPlay(video);
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, -1));
    store.awaitIdle();
    double original = store.weight(video);
    renameTable("plays", "held_plays");
    assertFalse(reset(store));
    assertEquals(original, store.weight(video), 0);
    assertEquals(-1, session.vote(video));
    renameTable("held_plays", "plays");
    assertTrue(reset(store));
    assertEquals(1, store.weight(video), 0);
    assertEquals(1, store.playCount(video));
    assertEquals(0, session.vote(video));
  }

  @Test
  public void switchingOrCancelingAfterDecayOnlyRemovesThisWindowsDecayedContribution()
      throws Exception {
    VideoItem video = item("reversible-mass");
    seedFeedback(video, 0, 0, 0, 2, 1, START);
    AtomicLong clock = new AtomicLong(START);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 1));
    clock.addAndGet(HALF_LIFE);
    assertTrue(session.setVote(video, -1));
    store.awaitIdle();
    assertArrayEquals(new double[] {0, 1, 1.5, START + HALF_LIFE}, masses(video), 1e-12);
    assertTrue(session.setVote(video, 0));
    store.awaitIdle();
    assertArrayEquals(new double[] {0, 1, .5, START + HALF_LIFE}, masses(video), 1e-12);
    assertEquals(0, session.vote(video));
  }

  @Test
  public void rescaledVotesContinueAddingAndOnlyUndoTheirOwnContribution() throws Exception {
    VideoItem video = item("capped-vote");
    seedFeedback(video, Long.MAX_VALUE, 63, 1_000_000, 999_999.75, 0, START);
    AtomicLong clock = new AtomicLong(START);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    PlaybackVoteSession partial = new PlaybackVoteSession(store),
        capped = new PlaybackVoteSession(store);
    assertTrue(partial.setVote(video, 1));
    assertTrue(capped.setVote(video, 1));
    store.awaitIdle();
    assertEquals(1_000_001.75, masses(video)[1], 0);
    assertTrue(capped.setVote(video, 0));
    store.awaitIdle();
    assertEquals(1_000_000.75, masses(video)[1], 0);
    clock.addAndGet(HALF_LIFE);
    assertTrue(partial.setVote(video, 0));
    store.awaitIdle();
    assertEquals(999_999.75 / 2, masses(video)[1], 1e-9);
    store.recordPlay(video);
    store.awaitIdle();
    assertEquals(Long.MAX_VALUE, store.playCount(video));
    assertTrue(Double.isFinite(store.weight(video)));
    double weight = store.weight(video);
    close(store);
    PlaybackStatsStore reloaded = open(50, executor(), clock::get);
    reloaded.awaitIdle();
    assertEquals(weight, reloaded.weight(video), 1e-12);
    assertEquals(999_999.75 / 2, masses(video)[1], 1e-9);
  }

  @Test
  public void rollbackReanchorsContributionWithItsAggregateBeforeFurtherDecayAndCancellation()
      throws Exception {
    VideoItem video = item("clock-vote");
    seedFeedback(video, 0, 0, 0, 4, 0, START);
    AtomicLong clock = new AtomicLong(START + HALF_LIFE);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 1)); // Historical mass 2 plus current mass 1.
    clock.addAndGet(HALF_LIFE);
    store.recordPlay(video); // Historical 1 plus current .5, re-anchored together.
    store.awaitIdle();
    clock.set(START);
    store.weight(video); // Backward-clock repair must also re-anchor the live token.
    clock.addAndGet(HALF_LIFE);
    assertTrue(session.setVote(video, 0));
    store.awaitIdle();
    assertEquals(.5, masses(video)[1], 1e-12); // Only old historical mass remains.
    assertEquals(0, session.vote(video));
    try {
      session.setVote(video, 2);
      fail("Invalid vote must be rejected");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void exposureKeepsGrowingBeyondWatchingScoreCapSoSparseLikesStaySparse() throws Exception {
    VideoItem video = item("large-exposure");
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    for (int i = 0; i < 128; i++) store.recordPlay(video);
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 1));
    store.awaitIdle();
    assertEquals(63, store.recentScore(video), 0);
    assertEquals(128, masses(video)[0], 0);
    assertEquals(.125 + 1.375 / 144, store.weight(video), 1e-12);
  }

  @Test
  public void ownedRenameKeepsTheSameWindowVoteAndItsDecayedUndo() throws Exception {
    VideoItem before = item("live-rename-before"), after = item("live-rename-after");
    seedFeedback(before, 0, 0, 0, 2, 0, START);
    AtomicLong clock = new AtomicLong(START);
    PlaybackStatsStore store = open(50, executor(), clock::get);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(before, 1));
    clock.addAndGet(HALF_LIFE);
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(1, session.vote(after));
    assertTrue(session.setVote(after, 1)); // A renamed item cannot gain a second window vote.
    store.awaitIdle();
    assertEquals(1.5, masses(after)[1], 1e-12);
    assertTrue(session.setVote(after, 0));
    store.awaitIdle();
    assertEquals(1, masses(after)[1], 1e-12);
    assertEquals(0, rowsFor(before));
  }

  @Test
  public void oldQueueReferencesResolveToTheRenamedAggregateEvenAfterCancelAndAnotherRename()
      throws Exception {
    VideoItem original = item("queue-old-reference"),
        renamed = item("queue-renamed"),
        newest = item("queue-renamed-again");
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(original, 1));
    store.migrate(original, renamed);
    store.awaitIdle();
    assertEquals(1, session.vote(original));
    assertEquals(1, session.vote(renamed));
    assertTrue(session.setVote(original, 1));
    assertTrue(session.setVote(original, 0));
    store.awaitIdle();
    assertEquals(0, masses(renamed)[1], 0);
    store.migrate(renamed, newest);
    store.awaitIdle();
    assertTrue(session.setVote(original, -1));
    assertEquals(-1, session.vote(newest));
    assertEquals(-1, session.vote(renamed));
    store.awaitIdle();
    assertEquals(0, rowsFor(original));
    assertEquals(0, rowsFor(renamed));
    assertArrayEquals(new double[] {0, 0, 1, START}, masses(newest), 0);
  }

  @Test
  public void renameCollisionsKeepOnlyNewestWindowVoteWithoutUndoingOtherWindows()
      throws Exception {
    VideoItem before = item("collision-before"), after = item("collision-after");
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    PlaybackVoteSession other = new PlaybackVoteSession(store),
        current = new PlaybackVoteSession(store);
    assertTrue(other.setVote(before, 1));
    assertTrue(current.setVote(before, 1));
    assertTrue(current.setVote(after, -1));
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(-1, current.vote(after));
    assertEquals(1, other.vote(after));
    store.awaitIdle();
    assertArrayEquals(new double[] {0, 1, 1, START}, masses(after), 1e-12);
    assertTrue(current.setVote(after, 0));
    store.awaitIdle();
    assertEquals(1, masses(after)[1], 1e-12);
    assertEquals(0, masses(after)[2], 1e-12);
    assertTrue(other.setVote(after, 0));
    store.awaitIdle();
    assertEquals(0, masses(after)[1], 1e-12);
  }

  @Test
  public void renamedMassesShareScaleAndKeepAllActiveContributionsReversible() throws Exception {
    VideoItem before = item("merge-cap-before"), after = item("merge-cap-after");
    seedFeedback(before, 0, 0, 0, 999_999, 0, START);
    seedFeedback(after, 0, 0, 0, 999_999, 0, START);
    PlaybackStatsStore store = open(50, executor(), () -> START);
    store.awaitIdle();
    PlaybackVoteSession first = new PlaybackVoteSession(store),
        second = new PlaybackVoteSession(store);
    assertTrue(first.setVote(before, 1));
    assertTrue(second.setVote(after, 1));
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(2_000_000, masses(after)[1], 1e-9);
    assertEquals(1, first.vote(after));
    assertTrue(first.setVote(after, 0));
    store.awaitIdle();
    assertEquals(1_999_999, masses(after)[1], 1e-9);
    assertTrue(second.setVote(after, 0));
    store.awaitIdle();
    assertEquals(1_999_998, masses(after)[1], 1e-9);
  }

  @Test
  public void saturatedRecencyNeverDropsVotesWhenTheMemoryCacheEvictsAnotherRow() throws Exception {
    VideoItem a = item("saturated-key-a"), b = item("saturated-key-b");
    VideoItem retained = PlaybackStatsStore.key(a).compareTo(PlaybackStatsStore.key(b)) > 0 ? a : b;
    VideoItem pruned = retained == a ? b : a;
    seedFeedback(retained, 1, 1, 1, 0, 0, Long.MAX_VALUE);
    PlaybackStatsStore store = open(1, executor(), () -> Long.MAX_VALUE);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(pruned, 1));
    assertEquals(1, session.vote(pruned));
    store.awaitIdle();
    assertEquals(1, rowsFor(pruned));
    assertEquals(1, rowsFor(retained));
    assertEquals(1, store.cachedRecordCount());
  }

  @Test
  public void unresolvedResetRejectsEvenAnIdenticalVoteWithoutPretendingItWasAccepted()
      throws Exception {
    VideoItem video = item("reset-same-vote");
    ExecutorService worker = executor();
    PlaybackStatsStore store = open(50, worker, () -> START);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 1));
    store.awaitIdle();
    CountDownLatch gate = block(worker), done = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    store.resetWeights(
        ok -> {
          success.set(ok);
          done.countDown();
        });
    assertFalse(session.setVote(video, 1));
    assertEquals(1, session.vote(video));
    gate.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(success.get());
    assertEquals(0, session.vote(video));
  }

  private boolean reset(PlaybackStatsStore store) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean(), mainThread = new AtomicBoolean();
    store.resetWeights(
        ok -> {
          success.set(ok);
          mainThread.set(Looper.myLooper() == Looper.getMainLooper());
          done.countDown();
        });
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(mainThread.get());
    store.awaitIdle();
    return success.get();
  }

  private PlaybackStatsStore.PreparedWeights prepare(
      PlaybackStatsStore store, List<VideoItem> videos) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    PlaybackStatsStore.PreparedWeights[] result = new PlaybackStatsStore.PreparedWeights[1];
    store.prepareWeights(
        videos,
        weights -> {
          result[0] = weights;
          done.countDown();
        });
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertNotNull(result[0]);
    return result[0];
  }

  private void seedFeedback(
      VideoItem item,
      long count,
      double score,
      double exposure,
      double likes,
      double dislikes,
      long updated)
      throws Exception {
    seedRecent(item, count, score, updated);
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "UPDATE plays SET exposure=?,likes=?,dislikes=?,preference_updated=? WHERE media_key=?",
          new Object[] {exposure, likes, dislikes, updated, PlaybackStatsStore.key(item)});
    }
  }

  private double[] masses(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT exposure,likes,dislikes,preference_updated,mass_exponent FROM plays WHERE"
                    + " media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(row.moveToFirst());
      int exponent = row.getInt(4);
      return new double[] {
        Math.scalb(row.getDouble(0), exponent),
        Math.scalb(row.getDouble(1), exponent),
        Math.scalb(row.getDouble(2), exponent),
        row.getLong(3)
      };
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
