package io.mediamaster.app.player;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.animation.PathInterpolator;

/** Ten short radial strokes whose trailing ends catch up to leave fading round dots. */
final class LikeBurstPainter {
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
  private static final PathInterpolator EASE_IN_OUT = new PathInterpolator(.77f, 0f, .175f, 1f);
  private static final float[] ANGLES = {-105, -71, -32, 7, 42, 79, 112, 149, 186, 221};
  private static final float[] REACH = {.94f, .84f, 1, .90f, .96f, .86f, .98f, .88f, 1, .90f};
  private static final float[] WIDTH = {.92f, 1.08f, .85f, 1, .94f, 1.10f, .85f, 1, .92f, 1.08f};
  private static final float[] DELAY = {.12f, .16f, .10f, .15f, .12f, .18f, .10f, .16f, .11f, .14f};
  private static final int[] COLORS = {
    0xffffa84c, 0xffff606b, 0xffa06ad7, 0xffc18bdc, 0xff78aef3,
    0xffa6d5ee, 0xffffce66, 0xffffe1a3, 0xffff9077, 0xffbd8ad8
  };
  private static final float[] X = new float[ANGLES.length];
  private static final float[] Y = new float[ANGLES.length];

  static {
    for (int i = 0; i < ANGLES.length; i++) {
      double angle = Math.toRadians(ANGLES[i]);
      X[i] = (float) Math.cos(angle);
      Y[i] = (float) Math.sin(angle);
    }
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  LikeBurstPainter() {
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
  }

  /**
   * Draw one burst at progress 0..1, normally over 450 ms. The supplied radius includes stroke caps
   * and an antialiasing margin. This method allocates nothing and submits at most ten lines.
   */
  void draw(Canvas canvas, float cx, float cy, float radius, float progress) {
    draw(canvas, cx, cy, radius, progress, 1f);
  }

  void draw(Canvas canvas, float cx, float cy, float radius, float progress, float fadeOpacity) {
    if (!(progress > 0 && progress < 1 && radius > 0)
        || !(fadeOpacity > 0)
        || !Float.isFinite(radius)
        || !Float.isFinite(cx)
        || !Float.isFinite(cy)) return;
    float head = EASE_OUT.getInterpolation(unit(progress / .72f));
    float opacity =
        EASE_OUT.getInterpolation(unit(progress / .08f))
            * (1 - EASE_OUT.getInterpolation(unit((progress - .78f) / .22f)));
    int alpha = Math.round(255 * opacity * unit(fadeOpacity));
    if (alpha == 0) return;
    for (int i = 0; i < X.length; i++) {
      float width = radius * .038f * WIDTH[i];
      float limit = Math.max(0, radius - width * .5f - Math.min(1f, radius * .1f));
      float outer = limit * REACH[i];
      float inner = limit * .50f;
      float leading = inner + (outer - inner) * head;
      float tail = EASE_IN_OUT.getInterpolation(unit((progress - DELAY[i]) / (.82f - DELAY[i])));
      // Keep a subpixel segment when the ends meet; round caps then draw a dot reliably on HWUI.
      float trailing =
          Math.max(0, Math.min(leading - radius * .001f, inner + (outer - inner) * tail));
      paint.setStrokeWidth(width);
      paint.setColor(COLORS[i]);
      paint.setAlpha(alpha);
      canvas.drawLine(
          cx + X[i] * trailing,
          cy + Y[i] * trailing,
          cx + X[i] * leading,
          cy + Y[i] * leading,
          paint);
    }
  }

  private static float unit(float value) {
    return Math.max(0, Math.min(1, value));
  }
}
