package io.mediamaster.app.player;

/** Native-first playback with a bounded, per-file compatibility fallback; not a latency ranking. */
final class SeekEnginePolicy {
  static final long FAILURE_COOLDOWN_MS = 24 * 60 * 60 * 1000L;
  long vlcFailureUntilMs;
  long softwareAvoidUntilMs;

  boolean shouldAvoidSoftware(long nowMs) {
    return softwareAvoidUntilMs > nowMs;
  }

  /** Record only after the replacement hardware-preferred player has rendered a real frame. */
  void recordSoftwareFallback(long nowMs) {
    softwareAvoidUntilMs = nowMs + FAILURE_COOLDOWN_MS;
  }

  boolean recordSoftwareSuccess() {
    boolean changed = softwareAvoidUntilMs != 0;
    softwareAvoidUntilMs = 0;
    return changed;
  }

  /** The same file uses the same policy before and after its preloaded page is promoted. */
  boolean chooseVlc(long nowMs) {
    return vlcFailureUntilMs <= nowMs;
  }

  static boolean shouldRememberVlcFailure(boolean foreground, boolean active, boolean nativeError) {
    return nativeError && (foreground || active);
  }

  /** Call only after a visible native playback error and an actual Media3 fallback frame. */
  void recordCompatibleFallback(long nowMs) {
    vlcFailureUntilMs = nowMs + FAILURE_COOLDOWN_MS;
  }

  boolean recordVlcSuccess() {
    boolean changed = vlcFailureUntilMs != 0;
    vlcFailureUntilMs = 0;
    return changed;
  }
}
