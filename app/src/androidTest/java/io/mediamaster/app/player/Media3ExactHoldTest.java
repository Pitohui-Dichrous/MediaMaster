package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opt-in sparse-video regressions. Control gain is tested, not audible output from silent fixtures.
 */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class Media3ExactHoldTest {
  private static final long RELEASE_LIMIT_MS = 1200;
  private static final String[] PREF_KEYS = {
    "engine_choice", "native_decoder_mode", "playback_mode"
  };
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final boolean[] present = new boolean[PREF_KEYS.length];
  private final int[] saved = new int[PREF_KEYS.length];
  private Context context;
  private SharedPreferences preferences;
  private Uri sparse, tail;
  private PlayerActivity activity;
  private VideoPage page;
  private ExoPlayer source;
  private String queueToken;
  private boolean preferencesChanged;

  @Before
  public void explicitFixturesAndIsolatedPreferences() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    String sparseArg = args.getString("benchSparseUri", "");
    String tailArg = args.getString("benchTailUri", "");
    assumeTrue(
        "Explicit generated fixtures are required", !sparseArg.isEmpty() || !tailArg.isEmpty());
    assertFalse("Both fixture arguments are required", sparseArg.isEmpty() || tailArg.isEmpty());
    context = instrumentation.getTargetContext();
    sparse = fixture(sparseArg, false);
    tail = fixture(tailArg, true);
    preferences = context.getSharedPreferences("player", 0);
    for (int i = 0; i < PREF_KEYS.length; i++) {
      present[i] = preferences.contains(PREF_KEYS[i]);
      saved[i] = preferences.getInt(PREF_KEYS[i], 0);
    }
    preferencesChanged = true;
    assertTrue(
        preferences
            .edit()
            .putInt(PREF_KEYS[0], PlaybackEngineChoice.SYSTEM)
            .putInt(PREF_KEYS[1], NativeDecoderPolicy.AUTO)
            .putInt(PREF_KEYS[2], PlaybackOrder.SEQUENTIAL)
            .commit());
  }

  @After
  public void restoreOwnedState() throws Exception {
    try {
      if (activity != null)
        onMain(
            () -> {
              if (!activity.isFinishing()) activity.finish();
              return null;
            });
    } finally {
      QueueStore.forget(queueToken);
      if (preferencesChanged) {
        SharedPreferences.Editor restore = preferences.edit();
        for (int i = 0; i < PREF_KEYS.length; i++) {
          if (present[i]) restore.putInt(PREF_KEYS[i], saved[i]);
          else restore.remove(PREF_KEYS[i]);
        }
        assertTrue("Original preferences must be restored", restore.commit());
      }
    }
  }

  @Test
  public void sparseExactSeekAndNoOpPreservePauseThenResume() throws Exception {
    launchPaused(sparse);
    long elapsed = seekPaused(2500);
    onMain(
        () -> {
          assertEquals(
              "Final seek remains exact", SeekParameters.EXACT, source.getSeekParameters());
          // A 1 fps stream has no frame within 100 ms of this target. Position is a clock here,
          // deliberately not presented as an independent accurate video-PTS measurement.
          assertTrue(
              "A real video callback must have been observed", page.lastRenderedPositionUs >= 0);
          assertTrue(
              "Fixture must exercise sparse output",
              Math.abs(page.lastRenderedPositionUs - 2_500_000) > 100_000);
          page.seekTo(source.getCurrentPosition());
          return null;
        });
    awaitReleasedPaused(2500, RELEASE_LIMIT_MS);
    assertPausedStable(2500);
    resumeAndObserveClock(2500);
    report(1, elapsed);
  }

  @Test
  public void rapidCancelledAndReplacementSeeksDoNotLeaveSuppressionOrMute() throws Exception {
    launchPaused(sparse);
    long start = SystemClock.uptimeMillis();
    onMain(
        () -> {
          page.seekTo(2500);
          assertNotNull("The test must replace an active hold", read(page, "exoExactHold"));
          page.beginScrub();
          page.scrubTo(3500);
          page.endScrub(3500, false);
          page.seekTo(1500);
          return null;
        });
    awaitReleasedPaused(1500, RELEASE_LIMIT_MS);
    long elapsed = SystemClock.uptimeMillis() - start;
    assertPausedStable(1500);
    resumeAndObserveClock(1500);
    report(2, elapsed);
  }

  @Test
  public void audioTailRestoresAudioAndDoesNotAdvanceOrLosePlayIntent() throws Exception {
    launchPaused(tail);
    long elapsed = seekPaused(4500);
    assertPausedStable(4500);
    onMain(
        () -> {
          assertFalse("An audio tail is not the end of the item", (boolean) read(page, "ended"));
          assertTrue(
              "Audio track must be restored",
              source.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO));
          assertFalse(
              "Audio suppression must not remain configured",
              source.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO));
          return null;
        });
    resumeAndObserveClock(4500);
    report(3, elapsed);
  }

  private Uri fixture(String argument, boolean audioTail) throws Exception {
    Uri uri = Uri.parse(argument);
    assertEquals("Only explicit local fixture files are accepted", "file", uri.getScheme());
    assertTrue("Fixture path is required", uri.getPath() != null && !uri.getPath().isEmpty());
    File external = context.getExternalFilesDir(null);
    assertNotNull("App external storage must be available", external);
    File directory = new File(external, "test-only/hold-regression").getCanonicalFile();
    File file = new File(uri.getPath()).getCanonicalFile();
    assertEquals("Fixture must belong to the test directory", directory, file.getParentFile());
    assertTrue("Fixture must be readable", file.isFile() && file.canRead() && file.length() > 0);
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(file.getPath());
      int videoTracks = 0, audioTracks = 0;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("video/")) {
          videoTracks++;
          assertEquals("Generated fixture width", 320, format.getInteger(MediaFormat.KEY_WIDTH));
          assertEquals("Generated fixture height", 180, format.getInteger(MediaFormat.KEY_HEIGHT));
          assertEquals(
              "Generated video duration",
              audioTail ? 2_000_000 : 6_000_000,
              format.getLong(MediaFormat.KEY_DURATION),
              150_000);
          if (!audioTail && format.containsKey(MediaFormat.KEY_FRAME_RATE))
            assertEquals(
                "Sparse fixture frame rate", 1, format.getInteger(MediaFormat.KEY_FRAME_RATE));
        } else if (mime != null && mime.startsWith("audio/")) {
          audioTracks++;
          assertEquals(
              "Generated audio duration",
              6_000_000,
              format.getLong(MediaFormat.KEY_DURATION),
              150_000);
        }
      }
      assertEquals("Exactly one generated video track", 1, videoTracks);
      assertEquals("Exactly one silent audio track", 1, audioTracks);
    } finally {
      extractor.release();
    }
    return Uri.fromFile(file);
  }

  @SuppressWarnings("unchecked")
  private void launchPaused(Uri uri) throws Exception {
    queueToken = UUID.randomUUID().toString();
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(
        new VideoItem(
            "hold-" + queueToken, "fixture.mp4", uri.toString(), "", 0, 6000, 0, 0, 320, 180));
    // An in-memory handoff avoids file-URI Intent exposure and disk queue-cache side effects.
    ((Map<String, ArrayList<VideoItem>>) readStatic(QueueStore.class, "MEMORY"))
        .put(queueToken, queue);
    context.startActivity(
        new Intent(context, PlayerActivity.class)
            .putExtra("queue", queueToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(
        () ->
            onMain(
                () -> {
                  for (android.app.Activity candidate :
                      ActivityLifecycleMonitorRegistry.getInstance()
                          .getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof PlayerActivity) activity = (PlayerActivity) candidate;
                  if (activity == null
                      || activity.isFinishing()
                      || !queueToken.equals(read(activity, "queueToken"))) return false;
                  page = (VideoPage) read(activity, "currentPage");
                  if (page == null
                      || page.item == null
                      || !Uri.parse(page.item.uri).equals(uri)
                      || !page.firstFrameReady
                      || page.buffering) return false;
                  assertNotNull("Explicit system route must use Media3", page.exo);
                  source = page.exo;
                  return source.getPlaybackState() == Player.STATE_READY
                      && source.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO);
                }),
        15000,
        "Generated fixture did not become ready");
    onMain(
        () -> {
          setPlaying(false);
          assertEquals("Whole item includes its audio tail", 6000, source.getDuration(), 150);
          return null;
        });
  }

  private long seekPaused(long target) throws Exception {
    long start = SystemClock.uptimeMillis();
    onMain(
        () -> {
          page.seekTo(target);
          return null;
        });
    awaitReleasedPaused(target, RELEASE_LIMIT_MS);
    return SystemClock.uptimeMillis() - start;
  }

  private void awaitReleasedPaused(long target, long limit) throws Exception {
    await(
        () ->
            onMain(
                () -> {
                  assertIdentity();
                  return read(page, "exoExactHold") == null
                      && !source.isScrubbingModeEnabled()
                      && source.getPlaybackSuppressionReason()
                          == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                      && source.getPlaybackState() == Player.STATE_READY
                      && !page.buffering
                      && !page.failed
                      && !source.getPlayWhenReady()
                      && !page.playRequested
                      && source.getVolume() == 0
                      && Math.abs(source.getCurrentPosition() - target) <= 100
                      && source.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO);
                }),
        limit,
        "Sparse seek retained suppression, changed target, or did not restore paused audio state");
  }

  private void assertPausedStable(long target) throws Exception {
    SystemClock.sleep(180);
    onMain(
        () -> {
          assertIdentity();
          assertFalse("Paused seek must stay paused", source.getPlayWhenReady());
          assertFalse("Paused seek must not run", source.isPlaying());
          assertEquals(
              "Paused clock must remain at the requested target",
              target,
              source.getCurrentPosition(),
              100);
          assertEquals("Paused control gain must be muted", 0f, source.getVolume(), 0f);
          assertNull("No deferred hold may remain", read(page, "exoExactHold"));
          return null;
        });
  }

  private void resumeAndObserveClock(long target) throws Exception {
    onMain(
        () -> {
          setPlaying(true);
          return null;
        });
    await(
        () ->
            onMain(
                () -> {
                  assertIdentity();
                  return source.isPlaying()
                      && source.getPlayWhenReady()
                      && page.playRequested
                      && !source.isScrubbingModeEnabled()
                      && read(page, "exoExactHold") == null
                      && source.getVolume() == 1f
                      && source.getCurrentPosition() >= target + 200
                      && source.getCurrentPosition() < target + 1000;
                }),
        1000,
        "Resuming after the hold did not restore clock and control gain");
    onMain(
        () -> {
          setPlaying(false);
          return null;
        });
  }

  private void assertIdentity() throws Exception {
    assertSame("The seek must not replace the page", page, read(activity, "currentPage"));
    assertSame("The seek must not replace the decoder", source, page.exo);
    assertFalse("The activity must not advance or finish", activity.isFinishing());
  }

  private void setPlaying(boolean playing) throws Exception {
    Method method = PlayerActivity.class.getDeclaredMethod("setPlaying", boolean.class);
    method.setAccessible(true);
    method.invoke(activity, playing);
  }

  private void report(int scenario, long releaseMs) {
    Bundle status = new Bundle();
    status.putInt("holdScenario", scenario);
    status.putLong("optimizationReleaseUpperMs", releaseMs);
    status.putLong("releaseWatchdogMs", RELEASE_LIMIT_MS);
    status.putInt("controlStatePassed", 1);
    instrumentation.sendStatus(2, status);
  }

  private static Object read(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object readStatic(Class<?> owner, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    main.post(task);
    try {
      return task.get(2, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      while (cause instanceof InvocationTargetException)
        cause = ((InvocationTargetException) cause).getCause();
      if (cause instanceof AssertionError) throw (AssertionError) cause;
      if (cause instanceof Exception) throw (Exception) cause;
      throw new RuntimeException(cause);
    }
  }

  private static void await(Callable<Boolean> condition, long timeoutMs, String failure)
      throws Exception {
    long deadline = SystemClock.uptimeMillis() + timeoutMs;
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(20);
    }
    fail(failure);
  }
}
