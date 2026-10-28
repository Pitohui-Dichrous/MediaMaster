package io.mediamaster.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.player.PlayerActivity;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verifies actual recent-task metadata, including a stale label surviving a background round trip.
 */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4.class)
public final class TaskIdentityTest {
  @Rule
  public ActivityTestRule<MainActivity> libraryRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  @Rule
  public ActivityTestRule<PlayerActivity> playerRule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context;
  private Activity activity;

  @Before
  public void prepare() {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
  }

  @After
  public void finish() {
    if (activity instanceof PlayerActivity) playerRule.finishActivity();
    else if (activity != null) libraryRule.finishActivity();
  }

  @Test
  public void libraryRestoresCurrentNameWhenResumingAnOldRecentTask() throws Exception {
    activity = libraryRule.launchActivity(new Intent(context, MainActivity.class));
    assertDeclaredNames();
    awaitCurrentTaskName();
    roundTripWithStaleName();
  }

  @Test
  public void externalPlayerUsesCurrentNameAndRepairsStaleTaskOnResume() throws Exception {
    Uri video = null;
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Video.Media._ID},
                MediaStore.Video.Media.DISPLAY_NAME + "=?",
                new String[] {"04_Portrait_Loop.mp4"},
                null)) {
      if (cursor != null && cursor.moveToFirst())
        video =
            Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
    }
    assertNotNull("Generated player fixture is installed", video);
    activity =
        playerRule.launchActivity(
            new Intent(Intent.ACTION_VIEW)
                .setClass(context, PlayerActivity.class)
                .setDataAndType(video, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    awaitCurrentTaskName();
    roundTripWithStaleName();
  }

  private void assertDeclaredNames() throws PackageManager.NameNotFoundException {
    String name = context.getString(R.string.app_name);
    PackageManager packages = context.getPackageManager();
    assertEquals("映序", name);
    assertEquals(name, context.getApplicationInfo().loadLabel(packages).toString());
    assertEquals(R.mipmap.ic_launcher, context.getApplicationInfo().icon);
    for (Class<?> page : new Class<?>[] {MainActivity.class, PlayerActivity.class})
      assertEquals(
          name,
          packages
              .getActivityInfo(new ComponentName(context, page), 0)
              .loadLabel(packages)
              .toString());
  }

  @SuppressWarnings("deprecation")
  private void roundTripWithStaleName() throws Exception {
    onMain(
        () -> {
          activity.setTaskDescription(new ActivityManager.TaskDescription("映序预览"));
          return null;
        });
    assertEquals("映序预览", ownTask().getTaskInfo().taskDescription.getLabel());
    assertTrue(onMain(() -> activity.moveTaskToBack(true)));
    awaitFocus(false);
    ownTask().moveToFront();
    awaitFocus(true);
    awaitCurrentTaskName();
    assertEquals(
        context.getString(R.string.app_name), onMain(() -> activity.getTitle().toString()));
  }

  private ActivityManager.AppTask ownTask() {
    for (ActivityManager.AppTask task :
        context.getSystemService(ActivityManager.class).getAppTasks()) {
      ActivityManager.RecentTaskInfo info = task.getTaskInfo();
      int taskId = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
      if (taskId == activity.getTaskId()) return task;
    }
    throw new AssertionError("The activity must own a visible recent task");
  }

  private void awaitCurrentTaskName() {
    long deadline = SystemClock.uptimeMillis() + 5000;
    String expected = context.getString(R.string.app_name);
    while (SystemClock.uptimeMillis() < deadline) {
      if (expected.equals(ownTask().getTaskInfo().taskDescription.getLabel())) return;
      SystemClock.sleep(50);
    }
    assertEquals(expected, ownTask().getTaskInfo().taskDescription.getLabel());
  }

  private void awaitFocus(boolean expected) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 5000;
    while (SystemClock.uptimeMillis() < deadline) {
      if (onMain(() -> activity.hasWindowFocus()) == expected) return;
      SystemClock.sleep(50);
    }
    assertEquals(expected, onMain(() -> activity.hasWindowFocus()));
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
