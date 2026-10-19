package io.mediamaster.app.player;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.PathParser;

/**
 * Google Material Icons Round, canonical 24 px SVG paths. Apache-2.0. Source:
 * https://github.com/google/material-design-icons/tree/master/src The bundled notices identify the
 * original icon names and license.
 */
final class PlayerIcons extends Drawable {
  static final int BACK = 0,
      PLAY = 1,
      PAUSE = 2,
      PREVIOUS = 3,
      NEXT = 4,
      REPEAT = 5,
      REPEAT_ONE = 6,
      SHUFFLE = 7,
      ROTATE = 8,
      MORE = 9,
      BRIGHTNESS = 10,
      VOLUME = 11,
      VOLUME_OFF = 12,
      FAST_FORWARD = 13,
      PORTRAIT_MODE = 14,
      FULLSCREEN = 15;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path;

  PlayerIcons(int icon, int color) {
    path = PathParser.createPathFromPathData(pathData(icon));
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
  }

  private static String pathData(int icon) {
    switch (icon) {
      case BACK:
        return "M19 11H7.83l4.88-4.88c.39-.39.39-1.03 0-1.42-.39-.39-1.02-.39-1.41 0l-6.59"
            + " 6.59c-.39.39-.39 1.02 0 1.41l6.59 6.59c.39.39 1.02.39 1.41 0 .39-.39.39-1.02"
            + " 0-1.41L7.83 13H19c.55 0 1-.45 1-1s-.45-1-1-1z";
      case PLAY:
        return "M8 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18c.62-.39.62-1.29 0-1.69L9.54 5.98C8.87"
            + " 5.55 8 6.03 8 6.82z";
      case PAUSE:
        return "M8 19c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2v10c0 1.1.9 2 2 2zm6-12v10c0 1.1.9 2"
            + " 2 2s2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2z";
      case PREVIOUS:
        return "M7 6c.55 0 1 .45 1 1v10c0 .55-.45 1-1 1s-1-.45-1-1V7c0-.55.45-1 1-1zm3.66 6.82l5.77"
            + " 4.07c.66.47 1.58-.01 1.58-.82V7.93c0-.81-.91-1.28-1.58-.82l-5.77"
            + " 4.07c-.57.4-.57 1.24 0 1.64z";
      case NEXT:
        return "M7.58 16.89l5.77-4.07c.56-.4.56-1.24 0-1.63L7.58 7.11C6.91 6.65 6 7.12 6"
            + " 7.93v8.14c0 .81.91 1.28 1.58.82zM16 7v10c0 .55.45 1 1 1s1-.45"
            + " 1-1V7c0-.55-.45-1-1-1s-1 .45-1 1z";
      case REPEAT:
        return "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51"
            + " 0-.71l-2.79-2.79c-.31-.31-.85-.09-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1"
            + " 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0"
            + " .71l2.79 2.79c.31.31.85.09.85-.36V19h11c.55 0 1-.45"
            + " 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3z";
      case REPEAT_ONE:
        return "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51"
            + " 0-.71l-2.79-2.79c-.31-.31-.85-.09-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1"
            + " 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0"
            + " .71l2.79 2.79c.31.31.85.09.85-.36V19h11c.55 0 1-.45"
            + " 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3zm-4-2.75V9.81c0-.45-.36-.81-.81-.81-.13"
            + " 0-.25.03-.36.09l-1.49.74c-.21.1-.34.32-.34.55 0 .34.28.62.62.62h.88v3.25c0"
            + " .41.34.75.75.75s.75-.34.75-.75z";
      case SHUFFLE:
        return "M10.59 9.17L6.12 4.7c-.39-.39-1.02-.39-1.41 0-.39.39-.39 1.02 0 1.41l4.46 4.46"
            + " 1.42-1.4zm4.76-4.32l1.19 1.19L4.7 17.88c-.39.39-.39 1.02 0 1.41.39.39"
            + " 1.02.39 1.41 0L17.96 7.46l1.19"
            + " 1.19c.31.31.85.09.85-.36V4.5c0-.28-.22-.5-.5-.5h-3.79c-.45"
            + " 0-.67.54-.36.85zm-.52 8.56l-1.41 1.41 3.13 3.13-1.2"
            + " 1.2c-.31.31-.09.85.36.85h3.79c.28 0"
            + " .5-.22.5-.5v-3.79c0-.45-.54-.67-.85-.35l-1.19 1.19-3.13-3.14z";
      case ROTATE:
        return "M10.23 1.75c-.59-.59-1.54-.59-2.12 0L1.75 8.11c-.59.59-.59 1.54 0 2.12l12.02"
            + " 12.02c.59.59 1.54.59 2.12 0l6.36-6.36c.59-.59.59-1.54 0-2.12L10.23"
            + " 1.75zm3.89 18.73L3.52 9.88c-.39-.39-.39-1.02 0-1.41l4.95-4.95c.39-.39"
            + " 1.02-.39 1.41 0l10.61 10.61c.39.39.39 1.02 0 1.41l-4.95"
            + " 4.95c-.39.38-1.03.38-1.42-.01zM17.61 1.4C16.04.57 14.06-.03 11.81.02c-.18"
            + " 0-.26.22-.14.35l3.48 3.48 1.33-1.33c3.09 1.46 5.34 4.37 5.89"
            + " 7.86.06.41.44.69.86.62.41-.06.69-.45.62-.86-.6-3.8-2.96-7-6.24-8.74zM8.85"
            + " 20.16l-1.33"
            + " 1.33c-3.09-1.46-5.34-4.37-5.89-7.86-.06-.41-.44-.69-.86-.62-.41.06-.69.45-.62.86.6"
            + " 3.81 2.96 7.01 6.24 8.75 1.57.83 3.55 1.43 5.8 1.38.18 0"
            + " .26-.22.14-.35l-3.48-3.49z";
      case MORE:
        return "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2"
            + " 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z";
      case BRIGHTNESS:
        return "M20 15.31l2.6-2.6c.39-.39.39-1.02 0-1.41L20"
            + " 8.69V5c0-.55-.45-1-1-1h-3.69l-2.6-2.6c-.39-.39-1.02-.39-1.41 0L8.69 4H5c-.55"
            + " 0-1 .45-1 1v3.69l-2.6 2.6c-.39.39-.39 1.02 0 1.41L4 15.3V19c0 .55.45 1 1"
            + " 1h3.69l2.6 2.6c.39.39 1.02.39 1.41 0l2.6-2.6H19c.55 0 1-.45 1-1v-3.69zm-8"
            + " 1.59V7.1c0-.61.55-1.11 1.15-.99C15.91 6.65 18 9.08 18 12s-2.09 5.35-4.85"
            + " 5.89c-.6.12-1.15-.38-1.15-.99z";
      case VOLUME:
        return "M3 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18"
            + " 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L7 9H4c-.55 0-1 .45-1 1zm13.5"
            + " 2c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 4.45v.2c0"
            + " .38.25.71.6.85C17.18 6.53 19 9.06 19 12s-1.82 5.47-4.4"
            + " 6.5c-.36.14-.6.47-.6.85v.2c0 .63.63 1.07 1.21.85C18.6 19.11 21 15.84 21"
            + " 12s-2.4-7.11-5.79-8.4c-.58-.23-1.21.22-1.21.85z";
      case VOLUME_OFF:
        return "M3.63 3.63c-.39.39-.39 1.02 0 1.41L7.29 8.7 7 9H4c-.55 0-1 .45-1 1v4c0 .55.45 1 1"
            + " 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71v-4.17l4.18"
            + " 4.18c-.49.37-1.02.68-1.6.91-.36.15-.58.53-.58.92 0 .72.73 1.18 1.39.91.8-.33"
            + " 1.55-.77 2.22-1.31l1.34 1.34c.39.39 1.02.39 1.41 0 .39-.39.39-1.02"
            + " 0-1.41L5.05 3.63c-.39-.39-1.02-.39-1.42 0zM19 12c0 .82-.15 1.61-.41"
            + " 2.34l1.53 1.53c.56-1.17.88-2.48.88-3.87"
            + " 0-3.83-2.4-7.11-5.78-8.4-.59-.23-1.22.23-1.22.86v.19c0 .38.25.71.61.85C17.18"
            + " 6.54 19 9.06 19 12zm-8.71-6.29l-.17.17L12"
            + " 7.76V6.41c0-.89-1.08-1.33-1.71-.7zM16.5"
            + " 12c0-1.77-1.02-3.29-2.5-4.03v1.79l2.48 2.48c.01-.08.02-.16.02-.24z";
      case FAST_FORWARD:
        return "M5.58 16.89l5.77-4.07c.56-.4.56-1.24 0-1.63L5.58 7.11C4.91 6.65 4 7.12 4"
            + " 7.93v8.14c0 .81.91 1.28 1.58.82zM13 7.93v8.14c0 .81.91 1.28"
            + " 1.58.82l5.77-4.07c.56-.4.56-1.24 0-1.63l-5.77-4.07c-.67-.47-1.58 0-1.58.81z";
      case PORTRAIT_MODE:
        return "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9"
            + " 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z";
      case FULLSCREEN:
        return "M6 14c-.55 0-1 .45-1 1v3c0 .55.45 1 1 1h3c.55 0 1-.45 1-1s-.45-1-1-1H7v-2c0-.55"
            + "-.45-1-1-1zm0-4c.55 0 1-.45 1-1V7h2c.55 0 1-.45 1-1s-.45-1-1-1H6c-.55 0-1 .45-1"
            + " 1v3c0 .55.45 1 1 1zm11 7h-2c-.55 0-1 .45-1 1s.45 1 1 1h3c.55 0 1-.45"
            + " 1-1v-3c0-.55-.45-1-1-1s-1 .45-1 1v2zM14 6c0 .55.45 1 1 1h2v2c0 .55.45 1 1"
            + " 1s1-.45 1-1V6c0-.55-.45-1-1-1h-3c-.55 0-1 .45-1 1z";
      default:
        throw new IllegalArgumentException("Unknown player icon: " + icon);
    }
  }

  @Override
  public void draw(Canvas canvas) {
    canvas.save();
    canvas.translate(getBounds().left, getBounds().top);
    canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
    canvas.drawPath(path, paint);
    canvas.restore();
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public int getIntrinsicWidth() {
    return 28;
  }

  @Override
  public int getIntrinsicHeight() {
    return 28;
  }
}
