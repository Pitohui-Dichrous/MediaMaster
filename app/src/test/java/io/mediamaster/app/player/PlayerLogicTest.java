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

  @Test
  public void sequentialPeeksDoNotNavigateAndBothCommitsMatchTheirCandidates() {
    PlaybackOrder order = new PlaybackOrder(4, 0, PlaybackOrder.SEQUENTIAL);
    for (int i = 0; i < 20; i++) {
      assertEquals(1, order.peekNext());
      assertEquals(3, order.peekPrevious());
      assertEquals(0, order.current());
    }
    assertEquals(order.peekNext(), order.next(false));
    assertEquals(1, order.current());
    assertEquals(order.peekPrevious(), order.previous());
    assertEquals(0, order.current());
    assertEquals(3, order.previous());
    assertEquals(0, order.peekNext());
  }

  @Test
  public void shufflePreloadingDoesNotCreateHistoryOrConsumeAnyDeckCandidates() {
    PlaybackOrder preloaded = new PlaybackOrder(7, 2, PlaybackOrder.SHUFFLE, new Random(27));
    PlaybackOrder baseline = new PlaybackOrder(7, 2, PlaybackOrder.SHUFFLE, new Random(27));
    for (int i = 0; i < 80; i++) {
      int current = preloaded.current();
      boolean hadPrevious = preloaded.hasPrevious();
      int candidate = preloaded.peekNext();
      for (int repeat = 0; repeat < 12; repeat++) {
        assertEquals(candidate, preloaded.peekNext());
        preloaded.peekPrevious();
        assertEquals(current, preloaded.current());
        assertEquals(hadPrevious, preloaded.hasPrevious());
      }
      assertEquals("Peeking must not alter the random sequence", baseline.next(false), candidate);
      assertEquals(candidate, preloaded.next(false));
    }
  }

  @Test
  public void shufflePeeksAtOriginDoNotInventAPreviousVideo() {
    PlaybackOrder order = new PlaybackOrder(6, 4, PlaybackOrder.SHUFFLE, new Random(11));
    int candidate = order.peekNext();
    for (int i = 0; i < 50; i++) {
      assertFalse(order.hasPrevious());
      assertEquals(4, order.peekPrevious());
      assertEquals(4, order.current());
      assertEquals(candidate, order.peekNext());
    }
    assertEquals(4, order.previous());
    assertEquals(candidate, order.next(false));
    assertTrue(order.hasPrevious());
    assertEquals(4, order.peekPrevious());
  }

  @Test
  public void shuffleBacktrackingKeepsForwardHistoryAndPreparedFutureCandidateStable() {
    PlaybackOrder order = new PlaybackOrder(5, 0, PlaybackOrder.SHUFFLE, new Random(5));
    int first = order.next(false);
    int second = order.next(false);
    int future = order.peekNext();
    assertEquals(first, order.peekPrevious());
    assertEquals(first, order.previous());
    assertEquals(second, order.peekNext());
    assertEquals(0, order.peekPrevious());
    assertEquals(0, order.previous());
    assertEquals(first, order.peekNext());
    assertFalse(order.hasPrevious());
    assertEquals(first, order.next(false));
    assertEquals(second, order.peekNext());
    assertEquals(second, order.next(false));
    assertEquals(future, order.peekNext());
    assertEquals(future, order.next(false));
  }

  @Test
  public void singleRepeatPreloadsManualNeighborsButNaturalEndsNeverConsumeThem() {
    PlaybackOrder order = new PlaybackOrder(3, 1, PlaybackOrder.SINGLE);
    for (int i = 0; i < 20; i++) {
      assertEquals(2, order.peekNext());
      assertEquals(0, order.peekPrevious());
      assertEquals(1, order.next(true));
      assertEquals(1, order.current());
    }
    assertEquals(order.peekNext(), order.next(false));
    assertEquals(2, order.current());
    assertEquals(2, order.next(true));
    assertEquals(order.peekPrevious(), order.previous());
    assertEquals(1, order.current());
  }

  @Test
  public void peeksRemainSafeForSingleVideoAndTwoVideoQueues() {
    for (int mode :
        new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SINGLE, PlaybackOrder.SHUFFLE}) {
      PlaybackOrder single = new PlaybackOrder(1, 0, mode, new Random(3));
      for (int i = 0; i < 6; i++) {
        assertEquals(0, single.peekNext());
        assertEquals(0, single.peekPrevious());
        assertEquals(0, single.current());
        assertEquals(0, single.next(false));
        assertEquals(0, single.previous());
      }
      PlaybackOrder pair = new PlaybackOrder(2, 0, mode, new Random(3));
      for (int i = 0; i < 12; i++) {
        int current = pair.current();
        assertEquals(1 - current, pair.peekNext());
        assertEquals(current, pair.current());
        assertEquals(pair.peekNext(), pair.next(false));
        assertEquals(current, pair.peekPrevious());
        assertEquals(current, pair.previous());
      }
    }
  }

  @Test
  public void changingModeDiscardsPreparedShuffleCandidateAndResetsHistory() {
    PlaybackOrder order = new PlaybackOrder(6, 3, PlaybackOrder.SHUFFLE, new Random(17));
    order.next(false);
    order.peekNext();
    int current = order.current();
    assertTrue(order.hasPrevious());
    order.setMode(PlaybackOrder.SINGLE);
    assertEquals(current, order.current());
    assertEquals((current + 1) % 6, order.peekNext());
    assertEquals(current, order.next(true));
    order.setMode(PlaybackOrder.SHUFFLE);
    assertEquals(current, order.current());
    assertEquals(current, order.peekPrevious());
    assertFalse(order.hasPrevious());
    int candidate = order.peekNext();
    assertNotEquals(current, candidate);
    assertEquals(candidate, order.next(false));
    assertEquals(current, order.peekPrevious());
  }

  @Test
  public void portraitSequentialAlwaysLoopsNaturallyButManualSwipesKeepDirectoryOrder() {
    PlaybackOrder order = new PlaybackOrder(4, 1, PlaybackOrder.SEQUENTIAL);
    assertFalse(order.repeatsCurrent());
    order.setPortraitMode(true);
    assertTrue(order.portraitMode());
    assertTrue(order.repeatsCurrent());
    assertEquals(PlaybackOrder.SEQUENTIAL, order.mode());
    for (int i = 0; i < 12; i++) {
      int current = order.current();
      int candidate = order.peekNext();
      for (int loop = 0; loop < 8; loop++) {
        assertEquals(current, order.next(true));
        assertEquals(candidate, order.peekNext());
        assertEquals(current, order.current());
      }
      assertEquals((current + 1) % 4, order.next(false));
      assertEquals(current, order.previous());
      assertEquals(candidate, order.next(false));
    }
    order.setPortraitMode(false);
    assertFalse(order.repeatsCurrent());
    assertEquals(order.peekNext(), order.next(true));
  }

  @Test
  public void portraitRandomNaturalLoopsNeverConsumePreloadedCandidatesOrWatchHistory() {
    PlaybackOrder portrait = new PlaybackOrder(7, 2, PlaybackOrder.SHUFFLE, new Random(32));
    PlaybackOrder manualBaseline = new PlaybackOrder(7, 2, PlaybackOrder.SHUFFLE, new Random(32));
    portrait.setPortraitMode(true);
    assertTrue(portrait.repeatsCurrent());
    assertEquals(PlaybackOrder.SHUFFLE, portrait.mode());
    for (int i = 0; i < 40; i++) {
      int current = portrait.current();
      int candidate = portrait.peekNext();
      int previous = portrait.peekPrevious();
      boolean hadPrevious = portrait.hasPrevious();
      for (int loop = 0; loop < 12; loop++) {
        assertEquals(current, portrait.next(true));
        assertEquals(candidate, portrait.peekNext());
        assertEquals(previous, portrait.peekPrevious());
        assertEquals(hadPrevious, portrait.hasPrevious());
      }
      assertEquals(manualBaseline.next(false), candidate);
      assertEquals(candidate, portrait.next(false));
    }
    int newest = portrait.current();
    int future = portrait.peekNext();
    portrait.previous();
    assertEquals(newest, portrait.peekNext());
    assertEquals(portrait.current(), portrait.next(true));
    assertEquals(newest, portrait.next(false));
    assertEquals(future, portrait.peekNext());
  }

  @Test
  public void portraitChoiceNeverOverwritesFullScreenSingleRepeatPreference() {
    PlaybackOrder order = new PlaybackOrder(5, 1, PlaybackOrder.SINGLE, new Random(12));
    assertEquals(PlaybackOrder.SEQUENTIAL, order.portraitNavigationMode());
    order.setPortraitMode(true);
    assertEquals(PlaybackOrder.SEQUENTIAL, order.mode());
    assertEquals(2, order.next(false));
    order.setMode(PlaybackOrder.SHUFFLE);
    assertEquals(PlaybackOrder.SHUFFLE, order.mode());
    assertEquals(PlaybackOrder.SINGLE, order.fullScreenMode());
    int current = order.current();
    int randomCandidate = order.peekNext();
    assertNotEquals(current, randomCandidate);
    assertEquals(current, order.next(true));
    assertEquals(randomCandidate, order.next(false));
    order.setPortraitMode(false);
    assertEquals(PlaybackOrder.SINGLE, order.mode());
    assertEquals(PlaybackOrder.SHUFFLE, order.portraitNavigationMode());
    assertTrue(order.repeatsCurrent());
    assertEquals(randomCandidate, order.next(true));
    assertEquals((randomCandidate + 1) % 5, order.next(false));
    order.setPortraitMode(true);
    assertEquals(PlaybackOrder.SHUFFLE, order.mode());
    assertEquals(PlaybackOrder.SINGLE, order.fullScreenMode());
  }

  @Test
  public void sameNavigationClassModeRoundTripsKeepShuffleHistoryAndPreparedCandidate() {
    PlaybackOrder order = new PlaybackOrder(6, 0, PlaybackOrder.SHUFFLE, new Random(6));
    int first = order.next(false);
    int second = order.next(false);
    int future = order.peekNext();
    for (int i = 0; i < 10; i++) {
      order.setPortraitMode(true);
      assertTrue(order.repeatsCurrent());
      assertEquals(second, order.next(true));
      assertEquals(first, order.peekPrevious());
      assertEquals(future, order.peekNext());
      order.setPortraitMode(false);
      assertFalse(order.repeatsCurrent());
      assertEquals(first, order.peekPrevious());
      assertEquals(future, order.peekNext());
    }
    assertEquals(first, order.previous());
    order.setPortraitMode(true);
    assertEquals(second, order.peekNext());
    assertEquals(second, order.next(false));
    order.setPortraitMode(false);
    assertEquals(future, order.next(true));
  }

  @Test
  public void restoringInactivePortraitChoiceLeavesFullScreenHistoryUntouched() {
    PlaybackOrder order = new PlaybackOrder(6, 0, PlaybackOrder.SHUFFLE, new Random(9));
    order.next(false);
    int current = order.current();
    int previous = order.peekPrevious();
    int future = order.peekNext();
    order.setPortraitNavigationMode(PlaybackOrder.SEQUENTIAL);
    assertFalse(order.portraitMode());
    assertEquals(PlaybackOrder.SHUFFLE, order.mode());
    assertEquals(PlaybackOrder.SHUFFLE, order.fullScreenMode());
    assertEquals(PlaybackOrder.SEQUENTIAL, order.portraitNavigationMode());
    assertEquals(current, order.current());
    assertEquals(previous, order.peekPrevious());
    assertEquals(future, order.peekNext());
    assertTrue(order.hasPrevious());
    order.setPortraitNavigationMode(PlaybackOrder.SHUFFLE);
    order.setPortraitMode(true);
    assertEquals(previous, order.peekPrevious());
    assertEquals(future, order.peekNext());
    order.setPortraitNavigationMode(PlaybackOrder.SEQUENTIAL);
    assertEquals((current + 1) % 6, order.peekNext());
    assertEquals(PlaybackOrder.SHUFFLE, order.fullScreenMode());
    order.setPortraitMode(false);
    assertFalse("A genuine order-class change starts a fresh random context", order.hasPrevious());
    assertEquals(current, order.peekPrevious());
  }

  @Test
  public void portraitSingleSelectionMapsToSequentialNavigationAndKeepsFullPreference() {
    PlaybackOrder order = new PlaybackOrder(4, 1, PlaybackOrder.SEQUENTIAL);
    order.setPortraitNavigationMode(PlaybackOrder.SINGLE);
    assertEquals(PlaybackOrder.SEQUENTIAL, order.portraitNavigationMode());
    order.setPortraitMode(true);
    order.setMode(PlaybackOrder.SINGLE);
    assertEquals(PlaybackOrder.SEQUENTIAL, order.mode());
    assertEquals(PlaybackOrder.SEQUENTIAL, order.fullScreenMode());
    assertTrue(order.repeatsCurrent());
    assertEquals(1, order.next(true));
    assertEquals(2, order.next(false));
    order.setPortraitMode(false);
    assertFalse(order.repeatsCurrent());
    assertEquals(3, order.next(true));
  }

  @Test
  public void portraitRepeatAndManualCandidatesAreSafeForOneAndTwoVideosInEitherOrder() {
    for (int fullMode :
        new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SINGLE, PlaybackOrder.SHUFFLE}) {
      for (int portraitOrder : new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
        for (int count : new int[] {1, 2}) {
          PlaybackOrder order = new PlaybackOrder(count, 0, fullMode, new Random(18));
          order.setPortraitNavigationMode(portraitOrder);
          order.setPortraitMode(true);
          for (int i = 0; i < 10; i++) {
            int current = order.current();
            int candidate = order.peekNext();
            assertEquals(current, order.next(true));
            assertEquals(candidate, order.peekNext());
            assertEquals(count == 1 ? current : 1 - current, candidate);
            assertEquals(candidate, order.next(false));
            assertEquals(current, order.previous());
          }
          order.setPortraitMode(false);
          assertEquals(fullMode, order.mode());
          assertEquals(fullMode == PlaybackOrder.SINGLE, order.repeatsCurrent());
        }
      }
    }
  }
}
