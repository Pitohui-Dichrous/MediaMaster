package io.mediamaster.app.player;

/**
 * Separates a standalone tap from a continuous like burst without pairwise trailing single taps.
 */
final class PortraitTapSequence {
  enum Release {
    IGNORED,
    PENDING_SINGLE,
    LIKE
  }

  private final long timeout, maxHold;
  private final double slopSquared;
  private long lastUp = -1, downAt = -1;
  private float lastX, lastY;
  private boolean pendingSingle, likeBurst, joining;

  PortraitTapSequence(long timeout, long maxHold, float doubleTapSlop) {
    this.timeout = Math.max(1, timeout);
    this.maxHold = Math.max(1, maxHold);
    slopSquared = (double) doubleTapSlop * doubleTapSlop;
  }

  /** Returns a previous standalone tap that was not part of this new press. */
  boolean down(long time, float x, float y) {
    if (downAt >= 0) cancel();
    double dx = x - lastX, dy = y - lastY;
    joining =
        lastUp >= 0
            && time >= lastUp
            && time - lastUp <= timeout
            && (likeBurst || dx * dx + dy * dy <= slopSquared);
    boolean previousSingle = pendingSingle && !joining;
    pendingSingle = false;
    if (!joining) likeBurst = false;
    downAt = time;
    return previousSingle;
  }

  /** A drag/hold/cancel must call cancel(); a valid short press commits only on release. */
  Release up(long time, float x, float y) {
    if (downAt < 0 || time < downAt || time - downAt >= maxHold) {
      cancel();
      return Release.IGNORED;
    }
    downAt = -1;
    lastUp = time;
    lastX = x;
    lastY = y;
    if (joining) {
      pendingSingle = false;
      likeBurst = true;
      joining = false;
      return Release.LIKE;
    }
    pendingSingle = true;
    return Release.PENDING_SINGLE;
  }

  boolean confirmSingle(long time) {
    if (!pendingSingle || downAt >= 0 || time < lastUp || time - lastUp < timeout) return false;
    cancel();
    return true;
  }

  long singleDeadline() {
    return lastUp + timeout;
  }

  void cancel() {
    pendingSingle = likeBurst = joining = false;
    lastUp = downAt = -1;
  }
}
