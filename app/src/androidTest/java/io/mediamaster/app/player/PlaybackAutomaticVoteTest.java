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
  public void automaticDecisionIsOncePerVideoAndWindowNotOncePerVisit() throws Exception {
    VideoItem a = item("automatic-a"), b = item("automatic-b");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(a, -1));
    assertTrue(session.applyAutomaticVote(b, 1));
    assertFalse(session.applyAutomaticVote(a, -1));
    assertFalse(session.applyAutomaticVote(a, 1));
    assertEquals(-1, session.vote(a));
    assertArrayEquals(new double[] {0, 1}, masses(a), 0);
    assertArrayEquals(new double[] {1, 0}, masses(b), 0);
    assertEquals(0, store.playCount(a));
    assertEquals(0, store.playCount(b));
    PlaybackVoteSession nextWindow = new PlaybackVoteSession(store);
    assertTrue(nextWindow.applyAutomaticVote(a, -1));
    assertArrayEquals(new double[] {0, 2}, masses(a), 0);
  }

  @Test
  public void manualChoiceAndCancellationAlwaysTakePrecedence() throws Exception {
    VideoItem video = item("manual-overrides");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(video, -1));
    assertTrue(session.setVote(video, 1));
    assertArrayEquals(new double[] {1, 0}, masses(video), 0);
    assertFalse(session.applyAutomaticVote(video, -1));
    assertTrue(session.setVote(video, 0));
    assertEquals(0, session.vote(video));
    assertFalse(session.applyAutomaticVote(video, 1));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertArrayEquals(new double[] {0, 0}, masses(video), 0);
    assertTrue(session.setVote(video, -1));
    assertTrue(session.setVote(video, -1));
    assertArrayEquals(new double[] {0, 1}, masses(video), 0);
  }

  @Test
  public void explicitNeutralChoiceWithoutPriorFeedbackAlsoBlocksAutomaticVotes() throws Exception {
    VideoItem video = item("manual-neutral");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 0));
    assertTrue(session.setVote(video, 0));
    assertFalse(session.applyAutomaticVote(video, 1));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertEquals(0, rowsFor(video));
    assertEquals(0, store.playCount(video));
  }

  @Test
  public void neutralDecisionWithoutAnAggregateFollowsRenameWithoutCreatingRows() throws Exception {
    VideoItem old = item("neutral-old"), renamed = item("neutral-renamed");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(old, 0));
    store.migrate(old, renamed);
    store.awaitIdle();
    assertFalse(session.applyAutomaticVote(old, 1));
    assertFalse(session.applyAutomaticVote(renamed, -1));
    assertEquals(0, rowsFor(old));
    assertEquals(0, rowsFor(renamed));
    assertTrue(session.setVote(renamed, 1));
    assertArrayEquals(new double[] {1, 0}, masses(renamed), 0);
    assertEquals(0, rowsFor(old));
  }

  @Test
  public void manualLikeBeforeAutomaticDislikeCannotBeOverwritten() throws Exception {
    VideoItem video = item("manual-first");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.setVote(video, 1));
    assertFalse(session.applyAutomaticVote(video, -1));
    assertFalse(session.applyAutomaticVote(video, 1));
    assertEquals(1, session.vote(video));
    assertArrayEquals(new double[] {1, 0}, masses(video), 0);
  }

  @Test
  public void renamedAndCancelledContributionStillBlocksOldAndNewQueueReferences()
      throws Exception {
    VideoItem old = item("rename-old"), renamed = item("rename-new"), newest = item("rename-last");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(old, 1));
    store.migrate(old, renamed);
    store.awaitIdle();
    assertFalse(session.applyAutomaticVote(renamed, 1));
    assertFalse(session.applyAutomaticVote(old, -1));
    assertTrue(session.setVote(renamed, 0));
    store.migrate(renamed, newest);
    store.awaitIdle();
    assertFalse(session.applyAutomaticVote(old, 1));
    assertFalse(session.applyAutomaticVote(renamed, -1));
    assertFalse(session.applyAutomaticVote(newest, 1));
    assertArrayEquals(new double[] {0, 0}, masses(newest), 0);
  }

  @Test
  public void resetClearsAutomaticHistoryButCannotAcceptFeedbackWhilePending() throws Exception {
    VideoItem video = item("reset");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(video, 1));
    store.awaitIdle();
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
    store.awaitIdle();
    assertEquals(0, session.vote(video));
    assertTrue(session.applyAutomaticVote(video, -1));
    assertArrayEquals(new double[] {0, 1}, masses(video), 0);
  }

  @Test
  public void cancellingDecayedAutomaticLikeDoesNotRemoveOtherWindowsMass() throws Exception {
    VideoItem video = item("decay");
    PlaybackVoteSession earlier = new PlaybackVoteSession(store);
    assertTrue(earlier.setVote(video, 1));
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    assertTrue(session.applyAutomaticVote(video, 1));
    clock.addAndGet(RecentPlaybackPolicy.HALF_LIFE_MS);
    assertTrue(session.setVote(video, 0));
    assertFalse(session.applyAutomaticVote(video, 1));
    assertArrayEquals(new double[] {.5, 0}, masses(video), 1e-12);
    assertTrue(session.setVote(video, -1));
    assertArrayEquals(new double[] {.5, 1}, masses(video), 1e-12);
  }

  @Test
  public void staleAutomaticEventIsRejectedAtBothSessionAndAtomicStoreBoundary() throws Exception {
    VideoItem video = item("stale-generation");
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    Object oldGeneration = store.voteGeneration();
    assertTrue(session.applyAutomaticVote(video, 1, oldGeneration));
    store.awaitIdle();
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    store.resetWeights(
        ok -> {
          success.set(ok);
          done.countDown();
        });
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(success.get());
    assertNotSame(oldGeneration, store.voteGeneration());
    assertFalse(session.applyAutomaticVote(video, -1, oldGeneration));
    // Simulate a reset completing after the caller's precheck but before the actual write.
    assertNull(store.applyVote(PlaybackStatsStore.key(video), null, -1, oldGeneration));
    assertArrayEquals(new double[] {0, 0}, masses(video), 0);
    assertTrue(session.applyAutomaticVote(video, -1, store.voteGeneration()));
    assertArrayEquals(new double[] {0, 1}, masses(video), 0);
  }

  @Test
  public void cancellingEvictedFeedbackDoesNotRecreateItsRowOrAllowAutomaticReentry()
      throws Exception {
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
    assertEquals(0, session.vote(victim));
    assertTrue(session.setVote(victim, 0));
    assertFalse(session.applyAutomaticVote(victim, 1));
    assertFalse(session.applyAutomaticVote(victim, -1));
    assertEquals(0, rowsFor(victim));
  }

  @Test
  public void invalidAutomaticStatesAreRejectedWithoutCreatingRecords() {
    PlaybackVoteSession session = new PlaybackVoteSession(store);
    for (int vote : new int[] {-2, 0, 2}) {
      try {
        session.applyAutomaticVote(item("invalid"), vote);
        fail("Expected invalid automatic vote");
      } catch (IllegalArgumentException expected) {
      }
    }
    assertEquals(0, session.vote(item("invalid")));
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
