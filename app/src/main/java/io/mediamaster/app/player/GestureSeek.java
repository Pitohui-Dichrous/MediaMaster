package io.mediamaster.app.player;

/** The whole gesture is measured from touch-down, making reversal precise and predictable. */
public final class GestureSeek {
  private GestureSeek() {}

  public static long deltaMs(
      float distancePx, float usableWidthPx, long durationMs, float sensitivity) {
    if (usableWidthPx <= 0 || durationMs <= 0 || Float.isNaN(distancePx)) return 0;
    double amount =
        Math.min(
            1.15,
            Math.abs(distancePx) / usableWidthPx * Math.max(0.35f, Math.min(2f, sensitivity)));
    // The linear first 12 seconds preserve small corrections. The cubic term gives a
    // long, deliberate swipe much greater reach without a sudden speed threshold.
    double reach = Math.min(durationMs, 15 * 60_000L);
    double milliseconds = 12_000 * amount + reach * amount * amount * amount;
    // Keep sub-second motion continuous: snapping to whole seconds makes a short video's
    // scrubber jump a large physical distance, even while the finger moves smoothly.
    long rounded = Math.round(milliseconds);
    return distancePx < 0 ? -rounded : rounded;
  }

  public static long targetMs(long originMs, long deltaMs, long durationMs) {
    return Math.max(0, Math.min(Math.max(0, durationMs), originMs + deltaMs));
  }
}
