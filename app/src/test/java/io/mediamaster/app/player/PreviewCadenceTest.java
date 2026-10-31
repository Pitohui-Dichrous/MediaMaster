package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PreviewCadenceTest {
  @Test
  public void fastFramesStayResponsiveWithoutDecodingEveryTouchEvent() {
    PreviewCadence cadence = new PreviewCadence();
    cadence.onDecode(20);
    assertEquals(60, cadence.delayMs(1020, 1000, 1020));
    assertEquals(0, cadence.delayMs(1080, 1000, 1020));
  }

  @Test
  public void slowFramesBackOffAndDoNotImmediatelyRecoverAfterOneCheapFrame() {
    PreviewCadence cadence = new PreviewCadence();
    cadence.onDecode(300);
    assertTrue(cadence.delayMs(1300, 1000, 1300) >= 100);
    cadence.onDecode(10);
    assertTrue(cadence.delayMs(2010, 2000, 2010) > 80);
  }

  @Test
  public void verySlowNativeDecodeStillLeavesPlaybackARecoveryWindow() {
    PreviewCadence cadence = new PreviewCadence();
    cadence.onDecode(1300);
    assertEquals(200, cadence.delayMs(2300, 1000, 2300));
    assertEquals(0, cadence.delayMs(2500, 1000, 2300));
  }

  @Test
  public void switchingVideoResetsPreviousSlowVideoPenalty() {
    PreviewCadence cadence = new PreviewCadence();
    cadence.onDecode(2000);
    cadence.reset();
    assertEquals(60, cadence.delayMs(1020, 1000, 1020));
  }
}
