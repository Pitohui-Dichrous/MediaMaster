package io.mediamaster.app.player;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import org.junit.Test;

public class WeightedShuffleTest {
  @Test
  public void countsReducePriorityGentlyWithoutOverflowOrZeroWeight() {
    assertEquals(1, WeightedShuffle.weight(0), 0);
    assertEquals(1, WeightedShuffle.weight(-10), 0);
    assertEquals(.5, WeightedShuffle.weight(3), 0);
    assertEquals(.1, WeightedShuffle.weight(99), 0);
    assertEquals(.01, WeightedShuffle.weight(Long.MAX_VALUE), 0);
    double last = 1;
    for (long count : new long[] {0, 1, 2, 10, 100, 1000, 10000, Long.MAX_VALUE}) {
      double weight = WeightedShuffle.weight(count);
      assertTrue(weight <= last);
      assertTrue(weight >= .01);
      last = weight;
    }
  }

  @Test
  public void homeChoiceUsesTheExactWeightIntervals() {
    // Deterministic midpoint quadrature: weights 1 : .5 : .1 occupy 10 : 5 : 1 of 16 cells.
    long[] plays = {0, 3, 99};
    int[] selected = new int[3];
    for (int draw = 0; draw < 16; draw++) {
      final double value = (draw + .5) / 16;
      int choice =
          WeightedShuffle.choose(3, index -> WeightedShuffle.weight(plays[index]), constant(value));
      selected[choice]++;
    }
    assertArrayEquals(new int[] {10, 5, 1}, selected);
  }

  @Test
  public void emptySingleAndInvalidWeightsRemainSafe() {
    assertEquals(
        -1,
        WeightedShuffle.choose(
            0,
            index -> {
              throw new AssertionError();
            },
            new Random(0)));
    assertEquals(
        0,
        WeightedShuffle.choose(1, index -> WeightedShuffle.weight(Long.MAX_VALUE), constant(.99)));
    double[] invalid = {Double.NaN, Double.NEGATIVE_INFINITY, 0};
    for (int item = 0; item < invalid.length; item++) {
      assertEquals(
          item, WeightedShuffle.choose(3, index -> invalid[index], constant((item + .5) / 3)));
    }
  }

  @Test
  public void initialRoundAnchorsCurrentAndPrioritizesLowerCountsWithMatchedRandomClocks() {
    long[] plays = {0, 99, 3, 0};
    ArrayList<Integer> round =
        WeightedShuffle.initialRound(
            4, 0, index -> WeightedShuffle.weight(plays[index]), constant(.5));
    assertEquals(Arrays.asList(0, 3, 2, 1), round);
  }

  @Test
  public void newRoundNeverDropsItsAdjacentItemAndReadsEachWeightOnce() {
    for (int adjacent = 0; adjacent < 5; adjacent++) {
      int[] reads = new int[5];
      ArrayList<Integer> round =
          WeightedShuffle.nextRound(
              5,
              adjacent,
              index -> {
                reads[index]++;
                return WeightedShuffle.weight(index * 20L);
              },
              new Random(7));
      assertEquals(5, round.size());
      assertEquals(5, new HashSet<>(round).size());
      assertNotEquals(adjacent, (int) round.get(0));
      for (int readsForItem : reads) assertEquals(1, readsForItem);
    }
  }

  @Test
  public void boundaryFirstChoiceUsesEligibleWeightsAndStillIncludesExcludedItemLater() {
    long[] plays = {0, 3, 99};
    int[] selected = new int[3];
    for (int draw = 0; draw < 6; draw++) {
      ArrayList<Integer> round =
          WeightedShuffle.nextRound(
              3, 0, index -> WeightedShuffle.weight(plays[index]), constant((draw + .5) / 6));
      selected[round.get(0)]++;
      assertEquals(3, new HashSet<>(round).size());
    }
    assertArrayEquals(new int[] {0, 5, 1}, selected);
  }

  @Test
  public void updatedCountsAffectNewChoicesWithoutMutatingPreviouslyPreparedRounds() {
    long[] plays = {0, 99};
    assertEquals(
        0, WeightedShuffle.choose(2, index -> WeightedShuffle.weight(plays[index]), constant(.5)));
    ArrayList<Integer> original =
        WeightedShuffle.initialRound(
            2, 0, index -> WeightedShuffle.weight(plays[index]), constant(.5));
    plays[0] = 9999;
    plays[1] = 0;
    assertEquals(
        1, WeightedShuffle.choose(2, index -> WeightedShuffle.weight(plays[index]), constant(.5)));
    assertEquals(Arrays.asList(0, 1), original);
  }

  private static Random constant(double value) {
    return new Random(0) {
      @Override
      public double nextDouble() {
        return value;
      }
    };
  }
}
