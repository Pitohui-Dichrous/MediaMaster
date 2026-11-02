package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
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
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real generated-media playback with a private, disposable statistics database. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class PlaybackEntryStatsTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private PlayerActivity activity;
  private PlaybackStatsStore store, originalStore;
  private Field singleton;
  private String databaseName, searchRoundId;
  private boolean storeInstalled, preferencesSaved;
  private ScopedPlaybackHistory history;
  private final Map<String, Integer> preferences = new HashMap<>();
  private final ArrayList<VideoItem> videos = new ArrayList<>();

  @Before
  public void prepareIsolatedStoreAndGeneratedQueue() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    SharedPreferences player = context.getSharedPreferences("player", 0);
    for (String name :
        new String[] {
          "playback_mode", "portrait_navigation_mode", "engine_choice", "native_decoder_mode"
        }) {
      preferences.put(name, player.contains(name) ? player.getInt(name, 0) : null);
    }
    preferencesSaved = true;
    assertTrue(
        player
            .edit()
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .putInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL)
            .putInt("engine_choice", 1)
            .putInt("native_decoder_mode", NativeDecoderPolicy.AUTO)
            .commit());
    databaseName = "test_playback_entries_" + UUID.randomUUID() + ".db";
    store = new PlaybackStatsStore(context, databaseName, 50);
    store.awaitIdle();
    onMain(
        () -> {
          history = ScopedPlaybackHistory.open(context);
          singleton = PlaybackStatsStore.class.getDeclaredField("instance");
          singleton.setAccessible(true);
          originalStore = (PlaybackStatsStore) singleton.get(null);
          singleton.set(null, store);
          storeInstalled = true;
          return null;
        });
    videos.add(fixture("01_Aurora.mp4"));
    videos.add(fixture("04_Portrait_Loop.mp4"));
  }

  @After
  public void restoreOnlyOwnedState() throws Exception {
    try {
      if (activity != null) {
        onMain(
            () -> {
              activity.finish();
              return null;
            });
        rule.finishActivity();
      }
      if (history != null) history.closeAfterActivityDestroyed(activity);
    } finally {
      if (storeInstalled)
        onMain(
            () -> {
              assertSame("The test still owns its isolated statistics", store, singleton.get(null));
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
  public void shortEntriesNeverCountAndReturningStartsASeparateWatchInterval() throws Exception {
    launch(videos);
    awaitWatching(1000);
    pauseAndReadWatched();
    assertEquals(0, store.playCount(videos.get(0)));
    click("下一条");
    await(() -> onMain(() -> current().index == 1));
    awaitWatching(1000);
    pauseAndReadWatched();
    assertEquals(0, store.playCount(videos.get(1)));
    click("上一条");
    await(() -> onMain(() -> current().index == 0));
    awaitWatching(1000);
    long watched = pauseAndReadWatched();
    assertTrue("Returning starts a new interval", watched < 2500);
    assertEquals(
        "Short visits never increase lifetime or recent counts", 0, store.playCount(videos.get(0)));
    assertEquals(0, store.playCount(videos.get(1)));
    resume();
    awaitCount(0, 1);
  }

  @Test
  public void quickManualNavigationAndRepeatedCloseContributeOnlyOneDislikePerVideo()
      throws Exception {
    launch(videos);
    pauseReadyAt(1499);
    click("下一条");
    await(() -> onMain(() -> current().index == 1));
    pauseReadyAt(1500);
    assertFeedback(0, 0, 1);
    assertEquals(0, store.playCount(videos.get(0)));
    click("上一条");
    await(() -> onMain(() -> current().index == 0));
    pauseReadyAt(1499);
    onMain(
        () -> {
          activity.finish();
          activity.finish();
          return null;
        });
    await(() -> onMain(() -> activity.isDestroyed()));
    assertFeedback(0, 0, 1);
    assertFeedback(1, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void manualCloseBelowOneAndAHalfSecondsCreatesOneDislike() throws Exception {
    launch(new ArrayList<>(videos.subList(0, 1)));
    pauseReadyAt(1499);
    finishAndWait();
    assertFeedback(0, 0, 1);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void halfSecondAccidentalOpenIsNotAnExtraDislike() throws Exception {
    launch(new ArrayList<>(videos.subList(0, 1)));
    pauseReadyAt(500);
    finishAndWait();
    assertFeedback(0, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void justOverHalfSecondGlanceCreatesAnExtraDislike() throws Exception {
    launch(new ArrayList<>(videos.subList(0, 1)));
    pauseReadyAt(501);
    finishAndWait();
    assertFeedback(0, 0, 1);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void manualCloseAtExactlyOneAndAHalfSecondsDoesNotDislike() throws Exception {
    launch(new ArrayList<>(videos.subList(0, 1)));
    pauseReadyAt(1500);
    finishAndWait();
    assertFeedback(0, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void searchViewingOverThreeSecondsLikesOnceWithoutRecordingAPlay() throws Exception {
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(0, 1, 0);
    assertManualFeedback(0);
    assertEquals(0, store.playCount(videos.get(0)));
    resume();
    await(() -> onMain(() -> current().isPlaying()));
    updateAndReadWatched();
    finishAndWait();
    assertFeedback(0, 1, 0);
    assertEquals(0, store.playCount(videos.get(0)));
    assertEquals(0, store.playCount(videos.get(1)));
  }

  @Test
  public void closingSearchResultQuicklyDoesNotDislikeOrRecordAPlay() throws Exception {
    launch(new ArrayList<>(videos.subList(0, 1)), true);
    pauseReadyAt(1499);
    finishAndWait();
    assertFeedback(0, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void everyVideoInSearchPlaybackIsExemptAndReceivesOnlyOneExtraLike() throws Exception {
    launch(videos, true);
    pauseReadyAt(1499);
    click("下一条");
    await(() -> onMain(() -> current().index == 1));
    awaitWatching(3001);
    pauseAndReadWatched();
    assertEquals(0, store.playCount(videos.get(0)));
    assertEquals(0, store.playCount(videos.get(1)));
    assertFeedback(0, 0, 0);
    assertFeedback(1, 1, 0);
    assertManualFeedback(0);
    click("上一条");
    await(() -> onMain(() -> current().index == 0));
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(0, 1, 0);
    click("下一条");
    await(() -> onMain(() -> current().index == 1));
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(1, 1, 0);
    assertEquals(0, store.playCount(videos.get(0)));
    assertEquals(0, store.playCount(videos.get(1)));
  }

  @Test
  public void extraDislikeStaysHiddenAndCanBeCancelledByManualLikeAfterReturning()
      throws Exception {
    launch(videos);
    pauseReadyAt(1000);
    click("下一条");
    await(() -> onMain(() -> current().index == 1));
    pauseReadyAt(1500);
    assertFeedback(0, 0, 1);
    click("上一条");
    await(() -> onMain(() -> current().index == 0));
    pauseReadyAt(1000);
    assertManualFeedback(0);
    click("点赞");
    assertManualFeedback(1);
    assertFeedback(0, 1, 0);
    click("取消点赞");
    assertManualFeedback(0);
    assertFeedback(0, 0, 0);
    click("下一条");
    await(() -> onMain(() -> current().index == 1));
    pauseReadyAt(1500);
    assertFeedback(0, 0, 0);
  }

  @Test
  public void manualLikeAddsSeparatelyToHiddenSearchLikeAndCancelsOnlyItsOwnMass()
      throws Exception {
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertManualFeedback(0);
    assertFeedback(0, 1, 0);
    click("点赞");
    assertManualFeedback(1);
    assertFeedback(0, 2, 0);
    click("取消点赞");
    assertManualFeedback(0);
    assertFeedback(0, 1, 0);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void searchRoundDeduplicatesReopenedPlayersAndRetainsManualDislikeCancellation()
      throws Exception {
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(0, 1, 0);
    finishAndWait();
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertManualFeedback(0);
    assertFeedback(0, 1, 0);
    click("点踩");
    assertManualFeedback(-1);
    assertFeedback(0, 0, 1);
    finishAndWait();
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertManualFeedback(0);
    assertFeedback(0, 0, 1);
    assertEquals(0, store.playCount(videos.get(0)));
    finishAndWait();
    searchRoundId = null;
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(0, 1, 1);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void unknownRestoredSearchRoundKeepsExemptionWithoutDuplicatingExtraLikes()
      throws Exception {
    searchRoundId = "restored-unavailable-round-" + UUID.randomUUID();
    launch(videos, true);
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(0, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
    assertManualFeedback(0);
    click("点赞");
    assertFeedback(0, 1, 0);
    assertManualFeedback(1);
  }

  @Test
  public void backgroundingAQuickEntryDoesNotCreateADislike() throws Exception {
    launch(videos);
    pauseReadyAt(1499);
    onMain(
        () -> {
          assertTrue(
              "Move only this test's playback task to the background",
              activity.moveTaskToBack(true));
          return null;
        });
    await(() -> onMain(() -> !(boolean) read("resumed") && !(boolean) read("started")));
    assertFeedback(0, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
    assertEquals(1499, updateAndReadWatched());
  }

  @Test
  public void searchSourceAndPartialViewingSurviveRecreationWithoutQuickDislike() throws Exception {
    launch(videos, true);
    pauseReadyAt(1000);
    recreateActivity();
    assertEquals(1000, updateAndReadWatched());
    assertFeedback(0, 0, 0);
    assertEquals(0, store.playCount(videos.get(0)));
    resume();
    awaitWatching(3001);
    pauseAndReadWatched();
    assertFeedback(0, 1, 0);
    assertEquals(0, store.playCount(videos.get(0)));
  }

  @Test
  public void nativePlaybackCountsOnlyQualifiedViewingAcrossPauseAndResume() throws Exception {
    assertTrue(
        context.getSharedPreferences("player", 0).edit().putInt("engine_choice", 2).commit());
    // Keep this check to one native decoder; the separate three-window test covers preloads.
    launch(new ArrayList<>(videos.subList(0, 1)));
    await(
        () ->
            onMain(
                () -> current().vlc != null && current().firstFrameReady && current().isPlaying()));
    awaitWatching(750);
    long watched = pauseAndReadWatched();
    assertTrue("Short native viewing has not reached the threshold", watched < 3000);
    assertEquals(0, store.playCount(videos.get(0)));
    SystemClock.sleep(3200);
    assertEquals("Paused native playback is not viewing", watched, updateAndReadWatched());
    assertEquals(0, store.playCount(videos.get(0)));
    resume();
    awaitCount(0, 1);
    assertTrue(onMain(() -> current().vlc != null));
    assertEquals("Only the played entry is recorded", 0, store.playCount(videos.get(1)));
    pauseAndReadWatched();
    assertEquals(1, store.playCount(videos.get(0)));
  }

  @Test
  public void pausedAndScrubbingTimeDoNotCountTowardActualViewing() throws Exception {
    launch(videos);
    awaitWatching(750);
    long watched = pauseAndReadWatched();
    SystemClock.sleep(3200);
    assertEquals(watched, updateAndReadWatched());
    assertEquals(0, store.playCount(videos.get(0)));
    assertFeedback(0, 0, 0);
    onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          invoke("beginPlayerScrub", new Class<?>[0]);
          return null;
        });
    long held = updateAndReadWatched();
    SystemClock.sleep(3200);
    assertEquals(
        "Seeking is not watching, even when the decoder renders previews",
        held,
        updateAndReadWatched());
    assertEquals(0, store.playCount(videos.get(0)));
    assertFeedback(0, 0, 0);
    onMain(
        () -> {
          invoke("endPlayerScrub", new Class<?>[] {long.class, boolean.class}, 1500L, true);
          return null;
        });
    awaitCount(0, 1);
  }

  @Test
  public void recreationPreservesPartialViewingButDoesNotCountPausedTime() throws Exception {
    launch(videos);
    awaitWatching(1000);
    long watched = pauseAndReadWatched();
    recreateActivity();
    assertEquals(watched, updateAndReadWatched());
    assertFeedback(0, 0, 0);
    SystemClock.sleep(3200);
    assertEquals(watched, updateAndReadWatched());
    assertEquals(0, store.playCount(videos.get(0)));
    assertFeedback(0, 0, 0);
    resume();
    awaitCount(0, 1);
    assertEquals(3001, updateAndReadWatched());
  }

  @Test
  public void savedStateFreezesTheOldEntryUntilItsNextResume() throws Exception {
    launch(videos);
    awaitWatching(1000);
    Bundle snapshot = new Bundle();
    onMain(
        () -> {
          invoke("onSaveInstanceState", new Class<?>[] {Bundle.class}, snapshot);
          return null;
        });
    long saved = snapshot.getLong("play_entry_watched_ms");
    assertFalse(snapshot.getBoolean("play_entry_recorded"));
    SystemClock.sleep(3200);
    assertEquals(
        "The still-visible old instance cannot outgrow its saved entry",
        saved,
        updateAndReadWatched());
    assertEquals(0, store.playCount(videos.get(0)));
    recreateActivity();
    awaitCount(0, 1);
    assertEquals(1, store.playCount(videos.get(0)));
  }

  @Test
  public void naturalLoopAccumulatesWatchTimeBeforeItsFirstCountAndDoesNotRecount()
      throws Exception {
    launch(new ArrayList<>(videos.subList(1, 2)));
    awaitWatching(500);
    pauseAndReadWatched();
    AtomicInteger loops = new AtomicInteger();
    onMain(
        () -> {
          current()
              .exo
              .addListener(
                  new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(MediaItem item, int reason) {
                      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                        loops.incrementAndGet();
                    }
                  });
          invoke("seekTo", new Class<?>[] {long.class}, Math.max(0, current().duration() - 250));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(() -> loops.get() > 0);
    assertEquals(
        "A quick loop is not yet three seconds of viewing", 0, store.playCount(videos.get(1)));
    awaitCount(1, 1);
    int firstLoops = loops.get();
    onMain(
        () -> {
          invoke("seekTo", new Class<?>[] {long.class}, Math.max(0, current().duration() - 250));
          return null;
        });
    await(() -> loops.get() > firstLoops);
    assertEquals(1, store.playCount(videos.get(1)));
  }

  @Test
  public void onlyActualNewEntriesCountAcrossPreloadResumeLoopAndCatalogReentry() throws Exception {
    launch(videos);
    awaitCount(0, 1);
    onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, false);
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, true);
          invoke("setPlaybackMode", new Class<?>[] {int.class}, PlaybackOrder.SHUFFLE);
          return null;
        });
    awaitNeighbors();
    assertEquals("Both prepared neighbors are unplayed", 0, store.playCount(videos.get(1)));
    for (int i = 0; i < 2; i++) {
      onMain(
          () -> {
            invoke("setPlaying", new Class<?>[] {boolean.class}, true);
            return null;
          });
      await(() -> onMain(() -> current().isPlaying()));
      onMain(
          () -> {
            invoke("setPlaying", new Class<?>[] {boolean.class}, false);
            invoke("seekTo", new Class<?>[] {long.class}, 1000L);
            return null;
          });
    }
    onMain(
        () -> {
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, false);
          invoke("setPortraitMode", new Class<?>[] {boolean.class}, true);
          return null;
        });
    awaitNeighbors();
    assertEquals(1, store.playCount(videos.get(0)));
    assertEquals(0, store.playCount(videos.get(1)));
    AtomicInteger loops = new AtomicInteger();
    onMain(
        () -> {
          ExoPlayer source = current().exo;
          source.addListener(
              new Player.Listener() {
                @Override
                public void onMediaItemTransition(MediaItem item, int reason) {
                  if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) loops.incrementAndGet();
                }
              });
          source.seekTo(Math.max(0, source.getDuration() - 350));
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
    await(() -> loops.get() > 0);
    assertEquals("Natural repeat does not create another entry", 1, store.playCount(videos.get(0)));
    assertEquals(0, store.playCount(videos.get(1)));
    click("下一条");
    awaitCount(1, 1);
    assertEquals(1, store.playCount(videos.get(0)));
    click("上一条");
    awaitCount(0, 2);
    assertEquals(1, store.playCount(videos.get(1)));
    rule.finishActivity();
    launch(videos);
    awaitCount(0, 3);
    assertEquals(
        "Returning from the catalog counts a new actual playback",
        3,
        store.playCount(videos.get(0)));
  }

  @Test
  public void recreationPreservesTheAlreadyCountedEntry() throws Exception {
    launch(videos);
    awaitCount(0, 1);
    recreateActivity();
    await(() -> onMain(() -> current().firstFrameReady && current().isPlaying()));
    updateAndReadWatched();
    assertEquals(
        "Recreated playback retains its already-counted entry", 1, store.playCount(videos.get(0)));
  }

  private void recreateActivity() throws Exception {
    PlayerActivity original = activity;
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
            activity.recreate();
            return null;
          });
      await(() -> replacement.get() != null);
      activity = replacement.get();
    } finally {
      ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
    }
  }

  @Test
  public void singleItemSequentialCompletionDoesNotIncreaseItsPlayCount() throws Exception {
    assertTrue(
        context
            .getSharedPreferences("player", 0)
            .edit()
            .putInt("playback_mode", PlaybackOrder.SEQUENTIAL)
            .commit());
    launch(new ArrayList<>(videos.subList(0, 1)));
    awaitCount(0, 1);
    int prepares = onMain(() -> current().prepareCount);
    onMain(
        () -> {
          current().exo.seekTo(Math.max(0, current().exo.getDuration() - 300));
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    current().prepareCount > prepares
                        && current().firstFrameReady
                        && current().isPlaying()
                        && current().position() < 2000));
    onMain(
        () -> {
          invoke("updateProgress", new Class<?>[0]);
          return null;
        });
    assertEquals(
        "A one-video automatic wrap is still the same entry", 1, store.playCount(videos.get(0)));
  }

  @Test
  public void singleItemAutomaticWrapKeepsPartialViewingAndSearchSource() throws Exception {
    launch(new ArrayList<>(videos.subList(0, 1)), true);
    pauseReadyAt(1000);
    onMain(
        () -> {
          invoke("navigateRelative", new Class<?>[] {boolean.class, boolean.class}, true, true);
          return null;
        });
    awaitWatching(3001);
    pauseAndReadWatched();
    assertEquals(0, store.playCount(videos.get(0)));
    assertFeedback(0, 1, 0);
  }

  @Test
  public void failedMediaNeverCountsAsPlayback() throws Exception {
    VideoItem invalid =
        new VideoItem(
            "test-missing-entry",
            "missing.mp4",
            Uri.fromFile(new File(context.getCacheDir(), "test-only-missing-entry.mp4")).toString(),
            "",
            0,
            0,
            0,
            0,
            640,
            360);
    ArrayList<VideoItem> missing = new ArrayList<>();
    missing.add(invalid);
    launch(missing);
    await(() -> onMain(() -> current().failed));
    onMain(
        () -> {
          invoke("updateProgress", new Class<?>[0]);
          return null;
        });
    assertEquals(0, store.playCount(invalid));
  }

  private void launch(ArrayList<VideoItem> items) {
    launch(items, false);
  }

  private void launch(ArrayList<VideoItem> items, boolean fromSearch) {
    if (fromSearch && searchRoundId == null) searchRoundId = SearchFeedbackRounds.create(store);
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, items))
                .putExtra("index", 0)
                .putExtra("from_search", fromSearch)
                .putExtra("search_round", fromSearch ? searchRoundId : null));
  }

  private void assertManualFeedback(int expected) throws Exception {
    onMain(
        () -> {
          assertEquals(expected, ((PlaybackVoteSession) read("voteSession")).vote(current().item));
          assertEquals(expected == 1, ((View) read("likeButton")).isSelected());
          assertEquals(expected == -1, ((View) read("dislikeButton")).isSelected());
          return null;
        });
  }

  /** Freeze a real prepared page at an exact logical boundary, not a scheduler-dependent sleep. */
  private void pauseReadyAt(long watchedMs) throws Exception {
    pauseAndReadWatched();
    await(() -> onMain(() -> current().firstFrameReady && !current().failed));
    onMain(
        () -> {
          ((PlaybackWatchTracker) read("watchTracker")).restore(watchedMs, false);
          return null;
        });
  }

  private void finishAndWait() throws Exception {
    onMain(
        () -> {
          activity.finish();
          return null;
        });
    await(() -> onMain(() -> activity.isDestroyed()));
  }

  private void assertFeedback(int index, double likes, double dislikes) throws Exception {
    store.awaitIdle();
    assertTrue(
        "Only inspect the disposable test database",
        databaseName.startsWith("test_playback_entries_"));
    try (SQLiteDatabase database =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READONLY);
        Cursor row =
            database.rawQuery(
                "SELECT likes,dislikes,mass_exponent FROM plays WHERE media_key=?",
                new String[] {PlaybackStatsStore.key(videos.get(index))})) {
      if (!row.moveToFirst()) {
        assertEquals(0, likes, 0);
        assertEquals(0, dislikes, 0);
      } else {
        // Three-day decay can run between assertions; a second full vote is still unambiguous.
        assertEquals(likes, Math.scalb(row.getDouble(0), row.getInt(2)), 0.0001);
        assertEquals(dislikes, Math.scalb(row.getDouble(1), row.getInt(2)), 0.0001);
      }
    }
  }

  private void click(String label) throws Exception {
    onMain(
        () -> {
          View action = action((View) read("bottomBar"), label);
          assertNotNull(label, action);
          assertTrue(action.performClick());
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

  private void awaitCount(int index, long count) throws Exception {
    await(() -> store.playCount(videos.get(index)) == count);
  }

  private void awaitWatching(long minimumMs) throws Exception {
    await(() -> updateAndReadWatched() >= minimumMs);
  }

  private long updateAndReadWatched() throws Exception {
    return onMain(
        () -> {
          invoke("updateProgress", new Class<?>[0]);
          return ((PlaybackWatchTracker) read("watchTracker")).watchedMs();
        });
  }

  private long pauseAndReadWatched() throws Exception {
    return onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, false);
          return ((PlaybackWatchTracker) read("watchTracker")).watchedMs();
        });
  }

  private void resume() throws Exception {
    onMain(
        () -> {
          invoke("setPlaying", new Class<?>[] {boolean.class}, true);
          return null;
        });
  }

  private void awaitNeighbors() throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  PlaybackOrder order = (PlaybackOrder) read("order");
                  VideoPage previous = (VideoPage) read("previousPage"),
                      next = (VideoPage) read("nextPage");
                  return previous.index == order.peekPrevious()
                      && next.index == order.peekNext()
                      && previous.firstFrameReady
                      && next.firstFrameReady
                      && !previous.buffering
                      && !next.buffering;
                }));
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
          "entry-test-" + name,
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
    return (VideoPage) read("currentPage");
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

  private <T> T onMain(Callable<T> task) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            value.set(task.call());
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() instanceof Error) throw (Error) failure.get();
    if (failure.get() instanceof Exception) throw (Exception) failure.get();
    return value.get();
  }

  private void await(Callable<Boolean> condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (SystemClock.elapsedRealtime() < end) {
      if (condition.call()) return;
      SystemClock.sleep(30);
    }
    fail("Playback condition did not become true");
  }
}
