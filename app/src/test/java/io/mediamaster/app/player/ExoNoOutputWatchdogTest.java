package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ExoNoOutputWatchdogTest {
  @Test
  public void visibleAttachedNeighborsCanTimeOutOnlyWhileAwaitingTheirFirstFrame() {
    assertTrue(ExoNoOutputWatchdog.canObserve(true, true, false, true));
    assertFalse(ExoNoOutputWatchdog.canObserve(true, true, false, false));
    assertTrue(ExoNoOutputWatchdog.canObserve(true, true, true, false));
    assertFalse(ExoNoOutputWatchdog.canObserve(false, true, false, true));
    assertFalse(ExoNoOutputWatchdog.canObserve(false, true, true, true));
    assertFalse(ExoNoOutputWatchdog.canObserve(true, false, false, true));
    assertFalse(ExoNoOutputWatchdog.canObserve(true, false, true, true));
  }

  @Test
  public void promotingAnUnreadyNeighborDoesNotRestartItsOpeningDeadline() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    assertTrue(ExoNoOutputWatchdog.canObserve(true, true, false, true));
    assertFalse(watch.expired(0, true, false, 0, -1));
    assertTrue(ExoNoOutputWatchdog.canObserve(true, true, true, true));
    assertFalse(watch.expired(7000, true, false, 0, -1));
    assertTrue(watch.expired(8000, true, false, 0, -1));
  }

  @Test
  public void initialOpeningHasItsOwnBound() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    assertFalse(watch.expired(0, true, false, 0, -1));
    assertFalse(watch.expired(7999, true, false, 0, -1));
    assertTrue(watch.expired(8000, true, false, 0, -1));
  }

  @Test
  public void repeatedPendingSeeksDoNotPostponeFailure() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    for (long time = 0; time < 4000; time += 100)
      assertFalse(watch.expired(time, false, true, 12, 1000000));
    assertTrue(watch.expired(4000, false, true, 12, 1000000));
  }

  @Test
  public void repeatedSamePresentationCannotImpersonateNewOutput() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    assertFalse(watch.expired(0, false, true, 12, 1000000));
    assertFalse(watch.expired(3000, false, true, 60, 1000000));
    assertTrue(watch.expired(4000, false, true, 90, 1000000));
  }

  @Test
  public void realOutputRenewsTheIntervalWithoutDependingOnDirection() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    assertFalse(watch.expired(0, false, true, 12, 1000000));
    assertFalse(watch.expired(3900, false, true, 13, 500000));
    assertFalse(watch.expired(7899, false, true, 13, 500000));
    assertTrue(watch.expired(7900, false, true, 13, 500000));
  }

  @Test
  public void completedReadyOrHeldWithoutPendingWorkDoesNotExpire() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    assertFalse(watch.expired(0, false, true, 12, 1000000));
    assertFalse(watch.expired(3500, false, false, 12, 1000000));
    assertFalse(watch.expired(20000, false, false, 12, 1000000));
    assertFalse(watch.expired(21000, false, true, 12, 1000000));
    assertFalse(watch.expired(24999, false, true, 12, 1000000));
    assertTrue(watch.expired(25000, false, true, 12, 1000000));
  }

  @Test
  public void hiddenOrReplacedPageCannotBorrowTheOldDeadline() {
    ExoNoOutputWatchdog watch = new ExoNoOutputWatchdog();
    assertFalse(watch.expired(0, false, true, 12, 1000000));
    watch.reset();
    assertFalse(watch.expired(20000, false, true, 12, 1000000));
    assertFalse(watch.expired(23999, false, true, 12, 1000000));
    assertTrue(watch.expired(24000, false, true, 12, 1000000));
  }
}
