package io.mediamaster.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Xml;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.library.LibraryFastScroller;
import io.mediamaster.app.library.LibraryHeader;
import io.mediamaster.app.media.VideoItem;
import io.mediamaster.app.media.VideoRepository;
import io.mediamaster.app.player.PlaybackOrder;
import io.mediamaster.app.player.PlayerActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

/**
 * Real Activity flows with generated emulator fixtures; never renames or deletes those fixtures.
 */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4.class)
public final class MainFlowTest {
  @Rule
  public ActivityTestRule<MainActivity> activityRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private MainActivity activity;

  @Before
  public void preparePermission() {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    if (Build.VERSION.SDK_INT <= 28) {
      instrumentation
          .getUiAutomation()
          .grantRuntimePermission(
              context.getPackageName(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
  }

  @After
  public void closeActivity() throws Exception {
    if (activity != null && activity != activityRule.getActivity())
      onMain(
          () -> {
            activity.finish();
            return null;
          });
    if (activity != null) activityRule.finishActivity();
  }

  @Test
  public void searchFindsRealLocalFilesAndClearsWithoutLosingTheLibrary() throws Exception {
    launchLibrary();
    int librarySize = onMain(() -> items("all").size());
    onMain(
        () -> {
          ((EditText) read(activity, "search")).setText("aUrOrA");
          List<VideoItem> matches = items("visible");
          assertFalse("Case-insensitive keyword finds the MP4 fixture", matches.isEmpty());
          for (VideoItem item : matches)
            assertTrue(item.name.toLowerCase(java.util.Locale.ROOT).contains("aurora"));
          ((EditText) read(activity, "search")).setText("unmatched-" + UUID.randomUUID());
          assertEquals("Unknown keyword has an empty result", 0, items("visible").size());
          ((EditText) read(activity, "search")).setText("");
          assertEquals(
              "Clearing restores every indexed video", librarySize, items("visible").size());
          return null;
        });
  }

  @Test
  public void allTenSortChoicesOrderIndependentMetadataCorrectly() throws Exception {
    launchLibrary();
    // Distinct, crossed values make dates, names, duration and size independently observable.
    onMain(
        () -> {
          List<VideoItem> original = new ArrayList<>(items("all"));
          int originalSort = (Integer) read(activity, "sort");
          String uri = original.get(0).uri;
          try {
            List<VideoItem> all = items("all");
            all.clear();
            all.add(
                new VideoItem("qa-beta", "Beta.mp4", uri, "", 4000, 9000, 3000, 1000, 640, 360));
            all.add(
                new VideoItem("qa-alpha", "Alpha.mp4", uri, "", 2000, 7000, 4000, 3000, 640, 360));
            all.add(
                new VideoItem("qa-omega", "Omega.mp4", uri, "", 1000, 3000, 1000, 4000, 640, 360));
            all.add(
                new VideoItem("qa-gamma", "Gamma.mp4", uri, "", 3000, 5000, 2000, 2000, 640, 360));
            String[][] expected = {
              {"Alpha.mp4", "Beta.mp4", "Gamma.mp4", "Omega.mp4"},
              {"Omega.mp4", "Gamma.mp4", "Beta.mp4", "Alpha.mp4"},
              {"Omega.mp4", "Alpha.mp4", "Gamma.mp4", "Beta.mp4"},
              {"Beta.mp4", "Gamma.mp4", "Alpha.mp4", "Omega.mp4"},
              {"Alpha.mp4", "Beta.mp4", "Gamma.mp4", "Omega.mp4"},
              {"Omega.mp4", "Gamma.mp4", "Beta.mp4", "Alpha.mp4"},
              {"Omega.mp4", "Gamma.mp4", "Alpha.mp4", "Beta.mp4"},
              {"Beta.mp4", "Alpha.mp4", "Gamma.mp4", "Omega.mp4"},
              {"Omega.mp4", "Alpha.mp4", "Gamma.mp4", "Beta.mp4"},
              {"Beta.mp4", "Gamma.mp4", "Alpha.mp4", "Omega.mp4"}
            };
            Field sort = MainActivity.class.getDeclaredField("sort");
            sort.setAccessible(true);
            for (int choice = 0; choice < expected.length; choice++) {
              sort.setInt(activity, choice);
              invoke(activity, "filter");
              List<String> names = new ArrayList<>();
              for (VideoItem item : items("visible")) names.add(item.name);
              assertEquals("Sort choice " + choice, Arrays.asList(expected[choice]), names);
            }
          } finally {
            items("all").clear();
            items("all").addAll(original);
            Field sort = MainActivity.class.getDeclaredField("sort");
            sort.setAccessible(true);
            sort.setInt(activity, originalSort);
            invoke(activity, "filter");
          }
          return null;
        });
  }

  @Test
  public void sortDialogHasFiveFieldsOneSharedReverseSwitchAndExplicitApply() throws Exception {
    launchLibrary();
    int originalSort = onMain(() -> (Integer) read(activity, "sort"));
    try {
      onMain(
          () -> {
            invoke(activity, "sortDialog");
            return null;
          });
      AccessibilityNodeInfo dialog = awaitSortDialog();
      assertEquals(
          "Exactly five sort fields",
          5,
          nodesWithClass(dialog, "android.widget.RadioButton").size());
      assertEquals(
          "One reverse switch shared by all fields",
          1,
          nodesWithClass(dialog, "android.widget.Switch").size());
      clickText(dialog, "文件大小");
      setReverse(true);
      assertEquals(
          "Selection waits for Apply",
          originalSort,
          (int) onMain(() -> (Integer) read(activity, "sort")));
      clickText(awaitSortDialog(), "应用");
      assertEquals(
          "Size descending applies to the library",
          9,
          (int) onMain(() -> (Integer) read(activity, "sort")));
      onMain(
          () -> {
            invoke(activity, "sortDialog");
            return null;
          });
      clickText(awaitSortDialog(), "视频名称");
      setReverse(false);
      clickText(awaitSortDialog(), "应用");
      assertEquals(
          "The same switch changes name sorting to ascending",
          4,
          (int) onMain(() -> (Integer) read(activity, "sort")));
    } finally {
      onMain(
          () -> {
            Field sort = MainActivity.class.getDeclaredField("sort");
            sort.setAccessible(true);
            sort.setInt(activity, originalSort);
            invoke(activity, "filter");
            activity
                .getPreferences(Context.MODE_PRIVATE)
                .edit()
                .putInt("sort", originalSort)
                .commit();
            return null;
          });
    }
  }

  @Test
  public void homeRandomButtonLaunchesAnActualShuffledPlayerQueue() throws Exception {
    launchLibrary();
    int previousMode =
        context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("playback_mode", 0);
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(PlayerActivity.class.getName(), null, false);
    Activity launched = null;
    try {
      Set<String> expectedIds =
          onMain(
              () -> {
                Set<String> ids = new HashSet<>();
                for (VideoItem item : items("visible")) ids.add(item.id);
                return ids;
              });
      onMain(
          () -> {
            invoke(activity, "playRandom");
            return null;
          });
      launched = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
      assertTrue("Random action opens the player", launched instanceof PlayerActivity);
      final Activity player = launched;
      onMain(
          () -> {
            PlaybackOrder order = (PlaybackOrder) read(player, "order");
            assertEquals(
                "Home random action activates shuffle", PlaybackOrder.SHUFFLE, order.mode());
            @SuppressWarnings("unchecked")
            List<VideoItem> queue = (List<VideoItem>) read(player, "queue");
            Set<String> actualIds = new HashSet<>();
            for (VideoItem item : queue) actualIds.add(item.id);
            assertEquals(
                "Player receives the complete current result list", expectedIds, actualIds);
            assertTrue(order.current() >= 0 && order.current() < queue.size());
            return null;
          });
    } finally {
      if (launched != null) {
        final Activity player = launched;
        onMain(
            () -> {
              player.finish();
              return null;
            });
      }
      instrumentation.removeMonitor(monitor);
      context
          .getSharedPreferences("player", Context.MODE_PRIVATE)
          .edit()
          .putInt("playback_mode", previousMode)
          .commit();
    }
  }

  @Test
  public void externalChangesWaitForManualRefreshAndOwnedVideoCanBeRenamedAndDeleted()
      throws Exception {
    launchLibrary();
    int initialSize = onMain(() -> items("all").size());
    VideoItem fixture =
        onMain(
            () -> {
              for (VideoItem item : items("all"))
                if (item.name.equals("01_Aurora.mp4")) return item;
              throw new AssertionError("Aurora MP4 QA fixture is required");
            });
    ContentResolver resolver = context.getContentResolver();
    String stem = "MediaMaster_QA_" + UUID.randomUUID();
    ContentValues values = new ContentValues();
    values.put(MediaStore.Video.Media.DISPLAY_NAME, stem + ".mp4");
    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
    if (Build.VERSION.SDK_INT >= 29) {
      values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MediaMasterQA/Temporary/");
      values.put(MediaStore.Video.Media.IS_PENDING, 1);
    }
    Uri owned = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    assertNotNull("Creates a separate app-owned temporary video", owned);
    boolean deleted = false;
    try {
      try (InputStream input = resolver.openInputStream(Uri.parse(fixture.uri));
          OutputStream output = resolver.openOutputStream(owned)) {
        assertNotNull(input);
        assertNotNull(output);
        byte[] bytes = new byte[16384];
        int count;
        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
      }
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(owned, ready, null, null);
      }
      // MediaStore notifications must not start a foreground rescan or change the visible library.
      resolver.notifyChange(owned, null);
      long idleDeadline = SystemClock.uptimeMillis() + 2000;
      while (SystemClock.uptimeMillis() < idleDeadline) {
        onMain(
            () -> {
              assertFalse(
                  "New media notifications do not trigger background scans",
                  (Boolean) read(activity, "scanning"));
              assertEquals(
                  "The foreground library stays unchanged until refresh",
                  initialSize,
                  items("all").size());
              for (VideoItem video : items("all")) assertFalse(video.name.equals(stem + ".mp4"));
              return null;
            });
        SystemClock.sleep(100);
      }
      refreshLibrary();
      awaitLibraryName(stem + ".mp4", true);
      VideoRepository repository = new VideoRepository(context);
      VideoItem item =
          onMain(
              () -> {
                for (VideoItem video : items("all"))
                  if (video.name.equals(stem + ".mp4")) return video;
                throw new AssertionError("Use the real library item and its scanned URI");
              });
      String renamedName = stem + "_renamed.mp4";
      VideoItem renamed = repository.rename(item, renamedName);
      try (Cursor cursor =
          resolver.query(
              Uri.parse(renamed.uri),
              new String[] {MediaStore.Video.Media.DISPLAY_NAME},
              null,
              null,
              null)) {
        assertNotNull(cursor);
        assertTrue(cursor.moveToFirst());
        assertEquals("Rename updates the stored file name", renamedName, cursor.getString(0));
      }
      refreshLibrary();
      awaitLibraryName(renamedName, true);
      awaitLibraryName(stem + ".mp4", false);
      repository.delete(renamed);
      deleted = true;
      try (Cursor cursor =
          resolver.query(
              MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
              new String[] {MediaStore.Video.Media._ID},
              MediaStore.Video.Media._ID + "=?",
              new String[] {Long.toString(ContentUris.parseId(owned))},
              null)) {
        assertTrue(
            "Deletion removes the temporary media row", cursor == null || cursor.getCount() == 0);
      }
      refreshLibrary();
      awaitLibraryName(renamedName, false);
    } finally {
      // Only this test's returned insert URI is ever removed.
      if (!deleted) {
        try {
          resolver.delete(owned, null, null);
        } catch (SecurityException alreadyRemoved) {
          try (Cursor cursor =
              resolver.query(
                  MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                  new String[] {MediaStore.Video.Media._ID},
                  MediaStore.Video.Media._ID + "=?",
                  new String[] {Long.toString(ContentUris.parseId(owned))},
                  null)) {
            if (cursor != null && cursor.getCount() != 0) throw alreadyRemoved;
          }
        }
      }
    }
  }

  @Test
  public void playerReturnAndBackgroundResumeNeverRewriteTheLibraryCache() throws Exception {
    launchLibrary();
    File cache = new File(context.getFilesDir(), "video-library-v4.json");
    long marker = 1_600_000_000_000L;
    assertTrue("Set a visible cache-write sentinel", cache.setLastModified(marker));
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(PlayerActivity.class.getName(), null, false);
    Activity player = null;
    try {
      onMain(
          () -> {
            invoke(activity, "playRandom");
            return null;
          });
      player = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
      assertNotNull("Actually enters the full-screen player", player);
      final Activity launched = player;
      onMain(
          () -> {
            launched.finish();
            return null;
          });
      SystemClock.sleep(700);
      assertEquals(
          "Returning from playback does not rescan or rewrite metadata",
          marker,
          cache.lastModified());
      // This is the exact launcher resume path, with no Activity destruction or task replacement.
      instrumentation
          .getUiAutomation()
          .performGlobalAction(
              android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
      SystemClock.sleep(300);
      Intent resume =
          new Intent(context, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      context.startActivity(resume);
      SystemClock.sleep(700);
      assertEquals("Switching back from Home does not rescan", marker, cache.lastModified());
      onMain(
          () -> {
            assertFalse((Boolean) read(activity, "scanning"));
            return null;
          });
    } finally {
      if (player != null) {
        final Activity launched = player;
        onMain(
            () -> {
              if (!launched.isFinishing()) launched.finish();
              return null;
            });
      }
      instrumentation.removeMonitor(monitor);
    }
  }

  @Test
  public void sameTaskRecreationSkipsScanButNewRecentTaskScansOnce() throws Exception {
    launchLibrary();
    File cache = new File(context.getFilesDir(), "video-library-v4.json");
    long marker = 1_600_000_000_000L;
    int originalTask = onMain(() -> activity.getTaskId());
    assertTrue(cache.setLastModified(marker));
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(MainActivity.class.getName(), null, false);
    try {
      onMain(
          () -> {
            activity.recreate();
            return null;
          });
      Activity recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
      assertTrue(
          "Configuration recreation creates the replacement Activity",
          recreated instanceof MainActivity);
      activity = (MainActivity) recreated;
      SystemClock.sleep(700);
      assertEquals(
          "The Activity is still inside the original task",
          originalTask,
          (int) onMain(() -> activity.getTaskId()));
      assertEquals(
          "Configuration recreation reads cached metadata without scanning",
          marker,
          cache.lastModified());
      onMain(
          () -> {
            activity.finishAndRemoveTask();
            return null;
          });
      SystemClock.sleep(300);
      activity =
          activityRule.launchActivity(
              new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.uptimeMillis() + 45000;
      while (cache.lastModified() == marker && SystemClock.uptimeMillis() < deadline)
        SystemClock.sleep(100);
      assertNotEquals(
          "A dismissed task gets a new task ID even if the process survives",
          originalTask,
          (int) onMain(() -> activity.getTaskId()));
      assertTrue("First entry of a fresh task actually scans", cache.lastModified() > marker);
      awaitScanIdle();
    } finally {
      instrumentation.removeMonitor(monitor);
    }
  }

  @Test
  public void appearanceSwitchPersistsAndPreservesSearchWithoutRescanningTheSameTask()
      throws Exception {
    SharedPreferences preferences =
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    boolean hadPreference = preferences.contains("dark");
    boolean originalDark = Ui.isDark(context);
    File cache = null;
    long originalCacheTime = 0;
    try {
      Ui.setDark(context, false);
      launchLibrary();
      int task = onMain(() -> activity.getTaskId());
      int librarySize = onMain(() -> items("all").size());
      cache = new File(context.getFilesDir(), "video-library-v4.json");
      originalCacheTime = cache.lastModified();
      long marker = 1_600_000_000_000L;
      assertTrue(
          "Create a scan-write sentinel after cold-start indexing", cache.setLastModified(marker));
      String keyword = "aUrOrA";
      onMain(
          () -> {
            ((EditText) read(activity, "search")).setText(keyword);
            return null;
          });
      float initialLuminance = onMain(this::libraryBackgroundLuminance);
      assertTrue("Light appearance is visibly light", initialLuminance > .5f);
      for (boolean dark : new boolean[] {true, false}) {
        chooseAppearanceThroughSettings(dark ? "深色" : "浅色");
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean restored = false;
        while (SystemClock.uptimeMillis() < deadline) {
          restored =
              onMain(
                  () ->
                      !(Boolean) read(activity, "scanning") && items("all").size() == librarySize);
          if (restored) break;
          SystemClock.sleep(50);
        }
        assertTrue("The recreated screen loads the existing library", restored);
        SystemClock.sleep(700);
        assertEquals(
            "Appearance recreation remains in the original task",
            task,
            onMain(() -> activity.getTaskId()).intValue());
        assertEquals(
            "Changing appearance must not scan or rewrite video metadata",
            marker,
            cache.lastModified());
        assertEquals(dark, Ui.isDark(context));
        assertAppearanceSavedToDisk(dark);
        onMain(
            () -> {
              assertEquals(
                  "Theme changes preserve the current search",
                  keyword,
                  ((EditText) read(activity, "search")).getText().toString());
              assertFalse(items("visible").isEmpty());
              for (VideoItem item : items("visible"))
                assertTrue(item.name.toLowerCase(java.util.Locale.ROOT).contains("aurora"));
              float luminance = libraryBackgroundLuminance();
              assertTrue(
                  "The recreated library visibly applies the selected appearance",
                  dark ? luminance < .15f : luminance > .5f);
              return null;
            });
      }
    } finally {
      SharedPreferences.Editor restore = preferences.edit();
      if (hadPreference) restore.putBoolean("dark", originalDark);
      else restore.remove("dark");
      restore.commit();
      onMain(
          () -> {
            Ui.configure(context);
            return null;
          });
      if (cache != null && originalCacheTime > 0) cache.setLastModified(originalCacheTime);
    }
  }

  @Test
  public void directoryChromeCollapsesOnlyNearTheTopAndFastHandleReallyScrolls() throws Exception {
    launchLibrary();
    List<VideoItem> original = onMain(() -> new ArrayList<>(items("all")));
    try {
      onMain(
          () -> {
            List<VideoItem> all = items("all");
            all.clear();
            for (int index = 0; index < 120; index++)
              all.add(original.get(index % original.size()));
            invoke(activity, "filter");
            return null;
          });
      SystemClock.sleep(250);
      onMain(
          () -> {
            RecyclerView list = (RecyclerView) read(activity, "list");
            LibraryHeader header = (LibraryHeader) read(activity, "libraryHeader");
            assertEquals(
                "The first row starts below all expanded chrome",
                header.collapseRange(),
                list.getPaddingTop());
            assertEquals(0f, header.collapseOffset(), 1f);
            list.scrollBy(0, header.collapseRange() / 2);
            assertTrue("Chrome follows the actual content offset", header.collapseOffset() > 0);
            assertTrue(
                "Half scroll does not teleport to the compact state",
                header.collapseOffset() < header.collapseRange());
            list.scrollBy(0, list.getHeight() * 3);
            assertEquals(
                "Browsing down removes title and random card",
                header.collapseRange(),
                header.collapseOffset(),
                1f);
            list.scrollBy(0, -list.getHeight());
            assertEquals(
                "A reverse gesture in the middle does not reopen chrome",
                header.collapseRange(),
                header.collapseOffset(),
                1f);
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0);
            return null;
          });
      SystemClock.sleep(200);
      onMain(
          () -> {
            LibraryHeader header = (LibraryHeader) read(activity, "libraryHeader");
            assertEquals(
                "Returning to the true top reopens the header", 0, header.collapseOffset(), 1f);
            LibraryFastScroller handle = (LibraryFastScroller) read(activity, "fastScroller");
            assertTrue(
                "The handle reserves the native 48dp hit width",
                handle.getWidth() >= Ui.dp(context, 48));
            float startY =
                (Float) read(handle, "thumbTop") + (Float) read(handle, "thumbHeight") / 2f;
            long now = SystemClock.uptimeMillis();
            MotionEvent down =
                MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, handle.getWidth() / 2f, startY, 0);
            try {
              assertTrue("Visible handle captures a real finger", handle.dispatchTouchEvent(down));
            } finally {
              down.recycle();
            }
            MotionEvent move =
                MotionEvent.obtain(
                    now,
                    now + 20,
                    MotionEvent.ACTION_MOVE,
                    handle.getWidth() / 2f,
                    handle.getHeight() * .8f,
                    0);
            try {
              handle.dispatchTouchEvent(move);
            } finally {
              move.recycle();
            }
            MotionEvent up =
                MotionEvent.obtain(
                    now,
                    now + 40,
                    MotionEvent.ACTION_UP,
                    handle.getWidth() / 2f,
                    handle.getHeight() * .8f,
                    0);
            try {
              handle.dispatchTouchEvent(up);
            } finally {
              up.recycle();
            }
            return null;
          });
      SystemClock.sleep(250);
      onMain(
          () -> {
            RecyclerView list = (RecyclerView) read(activity, "list");
            assertTrue(
                "Dragging the handle jumps well beyond the first screen",
                ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition()
                    > 50);
            LibraryHeader header = (LibraryHeader) read(activity, "libraryHeader");
            assertEquals(
                "Fast scrolling keeps the two pinned rows compact",
                header.collapseRange(),
                header.collapseOffset(),
                1f);
            return null;
          });
      dragFastHandleTo(false);
      SystemClock.sleep(250);
      onMain(
          () -> {
            RecyclerView list = (RecyclerView) read(activity, "list");
            LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
            assertEquals(
                "Bottom of the handle reaches the final row",
                list.getAdapter().getItemCount() - 1,
                layout.findLastVisibleItemPosition());
            View last = layout.findViewByPosition(list.getAdapter().getItemCount() - 1);
            assertNotNull(last);
            assertEquals(
                "Bottom has only the intended 16dp gutter",
                list.getHeight() - list.getPaddingBottom(),
                layout.getDecoratedBottom(last),
                2);
            return null;
          });
      dragFastHandleTo(true);
      SystemClock.sleep(250);
      onMain(
          () -> {
            RecyclerView list = (RecyclerView) read(activity, "list");
            LibraryHeader header = (LibraryHeader) read(activity, "libraryHeader");
            assertEquals(
                "Top of the handle reaches row zero",
                0,
                ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition());
            assertEquals("Top of the handle fully reopens chrome", 0f, header.collapseOffset(), 1f);
            return null;
          });
    } finally {
      onMain(
          () -> {
            items("all").clear();
            items("all").addAll(original);
            invoke(activity, "filter");
            return null;
          });
    }
  }

  private void dragFastHandleTo(boolean top) throws Exception {
    onMain(
        () -> {
          LibraryFastScroller handle = (LibraryFastScroller) read(activity, "fastScroller");
          float startY =
              (Float) read(handle, "thumbTop") + (Float) read(handle, "thumbHeight") / 2f;
          float targetY = top ? 0 : handle.getHeight();
          long now = SystemClock.uptimeMillis();
          MotionEvent down =
              MotionEvent.obtain(
                  now, now, MotionEvent.ACTION_DOWN, handle.getWidth() / 2f, startY, 0);
          MotionEvent move =
              MotionEvent.obtain(
                  now, now + 20, MotionEvent.ACTION_MOVE, handle.getWidth() / 2f, targetY, 0);
          MotionEvent up =
              MotionEvent.obtain(
                  now, now + 40, MotionEvent.ACTION_UP, handle.getWidth() / 2f, targetY, 0);
          try {
            assertTrue(handle.dispatchTouchEvent(down));
            handle.dispatchTouchEvent(move);
            handle.dispatchTouchEvent(up);
          } finally {
            down.recycle();
            move.recycle();
            up.recycle();
          }
          return null;
        });
  }

  private float libraryBackgroundLuminance() {
    ViewGroup content = activity.findViewById(android.R.id.content);
    View root = content.getChildAt(0);
    assertTrue(
        "The library exposes its visible solid background",
        root.getBackground() instanceof ColorDrawable);
    return Color.luminance(((ColorDrawable) root.getBackground()).getColor());
  }

  private void chooseAppearanceThroughSettings(String choice) throws Exception {
    onMain(
        () -> {
          View settings = viewWithDescription(activity.getWindow().getDecorView(), "设置");
          assertNotNull("The library settings control is present", settings);
          assertTrue(settings.isShown());
          assertTrue(settings.performClick());
          return null;
        });
    clickText(awaitWindowText("外观"), "外观");
    AccessibilityNodeInfo options = awaitWindowText(choice);
    assertTrue("The actual appearance dialog is open", hasExactText(options, "外观"));
    assertTrue(hasExactText(options, "浅色"));
    assertTrue(hasExactText(options, "深色"));
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(MainActivity.class.getName(), null, false);
    try {
      clickText(options, choice);
      Activity recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
      assertTrue(
          "Selecting a different appearance recreates the library",
          recreated instanceof MainActivity);
      assertNotSame(activity, recreated);
      activity = (MainActivity) recreated;
    } finally {
      instrumentation.removeMonitor(monitor);
    }
  }

  private AccessibilityNodeInfo awaitWindowText(String text) {
    long deadline = SystemClock.uptimeMillis() + 5000;
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
      if (root != null && hasExactText(root, text)) return root;
      SystemClock.sleep(50);
    }
    throw new AssertionError("No visible dialog option: " + text);
  }

  private static boolean hasExactText(AccessibilityNodeInfo root, String text) {
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
      if (node.getText() != null && text.contentEquals(node.getText())) return true;
    return false;
  }

  private void assertAppearanceSavedToDisk(boolean dark) throws Exception {
    File saved = new File(context.getApplicationInfo().dataDir, "shared_prefs/appearance.xml");
    long deadline = SystemClock.uptimeMillis() + 5000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (saved.isFile()) {
        try (InputStream input = new FileInputStream(saved)) {
          XmlPullParser parser = Xml.newPullParser();
          parser.setInput(input, "UTF-8");
          for (int event = parser.next();
              event != XmlPullParser.END_DOCUMENT;
              event = parser.next()) {
            if (event == XmlPullParser.START_TAG
                && "boolean".equals(parser.getName())
                && "dark".equals(parser.getAttributeValue(null, "name")))
              if (Boolean.toString(dark).equals(parser.getAttributeValue(null, "value"))) return;
          }
        }
      }
      SystemClock.sleep(50);
    }
    fail("Appearance selection must be persisted to disk");
  }

  private static View viewWithDescription(View view, String description) {
    CharSequence label = view.getContentDescription();
    if (label != null && description.contentEquals(label)) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = viewWithDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void awaitScanIdle() throws Exception {
    long deadline = SystemClock.uptimeMillis() + 45000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (onMain(() -> !(Boolean) read(activity, "scanning"))) return;
      SystemClock.sleep(100);
    }
    fail("Explicit library scan must settle");
  }

  private void launchLibrary() throws Exception {
    activity = activityRule.launchActivity(new Intent(context, MainActivity.class));
    long deadline = SystemClock.uptimeMillis() + 45000;
    while (SystemClock.uptimeMillis() < deadline) {
      boolean loaded =
          onMain(
              () -> {
                Set<String> names = new HashSet<>();
                for (VideoItem item : items("all")) names.add(item.name);
                return !(Boolean) read(activity, "scanning")
                    && names.containsAll(
                        Arrays.asList(
                            "01_Aurora.mp4",
                            "02_Horizon.mkv",
                            "03_Evening.avi",
                            "04_Portrait_Loop.mp4"));
              });
      if (loaded) return;
      SystemClock.sleep(100);
    }
    fail("Library scan must finish with all four generated QA fixtures within 45 seconds");
  }

  private void refreshLibrary() throws Exception {
    onMain(
        () -> {
          assertTrue(
              "Manual scan button is actionable",
              ((View) read(activity, "scanButton")).performClick());
          return null;
        });
  }

  private void awaitLibraryName(String name, boolean present) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < deadline) {
      boolean found =
          onMain(
              () -> {
                for (VideoItem item : items("all")) if (item.name.equals(name)) return true;
                return false;
              });
      if (found == present) return;
      SystemClock.sleep(100);
    }
    fail("Manual library refresh must " + (present ? "discover " : "remove ") + name);
  }

  private AccessibilityNodeInfo awaitSortDialog() {
    long deadline = SystemClock.uptimeMillis() + 5000;
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
      if (root != null && nodesWithClass(root, "android.widget.Switch").size() == 1) return root;
      SystemClock.sleep(100);
    }
    throw new AssertionError("Sort dialog must expose its shared reverse switch");
  }

  private void setReverse(boolean checked) {
    AccessibilityNodeInfo toggle =
        nodesWithClass(awaitSortDialog(), "android.widget.Switch").get(0);
    if (toggle.isChecked() != checked)
      assertTrue(
          "Reverse switch is actionable", toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK));
  }

  private static List<AccessibilityNodeInfo> nodesWithClass(
      AccessibilityNodeInfo root, String name) {
    List<AccessibilityNodeInfo> result = new ArrayList<>();
    if (name.contentEquals(root.getClassName())) result.add(root);
    for (int index = 0; index < root.getChildCount(); index++) {
      AccessibilityNodeInfo child = root.getChild(index);
      if (child != null) result.addAll(nodesWithClass(child, name));
    }
    return result;
  }

  private static void clickText(AccessibilityNodeInfo root, String text) {
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
      if (text.contentEquals(node.getText())) {
        assertTrue(text + " is actionable", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        return;
      }
    }
    throw new AssertionError("Missing dialog option " + text);
  }

  @SuppressWarnings("unchecked")
  private List<VideoItem> items(String name) throws Exception {
    return (List<VideoItem>) read(activity, name);
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
    if (error instanceof AssertionError) throw (AssertionError) error;
    if (error instanceof Exception) throw (Exception) error;
    if (error != null) throw new RuntimeException(error);
    return result.get();
  }
}
