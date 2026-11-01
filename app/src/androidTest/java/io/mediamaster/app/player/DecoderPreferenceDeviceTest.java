package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.MainActivity;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Real settings and decoder routing. Only explicitly named, generated public files are opened. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class DecoderPreferenceDeviceTest {
  private static final String FOLDER = "Movies/MediaMasterGeneric_20261101/";
  private static final String HIGH_RATE = "mm_modes_1080p60.mp4";
  private static final String BARCODE = "mm_seek_b2_h264_720_gop1.mp4";
  private static final String[] PREF_KEYS = {
    "engine_choice", "playback_mode", "native_decoder_mode"
  };

  @Rule
  public ActivityTestRule<MainActivity> libraryRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  @Rule
  public ActivityTestRule<PlayerActivity> playerRule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final boolean[] hadPreference = new boolean[PREF_KEYS.length];
  private final int[] oldPreference = new int[PREF_KEYS.length];
  private Context context;
  private MainActivity library;
  private PlayerActivity activity;
  private VideoPage page;
  private MediaPlayer source;
  private Map<String, ?> historyPreferences;
  private Object oldHistoryInstance;
  private boolean historyIsolated, preferencesSaved;

  @Before
  public void isolatePreferencesAndGeneratedHistory() throws Exception {
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    SharedPreferences prefs = context.getSharedPreferences("player", 0);
    for (int i = 0; i < PREF_KEYS.length; i++) {
      hadPreference[i] = prefs.contains(PREF_KEYS[i]);
      oldPreference[i] = prefs.getInt(PREF_KEYS[i], 0);
    }
    preferencesSaved = true;
    historyPreferences =
        new HashMap<>(context.getSharedPreferences("player_engine_history", 0).getAll());
    onMain(
        () -> {
          Field singleton = field(PlaybackEngineHistory.class, "instance");
          oldHistoryInstance = singleton.get(null);
          singleton.set(null, null);
          historyIsolated = true;
          return null;
        });
    assertTrue(
        prefs
            .edit()
            .putInt("engine_choice", PlaybackEngineChoice.AUTO)
            .putInt("playback_mode", PlaybackOrder.SINGLE)
            .putInt("native_decoder_mode", NativeDecoderPolicy.AUTO)
            .commit());
  }

  @After
  public void restoreOriginalPreferencesAndHistory() throws Exception {
    try {
      if (activity != null) {
        playerRule.finishActivity();
        await(() -> onMain(activity::isDestroyed));
      }
      if (library != null) {
        libraryRule.finishActivity();
        await(() -> onMain(library::isDestroyed));
      }
    } finally {
      try {
        if (historyIsolated) {
          onMain(
              () -> {
                field(PlaybackEngineHistory.class, "instance").set(null, oldHistoryInstance);
                return null;
              });
        }
      } finally {
        if (context != null && historyPreferences != null) {
          restoreMap(context.getSharedPreferences("player_engine_history", 0), historyPreferences);
        }
        if (context != null && preferencesSaved) {
          SharedPreferences.Editor restore = context.getSharedPreferences("player", 0).edit();
          for (int i = 0; i < PREF_KEYS.length; i++) {
            if (hadPreference[i]) restore.putInt(PREF_KEYS[i], oldPreference[i]);
            else restore.remove(PREF_KEYS[i]);
          }
          assertTrue(restore.commit());
        }
      }
    }
  }

  @Test
  public void settingsExposeOrderedChoicesAndPersistTheirRealValues() throws Exception {
    library = libraryRule.launchActivity(new Intent(context, MainActivity.class));
    String[] labels = {"优先软解", "默认解码", "优先硬解"};
    int[] values = {
      NativeDecoderPolicy.SOFTWARE, NativeDecoderPolicy.AUTO, NativeDecoderPolicy.HARDWARE
    };
    int selected = 1;
    for (int choice = 0; choice < labels.length; choice++) {
      openDecoderSettings();
      int previousBottom = -1;
      for (int index = 0; index < labels.length; index++) {
        AccessibilityNodeInfo option = awaitText(labels[index]);
        try {
          assertTrue(
              "Decoder choices must expose native single-selection state", option.isCheckable());
          assertEquals(
              "The saved option is checked when reopened", index == selected, option.isChecked());
          Rect bounds = new Rect();
          option.getBoundsInScreen(bounds);
          assertTrue(
              "Options appear in software/default/hardware order", bounds.top >= previousBottom);
          previousBottom = bounds.bottom;
        } finally {
          option.recycle();
        }
      }
      clickText(labels[choice]);
      int value = values[choice];
      await(
          () ->
              context.getSharedPreferences("player", 0).getInt("native_decoder_mode", -1) == value);
      assertEquals(value, NativeDecoderSelector.preference(context));
      selected = choice;
    }
    openDecoderSettings();
    AccessibilityNodeInfo hardware = awaitText("优先硬解");
    try {
      assertTrue(hardware.isChecked());
    } finally {
      hardware.recycle();
    }
    clickText("取消");
    assertEquals(NativeDecoderPolicy.HARDWARE, NativeDecoderSelector.preference(context));
  }

  @Test
  public void softwarePreferenceIgnoresOldAvoidanceAndPlaysHighRateVideo() throws Exception {
    setMode(NativeDecoderPolicy.SOFTWARE);
    VideoItem item = fixture(HIGH_RATE);
    onMain(
        () -> {
          SeekEnginePolicy history = PlaybackEngineHistory.get(context).forItem(item);
          history.softwareAvoidUntilMs = System.currentTimeMillis() + 60000;
          history.vlcFailureUntilMs = System.currentTimeMillis() + 60000;
          assertTrue(history.shouldAvoidSoftware(System.currentTimeMillis()));
          return null;
        });
    launch(item, true);
    assertFalse(
        "User software preference must not schedule automatic performance fallback",
        onMain(() -> field(VideoPage.class, "nativeHealthScheduled").getBoolean(page)));
    assertHighRatePausedScrubRetainsDecoder();
  }

  @Test
  public void defaultUsesReal1080p60HeadersWhenResourcesPermit() throws Exception {
    setMode(NativeDecoderPolicy.AUTO);
    VideoItem item = fixture(HIGH_RATE);
    NativeDecoderPolicy.Metadata headers =
        (NativeDecoderPolicy.Metadata)
            invokeStatic(
                NativeDecoderSelector.class,
                "readHeaders",
                new Class<?>[] {Context.class, VideoItem.class},
                context,
                item);
    assertEquals("video/avc", headers.mime);
    assertEquals(1920, Math.max(headers.width, headers.height));
    assertEquals(1080, Math.min(headers.width, headers.height));
    assertEquals(60, headers.frameRate, .1);
    assertTrue(
        "The actual generated media headers are eligible without any device identity",
        NativeDecoderPolicy.useSoftware(
            NativeDecoderPolicy.AUTO,
            false,
            headers,
            new NativeDecoderPolicy.Resources(
                2L * 1024 * 1024 * 1024, 64L * 1024 * 1024, false, false)));
    NativeDecoderPolicy.Resources resources =
        onMain(
            () ->
                (NativeDecoderPolicy.Resources)
                    invokeStatic(
                        NativeDecoderSelector.class,
                        "resources",
                        new Class<?>[] {Context.class},
                        context));
    Assume.assumeTrue(
        "Live memory/thermal pressure excludes AUTO software; no capacity is forced by this test",
        NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.AUTO, false, headers, resources));
    // Populate the real bounded header cache. A cold 150 ms deadline is allowed to choose hardware;
    // this checks the media/resource decision, not storage-probe timing.
    await(() -> resolveSoftware(item));
    launch(item, true);
    assertHighRatePausedScrubRetainsDecoder();
  }

  @Test
  public void hardwarePreferenceRetainsItsNativePathAndAccuratePausedScrub() throws Exception {
    setMode(NativeDecoderPolicy.HARDWARE);
    launch(fixture(BARCODE), false);
    pause();
    onMain(
        () -> {
          page.beginScrub();
          page.scrubTo(18720);
          invoke(page, "dispatchScrub", new Class<?>[0]);
          page.endScrub(6200, true);
          return null;
        });
    awaitQueueDrain();
    await(
        () -> {
          Bitmap bitmap = capture();
          try {
            OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(bitmap);
            // State-regression allowance only, consistent with existing native paused checks.
            return code != null && Math.abs(code.frame * 40L - 6200) <= 200;
          } finally {
            bitmap.recycle();
          }
        });
    assertPausedAndRetained(false);
  }

  private void assertHighRatePausedScrubRetainsDecoder() throws Exception {
    pause();
    onMain(
        () -> {
          page.seekTo(1000);
          return null;
        });
    awaitQueueDrain();
    Bitmap before = capture();
    try {
      onMain(
          () -> {
            page.beginScrub();
            page.scrubTo(6000);
            invoke(page, "dispatchScrub", new Class<?>[0]);
            page.endScrub(4200, true);
            return null;
          });
      awaitQueueDrain();
      await(
          () -> {
            Bitmap after = capture();
            try {
              return NativeFrameProbe.averageRgbDistance(before, after) > .8;
            } finally {
              after.recycle();
            }
          });
      assertPausedAndRetained(true);
    } finally {
      before.recycle();
    }
  }

  private void launch(VideoItem item, boolean software) throws Exception {
    ArrayList<VideoItem> items = new ArrayList<>();
    items.add(item);
    activity =
        playerRule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, items)));
    await(
        () ->
            onMain(
                () -> {
                  page = (VideoPage) field(PlayerActivity.class, "currentPage").get(activity);
                  if (page == null) return false;
                  assertFalse("The generated fixture must not fail", page.failed);
                  source = page.vlc;
                  return source != null
                      && page.firstFrameReady
                      && source.isSeekable()
                      && !(Boolean) field(VideoPage.class, "warmingVlc").get(page);
                }));
    onMain(
        () -> {
          assertNull(
              "The explicit/native automatic route must produce a native first frame", page.exo);
          assertEquals(software, field(VideoPage.class, "nativeSoftware").getBoolean(page));
          return null;
        });
    Bitmap frame = capture();
    try {
      assertTrue(
          "A native decoded surface is available", frame.getWidth() > 0 && frame.getHeight() > 0);
    } finally {
      frame.recycle();
    }
  }

  private void pause() throws Exception {
    onMain(
        () -> {
          invoke(activity, "setPlaying", new Class<?>[] {boolean.class}, false);
          return null;
        });
    await(() -> onMain(() -> !source.isPlaying() && !page.playRequested));
  }

  private void awaitQueueDrain() throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  assertSame(
                      "A healthy preference path must not replace its decoder", source, page.vlc);
                  return !((NativeSeekQueue) field(VideoPage.class, "nativeSeekQueue").get(page))
                      .hasWork();
                }));
  }

  private void assertPausedAndRetained(boolean software) throws Exception {
    onMain(
        () -> {
          assertSame(source, page.vlc);
          assertEquals(software, field(VideoPage.class, "nativeSoftware").getBoolean(page));
          assertFalse(page.failed);
          assertFalse(page.playRequested);
          assertFalse(source.isPlaying());
          assertFalse(page.isScrubbing());
          assertEquals(0, source.getVolume());
          return null;
        });
  }

  private Bitmap capture() throws Exception {
    View surface = onMain(() -> NativeFrameProbe.findVideoSurface(page.vlcView));
    assertNotNull("The generated video has a real output surface", surface);
    return NativeFrameProbe.captureSurface(instrumentation, surface);
  }

  private void setMode(int mode) {
    assertTrue(
        context
            .getSharedPreferences("player", 0)
            .edit()
            .putInt("native_decoder_mode", mode)
            .commit());
  }

  private boolean resolveSoftware(VideoItem item) throws Exception {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Boolean> answer = new AtomicReference<>();
    onMain(
        () -> {
          NativeDecoderSelector.resolve(
              context,
              item,
              false,
              software -> {
                answer.set(software);
                finished.countDown();
              });
          return null;
        });
    assertTrue("The bounded selector returns a decision", finished.await(2, TimeUnit.SECONDS));
    return Boolean.TRUE.equals(answer.get());
  }

  private VideoItem fixture(String name) {
    String column = Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : "_data";
    String location =
        Build.VERSION.SDK_INT >= 29
            ? FOLDER
            : new File(Environment.getExternalStorageDirectory(), FOLDER + name).getAbsolutePath();
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "duration", "_size", "date_modified", "width", "height"},
                "_display_name=? AND " + column + "=?",
                new String[] {name, location},
                null)) {
      assertNotNull(cursor);
      assertEquals(
          "Exactly one generated fixture in the dedicated QA folder is required",
          1,
          cursor.getCount());
      assertTrue(cursor.moveToFirst());
      Uri uri =
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
      return new VideoItem(
          "decoder-preference-" + SystemClock.elapsedRealtimeNanos(),
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

  private void openDecoderSettings() throws Exception {
    onMain(
        () -> {
          invoke(library, "settings", new Class<?>[0]);
          return null;
        });
    clickText("解码");
    AccessibilityNodeInfo option = awaitText("默认解码");
    option.recycle();
  }

  private AccessibilityNodeInfo awaitText(String text) throws Exception {
    AtomicReference<AccessibilityNodeInfo> match = new AtomicReference<>();
    await(
        () -> {
          AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
          if (root == null) return false;
          try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
              if (node.getText() != null
                  && text.contentEquals(node.getText())
                  && node.isVisibleToUser()) {
                match.set(node);
                return true;
              }
              node.recycle();
            }
            return false;
          } finally {
            root.recycle();
          }
        });
    return match.get();
  }

  private void clickText(String text) throws Exception {
    AccessibilityNodeInfo node = awaitText(text);
    try {
      assertTrue(
          "The named settings action is clickable",
          node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    } finally {
      node.recycle();
    }
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static Object invokeStatic(Class<?> type, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = type.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    new Handler(Looper.getMainLooper()).post(task);
    try {
      return task.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof AssertionError) throw (AssertionError) failure.getCause();
      if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
      throw new RuntimeException(failure.getCause());
    } finally {
      if (!task.isDone()) task.cancel(false);
    }
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 15000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(40);
    }
    fail("Generated-media preference behavior did not settle before the test deadline");
  }

  @SuppressWarnings("unchecked")
  private static void restoreMap(SharedPreferences prefs, Map<String, ?> values) {
    SharedPreferences.Editor editor = prefs.edit().clear();
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      Object value = entry.getValue();
      String key = entry.getKey();
      if (value instanceof String) editor.putString(key, (String) value);
      else if (value instanceof Integer) editor.putInt(key, (Integer) value);
      else if (value instanceof Long) editor.putLong(key, (Long) value);
      else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
      else if (value instanceof Float) editor.putFloat(key, (Float) value);
      else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
      else throw new AssertionError("Unexpected stored preference value type");
    }
    assertTrue(editor.commit());
  }
}
