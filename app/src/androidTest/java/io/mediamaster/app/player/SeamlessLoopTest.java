package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real visible Activity/decoder, rather than a mocked repeat flag. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public class SeamlessLoopTest {
  @Rule
  public ActivityTestRule<PlayerActivity> activityRule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  @Test
  public void shortMp4RepeatsWithoutRecreatingOrRebuffering() throws Exception {
    android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    int previousMode =
        context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("playback_mode", 0);
    PlayerActivity activity = null;
    try {
      Uri uri = findPortraitFixture(context);
      ArrayList<VideoItem> queue = new ArrayList<>();
      queue.add(
          new VideoItem(
              "qa-loop", "04 Portrait Loop.mp4", uri.toString(), "", 0, 0, 0, 0, 360, 640));
      String token = QueueStore.put(context, queue);
      context
          .getSharedPreferences("player", Context.MODE_PRIVATE)
          .edit()
          .putInt("playback_mode", PlaybackOrder.SINGLE)
          .commit();
      activity =
          activityRule.launchActivity(
              new Intent(context, PlayerActivity.class).putExtra("queue", token));
      final PlayerActivity target = activity;
      AtomicReference<ExoPlayer> source = new AtomicReference<>();
      CountDownLatch boundaries = new CountDownLatch(3);
      AtomicBoolean initialReady = new AtomicBoolean(false), rendered = new AtomicBoolean(false);
      AtomicInteger laterBuffering = new AtomicInteger();
      AtomicReference<PlaybackException> failure = new AtomicReference<>();
      instrumentation.runOnMainSync(
          () -> {
            try {
              ExoPlayer exo = readPlayer(target);
              source.set(exo);
              initialReady.set(exo.getPlaybackState() == Player.STATE_READY);
              exo.addListener(
                  new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(MediaItem item, int reason) {
                      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                        boundaries.countDown();
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                      if (state == Player.STATE_READY) initialReady.set(true);
                      if (state == Player.STATE_BUFFERING && initialReady.get())
                        laterBuffering.incrementAndGet();
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                      failure.set(error);
                    }
                  });
              exo.addAnalyticsListener(
                  new AnalyticsListener() {
                    @Override
                    public void onRenderedFirstFrame(
                        EventTime eventTime, Object output, long renderTimeMs) {
                      rendered.set(true);
                    }
                  });
              if (exo.getVideoDecoderCounters() != null
                  && exo.getVideoDecoderCounters().renderedOutputBufferCount > 0)
                rendered.set(true);
              exo.play();
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          });
      assertTrue(
          "Three native repeat boundaries within 90 seconds",
          boundaries.await(90, TimeUnit.SECONDS));
      assertNull("Decoder reported an error", failure.get());
      assertTrue("Video decoder produced a visible first frame", rendered.get());
      assertEquals("No buffering state after initial readiness", 0, laterBuffering.get());
      instrumentation.runOnMainSync(
          () -> {
            try {
              assertSame(
                  "Same playback pipeline after three loops", source.get(), readPlayer(target));
              assertEquals(Player.REPEAT_MODE_ONE, readPlayer(target).getRepeatMode());
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          });
    } finally {
      if (activity != null) activityRule.finishActivity();
      context
          .getSharedPreferences("player", Context.MODE_PRIVATE)
          .edit()
          .putInt("playback_mode", previousMode)
          .commit();
    }
  }

  private static ExoPlayer readPlayer(PlayerActivity activity) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField("exo");
    field.setAccessible(true);
    ExoPlayer player = (ExoPlayer) field.get(activity);
    assertNotNull("MP4 uses seamless Exo pipeline", player);
    return player;
  }

  private static Uri findPortraitFixture(Context context) {
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
    throw new AssertionError(
        "Install the short Portrait Loop MP4 fixture and scan it into MediaStore first");
  }
}
