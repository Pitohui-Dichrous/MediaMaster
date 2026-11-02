package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.media.VideoItem;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Automatic decisions use only this test's unique database, never the user's singleton. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackAutomaticVoteTest {
  private Context context;
  private String databaseName;
  private PlaybackStatsStore store;
  private ExecutorService worker;
  private final ArrayList<CountDownLatch> gates = new ArrayList<>();
  private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

  @Before
  public void openOwnedDatabase() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    databaseName = "playback-auto-vote-test-" + UUID.randomUUID() + ".db";
    assertFalse(context.getDatabasePath(databaseName).exists());
    worker = Executors.newSingleThreadExecutor();
    store = new PlaybackStatsStore(context, databaseName, 100, worker, clock::get);
    store.awaitIdle();
  }

  @After
  public void closeOwnedDatabase() throws Exception {
    for (CountDownLatch gate : gates) gate.countDown();
    try {
      if (store != null) store.close();
    } finally {
      if (worker != null) worker.shutdownNow();
      if (context != null && databaseName != null) {
        assertTrue(databaseName.startsWith("playback-auto-vote-test-"));
        context.deleteDatabase(databaseName);
      }
    }
  }

  @Test
  public void hiddenDecisionIsOncePerVideoAndWindowWithoutSelectingButton() throws Exception {
    VideoItem a = item("automatic-a"), b = item("automatic-b");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(a, -1));
    assertTrue(session.applyAutomaticVote(b, -1));
    assertFalse(session.applyAutomaticVote(a, -1));
    assertEquals(0, session.vote(a));
    assertEquals(0, session.vote(b));
    assertArrayEquals(new double[] {0, 1}, masses(a), 0);
    assertEquals(0, store.playCount(a));
    PlaybackVoteSession nextWindow = new PlaybackVoteSession(store);
    assertTrue(nextWindow.applyAutomaticVote(a, -1));
    assertArrayEquals(new double[] {0, 2}, masses(a), 0);
  }

  @Test
  public void sameSignManualVoteIsIndependentAndOnlyItsOwnMassCanBeToggled() throws Exception {
    VideoItem video = item("independent-dislike");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(video, -1));
    assertTrue(session.setVote(video, -1));
    assertEquals(-1, session.vote(video));
    assertArrayEquals(new double[] {0, 2}, masses(video), 0);
    assertTrue(session.setVote(video, 0));
    assertArrayEquals(new double[] {0, 1}, masses(video), 0);
    assertFalse(session.applyAutomaticVote(video, -1));
  }

  @Test
  public void manualLikeCancelsQuickDislikeAndPreventsReawardAfterUnselecting() throws Exception {
    VideoItem video = item("manual-overrides");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(video, -1));
    assertTrue(session.setVote(video, 1));
    assertArrayEquals(new double[] {1, 0}, masses(video), 0);
    assertTrue(session.setVote(video, 0));
    assertEquals(0, session.vote(video));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertArrayEquals(new double[] {0, 0}, masses(video), 0);
  }

  @Test
  public void oppositeManualIntentBeforeThresholdAlsoSuppressesTheExtraSlot() throws Exception {
    VideoItem video = item("manual-first");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 1));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertTrue(session.setVote(video, 0));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertArrayEquals(new double[] {0, 0}, masses(video), 0);
  }

  @Test
  public void plainManualNeutralDoesNotSpendAnUnseenExtraSlot() throws Exception {
    VideoItem video = item("manual-neutral");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 0));
    assertEquals(0, rowsFor(video));
    assertTrue(session.applyAutomaticVote(video, -1));
    assertEquals(0, session.vote(video));
    assertArrayEquals(new double[] {0, 1}, masses(video), 0);
  }

  @Test
  public void searchRoundDeduplicatesAcrossReopenedPlayersButNotNewSearches() throws Exception {
    VideoItem a = item("search-a"), b = item("search-b");
    String id = SearchFeedbackRounds.create(store);
    try {
      PlaybackVoteSession first = searchSession(id), reopened = searchSession(id);
      assertTrue(first.applyAutomaticVote(a, 1));
      assertFalse(reopened.applyAutomaticVote(a, 1));
      assertTrue(reopened.applyAutomaticVote(b, 1));
      assertEquals(0, first.vote(a));
      assertEquals(0, reopened.vote(a));
      assertEquals(0, store.playCount(a));
      assertArrayEquals(new double[] {1, 0}, masses(a), 0);
      String nextId = SearchFeedbackRounds.create(store);
      try {
        assertTrue(searchSession(nextId).applyAutomaticVote(a, 1));
        assertArrayEquals(new double[] {2, 0}, masses(a), 0);
      } finally {
        SearchFeedbackRounds.forget(nextId);
      }
    } finally {
      SearchFeedbackRounds.forget(id);
    }
  }

  @Test
  public void manualDislikeInLaterPlayerCancelsSharedSearchExtraPermanentlyForRound()
      throws Exception {
    VideoItem video = item("search-cancel");
    String id = SearchFeedbackRounds.create(store);
    try {
      PlaybackVoteSession first = searchSession(id), reopened = searchSession(id);
      assertTrue(first.applyAutomaticVote(video, 1));
      assertTrue(reopened.setVote(video, -1));
      assertArrayEquals(new double[] {0, 1}, masses(video), 0);
      assertTrue(reopened.setVote(video, 0));
      assertFalse(first.applyAutomaticVote(video, 1));
      assertFalse(searchSession(id).applyAutomaticVote(video, 1));
      assertArrayEquals(new double[] {0, 0}, masses(video), 0);
    } finally {
      SearchFeedbackRounds.forget(id);
    }
  }

  @Test
  public void searchManualLikeAndExtraLikeBothCountButManualToggleLeavesExtra() throws Exception {
    VideoItem video = item("search-like");
    PlaybackVoteSession session = positiveSession();
    assertTrue(session.setVote(video, 1));
    assertTrue(session.applyAutomaticVote(video, 1));
    assertArrayEquals(new double[] {2, 0}, masses(video), 0);
    assertTrue(session.setVote(video, 0));
    assertArrayEquals(new double[] {1, 0}, masses(video), 0);
    assertFalse(session.applyAutomaticVote(video, 1));
  }

  @Test
  public void searchDislikeBeforeThresholdCannotBeUndoneByLaterPassiveViewing() throws Exception {
    VideoItem video = item("search-opposed");
    PlaybackVoteSession session = positiveSession();
    assertTrue(session.setVote(video, -1));
    assertTrue(session.setVote(video, 0));
    assertFalse(session.applyAutomaticVote(video, 1));
    assertArrayEquals(new double[] {0, 0}, masses(video), 0);
  }

  @Test
  public void renamedExtraAndItsCancellationFollowOldAndNewQueueReferences() throws Exception {
    VideoItem old = item("rename-old"), renamed = item("rename-new"), newest = item("rename-last");
    PlaybackVoteSession session = positiveSession();
    assertTrue(session.applyAutomaticVote(old, 1));
    store.migrate(old, renamed);
    store.awaitIdle();
    assertFalse(session.applyAutomaticVote(renamed, 1));
    assertFalse(session.applyAutomaticVote(old, 1));
    assertTrue(session.setVote(renamed, -1));
    store.migrate(renamed, newest);
    store.awaitIdle();
    assertFalse(session.applyAutomaticVote(old, 1));
    assertFalse(session.applyAutomaticVote(renamed, 1));
    assertFalse(session.applyAutomaticVote(newest, 1));
    assertArrayEquals(new double[] {0, 1}, masses(newest), 0);
    assertEquals(-1, session.vote(newest));
  }

  @Test
  public void zeroMassSuppressionFollowsRenameWithoutCreatingARecord() throws Exception {
    VideoItem old = item("neutral-old"), renamed = item("neutral-renamed");
    PlaybackExtraFeedbackSession scope = new PlaybackExtraFeedbackSession(store, 1);
    assertTrue(scope.onManualVote(old, -1));
    assertEquals(0, rowsFor(old));
    store.migrate(old, renamed);
    store.awaitIdle();
    PlaybackVoteSession session = new PlaybackVoteSession(store, scope);
    assertFalse(session.applyAutomaticVote(old, 1));
    assertFalse(session.applyAutomaticVote(renamed, 1));
    assertEquals(0, rowsFor(renamed));
  }

  @Test
  public void resetClearsHiddenHistoryButRejectsPendingAndStaleEvents() throws Exception {
    VideoItem video = item("reset");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    Object oldGeneration = store.voteGeneration();
    assertTrue(session.applyAutomaticVote(video, -1));
    store.awaitIdle();
    CountDownLatch release = blockWorker();
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    store.resetWeights(
        ok -> {
          success.set(ok);
          done.countDown();
        });
    assertFalse(session.applyAutomaticVote(video, -1));
    assertFalse(session.setVote(video, 1));
    release.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(success.get());
    assertFalse(session.applyAutomaticVote(video, -1, oldGeneration));
    assertNull(store.applyVote(PlaybackStatsStore.key(video), null, -1, oldGeneration));
    assertTrue(session.applyAutomaticVote(video, -1));
    assertArrayEquals(new double[] {0, 1}, masses(video), 0);
    assertEquals(0, session.vote(video));
  }

  @Test
  public void oppositeManualVoteWithdrawsOnlyItsOwnDecayedSearchExtra() throws Exception {
    VideoItem video = item("decay");
    PlaybackVoteSession earlier = positiveSession(), current = positiveSession();
    assertTrue(earlier.setVote(video, 1));
    assertTrue(current.applyAutomaticVote(video, 1));
    clock.addAndGet(RecentPlaybackPolicy.HALF_LIFE_MS);
    assertTrue(current.setVote(video, -1));
    assertArrayEquals(new double[] {.5, 1}, masses(video), 1e-12);
    assertFalse(current.applyAutomaticVote(video, 1));
  }

  @Test
  public void evictedExtraRemainsConsumedAndItsCancellationDoesNotResurrectIt() throws Exception {
    store.close();
    worker.shutdownNow();
    worker = Executors.newSingleThreadExecutor();
    store = new PlaybackStatsStore(context, databaseName, 2, worker, clock::get, 262_144);
    store.awaitIdle();
    VideoItem victim = item("evicted-auto");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(victim, -1));
    store.awaitIdle();
    for (int from = 0; rowsFor(victim) != 0 && from < 4096; from += 128) {
      CountDownLatch release = blockWorker();
      try {
        for (int i = from; i < from + 128; i++) {
          clock.incrementAndGet();
          store.recordPlay(item("replacement-" + i));
        }
      } finally {
        release.countDown();
      }
      store.awaitIdle();
    }
    assertEquals(0, rowsFor(victim));
    assertFalse(session.applyAutomaticVote(victim, -1));
    assertTrue(session.setVote(victim, 0));
    assertEquals(0, rowsFor(victim));
    VideoItem renamed = item("evicted-renamed");
    store.migrate(victim, renamed);
    store.awaitIdle();
    assertFalse(session.applyAutomaticVote(renamed, -1));
    assertFalse(session.applyAutomaticVote(victim, -1));
    assertEquals(0, rowsFor(renamed));
  }

  @Test
  public void unknownOrForgottenSearchRoundFailsClosedButManualFeedbackStillWorks()
      throws Exception {
    VideoItem video = item("restored");
    String id = SearchFeedbackRounds.create(store);
    PlaybackVoteSession retained = searchSession(id);
    SearchFeedbackRounds.forget(id);
    PlaybackVoteSession restored = searchSession(id);
    assertFalse(restored.applyAutomaticVote(video, 1));
    assertTrue(restored.setVote(video, 1));
    assertTrue(retained.applyAutomaticVote(video, 1));
    assertArrayEquals(new double[] {2, 0}, masses(video), 0);
  }

  @Test
  public void sharedRoundCannotBeDoubleAwardedByConcurrentWindows() throws Exception {
    VideoItem video = item("concurrent");
    PlaybackExtraFeedbackSession scope = new PlaybackExtraFeedbackSession(store, 1);
    PlaybackVoteSession first = new PlaybackVoteSession(store, scope);
    PlaybackVoteSession second = new PlaybackVoteSession(store, scope);
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<Boolean> one =
          callers.submit(() -> first.applyAutomaticVote(video, 1));
      java.util.concurrent.Future<Boolean> two =
          callers.submit(() -> second.applyAutomaticVote(video, 1));
      assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
    } finally {
      callers.shutdownNow();
    }
    assertArrayEquals(new double[] {1, 0}, masses(video), 0);
  }

  @Test
  public void deletionRemovesAllWeightsAndInvalidatesLiveManualAndExtraReferences()
      throws Exception {
    VideoItem video = item("delete-all");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    store.recordPlay(video);
    assertTrue(session.applyAutomaticVote(video, -1));
    assertTrue(session.setVote(video, -1));
    store.remove(video);
    store.recordPlay(video); // queued/stale entry must not restore a confirmed deletion
    assertFalse(session.setVote(video, 1));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertEquals(0, session.vote(video));
    assertEquals(0, store.playCount(video));
    assertEquals(1, store.weight(video), 0);
    assertEquals(0, rowsFor(video));
    reopenStore();
    assertEquals(0, store.playCount(video));
    assertEquals(0, rowsFor(video));
  }

  @Test
  public void deletingRenamedVideoAlsoInvalidatesPreRenameWindowReferences() throws Exception {
    VideoItem old = item("delete-old"), renamed = item("delete-renamed");
    PlaybackVoteSession session = positiveSession();
    assertTrue(session.applyAutomaticVote(old, 1));
    store.migrate(old, renamed);
    store.awaitIdle();
    store.remove(renamed);
    assertFalse(session.setVote(old, 1));
    assertFalse(session.applyAutomaticVote(renamed, 1));
    store.recordPlay(old);
    assertEquals(0, rowsFor(old));
    assertEquals(0, rowsFor(renamed));
  }

  @Test
  public void deletionWinsOverColdHydrationPendingViewAndPendingRename() throws Exception {
    VideoItem old = item("pending-old"), renamed = item("pending-new");
    store.recordPlay(old);
    store.awaitIdle();
    CountDownLatch release = blockWorker();
    store.migrate(old, renamed);
    store.recordPlay(old);
    store.remove(renamed);
    release.countDown();
    assertEquals(0, rowsFor(old));
    assertEquals(0, rowsFor(renamed));
  }

  @Test
  public void deletionCancelsARealColdRowHydrationWithoutMergingItsPendingView() throws Exception {
    VideoItem cold = item("cold-delete"), newest = item("newest");
    store.recordPlay(cold);
    clock.incrementAndGet();
    store.recordPlay(newest);
    store.awaitIdle();
    store.close();
    worker.shutdownNow();
    worker = Executors.newSingleThreadExecutor();
    store = new PlaybackStatsStore(context, databaseName, 1, worker, clock::get);
    store.awaitIdle();
    CountDownLatch release = blockWorker();
    store.recordPlay(cold);
    store.remove(cold);
    release.countDown();
    assertEquals(0, rowsFor(cold));
    assertEquals(0, store.playCount(cold));
    assertEquals(1, store.playCount(newest));
  }

  @Test
  public void deletionQueuedDuringResetCannotBeLostOrRevivedByDeferredViews() throws Exception {
    VideoItem video = item("delete-during-reset");
    store.recordPlay(video);
    store.awaitIdle();
    CountDownLatch release = blockWorker(), done = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    store.resetWeights(
        ok -> {
          success.set(ok);
          done.countDown();
        });
    store.recordPlay(video);
    store.remove(video);
    store.recordPlay(video);
    release.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(success.get());
    assertEquals(0, rowsFor(video));
    assertEquals(0, store.playCount(video));
  }

  @Test
  public void invalidAutomaticStatesAndWrongScopeCannotCreateRecords() throws Exception {
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    for (int vote : new int[] {-2, 0, 2}) {
      try {
        session.applyAutomaticVote(item("invalid"), vote);
        fail("Expected invalid inference");
      } catch (IllegalArgumentException expected) {
      }
    }
    assertFalse(session.applyAutomaticVote(item("wrong-scope"), 1));
    assertEquals(0, rowsFor(item("wrong-scope")));
  }

  private PlaybackVoteSession positiveSession() {
    return new PlaybackVoteSession(store, new PlaybackExtraFeedbackSession(store, 1));
  }

  private PlaybackVoteSession searchSession(String id) {
    return new PlaybackVoteSession(store, SearchFeedbackRounds.resolve(store, id));
  }

  private CountDownLatch blockWorker() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    gates.add(release);
    worker.execute(
        () -> {
          started.countDown();
          try {
            release.await(15, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    return release;
  }

  private void reopenStore() throws Exception {
    store.close();
    worker.shutdownNow();
    worker = Executors.newSingleThreadExecutor();
    store = new PlaybackStatsStore(context, databaseName, 100, worker, clock::get);
    store.awaitIdle();
  }

  private double[] masses(VideoItem item) throws Exception {
    store.awaitIdle();
    try (SQLiteDatabase db =
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null);
        Cursor row =
            db.rawQuery(
                "SELECT likes,dislikes,mass_exponent FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(row.moveToFirst());
      return new double[] {
        Math.scalb(row.getDouble(0), row.getInt(2)), Math.scalb(row.getDouble(1), row.getInt(2))
      };
    }
  }

  private long rowsFor(VideoItem item) throws Exception {
    store.awaitIdle();
    try (SQLiteDatabase db =
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null);
        Cursor row =
            db.rawQuery(
                "SELECT COUNT(*) FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(item)})) {
      assertTrue(row.moveToFirst());
      return row.getLong(0);
    }
  }

  private VideoItem item(String id) {
    return new VideoItem(id, "generated.mp4", "", "", 0, 1000, 0, 0, 640, 360);
  }
}
