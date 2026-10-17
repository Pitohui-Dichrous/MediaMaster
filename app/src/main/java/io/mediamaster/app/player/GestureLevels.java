package io.mediamaster.app.player;

/** Linear screen-relative level adjustment; upward motion increases a level. */
public final class GestureLevels {
  private GestureLevels() {}

  public static float adjust(float origin, float distanceY, float screenHeight, float minimum) {
    if (screenHeight <= 0 || Float.isNaN(distanceY)) return origin;
    return Math.max(minimum, Math.min(1f, origin - distanceY / screenHeight));
  }
}
