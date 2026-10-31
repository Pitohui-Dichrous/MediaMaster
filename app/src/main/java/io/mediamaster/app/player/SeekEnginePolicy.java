package io.mediamaster.app.player;

/** Conservative per-file selection. A slow seek is evidence about Exo, not proof VLC is faster. */
final class SeekEnginePolicy {
  static final int MIN_SLOW_SAMPLES = 3;
  static final long SLOW_SEEK_MS = 450;
  int samples, slowSamples;
  double meanMs;
  boolean exoFailed, vlcFailed, vlcReady, trialUsed;
  long vlcFailureUntilMs;

  boolean chooseVlc(boolean foreground) {
    if (vlcFailed && vlcFailureUntilMs > 0 && System.currentTimeMillis() >= vlcFailureUntilMs)
      vlcFailed = false;
    if (vlcFailed) return false;
    if (exoFailed && vlcReady) return true;
    if (foreground && !trialUsed && slowSamples >= MIN_SLOW_SAMPLES && meanMs >= SLOW_SEEK_MS) {
      trialUsed = true;
      return true;
    }
    return false;
  }

  void recordVlcFailure() {
    vlcFailed = true;
    vlcFailureUntilMs = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
  }

  void recordExoSeek(long elapsedMs) {
    if (elapsedMs < 0 || elapsedMs > 3000) return;
    int weight = Math.min(samples, 15);
    meanMs = (meanMs * weight + elapsedMs) / (weight + 1);
    samples = Math.min(samples + 1, 16);
    if (elapsedMs >= SLOW_SEEK_MS) slowSamples = Math.min(slowSamples + 1, 16);
    else slowSamples = Math.max(0, slowSamples - 1);
  }
}
