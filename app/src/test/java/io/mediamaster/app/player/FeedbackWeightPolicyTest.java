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
  public void sharedThreeDayDecayRestoresTheOrdinaryBaselineAsFeedbackBecomesOld() {
    long halfLife = RecentPlaybackPolicy.HALF_LIFE_MS;
    assertEquals(32, FeedbackWeightPolicy.decay(64, 100, 100 + halfLife), 0);
    double originalOffset = FeedbackWeightPolicy.weight(63, 64, 64, 0) - .125;
    double oldScore = RecentPlaybackPolicy.decay(63, 0, 20 * halfLife);
    double oldMass = FeedbackWeightPolicy.decay(64, 0, 20 * halfLife);
    double oldOffset =
        FeedbackWeightPolicy.weight(oldScore, oldMass, oldMass, 0)
            - WeightedShuffle.weight(oldScore);
    assertTrue(originalOffset > 1);
    assertTrue(oldOffset >= 0 && oldOffset < .00001);
  }

  @Test
  public void threeDayClockKeepsWatchingLikesDislikesAndUndoTokensInTheSameUnit() {
    long start = 1_700_000_000_000L;
    long threeDays = 3L * 24 * 60 * 60 * 1000;
    FeedbackMass original = FeedbackMass.of(32, 12, 4, 0);
    FeedbackMass decayed = original.decay(start, start + threeDays);
    assertEquals(16, RecentPlaybackPolicy.decay(32, start, start + threeDays), 0);
    assertEquals(16, decayed.exposure, 0);
    assertEquals(6, decayed.likes, 0);
    assertEquals(2, decayed.dislikes, 0);
    double oldVote = FeedbackMass.convert(1, 0, start, decayed.exponent, start + threeDays);
    assertEquals(.5, oldVote, 0);
    FeedbackMass cancelled = decayed.withVotes(decayed.likes - oldVote, decayed.dislikes);
    assertEquals(5.5, cancelled.likes, 0);
    assertEquals(decayed.exposure, cancelled.exposure, 0);
    assertEquals(decayed.dislikes, cancelled.dislikes, 0);
  }

  @Test
  public void shorteningMemoryChangesRecoveryTimeWithoutChangingFreshActionStrengthOrBounds() {
    long day = 24L * 60 * 60 * 1000;
    double fresh = FeedbackWeightPolicy.weight(8, 8, 4, 1);
    double afterThreeDays =
        FeedbackWeightPolicy.weight(
            RecentPlaybackPolicy.decay(8, 0, 3 * day),
            FeedbackMass.of(8, 4, 1, 0).decay(0, 3 * day));
    assertEquals(FeedbackWeightPolicy.weight(4, 4, 2, .5), afterThreeDays, 0);
    assertEquals(FeedbackWeightPolicy.weight(8, FeedbackMass.of(8, 4, 1, 0)), fresh, 0);
    for (long age : new long[] {0, day, 3 * day, 30 * day, Long.MAX_VALUE}) {
      double score = RecentPlaybackPolicy.decay(63, 0, age);
      for (FeedbackMass feedback :
          new FeedbackMass[] {
            FeedbackMass.of(1_000_000, 1_000_000, 0, 0), FeedbackMass.of(1_000_000, 0, 1_000_000, 0)
          }) {
        double weight = FeedbackWeightPolicy.weight(score, feedback.decay(0, age));
        assertTrue(Double.isFinite(weight));
        assertTrue(weight >= FeedbackWeightPolicy.MIN_WEIGHT);
        assertTrue(weight <= FeedbackWeightPolicy.MAX_WEIGHT);
      }
    }
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
