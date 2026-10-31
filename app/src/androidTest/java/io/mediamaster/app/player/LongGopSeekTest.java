package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import io.mediamaster.app.media.VideoRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The generated 1080p/30fps fixture has ten-second GOPs; seek assertions inspect rendered PTS. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class LongGopSeekTest {
  @Rule
  public ActivityTestRule<PlayerActivity> activityRule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

  @Test
  public void exactSeeksAcrossLongGopsRenderTheRequestedFramesOnTheSamePlayer() throws Exception {
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    VideoItem fixture = null;
    for (VideoItem item : new VideoRepository(context).scan())
      if (item.name.equals("05_LongGop.mp4")) fixture = item;
    assumeNotNull(fixture);
    assertTrue("The test fixture covers all requested positions", fixture.durationMs >= 25000);
    // The performance policy should not learn a device preference from emulator test timing.
    fixture =
        new VideoItem(
            "long-gop-regression-" + SystemClock.elapsedRealtimeNanos(),
            fixture.name,
            fixture.uri,
            fixture.path,
            fixture.size,
            fixture.durationMs,
            fixture.modifiedMs,
            fixture.addedMs,
            fixture.width,
            fixture.height);
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(fixture);
    String token = QueueStore.put(context, queue);
    PlayerActivity activity =
        activityRule.launchActivity(
            new Intent(context, PlayerActivity.class).putExtra("queue", token));
    try {
      AtomicReference<PlaybackException> failure = new AtomicReference<>();
      ExoPlayer source =
          onMain(
              () -> {
                ExoPlayer player = readPlayer(activity);
                Method pause = PlayerActivity.class.getDeclaredMethod("setPlaying", boolean.class);
                pause.setAccessible(true);
                pause.invoke(activity, false);
                player.addListener(
                    new Player.Listener() {
                      @Override
                      public void onPlayerError(PlaybackException error) {
                        failure.set(error);
                      }
                    });
                assertEquals(SeekParameters.EXACT, player.getSeekParameters());
                return player;
              });
      await(
          () ->
              onMain(
                  () ->
                      source.getPlaybackState() == Player.STATE_READY
                          && renderedFrames(source) > 0),
          15000);
      // A paused, settled surface excludes a queued frame from normal playback when arming PTS.
      Thread.sleep(100);
      for (long targetMs : new long[] {3400, 18700, 6300}) {
        VideoPage page = onMain(() -> readPage(activity));
        long beforeSequence = page.lastRenderedFrameSequence;
        int before = onMain(() -> renderedFrames(source));
        long startedAt = SystemClock.elapsedRealtime();
        onMain(
            () -> {
              assertSame(
                  "Seeking retains the original decoder pipeline", source, readPlayer(activity));
              assertEquals(
                  "Actual seeking remains precise",
                  SeekParameters.EXACT,
                  source.getSeekParameters());
              assertFalse("The test measures a single paused seek", source.getPlayWhenReady());
              Method seek = PlayerActivity.class.getDeclaredMethod("seekTo", long.class);
              seek.setAccessible(true);
              seek.invoke(activity, targetMs);
              return null;
            });
        // Observe the production metadata listener rather than replacing its single-owner slot.
        await(
            () ->
                page.lastRenderedFrameSequence > beforeSequence
                    && Math.abs(page.lastRenderedPositionUs - targetMs * 1000) <= 100_000,
            20000);
        long renderedPtsUs = page.lastRenderedPositionUs;
        long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
        long errorUs = Math.abs(renderedPtsUs - targetMs * 1000);
        assertTrue(
            "First rendered frame follows precise target, not a distant keyframe: target="
                + targetMs
                + " ms, actual="
                + renderedPtsUs
                + " us",
            errorUs <= 100_000);
        await(() -> onMain(() -> renderedFrames(source) > before), 3000);
        int after = onMain(() -> renderedFrames(source));
        onMain(
            () -> {
              assertSame(source, readPlayer(activity));
              assertEquals(SeekParameters.EXACT, source.getSeekParameters());
              assertEquals(targetMs, source.getCurrentPosition(), 100);
              return null;
            });
        Log.i(
            "LongGopSeek",
            "api="
                + Build.VERSION.SDK_INT
                + " targetMs="
                + targetMs
                + " firstRenderedPtsUs="
                + renderedPtsUs
                + " errorUs="
                + errorUs
                + " elapsedMs="
                + elapsedMs
                + " renderedBuffers="
                + before
                + "->"
                + after);
        assertNull("No decoder error during long-GOP seeks", failure.get());
      }
    } finally {
      activityRule.finishActivity();
    }
  }

  private static ExoPlayer readPlayer(PlayerActivity activity) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField("exo");
    field.setAccessible(true);
    ExoPlayer player = (ExoPlayer) field.get(activity);
    assertNotNull("The MP4 stays in the configured Exo pipeline", player);
    return player;
  }

  private static VideoPage readPage(PlayerActivity activity) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField("currentPage");
    field.setAccessible(true);
    return (VideoPage) field.get(activity);
  }

  private static int renderedFrames(ExoPlayer player) {
    DecoderCounters counters = player.getVideoDecoderCounters();
    if (counters == null) return 0;
    counters.ensureUpdated();
    return counters.renderedOutputBufferCount;
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            value.set(action.call());
          } catch (Throwable failed) {
            error.set(failed);
          }
        });
    Throwable failure = error.get();
    if (failure instanceof AssertionError) throw (AssertionError) failure;
    if (failure instanceof Exception) throw (Exception) failure;
    if (failure != null) throw new RuntimeException(failure);
    return value.get();
  }

  private static void await(Callable<Boolean> condition, long timeoutMs) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeoutMs;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      Thread.sleep(20);
    }
    fail("Timed out waiting for decoded video frame");
  }
}
