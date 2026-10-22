package io.mediamaster.app.library;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/** Row feedback begins only when the row, rather than a clickable child, receives the touch. */
public final class PressFeedbackLayout extends LinearLayout {
  private final PressFeedback feedback;

  public PressFeedbackLayout(Context context) {
    super(context);
    feedback = new PressFeedback(this, this::nativeTouchEvent);
  }

  @Override
  @SuppressLint("ClickableViewAccessibility") // Native LinearLayout dispatches its own click.
  public boolean onTouchEvent(MotionEvent event) {
    return feedback.onTouchEvent(event);
  }

  private boolean nativeTouchEvent(MotionEvent event) {
    return super.onTouchEvent(event);
  }

  /** Also called when a video holder is rebound or recycled. */
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
