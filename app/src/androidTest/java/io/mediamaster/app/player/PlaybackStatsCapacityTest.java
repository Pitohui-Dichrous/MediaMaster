package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.media.VideoItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/** Tiny memory caches exercise complete persisted history in disposable test-owned databases. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackStatsCapacityTest {
  private static final long START = 1_700_000_000_000L;
  private Context context;
  private String databaseName;
  private final AtomicLong clock = new AtomicLong(START);
  private final ArrayList<PlaybackStatsStore> stores = new ArrayList<>();
  private final ArrayList<ExecutorService> executors = new ArrayList<>();

  @Before
  public void isolateDatabase() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    databaseName = "playback-capacity-instrumentation-" + UUID.randomUUID() + ".db";
    assertFalse(context.getDatabasePath(databaseName).exists());
  }

  @After
  public void cleanup() throws Exception {
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
      assertTrue(databaseName.startsWith("playback-capacity-instrumentation-"));
      context.deleteDatabase(databaseName);
      assertFalse(context.getDatabasePath(databaseName).exists());
    }
    if (first != null) throw first;
  }

  @Test
  public void fullQueueKeepsColdWeightsAndEveryPersistedRecordAcrossCacheEvictionAndRestart()
      throws Exception {
    List<VideoItem> videos = seedSeveral(8);
    PlaybackStatsStore store = open(2);
    store.awaitIdle();
    assertTrue(store.cachedRecordCount() <= 2);
    List<VideoItem> reordered =
        Arrays.asList(videos.get(7), videos.get(0), videos.get(4), videos.get(1), videos.get(0));
    PlaybackStatsStore.PreparedWeights weights = prepare(store, reordered);
    assertEquals(reordered.size(), weights.size());
    assertEquals(expectedFor(7), weights.weight(0), 1e-12);
    assertEquals(expectedFor(0), weights.weight(1), 1e-12);
    assertEquals(expectedFor(4), weights.weight(2), 1e-12);
    assertEquals(expectedFor(1), weights.weight(3), 1e-12);
    assertEquals(weights.weight(1), weights.weight(4), 0);
    assertEquals(8, rowCount());
    assertTrue(
        "Preparing a queue does not make the global cache unbounded",
        store.cachedRecordCount() <= 2);
    close(store);

    PlaybackStatsStore restarted = open(2);
    restarted.awaitIdle();
    PlaybackStatsStore.PreparedWeights restored = prepare(restarted, videos);
    for (int i = 0; i < videos.size(); i++) {
      assertEquals(expectedFor(i), restored.weight(i), 1e-12);
      assertEquals(i + 1, persisted(videos.get(i))[0], 0);
    }
    assertEquals(8, rowCount());
    assertTrue(restarted.cachedRecordCount() <= 2);
  }

  @Test
  public void retainedQueueLazilyDecaysAndUpdatesEveryDuplicateSlotAfterWatchingAndVoting()
      throws Exception {
    VideoItem a = item("live-a"), b = item("live-b");
    seed(a, 10, 3, 4, 2, 0, 0);
    seed(b, 1, 0, 1, 0, 0, 1);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights weights = prepare(store, Arrays.asList(a, b, a));
    assertEquals(.6, weights.weight(0), 1e-12);

    clock.addAndGet(RecentPlaybackPolicy.HALF_LIFE_MS);
    double decayed = expected(1.5, 2, 1, 0);
    assertEquals(decayed, weights.weight(0), 1e-12);
    assertEquals(decayed, weights.weight(2), 1e-12);
    assertEquals("Lazy snapshot decay must not rewrite a stored anchor", START, persisted(a)[5], 0);
    store.recordPlay(a);
    store.awaitIdle();
    double watched = expected(2.5, 3, 1, 0);
    assertEquals(watched, weights.weight(0), 1e-12);
    assertEquals(watched, weights.weight(2), 1e-12);

    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue("A prepared item has complete history without another read", session.setVote(a, 1));
    assertEquals(expected(2.5, 3, 2, 0), weights.weight(0), 1e-12);
    assertEquals(weights.weight(0), weights.weight(2), 0);
    assertTrue(session.setVote(a, 0));
    assertEquals(watched, weights.weight(0), 1e-12);
    assertEquals(watched, weights.weight(2), 1e-12);
    store.awaitIdle();
    assertTrue(store.cachedRecordCount() <= 1);
  }

  @Test
  public void coldViewingReadsAndAddsToSavedCountsAndFeedbackInsteadOfStartingAtZero()
      throws Exception {
    VideoItem cold = item("cold-watch"), hot = item("hot-watch");
    seed(cold, 7, 3, 7, 2, 1, 0);
    seed(hot, 1, 1, 1, 0, 0, 10);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    store.recordPlay(cold);
    store.recordPlay(cold);
    store.recordPlay(cold);
    store.awaitIdle();
    assertArrayEquals(new double[] {10, 6, 10, 2, 1, START}, persisted(cold), 1e-12);
    assertEquals(expected(6, 10, 2, 1), prepare(store, Arrays.asList(cold)).weight(0), 1e-12);
    assertEquals(2, rowCount());
  }

  @Test
  public void coldVoteHydratesHistoryAndItsLiveTokenSurvivesLaterCacheEviction() throws Exception {
    VideoItem cold = item("cold-vote"), hot = item("hot-vote");
    seed(cold, 7, 3, 7, 2, 1, 0);
    seed(hot, 1, 1, 1, 0, 0, 10);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertFalse("A cold vote must first hydrate the real history", session.setVote(cold, 1));
    store.awaitIdle();
    assertTrue(session.setVote(cold, 1));
    store.awaitIdle();
    assertEquals(3, persisted(cold)[3], 0);
    store.recordPlay(hot);
    store.awaitIdle();
    assertTrue(store.cachedRecordCount() <= 1);
    assertEquals("Cache eviction is not deletion of a window contribution", 1, session.vote(cold));
    acceptAfterHydration(store, session, cold, 0);
    assertArrayEquals(new double[] {7, 3, 7, 2, 1, START}, persisted(cold), 1e-12);
    assertEquals(0, session.vote(cold));
    assertEquals(2, rowCount());
  }

  @Test
  public void renameOfTwoColdIdentifiersMergesBothHistoriesAcrossRestart() throws Exception {
    VideoItem before = item("cold-before"), after = item("cold-after"), hot = item("hot");
    seed(before, 2, 1, 2, 2, 0, 0);
    seed(after, 3, 3, 3, 0, 1, 1);
    seed(hot, 1, 0, 1, 0, 0, 10);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    store.migrate(before, after);
    store.awaitIdle();
    assertEquals(0, rowsFor(before));
    assertArrayEquals(new double[] {5, 4, 5, 2, 1, START}, persisted(after), 1e-12);
    assertEquals(expected(4, 5, 2, 1), prepare(store, Arrays.asList(after)).weight(0), 1e-12);
    close(store);
    PlaybackStatsStore restarted = open(1);
    restarted.awaitIdle();
    assertEquals(expected(4, 5, 2, 1), prepare(restarted, Arrays.asList(after)).weight(0), 1e-12);
    assertEquals(2, rowCount());
  }

  @Test
  public void confirmedResetClearsTheEntireDatabaseAndAllLiveSnapshotsNotOnlyTheCache()
      throws Exception {
    List<VideoItem> videos = seedSeveral(6);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights first = prepare(store, videos.subList(0, 2));
    PlaybackStatsStore.PreparedWeights second = prepare(store, videos.subList(2, 3));
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(videos.get(0), 1));
    assertTrue(reset(store));
    assertEquals(0, session.vote(videos.get(0)));
    for (int i = 0; i < first.size(); i++) assertEquals(1, first.weight(i), 0);
    assertEquals(1, second.weight(0), 0);
    for (int i = 0; i < videos.size(); i++) {
      double[] row = persisted(videos.get(i));
      assertEquals(i + 1, row[0], 0);
      for (int j = 1; j <= 4; j++) assertEquals(0, row[j], 0);
    }
    assertEquals(6, rowCount());
    close(store);
    PlaybackStatsStore restarted = open(1);
    restarted.awaitIdle();
    PlaybackStatsStore.PreparedWeights restored = prepare(restarted, videos);
    for (int i = 0; i < restored.size(); i++) assertEquals(1, restored.weight(i), 0);
    assertEquals(6, rowCount());
  }

  @Test
  public void balancedFeedbackAndLifetimeOnlyRowsSurviveEvenWhenCurrentWeightIsOne()
      throws Exception {
    VideoItem balanced = item("balanced"), history = item("lifetime-only"), empty = item("empty");
    seed(balanced, 0, 0, 12, 4, 4, 0);
    seed(history, 17, 0, 0, 0, 0, 10);
    seed(empty, 0, 0, 0, 0, 0, 11);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights weights = prepare(store, Arrays.asList(balanced, history));
    assertEquals(1, weights.weight(0), 0);
    assertEquals(1, weights.weight(1), 0);
    assertEquals(1, rowsFor(balanced));
    assertEquals(1, rowsFor(history));
    assertEquals(17, persisted(history)[0], 0);
    // A completely empty row may be retained or compacted; a neutral *weight* is not that
    // condition.
    assertTrue(rowsFor(empty) <= 1);
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(balanced, 1));
    assertEquals(1 + .5 / 28, weights.weight(0), 1e-12);
    store.awaitIdle();
    assertArrayEquals(new double[] {0, 0, 12, 5, 4, START}, persisted(balanced), 1e-12);
    close(store);
    PlaybackStatsStore restarted = open(1);
    restarted.awaitIdle();
    assertEquals(1 + .5 / 28, prepare(restarted, Arrays.asList(balanced)).weight(0), 1e-12);
    assertEquals(17, persisted(history)[0], 0);
  }

  @Test
  public void preparedQueueReadsRemainCompleteOnMainWithoutReopeningTheDatabase() throws Exception {
    List<VideoItem> videos = seedSeveral(6);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights weights = prepare(store, videos);
    renameTable("plays", "held_plays");
    try {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                try {
                  for (int i = 0; i < videos.size(); i++)
                    assertEquals(expectedFor(i), weights.weight(i), 1e-12);
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      if (failure.get() instanceof Error) throw (Error) failure.get();
      if (failure.get() != null) throw new AssertionError(failure.get());
    } finally {
      renameTable("held_plays", "plays");
    }
  }

  @Test
  public void failedColdQueueReadReturnsFailureRatherThanInventingUniformWeights()
      throws Exception {
    VideoItem cold = item("read-failure-cold"), hot = item("read-failure-hot");
    seed(cold, 9, 3, 9, 3, 2, 0);
    seed(hot, 1, 1, 1, 0, 0, 10);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    renameTable("plays", "held_plays");
    try {
      assertNull(prepareOrNull(store, Arrays.asList(cold, hot)));
    } finally {
      renameTable("held_plays", "plays");
    }
    PlaybackStatsStore.PreparedWeights restored = prepare(store, Arrays.asList(cold, hot));
    assertEquals(expected(3, 9, 3, 2), restored.weight(0), 1e-12);
    assertEquals(expected(1, 1, 0, 0), restored.weight(1), 1e-12);
    assertArrayEquals(new double[] {9, 3, 9, 3, 2, START}, persisted(cold), 1e-12);
  }

  @Test
  public void failedColdViewingReadPreservesPendingDeltasUntilHistoryCanBeMerged()
      throws Exception {
    VideoItem cold = item("write-failure-cold"), hot = item("write-failure-hot");
    seed(cold, 9, 3, 9, 3, 2, 0);
    seed(hot, 1, 1, 1, 0, 0, 10);
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    renameTable("plays", "held_plays");
    try {
      store.recordPlay(cold);
      store.awaitIdle();
    } finally {
      renameTable("held_plays", "plays");
    }
    store.recordPlay(cold);
    store.awaitIdle();
    assertArrayEquals(new double[] {11, 5, 11, 3, 2, START}, persisted(cold), 1e-12);
    assertEquals(expected(5, 11, 3, 2), prepare(store, Arrays.asList(cold)).weight(0), 1e-12);
  }

  @Test
  public void versionFourUpgradePreservesEveryEffectiveRowBeyondTheNewCacheCapacity()
      throws Exception {
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "CREATE TABLE plays(media_key TEXT PRIMARY KEY NOT NULL,play_count INTEGER NOT NULL,"
              + "last_played INTEGER NOT NULL,recent_score REAL NOT NULL DEFAULT 0,"
              + "score_updated INTEGER NOT NULL DEFAULT 0,exposure REAL NOT NULL DEFAULT 0,"
              + "likes REAL NOT NULL DEFAULT 0,dislikes REAL NOT NULL DEFAULT 0,"
              + "preference_updated INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID");
      db.execSQL("CREATE INDEX plays_recent ON plays(last_played,media_key)");
      db.setVersion(4);
    }
    List<VideoItem> videos = seedSeveral(6);
    PlaybackStatsStore store = open(2);
    store.awaitIdle();
    PlaybackStatsStore.PreparedWeights weights = prepare(store, videos);
    for (int i = 0; i < videos.size(); i++) assertEquals(expectedFor(i), weights.weight(i), 1e-12);
    assertEquals(6, rowCount());
    assertTrue(store.cachedRecordCount() <= 2);
    try (SQLiteDatabase db = database()) {
      assertTrue(db.getVersion() >= 5);
    }
  }

  @Test
  public void saturatedRecencyCannotTurnCacheEvictionIntoVoteOrDatabaseLoss() throws Exception {
    clock.set(Long.MAX_VALUE);
    VideoItem a = item("saturated-cap-a"), b = item("saturated-cap-b");
    PlaybackStatsStore store = open(1);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    acceptAfterHydration(store, session, a, 1);
    acceptAfterHydration(store, session, b, -1);
    assertEquals(1, session.vote(a));
    assertEquals(-1, session.vote(b));
    assertEquals(2, rowCount());
    PlaybackStatsStore.PreparedWeights weights = prepare(store, Arrays.asList(a, b));
    assertEquals(expected(0, 0, 1, 0), weights.weight(0), 1e-12);
    assertEquals(expected(0, 0, 0, 1), weights.weight(1), 1e-12);
    assertTrue(store.cachedRecordCount() <= 1);
  }

  private PlaybackStatsStore.PreparedWeights prepare(
      PlaybackStatsStore store, List<VideoItem> queue) throws Exception {
    PlaybackStatsStore.PreparedWeights result = prepareOrNull(store, queue);
    assertNotNull("Complete queued history should be available", result);
    return result;
  }

  private PlaybackStatsStore.PreparedWeights prepareOrNull(
      PlaybackStatsStore store, List<VideoItem> queue) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<PlaybackStatsStore.PreparedWeights> result = new AtomicReference<>();
    AtomicBoolean mainThread = new AtomicBoolean();
    store.prepareWeights(
        queue,
        prepared -> {
          result.set(prepared);
          mainThread.set(Looper.myLooper() == Looper.getMainLooper());
          done.countDown();
        });
    assertTrue("Preparing a queue must complete", done.await(20, TimeUnit.SECONDS));
    assertTrue("Preparation result is delivered to the UI thread", mainThread.get());
    store.awaitIdle();
    return result.get();
  }

  private void acceptAfterHydration(
      PlaybackStatsStore store, PlaybackVoteSession session, VideoItem item, int vote)
      throws Exception {
    if (!session.setVote(item, vote)) {
      store.awaitIdle();
      assertTrue("An explicit retry must use the now-known history", session.setVote(item, vote));
    }
    store.awaitIdle();
  }

  private boolean reset(PlaybackStatsStore store) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean result = new AtomicBoolean(), mainThread = new AtomicBoolean();
    store.resetWeights(
        success -> {
          result.set(success);
          mainThread.set(Looper.myLooper() == Looper.getMainLooper());
          done.countDown();
        });
    assertTrue(done.await(20, TimeUnit.SECONDS));
    assertTrue(mainThread.get());
    store.awaitIdle();
    return result.get();
  }

  private List<VideoItem> seedSeveral(int count) throws Exception {
    ArrayList<VideoItem> videos = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      VideoItem video = item("queue-" + i);
      seed(video, i + 1, i, 20 + i, i % 3, i % 2, i);
      videos.add(video);
    }
    return videos;
  }

  private static double expectedFor(int index) {
    return expected(index, 20 + index, index % 3, index % 2);
  }

  private static double expected(double score, double exposure, double likes, double dislikes) {
    double base = Math.max(.125, 1 / Math.sqrt(1 + score));
    double affinity = (likes - dislikes) / (16 + Math.max(exposure, likes + dislikes));
    return affinity >= 0 ? base + (1.5 - base) * affinity : base + (base - .0625) * affinity;
  }

  private VideoItem item(String id) {
    return new VideoItem(id, "generated.mp4", "", "", 0, 1000, 0, 0, 640, 360);
  }

  private PlaybackStatsStore open(int cacheCapacity) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    PlaybackStatsStore store =
        new PlaybackStatsStore(context, databaseName, cacheCapacity, executor, clock::get);
    stores.add(store);
    return store;
  }

  private void close(PlaybackStatsStore store) throws Exception {
    store.close();
    stores.remove(store);
  }

  private SQLiteDatabase database() {
    assertTrue(databaseName.startsWith("playback-capacity-instrumentation-"));
    return context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null);
  }

  private void seed(
      VideoItem item,
      long count,
      double score,
      double exposure,
      double likes,
      double dislikes,
      long recencyOffset)
      throws Exception {
    if (!context.getDatabasePath(databaseName).exists()) {
      PlaybackStatsStore empty = open(1);
      empty.awaitIdle();
      close(empty);
    }
    try (SQLiteDatabase db = database()) {
      db.execSQL(
          "INSERT OR REPLACE INTO"
              + " plays(media_key,play_count,last_played,recent_score,score_updated,exposure,likes,dislikes,preference_updated)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          new Object[] {
            PlaybackStatsStore.key(item),
            count,
            START + recencyOffset,
            score,
            START,
            exposure,
            likes,
            dislikes,
            START
          });
    }
  }

  /** These fixtures stay below the common-unit rescaling threshold, so all masses use unit one. */
  private double[] persisted(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT play_count,recent_score,exposure,likes,dislikes,preference_updated"
                    + " FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue("Saved history remains available", row.moveToFirst());
      return new double[] {
        row.getLong(0),
        row.getDouble(1),
        row.getDouble(2),
        row.getDouble(3),
        row.getDouble(4),
        row.getLong(5)
      };
    }
  }

  private long rowCount() {
    try (SQLiteDatabase db = database();
        Cursor row = db.rawQuery("SELECT COUNT(*) FROM plays", null)) {
      assertTrue(row.moveToFirst());
      return row.getLong(0);
    }
  }

  private long rowsFor(VideoItem item) {
    try (SQLiteDatabase db = database();
        Cursor row =
            db.rawQuery(
                "SELECT COUNT(*) FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(row.moveToFirst());
      return row.getLong(0);
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
