package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.SeekBar;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.interfaces.IMedia;

/**
 * Sustained actual-input stress for explicitly supplied media. Pixels are sampled from the video
 * surface, never persisted. Stored hashes/distances cannot reconstruct a frame. Clock/state
 * evidence is deliberately separate from visible-picture evidence; neither establishes a decoded
 * PTS.
 */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class SustainedSeekStressTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean stop = new AtomicBoolean();
  private PlayerActivity activity;
  private VideoPage page;
  private View surface;
  private SeekBar seek;
  private HandlerThread copyThread;
  private Handler copyHandler;
  private final AtomicBoolean copyInFlight = new AtomicBoolean();
  private int frameWidth, frameHeight;
  private int barcodeFps, barcodeFrameCount;
  private long duration;
  private int left, top, trackWidth, padding;
  private Window.Callback originalWindowCallback;
  private volatile long measuredInputStartsAt = Long.MAX_VALUE;
  private final ArrayList<DeliveredTouch> delivered = new ArrayList<>();
  private final ArrayList<JSONObject> surfaceTransitions = new ArrayList<>();
  private volatile long captureBindingEpoch;
  private volatile boolean measuredReferenceInvalidated;
  private volatile boolean measuringBurst;
  private long lastSurfaceCheck;
  private String boundEngine = "unbound";
  private int boundPlayerIdentity, boundPageIdentity, boundSurfaceIdentity;

  @Test
  public void measureSustainedSeekTaps() throws Exception {
    Context context = instrumentation.getTargetContext();
    Bundle args = InstrumentationRegistry.getArguments();
    String run = args.getString("benchRun", "stress-" + SystemClock.uptimeMillis());
    assertTrue(run.matches("[A-Za-z0-9_-]{1,100}"));
    String alias = args.getString("benchAlias", "supplied-media");
    assertTrue(alias.matches("[A-Za-z0-9_-]{1,80}"));
    barcodeFps = Integer.parseInt(args.getString("benchBarcodeFps", "0"));
    barcodeFrameCount = Integer.parseInt(args.getString("benchBarcodeFrameCount", "480"));
    assertTrue(
        "Barcode oracle supports only the explicit synthetic 60 fps / 480-frame protocol",
        barcodeFps == 0 || barcodeFps == 60 && barcodeFrameCount == 480);
    if (barcodeFps != 0)
      assertTrue(
          "Barcode oracle requires an explicit synthetic fixture filename",
          args.getString("benchFile", "").matches("mm_seek_stress_[A-Za-z0-9_-]+\\.mp4"));
    int hz = Integer.parseInt(args.getString("benchHz", "12"));
    int seconds = Integer.parseInt(args.getString("benchSeconds", "12"));
    assertTrue(hz >= 1 && hz <= 20 && seconds >= 2 && seconds <= 30);
    String state = args.getString("benchState", "playing");
    assertTrue(state.equals("playing") || state.equals("paused"));
    boolean playing = state.equals("playing");
    String input = args.getString("benchInput", "physical");
    assertTrue(input.equals("physical") || input.equals("direct"));
    int engine = Integer.parseInt(args.getString("benchEngine", "0"));
    int decoder = Integer.parseInt(args.getString("benchNativeDecoder", "0"));
    assertTrue(engine >= 0 && engine <= 2 && decoder >= 0 && decoder <= 2);
    long recoveryLimit = Long.parseLong(args.getString("benchRecoveryLimitMs", "1500"));
    assertTrue(recoveryLimit >= 250 && recoveryLimit <= 3000);
    long referenceTimeout = Long.parseLong(args.getString("benchReferenceTimeoutMs", "5000"));
    assertTrue(referenceTimeout >= 5000 && referenceTimeout <= 15000);
    int steadySeconds = Integer.parseInt(args.getString("benchSteadySeconds", "0"));
    assertTrue(steadySeconds >= 0 && steadySeconds <= 60);
    assertTrue("Steady playback tail requires playing state", steadySeconds == 0 || playing);
    File output = new File(context.getExternalFilesDir(null), "test-only/seek-stress/" + run);
    assertTrue(output.isDirectory() || output.mkdirs());
    JSONArray samples = new JSONArray(), taps = new JSONArray(), baseline = new JSONArray();
    JSONObject result =
        new JSONObject()
            .put("protocol", "sustained-surface-v4")
            .put("run", run)
            .put("mediaAlias", alias)
            .put("state", state)
            .put("input", input)
            .put("scheduledHz", hz)
            .put("scheduledSeconds", seconds)
            .put("scheduledPressMs", 20)
            .put("engineChoice", engine)
            .put("nativeDecoderChoice", decoder)
            .put("recoveryLimitMs", recoveryLimit)
            .put("steadySeconds", steadySeconds)
            .put("barcodeFps", barcodeFps)
            .put("barcodeFrameCount", barcodeFps == 0 ? JSONObject.NULL : barcodeFrameCount)
            .put("barcodeToleranceMs", barcodeFps == 0 ? JSONObject.NULL : 150)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("timeline", samples)
            .put("taps", taps)
            .put("baseline", baseline)
            .put(
                "pictureEvidence",
                "PixelCopy/TextureView of the actual video surface. Only hashes and distances are"
                    + " persisted.")
            .put(
                "targetEvidence",
                "Final requested position is compared with an isolated single-tap reference from"
                    + " the same surface. This verifies repeatable image content, not independently"
                    + " decoded PTS. Playing references contain a 1.8-second observed trajectory.")
            .put(
                "clockEvidence",
                "Native time and counters are separate diagnostics, never treated as proof of"
                    + " rendered pixels.");
    SharedPreferences preferences = context.getSharedPreferences("player", 0);
    String[] keys = {"engine_choice", "native_decoder_mode", "playback_mode"};
    boolean[] present = new boolean[keys.length];
    int[] saved = new int[keys.length];
    for (int i = 0; i < keys.length; i++) {
      present[i] = preferences.contains(keys[i]);
      saved[i] = preferences.getInt(keys[i], 0);
    }
    Thread sender = null;
    AtomicReference<Throwable> injectionFailure = new AtomicReference<>();
    ArrayList<Signature> reference = new ArrayList<>();
    ArrayList<Tap> attemptedTaps = new ArrayList<>();
    long origin = 0;
    ScopedPlaybackHistory history = null;
    try {
      history = onMain(() -> ScopedPlaybackHistory.open(context), 2000);
      result.put("engineHistoryIsolated", true);
      automation.grantRuntimePermission(
          context.getPackageName(),
          Build.VERSION.SDK_INT >= 33
              ? Manifest.permission.READ_MEDIA_VIDEO
              : Manifest.permission.READ_EXTERNAL_STORAGE);
      Fixture fixture = fixture(context, args);
      result.put("indexedDurationMs", fixture.duration).put("indexedSize", fixture.size);
      assertTrue(
          preferences
              .edit()
              .putInt(keys[0], engine)
              .putInt(keys[1], decoder)
              .putInt(keys[2], PlaybackOrder.SINGLE)
              .commit());
      Intent open =
          new Intent(Intent.ACTION_VIEW)
              .setClass(context, PlayerActivity.class)
              .setDataAndType(fixture.uri, "video/mp4")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      context.startActivity(open);
      waitReady(fixture.uri);
      result.put("durationMs", duration).put("initialState", snapshot(2000));
      copyThread = new HandlerThread("stress-pixel-copy");
      copyThread.start();
      copyHandler = new Handler(copyThread.getLooper());
      refreshSurfaceBinding();
      // Keep reference setup outside the measured interval. The exact same physical coordinate is
      // used for the isolated final-target reference and the last tap of the stress stream.
      setPlaying(false);
      showControls();
      Tap referenceOriginTap = tapAt(.12);
      injectOne(referenceOriginTap, input);
      Signature referenceOrigin = stablePicture(referenceOriginTap.target, referenceTimeout);
      Tap finalTap = tapAt(.65);
      injectOne(finalTap, input);
      Signature targetReference = stablePicture(finalTap.target, referenceTimeout);
      if (barcodeFps != 0) {
        assertTrue(
            "Synthetic isolated target did not produce a guarded barcode",
            targetReference.barcodeFrame >= 0);
        assertTrue(
            "Synthetic isolated target is outside the independent 150 ms PTS bound",
            Math.abs(targetReference.barcodeFrameMs - finalTap.target) <= 150);
      }
      assertTrue(
          "Isolated target never changed the actual picture",
          distance(referenceOrigin, targetReference) > 3.5);
      result.put("isolatedTargetState", snapshot(1500)).put("isolatedRequestedMs", finalTap.target);
      reference.add(targetReference);
      baseline.put(new JSONObject().put("offsetMs", 0).put("hash", targetReference.hash));
      if (playing && barcodeFps == 0) {
        long referenceStart = setPlaying(true, 0);
        while (SystemClock.uptimeMillis() - referenceStart < 1800) {
          Signature frame = capture();
          frame.offset = SystemClock.uptimeMillis() - referenceStart;
          reference.add(frame);
          baseline.put(new JSONObject().put("offsetMs", frame.offset).put("hash", frame.hash));
          SystemClock.sleep(25);
        }
      }
      setPlaying(false);
      showControls();
      Tap reset = tapAt(.12);
      injectOne(reset, input);
      Signature before = stablePicture(reset.target, referenceTimeout);
      double distinct = distance(before, targetReference);
      result
          .put("referenceDistanceFromOrigin", distinct)
          .put("referenceDistinguishable", distinct > 3.5);
      assertTrue(
          "Origin and final target are visually indistinguishable; no recovery claim accepted",
          distinct > 3.5);
      setPlaying(playing);
      showControls();
      result.put("measuredInitialState", snapshot(1500));
      final int count = hz * seconds;
      Tap[] requests = new Tap[count];
      Random random = new Random(0x5ee123L);
      for (int i = 0; i < count - 1; i++) {
        // Alternate halves to guarantee large reversals while varying positions within each half.
        requests[i] =
            tapAt(i % 2 == 0 ? .57 + random.nextDouble() * .3 : .08 + random.nextDouble() * .3);
      }
      requests[count - 2] = tapAt(.12);
      requests[count - 1] = tapAt(.65);
      BarcodeOracle barcode =
          new BarcodeOracle(playing, requests[count - 1].target, barcodeFps, barcodeFrameCount);
      java.util.Collections.addAll(attemptedTaps, requests);
      if (input.equals("physical")) installDeliveryProbe();
      origin = SystemClock.uptimeMillis() + 120;
      measuredInputStartsAt = origin;
      measuringBurst = true;
      final long started = origin;
      sender =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < requests.length && !stop.get(); i++) {
                    waitUntil(started + Math.round(i * 1000d / hz));
                    injectOne(requests[i], input);
                  }
                } catch (Throwable error) {
                  injectionFailure.set(error);
                }
              },
              "sustained-seek-input");
      sender.start();
      Signature previous = before;
      long lastChange = origin, longestFreeze = 0, longestBurstFreeze = 0;
      long firstRecovery = -1, lastSnapshot = 0;
      long trajectoryFirstOffset = -1, trajectoryLastOffset = -1, pausedMatchSince = -1;
      long lastPictureAt = -1;
      int changes = 0, postChanges = 0, matchCount = 0, failedCaptures = 0;
      int consecutiveMatches = 0, mainTimeouts = 0, trajectoryMatches = 0;
      long end = origin + seconds * 1000L + 6000;
      while (SystemClock.uptimeMillis() < end) {
        if (injectionFailure.get() != null)
          throw new AssertionError("Input sender failed", injectionFailure.get());
        long sampleStart = SystemClock.uptimeMillis();
        JSONObject entry = new JSONObject().put("startMs", sampleStart - origin);
        try {
          Signature frame = capture();
          long now = SystemClock.uptimeMillis();
          long previousValidPictureAt = lastPictureAt;
          lastPictureAt = now;
          double change = distance(previous, frame);
          boolean discontinuity = previous.bindingEpoch != frame.bindingEpoch;
          if (discontinuity) {
            // Close only the actually observed old-surface interval. The replacement gap has no
            // valid picture evidence and must neither erase the old freeze nor inflate it.
            long oldLowerBound = Math.max(0, previousValidPictureAt - lastChange);
            longestFreeze = Math.max(longestFreeze, oldLowerBound);
            Tap finalRequest = requests[count - 1];
            long oldBurstEnd =
                finalRequest.up > 0
                    ? Math.min(previousValidPictureAt, finalRequest.up)
                    : previousValidPictureAt;
            longestBurstFreeze =
                Math.max(longestBurstFreeze, Math.max(0, oldBurstEnd - lastChange));
            entry
                .put("oldSurfaceUnchangedLowerBoundMs", oldLowerBound)
                .put(
                    "surfaceUnknownGapMs",
                    previousValidPictureAt < 0 ? 0 : now - previousValidPictureAt);
            change = 0;
            lastChange = now;
          }
          double nearest = Double.MAX_VALUE;
          long nearestOffset = -1;
          double earlyDistance = Double.MAX_VALUE;
          for (Signature candidate : reference) {
            double score = distance(candidate, frame);
            if (candidate.offset <= 250) earlyDistance = Math.min(earlyDistance, score);
            if (score < nearest) {
              nearest = score;
              nearestOffset = candidate.offset;
            }
          }
          Tap last = requests[count - 1];
          boolean afterLast = last.up > 0 && sampleStart >= last.up;
          if (barcodeFps != 0 && afterLast) barcode.accept(frame, now, last.up);
          long measuredBurstEnd = last.up > 0 ? Math.min(now, last.up) : now;
          longestBurstFreeze =
              Math.max(longestBurstFreeze, Math.max(0, measuredBurstEnd - lastChange));
          if (change > .8) {
            longestFreeze = Math.max(longestFreeze, now - lastChange);
            lastChange = now;
            changes++;
            if (afterLast) postChanges++;
          }
          if (afterLast && nearest <= 3.5) {
            matchCount++;
            consecutiveMatches++;
            if (pausedMatchSince < 0) pausedMatchSince = now;
            if (firstRecovery < 0 && earlyDistance <= 3.5) firstRecovery = now - last.up;
            if (firstRecovery >= 0
                && nearestOffset >= trajectoryLastOffset - 100
                && nearestOffset <= now - last.up - firstRecovery + 300) {
              if (trajectoryFirstOffset < 0) trajectoryFirstOffset = nearestOffset;
              trajectoryLastOffset = Math.max(trajectoryLastOffset, nearestOffset);
              trajectoryMatches++;
            }
          } else if (afterLast) {
            consecutiveMatches = 0;
            pausedMatchSince = -1;
          }
          entry
              .put("endMs", now - origin)
              .put("hash", frame.hash)
              .put("changeRgb", change)
              .put("surfaceDiscontinuity", discontinuity)
              .put("captureBindingEpoch", frame.bindingEpoch)
              .put(
                  "decodedFrameIndex",
                  frame.barcodeFrame < 0 ? JSONObject.NULL : frame.barcodeFrame)
              .put(
                  "decodedFrameMs", frame.barcodeFrame < 0 ? JSONObject.NULL : frame.barcodeFrameMs)
              .put("nearestReferenceRgb", nearest)
              .put("nearestReferenceOffsetMs", nearestOffset)
              .put("earlyReferenceRgb", earlyDistance)
              .put("afterLastUp", afterLast);
          previous = frame;
          if (now - lastSnapshot >= 200) {
            try {
              entry.put("state", snapshot(300));
            } catch (Exception stateFailure) {
              mainTimeouts++;
              entry.put("stateError", stateFailure.getClass().getSimpleName());
            }
            lastSnapshot = now;
          }
          samples.put(entry);
          if (afterLast
              && now - last.up >= 2000
              && (barcodeFps != 0
                  ? barcode.ready()
                  : firstRecovery >= 0
                      && (playing
                          ? postChanges >= 4
                              && trajectoryMatches >= 4
                              && trajectoryLastOffset - trajectoryFirstOffset >= 300
                          : consecutiveMatches >= 3 && now - pausedMatchSince >= 300))) break;
        } catch (Exception error) {
          failedCaptures++;
          samples.put(
              entry
                  .put("endMs", SystemClock.uptimeMillis() - origin)
                  .put("captureError", error.getClass().getSimpleName()));
        }
        SystemClock.sleep(20);
      }
      sender.join(2000);
      measuringBurst = false;
      assertFalse("Input sender did not stop", sender.isAlive());
      if (injectionFailure.get() != null)
        throw new AssertionError("Input sender failed", injectionFailure.get());
      Tap last = requests[count - 1];
      for (Tap tap : requests) taps.put(tap.json(origin));
      longestFreeze = Math.max(longestFreeze, Math.max(0, lastPictureAt - lastChange));
      result
          .put("tapCount", count)
          .put("finalRequestMs", last.target)
          .put("finalTapUpMs", last.up - origin)
          .put("observedPictureChanges", changes)
          .put("postFinalPictureChanges", postChanges)
          .put("referenceMatchCount", matchCount)
          .put("consecutiveReferenceMatches", consecutiveMatches)
          .put("pausedStableTailMs", pausedMatchSince < 0 ? 0 : lastPictureAt - pausedMatchSince)
          .put("trajectoryMatches", trajectoryMatches)
          .put("trajectoryProgressMs", trajectoryLastOffset - trajectoryFirstOffset)
          .put("mainSnapshotTimeouts", mainTimeouts)
          .put("firstReferenceMatchAfterFinalUpMs", firstRecovery)
          .put("barcodeOracle", barcodeFps == 0 ? JSONObject.NULL : barcode.json())
          .put("finalTargetRecoveryMs", barcodeFps == 0 ? firstRecovery : barcode.firstNearAfterUp)
          .put("longestObservedUnchangedMs", longestFreeze)
          .put("longestBurstUnchangedMs", longestBurstFreeze)
          .put("failedCaptures", failedCaptures)
          .put("referenceInvalidatedByEngineReplacement", measuredReferenceInvalidated)
          .put("finalState", snapshot(1500));
      JSONObject cadence = cadence(attemptedTaps, hz, origin, input.equals("physical"));
      result.put("cadence", cadence);
      assertTrue("Last tap never dispatched", last.up > 0);
      assertFalse(
          "Player/surface replacement invalidated the isolated reference; diagnostic run only",
          measuredReferenceInvalidated);
      assertTrue("Requested touch cadence was not delivered", cadence.getBoolean("valid"));
      assertEquals("Main thread stopped responding during the burst", 0, mainTimeouts);
      assertFalse("Current page failed", result.getJSONObject("finalState").getBoolean("failed"));
      assertTrue(
          "Rendered picture froze for multiple seconds during changing seek requests: "
              + longestBurstFreeze,
          longestBurstFreeze < 2000);
      long targetRecovery = barcodeFps == 0 ? firstRecovery : barcode.firstNearAfterUp;
      assertTrue("No rendered-picture recovery near the final target", targetRecovery >= 0);
      assertTrue(
          "Rendered-picture recovery exceeded the stated bound: " + targetRecovery,
          targetRecovery <= recoveryLimit);
      assertTrue("Playing picture stayed frozen after the burst", !playing || postChanges >= 4);
      if (barcodeFps != 0)
        assertTrue(
            "Final barcode PTS did not form a stable/forward target trajectory", barcode.ready());
      else {
        assertTrue(
            "No ordered rendered trajectory after the final tap",
            !playing
                || trajectoryMatches >= 4 && trajectoryLastOffset - trajectoryFirstOffset >= 300);
        assertTrue(
            "Paused final picture did not settle near the reference",
            playing
                || consecutiveMatches >= 3
                    && pausedMatchSince >= 0
                    && lastPictureAt - pausedMatchSince >= 300);
      }
      if (steadySeconds > 0) {
        JSONObject steady = new JSONObject();
        result.put("steady", steady);
        sampleSteady(steadySeconds, steady);
        assertEquals(
            "Steady playback main thread became unresponsive", 0, steady.getInt("mainErrors"));
        assertTrue(
            "Steady playback picture stopped moving", steady.getLong("longestUnchangedMs") < 2000);
        assertFalse(
            "Steady playback failed", steady.getJSONObject("finalState").getBoolean("failed"));
        if (Boolean.parseBoolean(args.getString("benchRequireSameDecoder", "false"))) {
          assertFalse(
              "Steady playback replaced its configured decoder",
              steady.getBoolean("decoderChanged"));
        }
      }
      result.put("completed", true);
    } catch (Throwable error) {
      result
          .put("completed", false)
          .put("errorType", error.getClass().getSimpleName())
          .put(
              "error",
              error instanceof AssertionError
                  ? String.valueOf(error.getMessage())
                  : "See errorType; framework message omitted for media privacy");
      // The supplied filename/URI is intentionally absent from structured artifacts.
      if (error instanceof Exception) throw (Exception) error;
      if (error instanceof Error) throw (Error) error;
      throw new RuntimeException(error);
    } finally {
      try {
        measuringBurst = false;
        stop.set(true);
        if (sender != null) sender.join(2000);
        if (taps.length() == 0) {
          for (Tap tap : attemptedTaps) taps.put(tap.json(origin));
        }
        if (!attemptedTaps.isEmpty())
          result.put("cadence", cadence(attemptedTaps, hz, origin, input.equals("physical")));
        synchronized (surfaceTransitions) {
          JSONArray transitions = new JSONArray();
          for (JSONObject transition : surfaceTransitions) transitions.put(transition);
          result.put("surfaceTransitions", transitions);
        }
        result.put("referenceInvalidatedByEngineReplacement", measuredReferenceInvalidated);
        try {
          result.put("lastDiagnosticState", snapshot(500));
        } catch (Exception error) {
          result.put("lastDiagnosticStateError", error.getClass().getSimpleName());
        }
      } finally {
        try {
          try {
            SharedPreferences.Editor restore = preferences.edit();
            for (int i = 0; i < keys.length; i++) {
              if (present[i]) restore.putInt(keys[i], saved[i]);
              else restore.remove(keys[i]);
            }
            result.put("preferencesRestored", restore.commit());
          } finally {
            if (activity != null)
              onMain(
                  () -> {
                    try {
                      if (originalWindowCallback != null)
                        activity.getWindow().setCallback(originalWindowCallback);
                    } finally {
                      activity.finish();
                    }
                    return null;
                  },
                  2000);
          }
          // Observe release after teardown without waiting indefinitely for a wedged native input.
          // A nonzero bounded count at this deadline remains diagnostic, not proof of a leak.
          JSONObject retirement = new JSONObject();
          JSONArray retirementTimeline = new JSONArray();
          retirement.put("samples", retirementTimeline).put("observationLimitMs", 2000);
          long retirementStarted = SystemClock.uptimeMillis();
          while (SystemClock.uptimeMillis() - retirementStarted < 2000) {
            JSONObject sample;
            try {
              sample = retirementDiagnostics();
            } catch (Exception unavailable) {
              sample =
                  new JSONObject()
                      .put("available", false)
                      .put("errorType", unavailable.getClass().getSimpleName());
            }
            sample.put("afterFinishRequestMs", SystemClock.uptimeMillis() - retirementStarted);
            boolean destroyed = false;
            try {
              destroyed = activity == null || onMain(activity::isDestroyed, 200);
              sample.put("activityDestroyed", destroyed);
            } catch (Exception stateUnavailable) {
              sample.put("activityStateError", stateUnavailable.getClass().getSimpleName());
            }
            retirementTimeline.put(sample);
            if (!sample.optBoolean("available", false)
                || destroyed && sample.optInt("ownedCount", -1) == 0) break;
            SystemClock.sleep(100);
          }
          result.put("retirementAfterTeardown", retirement);
        } finally {
          try {
            if (history != null) {
              history.closeAfterActivityDestroyed(activity);
              result.put("engineHistoryRestored", true);
            }
          } catch (Exception cleanupFailure) {
            result
                .put("completed", false)
                .put("historyCleanupErrorType", cleanupFailure.getClass().getSimpleName());
            throw cleanupFailure;
          } finally {
            if (copyThread != null) copyThread.quitSafely();
            try (OutputStreamWriter writer =
                new OutputStreamWriter(
                    new FileOutputStream(new File(output, "results.json")),
                    StandardCharsets.UTF_8)) {
              writer.write(result.toString(2));
            }
          }
        }
      }
    }
  }

  private void waitReady(Uri uri) throws Exception {
    long until = SystemClock.uptimeMillis() + 20000;
    while (SystemClock.uptimeMillis() < until) {
      boolean ready =
          onMain(
              () -> {
                for (android.app.Activity candidate :
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                  if (candidate instanceof PlayerActivity) activity = (PlayerActivity) candidate;
                }
                if (activity == null) return false;
                page = (VideoPage) read(activity, "currentPage");
                if (page == null
                    || page.item == null
                    || !Uri.parse(page.item.uri).equals(uri)
                    || !page.firstFrameReady
                    || page.buffering) return false;
                duration = page.duration();
                seek = (SeekBar) read(activity, "seek");
                surface =
                    page.exo != null && page.exoView != null
                        ? page.exoView.getVideoSurfaceView()
                        : NativeFrameProbe.findVideoSurface(page.vlcView);
                if (surface == null || duration < 1000) return false;
                configureCaptureDimensions();
                return true;
              },
              2000);
      if (ready) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError("Requested media did not produce a ready video surface");
  }

  private void sampleSteady(int seconds, JSONObject result) throws Exception {
    JSONArray frames = new JSONArray(), windows = new JSONArray();
    result
        .put("frames", frames)
        .put("windows", windows)
        .put(
            "note",
            "Two-second native counter windows are diagnostic. Counter reset across a loop/player"
                + " replacement is flagged, not reported as a negative rate.");
    JSONObject first = snapshot(1000), previousState = first;
    result.put("initialState", first);
    long start = SystemClock.uptimeMillis(), lastStateAt = start, lastChange = start, longest = 0;
    Signature previousPicture = capture();
    boolean changedDecoder = false;
    int mainErrors = 0, captureErrors = 0, changes = 0;
    while (SystemClock.uptimeMillis() - start < seconds * 1000L) {
      long now = SystemClock.uptimeMillis();
      try {
        Signature frame = capture();
        double change = distance(previousPicture, frame);
        if (change > .8) {
          longest = Math.max(longest, now - lastChange);
          lastChange = now;
          changes++;
        }
        frames.put(
            new JSONObject()
                .put("atMs", now - start)
                .put("hash", frame.hash)
                .put("changeRgb", change));
        previousPicture = frame;
      } catch (Exception error) {
        captureErrors++;
        frames.put(
            new JSONObject()
                .put("atMs", now - start)
                .put("captureError", error.getClass().getSimpleName()));
      }
      if (now - lastStateAt >= 2000) {
        try {
          JSONObject state = snapshot(500);
          boolean replaced = !sameDecoder(first, state);
          changedDecoder |= replaced;
          JSONObject window =
              new JSONObject()
                  .put("atMs", now - start)
                  .put("state", state)
                  .put("decoderChanged", replaced)
                  .put(
                      "clockWrapped",
                      state.optLong("nativeTimeMs", -1)
                          < previousState.optLong("nativeTimeMs", -1));
          long elapsed = state.getLong("atUptime") - previousState.getLong("atUptime");
          for (String key : new String[] {"decoded", "displayed", "lost"}) {
            if (state.has(key) && previousState.has(key)) {
              long delta = state.getLong(key) - previousState.getLong(key);
              window.put(key + "CounterReset", delta < 0 || !sameDecoder(previousState, state));
              window.put(
                  key + "PerSecond",
                  delta < 0 || elapsed <= 0 || !sameDecoder(previousState, state)
                      ? JSONObject.NULL
                      : delta * 1000d / elapsed);
            }
          }
          windows.put(window);
          previousState = state;
        } catch (Exception error) {
          mainErrors++;
          windows.put(
              new JSONObject()
                  .put("atMs", now - start)
                  .put("stateError", error.getClass().getSimpleName()));
        }
        lastStateAt = now;
      }
      SystemClock.sleep(80);
    }
    longest = Math.max(longest, SystemClock.uptimeMillis() - lastChange);
    JSONObject finalState = snapshot(1000);
    result
        .put("finalState", finalState)
        .put("decoderChanged", changedDecoder || !sameDecoder(first, finalState))
        .put("mainErrors", mainErrors)
        .put("captureErrors", captureErrors)
        .put("pictureChanges", changes)
        .put("longestUnchangedMs", longest);
  }

  private static boolean sameDecoder(JSONObject a, JSONObject b) {
    return a.optString("engine", "unknown").equals(b.optString("engine", "unknown"))
        && a.optInt("media3PlayerIdentity", -1) == b.optInt("media3PlayerIdentity", -1)
        && a.optInt("nativePlayerIdentity", -1) == b.optInt("nativePlayerIdentity", -1)
        && a.optBoolean("nativeSoftware", false) == b.optBoolean("nativeSoftware", false)
        && a.optInt("pageIdentity", -1) == b.optInt("pageIdentity", -1);
  }

  private void showControls() throws Exception {
    onMain(
        () -> {
          Method method = activity.getClass().getDeclaredMethod("showChrome");
          method.setAccessible(true);
          method.invoke(activity);
          return null;
        },
        1500);
    SystemClock.sleep(300);
    onMain(
        () -> {
          assertTrue("SeekBar is not visible", seek.isShown());
          int[] xy = new int[2];
          seek.getLocationOnScreen(xy);
          left = xy[0];
          top = xy[1] + seek.getHeight() / 2;
          padding = seek.getPaddingLeft();
          trackWidth = seek.getWidth() - padding - seek.getPaddingRight();
          return null;
        },
        1500);
  }

  private long setPlaying(boolean play) throws Exception {
    return setPlaying(play, 250);
  }

  private long setPlaying(boolean play, long settleMs) throws Exception {
    long began =
        onMain(
            () -> {
              Field shouldPlay = activity.getClass().getDeclaredField("shouldPlay");
              shouldPlay.setAccessible(true);
              shouldPlay.setBoolean(activity, play);
              page.setPlayback(true, play, true);
              return SystemClock.uptimeMillis();
            },
            2000);
    if (settleMs > 0) SystemClock.sleep(settleMs);
    return began;
  }

  private void installDeliveryProbe() throws Exception {
    onMain(
        () -> {
          originalWindowCallback = activity.getWindow().getCallback();
          Window.Callback delegate = originalWindowCallback;
          Window.Callback observer =
              (Window.Callback)
                  Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {Window.Callback.class},
                      (proxy, method, arguments) -> {
                        DeliveredTouch record = null;
                        if (method.getName().equals("dispatchTouchEvent")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof MotionEvent) {
                          MotionEvent event = (MotionEvent) arguments[0];
                          int action = event.getActionMasked();
                          if (event.getEventTime() >= measuredInputStartsAt
                              && (action == MotionEvent.ACTION_DOWN
                                  || action == MotionEvent.ACTION_UP)
                              && Math.abs(event.getRawY() - top) <= 4
                              && event.getRawX() >= left
                              && event.getRawX() <= left + padding * 2 + trackWidth) {
                            record =
                                new DeliveredTouch(
                                    action, event.getEventTime(), SystemClock.uptimeMillis());
                          }
                        }
                        try {
                          return method.invoke(delegate, arguments);
                        } catch (java.lang.reflect.InvocationTargetException error) {
                          throw error.getCause();
                        } finally {
                          if (record != null) {
                            record.completedAt = SystemClock.uptimeMillis();
                            synchronized (delivered) {
                              if (delivered.size() < 1300) delivered.add(record);
                            }
                          }
                        }
                      });
          activity.getWindow().setCallback(observer);
          return null;
        },
        1500);
  }

  private JSONObject cadence(ArrayList<Tap> taps, int hz, long origin, boolean physical)
      throws Exception {
    ArrayList<Long> injectedIntervals = new ArrayList<>(), deliveredIntervals = new ArrayList<>();
    int sentDowns = 0, sentUps = 0, receivedDowns = 0, receivedUps = 0;
    long firstSent = 0,
        firstReceived = 0,
        lastSent = 0,
        lastReceived = 0,
        maxLag = 0,
        maxDispatch = 0;
    for (Tap tap : taps) {
      if (tap.down > 0) {
        sentDowns++;
        if (firstSent == 0) firstSent = tap.down;
        if (lastSent > 0) injectedIntervals.add(tap.down - lastSent);
        lastSent = tap.down;
      }
      if (tap.up > 0 && tap.sent > 0) sentUps++;
    }
    JSONArray events = new JSONArray();
    synchronized (delivered) {
      for (DeliveredTouch event : delivered) {
        if (event.action == MotionEvent.ACTION_DOWN) {
          receivedDowns++;
          if (firstReceived == 0) firstReceived = event.startedAt;
          if (lastReceived > 0) deliveredIntervals.add(event.startedAt - lastReceived);
          lastReceived = event.startedAt;
        } else receivedUps++;
        maxLag = Math.max(maxLag, event.startedAt - event.eventTime);
        maxDispatch = Math.max(maxDispatch, event.completedAt - event.startedAt);
        events.put(
            new JSONObject()
                .put("action", event.action)
                .put("eventMs", event.eventTime - origin)
                .put("receivedMs", event.startedAt - origin)
                .put("handledMs", event.completedAt - origin));
      }
    }
    double interval = 1000d / hz;
    long sentP95 = percentile95(injectedIntervals), receivedP95 = percentile95(deliveredIntervals);
    long sentMax = max(injectedIntervals), receivedMax = max(deliveredIntervals);
    double sentHz = lastSent > firstSent ? (sentDowns - 1) * 1000d / (lastSent - firstSent) : 0;
    double receivedHz =
        lastReceived > firstReceived
            ? (receivedDowns - 1) * 1000d / (lastReceived - firstReceived)
            : 0;
    boolean valid =
        sentDowns == taps.size()
            && sentUps == taps.size()
            && sentHz >= hz * .95
            && sentP95 <= interval + 20
            && sentMax <= interval * 2 + 20;
    if (physical)
      valid &=
          receivedDowns == taps.size()
              && receivedUps == taps.size()
              && receivedHz >= hz * .95
              && maxLag <= 200
              && receivedP95 <= interval + 20
              && receivedMax <= interval * 2 + 20;
    return new JSONObject()
        .put("sentDowns", sentDowns)
        .put("sentUps", sentUps)
        .put("receivedDowns", receivedDowns)
        .put("receivedUps", receivedUps)
        .put("observedInjectedHz", sentHz)
        .put("observedDeliveredHz", receivedHz)
        .put("injectedIntervalP95Ms", sentP95)
        .put("receivedIntervalP95Ms", receivedP95)
        .put("maxInjectedIntervalMs", sentMax)
        .put("maxReceivedIntervalMs", receivedMax)
        .put("maxDeliveryLagMs", maxLag)
        .put("maxUiDispatchMs", maxDispatch)
        .put("actualWindowTouchDispatch", events)
        .put("valid", valid)
        .put(
            "note",
            "Physical delivery is observed by a pass-through Window.Callback; input acceptance"
                + " alone is not treated as app delivery.");
  }

  private static long percentile95(ArrayList<Long> values) {
    if (values.isEmpty()) return -1;
    ArrayList<Long> sorted = new ArrayList<>(values);
    java.util.Collections.sort(sorted);
    return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * .95) - 1));
  }

  private static long max(ArrayList<Long> values) {
    long maximum = -1;
    for (long value : values) maximum = Math.max(maximum, value);
    return maximum;
  }

  private Tap tapAt(double fraction) {
    int local = (int) Math.round(trackWidth * fraction);
    int progress = Math.round(local / (float) trackWidth * 10000);
    return new Tap(left + padding + local, top, duration * progress / 10000);
  }

  private void injectOne(Tap tap, String input) throws Exception {
    tap.down = SystemClock.uptimeMillis();
    if (input.equals("direct")) {
      onMain(
          () -> {
            page.beginScrub();
            page.scrubTo(tap.target);
            return null;
          },
          500);
      waitUntil(tap.down + 20);
      tap.up = SystemClock.uptimeMillis();
      onMain(
          () -> {
            page.endScrub(tap.target, true);
            return null;
          },
          500);
    } else {
      inject(MotionEvent.ACTION_DOWN, tap, tap.down);
      try {
        waitUntil(tap.down + 20);
        tap.up = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_UP, tap, tap.up);
      } catch (Throwable failure) {
        inject(MotionEvent.ACTION_CANCEL, tap, SystemClock.uptimeMillis());
        throw failure;
      }
    }
    tap.sent = SystemClock.uptimeMillis();
  }

  private void inject(int action, Tap tap, long when) {
    MotionEvent event = MotionEvent.obtain(tap.down, when, action, tap.x, tap.y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("Input injection failed", automation.injectInputEvent(event, false));
    } finally {
      event.recycle();
    }
  }

  private Signature stablePicture(long targetMs, long timeout) throws Exception {
    long until = SystemClock.uptimeMillis() + timeout;
    Signature previous = null;
    int stable = 0;
    while (SystemClock.uptimeMillis() < until) {
      // An unchanged old picture during buffering is not a completed isolated seek. Start the
      // image-stability window only after this exact request has reached the paused renderer.
      if (!referenceSettled(targetMs)) {
        previous = null;
        stable = 0;
        SystemClock.sleep(50);
        continue;
      }
      Signature current = capture();
      if (!referenceSettled(targetMs)) {
        previous = null;
        stable = 0;
        continue;
      }
      stable =
          previous != null
                  && previous.bindingEpoch == current.bindingEpoch
                  && distance(previous, current) < .15
              ? stable + 1
              : 0;
      if (stable >= 3) return current;
      previous = current;
      SystemClock.sleep(100);
    }
    throw new AssertionError("Isolated paused seek did not settle; no valid comparison reference");
  }

  private boolean referenceSettled(long targetMs) throws Exception {
    return onMain(
        () -> {
          if (page == null
              || read(activity, "currentPage") != page
              || !page.firstFrameReady
              || page.failed
              || page.buffering
              || page.playRequested
              || (boolean) read(page, "scrubbing")
              || Math.abs((long) read(page, "explicitSeekTarget") - targetMs) > 200) return false;
          if (page.exo != null) {
            long ptsUs = page.lastRenderedPositionUs;
            return page.exo.getPlaybackState() == Player.STATE_READY
                && !page.exo.getPlayWhenReady()
                && page.lastRenderedFrameSequence > 0
                && ptsUs >= 0
                && Math.abs(ptsUs - targetMs * 1000) <= 200_000
                && System.nanoTime() >= (long) read(page, "lastFrameReleaseTimeNs");
          }
          if (page.vlc == null || page.vlc.isPlaying()) return false;
          NativeSeekQueue queue = (NativeSeekQueue) read(page, "nativeSeekQueue");
          // Native clocks alone do not prove frame PTS. Keep the queue/output fence and the
          // following pixel-stability checks; synthetic media retains its independent barcode.
          return !queue.hasWork() && Math.abs(page.vlc.getTime() - targetMs) <= 200;
        },
        1000);
  }

  private Signature capture() throws Exception {
    if (SystemClock.uptimeMillis() - lastSurfaceCheck >= 80) refreshSurfaceBinding();
    long epoch = captureBindingEpoch;
    if (surface instanceof TextureView) {
      Bitmap frame = onMain(() -> ((TextureView) surface).getBitmap(frameWidth, frameHeight), 700);
      if (frame == null) throw new IllegalStateException("Texture has no picture");
      try {
        return new Signature(frame, epoch, barcodeFps, barcodeFrameCount);
      } finally {
        frame.recycle();
      }
    }
    if (!(surface instanceof SurfaceView))
      throw new IllegalStateException("No native video surface");
    if (!copyInFlight.compareAndSet(false, true))
      throw new IllegalStateException("Previous PixelCopy pending");
    Bitmap frame = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
    CountDownLatch done = new CountDownLatch(1);
    AtomicInteger code = new AtomicInteger(-1);
    AtomicBoolean abandoned = new AtomicBoolean();
    AtomicBoolean recycled = new AtomicBoolean();
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
                status -> {
                  code.set(status);
                  copyInFlight.set(false);
                  done.countDown();
                  if (abandoned.get()) recycle.run();
                },
                copyHandler);
          } catch (RuntimeException error) {
            code.set(-2);
            copyInFlight.set(false);
            done.countDown();
            if (abandoned.get()) recycle.run();
          }
        });
    if (!done.await(700, TimeUnit.MILLISECONDS)) {
      abandoned.set(true);
      if (done.getCount() == 0) recycle.run();
      // A late platform callback still owns the destination. It recycles it after completion.
      throw new java.util.concurrent.TimeoutException("PixelCopy timed out");
    }
    try {
      if (code.get() != PixelCopy.SUCCESS)
        throw new IllegalStateException("PixelCopy status " + code.get());
      return new Signature(frame, epoch, barcodeFps, barcodeFrameCount);
    } finally {
      recycle.run();
    }
  }

  private void refreshSurfaceBinding() throws Exception {
    onMain(
        () -> {
          VideoPage current = (VideoPage) read(activity, "currentPage");
          if (current == null) throw new IllegalStateException("No current playback page");
          String engine = current.exo != null ? "Media3" : current.vlc != null ? "LibVLC" : "none";
          Object player = current.exo != null ? current.exo : current.vlc;
          View currentSurface =
              current.exo != null && current.exoView != null
                  ? current.exoView.getVideoSurfaceView()
                  : NativeFrameProbe.findVideoSurface(current.vlcView);
          if (currentSurface == null
              || currentSurface.getWidth() <= 0
              || currentSurface.getHeight() <= 0)
            throw new IllegalStateException("Current engine has no ready surface");
          int playerIdentity = System.identityHashCode(player),
              pageIdentity = System.identityHashCode(current);
          int surfaceIdentity = System.identityHashCode(currentSurface);
          boolean changed =
              !engine.equals(boundEngine)
                  || playerIdentity != boundPlayerIdentity
                  || pageIdentity != boundPageIdentity
                  || surfaceIdentity != boundSurfaceIdentity;
          if (changed) {
            boolean wasBound = !boundEngine.equals("unbound");
            if (wasBound && measuringBurst) measuredReferenceInvalidated = true;
            JSONObject transition =
                new JSONObject()
                    .put("atUptime", SystemClock.uptimeMillis())
                    .put("duringMeasuredBurst", measuringBurst)
                    .put("fromEngine", boundEngine)
                    .put("toEngine", engine)
                    .put("fromPlayerIdentity", boundPlayerIdentity)
                    .put("toPlayerIdentity", playerIdentity)
                    .put("fromSurfaceIdentity", boundSurfaceIdentity)
                    .put("toSurfaceIdentity", surfaceIdentity)
                    .put("nativeSoftware", readOptional(current, "nativeSoftware"))
                    .put("bindingEpoch", ++captureBindingEpoch)
                    .put("referenceInvalidated", wasBound && measuringBurst);
            synchronized (surfaceTransitions) {
              surfaceTransitions.add(transition);
            }
            boundEngine = engine;
            boundPlayerIdentity = playerIdentity;
            boundPageIdentity = pageIdentity;
            boundSurfaceIdentity = surfaceIdentity;
          }
          page = current;
          surface = currentSurface;
          configureCaptureDimensions();
          lastSurfaceCheck = SystemClock.uptimeMillis();
          return null;
        },
        400);
  }

  private void configureCaptureDimensions() {
    float ratio = surface.getWidth() / (float) Math.max(1, surface.getHeight());
    if (barcodeFps != 0) {
      frameWidth = 256;
      frameHeight = Math.max(1, Math.round(256 / ratio));
    } else {
      frameWidth = ratio >= 1 ? 96 : Math.max(1, Math.round(96 * ratio));
      frameHeight = ratio >= 1 ? Math.max(1, Math.round(96 / ratio)) : 96;
    }
  }

  private JSONObject snapshot(long timeout) throws Exception {
    return onMain(
        () -> {
          JSONObject state = new JSONObject().put("atUptime", SystemClock.uptimeMillis());
          if (page == null) return state.put("available", false);
          state
              .put("engine", page.exo != null ? "Media3" : page.vlc != null ? "LibVLC" : "none")
              .put("positionMs", page.position())
              .put("playRequested", page.playRequested)
              .put("buffering", page.buffering)
              .put("failed", page.failed)
              .put("firstFrameReady", page.firstFrameReady)
              .put("pageIdentity", System.identityHashCode(page))
              .put("requestedUiTargetMs", read(activity, "gestureTarget"));
          state.put("nativeSeekDiagnostics", nativeSeekDiagnostics());
          state.put("nativeRetirement", retirementDiagnostics());
          Object lease = readOptionalObject(page, "nativeLease");
          if (lease != null) {
            Method failureStage = lease.getClass().getDeclaredMethod("failureStage");
            failureStage.setAccessible(true);
            Object stage = failureStage.invoke(lease);
            state.put(
                "nativeLease",
                new JSONObject()
                    .put("identity", System.identityHashCode(lease))
                    .put("failureStage", stage == null ? JSONObject.NULL : stage));
          } else state.put("nativeLease", JSONObject.NULL);
          if (page.exo != null)
            state
                .put("media3PlayerIdentity", System.identityHashCode(page.exo))
                .put("media3PlaybackState", page.exo.getPlaybackState())
                .put("media3Playing", page.exo.isPlaying())
                .put("media3SuppressionReason", page.exo.getPlaybackSuppressionReason())
                .put("media3Scrubbing", page.exo.isScrubbingModeEnabled())
                .put("media3PreciseHold", readOptionalObject(page, "exoExactHold") != null)
                .put("media3AudioGain", page.exo.getVolume())
                .put(
                    "media3LowLatencyConfigured",
                    readOptionalObject(page, "exoLowLatencyConfigured"))
                .put("media3HardwareConfigured", readOptionalObject(page, "exoHardwareConfigured"))
                .put(
                    "media3AdvertisedLowLatency",
                    readOptionalObject(page, "exoAdvertisedLowLatency"))
                .put("media3SoftwareConfigured", readOptionalObject(page, "exoSoftwareConfigured"))
                .put(
                    "media3AudioReuseAdmissions",
                    readOptionalObject(page, "exoAudioReuseAdmissions"))
                .put("media3AudioCodecReuses", readOptionalObject(page, "exoAudioCodecReuses"))
                .put(
                    "media3AudioKeyframeResets",
                    readOptionalObject(page, "exoAudioKeyframeResets"));
          if (page.vlc != null) {
            state
                .put("nativePlayerIdentity", System.identityHashCode(page.vlc))
                .put("nativeTimeMs", page.vlc.getTime())
                .put("nativePlaying", page.vlc.isPlaying())
                .put("nativeSoftware", read(page, "nativeSoftware"));
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
          }
          return state;
        },
        timeout);
  }

  private JSONObject nativeSeekDiagnostics() throws Exception {
    JSONObject diagnostics = new JSONObject();
    for (String name :
        new String[] {
          "nativeSeekFrameBaseline",
          "nativeSeekTextureBaseline",
          "nativeSeekDeadline",
          "nativeSeekSubmittedAt",
          "nativeSeekClockStepMs",
          "nativeSeekAwaitingPause",
          "lastRenderedFrameSequence",
          "lastRenderedPositionUs",
          "warmingVlc",
          "scrubbing",
          "pendingSeek",
          "pendingSeekSet"
        }) {
      diagnostics.put(name, readOptional(page, name));
    }
    Object queue = readOptionalObject(page, "nativeSeekQueue");
    if (queue == null) diagnostics.put("queue", JSONObject.NULL);
    else {
      JSONObject state =
          new JSONObject()
              .put("serial", readOptional(queue, "serial"))
              .put("submittedAtMs", readOptional(queue, "submittedAtMs"));
      for (String name : new String[] {"submitted", "pending"}) {
        Object request = readOptionalObject(queue, name);
        state.put(
            name,
            request == null
                ? JSONObject.NULL
                : new JSONObject()
                    .put("id", readOptional(request, "id"))
                    .put("positionMs", readOptional(request, "positionMs"))
                    .put("fast", readOptional(request, "fast")));
      }
      diagnostics.put("queue", state);
    }
    Object frames = readOptionalObject(page, "nativeSurfaceFrames");
    if (frames != null) {
      JSONObject state = new JSONObject();
      for (String name :
          new String[] {
            "frameSequence",
            "readyAfterSequence",
            "watchPending",
            "watchSerial",
            "available",
            "ready",
            "surfaceEpoch",
            "binding",
            "copyInFlight"
          }) state.put(name, readOptional(frames, name));
      diagnostics.put("surfaceFrames", state);
    } else diagnostics.put("surfaceFrames", JSONObject.NULL);
    return diagnostics;
  }

  private JSONObject retirementDiagnostics() throws Exception {
    JSONObject result = new JSONObject();
    try {
      Class<?> type = Class.forName("io.mediamaster.app.player.NativePlayerRetirement");
      Field sharedField = type.getDeclaredField("SHARED");
      sharedField.setAccessible(true);
      Object shared = sharedField.get(null);
      Method owned = type.getDeclaredMethod("ownedCount");
      Method quarantined = type.getDeclaredMethod("quarantinedCount");
      owned.setAccessible(true);
      quarantined.setAccessible(true);
      synchronized (shared) {
        result
            .put("available", true)
            .put("ownedCount", owned.invoke(shared))
            .put("quarantinedCount", quarantined.invoke(shared));
      }
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException oldBuild) {
      result.put("available", false).put("reason", "not-present-in-tested-build");
    }
    return result;
  }

  private static Object readOptional(Object object, String name) throws Exception {
    Object value = readOptionalObject(object, name);
    return value == null ? JSONObject.NULL : value;
  }

  private static Object readOptionalObject(Object object, String name) throws Exception {
    if (object == null) return null;
    try {
      return read(object, name);
    } catch (NoSuchFieldException absentInOlderBuild) {
      return null;
    }
  }

  private <T> T onMain(Callable<T> operation, long timeout) throws Exception {
    FutureTask<T> task = new FutureTask<>(operation);
    main.post(task);
    try {
      return task.get(timeout, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      task.cancel(false);
      throw error;
    }
  }

  private static Object read(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private static void waitUntil(long time) {
    long remaining = time - SystemClock.uptimeMillis();
    if (remaining > 0) SystemClock.sleep(remaining);
  }

  private Fixture fixture(Context context, Bundle args) {
    String name = args.getString("benchFile", "");
    String path = args.getString("benchRelativePath", "Movies/MediaMasterStress_20261101/");
    String explicit = args.getString("benchUri", "");
    assertTrue(
        "Supply one exact indexed file name or content URI",
        !name.isEmpty() || !explicit.isEmpty());
    Uri uri =
        explicit.isEmpty() ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : Uri.parse(explicit);
    assertEquals("Only indexed local media is allowed", "content", uri.getScheme());
    assertEquals("Only MediaStore URIs are allowed", "media", uri.getAuthority());
    String where = explicit.isEmpty() ? "_display_name=? AND relative_path=?" : null;
    String[] selection = explicit.isEmpty() ? new String[] {name, path} : null;
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {"_id", "duration", "_size", "_display_name"},
                where,
                selection,
                null)) {
      assertNotNull(cursor);
      assertEquals("Requested media selection must resolve exactly once", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      if (barcodeFps != 0) {
        assertTrue(
            "Indexed media is not a named synthetic stress fixture",
            cursor.getString(3).matches("mm_seek_stress_[A-Za-z0-9_-]+\\.mp4"));
        assertTrue(
            "Synthetic stress protocol requires an eight-second indexed video",
            Math.abs(cursor.getLong(1) - 8000) <= 150);
      }
      return new Fixture(
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0)),
          cursor.getLong(1),
          cursor.getLong(2));
    }
  }

  private static final class Fixture {
    final Uri uri;
    final long duration, size;

    Fixture(Uri uri, long duration, long size) {
      this.uri = uri;
      this.duration = duration;
      this.size = size;
    }
  }

  private static final class Tap {
    final int x, y;
    final long target;
    volatile long down, up, sent;

    Tap(int x, int y, long target) {
      this.x = x;
      this.y = y;
      this.target = target;
    }

    JSONObject json(long origin) throws Exception {
      return new JSONObject()
          .put("x", x)
          .put("y", y)
          .put("targetMs", target)
          .put("downMs", down == 0 ? JSONObject.NULL : down - origin)
          .put("upMs", up == 0 ? JSONObject.NULL : up - origin)
          .put("dispatchEndMs", sent == 0 ? JSONObject.NULL : sent - origin);
    }
  }

  private static final class DeliveredTouch {
    final int action;
    final long eventTime, startedAt;
    volatile long completedAt;

    DeliveredTouch(int action, long eventTime, long startedAt) {
      this.action = action;
      this.eventTime = eventTime;
      this.startedAt = startedAt;
    }
  }

  private static final class Signature {
    final int[] pixels;
    final String hash;
    final long bindingEpoch;
    final int barcodeFrame;
    final long barcodeFrameMs;
    long offset;

    Signature(Bitmap frame, long bindingEpoch, int barcodeFps, int barcodeFrameCount)
        throws Exception {
      this.bindingEpoch = bindingEpoch;
      OpticalSeekBenchmark.Code code = barcodeFps == 0 ? null : OpticalSeekBenchmark.decode(frame);
      barcodeFrame =
          code != null && code.frame >= 0 && code.frame < barcodeFrameCount ? code.frame : -1;
      barcodeFrameMs = barcodeFrame < 0 ? -1 : barcodeFrame * 1000L / barcodeFps;
      pixels = new int[frame.getWidth() * frame.getHeight()];
      frame.getPixels(pixels, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int value : pixels) {
        digest.update((byte) (value >> 16));
        digest.update((byte) (value >> 8));
        digest.update((byte) value);
      }
      StringBuilder out = new StringBuilder();
      for (byte value : digest.digest())
        out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
      hash = out.toString();
    }
  }

  /** Independent synthetic PTS oracle; no player clock or same-engine image matching is used. */
  private static final class BarcodeOracle {
    final boolean playing;
    final long targetMs, durationMs;
    long firstNearAfterUp = -1, anchorAt = -1, anchorFrame = -1, lastAt = -1, lastFrame = -1;
    long seriesStartAfterUp = -1, seriesProgressMs;
    int valid, invalid, consecutive;

    BarcodeOracle(boolean playing, long targetMs, int fps, int frames) {
      this.playing = playing;
      this.targetMs = targetMs;
      durationMs = fps == 0 ? 0 : frames * 1000L / fps;
    }

    void accept(Signature frame, long now, long finalUp) {
      if (frame.barcodeFrame < 0) {
        invalid++;
        resetSeries();
        return;
      }
      valid++;
      long actual = frame.barcodeFrameMs;
      boolean near = Math.abs(actual - targetMs) <= 150;
      if (firstNearAfterUp < 0 && near) firstNearAfterUp = now - finalUp;
      if (anchorAt >= 0) {
        long expected = playing ? (anchorFrame + now - anchorAt) % durationMs : anchorFrame;
        long error = Math.abs(actual - expected);
        error = Math.min(error, durationMs - error);
        long forward = actual - lastFrame;
        if (forward < 0 && lastFrame > durationMs - 300 && actual < 300) forward += durationMs;
        boolean consistent = error <= 150 && (playing ? forward >= 0 : near && actual == lastFrame);
        if (consistent) {
          consecutive++;
          seriesProgressMs += Math.max(0, forward);
          lastAt = now;
          lastFrame = actual;
          return;
        }
        resetSeries();
      }
      if (near) {
        anchorAt = lastAt = now;
        anchorFrame = lastFrame = actual;
        seriesStartAfterUp = now - finalUp;
        consecutive = 1;
        seriesProgressMs = 0;
      }
    }

    private void resetSeries() {
      anchorAt = anchorFrame = lastAt = lastFrame = -1;
      seriesStartAfterUp = -1;
      seriesProgressMs = 0;
      consecutive = 0;
    }

    boolean ready() {
      return firstNearAfterUp >= 0
          && anchorAt >= 0
          && lastAt - anchorAt >= 300
          && consecutive >= (playing ? 4 : 3)
          && (!playing || seriesProgressMs >= 250);
    }

    JSONObject json() throws Exception {
      return new JSONObject()
          .put("targetMs", targetMs)
          .put("toleranceMs", 150)
          .put("validPostFinalFrames", valid)
          .put("invalidPostFinalFrames", invalid)
          .put("firstNearAfterFinalUpMs", firstNearAfterUp)
          .put("currentSeriesStartAfterFinalUpMs", seriesStartAfterUp)
          .put("currentSeriesConsecutiveFrames", consecutive)
          .put("currentSeriesDurationMs", anchorAt < 0 ? 0 : lastAt - anchorAt)
          .put("currentSeriesProgressMs", seriesProgressMs)
          .put("lastDecodedFrameMs", lastFrame)
          .put("ready", ready())
          .put(
              "source",
              "Guard/parity-validated B2 frame index from the actual Surface; frame_index*1000/60,"
                  + " independent of player time");
    }
  }

  private static double distance(Signature first, Signature second) {
    if (first.pixels.length != second.pixels.length) return 255;
    long sum = 0;
    for (int i = 0; i < first.pixels.length; i++) {
      int a = first.pixels[i], b = second.pixels[i];
      sum += Math.abs((a >> 16 & 255) - (b >> 16 & 255));
      sum += Math.abs((a >> 8 & 255) - (b >> 8 & 255));
      sum += Math.abs((a & 255) - (b & 255));
    }
    return sum / (first.pixels.length * 3d);
  }
}
