package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/**
 * Native-paging acceptance tests: inspect decoded pixels and decoder identity before finger-up,
 * rather than treating a changed queue index as evidence of a seamless video page.
 */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class PortraitPreloadTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private PlayerActivity activity;
  private ArrayList<VideoItem> fixtures;
  private final ArrayList<Bitmap> references = new ArrayList<>();
  private int savedMode;
  private boolean hadMode;
  private int savedPortraitNavigation;
  private boolean hadPortraitNavigation;
  private boolean hadEngine;
  private int savedEngine;
  private boolean preferencesSaved, hadNativeDecoder;
  private int savedNativeDecoder;
  private long downTime;
  private AudioExclusivityProbe audioProbe;

  @Before
  public void launchRealPages() throws Exception {
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
    hadMode = context.getSharedPreferences("player", 0).contains("playback_mode");
    hadEngine = context.getSharedPreferences("player", 0).contains("engine_choice");
    savedEngine = context.getSharedPreferences("player", 0).getInt("engine_choice", 0);
    hadNativeDecoder = context.getSharedPreferences("player", 0).contains("native_decoder_mode");
    savedNativeDecoder = context.getSharedPreferences("player", 0).getInt("native_decoder_mode", 0);
    hadPortraitNavigation =
        context.getSharedPreferences("player", 0).contains("portrait_navigation_mode");
    savedPortraitNavigation =
        context
            .getSharedPreferences("player", 0)
            .getInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL);
    preferencesSaved = true;
    assertTrue(
        context
            .getSharedPreferences("player", 0)
            .edit()
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .putInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL)
            .putInt("engine_choice", 1)
            .putInt("native_decoder_mode", NativeDecoderPolicy.AUTO)
            .commit());
    fixtures = new ArrayList<>();
    // Three visibly different, real fixture files; explicit compatibility-engine coverage is below.
    for (String name : new String[] {"01_Aurora.mp4", "02_Horizon.mkv", "04_Portrait_Loop.mp4"}) {
      VideoItem item = fixture(name);
      fixtures.add(item);
      // Decode references before launching the three playback decoders to avoid test-induced load.
      references.add(NativeFrameProbe.referenceFirstFrame(context, item));
    }
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, fixtures))
                .putExtra("index", 2));
    await(
        () ->
            onMain(
                () ->
                    current().firstFrameReady
                        && current().exo != null
                        && current().exo.getPlaybackState() == Player.STATE_READY),
        15000);
    onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, false);
          return null;
        });
    audioProbe = new AudioExclusivityProbe();
    onMain(
        () -> {
          audioProbe.start();
          return null;
        });
    enterPortrait();
    awaitNeighbors();
  }

  @After
  public void finish() throws Exception {
    try {
      try {
        if (audioProbe != null)
          onMain(
              () -> {
                audioProbe.stop();
                return null;
              });
      } finally {
        if (activity != null) rule.finishActivity();
      }
    } finally {
      try {
        if (context != null && preferencesSaved) {
          android.content.SharedPreferences.Editor preferences =
              context.getSharedPreferences("player", 0).edit();
          if (hadMode) preferences.putInt("playback_mode", savedMode);
          else preferences.remove("playback_mode");
          if (hadPortraitNavigation)
            preferences.putInt("portrait_navigation_mode", savedPortraitNavigation);
          else preferences.remove("portrait_navigation_mode");
          if (hadEngine) preferences.putInt("engine_choice", savedEngine);
          else preferences.remove("engine_choice");
          if (hadNativeDecoder) preferences.putInt("native_decoder_mode", savedNativeDecoder);
          else preferences.remove("native_decoder_mode");
          assertTrue(preferences.commit());
        }
      } finally {
        for (Bitmap bitmap : references) bitmap.recycle();
        references.clear();
      }
    }
    if (audioProbe != null) audioProbe.assertHealthy();
  }

  @Test
  public void bothNeighborWindowsAlreadyContainDecodedPausedSilentFirstFrames() throws Exception {
    VideoPage previous = onMain(() -> previous()), next = onMain(() -> next());
    assertThreeAttachedRoots();
    assertPausedSilent(previous);
    assertPausedSilent(next);
    assertNativeFirstFrame(previous, "previous-preloaded");
    assertNativeFirstFrame(next, "next-preloaded");
    long previousPosition = onMain(() -> pagePosition(previous));
    long nextPosition = onMain(() -> pagePosition(next));
    int previousFrames = onMain(() -> renderedFrames(previous));
    int nextFrames = onMain(() -> renderedFrames(next));
    SystemClock.sleep(550);
    onMain(
        () -> {
          assertEquals(
              "Previous first frame stays paused", previousPosition, pagePosition(previous), 100);
          assertEquals("Next first frame stays paused", nextPosition, pagePosition(next), 100);
          assertEquals(previousFrames, renderedFrames(previous));
          assertEquals(nextFrames, renderedFrames(next));
          assertEquals(2, order().current());
          return null;
        });
  }

  @Test
  public void fullScreenNextAndPreviousStartTheNewDecoderAndAdvancePlayback() throws Exception {
    ExoPlayer initialDecoder = onMain(() -> current().exo);
    onMain(
        () -> {
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, false);
          assertFalse((Boolean) read("portraitMode"));
          assertFalse(
              "The initial video is intentionally paused", current().exo.getPlayWhenReady());
          assertTrue(findAction((View) read("bottomBar"), "下一条").performClick());
          return null;
        });
    awaitFullScreenPlayback(0, initialDecoder);
    ExoPlayer nextDecoder = onMain(() -> current().exo);
    onMain(
        () -> {
          assertTrue(findAction((View) read("bottomBar"), "上一条").performClick());
          return null;
        });
    awaitFullScreenPlayback(2, nextDecoder);
    assertOnlyCurrentAudible();
  }

  @Test
  public void failedCurrentPageStillPrimesUnpreparedNeighbors() throws Exception {
    VideoPage failedPage = onMain(() -> current());
    int nextPrepares = onMain(() -> next().prepareCount);
    int previousPrepares = onMain(() -> previous().prepareCount);
    onMain(
        () -> {
          previous().release();
          next().release();
          // Inject the terminal callback deterministically; all neighboring files remain real.
          Method fail = VideoPage.class.getDeclaredMethod("fail", long.class);
          fail.setAccessible(true);
          fail.invoke(failedPage, failedPage.generation);
          assertTrue(failedPage.failed);
          assertFalse(failedPage.firstFrameReady);
          return null;
        });
    awaitNeighbors();
    onMain(
        () -> {
          assertSame(failedPage, current());
          assertEquals(nextPrepares + 1, next().prepareCount);
          assertEquals(previousPrepares + 1, previous().prepareCount);
          assertTrue((Boolean) invoke("canRevealNext", new Class<?>[0]));
          assertTrue((Boolean) invoke("canRevealPrevious", new Class<?>[0]));
          return null;
        });
    assertOnlyCurrentAudible();
  }

  private void awaitFullScreenPlayback(int index, ExoPlayer replacedDecoder) throws Exception {
    await(
        () ->
            onMain(
                () ->
                    order().current() == index
                        && current().exo != null
                        && current().exo != replacedDecoder
                        && current().exo.isPlaying()
                        && current().firstFrameReady
                        && current().position() > 120),
        15000);
    onMain(
        () -> {
          assertFalse((Boolean) read("portraitMode"));
          assertTrue(current().playRequested);
          assertTrue(current().exo.getPlayWhenReady());
          assertEquals(1f, current().exo.getVolume(), 0f);
          assertTrue("The replacement renders actual video", renderedFrames(current()) > 0);
          assertNull(previous().exo);
          assertNull(next().exo);
          return null;
        });
  }

  private static View findAction(View view, String label) {
    if (label.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = findAction(group.getChildAt(i), label);
        if (match != null) return match;
      }
    }
    return null;
  }

  @Test
  public void heldNativeScrollRevealsRealNeighborAndCommitsTheSamePreparedDecoder()
      throws Exception {
    VideoPage outgoing = onMain(() -> current()), incoming = onMain(() -> next());
    ExoPlayer decoder = onMain(() -> incoming.exo);
    View videoSurface = onMain(() -> incoming.exoView.getVideoSurfaceView());
    int prepares = onMain(() -> incoming.prepareCount);
    long generation = onMain(() -> incoming.generation);
    String history = onMain(this::historyFingerprint);
    View touch = onMain(() -> (View) read("touchLayer"));
    float x = touch.getWidth() * .5f, y = touch.getHeight() * .78f, dy = -touch.getHeight() * .5f;
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_MOVE, x, y + dy);
    SystemClock.sleep(150);
    onMain(
        () -> {
          ThreeWindowPager pager = (ThreeWindowPager) read("pager");
          assertEquals(
              "The native viewport follows the finger before release",
              -dy,
              pager.offsetPixels(),
              3);
          assertFalse(pager.isSettling());
          assertTrue((Boolean) read("pageSuspended"));
          assertEquals(history, historyFingerprint());
          assertSame(incoming, next());
          Rect currentVisible = new Rect(), nextVisible = new Rect();
          assertTrue(
              "Outgoing video remains in the composited viewport",
              outgoing.exoView.getVideoSurfaceView().getGlobalVisibleRect(currentVisible));
          assertTrue(
              "Incoming native video is visibly revealed before finger-up",
              videoSurface.getGlobalVisibleRect(nextVisible));
          assertTrue(currentVisible.height() > 8 && nextVisible.height() > 8);
          Log.i(
              "PortraitNativeProof",
              "held outgoing="
                  + currentVisible
                  + " incoming="
                  + nextVisible
                  + " offset="
                  + pager.offsetPixels());
          return null;
        });
    assertPausedSilent(outgoing);
    assertPausedSilent(incoming);
    assertNativeFirstFrame(incoming, "held-next-native-frame");
    Bitmap held = NativeFrameProbe.captureCompositedScreen(instrumentation);
    try {
      NativeFrameProbe.writeEvidence(instrumentation, "held-two-native-windows", held);
    } finally {
      held.recycle();
    }
    int renderedBefore = onMain(() -> renderedFrames(incoming));
    send(MotionEvent.ACTION_UP, x, y + dy);
    await(
        () ->
            onMain(
                () ->
                    current() == incoming
                        && !(Boolean) read("pageTransitioning")
                        && incoming.exo.getPlayWhenReady()
                        && incoming.exo.isPlaying()),
        8000);
    onMain(
        () -> {
          assertSame("Commit promotes the same page root", incoming, current());
          assertSame("Commit never replaces the prepared ExoPlayer", decoder, incoming.exo);
          assertSame(
              "Commit never replaces or reattaches the native surface",
              videoSurface,
              incoming.exoView.getVideoSurfaceView());
          assertEquals(
              "Commit must not prepare incoming media again", prepares, incoming.prepareCount);
          assertEquals(generation, incoming.generation);
          assertEquals(0, order().current());
          return null;
        });
    await(
        () ->
            onMain(() -> renderedFrames(incoming) > renderedBefore && pagePosition(incoming) > 120),
        5000);
    assertOnlyCurrentAudible();
    assertThreeAttachedRoots();
  }

  @Test
  public void randomPreviewsCancelAndReverseWithoutConsumingHistoryOrReplacingThreeRoots()
      throws Exception {
    onMain(
        () -> {
          invoke("setPlaybackMode", new Class<?>[] {int.class}, PlaybackOrder.SHUFFLE);
          return null;
        });
    await(() -> onMain(() -> readyForPlayback(next())), 15000);
    Set<VideoPage> roots =
        onMain(() -> new HashSet<>(Arrays.asList(previous(), current(), next())));
    VideoPage initial = onMain(() -> current()), candidate = onMain(() -> next());
    ExoPlayer prepared = onMain(() -> candidate.exo);
    int prepares = onMain(() -> candidate.prepareCount),
        candidateIndex = onMain(() -> candidate.index);
    String before = onMain(this::historyFingerprint);
    View touch = onMain(() -> (View) read("touchLayer"));
    float x = touch.getWidth() * .5f, y = touch.getHeight() * .65f, height = touch.getHeight();
    for (int attempt = 0; attempt < 3; attempt++) {
      send(MotionEvent.ACTION_DOWN, x, y);
      send(MotionEvent.ACTION_MOVE, x, y - height * .4f);
      onMain(
          () -> {
            assertEquals(before, historyFingerprint());
            assertSame(candidate, next());
            assertEquals(candidateIndex, next().index);
            return null;
          });
      // Reverse inside the same touch, then cancel. Neither the preview nor rollback chooses a
      // video.
      send(MotionEvent.ACTION_MOVE, x, y - height * .02f);
      send(MotionEvent.ACTION_CANCEL, x, y - height * .02f);
      await(
          () ->
              onMain(
                  () ->
                      !((ThreeWindowPager) read("pager")).isSettling()
                          && Math.abs(((ThreeWindowPager) read("pager")).offsetPixels()) < 1),
          5000);
      onMain(
          () -> {
            assertSame(initial, current());
            assertSame(candidate, next());
            assertSame(prepared, candidate.exo);
            assertEquals(prepares, candidate.prepareCount);
            assertEquals(before, historyFingerprint());
            return null;
          });
    }
    swipeUp();
    await(() -> onMain(() -> current() == candidate && !(Boolean) read("pageTransitioning")), 8000);
    assertEquals(candidateIndex, onMain(() -> order().current()).intValue());
    onMain(
        () -> {
          assertEquals(
              "One committed page consumes exactly one history entry",
              2,
              orderList("history").size());
          assertEquals(
              "Exactly the original three native roots are reused",
              roots,
              new HashSet<>(Arrays.asList(previous(), current(), next())));
          return null;
        });
    assertOnlyCurrentAudible();
    assertThreeAttachedRoots();
  }

  @Test
  public void singleRepeatManualReversePromotesThePreparedPreviousNativePage() throws Exception {
    VideoPage target = onMain(() -> previous());
    ExoPlayer decoder = onMain(() -> target.exo);
    int prepares = onMain(() -> target.prepareCount);
    swipeDown();
    await(() -> onMain(() -> current() == target && !(Boolean) read("pageTransitioning")), 8000);
    onMain(
        () -> {
          assertSame(decoder, current().exo);
          assertEquals(prepares, current().prepareCount);
          assertEquals(1, order().current());
          assertEquals(PlaybackOrder.SEQUENTIAL, order().mode());
          assertEquals(PlaybackOrder.SINGLE, order().fullScreenMode());
          assertEquals(Player.REPEAT_MODE_ONE, current().exo.getRepeatMode());
          return null;
        });
    await(() -> onMain(() -> target.exo.isPlaying() && pagePosition(target) > 120), 5000);
    assertOnlyCurrentAudible();
    assertThreeAttachedRoots();
  }

  @Test
  public void portraitRepeatDoesNotConsumeSequentialOrRandomPreloadBeforeManualCommit()
      throws Exception {
    for (int navigation : new int[] {PlaybackOrder.SEQUENTIAL, PlaybackOrder.SHUFFLE}) {
      onMain(
          () -> {
            invoke("setPlaying", new Class<?>[] {boolean.class}, false);
            invoke("setPlaybackMode", new Class<?>[] {int.class}, navigation);
            return null;
          });
      await(() -> onMain(() -> readyForPlayback(next())), 20000);
      VideoPage repeating = onMain(() -> current());
      ExoPlayer decoder = onMain(() -> repeating.exo);
      VideoPage incoming = onMain(() -> next());
      ExoPlayer prepared = onMain(() -> incoming.exo);
      int index = onMain(() -> order().current());
      int candidate = onMain(() -> incoming.index);
      int prepares = onMain(() -> repeating.prepareCount);
      int incomingPrepares = onMain(() -> incoming.prepareCount);
      long generation = onMain(() -> incoming.generation);
      String before = onMain(this::historyFingerprint);
      AtomicInteger repeats = new AtomicInteger();
      onMain(
          () -> {
            decoder.addListener(
                new Player.Listener() {
                  @Override
                  public void onMediaItemTransition(MediaItem item, int reason) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                      repeats.incrementAndGet();
                  }
                });
            assertEquals(Player.REPEAT_MODE_ONE, decoder.getRepeatMode());
            decoder.seekTo(Math.max(0, decoder.getDuration() - 550));
            invoke("setPlaying", new Class<?>[] {boolean.class}, true);
            return null;
          });
      await(() -> repeats.get() > 0, 8000);
      onMain(
          () -> {
            assertSame("Portrait completion keeps the same native page", repeating, current());
            assertSame(decoder, current().exo);
            assertEquals(prepares, current().prepareCount);
            assertEquals(index, order().current());
            assertEquals(navigation, order().mode());
            assertEquals(
                "Natural completion never consumes manual navigation history",
                before,
                historyFingerprint());
            assertSame("The next video remains warm until explicitly chosen", incoming, next());
            assertEquals(candidate, next().index);
            assertSame(prepared, next().exo);
            assertEquals(incomingPrepares, next().prepareCount);
            invoke("setPlaying", new Class<?>[] {boolean.class}, false);
            return null;
          });
      swipeUp();
      await(
          () ->
              onMain(
                  () ->
                      current() == incoming
                          && incoming.exo.isPlaying()
                          && !(Boolean) read("pageTransitioning")),
          8000);
      onMain(
          () -> {
            assertSame(
                "An explicit swipe promotes the already rendered native page", incoming, current());
            assertSame(
                "An explicit swipe never replaces the warmed decoder", prepared, current().exo);
            assertEquals(incomingPrepares, current().prepareCount);
            assertEquals(generation, current().generation);
            assertEquals(candidate, order().current());
            assertEquals(navigation, order().mode());
            assertEquals(Player.REPEAT_MODE_ONE, current().exo.getRepeatMode());
            assertEquals(PlaybackOrder.SINGLE, order().fullScreenMode());
            return null;
          });
      await(() -> onMain(() -> pagePosition(incoming) > 120), 5000);
      assertOnlyCurrentAudible();
      assertThreeAttachedRoots();
    }
  }

  @Test
  public void fullPresentationPreservesCurrentDecoderAndReleasesNeighborsThenBackgroundReleasesAll()
      throws Exception {
    VideoPage center = onMain(() -> current()),
        oldPrevious = onMain(() -> previous()),
        oldNext = onMain(() -> next());
    ExoPlayer decoder = onMain(() -> center.exo);
    onMain(
        () -> {
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, false);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    oldPrevious.exo == null
                        && oldPrevious.vlc == null
                        && oldNext.exo == null
                        && oldNext.vlc == null),
        8000);
    onMain(
        () -> {
          assertSame(center, current());
          assertSame(decoder, center.exo);
          assertFalse(center.exo.getPlayWhenReady());
          assertFalse((Boolean) read("portraitMode"));
          assertEquals(PlaybackOrder.SINGLE, order().mode());
          assertEquals(Player.REPEAT_MODE_ONE, center.exo.getRepeatMode());
          return null;
        });
    assertTrue(
        "The real Android task moves to the background",
        onMain(() -> activity.moveTaskToBack(true)));
    await(
        () ->
            onMain(
                () ->
                    center.exo == null
                        && center.vlc == null
                        && oldPrevious.exo == null
                        && oldPrevious.vlc == null
                        && oldNext.exo == null
                        && oldNext.vlc == null),
        10000);
  }

  @Test
  public void nativeCompatibilityNeighborHasRealFrameAndCommitKeepsTheSameVlcDecoder()
      throws Exception {
    VideoPage incoming = onMain(() -> next());
    int index = onMain(() -> incoming.index);
    onMain(
        () -> {
          incoming.prepare(fixtures.get(index), index, 0L, true, true, 1f, false);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    readyForPlayback(incoming)
                        && incoming.vlc != null
                        && !incoming.vlc.isPlaying()),
        20000);
    assertPausedSilent(incoming);
    MediaPlayer decoder = onMain(() -> incoming.vlc);
    int prepares = onMain(() -> incoming.prepareCount);
    long generation = onMain(() -> incoming.generation);
    assertNativeFirstFrame(incoming, "compatibility-preloaded-native-frame");
    swipeUp();
    await(
        () ->
            onMain(() -> current() == incoming && incoming.vlc != null && incoming.vlc.isPlaying()),
        10000);
    onMain(
        () -> {
          assertSame(
              "VLC commit promotes its native decoder, not a newly opened media",
              decoder,
              current().vlc);
          assertEquals(prepares, current().prepareCount);
          assertEquals(generation, current().generation);
          assertEquals(index, order().current());
          assertEquals(PlaybackOrder.SEQUENTIAL, order().mode());
          assertEquals(PlaybackOrder.SINGLE, order().fullScreenMode());
          return null;
        });
    await(() -> onMain(() -> pagePosition(incoming) > 250), 8000);
    assertOnlyCurrentAudible();
    assertThreeAttachedRoots();
  }

  private void enterPortrait() throws Exception {
    onMain(
        () -> {
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    (Boolean) read("portraitMode")
                        && ((View) read("touchLayer")).getHeight()
                            > ((View) read("touchLayer")).getWidth()),
        8000);
  }

  private void awaitNeighbors() throws Exception {
    await(() -> onMain(() -> readyForPlayback(previous()) && readyForPlayback(next())), 20000);
    assertPausedSilent(onMain(() -> previous()));
    assertPausedSilent(onMain(() -> next()));
  }

  private boolean readyForPlayback(VideoPage page) {
    return page.firstFrameReady
        && !page.failed
        && (page.exo != null && page.exo.getPlaybackState() == Player.STATE_READY
            || page.vlc != null && !page.buffering && page.vlc.getLength() > 0);
  }

  private void assertNativeFirstFrame(VideoPage page, String evidenceName) throws Exception {
    boolean compatibility = onMain(() -> page.exo == null);
    Bitmap raw;
    if (!compatibility) raw = NativeFrameProbe.capturePlayerSurface(instrumentation, page.exoView);
    else {
      View surface = onMain(() -> findSurface(page.vlcView));
      assertNotNull("VLC has a native video surface", surface);
      raw = NativeFrameProbe.captureSurface(instrumentation, surface);
    }
    Bitmap frame = raw;
    try {
      // Save native pixels before any content assertion, including a failing black/incorrect frame.
      NativeFrameProbe.writeEvidence(
          instrumentation, compatibility ? evidenceName + "-raw" : evidenceName, raw);
      if (compatibility) {
        int[] trackSize =
            onMain(
                () -> {
                  Media.VideoTrack track = page.vlc.getCurrentVideoTrack();
                  assertNotNull("VLC has the actual decoded video track", track);
                  return new int[] {track.width, track.height};
                });
        frame = NativeFrameProbe.cropExpectedFitContent(raw, trackSize[0], trackSize[1]);
        NativeFrameProbe.writeEvidence(instrumentation, evidenceName + "-crop", frame);
        Log.i(
            "PortraitNativeProof",
            "VLC native viewport="
                + raw.getWidth()
                + "x"
                + raw.getHeight()
                + " videoTrack="
                + trackSize[0]
                + "x"
                + trackSize[1]
                + " expectedFitContent="
                + frame.getWidth()
                + "x"
                + frame.getHeight());
      }
      double visible = NativeFrameProbe.nonBlackFraction(frame);
      double distance =
          NativeFrameProbe.averageRgbDistance(references.get(onMain(() -> page.index)), frame);
      Log.i(
          "PortraitNativeProof",
          "page="
              + onMain(() -> page.index)
              + " nonBlackFraction="
              + visible
              + " firstFrameRgbDistance="
              + distance);
      assertTrue(
          "Preloaded native surface must contain real video pixels, not a black placeholder",
          visible > .2);
      assertTrue(
          "Preloaded native frame matches that file's first frame: difference=" + distance,
          distance < 38);
    } finally {
      if (frame != raw) frame.recycle();
      raw.recycle();
    }
  }

  private void assertPausedSilent(VideoPage page) throws Exception {
    onMain(
        () -> {
          assertTrue(page.firstFrameReady);
          assertFalse(page.failed);
          if (page.exo != null) {
            assertEquals(Player.STATE_READY, page.exo.getPlaybackState());
            assertFalse("A warmed neighbor remains paused", page.exo.getPlayWhenReady());
            assertEquals(
                "A warmed neighbor is muted before it is exposed", 0f, page.exo.getVolume(), 0);
            assertTrue(
                "Native video renderer produced at least one frame", renderedFrames(page) > 0);
          } else {
            assertNotNull(page.vlc);
            assertFalse(page.vlc.isPlaying());
            assertEquals(0, page.vlc.getVolume());
          }
          return null;
        });
  }

  private void assertOnlyCurrentAudible() throws Exception {
    onMain(
        () -> {
          audioProbe.sample();
          for (VideoPage page : pages())
            if (page != current()) {
              if (page.exo != null) assertEquals(0f, page.exo.getVolume(), 0);
              if (page.vlc != null) assertEquals(0, page.vlc.getVolume());
            }
          return null;
        });
    audioProbe.assertHealthy();
  }

  private void assertThreeAttachedRoots() throws Exception {
    onMain(
        () -> {
          VideoPage[] pages = pages();
          assertEquals(3, new HashSet<>(Arrays.asList(pages)).size());
          ViewGroup host = (ViewGroup) pages[0].getParent();
          assertNotNull(host);
          assertEquals("Exactly three native windows stay attached", 3, host.getChildCount());
          for (VideoPage page : pages) {
            assertSame(host, page.getParent());
            assertTrue(page.isAttachedToWindow());
          }
          return null;
        });
  }

  private static View findSurface(View root) {
    return NativeFrameProbe.findVideoSurface(root);
  }

  private VideoItem fixture(String name) {
    // Real-phone runs opt into their dedicated generated-fixture directory, avoiding a user's
    // unrelated video with an identical short name. Emulator fixtures retain the existing setup.
    String root = InstrumentationRegistry.getArguments().getString("fixtureRoot", "");
    String selection = root.isEmpty() ? "_display_name=?" : "_display_name=? AND _data=?";
    String[] arguments =
        root.isEmpty() ? new String[] {name} : new String[] {name, root + "/" + name};
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "_size", "duration", "width", "height"},
                selection,
                arguments,
                null)) {
      assertNotNull(cursor);
      assertTrue("Generated fixture is installed: " + name, cursor.moveToFirst());
      return new VideoItem(
          "native-" + name,
          name,
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0))
              .toString(),
          "",
          cursor.getLong(1),
          cursor.getLong(2),
          0,
          0,
          cursor.getInt(3),
          cursor.getInt(4));
    }
  }

  private void swipeUp() throws Exception {
    swipe(true);
  }

  private void swipeDown() throws Exception {
    swipe(false);
  }

  private void swipe(boolean up) throws Exception {
    View touch = onMain(() -> (View) read("touchLayer"));
    float x = touch.getWidth() * .5f,
        from = touch.getHeight() * (up ? .7f : .3f),
        to = touch.getHeight() * (up ? .3f : .7f);
    send(MotionEvent.ACTION_DOWN, x, from);
    for (int i = 1; i <= 10; i++) {
      SystemClock.sleep(20);
      send(MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 10f);
    }
    send(MotionEvent.ACTION_UP, x, to);
  }

  private void send(int action, float x, float y) throws Exception {
    onMain(
        () -> {
          long now = SystemClock.uptimeMillis();
          if (action == MotionEvent.ACTION_DOWN) downTime = now;
          MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
          ((View) read("touchLayer")).dispatchTouchEvent(event);
          event.recycle();
          audioProbe.sample();
          return null;
        });
  }

  private String historyFingerprint() throws Exception {
    PlaybackOrder order = order();
    return order.current()
        + ":"
        + order.mode()
        + ":"
        + orderField("cursor")
        + ":"
        + orderList("history")
        + ":"
        + orderList("deck");
  }

  private Object orderField(String name) throws Exception {
    Field field = PlaybackOrder.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(order());
  }

  private ArrayList<?> orderList(String name) throws Exception {
    return new ArrayList<>((ArrayList<?>) orderField(name));
  }

  private PlaybackOrder order() throws Exception {
    return (PlaybackOrder) read("order");
  }

  private VideoPage current() throws Exception {
    return (VideoPage) read("currentPage");
  }

  private VideoPage previous() throws Exception {
    return (VideoPage) read("previousPage");
  }

  private VideoPage next() throws Exception {
    return (VideoPage) read("nextPage");
  }

  private VideoPage[] pages() throws Exception {
    return new VideoPage[] {previous(), current(), next()};
  }

  private long pagePosition(VideoPage page) {
    return page.exo != null
        ? page.exo.getCurrentPosition()
        : page.vlc != null ? page.vlc.getTime() : -1;
  }

  private int renderedFrames(VideoPage page) {
    if (page.exo == null) return 0;
    DecoderCounters counters = page.exo.getVideoDecoderCounters();
    if (counters == null) return 0;
    counters.ensureUpdated();
    return counters.renderedOutputBufferCount;
  }

  private Object read(String name) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = PlayerActivity.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(activity, args);
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            result.set(action.call());
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    Throwable error = failure.get();
    if (error instanceof Exception) throw (Exception) error;
    if (error instanceof AssertionError) throw (AssertionError) error;
    if (error != null) throw new RuntimeException(error);
    return result.get();
  }

  private void await(Callable<Boolean> condition, long timeout) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      if (audioProbe != null) audioProbe.assertHealthy();
      SystemClock.sleep(30);
    }
    fail("Native three-window condition timed out after " + timeout + " ms");
  }

  /**
   * Samples all attached decoders during native callbacks and every display frame, not just EOF.
   */
  private final class AudioExclusivityProbe {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Set<ExoPlayer> observed = new HashSet<>();
    private boolean running;
    private final Player.Listener listener =
        new Player.Listener() {
          @Override
          public void onEvents(Player player, Player.Events events) {
            sample();
          }
        };
    private final Runnable sampler =
        new Runnable() {
          @Override
          public void run() {
            if (!running) return;
            sample();
            main.postDelayed(this, 8);
          }
        };

    void start() {
      running = true;
      main.post(sampler);
    }

    void sample() {
      if (!running) return;
      try {
        int audible = 0;
        for (VideoPage page : pages()) {
          if (page == null) continue;
          if (page.exo != null) {
            if (observed.add(page.exo)) page.exo.addListener(listener);
            if (page.exo.getPlayWhenReady() && page.exo.getVolume() > .0001f) audible++;
          }
          if (page.vlc != null && page.vlc.isPlaying() && page.vlc.getVolume() > 0) audible++;
        }
        assertTrue(
            "At most one native decoder may produce audible playback; got " + audible,
            audible <= 1);
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
      }
    }

    void stop() {
      running = false;
      main.removeCallbacks(sampler);
    }

    void assertHealthy() {
      if (failure.get() != null)
        throw new AssertionError("Native audio exclusivity failed during paging", failure.get());
    }
  }
}
