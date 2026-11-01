package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.MainActivity;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real touch dispatch and dialogs; votes and viewing records use a disposable private database. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class FeedbackInteractionTest {
  @Rule
  public ActivityTestRule<PlayerActivity> playerRule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  @Rule
  public ActivityTestRule<MainActivity> mainRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private PlayerActivity player;
  private MainActivity catalog;
  private PlaybackStatsStore store, originalStore;
  private PlaybackVoteSession resetSeed;
  private Field singleton;
  private String databaseName;
  private boolean installed, preferencesSaved;
  private ScopedPlaybackHistory history;
  private final Map<String, Integer> preferences = new HashMap<>();
  private final ArrayList<VideoItem> videos = new ArrayList<>();

  @Before
  public void isolateVotesAndPreferences() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    SharedPreferences saved = context.getSharedPreferences("player", 0);
    for (String key :
        new String[] {
          "playback_mode", "portrait_navigation_mode", "engine_choice", "native_decoder_mode"
        }) preferences.put(key, saved.contains(key) ? saved.getInt(key, 0) : null);
    preferencesSaved = true;
    assertTrue(
        saved
            .edit()
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .putInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL)
            .putInt("engine_choice", 1)
            .putInt("native_decoder_mode", NativeDecoderPolicy.AUTO)
            .commit());
    databaseName = "feedback-interaction-" + UUID.randomUUID() + ".db";
    // Gesture assertions isolate feedback changes from wall-clock decay; decay has its own tests.
    store =
        new PlaybackStatsStore(
            context,
            databaseName,
            50,
            Executors.newSingleThreadExecutor(),
            () -> 1_700_000_000_000L);
    store.awaitIdle();
    onMain(
        () -> {
          history = ScopedPlaybackHistory.open(context);
          singleton = PlaybackStatsStore.class.getDeclaredField("instance");
          singleton.setAccessible(true);
          originalStore = (PlaybackStatsStore) singleton.get(null);
          singleton.set(null, store);
          installed = true;
          return null;
        });
    videos.add(fixture("01_Aurora.mp4"));
    videos.add(fixture("04_Portrait_Loop.mp4"));
  }

  @After
  public void restoreOwnedStateOnly() throws Exception {
    try {
      if (player != null) {
        onMain(
            () -> {
              player.finish();
              return null;
            });
        playerRule.finishActivity();
      }
      if (catalog != null) {
        onMain(
            () -> {
              catalog.finish();
              return null;
            });
        mainRule.finishActivity();
      }
      if (history != null) history.closeAfterActivityDestroyed(player);
    } finally {
      if (installed)
        onMain(
            () -> {
              assertSame(store, singleton.get(null));
              singleton.set(null, originalStore);
              return null;
            });
      if (store != null) store.close();
      if (context != null && databaseName != null) context.deleteDatabase(databaseName);
      if (context != null && preferencesSaved) {
        SharedPreferences.Editor editor = context.getSharedPreferences("player", 0).edit();
        for (Map.Entry<String, Integer> entry : preferences.entrySet()) {
          if (entry.getValue() == null) editor.remove(entry.getKey());
          else editor.putInt(entry.getKey(), entry.getValue());
        }
        assertTrue(editor.commit());
      }
    }
  }

  @Test
  public void buttonsToggleOneExclusiveVoteAndExposeActionableStates() throws Exception {
    double baseline = seedModerateExposure();
    launch(false);
    setPlaying(false);
    assertVote(0);
    clickField("likeButton");
    assertVote(1);
    double likedWeight = store.weight(videos.get(0));
    assertTrue("One like moves the same video's weight above its baseline", likedWeight > baseline);
    clickField("likeButton");
    assertVote(0);
    assertEquals(
        "Cancelling removes this window's contribution",
        baseline,
        store.weight(videos.get(0)),
        1e-12);
    clickField("dislikeButton");
    assertVote(-1);
    double dislikedWeight = store.weight(videos.get(0));
    assertTrue(
        "One dislike moves the same video's weight below its baseline", dislikedWeight < baseline);
    clickField("likeButton");
    assertVote(1);
    assertEquals(
        "Opposite votes replace, rather than stack",
        likedWeight,
        store.weight(videos.get(0)),
        1e-12);
    clickField("dislikeButton");
    assertVote(-1);
    assertEquals(dislikedWeight, store.weight(videos.get(0)), 1e-12);
    clickField("dislikeButton");
    assertVote(0);
    assertEquals(baseline, store.weight(videos.get(0)), 1e-12);
    assertFalse("Voting must not resume paused playback", playRequested());
  }

  @Test
  public void portraitSingleTapPausesAndResumesWithoutVoting() throws Exception {
    launch(true);
    setPlaying(true);
    tap();
    awaitTapConfirmation();
    assertFalse(playRequested());
    assertVote(0);
    tap();
    awaitTapConfirmation();
    assertTrue(playRequested());
    assertVote(0);
  }

  @Test
  public void portraitDoubleTapLikesWithoutPlaybackToggleOrStackedWeight() throws Exception {
    double baseline = seedModerateExposure();
    launch(true);
    setPlaying(false);
    doubleTap();
    awaitTapConfirmation();
    assertVote(1);
    assertFalse("A double tap must not leak the first tap's pause/resume", playRequested());
    double initialWeight = store.weight(videos.get(0));
    assertTrue(initialWeight > baseline);
    doubleTap();
    awaitTapConfirmation();
    assertVote(1);
    assertEquals(
        "Repeated double taps are idempotent", initialWeight, store.weight(videos.get(0)), 1e-12);
    clickField("dislikeButton");
    assertVote(-1);
    doubleTap();
    awaitTapConfirmation();
    assertVote(1);
    assertEquals(
        "Double tapping replaces a dislike, not adds another vote",
        initialWeight,
        store.weight(videos.get(0)),
        1e-12);
    assertFalse(playRequested());
  }

  @Test
  public void portraitTapBurstsNeverLeaveASingleTapTailOrStackVotes() throws Exception {
    double baseline = seedModerateExposure();
    launch(true);
    setPlaying(false);
    double liked = Double.NaN;
    // Fast pairs and odd-length bursts must have the same meaning as sustained even bursts.
    for (int[] burst : new int[][] {{2, 20}, {3, 70}, {5, 20}, {6, 180}}) {
      tapBurst(burst[0], burst[1]);
      awaitTapConfirmation();
      assertVote(1);
      assertFalse(
          "No trailing tap may resume playback after a " + burst[0] + "-tap burst",
          playRequested());
      double weight = store.weight(videos.get(0));
      if (Double.isNaN(liked)) {
        liked = weight;
        assertTrue(liked > baseline);
      } else {
        assertEquals("A continuous burst cannot contribute another vote", liked, weight, 1e-12);
      }
    }
    // The burst boundary must not swallow an independent single tap after its timeout.
    tap();
    awaitTapConfirmation();
    assertTrue("A later independent single tap still resumes", playRequested());
    tapBurst(3, 70);
    awaitTapConfirmation();
    assertTrue("A burst cannot pause currently playing video either", playRequested());
    tap();
    awaitTapConfirmation();
    assertFalse("A later independent single tap still pauses", playRequested());
    assertVote(1);
  }

  private void tapBurst(int count, long gapMillis) throws Exception {
    float[] point = center();
    for (int index = 0; index < count; index++) {
      long down = SystemClock.uptimeMillis();
      touch(MotionEvent.ACTION_DOWN, down, point[0], point[1]);
      SystemClock.sleep(15);
      touch(MotionEvent.ACTION_UP, down, point[0], point[1]);
      if (index + 1 < count) SystemClock.sleep(gapMillis);
    }
  }

  @Test
  public void newWindowCanContributeAgainAndCancelOnlyItsOwnContribution() throws Exception {
    double baseline = seedModerateExposure();
    launch(true);
    setPlaying(false);
    clickField("likeButton");
    assertVote(1);
    PlaybackVoteSession firstWindow = onMain(this::votes);
    double firstWeight = store.weight(videos.get(0));
    assertTrue(firstWeight > baseline);
    recreatePlayer();
    assertSame(
        "Configuration recreation retains the same playback window",
        firstWindow,
        onMain(this::votes));
    assertVote(1);
    doubleTap();
    awaitTapConfirmation();
    assertEquals(
        "Recreation must not enable duplicate votes",
        firstWeight,
        store.weight(videos.get(0)),
        1e-12);
    onMain(
        () -> {
          player.finish();
          return null;
        });
    playerRule.finishActivity();
    await(() -> onMain(() -> player.isDestroyed()));
    store.awaitIdle();
    launch(true);
    setPlaying(false);
    assertNotSame(firstWindow, onMain(this::votes));
    assertVote(0);
    assertEquals(
        "Old window's weighting survives reopening",
        firstWeight,
        store.weight(videos.get(0)),
        1e-12);
    clickField("likeButton");
    assertVote(1);
    assertTrue(
        "A new window can strengthen its recent preference",
        store.weight(videos.get(0)) > firstWeight);
    clickField("likeButton");
    assertVote(0);
    assertEquals(
        "Cancelling does not undo the previous window's contribution",
        firstWeight,
        store.weight(videos.get(0)),
        1e-12);
  }

  @Test
  public void fullScreenRetainsSingleTapChromeAndDoubleTapPlayback() throws Exception {
    launch(false);
    setPlaying(false);
    boolean before = onMain(() -> (boolean) read(player, "chromeVisible"));
    tap();
    awaitTapConfirmation();
    assertEquals(!before, onMain(() -> (boolean) read(player, "chromeVisible")));
    assertFalse(playRequested());
    doubleTap();
    awaitTapConfirmation();
    assertTrue(playRequested());
    assertVote(0);
  }

  @Test
  public void horizontalSeekCancelsTapRecognitionAndHidesChrome() throws Exception {
    launch(true);
    setPlaying(false);
    float[] center = center();
    long down = SystemClock.uptimeMillis();
    touch(MotionEvent.ACTION_DOWN, down, center[0], center[1]);
    SystemClock.sleep(30);
    touch(MotionEvent.ACTION_MOVE, down, center[0] + center[0] * .35f, center[1]);
    SystemClock.sleep(30);
    touch(MotionEvent.ACTION_UP, down, center[0] + center[0] * .35f, center[1]);
    awaitTapConfirmation();
    assertVote(0);
    assertFalse(playRequested());
    assertFalse(onMain(() -> (boolean) read(player, "chromeVisible")));
  }

  @Test
  public void cancelledTapDoesNotPauseOrVote() throws Exception {
    launch(true);
    setPlaying(false);
    float[] center = center();
    long down = SystemClock.uptimeMillis();
    touch(MotionEvent.ACTION_DOWN, down, center[0], center[1]);
    touch(MotionEvent.ACTION_CANCEL, down, center[0], center[1]);
    awaitTapConfirmation();
    assertVote(0);
    assertFalse(playRequested());
  }

  @Test
  public void secondTapBecomingASeekCannotLikeOrTogglePlayback() throws Exception {
    launch(true);
    setPlaying(false);
    tap();
    SystemClock.sleep(70);
    float[] center = center();
    long down = SystemClock.uptimeMillis();
    touch(MotionEvent.ACTION_DOWN, down, center[0], center[1]);
    SystemClock.sleep(30);
    touch(MotionEvent.ACTION_MOVE, down, center[0] * 1.35f, center[1]);
    touch(MotionEvent.ACTION_UP, down, center[0] * 1.35f, center[1]);
    awaitTapConfirmation();
    assertVote(0);
    assertFalse(playRequested());
  }

  @Test
  public void secondTapDiagonalDragOrReleaseJumpCannotLikeOrTogglePlayback() throws Exception {
    launch(true);
    setPlaying(false);
    int initialIndex = onMain(() -> current().index);
    float distance = 40 * context.getResources().getDisplayMetrics().density;
    for (boolean includeMove : new boolean[] {true, false}) {
      tap();
      SystemClock.sleep(70);
      float[] center = center();
      long down = SystemClock.uptimeMillis();
      touch(MotionEvent.ACTION_DOWN, down, center[0], center[1]);
      SystemClock.sleep(30);
      if (includeMove)
        touch(MotionEvent.ACTION_MOVE, down, center[0] + distance, center[1] + distance);
      // Both axes are equal: neither directional gesture passes the 1.3 dominance threshold.
      // The release-only case also exercises a fast pointer jump with no delivered MOVE.
      touch(MotionEvent.ACTION_UP, down, center[0] + distance, center[1] + distance);
      awaitTapConfirmation();
      assertVote(0);
      assertFalse("A moved second tap cannot leak pause/resume", playRequested());
      assertEquals(
          "A diagonal tap cancellation cannot navigate",
          initialIndex,
          (int) onMain(() -> current().index));
    }
  }

  @Test
  public void pendingSingleTapCannotToggleTheNextVideo() throws Exception {
    launch(true);
    setPlaying(false);
    tap();
    clickAction("下一条");
    boolean nextRequested = playRequested();
    awaitTapConfirmation();
    assertTrue(onMain(() -> current().index == 1));
    assertEquals(
        "The prior page's pending tap cannot affect its successor", nextRequested, playRequested());
    assertVote(0);
  }

  @Test
  public void openingSettingsCancelsPendingSingleTap() throws Exception {
    launch(true);
    setPlaying(false);
    tap();
    onMain(
        () -> {
          invoke(player, "showSettings", new Class<?>[0]);
          return null;
        });
    awaitTapConfirmation();
    assertFalse("A pending screen tap cannot resume underneath settings", playRequested());
    assertVote(0);
  }

  @Test
  public void navigationShowsEachVideosOwnVoteAndLeavesPreloadedPageIntact() throws Exception {
    launch(true);
    setPlaying(false);
    clickField("likeButton");
    assertVote(1);
    clickAction("下一条");
    await(() -> onMain(() -> current().index == 1 && current().firstFrameReady));
    setPlaying(false);
    assertVote(0);
    clickField("dislikeButton");
    assertVote(-1);
    clickAction("上一条");
    await(() -> onMain(() -> current().index == 0 && current().firstFrameReady));
    setPlaying(false);
    assertVote(1);
    assertEquals(-1, (int) onMain(() -> votes().vote(videos.get(1))));
  }

  @Test
  public void resetNeedsConfirmationThenClearsWeightsButKeepsLifetimeCounts() throws Exception {
    store.recordPlay(videos.get(0));
    resetSeed = new PlaybackVoteSession(store);
    resetSeed.setVote(videos.get(0), 1);
    store.awaitIdle();
    catalog = mainRule.launchActivity(new Intent(context, MainActivity.class));
    openResetConfirmation();
    assertNotNull(exactText("清除由于观看和点赞/点踩产生的随机播放排序权重"));
    clickText("取消");
    store.awaitIdle();
    assertEquals(1, resetSeed.vote(videos.get(0)));
    assertTrue(store.recentScore(videos.get(0)) > .99);
    openResetConfirmation();
    clickText("重置");
    await(() -> resetSeed.vote(videos.get(0)) == 0 && store.recentScore(videos.get(0)) == 0);
    store.awaitIdle();
    assertEquals("Reset only affects preference weighting", 1, store.playCount(videos.get(0)));
    assertEquals(1, store.weight(videos.get(0)), 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void openingSettingsOrResetCancelsPendingRandomPlaybackBeforeIoCompletes()
      throws Exception {
    catalog = mainRule.launchActivity(new Intent(context, MainActivity.class));
    await(() -> onMain(() -> !(boolean) read(catalog, "scanning")));
    onMain(
        () -> {
          // Stop future library callbacks before replacing the test Activity's visible queue.
          // The underlying user's library/cache and all media files remain untouched.
          invoke(read(catalog, "mediaObserver"), "stop", new Class<?>[0]);
          ((Handler) read(catalog, "handler"))
              .removeCallbacks((Runnable) read(catalog, "reconcileMediaIndex"));
          List<VideoItem> all = (List<VideoItem>) read(catalog, "all");
          all.clear();
          all.addAll(videos);
          invoke(catalog, "filter", new Class<?>[0]);
          List<VideoItem> visible = (List<VideoItem>) read(catalog, "visible");
          visible.clear();
          visible.addAll(videos);
          return null;
        });
    ExecutorService io = (ExecutorService) read(store, "io");
    Instrumentation.ActivityMonitor launches =
        instrumentation.addMonitor(PlayerActivity.class.getName(), null, true);
    try {
      for (String entry : new String[] {"settings", "resetWeights"}) {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io.execute(
            () -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
        try {
          assertTrue(
              "Test owns the blocked statistics IO lane", entered.await(5, TimeUnit.SECONDS));
          onMain(
              () -> {
                assertEquals(videos, read(catalog, "visible"));
                invoke(catalog, "playRandom", new Class<?>[0]);
                assertTrue(
                    "Sampling is pending while IO is gated",
                    (boolean) read(catalog, "randomPending"));
                int request = (int) read(catalog, "randomRequest");
                invoke(catalog, entry, new Class<?>[0]);
                assertFalse(
                    "Opening another action cancels immediately",
                    (boolean) read(catalog, "randomPending"));
                assertNotEquals(request, (int) read(catalog, "randomRequest"));
                return null;
              });
          if (entry.equals("settings")) clickText("重置权重");
          await(() -> exactText("重置") != null && exactText("取消") != null);
          assertEquals(videos, onMain(() -> read(catalog, "visible")));
          release.countDown();
          store.awaitIdle();
          instrumentation.waitForIdleSync();
          assertEquals("A cancelled request must never launch a player", 0, launches.getHits());
          assertFalse(onMain(() -> (boolean) read(catalog, "randomPending")));
          assertNotNull("The reset confirmation remains in front", exactText("取消"));
          clickText("取消");
        } finally {
          release.countDown();
        }
      }
    } finally {
      // The monitor blocks even an unexpected launch, so no private media can be played on failure.
      // Drain the released IO lane and close this test Activity before removing that guard.
      try {
        store.awaitIdle();
        instrumentation.waitForIdleSync();
      } finally {
        onMain(
            () -> {
              catalog.finish();
              return null;
            });
        mainRule.finishActivity();
        instrumentation.waitForIdleSync();
        instrumentation.removeMonitor(launches);
      }
    }
  }

  @Test
  public void playerResetKeepsCurrentDecoderAndClearsSelectedButtons() throws Exception {
    store.recordPlay(videos.get(0));
    store.awaitIdle();
    launch(true);
    setPlaying(false);
    clickField("likeButton");
    assertVote(1);
    VideoPage active = onMain(this::current);
    int prepares = onMain(() -> current().prepareCount);
    onMain(
        () -> {
          invoke(player, "showSettings", new Class<?>[0]);
          return null;
        });
    clickText("重置权重");
    clickText("重置");
    await(
        () ->
            onMain(() -> votes().vote(videos.get(0))) == 0
                && store.recentScore(videos.get(0)) == 0);
    instrumentation.waitForIdleSync();
    assertVote(0);
    assertSame(active, onMain(this::current));
    assertEquals(prepares, (int) onMain(() -> current().prepareCount));
    assertFalse(playRequested());
  }

  @Test
  public void externalResetAfterRecreationClearsUiWithoutRepreparingCurrentVideo()
      throws Exception {
    store.recordPlay(videos.get(0));
    store.awaitIdle();
    launch(true);
    setPlaying(false);
    clickField("likeButton");
    assertVote(1);
    PlaybackVoteSession retained = onMain(this::votes);
    recreatePlayer();
    assertSame(retained, onMain(this::votes));
    assertVote(1);
    VideoPage active = onMain(this::current);
    int prepares = onMain(() -> current().prepareCount);
    AtomicReference<Boolean> result = new AtomicReference<>();
    // This callback deliberately has no reference to either Activity and refreshes no UI. It
    // models a durable reset completing after the dialog owner was destroyed and replaced.
    store.resetWeights(result::set);
    await(() -> result.get() != null);
    assertTrue("The isolated reset was committed", result.get());
    await(
        () ->
            onMain(
                () ->
                    !((ImageButton) read(player, "likeButton")).isSelected()
                        && !((ImageButton) read(player, "dislikeButton")).isSelected()));
    // Only the existing progress ticker/lifecycle can have reconciled the new generation above.
    assertVote(0);
    assertSame(active, onMain(this::current));
    assertEquals(prepares, (int) onMain(() -> current().prepareCount));
    assertFalse("Reset must not resume paused playback", playRequested());
    assertEquals("Historical counts survive reset", 1, store.playCount(videos.get(0)));
    assertEquals(1, store.weight(videos.get(0)), 0);
  }

  @Test
  public void feedbackLayoutAndStickerRemainAccessibleAndTouchThrough() throws Exception {
    launch(true);
    setPlaying(false);
    clickField("likeButton");
    assertTopBarLayout(false);
    captureFixtureOnly("portrait-liked.png");
    onMain(
        () -> {
          invoke(player, "setPortraitMode", new Class<?>[] {boolean.class}, false);
          return null;
        });
    awaitInputWindow(false);
    clickField("dislikeButton");
    assertTopBarLayout(true);
    assertCompactTopBar();
    captureFixtureOnly("fullscreen-disliked.png");
    onMain(
        () -> {
          invoke(player, "setPortraitMode", new Class<?>[] {boolean.class}, true);
          return null;
        });
    awaitInputWindow(true);
    WindowCapture baseline = onMain(this::requestFixtureWindowCopy);
    WindowCapture effect = null;
    try {
      awaitWindowCopy(baseline);
      float[] point =
          onMain(
              () -> {
                View view = (View) read(player, "touchLayer");
                return new float[] {view.getWidth() * .88f, view.getHeight() * .22f};
              });
      for (int tap = 0; tap < 2; tap++) {
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down, point[0], point[1]);
        SystemClock.sleep(30);
        touch(MotionEvent.ACTION_UP, down, point[0], point[1]);
        if (tap == 0) SystemClock.sleep(70);
      }
      SystemClock.sleep(100);
      effect =
          onMain(
              () -> {
                ViewGroup overlay = (ViewGroup) read(player, "likeEffect");
                android.widget.ImageView sticker = (android.widget.ImageView) overlay.getChildAt(0);
                assertEquals(View.VISIBLE, sticker.getVisibility());
                // Request the actual Window pixels before screenshot-idle waits, compression or
                // image inspection can outlast this intentionally short animation.
                WindowCapture copy = requestFixtureWindowCopy();
                Rect visible = new Rect();
                boolean globallyVisible = overlay.getGlobalVisibleRect(visible);
                int[] location = new int[2];
                overlay.getLocationInWindow(location);
                copy.stickerWidth = sticker.getWidth();
                copy.stickerHeight = sticker.getHeight();
                copy.imageAlpha = sticker.getImageAlpha();
                copy.viewAlpha = sticker.getAlpha();
                float x = location[0] + sticker.getX(), y = location[1] + sticker.getY();
                copy.stickerArea =
                    new Rect(
                        (int) Math.floor(x - sticker.getWidth() * .15f),
                        (int) Math.floor(y - sticker.getHeight() * .15f),
                        (int) Math.ceil(x + sticker.getWidth() * 1.15f),
                        (int) Math.ceil(y + sticker.getHeight() * 1.15f));
                Drawable drawable = sticker.getDrawable();
                if (drawable instanceof BitmapDrawable)
                  copy.source = ((BitmapDrawable) drawable).getBitmap();
                copy.properties =
                    "alpha="
                        + sticker.getAlpha()
                        + "\nimageAlpha="
                        + sticker.getImageAlpha()
                        + "\nscaleX="
                        + sticker.getScaleX()
                        + "\nscaleY="
                        + sticker.getScaleY()
                        + "\nwidth="
                        + sticker.getWidth()
                        + "\nheight="
                        + sticker.getHeight()
                        + "\nmeasuredWidth="
                        + sticker.getMeasuredWidth()
                        + "\nmeasuredHeight="
                        + sticker.getMeasuredHeight()
                        + "\nisShown="
                        + sticker.isShown()
                        + "\noverlayAlpha="
                        + overlay.getAlpha()
                        + "\noverlayVisibility="
                        + overlay.getVisibility()
                        + "\noverlayGlobalVisible="
                        + globallyVisible
                        + "\noverlayGlobalRect="
                        + visible.toShortString()
                        + "\nanimatorsEnabled="
                        + ValueAnimator.areAnimatorsEnabled()
                        + "\ndrawable="
                        + (drawable == null ? "null" : drawable.getClass().getSimpleName())
                        + "\ndrawableWidth="
                        + (drawable == null ? 0 : drawable.getIntrinsicWidth())
                        + "\ndrawableHeight="
                        + (drawable == null ? 0 : drawable.getIntrinsicHeight())
                        + "\nbitmapWidth="
                        + (copy.source == null ? 0 : copy.source.getWidth())
                        + "\nbitmapHeight="
                        + (copy.source == null ? 0 : copy.source.getHeight())
                        + "\nbitmapHasAlpha="
                        + (copy.source != null && copy.source.hasAlpha())
                        + "\n";
                assertTrue(sticker.getX() >= 0 && sticker.getY() >= 0);
                assertTrue(sticker.getX() + sticker.getWidth() <= overlay.getWidth());
                assertTrue(sticker.getY() + sticker.getHeight() <= overlay.getHeight());
                MotionEvent event =
                    MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
                try {
                  assertFalse("Feedback never steals a gesture", overlay.dispatchTouchEvent(event));
                } finally {
                  event.recycle();
                }
                return copy;
              });
      awaitWindowCopy(effect);
      int sourcePixels = nonTransparentPixels(effect.source);
      int changed = changedPixels(baseline.bitmap, effect.bitmap, effect.stickerArea);
      String diagnostics =
          effect.properties
              + "sourceNonTransparentPixels="
              + sourcePixels
              + "\nwindowChangedPixelsInStickerArea="
              + changed
              + "\ncopyResult="
              + effect.result
              + "\n";
      saveWindowEvidence(baseline.bitmap, effect.bitmap, diagnostics);
      assertTrue(
          "Sticker must be measured and laid out: " + diagnostics,
          effect.stickerWidth > 0 && effect.stickerHeight > 0);
      assertEquals(
          "View animation must not make the source ImageView drawable transparent",
          255,
          effect.imageAlpha);
      assertTrue("The animated View itself is visible: " + diagnostics, effect.viewAlpha > 0);
      assertTrue(
          "The decoded supplied bitmap has visible pixels: " + diagnostics, sourcePixels > 0);
      assertTrue(
          "The sticker must visibly change the paused Window at its position: " + diagnostics,
          changed > Math.max(64, effect.stickerWidth * effect.stickerHeight / 2000));
    } finally {
      baseline.close();
      if (effect != null) effect.close();
    }
    captureFixtureOnly("portrait-like-effect.png");
    assertVote(1);
    await(
        () ->
            onMain(
                () ->
                    ((ViewGroup) read(player, "likeEffect")).getChildAt(0).getVisibility()
                        != View.VISIBLE));
  }

  private void assertTopBarLayout(boolean rotationVisible) throws Exception {
    onMain(
        () -> {
          ViewGroup bar = (ViewGroup) read(player, "topBar");
          View counter = (View) read(player, "subtitle");
          View like = (View) read(player, "likeButton");
          View dislike = (View) read(player, "dislikeButton");
          View rotate = (View) read(player, "rotateButton");
          int target = Math.round(48 * context.getResources().getDisplayMetrics().density);
          assertTrue(like.getWidth() >= target && like.getHeight() >= target);
          assertTrue(dislike.getWidth() >= target && dislike.getHeight() >= target);
          assertTrue(counter.getRight() <= like.getLeft());
          assertTrue(like.getRight() <= dislike.getLeft());
          if (rotationVisible) assertTrue(dislike.getRight() <= rotate.getLeft());
          assertTrue(dislike.getRight() <= bar.getWidth() - bar.getPaddingRight());
          return null;
        });
  }

  /** Uses the API 26 Window copy path; no main-thread wait can stall the short animation. */
  private WindowCapture requestFixtureWindowCopy() throws Exception {
    assertEquals(Looper.getMainLooper(), Looper.myLooper());
    assertFixtureWindow();
    View decor = player.getWindow().getDecorView();
    WindowCapture copy =
        new WindowCapture(
            player,
            Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888));
    try {
      // https://developer.android.com/reference/android/view/PixelCopy
      PixelCopy.request(
          player.getWindow(), copy.bitmap, copy::complete, new Handler(Looper.getMainLooper()));
      return copy;
    } catch (RuntimeException | Error failure) {
      copy.complete(PixelCopy.ERROR_SOURCE_INVALID);
      copy.close();
      throw failure;
    }
  }

  private void awaitWindowCopy(WindowCapture copy) throws Exception {
    assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
    assertTrue("Window copy completed", copy.done.await(5, TimeUnit.SECONDS));
    assertEquals("Window copy succeeded", PixelCopy.SUCCESS, copy.result);
    onMain(
        () -> {
          assertSame(copy.owner, player);
          assertFixtureWindow();
          return null;
        });
  }

  private void assertFixtureWindow() throws Exception {
    assertTrue(player.hasWindowFocus());
    assertTrue((boolean) read(player, "resumed"));
    assertNotNull(current().item);
    assertEquals("01_Aurora.mp4", current().item.name);
    assertEquals(videos.get(0).uri, current().item.uri);
  }

  private void saveWindowEvidence(Bitmap before, Bitmap shown, String diagnostics)
      throws Exception {
    if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureFeedbackUi")))
      return;
    onMain(
        () -> {
          assertFixtureWindow();
          return null;
        });
    File directory = new File(context.getExternalFilesDir(null), "feedback-ui");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    try (FileOutputStream output =
        new FileOutputStream(new File(directory, "sticker-before.png"))) {
      assertTrue(before.compress(Bitmap.CompressFormat.PNG, 100, output));
    }
    try (FileOutputStream output =
        new FileOutputStream(new File(directory, "sticker-window.png"))) {
      assertTrue(shown.compress(Bitmap.CompressFormat.PNG, 100, output));
    }
    try (FileOutputStream output =
        new FileOutputStream(new File(directory, "sticker-properties.txt"))) {
      output.write(diagnostics.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static int nonTransparentPixels(Bitmap bitmap) {
    if (bitmap == null) return 0;
    int count = 0;
    int[] row = new int[bitmap.getWidth()];
    for (int y = 0; y < bitmap.getHeight(); y++) {
      bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
      for (int pixel : row) if ((pixel >>> 24) != 0) count++;
    }
    return count;
  }

  private static int changedPixels(Bitmap before, Bitmap shown, Rect stickerArea) {
    assertEquals(before.getWidth(), shown.getWidth());
    assertEquals(before.getHeight(), shown.getHeight());
    Rect area = new Rect(stickerArea);
    if (!area.intersect(0, 0, before.getWidth(), before.getHeight())) return 0;
    int count = 0;
    int[] a = new int[area.width()], b = new int[area.width()];
    for (int y = area.top; y < area.bottom; y++) {
      before.getPixels(a, 0, a.length, area.left, y, a.length, 1);
      shown.getPixels(b, 0, b.length, area.left, y, b.length, 1);
      for (int x = 0; x < a.length; x++) {
        int difference =
            Math.abs((a[x] >>> 16 & 255) - (b[x] >>> 16 & 255))
                + Math.abs((a[x] >>> 8 & 255) - (b[x] >>> 8 & 255))
                + Math.abs((a[x] & 255) - (b[x] & 255));
        if (difference > 24) count++;
      }
    }
    return count;
  }

  private static final class WindowCapture implements AutoCloseable {
    final PlayerActivity owner;
    final Bitmap bitmap;
    final CountDownLatch done = new CountDownLatch(1);
    volatile int result = -1;
    Bitmap source;
    Rect stickerArea;
    String properties;
    int stickerWidth, stickerHeight, imageAlpha;
    float viewAlpha;
    boolean closed;

    WindowCapture(PlayerActivity owner, Bitmap bitmap) {
      this.owner = owner;
      this.bitmap = bitmap;
    }

    synchronized void complete(int code) {
      result = code;
      done.countDown();
      if (closed) bitmap.recycle();
    }

    @Override
    public synchronized void close() {
      closed = true;
      if (done.getCount() == 0) bitmap.recycle();
    }
  }

  @Test
  public void stampFeedbackDeformsAndRotatesWithoutClippingAtAnyCorner() throws Exception {
    org.junit.Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
    launch(true);
    setPlaying(false);
    LikeEffectView overlay = onMain(() -> (LikeEffectView) read(player, "likeEffect"));
    boolean[] observed = new boolean[2];
    for (int corner = 0; corner < 4; corner++) {
      final int edge = corner;
      onMain(
          () -> {
            overlay.showAt(
                (edge & 1) == 0 ? 0 : overlay.getWidth(),
                (edge & 2) == 0 ? 0 : overlay.getHeight());
            return null;
          });
      await(
          () ->
              onMain(
                  () -> {
                    View sticker = overlay.getChildAt(0);
                    if (sticker.getVisibility() != View.VISIBLE) return true;
                    assertTrue(sticker.getWidth() > 0 && sticker.getHeight() > 0);
                    RectF bounds = new RectF(0, 0, sticker.getWidth(), sticker.getHeight());
                    sticker.getMatrix().mapRect(bounds);
                    bounds.offset(sticker.getLeft(), sticker.getTop());
                    assertTrue(
                        "Rotated sticker stays inside the screen: " + bounds,
                        bounds.left >= -.5f
                            && bounds.top >= -.5f
                            && bounds.right <= overlay.getWidth() + .5f
                            && bounds.bottom <= overlay.getHeight() + .5f);
                    if (sticker.getAlpha() > .5f) {
                      observed[0] |= Math.abs(sticker.getScaleX() - sticker.getScaleY()) > .02f;
                      observed[1] |= Math.abs(sticker.getRotation()) > .5f;
                    }
                    return false;
                  }));
    }
    assertTrue("Visible feedback includes squash and rebound", observed[0]);
    assertTrue("Visible feedback includes a light rotation", observed[1]);
  }

  @Test
  public void stampRetapContinuesCurrentPoseAndClearCancelsEveryPendingPhase() throws Exception {
    org.junit.Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
    launch(true);
    setPlaying(false);
    LikeEffectView overlay = onMain(() -> (LikeEffectView) read(player, "likeEffect"));
    onMain(
        () -> {
          overlay.showAt(overlay.getWidth() * .3f, overlay.getHeight() * .3f);
          return null;
        });
    await(() -> onMain(() -> overlay.getChildAt(0).getAlpha() >= .98f));
    // Retarget during the exit: its old completion must not hide the new feedback.
    await(
        () ->
            onMain(
                () -> {
                  View sticker = overlay.getChildAt(0);
                  return sticker.getVisibility() == View.VISIBLE
                      && sticker.getAlpha() > .2f
                      && sticker.getAlpha() < .8f;
                }));
    onMain(
        () -> {
          View sticker = overlay.getChildAt(0);
          float[] before = {
            sticker.getScaleX(),
            sticker.getScaleY(),
            sticker.getRotation(),
            sticker.getTranslationX(),
            sticker.getTranslationY(),
            sticker.getAlpha()
          };
          overlay.showAt(overlay.getWidth() * .7f, overlay.getHeight() * .6f);
          assertArrayEquals(
              "Retapping preserves the current pose until the next frame",
              before,
              new float[] {
                sticker.getScaleX(),
                sticker.getScaleY(),
                sticker.getRotation(),
                sticker.getTranslationX(),
                sticker.getTranslationY(),
                sticker.getAlpha()
              },
              .0001f);
          return null;
        });
    SystemClock.sleep(250);
    onMain(
        () -> {
          View sticker = overlay.getChildAt(0);
          assertEquals(View.VISIBLE, sticker.getVisibility());
          assertTrue(sticker.getAlpha() > .5f);
          overlay.clear();
          assertEquals(View.INVISIBLE, sticker.getVisibility());
          assertEquals(0, sticker.getRotation(), 0);
          assertEquals(1, sticker.getScaleX(), 0);
          assertEquals(1, sticker.getScaleY(), 0);
          return null;
        });
    SystemClock.sleep(1100);
    onMain(
        () -> {
          View sticker = overlay.getChildAt(0);
          assertEquals(
              "Cancelled phases cannot resurrect feedback",
              View.INVISIBLE,
              sticker.getVisibility());
          assertEquals(0, sticker.getAlpha(), 0);
          assertEquals(0, sticker.getRotation(), 0);
          return null;
        });
  }

  @Test
  public void reducedMotionUsesStaticFeedbackThenClearsWithoutDeviceSettingChanges()
      throws Exception {
    launch(true);
    setPlaying(false);
    LikeEffectView overlay =
        onMain(
            () -> {
              LikeEffectView view = new LikeEffectView(player, () -> false);
              ((FrameLayout) read(player, "root"))
                  .addView(view, new FrameLayout.LayoutParams(-1, -1));
              return view;
            });
    try {
      await(() -> onMain(() -> overlay.getWidth() > 0 && overlay.getHeight() > 0));
      float[] position =
          onMain(
              () -> {
                overlay.showAt(overlay.getWidth() * .5f, overlay.getHeight() * .5f);
                View sticker = overlay.getChildAt(0);
                assertEquals(View.VISIBLE, sticker.getVisibility());
                assertEquals(1, sticker.getAlpha(), 0);
                assertEquals(1, sticker.getScaleX(), 0);
                assertEquals(1, sticker.getScaleY(), 0);
                assertEquals(0, sticker.getRotation(), 0);
                return new float[] {sticker.getTranslationX(), sticker.getTranslationY()};
              });
      SystemClock.sleep(100);
      onMain(
          () -> {
            View sticker = overlay.getChildAt(0);
            assertEquals(position[0], sticker.getTranslationX(), 0);
            assertEquals(position[1], sticker.getTranslationY(), 0);
            assertEquals(1, sticker.getScaleX(), 0);
            assertEquals(1, sticker.getScaleY(), 0);
            assertEquals(0, sticker.getRotation(), 0);
            return null;
          });
      await(() -> onMain(() -> overlay.getChildAt(0).getVisibility() != View.VISIBLE));
    } finally {
      onMain(
          () -> {
            ((FrameLayout) read(player, "root")).removeView(overlay);
            return null;
          });
    }
  }

  @Test
  public void compactPortraitAndLandscapeBarsKeepControlsUsableWithLargeText() throws Exception {
    launch(false);
    setPlaying(false);
    onMain(
        () -> {
          // A scoped configuration context supplies large-text metrics only for these views.
          // Neither the device configuration nor the application's shared Resources are mutated.
          Configuration larger = new Configuration(player.getResources().getConfiguration());
          larger.fontScale = 2f;
          Context scaled = player.createConfigurationContext(larger);
          ViewGroup[] bars = {
            (ViewGroup) read(player, "topBar"), (ViewGroup) read(player, "bottomBar")
          };
          Map<TextView, Float> sizes = new HashMap<>();
          Map<TextView, CharSequence> labels = new HashMap<>();
          int[][] original = new int[bars.length][8];
          for (int index = 0; index < bars.length; index++) {
            ViewGroup bar = bars[index];
            collectTextSizes(bar, sizes);
            original[index] =
                new int[] {
                  bar.getLeft(), bar.getTop(), bar.getRight(), bar.getBottom(),
                  bar.getPaddingLeft(), bar.getPaddingTop(), bar.getPaddingRight(),
                      bar.getPaddingBottom()
                };
          }
          try {
            for (Map.Entry<TextView, Float> entry : sizes.entrySet()) {
              TextView text = entry.getKey();
              labels.put(text, text.getText());
              float sp =
                  Build.VERSION.SDK_INT >= 34
                      ? TypedValue.deriveDimension(
                          TypedValue.COMPLEX_UNIT_SP,
                          entry.getValue(),
                          player.getResources().getDisplayMetrics())
                      : entry.getValue() / player.getResources().getDisplayMetrics().scaledDensity;
              text.setTextSize(
                  TypedValue.COMPLEX_UNIT_PX,
                  TypedValue.applyDimension(
                      TypedValue.COMPLEX_UNIT_SP, sp, scaled.getResources().getDisplayMetrics()));
            }
            ((TextView) read(player, "title")).setText("长视频文件名 Video 2026 — 本地测试.mp4");
            ((TextView) read(player, "subtitle")).setText("1000000 / 1000000");
            ((TextView) read(player, "currentTime")).setText("01:23:45");
            ((TextView) read(player, "totalTime")).setText("12:34:56");
            float density = player.getResources().getDisplayMetrics().density;
            for (int availableDp : new int[] {360, 640}) {
              int width = Math.round(availableDp * density);
              for (int index = 0; index < bars.length; index++) {
                ViewGroup bar = bars[index];
                // Test content widths; physical cutout/gesture insets stay owned by the window.
                int gutter = Math.round((index == 0 ? 8 : 16) * density);
                bar.setPadding(gutter, original[index][5], gutter, original[index][7]);
                bar.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(
                        Math.round(240 * density), View.MeasureSpec.AT_MOST));
                bar.layout(0, 0, width, bar.getMeasuredHeight());
                assertUsableBounds(bar, Math.round(48 * density));
              }
              for (TextView text : sizes.keySet()) {
                if (text.getVisibility() != View.VISIBLE || text.getWidth() == 0) continue;
                assertNotNull(text.getLayout());
                assertTrue(
                    "Large text stays on its intended single line",
                    text.getLayout().getLineCount() <= 1);
                assertTrue(
                    "Large text is not clipped vertically",
                    text.getLayout().getHeight()
                        <= text.getHeight()
                            - text.getCompoundPaddingTop()
                            - text.getCompoundPaddingBottom());
              }
            }
          } finally {
            for (Map.Entry<TextView, Float> entry : sizes.entrySet()) {
              entry.getKey().setTextSize(TypedValue.COMPLEX_UNIT_PX, entry.getValue());
              if (labels.containsKey(entry.getKey()))
                entry.getKey().setText(labels.get(entry.getKey()));
            }
            for (int index = 0; index < bars.length; index++) {
              ViewGroup bar = bars[index];
              int[] old = original[index];
              bar.setPadding(old[4], old[5], old[6], old[7]);
              bar.measure(
                  View.MeasureSpec.makeMeasureSpec(old[2] - old[0], View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(old[3] - old[1], View.MeasureSpec.EXACTLY));
              bar.layout(old[0], old[1], old[2], old[3]);
              bar.requestLayout();
            }
          }
          return null;
        });
  }

  private static void collectTextSizes(View view, Map<TextView, Float> sizes) {
    if (view instanceof TextView) sizes.put((TextView) view, ((TextView) view).getTextSize());
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++)
        collectTextSizes(group.getChildAt(index), sizes);
    }
  }

  private static void assertUsableBounds(ViewGroup parent, int minimumTarget) {
    for (int index = 0; index < parent.getChildCount(); index++) {
      View child = parent.getChildAt(index);
      if (child.getVisibility() == View.GONE) continue;
      assertTrue(
          "Control must fit its content width",
          child.getLeft() >= parent.getPaddingLeft()
              && child.getRight() <= parent.getWidth() - parent.getPaddingRight());
      assertTrue(
          "Control must fit its content height",
          child.getTop() >= parent.getPaddingTop()
              && child.getBottom() <= parent.getHeight() - parent.getPaddingBottom());
      if (child instanceof ImageButton) {
        assertTrue(
            "Buttons retain Android touch targets",
            child.getWidth() >= minimumTarget && child.getHeight() >= minimumTarget);
      }
      if (child instanceof ViewGroup) assertUsableBounds((ViewGroup) child, minimumTarget);
    }
  }

  private double seedModerateExposure() throws Exception {
    for (int count = 0; count < 4; count++) store.recordPlay(videos.get(0));
    store.awaitIdle();
    return store.weight(videos.get(0));
  }

  private void assertCompactTopBar() throws Exception {
    onMain(
        () -> {
          ViewGroup bar = (ViewGroup) read(player, "topBar");
          TextView counter = (TextView) read(player, "subtitle");
          CharSequence text = counter.getText();
          int left = bar.getPaddingLeft(), top = bar.getPaddingTop();
          int right = bar.getPaddingRight(), bottom = bar.getPaddingBottom();
          int oldWidth = bar.getWidth(), oldHeight = bar.getHeight();
          float density = context.getResources().getDisplayMetrics().density;
          int gutter = Math.round(8 * density), width = Math.round(320 * density);
          try {
            bar.setPadding(gutter, top, gutter, bottom);
            counter.setText("1000000 / 1000000");
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(oldHeight, View.MeasureSpec.AT_MOST));
            bar.layout(0, 0, width, bar.getMeasuredHeight());
            for (int i = 0; i < bar.getChildCount(); i++) {
              View child = bar.getChildAt(i);
              if (child.getVisibility() != View.GONE)
                assertTrue(
                    "Compact toolbar keeps every control inside its available width",
                    child.getRight() <= width - gutter);
            }
          } finally {
            counter.setText(text);
            bar.setPadding(left, top, right, bottom);
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(oldWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(oldHeight, View.MeasureSpec.EXACTLY));
            bar.layout(0, 0, oldWidth, oldHeight);
            bar.requestLayout();
          }
          return null;
        });
  }

  private void captureFixtureOnly(String name) throws Exception {
    if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureFeedbackUi")))
      return;
    await(
        () ->
            onMain(
                () ->
                    player.hasWindowFocus()
                        && (boolean) read(player, "resumed")
                        && current().item != null
                        && current().item.name.equals("01_Aurora.mp4")
                        && current().item.uri.equals(videos.get(0).uri)));
    assertTrue(
        onMain(
            () ->
                player.hasWindowFocus()
                    && current().item.name.equals("01_Aurora.mp4")
                    && current().item.uri.equals(videos.get(0).uri)));
    File directory = new File(context.getExternalFilesDir(null), "feedback-ui");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(screenshot);
    try {
      // A window change during capture must not save another surface or an unrelated video.
      assertTrue(
          onMain(
              () ->
                  player.hasWindowFocus()
                      && current().item.name.equals("01_Aurora.mp4")
                      && current().item.uri.equals(videos.get(0).uri)));
      try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
      }
    } finally {
      screenshot.recycle();
    }
  }

  private void openResetConfirmation() throws Exception {
    onMain(
        () -> {
          invoke(catalog, "settings", new Class<?>[0]);
          return null;
        });
    clickText("重置权重");
    await(() -> exactText("重置") != null && exactText("取消") != null);
    assertEquals("Opening confirmation cannot reset data", 1, resetSeed.vote(videos.get(0)));
  }

  private void launch(boolean portrait) throws Exception {
    player =
        playerRule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, videos))
                .putExtra("index", 0));
    await(() -> onMain(() -> current().firstFrameReady && !current().buffering));
    setPlaying(false);
    if (portrait)
      onMain(
          () -> {
            invoke(player, "setPortraitMode", new Class<?>[] {boolean.class}, true);
            return null;
          });
    awaitInputWindow(portrait || videos.get(0).height > videos.get(0).width);
  }

  private void awaitInputWindow(boolean expectedPortrait) throws Exception {
    long[] stableSince = {0};
    await(
        () ->
            onMain(
                () -> {
                  View touch = (View) read(player, "touchLayer");
                  boolean ready =
                      player.hasWindowFocus()
                          && (boolean) read(player, "resumed")
                          && touch.isLaidOut()
                          && !touch.isLayoutRequested()
                          && player.getResources().getConfiguration().orientation
                              == (expectedPortrait
                                  ? Configuration.ORIENTATION_PORTRAIT
                                  : Configuration.ORIENTATION_LANDSCAPE)
                          && (expectedPortrait
                              ? touch.getHeight() > touch.getWidth()
                              : touch.getWidth() > touch.getHeight());
                  if (!ready) {
                    stableSince[0] = 0;
                    return false;
                  }
                  long now = SystemClock.uptimeMillis();
                  if (stableSince[0] == 0) stableSince[0] = now;
                  // Orientation can lay out the new size before focus is handed back. Require a
                  // settled input window so focus-loss cancellation cannot swallow the first tap.
                  // Also let the system's rotation screenshot leave SurfaceFlinger before QA
                  // captures the actual new surface or sends the first gesture.
                  return now - stableSince[0] >= 500;
                }));
    instrumentation.waitForIdleSync();
  }

  private void recreatePlayer() throws Exception {
    PlayerActivity original = player;
    AtomicReference<PlayerActivity> replacement = new AtomicReference<>();
    ActivityLifecycleCallback callback =
        (Activity candidate, Stage stage) -> {
          if (candidate instanceof PlayerActivity
              && candidate != original
              && stage == Stage.RESUMED) replacement.set((PlayerActivity) candidate);
        };
    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
    try {
      onMain(
          () -> {
            player.recreate();
            return null;
          });
      await(() -> replacement.get() != null);
      player = replacement.get();
      await(() -> onMain(() -> current().firstFrameReady && !current().buffering));
      setPlaying(false);
    } finally {
      ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
    }
  }

  private void assertVote(int expected) throws Exception {
    onMain(
        () -> {
          VideoItem item = current().item;
          assertNotNull(item);
          assertEquals(expected, votes().vote(item));
          ImageButton like = (ImageButton) read(player, "likeButton");
          ImageButton dislike = (ImageButton) read(player, "dislikeButton");
          assertEquals(expected == 1, like.isSelected());
          assertEquals(expected == -1, dislike.isSelected());
          assertEquals(expected == 1 ? "取消点赞" : "点赞", like.getContentDescription().toString());
          assertEquals(expected == -1 ? "取消点踩" : "点踩", dislike.getContentDescription().toString());
          assertEquals(View.VISIBLE, like.getVisibility());
          assertEquals(View.VISIBLE, dislike.getVisibility());
          return null;
        });
  }

  private void clickField(String field) throws Exception {
    onMain(
        () -> {
          assertTrue(((View) read(player, field)).performClick());
          return null;
        });
  }

  private void clickAction(String label) throws Exception {
    onMain(
        () -> {
          View button = action((View) read(player, "bottomBar"), label);
          assertNotNull(label, button);
          assertTrue(button.performClick());
          return null;
        });
  }

  private static View action(View view, String label) {
    if (label.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = action(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void setPlaying(boolean play) throws Exception {
    onMain(
        () -> {
          invoke(player, "setPlaying", new Class<?>[] {boolean.class}, play);
          return null;
        });
  }

  private boolean playRequested() throws Exception {
    return onMain(() -> (boolean) invoke(player, "playRequested", new Class<?>[0]));
  }

  private float[] center() throws Exception {
    return onMain(
        () -> {
          View view = (View) read(player, "touchLayer");
          return new float[] {view.getWidth() * .5f, view.getHeight() * .55f};
        });
  }

  private void tap() throws Exception {
    float[] point = center();
    long down = SystemClock.uptimeMillis();
    touch(MotionEvent.ACTION_DOWN, down, point[0], point[1]);
    SystemClock.sleep(30);
    touch(MotionEvent.ACTION_UP, down, point[0], point[1]);
  }

  private void doubleTap() throws Exception {
    tap();
    SystemClock.sleep(70);
    tap();
  }

  private void touch(int action, long down, float x, float y) throws Exception {
    onMain(
        () -> {
          MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
          try {
            assertTrue(((View) read(player, "touchLayer")).dispatchTouchEvent(event));
          } finally {
            event.recycle();
          }
          return null;
        });
  }

  private void awaitTapConfirmation() {
    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 100L);
    instrumentation.waitForIdleSync();
  }

  private void clickText(String label) throws Exception {
    await(() -> exactText(label) != null);
    AccessibilityNodeInfo node = exactText(label);
    assertNotNull(label, node);
    boolean clicked = false;
    for (int attempt = 0; node != null && attempt < 3; attempt++, node = node.getParent()) {
      if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        clicked = true;
        break;
      }
    }
    assertTrue(label + " must be actionable", clicked);
    instrumentation.waitForIdleSync();
  }

  private AccessibilityNodeInfo exactText(String label) {
    AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
    if (root == null) return null;
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
      if (label.contentEquals(node.getText())) return node;
    return null;
  }

  private VideoItem fixture(String name) throws Exception {
    String root = InstrumentationRegistry.getArguments().getString("fixtureRoot", "").trim();
    assertFalse("Use the dedicated generated-fixture directory", root.isEmpty());
    assertTrue(new File(root).isAbsolute());
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "_size", "duration", "width", "height"},
                "_display_name=? AND _data=?",
                new String[] {name, new File(root, name).getCanonicalPath()},
                null)) {
      assertNotNull(cursor);
      assertEquals("Exactly one generated fixture: " + name, 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      return new VideoItem(
          "feedback-test-" + name,
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

  private VideoPage current() throws Exception {
    return (VideoPage) read(player, "currentPage");
  }

  private PlaybackVoteSession votes() throws Exception {
    return (PlaybackVoteSession) read(player, "voteSession");
  }

  private static Object read(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, args);
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
    if (failure.get() instanceof Error) throw (Error) failure.get();
    if (failure.get() instanceof Exception) throw (Exception) failure.get();
    return result.get();
  }

  private void await(Callable<Boolean> condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 20000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(30);
    }
    fail("Feedback interaction condition did not become true");
  }
}
