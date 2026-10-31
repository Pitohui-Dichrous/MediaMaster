package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.media.VideoItem;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Native decoder regressions for scrubbing; deadlines guard hangs, not device speed claims. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class ScrubSessionTest {
  @Rule
  public ActivityTestRule<PlayerActivity> rule =
      new ActivityTestRule<>(PlayerActivity.class, false, false);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final String trialId = Long.toString(SystemClock.elapsedRealtimeNanos());
  private Context context;
  private PlayerActivity activity;
  private int savedMode;

  @Before
  public void permissionsAndPlaybackMode() {
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    savedMode = context.getSharedPreferences("player", 0).getInt("playback_mode", 0);
    context
        .getSharedPreferences("player", 0)
        .edit()
        .putInt("playback_mode", PlaybackOrder.SEQUENTIAL)
        .commit();
  }

  @After
  public void finish() {
    if (activity != null) rule.finishActivity();
    if (context != null)
      context.getSharedPreferences("player", 0).edit().putInt("playback_mode", savedMode).commit();
  }

  @Test
  public void longGopBurstKeepsOnlyTheFinalExactTargetOnTheSameDecoder() throws Exception {
    launch("05_LongGop.mp4");
    VideoPage page = onMain(this::current);
    ExoPlayer source = onMain(() -> page.exo);
    assertNotNull(source);
    pauseAndSeek(page, 1000);
    long beforeSequence = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.beginScrub();
          for (int i = 0; i < 80; i++) page.scrubTo(i % 2 == 0 ? 3400 + i : 22000 - i);
          assertEquals("Intermediate scrubbing is silent", 0f, source.getVolume(), 0f);
          page.endScrub(18700, true);
          return null;
        });
    awaitExactFrame(page, source, beforeSequence, 18700);
    // Allow an obsolete scheduled intermediate request to run, if cancellation was broken.
    SystemClock.sleep(350);
    onMain(
        () -> {
          assertSame("Scrubbing does not reopen the pipeline", source, page.exo);
          assertEquals(18700, source.getCurrentPosition(), 100);
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          assertFalse("A paused drag remains paused", source.getPlayWhenReady());
          assertEquals(0, ((PlaybackOrder) read("order")).current());
          return null;
        });
  }

  @Test
  public void releasingAtTheCompletedCoarseTargetStillRendersAnExactFinalFrame() throws Exception {
    launch("04_Portrait_Loop.mp4");
    VideoPage page = onMain(this::current);
    ExoPlayer source = onMain(() -> page.exo);
    pauseAndSeek(page, 1000);
    long beforeDrag = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.beginScrub();
          page.scrubTo(4200);
          return null;
        });
    await(
        () ->
            page.lastRenderedFrameSequence > beforeDrag
                && Math.abs(page.lastRenderedPositionUs - 4_200_000) <= 350_000
                && onMain(() -> source.getPlaybackState() == Player.STATE_READY),
        15000);
    // This 25 fps fixture has a keyframe every second. Establish that the intermediate seek
    // actually landed on 4.0 s, so an identical final request cannot pass with that old frame.
    assertEquals(
        "The fixture exercises a coarse keyframe landing",
        4_000_000,
        page.lastRenderedPositionUs,
        80_000);
    long beforeCommit = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.endScrub(4200, true);
          return null;
        });
    awaitExactFrame(page, source, beforeCommit, 4200);
    onMain(
        () -> {
          assertSame(source, page.exo);
          assertEquals(4200, source.getCurrentPosition(), 100);
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          assertFalse(source.getPlayWhenReady());
          return null;
        });
  }

  @Test
  public void continuousMoveRequestsProduceARealPreviewBeforeRelease() throws Exception {
    launch("04_Portrait_Loop.mp4");
    VideoPage page = onMain(this::current);
    pauseAndSeek(page, 1000);
    long beforeDrag = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.beginScrub();
          return null;
        });
    boolean capturedDuringMovement = false;
    int moves = 0;
    long deadline = SystemClock.elapsedRealtime() + 15000;
    try {
      while (SystemClock.elapsedRealtime() < deadline) {
        int moveNumber = ++moves;
        long targetMs = 1800 + (moveNumber % 1600) * 2;
        Bitmap preview =
            onMain(
                () -> {
                  page.scrubTo(targetMs);
                  // Keep submitting new positions even while a previous frame is decoding.
                  // Inspect after several moves, not just the first request's idle interval.
                  return moveNumber >= 20 ? page.captureScrubFrame() : null;
                });
        if (preview != null) {
          try {
            assertTrue(page.lastRenderedFrameSequence > beforeDrag);
            assertTrue(
                "Preview contains native video pixels",
                NativeFrameProbe.nonBlackFraction(preview) > .1);
            assertTrue(preview.getWidth() <= 256 && preview.getHeight() <= 144);
            capturedDuringMovement = true;
          } finally {
            preview.recycle();
          }
          break;
        }
        SystemClock.sleep(16);
      }
      assertTrue(
          "Continuous input must not starve previews until pointer-up", capturedDuringMovement);
    } finally {
      onMain(
          () -> {
            page.endScrub(1000, false);
            return null;
          });
    }
  }

  @Test
  public void cancelledPausedScrubReturnsToItsOriginAndRejectsLateRequests() throws Exception {
    launch("04_Portrait_Loop.mp4");
    VideoPage page = onMain(this::current);
    ExoPlayer source = onMain(() -> page.exo);
    pauseAndSeek(page, 1400);
    long beforeDrag = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.beginScrub();
          page.scrubTo(4200);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    source.getPlaybackState() == Player.STATE_READY
                        && page.lastRenderedFrameSequence > beforeDrag
                        && Math.abs(page.lastRenderedPositionUs - 4_200_000) <= 350_000),
        15000);
    long beforeSequence = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.scrubTo(5200);
          page.endScrub(5200, false);
          return null;
        });
    awaitExactFrame(page, source, beforeSequence, 1400);
    SystemClock.sleep(350);
    onMain(
        () -> {
          assertEquals(1400, source.getCurrentPosition(), 100);
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          assertFalse(source.getPlayWhenReady());
          assertEquals(0f, source.getVolume(), 0f);
          return null;
        });
  }

  @Test
  public void scrubbingNearTheEndStaysSilentAndDoesNotAdvanceTheQueue() throws Exception {
    launch("04_Portrait_Loop.mp4", "01_Aurora.mp4");
    VideoPage page = onMain(this::current);
    ExoPlayer source = onMain(() -> page.exo);
    long beforeDrag = page.lastRenderedFrameSequence;
    long nearEnd = onMain(() -> page.duration() - 120);
    onMain(
        () -> {
          setPlaying(true);
          page.beginScrub();
          page.scrubTo(nearEnd);
          return null;
        });
    await(
        () ->
            onMain(
                () ->
                    source.getPlaybackState() == Player.STATE_READY
                        && page.lastRenderedFrameSequence > beforeDrag
                        && Math.abs(page.lastRenderedPositionUs - nearEnd * 1000) <= 350_000),
        15000);
    SystemClock.sleep(350);
    onMain(
        () -> {
          assertSame(page, current());
          assertEquals(
              "A drag near EOF is not natural completion",
              0,
              ((PlaybackOrder) read("order")).current());
          assertEquals(0f, source.getVolume(), 0f);
          assertFalse("Scrubbing suspends actual playback", source.isPlaying());
          page.endScrub(1000, true);
          return null;
        });
    await(() -> onMain(() -> source.isPlaying() && source.getVolume() > .99f), 15000);
    onMain(
        () -> {
          assertSame(source, current().exo);
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          assertEquals(0, ((PlaybackOrder) read("order")).current());
          assertTrue(source.getCurrentPosition() >= 900 && source.getCurrentPosition() < 3000);
          return null;
        });
  }

  @Test
  public void losingPlayIntentDuringAnInitiallyPlayingScrubDoesNotResumeOnRelease()
      throws Exception {
    launch("04_Portrait_Loop.mp4");
    VideoPage page = onMain(this::current);
    ExoPlayer source = onMain(() -> page.exo);
    pauseAndSeek(page, 1000);
    onMain(
        () -> {
          setPlaying(true);
          return null;
        });
    await(() -> onMain(source::isPlaying), 15000);
    long beforeCommit = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          page.beginScrub();
          page.scrubTo(3400);
          assertTrue("The gesture started with a playing intent", source.getPlayWhenReady());
          // Model a playback request revoked independently of the activity's play/pause button.
          // Pointer-up can arrive immediately, before a deferred state listener is delivered.
          source.setPlayWhenReady(false);
          page.endScrub(4200, true);
          return null;
        });
    awaitExactFrame(page, source, beforeCommit, 4200);
    SystemClock.sleep(350);
    onMain(
        () -> {
          assertFalse(
              "Pointer-up must not restore a revoked play intent", source.getPlayWhenReady());
          assertFalse(source.isPlaying());
          assertEquals(0f, source.getVolume(), 0f);
          assertEquals(4200, source.getCurrentPosition(), 100);
          assertEquals(SeekParameters.EXACT, source.getSeekParameters());
          return null;
        });
  }

  @Test
  public void decoderFallbackKeepsTheGestureSilentAndCancellationRestoresItsOrigin()
      throws Exception {
    launch("05_LongGop.mp4");
    VideoPage page = onMain(this::current);
    ExoPlayer original = onMain(() -> page.exo);
    pauseAndSeek(page, 1000);
    onMain(
        () -> {
          setPlaying(true);
          return null;
        });
    await(() -> onMain(original::isPlaying), 15000);
    long origin =
        onMain(
            () -> {
              long position = page.position();
              page.beginScrub();
              page.scrubTo(4200);
              // Force the same private engine entry point used by decoder-error fallback.
              // This tests state transfer, without relying on a deliberately broken video.
              Method fallback =
                  VideoPage.class.getDeclaredMethod("startVlc", long.class, long.class);
              fallback.setAccessible(true);
              fallback.invoke(page, page.generation, 4200L);
              assertNull(page.exo);
              assertNotNull(page.vlc);
              assertEquals(0, page.vlc.getVolume());
              return position;
            });
    MediaPlayer replacement = onMain(() -> page.vlc);
    awaitVlcState(
        page,
        replacement,
        () ->
            onMain(
                () -> {
                  assertSame(replacement, page.vlc);
                  assertEquals("Fallback warmup must remain silent", 0, replacement.getVolume());
                  return page.firstFrameReady && !page.buffering && !replacement.isPlaying();
                }),
        20000,
        "Fallback must finish silent warmup and pause");
    // Let the already-paused warmup frame settle before recording the actual native texture.
    SystemClock.sleep(100);
    long beforeFollowUp = onMain(replacement::getTime);
    long beforeTexture = onMain(() -> textureTimestamp(page.vlcView));
    assertTrue("VLC warmup produced a timestamped native frame", beforeTexture > 0);
    onMain(
        () -> {
          page.scrubTo(12300);
          return null;
        });
    awaitVlcState(
        page,
        replacement,
        () ->
            onMain(
                () -> {
                  assertEquals(0, replacement.getVolume());
                  assertFalse(
                      "The new decoder stays paused during the same gesture",
                      replacement.isPlaying());
                  // Crossing the ten-second GOP must produce a new native frame while paused.
                  // FAST need not report or render the exact 12.3 s target.
                  long timestamp = textureTimestamp(page.vlcView);
                  if (timestamp <= 0 || timestamp == beforeTexture) return false;
                  Bitmap preview = page.captureScrubFrame();
                  if (preview == null) return false;
                  try {
                    assertTrue(
                        "The follow-up seek produces non-black native video pixels",
                        NativeFrameProbe.nonBlackFraction(preview) > .1);
                    return true;
                  } finally {
                    preview.recycle();
                  }
                }),
        15000,
        "The follow-up coarse seek must render a new native preview: beforeTime="
            + beforeFollowUp
            + ", beforeTexture="
            + beforeTexture);
    onMain(
        () -> {
          page.endScrub(12300, false);
          return null;
        });
    // LibVLC's reported clock is sufficient to distinguish restoration of the 1 s origin from
    // the 4.2/12.3 s intermediate targets; it is not claimed as exact rendered-frame evidence.
    awaitVlcState(
        page,
        replacement,
        () ->
            onMain(
                () ->
                    replacement.isPlaying()
                        && page.active
                        && page.playRequested
                        && pageAudible(page)
                        && replacement.getTime() >= origin - 200
                        && replacement.getTime() < origin + 1000),
        15000,
        "Cancellation must restore the original playing position: origin=" + origin);
    // OpenSL ES can apply output gain while LibVLC's getVolume() remains zero. Verify the
    // live native mixer track for this process instead of mistaking that stale getter for mute.
    assertOwnNativeAudioTrackAudible();
    onMain(
        () -> {
          assertSame(page, current());
          assertSame(replacement, page.vlc);
          assertEquals(0, ((PlaybackOrder) read("order")).current());
          return null;
        });
  }

  @Test
  public void committingZeroDuringVlcFallbackWarmupPreservesThePendingSeek() throws Exception {
    launch("04_Portrait_Loop.mp4");
    VideoPage page = onMain(this::current);
    pauseAndSeek(page, 1000);
    MediaPlayer replacement =
        onMain(
            () -> {
              page.beginScrub();
              page.scrubTo(4200);
              Method fallback =
                  VideoPage.class.getDeclaredMethod("startVlc", long.class, long.class);
              fallback.setAccessible(true);
              fallback.invoke(page, page.generation, 4200L);
              assertNotNull(page.vlc);
              // Commit in the same UI callback, before asynchronous warmup/seekable events.
              // A device may already be seekable; either path must retain an explicit zero.
              page.endScrub(0, true);
              return page.vlc;
            });
    awaitVlcState(
        page,
        replacement,
        () ->
            onMain(
                () -> {
                  assertSame(replacement, page.vlc);
                  assertEquals("A paused warmup must remain silent", 0, replacement.getVolume());
                  return page.firstFrameReady
                      && !page.buffering
                      && !replacement.isPlaying()
                      && replacement.getTime() >= 0
                      && replacement.getTime() <= 500;
                }),
        20000,
        "An explicit zero seek during warmup must replace the 4.2 s fallback position");
    onMain(
        () -> {
          Field pending = VideoPage.class.getDeclaredField("pendingSeekSet");
          pending.setAccessible(true);
          assertFalse("The explicit zero request has been consumed", pending.getBoolean(page));
          assertFalse(replacement.isPlaying());
          assertEquals(0, replacement.getVolume());
          return null;
        });
  }

  @Test
  public void releasedPageCannotBeRevivedByQueuedScrubWork() throws Exception {
    launch("04_Portrait_Loop.mp4");
    VideoPage page = onMain(this::current);
    pauseAndSeek(page, 1000);
    onMain(
        () -> {
          page.beginScrub();
          for (int i = 0; i < 50; i++) page.scrubTo(3000 + i * 20);
          page.release();
          // A pointer-up delivered after activity teardown must remain harmless.
          page.endScrub(4500, true);
          return null;
        });
    SystemClock.sleep(350);
    onMain(
        () -> {
          assertNull(page.exo);
          assertNull(page.vlc);
          assertFalse(page.firstFrameReady);
          return null;
        });
  }

  private void launch(String... names) throws Exception {
    ArrayList<VideoItem> queue = new ArrayList<>();
    for (String name : names) queue.add(fixture(name));
    activity =
        rule.launchActivity(
            new Intent(context, PlayerActivity.class)
                .putExtra("queue", QueueStore.put(context, queue)));
    await(
        () ->
            onMain(
                () ->
                    current().exo != null
                        && current().firstFrameReady
                        && current().exo.getPlaybackState() == Player.STATE_READY),
        20000);
  }

  private VideoItem fixture(String name) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "duration", "_size", "date_modified", "width", "height"},
                "_display_name=?",
                new String[] {name},
                null)) {
      if (cursor != null && cursor.moveToFirst()) {
        Uri uri =
            Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
        return new VideoItem(
            // Keep emulator timing samples isolated from later tests' engine choices.
            "scrub-test-" + trialId + "-" + cursor.getString(0),
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
    throw new AssertionError("Install generated fixture first: " + name);
  }

  private void pauseAndSeek(VideoPage page, long targetMs) throws Exception {
    ExoPlayer source = onMain(() -> page.exo);
    assertNotNull(source);
    long beforeSequence = page.lastRenderedFrameSequence;
    onMain(
        () -> {
          setPlaying(false);
          page.seekTo(targetMs);
          return null;
        });
    awaitExactFrame(page, source, beforeSequence, targetMs);
  }

  private void awaitExactFrame(VideoPage page, ExoPlayer source, long beforeSequence, long targetMs)
      throws Exception {
    await(
        () ->
            page.lastRenderedFrameSequence > beforeSequence
                && Math.abs(page.lastRenderedPositionUs - targetMs * 1000) <= 100_000
                && onMain(() -> source.getPlaybackState() == Player.STATE_READY),
        20000);
    assertEquals(
        "Decoder presents the exact final target rather than a distant keyframe",
        targetMs * 1000,
        page.lastRenderedPositionUs,
        100_000);
  }

  private void setPlaying(boolean value) throws Exception {
    Method method = PlayerActivity.class.getDeclaredMethod("setPlaying", boolean.class);
    method.setAccessible(true);
    method.invoke(activity, value);
  }

  private void awaitVlcState(
      VideoPage page,
      MediaPlayer source,
      Callable<Boolean> condition,
      long timeoutMs,
      String description)
      throws Exception {
    try {
      await(condition, timeoutMs);
    } catch (AssertionError error) {
      String actual =
          onMain(
              () ->
                  " [time="
                      + source.getTime()
                      + ", playing="
                      + source.isPlaying()
                      + ", volume="
                      + source.getVolume()
                      + ", buffering="
                      + page.buffering
                      + ", firstFrame="
                      + page.firstFrameReady
                      + ", pendingSeek="
                      + page.pendingSeek
                      + ", texture="
                      + textureTimestamp(page.vlcView)
                      + ", requested="
                      + page.playRequested
                      + ", active="
                      + page.active
                      + ", audible="
                      + pageAudible(page)
                      + ", activityPlay="
                      + read("shouldPlay")
                      + ", seekable="
                      + source.isSeekable()
                      + ", audioTracks="
                      + source.getAudioTracksCount()
                      + ", audioTrack="
                      + source.getAudioTrack()
                      + "]");
      throw new AssertionError(description + actual, error);
    }
  }

  private static boolean pageAudible(VideoPage page) throws Exception {
    Field audible = VideoPage.class.getDeclaredField("audible");
    audible.setAccessible(true);
    return audible.getBoolean(page);
  }

  private void assertOwnNativeAudioTrackAudible() throws Exception {
    String pid = Integer.toString(android.os.Process.myPid());
    long deadline = SystemClock.elapsedRealtime() + 5000;
    String evidence = "No AudioFlinger dump read";
    while (SystemClock.elapsedRealtime() < deadline) {
      String dump;
      try (InputStream input =
              new ParcelFileDescriptor.AutoCloseInputStream(
                  instrumentation
                      .getUiAutomation()
                      .executeShellCommand("dumpsys media.audio_flinger"));
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        dump = new String(output.toByteArray(), StandardCharsets.UTF_8);
      }
      boolean inLiveTable = false;
      int tables = 0;
      StringBuilder ownRows = new StringBuilder();
      for (String line : dump.split("\\r?\\n")) {
        String trimmed = line.trim();
        if (trimmed.startsWith("Type")
            && trimmed.contains("Active Client Session")
            && trimmed.contains("G db  L dB  R dB")) {
          inLiveTable = true;
          tables++;
          continue;
        }
        if (!inLiveTable) continue;
        String[] cells = trimmed.split("\\s+");
        // The optional Type column is blank for ordinary tracks and a letter for fast tracks.
        int active =
            cells.length > 1 && ("yes".equals(cells[1]) || "no".equals(cells[1]))
                ? 1
                : cells.length > 2 && ("yes".equals(cells[2]) || "no".equals(cells[2])) ? 2 : -1;
        if (active < 0 || cells.length <= active + 14) {
          // Stop at the end of the current table: historical AT::add/remove logs are not proof.
          inLiveTable = false;
          continue;
        }
        if (!pid.equals(cells[active + 1])) continue;
        ownRows.append(trimmed).append('\n');
        if ("yes".equals(cells[active])
            && finiteDb(cells[active + 12])
            && finiteDb(cells[active + 13])
            && finiteDb(cells[active + 14])) {
          Log.i("ScrubSessionTest", "Native audio resumed pid=" + pid + ": " + trimmed);
          return;
        }
      }
      evidence = "liveTables=" + tables + ", ownRows=" + ownRows;
      SystemClock.sleep(100);
    }
    fail("No active unmuted AudioFlinger track for test process pid=" + pid + "; " + evidence);
  }

  private static boolean finiteDb(String value) {
    try {
      double gain = Double.parseDouble(value);
      return !Double.isNaN(gain) && !Double.isInfinite(gain);
    } catch (NumberFormatException nonFiniteOrUnsupported) {
      return false;
    }
  }

  private static long textureTimestamp(View view) {
    if (view instanceof TextureView) {
      TextureView texture = (TextureView) view;
      return texture.isAvailable() && texture.getSurfaceTexture() != null
          ? texture.getSurfaceTexture().getTimestamp()
          : 0;
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        long timestamp = textureTimestamp(group.getChildAt(i));
        if (timestamp > 0) return timestamp;
      }
    }
    return 0;
  }

  private VideoPage current() throws Exception {
    return (VideoPage) read("currentPage");
  }

  private Object read(String name) throws Exception {
    Field field = PlayerActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
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

  private static void await(Callable<Boolean> condition, long timeoutMs) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeoutMs;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(25);
    }
    fail("Timed out waiting for scrub decoder state");
  }
}
