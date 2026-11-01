package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeSeekQueueTest {
  @Test
  public void firstRequestIsImmediateAndHealthyAcknowledgementDoesNotAddADebounce() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(4200, false);
    NativeSeekQueue.Request first = queue.take(100);
    assertNotNull(first);
    assertEquals(4200, first.positionMs);
    assertFalse(first.fast);
    assertTrue(queue.acknowledge(first.id));
    queue.offer(18720, false);
    assertEquals(18720, queue.take(101).positionMs);
  }

  @Test
  public void sustainedTwelveHertzInputKeepsOnlyLatestTargetWithoutTimedReflush() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(4200, false);
    NativeSeekQueue.Request first = queue.take(0);
    long last = 0;
    for (int tap = 1; tap <= 360; tap++) {
      last = tap * 7919L % 30000;
      queue.offer(last, false);
      assertNull(
          "The decoder is still processing its single submitted request", queue.take(tap * 83));
      assertSame(first, queue.submitted());
      assertEquals(last, queue.latestPositionMs());
    }
    assertTrue(queue.isStalled(30000, 1500));
    assertTrue(queue.acknowledge(first.id));
    NativeSeekQueue.Request finalTarget = queue.take(30001);
    assertEquals(last, finalTarget.positionMs);
    assertFalse(finalTarget.fast);
    assertFalse(queue.hasPending());
    assertNull(queue.take(60000));
  }

  @Test
  public void finalAccurateTargetSurvivesAnInFlightFastRequestAtTheSamePosition() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(6200, true);
    NativeSeekQueue.Request coarse = queue.take(10);
    queue.offer(6200, false);
    assertTrue(queue.hasPending());
    assertTrue(queue.acknowledge(coarse.id));
    NativeSeekQueue.Request precise = queue.take(11);
    assertEquals(6200, precise.positionMs);
    assertFalse(precise.fast);
  }

  @Test
  public void cancelRestoresOriginInsteadOfLettingAnOlderPendingTargetRenderLater() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(18720, true);
    NativeSeekQueue.Request coarse = queue.take(10);
    queue.offer(24000, true);
    queue.offer(4200, false);
    queue.acknowledge(coarse.id);
    NativeSeekQueue.Request cancelled = queue.take(20);
    assertEquals(4200, cancelled.positionMs);
    assertFalse(cancelled.fast);
    assertFalse(queue.hasPending());
  }

  @Test
  public void latestIntentCanReturnToAnAlreadySubmittedAccurateTarget() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(4200, false);
    NativeSeekQueue.Request first = queue.take(0);
    queue.offer(6200, false);
    queue.offer(4200, true);
    assertFalse(queue.hasPending());
    assertEquals(4200, queue.latestPositionMs());
    queue.acknowledge(first.id);
    assertFalse(queue.hasWork());
    assertNull(queue.take(1));
  }

  @Test
  public void aStaleFrameCannotReleaseTheNextRequestOrAReplacementPlayer() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(4200, false);
    NativeSeekQueue.Request old = queue.take(0);
    queue.reset();
    queue.offer(6200, false);
    NativeSeekQueue.Request current = queue.take(10);
    assertFalse(queue.acknowledge(old.id));
    assertSame(current, queue.submitted());
    assertTrue(queue.acknowledge(current.id));
    assertFalse(queue.acknowledge(current.id));
    assertEquals(-1, queue.latestPositionMs());
  }

  @Test
  public void latestIntentKeepsExplicitZeroAndResetDropsAllWork() {
    NativeSeekQueue queue = new NativeSeekQueue();
    queue.offer(18720, false);
    queue.take(100);
    queue.offer(0, false);
    assertEquals(0, queue.latestPositionMs());
    queue.reset();
    assertFalse(queue.hasWork());
    assertFalse(queue.isStalled(100000, 100));
    assertNull(queue.take(100000));
  }
}
