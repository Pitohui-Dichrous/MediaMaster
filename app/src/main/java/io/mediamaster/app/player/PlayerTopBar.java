package io.mediamaster.app.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Keeps the video count intact; only the filename gives up horizontal space. */
// PlayerActivity supplies the existing controls directly; this container is never inflated from
// XML.
@SuppressLint("ViewConstructor")
final class PlayerTopBar extends ViewGroup {
  private final View back, feedback, rotate, more;
  private final View[] controls;
  private final TextView title, counter;
  private int rowHeight, countRowHeight;

  PlayerTopBar(
      Context context,
      View back,
      TextView title,
      TextView counter,
      View feedback,
      View rotate,
      View more) {
    super(context);
    this.back = back;
    this.title = title;
    this.counter = counter;
    this.feedback = feedback;
    this.rotate = rotate;
    this.more = more;
    controls = new View[] {back, feedback, rotate, more};
    addView(back);
    addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    addView(counter, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    addView(feedback, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    addView(rotate);
    addView(more);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
    int buttonsWidth = 0;
    rowHeight = 0;
    for (View control : controls) {
      if (!attached(control)) continue;
      LayoutParams params = control.getLayoutParams();
      control.measure(
          MeasureSpec.makeMeasureSpec(
              params.width >= 0 ? params.width : contentWidth,
              params.width >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
          MeasureSpec.makeMeasureSpec(
              Math.max(0, params.height),
              params.height >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
      buttonsWidth += control.getMeasuredWidth();
      rowHeight = Math.max(rowHeight, control.getMeasuredHeight());
    }
    counter.measure(
        MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    int countWidth = counter.getMeasuredWidth();
    int titleMinimum = Math.round(48 * getResources().getDisplayMetrics().density);
    // At enlarged system fonts, use a shallow second line instead of clipping the count or
    // shrinking touch targets. Ordinary phone widths keep the original single-row presentation.
    boolean countBelow = buttonsWidth + countWidth + titleMinimum > contentWidth;
    int titleWidth = Math.max(0, contentWidth - buttonsWidth - (countBelow ? 0 : countWidth));
    title.measure(
        MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    rowHeight = Math.max(rowHeight, title.getMeasuredHeight());
    if (!countBelow) rowHeight = Math.max(rowHeight, counter.getMeasuredHeight());
    countRowHeight = countBelow ? counter.getMeasuredHeight() : 0;
    setMeasuredDimension(
        resolveSize(width, widthMeasureSpec),
        resolveSize(
            getPaddingTop() + rowHeight + countRowHeight + getPaddingBottom(), heightMeasureSpec));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    int x = getPaddingLeft();
    x = placeInRow(back, x);
    x = placeInRow(title, x);
    if (countRowHeight == 0) x = placeInRow(counter, x);
    x = placeInRow(feedback, x);
    x = placeInRow(rotate, x);
    placeInRow(more, x);
    if (countRowHeight > 0) {
      int edge = getWidth() - getPaddingRight();
      int countTop = getPaddingTop() + rowHeight;
      counter.layout(
          edge - counter.getMeasuredWidth(),
          countTop,
          edge,
          countTop + counter.getMeasuredHeight());
    }
  }

  private boolean attached(View child) {
    return child.getParent() == this && child.getVisibility() != GONE;
  }

  private int placeInRow(View child, int x) {
    if (!attached(child)) return x;
    int y = getPaddingTop() + (rowHeight - child.getMeasuredHeight()) / 2;
    child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
    return x + child.getMeasuredWidth();
  }
}
