package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** A resumed compatibility decoder must repeat the whole clip, not its resumed suffix. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class VlcResumeLoopTest {
  @Rule
  public ActivityTestRule<PlayerActivity> activityRule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  @Test
  public void compatibilityResumeOffsetIsAppliedOnceAndNextLoopStartsAtZero() throws Exception {
    android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    int oldMode =
        context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("playback_mode", 0);
    PlayerActivity activity = null;
    try {
      ArrayList<VideoItem> queue = new ArrayList<>();
      queue.add(
          new VideoItem(
              "qa-vlc-loop",
              "04_Portrait_Loop.mp4",
              findFixture(context).toString(),
              "",
              0,
              6000,
              0,
              0,
              360,
              640));
      context
          .getSharedPreferences("player", Context.MODE_PRIVATE)
          .edit()
          .putInt("playback_mode", PlaybackOrder.SINGLE)
          .commit();
      activity =
          activityRule.launchActivity(
              new Intent(context, PlayerActivity.class)
                  .putExtra("queue", QueueStore.put(context, queue)));
      final PlayerActivity target = activity;
      AtomicReference<MediaPlayer> source = new AtomicReference<>();
      instrumentation.runOnMainSync(
          () -> {
            try {
              Method start = PlayerActivity.class.getDeclaredMethod("startVlc", long.class);
              start.setAccessible(true);
              start.invoke(target, 3000L);
              source.set(readVlc(target));
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          });
      long deadline = System.currentTimeMillis() + 25_000;
      boolean resumed = false, nearEnd = false, wrapped = false, replayedBeginning = false;
      long minimumAfterWrap = Long.MAX_VALUE;
      while (System.currentTimeMillis() < deadline && !replayedBeginning) {
        final long[] sample = {-1};
        instrumentation.runOnMainSync(
            () -> {
              try {
                assertSame("Native VLC instance survives the loop", source.get(), readVlc(target));
                sample[0] = source.get().getTime();
              } catch (Exception error) {
                throw new AssertionError(error);
              }
            });
        long time = sample[0];
        if (time >= 3000 && time < 4700) resumed = true;
        if (resumed && time >= 4700) nearEnd = true;
        if (nearEnd && time >= 0 && time < 1300) wrapped = true;
        if (wrapped) {
          minimumAfterWrap = Math.min(minimumAfterWrap, time);
          if (time >= 800 && time < 2500) replayedBeginning = true;
        }
        Thread.sleep(80);
      }
      assertTrue("VLC restored playback at the 3-second position", resumed);
      assertTrue("The resumed suffix reached the end", nearEnd);
      assertTrue("The next native loop returned near zero instead of the resume offset", wrapped);
      assertTrue(
          "Native playback continued through the beginning of the next loop", replayedBeginning);
      Log.i(
          "VlcResumeLoopTest",
          "resume=3000 ms; next-loop minimum=" + minimumAfterWrap + " ms; same native instance");
    } finally {
      if (activity != null) activityRule.finishActivity();
      context
          .getSharedPreferences("player", Context.MODE_PRIVATE)
          .edit()
          .putInt("playback_mode", oldMode)
          .commit();
    }
  }

  private static MediaPlayer readVlc(PlayerActivity activity) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField("vlc");
    field.setAccessible(true);
    MediaPlayer player = (MediaPlayer) field.get(activity);
    assertNotNull("Compatibility engine was created", player);
    return player;
  }

  private static Uri findFixture(Context context) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Video.Media._ID},
                MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?",
                new String[] {"%Portrait%Loop%"},
                null)) {
      if (cursor != null && cursor.moveToFirst())
        return Uri.withAppendedPath(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
    }
    throw new AssertionError("Install and media-scan the Portrait Loop MP4 fixture first");
  }
}
