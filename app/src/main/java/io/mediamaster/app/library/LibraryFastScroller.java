package io.mediamaster.app.library;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import io.mediamaster.app.Ui;

/** A narrow visible handle with a full 48dp hit area, backed by the list's own scroll metrics. */
public final class LibraryFastScroller extends View {
  private final RecyclerView list;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density;
  private float thumbTop, thumbHeight, opacity = .62f, expansion;
  private float requestedOpacity = .62f, requestedExpansion;
  private float grabOffset;
  private int topInset;
  private float dragY;
  private boolean dragging, scrollable;
  private int lastSection = -1;
  private long lastHaptic;
  private ValueAnimator fade;
  private final Runnable settle = () -> animateState(.62f, 0);

  public LibraryFastScroller(Context context, RecyclerView list) {
    super(context);
    this.list = list;
    density = getResources().getDisplayMetrics().density;
    setFocusable(true);
    setContentDescription("快速滚动视频目录");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    list.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(RecyclerView view, int dx, int dy) {
            updateMetrics();
            if (dy != 0) show();
          }

          @Override
          public void onScrollStateChanged(RecyclerView view, int state) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) show();
          }
        });
    list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateMetrics());
  }

  private float dp(float value) {
    return value * density;
  }

  /** Keep the initial handle below expanded chrome; no layout is requested while scrolling. */
  public void setTopInset(int inset) {
    if (topInset == inset) return;
    topInset = Math.max(0, inset);
    updateMetrics();
  }

  private void updateMetrics() {
    int range = list.computeVerticalScrollRange();
    int extent = list.computeVerticalScrollExtent();
    scrollable =
        range > extent && list.getAdapter() != null && list.getAdapter().getItemCount() > 1;
    if (!scrollable) {
      if (dragging) release();
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      invalidate();
      return;
    }
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    float trackHeight = Math.max(0, getHeight() - topInset - dp(16));
    thumbHeight = Math.min(trackHeight, Math.max(dp(48), trackHeight * extent / range));
    float fraction =
        Math.max(0f, Math.min(1f, list.computeVerticalScrollOffset() / (float) (range - extent)));
    thumbTop = topInset + dp(8) + (trackHeight - thumbHeight) * fraction;
    if (dragging) {
      thumbTop =
          Math.max(
              topInset + dp(8), Math.min(getHeight() - dp(8) - thumbHeight, dragY - grabOffset));
    }
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    updateMetrics();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (!scrollable) return;
    float width = dp(4 + 3 * expansion);
    float right = getWidth() - dp(4);
    paint.setColor(Ui.CONTROL);
    paint.setAlpha(Math.round(255 * opacity));
    canvas.drawRoundRect(
        right - width, thumbTop, right, thumbTop + thumbHeight, width / 2, width / 2, paint);
  }

  private void show() {
    removeCallbacks(settle);
    animateState(.88f, dragging ? 1 : 0);
    if (!dragging) postDelayed(settle, 1200);
  }

  private void animateState(float targetOpacity, float targetExpansion) {
    if (requestedOpacity == targetOpacity && requestedExpansion == targetExpansion) return;
    requestedOpacity = targetOpacity;
    requestedExpansion = targetExpansion;
    if (fade != null) fade.cancel();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      opacity = targetOpacity;
      expansion = targetExpansion;
      invalidate();
      return;
    }
    float fromOpacity = opacity, fromExpansion = expansion;
    fade = ValueAnimator.ofFloat(0, 1);
    fade.setDuration(150);
    fade.setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f));
    fade.addUpdateListener(
        a -> {
          float value = (Float) a.getAnimatedValue();
          opacity = fromOpacity + (targetOpacity - fromOpacity) * value;
          expansion = fromExpansion + (targetExpansion - fromExpansion) * value;
          invalidate();
        });
    fade.start();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        updateMetrics();
        if (!scrollable
            || event.getY() < thumbTop - dp(12)
            || event.getY() > thumbTop + thumbHeight + dp(12)) return false;
        dragging = true;
        dragY = event.getY();
        grabOffset = Math.max(0, Math.min(thumbHeight, event.getY() - thumbTop));
        list.stopScroll();
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        lastHaptic = SystemClock.uptimeMillis();
        lastSection = -1;
        show();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging) return false;
        dragY = event.getY();
        float travel = Math.max(1, getHeight() - topInset - dp(16) - thumbHeight);
        scrollToFraction((dragY - grabOffset - topInset - dp(8)) / travel);
        return true;
      case MotionEvent.ACTION_UP:
        if (!dragging) return false;
        release();
        performClick();
        return true;
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_POINTER_DOWN:
        if (!dragging) return false;
        release();
        return true;
      default:
        return dragging;
    }
  }

  private void scrollToFraction(float raw) {
    float fraction = Math.max(0, Math.min(1, raw));
    int count = list.getAdapter() == null ? 0 : list.getAdapter().getItemCount();
    if (count == 0 || !(list.getLayoutManager() instanceof LinearLayoutManager)) return;
    int range = list.computeVerticalScrollRange(), extent = list.computeVerticalScrollExtent();
    float averageRow = Math.max(1, range / (float) count);
    int offset = Math.round(fraction * Math.max(0, range - extent));
    int position = Math.min(count - 1, (int) (offset / averageRow));
    int remainder = Math.round(offset - position * averageRow);
    ((LinearLayoutManager) list.getLayoutManager())
        .scrollToPositionWithOffset(position, -remainder);
    int section = Math.round(fraction * 20);
    long now = SystemClock.uptimeMillis();
    if (section != lastSection && now - lastHaptic >= 80) {
      performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      lastHaptic = now;
    }
    lastSection = section;
    show();
    // Layout delivers the final authoritative metrics on the following frame.
    postOnAnimation(this::updateMetrics);
  }

  private void release() {
    dragging = false;
    updateMetrics();
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    show();
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.SeekBar");
    info.setScrollable(scrollable);
    if (!scrollable) return;
    int maximum =
        Math.max(1, list.computeVerticalScrollRange() - list.computeVerticalScrollExtent());
    float value = Math.max(0, Math.min(100, list.computeVerticalScrollOffset() * 100f / maximum));
    info.setRangeInfo(
        AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 100, value));
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
  }

  @Override
  public boolean performAccessibilityAction(int action, Bundle arguments) {
    if (scrollable
        && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
        && arguments != null) {
      scrollToFraction(
          arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) / 100f);
      return true;
    }
    if (scrollable
        && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
      list.smoothScrollBy(
          0,
          (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1)
              * list.getHeight()
              * 3
              / 4);
      return true;
    }
    return super.performAccessibilityAction(action, arguments);
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(settle);
    if (fade != null) fade.cancel();
    super.onDetachedFromWindow();
  }
}
