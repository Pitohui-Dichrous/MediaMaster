package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlaybackWatchTrackerTest {
  @Test
  public void thresholdIsStrictlyGreaterThanThreeSecondsAndEmitsOnce() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    assertFalse(tracker.update(true, 100));
    assertFalse(tracker.update(true, 3100));
    assertEquals(3000, tracker.watchedMs());
    assertTrue(tracker.update(true, 3101));
    assertTrue(tracker.recorded());
    assertFalse(tracker.update(true, 100_000));
    assertFalse(tracker.update(false, 100_001));
  }

  @Test
  public void pauseSeekAndBackgroundIntervalsDoNotCountButQualifiedIntervalsAccumulate() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    tracker.update(true, 0);
    assertFalse(tracker.update(false, 1400));
    assertFalse(tracker.update(false, 100_000));
    assertEquals(1400, tracker.watchedMs());
    assertFalse(tracker.update(true, 100_000));
    assertFalse(tracker.update(false, 101_600));
    assertEquals(3000, tracker.watchedMs());
    assertFalse(tracker.update(true, 1_000_000));
    assertTrue(tracker.update(false, 1_000_001));
  }

  @Test
  public void newEntryResetsAndShortLoopsCanAccumulateWithinTheSameEntry() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    for (int loop = 0; loop < 3; loop++) {
      tracker.update(true, loop * 2000L);
      assertFalse(tracker.update(false, loop * 2000L + 1000));
    }
    tracker.update(true, 6000);
    assertTrue(tracker.update(false, 6001));
    tracker.reset();
    assertEquals(0, tracker.watchedMs());
    assertFalse(tracker.recorded());
    assertFalse(tracker.update(true, 9000));
    assertFalse(tracker.update(false, 9500));
  }

  @Test
  public void restorationKeepsElapsedViewingButNeverUsesAnOldRunningAnchor() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    tracker.restore(2000, false);
    assertFalse(tracker.update(false, 100_000));
    assertFalse(tracker.update(true, 100_000));
    assertFalse(tracker.update(true, 101_000));
    assertTrue(tracker.update(true, 101_001));
    tracker.restore(tracker.watchedMs(), tracker.recorded());
    assertFalse(tracker.update(true, Long.MAX_VALUE));
  }

  @Test
  public void clockAnomaliesNeverInventViewingAndExtremeTimesCannotOverflow() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    tracker.update(true, 10_000);
    assertFalse(tracker.update(true, 9000));
    assertEquals(0, tracker.watchedMs());
    assertFalse(tracker.update(false, 9500));
    assertEquals(500, tracker.watchedMs());
    assertFalse(tracker.update(true, -1));
    assertFalse(tracker.update(true, Long.MAX_VALUE - 500));
    assertFalse(tracker.update(false, Long.MAX_VALUE));
    assertEquals(1000, tracker.watchedMs());
    tracker.restore(Long.MIN_VALUE, false);
    assertEquals(0, tracker.watchedMs());
    tracker.update(true, 0);
    assertTrue(tracker.update(false, Long.MAX_VALUE));
    assertEquals(3001, tracker.watchedMs());
    tracker.restore(Long.MAX_VALUE, false);
    assertTrue(tracker.update(false, 0));
  }
}
