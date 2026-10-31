package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class SoftwareDecodeHealthPolicyTest {
  @Test
  public void needsTwoCompletePoorWindows() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    assertFalse(p.update(0, 10, 10, 0, 25));
    assertFalse(p.update(1000, 20, 20, 0, 25));
    assertFalse(p.update(2000, 30, 30, 0, 25));
    assertTrue(p.update(4000, 50, 50, 0, 25));
  }

  @Test
  public void sustainedFrameLossTriggersEvenWhenDisplayedFpsIsAcceptable() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25);
    assertFalse(p.update(2000, 70, 60, 10, 25));
    assertTrue(p.update(4000, 130, 110, 20, 25));
  }

  @Test
  public void normalPlaybackAndTenPercentLossAreAccepted() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25);
    assertFalse(p.update(2000, 70, 64, 6, 25));
    assertFalse(p.update(4000, 130, 118, 12, 25));
  }

  @Test
  public void healthyWindowBreaksTheFailureSequence() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25);
    assertFalse(p.update(2000, 30, 30, 0, 25));
    assertFalse(p.update(4000, 80, 80, 0, 25));
    assertFalse(p.update(6000, 100, 100, 0, 25));
  }

  @Test
  public void seekOrPauseResetDiscardsOldWindows() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25);
    p.update(2000, 30, 30, 0, 25);
    p.reset();
    assertFalse(p.update(3000, 100, 100, 0, 25));
    assertFalse(p.update(5000, 120, 120, 0, 25));
  }

  @Test
  public void UnchangedOrMissingCountersAreNotPerformanceEvidence() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    assertFalse(p.update(0, 0, 0, 0, 25));
    p.update(100, 10, 10, 0, 25);
    p.update(2100, 30, 30, 0, 25);
    assertFalse(p.update(4100, 30, 30, 0, 25));
    assertFalse(p.update(6100, 50, 50, 0, 25));
  }

  @Test
  public void counterResetOrLongSchedulingGapStartsANewBaseline() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25);
    p.update(2000, 30, 30, 0, 25);
    assertFalse(p.update(4000, 5, 5, 0, 25));
    assertFalse(p.update(6000, 25, 25, 0, 25));
    assertFalse(p.update(13000, 45, 45, 0, 25));
    assertFalse(p.update(15000, 65, 65, 0, 25));
  }

  @Test
  public void unknownFrameRateCannotTriggerFallback() {
    for (float fps : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
      SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
      assertFalse(p.update(0, 10, 10, 0, fps));
      assertFalse(p.update(2000, 30, 11, 19, fps));
      assertFalse(p.update(4000, 50, 12, 38, fps));
    }
  }
}
