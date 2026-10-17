package io.mediamaster.app.library;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Scroll-driven chrome: immutable child layout, only translations and clipping change per frame.
 */
public final class LibraryHeader extends ViewGroup {
  private View title, search, random, library;
  private int titleHeight, randomHeight, expandedHeight, compactHeight;
  private float collapse;
  private final Rect clip = new Rect();

  public LibraryHeader(Context context) {
    super(context);
    setClipChildren(true);
  }

  public void setSections(View title, View search, View random, View library) {
    this.title = title;
    this.search = search;
    this.random = random;
    this.library = library;
    // Moving sections are underneath the two opaque pinned surfaces.
    addView(title, new LayoutParams(-1, -2));
    addView(random, new LayoutParams(-1, -2));
    addView(search, new LayoutParams(-1, -2));
    addView(library, new LayoutParams(-1, -2));
  }

  public int collapseRange() {
    return titleHeight + randomHeight;
  }

  public int compactHeight() {
    return compactHeight;
  }

  public float collapseOffset() {
    return collapse;
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    for (int index = 0; index < getChildCount(); index++)
      getChildAt(index)
          .measure(
              MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    titleHeight = title.getMeasuredHeight();
    randomHeight = random.getMeasuredHeight();
    compactHeight = search.getMeasuredHeight() + library.getMeasuredHeight();
    expandedHeight = compactHeight + collapseRange();
    setMeasuredDimension(width, expandedHeight);
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int width = r - l;
    title.layout(0, 0, width, titleHeight);
    search.layout(0, titleHeight, width, titleHeight + search.getMeasuredHeight());
    random.layout(
        0,
        titleHeight + search.getMeasuredHeight(),
        width,
        titleHeight + search.getMeasuredHeight() + randomHeight);
    library.layout(0, expandedHeight - library.getMeasuredHeight(), width, expandedHeight);
    setCollapseOffset(collapse);
  }

  public void setCollapseOffset(float value) {
    collapse = Math.max(0, Math.min(collapseRange(), value));
    title.setTranslationY(-collapse);
    title.setAlpha(Math.max(0, 1 - collapse / Math.max(1, titleHeight)));
    search.setTranslationY(-Math.min(collapse, titleHeight));
    random.setTranslationY(-collapse);
    random.setAlpha(Math.max(0, 1 - collapse / Math.max(1, collapseRange())));
    library.setTranslationY(-collapse);
    title.setVisibility(collapse >= titleHeight ? INVISIBLE : VISIBLE);
    random.setVisibility(collapse >= collapseRange() ? INVISIBLE : VISIBLE);
    clip.set(0, 0, getWidth(), Math.round(expandedHeight - collapse));
    setClipBounds(clip);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getY() > expandedHeight - collapse) return false;
    return super.dispatchTouchEvent(event);
  }
}
