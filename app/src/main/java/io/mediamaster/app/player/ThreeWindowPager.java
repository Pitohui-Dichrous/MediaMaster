package io.mediamaster.app.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.OverScroller;
import android.widget.ScrollView;

/**
 * A native three-page scrolling viewport, not a single video translated out before loading another.
 * All three page roots stay attached for the lifetime of this view. The owner preloads their video
 * players and routes gestures; committing a page rotates these roots' roles without reparenting any
 * video surface.
 */
public final class ThreeWindowPager extends ScrollView {
  private static final int SETTLE_DURATION_MS = 160;
  private static final float MAX_DRAG_FRACTION = .98f;

  private final PageHost pageHost;
  private final OverScroller scroller;
  private final View[] attachedPages = new View[3];
  private View previousPage, currentPage, nextPage;
  private int pageHeight;
  private boolean pagesInitialized, centerAfterLayout = true, settling;
  private int settleTarget;
  private Runnable settleCallback;

  public ThreeWindowPager(Context context) {
    super(context);
    setVerticalScrollBarEnabled(false);
    setHorizontalScrollBarEnabled(false);
    setOverScrollMode(OVER_SCROLL_NEVER);
    setSmoothScrollingEnabled(false);
    setFillViewport(false);
    setFocusable(false);
    setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    pageHost = new PageHost(context);
    addView(pageHost, new ScrollView.LayoutParams(-1, -2));
    scroller = new OverScroller(context, new PathInterpolator(.23f, 1f, .32f, 1f));
  }

  /**
   * Initially attaches exactly three distinct roots. Later calls must pass those same roots in
   * their new roles. At a settled edge, role placement and recentering happen synchronously: the
   * selected root moves from 0/2H to H while scrollY moves by the same amount, preserving its
   * screen position.
   */
  public void setPages(View previous, View current, View next) {
    if (previous == null
        || current == null
        || next == null
        || previous == current
        || previous == next
        || current == next) throw new IllegalArgumentException("Provide three distinct page roots");

    View[] roles = {previous, current, next};
    if (!pagesInitialized) {
      for (View page : roles)
        if (page.getParent() != null)
          throw new IllegalArgumentException("Page roots must initially be unattached");
      for (int i = 0; i < roles.length; i++) {
        attachedPages[i] = roles[i];
        pageHost.addView(roles[i], new ViewGroup.LayoutParams(-1, -1));
      }
      pagesInitialized = true;
    } else {
      for (View page : roles)
        if (page != attachedPages[0] && page != attachedPages[1] && page != attachedPages[2])
          throw new IllegalArgumentException("Rotate the existing roots without replacing them");
    }

    stopSettling();
    previousPage = previous;
    currentPage = current;
    nextPage = next;
    pageHost.positionPages();
    scrollTo(0, pageHeight);
    // Before first layout the ScrollView has no range yet. Center once the content has its 3H size.
    if (pageHost.getHeight() == 0) centerAfterLayout = true;
    pageHost.requestLayout();
  }

  /** Finger displacement from touch-down, in pixels: upward motion reveals the next page. */
  public void dragTo(float fingerDy, boolean allowPrevious, boolean allowNext) {
    stopSettling();
    if (Float.isNaN(fingerDy) || Float.isInfinite(fingerDy)) fingerDy = 0;
    float limit = pageHeight * MAX_DRAG_FRACTION;
    float displacement =
        Math.max(allowNext ? -limit : 0, Math.min(allowPrevious ? limit : 0, fingerDy));
    scrollTo(0, Math.round(pageHeight - displacement));
  }

  /**
   * Settles to next (+1), previous (-1), or current (0). The exact native scroll position is
   * committed before invoking the callback; that callback may immediately rotate roles with
   * setPages().
   */
  public void settle(int direction, Runnable onSettled) {
    stopSettling();
    settleTarget = pageHeight * (direction > 0 ? 2 : direction < 0 ? 0 : 1);
    settleCallback = onSettled;
    settling = true;
    int remaining = settleTarget - getScrollY();
    if (remaining == 0 || pageHeight == 0 || !ValueAnimator.areAnimatorsEnabled()) {
      finishSettling();
      return;
    }
    scroller.startScroll(0, getScrollY(), 0, remaining, SETTLE_DURATION_MS);
    postInvalidateOnAnimation();
  }

  /** Cancels an in-flight commit without invoking its callback, then restores the current page. */
  public void cancelAndCenter() {
    stopSettling();
    scrollTo(0, pageHeight);
    if (pageHost.getHeight() == 0) centerAfterLayout = true;
  }

  public boolean isSettling() {
    return settling;
  }

  /** Native displacement from the middle page; positive reveals next, negative reveals previous. */
  public float offsetPixels() {
    return getScrollY() - pageHeight;
  }

  private void stopSettling() {
    scroller.abortAnimation();
    settling = false;
    settleCallback = null;
  }

  private void finishSettling() {
    if (!settling) return;
    Runnable callback = settleCallback;
    settleCallback = null;
    settling = false;
    scrollTo(0, settleTarget);
    if (callback != null) callback.run();
  }

  @Override
  public void computeScroll() {
    if (!settling) return;
    boolean moving = scroller.computeScrollOffset();
    if (moving) scrollTo(0, scroller.getCurrY());
    if (!moving || scroller.isFinished()) finishSettling();
    else postInvalidateOnAnimation();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    pageHeight =
        Math.max(0, MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom());
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    int measuredHeight = Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom());
    if (pageHeight != measuredHeight) {
      pageHeight = measuredHeight;
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    if (width != oldWidth || height != oldHeight) {
      stopSettling();
      pageHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
      centerAfterLayout = true;
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    pageHost.positionPages();
    if (centerAfterLayout) {
      centerAfterLayout = false;
      scrollTo(0, pageHeight);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelAndCenter();
    super.onDetachedFromWindow();
  }

  // The full-screen touch layer owns axis lock, velocity, seek and paging decisions.
  @Override
  public boolean onInterceptTouchEvent(MotionEvent event) {
    return false;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    return false;
  }

  private final class PageHost extends ViewGroup {
    PageHost(Context context) {
      super(context);
      setClipChildren(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
      int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
      int childHeight = MeasureSpec.makeMeasureSpec(pageHeight, MeasureSpec.EXACTLY);
      for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(childWidth, childHeight);
      setMeasuredDimension(width, pageHeight * 3);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      positionPages();
    }

    void positionPages() {
      int width = getWidth();
      if (previousPage != null) previousPage.layout(0, 0, width, pageHeight);
      if (currentPage != null) currentPage.layout(0, pageHeight, width, pageHeight * 2);
      if (nextPage != null) nextPage.layout(0, pageHeight * 2, width, pageHeight * 3);
    }
  }
}
