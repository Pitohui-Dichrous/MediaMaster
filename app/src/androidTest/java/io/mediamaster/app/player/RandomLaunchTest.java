package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real homepage entry and settings; every playable item and history store belongs to this test. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class RandomLaunchTest {
  private static final String PREFERENCE = "random_start_portrait";

  @Rule
  public ActivityTestRule<MainActivity> mainRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private MainActivity catalog;
  private PlayerActivity player;
  private ScopedPlaybackHistory history;
  private PlaybackStatsStore store, originalStore;
  private Field singleton;
  private String databaseName;
  private boolean installed, preferencesSaved;
  private final Map<String, Object> preferences = new HashMap<>();
  private final ArrayList<VideoItem> videos = new ArrayList<>();

  @Before
  public void isolateHistoriesAndLaunchPreferences() throws Exception {
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
          PREFERENCE,
          "playback_mode",
          "portrait_navigation_mode",
          "engine_choice",
          "native_decoder_mode"
        }) preferences.put(key, saved.getAll().get(key));
    preferencesSaved = true;
    assertTrue(
        saved
            .edit()
            .remove(PREFERENCE)
            .putInt("playback_mode", PlaybackOrder.SEQUENTIAL)
            .putInt("portrait_navigation_mode", PlaybackOrder.SEQUENTIAL)
            .putInt("engine_choice", 1)
            .putInt("native_decoder_mode", NativeDecoderPolicy.AUTO)
            .commit());
    databaseName = "random-entry-" + UUID.randomUUID() + ".db";
    store =
        new PlaybackStatsStore(
            context,
            databaseName,
            50,
            Executors.newSingleThreadExecutor(),
            System::currentTimeMillis);
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
      onMain(
          () -> {
            if (player != null) player.finish();
            if (catalog != null) catalog.finish();
            return null;
          });
      mainRule.finishActivity();
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
        for (Map.Entry<String, Object> entry : preferences.entrySet()) {
          Object value = entry.getValue();
          if (value == null) editor.remove(entry.getKey());
          else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
          else editor.putInt(entry.getKey(), (Integer) value);
        }
        assertTrue(editor.commit());
      }
    }
  }

  @Test
  public void homepageRandomDefaultsToPortraitAndRepeatsWhileManualNavigationShuffles()
      throws Exception {
    launchCatalog();
    launchFromHome(true);
    assertPresentation(true);
    int original = onMain(() -> order().current());
    onMain(
        () -> {
          assertEquals(PlaybackOrder.SHUFFLE, order().mode());
          assertTrue(order().repeatsCurrent());
          assertEquals(original, order().next(true));
          assertNotNull(current().exo);
          assertEquals(Player.REPEAT_MODE_ONE, current().exo.getRepeatMode());
          assertTrue(description(player.getWindow().getDecorView(), "下一条").performClick());
          return null;
        });
    await(() -> onMain(() -> order().current() != original && current().firstFrameReady));
    assertPresentation(true);
    assertTrue(onMain(() -> order().repeatsCurrent()));
    assertEquals(PlaybackOrder.SHUFFLE, (int) onMain(() -> order().mode()));
  }

  @Test
  public void fullscreenChoicePersistsThroughHomepageRecreationAndRandomLaunch() throws Exception {
    launchCatalog();
    onMain(
        () -> {
          assertTrue(description(catalog.getWindow().getDecorView(), "设置").performClick());
          return null;
        });
    clickText("随机入口");
    await(() -> exactText("竖屏") != null && exactText("全屏") != null);
    assertTrue(exactText("竖屏").isChecked());
    captureSettings();
    clickText("全屏");
    assertFalse(context.getSharedPreferences("player", 0).getBoolean(PREFERENCE, true));
    catalog = recreate(catalog, MainActivity.class);
    isolateCatalogQueue();
    launchFromHome(true);
    assertPresentation(false);
    assertEquals(PlaybackOrder.SHUFFLE, (int) onMain(() -> order().mode()));
    assertFalse(onMain(() -> order().repeatsCurrent()));
  }

  @Test
  public void ordinaryVideoRowStillOpensFullscreenWithPortraitRandomDefault() throws Exception {
    launchCatalog();
    launchFromHome(false);
    assertPresentation(false);
    assertEquals(PlaybackOrder.SEQUENTIAL, (int) onMain(() -> order().mode()));
    assertFalse(player.getIntent().getBooleanExtra("start_portrait", false));
  }

  @Test
  public void recreationPreservesUserPresentationInsteadOfReapplyingEntryDefault()
      throws Exception {
    launchCatalog();
    launchFromHome(true);
    assertPresentation(true);
    togglePresentation();
    assertPresentation(false);
    player = recreate(player, PlayerActivity.class);
    awaitPlayerReady();
    assertPresentation(false);
    togglePresentation();
    assertPresentation(true);
    player = recreate(player, PlayerActivity.class);
    awaitPlayerReady();
    assertPresentation(true);
    assertEquals(PlaybackOrder.SHUFFLE, (int) onMain(() -> order().mode()));
    assertTrue(onMain(() -> order().repeatsCurrent()));
  }

  private void launchCatalog() throws Exception {
    catalog = mainRule.launchActivity(new Intent(context, MainActivity.class));
    isolateCatalogQueue();
  }

  @SuppressWarnings("unchecked")
  private void isolateCatalogQueue() throws Exception {
    // The normal homepage may read the media index. No full scan, private playback, or mutation is
    // requested. Wait for its existing query, then replace the in-memory queue before any click.
    await(() -> onMain(() -> !(boolean) read(catalog, "scanning")));
    onMain(
        () -> {
          invoke(read(catalog, "mediaObserver"), "stop");
          ((Handler) read(catalog, "handler"))
              .removeCallbacks((Runnable) read(catalog, "reconcileMediaIndex"));
          ((RecyclerView) read(catalog, "list")).setItemAnimator(null);
          List<VideoItem> all = (List<VideoItem>) read(catalog, "all");
          all.clear();
          all.addAll(videos);
          invoke(catalog, "filter");
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    ((RecyclerView) read(catalog, "list")).findViewHolderForAdapterPosition(0)
                        != null));
    assertTrue(onMain(this::catalogContainsOnlyFixtures));
  }

  private void launchFromHome(boolean random) throws Exception {
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(PlayerActivity.class.getName(), null, false);
    try {
      onMain(
          () -> {
            assertTrue(catalogContainsOnlyFixtures());
            if (random)
              assertTrue(description(catalog.getWindow().getDecorView(), "随机播放").performClick());
            else
              assertTrue(
                  ((RecyclerView) read(catalog, "list"))
                      .findViewHolderForAdapterPosition(0)
                      .itemView
                      .performClick());
            return null;
          });
      player = (PlayerActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
      assertNotNull("Homepage entry must launch the player", player);
    } finally {
      instrumentation.removeMonitor(monitor);
    }
    awaitPlayerReady();
    onMain(
        () -> {
          @SuppressWarnings("unchecked")
          List<VideoItem> queue = (List<VideoItem>) read(player, "queue");
          assertEquals(videos.size(), queue.size());
          for (VideoItem item : queue) assertTrue(isFixture(item));
          return null;
        });
  }

  private void awaitPlayerReady() throws Exception {
    await(() -> onMain(() -> current().firstFrameReady && !current().buffering));
    onMain(
        () -> {
          Method method = PlayerActivity.class.getDeclaredMethod("setPlaying", boolean.class);
          method.setAccessible(true);
          method.invoke(player, false);
          return null;
        });
  }

  private void assertPresentation(boolean portrait) throws Exception {
    onMain(
        () -> {
          assertEquals(portrait, read(player, "portraitMode"));
          assertEquals(portrait, order().portraitMode());
          View button = (View) read(player, "portraitButton");
          assertEquals(portrait ? "全屏播放" : "竖屏播放", button.getContentDescription());
          return null;
        });
  }

  private void togglePresentation() throws Exception {
    onMain(
        () -> {
          assertTrue(((View) read(player, "portraitButton")).performClick());
          return null;
        });
  }

  private <T extends Activity> T recreate(T original, Class<T> type) throws Exception {
    AtomicReference<T> replacement = new AtomicReference<>();
    ActivityLifecycleCallback callback =
        (candidate, stage) -> {
          if (type.isInstance(candidate) && candidate != original && stage == Stage.RESUMED)
            replacement.set(type.cast(candidate));
        };
    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
    try {
      onMain(
          () -> {
            original.recreate();
            return null;
          });
      await(() -> replacement.get() != null);
      return replacement.get();
    } finally {
      ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
    }
  }

  private void captureSettings() throws Exception {
    if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureFeedbackUi")))
      return;
    // Accessibility can expose the new dialog before the platform window transition is drawn.
    instrumentation.waitForIdleSync();
    SystemClock.sleep(400);
    assertTrue(onMain(this::catalogContainsOnlyFixtures));
    assertNotNull(exactText("随机入口"));
    Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(screenshot);
    try {
      assertTrue(onMain(this::catalogContainsOnlyFixtures));
      assertNotNull(exactText("随机入口"));
      File directory = new File(context.getExternalFilesDir(null), "feedback-ui");
      assertTrue(directory.isDirectory() || directory.mkdirs());
      try (FileOutputStream output =
          new FileOutputStream(new File(directory, "random-entry-settings.png"))) {
        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
      }
    } finally {
      screenshot.recycle();
    }
  }

  @SuppressWarnings("unchecked")
  private boolean catalogContainsOnlyFixtures() throws Exception {
    List<VideoItem> visible = (List<VideoItem>) read(catalog, "visible");
    if (visible.size() != videos.size()) return false;
    for (VideoItem item : visible) if (!isFixture(item)) return false;
    return true;
  }

  private boolean isFixture(VideoItem item) {
    for (VideoItem fixture : videos)
      if (fixture.id.equals(item.id) && fixture.uri.equals(item.uri)) return true;
    return false;
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
          "random-entry-test-" + name,
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

  private PlaybackOrder order() throws Exception {
    return (PlaybackOrder) read(player, "order");
  }

  private VideoPage current() throws Exception {
    return (VideoPage) read(player, "currentPage");
  }

  private static View description(View view, String label) {
    if (view.getContentDescription() != null && label.contentEquals(view.getContentDescription()))
      return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = description(group.getChildAt(i), label);
        if (match != null) return match;
      }
    }
    return null;
  }

  private void clickText(String label) throws Exception {
    await(() -> exactText(label) != null);
    assertTrue(exactText(label).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    instrumentation.waitForIdleSync();
  }

  private AccessibilityNodeInfo exactText(String label) {
    AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
    if (root == null) return null;
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
      if (label.contentEquals(node.getText())) return node;
    return null;
  }

  private static Object read(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void invoke(Object target, String name) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(target);
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
    fail("Random entry condition did not become true");
  }
}
