package io.mediamaster.app.library;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;

/** Measures the list once for its largest viewport; chrome scrolls through real list padding. */
public final class LibraryViewport extends ViewGroup {
  private final LibraryHeader header;
  private final View body;
  private final RecyclerView list;

  public LibraryViewport(Context context, LibraryHeader header, View body, RecyclerView list) {
    super(context);
    this.header = header;
    this.body = body;
    this.list = list;
    addView(body, new LayoutParams(-1, -1));
    addView(header, new LayoutParams(-1, -2));
    setClipChildren(true);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
    header.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    if (list.getPaddingTop() != header.collapseRange())
      list.setPadding(
          list.getPaddingLeft(),
          header.collapseRange(),
          list.getPaddingRight(),
          list.getPaddingBottom());
    body.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(
            Math.max(0, height - header.compactHeight()), MeasureSpec.EXACTLY));
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    body.layout(0, header.compactHeight(), getMeasuredWidth(), getMeasuredHeight());
    header.layout(0, 0, getMeasuredWidth(), header.getMeasuredHeight());
    header.setCollapseOffset(list.computeVerticalScrollOffset());
  }
}
