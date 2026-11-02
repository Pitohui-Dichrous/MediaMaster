package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class SoftwareDecodeHealthPolicyTest {
  @Test
  public void needsTwoCompletePoorWindows() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    assertFalse(p.update(0, 10, 10, 0, 25, 60, 0));
    assertFalse(p.update(1000, 20, 20, 0, 25, 60, 500));
    assertFalse(p.update(2000, 30, 30, 0, 25, 60, 1000));
    assertTrue(p.update(4000, 50, 50, 0, 25, 60, 2000));
  }

  @Test
  public void sustainedFrameLossTriggersEvenWhenDisplayedFpsIsAcceptable() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25, 60, 0);
    assertFalse(p.update(2000, 70, 60, 10, 25, 60, 1000));
    assertTrue(p.update(4000, 130, 110, 20, 25, 60, 2000));
  }

  @Test
  public void normalPlaybackAndTenPercentLossAreAccepted() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25, 60, 0);
    assertFalse(p.update(2000, 70, 64, 6, 25, 60, 1000));
    assertFalse(p.update(4000, 130, 118, 12, 25, 60, 2000));
  }

  @Test
  public void healthyWindowBreaksTheFailureSequence() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25, 60, 0);
    assertFalse(p.update(2000, 30, 30, 0, 25, 60, 1000));
    assertFalse(p.update(4000, 80, 80, 0, 25, 60, 2000));
    assertFalse(p.update(6000, 100, 100, 0, 25, 60, 3000));
  }

  @Test
  public void seekOrPauseResetDiscardsOldWindows() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25, 60, 0);
    p.update(2000, 30, 30, 0, 25, 60, 1000);
    p.reset();
    assertFalse(p.update(3000, 100, 100, 0, 25, 60, 1500));
    assertFalse(p.update(5000, 120, 120, 0, 25, 60, 2500));
  }

  @Test
  public void UnchangedOrMissingCountersAreNotPerformanceEvidence() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    assertFalse(p.update(0, 0, 0, 0, 25, 60, 0));
    p.update(100, 10, 10, 0, 25, 60, 50);
    p.update(2100, 30, 30, 0, 25, 60, 1050);
    assertFalse(p.update(4100, 30, 30, 0, 25, 60, 2050));
    assertFalse(p.update(6100, 50, 50, 0, 25, 60, 3050));
  }

  @Test
  public void counterResetOrLongSchedulingGapStartsANewBaseline() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 10, 10, 0, 25, 60, 0);
    p.update(2000, 30, 30, 0, 25, 60, 1000);
    assertFalse(p.update(4000, 5, 5, 0, 25, 60, 2000));
    assertFalse(p.update(6000, 25, 25, 0, 25, 60, 3000));
    assertFalse(p.update(13000, 45, 45, 0, 25, 60, 6500));
    assertFalse(p.update(15000, 65, 65, 0, 25, 60, 7500));
  }

  @Test
  public void unknownFrameRateCannotTriggerFallback() {
    for (float fps : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
      SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
      assertFalse(p.update(0, 10, 10, 0, fps, 60, 0));
      assertFalse(p.update(2000, 30, 11, 19, fps, 60, 1000));
      assertFalse(p.update(4000, 50, 12, 38, fps, 60, 2000));
    }
  }

  @Test
  public void screenRateLimitsBothDropAndUnderRateEvidenceOnHighFrameRateMedia() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 1, 1, 0, 120, 60, 0);
    assertFalse(p.update(2000, 241, 121, 120, 120, 60, 1000));
    assertFalse(
        "A full 60 presented fps is healthy on a 60 Hz display",
        p.update(4000, 481, 241, 240, 120, 60, 2000));
    assertFalse(p.update(6000, 721, 301, 420, 120, 60, 3000));
    assertTrue(
        "30 presented fps on the same display remains a real deficit",
        p.update(8000, 961, 361, 600, 120, 60, 4000));
  }

  @Test
  public void sparseHighRateTrackKeepingRealTimeIsNotSlowDecoding() {
    SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
    p.update(0, 1, 1, 0, 120, 60, 0);
    assertFalse(p.update(2000, 11, 11, 0, 120, 60, 1950));
    assertFalse(p.update(4000, 21, 21, 0, 120, 60, 3990));
    assertFalse(p.update(6000, 31, 31, 0, 120, 60, -1));
    assertFalse(p.update(8000, 41, 41, 0, 120, 60, -1));
  }

  @Test
  public void unknownDisplayRateCannotCreatePlaybackFailure() {
    for (float hz : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
      SoftwareDecodeHealthPolicy p = new SoftwareDecodeHealthPolicy();
      p.update(0, 1, 1, 0, 120, hz, 0);
      assertFalse(p.update(2000, 241, 11, 230, 120, hz, 1000));
      assertFalse(p.update(4000, 481, 21, 460, 120, hz, 2000));
    }
  }
}
