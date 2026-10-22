package io.mediamaster.app.library;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;

/** Shared pointer-only scaling; native views retain click, focus, and long-press dispatch. */
final class PressFeedback {
  interface NativeTouch {
    boolean handle(MotionEvent event);
  }

  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
  private final View view;
  private final NativeTouch nativeTouch;
  private final int touchSlop;
  private float downX, downY;
  private long downTime;
  private boolean pointerDown, gestureCanceled;

  PressFeedback(View view, NativeTouch nativeTouch) {
    this.view = view;
    this.nativeTouch = nativeTouch;
    touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
  }

  boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        gestureCanceled = false;
        pointerDown = view.isEnabled();
        downX = event.getX();
        downY = event.getY();
        downTime = event.getDownTime();
        boolean handled = nativeTouch.handle(event);
        if (pointerDown && handled) {
          view.setPressed(true);
          animateScale(.95f, 100);
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
        animateScale(1f, 120);
        if (canceled) return true;
        break;
      default:
        break;
    }
    return nativeTouch.handle(event);
  }

  private void cancelPointer(MotionEvent event) {
    pointerDown = false;
    gestureCanceled = true;
    MotionEvent cancel = MotionEvent.obtain(event);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    nativeTouch.handle(cancel);
    cancel.recycle();
    animateScale(1f, 120);
  }

  private void animateScale(float target, long duration) {
    // Retarget from the currently displayed value when another touch interrupts the animation.
    view.animate().cancel();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      view.setScaleX(1f);
      view.setScaleY(1f);
      return;
    }
    view.animate()
        .scaleX(target)
        .scaleY(target)
        .setDuration(duration)
        .setInterpolator(EASE_OUT)
        .start();
  }

  void reset() {
    if (pointerDown) {
      MotionEvent cancel =
          MotionEvent.obtain(
              downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0);
      pointerDown = false;
      nativeTouch.handle(cancel);
      cancel.recycle();
    }
    gestureCanceled = true;
    view.animate().cancel();
    view.setScaleX(1f);
    view.setScaleY(1f);
    view.setPressed(false);
  }
}
