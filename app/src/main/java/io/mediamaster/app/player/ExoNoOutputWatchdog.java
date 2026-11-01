package io.mediamaster.app.player;

/** No-output deadline only; a clock, READY state, or this monitor never proves seek accuracy. */
final class ExoNoOutputWatchdog {
  static final long INITIAL_DEADLINE_MS = 8000, SEEK_DEADLINE_MS = 4000;
  private long noProgressSince = -1, sequence = -1, presentationUs = -1;

  static boolean canObserve(
      boolean attached, boolean windowVisible, boolean active, boolean awaitingFirstFrame) {
    // A paused neighbor must produce its first picture before it is eligible for promotion.
    return attached && windowVisible && (awaitingFirstFrame || active);
  }

  boolean expired(
      long nowMs,
      boolean awaitingFirstFrame,
      boolean awaitingSeek,
      long frameSequence,
      long ptsUs) {
    if (!awaitingFirstFrame && !awaitingSeek) {
      reset();
      return false;
    }
    boolean realOutput = frameSequence > sequence && ptsUs >= 0 && ptsUs != presentationUs;
    if (noProgressSince < 0 || (sequence >= 0 && realOutput)) noProgressSince = nowMs;
    sequence = frameSequence;
    presentationUs = ptsUs;
    return nowMs - noProgressSince >= (awaitingFirstFrame ? INITIAL_DEADLINE_MS : SEEK_DEADLINE_MS);
  }

  /** Hidden/detached pages and inactive completed pages restart when eligible again. */
  void reset() {
    noProgressSince = sequence = presentationUs = -1;
  }
}
