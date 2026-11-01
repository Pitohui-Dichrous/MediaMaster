package io.mediamaster.app.player;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import org.junit.Test;

public class WeightedShuffleTest {
  @Test
  public void recentScoresReducePriorityWithinTheProbabilityFloor() {
    assertEquals(1, WeightedShuffle.weight(0), 0);
    assertEquals(1, WeightedShuffle.weight(-10), 0);
    assertEquals(.5, WeightedShuffle.weight(3), 0);
    assertEquals(.125, WeightedShuffle.weight(63), 0);
    assertEquals(.125, WeightedShuffle.weight(Long.MAX_VALUE), 0);
    assertEquals(1 / Math.sqrt(1.5), WeightedShuffle.weight(.5), 0);
    assertEquals(1, WeightedShuffle.weight(Double.NaN), 0);
    assertEquals(.125, WeightedShuffle.weight(Double.POSITIVE_INFINITY), 0);
    double last = 1;
    for (long count : new long[] {0, 1, 2, 10, 100, 1000, 10000, Long.MAX_VALUE}) {
      double weight = WeightedShuffle.weight(count);
      assertTrue(weight <= last);
      assertTrue(weight >= .125);
      last = weight;
    }
  }

  @Test
  public void feedbackWeightsKeepTheirFullRangeInSamplerAndWeightedRounds() {
    double[] weights = {1.5, .5, .0625};
    int[] selected = new int[3];
    // Exact midpoint cells cover 1.5 : .5 : .0625, detecting either old clamp.
    for (int i = 0; i < 33; i++)
      selected[WeightedShuffle.choose(3, n -> weights[n], constant((i + .5) / 33))]++;
    assertArrayEquals(new int[] {24, 8, 1}, selected);
    assertEquals(
        Arrays.asList(0, 1, 2), WeightedShuffle.initialRound(3, 0, n -> weights[n], constant(.5)));
  }

  @Test
  public void leastFavoredCandidateStillHasAtLeastOneTwentyFourthOfUniformProbability() {
    int count = 50_000;
    double min = FeedbackWeightPolicy.MIN_WEIGHT, max = FeedbackWeightPolicy.MAX_WEIGHT;
    double probability = min / ((count - 1) * max + min);
    assertTrue(probability >= 1.0 / (24 * count));
    assertEquals(
        0, WeightedShuffle.choose(count, n -> n == 0 ? min : max, constant(probability * .5)));
    assertEquals(
        1, WeightedShuffle.choose(count, n -> n == 0 ? min : max, constant(probability * 1.01)));
  }

  @Test
  public void homeChoiceUsesTheExactWeightIntervals() {
    // Deterministic midpoint quadrature: weights 1 : .5 : .125 occupy 8 : 4 : 1 of 13 cells.
    long[] plays = {0, 3, 99};
    int[] selected = new int[3];
    for (int draw = 0; draw < 13; draw++) {
      final double value = (draw + .5) / 13;
      int choice =
          WeightedShuffle.choose(3, index -> WeightedShuffle.weight(plays[index]), constant(value));
      selected[choice]++;
    }
    assertArrayEquals(new int[] {8, 4, 1}, selected);
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
    for (int draw = 0; draw < 5; draw++) {
      ArrayList<Integer> round =
          WeightedShuffle.nextRound(
              3, 0, index -> WeightedShuffle.weight(plays[index]), constant((draw + .5) / 5));
      selected[round.get(0)]++;
      assertEquals(3, new HashSet<>(round).size());
    }
    assertArrayEquals(new int[] {0, 4, 1}, selected);
  }

  @Test
  public void allMinimumOrEqualWeightsAreUniformInsteadOfExhausted() {
    for (double weight : new double[] {.01, .125, .5, 1, Double.MAX_VALUE}) {
      for (int count : new int[] {1, 2, 17}) {
        for (int item = 0; item < count; item++) {
          assertEquals(
              item, WeightedShuffle.choose(count, index -> weight, constant((item + .5) / count)));
        }
      }
    }
  }

  @Test
  public void cappedVideoKeepsAtLeastOneEighthOfUniformChanceEvenInALargeLibrary() {
    int count = 50_000;
    double probability = .125 / (count - 1 + .125);
    assertTrue(probability >= .125 / count);
    assertEquals(
        0,
        WeightedShuffle.choose(count, index -> index == 0 ? .125 : 1, constant(probability * .5)));
    assertEquals(
        1,
        WeightedShuffle.choose(
            count, index -> index == 0 ? .125 : 1, constant(probability * 1.01)));
    assertEquals(
        count - 1,
        WeightedShuffle.choose(count, index -> Double.MAX_VALUE, constant(Math.nextDown(1.0))));
  }

  @Test
  public void decayingRecentScoresCanReverseTheRelativeSelectionPriority() {
    long halfLife = RecentPlaybackPolicy.HALF_LIFE_MS;
    double[] score = {63, 3};
    assertEquals(
        1, WeightedShuffle.choose(2, index -> WeightedShuffle.weight(score[index]), constant(.5)));
    score[0] = RecentPlaybackPolicy.decay(score[0], 0, 7 * halfLife);
    score[1] = RecentPlaybackPolicy.addPlay(score[1], 7 * halfLife, 7 * halfLife);
    assertEquals(
        0, WeightedShuffle.choose(2, index -> WeightedShuffle.weight(score[index]), constant(.5)));
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
