package io.mediamaster.app.player;

/** Screen-relative portrait paging physics, independent of touch routing and playback order. */
public final class PortraitSwipe {
  private static final float MAX_OFFSET_FRACTION = .85f;
  private static final float DISTANCE_FRACTION = .16f;
  private static final float MIN_DISTANCE_DP = 56f;
  private static final float MIN_FLING_DISTANCE_DP = 32f;
  private static final float MIN_FLING_VELOCITY_DP = 1500f;

  private PortraitSwipe() {}

  /**
   * Returns the visual translation in pixels, with the same sign as finger motion. The initial
   * movement follows the finger directly; resistance grows continuously toward an 85% height limit.
   * Compute this from touch-down displacement, rather than accumulating per-frame deltas, so
   * reversal retraces the same path without a jump.
   */
  public static float offset(float distanceY, float screenHeight) {
    if (!finite(distanceY) || !finite(screenHeight) || screenHeight <= 0) return 0;
    double limit = screenHeight * (double) MAX_OFFSET_FRACTION;
    double magnitude = limit * -Math.expm1(-Math.abs((double) distanceY) / limit);
    return Math.copySign((float) Math.min(limit, magnitude), distanceY);
  }

  /**
   * Returns +1 for the next video (up), -1 for the previous video (down), or 0 to settle back.
   * Distance and velocity are signed screen pixels and pixels/second, respectively. Call once on
   * release after the caller has locked the vertical axis and excluded cancellation or additional
   * pointers.
   */
  public static int direction(float distanceY, float velocityY, float screenHeight, float density) {
    if (!finite(distanceY)
        || !finite(velocityY)
        || !finite(screenHeight)
        || !finite(density)
        || screenHeight <= 0
        || density <= 0) return 0;

    double distance = Math.abs((double) distanceY);
    double velocity = Math.abs((double) velocityY);
    double flingVelocity = MIN_FLING_VELOCITY_DP * (double) density;
    boolean sameDirection = (distanceY < 0 && velocityY < 0) || (distanceY > 0 && velocityY > 0);
    // A deliberate fast reversal on release means the user pulled the page back, even if an earlier
    // part of the drag crossed the distance threshold. A gentle reversal still follows final
    // distance.
    if (velocity >= flingVelocity && !sameDirection) return 0;

    double distanceThreshold =
        Math.max(MIN_DISTANCE_DP * (double) density, screenHeight * (double) DISTANCE_FRACTION);
    boolean crossedDistance = distance >= distanceThreshold;
    boolean deliberateFling =
        sameDirection
            && distance >= MIN_FLING_DISTANCE_DP * (double) density
            && velocity >= flingVelocity;
    if (!crossedDistance && !deliberateFling) return 0;
    return distanceY < 0 ? 1 : -1;
  }

  private static boolean finite(float value) {
    return !Float.isNaN(value) && !Float.isInfinite(value);
  }
}
