package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class FeedbackWeightPolicyTest {
  @Test
  public void noVotesOrBalancedVotesLeaveTheExistingWatchingWeightUntouched() {
    for (double score : new double[] {0, .5, 3, 63}) {
      double base = WeightedShuffle.weight(score);
      assertEquals(base, FeedbackWeightPolicy.weight(score, 500, 0, 0), 0);
      assertEquals(base, FeedbackWeightPolicy.weight(score, 500, 37, 37), 0);
    }
  }

  @Test
  public void firstVoteIsSmallButRepeatedPositiveExposureEventuallyOvercomesWatchPenalty() {
    assertEquals(1 + .5 / 17, FeedbackWeightPolicy.weight(0, 0, 1, 0), 1e-12);
    assertEquals(1 - .9375 / 17, FeedbackWeightPolicy.weight(0, 0, 0, 1), 1e-12);
    assertEquals(.125 + 1.375 * 64 / 80, FeedbackWeightPolicy.weight(63, 64, 64, 0), 1e-12);
    assertTrue(FeedbackWeightPolicy.weight(63, 64, 64, 0) > 1);
    assertTrue(FeedbackWeightPolicy.weight(63, 64, 0, 64) < .125);
  }

  @Test
  public void sparseFeedbackCannotExploitTheSixtyThreePointWatchingCap() {
    double sparse = FeedbackWeightPolicy.weight(63, 10_000, 4, 0);
    assertTrue(sparse > .125);
    assertTrue(sparse < .126);
    assertTrue(FeedbackWeightPolicy.weight(63, 100, 4, 0) > sparse);
  }

  @Test
  public void sharedSevenDayDecayRestoresTheOrdinaryBaselineAsFeedbackBecomesOld() {
    long week = RecentPlaybackPolicy.HALF_LIFE_MS;
    assertEquals(32, FeedbackWeightPolicy.decay(64, 100, 100 + week), 0);
    double originalOffset = FeedbackWeightPolicy.weight(63, 64, 64, 0) - .125;
    double oldScore = RecentPlaybackPolicy.decay(63, 0, 20 * week);
    double oldMass = FeedbackWeightPolicy.decay(64, 0, 20 * week);
    double oldOffset =
        FeedbackWeightPolicy.weight(oldScore, oldMass, oldMass, 0)
            - WeightedShuffle.weight(oldScore);
    assertTrue(originalOffset > 1);
    assertTrue(oldOffset >= 0 && oldOffset < .00001);
  }

  @Test
  public void malformedMassExtremeTimeAndHugeCountsRemainFiniteAndBounded() {
    for (double mass : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
      for (int sign : new int[] {-1, 1}) {
        double weight =
            FeedbackWeightPolicy.weight(
                Double.POSITIVE_INFINITY, mass, sign > 0 ? mass : 0, sign < 0 ? mass : 0);
        assertTrue(Double.isFinite(weight));
        assertTrue(weight >= .0625 && weight <= 1.5);
      }
    }
    assertEquals(Double.MAX_VALUE, FeedbackWeightPolicy.sanitize(Double.POSITIVE_INFINITY), 0);
    assertEquals(0, FeedbackWeightPolicy.decay(1_000_000, 0, Long.MAX_VALUE), 0);
    assertEquals(7, FeedbackWeightPolicy.decay(7, Long.MAX_VALUE, 0), 0);
    assertEquals(0, FeedbackWeightPolicy.decay(7, Long.MIN_VALUE, Long.MAX_VALUE), 0);
  }

  @Test
  public void positiveAndNegativeRatiosAreMonotoneAndNeverReachZeroChance() {
    double previousLike = .125, previousDislike = .125;
    for (int votes = 0; votes <= 1000; votes++) {
      double like = FeedbackWeightPolicy.weight(63, votes, votes, 0);
      double dislike = FeedbackWeightPolicy.weight(63, votes, 0, votes);
      assertTrue(like >= previousLike && like < 1.5);
      assertTrue(dislike <= previousDislike && dislike > .0625);
      previousLike = like;
      previousDislike = dislike;
    }
  }
}
