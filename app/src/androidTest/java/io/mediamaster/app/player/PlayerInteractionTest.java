package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the actual transparent controls and mutually exclusive pointer gestures. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class PlayerInteractionTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private Instrumentation instrumentation;
  private PlayerActivity activity;
  private View touch;
  private long downTime;
  private int savedMode;
  private Context context;

  @Before
  public void launch() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    savedMode = context.getSharedPreferences("player", 0).getInt("playback_mode", 0);
    context
        .getSharedPreferences("player", 0)
        .edit()
        .putInt("playback_mode", PlaybackOrder.SINGLE)
        .commit();
    Uri fixture = null;
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id"},
                "_display_name=?",
                new String[] {"04_Portrait_Loop.mp4"},
                null)) {
      if (c != null && c.moveToFirst())
        fixture = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getString(0));
    }
    assertNotNull("Generated portrait fixture installed", fixture);
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(
        new VideoItem(
            "gesture-fixture",
            "04_Portrait_Loop.mp4",
            fixture.toString(),
            "",
            0,
            6000,
            0,
            0,
            360,
            640));
    String token = QueueStore.put(context, queue);
    activity =
        rule.launchActivity(new Intent(context, PlayerActivity.class).putExtra("queue", token));
    await(
        () -> onMain(() -> ((ExoPlayer) read("exo")).getPlaybackState() == Player.STATE_READY),
        15000);
    touch = onMain(() -> (View) read("touchLayer"));
  }

  @After
  public void finish() {
    if (activity != null) rule.finishActivity();
    if (context != null)
      context.getSharedPreferences("player", 0).edit().putInt("playback_mode", savedMode).commit();
  }

  @Test
  public void longPressTemporarilyDoublesSpeedThenRestoresAndCancelDoesNotLeaveItBoosted()
      throws Exception {
    onMain(
        () -> {
          Field f = PlayerActivity.class.getDeclaredField("speed");
          f.setAccessible(true);
          f.setFloat(activity, 1.5f);
          ((ExoPlayer) read("exo")).setPlaybackSpeed(1.5f);
          return null;
        });
    float x = touch.getWidth() * .5f, y = touch.getHeight() * .4f;
    send(MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180);
    onMain(
        () -> {
          assertTrue((Boolean) read("boosting"));
          assertEquals(2f, ((ExoPlayer) read("exo")).getPlaybackParameters().speed, 0);
          return null;
        });
    send(MotionEvent.ACTION_UP, x, y);
    onMain(
        () -> {
          assertFalse((Boolean) read("boosting"));
          assertEquals(1.5f, ((ExoPlayer) read("exo")).getPlaybackParameters().speed, 0);
          return null;
        });
    send(MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 120);
    send(MotionEvent.ACTION_CANCEL, x, y);
    onMain(
        () -> {
          assertFalse((Boolean) read("boosting"));
          assertEquals(1.5f, ((ExoPlayer) read("exo")).getPlaybackParameters().speed, 0);
          return null;
        });
  }

  @Test
  public void leftDragChangesOnlyWindowBrightnessLinearlyAndRightDragChangesMusicVolume()
      throws Exception {
    AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    int originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    int systemBrightness =
        Settings.System.getInt(
            context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
    try {
      onMain(
          () -> {
            WindowManager.LayoutParams a = activity.getWindow().getAttributes();
            a.screenBrightness = .35f;
            activity.getWindow().setAttributes(a);
            return null;
          });
      float h = touch.getHeight(), w = touch.getWidth();
      send(MotionEvent.ACTION_DOWN, w * .25f, h * .65f);
      send(MotionEvent.ACTION_MOVE, w * .25f, h * .40f);
      send(MotionEvent.ACTION_UP, w * .25f, h * .40f);
      onMain(
          () -> {
            assertEquals(.60f, activity.getWindow().getAttributes().screenBrightness, .015f);
            return null;
          });
      assertEquals(
          "Brightness is local to player window",
          systemBrightness,
          Settings.System.getInt(
              context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128));
      assertEquals(
          "Left gesture never changes volume",
          originalVolume,
          audio.getStreamVolume(AudioManager.STREAM_MUSIC));
      int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      int initial = Math.max(1, max / 3);
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, initial, 0);
      send(MotionEvent.ACTION_DOWN, w * .75f, h * .65f);
      send(MotionEvent.ACTION_MOVE, w * .75f, h * .45f);
      send(MotionEvent.ACTION_UP, w * .75f, h * .45f);
      assertEquals(
          Math.round(Math.min(1f, initial / (float) max + .2f) * max),
          audio.getStreamVolume(AudioManager.STREAM_MUSIC));
      onMain(
          () -> {
            assertEquals(
                "Right gesture never changes brightness",
                .60f,
                activity.getWindow().getAttributes().screenBrightness,
                .015f);
            return null;
          });
    } finally {
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
    }
  }

  @Test
  public void smallScreenMovesContinuouslyAdvanceAndReverseTheShortVideoScrubber()
      throws Exception {
    onMain(
        () -> {
          ExoPlayer p = (ExoPlayer) read("exo");
          p.pause();
          p.seekTo(1000);
          return null;
        });
    SystemClock.sleep(200);
    float density = context.getResources().getDisplayMetrics().density;
    float start = touch.getWidth() * .35f, y = touch.getHeight() * .4f;
    send(MotionEvent.ACTION_DOWN, start, y);
    int previous = -1;
    int distinct = 0;
    for (int index = 0; index < 40; index++) {
      send(MotionEvent.ACTION_MOVE, start + (20 + index * .5f) * density, y);
      int progress = onMain(() -> ((SeekBar) read("seek")).getProgress());
      if (previous >= 0) {
        assertTrue("Each half-dp finger movement advances the thumb", progress > previous);
        assertTrue("A short clip must not jump a whole-second segment", progress - previous < 100);
        distinct++;
      }
      previous = progress;
      SystemClock.sleep(16);
    }
    assertEquals(39, distinct);
    // More than two progress ticker intervals passed; playback itself must still be uncommitted.
    assertEquals(
        1000, onMain(() -> ((ExoPlayer) read("exo")).getCurrentPosition()).longValue(), 100);
    for (int index = 38; index >= 0; index--) {
      send(MotionEvent.ACTION_MOVE, start + (20 + index * .5f) * density, y);
      int progress = onMain(() -> ((SeekBar) read("seek")).getProgress());
      assertTrue("Reversing also follows every move", progress < previous);
      assertTrue(previous - progress < 100);
      previous = progress;
      SystemClock.sleep(16);
    }
    long target = onMain(() -> (Long) read("gestureTarget"));
    send(MotionEvent.ACTION_UP, start + 20 * density, y);
    assertEquals(
        target, onMain(() -> ((ExoPlayer) read("exo")).getCurrentPosition()).longValue(), 100);
  }

  @Test
  public void horizontalDragShowsActualFrameWithoutCommittingUntilRelease() throws Exception {
    onMain(
        () -> {
          ExoPlayer player = (ExoPlayer) read("exo");
          player.pause();
          player.seekTo(1000);
          return null;
        });
    SystemClock.sleep(250);
    float w = touch.getWidth(), h = touch.getHeight();
    float start = w * .35f;
    send(MotionEvent.ACTION_DOWN, start, h * .45f);
    send(MotionEvent.ACTION_MOVE, start + w * .18f, h * .45f);
    await(() -> onMain(() -> ((ImageView) read("previewImage")).getDrawable() != null), 8000);
    onMain(
        () -> {
          assertTrue((Boolean) read("gestureSeeking"));
          assertEquals(View.VISIBLE, ((View) read("seekPreview")).getVisibility());
          assertEquals(View.VISIBLE, ((View) read("bottomBar")).getVisibility());
          long target = (Long) read("gestureTarget");
          assertEquals((int) (target * 10000 / 6000), ((SeekBar) read("seek")).getProgress());
          assertEquals(
              "Video position remains unchanged while dragging",
              1000,
              ((ExoPlayer) read("exo")).getCurrentPosition(),
              150);
          assertTrue(((TextView) read("previewTime")).getText().toString().contains("/"));
          return null;
        });
    long target = onMain(() -> (Long) read("gestureTarget"));
    send(MotionEvent.ACTION_UP, start + w * .18f, h * .45f);
    onMain(
        () -> {
          assertEquals(target, ((ExoPlayer) read("exo")).getCurrentPosition(), 250);
          assertEquals(View.GONE, ((View) read("seekPreview")).getVisibility());
          assertScreenSeekFinishedAndChromeHidden();
          return null;
        });
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 80);
    onMain(
        () -> {
          assertFalse((Boolean) read("boosting"));
          assertScreenSeekFinishedAndChromeHidden();
          assertFalse(
              "A paused swipe must not start playback",
              ((ExoPlayer) read("exo")).getPlayWhenReady());
          return null;
        });
  }

  @Test
  public void playingHorizontalSeekHidesControlsImmediatelyAndLateUpdatesDoNotReopenThem()
      throws Exception {
    float width = touch.getWidth(), y = touch.getHeight() * .45f, x = width * .4f;
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_MOVE, x + width * .08f, y);
    onMain(
        () -> {
          assertTrue((Boolean) read("gestureSeeking"));
          assertEquals(View.VISIBLE, ((View) read("bottomBar")).getVisibility());
          assertEquals(View.VISIBLE, ((View) read("seekPreview")).getVisibility());
          return null;
        });
    send(MotionEvent.ACTION_UP, x + width * .08f, y);
    onMain(
        () -> {
          assertScreenSeekFinishedAndChromeHidden();
          assertTrue(
              "Screen seeking retains the playing state",
              ((ExoPlayer) read("exo")).getPlayWhenReady());
          return null;
        });
    // Let seek completion, preview extraction and the progress ticker deliver their callbacks.
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150);
    onMain(
        () -> {
          assertScreenSeekFinishedAndChromeHidden();
          return null;
        });
  }

  @Test
  public void cancelledHorizontalSeekHidesControlsWithoutSeekingAndTheNextTapStillWorks()
      throws Exception {
    onMain(
        () -> {
          ExoPlayer player = (ExoPlayer) read("exo");
          player.pause();
          player.seekTo(1000);
          return null;
        });
    SystemClock.sleep(180);
    float width = touch.getWidth(), x = width * .35f, y = touch.getHeight() * .45f;
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_MOVE, x + width * .18f, y);
    assertEquals(View.VISIBLE, onMain(() -> ((View) read("bottomBar")).getVisibility()).intValue());
    send(MotionEvent.ACTION_CANCEL, x + width * .18f, y);
    onMain(
        () -> {
          assertScreenSeekFinishedAndChromeHidden();
          assertEquals(
              "Cancellation does not commit the preview",
              1000,
              ((ExoPlayer) read("exo")).getCurrentPosition(),
              150);
          assertFalse(((ExoPlayer) read("exo")).getPlayWhenReady());
          return null;
        });
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 80);
    onMain(
        () -> {
          assertScreenSeekFinishedAndChromeHidden();
          assertFalse((Boolean) read("boosting"));
          return null;
        });
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_UP, x, y);
    await(() -> onMain(() -> ((View) read("bottomBar")).getVisibility() == View.VISIBLE), 1200);
  }

  private void assertScreenSeekFinishedAndChromeHidden() throws Exception {
    assertFalse((Boolean) read("touchDown"));
    assertFalse((Boolean) read("gestureSeeking"));
    assertEquals(0, ((Integer) read("gestureKind")).intValue());
    assertFalse((Boolean) read("chromeVisible"));
    assertEquals(View.INVISIBLE, ((View) read("topBar")).getVisibility());
    assertEquals(View.INVISIBLE, ((View) read("bottomBar")).getVisibility());
    assertEquals(View.GONE, ((View) read("seekPreview")).getVisibility());
    assertNull(
        "A late preview callback cannot restore the image",
        ((ImageView) read("previewImage")).getDrawable());
  }

  @Test
  public void seekBarUsesSamePicturePreviewAndPlayerChromeHasNoOpaquePanelsOrHelpLabels()
      throws Exception {
    onMain(
        () -> {
          ExoPlayer player = (ExoPlayer) read("exo");
          player.pause();
          assertNull(((View) read("topBar")).getBackground());
          assertNull(((View) read("bottomBar")).getBackground());
          assertEquals("1 / 1", ((TextView) read("subtitle")).getText().toString());
          LinearLayout bottom = (LinearLayout) read("bottomBar");
          assertEquals("Only times, seek bar and controls remain", 3, bottom.getChildCount());
          return null;
        });
    SeekBar bar = onMain(() -> (SeekBar) read("seek"));
    onMain(
        () -> {
          long now = SystemClock.uptimeMillis();
          MotionEvent down =
              MotionEvent.obtain(
                  now,
                  now,
                  MotionEvent.ACTION_DOWN,
                  bar.getWidth() * .3f,
                  bar.getHeight() * .5f,
                  0);
          bar.dispatchTouchEvent(down);
          down.recycle();
          MotionEvent move =
              MotionEvent.obtain(
                  now,
                  now + 40,
                  MotionEvent.ACTION_MOVE,
                  bar.getWidth() * .65f,
                  bar.getHeight() * .5f,
                  0);
          bar.dispatchTouchEvent(move);
          move.recycle();
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    ((View) read("seekPreview")).getVisibility() == View.VISIBLE
                        && ((ImageView) read("previewImage")).getDrawable() != null),
        8000);
    onMain(
        () -> {
          long now = SystemClock.uptimeMillis();
          MotionEvent up =
              MotionEvent.obtain(
                  now - 50,
                  now,
                  MotionEvent.ACTION_UP,
                  bar.getWidth() * .65f,
                  bar.getHeight() * .5f,
                  0);
          bar.dispatchTouchEvent(up);
          up.recycle();
          assertEquals(View.GONE, ((View) read("seekPreview")).getVisibility());
          assertEquals(
              "SeekBar dragging keeps the controls visible",
              View.VISIBLE,
              ((View) read("bottomBar")).getVisibility());
          return null;
        });
  }

  @Test
  public void loadingWaitsASecondAndRepeatedBufferSignalsDoNotRestartTheDeadline()
      throws Exception {
    onMain(
        () -> {
          ((ExoPlayer) read("exo")).pause();
          loadingSignal(false);
          loadingSignal(true);
          return null;
        });
    SystemClock.sleep(350);
    assertEquals(View.GONE, onMain(() -> ((View) read("loading")).getVisibility()).intValue());
    onMain(
        () -> {
          loadingSignal(false);
          return null;
        });
    SystemClock.sleep(800);
    assertEquals(
        "A finished short stall must never flash a stale spinner",
        View.GONE,
        onMain(() -> ((View) read("loading")).getVisibility()).intValue());
    onMain(
        () -> {
          loadingSignal(true);
          return null;
        });
    SystemClock.sleep(700);
    onMain(
        () -> {
          loadingSignal(true);
          return null;
        });
    SystemClock.sleep(450);
    assertEquals(
        "Repeated VLC buffering events share one continuous waiting deadline",
        View.VISIBLE,
        onMain(() -> ((View) read("loading")).getVisibility()).intValue());
    onMain(
        () -> {
          loadingSignal(false);
          return null;
        });
    assertEquals(View.GONE, onMain(() -> ((View) read("loading")).getVisibility()).intValue());
  }

  @Test
  public void heldScreenSeekKeepsTheTimelineVisibleAndReversesTheCharacter() throws Exception {
    onMain(
        () -> {
          ExoPlayer p = (ExoPlayer) read("exo");
          p.pause();
          p.seekTo(1000);
          return null;
        });
    SystemClock.sleep(200);
    float w = touch.getWidth(), y = touch.getHeight() * .4f, x = w * .35f;
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_MOVE, x + w * .18f, y);
    assertTrue(
        onMain(() -> ((DirectionalThumbDrawable) ((SeekBar) read("seek")).getThumb()).isForward()));
    SystemClock.sleep(3800);
    assertEquals(
        "Auto-hide must wait until seeking finishes",
        View.VISIBLE,
        onMain(() -> ((View) read("bottomBar")).getVisibility()).intValue());
    send(MotionEvent.ACTION_MOVE, x + w * .05f, y);
    assertFalse(
        onMain(() -> ((DirectionalThumbDrawable) ((SeekBar) read("seek")).getThumb()).isForward()));
    onMain(
        () -> {
          loadingSignal(true);
          return null;
        });
    SystemClock.sleep(1100);
    assertEquals(
        "A preview gesture itself does not show a loading overlay",
        View.GONE,
        onMain(() -> ((View) read("loading")).getVisibility()).intValue());
    send(MotionEvent.ACTION_UP, x + w * .05f, y);
    onMain(
        () -> {
          loadingSignal(false);
          return null;
        });
  }

  private void loadingSignal(boolean buffering) throws Exception {
    Method m = PlayerActivity.class.getDeclaredMethod("showLoading", boolean.class, String.class);
    m.setAccessible(true);
    m.invoke(activity, buffering, "");
  }

  @Test
  public void secondPointerCancelsGestureUntilACompletelyNewTouchBegins() throws Exception {
    float w = touch.getWidth(), h = touch.getHeight();
    send(MotionEvent.ACTION_DOWN, w * .5f, h * .4f);
    send(MotionEvent.ACTION_POINTER_DOWN, w * .5f, h * .4f);
    send(MotionEvent.ACTION_MOVE, w * .8f, h * .4f);
    send(MotionEvent.ACTION_UP, w * .8f, h * .4f);
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
    onMain(
        () -> {
          assertFalse((Boolean) read("touchDown"));
          assertFalse((Boolean) read("gestureSeeking"));
          assertFalse((Boolean) read("boosting"));
          assertEquals(View.GONE, ((View) read("seekPreview")).getVisibility());
          return null;
        });
  }

  private void send(int action, float x, float y) throws Exception {
    onMain(
        () -> {
          long now = SystemClock.uptimeMillis();
          if (action == MotionEvent.ACTION_DOWN) downTime = now;
          MotionEvent e = MotionEvent.obtain(downTime, now, action, x, y, 0);
          touch.dispatchTouchEvent(e);
          e.recycle();
          return null;
        });
  }

  private Object read(String name) throws Exception {
    Field f = PlayerActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  private <T> T onMain(Callable<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            result.set(task.call());
          } catch (Throwable e) {
            failure.set(e);
          }
        });
    Throwable e = failure.get();
    if (e instanceof AssertionError) throw (AssertionError) e;
    if (e instanceof Exception) throw (Exception) e;
    if (e != null) throw new RuntimeException(e);
    return result.get();
  }

  private void await(Callable<Boolean> condition, long timeout) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(40);
    }
    fail("Condition did not become true within " + timeout + " ms");
  }
}
