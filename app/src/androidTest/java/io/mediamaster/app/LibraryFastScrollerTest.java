package io.mediamaster.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.OverScroller;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.library.LibraryFastScroller;
import io.mediamaster.app.library.LibraryHeader;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Finger-driven scrolling against the same 120-row fixture as the directory chrome test. */
@RunWith(AndroidJUnit4.class)
public final class LibraryFastScrollerTest {
  @Rule
  public ActivityTestRule<MainActivity> activityRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private MainActivity activity;
  private RecyclerView list;
  private LibraryFastScroller handle;
  private List<VideoItem> original;
  private String lastGesture = "No drag sampled yet";

  @Before
  public void showLongLibrary() throws Exception {
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    activity = activityRule.launchActivity(new Intent(context, MainActivity.class));
    long deadline = SystemClock.uptimeMillis() + 45000;
    while (!onMain(() -> !(Boolean) read(activity, "scanning") && !items().isEmpty())) {
      assertTrue(
          "The existing video fixtures finish indexing", SystemClock.uptimeMillis() < deadline);
      SystemClock.sleep(50);
    }
    onMain(
        () -> {
          original = new ArrayList<>(items());
          items().clear();
          for (int index = 0; index < 120; index++)
            items().add(original.get(index % original.size()));
          invoke(activity, "filter");
          list = (RecyclerView) read(activity, "list");
          handle = (LibraryFastScroller) read(activity, "fastScroller");
          return null;
        });
    SystemClock.sleep(200);
    assertEquals(120, onMain(() -> list.getAdapter().getItemCount()).intValue());
  }

  @After
  public void restoreLibrary() throws Exception {
    try {
      if (activity != null && original != null)
        onMain(
            () -> {
              dispatch(
                  handle, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, thumbCenter());
              items().clear();
              items().addAll(original);
              invoke(activity, "filter");
              return null;
            });
    } finally {
      if (activity != null) activityRule.finishActivity();
    }
  }

  @Test
  public void randomActionIsAFullWidthRowBelowSearchAndCollapsesOnAnActualUpwardSwipe()
      throws Exception {
    onMain(
        () -> {
          View random = withDescription(activity.getWindow().getDecorView(), "随机播放，开启随机连播");
          assertTrue(
              "The standalone random action remains an accessible button",
              random instanceof Button);
          assertTrue(random.isShown() && random.isEnabled());
          View searchBar = (View) ((EditText) read(activity, "search")).getParent();
          Rect searchBounds = visibleBounds(searchBar), randomBounds = visibleBounds(random);
          assertEquals(
              "Random and search share the same left edge",
              searchBounds.left,
              randomBounds.left,
              2);
          assertEquals(
              "Random and search share the same right edge",
              searchBounds.right,
              randomBounds.right,
              2);
          assertTrue(
              "Random occupies its own row below search", randomBounds.top >= searchBounds.bottom);
          assertTrue(
              "The full row remains a native-size touch target", random.getHeight() >= dp(48));
          return null;
        });
    long downTime = SystemClock.uptimeMillis();
    float start = onMain(() -> list.getHeight() * .82f);
    onMain(() -> dispatch(list, downTime, MotionEvent.ACTION_DOWN, start));
    for (int step = 1; step <= 8; step++) {
      SystemClock.sleep(16);
      float y = start - start * .75f * step / 8;
      onMain(() -> dispatch(list, downTime, MotionEvent.ACTION_MOVE, y));
    }
    // The drag itself must collapse chrome; cancellation prevents a list fling obscuring that fact.
    onMain(() -> dispatch(list, downTime, MotionEvent.ACTION_CANCEL, start * .25f));
    SystemClock.sleep(100);
    onMain(
        () -> {
          LibraryHeader header = (LibraryHeader) read(activity, "libraryHeader");
          assertEquals(header.collapseRange(), header.collapseOffset(), 1f);
          assertFalse(
              withDescription(activity.getWindow().getDecorView(), "随机播放，开启随机连播").isShown());
          assertFalse(withDescription(activity.getWindow().getDecorView(), "设置").isShown());
          assertFalse(
              visibleBounds((View) ((EditText) read(activity, "search")).getParent()).isEmpty());
          assertFalse(visibleBounds(header.getChildAt(3)).isEmpty());
          assertTrue(
              "The real list moved beyond the collapsing header",
              offset() > header.collapseRange());
          return null;
        });
  }

  @Test
  public void slowReleasesStopAndFastReleasesContinueInBothDirectionsThenSettle() throws Exception {
    for (int direction : new int[] {1, -1}) {
      positionAt(direction > 0 ? .3f : .7f);
      int before = onMain(this::offset);
      drag(direction * dp(32), 8, 80, MotionEvent.ACTION_UP);
      assertTrue(
          "Slow dragging moves the actual video list",
          direction * (onMain(this::offset) - before) > dp(4));
      assertTrue(
          "A slow release never starts native inertia",
          onMain(() -> nativeScroller().isFinished()));
      assertStableAfterRelease("A slow release has no residual scrolling");

      positionAt(direction > 0 ? .3f : .7f);
      drag(direction * dp(96), 6, 16, MotionEvent.ACTION_UP);
      assertContinuesInDirection(direction);
      awaitSettled();
      assertStableAfterRelease("Friction brings the fast scroll to rest");
    }
  }

  @Test
  public void newFingerStopsInertiaAndCancelOrSecondFingerCannotStartIt() throws Exception {
    // Leave a longer runway so this case measures interruption before reaching a list boundary.
    positionAt(.15f);
    drag(dp(120), 6, 16, MotionEvent.ACTION_UP);
    SystemClock.sleep(40);
    int coasting = onMain(this::offset);
    SystemClock.sleep(35);
    long initialDownTime =
        onMain(
            () -> {
              assertTrue(
                  "The interruption follows actual movement after release; " + describeState(),
                  offset() > coasting);
              assertFalse(
                  "The new finger arrives while the fling is still running; " + describeState(),
                  nativeScroller().isFinished());
              // Check and interrupt in the same main-thread turn: no extra queued test work can let
              // it stop.
              long now = SystemClock.uptimeMillis();
              assertTrue(dispatch(handle, now, MotionEvent.ACTION_DOWN, thumbCenter()));
              assertTrue(
                  "DOWN closes native inertia immediately; " + describeState(),
                  nativeScroller().isFinished());
              return now;
            });
    assertStableAfterRelease("A new DOWN immediately stops the previous fling");
    onMain(() -> dispatch(handle, initialDownTime, MotionEvent.ACTION_CANCEL, thumbCenter()));
    assertStableAfterRelease("Cancelling the stationary new finger stays stopped");

    positionAt(.3f);
    drag(dp(96), 6, 16, MotionEvent.ACTION_CANCEL);
    assertTrue("CANCEL never starts native inertia", onMain(() -> nativeScroller().isFinished()));
    assertStableAfterRelease("CANCEL discards even a fast drag velocity");

    positionAt(.3f);
    long downTime = beginGrab();
    float start = onMain(this::thumbCenter);
    moveFinger(downTime, start, dp(96), 6, 16);
    long heldDownTime = downTime;
    onMain(
        () -> {
          secondFingerEvent(heldDownTime, start + dp(96), MotionEvent.ACTION_POINTER_DOWN);
          assertTrue("Adding a pointer never starts native inertia", nativeScroller().isFinished());
          secondFingerEvent(heldDownTime, start + dp(96), MotionEvent.ACTION_POINTER_UP);
          dispatch(handle, heldDownTime, MotionEvent.ACTION_UP, start + dp(96));
          return null;
        });
    assertStableAfterRelease("A second pointer cancels the drag without releasing inertia");
  }

  @Test
  public void fastReleasesClampAtBothRealListEdgesWithoutRebounding() throws Exception {
    for (boolean bottom : new boolean[] {true, false}) {
      positionAt(bottom ? .9f : .1f);
      drag((bottom ? 1 : -1) * dp(240), 6, 16, MotionEvent.ACTION_UP);
      awaitSettled();
      onMain(
          () -> {
            LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
            assertFalse(
                "The clamped list cannot move farther through the boundary",
                list.canScrollVertically(bottom ? 1 : -1));
            if (bottom) {
              int lastIndex = list.getAdapter().getItemCount() - 1;
              assertEquals(lastIndex, layout.findLastVisibleItemPosition());
              View last = layout.findViewByPosition(lastIndex);
              assertNotNull(last);
              assertEquals(
                  "The last video keeps only the intended bottom gutter",
                  list.getHeight() - list.getPaddingBottom(),
                  layout.getDecoratedBottom(last),
                  3);
            } else {
              assertEquals(0, layout.findFirstVisibleItemPosition());
              View first = layout.findViewByPosition(0);
              assertNotNull(first);
              assertEquals(list.getPaddingTop(), layout.getDecoratedTop(first), 3);
              assertEquals(
                  0f, ((LibraryHeader) read(activity, "libraryHeader")).collapseOffset(), 1f);
            }
            return null;
          });
      assertStableAfterRelease("The boundary never bounces the list in the opposite direction");
    }
  }

  private void positionAt(float fraction) throws Exception {
    awaitSettled();
    onMain(
        () -> {
          Bundle progress = new Bundle();
          progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, fraction * 100);
          assertTrue(
              handle.performAccessibilityAction(
                  AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress));
          return null;
        });
    SystemClock.sleep(100);
  }

  private long beginGrab() throws Exception {
    long now = SystemClock.uptimeMillis();
    onMain(
        () -> {
          assertTrue(
              "A finger captures the actual scroll handle",
              dispatch(handle, now, MotionEvent.ACTION_DOWN, thumbCenter()));
          return null;
        });
    return now;
  }

  private void drag(float delta, int steps, long frameMs, int finishAction) throws Exception {
    long downTime = beginGrab();
    float start = onMain(this::thumbCenter);
    moveFinger(downTime, start, delta, steps, frameMs);
    long releaseSampleTime = downTime + steps * frameMs + 1;
    waitUntilSampleTime(releaseSampleTime);
    onMain(
        () -> {
          long handledAt = SystemClock.uptimeMillis();
          boolean consumed =
              dispatch(handle, downTime, releaseSampleTime, finishAction, start + delta);
          lastGesture +=
              " releaseAction="
                  + finishAction
                  + " releaseSampleMs="
                  + (releaseSampleTime - downTime)
                  + " releaseWallMs="
                  + (handledAt - downTime)
                  + " releaseLagMs="
                  + (handledAt - releaseSampleTime);
          return consumed;
        });
  }

  private void moveFinger(long downTime, float start, float delta, int steps, long frameMs)
      throws Exception {
    long maxDispatchLag = 0;
    for (int step = 1; step <= steps; step++) {
      // Hardware keeps the sample time even when the app's main thread processes an event late.
      // Never submit a future sample, but do not turn dispatch latency into a slower finger either.
      long sampleTime = downTime + step * frameMs;
      waitUntilSampleTime(sampleTime);
      float y = start + delta * step / steps;
      long handledAt =
          onMain(
              () -> {
                long now = SystemClock.uptimeMillis();
                dispatch(handle, downTime, sampleTime, MotionEvent.ACTION_MOVE, y);
                return now;
              });
      maxDispatchLag = Math.max(maxDispatchLag, handledAt - sampleTime);
      lastGesture =
          "deltaDp="
              + delta / context.getResources().getDisplayMetrics().density
              + " samples="
              + step
              + "/"
              + steps
              + " sampleIntervalMs="
              + frameMs
              + " sampleDurationMs="
              + (sampleTime - downTime)
              + " dispatchWallMs="
              + (handledAt - downTime)
              + " maxDispatchLagMs="
              + maxDispatchLag;
    }
  }

  private static void waitUntilSampleTime(long sampleTime) {
    long remaining = sampleTime - SystemClock.uptimeMillis();
    if (remaining > 0) SystemClock.sleep(remaining);
  }

  private void assertContinuesInDirection(int direction) throws Exception {
    // Exclude the final drag layout; later movement must be the release's inertia.
    SystemClock.sleep(40);
    int previous = onMain(this::offset), first = previous;
    for (int sample = 0; sample < 4; sample++) {
      SystemClock.sleep(35);
      int current = onMain(this::offset);
      check(
          direction * (current - previous) >= -2,
          "Release motion preserves the finger's direction: previous="
              + previous
              + " current="
              + current);
      previous = current;
    }
    check(
        direction * (previous - first) > dp(4),
        "A quick release continues scrolling after the finger is lifted: first="
            + first
            + " last="
            + previous);
  }

  private void assertStableAfterRelease(String message) throws Exception {
    SystemClock.sleep(80);
    int atRest = onMain(this::offset);
    for (int sample = 0; sample < 3; sample++) {
      SystemClock.sleep(75);
      int current = onMain(this::offset);
      check(
          Math.abs(atRest - current) <= 2, message + ": release=" + atRest + " current=" + current);
    }
    check(onMain(() -> nativeScroller().isFinished()), message);
  }

  private void awaitSettled() throws Exception {
    long deadline = SystemClock.uptimeMillis() + 8000;
    while (!onMain(() -> nativeScroller().isFinished())) {
      check(SystemClock.uptimeMillis() < deadline, "The native release animation finishes");
      SystemClock.sleep(40);
    }
    SystemClock.sleep(60);
  }

  private OverScroller nativeScroller() throws Exception {
    for (Field field : handle.getClass().getDeclaredFields())
      if (field.getType() == OverScroller.class) {
        field.setAccessible(true);
        return (OverScroller) field.get(handle);
      }
    throw new AssertionError(
        "Fast scrolling must expose its native OverScroller for settling checks");
  }

  private void check(boolean condition, String message) throws Exception {
    if (!condition) fail(message + "; " + onMain(this::describeState));
  }

  /** Diagnostics are deliberately actual list/native values, rather than the velocity formula. */
  private String describeState() throws Exception {
    OverScroller scroller = nativeScroller();
    return lastGesture
        + "; offset="
        + offset()
        + " maxOffset="
        + (list.computeVerticalScrollRange() - list.computeVerticalScrollExtent())
        + " canUp="
        + list.canScrollVertically(-1)
        + " canDown="
        + list.canScrollVertically(1)
        + " nativeFinished="
        + scroller.isFinished()
        + " nativeStart="
        + scroller.getStartY()
        + " nativeCurrent="
        + scroller.getCurrY()
        + " nativeFinal="
        + scroller.getFinalY()
        + " nativeVelocityPxPerSec="
        + scroller.getCurrVelocity()
        + " thumbTop="
        + read(handle, "thumbTop")
        + " thumbHeight="
        + read(handle, "thumbHeight");
  }

  private void secondFingerEvent(long downTime, float y, int action) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
    for (int index = 0; index < 2; index++) {
      properties[index] = new MotionEvent.PointerProperties();
      properties[index].id = index;
      properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[index] = new MotionEvent.PointerCoords();
      coordinates[index].x = handle.getWidth() / 2f;
      coordinates[index].y = y + index * dp(8);
      coordinates[index].pressure = coordinates[index].size = 1;
    }
    MotionEvent event =
        MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            properties,
            coordinates,
            0,
            0,
            1,
            1,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0);
    try {
      handle.dispatchTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  private boolean dispatch(View target, long downTime, int action, float y) {
    return dispatch(target, downTime, SystemClock.uptimeMillis(), action, y);
  }

  private boolean dispatch(View target, long downTime, long sampleTime, int action, float y) {
    MotionEvent event =
        MotionEvent.obtain(downTime, sampleTime, action, target.getWidth() / 2f, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      return target.dispatchTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  private int offset() {
    return list.computeVerticalScrollOffset();
  }

  private float thumbCenter() throws Exception {
    return (Float) read(handle, "thumbTop") + (Float) read(handle, "thumbHeight") / 2;
  }

  private int dp(int value) {
    return Ui.dp(context, value);
  }

  @SuppressWarnings("unchecked")
  private List<VideoItem> items() throws Exception {
    return (List<VideoItem>) read(activity, "all");
  }

  private static Rect visibleBounds(View view) {
    Rect result = new Rect();
    assertTrue("The expected directory row is visibly present", view.getGlobalVisibleRect(result));
    return result;
  }

  private static View withDescription(View view, String description) {
    CharSequence label = view.getContentDescription();
    if (label != null && description.contentEquals(label)) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        View found = withDescription(group.getChildAt(index), description);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static Object read(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void invoke(Object target, String name) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(target);
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            result.set(action.call());
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    Throwable error = failure.get();
    if (error instanceof AssertionError) throw (AssertionError) error;
    if (error instanceof Exception) throw (Exception) error;
    if (error != null) throw new RuntimeException(error);
    return result.get();
  }
}
