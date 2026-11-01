package io.mediamaster.app.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import io.mediamaster.app.R;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** Touch-through confetti stickers; a fresh like quickly fades any preceding feedback. */
public final class LikeEffectView extends FrameLayout {
  static final int MAX_BURSTS = 8;
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
  private static final PathInterpolator EASE_IN_OUT = new PathInterpolator(.77f, 0f, .175f, 1f);
  private final BooleanSupplier motionEnabled;
  private final LikeBurstPainter burstPainter = new LikeBurstPainter();
  private final Bitmap bitmap;
  private final ArrayList<Slot> slots = new ArrayList<>(MAX_BURSTS);
  private final int[] drawingOrder = new int[MAX_BURSTS];
  private int stickerWidth, stickerHeight;
  private float burstRadius;
  private long serial;
  private Slot latest;

  public LikeEffectView(Context context) {
    this(context, ValueAnimator::areAnimatorsEnabled);
  }

  LikeEffectView(Context context, BooleanSupplier motionEnabled) {
    super(context);
    this.motionEnabled = motionEnabled;
    setClickable(false);
    setFocusable(false);
    setWillNotDraw(false);
    setChildrenDrawingOrderEnabled(true);
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

    // Decode the supplied pixels once. All slots share this Bitmap, including its original margins.
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
    bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.like_effect, options);
    stickerWidth = dp(144);
    stickerHeight = dp(192);
    // Pre-create the bounded pool so rapid likes never decode images or add/layout new children.
    for (int i = 0; i < MAX_BURSTS; i++) {
      Slot slot = new Slot(context);
      slots.add(slot);
      drawingOrder[i] = i;
      addView(slot.sticker, new LayoutParams(stickerWidth, stickerHeight));
      slot.updateSize();
    }
    latest = slots.get(0);
  }

  /** x and y share the containing player root's coordinates. Each call starts its own feedback. */
  public void showAt(float x, float y) {
    if (getWidth() <= 0 || getHeight() <= 0 || bitmap == null) return;
    for (int i = 0; i < slots.size(); i++) slots.get(i).retire();
    Slot chosen = null;
    for (int i = 0; i < slots.size(); i++) {
      Slot slot = slots.get(i);
      if (!slot.active) {
        chosen = slot;
        break;
      }
    }
    if (chosen == null) {
      // Ages are differences between nearby serials, so even a counter wrap preserves ordering.
      chosen = slots.get(0);
      for (int i = 1; i < slots.size(); i++) {
        Slot slot = slots.get(i);
        if (serial - slot.serial > serial - chosen.serial) chosen = slot;
      }
    }
    latest = chosen;
    chosen.start(x, y, ++serial);
    updateDrawingOrder();
  }

  int activeEffectCount() {
    int count = 0;
    for (int i = 0; i < slots.size(); i++) if (slots.get(i).active) count++;
    return count;
  }

  ImageView latestSticker() {
    return latest.sticker;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    for (int i = 0; i < slots.size(); i++) {
      Slot slot = slots.get(i);
      if (slot.active && slot.burstProgress < 1)
        burstPainter.draw(
            canvas, slot.centerX, slot.centerY, burstRadius, slot.burstProgress, slot.burstOpacity);
    }
  }

  @Override
  protected int getChildDrawingOrder(int childCount, int drawingPosition) {
    return drawingOrder[drawingPosition];
  }

  private void updateDrawingOrder() {
    // Keep child identities/order stable for reuse; only paint the newest active sticker last.
    for (int i = 0; i < slots.size(); i++) {
      drawingOrder[i] = i;
      int j = i;
      while (j > 0 && drawsAfter(slots.get(drawingOrder[j - 1]), slots.get(drawingOrder[j]))) {
        int previous = drawingOrder[j - 1];
        drawingOrder[j - 1] = drawingOrder[j];
        drawingOrder[j] = previous;
        j--;
      }
    }
    invalidate();
  }

  private boolean drawsAfter(Slot first, Slot second) {
    if (first.active != second.active) return first.active;
    return first.active && serial - first.serial < serial - second.serial;
  }

  /** Clear every slot on page changes, presentation changes, pause, resize and detach. */
  public void clear() {
    for (int i = 0; i < slots.size(); i++) slots.get(i).cancel();
    updateDrawingOrder();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int height = MeasureSpec.getSize(heightMeasureSpec);
    int nextWidth =
        Math.max(1, Math.round(Math.min(dp(144), Math.min(width * .42f, height * .27f))));
    int nextHeight = Math.round(nextWidth * 4f / 3f);
    float nextRadius = Math.min(dp(112), Math.min(width, height) * .31f);
    if (stickerWidth != nextWidth || stickerHeight != nextHeight || burstRadius != nextRadius) {
      clear();
      stickerWidth = nextWidth;
      stickerHeight = nextHeight;
      burstRadius = nextRadius;
      // FrameLayout must measure the new child sizes in this same pass. Updating layout params
      // from onSizeChanged is too late: the old measured size can survive the orientation change.
      for (int i = 0; i < slots.size(); i++) slots.get(i).updateSize();
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    clear();
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

  private final class Slot {
    final ImageView sticker;
    final ValueAnimator burstAnimator = ValueAnimator.ofFloat(0, 1);
    final ValueAnimator retireAnimator = ValueAnimator.ofFloat(1, 0);
    final Runnable dismiss = this::fadeOut;
    boolean active, retiring;
    int generation, burstGeneration, retireGeneration;
    long serial;
    float centerX, centerY, restingX, restingY, lean, burstProgress = 1f;
    float burstOpacity = 1f, retireStartAlpha;

    Slot(Context context) {
      sticker = new ImageView(context);
      sticker.setClickable(false);
      sticker.setFocusable(false);
      sticker.setScaleType(ImageView.ScaleType.FIT_CENTER);
      sticker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      sticker.setImageBitmap(bitmap);
      sticker.setVisibility(View.INVISIBLE);
      burstAnimator.setDuration(450);
      burstAnimator.setInterpolator(null);
      burstAnimator.addUpdateListener(
          animation -> {
            if (!active || generation != burstGeneration) return;
            burstProgress = (float) animation.getAnimatedValue();
            invalidate();
          });
      retireAnimator.setDuration(120);
      retireAnimator.setInterpolator(EASE_OUT);
      retireAnimator.addUpdateListener(
          animation -> {
            if (!active || !retiring || generation != retireGeneration) return;
            burstOpacity = (float) animation.getAnimatedValue();
            sticker.setAlpha(retireStartAlpha * burstOpacity);
            invalidate();
          });
      retireAnimator.addListener(
          new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
              if (active && retiring && generation == retireGeneration) finish();
            }
          });
    }

    void updateSize() {
      LayoutParams params = (LayoutParams) sticker.getLayoutParams();
      params.width = stickerWidth;
      params.height = stickerHeight;
      // Explicit pivots keep the first rendered overshoot centered before any default-pivot update.
      sticker.setPivotX(stickerWidth / 2f);
      sticker.setPivotY(stickerHeight / 2f);
    }

    void start(float x, float y, long serial) {
      cancel();
      this.serial = serial;
      int stamp = generation;
      float sin = .208f, cos = .979f;
      float marginX =
          Math.max(burstRadius, (stickerWidth * cos + stickerHeight * sin) * .63f) + dp(8);
      float marginY =
          Math.max(burstRadius, (stickerHeight * cos + stickerWidth * sin) * .63f) + dp(8);
      centerX = clamp(x, marginX, getWidth() - marginX);
      centerY = clamp(y, marginY, getHeight() - marginY);
      restingX = centerX - stickerWidth / 2f;
      restingY = centerY - stickerHeight / 2f;
      lean = x < getWidth() / 2f ? -1f : 1f;
      active = true;
      sticker.setTranslationX(restingX);
      sticker.setTranslationY(restingY);
      sticker.setVisibility(View.VISIBLE);
      // Float alpha animates the View, not ImageView's integer drawable-opacity overload.
      if (!motionEnabled.getAsBoolean()) {
        sticker.setAlpha(1f);
        sticker.setScaleX(1);
        sticker.setScaleY(1);
        sticker.setRotation(0);
        postDelayed(dismiss, 300);
        return;
      }
      sticker.setAlpha(0f);
      sticker.setScaleX(.42f);
      sticker.setScaleY(.42f);
      sticker.setRotation(12f * lean);
      burstGeneration = stamp;
      burstProgress = 0;
      burstAnimator.start();
      invalidate();
      pose(
          stamp,
          200,
          1.26f,
          -10,
          1,
          EASE_IN_OUT,
          () -> pose(stamp, 200, 1, 0, 1, EASE_OUT, () -> postDelayed(dismiss, 450)));
    }

    void pose(
        int stamp,
        long duration,
        float scale,
        float angle,
        float alpha,
        Interpolator curve,
        Runnable next) {
      sticker
          .animate()
          .alpha(alpha)
          .scaleX(scale)
          .scaleY(scale)
          .rotation(angle * lean)
          .translationX(restingX)
          .translationY(restingY)
          .setDuration(duration)
          .setInterpolator(curve)
          .withEndAction(
              () -> {
                if (active && generation == stamp) next.run();
              })
          .start();
    }

    void fadeOut() {
      if (!active) return;
      if (!motionEnabled.getAsBoolean()) {
        finish();
        return;
      }
      pose(generation, 150, .88f, 0, 0, EASE_OUT, this::finish);
    }

    void retire() {
      if (!active || retiring) return;
      if (!motionEnabled.getAsBoolean()) {
        finish();
        return;
      }
      // Hold the exact visible pose and fade from its current opacity. A third like does not
      // restart this exit or extend the life of a sticker that is already fading.
      generation++;
      removeCallbacks(dismiss);
      sticker.animate().withEndAction(null).cancel();
      burstAnimator.cancel();
      retiring = true;
      retireGeneration = generation;
      retireStartAlpha = sticker.getAlpha();
      retireAnimator.start();
    }

    void finish() {
      cancel();
      updateDrawingOrder();
    }

    void cancel() {
      generation++;
      active = false;
      retiring = false;
      removeCallbacks(dismiss);
      sticker.animate().withEndAction(null).cancel();
      burstAnimator.cancel();
      retireAnimator.cancel();
      burstProgress = 1;
      burstOpacity = 1;
      sticker.setVisibility(View.INVISIBLE);
      sticker.setAlpha(0f);
      sticker.setScaleX(1);
      sticker.setScaleY(1);
      sticker.setRotation(0);
      sticker.setTranslationX(restingX);
      sticker.setTranslationY(restingY);
    }
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(Math.max(min, max), value));
  }
}
