package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.SeekBar;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.interfaces.IMedia;

/**
 * Opt-in A/B/A cold-decoder and random-seek diagnostic. No media pictures or identifiers are saved.
 */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class ColdRandomSeekTest {
  private static final String[] PREFS = {"engine_choice", "native_decoder_mode", "playback_mode"};
  private static final double[] COLD = {.82, .18, .66, .08, .92, .34};
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final JSONArray samples = new JSONArray(), taps = new JSONArray(), cold = new JSONArray();
  private final JSONArray transitions = new JSONArray(),
      errors = new JSONArray(),
      phases = new JSONArray();
  private Context context;
  private PlayerActivity activity;
  private Window.Callback originalCallback;
  private String queueToken;
  private ExoPlayer observedExo;
  private Player.Listener errorListener;
  private Object boundPlayer;
  private VideoPage boundPage;
  private View boundSurface;
  private long boundFrameGeneration, epoch, started, deadline, lastChangeAt;
  private int phase, pictureChanges, captureErrors, boundEngineCode;
  private boolean sawPageFailure, coldPlaying, randomDrag;
  private int pressMs;
  private Bitmap previousPicture;
  private long previousEpoch;
  private HandlerThread copyThread;
  private Handler copyHandler;
  private volatile Tap activeTap;
  private String failureStage = "setup";

  @Test
  public void observeColdReopenAndRandomSeeks() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    String uriA = args.getString("benchUriA", ""), uriB = args.getString("benchUriB", "");
    assumeTrue("Supply both explicit local media URIs", !uriA.isEmpty() || !uriB.isEmpty());
    assertFalse("Both explicit media are required", uriA.isEmpty() || uriB.isEmpty());
    assertFalse("Two distinct media are required", uriA.equals(uriB));
    int seconds = integer(args, "benchSeconds", 90);
    int decoder = integer(args, "benchNativeDecoder", 0);
    int engine = integer(args, "benchEngine", 0);
    boolean portrait = Boolean.parseBoolean(args.getString("benchPortrait", "false"));
    boolean singlePhase = Boolean.parseBoolean(args.getString("benchSinglePhase", "false"));
    coldPlaying = Boolean.parseBoolean(args.getString("benchColdPlaying", "false"));
    randomDrag = Boolean.parseBoolean(args.getString("benchDrag", "false"));
    pressMs = integer(args, "benchPressMs", 20);
    long seed = Long.parseLong(args.getString("benchSeed", "47109"));
    assertTrue(seconds >= 15 && seconds <= 180);
    assertTrue(decoder >= 0 && decoder <= 2 && engine >= 0 && engine <= 2);
    assertTrue(pressMs >= 20 && pressMs <= 500);
    String run = args.getString("benchRun", "cold-" + SystemClock.uptimeMillis());
    assertTrue(run.matches("[A-Za-z0-9_-]{1,100}"));
    context = instrumentation.getTargetContext();
    SharedPreferences preferences = context.getSharedPreferences("player", 0);
    boolean[] present = new boolean[PREFS.length];
    int[] saved = new int[PREFS.length];
    for (int i = 0; i < PREFS.length; i++) {
      present[i] = preferences.contains(PREFS[i]);
      saved[i] = preferences.getInt(PREFS[i], 0);
    }
    JSONObject result =
        new JSONObject()
            .put("protocol", "cold-random-v3")
            .put("randomBudgetSeconds", seconds)
            .put("overallLimitMs", 200000)
            .put("seed", seed)
            .put("engineChoice", engine)
            .put("nativeDecoderChoice", decoder)
            .put("originalEngineChoice", saved[0])
            .put("originalNativeDecoderChoice", saved[1])
            .put("originalPlaybackMode", saved[2])
            .put("portrait", portrait)
            .put("singlePhase", singlePhase)
            .put("coldPlaying", coldPlaying)
            .put("randomDrag", randomDrag)
            .put("nominalSampleIntervalMs", 100)
            .put("scheduledPressMs", pressMs)
            .put("randomDragDurationMs", randomDrag ? 300 : JSONObject.NULL)
            .put(
                "pressCoverage",
                "20 ms taps do not exercise the 50 ms first-preview delay; inspect"
                    + " scrubSubmittedCount")
            .put(
                "coldMeaning", "new Activity and decoder; filesystem and OS caches are not cleared")
            .put(
                "pictureEvidence",
                "surface changes only; native clocks and queue ACK do not prove exact frame PTS")
            .put(
                "coldPictureEvidence",
                "first changed surface is sampled after UP; while playing it may be natural"
                    + " playback, not request-specific output")
            .put(
                "errorCoverage",
                "additive Media3 error listener; native state polling may miss transient errors")
            .put("phaseOrder", singlePhase ? "A" : "A,B,A")
            .put("phases", phases)
            .put("coldSeeks", cold)
            .put("taps", taps)
            .put("samples", samples)
            .put("engineTransitions", transitions)
            .put("errors", errors)
            .put("completed", false)
            .put("passed", false);
    writePreferenceRecovery(run, saved, present);
    result.put("preferenceRecoverySnapshotWritten", true);
    ScopedPlaybackHistory history = null;
    Throwable failure = null;
    try {
      history = onMain(() -> ScopedPlaybackHistory.open(context));
      result.put("engineHistoryIsolated", true);
      automation.grantRuntimePermission(
          context.getPackageName(),
          Build.VERSION.SDK_INT >= 33
              ? Manifest.permission.READ_MEDIA_VIDEO
              : Manifest.permission.READ_EXTERNAL_STORAGE);
      VideoItem a = fixture(Uri.parse(uriA), "a"), b = fixture(Uri.parse(uriB), "b");
      assertTrue(
          preferences
              .edit()
              .putInt(PREFS[0], engine)
              .putInt(PREFS[1], decoder)
              .putInt(PREFS[2], PlaybackOrder.SINGLE)
              .commit());
      copyThread = new HandlerThread("cold-random-copy");
      copyThread.start();
      copyHandler = new Handler(copyThread.getLooper());
      started = SystemClock.uptimeMillis();
      deadline = started + 200000;
      int[] budgets =
          singlePhase
              ? new int[] {seconds * 1000}
              : new int[] {seconds * 600, Math.min(10000, seconds * 100), seconds * 300};
      VideoItem[] items = singlePhase ? new VideoItem[] {a} : new VideoItem[] {a, b, a};
      for (int p = 0; p < items.length; p++) {
        phase = p + 1;
        JSONObject phaseResult = new JSONObject().put("phase", phase).put("openedAtMs", elapsed());
        phases.put(phaseResult);
        try {
          launch(items[p], p == 1 ? a : b, portrait);
          waitForReady(phaseResult);
          if (portrait)
            onMain(
                () -> {
                  Method method =
                      PlayerActivity.class.getDeclaredMethod("setPortraitMode", boolean.class);
                  method.setAccessible(true);
                  method.invoke(activity, true);
                  return null;
                });
          setPlaying(coldPlaying);
          sampleFor(300);
          for (int i = 0; i < COLD.length; i++) coldSeek(i + 1, COLD[i]);
          setPlaying(true);
          int effectiveBudget =
              singlePhase
                  ? (int)
                      Math.max(
                          0, Math.min(budgets[p], deadline - SystemClock.uptimeMillis() - 13000))
                  : budgets[p];
          phaseResult
              .put("requestedRandomBudgetMs", budgets[p])
              .put("effectiveRandomBudgetMs", effectiveBudget);
          randomWork(effectiveBudget, new Random(seed + (p == 1 ? 1 : 0)));
          int before = pictureChanges;
          JSONObject output = observeTail(p == items.length - 1 ? 10000 : 3000);
          Snapshot last = observe();
          phaseResult
              .put("tailPictureChanges", pictureChanges - before)
              .put("outputObservation", output)
              .put("finalState", last.state)
              .put("endedAtMs", elapsed());
          // A static scene alone is not a decoder failure; preserve raw output/counter evidence.
          assertFalse("Playback exhausted all routes", last.state.optBoolean("failed"));
          assertTrue("Playback intent was not retained", last.state.optBoolean("playRequested"));
          assertTrue(
              "Playback did not resume after the idle tail", last.state.optBoolean("playing"));
          assertFalse("Native seek queue remained stuck", last.state.optBoolean("queueWork"));
          assertFalse("Exact-seek hold remained stuck", last.state.optBoolean("exactHold"));
          assertFalse(
              "Scrubbing remained active after finger release", last.state.optBoolean("scrubbing"));
          assertTrue(
              "Idle tail has no usable output/progress evidence", output.getBoolean("observed"));
        } finally {
          closeActivity();
        }
      }
      result.put("completed", true).put("passed", !sawPageFailure);
    } catch (Throwable error) {
      failure = error;
      result
          .put("failurePhase", phase)
          .put("failureCategory", category(error))
          .put("failureStage", failureStage)
          .put("failureSite", failureSite(error));
      if (activeTap != null) result.put("lastInput", inputDiagnostic(activeTap));
    } finally {
      try {
        try {
          if (activeTap != null && activeTap.downHandled > 0 && activeTap.upHandled == 0)
            inject(activeTap, MotionEvent.ACTION_CANCEL, true);
        } finally {
          try {
            closeActivity();
          } finally {
            if (history != null) {
              history.closeAfterActivityDestroyed(activity);
              result.put("engineHistoryRestored", true);
            }
          }
        }
      } catch (Throwable cleanup) {
        if (failure == null) failure = cleanup;
        result.put("passed", false).put("cleanupFailed", true);
      } finally {
        SharedPreferences.Editor restore = preferences.edit();
        for (int i = 0; i < PREFS.length; i++) {
          if (present[i]) restore.putInt(PREFS[i], saved[i]);
          else restore.remove(PREFS[i]);
        }
        boolean restored = restore.commit();
        result
            .put("preferencesRestored", restored)
            .put("captureErrors", captureErrors)
            .put("pictureChanges", pictureChanges)
            .put("pageFailureObserved", sawPageFailure)
            .put("elapsedMs", started == 0 ? 0 : elapsed());
        if (!restored) {
          result.put("passed", false);
          if (failure == null) failure = new AssertionError("Preference restore failed");
        }
        if (previousPicture != null) {
          previousPicture.recycle();
          previousPicture = null;
        }
        if (copyThread != null) copyThread.quitSafely();
        writeResult(run, result);
      }
    }
    if (failure != null || !result.optBoolean("passed"))
      throw new AssertionError("Cold random seek diagnostic failed in phase " + phase);
  }

  private void coldSeek(int index, double fraction) throws Exception {
    requireTime();
    Snapshot start = observe();
    Bitmap origin = start.surface == null ? null : capture(start.surface);
    Tap tap = tap(fraction);
    JSONObject row =
        new JSONObject()
            .put("phase", phase)
            .put("index", index)
            .put("fraction", fraction)
            .put("playing", coldPlaying)
            .put("startEpoch", start.epoch)
            .put("before", start.state);
    cold.put(row);
    long firstChanged = -1, firstAck = -1;
    Snapshot last = start;
    long until = Math.min(deadline, SystemClock.uptimeMillis() + 4000);
    try {
      while (SystemClock.uptimeMillis() < until) {
        last = tick();
        long now = SystemClock.uptimeMillis();
        if (firstChanged < 0
            && origin != null
            && previousPicture != null
            && previousEpoch == last.epoch
            && NativeFrameProbe.averageRgbDistance(origin, previousPicture) > 3.5)
          firstChanged = now;
        if (firstAck < 0
            && tap.upHandled > 0
            && last.state.optLong("requestedTargetMs", -1) == tap.target
            && !last.state.optBoolean("queueWork")
            && !last.state.optBoolean("exactHold")
            && !last.state.optBoolean("scrubbing")
            && !last.state.optBoolean("buffering")) firstAck = now;
        if (firstChanged > 0 && firstAck > 0) break;
        SystemClock.sleep(80);
      }
      row.put("firstChangedFromEventUpMs", delta(firstChanged, tap.eventUp))
          .put("queueAndHoldReleasedFromEventUpMs", delta(firstAck, tap.eventUp))
          .put("pictureChanged", firstChanged > 0)
          .put("controlReleased", firstAck > 0)
          .put("engineChanged", last.epoch != start.epoch)
          .put("targetMs", tap.target)
          .put("after", last.state);
      sampleFor(200);
    } finally {
      if (origin != null) origin.recycle();
    }
  }

  private void randomWork(int requestedMs, Random random) throws Exception {
    long begin = SystemClock.uptimeMillis(), until = Math.min(deadline, begin + requestedMs);
    long nextTap = begin;
    int recoveryWindows = 0;
    while (SystemClock.uptimeMillis() < until) {
      requireTime();
      long now = SystemClock.uptimeMillis();
      boolean recovery = (now - begin) % 12000 >= 10000;
      if (!recovery && now >= nextTap) {
        tap(.06 + .88 * random.nextDouble(), randomDrag);
        nextTap = SystemClock.uptimeMillis() + 180 + random.nextInt(620);
      }
      if (recovery) recoveryWindows++;
      tick();
      SystemClock.sleep(80);
    }
    phases
        .getJSONObject(phase - 1)
        .put("randomElapsedMs", SystemClock.uptimeMillis() - begin)
        .put("recoveryWindowSamples", recoveryWindows);
  }

  private void sampleFor(long milliseconds) throws Exception {
    long until = Math.min(deadline, SystemClock.uptimeMillis() + milliseconds);
    while (SystemClock.uptimeMillis() < until) {
      tick();
      SystemClock.sleep(80);
    }
    requireTime();
  }

  private JSONObject observeTail(long milliseconds) throws Exception {
    long until = Math.min(deadline, SystemClock.uptimeMillis() + milliseconds);
    int usable = 0, progress = 0, resets = 0, rgbChanges = 0, missingStats = 0;
    Snapshot previous = null;
    while (SystemClock.uptimeMillis() < until) {
      Snapshot current = tick();
      JSONObject now = current.state;
      assertFalse("Playback failed during the idle tail", now.optBoolean("failed"));
      if (now.optBoolean("captureUsable")) {
        usable++;
        boolean counterAvailable = now.has("displayed") || now.has("exoFrameSequence");
        if (!counterAvailable) missingStats++;
        if (now.optDouble("rgbChange", 0) > .8) rgbChanges++;
        if (previous != null && previous.epoch == current.epoch) {
          JSONObject before = previous.state;
          if (now.has("displayed") && before.has("displayed")) {
            long displayed = now.getLong("displayed") - before.getLong("displayed");
            if (displayed < 0) resets++;
            else if (displayed > 0) progress++;
          } else if (now.has("exoFrameSequence") && before.has("exoFrameSequence")) {
            long frames = now.getLong("exoFrameSequence") - before.getLong("exoFrameSequence");
            if (frames < 0) resets++;
            else if (frames > 0 && now.optLong("exoRenderedPtsUs", -1) >= 0) progress++;
          }
        }
      }
      previous = current;
      SystemClock.sleep(80);
    }
    requireTime();
    boolean observed = usable >= 5 && (progress >= 2 || missingStats > 0 && rgbChanges >= 2);
    return new JSONObject()
        .put("observed", observed)
        .put("usableCaptures", usable)
        .put("counterProgressWindows", progress)
        .put("counterResets", resets)
        .put("rgbChanges", rgbChanges)
        .put("capturesWithoutCounters", missingStats)
        .put(
            "evidence",
            "usable surface plus monotonic displayed or renderer progress; RGB fallback only"
                + " when counters unavailable");
  }

  private Snapshot tick() throws Exception {
    requireTime();
    Snapshot before = observe();
    Bitmap frame = null;
    try {
      if (before.surface != null) frame = capture(before.surface);
      Snapshot after = observe();
      JSONObject row = after.state;
      if (frame != null && before.epoch == after.epoch) {
        row.put("captureUsable", true);
        double change =
            previousPicture != null && previousEpoch == after.epoch
                ? NativeFrameProbe.averageRgbDistance(previousPicture, frame)
                : -1;
        row.put("rgbChange", change < 0 ? JSONObject.NULL : Math.round(change * 100) / 100d);
        if (change > .8) {
          pictureChanges++;
          lastChangeAt = SystemClock.uptimeMillis();
        }
        row.put("unchangedMs", lastChangeAt == 0 ? 0 : SystemClock.uptimeMillis() - lastChangeAt);
        if (previousPicture != null) previousPicture.recycle();
        previousPicture = frame;
        previousEpoch = after.epoch;
        frame = null;
      } else row.put("captureUnavailable", true).put("captureUsable", false);
      samples.put(row);
      return after;
    } catch (java.util.concurrent.TimeoutException | AssertionError unavailable) {
      captureErrors++;
      samples.put(before.state.put("captureUnavailable", true).put("captureUsable", false));
      return before;
    } finally {
      if (frame != null) frame.recycle();
    }
  }

  private Snapshot observe() throws Exception {
    return onMain(
        () -> {
          VideoPage page = activity == null ? null : (VideoPage) read(activity, "currentPage");
          Object player = page == null ? null : page.exo != null ? page.exo : page.vlc;
          View surface =
              page == null
                  ? null
                  : page.exo != null
                      ? page.exoView.getVideoSurfaceView()
                      : NativeFrameProbe.findVideoSurface(page.vlcView);
          if (surface != null && (surface.getWidth() <= 0 || surface.getHeight() <= 0))
            surface = null;
          long frameGeneration = page == null ? -1 : (long) read(page, "engineFrameGeneration");
          if (page != boundPage
              || player != boundPlayer
              || surface != boundSurface
              || frameGeneration != boundFrameGeneration) {
            int previousEngine = boundEngineCode;
            boundPage = page;
            boundPlayer = player;
            boundSurface = surface;
            boundFrameGeneration = frameGeneration;
            boundEngineCode = engineCode(page);
            lastChangeAt = SystemClock.uptimeMillis();
            epoch++;
            transitions.put(
                new JSONObject()
                    .put("atMs", elapsed())
                    .put("phase", phase)
                    .put("epoch", epoch)
                    .put("fromEngineCode", previousEngine)
                    .put("toEngineCode", boundEngineCode));
            bindErrorListener(page == null ? null : page.exo);
          }
          JSONObject state =
              new JSONObject()
                  .put("atMs", elapsed())
                  .put("phase", phase)
                  .put("epoch", epoch)
                  .put("engineCode", engineCode(page))
                  .put("surfaceAvailable", surface != null);
          recordNeighbors(state);
          if (page != null) {
            sawPageFailure |= page.failed;
            NativeSeekQueue queue = (NativeSeekQueue) read(page, "nativeSeekQueue");
            NativeSeekQueue.Request submitted = queue.submitted();
            NativeSeekQueue.Request pending = (NativeSeekQueue.Request) read(queue, "pending");
            state
                .put("failed", page.failed)
                .put("buffering", page.buffering)
                .put("firstFrameReady", page.firstFrameReady)
                .put("currentIndex", page.index)
                .put("currentPrepareCount", page.prepareCount)
                .put("playRequested", page.playRequested)
                .put("activityShouldPlay", (boolean) read(activity, "shouldPlay"))
                .put("scrubbing", (boolean) read(page, "scrubbing"))
                .put("scrubSubmittedCount", (int) read(page, "scrubSubmittedCount"))
                .put("requestedTargetMs", (long) read(page, "explicitSeekTarget"))
                .put("queueWork", queue.hasWork())
                .put("queuePending", queue.hasPending())
                .put("nativeQueueSerial", (long) read(queue, "serial"))
                .put("nativeSubmittedId", submitted == null ? JSONObject.NULL : submitted.id)
                .put("nativeSubmittedFast", submitted == null ? JSONObject.NULL : submitted.fast)
                .put(
                    "nativeSubmittedPositionMs",
                    submitted == null ? JSONObject.NULL : submitted.positionMs)
                .put("nativePendingId", pending == null ? JSONObject.NULL : pending.id)
                .put("nativePendingFast", pending == null ? JSONObject.NULL : pending.fast)
                .put(
                    "nativePendingPositionMs",
                    pending == null ? JSONObject.NULL : pending.positionMs)
                .put(
                    "queueAgeMs",
                    queue.submitted() == null
                        ? 0
                        : SystemClock.uptimeMillis() - (long) read(queue, "submittedAtMs"))
                .put("exactHold", read(page, "exoExactHold") != null)
                .put("nativeAwaitingPause", (boolean) read(page, "nativeSeekAwaitingPause"))
                .put(
                    "nativeSeekDeadlineRemainingMs",
                    Math.max(
                        0, (long) read(page, "nativeSeekDeadline") - SystemClock.uptimeMillis()))
                .put(
                    "nativeWarmupDeadlineRemainingMs",
                    Math.max(0, (long) read(page, "warmupDeadline") - SystemClock.uptimeMillis()))
                .put("triedNativeSoftware", (boolean) read(page, "triedNativeSoftware"))
                .put("triedNativeHardware", (boolean) read(page, "triedNativeHardware"))
                .put("triedMedia3", (boolean) read(page, "triedExo"))
                .put("triedMedia3Hardware", (boolean) read(page, "triedExoHardware"));
            if (page.exo != null)
              state
                  .put("exoState", page.exo.getPlaybackState())
                  .put("playing", page.exo.isPlaying())
                  .put("exoSuppression", page.exo.getPlaybackSuppressionReason())
                  .put("exoGain", page.exo.getVolume())
                  .put("exoFrameSequence", page.lastRenderedFrameSequence)
                  .put("exoRenderedPtsUs", page.lastRenderedPositionUs)
                  .put(
                      "exoErrorCode",
                      page.exo.getPlayerError() == null ? 0 : page.exo.getPlayerError().errorCode);
            if (page.vlc != null) {
              state.put("playing", page.vlc.isPlaying()).put("nativeClockMs", page.vlc.getTime());
              try {
                state.put(
                    "nativeState",
                    page.vlc.getClass().getMethod("getPlayerState").invoke(page.vlc));
              } catch (NoSuchMethodException absent) {
                state.put("nativeState", JSONObject.NULL);
              }
              IMedia media = page.vlc.getMedia();
              try {
                IMedia.Stats stats = media == null ? null : media.getStats();
                if (stats != null)
                  state
                      .put("decoded", stats.decodedVideo)
                      .put("displayed", stats.displayedPictures)
                      .put("lost", stats.lostPictures);
              } finally {
                if (media != null) media.release();
              }
              Object frames = read(page, "nativeSurfaceFrames");
              if (frames != null)
                state.put("nativeSurfaceSequence", (long) read(frames, "frameSequence"));
            }
          }
          Field shared = NativePlayerRetirement.class.getDeclaredField("SHARED");
          shared.setAccessible(true);
          NativePlayerRetirement retirement = (NativePlayerRetirement) shared.get(null);
          state
              .put("nativeOwned", retirement.ownedCount())
              .put("nativeQuarantined", retirement.quarantinedCount());
          return new Snapshot(surface, epoch, state);
        });
  }

  private void bindErrorListener(ExoPlayer source) {
    if (observedExo == source) return;
    if (observedExo != null && errorListener != null) observedExo.removeListener(errorListener);
    observedExo = source;
    errorListener = null;
    if (source == null) return;
    final long sourceEpoch = epoch;
    errorListener =
        new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            try {
              errors.put(
                  new JSONObject()
                      .put("atMs", elapsed())
                      .put("phase", phase)
                      .put("epoch", sourceEpoch)
                      .put("media3ErrorCode", error.errorCode));
            } catch (org.json.JSONException impossible) {
              throw new AssertionError("Numeric diagnostic error");
            }
          }
        };
    source.addListener(errorListener);
  }

  /** Main-thread numeric snapshots of real adjacent windows, never media identifiers. */
  private void recordNeighbors(JSONObject state) throws Exception {
    JSONArray neighbors = new JSONArray();
    int count = 0, warming = 0, prepareCount = 0;
    if (activity != null) {
      state.put("portraitMode", (boolean) read(activity, "portraitMode"));
      for (String field : new String[] {"previousPage", "nextPage"}) {
        VideoPage neighbor = (VideoPage) read(activity, field);
        if (neighbor == null) continue;
        boolean bound = neighbor.item != null && neighbor.index >= 0;
        boolean nativeWarming = (boolean) read(neighbor, "warmingVlc");
        if (bound) count++;
        if (bound && (nativeWarming || !neighbor.firstFrameReady)) warming++;
        prepareCount += neighbor.prepareCount;
        neighbors.put(
            new JSONObject()
                .put("bound", bound)
                .put("index", neighbor.index)
                .put("engineCode", engineCode(neighbor))
                .put("firstFrameReady", neighbor.firstFrameReady)
                .put("buffering", neighbor.buffering)
                .put("nativeWarming", nativeWarming)
                .put("prepareCount", neighbor.prepareCount));
      }
    }
    state
        .put("neighbors", neighbors)
        .put("neighborCount", count)
        .put("neighborWarmingCount", warming)
        .put("neighborPrepareCount", prepareCount);
  }

  private int engineCode(VideoPage page) throws Exception {
    if (page == null) return 0;
    if (page.exo != null)
      return (boolean) read(page, "exoHardwareConfigured")
          ? 1
          : (boolean) read(page, "exoSoftwareConfigured") ? 2 : 3;
    if (page.vlc != null) return (boolean) read(page, "nativeSoftware") ? 4 : 5;
    return 0;
  }

  private void setPlaying(boolean playing) throws Exception {
    onMain(
        () -> {
          Field intent = PlayerActivity.class.getDeclaredField("shouldPlay");
          intent.setAccessible(true);
          intent.setBoolean(activity, playing);
          VideoPage page = (VideoPage) read(activity, "currentPage");
          page.setPlayback(true, playing, true);
          return null;
        });
  }

  private Tap tap(double fraction) throws Exception {
    return tap(fraction, false);
  }

  private Tap tap(double fraction, boolean drag) throws Exception {
    failureStage = "input-layout";
    int[] geometry = awaitSeekGeometry();
    Tap tap =
        onMain(
            () -> {
              SeekBar bar = (SeekBar) read(activity, "seek");
              int width = geometry[2] - geometry[4] - geometry[5];
              int trackLeft = geometry[0] + geometry[4];
              int startX = trackLeft + Math.round(width * bar.getProgress() / (float) bar.getMax());
              int endX = trackLeft + (int) Math.round(width * fraction);
              if (drag && Math.abs(endX - startX) < width / 4)
                endX = trackLeft + Math.round(width * (startX - trackLeft < width / 2 ? .9f : .1f));
              Tap next = new Tap(endX, geometry[1] + geometry[3] / 2, startX, drag);
              next.geometry = geometry;
              next.serialBefore = (long) read(read(activity, "currentPage"), "explicitSeekSerial");
              return next;
            });
    activeTap = tap;
    failureStage = "input-down";
    inject(tap, MotionEvent.ACTION_DOWN, false, drag ? tap.startX : tap.x);
    long consumeDeadline = Math.min(deadline, SystemClock.uptimeMillis() + 1500);
    boolean consumed = false;
    while (SystemClock.uptimeMillis() < consumeDeadline) {
      consumed =
          onMain(
              () -> {
                VideoPage page = (VideoPage) read(activity, "currentPage");
                tap.serialAfter = (long) read(page, "explicitSeekSerial");
                tap.pageScrubbing = (boolean) read(page, "scrubbing");
                tap.activityScrubbing = (boolean) read(activity, "scrubbing");
                tap.previewCount = (int) read(page, "scrubSubmittedCount");
                return tap.downHandled > 0 && tap.pageScrubbing && tap.activityScrubbing;
              });
      if (consumed) break;
      SystemClock.sleep(10);
    }
    assertTrue("DOWN did not enter SeekBar tracking", consumed);
    tap.consumedAt = SystemClock.uptimeMillis();
    failureStage = "input-held";
    if (drag) {
      int[] times = {90, 180, 280};
      for (int i = 0; i < times.length; i++) {
        waitUntil(tap.consumedAt + times[i]);
        inject(
            tap,
            MotionEvent.ACTION_MOVE,
            false,
            tap.startX + Math.round((tap.x - tap.startX) * (i + 1) / 3f));
      }
      waitUntil(tap.consumedAt + 300);
    } else {
      if (pressMs >= 50) {
        waitUntil(tap.consumedAt + 30);
        inject(tap, MotionEvent.ACTION_MOVE, false);
      }
      waitUntil(tap.consumedAt + pressMs);
    }
    if (drag || pressMs >= 50) {
      tap.previewCount =
          onMain(() -> (int) read(read(activity, "currentPage"), "scrubSubmittedCount"));
      assertTrue("Held input must dispatch a real preview", tap.previewCount > 0);
    }
    failureStage = "input-up";
    inject(tap, MotionEvent.ACTION_UP, false);
    long until = Math.min(deadline, SystemClock.uptimeMillis() + 1500);
    while (tap.upHandled == 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(10);
    assertTrue("Physical UP was not delivered", tap.upHandled > 0);
    onMain(
        () -> {
          VideoPage page = (VideoPage) read(activity, "currentPage");
          tap.serialAfter = (long) read(page, "explicitSeekSerial");
          tap.pageScrubbing = (boolean) read(page, "scrubbing");
          tap.activityScrubbing = (boolean) read(activity, "scrubbing");
          assertTrue(
              "UP must commit a consumed seek",
              tap.serialAfter > tap.serialBefore && tap.target >= 0);
          assertFalse(
              "UP must finish SeekBar tracking", tap.pageScrubbing || tap.activityScrubbing);
          return null;
        });
    if (drag) assertEquals("All three physical MOVE events must arrive", 3, tap.moves);
    taps.put(
        new JSONObject()
            .put("phase", phase)
            .put("atMs", tap.eventDown - started)
            .put("fraction", fraction)
            .put("drag", drag)
            .put("deliveredMoveCount", tap.moves)
            .put("targetMs", tap.target)
            .put("inputConsumed", true)
            .put("heldAfterConsumptionMs", tap.eventUp - tap.consumedAt)
            .put("previewCountBeforeUp", tap.previewCount)
            .put("serialAdvance", tap.serialAfter - tap.serialBefore)
            .put("eventPressMs", tap.eventUp - tap.eventDown)
            .put("downDeliveryLagMs", tap.downReceived - tap.eventDown)
            .put("upDeliveryLagMs", tap.upReceived - tap.eventUp)
            .put("downDispatchMs", tap.downHandled - tap.downReceived)
            .put("upDispatchMs", tap.upHandled - tap.upReceived));
    failureStage = "observation";
    return tap;
  }

  /**
   * Input coordinates are sampled only after focused, visible layout stays unchanged for 200 ms.
   */
  private int[] awaitSeekGeometry() throws Exception {
    onMain(
        () -> {
          for (String name : new String[] {"showChrome", "updateProgress"}) {
            Method method = PlayerActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(activity);
          }
          return null;
        });
    long until = Math.min(deadline, SystemClock.uptimeMillis() + 3000), stableSince = 0;
    int[] last = null;
    while (SystemClock.uptimeMillis() < until) {
      int[] now =
          onMain(
              () -> {
                SeekBar bar = (SeekBar) read(activity, "seek");
                View root = activity.getWindow().getDecorView();
                Rect visible = new Rect();
                if (!root.hasWindowFocus()
                    || !bar.hasWindowFocus()
                    || !bar.isShown()
                    || !bar.isLaidOut()
                    || bar.isLayoutRequested()
                    || root.isLayoutRequested()
                    || bar.getWidth() <= bar.getPaddingLeft() + bar.getPaddingRight()
                    || bar.getHeight() <= 0
                    || !bar.getGlobalVisibleRect(visible)) return null;
                int[] xy = new int[2], rootXY = new int[2];
                bar.getLocationOnScreen(xy);
                root.getLocationOnScreen(rootXY);
                return new int[] {
                  xy[0],
                  xy[1],
                  bar.getWidth(),
                  bar.getHeight(),
                  bar.getPaddingLeft(),
                  bar.getPaddingRight(),
                  rootXY[0],
                  rootXY[1],
                  root.getWidth(),
                  root.getHeight(),
                  visible.left,
                  visible.top,
                  visible.right,
                  visible.bottom
                };
              });
      long at = SystemClock.uptimeMillis();
      if (now == null || !Arrays.equals(last, now)) {
        last = now;
        stableSince = at;
      } else if (at - stableSince >= 200) return now;
      SystemClock.sleep(40);
    }
    throw new AssertionError("Seek geometry did not settle");
  }

  private static JSONObject inputDiagnostic(Tap tap) throws Exception {
    JSONObject result =
        new JSONObject()
            .put("x", tap.x)
            .put("y", tap.y)
            .put("eventDown", tap.eventDown)
            .put("downReceived", tap.downReceived)
            .put("downHandled", tap.downHandled)
            .put("consumedAt", tap.consumedAt)
            .put("eventUp", tap.eventUp)
            .put("upHandled", tap.upHandled)
            .put("serialBefore", tap.serialBefore)
            .put("serialAfter", tap.serialAfter)
            .put("previewCount", tap.previewCount)
            .put("pageScrubbing", tap.pageScrubbing)
            .put("activityScrubbing", tap.activityScrubbing)
            .put("targetMs", tap.target);
    if (tap.geometry != null) result.put("geometry", new JSONArray(tap.geometry));
    return result;
  }

  private static String failureSite(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause())
      for (StackTraceElement site : current.getStackTrace())
        if (site.getClassName().equals(ColdRandomSeekTest.class.getName()))
          return "ColdRandomSeekTest." + site.getMethodName() + ":" + site.getLineNumber();
    return "unavailable";
  }

  private void inject(Tap tap, int action, boolean synchronous) {
    inject(tap, action, synchronous, tap.x);
  }

  private void inject(Tap tap, int action, boolean synchronous, int x) {
    long now = SystemClock.uptimeMillis();
    if (action == MotionEvent.ACTION_DOWN) tap.eventDown = now;
    if (action == MotionEvent.ACTION_UP) tap.eventUp = now;
    MotionEvent event = MotionEvent.obtain(tap.eventDown, now, action, x, tap.y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("Input injection rejected", automation.injectInputEvent(event, synchronous));
    } finally {
      event.recycle();
    }
  }

  @SuppressWarnings("unchecked")
  private void launch(VideoItem item, VideoItem neighbor, boolean portrait) throws Exception {
    requireTime();
    queueToken = UUID.randomUUID().toString();
    ArrayList<VideoItem> queue = new ArrayList<>();
    queue.add(item);
    if (portrait) queue.add(neighbor);
    Field memory = QueueStore.class.getDeclaredField("MEMORY");
    memory.setAccessible(true);
    ((Map<String, ArrayList<VideoItem>>) memory.get(null)).put(queueToken, queue);
    activity = null;
    context.startActivity(
        new Intent(context, PlayerActivity.class)
            .putExtra("queue", queueToken)
            .putExtra("index", 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  private void waitForReady(JSONObject phaseResult) throws Exception {
    long until = Math.min(deadline, SystemClock.uptimeMillis() + 16000);
    while (SystemClock.uptimeMillis() < until) {
      onMain(
          () -> {
            if (activity == null)
              for (Activity candidate :
                  ActivityLifecycleMonitorRegistry.getInstance()
                      .getActivitiesInStage(Stage.RESUMED))
                if (candidate instanceof PlayerActivity
                    && queueToken.equals(read(candidate, "queueToken")))
                  activity = (PlayerActivity) candidate;
            return null;
          });
      Snapshot state = tick();
      if (activity != null
          && state.state.optBoolean("firstFrameReady")
          && !state.state.optBoolean("buffering")
          && state.surface != null) {
        installInputProbe();
        phaseResult.put("readyAtMs", elapsed()).put("initialState", state.state);
        return;
      }
      SystemClock.sleep(50);
    }
    throw new AssertionError("Explicit media did not open within the observation bound");
  }

  private void installInputProbe() throws Exception {
    onMain(
        () -> {
          originalCallback = activity.getWindow().getCallback();
          activity
              .getWindow()
              .setCallback(
                  (Window.Callback)
                      Proxy.newProxyInstance(
                          getClass().getClassLoader(),
                          new Class<?>[] {Window.Callback.class},
                          (proxy, method, arguments) -> {
                            MotionEvent event =
                                method.getName().equals("dispatchTouchEvent")
                                        && arguments != null
                                        && arguments.length == 1
                                        && arguments[0] instanceof MotionEvent
                                    ? (MotionEvent) arguments[0]
                                    : null;
                            Tap tap = activeTap;
                            boolean tracked =
                                event != null
                                    && tap != null
                                    && event.getDownTime() == tap.eventDown;
                            long received = SystemClock.uptimeMillis();
                            if (tracked && event.getActionMasked() == MotionEvent.ACTION_DOWN)
                              tap.downReceived = received;
                            if (tracked && event.getActionMasked() == MotionEvent.ACTION_UP)
                              tap.upReceived = received;
                            try {
                              return method.invoke(originalCallback, arguments);
                            } catch (InvocationTargetException error) {
                              throw error.getCause();
                            } finally {
                              long handled = SystemClock.uptimeMillis();
                              if (tracked) {
                                tap.target = (long) read(activity, "gestureTarget");
                                if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                                  tap.downHandled = handled;
                                if (event.getActionMasked() == MotionEvent.ACTION_UP)
                                  tap.upHandled = handled;
                                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) tap.moves++;
                              }
                            }
                          }));
          return null;
        });
  }

  private void closeActivity() throws Exception {
    PlayerActivity owned = activity;
    try {
      if (owned != null) {
        onMain(
            () -> {
              try {
                bindErrorListener(null);
                if (originalCallback != null) owned.getWindow().setCallback(originalCallback);
              } finally {
                owned.finish();
              }
              return null;
            });
        long until = SystemClock.uptimeMillis() + 3000;
        while (!onMain(owned::isDestroyed) && SystemClock.uptimeMillis() < until)
          SystemClock.sleep(20);
        assertTrue("Owned player did not close", onMain(owned::isDestroyed));
      }
    } finally {
      QueueStore.forget(queueToken);
      queueToken = null;
      originalCallback = null;
      if (owned == null || owned.isDestroyed()) activity = null;
      activeTap = null;
      if (previousPicture != null) {
        previousPicture.recycle();
        previousPicture = null;
      }
    }
  }

  private VideoItem fixture(Uri uri, String slot) throws Exception {
    assertEquals("Only local indexed media is allowed", "content", uri.getScheme());
    assertEquals("Only MediaStore media is allowed", "media", uri.getAuthority());
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {"duration", "_size", "width", "height", "_display_name"},
                null,
                null,
                null)) {
      assertNotNull("Explicit media unavailable", c);
      assertEquals(1, c.getCount());
      assertTrue(c.moveToFirst());
      String name = c.getString(4);
      int dot = name == null ? -1 : name.lastIndexOf('.');
      String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
      if (!extension.matches("\\.(mp4|mkv|avi|mov|webm|m4v|3gp|flv|wmv|mpg|mpeg|ts|m2ts)"))
        extension = ".mp4";
      return new VideoItem(
          "cold-fixture-" + slot,
          "fixture" + extension,
          uri.toString(),
          "",
          c.getLong(1),
          c.getLong(0),
          0,
          0,
          c.getInt(2),
          c.getInt(3));
    }
  }

  private Bitmap capture(View surface) throws Exception {
    int[] dimensions = onMain(() -> new int[] {surface.getWidth(), surface.getHeight()});
    float scale = 96f / Math.max(1, Math.max(dimensions[0], dimensions[1]));
    int width = Math.max(1, Math.round(dimensions[0] * scale)),
        height = Math.max(1, Math.round(dimensions[1] * scale));
    if (surface instanceof TextureView) {
      Bitmap frame = onMain(() -> ((TextureView) surface).getBitmap(width, height));
      assertNotNull("Texture picture unavailable", frame);
      return frame;
    }
    assertTrue("Native surface unavailable", surface instanceof SurfaceView);
    Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    CountDownLatch done = new CountDownLatch(1);
    AtomicInteger status = new AtomicInteger(-1);
    AtomicBoolean abandoned = new AtomicBoolean(), recycled = new AtomicBoolean();
    Runnable recycle =
        () -> {
          if (recycled.compareAndSet(false, true)) frame.recycle();
        };
    main.post(
        () -> {
          try {
            PixelCopy.request(
                (SurfaceView) surface,
                frame,
                code -> {
                  status.set(code);
                  done.countDown();
                  if (abandoned.get()) recycle.run();
                },
                copyHandler);
          } catch (RuntimeException unavailable) {
            status.set(-2);
            done.countDown();
            if (abandoned.get()) recycle.run();
          }
        });
    if (!done.await(700, TimeUnit.MILLISECONDS)) {
      abandoned.set(true);
      if (done.getCount() == 0) recycle.run();
      throw new java.util.concurrent.TimeoutException("PixelCopy timeout");
    }
    if (status.get() != PixelCopy.SUCCESS) {
      recycle.run();
      throw new AssertionError("PixelCopy unavailable");
    }
    return frame;
  }

  private <T> T onMain(Callable<T> operation) throws Exception {
    FutureTask<T> task = new FutureTask<>(operation);
    main.post(task);
    try {
      return task.get(1200, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      task.cancel(false);
      throw error;
    } catch (java.util.concurrent.ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    }
  }

  private static Object read(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static int integer(Bundle args, String key, int fallback) {
    return Integer.parseInt(args.getString(key, Integer.toString(fallback)));
  }

  private static Object delta(long value, long origin) {
    return value > 0 && origin > 0 ? value - origin : JSONObject.NULL;
  }

  private static void waitUntil(long time) {
    long delay = time - SystemClock.uptimeMillis();
    if (delay > 0) SystemClock.sleep(delay);
  }

  private long elapsed() {
    return SystemClock.uptimeMillis() - started;
  }

  private void requireTime() {
    assertTrue("Overall observation deadline exceeded", SystemClock.uptimeMillis() < deadline);
  }

  private static String category(Throwable error) {
    return error instanceof AssertionError
        ? "assertion"
        : error instanceof java.util.concurrent.TimeoutException
            ? "timeout"
            : error instanceof SecurityException ? "permission" : "execution";
  }

  private void writeResult(String run, JSONObject result) throws Exception {
    result.put(
        "engineCodes",
        "0:none,1:Media3-hardware-configured,2:Media3-software-configured,3:Media3-default,4:LibVLC-software-requested,5:LibVLC-hardware-preferred");
    File external = context.getExternalFilesDir(null);
    assertNotNull(external);
    File directory = new File(external, "test-only/cold-random/" + run);
    assertTrue(directory.isDirectory() || directory.mkdirs());
    try (FileOutputStream stream = new FileOutputStream(new File(directory, "results.json"))) {
      stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    Bundle status = new Bundle();
    status.putBoolean("coldRandomPassed", result.optBoolean("passed"));
    status.putInt("coldRandomPhase", phase);
    instrumentation.sendStatus(2, status);
  }

  /** Durable original values must exist before any preference override, even if JNI aborts. */
  private void writePreferenceRecovery(String run, int[] saved, boolean[] present)
      throws Exception {
    JSONObject recovery = new JSONObject().put("protocol", "cold-random-preferences-v1");
    for (int i = 0; i < PREFS.length; i++)
      recovery.put(PREFS[i], new JSONObject().put("present", present[i]).put("value", saved[i]));
    File external = context.getExternalFilesDir(null);
    assertNotNull("App external files unavailable", external);
    File directory = new File(external, "test-only/cold-random/" + run);
    assertTrue("Recovery directory unavailable", directory.isDirectory() || directory.mkdirs());
    File target = new File(directory, "preferences-before.json");
    assertFalse(
        "Use a new run label to preserve the original preference snapshot", target.exists());
    try (FileOutputStream stream = new FileOutputStream(target)) {
      stream.write(recovery.toString(2).getBytes(StandardCharsets.UTF_8));
      stream.getFD().sync();
    }
    Bundle status = new Bundle();
    status.putString("coldRandomPreferencesBefore", recovery.toString());
    instrumentation.sendStatus(2, status);
  }

  private static final class Snapshot {
    final View surface;
    final long epoch;
    final JSONObject state;

    Snapshot(View surface, long epoch, JSONObject state) {
      this.surface = surface;
      this.epoch = epoch;
      this.state = state;
    }
  }

  private static final class Tap {
    final int x, y, startX;
    final boolean drag;
    volatile int moves;
    int[] geometry;
    volatile int previewCount;
    volatile boolean pageScrubbing, activityScrubbing;
    volatile long serialBefore, serialAfter, consumedAt;
    volatile long eventDown, eventUp, downReceived, upReceived, downHandled, upHandled, target = -1;

    Tap(int x, int y, int startX, boolean drag) {
      this.x = x;
      this.y = y;
      this.startX = startX;
      this.drag = drag;
    }
  }
}
