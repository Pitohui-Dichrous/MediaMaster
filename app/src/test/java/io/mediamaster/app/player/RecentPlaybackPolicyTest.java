package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentPlaybackPolicyTest {
  private static final long WEEK = RecentPlaybackPolicy.HALF_LIFE_MS;
  private static final long START = 1_700_000_000_000L;

  @Test
  public void oldPlaysLoseHalfTheirInfluenceEverySevenDays() {
    assertEquals(7L * 24 * 60 * 60 * 1000, WEEK);
    assertEquals(12, RecentPlaybackPolicy.decay(12, START, START), 0);
    assertEquals(6, RecentPlaybackPolicy.decay(12, START, START + WEEK), 0);
    assertEquals(3, RecentPlaybackPolicy.decay(12, START, START + 2 * WEEK), 0);
    assertEquals(12 / Math.sqrt(2), RecentPlaybackPolicy.decay(12, START, START + WEEK / 2), 1e-12);
  }

  @Test
  public void newPlayAddsOnlyItsOwnFreshInfluence() {
    double atWeekOne = RecentPlaybackPolicy.addPlay(8, START, START + WEEK);
    assertEquals(5, atWeekOne, 0);
    assertEquals(2.5, RecentPlaybackPolicy.decay(atWeekOne, START + WEEK, START + 2 * WEEK), 0);
    // Four old plays plus one fresh play are not equivalent to five fresh plays.
    assertTrue(atWeekOne < RecentPlaybackPolicy.addPlay(8, START + WEEK, START + WEEK));
  }

  @Test
  public void unusedVideoRecoversInsteadOfKeepingItsHistoricalPenalty() {
    double oldScore = RecentPlaybackPolicy.MAX_SCORE;
    double original = WeightedShuffle.weight(oldScore);
    double afterWeek =
        WeightedShuffle.weight(RecentPlaybackPolicy.decay(oldScore, START, START + WEEK));
    double afterFourWeeks =
        WeightedShuffle.weight(RecentPlaybackPolicy.decay(oldScore, START, START + 4 * WEEK));
    double afterYear =
        WeightedShuffle.weight(RecentPlaybackPolicy.decay(oldScore, START, START + 52 * WEEK));
    assertEquals(.125, original, 0);
    assertTrue(afterWeek > original);
    assertTrue(afterFourWeeks > .45);
    assertTrue(afterYear > .9999999);
    assertTrue(afterYear > afterFourWeeks);
  }

  @Test
  public void repeatedPlaysAreBoundedAndCanRecoverFromTheCap() {
    double score = 0;
    for (int i = 0; i < 100_000; i++) score = RecentPlaybackPolicy.addPlay(score, START, START);
    assertEquals(63, score, 0);
    assertEquals(31.5, RecentPlaybackPolicy.decay(score, START, START + WEEK), 0);
    assertEquals(32.5, RecentPlaybackPolicy.addPlay(score, START, START + WEEK), 0);
  }

  @Test
  public void sanitizationAndHugeTimeIntervalsNeverProduceInvalidScores() {
    assertEquals(0, RecentPlaybackPolicy.sanitizeScore(Double.NaN), 0);
    assertEquals(0, RecentPlaybackPolicy.sanitizeScore(Double.NEGATIVE_INFINITY), 0);
    assertEquals(0, RecentPlaybackPolicy.sanitizeScore(-1), 0);
    assertEquals(63, RecentPlaybackPolicy.sanitizeScore(Double.POSITIVE_INFINITY), 0);
    assertEquals(63, RecentPlaybackPolicy.sanitizeScore(Double.MAX_VALUE), 0);
    assertEquals(0, RecentPlaybackPolicy.decay(63, Long.MIN_VALUE, Long.MAX_VALUE), 0);
    assertEquals(1, RecentPlaybackPolicy.addPlay(63, Long.MIN_VALUE, Long.MAX_VALUE), 0);
    assertEquals(0, RecentPlaybackPolicy.decay(0, Long.MIN_VALUE, Long.MAX_VALUE), 0);
  }

  @Test
  public void smallElapsedTimeNearLongBoundaryRetainsMillisecondPrecision() {
    assertEquals(4, RecentPlaybackPolicy.decay(8, Long.MAX_VALUE - WEEK, Long.MAX_VALUE), 0);
    assertEquals(4, RecentPlaybackPolicy.decay(8, Long.MIN_VALUE, Long.MIN_VALUE + WEEK), 0);
    assertTrue(RecentPlaybackPolicy.decay(8, Long.MAX_VALUE - 1, Long.MAX_VALUE) < 8);
  }

  @Test
  public void clockRollbackDoesNotInflateAndNextPlayReanchorsTheScore() {
    assertEquals(8, RecentPlaybackPolicy.decay(8, START + WEEK, START), 0);
    double reanchored = RecentPlaybackPolicy.addPlay(8, START + WEEK, START);
    assertEquals(9, reanchored, 0);
    assertEquals(4.5, RecentPlaybackPolicy.decay(reanchored, START, START + WEEK), 0);
    assertEquals(63, RecentPlaybackPolicy.decay(63, Long.MAX_VALUE, Long.MIN_VALUE), 0);
  }

  @Test
  public void legacyMigrationBoundsTheEstimateWithoutOverflowOrInventingIndividualDates() {
    assertEquals(0, RecentPlaybackPolicy.migrateLegacy(Long.MIN_VALUE, START, START), 0);
    assertEquals(63, RecentPlaybackPolicy.migrateLegacy(Long.MAX_VALUE, START, START), 0);
    assertEquals(31.5, RecentPlaybackPolicy.migrateLegacy(Long.MAX_VALUE, START, START + WEEK), 0);
    assertEquals(1.5, RecentPlaybackPolicy.migrateLegacy(3, START, START + WEEK), 0);
    assertEquals(
        0, RecentPlaybackPolicy.migrateLegacy(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE), 0);
  }

  @Test
  public void readingAtAnyFrequencyDoesNotChangeFutureDecay() {
    double saved = 7.25;
    for (int hour = 0; hour <= 7 * 24; hour++) {
      RecentPlaybackPolicy.decay(saved, START, START + hour * WEEK / (7 * 24));
    }
    assertEquals(3.625, RecentPlaybackPolicy.decay(saved, START, START + WEEK), 0);
    assertEquals(saved, RecentPlaybackPolicy.decay(saved, START, START), 0);
  }
}
