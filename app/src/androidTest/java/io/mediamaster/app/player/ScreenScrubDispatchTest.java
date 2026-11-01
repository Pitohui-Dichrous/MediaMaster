package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opt-in physical screen gestures; no fixture identities, screenshots, or media copies are saved.
 */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class ScreenScrubDispatchTest {
  private static final String[] PREF_KEYS = {
    "engine_choice", "native_decoder_mode", "playback_mode"
  };
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final boolean[] present = new boolean[PREF_KEYS.length];
  private final int[] saved = new int[PREF_KEYS.length];
  private final AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
  private Context context;
  private SharedPreferences preferences;
  private ScopedPlaybackHistory history;
  private PlayerActivity activity;
  private VideoPage page;
  private ExoPlayer source;
  private Window.Callback originalCallback;
  private View touch;
  private String queueToken;
  private boolean preferencesChanged, sensitivityPresent, fingerDown;
  private float savedSensitivity, x, y, horizontalEnd, verticalEnd;
  private int videoWidth, videoHeight, barcodeFps;
  private long durationMs, originMs;
  private volatile long downTime;
  private volatile Delivery delivered;

  @Before
  public void launchExplicitFixture() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    String argument = args.getString("benchUri", "");
    assumeTrue("Explicit local screen-gesture fixture required", !argument.isEmpty());
    barcodeFps = Integer.parseInt(args.getString("benchBarcodeFps", "0"));
    assertTrue("Optional barcode fixture must use 60 fps", barcodeFps == 0 || barcodeFps == 60);
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    Uri fixture = readFixture(argument);
    preferences = context.getSharedPreferences("player", 0);
    for (int i = 0; i < PREF_KEYS.length; i++) {
      present[i] = preferences.contains(PREF_KEYS[i]);
      saved[i] = preferences.getInt(PREF_KEYS[i], 0);
    }
    sensitivityPresent = preferences.contains("seek_sensitivity");
    savedSensitivity = preferences.getFloat("seek_sensitivity", 1f);
    history = onMain(() -> ScopedPlaybackHistory.open(context));
    preferencesChanged = true;
    assertTrue(
        "Test preferences must be configured",
        preferences
            .edit()
            .putInt(PREF_KEYS[0], PlaybackEngineChoice.SYSTEM)
            .putInt(PREF_KEYS[1], NativeDecoderPolicy.AUTO)
            .putInt(PREF_KEYS[2], PlaybackOrder.SINGLE)
            .putFloat("seek_sensitivity", 1f)
            .commit());
    launchInMemory(fixture);
    onMain(
        () -> {
          invoke(activity, "setPlaying", boolean.class, false);
          invoke(activity, "setChrome", boolean.class, false);
          return null;
        });
    originMs = Math.max(1000, Math.min(3000, durationMs / 4));
    long baseline = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.seekTo(originMs);
          return null;
        });
    awaitExact(originMs, baseline);
    onMain(
        () -> {
          touch = (View) read(activity, "touchLayer");
          assertTrue("Video touch surface must be visible", touch.isShown());
          int[] offset = new int[2];
          touch.getLocationOnScreen(offset);
          assertTrue(
              "Video surface must be laid out", touch.getWidth() > 200 && touch.getHeight() > 200);
          x = offset[0] + touch.getWidth() * .28f;
          y = offset[1] + touch.getHeight() * .48f;
          horizontalEnd = x + touch.getWidth() * .10f;
          verticalEnd = y - touch.getHeight() * .16f;
          assertFalse(
              "Fixture enters the ordinary full-screen presentation",
              (boolean) read(activity, "portraitMode"));
          installDeliveryObserver();
          return null;
        });
  }

  @After
  public void restoreOwnedState() throws Exception {
    try {
      if (fingerDown && activity != null) inject(MotionEvent.ACTION_CANCEL, x, y);
    } finally {
      try {
        try {
          if (activity != null) {
            try {
              onMain(
                  () -> {
                    if (originalCallback != null)
                      activity.getWindow().setCallback(originalCallback);
                    return null;
                  });
            } finally {
              onMain(
                  () -> {
                    if (!activity.isFinishing()) activity.finish();
                    return null;
                  });
            }
          }
        } finally {
          if (history != null) history.closeAfterActivityDestroyed(activity);
        }
      } finally {
        QueueStore.forget(queueToken);
        if (preferencesChanged) {
          SharedPreferences.Editor restore = preferences.edit();
          for (int i = 0; i < PREF_KEYS.length; i++) {
            if (present[i]) restore.putInt(PREF_KEYS[i], saved[i]);
            else restore.remove(PREF_KEYS[i]);
          }
          if (sensitivityPresent) restore.putFloat("seek_sensitivity", savedSensitivity);
          else restore.remove("seek_sensitivity");
          assertTrue("Original preferences must be restored", restore.commit());
        }
      }
    }
  }

  @Test
  public void heldHorizontalGestureUsesOriginalDownTimeAndCommitsActualFrame() throws Exception {
    int previousCount = onMain(() -> (int) read(page, "scrubSubmittedCount"));
    inject(MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(80);
    onMain(
        () -> {
          assertFalse(
              "Holding DOWN alone must not start a scrub", (boolean) read(page, "scrubbing"));
          assertEquals(
              "Holding DOWN must not submit a preview",
              previousCount,
              (int) read(page, "scrubSubmittedCount"));
          assertEquals(originMs, source.getCurrentPosition(), 100);
          return null;
        });
    long baseline = page.lastRenderedFrameSequence;
    inject(MotionEvent.ACTION_MOVE, horizontalEnd, y);
    Delivery move = observed(MotionEvent.ACTION_MOVE);
    assertTrue(
        "The gesture must be recognized before long press",
        move.handledMs - downTime < ViewConfiguration.getLongPressTimeout());
    assertTrue("Physical horizontal motion must start seeking", move.seeking);
    assertEquals("The original DOWN anchors the 50ms guard", downTime, move.scrubStartedMs);
    assertTrue("An aged gesture submits its preview in the MOVE dispatch", move.submittedCount > 0);
    assertTrue(
        "Preview dispatch belongs to this MOVE",
        move.dispatchMs >= move.receivedMs && move.dispatchMs <= move.handledMs);
    assertTrue(
        "The gesture must move to a distinct target", Math.abs(move.targetMs - originMs) > 300);
    awaitPreview(move.targetMs, baseline);
    inject(MotionEvent.ACTION_UP, horizontalEnd, y);
    assertFinished(move.targetMs);
    report(1, move.handledMs - downTime, move.dispatchMs - move.receivedMs);
  }

  @Test
  public void cancelledHorizontalGestureRestoresExactOriginAndPause() throws Exception {
    inject(MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(80);
    long baseline = page.lastRenderedFrameSequence;
    inject(MotionEvent.ACTION_MOVE, horizontalEnd, y);
    Delivery move = observed(MotionEvent.ACTION_MOVE);
    assertTrue(
        "Horizontal motion must actually start previewing",
        move.seeking && move.submittedCount > 0);
    awaitPreview(move.targetMs, baseline);
    long cancelBaseline = page.lastRenderedFrameSequence;
    inject(MotionEvent.ACTION_CANCEL, horizontalEnd, y);
    observed(MotionEvent.ACTION_CANCEL);
    awaitExact(originMs, cancelBaseline);
    assertFinished(originMs);
    report(2, move.handledMs - downTime, move.dispatchMs - move.receivedMs);
  }

  @Test
  public void verticalAndUnrecognizedCancelledTouchesNeverSubmitPreview() throws Exception {
    int before = onMain(() -> (int) read(page, "scrubSubmittedCount"));
    long seekSerial = onMain(() -> (long) read(page, "explicitSeekSerial"));
    inject(MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(80);
    inject(MotionEvent.ACTION_MOVE, x, verticalEnd);
    Delivery vertical = observed(MotionEvent.ACTION_MOVE);
    assertFalse("A vertical gesture must not become a horizontal seek", vertical.seeking);
    assertEquals("The left-side vertical action is brightness", 2, vertical.gestureKind);
    assertEquals("Vertical movement does not submit a preview", before, vertical.submittedCount);
    inject(MotionEvent.ACTION_UP, x, verticalEnd);
    inject(MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(80);
    inject(MotionEvent.ACTION_CANCEL, x, y);
    Delivery cancel = observed(MotionEvent.ACTION_CANCEL);
    assertFalse(cancel.seeking);
    assertEquals(
        "Cancel before recognition never submits a preview", before, cancel.submittedCount);
    SystemClock.sleep(100);
    onMain(
        () -> {
          assertIdentity();
          assertEquals(
              "No video seek was triggered", seekSerial, (long) read(page, "explicitSeekSerial"));
          assertEquals(originMs, source.getCurrentPosition(), 100);
          assertFalse(source.getPlayWhenReady());
          assertEquals(0f, source.getVolume(), 0f);
          assertFalse((boolean) read(page, "scrubbing"));
          assertEquals(View.GONE, ((View) read(activity, "seekPreview")).getVisibility());
          return null;
        });
    report(3, 0, 0);
  }

  private Uri readFixture(String argument) throws Exception {
    Uri uri = Uri.parse(argument);
    if ("file".equals(uri.getScheme())) {
      assertNotNull("App test storage is required", context.getExternalFilesDir(null));
      assertNotNull("Explicit fixture path is required", uri.getPath());
      File root = new File(context.getExternalFilesDir(null), "test-only").getCanonicalFile();
      File file = new File(uri.getPath()).getCanonicalFile();
      assertTrue(
          "File fixtures must be inside app test-only storage",
          file.toPath().startsWith(root.toPath()));
      assertTrue("Explicit fixture must be readable", file.isFile() && file.canRead());
      uri = Uri.fromFile(file);
    } else
      assertEquals(
          "Only explicit local content or test-file URIs are accepted", "content", uri.getScheme());
    MediaExtractor extractor = new MediaExtractor();
    int tracks = 0;
    try {
      extractor.setDataSource(context, uri, null);
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("video/")) continue;
        tracks++;
        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000;
      }
    } catch (Exception unreadable) {
      throw new AssertionError("Explicit fixture headers could not be read");
    } finally {
      extractor.release();
    }
    assertEquals("Exactly one video track is required", 1, tracks);
    assertTrue(
        "Use a moving fixture of at least five seconds",
        durationMs >= 5000 && videoWidth > 0 && videoHeight > 0);
    return uri;
  }

  @SuppressWarnings("unchecked")
  private void launchInMemory(Uri uri) throws Exception {
    queueToken = UUID.randomUUID().toString();
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(
        new VideoItem(
            "screen-" + queueToken,
            "fixture.mp4",
            uri.toString(),
            "",
            0,
            durationMs,
            0,
            0,
            videoWidth,
            videoHeight));
    Field memory = QueueStore.class.getDeclaredField("MEMORY");
    memory.setAccessible(true);
    ((Map<String, ArrayList<VideoItem>>) memory.get(null)).put(queueToken, queue);
    context.startActivity(
        new Intent(context, PlayerActivity.class)
            .putExtra("queue", queueToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(
        () ->
            onMain(
                () -> {
                  for (Activity candidate :
                      ActivityLifecycleMonitorRegistry.getInstance()
                          .getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof PlayerActivity
                        && queueToken.equals(read(candidate, "queueToken")))
                      activity = (PlayerActivity) candidate;
                  if (activity == null || activity.isFinishing() || !activity.hasWindowFocus())
                    return false;
                  page = (VideoPage) read(activity, "currentPage");
                  if (page == null || !page.firstFrameReady || page.buffering) return false;
                  assertNotNull("Explicit system test requires Media3", page.exo);
                  source = page.exo;
                  return source.getPlaybackState() == Player.STATE_READY;
                }),
        "Fixture did not become ready");
  }

  private void installDeliveryObserver() {
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
                      long received = SystemClock.uptimeMillis();
                      try {
                        return method.invoke(originalCallback, arguments);
                      } catch (InvocationTargetException error) {
                        throw error.getCause();
                      } finally {
                        if (event != null && event.getDownTime() == downTime) {
                          try {
                            delivered =
                                new Delivery(
                                    event.getActionMasked(),
                                    received,
                                    SystemClock.uptimeMillis(),
                                    (boolean) read(activity, "gestureSeeking"),
                                    (int) read(activity, "gestureKind"),
                                    (long) read(activity, "gestureTarget"),
                                    (long) read(page, "scrubStartedAt"),
                                    (long) read(page, "lastScrubDispatch"),
                                    (int) read(page, "scrubSubmittedCount"));
                          } catch (Throwable error) {
                            deliveryFailure.compareAndSet(null, error);
                          }
                        }
                      }
                    }));
  }

  private void inject(int action, float atX, float atY) {
    long now = SystemClock.uptimeMillis();
    if (action == MotionEvent.ACTION_DOWN) {
      downTime = now;
      fingerDown = true;
    }
    MotionEvent event = MotionEvent.obtain(downTime, now, action, atX, atY, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue(
          "Physical touch injection must be accepted",
          instrumentation.getUiAutomation().injectInputEvent(event, true));
    } finally {
      event.recycle();
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        fingerDown = false;
    }
  }

  private Delivery observed(int action) throws Exception {
    onMain(() -> null);
    if (deliveryFailure.get() != null)
      throw new AssertionError("Could not observe delivered screen touch");
    Delivery result = delivered;
    assertNotNull("The application must receive the injected touch", result);
    assertEquals("Expected touch action must reach the actual window", action, result.action);
    return result;
  }

  private void awaitPreview(long target, long baseline) throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  assertIdentity();
                  return page.lastRenderedFrameSequence > baseline
                      && Math.abs(page.lastRenderedPositionUs / 1000 - target) <= 350
                      && source.getVolume() == 0
                      && !source.getPlayWhenReady();
                }),
        "Horizontal preview did not produce a real target-adjacent video frame");
    awaitDisplayedPreview(target);
  }

  private void awaitDisplayedPreview(long target) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 5000;
    do {
      PreviewCopy preview =
          onMain(
              () -> {
                assertIdentity();
                ImageView image = (ImageView) read(activity, "previewImage");
                long caption = page.lastScrubPreviewPositionMs;
                if (!image.isShown()
                    || !(image.getDrawable() instanceof BitmapDrawable)
                    || caption < 0
                    || Math.abs(caption - target) > 350) return null;
                assertTrue(
                    "The active decoder must have supplied the image",
                    (long) read(page, "capturedTextureTimestamp") > 0);
                assertTrue(
                    "Displayed preview caption must describe its copied frame",
                    ((TextView) read(activity, "previewTime"))
                        .getText()
                        .toString()
                        .startsWith(PlayerActivity.formatTime(caption) + " / "));
                // With an active Media3 player, this displayed image comes exclusively from
                // refreshScrubPreview -> captureScrubFrame; the extractor branch is not used.
                // Copy instead of invoking captureScrubFrame again and consuming the only paused
                // texture before the real UI can use it. Never recycle the UI-owned bitmap.
                Bitmap displayed = ((BitmapDrawable) image.getDrawable()).getBitmap();
                assertFalse("Displayed preview must remain usable", displayed.isRecycled());
                Bitmap copy = displayed.copy(Bitmap.Config.ARGB_8888, false);
                assertNotNull("Displayed preview copy must succeed", copy);
                return new PreviewCopy(copy, caption);
              });
      if (preview != null) {
        try {
          if (barcodeFps == 0) return;
          Bitmap content =
              NativeFrameProbe.cropExpectedFitContent(preview.bitmap, videoWidth, videoHeight);
          try {
            OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(content);
            if (code != null
                && Math.abs(code.frame * 1000L / barcodeFps - target) <= 350
                && Math.abs(code.frame * 1000L / barcodeFps - preview.positionMs) <= 150) return;
          } finally {
            if (content != preview.bitmap) content.recycle();
          }
        } finally {
          preview.bitmap.recycle();
        }
      }
      SystemClock.sleep(20);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("The held gesture must display a real preview with a valid frame caption");
  }

  private void awaitExact(long target, long baseline) throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  assertIdentity();
                  return page.lastRenderedFrameSequence > baseline
                      && Math.abs(page.lastRenderedPositionUs / 1000 - target) <= 150
                      && Math.abs(source.getCurrentPosition() - target) <= 100
                      && source.getPlaybackState() == Player.STATE_READY
                      && !page.buffering
                      && !source.isScrubbingModeEnabled()
                      && read(page, "exoExactHold") == null;
                }),
        "Precise target did not render and settle");
  }

  private void assertFinished(long target) throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  assertIdentity();
                  return Math.abs(page.lastRenderedPositionUs / 1000 - target) <= 150
                      && Math.abs(source.getCurrentPosition() - target) <= 100
                      && source.getPlaybackState() == Player.STATE_READY
                      && !source.isScrubbingModeEnabled()
                      && read(page, "exoExactHold") == null;
                }),
        "Final exact screen-seek target did not settle");
    onMain(
        () -> {
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          assertFalse(source.getPlayWhenReady());
          assertFalse(page.playRequested);
          assertEquals(0f, source.getVolume(), 0f);
          assertFalse((boolean) read(activity, "gestureSeeking"));
          assertFalse((boolean) read(page, "scrubbing"));
          assertEquals(View.GONE, ((View) read(activity, "seekPreview")).getVisibility());
          assertEquals(View.INVISIBLE, ((View) read(activity, "bottomBar")).getVisibility());
          return null;
        });
    // Metadata precedes the texture commit. Poll the actual surface instead of accepting an old
    // texture or making a scheduler-sensitive single capture immediately after the callback.
    long deadline = SystemClock.uptimeMillis() + 3000;
    do {
      Bitmap frame = NativeFrameProbe.capturePlayerSurface(instrumentation, page.exoView);
      try {
        if (barcodeFps == 0) return;
        Bitmap content = NativeFrameProbe.cropExpectedFitContent(frame, videoWidth, videoHeight);
        try {
          OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(content);
          if (code != null && Math.abs(code.frame * 1000L / barcodeFps - target) <= 150) return;
        } finally {
          if (content != frame) content.recycle();
        }
      } finally {
        frame.recycle();
      }
      SystemClock.sleep(20);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("Independent rendered barcode must match the final target");
  }

  private void assertIdentity() throws Exception {
    assertSame("Screen gestures must retain the page", page, read(activity, "currentPage"));
    assertSame("Screen gestures must retain the decoder", source, page.exo);
    assertFalse("Screen gesture must not cause a playback failure", page.failed);
  }

  private void report(int scenario, long recognitionMs, long dispatchMs) throws Exception {
    JSONObject result =
        new JSONObject()
            .put("scenario", scenario)
            .put("recognitionMs", recognitionMs)
            .put("dispatchWithinMoveMs", dispatchMs)
            .put("displayedPreviewChecked", scenario != 3)
            .put("barcodeChecked", scenario != 3 && barcodeFps != 0)
            .put("passed", true);
    Bundle status = new Bundle();
    status.putString("SCREEN_SCRUB", result.toString());
    instrumentation.sendStatus(2, status);
  }

  private static Object read(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void invoke(Object owner, String name, Class<?> type, Object argument)
      throws Exception {
    Method method = owner.getClass().getDeclaredMethod(name, type);
    method.setAccessible(true);
    method.invoke(owner, argument);
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    main.post(task);
    try {
      return task.get(2, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      while (cause instanceof InvocationTargetException)
        cause = ((InvocationTargetException) cause).getCause();
      if (cause instanceof Error) throw (Error) cause;
      if (cause instanceof Exception) throw (Exception) cause;
      throw new RuntimeException(cause);
    } catch (Exception error) {
      task.cancel(false);
      throw error;
    }
  }

  private static void await(Callable<Boolean> condition, String failure) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 10000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(20);
    }
    fail(failure);
  }

  private static final class Delivery {
    final int action, gestureKind, submittedCount;
    final long receivedMs, handledMs, targetMs, scrubStartedMs, dispatchMs;
    final boolean seeking;

    Delivery(
        int action,
        long receivedMs,
        long handledMs,
        boolean seeking,
        int gestureKind,
        long targetMs,
        long scrubStartedMs,
        long dispatchMs,
        int submittedCount) {
      this.action = action;
      this.receivedMs = receivedMs;
      this.handledMs = handledMs;
      this.seeking = seeking;
      this.gestureKind = gestureKind;
      this.targetMs = targetMs;
      this.scrubStartedMs = scrubStartedMs;
      this.dispatchMs = dispatchMs;
      this.submittedCount = submittedCount;
    }
  }

  private static final class PreviewCopy {
    final Bitmap bitmap;
    final long positionMs;

    PreviewCopy(Bitmap bitmap, long positionMs) {
      this.bitmap = bitmap;
      this.positionMs = positionMs;
    }
  }
}
