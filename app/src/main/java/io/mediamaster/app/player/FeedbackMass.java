package io.mediamaster.app.player;

/** A common binary unit for all feedback masses. Scaling never independently clips a component. */
public final class FeedbackMass {
  public static final double RESCALE_AT = 1_000_000;
  public static final int MAX_EXPONENT = 1022;
  public final double exposure, likes, dislikes;
  public final int exponent;

  private FeedbackMass(double exposure, double likes, double dislikes, int exponent) {
    this.exposure = exposure;
    this.likes = likes;
    this.dislikes = dislikes;
    this.exponent = exponent;
  }

  public static FeedbackMass of(double exposure, double likes, double dislikes, int exponent) {
    exposure = finite(exposure);
    likes = finite(likes);
    dislikes = finite(dislikes);
    exponent = Math.max(0, Math.min(MAX_EXPONENT, exponent));
    double maximum = Math.max(exposure, Math.max(likes, dislikes));
    if (maximum == 0) return new FeedbackMass(0, 0, 0, 0);
    if (maximum > RESCALE_AT && exponent < MAX_EXPONENT) {
      int shift =
          Math.min(
              MAX_EXPONENT - exponent,
              Math.max(1, Math.getExponent(maximum) - Math.getExponent(RESCALE_AT) + 1));
      exposure = Math.scalb(exposure, -shift);
      likes = Math.scalb(likes, -shift);
      dislikes = Math.scalb(dislikes, -shift);
      exponent += shift;
      maximum = Math.scalb(maximum, -shift);
    }
    if (maximum <= RESCALE_AT / 2 && exponent > 0) {
      int shift =
          Math.min(exponent, Math.max(0, Math.getExponent(RESCALE_AT) - Math.getExponent(maximum)));
      if (Math.scalb(maximum, shift) > RESCALE_AT) shift--;
      if (shift > 0) {
        exposure = Math.scalb(exposure, shift);
        likes = Math.scalb(likes, shift);
        dislikes = Math.scalb(dislikes, shift);
        exponent -= shift;
      }
    }
    return new FeedbackMass(exposure, likes, dislikes, exponent);
  }

  public double unit() {
    return Math.scalb(1.0, -exponent);
  }

  public FeedbackMass decay(long updated, long now) {
    double halves = halfLives(updated, now);
    // Spend whole half-lives by restoring a larger unit first. This prevents an intermediate
    // pow() underflow from erasing masses whose unscaled value is still substantial.
    int restore = (int) Math.min(exponent, Math.floor(halves));
    return of(
        scale(exposure, -(halves - restore)),
        scale(likes, -(halves - restore)),
        scale(dislikes, -(halves - restore)),
        exponent - restore);
  }

  public FeedbackMass addExposure() {
    return of(exposure + unit(), likes, dislikes, exponent);
  }

  public FeedbackMass withVotes(double likes, double dislikes) {
    return of(exposure, likes, dislikes, exponent);
  }

  public FeedbackMass merge(FeedbackMass other) {
    int shared = Math.max(exponent, other.exponent);
    double e1 = Math.scalb(exposure, exponent - shared),
        e2 = Math.scalb(other.exposure, other.exponent - shared);
    double l1 = Math.scalb(likes, exponent - shared),
        l2 = Math.scalb(other.likes, other.exponent - shared);
    double d1 = Math.scalb(dislikes, exponent - shared),
        d2 = Math.scalb(other.dislikes, other.exponent - shared);
    // Only malformed or physically unreachable extreme inputs can hit the exponent ceiling.
    // At that representational ceiling, retain component ratios with one common half-scale,
    // never independent saturation or a reset to zero.
    boolean overflow =
        e1 > Double.MAX_VALUE - e2 || l1 > Double.MAX_VALUE - l2 || d1 > Double.MAX_VALUE - d2;
    if (overflow) {
      e1 *= .5;
      e2 *= .5;
      l1 *= .5;
      l2 *= .5;
      d1 *= .5;
      d2 *= .5;
      if (shared < MAX_EXPONENT) shared++;
    }
    return of(e1 + e2, l1 + l2, d1 + d2, shared);
  }

  public double affinity() {
    double prior = 16 * unit();
    double common = Math.max(prior, Math.max(exposure, Math.max(likes, dislikes)));
    double e = exposure / common, l = likes / common, d = dislikes / common;
    return (l - d) / (prior / common + Math.max(e, l + d));
  }

  /** Express a decayed contribution in a destination record's current binary unit. */
  public static double convert(
      double amount, int fromExponent, long updated, int toExponent, long now) {
    return scale(amount, (double) fromExponent - toExponent - halfLives(updated, now));
  }

  public static double finite(double value) {
    if (Double.isNaN(value) || value <= 0) return 0;
    return Math.min(Double.MAX_VALUE, value);
  }

  private static double halfLives(long updated, long now) {
    return now <= updated
        ? 0
        : ((double) now - (double) updated) / RecentPlaybackPolicy.HALF_LIFE_MS;
  }

  private static double scale(double value, double power) {
    value = finite(value);
    if (value == 0) return 0;
    // Normalize before the fractional multiplication; otherwise a subnormal may round away
    // its fraction before a later positive scale restores it to a normal value.
    int sourcePower = value < Double.MIN_NORMAL ? -1074 : Math.getExponent(value);
    double significand = Math.scalb(value, -sourcePower);
    power += sourcePower;
    if (power < -2200) return 0;
    if (power > 2200) return Double.MAX_VALUE;
    int whole = (int) Math.floor(power);
    double fraction = Math.pow(2, power - whole);
    return finite(Math.scalb(significand * fraction, whole));
  }
}
