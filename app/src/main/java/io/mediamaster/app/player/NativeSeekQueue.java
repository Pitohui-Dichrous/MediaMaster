package io.mediamaster.app.player;

/** One submitted native seek and one replaceable latest request, across touch sessions. */
final class NativeSeekQueue {
  static final class Request {
    final long id, positionMs;
    final boolean fast;

    private Request(long id, long positionMs, boolean fast) {
      this.id = id;
      this.positionMs = positionMs;
      this.fast = fast;
    }
  }

  private long serial, submittedAtMs;
  private Request submitted, pending;

  /**
   * New intent replaces old pending work; an accurate final request cannot be lost to fast seek.
   */
  void offer(long positionMs, boolean fast) {
    long target = Math.max(0, positionMs);
    if (submitted != null && submitted.positionMs == target && (!submitted.fast || fast)) {
      pending = null;
      return;
    }
    if (pending != null && pending.positionMs == target && pending.fast == fast) return;
    pending = new Request(++serial, target, fast);
  }

  /** The first request is immediate. A timer alone never permits another decoder flush. */
  Request take(long nowMs) {
    if (submitted != null || pending == null) return null;
    submitted = pending;
    pending = null;
    submittedAtMs = nowMs;
    return submitted;
  }

  /**
   * Caller must supply fresh output evidence for this request, not merely an updated input clock.
   */
  boolean acknowledge(long requestId) {
    if (submitted == null || submitted.id != requestId) return false;
    submitted = null;
    return true;
  }

  Request submitted() {
    return submitted;
  }

  boolean hasPending() {
    return pending != null;
  }

  boolean hasWork() {
    return submitted != null || pending != null;
  }

  long latestPositionMs() {
    Request latest = pending != null ? pending : submitted;
    return latest == null ? -1 : latest.positionMs;
  }

  /** A watchdog may recover a stuck engine; elapsed time is not an acknowledgement. */
  boolean isStalled(long nowMs, long timeoutMs) {
    return submitted != null && nowMs - submittedAtMs >= timeoutMs;
  }

  /** Serial numbers remain unique so old surface callbacks cannot acknowledge a replacement. */
  void reset() {
    submitted = pending = null;
    submittedAtMs = 0;
  }
}
