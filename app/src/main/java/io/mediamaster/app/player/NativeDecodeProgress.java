package io.mediamaster.app.player;

/** A liveness clock, never a throughput or seek-latency score. */
final class NativeDecodeProgress {
  private long decoded = -1, displayed = -1, frameToken, lastProgressMs;
  private boolean statsSeen;

  void reset(long nowMs, long frameToken) {
    decoded = displayed = -1;
    statsSeen = false;
    this.frameToken = frameToken;
    lastProgressMs = nowMs;
  }

  void observe(long nowMs, long decoded, long displayed, long frameToken) {
    // Once counters have demonstrated real activity, surface redraws cannot keep a stalled
    // decoder alive. Negative samples also mean "not queried this tick", not lost capability.
    // An implementation returning only zeroes has not established usable native statistics.
    statsSeen |= decoded > 0 || displayed > 0;
    boolean progress =
        (decoded >= 0 && this.decoded >= 0 && decoded > this.decoded)
            || (displayed >= 0 && this.displayed >= 0 && displayed > this.displayed)
            || (!statsSeen && frameToken > 0 && frameToken != this.frameToken);
    if (progress || nowMs < lastProgressMs) lastProgressMs = nowMs;
    if (decoded >= 0) this.decoded = decoded;
    if (displayed >= 0) this.displayed = displayed;
    this.frameToken = frameToken;
  }

  long deadline(long noProgressMs) {
    return lastProgressMs + noProgressMs;
  }
}
