package io.mediamaster.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.Locale;

public final class Ui {
  public static int BG = 0xfff7f8f5,
      SURFACE = 0xffedf0eb,
      BORDER = 0xffdce1da,
      TEXT = 0xff272637,
      MUTED = 0xff62666b,
      SOFT_ACCENT = 0xffe8f0e9,
      INK = TEXT,
      CONTROL = 0xff145f39;
  public static final int ACCENT = 0xff145f39,
      ON_ACCENT = Color.WHITE,
      PURPLE = 0xff272637,
      DEEP_PURPLE = 0xff201d2c,
      RED = 0xffb3261e;
  private static boolean dark;

  public static boolean isDark(Context context) {
    return context
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .getBoolean("dark", false);
  }

  public static void setDark(Context context, boolean enabled) {
    context
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("dark", enabled)
        .apply();
  }

  /**
   * MainActivity recreates its views when appearance changes; playback accent tokens stay fixed.
   */
  public static void configure(Context context) {
    dark = isDark(context);
    BG = dark ? DEEP_PURPLE : 0xfff7f8f5;
    SURFACE = dark ? PURPLE : 0xffedf0eb;
    BORDER = dark ? 0xff464352 : 0xffdce1da;
    TEXT = dark ? 0xfff5f4f8 : PURPLE;
    MUTED = dark ? 0xffc2bfcc : 0xff62666b;
    INK = TEXT;
    SOFT_ACCENT = dark ? 0xff2a3d35 : 0xffe8f0e9;
    CONTROL = dark ? 0xfff5f4f8 : ACCENT;
  }

  public static int dialogTheme(Context context) {
    return isDark(context) ? R.style.DialogThemeDark : R.style.DialogTheme;
  }

  static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static TextView text(Context c, String value, float size, int color) {
    TextView v = new TextView(c);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setIncludeFontPadding(false);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  static GradientDrawable shape(Context c, int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(c, radius));
    return g;
  }

  static void surface(View v, int color, int radius) {
    v.setBackground(shape(v.getContext(), color, radius));
  }

  static void ripple(View v, int color, int radius) {
    v.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(dark ? 0x24ffffff : 0x1f145f39),
            shape(v.getContext(), color, radius),
            shape(v.getContext(), Color.WHITE, radius)));
    v.setClickable(true);
    v.setFocusable(true);
  }

  static ImageButton icon(Context c, String kind, String label) {
    ImageButton b = new ImageButton(c);
    b.setImageDrawable(new Icon(kind, TEXT));
    b.setContentDescription(label);
    b.setTooltipText(label);
    b.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
    ripple(b, Color.TRANSPARENT, 8);
    b.setImageTintList(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {TEXT & 0x00ffffff | 0x66000000, TEXT}));
    b.setMinimumWidth(dp(c, 48));
    b.setMinimumHeight(dp(c, 48));
    b.setElevation(0);
    return b;
  }

  static String size(long b) {
    if (b < 1024) return b + " B";
    if (b < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", b / 1024d);
    if (b < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", b / (1024d * 1024));
    return String.format(Locale.CHINA, "%.2f GB", b / (1024d * 1024 * 1024));
  }

  static String duration(long ms) {
    long s = ms / 1000;
    return s >= 3600
        ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
  }

  /** Official Google Material Icons Round SVG paths; see assets/licenses/NOTICE.txt. */
  static class Icon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path;

    Icon(String kind, int color) {
      paint.setColor(color);
      paint.setStyle(Paint.Style.FILL);
      path = androidx.core.graphics.PathParser.createPathFromPathData(pathData(kind));
    }

    private static String pathData(String kind) {
      switch (kind) {
        case "search":
          return "M15.5 14h-.79l-.28-.27c1.2-1.4 1.82-3.31"
              + " 1.48-5.34-.47-2.78-2.79-5-5.59-5.34-4.23-.52-7.79 3.04-7.27 7.27.34 2.8"
              + " 2.56 5.12 5.34 5.59 2.03.34 3.94-.28 5.34-1.48l.27.28v.79l4.25 4.25c.41.41"
              + " 1.08.41 1.49 0 .41-.41.41-1.08 0-1.49L15.5 14zm-6 0C7.01 14 5 11.99 5"
              + " 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z";
        case "folder":
          return "M20 6h-8l-1.41-1.41C10.21 4.21 9.7 4 9.17 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2"
              + " 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-1 12H5c-.55"
              + " 0-1-.45-1-1V9c0-.55.45-1 1-1h14c.55 0 1 .45 1 1v8c0 .55-.45 1-1 1z";
        case "scan":
          return "M17.65 6.35c-1.63-1.63-3.94-2.57-6.48-2.31-3.67.37-6.69 3.35-7.1 7.02C3.52 15.91"
              + " 7.27 20 12 20c3.19 0 5.93-1.87 7.21-4.56.32-.67-.16-1.44-.9-1.44-.37"
              + " 0-.72.2-.88.53-1.13 2.43-3.84 3.97-6.8"
              + " 3.31-2.22-.49-4.01-2.3-4.48-4.52C5.31 9.44 8.26 6 12 6c1.66 0 3.14.69 4.22"
              + " 1.78l-1.51 1.51c-.63.63-.19 1.71.7 1.71H19c.55 0 1-.45"
              + " 1-1V6.41c0-.89-1.08-1.34-1.71-.71l-.64.65z";
        case "settings":
          return "M19.5,12c0-0.23-0.01-0.45-0.03-0.68l1.86-1.41c0.4-0.3,0.51-0.86,0.26-1.3l-1.87-3.23c-0.25-0.44-0.79-0.62-1.25-0.42"
                     + " l-2.15,0.91c-0.37-0.26-0.76-0.49-1.17-0.68l-0.29-2.31C14.8,2.38,14.37,2,13.87,2h-3.73C9.63,2,9.2,2.38,9.14,2.88L8.85,5.19"
                     + " c-0.41,0.19-0.8,0.42-1.17,0.68L5.53,4.96c-0.46-0.2-1-0.02-1.25,0.42L2.41,8.62c-0.25,0.44-0.14,0.99,0.26,1.3l1.86,1.41"
                     + " C4.51,11.55,4.5,11.77,4.5,12s0.01,0.45,0.03,0.68l-1.86,1.41c-0.4,0.3-0.51,0.86-0.26,1.3l1.87,3.23c0.25,0.44,0.79,0.62,1.25,0.42"
                     + " l2.15-0.91c0.37,0.26,0.76,0.49,1.17,0.68l0.29,2.31C9.2,21.62,9.63,22,10.13,22h3.73c0.5,0,0.93-0.38,0.99-0.88l0.29-2.31"
                     + " c0.41-0.19,0.8-0.42,1.17-0.68l2.15,0.91c0.46,0.2,1,0.02,1.25-0.42l1.87-3.23c0.25-0.44,0.14-0.99-0.26-1.3l-1.86-1.41"
                     + " C19.49,12.45,19.5,12.23,19.5,12z"
                     + " M12.04,15.5c-1.93,0-3.5-1.57-3.5-3.5s1.57-3.5,3.5-3.5s3.5,1.57,3.5,3.5S13.97,15.5,12.04,15.5z";
        case "more":
          return "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2"
              + " 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z";
        case "sort":
          return "M4 18h4c.55 0 1-.45 1-1s-.45-1-1-1H4c-.55 0-1 .45-1 1s.45 1 1 1zM3 7c0 .55.45 1 1"
              + " 1h16c.55 0 1-.45 1-1s-.45-1-1-1H4c-.55 0-1 .45-1 1zm1 6h10c.55 0 1-.45"
              + " 1-1s-.45-1-1-1H4c-.55 0-1 .45-1 1s.45 1 1 1z";
        case "shuffle":
          return "M10.59 9.17L6.12 4.7c-.39-.39-1.02-.39-1.41 0-.39.39-.39 1.02 0 1.41l4.46 4.46"
              + " 1.42-1.4zm4.76-4.32l1.19 1.19L4.7 17.88c-.39.39-.39 1.02 0 1.41.39.39"
              + " 1.02.39 1.41 0L17.96 7.46l1.19"
              + " 1.19c.31.31.85.09.85-.36V4.5c0-.28-.22-.5-.5-.5h-3.79c-.45"
              + " 0-.67.54-.36.85zm-.52 8.56l-1.41 1.41 3.13 3.13-1.2"
              + " 1.2c-.31.31-.09.85.36.85h3.79c.28 0"
              + " .5-.22.5-.5v-3.79c0-.45-.54-.67-.85-.35l-1.19 1.19-3.13-3.14z";
        case "close":
          return "M18.3 5.71c-.39-.39-1.02-.39-1.41 0L12 10.59 7.11 5.7c-.39-.39-1.02-.39-1.41"
              + " 0-.39.39-.39 1.02 0 1.41L10.59 12 5.7 16.89c-.39.39-.39 1.02 0 1.41.39.39"
              + " 1.02.39 1.41 0L12 13.41l4.89 4.89c.39.39 1.02.39 1.41 0 .39-.39.39-1.02"
              + " 0-1.41L13.41 12l4.89-4.89c.38-.38.38-1.02 0-1.4z";
        case "play":
          return "M8 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18c.62-.39.62-1.29 0-1.69L9.54"
              + " 5.98C8.87 5.55 8 6.03 8 6.82z";
        default:
          return "M3 6c-.55 0-1 .45-1 1v13c0 1.1.9 2 2 2h13c.55 0 1-.45 1-1s-.45-1-1-1H5c-.55"
              + " 0-1-.45-1-1V7c0-.55-.45-1-1-1zm17-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2"
              + " 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l5.47 4.1c.27.2.27.6 0"
              + " .8L12 14.5z";
      }
    }

    @Override
    public void draw(Canvas canvas) {
      canvas.save();
      Rect bounds = getBounds();
      canvas.translate(bounds.left, bounds.top);
      canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
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
  }
}
