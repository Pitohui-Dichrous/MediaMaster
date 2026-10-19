package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PortraitSwipeTest {
  @Test
  public void offsetFollowsFingerWithoutADiscontinuityAndRetracesOnReversal() {
    assertEquals(0f, PortraitSwipe.offset(0, 1000), 0f);
    assertEquals(1f, PortraitSwipe.offset(1, 1000), .001f);
    float near = PortraitSwipe.offset(100, 1000);
    float far = PortraitSwipe.offset(300, 1000);
    assertTrue(near > 0 && near < 100);
    assertTrue(far > near && far < 300);
    assertEquals(near, PortraitSwipe.offset(100, 1000), 0f);
    assertEquals(-near, PortraitSwipe.offset(-100, 1000), 0f);
    assertTrue(PortraitSwipe.offset(101, 1000) - near < 1);
  }

  @Test
  public void offsetResistsOvershootInBothDirectionsAndScalesWithScreen() {
    assertTrue(PortraitSwipe.offset(100_000, 1000) <= 850f);
    assertTrue(PortraitSwipe.offset(-100_000, 1000) >= -850f);
    assertEquals(PortraitSwipe.offset(100, 1000) * 2, PortraitSwipe.offset(200, 2000), .0001f);
  }

  @Test
  public void deliberateUpAndDownDragsSelectNextAndPrevious() {
    assertEquals(1, PortraitSwipe.direction(-200, 0, 1000, 1));
    assertEquals(-1, PortraitSwipe.direction(200, 0, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(-159, 0, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(159, 0, 1000, 1));
    assertEquals(1, PortraitSwipe.direction(-160, 0, 1000, 1));
    assertEquals(-1, PortraitSwipe.direction(160, 0, 1000, 1));
  }

  @Test
  public void compactScreensStillRequire56DpAndDensityDoesNotChangePhysicalThreshold() {
    assertEquals(0, PortraitSwipe.direction(-55, 0, 200, 1));
    assertEquals(1, PortraitSwipe.direction(-56, 0, 200, 1));
    assertEquals(0, PortraitSwipe.direction(-111, 0, 400, 2));
    assertEquals(1, PortraitSwipe.direction(-112, 0, 400, 2));
    assertEquals(1, PortraitSwipe.direction(-320, 0, 2000, 2));
  }

  @Test
  public void fastFlingNeedsMinimumTravelAndSameDirectionAtDensityAdjustedVelocity() {
    assertEquals(0, PortraitSwipe.direction(-31, -3000, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(-32, -1499, 1000, 1));
    assertEquals(1, PortraitSwipe.direction(-32, -1500, 1000, 1));
    assertEquals(-1, PortraitSwipe.direction(32, 1500, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(-64, -2999, 2000, 2));
    assertEquals(1, PortraitSwipe.direction(-64, -3000, 2000, 2));
  }

  @Test
  public void pullingBackCancelsRatherThanUsingTheFarthestPointReached() {
    assertEquals(0, PortraitSwipe.direction(-40, 500, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(-200, 1800, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(200, -1800, 1000, 1));
    assertEquals(1, PortraitSwipe.direction(-200, 250, 1000, 1));
    assertEquals(-1, PortraitSwipe.direction(200, -250, 1000, 1));
    assertEquals(0, PortraitSwipe.direction(0, -5000, 1000, 1));
  }

  @Test
  public void invalidGeometryAndNonFiniteMotionNeverMoveOrTurnAPage() {
    for (float invalid :
        new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      assertEquals(0f, PortraitSwipe.offset(invalid, 1000), 0f);
      assertEquals(0f, PortraitSwipe.offset(200, invalid), 0f);
      assertEquals(0, PortraitSwipe.direction(invalid, -2000, 1000, 1));
      assertEquals(0, PortraitSwipe.direction(-200, invalid, 1000, 1));
      assertEquals(0, PortraitSwipe.direction(-200, -2000, invalid, 1));
      assertEquals(0, PortraitSwipe.direction(-200, -2000, 1000, invalid));
    }
    assertEquals(0f, PortraitSwipe.offset(200, 0), 0f);
    assertEquals(0f, PortraitSwipe.offset(200, -100), 0f);
    assertEquals(0, PortraitSwipe.direction(-200, -2000, 0, 1));
    assertEquals(0, PortraitSwipe.direction(-200, -2000, 1000, 0));
    assertEquals(0, PortraitSwipe.direction(-200, -2000, 1000, -1));
  }
}
