package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
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
  private String databaseName;
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
      await(() -> onMain(() -> current().firstFrameReady && current().isPlaying()));
      onMain(
          () -> {
            invoke("updateProgress", new Class<?>[0]);
            return null;
          });
      assertEquals(
          "Recreated playback retains its already-counted entry",
          1,
          store.playCount(videos.get(0)));
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
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, items))
                .putExtra("index", 0));
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
