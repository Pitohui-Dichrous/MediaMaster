package io.mediamaster.app.player;

/** Bounded, decaying feedback relative to recent actual viewing exposure. */
public final class FeedbackWeightPolicy {
  public static final double MIN_WEIGHT = 1.0 / 16, MAX_WEIGHT = 1.5;

  private FeedbackWeightPolicy() {}

  public static double sanitize(double value) {
    return FeedbackMass.finite(value);
  }

  public static double decay(double value, long updated, long now) {
    return FeedbackMass.convert(value, 0, updated, 0, now);
  }

  public static double weight(double recentScore, double exposure, double likes, double dislikes) {
    return weight(recentScore, FeedbackMass.of(exposure, likes, dislikes, 0));
  }

  public static double weight(double recentScore, FeedbackMass mass) {
    double base = WeightedShuffle.weight(recentScore);
    double affinity = mass.affinity();
    double value =
        affinity >= 0
            ? base + (MAX_WEIGHT - base) * affinity
            : base + (base - MIN_WEIGHT) * affinity;
    return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, value));
  }
}
