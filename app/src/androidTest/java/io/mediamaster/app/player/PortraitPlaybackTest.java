package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Real player, codec and pointer coverage for the separate portrait browsing presentation. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class PortraitPlaybackTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private PlayerActivity activity;
  private ArrayList<VideoItem> fixtures;
  private long downTime;
  private int savedMode;

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
    fixtures = new ArrayList<>();
    for (String name :
        new String[] {"01_Aurora.mp4", "02_Horizon.mkv", "03_Evening.avi", "04_Portrait_Loop.mp4"})
      fixtures.add(findFixture(name));
    launchFreshQueue();
  }

  @After
  public void finish() throws Exception {
    if (activity != null && !activity.isDestroyed()) {
      onMain(
          () -> {
            activity.finish();
            return null;
          });
    }
    rule.finishActivity();
    if (context != null)
      context.getSharedPreferences("player", 0).edit().putInt("playback_mode", savedMode).commit();
  }

  @Test
  public void eachNewCatalogEntryStartsInOriginalFullPresentation() throws Exception {
    assertFalse(onMain(() -> (Boolean) read("portraitMode")));
    assertTrue(onMain(() -> ((ImageButton) read("portraitButton")).isShown()));
    setPortrait(true);
    assertTrue(onMain(() -> (Boolean) read("portraitMode")));
    rule.finishActivity();
    launchFreshQueue();
    assertFalse("Presentation is session state, not a sticky preference", portrait());
    assertEquals(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        onMain(() -> activity.getRequestedOrientation()).intValue());
  }

  @Test
  public void modeButtonsPreservePipelinePositionPauseSpeedOrderAndManualOrientation()
      throws Exception {
    onMain(
        () -> {
          setField("orientationOverride", true);
          setField("desiredOrientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
          activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
          setField("speed", 1.5f);
          ExoPlayer player = (ExoPlayer) read("exo");
          player.setPlaybackSpeed(1.5f);
          player.seekTo(1400);
          return null;
        });
    cycleTo(PlaybackOrder.SHUFFLE);
    awaitReady();
    ExoPlayer pipeline = onMain(() -> (ExoPlayer) read("exo"));
    PlaybackOrder originalOrder = onMain(() -> (PlaybackOrder) read("order"));
    onMain(
        () -> {
          ((ImageButton) read("portraitButton")).performClick();
          return null;
        });
    await(() -> portrait() && isPortraitConfiguration(), 5000);
    assertSessionPreserved(pipeline, originalOrder, 1400, 1.5f, PlaybackOrder.SHUFFLE);
    onMain(
        () -> {
          ((ImageButton) read("portraitButton")).performClick();
          return null;
        });
    await(() -> !portrait() && !isPortraitConfiguration(), 5000);
    assertSessionPreserved(pipeline, originalOrder, 1400, 1.5f, PlaybackOrder.SHUFFLE);
    assertTrue(onMain(() -> (Boolean) read("orientationOverride")));
    assertEquals(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        onMain(() -> activity.getRequestedOrientation()).intValue());
  }

  @Test
  public void vlcPresentationRoundTripPreservesNativePlayerAndManualPagingStillWorks()
      throws Exception {
    onMain(
        () -> {
          setField("speed", 1.5f);
          setField("shouldPlay", true);
          invoke("startVlc", new Class<?>[] {long.class}, 1200L);
          return null;
        });
    MediaPlayer compatibilityPlayer = onMain(() -> (MediaPlayer) read("vlc"));
    assertNotNull("The compatibility engine was created", compatibilityPlayer);
    await(
        () ->
            onMain(
                () ->
                    compatibilityPlayer.isPlaying()
                        && compatibilityPlayer.getTime() >= 1600
                        && compatibilityPlayer.getLength() > 0
                        && compatibilityPlayer.getCurrentVideoTrack() != null),
        15000);
    onMain(
        () -> {
          assertNull("The native compatibility engine owns playback", read("exo"));
          assertTrue((Boolean) read("useVlc"));
          invoke("setPlaying", new Class<?>[] {boolean.class}, false);
          return null;
        });
    await(() -> onMain(() -> !compatibilityPlayer.isPlaying()), 5000);
    SystemClock.sleep(200);
    long pausedPosition = onMain(compatibilityPlayer::getTime);
    assertTrue(
        "The compatibility decoder played beyond the requested resume point",
        pausedPosition >= 1600);
    PlaybackOrder originalOrder = onMain(() -> (PlaybackOrder) read("order"));
    for (boolean portrait : new boolean[] {true, false, true}) {
      setPortrait(portrait);
      onMain(
          () -> {
            assertSame(
                "Presentation switching keeps the native VLC instance",
                compatibilityPlayer,
                read("vlc"));
            assertSame("The compatibility queue and history survive", originalOrder, read("order"));
            assertNull(read("exo"));
            assertEquals(pausedPosition, compatibilityPlayer.getTime(), 180);
            assertFalse(compatibilityPlayer.isPlaying());
            assertFalse((Boolean) read("shouldPlay"));
            assertEquals(1.5f, compatibilityPlayer.getRate(), .01f);
            assertEquals(PlaybackOrder.SINGLE, originalOrder.mode());
            assertEquals(0, originalOrder.current());
            return null;
          });
    }
    page(true, 1);
    assertEquals(
        "Manual portrait paging overrides single-repeat without changing the policy",
        PlaybackOrder.SINGLE,
        onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
    assertEquals(1.5f, onMain(() -> (Float) read("speed")), 0);
  }

  @Test
  public void sequentialSwipesUseCatalogOrderAndKeepBothAspectRatiosInPortrait() throws Exception {
    cycleTo(PlaybackOrder.SEQUENTIAL);
    setPortrait(true);
    page(false, 3); // Previous wraps from the first landscape video to the last portrait video.
    page(true, 0);
    page(true, 1);
    page(false, 0);
    assertTrue(portrait());
    assertTrue(isPortraitConfiguration());
  }

  @Test
  public void shuffleSwipesUseRandomDeckAndActualHistoryEvenAfterPresentationRoundTrip()
      throws Exception {
    cycleTo(PlaybackOrder.SHUFFLE);
    setPortrait(true);
    PlaybackOrder originalOrder = onMain(() -> (PlaybackOrder) read("order"));
    swipePage(true);
    await(() -> currentIndex() != 0, 5000);
    pauseAndAwaitReady();
    int randomVideo = currentIndex();
    assertTrue(randomVideo >= 1 && randomVideo < fixtures.size());
    onMain(
        () -> {
          invoke("setPlaybackMode", new Class<?>[] {int.class}, PlaybackOrder.SHUFFLE);
          assertSame(
              "Selecting the current mode preserves the random history",
              originalOrder,
              read("order"));
          assertEquals(randomVideo, ((PlaybackOrder) read("order")).current());
          return null;
        });
    setPortrait(false);
    setPortrait(true);
    assertSame(originalOrder, onMain(() -> (PlaybackOrder) read("order")));
    page(false, 0);
    page(true, randomVideo);
    assertEquals(
        PlaybackOrder.SHUFFLE, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
  }

  @Test
  public void singleRepeatKeepsDecoderAndEpisodeUntilAnExplicitUpSwipe() throws Exception {
    setPortrait(true);
    AtomicInteger loops = new AtomicInteger();
    ExoPlayer pipeline = onMain(() -> (ExoPlayer) read("exo"));
    onMain(
        () -> {
          pipeline.addListener(
              new Player.Listener() {
                @Override
                public void onMediaItemTransition(MediaItem item, int reason) {
                  if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) loops.incrementAndGet();
                }
              });
          assertEquals(Player.REPEAT_MODE_ONE, pipeline.getRepeatMode());
          pipeline.seekTo(Math.max(0, pipeline.getDuration() - 650));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(() -> loops.get() > 0, 8000);
    assertEquals(0, currentIndex());
    assertSame(pipeline, onMain(() -> (ExoPlayer) read("exo")));
    page(true, 1);
    assertEquals(
        PlaybackOrder.SINGLE, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
    assertEquals(
        Player.REPEAT_MODE_ONE, onMain(() -> ((ExoPlayer) read("exo")).getRepeatMode()).intValue());
  }

  @Test
  public void sequentialAndShuffleNaturalEndsContinueInPortrait() throws Exception {
    cycleTo(PlaybackOrder.SEQUENTIAL);
    setPortrait(true);
    onMain(
        () -> {
          ExoPlayer player = (ExoPlayer) read("exo");
          player.seekTo(Math.max(0, player.getDuration() - 550));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(() -> currentIndex() == 1, 10000);
    pauseAndAwaitReady();
    assertTrue(portrait());
    assertTrue(isPortraitConfiguration());
    assertEquals(
        PlaybackOrder.SEQUENTIAL, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
    cycleTo(PlaybackOrder.SHUFFLE);
    onMain(
        () -> {
          ExoPlayer player = (ExoPlayer) read("exo");
          assertNotNull("Generated MKV remains on the Exo pipeline", player);
          player.seekTo(Math.max(0, player.getDuration() - 550));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(() -> currentIndex() != 1, 10000);
    pauseAndAwaitReady();
    assertTrue(portrait());
    assertTrue(isPortraitConfiguration());
    assertEquals(
        PlaybackOrder.SHUFFLE, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
  }

  @Test
  public void portraitEndDuringHeldVerticalGestureDoesNotSkipTwoEpisodes() throws Exception {
    cycleTo(PlaybackOrder.SEQUENTIAL);
    setPortrait(true);
    ExoPlayer source = onMain(() -> (ExoPlayer) read("exo"));
    View surface = touch();
    float x = surface.getWidth() * .5f;
    float startY = surface.getHeight() * .65f;
    float endY = startY - surface.getHeight() * .25f;
    onMain(
        () -> {
          source.seekTo(Math.max(0, source.getDuration() - 650));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    send(MotionEvent.ACTION_DOWN, x, startY);
    send(MotionEvent.ACTION_MOVE, x, endY);
    SystemClock.sleep(1100);
    await(
        () ->
            onMain(
                () -> source.getPlaybackState() == Player.STATE_ENDED || currentIndexOnMain() != 0),
        8000);
    assertEquals("Actual EOF waits for the held paging gesture", 0, currentIndex());
    assertEquals(Player.STATE_ENDED, onMain(source::getPlaybackState).intValue());
    assertTrue(onMain(() -> (Boolean) read("touchDown")));
    // Allow another deferred automatic-advance deadline to pass before completing the same gesture.
    SystemClock.sleep(250);
    assertEquals(0, currentIndex());
    send(MotionEvent.ACTION_UP, x, endY);
    await(() -> currentIndex() == 1, 5000);
    pauseAndAwaitReady();
    SystemClock.sleep(500);
    assertEquals(
        "The old EOF callback must not skip the freshly selected video", 1, currentIndex());
    assertTrue(portrait());
    assertTrue(isPortraitConfiguration());
  }

  @Test
  public void horizontalPortraitDragStillTracksEverySmallMoveWithoutChangingEpisode()
      throws Exception {
    setPortrait(true);
    onMain(
        () -> {
          ((ExoPlayer) read("exo")).seekTo(1000);
          return null;
        });
    awaitReady();
    View surface = touch();
    float density = context.getResources().getDisplayMetrics().density;
    float x = surface.getWidth() * .32f, y = surface.getHeight() * .45f;
    send(MotionEvent.ACTION_DOWN, x, y);
    int previous = -1;
    for (int i = 0; i < 24; i++) {
      send(MotionEvent.ACTION_MOVE, x + (20 + i * .5f) * density, y);
      int progress = onMain(() -> ((SeekBar) read("seek")).getProgress());
      if (previous >= 0) {
        assertTrue("Every half-dp movement advances the scrubber", progress > previous);
        assertTrue(
            "Short video progress never jumps a whole-second segment", progress - previous < 100);
      }
      previous = progress;
      assertEquals(0, currentIndex());
      SystemClock.sleep(16);
    }
    long target = onMain(() -> (Long) read("gestureTarget"));
    assertEquals(
        1000, onMain(() -> ((ExoPlayer) read("exo")).getCurrentPosition()).longValue(), 100);
    assertEquals(View.VISIBLE, onMain(() -> ((View) read("bottomBar")).getVisibility()).intValue());
    send(MotionEvent.ACTION_UP, x + 31.5f * density, y);
    assertEquals(
        target, onMain(() -> ((ExoPlayer) read("exo")).getCurrentPosition()).longValue(), 120);
    assertTrue(portrait());
    assertEquals(0, currentIndex());
  }

  @Test
  public void portraitVerticalSwipesDoNotChangeLevelsAndFullModeRetainsLevelGestures()
      throws Exception {
    AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    int originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    try {
      onMain(
          () -> {
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            attributes.screenBrightness = .4f;
            activity.getWindow().setAttributes(attributes);
            return null;
          });
      cycleTo(PlaybackOrder.SEQUENTIAL);
      setPortrait(true);
      page(true, 1);
      assertEquals(originalVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
      assertEquals(.4f, onMain(() -> activity.getWindow().getAttributes().screenBrightness), .01f);
      setPortrait(false);
      View surface = touch();
      float width = surface.getWidth(), height = surface.getHeight();
      swipe(width * .25f, height * .65f, width * .25f, height * .40f);
      assertEquals(
          .65f, onMain(() -> activity.getWindow().getAttributes().screenBrightness), .015f);
      int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      int initial = Math.max(1, max / 3);
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, initial, 0);
      swipe(width * .75f, height * .65f, width * .75f, height * .45f);
      assertEquals(
          Math.round(Math.min(1f, initial / (float) max + .2f) * max),
          audio.getStreamVolume(AudioManager.STREAM_MUSIC));
      assertEquals("Level gestures must not page in full mode", 1, currentIndex());
    } finally {
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
    }
  }

  @Test
  public void shortDiagonalCanceledAndMultitouchGesturesNeverChangeEpisode() throws Exception {
    setPortrait(true);
    View surface = touch();
    float density = context.getResources().getDisplayMetrics().density;
    float x = surface.getWidth() * .5f, y = surface.getHeight() * .6f;
    swipe(x, y, x, y - 24 * density);
    SystemClock.sleep(450);
    assertEquals(0, currentIndex());
    swipe(x, y, x + 65 * density, y - 65 * density);
    SystemClock.sleep(450);
    assertEquals(0, currentIndex());
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_MOVE, x, y - surface.getHeight() * .3f);
    send(MotionEvent.ACTION_CANCEL, x, y - surface.getHeight() * .3f);
    SystemClock.sleep(450);
    assertEquals(0, currentIndex());
    send(MotionEvent.ACTION_DOWN, x, y);
    sendSecondPointer(x, y);
    send(MotionEvent.ACTION_MOVE, x, y - surface.getHeight() * .3f);
    send(MotionEvent.ACTION_UP, x, y - surface.getHeight() * .3f);
    SystemClock.sleep(450);
    assertEquals(0, currentIndex());
    assertFalse(onMain(() -> (Boolean) read("touchDown")));
    assertFalse(onMain(() -> (Boolean) read("pageTransitioning")));
  }

  @Test
  public void portraitModeButtonCyclesAllPlaybackPoliciesWithoutReplacingDecoder()
      throws Exception {
    setPortrait(true);
    ExoPlayer pipeline = onMain(() -> (ExoPlayer) read("exo"));
    for (int mode :
        new int[] {PlaybackOrder.SHUFFLE, PlaybackOrder.SEQUENTIAL, PlaybackOrder.SINGLE}) {
      onMain(
          () -> {
            ImageButton button = (ImageButton) read("modeButton");
            assertTrue(button.isShown());
            assertTrue(button.performClick());
            assertEquals(mode, ((PlaybackOrder) read("order")).mode());
            assertEquals(
                mode == PlaybackOrder.SINGLE ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF,
                pipeline.getRepeatMode());
            return null;
          });
      assertSame(pipeline, onMain(() -> (ExoPlayer) read("exo")));
      assertTrue(portrait());
    }
  }

  @Test
  public void recreationRestoresPortraitPresentationQueuePositionSpeedAndPlaybackPolicy()
      throws Exception {
    setPortrait(true);
    cycleTo(PlaybackOrder.SHUFFLE);
    onMain(
        () -> {
          setField("speed", 1.25f);
          ExoPlayer player = (ExoPlayer) read("exo");
          player.setPlaybackSpeed(1.25f);
          player.seekTo(1700);
          return null;
        });
    awaitReady();
    PlayerActivity oldActivity = activity;
    AtomicReference<PlayerActivity> recreated = new AtomicReference<>();
    ActivityLifecycleCallback callback =
        (Activity candidate, Stage stage) -> {
          if (candidate instanceof PlayerActivity
              && candidate != oldActivity
              && stage == Stage.RESUMED) recreated.set((PlayerActivity) candidate);
        };
    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
    try {
      onMain(
          () -> {
            oldActivity.recreate();
            return null;
          });
      await(() -> recreated.get() != null, 10000);
      activity = recreated.get();
      awaitReady();
      assertTrue(portrait());
      assertTrue(isPortraitConfiguration());
      assertEquals(0, currentIndex());
      assertEquals(
          PlaybackOrder.SHUFFLE, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
      assertEquals(1.25f, onMain(() -> ((ExoPlayer) read("exo")).getPlaybackParameters().speed), 0);
      assertEquals(
          1700, onMain(() -> ((ExoPlayer) read("exo")).getCurrentPosition()).longValue(), 150);
      assertFalse(onMain(() -> ((ExoPlayer) read("exo")).getPlayWhenReady()));
    } finally {
      ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
    }
  }

  private void launchFreshQueue() throws Exception {
    String token = QueueStore.put(context, fixtures);
    activity =
        rule.launchActivity(new Intent(context, PlayerActivity.class).putExtra("queue", token));
    awaitReady();
    pauseAndAwaitReady();
  }

  private VideoItem findFixture(String name) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "_size", "duration", "width", "height"},
                "_display_name=?",
                new String[] {name},
                null)) {
      assertNotNull("MediaStore available", cursor);
      assertTrue("Generated fixture installed: " + name, cursor.moveToFirst());
      Uri uri =
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
      return new VideoItem(
          "portrait-" + name,
          name,
          uri.toString(),
          "",
          cursor.getLong(1),
          cursor.getLong(2),
          0,
          0,
          cursor.getInt(3),
          cursor.getInt(4));
    }
  }

  private void assertSessionPreserved(
      ExoPlayer player, PlaybackOrder order, long position, float speed, int mode)
      throws Exception {
    onMain(
        () -> {
          assertSame("Presentation switch never recreates the decoder", player, read("exo"));
          assertSame("Random deck and previous-video history survive", order, read("order"));
          assertEquals(position, player.getCurrentPosition(), 120);
          assertFalse(player.getPlayWhenReady());
          assertEquals(speed, player.getPlaybackParameters().speed, 0);
          assertEquals(mode, order.mode());
          assertEquals(0, order.current());
          return null;
        });
  }

  private void setPortrait(boolean portrait) throws Exception {
    onMain(
        () -> {
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, portrait);
          return null;
        });
    await(() -> portrait() == portrait && (!portrait || isPortraitConfiguration()), 5000);
    await(() -> !onMain(() -> (Boolean) read("pageTransitioning")), 5000);
    // Give handled configuration changes and the surface layout a frame to settle.
    instrumentation.waitForIdleSync();
    SystemClock.sleep(200);
  }

  private void cycleTo(int mode) throws Exception {
    onMain(
        () -> {
          int safety = 0;
          while (((PlaybackOrder) read("order")).mode() != mode && safety++ < 3)
            ((ImageButton) read("modeButton")).performClick();
          assertEquals(mode, ((PlaybackOrder) read("order")).mode());
          return null;
        });
  }

  private boolean portrait() throws Exception {
    return onMain(() -> (Boolean) read("portraitMode"));
  }

  private boolean isPortraitConfiguration() throws Exception {
    return onMain(
        () ->
            activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT);
  }

  private int currentIndex() throws Exception {
    return onMain(this::currentIndexOnMain);
  }

  private int currentIndexOnMain() throws Exception {
    return ((PlaybackOrder) read("order")).current();
  }

  private void page(boolean up, int expected) throws Exception {
    swipePage(up);
    await(() -> currentIndex() == expected, 5000);
    pauseAndAwaitReady();
    assertTrue("Paging never leaves portrait presentation", portrait());
    assertTrue(isPortraitConfiguration());
  }

  private void swipePage(boolean up) throws Exception {
    View surface = touch();
    float x = surface.getWidth() * .5f;
    swipe(x, surface.getHeight() * (up ? .65f : .35f), x, surface.getHeight() * (up ? .35f : .65f));
  }

  private void pauseAndAwaitReady() throws Exception {
    onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, false);
          return null;
        });
    awaitReady();
  }

  private void awaitReady() throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  ExoPlayer exo = (ExoPlayer) read("exo");
                  MediaPlayer vlc = (MediaPlayer) read("vlc");
                  return !(Boolean) read("pageTransitioning")
                      && (exo != null && exo.getPlaybackState() == Player.STATE_READY
                          || vlc != null && vlc.getLength() > 0);
                }),
        15000);
  }

  private View touch() throws Exception {
    return onMain(() -> (View) read("touchLayer"));
  }

  private void swipe(float fromX, float fromY, float toX, float toY) throws Exception {
    send(MotionEvent.ACTION_DOWN, fromX, fromY);
    for (int i = 1; i <= 10; i++) {
      SystemClock.sleep(20);
      send(
          MotionEvent.ACTION_MOVE,
          fromX + (toX - fromX) * i / 10f,
          fromY + (toY - fromY) * i / 10f);
    }
    send(MotionEvent.ACTION_UP, toX, toY);
  }

  private void send(int action, float x, float y) throws Exception {
    onMain(
        () -> {
          long now = SystemClock.uptimeMillis();
          if (action == MotionEvent.ACTION_DOWN) downTime = now;
          MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
          ((View) read("touchLayer")).dispatchTouchEvent(event);
          event.recycle();
          return null;
        });
  }

  private void sendSecondPointer(float x, float y) throws Exception {
    onMain(
        () -> {
          MotionEvent.PointerProperties first = new MotionEvent.PointerProperties();
          first.id = 0;
          first.toolType = MotionEvent.TOOL_TYPE_FINGER;
          MotionEvent.PointerProperties second = new MotionEvent.PointerProperties();
          second.id = 1;
          second.toolType = MotionEvent.TOOL_TYPE_FINGER;
          MotionEvent.PointerCoords firstCoordinates = new MotionEvent.PointerCoords();
          firstCoordinates.x = x;
          firstCoordinates.y = y;
          firstCoordinates.pressure = firstCoordinates.size = 1;
          MotionEvent.PointerCoords secondCoordinates = new MotionEvent.PointerCoords();
          secondCoordinates.x = x + 30;
          secondCoordinates.y = y;
          secondCoordinates.pressure = secondCoordinates.size = 1;
          MotionEvent event =
              MotionEvent.obtain(
                  downTime,
                  SystemClock.uptimeMillis(),
                  MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                  2,
                  new MotionEvent.PointerProperties[] {first, second},
                  new MotionEvent.PointerCoords[] {firstCoordinates, secondCoordinates},
                  0,
                  0,
                  1f,
                  1f,
                  0,
                  0,
                  android.view.InputDevice.SOURCE_TOUCHSCREEN,
                  0);
          ((View) read("touchLayer")).dispatchTouchEvent(event);
          event.recycle();
          return null;
        });
  }

  private Object read(String name) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void setField(String name, Object value) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(activity, value);
  }

  private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments)
      throws Exception {
    Method method = PlayerActivity.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(activity, arguments);
  }

  private <T> T onMain(Callable<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            result.set(task.call());
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

  private void await(Callable<Boolean> condition, long timeout) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(40);
    }
    fail("Condition did not become true within " + timeout + " ms");
  }
}
