package io.mediamaster.app.player;

/** A liveness clock, never a throughput or seek-latency score. */
final class NativeDecodeProgress {
  private long decoded = -1, displayed = -1, frameToken, lastProgressMs;

  void reset(long nowMs, long frameToken) {
    decoded = displayed = -1;
    this.frameToken = frameToken;
    lastProgressMs = nowMs;
  }

  void observe(long nowMs, long decoded, long displayed, long frameToken) {
    boolean progress =
        (decoded >= 0 && this.decoded >= 0 && decoded > this.decoded)
            || (displayed >= 0 && this.displayed >= 0 && displayed > this.displayed)
            || (frameToken > 0 && frameToken != this.frameToken);
    if (progress || nowMs < lastProgressMs) lastProgressMs = nowMs;
    if (decoded >= 0) this.decoded = decoded;
    if (displayed >= 0) this.displayed = displayed;
    this.frameToken = frameToken;
  }

  long deadline(long noProgressMs) {
    return lastProgressMs + noProgressMs;
  }
}
