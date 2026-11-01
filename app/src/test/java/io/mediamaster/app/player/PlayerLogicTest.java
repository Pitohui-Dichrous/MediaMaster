package io.mediamaster.app.player;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashMap;
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
  public void shuffleVisitsEntireRoundAndPreviousReversesForwardNavigation() {
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
  public void randomPreviousWrapsFromItsInitialItemAndReturnsForward() {
    PlaybackOrder order = new PlaybackOrder(4, 0, PlaybackOrder.SHUFFLE, new Random(7));
    assertTrue(order.hasPrevious());
    int previous = order.peekPrevious();
    assertNotEquals(0, previous);
    assertEquals(previous, order.previous());
    assertEquals(0, order.peekNext());
    assertEquals(0, order.next(false));
    order.next(false);
    assertTrue(order.hasPrevious());
    assertEquals(0, order.previous());
    assertTrue(order.hasPrevious());
    order.setMode(PlaybackOrder.SINGLE);
    assertTrue(order.hasPrevious());
  }

  @Test
  public void oneVideoShuffleIsSafe() {
    PlaybackOrder order = new PlaybackOrder(1, 0, PlaybackOrder.SHUFFLE);
    for (int i = 0; i < 10; i++) assertEquals(0, order.next(true));
    assertEquals(0, order.previous());
    assertFalse(order.hasPrevious());
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
  public void shufflePreloadingDoesNotAdvanceOrChangeTheForwardRounds() {
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
  public void shufflePeeksAtOriginExposeStableNeighborsWithoutNavigating() {
    PlaybackOrder order = new PlaybackOrder(6, 4, PlaybackOrder.SHUFFLE, new Random(11));
    int candidate = order.peekNext();
    int predecessor = order.peekPrevious();
    assertNotEquals(4, predecessor);
    for (int i = 0; i < 50; i++) {
      assertTrue(order.hasPrevious());
      assertEquals(predecessor, order.peekPrevious());
      assertEquals(4, order.current());
      assertEquals(candidate, order.peekNext());
    }
    assertEquals(predecessor, order.previous());
    assertEquals(4, order.next(false));
    assertEquals(candidate, order.next(false));
    assertTrue(order.hasPrevious());
    assertEquals(4, order.peekPrevious());
  }

  @Test
  public void shuffleBacktrackingKeepsBothPreloadedNeighborsStable() {
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
    assertTrue(order.hasPrevious());
    assertNotEquals(0, order.peekPrevious());
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
        assertFalse(single.hasPrevious());
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
  public void changingModeCreatesANewInitialRoundAnchoredAtTheCurrentVideo() {
    CountingRandom random = new CountingRandom(17);
    PlaybackOrder order = new PlaybackOrder(6, 3, PlaybackOrder.SHUFFLE, random);
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
    int drawsBeforeReentry = random.draws;
    assertNotEquals(current, order.peekPrevious());
    assertTrue("A new shuffle session draws a new permutation", random.draws > drawsBeforeReentry);
    assertTrue(order.hasPrevious());
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
  public void portraitRandomNaturalLoopsNeverAdvanceTheRoundsOrItsPreloadedCandidates() {
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
  public void sameNavigationClassModeRoundTripsKeepShuffleRoundsAndPreparedCandidate() {
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
  public void restoringInactivePortraitChoiceLeavesFullScreenRoundsUntouched() {
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
    assertTrue("A new random context still has a previous-round item", order.hasPrevious());
    assertNotEquals(current, order.peekPrevious());
    assertEquals(current, order.current());
  }

  @Test
  public void everyNewRoundVisitsAllVideosOnceInEitherDirectionBeyondTheOldHistoryLimit() {
    for (int count : new int[] {1, 2, 7, 2053}) {
      int initial = count / 2;
      int[] visits = new int[count];
      for (boolean forward : new boolean[] {true, false}) {
        PlaybackOrder order =
            new PlaybackOrder(count, initial, PlaybackOrder.SHUFFLE, new Random(43));
        for (int round = 0; round < 6; round++) {
          if (round > 0 || !forward) {
            int before = order.current();
            int item = forward ? order.next(false) : order.previous();
            if (count > 1) assertNotEquals(before, item);
          }
          HashSet<Integer> seen = new HashSet<>();
          seen.add(order.current());
          visits[order.current()]++;
          for (int step = 1; step < count; step++) {
            int before = order.current();
            int candidate = forward ? order.peekNext() : order.peekPrevious();
            assertEquals(before, order.current());
            int item = forward ? order.next(false) : order.previous();
            assertEquals(candidate, item);
            if (count > 1) assertNotEquals(before, item);
            assertTrue("Every item must appear exactly once per round", seen.add(item));
            visits[item]++;
          }
          assertEquals(count, seen.size());
        }
      }
      for (int visitsForItem : visits) assertEquals(12, visitsForItem);
    }
  }

  @Test
  public void initialRoundShuffleDrawsGiveEveryEligibleItemEqualPositions() {
    // Enumerate all 4! equally likely draw paths for four unanchored items. This is exact,
    // deterministic coverage of inclusion and positional fairness, not a random frequency test.
    for (int initial = 0; initial < 5; initial++) {
      HashSet<String> permutations = new HashSet<>();
      int[][] positions = new int[5][5];
      for (int a = 0; a < 4; a++) {
        for (int b = 0; b < 3; b++) {
          for (int c = 0; c < 2; c++) {
            int[] draws = {a, b, c};
            Random random =
                new Random(0) {
                  private int index;

                  @Override
                  public int nextInt(int bound) {
                    assertTrue("Unexpected extra random draw", index < draws.length);
                    int value = draws[index++];
                    assertTrue(value >= 0 && value < bound);
                    return value;
                  }
                };
            PlaybackOrder order = new PlaybackOrder(5, initial, PlaybackOrder.SHUFFLE, random);
            int[] round = new int[5];
            round[0] = order.current();
            for (int position = 1; position < round.length; position++) {
              round[position] = order.next(false);
              positions[position][round[position]]++;
            }
            assertEquals(initial, round[0]);
            assertTrue(
                "Every draw path must produce a distinct permutation",
                permutations.add(Arrays.toString(round)));
          }
        }
      }
      assertEquals(24, permutations.size());
      for (int position = 1; position < 5; position++) {
        for (int item = 0; item < 5; item++) {
          assertEquals(item == initial ? 0 : 6, positions[position][item]);
        }
      }
    }
  }

  @Test
  public void eachBoundaryAllowedPermutationIsEquallyLikelyAndStillIncludesTheLastVideo() {
    // Four-item permutations have 24 equally likely shuffle paths. Enumerating a third
    // repair choice even when unused weights each path identically and covers all 72 cases.
    HashMap<String, Integer> permutations = new HashMap<>();
    int[] firstPositions = new int[4];
    int previousLast = -1;
    for (int a = 0; a < 4; a++) {
      for (int b = 0; b < 3; b++) {
        for (int c = 0; c < 2; c++) {
          for (int repair = 0; repair < 3; repair++) {
            int[] draws = {0, 0, a, b, c, repair};
            Random random =
                new Random(0) {
                  private int index;

                  @Override
                  public int nextInt(int bound) {
                    assertTrue("Unexpected extra random draw", index < draws.length);
                    int value = draws[index++];
                    assertTrue(value >= 0 && value < bound);
                    return value;
                  }
                };
            PlaybackOrder order = new PlaybackOrder(4, 0, PlaybackOrder.SHUFFLE, random);
            for (int step = 1; step < 4; step++) order.next(false);
            previousLast = order.current();
            int[] round = new int[4];
            HashSet<Integer> seen = new HashSet<>();
            for (int position = 0; position < 4; position++) {
              round[position] = order.next(false);
              assertTrue(seen.add(round[position]));
            }
            assertNotEquals(previousLast, round[0]);
            assertEquals(4, seen.size());
            firstPositions[round[0]]++;
            String permutation = Arrays.toString(round);
            permutations.put(permutation, permutations.getOrDefault(permutation, 0) + 1);
          }
        }
      }
    }
    assertEquals(18, permutations.size());
    for (int occurrences : permutations.values()) assertEquals(4, occurrences);
    for (int item = 0; item < 4; item++) {
      assertEquals(item == previousLast ? 0 : 24, firstPositions[item]);
    }
  }

  @Test
  public void recentRoundBoundariesRetraceExactlyWhilePeekingBothSides() {
    for (int count : new int[] {2, 3, 9}) {
      PlaybackOrder order = new PlaybackOrder(count, 0, PlaybackOrder.SHUFFLE, new Random(91));
      int[] path = new int[2 * count];
      path[0] = order.current();
      for (int step = 1; step < path.length; step++) {
        order.peekPrevious();
        int candidate = order.peekNext();
        path[step] = order.next(false);
        assertEquals(candidate, path[step]);
      }
      order.peekNext();
      for (int step = path.length - 2; step >= 0; step--) {
        order.peekNext();
        assertEquals(path[step], order.peekPrevious());
        assertEquals(path[step], order.previous());
      }
      order.peekPrevious();
      for (int step = 1; step < path.length; step++) {
        assertEquals(path[step], order.peekNext());
        assertEquals(path[step], order.next(false));
      }
    }
  }

  private static final class CountingRandom extends Random {
    int draws;

    CountingRandom(long seed) {
      super(seed);
    }

    @Override
    public int nextInt(int bound) {
      draws++;
      return super.nextInt(bound);
    }
  }

  @Test
  public void weightedRoundsCoverAllItemsWithoutBoundaryRepeatsInBothDirections() {
    for (int count : new int[] {1, 2, 17, 2053}) {
      for (boolean forward : new boolean[] {true, false}) {
        PlaybackOrder order =
            new PlaybackOrder(
                count,
                count / 2,
                PlaybackOrder.SHUFFLE,
                new Random(67),
                index -> WeightedShuffle.weight(index * 20L));
        for (int round = 0; round < 6; round++) {
          if (round > 0 || !forward) {
            int before = order.current();
            int item = forward ? order.next(false) : order.previous();
            if (count > 1) assertNotEquals(before, item);
          }
          HashSet<Integer> seen = new HashSet<>();
          seen.add(order.current());
          for (int step = 1; step < count; step++) {
            int before = order.current();
            int candidate = forward ? order.peekNext() : order.peekPrevious();
            assertEquals(before, order.current());
            assertEquals(candidate, forward ? order.next(false) : order.previous());
            assertTrue(seen.add(candidate));
          }
          assertEquals(count, seen.size());
        }
      }
    }
  }

  @Test
  public void changedPlayCountsDoNotInvalidatePreparedNeighborsOrRecentWeightedHistory() {
    long[] plays = {0, 1, 10, 100};
    int[] reads = new int[4];
    PlaybackOrder order =
        new PlaybackOrder(
            4,
            0,
            PlaybackOrder.SHUFFLE,
            new Random(78),
            index -> {
              reads[index]++;
              return WeightedShuffle.weight(plays[index]);
            });
    int next = order.peekNext();
    int previous = order.peekPrevious();
    for (int readCount : reads) assertEquals(2, readCount);
    Arrays.fill(plays, 1000);
    plays[3] = 0;
    for (int repeat = 0; repeat < 10; repeat++) {
      assertEquals(next, order.peekNext());
      assertEquals(previous, order.peekPrevious());
    }
    for (int readCount : reads) assertEquals(2, readCount);
    int[] path = new int[8];
    path[0] = order.current();
    for (int step = 1; step < path.length; step++) path[step] = order.next(false);
    for (int readCount : reads) assertEquals(3, readCount);
    for (int step = path.length - 2; step >= 0; step--) {
      assertEquals(path[step], order.peekPrevious());
      assertEquals(path[step], order.previous());
    }
    assertEquals(previous, order.peekPrevious());
    for (int readCount : reads) assertEquals(3, readCount);
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
