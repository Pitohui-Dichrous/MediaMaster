package io.mediamaster.app.player;

/** Sustained playback health only; these counters cannot measure exact-seek completion. */
final class SoftwareDecodeHealthPolicy {
  private static final long WINDOW_MS = 2000;
  private long startedAt = -1, decoded, displayed, lost, mediaTime;
  private int badWindows;

  void reset() {
    startedAt = -1;
    badWindows = 0;
  }

  /** Caller resets on seek, looping, pause, buffering, page changes, background or non-1x speed. */
  boolean update(
      long nowMs,
      long decoded,
      long displayed,
      long lost,
      float expectedFps,
      float displayHz,
      long mediaTimeMs) {
    if (decoded < 0
        || displayed <= 0
        || lost < 0
        || !(expectedFps > 0)
        || Float.isInfinite(expectedFps)
        || !(displayHz > 0)
        || Float.isInfinite(displayHz)) {
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
      baseline(nowMs, decoded, displayed, lost, mediaTimeMs);
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
      baseline(nowMs, decoded, displayed, lost, mediaTimeMs);
      return false;
    }
    // A 60 Hz screen cannot present all 120 frames per second of an otherwise healthy stream.
    // Only count missing pictures that the current display could actually have presented.
    double presentable = Math.min((double) displayedDelta + lostDelta, displayHz * elapsed / 1000d);
    boolean dropping = presentable >= 20 && displayedDelta < presentable * .90;
    // Nominal FPS may describe the fastest part of a variable-rate track. Low output without
    // losses is evidence of trouble only if the 1x media clock also falls behind real time.
    boolean clockBehind =
        mediaTime >= 0 && mediaTimeMs >= mediaTime && mediaTimeMs - mediaTime < elapsed * .75;
    boolean underRate =
        clockBehind
            && decodedDelta > 0
            && displayedDelta * 1000d / elapsed < Math.min(expectedFps, displayHz) * .75;
    badWindows = dropping || underRate ? badWindows + 1 : 0;
    baseline(nowMs, decoded, displayed, lost, mediaTimeMs);
    return badWindows >= 2;
  }

  private void baseline(long nowMs, long decoded, long displayed, long lost, long mediaTimeMs) {
    startedAt = nowMs;
    this.decoded = decoded;
    this.displayed = displayed;
    this.lost = lost;
    mediaTime = mediaTimeMs;
  }
}
