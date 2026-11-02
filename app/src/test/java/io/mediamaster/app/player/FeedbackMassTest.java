package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class FeedbackMassTest {
  private static final long HALF_LIFE = RecentPlaybackPolicy.HALF_LIFE_MS;

  @Test
  public void crossingTheMillionBoundaryRescalesTogetherWithoutAWeightJump() {
    FeedbackMass before = FeedbackMass.of(1_000_000, 750_000, 250_000, 0);
    FeedbackMass after = before.addExposure();
    assertTrue(after.exponent > before.exponent);
    assertEquals(1_000_001, unscaled(after.exposure, after), 0);
    assertEquals(750_000, unscaled(after.likes, after), 0);
    assertEquals(250_000, unscaled(after.dislikes, after), 0);
    assertEquals(3, after.likes / after.dislikes, 0);
    double oldWeight = FeedbackWeightPolicy.weight(63, before);
    double newWeight = FeedbackWeightPolicy.weight(63, after);
    assertTrue("One extra exposure must not increase affinity", newWeight <= oldWeight);
    assertEquals(
        "The storage scale boundary is not a preference boundary", oldWeight, newWeight, 1e-6);
  }

  @Test
  public void largeUnequalMassesKeepTheirActualRatiosInsteadOfIndependentClips() {
    FeedbackMass mass = FeedbackMass.of(8_000_000, 6_000_000, 2_000_000, 0);
    assertEquals(8_000_000, unscaled(mass.exposure, mass), 0);
    assertEquals(6_000_000, unscaled(mass.likes, mass), 0);
    assertEquals(2_000_000, unscaled(mass.dislikes, mass), 0);
    assertEquals(3, mass.likes / mass.dislikes, 0);
    FeedbackMass same = FeedbackMass.of(4_000_000, 3_000_000, 1_000_000, 1);
    assertEquals(mass.affinity(), same.affinity(), 0);
    assertEquals(FeedbackWeightPolicy.weight(63, mass), FeedbackWeightPolicy.weight(63, same), 0);
  }

  @Test
  public void mergingDifferentUnitsPreservesBothContributionsAndIsCommutative() {
    FeedbackMass small = FeedbackMass.of(12, 9, 3, 0);
    FeedbackMass large = FeedbackMass.of(500_000, 125_000, 375_000, 6);
    FeedbackMass merged = small.merge(large);
    FeedbackMass reverse = large.merge(small);
    assertEquals(32_000_012, unscaled(merged.exposure, merged), 0);
    assertEquals(8_000_009, unscaled(merged.likes, merged), 0);
    assertEquals(24_000_003, unscaled(merged.dislikes, merged), 0);
    assertEquals(merged.exponent, reverse.exponent);
    assertEquals(merged.exposure, reverse.exposure, 0);
    assertEquals(merged.likes, reverse.likes, 0);
    assertEquals(merged.dislikes, reverse.dislikes, 0);
    assertTrue(merged.affinity() < 0);
  }

  @Test
  public void longDecaySpendsTheCommonExponentBeforeAStandalonePowCouldUnderflow() {
    FeedbackMass huge = FeedbackMass.of(800_000, 600_000, 200_000, 1022);
    FeedbackMass decayed = huge.decay(0, 1050 * HALF_LIFE);
    assertEquals(0, decayed.exponent);
    assertEquals(Math.scalb(800_000.0, -28), decayed.exposure, 0);
    assertEquals(Math.scalb(600_000.0, -28), decayed.likes, 0);
    assertEquals(Math.scalb(200_000.0, -28), decayed.dislikes, 0);
    assertEquals(3, decayed.likes / decayed.dislikes, 0);
    assertTrue(
        "Substantial represented history must not disappear with pow underflow",
        decayed.affinity() > 0);
  }

  @Test
  public void threeDayDecayRestoresTheUnitAndNewEventsRegainTheirNaturalSize() {
    FeedbackMass original = FeedbackMass.of(800_000, 600_000, 200_000, 3);
    FeedbackMass halfLifeOld = original.decay(0, HALF_LIFE);
    assertEquals(original.unit() * 2, halfLifeOld.unit(), 0);
    assertEquals(
        unscaled(original.exposure, original) / 2, unscaled(halfLifeOld.exposure, halfLifeOld), 0);
    FeedbackMass ordinary = original.decay(0, 3 * HALF_LIFE);
    assertEquals(0, ordinary.exponent);
    assertEquals(1, ordinary.unit(), 0);
    FeedbackMass nextView = ordinary.addExposure();
    assertEquals(ordinary.exposure + 1, nextView.exposure, 0);
    assertEquals(ordinary.likes, nextView.likes, 0);
    assertEquals(ordinary.dislikes, nextView.dislikes, 0);
  }

  @Test
  public void malformedValuesCannotEraseOtherFinitePreferencesOrMakeNonFiniteWeights() {
    FeedbackMass repaired =
        FeedbackMass.of(Double.NaN, Double.MAX_VALUE, Double.MAX_VALUE / 4, Integer.MIN_VALUE);
    assertEquals(0, repaired.exposure, 0);
    assertEquals(4, repaired.likes / repaired.dislikes, 0);
    assertTrue(repaired.affinity() > 0);
    FeedbackMass infinite =
        FeedbackMass.of(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, Integer.MAX_VALUE);
    assertTrue(Double.isFinite(infinite.exposure));
    assertTrue(Double.isFinite(infinite.likes));
    assertTrue(infinite.unit() > 0);
    double weight = FeedbackWeightPolicy.weight(Double.POSITIVE_INFINITY, infinite);
    assertTrue(Double.isFinite(weight));
    assertTrue(weight > WeightedShuffle.weight(63));
    assertTrue(weight <= FeedbackWeightPolicy.MAX_WEIGHT);
    FeedbackMass empty =
        FeedbackMass.of(-1, Double.NaN, Double.NEGATIVE_INFINITY, Integer.MAX_VALUE);
    assertEquals(0, empty.exponent);
    assertEquals(0, empty.affinity(), 0);
  }

  @Test
  public void overflowAtTheRepresentationalCeilingKeepsACommonScaleAndPreferenceRatio() {
    FeedbackMass original =
        FeedbackMass.of(
            Double.MAX_VALUE,
            Double.MAX_VALUE / 2,
            Double.MAX_VALUE / 4,
            FeedbackMass.MAX_EXPONENT);
    FeedbackMass merged = original.merge(original);
    assertTrue(Double.isFinite(merged.exposure));
    assertTrue(Double.isFinite(merged.likes));
    assertTrue(Double.isFinite(merged.dislikes));
    assertEquals(2, merged.likes / merged.dislikes, 0);
    assertEquals(2, merged.exposure / merged.likes, 0);
    assertEquals(original.affinity(), merged.affinity(), 0);
    assertTrue(merged.unit() > 0);
  }

  @Test
  public void aWindowCanUndoItsDecayedContributionAfterAStorageScaleChange() {
    FeedbackMass baseline = FeedbackMass.of(1_000_000, 500_000, 200_000, 0);
    FeedbackMass voted = baseline.withVotes(baseline.likes + baseline.unit(), baseline.dislikes);
    FeedbackMass advanced = voted.addExposure();
    assertTrue(advanced.exponent > baseline.exponent);
    assertEquals(.5, FeedbackMass.convert(1, baseline.exponent, 0, advanced.exponent, 0), 0);
    long later = HALF_LIFE / 4;
    FeedbackMass current = advanced.decay(0, later);
    double contribution = FeedbackMass.convert(1, baseline.exponent, 0, current.exponent, later);
    FeedbackMass undone = current.withVotes(current.likes - contribution, current.dislikes);
    FeedbackMass expected = baseline.addExposure().decay(0, later);
    assertEquals(expected.exponent, undone.exponent);
    assertEquals(expected.exposure, undone.exposure, 1e-9);
    assertEquals(expected.likes, undone.likes, 1e-9);
    assertEquals(expected.dislikes, undone.dislikes, 1e-9);
    assertEquals(expected.affinity(), undone.affinity(), 1e-14);
  }

  @Test
  public void subnormalTokensRegainPrecisionWhenConvertedToALargerRepresentableUnit() {
    double expected = Math.scalb(Double.MIN_VALUE, 1000) / Math.sqrt(2);
    double converted = FeedbackMass.convert(Double.MIN_VALUE, 1000, 0, 0, HALF_LIFE / 2);
    assertTrue(converted > 0);
    assertEquals(
        "Fractional decay must be applied after preserving the subnormal significand",
        expected,
        converted,
        expected * 2e-15);
    FeedbackMass tiny =
        FeedbackMass.of(4 * Double.MIN_VALUE, 3 * Double.MIN_VALUE, Double.MIN_VALUE, 1000);
    assertEquals(3, tiny.likes / tiny.dislikes, 0);
    assertTrue(tiny.affinity() > 0);
  }

  @Test
  public void clockRollbackAndFullyExpiredHistoryRemainWellDefined() {
    FeedbackMass original = FeedbackMass.of(100, 60, 20, 0);
    FeedbackMass rollback = original.decay(Long.MAX_VALUE, Long.MIN_VALUE);
    assertEquals(original.exposure, rollback.exposure, 0);
    assertEquals(original.likes, rollback.likes, 0);
    assertEquals(original.dislikes, rollback.dislikes, 0);
    assertEquals(1, FeedbackMass.convert(1, 0, Long.MAX_VALUE, 0, Long.MIN_VALUE), 0);
    FeedbackMass expired = original.decay(Long.MIN_VALUE, Long.MAX_VALUE);
    assertEquals(0, expired.exposure, 0);
    assertEquals(0, expired.likes, 0);
    assertEquals(0, expired.dislikes, 0);
    assertEquals(0, expired.exponent);
    assertEquals(WeightedShuffle.weight(63), FeedbackWeightPolicy.weight(63, expired), 0);
  }

  @Test
  public void nearLongBoundariesFeedbackUsesTheSameMillisecondPrecisionAsWatching() {
    for (long start : new long[] {Long.MIN_VALUE, Long.MAX_VALUE - HALF_LIFE}) {
      long end = start + HALF_LIFE;
      FeedbackMass decayed = FeedbackMass.of(8, 6, 2, 0).decay(start, end);
      assertEquals(RecentPlaybackPolicy.decay(8, start, end), decayed.exposure, 0);
      assertEquals(4, decayed.exposure, 0);
      assertEquals(3, decayed.likes, 0);
      assertEquals(1, decayed.dislikes, 0);
      assertEquals(.5, FeedbackMass.convert(1, 0, start, 0, end), 0);
    }
    long start = Long.MAX_VALUE - 1, end = Long.MAX_VALUE;
    double expected = RecentPlaybackPolicy.decay(1, start, end);
    assertTrue(expected < 1);
    assertEquals(expected, FeedbackMass.convert(1, 0, start, 0, end), 0);
  }

  private static double unscaled(double value, FeedbackMass mass) {
    return Math.scalb(value, mass.exponent);
  }
}
