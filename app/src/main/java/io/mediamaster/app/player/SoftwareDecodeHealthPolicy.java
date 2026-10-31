package io.mediamaster.app.player;

/** Sustained playback health only; these counters cannot measure exact-seek completion. */
final class SoftwareDecodeHealthPolicy {
  private static final long WINDOW_MS = 2000;
  private long startedAt = -1, decoded, displayed, lost;
  private int badWindows;

  void reset() {
    startedAt = -1;
    badWindows = 0;
  }

  /** Caller resets on seek, looping, pause, buffering, page changes, background or non-1x speed. */
  boolean update(long nowMs, long decoded, long displayed, long lost, float expectedFps) {
    if (decoded < 0
        || displayed <= 0
        || lost < 0
        || !(expectedFps > 0)
        || Float.isInfinite(expectedFps)) {
      reset();
      return false;
    }
    if (startedAt < 0
        || nowMs < startedAt
        || nowMs - startedAt > 6000
        || decoded < this.decoded
        || displayed < this.displayed
        || lost < this.lost) {
      reset();
      baseline(nowMs, decoded, displayed, lost);
      return false;
    }
    long elapsed = nowMs - startedAt;
    if (elapsed < WINDOW_MS) return false;
    long decodedDelta = decoded - this.decoded;
    long displayedDelta = displayed - this.displayed;
    long lostDelta = lost - this.lost;
    // Absent/stale statistics are not a measured failure. Do not promote a frozen counter to a
    // decoder diagnosis, and do not carry an old poor window across an unobserved interval.
    if (decodedDelta == 0 && displayedDelta == 0 && lostDelta == 0) {
      reset();
      baseline(nowMs, decoded, displayed, lost);
      return false;
    }
    long pictures = displayedDelta + lostDelta;
    boolean dropping = pictures >= 20 && lostDelta / (double) pictures > .10;
    boolean underRate = decodedDelta > 0 && displayedDelta * 1000d / elapsed < expectedFps * .75;
    badWindows = dropping || underRate ? badWindows + 1 : 0;
    baseline(nowMs, decoded, displayed, lost);
    return badWindows >= 2;
  }

  private void baseline(long nowMs, long decoded, long displayed, long lost) {
    startedAt = nowMs;
    this.decoded = decoded;
    this.displayed = displayed;
    this.lost = lost;
  }
}
