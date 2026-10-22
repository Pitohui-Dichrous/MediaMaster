package io.mediamaster.app.library;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.Button;

/** Touch feedback that leaves keyboard activation and native accessibility clicks unchanged. */
public final class PressFeedbackButton extends Button {
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
  private final int touchSlop;
  private float downX, downY;
  private boolean pointerDown, gestureCanceled;

  public PressFeedbackButton(Context context) {
    super(context);
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
  }

  @Override
  @SuppressLint("ClickableViewAccessibility") // Native Button handles performClick via super.
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        gestureCanceled = false;
        pointerDown = isEnabled();
        downX = event.getX();
        downY = event.getY();
        boolean handled = super.onTouchEvent(event);
        if (pointerDown && handled) {
          // The header may delay native pressed states; acknowledge this touch immediately.
          setPressed(true);
          animateScale(.98f, 100);
        } else {
          pointerDown = false;
        }
        return handled;
      case MotionEvent.ACTION_MOVE:
        if (gestureCanceled) return true;
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (pointerDown && dx * dx + dy * dy > touchSlop * touchSlop) {
          cancelPointer(event);
          return true;
        }
        break;
      case MotionEvent.ACTION_POINTER_DOWN:
        if (pointerDown) cancelPointer(event);
        if (gestureCanceled) return true;
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        boolean canceled = gestureCanceled;
        pointerDown = false;
        gestureCanceled = false;
        animateScale(1f, 160);
        if (canceled) return true;
        break;
      default:
        break;
    }
    return super.onTouchEvent(event);
  }

  private void cancelPointer(MotionEvent event) {
    pointerDown = false;
    gestureCanceled = true;
    MotionEvent cancel = MotionEvent.obtain(event);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    super.onTouchEvent(cancel);
    cancel.recycle();
    animateScale(1f, 160);
  }

  private void animateScale(float target, long duration) {
    // Canceling preserves the current scale, so a quick second touch reverses without a jump.
    animate().cancel();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      setScaleX(1f);
      setScaleY(1f);
      return;
    }
    animate().scaleX(target).scaleY(target).setDuration(duration).setInterpolator(EASE_OUT).start();
  }

  private void clearFeedback() {
    pointerDown = false;
    gestureCanceled = true;
    animate().cancel();
    setScaleX(1f);
    setScaleY(1f);
    setPressed(false);
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    if (!hasWindowFocus) clearFeedback();
  }

  @Override
  protected void onDetachedFromWindow() {
    clearFeedback();
    super.onDetachedFromWindow();
  }
}
