package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioFocusRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Real native-player state regressions. Frame checks use generated pixels, never the VLC clock. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class NativeSeekLatencyTest {
  @Rule public TestName testName = new TestName();

  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private Context context;
  private PlayerActivity activity;
  private VideoPage page;
  private MediaPlayer source;
  private boolean hadEngine, hadMode;
  private int savedEngine, savedMode;

  @Before
  public void launchNativeFixture() throws Exception {
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    SharedPreferences prefs = context.getSharedPreferences("player", 0);
    hadEngine = prefs.contains("engine_choice");
    savedEngine = prefs.getInt("engine_choice", 0);
    hadMode = prefs.contains("playback_mode");
    savedMode = prefs.getInt("playback_mode", 0);
    assertTrue(
        prefs
            .edit()
            .putInt(
                "engine_choice",
                testName.getMethodName().equals("autoModeStartsOnNativePipelineForKnownLocalVideo")
                    ? 0
                    : 2)
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .commit());
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(fixture());
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, queue)));
    await(
        () ->
            onMain(
                () -> {
                  page = (VideoPage) read(activity, "currentPage");
                  source = page == null ? null : page.vlc;
                  return source != null
                      && page.firstFrameReady
                      && source.isSeekable()
                      && !flag("warmingVlc")
                      && source.isPlaying()
                      && flag("vlcRunningRequested");
                }));
  }

  @After
  public void restorePreferences() {
    if (activity != null) rule.finishActivity();
    if (context == null) return;
    SharedPreferences.Editor restore = context.getSharedPreferences("player", 0).edit();
    if (hadEngine) restore.putInt("engine_choice", savedEngine);
    else restore.remove("engine_choice");
    if (hadMode) restore.putInt("playback_mode", savedMode);
    else restore.remove("playback_mode");
    restore.commit();
  }

  @Test
  public void autoModeStartsOnNativePipelineForKnownLocalVideo() throws Exception {
    onMain(
        () -> {
          assertNotNull("AUTO begins with the measured native pipeline", page.vlc);
          assertNull("Media3 remains the fallback instead of the initial decoder", page.exo);
          assertTrue(page.firstFrameReady);
          assertTrue(source.isPlaying());
          return null;
        });
  }

  @Test
  public void cancelledScrubRestoresOriginAndTheNextChangedTargetWins() throws Exception {
    setPlaying(false);
    assertPlaying(false);
    onMain(
        () -> {
          page.seekTo(4200);
          return null;
        });
    awaitPicture(4200, false);
    long origin =
        onMain(
            () -> {
              long position = page.position();
              page.beginScrub();
              page.scrubTo(18720);
              return position;
            });
    await(
        () -> {
          long frame = decodedFrameMs();
          return frame >= 10000 && frame <= 22000;
        });
    NativeAudioProbe.assertMuted(instrumentation);
    onMain(
        () -> {
          page.endScrub(18720, false);
          assertFalse(flag("scrubbing"));
          assertFalse(flag("vlcRunningRequested"));
          return null;
        });
    awaitPicture(origin, false);
    // A cancelled pending request must not render later after the origin has been restored.
    SystemClock.sleep(250);
    awaitPicture(origin, false);
    onMain(
        () -> {
          page.beginScrub();
          page.scrubTo(6200);
          page.scrubTo(18720);
          page.endScrub(18720, true);
          assertFalse(flag("vlcRunningRequested"));
          assertEquals(
              "No coarse callback ran inside this quick interaction",
              0,
              (int) (Integer) read(page, "scrubSubmittedCount"));
          return null;
        });
    awaitPicture(18720, false);
    assertPlaying(false);
    assertSame(source, onMain(() -> page.vlc));
  }

  @Test
  public void playingQuickTapKeepsNativeClockRunningWithoutAnIntermediatePause() throws Exception {
    assertPlaying(true);
    long target = quickWidgetTap(.65f, true);
    awaitPicture(target, true);
    assertPlaying(true);
    NativeAudioProbe.assertAudible(instrumentation);
    onMain(
        () -> {
          assertSame(source, page.vlc);
          return null;
        });
  }

  @Test
  public void pausedQuickTapRemainsPausedAndRendersItsAccurateTarget() throws Exception {
    setPlaying(false);
    assertPlaying(false);
    long target = quickWidgetTap(.35f, false);
    awaitPicture(target, false);
    SystemClock.sleep(150);
    assertPlaying(false);
    onMain(
        () -> {
          assertSame(source, page.vlc);
          assertEquals(0, source.getVolume());
          return null;
        });
  }

  @Test
  public void sustainedDragIsSilentAndRestoresBothPlaybackIntents() throws Exception {
    for (boolean resume : new boolean[] {true, false}) {
      setPlaying(resume);
      assertPlaying(resume);
      onMain(
          () -> {
            page.beginScrub();
            page.scrubTo(4200);
            page.scrubTo(6200);
            assertEquals(0, source.getVolume());
            return null;
          });
      await(
          () ->
              onMain(
                  () ->
                      (Integer) read(page, "scrubSubmittedCount") > 0
                          && flag("vlcRunningRequested") == resume
                          && source.isPlaying() == resume));
      NativeAudioProbe.assertMuted(instrumentation);
      onMain(
          () -> {
            assertTrue(flag("scrubbing"));
            assertEquals("Held native scrub remains silent", 0, source.getVolume());
            assertEquals(
                "Scrubbing preserves the intended post-release state",
                resume,
                flag("playRequested"));
            page.scrubTo(12100);
            page.endScrub(18720, true);
            assertFalse(flag("scrubbing"));
            assertEquals(resume, flag("vlcRunningRequested"));
            return null;
          });
      awaitPicture(18720, resume);
      assertPlaying(resume);
      if (resume) NativeAudioProbe.assertAudible(instrumentation);
      else NativeAudioProbe.assertMuted(instrumentation);
      onMain(
          () -> {
            assertSame("A drag does not replace the native player", source, page.vlc);
            if (!resume) assertEquals(0, source.getVolume());
            return null;
          });
    }
  }

  @Test
  public void eightyMillisecondPressStaysMutedAndReleasesToTheAccurateTarget() throws Exception {
    for (boolean resume : new boolean[] {true, false}) {
      setPlaying(resume);
      assertPlaying(resume);
      final long down = SystemClock.uptimeMillis();
      final float[] point = new float[1];
      long target =
          onMain(
              () -> {
                SeekBar bar = (SeekBar) read(activity, "seek");
                point[0] =
                    bar.getPaddingLeft()
                        + .45f * (bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
                touch(bar, MotionEvent.ACTION_DOWN, point[0], down, down);
                return (Long) read(activity, "gestureTarget");
              });
      SystemClock.sleep(80);
      onMain(
          () -> {
            assertTrue(flag("scrubbing"));
            assertEquals(resume, flag("vlcRunningRequested"));
            assertEquals(resume, source.isPlaying());
            assertEquals(0, source.getVolume());
            return null;
          });
      NativeAudioProbe.assertMuted(instrumentation);
      onMain(
          () -> {
            SeekBar bar = (SeekBar) read(activity, "seek");
            touch(bar, MotionEvent.ACTION_UP, point[0], down, SystemClock.uptimeMillis());
            assertFalse(flag("scrubbing"));
            assertEquals(resume, flag("vlcRunningRequested"));
            return null;
          });
      awaitPicture(target, resume);
      assertPlaying(resume);
      if (resume) NativeAudioProbe.assertAudible(instrumentation);
      else NativeAudioProbe.assertMuted(instrumentation);
    }
  }

  @Test
  public void duckGainCannotUnmuteAHeldScrubOrItsPausedRestoration() throws Exception {
    for (boolean resume : new boolean[] {true, false}) {
      setPlaying(resume);
      assertPlaying(resume);
      long origin =
          onMain(
              () -> {
                long position = page.position();
                page.beginScrub();
                page.scrubTo(6200);
                // This is the production action called by CAN_DUCK, without competing for the
                // user's global audio focus from another test client.
                page.setNativeDucked(true);
                assertEquals(0, source.getVolume());
                return position;
              });
      NativeAudioProbe.assertMuted(instrumentation);
      onMain(
          () -> {
            page.endScrub(origin, false);
            assertEquals(resume, flag("playRequested"));
            return null;
          });
      assertPlaying(resume);
      awaitNativeVolume(resume ? 25 : 0);
      if (resume) NativeAudioProbe.assertAudible(instrumentation);
      else NativeAudioProbe.assertMuted(instrumentation);
      onMain(
          () -> {
            page.setNativeDucked(false);
            return null;
          });
      awaitNativeVolume(resume ? 100 : 0);
      if (!resume) NativeAudioProbe.assertMuted(instrumentation);
    }
  }

  @Test
  public void duckGainCannotBypassWarmupOrAnUnavailableNativeSurface() throws Exception {
    Assume.assumeTrue("Surface lifecycle path requires Android 14", Build.VERSION.SDK_INT >= 34);
    View rendered = onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    assertTrue(rendered instanceof SurfaceView);
    SurfaceView surface = (SurfaceView) rendered;
    NativeAudioProbe.assertAudible(instrumentation);
    try {
      onMain(
          () -> {
            // Hold the existing real decoder in the same gate state as an outstanding first-frame
            // probe. Avoid timing a sub-frame warmup interval or adding a production test hook.
            Runnable settledProbe = (Runnable) read(page, "frameProbe");
            if (settledProbe != null) ((Handler) read(page, "main")).removeCallbacks(settledProbe);
            Field warming = VideoPage.class.getDeclaredField("warmingVlc");
            warming.setAccessible(true);
            warming.setBoolean(page, true);
            page.firstFrameReady = false;
            page.setNativeDucked(true);
            assertEquals(0, source.getVolume());
            return null;
          });
      NativeAudioProbe.assertMuted(instrumentation);
      onMain(
          () -> {
            Field warming = VideoPage.class.getDeclaredField("warmingVlc");
            warming.setAccessible(true);
            warming.setBoolean(page, false);
            page.firstFrameReady = true;
            page.setNativeDucked(false);
            surface.setVisibility(View.INVISIBLE);
            return null;
          });
      await(
          () ->
              onMain(
                  () ->
                      !surface.getHolder().getSurface().isValid()
                          && !page.firstFrameReady
                          && !flag("warmingVlc")));
      onMain(
          () -> {
            page.setNativeDucked(true);
            assertEquals(0, source.getVolume());
            assertSame(source, page.vlc);
            return null;
          });
      NativeAudioProbe.assertMuted(instrumentation);
    } finally {
      onMain(
          () -> {
            Field warming = VideoPage.class.getDeclaredField("warmingVlc");
            warming.setAccessible(true);
            warming.setBoolean(page, false);
            surface.setVisibility(View.VISIBLE);
            page.setNativeDucked(false);
            return null;
          });
    }
    await(
        () ->
            onMain(
                () ->
                    page.firstFrameReady
                        && !flag("warmingVlc")
                        && NativeFrameProbe.findVideoSurface(page.vlcView) != null
                        && source.isPlaying()));
    assertSame("Surface recovery keeps the same native decoder", source, onMain(() -> page.vlc));
    await(() -> decodedFrameMs() >= 0);
    NativeAudioProbe.assertAudible(instrumentation);

    setPlaying(false);
    assertPlaying(false);
    long pausedAt = onMain(page::position);
    SurfaceView pausedSurface =
        (SurfaceView) onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    onMain(
        () -> {
          pausedSurface.setVisibility(View.INVISIBLE);
          return null;
        });
    await(() -> onMain(() -> !pausedSurface.getHolder().getSurface().isValid()));
    NativeAudioProbe.assertMuted(instrumentation);
    onMain(
        () -> {
          pausedSurface.setVisibility(View.VISIBLE);
          return null;
        });
    await(() -> onMain(() -> page.firstFrameReady && !flag("warmingVlc")));
    assertPlaying(false);
    assertSame("Paused surface recovery retains the native input", source, onMain(() -> page.vlc));
    awaitPicture(pausedAt, false);
    NativeAudioProbe.assertMuted(instrumentation);

    SurfaceView changedTargetSurface =
        (SurfaceView) onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    onMain(
        () -> {
          changedTargetSurface.setVisibility(View.INVISIBLE);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    !changedTargetSurface.getHolder().getSurface().isValid()
                        && !page.firstFrameReady));
    onMain(
        () -> {
          changedTargetSurface.setVisibility(View.VISIBLE);
          // The saved pre-destruction pause target must not override a newer user request while
          // the Surface callback/reconnect is still pending on this same UI turn.
          page.seekTo(18720);
          return null;
        });
    await(() -> onMain(() -> page.firstFrameReady && !flag("warmingVlc")));
    assertPlaying(false);
    assertSame(source, onMain(() -> page.vlc));
    awaitPicture(18720, false);
    NativeAudioProbe.assertMuted(instrumentation);
  }

  private void awaitNativeVolume(int expected) throws Exception {
    try {
      await(
          () ->
              onMain(
                  () ->
                      source.getVolume() == expected
                          && (expected == 0 || (page.firstFrameReady && !flag("warmingVlc")))));
    } catch (AssertionError failure) {
      String state =
          onMain(
              () -> {
                NativeSurfaceFrames frames =
                    (NativeSurfaceFrames) read(page, "nativeSurfaceFrames");
                return "expected="
                    + expected
                    + ", actual="
                    + source.getVolume()
                    + ", active="
                    + page.active
                    + ", play="
                    + page.playRequested
                    + ", audible="
                    + flag("audible")
                    + ", warming="
                    + flag("warmingVlc")
                    + ", scrubbing="
                    + page.isScrubbing()
                    + ", ducked="
                    + flag("nativeDucked")
                    + ", ready="
                    + page.firstFrameReady
                    + ", surfaceReady="
                    + (frames != null && frames.isReady())
                    + ", voutAttached="
                    + source.getVLCVout().areViewsAttached();
              });
      throw new AssertionError("Native volume did not settle: " + state, failure);
    }
  }

  @Test
  public void nativeReplacementReacquiresFocusAndPreservesPausedIntent() throws Exception {
    AudioFocusRequest previousFocus = onMain(() -> (AudioFocusRequest) read(activity, "vlcFocus"));
    assertNotNull(previousFocus);
    replaceNativeOnTheSamePage();
    assertPlaying(true);
    AudioFocusRequest replacementFocus =
        onMain(() -> (AudioFocusRequest) read(activity, "vlcFocus"));
    assertNotNull("A native-to-native fallback owns a new focus request", replacementFocus);
    assertNotSame(previousFocus, replacementFocus);
    onMain(
        () -> {
          // Recheck the real system grant for the production-created replacement request. A
          // second client in this same UID does not reliably cause an OEM focus-loss callback;
          // this regression establishes ownership/handoff, not external interruption delivery.
          Method request = PlayerActivity.class.getDeclaredMethod("requestVlcFocus");
          request.setAccessible(true);
          assertTrue((Boolean) request.invoke(activity));
          assertSame(replacementFocus, read(activity, "vlcFocus"));
          return null;
        });
    NativeAudioProbe.assertAudible(instrumentation);

    setPlaying(false);
    assertPlaying(false);
    replaceNativeOnTheSamePage();
    assertPlaying(false);
    onMain(
        () -> {
          assertFalse((Boolean) read(activity, "shouldPlay"));
          assertNull(
              "A paused replacement must not request audio focus", read(activity, "vlcFocus"));
          assertEquals(0, source.getVolume());
          return null;
        });
    NativeAudioProbe.assertMuted(instrumentation);
  }

  private void replaceNativeOnTheSamePage() throws Exception {
    VideoPage samePage = page;
    MediaPlayer previous = source;
    onMain(
        () -> {
          // Exercise the actual native-to-native replacement called by software failure/health
          // fallback. Decoder selection and unrelated preferences stay untouched.
          Method replacement =
              VideoPage.class.getDeclaredMethod("startVlc", long.class, long.class, boolean.class);
          replacement.setAccessible(true);
          replacement.invoke(page, page.generation, page.position(), false);
          source = page.vlc;
          assertNotNull(source);
          assertNotSame(previous, source);
          assertSame(samePage, read(activity, "currentPage"));
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    page.firstFrameReady
                        && !flag("warmingVlc")
                        && source == page.vlc
                        && source == read(activity, "vlc")));
  }

  private long quickWidgetTap(float fraction, boolean playing) throws Exception {
    return onMain(
        () -> {
          SeekBar bar = (SeekBar) read(activity, "seek");
          assertTrue(bar.getWidth() > 0);
          float x =
              bar.getPaddingLeft()
                  + fraction * (bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
          long now = SystemClock.uptimeMillis();
          // Queued DOWN/UP occupy one UI turn, so the pending 50ms intermediate cannot execute.
          // This verifies the exact quick-tap branch, not a hardware-independent latency deadline.
          touch(bar, MotionEvent.ACTION_DOWN, x, now, now);
          assertTrue(flag("scrubbing"));
          assertEquals(
              "Touch-down must not request a native pause for a quick tap",
              playing,
              flag("vlcRunningRequested"));
          assertEquals(playing, flag("playRequested"));
          assertEquals(0, source.getVolume());
          assertEquals(0, (int) (Integer) read(page, "scrubSubmittedCount"));
          long target = (Long) read(activity, "gestureTarget");
          touch(bar, MotionEvent.ACTION_UP, x, now, now + 20);
          assertFalse(flag("scrubbing"));
          assertEquals(
              "Quick release preserves the original native running state",
              playing,
              flag("vlcRunningRequested"));
          assertEquals(playing, flag("playRequested"));
          assertEquals(
              "Only the final seek is needed",
              0,
              (int) (Integer) read(page, "scrubSubmittedCount"));
          assertSame(source, page.vlc);
          return target;
        });
  }

  private void assertPlaying(boolean expected) throws Exception {
    await(
        () ->
            onMain(
                () ->
                    flag("vlcRunningRequested") == expected
                        && flag("playRequested") == expected
                        && source.isPlaying() == expected));
  }

  private void awaitPicture(long targetMs, boolean playing) throws Exception {
    long[] lastFrame = {-1};
    try {
      await(
          () -> {
            long frame = decodedFrameMs();
            lastFrame[0] = frame;
            long difference = frame - targetMs;
            // This state regression permits 200ms (five fixture frames) of paused native preroll:
            // the real VLC paths can land 120-160ms early. It does not establish precise-seek
            // accuracy. The separate calibrated optical benchmark keeps its 120ms threshold and
            // reports signed landing error; its failures must not be relabeled by this allowance.
            return frame >= 0
                && difference >= (playing ? -100 : -200)
                && difference <= (playing ? 1500 : 200);
          });
    } catch (AssertionError failure) {
      throw new AssertionError(
          "Native rendered target not verified: requested="
              + targetMs
              + ", lastDecoded="
              + lastFrame[0]
              + ", error="
              + (lastFrame[0] - targetMs)
              + ", playing="
              + playing,
          failure);
    }
  }

  private long decodedFrameMs() throws Exception {
    View surface = onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    if (surface == null) return -1;
    // PixelCopy waits on the instrumentation thread; its callback can safely run on the UI thread.
    Bitmap bitmap = NativeFrameProbe.captureSurface(instrumentation, surface);
    try {
      OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(bitmap);
      return code == null ? -1 : code.frame * 40L;
    } finally {
      bitmap.recycle();
    }
  }

  private void setPlaying(boolean playing) throws Exception {
    onMain(
        () -> {
          Method setter = PlayerActivity.class.getDeclaredMethod("setPlaying", boolean.class);
          setter.setAccessible(true);
          setter.invoke(activity, playing);
          return null;
        });
  }

  private boolean flag(String name) throws Exception {
    return (Boolean) read(page, name);
  }

  private static Object read(Object instance, String name) throws Exception {
    Field field = instance.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  private static void touch(SeekBar bar, int action, float x, long down, long when) {
    MotionEvent event = MotionEvent.obtain(down, when, action, x, bar.getHeight() * .5f, 0);
    try {
      assertTrue(bar.dispatchTouchEvent(event));
    } finally {
      event.recycle();
    }
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
      assertEquals("Install exactly one generated B2 H.264720 fixture", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      Uri uri =
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
      return new VideoItem(
          "native-seek-" + SystemClock.elapsedRealtimeNanos(),
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
    long deadline = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(30);
    }
    fail("Native state/frame did not settle; this is a hang watchdog, not a speed threshold");
  }
}
