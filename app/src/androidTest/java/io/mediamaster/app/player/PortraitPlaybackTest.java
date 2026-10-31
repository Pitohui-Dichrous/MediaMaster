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
  private int savedPortraitNavigation;
  private boolean hadPortraitNavigation;
  private boolean hadEngine;
  private int savedEngine;

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
    hadEngine = context.getSharedPreferences("player", 0).contains("engine_choice");
    savedEngine = context.getSharedPreferences("player", 0).getInt("engine_choice", 0);
    hadPortraitNavigation =
        context.getSharedPreferences("player", 0).contains("portrait_navigation_mode");
    savedPortraitNavigation =
        context
            .getSharedPreferences("player", 0)
            .getInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL);
    context
        .getSharedPreferences("player", 0)
        .edit()
        .putInt("playback_mode", PlaybackOrder.SINGLE)
        .putInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL)
        .putInt("engine_choice", 1)
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
    if (context != null) {
      android.content.SharedPreferences.Editor preferences =
          context.getSharedPreferences("player", 0).edit().putInt("playback_mode", savedMode);
      if (hadPortraitNavigation)
        preferences.putInt("portrait_navigation_mode", savedPortraitNavigation);
      else preferences.remove("portrait_navigation_mode");
      if (hadEngine) preferences.putInt("engine_choice", savedEngine);
      else preferences.remove("engine_choice");
      preferences.commit();
    }
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
    cycleTo(PlaybackOrder.SHUFFLE);
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
            assertEquals(
                portrait ? PlaybackOrder.SEQUENTIAL : PlaybackOrder.SINGLE, originalOrder.mode());
            assertEquals(PlaybackOrder.SINGLE, originalOrder.fullScreenMode());
            assertEquals(0, originalOrder.current());
            return null;
          });
    }
    page(true, 1);
    assertEquals(
        "Portrait manual paging uses its sequential navigation policy",
        PlaybackOrder.SEQUENTIAL,
        onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
    assertEquals(
        PlaybackOrder.SINGLE,
        onMain(() -> ((PlaybackOrder) read("order")).fullScreenMode()).intValue());
    assertEquals(1.5f, onMain(() -> (Float) read("speed")), 0);
  }

  @Test
  public void sequentialSwipesUseCatalogOrderAndKeepBothAspectRatiosInPortrait() throws Exception {
    setPortrait(true);
    cycleTo(PlaybackOrder.SEQUENTIAL);
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
    cycleTo(PlaybackOrder.SHUFFLE);
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
        PlaybackOrder.SEQUENTIAL, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
    assertEquals(
        PlaybackOrder.SINGLE,
        onMain(() -> ((PlaybackOrder) read("order")).fullScreenMode()).intValue());
    assertEquals(
        Player.REPEAT_MODE_ONE, onMain(() -> ((ExoPlayer) read("exo")).getRepeatMode()).intValue());
  }

  @Test
  public void sequentialAndShufflePortraitOrdersBothRepeatNaturallyWithoutConsumingTheNextVideo()
      throws Exception {
    setPortrait(true);
    ExoPlayer source = onMain(() -> (ExoPlayer) read("exo"));
    AtomicInteger loops = new AtomicInteger();
    onMain(
        () -> {
          source.addListener(
              new Player.Listener() {
                @Override
                public void onMediaItemTransition(MediaItem item, int reason) {
                  if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) loops.incrementAndGet();
                }
              });
          return null;
        });
    for (int navigation : new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
      cycleTo(navigation);
      int before = loops.get();
      int candidate = onMain(() -> ((PlaybackOrder) read("order")).peekNext());
      onMain(
          () -> {
            assertEquals(Player.REPEAT_MODE_ONE, source.getRepeatMode());
            source.seekTo(Math.max(0, source.getDuration() - 550));
            invoke("setPlaying", new Class<?>[] {boolean.class}, true);
            return null;
          });
      await(() -> loops.get() > before, 8000);
      pauseAndAwaitReady();
      onMain(
          () -> {
            PlaybackOrder order = (PlaybackOrder) read("order");
            assertSame("Natural portrait repeat keeps the original decoder", source, read("exo"));
            assertEquals(0, order.current());
            assertEquals(navigation, order.mode());
            assertEquals(
                "Natural repeat must not consume the warmed next candidate",
                candidate,
                order.peekNext());
            assertEquals(
                "Portrait navigation never overwrites the full-screen preference",
                PlaybackOrder.SINGLE,
                order.fullScreenMode());
            return null;
          });
      assertTrue(portrait());
    }
  }

  @Test
  public void returningToFullscreenRestoresSequentialAndShuffleNaturalAdvance() throws Exception {
    for (int fullscreen : new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
      cycleTo(fullscreen);
      ExoPlayer source = onMain(() -> (ExoPlayer) read("exo"));
      setPortrait(true);
      cycleTo(
          fullscreen == PlaybackOrder.SEQUENTIAL
              ? PlaybackOrder.SHUFFLE
              : PlaybackOrder.SEQUENTIAL);
      onMain(
          () -> {
            assertEquals(Player.REPEAT_MODE_ONE, source.getRepeatMode());
            assertEquals(fullscreen, ((PlaybackOrder) read("order")).fullScreenMode());
            return null;
          });
      setPortrait(false);
      int expected = onMain(() -> ((PlaybackOrder) read("order")).peekNext());
      onMain(
          () -> {
            assertSame(source, read("exo"));
            assertEquals(fullscreen, ((PlaybackOrder) read("order")).mode());
            assertEquals(Player.REPEAT_MODE_OFF, source.getRepeatMode());
            source.seekTo(Math.max(0, source.getDuration() - 550));
            invoke("setPlaying", new Class<?>[] {boolean.class}, true);
            return null;
          });
      await(() -> currentIndex() == expected, 10000);
      pauseAndAwaitReady();
      assertFalse(portrait());
      assertEquals(fullscreen, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
    }
  }

  @Test
  public void compatibilityEngineRepeatsTheSameVideoUnderBothPortraitNavigationOrders()
      throws Exception {
    setPortrait(true);
    onMain(
        () -> {
          invoke("startVlc", new Class<?>[] {long.class}, 0L);
          return null;
        });
    await(
        () ->
            onMain(
                () -> {
                  VideoPage page = (VideoPage) read("currentPage");
                  return page.firstFrameReady
                      && page.vlc != null
                      && page.vlc.getLength() > 0
                      && !page.vlc.isPlaying();
                }),
        20000);
    MediaPlayer source = onMain(() -> (MediaPlayer) read("vlc"));
    VideoPage originalPage = onMain(() -> (VideoPage) read("currentPage"));
    int prepares = onMain(() -> originalPage.prepareCount);
    for (int navigation : new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
      cycleTo(navigation);
      int candidate = onMain(() -> ((PlaybackOrder) read("order")).peekNext());
      long length = onMain(source::getLength);
      onMain(
          () -> {
            assertSame(source, read("vlc"));
            invoke("seekTo", new Class<?>[] {long.class}, Math.max(0, length - 1200));
            invoke("setPlaying", new Class<?>[] {boolean.class}, true);
            return null;
          });
      await(() -> onMain(() -> source.getTime() >= length - 1500), 8000);
      await(() -> onMain(() -> source.getTime() >= 0 && source.getTime() < 1000), 10000);
      await(() -> onMain(() -> source.getTime() >= 450 && source.getTime() < 2000), 5000);
      onMain(
          () -> {
            PlaybackOrder order = (PlaybackOrder) read("order");
            assertSame(
                "A native VLC portrait loop keeps its existing decoder", source, read("vlc"));
            assertSame(originalPage, read("currentPage"));
            assertEquals(
                "Native repeat never reopens the media", prepares, originalPage.prepareCount);
            assertEquals(0, order.current());
            assertEquals(navigation, order.mode());
            assertEquals(candidate, order.peekNext());
            assertEquals(PlaybackOrder.SINGLE, order.fullScreenMode());
            return null;
          });
      pauseAndAwaitReady();
    }
  }

  @Test
  public void compatibilityFullscreenPreservesItsDecoderAndDistinguishesManualRewindFromNaturalEnd()
      throws Exception {
    cycleTo(PlaybackOrder.SEQUENTIAL);
    onMain(
        () -> {
          invoke("startVlc", new Class<?>[] {long.class}, 0L);
          return null;
        });
    await(
        () ->
            onMain(
                () -> {
                  VideoPage page = (VideoPage) read("currentPage");
                  return page.firstFrameReady
                      && page.vlc != null
                      && page.vlc.getLength() > 0
                      && !page.vlc.isPlaying();
                }),
        20000);
    MediaPlayer source = onMain(() -> (MediaPlayer) read("vlc"));
    VideoPage originalPage = onMain(() -> (VideoPage) read("currentPage"));
    int prepares = onMain(() -> originalPage.prepareCount);
    long generation = onMain(() -> originalPage.generation);
    setPortrait(true);
    setPortrait(false);
    long length = onMain(source::getLength);
    onMain(
        () -> {
          assertSame("Changing the completion policy does not replace VLC", source, read("vlc"));
          assertSame(originalPage, read("currentPage"));
          assertEquals(prepares, originalPage.prepareCount);
          assertEquals(generation, originalPage.generation);
          assertEquals(PlaybackOrder.SEQUENTIAL, ((PlaybackOrder) read("order")).mode());
          assertFalse((Boolean) read("shouldPlay"));
          invoke("seekTo", new Class<?>[] {long.class}, Math.max(0, length - 1200));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(() -> onMain(() -> source.getTime() >= length - 1500), 8000);
    await(() -> onMain(() -> source.getTime() >= length - 1000), 8000);
    onMain(
        () -> {
          assertTrue("The rewind happens during real near-end playback", source.isPlaying());
          invoke("seekTo", new Class<?>[] {long.class}, 200L);
          return null;
        });
    await(() -> onMain(() -> source.getTime() >= 0 && source.getTime() < 800), 5000);
    onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(
        () ->
            currentIndex() != 0 || onMain(() -> source.getTime() >= 900 && source.getTime() < 2500),
        6000);
    onMain(
        () -> {
          assertEquals(
              "A deliberate rewind must not be mistaken for native end-of-file",
              0,
              currentIndexOnMain());
          assertSame(source, read("vlc"));
          assertEquals(prepares, originalPage.prepareCount);
          invoke("seekTo", new Class<?>[] {long.class}, Math.max(0, length - 1200));
          return null;
        });
    await(() -> currentIndex() == 1, 10000);
    pauseAndAwaitReady();
    assertFalse(portrait());
    assertEquals(
        PlaybackOrder.SEQUENTIAL, onMain(() -> ((PlaybackOrder) read("order")).mode()).intValue());
  }

  @Test
  public void heldHorizontalSeekDefersFullscreenCompletionAndHonorsPlayingAndPausedIntent()
      throws Exception {
    for (int mode : new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
      cycleTo(mode);
      ExoPlayer source = onMain(() -> (ExoPlayer) read("exo"));
      int candidate = onMain(() -> ((PlaybackOrder) read("order")).peekNext());
      onMain(
          () -> {
            invoke("seekTo", new Class<?>[] {long.class}, Math.max(0, source.getDuration() - 650));
            invoke("setPlaying", new Class<?>[] {boolean.class}, true);
            return null;
          });
      View surface = touch();
      float width = surface.getWidth(), y = surface.getHeight() * .45f;
      send(MotionEvent.ACTION_DOWN, width * .8f, y);
      send(MotionEvent.ACTION_MOVE, width * .3f, y);
      long selected = onMain(() -> (Long) read("gestureTarget"));
      onMain(
          () -> {
            assertTrue((Boolean) read("gestureSeeking"));
            assertTrue(
                "The chosen rewind is well before the end", selected < source.getDuration() - 1500);
            return null;
          });
      await(
          () ->
              onMain(
                  () ->
                      currentIndexOnMain() != 0 || source.getPlaybackState() == Player.STATE_ENDED),
          8000);
      SystemClock.sleep(500);
      onMain(
          () -> {
            assertEquals("Natural completion waits while a seek is held", 0, currentIndexOnMain());
            assertSame(source, read("exo"));
            assertEquals(Player.STATE_ENDED, source.getPlaybackState());
            return null;
          });
      send(MotionEvent.ACTION_UP, width * .3f, y);
      await(() -> onMain(() -> currentIndexOnMain() != 0 || source.isPlaying()), 8000);
      SystemClock.sleep(600);
      onMain(
          () -> {
            assertEquals(
                "A stale EOF callback must not override the committed rewind",
                0,
                currentIndexOnMain());
            assertSame(source, read("exo"));
            assertTrue(source.isPlaying());
            assertTrue(source.getCurrentPosition() >= selected);
            assertTrue(source.getCurrentPosition() < selected + 2500);
            assertEquals(candidate, ((PlaybackOrder) read("order")).peekNext());
            invoke("setPlaying", new Class<?>[] {boolean.class}, false);
            invoke("seekTo", new Class<?>[] {long.class}, 1500L);
            return null;
          });
      awaitReady();
      send(MotionEvent.ACTION_DOWN, width * .4f, y);
      send(MotionEvent.ACTION_MOVE, width * .5f, y);
      long pausedTarget = onMain(() -> (Long) read("gestureTarget"));
      send(MotionEvent.ACTION_UP, width * .5f, y);
      awaitReady();
      SystemClock.sleep(500);
      onMain(
          () -> {
            assertEquals(0, currentIndexOnMain());
            assertSame(source, read("exo"));
            assertFalse("A paused manual seek stays paused", source.getPlayWhenReady());
            assertEquals(pausedTarget, source.getCurrentPosition(), 120);
            return null;
          });
    }
  }

  @Test
  public void portraitHeldVerticalScrollPausesNearEndAndReleaseAdvancesOnlyOneEpisode()
      throws Exception {
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
    long heldPosition =
        onMain(
            () -> {
              assertFalse(
                  "Vertical axis lock pauses the outgoing decoder", source.getPlayWhenReady());
              assertEquals(
                  "Vertical scrolling immediately closes its audio gate",
                  0f,
                  source.getVolume(),
                  0);
              assertEquals(source.getDuration() - 650, source.getCurrentPosition(), 180);
              return source.getCurrentPosition();
            });
    SystemClock.sleep(1100);
    onMain(
        () -> {
          assertEquals("A held native scroll must not advance the queue", 0, currentIndexOnMain());
          assertSame("The outgoing decoder remains attached while held", source, read("exo"));
          assertFalse(source.getPlayWhenReady());
          assertEquals(0f, source.getVolume(), 0);
          assertEquals(
              "Holding across the old EOF deadline does not advance playback",
              heldPosition,
              source.getCurrentPosition(),
              100);
          assertTrue((Boolean) read("touchDown"));
          return null;
        });
    send(MotionEvent.ACTION_UP, x, endY);
    await(
        () -> onMain(() -> currentIndexOnMain() == 1 && ((ExoPlayer) read("exo")).isPlaying()),
        8000);
    SystemClock.sleep(500);
    assertEquals(
        "Release selects one video without a stale EOF skipping another", 1, currentIndex());
    assertTrue(onMain(() -> ((ExoPlayer) read("exo")).isPlaying()));
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
    onMain(
        () -> {
          assertFalse((Boolean) read("chromeVisible"));
          assertEquals(View.INVISIBLE, ((View) read("topBar")).getVisibility());
          assertEquals(View.INVISIBLE, ((View) read("bottomBar")).getVisibility());
          assertEquals(View.GONE, ((View) read("seekPreview")).getVisibility());
          return null;
        });
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
  public void portraitModeButtonSwitchesOnlyManualOrderAndAlwaysKeepsNativeRepeat()
      throws Exception {
    setPortrait(true);
    ExoPlayer pipeline = onMain(() -> (ExoPlayer) read("exo"));
    for (int mode :
        new int[] {PlaybackOrder.SHUFFLE, PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
      onMain(
          () -> {
            ImageButton button = (ImageButton) read("modeButton");
            assertTrue(button.isShown());
            assertTrue(button.performClick());
            assertEquals(mode, ((PlaybackOrder) read("order")).mode());
            assertEquals(Player.REPEAT_MODE_ONE, pipeline.getRepeatMode());
            assertEquals(PlaybackOrder.SINGLE, ((PlaybackOrder) read("order")).fullScreenMode());
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
    if (portrait) awaitPortraitNeighbors();
    // Give handled configuration changes and the surface layout a frame to settle.
    instrumentation.waitForIdleSync();
    SystemClock.sleep(200);
  }

  private void awaitPortraitNeighbors() throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  PlaybackOrder currentOrder = (PlaybackOrder) read("order");
                  VideoPage nextPage = (VideoPage) read("nextPage");
                  VideoPage previousPage = (VideoPage) read("previousPage");
                  return nextPage.firstFrameReady
                      && (nextPage.exo == null
                          || nextPage.exo.getPlaybackState() == Player.STATE_READY)
                      && (!currentOrder.hasPrevious()
                          || previousPage.firstFrameReady
                              && (previousPage.exo == null
                                  || previousPage.exo.getPlaybackState() == Player.STATE_READY));
                }),
        20000);
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
    if (portrait()) awaitPortraitNeighbors();
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
    long readyDeadline = SystemClock.elapsedRealtime() + 20000;
    boolean prepared = false;
    while (SystemClock.elapsedRealtime() < readyDeadline) {
      prepared =
          onMain(
              () -> {
                VideoPage target = (VideoPage) read(up ? "nextPage" : "previousPage");
                if (target == null) return false;
                assertFalse(
                    "The requested fixture failed to preload: index=" + target.index,
                    target.failed);
                return target.index == expected
                    && target.firstFrameReady
                    && (target.exo != null && target.exo.getPlaybackState() == Player.STATE_READY
                        || target.vlc != null
                            && !target.buffering
                            && target.pendingSeek == 0
                            && target.vlc.getLength() > 0
                            && !target.vlc.isPlaying());
              });
      if (prepared) break;
      SystemClock.sleep(40);
    }
    if (!prepared) {
      String state =
          onMain(
              () -> {
                VideoPage target = (VideoPage) read(up ? "nextPage" : "previousPage");
                if (target == null) return "target=null";
                return "current="
                    + currentIndexOnMain()
                    + ", target="
                    + target.index
                    + ", firstFrame="
                    + target.firstFrameReady
                    + ", failed="
                    + target.failed
                    + ", buffering="
                    + target.buffering
                    + ", pendingSeek="
                    + target.pendingSeek
                    + ", exoState="
                    + (target.exo == null ? "none" : target.exo.getPlaybackState())
                    + ", vlcLength="
                    + (target.vlc == null ? "none" : target.vlc.getLength())
                    + ", vlcPlaying="
                    + (target.vlc != null && target.vlc.isPlaying());
              });
      fail(
          "The "
              + (up ? "next" : "previous")
              + " page "
              + expected
              + " did not finish preparing before the swipe: "
              + state);
    }
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
