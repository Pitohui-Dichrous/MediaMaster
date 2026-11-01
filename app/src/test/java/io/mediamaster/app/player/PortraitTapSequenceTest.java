package io.mediamaster.app.player;

import static io.mediamaster.app.player.PortraitTapSequence.Release.*;
import static org.junit.Assert.*;

import org.junit.Test;

public final class PortraitTapSequenceTest {
  private PortraitTapSequence taps() {
    return new PortraitTapSequence(300, 500, 100);
  }

  @Test
  public void standaloneTapConfirmsOnlyOnceAfterReleaseTimeout() {
    PortraitTapSequence taps = taps();
    assertFalse(taps.down(1000, 20, 20));
    assertEquals(PENDING_SINGLE, taps.up(1020, 20, 20));
    assertEquals(1320, taps.singleDeadline());
    assertFalse(taps.confirmSingle(1319));
    assertTrue(taps.confirmSingle(1320));
    assertFalse(taps.confirmSingle(1500));
  }

  @Test
  public void fastSecondTapLikesOnlyAfterReleaseWithoutMinimumIntertapDelay() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 20, 20);
    taps.up(1015, 20, 20);
    assertFalse(taps.down(1020, 20, 20));
    assertFalse(taps.confirmSingle(1030));
    assertEquals(LIKE, taps.up(1040, 20, 20));
    assertFalse(taps.confirmSingle(2000));
  }

  @Test
  public void oddAndEvenBurstsNeverLeaveATrailingSingle() {
    for (int count : new int[] {3, 5, 6, 25}) {
      for (int gap : new int[] {20, 70, 180, 299}) {
        PortraitTapSequence taps = taps();
        long time = 1000;
        for (int n = 0; n < count; n++) {
          assertFalse(taps.down(time, 20, 20));
          assertEquals(n == 0 ? PENDING_SINGLE : LIKE, taps.up(time + 15, 20, 20));
          time += 15 + gap;
        }
        assertFalse(taps.confirmSingle(time + 1000));
      }
    }
  }

  @Test
  public void independentTapAfterBurstTimeoutStillConfirmsNormally() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 20, 20);
    taps.up(1020, 20, 20);
    taps.down(1100, 20, 20);
    assertEquals(LIKE, taps.up(1120, 20, 20));
    assertFalse(taps.down(1421, 20, 20));
    assertEquals(PENDING_SINGLE, taps.up(1440, 20, 20));
    assertTrue(taps.confirmSingle(1740));
  }

  @Test
  public void secondPressDefersFirstSingleAndCancelDiscardsTheWholeSequence() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 20, 20);
    taps.up(1020, 20, 20);
    taps.down(1250, 20, 20);
    assertFalse(taps.confirmSingle(1400));
    taps.cancel(); // A diagonal drag, long press, page change or lost window focus.
    assertEquals(IGNORED, taps.up(1450, 40, 40));
    assertFalse(taps.confirmSingle(2000));
    assertFalse(taps.down(2010, 20, 20));
    assertEquals(PENDING_SINGLE, taps.up(2030, 20, 20));
  }

  @Test
  public void longOrInvalidPressCannotLikeEvenAtAnEdgeWithoutBoost() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 1, 1);
    taps.up(1010, 1, 1);
    taps.down(1030, 1, 1);
    assertEquals(IGNORED, taps.up(1530, 1, 1));
    assertFalse(taps.confirmSingle(2000));
    assertEquals(IGNORED, taps.up(2100, 1, 1));
  }

  @Test
  public void initialPairUsesSlopButAnEstablishedLikeBurstCanMove() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 20, 20);
    taps.up(1010, 20, 20);
    assertTrue(taps.down(1100, 300, 300)); // The distant first two taps are independent.
    assertEquals(PENDING_SINGLE, taps.up(1110, 300, 300));
    assertFalse(taps.down(1200, 310, 310));
    assertEquals(LIKE, taps.up(1210, 310, 310));
    assertFalse(taps.down(1300, 20, 20));
    assertEquals(LIKE, taps.up(1310, 20, 20));
    assertFalse(taps.confirmSingle(2000));
  }

  @Test
  public void delayedHandlerDoesNotMergeTwoIndependentTaps() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 20, 20);
    taps.up(1010, 20, 20);
    // Input may be processed before the delayed confirmation callback after a busy main thread.
    assertTrue(taps.down(1500, 20, 20));
    assertEquals(PENDING_SINGLE, taps.up(1510, 20, 20));
    assertTrue(taps.confirmSingle(1810));
  }

  @Test
  public void cancellingAnEstablishedBurstDoesNotLikeTheNextVideosFirstTap() {
    PortraitTapSequence taps = taps();
    taps.down(1000, 20, 20);
    taps.up(1010, 20, 20);
    taps.down(1050, 20, 20);
    assertEquals(LIKE, taps.up(1060, 20, 20));
    taps.cancel();
    assertFalse(taps.down(1100, 20, 20));
    assertEquals(PENDING_SINGLE, taps.up(1110, 20, 20));
    assertTrue(taps.confirmSingle(1410));
  }
}
