package io.mediamaster.app.player;

/**
 * Response of settled seeks on the active software player. The native clock alone is not output.
 */
final class CommittedSeekHealth {
  private long start = -1, lastProbe, lastSlow;
  private int consecutiveSlow;

  void begin(long now) {
    start = lastProbe = now;
  }

  void discardSample() {
    start = -1;
  }

  void reset() {
    discardSample();
    consecutiveSlow = 0;
  }

  void probe(long now) {
    // A delayed UI thread cannot tell when the surface callback really became available.
    if (start >= 0 && (now < lastProbe || now - lastProbe > 250)) discardSample();
    lastProbe = now;
  }

  /** Called only after fresh output and the seek queue's target-clock check both succeed. */
  boolean acknowledged(long now) {
    probe(now);
    if (start < 0) return false;
    long elapsed = now - start;
    discardSample();
    if (elapsed < 650) {
      consecutiveSlow = 0;
      return false;
    }
    if (now < lastSlow || now - lastSlow > 30_000) consecutiveSlow = 0;
    lastSlow = now;
    return ++consecutiveSlow >= 3;
  }
}
