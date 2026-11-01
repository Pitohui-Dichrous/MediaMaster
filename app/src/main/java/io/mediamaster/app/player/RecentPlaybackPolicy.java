package io.mediamaster.app.player;

/** A bounded recent-play score with a seven-day half-life; reads never change the saved record. */
public final class RecentPlaybackPolicy {
  public static final long HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000;
  public static final double MAX_SCORE = 63;

  private RecentPlaybackPolicy() {}

  public static double sanitizeScore(double score) {
    if (Double.isNaN(score) || score <= 0) return 0;
    return Math.min(MAX_SCORE, score);
  }

  /** A backward clock jump does not increase the score or fabricate elapsed time. */
  public static double decay(double score, long updatedAt, long now) {
    double bounded = sanitizeScore(score);
    if (bounded == 0 || now <= updatedAt) return bounded;
    long difference = now - updatedAt;
    // Ordered timestamps can still overflow signed subtraction across the long boundary.
    double elapsed = difference >= 0 ? difference : (double) now - (double) updatedAt;
    return bounded * Math.pow(.5, elapsed / HALF_LIFE_MS);
  }

  /** Persist the returned score with updatedAt=now, also reanchoring after a clock rollback. */
  public static double addPlay(double score, long updatedAt, long now) {
    return Math.min(MAX_SCORE, decay(score, updatedAt, now) + 1);
  }

  /**
   * Legacy rows have no individual play timestamps. Treat their bounded total as an estimate at
   * their last-play time; this must only initialize the recent score once during schema migration.
   */
  public static double migrateLegacy(long count, long lastPlayed, long now) {
    return decay(Math.min(MAX_SCORE, Math.max(0L, count)), lastPlayed, now);
  }
}
