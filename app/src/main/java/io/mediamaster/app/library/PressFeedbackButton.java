package io.mediamaster.app.library;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.widget.Button;

/** Touch feedback that leaves keyboard activation and native accessibility clicks unchanged. */
public final class PressFeedbackButton extends Button {
  private final PressFeedback feedback;

  public PressFeedbackButton(Context context) {
    super(context);
    feedback = new PressFeedback(this, this::nativeTouchEvent);
  }

  @Override
  @SuppressLint("ClickableViewAccessibility") // Native Button handles performClick via super.
  public boolean onTouchEvent(MotionEvent event) {
    return feedback.onTouchEvent(event);
  }

  private boolean nativeTouchEvent(MotionEvent event) {
    return super.onTouchEvent(event);
  }

  public void resetPressFeedback() {
    if (feedback != null) feedback.reset();
  }

  @Override
  public boolean performClick() {
    resetPressFeedback();
    return super.performClick();
  }

  @Override
  public boolean performLongClick() {
    resetPressFeedback();
    return super.performLongClick();
  }

  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    if (!hasWindowFocus) resetPressFeedback();
  }

  @Override
  protected void onDetachedFromWindow() {
    resetPressFeedback();
    super.onDetachedFromWindow();
  }
}
