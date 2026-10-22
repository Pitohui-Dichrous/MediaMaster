package io.mediamaster.app.library;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import io.mediamaster.app.Ui;
import java.util.function.BooleanSupplier;

/**
 * Pulls one scrolling child down to reveal a distance-driven arc behind it. Refresh starts only
 * after the child has returned home; ordinary list scrolling and flings remain owned by the child.
 */
public final class PullRefreshLayout extends FrameLayout {
  private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF arcBounds = new RectF();
  private final float density;
  private final int touchSlop;
  private final float dragRate = .5f;
  private final float revealDistance;
  private final float triggerDistance;
  private View target;
  private Runnable refreshListener;
  private BooleanSupplier childScrollUpCallback;
  private int foreground = Ui.CONTROL, background = Ui.BG, indicatorInset;
  private int activePointer = -1, lastHapticStep = -1, animationGeneration;
  private float downX, downY, pullStartY, pullDistance;
  private boolean possiblePull, dragging, refreshing, returning, pendingRefresh;
  private ValueAnimator returnAnimator;
  private final HapticPulseQueue hapticPulses;
  private final Vibrator vibrator;
  private final AudioAttributes hapticAttributes;

  public PullRefreshLayout(Context context) {
    super(context);
    density = getResources().getDisplayMetrics().density;
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    revealDistance = dp(16);
    triggerDistance = dp(96);
    arcPaint.setStyle(Paint.Style.STROKE);
    arcPaint.setStrokeWidth(dp(2));
    arcPaint.setStrokeCap(Paint.Cap.ROUND);
    setWillNotDraw(false);
    setClipChildren(true);
    setFocusable(false);
    vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    hapticAttributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    hapticPulses =
        new HapticPulseQueue(
            new HapticPulseQueue.Scheduler() {
              @Override
              public long nowMillis() {
                return SystemClock.uptimeMillis();
              }

              @Override
              public void postDelayed(Runnable action, long delayMillis) {
                PullRefreshLayout.this.postDelayed(action, delayMillis);
              }

              @Override
              public void remove(Runnable action) {
                removeCallbacks(action);
              }
            },
            this::performPullClick);
  }

  private boolean performPullClick() {
    if (!isAttachedToWindow() || !hasWindowFocus() || !isHapticFeedbackEnabled()) return false;
    if (Settings.System.getInt(
            getContext().getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        == 0) return false;
    if (vibrator == null || !vibrator.hasVibrator()) return false;
    try {
      // Texture ticks can be unsupported; OEM click effects can outlast the next distance step.
      // A 20ms touch pulse leaves a quiet gap before the queue's next 40ms slot. Sonification
      // attributes classify it as TOUCH so Android's touch intensity and DND settings still apply.
      vibrator.vibrate(
          VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE), hapticAttributes);
      return true;
    } catch (SecurityException denied) {
      // The normal VIBRATE permission is declared; keep a native fallback for restricted builds.
      return performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }
  }

  public void setOnRefreshListener(Runnable listener) {
    refreshListener = listener;
  }

  public void setOnChildScrollUpCallback(BooleanSupplier callback) {
    childScrollUpCallback = callback;
  }

  /** The child's expanded-header top padding, in pixels. */
  public void setIndicatorInset(int inset) {
    indicatorInset = Math.max(0, inset);
    invalidate();
  }

  public void setColors(int foreground, int background) {
    this.foreground = foreground;
    this.background = background;
    invalidate();
  }

  /**
   * External work may set busy directly. Neither true nor false dispatches the refresh listener.
   */
  public void setRefreshing(boolean refreshing) {
    pendingRefresh = false;
    cancelReturnAnimation();
    resetTouchTracking();
    this.refreshing = refreshing;
    setPullDistance(0, false);
  }

  public boolean isRefreshing() {
    return refreshing;
  }

  @Override
  public void onViewAdded(View child) {
    super.onViewAdded(child);
    if (getChildCount() > 1)
      throw new IllegalStateException("PullRefreshLayout accepts one scrolling child");
    target = child;
    target.setTranslationY(pullDistance);
  }

  @Override
  public void onViewRemoved(View child) {
    if (target == child) {
      cancelPendingAndReset();
      target = null;
    }
    super.onViewRemoved(child);
  }

  private float dp(float value) {
    return value * density;
  }

  private boolean canChildScrollUp() {
    return childScrollUpCallback != null
        ? childScrollUpCallback.getAsBoolean()
        : target != null && target.canScrollVertically(-1);
  }

  private void beginTouch(MotionEvent event) {
    hapticPulses.cancel();
    resetTouchTracking();
    if (refreshing || returning || target == null) return;
    activePointer = event.getPointerId(0);
    downX = event.getX();
    downY = event.getY();
    lastHapticStep = -1;
    possiblePull = !canChildScrollUp();
  }

  private boolean tryStartPull(MotionEvent event) {
    if (dragging) return true;
    if (!possiblePull || refreshing || returning) return false;
    int pointer = event.findPointerIndex(activePointer);
    if (pointer < 0) {
      possiblePull = false;
      return false;
    }
    float dx = event.getX(pointer) - downX;
    float dy = event.getY(pointer) - downY;
    // Commit horizontal and upward gestures to the list. Reversing them later is still list scroll.
    if ((Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) || dy < -touchSlop) {
      possiblePull = false;
      return false;
    }
    if (dy <= touchSlop || dy <= Math.abs(dx) * 1.2f || canChildScrollUp()) return false;
    dragging = true;
    pullStartY = downY + touchSlop;
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    return true;
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        beginTouch(event);
        return false;
      case MotionEvent.ACTION_MOVE:
        return tryStartPull(event);
      case MotionEvent.ACTION_POINTER_DOWN:
        if (dragging) return true;
        resetTouchTracking();
        return false;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (dragging) return true;
        resetTouchTracking();
        return false;
      default:
        return dragging;
    }
  }

  @Override
  // This container handles only dragging; the library's refresh button exposes the same action
  // to accessibility and keyboard users. Child rows keep their own native click dispatch.
  @SuppressLint("ClickableViewAccessibility")
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        beginTouch(event);
        return possiblePull;
      case MotionEvent.ACTION_MOVE:
        if (!tryStartPull(event)) return possiblePull || returning;
        int pointer = event.findPointerIndex(activePointer);
        if (pointer < 0) {
          finishPull(false);
          return true;
        }
        setPullDistance((event.getY(pointer) - pullStartY) * dragRate, true);
        return true;
      case MotionEvent.ACTION_UP:
        if (!dragging) return returning || refreshing;
        int releasedPointer = event.findPointerIndex(activePointer);
        if (releasedPointer >= 0)
          setPullDistance((event.getY(releasedPointer) - pullStartY) * dragRate, true);
        finishPull(releasedPointer >= 0 && pullDistance >= triggerDistance);
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_CANCEL:
        if (!dragging) {
          resetTouchTracking();
          return returning || refreshing;
        }
        finishPull(false);
        return true;
      default:
        return dragging || returning;
    }
  }

  @Override
  public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
    // RecyclerView asks early during a drag. At its top we still need to see enough movement to
    // choose pull versus horizontal/list scrolling; once that choice is made, normal rules apply.
    if (disallowIntercept && possiblePull && !dragging && !refreshing && !returning) return;
    super.requestDisallowInterceptTouchEvent(disallowIntercept);
  }

  private void setPullDistance(float distance, boolean fromFinger) {
    if (Float.isNaN(distance) || Float.isInfinite(distance)) distance = 0;
    float next = Math.max(0, Math.min(triggerDistance * 1.5f, distance));
    boolean movingForward = next > pullDistance;
    pullDistance = next;
    if (target != null) target.setTranslationY(next);
    invalidate();
    if (fromFinger && movingForward && next >= revealDistance) {
      float progress = Math.min(1, (next - revealDistance) / (triggerDistance - revealDistance));
      int reached = Math.min(4, (int) Math.floor(progress * 4 + .0001f));
      if (lastHapticStep < reached) {
        lastHapticStep = reached;
        // Distance earns each click once. Queue crossed thresholds instead of submitting them
        // simultaneously, which can replace the previous vibration before it has been felt.
        hapticPulses.advanceTo(reached);
      }
    }
  }

  private void finishPull(boolean commit) {
    // A normal committed release keeps earned clicks (at most 160ms) during the 180ms return.
    // Cancellation must never leave tactile feedback running after the gesture is abandoned.
    if (!commit) hapticPulses.cancel();
    resetTouchTracking();
    cancelReturnAnimation();
    pendingRefresh = commit && refreshListener != null;
    if (pendingRefresh) refreshing = true;
    returning = true;
    final int generation = animationGeneration;
    if (pullDistance <= 0 || !ValueAnimator.areAnimatorsEnabled()) {
      finishReturn(generation);
      return;
    }
    ValueAnimator animator = ValueAnimator.ofFloat(pullDistance, 0);
    returnAnimator = animator;
    animator.setDuration(180);
    animator.setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f));
    animator.addUpdateListener(
        value -> {
          if (generation == animationGeneration)
            setPullDistance((Float) value.getAnimatedValue(), false);
        });
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            finishReturn(generation);
          }
        });
    animator.start();
  }

  private void finishReturn(int generation) {
    if (generation != animationGeneration || !returning) return;
    returnAnimator = null;
    returning = false;
    setPullDistance(0, false);
    boolean notify = pendingRefresh;
    pendingRefresh = false;
    if (!notify) return;
    if (!isAttachedToWindow() || !hasWindowFocus() || refreshListener == null) {
      refreshing = false;
      return;
    }
    refreshListener.run();
  }

  private void cancelReturnAnimation() {
    animationGeneration++;
    ValueAnimator animator = returnAnimator;
    returnAnimator = null;
    returning = false;
    if (animator != null) animator.cancel();
  }

  private void resetTouchTracking() {
    possiblePull = false;
    dragging = false;
    activePointer = -1;
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
  }

  private void cancelPendingAndReset() {
    if (hapticPulses != null) hapticPulses.cancel();
    boolean hadPendingRefresh = pendingRefresh;
    pendingRefresh = false;
    cancelReturnAnimation();
    resetTouchTracking();
    setPullDistance(0, false);
    if (hadPendingRefresh) refreshing = false;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (pullDistance <= 0) return;
    float top = Math.min(getHeight(), indicatorInset);
    float bottom = Math.min(getHeight(), top + pullDistance);
    if (bottom <= top) return;
    int saved = canvas.save();
    // Only draw in the gap opened in front of row zero, never through list rows or fixed chrome.
    canvas.clipRect(0, top, getWidth(), bottom);
    canvas.drawColor(background);
    float opacity = Math.max(0, Math.min(1, (pullDistance - dp(4)) / (revealDistance - dp(4))));
    float progress =
        Math.max(
            0, Math.min(1, (pullDistance - revealDistance) / (triggerDistance - revealDistance)));
    float radius = dp(10);
    // At first reveal the upper semicircle fits inside the small gap; then center within the gap.
    float centerY = top + Math.min(pullDistance - dp(3), Math.max(dp(13), pullDistance / 2));
    float centerX = getWidth() / 2f;
    arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    arcPaint.setColor(foreground);
    arcPaint.setAlpha(Math.round(255 * opacity));
    canvas.drawArc(arcBounds, 180, 180 + progress * 180, false, arcPaint);
    canvas.restoreToCount(saved);
  }

  /** Cancels an unfinished pull/return without cancelling work already owned by the listener. */
  public void cancelPull() {
    cancelPendingAndReset();
  }

  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    if (!hasWindowFocus) cancelPendingAndReset();
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelPendingAndReset();
    super.onDetachedFromWindow();
  }
}
