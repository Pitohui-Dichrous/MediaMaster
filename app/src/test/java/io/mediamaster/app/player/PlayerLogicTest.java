package io.mediamaster.app.player;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Random;
import org.junit.Test;

public class PlayerLogicTest {
  @Test
  public void tinySwipeIsSecondPreciseAndLongSwipeAccelerates() {
    assertEquals(1067, GestureSeek.deltaMs(24, 360, 7_200_000, 1));
    long small = GestureSeek.deltaMs(48, 360, 7_200_000, 1);
    long large = GestureSeek.deltaMs(240, 360, 7_200_000, 1);
    assertTrue(small >= 1000 && small <= 5000);
    assertTrue(large > small * 20);
    assertEquals(-large, GestureSeek.deltaMs(-240, 360, 7_200_000, 1));
  }

  @Test
  public void shortClipsKeepEverySmallMoveContinuousInsteadOfSnappingToSeconds() {
    for (long duration : new long[] {1000, 6000, 60000}) {
      long previous = 0;
      for (int pixel = 1; pixel <= 20; pixel++) {
        long next = GestureSeek.deltaMs(pixel, 360, duration, 1);
        assertTrue("Every small move must advance the target", next > previous);
        assertTrue("One pixel must not turn into a full second", next - previous < 50);
        assertEquals(-next, GestureSeek.deltaMs(-pixel, 360, duration, 1));
        previous = next;
      }
    }
  }

  @Test
  public void fractionalMovesRemainReversibleWithoutAccumulatingRoundedSteps() {
    long first = GestureSeek.deltaMs(18.25f, 360, 6000, 1);
    long outward = GestureSeek.deltaMs(18.75f, 360, 6000, 1);
    assertTrue(outward > first);
    assertTrue(outward - first < 30);
    assertEquals(first, GestureSeek.deltaMs(18.25f, 360, 6000, 1));
    assertEquals(-first, GestureSeek.deltaMs(-18.25f, 360, 6000, 1));
  }

  @Test
  public void seekClampsAndReversesWithoutAccumulation() {
    assertEquals(0, GestureSeek.targetMs(1000, -5000, 60000));
    assertEquals(60000, GestureSeek.targetMs(50000, 50000, 60000));
    assertEquals(0, GestureSeek.deltaMs(0, 360, 60000, 1));
    assertTrue(
        GestureSeek.deltaMs(50, 360, 60000, 1.5f) > GestureSeek.deltaMs(50, 360, 60000, .65f));
  }

  @Test
  public void shuffleVisitsEntireDeckAndPreviousRetracesHistory() {
    PlaybackOrder order = new PlaybackOrder(20, 4, PlaybackOrder.SHUFFLE, new Random(42));
    HashSet<Integer> visited = new HashSet<>();
    visited.add(order.current());
    int before = 4;
    for (int i = 0; i < 19; i++) {
      int item = order.next(true);
      assertNotEquals(before, item);
      assertTrue(visited.add(item));
      before = item;
    }
    assertEquals(20, visited.size());
    int last = order.current();
    order.previous();
    assertEquals(last, order.next(false));
    assertNotEquals(last, order.next(true));
  }

  @Test
  public void repeatOneOnlyAffectsAutomaticAdvance() {
    PlaybackOrder order = new PlaybackOrder(3, 1, PlaybackOrder.SINGLE);
    assertEquals(1, order.next(true));
    assertEquals(2, order.next(false));
    assertEquals(1, order.previous());
    order.setMode(PlaybackOrder.SEQUENTIAL);
    assertEquals(2, order.next(true));
    assertEquals(0, order.next(true));
  }

  @Test
  public void randomPreviousAvailabilityFollowsActualWatchHistory() {
    PlaybackOrder order = new PlaybackOrder(4, 0, PlaybackOrder.SHUFFLE, new Random(7));
    assertFalse(order.hasPrevious());
    order.next(false);
    assertTrue(order.hasPrevious());
    assertEquals(0, order.previous());
    assertFalse(order.hasPrevious());
    order.setMode(PlaybackOrder.SINGLE);
    assertTrue(order.hasPrevious());
  }

  @Test
  public void oneVideoShuffleIsSafe() {
    PlaybackOrder order = new PlaybackOrder(1, 0, PlaybackOrder.SHUFFLE);
    for (int i = 0; i < 10; i++) assertEquals(0, order.next(true));
    assertEquals(0, order.previous());
  }
}
