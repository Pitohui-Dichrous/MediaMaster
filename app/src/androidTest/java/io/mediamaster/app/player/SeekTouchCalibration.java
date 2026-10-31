package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Physical-touch calibration only. Widget request coordinates never establish decoded frame PTS.
 */
final class SeekTouchCalibration {
  static final long PROTOCOL_DURATION_MS = 30_000;

  interface Controls {
    void pause() throws Exception;

    Rect renew() throws Exception;
  }

  static final class Point {
    final int x;
    final double inferredRequestMs;

    Point(int x, double inferredRequestMs) {
      this.x = x;
      this.inferredRequestMs = inferredRequestMs;
    }
  }

  private final double interceptMs, millisecondsPerPixel;
  private final int calibratedWidth;
  final JSONObject evidence;

  private SeekTouchCalibration(
      double interceptMs, double millisecondsPerPixel, int calibratedWidth, JSONObject evidence) {
    this.interceptMs = interceptMs;
    this.millisecondsPerPixel = millisecondsPerPixel;
    this.calibratedWidth = calibratedWidth;
    this.evidence = evidence;
  }

  static long targetMs(double fraction) {
    return (long) (PROTOCOL_DURATION_MS * fraction) / 40 * 40;
  }

  Point point(Rect bar, long targetMs) {
    assertEquals("SeekBar width changed after physical calibration", calibratedWidth, bar.width());
    int localX = (int) Math.round((targetMs - interceptMs) / millisecondsPerPixel);
    assertTrue(
        "Calibrated request is outside the visible SeekBar", localX > 0 && localX < bar.width());
    double inferred = interceptMs + millisecondsPerPixel * localX;
    assertTrue(
        "Integer touch coordinate cannot represent requested time within one frame",
        Math.abs(inferred - targetMs) < 40);
    return new Point(bar.left + localX, inferred);
  }

  static Point uncalibrated(
      Context context, String packageName, Rect bar, long targetMs, long durationMs) {
    // The VLC 3.7.2 runtime overlay replaces its XML's zero padding with 8 dp on phones.
    float inset =
        (packageName.equals(context.getPackageName()) ? 18 : 8)
            * context.getResources().getDisplayMetrics().density;
    int x =
        Math.round(bar.left + inset + targetMs / (float) durationMs * (bar.width() - 2 * inset));
    double inferred = (x - bar.left - inset) / (bar.width() - 2.0 * inset) * durationMs;
    return new Point(x, inferred);
  }

  static SeekTouchCalibration measure(
      Instrumentation instrumentation, String packageName, Controls controls, JSONObject evidence)
      throws Exception {
    UiAutomation automation = instrumentation.getUiAutomation();
    boolean own = packageName.equals(instrumentation.getTargetContext().getPackageName());
    evidence
        .put(
            "method",
            "Three held physical touches; stable widget RangeInfo and requested milliseconds")
        .put("usesDecodedFrameForCalibration", false)
        .put(
            "requestSource",
            own ? "held-gestureTarget-with-RangeInfo" : "held-RangeInfo-current-ms");
    JSONArray samples = new JSONArray();
    evidence.put("samples", samples);
    double[] xs = new double[3], requests = new double[3];
    double[] fractions = {.17, .53, .83};
    int width = -1;
    for (int i = 0; i < fractions.length; i++) {
      controls.pause();
      Rect bar = controls.renew();
      controls.pause();
      Range before = range(automation, packageName);
      assertNotNull("Calibration requires a visible SeekBar with numeric RangeInfo", before);
      bar = before.bounds;
      if (width < 0) width = bar.width();
      assertEquals("Calibration layout changed", width, bar.width());
      int x = bar.left + (int) Math.round(bar.width() * fractions[i]);
      long down = SystemClock.uptimeMillis();
      inject(automation, MotionEvent.ACTION_DOWN, x, bar.exactCenterY(), down);
      try {
        SystemClock.sleep(100);
        Range first = range(automation, packageName);
        long firstRequest = own ? ownRequestedMs(instrumentation) : -1;
        SystemClock.sleep(100);
        Range second = range(automation, packageName);
        long secondRequest = own ? ownRequestedMs(instrumentation) : -1;
        assertNotNull("SeekBar disappeared during held calibration touch", first);
        assertNotNull("SeekBar disappeared during held calibration touch", second);
        assertEquals("SeekBar moved during calibration", bar, second.bounds);
        assertEquals("Held widget progress is not stable", first.current, second.current, .01);
        assertEquals("Held widget range changed", first.maximum, second.maximum, .01);
        assertEquals("Expected zero-based seek range", 0, second.minimum, .01);
        assertTrue("Seek range is invalid", second.maximum > 0);
        double requested;
        if (own) {
          assertEquals("Held app request is not stable", firstRequest, secondRequest);
          assertTrue("Own progress range changed unexpectedly", second.maximum == 10000);
          requested = secondRequest;
        } else {
          assertTrue(
              "External VLC RangeInfo must expose the fixture's millisecond duration",
              second.maximum >= 29500 && second.maximum <= 30500);
          requested = second.current;
        }
        xs[i] = x - bar.left;
        requests[i] = requested;
        samples.put(
            new JSONObject()
                .put("x", x)
                .put("localX", xs[i])
                .put("seekBar", bar.flattenToString())
                .put("rangeMin", second.minimum)
                .put("rangeMax", second.maximum)
                .put("rangeCurrent", second.current)
                .put("requestedMs", requested)
                .put("heldMs", SystemClock.uptimeMillis() - down));
      } finally {
        inject(automation, MotionEvent.ACTION_UP, x, bar.exactCenterY(), down);
      }
      SystemClock.sleep(250);
    }
    double slope = (requests[2] - requests[0]) / (xs[2] - xs[0]);
    double intercept = requests[0] - slope * xs[0];
    double residual = requests[1] - (intercept + slope * xs[1]);
    evidence
        .put("interceptMs", intercept)
        .put("millisecondsPerPixel", slope)
        .put("validationResidualMs", residual)
        .put("seekBarWidthPx", width);
    assertTrue(
        "Calibration slope is not a plausible 30-second seek range",
        slope > 0 && slope * width >= 29500 && slope * width <= 36000);
    assertTrue(
        "Independent middle point does not validate the touch-to-request fit",
        Math.abs(residual) < 20);
    evidence.put("validated", true);
    return new SeekTouchCalibration(intercept, slope, width, evidence);
  }

  /** Resets setup away from EOF with the same physical gesture in both apps, outside timing. */
  static JSONObject resetOrigin(
      Instrumentation instrumentation,
      String packageName,
      Controls controls,
      SeekTouchCalibration calibration,
      long durationMs)
      throws Exception {
    controls.pause();
    Rect bar = controls.renew();
    controls.pause();
    Range fresh = range(instrumentation.getUiAutomation(), packageName);
    assertNotNull("No visible SeekBar for the common origin reset", fresh);
    bar = fresh.bounds;
    long requested = 400;
    Point point =
        calibration == null
            ? uncalibrated(
                instrumentation.getTargetContext(), packageName, bar, requested, durationMs)
            : calibration.point(bar, requested);
    long down = SystemClock.uptimeMillis();
    inject(
        instrumentation.getUiAutomation(),
        MotionEvent.ACTION_DOWN,
        point.x,
        bar.exactCenterY(),
        down);
    try {
      SystemClock.sleep(80);
    } finally {
      inject(
          instrumentation.getUiAutomation(),
          MotionEvent.ACTION_UP,
          point.x,
          bar.exactCenterY(),
          down);
    }
    return new JSONObject()
        .put("requestedMs", requested)
        .put("inferredInputRequestMs", point.inferredRequestMs)
        .put("tapX", point.x)
        .put("tapY", bar.exactCenterY());
  }

  private static long ownRequestedMs(Instrumentation instrumentation) {
    AtomicReference<Long> requested = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            for (Activity activity :
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
              if (!activity.getClass().getName().equals("io.mediamaster.app.player.PlayerActivity"))
                continue;
              Field field = activity.getClass().getDeclaredField("gestureTarget");
              field.setAccessible(true);
              requested.set(field.getLong(activity));
            }
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null)
      throw new AssertionError("Cannot inspect held request", failure.get());
    assertNotNull("No resumed own player for calibration", requested.get());
    return requested.get();
  }

  private static Range range(UiAutomation automation, String packageName) {
    if (Build.VERSION.SDK_INT >= 33) automation.clearCache();
    AccessibilityNodeInfo root = automation.getRootInActiveWindow();
    if (root != null) root.refresh();
    return range(root, packageName, null);
  }

  private static Range range(AccessibilityNodeInfo node, String packageName, Range best) {
    if (node == null || !node.isVisibleToUser()) return best;
    if (node.getPackageName() != null && !packageName.contentEquals(node.getPackageName()))
      return best;
    AccessibilityNodeInfo.RangeInfo info = node.getRangeInfo();
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (info != null
        && bounds.width() > 200
        && bounds.width() > bounds.height() * 4
        && (best == null || bounds.width() > best.bounds.width())) best = new Range(bounds, info);
    for (int i = 0; i < node.getChildCount(); i++)
      best = range(node.getChild(i), packageName, best);
    return best;
  }

  private static void inject(UiAutomation automation, int action, float x, float y, long down) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("Calibration touch injection failed", automation.injectInputEvent(event, true));
    } finally {
      event.recycle();
    }
  }

  private static final class Range {
    final Rect bounds;
    final float minimum, maximum, current;

    Range(Rect bounds, AccessibilityNodeInfo.RangeInfo range) {
      this.bounds = bounds;
      minimum = range.getMin();
      maximum = range.getMax();
      current = range.getCurrent();
    }
  }
}
