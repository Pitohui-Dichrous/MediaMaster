package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.SeekBar;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Generated-video state regressions; timing benchmarks remain separate from these watchdogs. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class NativeSeekQueueDeviceTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final String[] preferenceNames = {
    "engine_choice", "playback_mode", "native_decoder_mode"
  };
  private final boolean[] hadPreference = new boolean[3];
  private final int[] savedPreference = new int[3];
  private Context context;
  private PlayerActivity activity;
  private VideoPage page;
  private MediaPlayer source;
  private ScopedPlaybackHistory history;
  private boolean preferencesCaptured;

  @Before
  public void launchGeneratedSoftwareFixture() throws Exception {
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    SharedPreferences prefs = context.getSharedPreferences("player", 0);
    for (int i = 0; i < preferenceNames.length; i++) {
      hadPreference[i] = prefs.contains(preferenceNames[i]);
      savedPreference[i] = prefs.getInt(preferenceNames[i], 0);
    }
    preferencesCaptured = true;
    history = onMain(() -> ScopedPlaybackHistory.open(context));
    int decoder =
        Integer.parseInt(
            InstrumentationRegistry.getArguments()
                .getString("benchQueueDecoder", Integer.toString(NativeDecoderPolicy.SOFTWARE)));
    assertTrue(decoder >= 0 && decoder <= 2);
    assertTrue(
        prefs
            .edit()
            .putInt("engine_choice", 2)
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .putInt("native_decoder_mode", decoder)
            .commit());
    ArrayList<VideoItem> items = new ArrayList<>();
    items.add(fixture());
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, items)));
    await(
        () ->
            onMain(
                () -> {
                  page = (VideoPage) read(activity, "currentPage");
                  source = page == null ? null : page.vlc;
                  return source != null
                      && page.firstFrameReady
                      && source.isSeekable()
                      && !flag("warmingVlc");
                }));
    setPlaying(false);
    await(() -> onMain(() -> !source.isPlaying()));
  }

  @After
  public void restorePreferences() throws Exception {
    try {
      try {
        if (activity != null) rule.finishActivity();
      } finally {
        if (history != null) history.closeAfterActivityDestroyed(activity);
      }
    } finally {
      if (context != null && preferencesCaptured) {
        SharedPreferences.Editor restore = context.getSharedPreferences("player", 0).edit();
        for (int i = 0; i < preferenceNames.length; i++) {
          if (hadPreference[i]) restore.putInt(preferenceNames[i], savedPreference[i]);
          else restore.remove(preferenceNames[i]);
        }
        assertTrue(restore.commit());
      }
    }
  }

  @Test
  public void heldPausedPreviewProducesTargetWithoutReplacingNativeDecoder() throws Exception {
    long target = physicalHeldSeek(false);
    awaitPausedPicture(target);
    awaitQueueDrain();
    SystemClock.sleep(2300);
    awaitPausedPicture(target);
    assertRetainedPaused();
  }

  @Test
  public void heldPlayingPreviewResumesActualBarcodeProgressOnSameNativeDecoder() throws Exception {
    long target = physicalHeldSeek(true);
    long started = SystemClock.uptimeMillis(), anchorFrame = -1, anchorAt = -1, latestFrame = -1;
    int nearTargetRewinds = 0;
    boolean settled = false;
    JSONArray trace = new JSONArray();
    try {
      while (SystemClock.uptimeMillis() - started < 4000) {
        boolean queueBefore =
            onMain(
                () -> {
                  assertSame("Held preview must retain its native decoder", source, page.vlc);
                  assertTrue("Playing intent must survive held preview", page.playRequested);
                  assertFalse("Released input must finish scrubbing", flag("scrubbing"));
                  return queue().hasWork();
                });
        long frame = barcodeFrameMs();
        long now = SystemClock.uptimeMillis();
        boolean queueAfter = onMain(() -> queue().hasWork());
        trace.put(
            new JSONObject()
                .put("atMs", now - started)
                .put("frameMs", frame)
                .put("queueBefore", queueBefore)
                .put("queueAfter", queueAfter));
        // Preview playback may already be ahead when final EXACT revisits the same target.
        // Queue drain alone is not a PTS oracle: both barcode proximity and forward output follow.
        if (queueBefore || queueAfter || frame < 0) {
          anchorFrame = latestFrame = -1;
        } else if (anchorFrame < 0) {
          if (Math.abs(frame - target) <= 200) {
            anchorFrame = latestFrame = frame;
            anchorAt = now;
          }
        } else if (frame < latestFrame) {
          assertTrue(
              "Only a bounded final retarget near the exact target is permitted",
              Math.abs(frame - target) <= 200
                  && Math.abs(latestFrame - target) <= 200
                  && ++nearTargetRewinds <= 2);
          anchorFrame = latestFrame = frame;
          anchorAt = now;
        } else {
          latestFrame = frame;
          if (frame - anchorFrame >= 200
              && now - anchorAt >= 150
              && Math.abs((frame - anchorFrame) - (now - anchorAt)) <= 300) {
            settled = true;
            break;
          }
        }
        SystemClock.sleep(30);
      }
      assertTrue(
          "Final target must converge to sustained forward barcode output within 4 seconds",
          settled);
      long settledAt = SystemClock.uptimeMillis(), settledFrame = latestFrame;
      SystemClock.sleep(2300);
      long after = barcodeFrameMs(), at = SystemClock.uptimeMillis();
      trace.put(
          new JSONObject()
              .put("atMs", at - started)
              .put("frameMs", after)
              .put("retainedTail", true));
      assertTrue(
          "Actual output must keep advancing beyond the recovery watchdog",
          after - settledFrame >= 500
              && Math.abs((after - settledFrame) - (at - settledAt)) <= 800);
      onMain(
          () -> {
            assertSame(source, page.vlc);
            assertTrue(page.playRequested);
            assertTrue(source.isPlaying());
            assertTrue("Playing output must not remain muted", source.getVolume() > 0);
            assertFalse(queue().hasWork());
            return null;
          });
    } finally {
      Bundle status = new Bundle();
      status.putString(
          "heldPlayingBarcode",
          new JSONObject()
              .put("targetMs", target)
              .put("nearTargetRewinds", nearTargetRewinds)
              .put("settled", settled)
              .put("samples", trace)
              .toString());
      instrumentation.sendStatus(2, status);
    }
  }

  /** Real input exceeds the 50 ms preview delay; final output, not a flag alone, is the oracle. */
  private long physicalHeldSeek(boolean playing) throws Exception {
    seek(6200);
    awaitPausedPicture(6200);
    awaitQueueDrain();
    setPlaying(playing);
    if (playing) await(() -> onMain(source::isPlaying));
    int[] geometry = awaitSeekGeometry();
    int[] location =
        onMain(
            () -> {
              assertTrue("Generated fixture must span the target", page.duration() > 26000);
              int x =
                  geometry[0]
                      + geometry[4]
                      + Math.round(
                          (geometry[2] - geometry[4] - geometry[5]) * 18720f / page.duration());
              return new int[] {x, geometry[1] + geometry[3] / 2};
            });
    long serialBefore = onMain(() -> (long) read(page, "explicitSeekSerial"));
    long down = SystemClock.uptimeMillis();
    boolean released = false;
    try {
      injectHeld(down, MotionEvent.ACTION_DOWN, location);
      long consumeDeadline = SystemClock.uptimeMillis() + 1500;
      while (!onMain(() -> flag("scrubbing") && (boolean) read(activity, "scrubbing"))
          && SystemClock.uptimeMillis() < consumeDeadline) SystemClock.sleep(10);
      assertTrue(
          "DOWN did not reach SeekBar; " + heldDiagnostic(location),
          onMain(() -> flag("scrubbing") && (boolean) read(activity, "scrubbing")));
      long consumed = SystemClock.uptimeMillis();
      SystemClock.sleep(30);
      injectHeld(down, MotionEvent.ACTION_MOVE, location);
      long remaining = 120 - (SystemClock.uptimeMillis() - consumed);
      if (remaining > 0) SystemClock.sleep(remaining);
      String diagnostic = heldDiagnostic(location);
      onMain(
          () -> {
            assertSame(source, page.vlc);
            assertTrue(
                "Held input must dispatch a real preview; " + diagnostic,
                (int) read(page, "scrubSubmittedCount") > 0);
            NativeSeekQueue.Request submitted = queue().submitted();
            if (submitted != null)
              assertFalse("Native preview must request accurate decoding", submitted.fast);
            return null;
          });
      injectHeld(down, MotionEvent.ACTION_UP, location);
      released = true;
      return onMain(
          () -> {
            long target = (long) read(activity, "gestureTarget");
            assertTrue(
                "UP must commit a consumed seek",
                (long) read(page, "explicitSeekSerial") > serialBefore);
            assertFalse("UP must finish SeekBar tracking", flag("scrubbing"));
            assertTrue(
                "Target must fit the existing barcode decoder", target > 1000 && target < 28000);
            return target;
          });
    } finally {
      if (!released) injectHeld(down, MotionEvent.ACTION_CANCEL, location);
    }
  }

  /**
   * GONE-to-visible controls and rotation must finish layout before physical coordinates are used.
   */
  private int[] awaitSeekGeometry() throws Exception {
    onMain(
        () -> {
          invoke(activity, "showChrome");
          invoke(activity, "updateProgress");
          return null;
        });
    long until = SystemClock.uptimeMillis() + 3000, stableSince = 0;
    int[] last = null;
    while (SystemClock.uptimeMillis() < until) {
      int[] now =
          onMain(
              () -> {
                SeekBar bar = (SeekBar) read(activity, "seek");
                View root = activity.getWindow().getDecorView();
                Rect visible = new Rect();
                if (!root.hasWindowFocus()
                    || !bar.hasWindowFocus()
                    || !bar.isShown()
                    || !bar.isLaidOut()
                    || bar.isLayoutRequested()
                    || root.isLayoutRequested()
                    || bar.getWidth() <= bar.getPaddingLeft() + bar.getPaddingRight()
                    || bar.getHeight() <= 0
                    || !bar.getGlobalVisibleRect(visible)) return null;
                int[] xy = new int[2], rootXY = new int[2];
                bar.getLocationOnScreen(xy);
                root.getLocationOnScreen(rootXY);
                return new int[] {
                  xy[0],
                  xy[1],
                  bar.getWidth(),
                  bar.getHeight(),
                  bar.getPaddingLeft(),
                  bar.getPaddingRight(),
                  rootXY[0],
                  rootXY[1],
                  root.getWidth(),
                  root.getHeight(),
                  visible.left,
                  visible.top,
                  visible.right,
                  visible.bottom
                };
              });
      long at = SystemClock.uptimeMillis();
      if (now == null || !Arrays.equals(last, now)) {
        last = now;
        stableSince = at;
      } else if (at - stableSince >= 200) return now;
      SystemClock.sleep(40);
    }
    throw new AssertionError("Seek geometry did not settle; " + heldDiagnostic(new int[] {-1, -1}));
  }

  private String heldDiagnostic(int[] location) throws Exception {
    return onMain(
        () -> {
          SeekBar bar = (SeekBar) read(activity, "seek");
          int[] xy = new int[2];
          bar.getLocationOnScreen(xy);
          return "x="
              + location[0]
              + ",y="
              + location[1]
              + ",barX="
              + xy[0]
              + ",barY="
              + xy[1]
              + ",width="
              + bar.getWidth()
              + ",height="
              + bar.getHeight()
              + ",shown="
              + bar.isShown()
              + ",focused="
              + bar.hasWindowFocus()
              + ",scrubbing="
              + flag("scrubbing")
              + ",count="
              + (int) read(page, "scrubSubmittedCount")
              + ",serial="
              + (long) read(page, "explicitSeekSerial");
        });
  }

  private void injectHeld(long down, int action, int[] location) {
    MotionEvent event =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0], location[1], 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue(
          "Physical held input rejected",
          instrumentation.getUiAutomation().injectInputEvent(event, true));
    } finally {
      event.recycle();
    }
  }

  private long barcodeFrameMs() throws Exception {
    View surface = onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    if (surface == null) return -1;
    Bitmap picture = NativeFrameProbe.captureSurface(instrumentation, surface);
    try {
      OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(picture);
      return code == null ? -1 : code.frame * 40L;
    } finally {
      picture.recycle();
    }
  }

  @Test
  public void repeatedPausedPositionAndNativeClockNoopDoNotReplaceTheDecoder() throws Exception {
    seek(6200);
    awaitPausedPicture(6200);
    awaitQueueDrain();
    onMain(
        () -> {
          page.seekTo(6200);
          page.seekTo(6200);
          page.seekTo(6200);
          return null;
        });
    awaitPausedPicture(6200);
    awaitQueueDrain();
    long currentClock = onMain(source::getTime);
    seek(currentClock);
    awaitPausedPicture(currentClock);
    awaitQueueDrain();
    // Catch a delayed false watchdog fallback after a valid/no-op paused output.
    SystemClock.sleep(2300);
    assertRetainedPaused();
  }

  @Test
  public void cancelDuringSubmittedPreviewKeepsOnlyTheCancelOrigin() throws Exception {
    seek(4200);
    awaitPausedPicture(4200);
    awaitQueueDrain();
    long origin =
        onMain(
            () -> {
              long at = page.position();
              page.beginScrub();
              page.scrubTo(18720);
              invoke(page, "dispatchScrub");
              assertNotNull(queue().submitted());
              page.scrubTo(24000);
              page.endScrub(24000, false);
              assertEquals(at, queue().latestPositionMs());
              assertTrue(queue().hasPending());
              return at;
            });
    awaitPausedPicture(origin);
    awaitQueueDrain();
    SystemClock.sleep(350);
    awaitPausedPicture(origin);
    assertRetainedPaused();
  }

  @Test
  public void pauseDuringSubmittedSeekKeepsLatestTargetAndNeverResumes() throws Exception {
    setPlaying(true);
    await(() -> onMain(source::isPlaying));
    onMain(
        () -> {
          page.seekTo(6200);
          page.seekTo(18720);
          assertNotNull(queue().submitted());
          assertTrue(queue().hasPending());
          invoke(activity, "setPlaying", false);
          assertFalse(page.playRequested);
          return null;
        });
    awaitPausedPicture(18720);
    awaitQueueDrain();
    SystemClock.sleep(350);
    awaitPausedPicture(18720);
    assertRetainedPaused();
  }

  @Test
  public void surfaceLossDuringPendingSeekKeepsNewestTargetAndSamePausedInput() throws Exception {
    Assume.assumeTrue("SurfaceView recovery requires Android 14", Build.VERSION.SDK_INT >= 34);
    SurfaceView surface =
        (SurfaceView) onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    assertNotNull(surface);
    onMain(
        () -> {
          page.seekTo(6200);
          page.seekTo(18720);
          assertTrue(queue().hasWork());
          surface.setVisibility(View.INVISIBLE);
          return null;
        });
    await(() -> onMain(() -> !surface.getHolder().getSurface().isValid()));
    onMain(
        () -> {
          surface.setVisibility(View.VISIBLE);
          for (int tap = 0; tap < 12; tap++) page.seekTo(tap % 2 == 0 ? 24000 : 0);
          page.seekTo(10480);
          assertNull("Warmup must not flush once for every new tap", queue().submitted());
          assertEquals(10480, queue().latestPositionMs());
          return null;
        });
    await(() -> onMain(() -> page.firstFrameReady && !flag("warmingVlc")));
    awaitPausedPicture(10480);
    awaitQueueDrain();
    assertRetainedPaused();
  }

  @Test
  public void inactivePageRetainsLatestSeekWithoutAWatchdogReplacement() throws Exception {
    onMain(
        () -> {
          page.seekTo(6200);
          page.seekTo(18720);
          page.setPlayback(false, false, false);
          return null;
        });
    SystemClock.sleep(2300);
    onMain(
        () -> {
          assertSame(
              "An offscreen neighbor must not trigger a seek watchdog fallback", source, page.vlc);
          assertFalse(page.active);
          assertFalse(source.isPlaying());
          assertEquals(18720, queue().latestPositionMs());
          page.setPlayback(true, false, true);
          return null;
        });
    awaitPausedPicture(18720);
    awaitQueueDrain();
    assertRetainedPaused();
  }

  @Test
  public void finishingActivityRetiresRealNativePlayerWithoutQuarantiningItsLease()
      throws Exception {
    NativePlayerRetirement.Lease lease =
        (NativePlayerRetirement.Lease) onMain(() -> read(page, "nativeLease"));
    assertNotNull(lease);
    Object owner = read(lease, "owner");
    rule.finishActivity();
    activity = null;
    await(
        () -> {
          synchronized (owner) {
            String state = String.valueOf(read(lease, "state"));
            assertNotEquals(
                "Native cleanup failed at " + lease.failureStage(), "QUARANTINED", state);
            return "RELEASED".equals(state);
          }
        });
    assertNull(onMain(() -> page.vlc));
  }

  private void seek(long target) throws Exception {
    onMain(
        () -> {
          page.seekTo(target);
          return null;
        });
  }

  private void setPlaying(boolean playing) throws Exception {
    onMain(
        () -> {
          invoke(activity, "setPlaying", playing);
          return null;
        });
  }

  private void awaitQueueDrain() throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  assertSame("A healthy seek must not rebuild its decoder", source, page.vlc);
                  return !queue().hasWork();
                }));
  }

  private void assertRetainedPaused() throws Exception {
    onMain(
        () -> {
          assertSame(source, page.vlc);
          assertFalse(page.playRequested);
          assertFalse(flag("vlcRunningRequested"));
          assertFalse(source.isPlaying());
          assertEquals(0, source.getVolume());
          assertFalse(queue().hasWork());
          return null;
        });
  }

  private void awaitPausedPicture(long target) throws Exception {
    long[] last = {-1};
    try {
      await(
          () -> {
            View surface = onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
            if (surface == null) return false;
            Bitmap picture = NativeFrameProbe.captureSurface(instrumentation, surface);
            try {
              OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(picture);
              last[0] = code == null ? -1 : code.frame * 40L;
              // Same explicit state-regression allowance as NativeSeekLatencyTest; this is not
              // a speed/accuracy benchmark and does not change the optical benchmark's 120 ms gate.
              return last[0] >= 0 && Math.abs(last[0] - target) <= 200;
            } finally {
              picture.recycle();
            }
          });
    } catch (AssertionError failure) {
      throw new AssertionError(
          "Expected generated frame near " + target + "; last=" + last[0], failure);
    }
  }

  private NativeSeekQueue queue() throws Exception {
    return (NativeSeekQueue) read(page, "nativeSeekQueue");
  }

  private boolean flag(String name) throws Exception {
    return (Boolean) read(page, name);
  }

  private static Object read(Object instance, String name) throws Exception {
    Field field = instance.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  private static void invoke(Object instance, String name, Object... arguments) throws Exception {
    Class<?>[] types = new Class<?>[arguments.length];
    for (int i = 0; i < types.length; i++) types[i] = boolean.class;
    Method method = instance.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    method.invoke(instance, arguments);
  }

  private VideoItem fixture() {
    String name = "mm_seek_b2_h264_720_gop1.mp4";
    String explicit = InstrumentationRegistry.getArguments().getString("benchQueueUri", "");
    Uri query =
        explicit.isEmpty() ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : Uri.parse(explicit);
    assertEquals("Only indexed generated media is supported", "content", query.getScheme());
    assertEquals("Only MediaStore is supported", "media", query.getAuthority());
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                query,
                new String[] {
                  "_id", "duration", "_size", "date_modified", "width", "height", "_display_name"
                },
                explicit.isEmpty() ? "_display_name=?" : null,
                explicit.isEmpty() ? new String[] {name} : null,
                null)) {
      assertNotNull(cursor);
      assertEquals("Exactly one generated B2 H.264720 fixture is required", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      assertTrue(
          "Only generated barcode fixtures are accepted",
          cursor.getString(6).matches("mm_seek_(b2|stress)_[A-Za-z0-9_-]+\\.mp4"));
      assertTrue("Generated fixture must cover all regression targets", cursor.getLong(1) >= 26000);
      Uri uri =
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
      return new VideoItem(
          "native-seek-queue-" + SystemClock.elapsedRealtimeNanos(),
          cursor.getString(6),
          uri.toString(),
          "",
          cursor.getLong(2),
          cursor.getLong(1),
          cursor.getLong(3) * 1000,
          0,
          cursor.getInt(4),
          cursor.getInt(5));
    }
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
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

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 15000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(30);
    }
    fail("Generated-video queue state/frame did not settle before the hang watchdog");
  }
}
