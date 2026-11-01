package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.SeekBar;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opt-in physical taps on explicit media. References and pictures remain in memory. Native clocks
 * are never interpreted as frame PTS. First changed picture and confirmed target are separate.
 */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class IsolatedPhysicalSeekTest {
  private static final long REFERENCE_DWELL_MS = 2000, REFERENCE_QUIET_MS = 1000;
  private static final String[] PREFS = {"engine_choice", "native_decoder_mode", "playback_mode"};
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();
  private final Handler main = new Handler(Looper.getMainLooper());
  private PlayerActivity activity;
  private VideoPage page;
  private VideoItem fixtureItem;
  private View surface;
  private Object player;
  private Window.Callback originalCallback;
  private HandlerThread copyThread;
  private Handler copyHandler;
  private long duration, generation, phaseDeadline;
  private int left, width, y, bitmapWidth, bitmapHeight, barcodeFps;
  private volatile Tap activeTap;
  private volatile boolean stop;
  private Thread sender;

  @Test
  public void measureIsolatedPhysicalSeeks() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    String supplied = args.getString("benchUri", "");
    assumeTrue("Supply an explicit local MediaStore URI", !supplied.isEmpty());
    int operations = integer(args, "benchOperations", 12);
    int engine = integer(args, "benchEngine", 0), decoder = integer(args, "benchNativeDecoder", 0);
    int recoveryLimit = integer(args, "benchRecoveryLimitMs", 3000);
    barcodeFps = integer(args, "benchBarcodeFps", 0);
    assertTrue(operations == 6 || operations == 12);
    assertTrue(engine >= 0 && engine <= 2 && decoder >= 0 && decoder <= 2);
    assertTrue(recoveryLimit >= 250 && recoveryLimit <= 5000);
    assertTrue(barcodeFps == 0 || barcodeFps == 60);
    String run = args.getString("benchRun", "isolated-" + SystemClock.uptimeMillis());
    assertTrue(run.matches("[A-Za-z0-9_-]{1,100}"));
    Context context = instrumentation.getTargetContext();
    SharedPreferences preferences = context.getSharedPreferences("player", 0);
    boolean[] present = new boolean[PREFS.length];
    int[] saved = new int[PREFS.length];
    for (int i = 0; i < PREFS.length; i++) {
      present[i] = preferences.contains(PREFS[i]);
      saved[i] = preferences.getInt(PREFS[i], 0);
    }
    JSONArray measured = new JSONArray();
    JSONObject result =
        new JSONObject()
            .put("protocol", "isolated-physical-v2")
            .put("operationsExpected", operations)
            .put("engineChoice", engine)
            .put("nativeDecoderChoice", decoder)
            .put("referenceMinimumDwellMs", REFERENCE_DWELL_MS)
            .put("referenceQuietMs", REFERENCE_QUIET_MS)
            .put("phaseLimitMs", 60000)
            .put("targetConfirmationMs", 150)
            .put("scheduledPressMs", 20)
            .put("recoveryLimitMs", recoveryLimit)
            .put("barcodeFps", barcodeFps)
            .put(
                "timingEvidence",
                "capture-completion upper bounds; Injected means MotionEvent eventTime; Dispatch"
                    + " means callback entry; first change can precede UP")
            .put(
                "legacyTimingFields",
                "deliveredPressMs and unsuffixed FromDown/FromUp metrics retain v1"
                    + " callback-completion origins, excluding input delivery and handling")
            .put("timestampOrigin", "input timestamps are relative to injected DOWN eventTime")
            .put(
                "targetEvidence",
                barcodeFps != 0
                    ? "guarded-barcode-and-surface-reference"
                    : "same-surface-content-reference; native frame PTS not independently proven")
            .put("operations", measured)
            .put("completed", false);
    ScopedPlaybackHistory history = null;
    String token = UUID.randomUUID().toString();
    ArrayList<Reference> references = new ArrayList<>();
    int stage = 0;
    Throwable failure = null;
    try {
      history = onMain(() -> ScopedPlaybackHistory.open(context));
      result.put("engineHistoryIsolated", true);
      automation.grantRuntimePermission(
          context.getPackageName(),
          Build.VERSION.SDK_INT >= 33
              ? Manifest.permission.READ_MEDIA_VIDEO
              : Manifest.permission.READ_EXTERNAL_STORAGE);
      Uri uri = explicitFixture(context, supplied);
      assertTrue(
          preferences
              .edit()
              .putInt(PREFS[0], engine)
              .putInt(PREFS[1], decoder)
              .putInt(PREFS[2], PlaybackOrder.SINGLE)
              .commit());
      launch(context, uri, token);
      awaitReady(token);
      copyThread = new HandlerThread("isolated-seek-copy");
      copyThread.start();
      copyHandler = new Handler(copyThread.getLooper());
      onMain(
          () -> {
            Field play = PlayerActivity.class.getDeclaredField("shouldPlay");
            play.setAccessible(true);
            play.setBoolean(activity, false);
            page.setPlayback(true, false, true);
            result
                .put("engine", page.exo != null ? "Media3" : "LibVLC")
                .put("nativeSoftware", optional(page, "nativeSoftware"))
                .put("media3HardwareConfigured", optional(page, "exoHardwareConfigured"))
                .put("media3SoftwareConfigured", optional(page, "exoSoftwareConfigured"));
            return null;
          });
      installDeliveryProbe();
      showControls();
      onMain(
          () -> {
            SeekBar seek = (SeekBar) read(activity, "seek");
            int[] xy = new int[2];
            seek.getLocationOnScreen(xy);
            left = xy[0] + seek.getPaddingLeft();
            width = seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight();
            y = xy[1] + seek.getHeight() / 2;
            assertTrue("A usable physical seek track is required", width >= 100);
            return null;
          });
      phaseDeadline = SystemClock.uptimeMillis() + 60000;
      int steps = operations / 2;
      for (int i = 0; i <= steps; i++) {
        stage = 10 + i;
        int x = left + Math.round(width * (.08f + .84f * i / steps));
        showControls();
        Tap calibrated = synchronousTap(x);
        Bitmap picture = settledReference(calibrated.target);
        Reference reference = new Reference(x, calibrated.target, picture);
        references.add(reference);
        assertTrue(
            "Reference requires visible picture content",
            NativeFrameProbe.nonBlackFraction(picture) > .01);
        if (i > 0)
          assertTrue(
              "Adjacent references must be visually distinguishable",
              distance(references.get(i - 1).picture, picture) > 3.5);
      }
      stage = 30;
      Bitmap reset = settledReference(references.get(0).target);
      try {
        assertTrue(
            "Reset must match the fixed origin reference",
            distance(reset, references.get(0).picture) < 2);
      } finally {
        reset.recycle();
      }
      int index = 0;
      for (int i = 1; i <= steps; i++) {
        stage = 40 + index;
        measure(references.get(i - 1), references.get(i), ++index, 1, recoveryLimit, measured);
      }
      for (int i = steps - 1; i >= 0; i--) {
        stage = 40 + index;
        measure(references.get(i + 1), references.get(i), ++index, -1, recoveryLimit, measured);
      }
      assertEquals(operations, measured.length());
      result.put("completed", true);
    } catch (Throwable error) {
      failure = error;
      result.put("failureStage", stage).put("failureCategory", category(error));
    } finally {
      stop = true;
      try {
        try {
          if (sender != null) {
            sender.join(1500);
            assertFalse("Input sender did not stop", sender.isAlive());
          }
        } finally {
          try {
            if (activeTap != null && activeTap.downs > 0 && activeTap.ups == 0)
              inject(activeTap, MotionEvent.ACTION_CANCEL, true);
          } finally {
            try {
              if (activity != null)
                onMain(
                    () -> {
                      try {
                        if (originalCallback != null)
                          activity.getWindow().setCallback(originalCallback);
                      } finally {
                        activity.finish();
                      }
                      return null;
                    });
            } finally {
              if (history != null) {
                history.closeAfterActivityDestroyed(activity);
                result.put("engineHistoryRestored", true);
              }
            }
          }
        }
      } catch (Throwable cleanup) {
        if (failure == null) failure = cleanup;
        result.put("completed", false).put("cleanupFailed", true);
      } finally {
        QueueStore.forget(token);
        SharedPreferences.Editor restore = preferences.edit();
        for (int i = 0; i < PREFS.length; i++) {
          if (present[i]) restore.putInt(PREFS[i], saved[i]);
          else restore.remove(PREFS[i]);
        }
        boolean restored = restore.commit();
        result.put("preferencesRestored", restored);
        if (!restored) {
          result.put("completed", false);
          if (failure == null) failure = new AssertionError("Preference restoration failed");
        }
        for (Reference reference : references) reference.picture.recycle();
        if (copyThread != null) copyThread.quitSafely();
        writeResult(context, run, result);
      }
    }
    // Keep arbitrary decoder/URI exception text out of instrumentation output too.
    if (failure != null)
      throw new AssertionError(
          "Isolated physical seek failed at stage " + stage + " (" + category(failure) + ")");
  }

  private void measure(
      Reference origin,
      Reference target,
      int index,
      int direction,
      int recoveryLimit,
      JSONArray output)
      throws Exception {
    assertTrue("Benchmark phase deadline exceeded", SystemClock.uptimeMillis() < phaseDeadline);
    showControls();
    State beforeOrigin = state(origin.target);
    Bitmap baseline = capture();
    try {
      assertTrue(
          "Operation must start from the fixed paused reference",
          beforeOrigin.ready
              && state(origin.target).ready
              && distance(baseline, origin.picture) < 2);
    } finally {
      baseline.recycle();
    }
    Tap tap = new Tap(target.x, SystemClock.uptimeMillis() + 80);
    activeTap = tap;
    AtomicReference<Throwable> inputFailure = new AtomicReference<>();
    sender =
        new Thread(
            () -> {
              try {
                waitUntil(tap.downTime);
                if (stop) return;
                inject(tap, MotionEvent.ACTION_DOWN, false);
                waitUntil(tap.downTime + 20);
                if (!stop) inject(tap, MotionEvent.ACTION_UP, false);
              } catch (Throwable error) {
                inputFailure.set(error);
              }
            },
            "isolated-seek-input");
    sender.start();
    long firstChanged = -1, firstMatch = -1, stableStart = -1, confirmedAt = -1;
    int captures = 0;
    long maxCopyMs = 0, previousCapture = -1, maxSampleGapMs = 0;
    JSONObject operation =
        new JSONObject()
            .put("index", index)
            .put("direction", direction)
            .put("expectedTargetMs", target.target)
            .put("passed", false);
    output.put(operation);
    long until = Math.min(phaseDeadline, tap.downTime + recoveryLimit + 1000);
    try {
      waitUntil(tap.downTime);
      while (SystemClock.uptimeMillis() < until) {
        long began = SystemClock.uptimeMillis();
        State before = state(target.target);
        long copyStart = SystemClock.uptimeMillis();
        Bitmap frame = capture();
        long observedAt = SystemClock.uptimeMillis();
        try {
          State after = state(target.target);
          captures++;
          maxCopyMs = Math.max(maxCopyMs, observedAt - copyStart);
          if (previousCapture > 0)
            maxSampleGapMs = Math.max(maxSampleGapMs, observedAt - previousCapture);
          previousCapture = observedAt;
          double changed = distance(origin.picture, frame);
          if (firstChanged < 0
              && tap.deliveredDown > 0
              && copyStart >= tap.deliveredDown
              && changed > 3.5
              && NativeFrameProbe.nonBlackFraction(frame) > .01) firstChanged = observedAt;
          boolean valid =
              tap.deliveredUp > 0
                  && copyStart >= tap.deliveredUp
                  && tap.target == target.target
                  && before.ready
                  && after.ready
                  && before.sequence == after.sequence
                  && distance(target.picture, frame) < 2
                  && barcodeNear(frame, target.target);
          if (valid) {
            if (firstMatch < 0) firstMatch = observedAt;
            if (stableStart < 0) stableStart = observedAt;
            if (observedAt - stableStart >= 150) {
              confirmedAt = observedAt;
              break;
            }
          } else stableStart = -1;
        } finally {
          frame.recycle();
        }
        assertNull("Physical input failed", inputFailure.get());
        waitUntil(began + 16);
      }
      sender.join(1000);
      assertFalse("Input sender did not finish", sender.isAlive());
      assertNull("Physical input failed", inputFailure.get());
      assertEquals("Exactly one DOWN must be delivered", 1, tap.downs);
      assertEquals("Exactly one UP must be delivered", 1, tap.ups);
      assertEquals(
          "Physical target differs from the immutable reference", target.target, tap.target);
      assertTrue("No changed video picture observed", firstChanged >= 0);
      assertTrue("Reference target was not stably confirmed", confirmedAt >= 0);
      assertTrue(
          "Target recovery exceeded the observation bound",
          stableStart - tap.deliveredUp <= recoveryLimit);
      State paused = state(target.target);
      assertTrue("Paused intent must remain settled", paused.ready);
      operation.put("passed", true);
    } finally {
      operation
          .put("actualTargetMs", tap.target)
          .put("deliveredDownCount", tap.downs)
          .put("deliveredUpCount", tap.ups)
          .put("captures", captures)
          .put("maxCopyMs", maxCopyMs)
          .put("maxSampleGapMs", maxSampleGapMs)
          .put("eventDownMs", delta(tap.eventDown, tap.eventDown))
          .put("eventUpMs", delta(tap.eventUp, tap.eventDown))
          .put("callbackReceivedDownMs", delta(tap.receivedDown, tap.eventDown))
          .put("callbackHandledDownMs", delta(tap.deliveredDown, tap.eventDown))
          .put("callbackReceivedUpMs", delta(tap.receivedUp, tap.eventDown))
          .put("callbackHandledUpMs", delta(tap.deliveredUp, tap.eventDown))
          .put("downDeliveryLagMs", delta(tap.receivedDown, tap.eventDown))
          .put("upDeliveryLagMs", delta(tap.receivedUp, tap.eventUp))
          .put("downDispatchMs", delta(tap.deliveredDown, tap.receivedDown))
          .put("upDispatchMs", delta(tap.deliveredUp, tap.receivedUp))
          .put("deliveredPressMs", delta(tap.deliveredUp, tap.deliveredDown))
          .put("firstVisibleChangeFromDownMs", delta(firstChanged, tap.deliveredDown))
          .put("firstVisibleChangeFromUpMs", delta(firstChanged, tap.deliveredUp))
          .put("firstReferenceMatchFromUpMs", delta(firstMatch, tap.deliveredUp))
          .put("stableReferenceStartFromUpMs", delta(stableStart, tap.deliveredUp))
          .put("targetConfirmationFromUpMs", delta(confirmedAt, tap.deliveredUp))
          .put("firstVisibleChangeFromInjectedDownMs", delta(firstChanged, tap.eventDown))
          .put("firstVisibleChangeFromInjectedUpMs", delta(firstChanged, tap.eventUp))
          .put("firstVisibleChangeFromDispatchDownMs", delta(firstChanged, tap.receivedDown))
          .put("firstVisibleChangeFromDispatchUpMs", delta(firstChanged, tap.receivedUp))
          .put("firstReferenceMatchFromInjectedUpMs", delta(firstMatch, tap.eventUp))
          .put("firstReferenceMatchFromDispatchUpMs", delta(firstMatch, tap.receivedUp))
          .put("stableReferenceStartFromInjectedUpMs", delta(stableStart, tap.eventUp))
          .put("stableReferenceStartFromDispatchUpMs", delta(stableStart, tap.receivedUp))
          .put("targetConfirmationFromInjectedUpMs", delta(confirmedAt, tap.eventUp))
          .put("targetConfirmationFromDispatchUpMs", delta(confirmedAt, tap.receivedUp));
    }
  }

  private Bitmap settledReference(long target) throws Exception {
    onMain(
        () -> {
          page.seekTo(target);
          return null;
        });
    long started = SystemClock.uptimeMillis();
    long deadline = Math.min(phaseDeadline, started + 15000), quietSince = -1;
    Bitmap previous = null;
    try {
      while (SystemClock.uptimeMillis() < deadline) {
        if (SystemClock.uptimeMillis() - started < REFERENCE_DWELL_MS) {
          SystemClock.sleep(40);
          continue;
        }
        State before = state(target);
        Bitmap picture = capture();
        try {
          State after = state(target);
          boolean valid =
              before.ready
                  && after.ready
                  && before.sequence == after.sequence
                  && barcodeNear(picture, target);
          long now = SystemClock.uptimeMillis();
          if (valid && previous != null && distance(previous, picture) < .15) {
            if (quietSince < 0) quietSince = now;
          } else quietSince = -1;
          if (previous != null) previous.recycle();
          previous = picture;
          picture = null;
          if (quietSince >= 0 && now - quietSince >= REFERENCE_QUIET_MS) {
            Bitmap result = previous;
            previous = null;
            return result;
          }
        } finally {
          if (picture != null) picture.recycle();
        }
        SystemClock.sleep(60);
      }
      throw new AssertionError("Isolated reference failed dwell and quiet requirements");
    } finally {
      if (previous != null) previous.recycle();
    }
  }

  private State state(long target) throws Exception {
    return onMain(
        () -> {
          assertSame("Page changed", page, read(activity, "currentPage"));
          assertSame(
              "Decoder changed; reference invalid", player, page.exo != null ? page.exo : page.vlc);
          assertSame("Surface changed; reference invalid", surface, currentSurface());
          assertEquals(
              "Frame generation changed", generation, (long) read(page, "engineFrameGeneration"));
          assertFalse("User pause intent changed", (boolean) read(activity, "shouldPlay"));
          assertFalse("Page pause intent changed", page.playRequested);
          boolean ready =
              !page.failed
                  && !page.buffering
                  && page.firstFrameReady
                  && !(boolean) read(page, "scrubbing")
                  && Math.abs((long) read(page, "explicitSeekTarget") - target) <= 1;
          if (page.exo != null)
            return new State(
                page.lastRenderedFrameSequence,
                ready
                    && page.exo.getPlaybackState() == Player.STATE_READY
                    && !page.exo.getPlayWhenReady()
                    && page.lastRenderedPositionUs >= 0
                    && Math.abs(page.lastRenderedPositionUs - target * 1000) <= 200000
                    && System.nanoTime() >= (long) read(page, "lastFrameReleaseTimeNs"));
          NativeSeekQueue queue = (NativeSeekQueue) read(page, "nativeSeekQueue");
          // Queue/pause state is a fence only. No native clock is used as decoded-frame evidence.
          return new State(
              -1, ready && page.vlc != null && !page.vlc.isPlaying() && !queue.hasWork());
        });
  }

  private Bitmap capture() throws Exception {
    if (surface instanceof TextureView) {
      Bitmap result = onMain(() -> ((TextureView) surface).getBitmap(bitmapWidth, bitmapHeight));
      assertNotNull("Texture has no video picture", result);
      return result;
    }
    assertTrue("Native video surface required", surface instanceof SurfaceView);
    Bitmap frame = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
    CountDownLatch copied = new CountDownLatch(1);
    AtomicInteger code = new AtomicInteger(-1);
    AtomicBoolean abandoned = new AtomicBoolean(), recycled = new AtomicBoolean();
    Runnable recycle =
        () -> {
          if (recycled.compareAndSet(false, true)) frame.recycle();
        };
    main.post(
        () -> {
          try {
            PixelCopy.request(
                (SurfaceView) surface,
                frame,
                status -> {
                  code.set(status);
                  copied.countDown();
                  if (abandoned.get()) recycle.run();
                },
                copyHandler);
          } catch (RuntimeException failure) {
            code.set(-2);
            copied.countDown();
            if (abandoned.get()) recycle.run();
          }
        });
    if (!copied.await(700, TimeUnit.MILLISECONDS)) {
      abandoned.set(true);
      if (copied.getCount() == 0) recycle.run();
      throw new java.util.concurrent.TimeoutException("Surface capture timed out");
    }
    if (code.get() != PixelCopy.SUCCESS) {
      recycle.run();
      throw new AssertionError("Surface capture did not receive a picture");
    }
    return frame;
  }

  private boolean barcodeNear(Bitmap frame, long target) {
    if (barcodeFps == 0) return true;
    OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(frame);
    return code != null
        && code.frame >= 0
        && code.frame < 480
        && Math.abs(code.frame * 1000L / barcodeFps - target) <= 150;
  }

  private Uri explicitFixture(Context context, String supplied) {
    Uri uri = Uri.parse(supplied);
    assertEquals("Only local indexed media is allowed", "content", uri.getScheme());
    assertEquals("Only MediaStore media is allowed", "media", uri.getAuthority());
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {"_id", "duration", "_display_name", "_size", "width", "height"},
                null,
                null,
                null)) {
      assertNotNull("Explicit fixture is unavailable", c);
      assertEquals("Explicit selection must resolve once", 1, c.getCount());
      assertTrue(c.moveToFirst());
      if (barcodeFps != 0) {
        assertTrue(
            "Barcode requires the generated fixture",
            c.getString(2).matches("mm_seek_stress_[A-Za-z0-9_-]+\\.mp4"));
        assertEquals("Barcode fixture duration", 8000, c.getLong(1), 150);
      }
      fixtureItem =
          new VideoItem(
              "isolated-fixture",
              "fixture" + extension(c.getString(2)),
              uri.toString(),
              "",
              c.getLong(3),
              c.getLong(1),
              0,
              0,
              c.getInt(4),
              c.getInt(5));
      return uri;
    }
  }

  @SuppressWarnings("unchecked")
  private void launch(Context context, Uri uri, String token) throws Exception {
    ArrayList<VideoItem> queue = new ArrayList<>();
    assertEquals(uri.toString(), fixtureItem.uri);
    queue.add(fixtureItem);
    Field memory = QueueStore.class.getDeclaredField("MEMORY");
    memory.setAccessible(true);
    ((Map<String, ArrayList<VideoItem>>) memory.get(null)).put(token, queue);
    context.startActivity(
        new Intent(context, PlayerActivity.class)
            .putExtra("queue", token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  private void awaitReady(String token) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (onMain(
          () -> {
            for (android.app.Activity candidate :
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
              if (candidate instanceof PlayerActivity) activity = (PlayerActivity) candidate;
            if (activity == null || !token.equals(read(activity, "queueToken"))) return false;
            page = (VideoPage) read(activity, "currentPage");
            if (page == null || !page.firstFrameReady || page.buffering || page.failed)
              return false;
            surface = currentSurface();
            if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0)
              return false;
            duration = page.duration();
            if (duration < 3000) return false;
            player = page.exo != null ? page.exo : page.vlc;
            generation = (long) read(page, "engineFrameGeneration");
            float scale = Math.min(1f, 384f / Math.max(surface.getWidth(), surface.getHeight()));
            bitmapWidth = Math.max(1, Math.round(surface.getWidth() * scale));
            bitmapHeight = Math.max(1, Math.round(surface.getHeight() * scale));
            return true;
          })) return;
      SystemClock.sleep(80);
    }
    fail("Explicit fixture did not produce a ready video surface");
  }

  private View currentSurface() {
    return page.exo != null
        ? page.exoView.getVideoSurfaceView()
        : NativeFrameProbe.findVideoSurface(page.vlcView);
  }

  private void showControls() throws Exception {
    onMain(
        () -> {
          for (String name : new String[] {"showChrome", "updateProgress"}) {
            Method method = PlayerActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(activity);
          }
          assertTrue("Seek control must be visible", ((SeekBar) read(activity, "seek")).isShown());
          return null;
        });
  }

  private Tap synchronousTap(int x) throws Exception {
    Tap tap = new Tap(x, SystemClock.uptimeMillis());
    activeTap = tap;
    inject(tap, MotionEvent.ACTION_DOWN, true);
    SystemClock.sleep(20);
    inject(tap, MotionEvent.ACTION_UP, true);
    onMain(() -> null);
    assertEquals(1, tap.downs);
    assertEquals(1, tap.ups);
    assertTrue("Physical target must be inside the item", tap.target > 0 && tap.target < duration);
    return tap;
  }

  private void installDeliveryProbe() throws Exception {
    onMain(
        () -> {
          originalCallback = activity.getWindow().getCallback();
          activity
              .getWindow()
              .setCallback(
                  (Window.Callback)
                      Proxy.newProxyInstance(
                          getClass().getClassLoader(),
                          new Class<?>[] {Window.Callback.class},
                          (proxy, method, arguments) -> {
                            MotionEvent event =
                                method.getName().equals("dispatchTouchEvent")
                                        && arguments != null
                                        && arguments.length == 1
                                        && arguments[0] instanceof MotionEvent
                                    ? (MotionEvent) arguments[0]
                                    : null;
                            Tap tap = activeTap;
                            boolean tracked =
                                event != null && tap != null && event.getDownTime() == tap.downTime;
                            long received = SystemClock.uptimeMillis();
                            if (tracked) {
                              if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                                tap.receivedDown = received;
                              else if (event.getActionMasked() == MotionEvent.ACTION_UP)
                                tap.receivedUp = received;
                            }
                            try {
                              return method.invoke(originalCallback, arguments);
                            } catch (InvocationTargetException error) {
                              throw error.getCause();
                            } finally {
                              long handled = SystemClock.uptimeMillis();
                              if (tracked) {
                                tap.target = (long) read(activity, "gestureTarget");
                                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                  tap.downs++;
                                  tap.deliveredDown = handled;
                                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                  tap.ups++;
                                  tap.deliveredUp = handled;
                                }
                              }
                            }
                          }));
          return null;
        });
  }

  private void inject(Tap tap, int action, boolean synchronous) {
    MotionEvent event =
        MotionEvent.obtain(tap.downTime, SystemClock.uptimeMillis(), action, tap.x, y, 0);
    if (action == MotionEvent.ACTION_DOWN) tap.eventDown = event.getEventTime();
    else if (action == MotionEvent.ACTION_UP) tap.eventUp = event.getEventTime();
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("Physical input rejected", automation.injectInputEvent(event, synchronous));
    } finally {
      event.recycle();
    }
  }

  private <T> T onMain(Callable<T> operation) throws Exception {
    FutureTask<T> task = new FutureTask<>(operation);
    main.post(task);
    try {
      return task.get(1500, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      task.cancel(false);
      throw error;
    } catch (java.util.concurrent.ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    }
  }

  private static Object read(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object optional(Object owner, String name) throws Exception {
    try {
      return read(owner, name);
    } catch (NoSuchFieldException absent) {
      return JSONObject.NULL;
    }
  }

  private static int integer(Bundle args, String name, int fallback) {
    return Integer.parseInt(args.getString(name, Integer.toString(fallback)));
  }

  private static String extension(String name) {
    if (name == null) return ".mp4";
    int dot = name.lastIndexOf('.');
    String suffix = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    return suffix.matches("\\.(mp4|mkv|avi|mov|webm|m4v|3gp|flv|wmv|mpg|mpeg|ts|m2ts)")
        ? suffix
        : ".mp4";
  }

  private static Object delta(long observed, long origin) {
    return observed > 0 && origin > 0 ? observed - origin : JSONObject.NULL;
  }

  private static double distance(Bitmap a, Bitmap b) {
    return NativeFrameProbe.averageRgbDistance(a, b);
  }

  private static void waitUntil(long time) {
    long delay = time - SystemClock.uptimeMillis();
    if (delay > 0) SystemClock.sleep(delay);
  }

  private static String category(Throwable error) {
    if (error instanceof AssertionError) return "assertion";
    if (error instanceof java.util.concurrent.TimeoutException) return "timeout";
    if (error instanceof SecurityException) return "permission";
    return "execution";
  }

  private void writeResult(Context context, String run, JSONObject result) throws Exception {
    File external = context.getExternalFilesDir(null);
    assertNotNull("App external files directory unavailable", external);
    File directory = new File(external, "test-only/isolated-seek/" + run);
    assertTrue("Test output directory unavailable", directory.isDirectory() || directory.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(directory, "results.json"))) {
      out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    Bundle status = new Bundle();
    status.putString("isolatedSeekCompleted", Boolean.toString(result.optBoolean("completed")));
    status.putInt("isolatedSeekOperations", result.getJSONArray("operations").length());
    instrumentation.sendStatus(2, status);
  }

  private static final class Reference {
    final int x;
    final long target;
    final Bitmap picture;

    Reference(int x, long target, Bitmap picture) {
      this.x = x;
      this.target = target;
      this.picture = picture;
    }
  }

  private static final class State {
    final long sequence;
    final boolean ready;

    State(long sequence, boolean ready) {
      this.sequence = sequence;
      this.ready = ready;
    }
  }

  private static final class Tap {
    final int x;
    final long downTime;
    volatile long eventDown, eventUp, receivedDown, receivedUp;
    volatile long deliveredDown, deliveredUp, target = -1;
    volatile int downs, ups;

    Tap(int x, long downTime) {
      this.x = x;
      this.downTime = downTime;
    }
  }
}
