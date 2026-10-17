package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class GestureLevelsTest {
  @Test
  public void quarterScreenMovesExactlyOneQuarterAndReverses() {
    assertEquals(.60f, GestureLevels.adjust(.35f, -200, 800, .01f), .0001f);
    assertEquals(.10f, GestureLevels.adjust(.35f, 200, 800, .01f), .0001f);
    assertEquals(.35f, GestureLevels.adjust(.35f, 0, 800, .01f), .0001f);
  }

  @Test
  public void levelsClampAtValidBoundsIncludingMuteAndDimScreen() {
    assertEquals(1f, GestureLevels.adjust(.5f, -1600, 800, 0), 0);
    assertEquals(0f, GestureLevels.adjust(.5f, 1600, 800, 0), 0);
    assertEquals(.01f, GestureLevels.adjust(.5f, 1600, 800, .01f), 0);
  }
}
