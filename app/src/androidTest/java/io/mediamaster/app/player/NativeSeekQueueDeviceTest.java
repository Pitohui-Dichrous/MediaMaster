package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.SurfaceView;
import android.view.View;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
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
    assertTrue(
        prefs
            .edit()
            .putInt("engine_choice", 2)
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .putInt("native_decoder_mode", NativeDecoderPolicy.SOFTWARE)
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
  public void restorePreferences() {
    if (activity != null) rule.finishActivity();
    if (context == null) return;
    SharedPreferences.Editor restore = context.getSharedPreferences("player", 0).edit();
    for (int i = 0; i < preferenceNames.length; i++) {
      if (hadPreference[i]) restore.putInt(preferenceNames[i], savedPreference[i]);
      else restore.remove(preferenceNames[i]);
    }
    assertTrue(restore.commit());
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
  public void cancelDuringSubmittedCoarseSeekKeepsOnlyTheCancelOrigin() throws Exception {
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
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "duration", "_size", "date_modified", "width", "height"},
                "_display_name=?",
                new String[] {name},
                null)) {
      assertNotNull(cursor);
      assertEquals("Exactly one generated B2 H.264720 fixture is required", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      Uri uri =
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
      return new VideoItem(
          "native-seek-queue-" + SystemClock.elapsedRealtimeNanos(),
          name,
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
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            value.set(action.call());
          } catch (Throwable failure) {
            error.set(failure);
          }
        });
    if (error.get() instanceof AssertionError) throw (AssertionError) error.get();
    if (error.get() instanceof Exception) throw (Exception) error.get();
    if (error.get() != null) throw new RuntimeException(error.get());
    return value.get();
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
