package io.mediamaster.app.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import io.mediamaster.app.R;
import java.util.function.BooleanSupplier;

/** A single, touch-through sticker for a confirmed portrait double tap. */
public final class LikeEffectView extends FrameLayout {
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
  private static final PathInterpolator EASE_IN_OUT = new PathInterpolator(.77f, 0f, .175f, 1f);
  private final ImageView sticker;
  private final BooleanSupplier motionEnabled;
  private final Runnable dismiss = this::releaseStamp;
  private int stickerWidth;
  private int stickerHeight;
  private int generation;
  private float restingX, restingY, lean;

  public LikeEffectView(Context context) {
    this(context, ValueAnimator::areAnimatorsEnabled);
  }

  LikeEffectView(Context context, BooleanSupplier motionEnabled) {
    super(context);
    this.motionEnabled = motionEnabled;
    setClickable(false);
    setFocusable(false);
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    sticker = new ImageView(context);
    sticker.setClickable(false);
    sticker.setFocusable(false);
    sticker.setScaleType(ImageView.ScaleType.FIT_CENTER);
    sticker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

    // Keep the supplied PNG byte-for-byte; decode only the size needed for its small overlay.
    // The image is decoded before the first gesture, never during a seek or a double tap.
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeResource(getResources(), R.drawable.like_effect, bounds);
    BitmapFactory.Options options = new BitmapFactory.Options();
    int targetHeight = Math.max(1, dp(192));
    options.inSampleSize = 1;
    while (bounds.outHeight / (options.inSampleSize * 2) >= targetHeight) {
      options.inSampleSize *= 2;
    }
    options.inScaled = false;
    Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.like_effect, options);
    sticker.setImageBitmap(bitmap);
    stickerWidth = dp(144);
    stickerHeight = dp(192);
    addView(sticker, new LayoutParams(stickerWidth, stickerHeight));
    sticker.setVisibility(View.INVISIBLE);
  }

  /** x and y are coordinates in this full-screen overlay, matching its containing player root. */
  public void showAt(float x, float y) {
    if (getWidth() <= 0 || getHeight() <= 0 || sticker.getDrawable() == null) return;
    boolean continuing = sticker.getVisibility() == View.VISIBLE;
    int thisGeneration = ++generation;
    removeCallbacks(dismiss);
    sticker.animate().withEndAction(null).cancel();
    // Allow for the rotated corners and the final upward lift, including taps near an edge.
    float edge = dp(32);
    restingX = clamp(x - stickerWidth / 2f, edge, getWidth() - stickerWidth - edge);
    restingY = clamp(y - stickerHeight / 2f, edge, getHeight() - stickerHeight - edge);
    lean = x < getWidth() / 2f ? -1f : 1f;
    sticker.setVisibility(View.VISIBLE);
    // Float alpha animates the View; ImageView's integer overload would hide the drawable itself.
    if (!motionEnabled.getAsBoolean()) {
      sticker.setAlpha(1f);
      sticker.setScaleX(1);
      sticker.setScaleY(1);
      sticker.setRotation(0);
      sticker.setTranslationX(restingX);
      sticker.setTranslationY(restingY);
      // Keep readable confirmation when the system disables motion, without movement or bounce.
      postDelayed(dismiss, 300);
      return;
    }
    if (!continuing) {
      sticker.setAlpha(0f);
      sticker.setScaleX(.94f);
      sticker.setScaleY(.94f);
      sticker.setRotation(-8f * lean);
      sticker.setTranslationX(restingX);
      sticker.setTranslationY(restingY - dp(8));
    }
    // Press into the tap, rebound with diminishing deformation, then breathe before lifting away.
    pose(thisGeneration, 125, 1.14f, .86f, 5, 3, 1, EASE_OUT, () -> rebound(thisGeneration));
  }

  private void rebound(int stamp) {
    pose(
        stamp,
        160,
        .94f,
        1.10f,
        -4,
        -4,
        1,
        EASE_IN_OUT,
        () ->
            pose(
                stamp,
                125,
                1.04f,
                .97f,
                2,
                0,
                1,
                EASE_IN_OUT,
                () ->
                    pose(stamp, 125, 1, 1, 0, 0, 1, EASE_IN_OUT, () -> postDelayed(dismiss, 100))));
  }

  private void pose(
      int stamp,
      long duration,
      float sx,
      float sy,
      float angle,
      float lift,
      float alpha,
      Interpolator curve,
      Runnable next) {
    sticker
        .animate()
        .alpha(alpha)
        .scaleX(sx)
        .scaleY(sy)
        .rotation(angle * lean)
        .translationX(restingX)
        .translationY(restingY + dp(lift))
        .setDuration(duration)
        .setInterpolator(curve)
        .withEndAction(
            () -> {
              if (generation == stamp) next.run();
            })
        .start();
  }

  private void releaseStamp() {
    if (!motionEnabled.getAsBoolean()) {
      clear();
      return;
    }
    int stamp = generation;
    pose(
        stamp,
        100,
        1.08f,
        .93f,
        -3,
        3,
        1,
        EASE_IN_OUT,
        () -> pose(stamp, 160, .92f, 1.06f, 6, -14, 0, EASE_OUT, this::clear));
  }

  /**
   * Clear on page changes, mode changes and pause; old feedback must not cross into another video.
   */
  public void clear() {
    generation++;
    removeCallbacks(dismiss);
    sticker.animate().withEndAction(null).cancel();
    sticker.setVisibility(View.INVISIBLE);
    sticker.setAlpha(0f);
    sticker.setScaleX(1);
    sticker.setScaleY(1);
    sticker.setRotation(0);
    sticker.setTranslationX(restingX);
    sticker.setTranslationY(restingY);
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    clear();
    // Retain the original 3:4 aspect ratio, including its existing transparent margins.
    stickerWidth =
        Math.max(1, Math.round(Math.min(dp(144), Math.min(width * .42f, height * .27f))));
    stickerHeight = Math.round(stickerWidth * 4f / 3f);
    sticker.setLayoutParams(new LayoutParams(stickerWidth, stickerHeight));
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    return false;
  }

  @Override
  protected void onDetachedFromWindow() {
    clear();
    super.onDetachedFromWindow();
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(Math.max(min, max), value));
  }
}
