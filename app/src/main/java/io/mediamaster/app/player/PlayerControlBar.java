package io.mediamaster.app.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Anchored corner actions and a centered transport, with a compact narrow-screen fallback. */
// PlayerActivity supplies the existing controls directly; this container is never inflated from
// XML.
@SuppressLint("ViewConstructor")
final class PlayerControlBar extends ViewGroup {
  private final View actions, transport, presentation;
  private final View[] controls;
  private boolean stacked;
  private int firstRowHeight, transportRowHeight;

  PlayerControlBar(Context context, View actions, View transport, View presentation) {
    super(context);
    this.actions = actions;
    this.transport = transport;
    this.presentation = presentation;
    controls = new View[] {actions, transport, presentation};
    addView(actions, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    addView(transport, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    addView(presentation);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
    for (View child : controls) {
      LayoutParams params = child.getLayoutParams();
      child.measure(
          MeasureSpec.makeMeasureSpec(
              params.width >= 0 ? params.width : contentWidth,
              params.width >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
          MeasureSpec.makeMeasureSpec(
              Math.max(0, params.height),
              params.height >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
    }
    stacked =
        transport.getMeasuredWidth()
                + 2 * Math.max(actions.getMeasuredWidth(), presentation.getMeasuredWidth())
            > contentWidth;
    firstRowHeight = Math.max(actions.getMeasuredHeight(), presentation.getMeasuredHeight());
    transportRowHeight = transport.getMeasuredHeight();
    int height =
        stacked
            ? firstRowHeight + transportRowHeight
            : Math.max(firstRowHeight, transportRowHeight);
    setMeasuredDimension(
        resolveSize(width, widthMeasureSpec),
        resolveSize(getPaddingTop() + height + getPaddingBottom(), heightMeasureSpec));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    int start = getPaddingLeft(), end = getWidth() - getPaddingRight();
    int y = getPaddingTop();
    int rowHeight = stacked ? firstRowHeight : Math.max(firstRowHeight, transportRowHeight);
    place(actions, start, y + (rowHeight - actions.getMeasuredHeight()) / 2);
    place(
        presentation,
        end - presentation.getMeasuredWidth(),
        y + (rowHeight - presentation.getMeasuredHeight()) / 2);
    place(
        transport,
        start + (end - start - transport.getMeasuredWidth()) / 2,
        stacked ? y + firstRowHeight : y + (rowHeight - transportRowHeight) / 2);
  }

  private void place(View child, int left, int top) {
    child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
  }
}
