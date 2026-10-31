package io.mediamaster.app.player;

/** Bounded pacing for optional previews; the main playback decoder keeps priority. */
final class PreviewCadence {
  private long decodeCostMs;

  void onDecode(long elapsedMs) {
    long cost = Math.max(0, Math.min(4000, elapsedMs));
    // Slow work takes effect immediately. Recovery is gradual so alternating cheap/expensive
    // keyframes do not keep returning the worker to its fastest request rate.
    decodeCostMs = cost > decodeCostMs ? cost : (decodeCostMs * 3 + cost) / 4;
  }

  long delayMs(long now, long lastStarted, long lastCompleted) {
    long interval = Math.max(80, Math.min(400, decodeCostMs * 2));
    long recovery = Math.max(0, Math.min(200, (decodeCostMs - 80) / 2));
    // A decode longer than the start-to-start cap still gets a small recovery window instead of
    // continuously saturating the decoder with another optional thumbnail.
    return Math.max(0, Math.max(interval - (now - lastStarted), recovery - (now - lastCompleted)));
  }

  void reset() {
    decodeCostMs = 0;
  }
}
