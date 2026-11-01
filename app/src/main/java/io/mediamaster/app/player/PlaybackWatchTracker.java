package io.mediamaster.app.player;

/** Accumulates qualified wall-clock viewing within one entry, never a media-position delta. */
public final class PlaybackWatchTracker {
  public static final long REQUIRED_WATCH_MS = 3000;
  private static final long QUALIFIED_MS = REQUIRED_WATCH_MS + 1;
  private long watchedMs, anchorMs;
  private boolean running, recorded;

  /**
   * Restored entries deliberately start stopped: elapsed background/process time is not viewing.
   */
  public void restore(long watchedMs, boolean recorded) {
    this.watchedMs = Math.max(0, Math.min(QUALIFIED_MS, watchedMs));
    this.recorded = recorded;
    running = false;
    anchorMs = 0;
  }

  public void reset() {
    restore(0, false);
  }

  /**
   * Settle the previously qualified interval, then start or stop the next interval. Returns true
   * exactly once, after strictly more than three seconds of actual viewing.
   */
  public boolean update(boolean eligible, long elapsedRealtimeMs) {
    if (!recorded && running && elapsedRealtimeMs >= 0 && elapsedRealtimeMs >= anchorMs) {
      long elapsed = elapsedRealtimeMs - anchorMs;
      // Both monotonic timestamps are nonnegative, so subtraction cannot overflow. Only the
      // threshold matters; clamping before addition also protects corrupt restored values.
      watchedMs = Math.min(QUALIFIED_MS, watchedMs + Math.min(QUALIFIED_MS, elapsed));
    }
    running = !recorded && eligible && elapsedRealtimeMs >= 0;
    anchorMs = elapsedRealtimeMs;
    if (!recorded && watchedMs > REQUIRED_WATCH_MS) {
      recorded = true;
      running = false;
      return true;
    }
    return false;
  }

  public long watchedMs() {
    return watchedMs;
  }

  public boolean recorded() {
    return recorded;
  }
}
