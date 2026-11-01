package io.mediamaster.app.player;

import static org.junit.Assert.*;

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
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.SeekBar;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** One physical forward gesture; picture-change latency is separate from final target accuracy. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class ContinuousLongGopScrubTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean stop = new AtomicBoolean(), fingerDown = new AtomicBoolean();
  private final JSONArray delivered = new JSONArray();
  private PlayerActivity activity;
  private VideoPage page;
  private View surface;
  private Object player;
  private Window.Callback originalCallback;
  private long frameGeneration, duration;
  private volatile long downTime, deliveredUp, deliveredTarget;
  private volatile int lastX;
  private int trackLeft, trackWidth, y, barcodeFps;

  @Test
  public void measureContinuousForwardScrub() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    Context context = instrumentation.getTargetContext();
    int engine = integer(args, "benchEngine", 1), decoder = integer(args, "benchNativeDecoder", 0);
    int wallMs = integer(args, "benchScrubWallMs", 2000);
    int recoveryLimit = integer(args, "benchRecoveryLimitMs", 1500);
    int freezeLimit = integer(args, "benchFreezeLimitMs", 1500);
    barcodeFps = integer(args, "benchBarcodeFps", 0);
    assertTrue(engine >= 0 && engine <= 2 && decoder >= 0 && decoder <= 2);
    assertTrue(wallMs >= 1000 && wallMs <= 4000);
    assertTrue(recoveryLimit >= 250 && recoveryLimit <= 5000);
    assertTrue(freezeLimit >= 100 && freezeLimit <= 5000);
    assertTrue(barcodeFps == 0 || barcodeFps == 60);
    SharedPreferences preferences = context.getSharedPreferences("player", 0);
    String[] keys = {"engine_choice", "native_decoder_mode", "playback_mode"};
    boolean[] present = new boolean[keys.length];
    int[] saved = new int[keys.length];
    for (int i = 0; i < keys.length; i++) {
      present[i] = preferences.contains(keys[i]);
      saved[i] = preferences.getInt(keys[i], 0);
    }
    JSONArray samples = new JSONArray();
    JSONObject result =
        new JSONObject()
            .put("protocol", "continuous-forward-v1")
            .put("engineChoice", engine)
            .put("nativeDecoderChoice", decoder)
            .put("wallMs", wallMs)
            .put("recoveryLimitMs", recoveryLimit)
            .put("freezeLimitMs", freezeLimit)
            .put("sampleLimitHz", 25)
            .put("barcodeFps", barcodeFps)
            .put(
                "sampleColumns",
                "endMs,copyMs,lastChangedDistance,referenceDistance,ptsMs,uiTargetMs,ready")
            .put("samples", samples)
            .put("deliveredColumns", "action,eventMs,handledMs,x,targetMs")
            .put("delivered", delivered)
            .put("passed", false);
    Thread sender = null;
    Bitmap reference = null, origin = null, previous = null;
    AtomicReference<Throwable> injectionFailure = new AtomicReference<>();
    ScopedPlaybackHistory history = null;
    try {
      history = onMain(() -> ScopedPlaybackHistory.open(context));
      result.put("engineHistoryIsolated", true);
      automation.grantRuntimePermission(
          context.getPackageName(),
          Build.VERSION.SDK_INT >= 33
              ? Manifest.permission.READ_MEDIA_VIDEO
              : Manifest.permission.READ_EXTERNAL_STORAGE);
      Uri fixture = explicitFixture(context, args);
      assertTrue(
          preferences
              .edit()
              .putInt(keys[0], engine)
              .putInt(keys[1], decoder)
              .putInt(keys[2], PlaybackOrder.SINGLE)
              .commit());
      context.startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setClass(context, PlayerActivity.class)
              .setDataAndType(fixture, "video/mp4")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
      awaitReady(fixture);
      onMain(
          () -> {
            if (engine == 1 && decoder == 0)
              assertNotNull("Explicit system benchmark requires Media3", page.exo);
            else if (engine == 2 || decoder == NativeDecoderPolicy.SOFTWARE)
              assertNotNull("Explicit native/software benchmark requires LibVLC", page.vlc);
            else if (decoder == NativeDecoderPolicy.HARDWARE && page.exo != null) {
              assertEquals(Boolean.TRUE, optional(page, "exoHardwareConfigured"));
              assertEquals(Boolean.FALSE, optional(page, "exoSoftwareConfigured"));
            }
            result
                .put("isMedia3", page.exo != null)
                .put("nativeSoftware", page.vlc != null && (boolean) read(page, "nativeSoftware"))
                .put("media3HardwareConfigured", optional(page, "exoHardwareConfigured"))
                .put("media3SoftwareConfigured", optional(page, "exoSoftwareConfigured"))
                .put("media3AudioReuseAdmissions", optional(page, "exoAudioReuseAdmissions"))
                .put("media3AudioCodecReuses", optional(page, "exoAudioCodecReuses"))
                .put("media3AudioKeyframeResets", optional(page, "exoAudioKeyframeResets"));
            Field play = PlayerActivity.class.getDeclaredField("shouldPlay");
            play.setAccessible(true);
            play.setBoolean(activity, false);
            page.setPlayback(true, false, true);
            Method chrome = PlayerActivity.class.getDeclaredMethod("showChrome");
            chrome.setAccessible(true);
            chrome.invoke(activity);
            return null;
          });
      SystemClock.sleep(250);
      onMain(
          () -> {
            SeekBar seek = (SeekBar) read(activity, "seek");
            assertTrue("Seek control must be visible", seek.isShown());
            int[] xy = new int[2];
            seek.getLocationOnScreen(xy);
            trackLeft = xy[0] + seek.getPaddingLeft();
            trackWidth = seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight();
            y = xy[1] + seek.getHeight() / 2;
            return null;
          });
      long start =
          integer(args, "benchScrubStartMs", barcodeFps != 0 ? 1000 : (int) (duration * .12));
      long span = integer(args, "benchScrubSpanMs", barcodeFps != 0 ? 5500 : 20000);
      assertTrue(
          "Explicit scrub interval must fit the fixture",
          start >= 0 && span > 0 && start + span < duration - 100);
      int fromX = xAt(start), toX = xAt(start + span);
      assertTrue("The chosen interval must span at least eight physical pixels", toX - fromX >= 8);
      long startTarget = targetAt(fromX);
      origin = settledReference(startTarget);
      int originProgress = showReadySeek();
      // Android preserves the offset between the finger and thumb center. A mathematical x/width
      // estimate is therefore not the actual end target. Calibrate the same physical gesture from
      // the same paused thumb position before taking the isolated accurate reference.
      long finalTarget = calibrateTarget(fromX, toX);
      result
          .put("durationMs", duration)
          .put("startTargetMs", startTarget)
          .put("finalTargetMs", finalTarget)
          .put("isMedia3", page.exo != null)
          .put(
              "finalOracle",
              barcodeFps != 0
                  ? "barcode150ms"
                  : page.exo != null
                      ? "metadata200ms-and-surface-reference"
                      : "same-surface-reference-not-independent-PTS");
      reference = settledReference(finalTarget);
      origin.recycle();
      origin = null;
      origin = settledReference(startTarget);
      assertTrue(
          "Interval endpoints must be visually distinguishable",
          NativeFrameProbe.averageRgbDistance(origin, reference) > 3.5);
      assertEquals(
          "Calibration requires the identical starting thumb position",
          originProgress,
          showReadySeek());
      SystemClock.sleep(150);
      installDeliveryProbe();
      previous = origin;
      origin = null;
      downTime = SystemClock.uptimeMillis() + 100;
      final int moveCount = wallMs * 30 / 1000;
      sender =
          new Thread(
              () -> {
                try {
                  waitUntil(downTime);
                  fingerDown.set(true);
                  inject(MotionEvent.ACTION_DOWN, fromX);
                  for (int i = 1; i <= moveCount && !stop.get(); i++) {
                    waitUntil(downTime + (long) wallMs * i / moveCount);
                    inject(
                        MotionEvent.ACTION_MOVE,
                        fromX + Math.round((toX - fromX) * i / (float) moveCount));
                  }
                  if (!stop.get()) {
                    inject(MotionEvent.ACTION_UP, toX);
                    fingerDown.set(false);
                  }
                } catch (Throwable error) {
                  injectionFailure.set(error);
                } finally {
                  if (fingerDown.getAndSet(false)) {
                    try {
                      inject(MotionEvent.ACTION_CANCEL, lastX);
                    } catch (Throwable error) {
                      injectionFailure.compareAndSet(null, error);
                    }
                  }
                }
              },
              "continuous-seek-input");
      sender.start();
      waitUntil(downTime);
      long firstChanged = -1, lastChanged = downTime, longestUnchanged = 0, finalRecovery = -1;
      int changes = 0, stableFinal = 0;
      long deadline = downTime + wallMs + recoveryLimit + 1200;
      while (SystemClock.uptimeMillis() < deadline) {
        long began = SystemClock.uptimeMillis();
        State before = state(finalTarget);
        Bitmap frame = NativeFrameProbe.captureSurface(instrumentation, surface);
        try {
          long ended = SystemClock.uptimeMillis();
          State after = state(finalTarget);
          double changed = NativeFrameProbe.averageRgbDistance(previous, frame);
          double referenceDistance = NativeFrameProbe.averageRgbDistance(reference, frame);
          if (changed > .35) {
            if (firstChanged < 0) firstChanged = ended - downTime;
            // A copy spanning UP cannot establish that the changed picture was visible while
            // the finger was still down. Keep it for final recovery, not in-gesture evidence.
            if (deliveredUp == 0 || ended <= deliveredUp) {
              longestUnchanged = Math.max(longestUnchanged, ended - lastChanged);
              lastChanged = ended;
              changes++;
            }
          }
          boolean finalAccurate =
              deliveredUp > 0
                  && began >= deliveredUp
                  && deliveredTarget == finalTarget
                  && before.settled
                  && after.settled
                  && before.sequence == after.sequence
                  && referenceDistance < 2
                  && barcodeNear(frame, finalTarget);
          stableFinal = finalAccurate ? stableFinal + 1 : 0;
          if (finalAccurate && finalRecovery < 0) finalRecovery = ended - deliveredUp;
          if (!finalAccurate) finalRecovery = -1;
          samples.put(
              new JSONArray()
                  .put(ended - downTime)
                  .put(ended - began)
                  .put(round(changed))
                  .put(round(referenceDistance))
                  .put(after.ptsMs)
                  .put(deliveredTarget)
                  .put(after.settled ? 1 : 0));
          if (changed > .35) {
            previous.recycle();
            previous = frame;
            frame = null;
          }
          if (stableFinal >= 3) break;
        } finally {
          if (frame != null) frame.recycle();
        }
        if (injectionFailure.get() != null) throw new AssertionError("Input injection failed");
        waitUntil(began + 40);
      }
      sender.join(2000);
      assertNull("Input injection failed", injectionFailure.get());
      assertFalse("Input sender did not finish", sender.isAlive());
      assertTrue("The final UP must reach the application", deliveredUp > 0);
      assertEquals(
          "Measured endpoint differs from calibrated physical target",
          finalTarget,
          deliveredTarget);
      longestUnchanged = Math.max(longestUnchanged, deliveredUp - lastChanged);
      result
          .put("firstChangedPictureUpperMs", firstChanged)
          .put("changedPicturesDuringGesture", changes)
          .put("longestUnchangedDuringGestureUpperMs", longestUnchanged)
          .put("finalAccurateRecoveryUpperMs", finalRecovery)
          .put("stableFinalSamples", stableFinal);
      checkDelivery(fromX, toX, wallMs);
      assertTrue("No actual picture change during the gesture", changes > 0);
      assertTrue("Visible picture exceeded the freeze bound", longestUnchanged <= freezeLimit);
      assertTrue("Final precise frame did not settle", stableFinal >= 3);
      assertTrue("Final precise frame exceeded the recovery bound", finalRecovery <= recoveryLimit);
      result.put("passed", true);
    } catch (Throwable error) {
      result.put("errorType", error.getClass().getSimpleName());
      if (error instanceof AssertionError) result.put("assertion", error.getMessage());
      if (error instanceof Exception) throw (Exception) error;
      if (error instanceof Error) throw (Error) error;
      throw new RuntimeException(error);
    } finally {
      try {
        stop.set(true);
        if (sender != null) sender.join(2500);
        if (fingerDown.getAndSet(false)) inject(MotionEvent.ACTION_CANCEL, lastX);
      } finally {
        try {
          if (page != null)
            onMain(
                () -> {
                  result
                      .put(
                          "finalMedia3AudioReuseAdmissions",
                          optional(page, "exoAudioReuseAdmissions"))
                      .put("finalMedia3AudioCodecReuses", optional(page, "exoAudioCodecReuses"))
                      .put(
                          "finalMedia3AudioKeyframeResets",
                          optional(page, "exoAudioKeyframeResets"));
                  return null;
                });
        } catch (Exception unavailable) {
          result.put("audioReuseDiagnosticErrorType", unavailable.getClass().getSimpleName());
        }
        for (Bitmap bitmap : new Bitmap[] {origin, reference, previous})
          if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
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
          try {
            if (history != null) {
              history.closeAfterActivityDestroyed(activity);
              result.put("engineHistoryRestored", true);
            }
          } catch (Exception cleanupFailure) {
            result
                .put("passed", false)
                .put("historyCleanupErrorType", cleanupFailure.getClass().getSimpleName());
            throw cleanupFailure;
          } finally {
            SharedPreferences.Editor restore = preferences.edit();
            for (int i = 0; i < keys.length; i++) {
              if (present[i]) restore.putInt(keys[i], saved[i]);
              else restore.remove(keys[i]);
            }
            result.put("preferencesRestored", restore.commit());
            Bundle status = new Bundle();
            status.putString("continuousScrub", result.toString());
            instrumentation.sendStatus(2, status);
          }
        }
      }
    }
  }

  private Uri explicitFixture(Context context, Bundle args) {
    String explicit = args.getString("benchUri", ""), name = args.getString("benchFile", "");
    assertTrue(
        "Supply exactly one indexed file name or MediaStore URI",
        explicit.isEmpty() != name.isEmpty());
    Uri uri =
        explicit.isEmpty() ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : Uri.parse(explicit);
    assertEquals("Only local indexed media is allowed", "content", uri.getScheme());
    assertEquals("Only MediaStore media is allowed", "media", uri.getAuthority());
    String path = args.getString("benchRelativePath", "Movies/MediaMasterStress_20261101/");
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {"_id", "duration", "_display_name"},
                explicit.isEmpty() ? "_display_name=? AND relative_path=?" : null,
                explicit.isEmpty() ? new String[] {name, path} : null,
                null)) {
      assertNotNull("Requested fixture is not indexed", c);
      assertEquals("Fixture selection must resolve exactly once", 1, c.getCount());
      assertTrue(c.moveToFirst());
      if (barcodeFps != 0) {
        assertTrue(
            "Barcode oracle requires the generated fixture",
            c.getString(2).matches("mm_seek_stress_[A-Za-z0-9_-]+\\.mp4"));
        assertEquals("Barcode fixture must contain 480 frames at 60 fps", 8000, c.getLong(1), 150);
      }
      return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getString(0));
    }
  }

  private void awaitReady(Uri uri) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (onMain(
          () -> {
            for (android.app.Activity candidate :
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
              if (candidate instanceof PlayerActivity) activity = (PlayerActivity) candidate;
            if (activity == null) return false;
            page = (VideoPage) read(activity, "currentPage");
            if (page == null
                || page.item == null
                || !Uri.parse(page.item.uri).equals(uri)
                || !page.firstFrameReady
                || page.buffering) return false;
            surface = currentSurface();
            if (surface == null) return false;
            player = page.exo != null ? page.exo : page.vlc;
            frameGeneration = (long) read(page, "engineFrameGeneration");
            duration = page.duration();
            return duration > 1000;
          })) return;
      SystemClock.sleep(100);
    }
    fail("Requested fixture did not produce a ready surface");
  }

  private View currentSurface() {
    return page.exo != null
        ? page.exoView.getVideoSurfaceView()
        : NativeFrameProbe.findVideoSurface(page.vlcView);
  }

  private State state(long target) throws Exception {
    return onMain(
        () -> {
          assertSame("Page was replaced", page, read(activity, "currentPage"));
          assertSame(
              "Decoder was replaced; reference is invalid",
              player,
              page.exo != null ? page.exo : page.vlc);
          assertSame("Surface was replaced; reference is invalid", surface, currentSurface());
          assertEquals(
              "Frame generation changed",
              frameGeneration,
              (long) read(page, "engineFrameGeneration"));
          boolean idle =
              !page.failed
                  && !page.buffering
                  && !page.playRequested
                  && !(boolean) read(page, "scrubbing");
          if (page.exo != null) {
            long pts = page.lastRenderedPositionUs;
            return new State(
                pts < 0 ? -1 : pts / 1000,
                page.lastRenderedFrameSequence,
                idle
                    && page.exo.getPlaybackState() == Player.STATE_READY
                    && !page.exo.getPlayWhenReady()
                    && pts >= 0
                    && Math.abs(pts - target * 1000) <= 200_000
                    && System.nanoTime() >= (long) read(page, "lastFrameReleaseTimeNs"));
          }
          NativeSeekQueue queue = (NativeSeekQueue) read(page, "nativeSeekQueue");
          return new State(
              -1,
              -1,
              idle
                  && !page.vlc.isPlaying()
                  && !queue.hasWork()
                  && Math.abs(page.vlc.getTime() - target) <= 200);
        });
  }

  private Bitmap settledReference(long target) throws Exception {
    onMain(
        () -> {
          page.seekTo(target);
          return null;
        });
    Bitmap previous = null;
    int stable = 0;
    try {
      long deadline = SystemClock.uptimeMillis() + 15000;
      while (SystemClock.uptimeMillis() < deadline) {
        if (!state(target).settled) {
          stable = 0;
          SystemClock.sleep(40);
          continue;
        }
        Bitmap frame = NativeFrameProbe.captureSurface(instrumentation, surface);
        boolean valid = state(target).settled && barcodeNear(frame, target);
        stable =
            valid && previous != null && NativeFrameProbe.averageRgbDistance(previous, frame) < .15
                ? stable + 1
                : 0;
        if (previous != null) previous.recycle();
        previous = frame;
        if (stable >= 3) {
          Bitmap result = previous;
          previous = null;
          return result;
        }
        SystemClock.sleep(80);
      }
      throw new AssertionError("Isolated exact reference did not settle");
    } finally {
      if (previous != null) previous.recycle();
    }
  }

  private boolean barcodeNear(Bitmap frame, long target) {
    if (barcodeFps == 0) return true;
    OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(frame);
    return code != null
        && code.frame >= 0
        && code.frame < 480
        && Math.abs(code.frame * 1000L / barcodeFps - target) <= 150;
  }

  private int showReadySeek() throws Exception {
    return onMain(
        () -> {
          Method chrome = PlayerActivity.class.getDeclaredMethod("showChrome");
          chrome.setAccessible(true);
          chrome.invoke(activity);
          Method progress = PlayerActivity.class.getDeclaredMethod("updateProgress");
          progress.setAccessible(true);
          progress.invoke(activity);
          SeekBar seek = (SeekBar) read(activity, "seek");
          assertTrue("Seek control must be visible", seek.isShown());
          return seek.getProgress();
        });
  }

  private long calibrateTarget(int from, int to) throws Exception {
    downTime = SystemClock.uptimeMillis();
    fingerDown.set(true);
    try {
      inject(MotionEvent.ACTION_DOWN, from, true);
      SystemClock.sleep(35);
      inject(MotionEvent.ACTION_MOVE, to, true);
      SystemClock.sleep(35);
      inject(MotionEvent.ACTION_UP, to, true);
      fingerDown.set(false);
      return onMain(() -> (long) read(activity, "gestureTarget"));
    } finally {
      if (fingerDown.getAndSet(false)) inject(MotionEvent.ACTION_CANCEL, lastX, true);
      downTime = 0;
    }
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
                            try {
                              return method.invoke(originalCallback, arguments);
                            } catch (InvocationTargetException e) {
                              throw e.getCause();
                            } finally {
                              if (event != null
                                  && downTime > 0
                                  && event.getDownTime() == downTime) {
                                deliveredTarget = (long) read(activity, "gestureTarget");
                                long handled = SystemClock.uptimeMillis();
                                synchronized (delivered) {
                                  assertTrue(
                                      "Unexpectedly large input trace", delivered.length() < 256);
                                  delivered.put(
                                      new JSONArray()
                                          .put(event.getActionMasked())
                                          .put(event.getEventTime() - downTime)
                                          .put(handled - downTime)
                                          .put(Math.round(event.getRawX()))
                                          .put(deliveredTarget));
                                }
                                if (event.getActionMasked() == MotionEvent.ACTION_UP)
                                  deliveredUp = handled;
                              }
                            }
                          }));
          return null;
        });
  }

  private void checkDelivery(int from, int to, int wall) throws Exception {
    int downs = 0, ups = 0, moves = 0, previous = from;
    synchronized (delivered) {
      for (int i = 0; i < delivered.length(); i++) {
        JSONArray event = delivered.getJSONArray(i);
        int action = event.getInt(0), x = event.getInt(3);
        assertTrue("Gesture reversed direction", x >= previous);
        previous = x;
        if (action == MotionEvent.ACTION_DOWN) downs++;
        else if (action == MotionEvent.ACTION_MOVE) moves++;
        else if (action == MotionEvent.ACTION_UP) ups++;
        else fail("Continuous gesture was canceled or interrupted");
      }
    }
    assertEquals("One physical DOWN required", 1, downs);
    assertEquals("One physical UP required", 1, ups);
    assertTrue("Application did not receive continuous MOVE events", moves >= 10);
    assertEquals(to, previous);
    assertTrue(
        "Gesture duration differs from scheduled duration",
        Math.abs(deliveredUp - downTime - wall) < 500);
  }

  private void inject(int action, int x) {
    inject(action, x, false);
  }

  private void inject(int action, int x, boolean synchronous) {
    lastX = x;
    MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("Input injection rejected", automation.injectInputEvent(event, synchronous));
    } finally {
      event.recycle();
    }
  }

  private int xAt(long target) {
    return trackLeft + Math.round(trackWidth * target / (float) duration);
  }

  private long targetAt(int x) {
    return duration * Math.round((x - trackLeft) * 10000f / trackWidth) / 10000;
  }

  private static Object optional(Object owner, String name) throws Exception {
    try {
      return read(owner, name);
    } catch (NoSuchFieldException absent) {
      return null;
    }
  }

  private static int integer(Bundle args, String name, int fallback) {
    return Integer.parseInt(args.getString(name, Integer.toString(fallback)));
  }

  private static double round(double value) {
    return Math.round(value * 100d) / 100d;
  }

  private static void waitUntil(long time) {
    long delay = time - SystemClock.uptimeMillis();
    if (delay > 0) SystemClock.sleep(delay);
  }

  private static Object read(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }

  private <T> T onMain(Callable<T> operation) throws Exception {
    FutureTask<T> task = new FutureTask<>(operation);
    main.post(task);
    try {
      return task.get(2000, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      task.cancel(false);
      throw e;
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    }
  }

  private static final class State {
    final long ptsMs, sequence;
    final boolean settled;

    State(long ptsMs, long sequence, boolean settled) {
      this.ptsMs = ptsMs;
      this.sequence = sequence;
      this.settled = settled;
    }
  }
}
