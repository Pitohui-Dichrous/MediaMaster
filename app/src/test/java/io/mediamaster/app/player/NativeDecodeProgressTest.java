package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeDecodeProgressTest {
  @Test
  public void slowButContinuingDecodeKeepsSoftwareEvenBeforeAnyOutput() {
    NativeDecodeProgress progress = new NativeDecodeProgress();
    progress.reset(0, 1);
    progress.observe(0, 100, 50, 1);
    // One decoded picture every five seconds is poor throughput, but not inability to decode.
    for (int i = 1; i <= 20; i++) {
      long now = i * 5000L;
      progress.observe(now, 100 + i, 50, 1);
      assertTrue(progress.deadline(10000) > now);
    }
    assertEquals(110000, progress.deadline(10000));
  }

  @Test
  public void unchangedOrUnavailableCountersCannotKeepAStalledDecoderAlive() {
    NativeDecodeProgress progress = new NativeDecodeProgress();
    progress.reset(100, 1);
    progress.observe(200, 100, 50, 1);
    progress.observe(5000, 100, 50, 1);
    progress.observe(10000, -1, -1, 1);
    assertEquals(10100, progress.deadline(10000));
  }

  @Test
  public void actualOutputIsProgressWhenNativeStatsAreUnavailable() {
    NativeDecodeProgress progress = new NativeDecodeProgress();
    progress.reset(100, 1);
    progress.observe(9000, -1, -1, 2);
    assertEquals(19000, progress.deadline(10000));
  }

  @Test
  public void counterResetAndANewSeekCannotBorrowOldProgress() {
    NativeDecodeProgress progress = new NativeDecodeProgress();
    progress.reset(0, 1);
    progress.observe(100, 100, 50, 1);
    progress.observe(500, 101, 51, 2);
    progress.observe(1000, 0, 0, 2);
    assertEquals(10500, progress.deadline(10000));
    progress.reset(2000, 2);
    progress.observe(2100, 200, 150, 2);
    assertEquals(12000, progress.deadline(10000));
  }
}
