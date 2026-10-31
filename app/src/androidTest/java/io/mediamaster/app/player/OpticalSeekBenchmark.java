package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test-only optical measurement of the actual foreground app, including a separately installed VLC.
 * Only generated mm_seek_/SeekBench_ files may be opened. Does not access a playback engine clock.
 */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4.class)
public final class OpticalSeekBenchmark {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();
  private String packageName;
  private File output;
  private long chromeShownAt;

  @Test
  public void barcodeDecoderRecognizesMainVideoAndRejectsBrokenParity() {
    Bitmap bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(bitmap);
      canvas.drawColor(Color.BLACK);
      Paint paint = new Paint();
      int frame = 749, parity = Integer.bitCount(frame) & 1;
      for (int i = 0; i < 16; i++) {
        int color =
            i == 0
                ? Color.RED
                : i == 15
                    ? Color.BLUE
                    : i == 1
                        ? Color.WHITE
                        : ((i == 14 ? parity : (frame >> (i - 2)) & 1) == 1
                            ? Color.WHITE
                            : Color.BLACK);
        paint.setColor(color);
        canvas.drawRect(128 + i * 64, 180, 128 + (i + 1) * 64, 540, paint);
      }
      Code code = decode(bitmap);
      assertNotNull(code);
      assertEquals(frame, code.frame);
      paint.setColor(parity == 1 ? Color.BLACK : Color.WHITE);
      canvas.drawRect(128 + 14 * 64, 180, 128 + 15 * 64, 540, paint);
      assertNull("A corrupt parity cell must not become latency evidence", decode(bitmap));
    } finally {
      bitmap.recycle();
    }
  }

  @Test
  public void measureSeekLatency() throws Exception {
    Context context = instrumentation.getTargetContext();
    AccessibilityServiceInfo accessibility = automation.getServiceInfo();
    accessibility.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    automation.setServiceInfo(accessibility);
    Bundle args = InstrumentationRegistry.getArguments();
    packageName = args.getString("benchPackage", "");
    assertTrue(
        "Only the installed VLC or this test's app may be benchmarked",
        packageName.equals("org.videolan.vlc") || packageName.equals(context.getPackageName()));
    String name = args.getString("benchFile", "");
    assertTrue(
        "Only an explicitly named generated benchmark video is allowed",
        name.matches("(?:mm_seek_|SeekBench_)[A-Za-z0-9_-]+\\.mp4"));
    String state = args.getString("benchState", "paused");
    assertTrue(state.equals("paused") || state.equals("playing"));
    boolean playing = state.equals("playing");
    String run = args.getString("benchRun", "run-" + SystemClock.uptimeMillis());
    assertTrue(run.matches("[A-Za-z0-9_-]{1,100}"));
    String calibrationArg = args.getString("benchCalibrate", "true");
    assertTrue(
        "benchCalibrate must be true or false",
        calibrationArg.equals("true") || calibrationArg.equals("false"));
    boolean calibrate = Boolean.parseBoolean(calibrationArg);
    int toleranceMs =
        Integer.parseInt(args.getString("benchToleranceMs", calibrate ? "120" : "1000"));
    assertTrue(toleranceMs >= 100 && toleranceMs <= 2000);
    long timeoutMs = Long.parseLong(args.getString("benchTimeoutMs", "10000"));
    assertTrue(timeoutMs >= 1000 && timeoutMs <= 30000);
    output = new File(context.getExternalFilesDir(null), "test-only/seek-benchmark/" + run);
    assertTrue(output.isDirectory() || output.mkdirs());
    automation.grantRuntimePermission(
        context.getPackageName(),
        Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE);
    Fixture fixture = fixture(context, name);
    SharedPreferences preferences = context.getSharedPreferences("player", 0);
    boolean hadEngine = preferences.contains("engine_choice");
    int savedEngine = preferences.getInt("engine_choice", 0);
    String nativeDecoderArg = args.getString("benchNativeDecoder");
    boolean overrideNativeDecoder = nativeDecoderArg != null;
    boolean hadNativeDecoder = preferences.contains("native_decoder_mode");
    int savedNativeDecoder = preferences.getInt("native_decoder_mode", 0);
    if (overrideNativeDecoder) {
      assertTrue(
          "Native decoder overrides apply only to the app under test",
          packageName.equals(context.getPackageName()));
      int decoder = Integer.parseInt(nativeDecoderArg);
      assertTrue(
          "benchNativeDecoder must be0(auto),1(hardware),or2(software)",
          decoder >= 0 && decoder <= 2);
    }
    String engineArg = args.getString("benchEngine");
    boolean overrideEngine = engineArg != null && packageName.equals(context.getPackageName());
    if (engineArg != null) {
      assertTrue(
          "Engine selection applies only to the app under test, never VLC settings",
          overrideEngine);
      int choice = Integer.parseInt(engineArg);
      assertTrue(choice >= 0 && choice <= 2);
    }
    JSONObject result =
        new JSONObject()
            .put("run", run)
            .put("package", packageName)
            .put("file", name)
            .put("state", state)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", "reference-device")
            .put("durationMs", fixture.durationMs)
            .put("protocolDurationMs", SeekTouchCalibration.PROTOCOL_DURATION_MS)
            .put("physicalTouchCalibration", calibrate)
            .put("frameRate", 25)
            .put(
                "nativeDecoderChoiceRequested",
                overrideNativeDecoder ? Integer.parseInt(nativeDecoderArg) : JSONObject.NULL)
            .put("targetToleranceMs", toleranceMs)
            .put("timeoutMs", timeoutMs)
            .put(
                "engineChoiceRequested",
                overrideEngine ? Integer.parseInt(engineArg) : JSONObject.NULL)
            .put(
                "measurement",
                "Wall uptime from DOWN injection to optically decoded foreground frame; intervals"
                    + " include screenshot sampling uncertainty")
            .put(
                "accuracyNote",
                "Requested points are fixed 25-fps fixture timestamps; calibration observes held"
                    + " widget requests, never decoded landing frames. Inferred input quantization"
                    + " error is recorded separately. Optical frame sampling determines latency.")
            .put("collectionCompleted", false)
            .put("pausedMinimumObservationMs", 1000)
            .put("pausedStableWindowMs", 300)
            .put("rows", new JSONArray());
    JSONArray rows = result.getJSONArray("rows");
    JSONArray accuracyFailures = new JSONArray(), observationFailures = new JSONArray();
    result
        .put("accuracyFailures", accuracyFailures)
        .put("observationFailures", observationFailures);
    try {
      if (overrideEngine)
        assertTrue(
            preferences.edit().putInt("engine_choice", Integer.parseInt(engineArg)).commit());
      if (overrideNativeDecoder)
        assertTrue(
            preferences
                .edit()
                .putInt("native_decoder_mode", Integer.parseInt(nativeDecoderArg))
                .commit());
      stopExternalVlc();
      Intent open =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(fixture.uri, "video/mp4")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      if (packageName.equals(context.getPackageName()))
        open.setClassName(packageName, "io.mediamaster.app.player.PlayerActivity");
      else open.setPackage(packageName);
      context.startActivity(open);
      SystemClock.sleep(500);
      waitForBarcode(20000).close();
      result
          .put("actualEngine", actualEngine(context))
          .put("nativeRuntime", NativeBenchmarkEvidence.capture(instrumentation, packageName));
      setPlaybackState(true);
      setPlaybackState(playing);
      String actualTitle = verifyFixture(context, name);
      result
          .put("actualTitle", actualTitle)
          .put("actualFixture", name)
          .put(
              "fixtureIdentitySource",
              packageName.equals(context.getPackageName())
                  ? "resumed-currentPage-item"
                  : "visible-player_overlay_title");
      SeekTouchCalibration calibration = null;
      if (calibrate) {
        JSONObject calibrationEvidence = new JSONObject();
        result.put("calibration", calibrationEvidence);
        calibration =
            SeekTouchCalibration.measure(
                instrumentation,
                packageName,
                new SeekTouchCalibration.Controls() {
                  @Override
                  public void pause() throws Exception {
                    setPlaybackState(false);
                  }

                  @Override
                  public Rect renew() throws Exception {
                    return renewChrome();
                  }
                },
                calibrationEvidence);
      }
      JSONObject originReset =
          SeekTouchCalibration.resetOrigin(
              instrumentation,
              packageName,
              new SeekTouchCalibration.Controls() {
                @Override
                public void pause() throws Exception {
                  setPlaybackState(false);
                }

                @Override
                public Rect renew() throws Exception {
                  return renewChrome();
                }
              },
              calibration,
              fixture.durationMs);
      result.put("originReset", originReset);
      verifyOrigin(originReset);
      String[] fractions = args.getString("benchTargets", ".2,.7,.35,.8,.25,.65").split(",");
      assertTrue(fractions.length >= 1 && fractions.length <= 30);
      for (int index = 0; index < fractions.length; index++) {
        double fraction = Double.parseDouble(fractions[index]);
        assertTrue(fraction > .05 && fraction < .95);
        setPlaybackState(playing);
        String engine = actualEngine(context);
        JSONObject nativeRuntime = NativeBenchmarkEvidence.capture(instrumentation, packageName);
        Rect bar = renewChrome();
        setPlaybackState(playing);
        Capture before = waitForBarcode(3000);
        boolean barStillVisible = false;
        for (int attempt = 0; attempt < 3; attempt++) {
          Rect freshBar = largestSeekBar(freshRoot(), null);
          if (freshBar != null) {
            bar = freshBar;
            barStillVisible = true;
            break;
          }
          before.close();
          bar = ensureSeekBar();
          before = waitForBarcode(3000);
        }
        long beforeMs = before.code.frame * 40L;
        long beforeCapturedAt = before.captureEnded;
        float mainSpan = before.code.span;
        Bitmap beforeProof = before.bitmap;
        before.retained = true;
        before.close();
        assertTrue("SeekBar disappeared during premeasurement setup", barStillVisible);
        long requestedMs = SeekTouchCalibration.targetMs(fraction);
        double suppliedFraction = fraction;
        if (index == 0 && Math.abs(requestedMs - beforeMs) <= 2000) {
          fraction = beforeMs < fixture.durationMs / 2 ? .8 : .2;
          requestedMs = SeekTouchCalibration.targetMs(fraction);
        }
        assertTrue(
            "Target must be a large jump rather than normal playback: before="
                + beforeMs
                + ", target="
                + requestedMs,
            Math.abs(requestedMs - beforeMs) > 2000);
        SeekTouchCalibration.Point touch =
            calibration != null
                ? calibration.point(bar, requestedMs)
                : SeekTouchCalibration.uncalibrated(
                    context, packageName, bar, requestedMs, fixture.durationMs);
        float x = touch.x;
        float y = bar.exactCenterY();
        JSONObject row =
            new JSONObject()
                .put("trial", index)
                .put("fraction", fraction)
                .put("suppliedFraction", suppliedFraction)
                .put("requestedMs", requestedMs)
                .put("inferredInputRequestMs", touch.inferredRequestMs)
                .put("inputQuantizationErrorMs", touch.inferredRequestMs - requestedMs)
                .put("beforeFrameMs", beforeMs)
                .put("tapX", x)
                .put("tapY", y)
                .put("seekBar", bar.flattenToString());
        row.put("actualEngine", engine).put("nativeRuntime", nativeRuntime);
        rows.put(row);
        long down = SystemClock.uptimeMillis();
        row.put("beforeFrameAgeAtDownMs", down - beforeCapturedAt);
        row.put("chromeAgeAtDownMs", down - chromeShownAt);
        inject(MotionEvent.ACTION_DOWN, x, y, down, down, false);
        long untilUp = down + 20 - SystemClock.uptimeMillis();
        if (untilUp > 0) SystemClock.sleep(untilUp);
        long up = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_UP, x, y, down, up, false);
        row.put("inputDispatchMs", SystemClock.uptimeMillis() - down).put("downUpMs", up - down);
        long lastVerifiedNonJumpStart = down, lastVerifiedNonTargetStart = down;
        JSONArray timeline = new JSONArray();
        row.put("timeline", timeline).put("beforeBarcodeSpan", mainSpan);
        int captures = 0, validCaptures = 0;
        long captureTotal = 0, maxCapture = 0, maxDecode = 0;
        boolean jumpSeen = false, targetSeen = false, pausedLandingObserved = false;
        long pausedStableFrame = -1, pausedStableFirstEnd = 0;
        int pausedStableSamples = 0, pausedStableFirstIndex = 0;
        Bitmap firstJumpProof = null, nearTargetProof = null;
        while (SystemClock.uptimeMillis() - down < timeoutMs) {
          try (Capture frame = capture()) {
            captures++;
            captureTotal += frame.captureMs;
            maxCapture = Math.max(maxCapture, frame.captureMs);
            maxDecode = Math.max(maxDecode, frame.decodeMs);
            if (frame.code != null && frame.code.span >= mainSpan * .9f) {
              validCaptures++;
              long frameMs = frame.code.frame * 40L;
              long elapsed = frame.captureEnded - down;
              long expectedWithoutSeek =
                  playing ? beforeMs + frame.captureEnded - beforeCapturedAt : beforeMs;
              long naturalDistance =
                  circularDistance(frameMs, expectedWithoutSeek, fixture.durationMs);
              timeline.put(
                  new JSONObject()
                      .put("captureStartMs", frame.captureStarted - down)
                      .put("captureEndMs", elapsed)
                      .put("frameMs", frameMs)
                      .put("span", frame.code.span));
              if (!jumpSeen && naturalDistance > 750) {
                jumpSeen = true;
                row.put("firstJumpLowerMs", Math.max(0, lastVerifiedNonJumpStart - down))
                    .put("firstJumpUpperMs", elapsed)
                    .put("firstJumpFrameMs", frameMs);
                firstJumpProof = frame.bitmap;
                frame.retained = true;
              }
              if (!jumpSeen && naturalDistance <= 750)
                lastVerifiedNonJumpStart = frame.captureStarted;
              // Allow ordinary forward playback after the requested point, but never a distant
              // keyframe. First-jump and near-target times remain separate in the results.
              long targetDistance = Math.abs(frameMs - requestedMs);
              long advancingDistance =
                  playing ? Math.abs(frameMs - requestedMs - elapsed) : targetDistance;
              boolean nearTarget =
                  jumpSeen && Math.min(targetDistance, advancingDistance) <= toleranceMs;
              if (!targetSeen && nearTarget) {
                targetSeen = true;
                row.put("nearTargetLowerMs", Math.max(0, lastVerifiedNonTargetStart - down))
                    .put("nearTargetUpperMs", elapsed)
                    .put("nearTargetFrameMs", frameMs);
                nearTargetProof = frame.bitmap;
                frame.retained = true;
              }
              if (!targetSeen && !nearTarget) lastVerifiedNonTargetStart = frame.captureStarted;
              row.put("lastFrameMs", frameMs);
              if (!playing) {
                if (frameMs != pausedStableFrame) {
                  pausedStableFrame = frameMs;
                  pausedStableFirstEnd = elapsed;
                  pausedStableFirstIndex = timeline.length() - 1;
                  pausedStableSamples = 1;
                } else pausedStableSamples++;
                pausedLandingObserved =
                    naturalDistance > 750
                        && elapsed >= 1000
                        && pausedStableSamples >= 3
                        && elapsed - pausedStableFirstEnd >= 300;
                if (pausedLandingObserved) break;
              } else if (targetSeen && elapsed >= 800) break;
            } else if (!playing) {
              // An undecodable screenshot cannot establish a continuous stable observation window.
              pausedStableFrame = -1;
              pausedStableSamples = 0;
            }
          }
        }
        row.put("status", targetSeen ? "observed" : jumpSeen ? "jump-only" : "unobserved")
            .put("captures", captures)
            .put("validCaptures", validCaptures)
            .put("meanCaptureMs", captures == 0 ? 0 : (double) captureTotal / captures)
            .put("maxCaptureMs", maxCapture)
            .put("maxDecodeMs", maxDecode);
        if (!playing && pausedLandingObserved) {
          int last = timeline.length() - 1, firstStable = last;
          long landed = timeline.getJSONObject(last).getLong("frameMs");
          while (firstStable > pausedStableFirstIndex
              && timeline.getJSONObject(firstStable - 1).getLong("frameMs") == landed)
            firstStable--;
          JSONObject firstStableSample = timeline.getJSONObject(firstStable);
          row.put("finalStableFrameMs", landed)
              .put("finalStableTargetErrorMs", landed - requestedMs)
              .put("finalStableInputErrorMs", landed - touch.inferredRequestMs)
              .put(
                  "finalStableDurationMs",
                  timeline.getJSONObject(last).getLong("captureEndMs")
                      - firstStableSample.getLong("captureStartMs"))
              .put(
                  "finalStableFirstLowerMs",
                  firstStable == 0
                      ? 0
                      : timeline.getJSONObject(firstStable - 1).getLong("captureStartMs"))
              .put("finalStableFirstUpperMs", firstStableSample.getLong("captureEndMs"));
        }
        if (!playing) {
          boolean accurate =
              pausedLandingObserved
                  && Math.abs(row.getLong("finalStableTargetErrorMs")) <= toleranceMs;
          row.put(
                  "observationStatus",
                  pausedLandingObserved
                      ? "stable-landing"
                      : jumpSeen ? "jump-without-stable-landing" : "no-observed-jump")
              .put(
                  "accuracyStatus",
                  pausedLandingObserved
                      ? accurate ? "within-tolerance" : "outside-tolerance"
                      : "unassessed")
              .put(
                  "status",
                  pausedLandingObserved
                      ? accurate ? "observed" : "settled-outside-tolerance"
                      : jumpSeen ? "jump-only" : "unobserved");
          if (!pausedLandingObserved) observationFailures.put(index);
          else if (!accurate) accuracyFailures.put(index);
        }
        if (playing && timeline.length() > 0) {
          int last = timeline.length() - 1, firstConsistent = last;
          JSONObject finalSample = timeline.getJSONObject(last);
          long finalPts = finalSample.getLong("frameMs");
          long finalTime = finalSample.getLong("captureEndMs");
          while (firstConsistent > 0) {
            JSONObject earlier = timeline.getJSONObject(firstConsistent - 1);
            long predicted =
                earlier.getLong("frameMs") + finalTime - earlier.getLong("captureEndMs");
            if (circularDistance(finalPts, predicted, fixture.durationMs) > 120) break;
            firstConsistent--;
          }
          JSONObject firstConsistentSample = timeline.getJSONObject(firstConsistent);
          row.put("finalConsistentTrajectoryFrameMs", finalPts)
              .put(
                  "finalConsistentTrajectoryDurationMs",
                  finalTime - firstConsistentSample.getLong("captureStartMs"))
              .put(
                  "finalConsistentTrajectoryFirstLowerMs",
                  firstConsistent == 0
                      ? 0
                      : timeline.getJSONObject(firstConsistent - 1).getLong("captureStartMs"))
              .put(
                  "finalConsistentTrajectoryFirstUpperMs",
                  firstConsistentSample.getLong("captureEndMs"));
        }
        row.put(
            "nativeRuntimeAfter", NativeBenchmarkEvidence.capture(instrumentation, packageName));
        // PNG compression and disk writes run after timing is complete, not between seek frames.
        try {
          save(beforeProof, "trial-" + index + "-before-down.png");
          if (firstJumpProof != null) save(firstJumpProof, "trial-" + index + "-first-jump.png");
          if (nearTargetProof != null) save(nearTargetProof, "trial-" + index + "-near-target.png");
        } finally {
          beforeProof.recycle();
          if (firstJumpProof != null) firstJumpProof.recycle();
          if (nearTargetProof != null && nearTargetProof != firstJumpProof)
            nearTargetProof.recycle();
        }
        write(result);
        Log.i("OpticalSeek", row.toString());
        if (playing)
          assertTrue("No verified target picture; partial evidence saved: " + row, targetSeen);
        SystemClock.sleep(300);
      }
      result
          .put("collectionCompleted", true)
          .put("observationPassed", observationFailures.length() == 0)
          .put("accuracyAssessed", playing ? JSONObject.NULL : observationFailures.length() == 0)
          .put(
              "accuracyPassed",
              playing
                  ? JSONObject.NULL
                  : observationFailures.length() == 0 && accuracyFailures.length() == 0);
      if (!playing) {
        assertTrue(
            "All targets were collected; no stable changed landing for trials "
                + observationFailures,
            observationFailures.length() == 0);
        assertTrue(
            "All targets were collected; stable landing outside unchanged "
                + toleranceMs
                + "ms accuracy tolerance for trials "
                + accuracyFailures,
            accuracyFailures.length() == 0);
      }
      result.put("completed", true);
    } catch (Throwable failure) {
      result.put("completed", false).put("error", failure.toString());
      if (packageName.equals(context.getPackageName())) {
        try {
          result.put("ownFailureState", ownFailureSnapshot());
        } catch (Throwable unavailable) {
          result.put("snapshotError", unavailable.toString());
        }
      }
      throw failure;
    } finally {
      if (overrideNativeDecoder) {
        SharedPreferences.Editor restore = preferences.edit();
        if (hadNativeDecoder) restore.putInt("native_decoder_mode", savedNativeDecoder);
        else restore.remove("native_decoder_mode");
        restore.commit();
      }
      if (overrideEngine) {
        SharedPreferences.Editor restore = preferences.edit();
        if (hadEngine) restore.putInt("engine_choice", savedEngine);
        else restore.remove("engine_choice");
        restore.commit();
      }

      write(result);
      Log.i("OpticalSeek", "Results: " + new File(output, "results.json").getAbsolutePath());
    }
  }

  private void verifyOrigin(JSONObject evidence) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 10000;
    long previous = -1;
    while (SystemClock.uptimeMillis() < deadline) {
      try (Capture frame = capture()) {
        if (frame.code != null) {
          long pts = frame.code.frame * 40L;
          if (Math.abs(pts - 400) <= 120 && pts == previous) {
            evidence.put("verifiedFrameMs", pts).put("verifiedPaused", true);
            return;
          }
          previous = pts;
        } else previous = -1;
      }
      SystemClock.sleep(100);
    }
    throw new AssertionError(
        "The paused common origin did not settle optically near400ms; no measured seek was"
            + " injected");
  }

  private static long circularDistance(long a, long b, long duration) {
    long difference = Math.abs((a - b) % duration);
    return Math.min(difference, duration - difference);
  }

  private String actualEngine(Context context) {
    if (!packageName.equals(context.getPackageName())) return "external-vlc-app";
    AtomicReference<String> engine = new AtomicReference<>("unknown-no-resumed-player");
    instrumentation.runOnMainSync(
        () -> {
          for (Activity activity :
              ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
            if (!activity.getClass().getName().equals("io.mediamaster.app.player.PlayerActivity"))
              continue;
            try {
              Field current = activity.getClass().getDeclaredField("currentPage");
              current.setAccessible(true);
              Object page = current.get(activity);
              if (page == null) continue;
              Field exo = page.getClass().getDeclaredField("exo");
              Field vlc = page.getClass().getDeclaredField("vlc");
              exo.setAccessible(true);
              vlc.setAccessible(true);
              engine.set(
                  exo.get(page) != null
                      ? "Media3"
                      : vlc.get(page) != null ? "LibVLC" : "unknown-no-engine");
            } catch (ReflectiveOperationException unavailable) {
              engine.set("unknown-" + unavailable.getClass().getSimpleName());
            }
          }
        });
    return engine.get();
  }

  private JSONObject ownFailureSnapshot() throws Exception {
    JSONObject snapshot =
        new JSONObject()
            .put("note", "Failure diagnostics only; player clocks are not latency evidence");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            for (Activity activity :
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
              if (!activity.getClass().getName().equals("io.mediamaster.app.player.PlayerActivity"))
                continue;
              snapshot.put("activity", activity.getClass().getName());
              diagnosticField(snapshot, activity, "shouldPlay");
              diagnosticField(snapshot, activity, "gestureKind");
              Field current = activity.getClass().getDeclaredField("currentPage");
              current.setAccessible(true);
              Object page = current.get(activity);
              if (page == null) {
                snapshot.put("page", JSONObject.NULL);
                continue;
              }
              for (String field :
                  new String[] {
                    "active",
                    "playRequested",
                    "firstFrameReady",
                    "failed",
                    "buffering",
                    "scrubbing",
                    "warmingVlc",
                    "vlcRunningRequested"
                  }) diagnosticField(snapshot, page, field);
              Field nativeField = page.getClass().getDeclaredField("vlc");
              nativeField.setAccessible(true);
              org.videolan.libvlc.MediaPlayer nativePlayer =
                  (org.videolan.libvlc.MediaPlayer) nativeField.get(page);
              if (nativePlayer != null)
                snapshot
                    .put("engine", "LibVLC")
                    .put("nativePlaying", nativePlayer.isPlaying())
                    .put("nativeClockMs", nativePlayer.getTime())
                    .put("nativeVolumeGetter", nativePlayer.getVolume());
              Field exoField = page.getClass().getDeclaredField("exo");
              exoField.setAccessible(true);
              androidx.media3.exoplayer.ExoPlayer exo =
                  (androidx.media3.exoplayer.ExoPlayer) exoField.get(page);
              if (exo != null)
                snapshot
                    .put("engine", "Media3")
                    .put("exoState", exo.getPlaybackState())
                    .put("exoPlayWhenReady", exo.getPlayWhenReady())
                    .put("exoPlaying", exo.isPlaying())
                    .put("exoClockMs", exo.getCurrentPosition());
            }
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) snapshot.put("partialSnapshotError", failure.get().toString());
    return snapshot;
  }

  private static void diagnosticField(JSONObject output, Object owner, String name) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      output.put(name, field.get(owner));
    } catch (Exception unavailable) {
      /* Older app versions may not expose every state field. */
    }
  }

  private void stopExternalVlc() throws Exception {
    if (!packageName.equals("org.videolan.vlc")) return;
    // Each external-app trial owns only an explicitly named generated fixture. A fresh launch
    // prevents VLC from retaining the previous ACTION_VIEW media while accepting another intent.
    try (java.io.InputStream stream =
        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("am force-stop org.videolan.vlc"))) {
      byte[] buffer = new byte[1024];
      while (stream.read(buffer) >= 0) {
        /* Drain the command and close its descriptor. */
      }
    }
    SystemClock.sleep(100);
  }

  private String verifyFixture(Context context, String expected) throws Exception {
    String actual = null;
    if (packageName.equals(context.getPackageName())) {
      AtomicReference<String> value = new AtomicReference<>();
      AtomicReference<Throwable> identityFailure = new AtomicReference<>();
      instrumentation.runOnMainSync(
          () -> {
            for (Activity activity :
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
              if (!activity.getClass().getName().equals("io.mediamaster.app.player.PlayerActivity"))
                continue;
              try {
                Field current = activity.getClass().getDeclaredField("currentPage");
                current.setAccessible(true);
                Object page = current.get(activity);
                if (page == null) continue;
                Field itemField = page.getClass().getDeclaredField("item");
                itemField.setAccessible(true);
                Object item = itemField.get(page);
                if (item == null) continue;
                Field nameField = item.getClass().getDeclaredField("name");
                nameField.setAccessible(true);
                value.set((String) nameField.get(item));
              } catch (ReflectiveOperationException failure) {
                identityFailure.set(failure);
              }
            }
          });
      if (identityFailure.get() != null)
        throw new AssertionError(
            "Cannot verify the current playback fixture", identityFailure.get());
      actual = value.get();
    } else {
      ensureSeekBar();
      for (int attempt = 0; attempt < 8 && actual == null; attempt++) {
        actual = fixtureTitle(freshRoot());
        if (actual == null) SystemClock.sleep(100);
      }
    }
    assertNotNull("Cannot establish actual fixture identity; no latency evidence accepted", actual);
    assertEquals(
        "The visible player must contain the requested generated fixture",
        fixtureBasename(expected),
        fixtureBasename(actual));
    return actual;
  }

  private static String fixtureBasename(String value) {
    String title = value.trim();
    return title.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
        ? title.substring(0, title.length() - 4)
        : title;
  }

  private static String fixtureTitle(AccessibilityNodeInfo node) {
    if (node == null || !node.isVisibleToUser()) return null;
    String id = node.getViewIdResourceName();
    if (id != null && id.endsWith("/player_overlay_title") && node.getText() != null)
      return node.getText().toString();
    for (int i = 0; i < node.getChildCount(); i++) {
      String title = fixtureTitle(node.getChild(i));
      if (title != null) return title;
    }
    return null;
  }

  private void write(JSONObject result) throws Exception {
    try (OutputStreamWriter writer =
        new OutputStreamWriter(
            new FileOutputStream(new File(output, "results.json")), StandardCharsets.UTF_8)) {
      writer.write(result.toString(2));
    }
    try (OutputStreamWriter writer =
        new OutputStreamWriter(
            new FileOutputStream(new File(output, "results.csv")), StandardCharsets.UTF_8)) {
      JSONArray rows = result.getJSONArray("rows");
      String[] keys = {
        "trial",
        "fraction",
        "requestedMs",
        "inferredInputRequestMs",
        "inputQuantizationErrorMs",
        "beforeFrameMs",
        "status",
        "observationStatus",
        "accuracyStatus",
        "firstJumpLowerMs",
        "firstJumpUpperMs",
        "firstJumpFrameMs",
        "nearTargetLowerMs",
        "nearTargetUpperMs",
        "nearTargetFrameMs",
        "finalStableFirstLowerMs",
        "finalStableFirstUpperMs",
        "finalStableFrameMs",
        "finalStableDurationMs",
        "finalStableTargetErrorMs",
        "finalStableInputErrorMs",
        "captures",
        "validCaptures",
        "meanCaptureMs",
        "maxCaptureMs",
        "maxDecodeMs"
      };
      writer.write(String.join(",", keys) + "\n");
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        for (int k = 0; k < keys.length; k++) {
          if (k > 0) writer.write(',');
          writer.write(row.has(keys[k]) ? row.get(keys[k]).toString() : "");
        }
        writer.write('\n');
      }
    }
  }

  private void setPlaybackState(boolean playing) throws Exception {
    for (int attempt = 0; attempt < 8; attempt++) {
      ensureSeekBar();
      Capture first = waitForBarcode(5000);
      int start = first.code.frame;
      first.close();
      SystemClock.sleep(220);
      Capture second = waitForBarcode(3000);
      boolean moving = second.code.frame != start;
      second.close();
      AccessibilityNodeInfo root = freshRoot();
      AccessibilityNodeInfo button = findPlayButton(root);
      if (button == null) continue;
      Boolean semanticPlaying = semanticPlaying(button);
      if (moving == playing && (semanticPlaying == null || semanticPlaying == playing)) return;
      if (semanticPlaying != null && semanticPlaying != moving) {
        // A stale picture or a pending pause must not cause a second, opposite toggle.
        SystemClock.sleep(300);
        continue;
      }
      Rect bounds = new Rect();
      button.getBoundsInScreen(bounds);
      tap(bounds.exactCenterX(), bounds.exactCenterY());
      SystemClock.sleep(300);
    }
    fail("Could not verify requested playback state optically: " + playing);
  }

  private static Boolean semanticPlaying(AccessibilityNodeInfo button) {
    String label =
        button.getContentDescription() == null ? "" : button.getContentDescription().toString();
    if (label.matches("(?i)(暂停|Pause|Pause video)")) return true;
    if (label.matches("(?i)(播放|Play|Resume|Play video)")) return false;
    return null;
  }

  private AccessibilityNodeInfo freshRoot() {
    if (Build.VERSION.SDK_INT >= 33) automation.clearCache();
    AccessibilityNodeInfo root = automation.getRootInActiveWindow();
    if (root != null) root.refresh();
    return root;
  }

  private AccessibilityNodeInfo findPlayButton(AccessibilityNodeInfo node) {
    if (node == null || !node.isVisibleToUser()) return null;
    String desc =
        node.getContentDescription() == null ? "" : node.getContentDescription().toString();
    String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
    if (id.endsWith("/player_overlay_play")
        || id.endsWith("/play_pause")
        || desc.matches("(?i)(播放|暂停|Play|Pause|Resume|Play video|Pause video)")) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo found = findPlayButton(node.getChild(i));
      if (found != null) return found;
    }
    return null;
  }

  private Rect ensureSeekBar() throws Exception {
    for (int attempt = 0; attempt < 8; attempt++) {
      AccessibilityNodeInfo root = freshRoot();
      Rect bar = largestSeekBar(root, null);
      if (bar != null) return bar;
      if (attempt == 0 || attempt == 4) {
        try (Capture screen = capture()) {
          tap(screen.bitmap.getWidth() * .5f, screen.bitmap.getHeight() * .5f);
        }
      }
      SystemClock.sleep(250);
    }
    throw new AssertionError(
        "No visible horizontal SeekBar in actual foreground app " + packageName);
  }

  private Rect renewChrome() throws Exception {
    float x, y;
    try (Capture screen = capture()) {
      x = screen.bitmap.getWidth() * .5f;
      y = screen.bitmap.getHeight() * .5f;
    }
    AccessibilityNodeInfo root = freshRoot();
    if (largestSeekBar(root, null) != null) {
      // Avoid a center play button on an app/layout that uses one.
      AccessibilityNodeInfo play = findPlayButton(root);
      if (play != null) {
        Rect bounds = new Rect();
        play.getBoundsInScreen(bounds);
        if (bounds.contains(Math.round(x), Math.round(y))) {
          x *= .7f;
          y *= .8f;
        }
      }
      tap(x, y);
      long hideTapFinished = SystemClock.uptimeMillis();
      long hideDeadline = hideTapFinished + 1000;
      while (largestSeekBar(freshRoot(), null) != null && SystemClock.uptimeMillis() < hideDeadline)
        SystemClock.sleep(80);
      assertNull(
          "Controls did not hide; no measured seek was injected",
          largestSeekBar(freshRoot(), null));
      long remainingTapGap = hideTapFinished + 450 - SystemClock.uptimeMillis();
      if (remainingTapGap > 0) SystemClock.sleep(remainingTapGap);
    }
    tap(x, y);
    chromeShownAt = SystemClock.uptimeMillis();
    SystemClock.sleep(650);
    Rect previous = largestSeekBar(freshRoot(), null);
    if (previous == null) {
      previous = ensureSeekBar();
      chromeShownAt = SystemClock.uptimeMillis();
      SystemClock.sleep(350);
    }
    for (int attempt = 0; attempt < 4; attempt++) {
      SystemClock.sleep(100);
      Rect bar = largestSeekBar(freshRoot(), null);
      if (bar != null && bar.equals(previous)) return bar;
      previous = bar;
    }
    throw new AssertionError("Controls did not settle; no measured seek was injected");
  }

  private Rect largestSeekBar(AccessibilityNodeInfo node, Rect best) {
    if (node == null || !node.isVisibleToUser()) return best;
    if (node.getPackageName() != null && !packageName.contentEquals(node.getPackageName()))
      return best;
    String className = node.getClassName() == null ? "" : node.getClassName().toString();
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if ((className.endsWith("SeekBar") || node.getRangeInfo() != null)
        && bounds.width() > 200
        && bounds.width() > bounds.height() * 4
        && (best == null || bounds.width() > best.width())) best = new Rect(bounds);
    for (int i = 0; i < node.getChildCount(); i++) best = largestSeekBar(node.getChild(i), best);
    return best;
  }

  private void tap(float x, float y) {
    long now = SystemClock.uptimeMillis();
    inject(MotionEvent.ACTION_DOWN, x, y, now, now);
    long untilUp = now + 80 - SystemClock.uptimeMillis();
    if (untilUp > 0) SystemClock.sleep(untilUp);
    inject(MotionEvent.ACTION_UP, x, y, now, SystemClock.uptimeMillis());
  }

  private void inject(int action, float x, float y, long down, long when) {
    inject(action, x, y, down, when, true);
  }

  private void inject(int action, float x, float y, long down, long when, boolean synchronous) {
    MotionEvent event = MotionEvent.obtain(down, when, action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("Input injection failed", automation.injectInputEvent(event, synchronous));
    } finally {
      event.recycle();
    }
  }

  private Capture waitForBarcode(long timeoutMs) throws Exception {
    long deadline = SystemClock.uptimeMillis() + timeoutMs;
    while (SystemClock.uptimeMillis() < deadline) {
      Capture frame = capture();
      if (frame.code != null) return frame;
      frame.close();
      SystemClock.sleep(30);
    }
    try (Capture missing = capture()) {
      save(missing.bitmap, "barcode-unavailable.png");
    }
    throw new AssertionError("No validated generated-video barcode in foreground screenshot");
  }

  private Capture capture() {
    long started = SystemClock.uptimeMillis();
    Bitmap screen = automation.takeScreenshot();
    long ended = SystemClock.uptimeMillis();
    assertNotNull("Foreground screenshot unavailable", screen);
    if (screen.getConfig() == Bitmap.Config.HARDWARE) {
      Bitmap readable = screen.copy(Bitmap.Config.ARGB_8888, false);
      screen.recycle();
      screen = readable;
    }
    assertNotNull(screen);
    long decodeStarted = ended;
    Code code = decode(screen);
    return new Capture(
        screen, code, started, ended, ended - started, SystemClock.uptimeMillis() - decodeStarted);
  }

  private void save(Bitmap bitmap, String name) throws Exception {
    try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
    }
  }

  /**
   * Widest validated main-video code wins over a smaller player preview containing the same code.
   */
  static Code decode(Bitmap screen) {
    int width = screen.getWidth(), height = screen.getHeight();
    int[] row = new int[width];
    ArrayList<Code> candidates = new ArrayList<>();
    float widest = 0;
    for (int y = height / 10; y < height * 9 / 10; y += Math.max(3, height / 70)) {
      screen.getPixels(row, 0, width, 0, y, width, 1);
      List<Run> reds = runs(row, true), blues = runs(row, false);
      for (Run red : reds)
        for (Run blue : blues) {
          float pitch = (blue.center() - red.center()) / 15f;
          float span = pitch * 16;
          if (pitch < 5 || span < width * .3f) continue;
          if (red.width() < pitch * .7f
              || red.width() > pitch * 1.3f
              || blue.width() < pitch * .7f
              || blue.width() > pitch * 1.3f) continue;
          if (bit(row, red.center() - .75f * pitch) != 0
              || bit(row, blue.center() + .75f * pitch) != 0) continue;
          int n = decodeRow(row, red.center(), pitch);
          if (n < 0) continue;
          candidates.add(new Code(n, span, y));
          widest = Math.max(widest, span);
        }
    }
    int[] votes = new int[750], lastRow = new int[750];
    java.util.Arrays.fill(lastRow, -1);
    HashSet<Integer> rows = new HashSet<>();
    for (Code candidate : candidates) {
      if (candidate.span < widest * .95f) continue;
      rows.add(candidate.row);
      if (lastRow[candidate.frame] == candidate.row) continue;
      votes[candidate.frame]++;
      lastRow[candidate.frame] = candidate.row;
    }
    int winner = -1, winningVotes = 0;
    for (int frame = 0; frame < votes.length; frame++) {
      if (votes[frame] > winningVotes) {
        winner = frame;
        winningVotes = votes[frame];
      }
    }
    return winningVotes >= 3 && winningVotes * 2 > rows.size() ? new Code(winner, widest) : null;
  }

  private static int decodeRow(int[] row, float redCenter, float pitch) {
    if (bit(row, redCenter + pitch) != 1) return -1;
    int frame = 0, parity = 0;
    for (int i = 0; i < 12; i++) {
      int value = bit(row, redCenter + pitch * (i + 2));
      if (value < 0) return -1;
      frame |= value << i;
      parity ^= value;
    }
    return frame < 750 && bit(row, redCenter + pitch * 14) == parity ? frame : -1;
  }

  private static int bit(int[] row, float x) {
    int center = Math.round(x);
    if (center < 2 || center >= row.length - 2) return -1;
    int white = 0, black = 0;
    for (int i = center - 1; i <= center + 1; i++) {
      int c = row[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
      if (r > 150 && g > 150 && b > 150) white++;
      if (r < 80 && g < 80 && b < 80) black++;
    }
    return white >= 2 ? 1 : black >= 2 ? 0 : -1;
  }

  private static List<Run> runs(int[] row, boolean red) {
    ArrayList<Run> result = new ArrayList<>();
    int begin = -1;
    for (int x = 0; x <= row.length; x++) {
      int c = x < row.length ? row[x] : 0;
      int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
      boolean matches =
          x < row.length && (red ? r > 150 && g < 100 && b < 100 : b > 150 && r < 100 && g < 130);
      if (matches && begin < 0) begin = x;
      if (!matches && begin >= 0) {
        if (x - begin >= 5) result.add(new Run(begin, x));
        begin = -1;
      }
    }
    return result;
  }

  private Fixture fixture(Context context, String name) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "duration"},
                "_display_name=?",
                new String[] {name},
                null)) {
      assertNotNull(cursor);
      assertEquals(
          "Benchmark fixture name must be unique in the media index", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      long duration = cursor.getLong(1);
      assertTrue("Expected generated 30-second video", duration >= 29500 && duration <= 30500);
      return new Fixture(
          Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0)),
          duration);
    }
  }

  static final class Code {
    final int frame;
    final float span;
    final int row;

    Code(int frame, float span) {
      this(frame, span, -1);
    }

    Code(int frame, float span, int row) {
      this.frame = frame;
      this.span = span;
      this.row = row;
    }
  }

  private static final class Run {
    final int start, end;

    Run(int start, int end) {
      this.start = start;
      this.end = end;
    }

    float center() {
      return (start + end - 1) / 2f;
    }

    int width() {
      return end - start;
    }
  }

  private static final class Fixture {
    final Uri uri;
    final long durationMs;

    Fixture(Uri uri, long durationMs) {
      this.uri = uri;
      this.durationMs = durationMs;
    }
  }

  private static final class Capture implements AutoCloseable {
    boolean retained;
    final Bitmap bitmap;
    final Code code;
    final long captureStarted, captureEnded, captureMs, decodeMs;

    Capture(Bitmap bitmap, Code code, long started, long ended, long captureMs, long decodeMs) {
      this.bitmap = bitmap;
      this.code = code;
      this.captureStarted = started;
      this.captureEnded = ended;
      this.captureMs = captureMs;
      this.decodeMs = decodeMs;
    }

    @Override
    public void close() {
      if (!retained) bitmap.recycle();
    }
  }
}
