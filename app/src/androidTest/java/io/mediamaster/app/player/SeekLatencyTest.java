package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.SeekBar;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual rendered-frame diagnostics, not a device-independent speed benchmark. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class SeekLatencyTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private Context context;
  private PlayerActivity activity;
  private VideoPage page;
  private ExoPlayer source;
  private Probe probe;
  private int savedMode;
  private int savedEngine;
  private boolean hadEngine;

  @Before
  public void launchWithAudioVideoFixture() throws Exception {
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    savedMode = context.getSharedPreferences("player", 0).getInt("playback_mode", 0);
    hadEngine = context.getSharedPreferences("player", 0).contains("engine_choice");
    savedEngine = context.getSharedPreferences("player", 0).getInt("engine_choice", 0);
    context
        .getSharedPreferences("player", 0)
        .edit()
        .putInt("playback_mode", PlaybackOrder.SINGLE)
        .putInt("engine_choice", 1)
        .commit();
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(fixture());
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, queue)));
    await(
        () ->
            onMain(
                () -> {
                  page = (VideoPage) read("currentPage");
                  source = page.exo;
                  return source != null
                      && page.firstFrameReady
                      && source.getPlaybackState() == Player.STATE_READY
                      && source.getAudioDecoderCounters() != null;
                }),
        20000);
    pauseAt(1000);
    probe = new Probe();
    onMain(
        () -> {
          source.addListener(probe);
          source.addAnalyticsListener(probe);
          return null;
        });
  }

  @After
  public void finish() {
    if (activity != null) rule.finishActivity();
    if (context != null) {
      android.content.SharedPreferences.Editor editor =
          context.getSharedPreferences("player", 0).edit().putInt("playback_mode", savedMode);
      if (hadEngine) editor.putInt("engine_choice", savedEngine);
      else editor.remove("engine_choice");
      editor.commit();
    }
  }

  @Test
  public void quickSeekBarTapsSubmitOnlyOneExactSeekWithoutTogglingAudioTracks() throws Exception {
    for (float fraction : new float[] {.13f, .68f, .31f}) {
      pauseAt(1000);
      onMain(
          () -> {
            probe.reset();
            return null;
          });
      Sample sample = onMain(() -> tapSample(fraction, true));
      awaitFrame(sample, false);
      SystemClock.sleep(100);
      onMain(
          () -> {
            assertEquals("A quick tap submits only its final seek", 1, probe.seekRequests);
            assertEquals(0, probe.audioDisabled);
            assertEquals(0, probe.audioReleased);
            assertEquals(0, probe.audioInitialized);
            assertEquals(SeekParameters.EXACT, source.getSeekParameters());
            assertFalse(source.getPlayWhenReady());
            assertEquals(sample.targetMs, source.getCurrentPosition(), 100);
            return null;
          });
    }
  }

  @Test
  public void directExactAndInteractivePathsReportActualFramesAndResumeState() throws Exception {
    // Ordered diagnostics use the same generated file, not matched cold-cache performance trials.
    Sample direct =
        onMain(
            () -> {
              probe.reset();
              Sample result = sample("direct-exact-paused", 18700);
              page.seekTo(result.targetMs);
              return result;
            });
    awaitFrame(direct, false);
    pauseAt(1000);
    Sample tapped =
        onMain(
            () -> {
              probe.reset();
              return tapSample(.624f, false);
            });
    awaitFrame(tapped, false);
    runInteractiveDiagnostic(false);
  }

  @Test
  public void sustainedScrubKeepsTheExistingAudioDecoderSelected() throws Exception {
    runInteractiveDiagnostic(true);
  }

  private void runInteractiveDiagnostic(boolean verifyAudio) throws Exception {
    pauseAt(1000);
    onMain(
        () -> {
          setPlaying(true);
          return null;
        });
    await(() -> onMain(source::isPlaying), 15000);
    onMain(
        () -> {
          probe.reset();
          page.beginScrub();
          page.scrubTo(3400);
          return null;
        });
    await(() -> onMain(source::isScrubbingModeEnabled), 5000);
    await(
        () ->
            onMain(
                () ->
                    page.lastRenderedPositionUs >= 3_050_000
                        && page.lastRenderedPositionUs <= 3_750_000),
        20000);
    onMain(
        () -> {
          assertEquals("Dragging stays silent", 0f, source.getVolume(), 0f);
          if (verifyAudio) assertAudioRetained();
          return null;
        });
    for (long target : new long[] {4100, 6200, 9800}) {
      onMain(
          () -> {
            page.scrubTo(target);
            return null;
          });
      SystemClock.sleep(90);
    }
    Sample finalSeek =
        onMain(
            () -> {
              Sample result = sample("drag-final-exact-playing", 18700);
              page.endScrub(result.targetMs, true);
              return result;
            });
    awaitFrame(finalSeek, true);
    onMain(
        () -> {
          if (verifyAudio) assertAudioRetained();
          assertTrue(source.getPlayWhenReady());
          assertFalse(source.isScrubbingModeEnabled());
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          assertSame(page.exo, source);
          return null;
        });
  }

  private void assertAudioRetained() {
    assertEquals("The audio decoder remains selected", 0, probe.audioDisabled);
    assertEquals(0, probe.audioReleased);
    assertEquals(0, probe.audioInitialized);
  }

  private Sample tapSample(float fraction, boolean verifyLazy) throws Exception {
    SeekBar bar = (SeekBar) read("seek");
    assertTrue(bar.getWidth() > 0);
    float x =
        bar.getPaddingLeft()
            + fraction * (bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
    long now = SystemClock.uptimeMillis();
    Sample result = sample("quick-ui-tap", 0);
    // Both actual widget events occupy one UI turn, like queued rapid input. The 20 ms event
    // timestamps do not claim that the instrumentation thread slept for 20 ms.
    touch(bar, MotionEvent.ACTION_DOWN, x, now, now);
    if (verifyLazy) assertFalse(source.isScrubbingModeEnabled());
    result.targetMs = (Long) read("gestureTarget");
    touch(bar, MotionEvent.ACTION_UP, x, now, now + 20);
    if (verifyLazy) assertFalse(source.isScrubbingModeEnabled());
    return result;
  }

  private Sample sample(String label, long target) {
    return new Sample(
        label,
        target,
        page.lastRenderedFrameSequence,
        textureTimestamp(),
        SystemClock.elapsedRealtime());
  }

  private void awaitFrame(Sample sample, boolean playing) throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  if (sample.frameMs < 0
                      && page.lastRenderedFrameSequence > sample.sequence
                      && Math.abs(page.lastRenderedPositionUs - sample.targetMs * 1000) <= 100_000
                      && textureTimestamp() > 0
                      && textureTimestamp() != sample.texture) {
                    sample.frameMs = SystemClock.elapsedRealtime() - sample.started;
                    sample.ptsUs = page.lastRenderedPositionUs;
                  }
                  // State may briefly still describe the pre-seek pipeline. Only count readiness
                  // and resumed playback after the new target frame has actually reached a texture.
                  if (sample.frameMs >= 0
                      && sample.readyMs < 0
                      && source.getPlaybackState() == Player.STATE_READY)
                    sample.readyMs = SystemClock.elapsedRealtime() - sample.started;
                  if (sample.frameMs >= 0 && playing && sample.playingMs < 0 && source.isPlaying())
                    sample.playingMs = SystemClock.elapsedRealtime() - sample.started;
                  return sample.frameMs >= 0
                      && sample.readyMs >= 0
                      && (!playing || sample.playingMs >= 0);
                }),
        20000);
    Log.i(
        "SeekLatency",
        onMain(
            () ->
                sample.label
                    + " targetMs="
                    + sample.targetMs
                    + " ptsUs="
                    + sample.ptsUs
                    + " frameMs="
                    + sample.frameMs
                    + " readyMs="
                    + sample.readyMs
                    + " playingMs="
                    + sample.playingMs
                    + " seekRequests="
                    + probe.seekRequests
                    + " audioInit="
                    + probe.audioInitialized
                    + " audioReleased="
                    + probe.audioReleased
                    + " audioDisabled="
                    + probe.audioDisabled));
  }

  private void pauseAt(long target) throws Exception {
    long sequence = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          setPlaying(false);
          page.seekTo(target);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    source.getPlaybackState() == Player.STATE_READY
                        && Math.abs(page.lastRenderedPositionUs - target * 1000) <= 100_000
                        && (page.lastRenderedFrameSequence > sequence
                            || Math.abs(source.getCurrentPosition() - target) < 100)),
        20000);
  }

  private long textureTimestamp() {
    android.view.View view = page.exoView.getVideoSurfaceView();
    if (!(view instanceof TextureView)) return 0;
    TextureView texture = (TextureView) view;
    return texture.isAvailable() && texture.getSurfaceTexture() != null
        ? texture.getSurfaceTexture().getTimestamp()
        : 0;
  }

  private static void touch(SeekBar bar, int action, float x, long down, long when) {
    MotionEvent event = MotionEvent.obtain(down, when, action, x, bar.getHeight() * .5f, 0);
    try {
      assertTrue(bar.dispatchTouchEvent(event));
    } finally {
      event.recycle();
    }
  }

  private VideoItem fixture() {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "duration", "_size", "date_modified", "width", "height"},
                "_display_name=?",
                new String[] {"05_LongGop.mp4"},
                null)) {
      if (cursor != null && cursor.moveToFirst()) {
        Uri uri =
            Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
        return new VideoItem(
            "latency-" + SystemClock.elapsedRealtimeNanos(),
            "05_LongGop.mp4",
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
    throw new AssertionError("Install generated 05_LongGop.mp4 fixture first");
  }

  private Object read(String name) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void setPlaying(boolean value) throws Exception {
    Method method = PlayerActivity.class.getDeclaredMethod("setPlaying", boolean.class);
    method.setAccessible(true);
    method.invoke(activity, value);
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            value.set(action.call());
          } catch (Throwable failure) {
            error.set(failure);
          }
        });
    if (error.get() instanceof AssertionError) throw (AssertionError) error.get();
    if (error.get() instanceof Exception) throw (Exception) error.get();
    if (error.get() != null) throw new RuntimeException(error.get());
    return value.get();
  }

  private static void await(Callable<Boolean> condition, long timeout) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(5);
    }
    fail("Timed out waiting for an actual seek frame/state; no speed threshold is asserted");
  }

  private static final class Probe implements Player.Listener, AnalyticsListener {
    int seekRequests, audioInitialized, audioReleased, audioDisabled;

    void reset() {
      seekRequests = audioInitialized = audioReleased = audioDisabled = 0;
    }

    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK) seekRequests++;
    }

    @Override
    public void onAudioDecoderInitialized(
        EventTime event, String name, long timestamp, long duration) {
      audioInitialized++;
    }

    @Override
    public void onAudioDecoderReleased(EventTime event, String name) {
      audioReleased++;
    }

    @Override
    public void onAudioDisabled(EventTime event, DecoderCounters counters) {
      audioDisabled++;
    }
  }

  private static final class Sample {
    final String label;
    long targetMs;
    final long sequence, texture, started;
    long frameMs = -1, readyMs = -1, playingMs = -1, ptsUs;

    Sample(String label, long targetMs, long sequence, long texture, long started) {
      this.label = label;
      this.targetMs = targetMs;
      this.sequence = sequence;
      this.texture = texture;
      this.started = started;
    }
  }
}
