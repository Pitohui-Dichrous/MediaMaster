package io.mediamaster.app.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;
import io.mediamaster.app.R;

/**
 * Small, transparent character scrubber. The supplied PNG bytes remain unchanged; transparent
 * margins, optical size and the backward character's mirror are handled only while drawing.
 */
public final class DirectionalThumbDrawable extends Drawable {
  private static final long TURN_DURATION_MS = 140;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Frame forwardFrame;
  private final Frame backwardFrame;
  private final int size;
  private final RectF destination = new RectF();
  private boolean forward = true;
  private float forwardWeight = 1f;
  private int alpha = 255;
  private ValueAnimator turn;

  public DirectionalThumbDrawable(Context context) {
    size = Math.max(1, Math.round(34 * context.getResources().getDisplayMetrics().density));
    forwardFrame = load(context, R.drawable.scrubber_forward, size);
    backwardFrame = load(context, R.drawable.scrubber_backward, size);
    // Equal visible silhouette area avoids the taller backward artwork feeling like a larger thumb.
    float commonCoverage = Math.min(forwardFrame.coverage, backwardFrame.coverage);
    forwardFrame.opticalScale = (float) Math.sqrt(commonCoverage / forwardFrame.coverage);
    backwardFrame.opticalScale = (float) Math.sqrt(commonCoverage / backwardFrame.coverage);
  }

  /**
   * Must be called on the UI thread. Repeated calls in the same direction do not restart motion.
   */
  public void setDirection(boolean forward) {
    if (this.forward == forward) return;
    this.forward = forward;
    if (turn != null) turn.cancel();
    float target = forward ? 1f : 0f;
    if (!isVisible() || !ValueAnimator.areAnimatorsEnabled()) {
      forwardWeight = target;
      invalidateSelf();
      return;
    }
    // Start from the currently displayed mix, including when a second reversal interrupts the
    // first.
    turn = ValueAnimator.ofFloat(forwardWeight, target);
    turn.setDuration(TURN_DURATION_MS);
    turn.setInterpolator(new DecelerateInterpolator(1.25f));
    turn.addUpdateListener(
        animation -> {
          forwardWeight = (float) animation.getAnimatedValue();
          invalidateSelf();
        });
    turn.start();
  }

  public boolean isForward() {
    return forward;
  }

  @Override
  public void draw(Canvas canvas) {
    if (getBounds().isEmpty() || alpha == 0) return;
    drawFrame(canvas, backwardFrame, 1f - forwardWeight, true);
    drawFrame(canvas, forwardFrame, forwardWeight, false);
  }

  private void drawFrame(Canvas canvas, Frame frame, float weight, boolean mirror) {
    if (weight <= 0.001f) return;
    float centerX = getBounds().exactCenterX();
    float centerY = getBounds().exactCenterY();
    float edge = Math.min(getBounds().width(), getBounds().height()) * (32f / 34f);
    float scale = edge / Math.max(frame.crop.width(), frame.crop.height()) * frame.opticalScale;
    float width = frame.crop.width() * scale;
    float height = frame.crop.height() * scale;
    destination.set(
        centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2);
    int save = canvas.save();
    // The tiny yaw and shared anchor make direction changes feel continuous, without moving the
    // bar.
    canvas.rotate((mirror ? -1 : 1) * (1f - weight) * 3f, centerX, centerY);
    canvas.scale((mirror ? -1f : 1f) * (0.94f + 0.06f * weight), 1f, centerX, centerY);
    paint.setAlpha(Math.round(alpha * weight));
    canvas.drawBitmap(frame.bitmap, frame.crop, destination, paint);
    canvas.restoreToCount(save);
  }

  private static Frame load(Context context, int resource, int displaySize) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeResource(context.getResources(), resource, options);
    int sample = 1;
    // Keep enough pixels for high-density displays, without retaining two full-size 1254px
    // stickers.
    while (Math.min(options.outWidth, options.outHeight) / (sample * 2) >= displaySize * 1.5f) {
      sample *= 2;
    }
    options.inJustDecodeBounds = false;
    options.inSampleSize = sample;
    options.inScaled = false;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resource, options);
    if (bitmap == null) throw new IllegalStateException("Unable to decode scrubber artwork");
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    int[] pixels = new int[width * height];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    int[] columns = new int[width];
    int[] rows = new int[height];
    int area = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (Color.alpha(pixels[y * width + x]) >= 64) {
          columns[x]++;
          rows[y]++;
          area++;
        }
      }
    }
    int rowThreshold = Math.max(1, Math.round(width * 0.015f));
    int columnThreshold = Math.max(1, Math.round(height * 0.015f));
    int left = 0;
    int right = width - 1;
    int top = 0;
    int bottom = height - 1;
    // Ignore isolated low-alpha flecks at the original canvas edges, preserving the sticker
    // outline.
    while (left < right && columns[left] < columnThreshold) left++;
    while (right > left && columns[right] < columnThreshold) right--;
    while (top < bottom && rows[top] < rowThreshold) top++;
    while (bottom > top && rows[bottom] < rowThreshold) bottom--;
    Rect crop =
        new Rect(
            Math.max(0, left - 1),
            Math.max(0, top - 1),
            Math.min(width, right + 2),
            Math.min(height, bottom + 2));
    float edge = Math.max(crop.width(), crop.height());
    return new Frame(bitmap, crop, Math.max(0.01f, area / (edge * edge)));
  }

  @Override
  public void jumpToCurrentState() {
    if (turn != null) turn.cancel();
    forwardWeight = forward ? 1f : 0f;
    invalidateSelf();
  }

  @Override
  public boolean setVisible(boolean visible, boolean restart) {
    boolean changed = super.setVisible(visible, restart);
    if (!visible) jumpToCurrentState();
    return changed;
  }

  @Override
  public void setAlpha(int alpha) {
    this.alpha = Math.max(0, Math.min(255, alpha));
    invalidateSelf();
  }

  @Override
  public int getAlpha() {
    return alpha;
  }

  @Override
  public void setColorFilter(ColorFilter colorFilter) {
    paint.setColorFilter(colorFilter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public int getIntrinsicWidth() {
    return size;
  }

  @Override
  public int getIntrinsicHeight() {
    return size;
  }

  private static final class Frame {
    final Bitmap bitmap;
    final Rect crop;
    final float coverage;
    float opticalScale = 1f;

    Frame(Bitmap bitmap, Rect crop, float coverage) {
      this.bitmap = bitmap;
      this.crop = crop;
      this.coverage = coverage;
    }
  }
}
