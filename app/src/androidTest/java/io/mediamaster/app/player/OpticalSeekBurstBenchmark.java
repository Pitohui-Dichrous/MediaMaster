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
import io.mediamaster.app.player.OpticalSeekBenchmark.Code;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual foreground frame sampling while an independent input thread sends six rapid seek taps. */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4.class)
public final class OpticalSeekBurstBenchmark {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();
  private String packageName;
  private File output;
  private long chromeShownAt;

  @Test
  public void measureRapidSeekBurst() throws Exception {
    Context context = instrumentation.getTargetContext();
    Bundle args = InstrumentationRegistry.getArguments();
    packageName = args.getString("benchPackage", "");
    assertTrue(
        "Only installed VLC or the app under test is allowed",
        packageName.equals("org.videolan.vlc") || packageName.equals(context.getPackageName()));
    String name = args.getString("benchFile", "");
    assertTrue(
        "Only generated benchmark fixtures may be opened",
        name.matches("(?:mm_seek_|SeekBench_)[A-Za-z0-9_-]+\\.mp4"));
    String state = args.getString("benchState", "playing");
    assertTrue(state.equals("paused") || state.equals("playing"));
    boolean playing = state.equals("playing");
    String run = args.getString("benchRun", "burst-" + SystemClock.uptimeMillis());
    assertTrue(run.matches("[A-Za-z0-9_-]{1,100}"));
    long timeoutMs = Long.parseLong(args.getString("benchTimeoutMs", "10000"));
    assertTrue(timeoutMs >= 1000 && timeoutMs <= 30000);
    String calibrationArg = args.getString("benchCalibrate", "true");
    assertTrue(
        "benchCalibrate must be true or false",
        calibrationArg.equals("true") || calibrationArg.equals("false"));
    boolean calibrate = Boolean.parseBoolean(calibrationArg);
    int toleranceMs =
        Integer.parseInt(args.getString("benchToleranceMs", calibrate ? "120" : "1000"));
    assertTrue(toleranceMs >= 100 && toleranceMs <= 2000);
    String[] fractions = args.getString("benchTargets", ".2,.7,.35,.8,.25,.65").split(",");
    assertEquals("A burst contains exactly six separately released taps", 6, fractions.length);
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
      assertTrue("Never modify VLC preferences", overrideEngine);
      assertTrue(Integer.parseInt(engineArg) >= 0 && Integer.parseInt(engineArg) <= 2);
    }
    AccessibilityServiceInfo accessibility = automation.getServiceInfo();
    int originalFlags = accessibility.flags;
    accessibility.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    automation.setServiceInfo(accessibility);
    JSONObject result =
        new JSONObject()
            .put("run", run)
            .put("package", packageName)
            .put("file", name)
            .put("state", state)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("durationMs", fixture.durationMs)
            .put("protocolDurationMs", SeekTouchCalibration.PROTOCOL_DURATION_MS)
            .put("physicalTouchCalibration", calibrate)
            .put("frameRate", 25)
            .put(
                "nativeDecoderChoiceRequested",
                overrideNativeDecoder ? Integer.parseInt(nativeDecoderArg) : JSONObject.NULL)
            .put("scheduledTapSpacingMs", 120)
            .put("scheduledPressMs", 20)
            .put("timeoutMs", timeoutMs)
            .put("targetToleranceMs", toleranceMs)
            .put(
                "engineChoiceRequested",
                overrideEngine ? Integer.parseInt(engineArg) : JSONObject.NULL)
            .put(
                "measurement",
                "Six taps are scheduled by an independent input thread. Actual event times and"
                    + " foreground main-video frames are recorded; screenshot intervals bound"
                    + " observations.")
            .put(
                "accuracyNote",
                "Fixed fixture targets use independently calibrated physical touch positions. Input"
                    + " quantization error is separate from decoded PTS. A changed frame after the"
                    + " last tap may belong to an earlier queued seek; only the near-last-target"
                    + " and final stable/consistent suffix are attributed to the last request."
                    + " Screenshot polling can miss intermediate pictures and cannot prove zero"
                    + " latency.");
    Thread sender = null;
    Tap[] taps = new Tap[6];
    ArrayList<Sample> samples = new ArrayList<>();
    ArrayList<CaptureIssue> invalidCaptures = new ArrayList<>();
    int invalidCaptureCount = 0;
    CaptureIssue retainedInvalidCapture = null;
    AtomicReference<Throwable> injectionFailure = new AtomicReference<>();
    long origin = 0;
    Bitmap beforeProof = null, invalidAfterLastDownProof = null;
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
      // A resumed paused surface can retain an old picture. Both apps first prove real playback.
      setPlaybackState(true);
      setPlaybackState(playing);
      result
          .put("actualEngine", actualEngine(context))
          .put("nativeRuntime", NativeBenchmarkEvidence.capture(instrumentation, packageName));
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
      setPlaybackState(playing);
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
      float mainSpan = before.code.span;
      beforeProof = before.bitmap;
      assertTrue("SeekBar disappeared during premeasurement setup", barStillVisible);
      for (int i = 0; i < taps.length; i++) {
        double fraction = Double.parseDouble(fractions[i]);
        assertTrue(fraction > .05 && fraction < .95);
        long requestedMs = SeekTouchCalibration.targetMs(fraction);
        SeekTouchCalibration.Point touch =
            calibration != null
                ? calibration.point(bar, requestedMs)
                : SeekTouchCalibration.uncalibrated(
                    context, packageName, bar, requestedMs, fixture.durationMs);
        taps[i] =
            new Tap(fraction, requestedMs, touch.x, bar.exactCenterY(), touch.inferredRequestMs);
      }
      result
          .put("beforeFrameMs", beforeMs)
          .put("beforeBarcodeSpan", mainSpan)
          .put("seekBar", bar.flattenToString())
          .put("seekBarBefore", bar.flattenToString())
          .put("seekBarAfter", JSONObject.NULL)
          .put("seekBarAfterNote", "Not sampled; no accessibility polling added during the burst");
      result.put("nativeRuntime", NativeBenchmarkEvidence.capture(instrumentation, packageName));
      origin = SystemClock.uptimeMillis() + 150;
      result.put("chromeAgeAtFirstPlannedDownMs", origin - chromeShownAt);
      final long start = origin;
      sender =
          new Thread(
              () -> {
                Tap active = null;
                try {
                  for (int i = 0; i < taps.length; i++) {
                    waitUntil(start + i * 120);
                    active = taps[i];
                    active.down = SystemClock.uptimeMillis();
                    inject(
                        MotionEvent.ACTION_DOWN,
                        active.x,
                        active.y,
                        active.down,
                        active.down,
                        false);
                    waitUntil(active.down + 20);
                    active.up = SystemClock.uptimeMillis();
                    inject(
                        MotionEvent.ACTION_UP, active.x, active.y, active.down, active.up, false);
                    active.dispatchEnd = SystemClock.uptimeMillis();
                    active = null;
                  }
                } catch (Throwable failure) {
                  injectionFailure.set(failure);
                } finally {
                  if (active != null && active.down > 0) {
                    try {
                      inject(
                          MotionEvent.ACTION_CANCEL,
                          active.x,
                          active.y,
                          active.down,
                          SystemClock.uptimeMillis(),
                          false);
                    } catch (Throwable cancelFailure) {
                      if (injectionFailure.get() == null) injectionFailure.set(cancelFailure);
                    }
                  }
                }
              },
              "optical-seek-input");
      sender.start();
      boolean lastTargetSeen = false;
      int captureCount = 0;
      long captureTotal = 0, maxCapture = 0, maxDecode = 0;
      while (SystemClock.uptimeMillis() - origin < timeoutMs + 600) {
        if (injectionFailure.get() != null)
          throw new AssertionError("Input injection failed", injectionFailure.get());
        try (Capture frame = capture()) {
          captureCount++;
          captureTotal += frame.captureMs;
          maxCapture = Math.max(maxCapture, frame.captureMs);
          maxDecode = Math.max(maxDecode, frame.decodeMs);
          if (frame.code != null && frame.code.span >= mainSpan * .9f) {
            Sample sample =
                new Sample(
                    frame.captureStarted - origin,
                    frame.captureEnded - origin,
                    frame.code.frame * 40L,
                    frame.code.span);
            samples.add(sample);
            Tap last = taps[taps.length - 1];
            if (last.down > 0 && frame.captureEnded >= last.down) {
              long afterLast = frame.captureEnded - last.down;
              long distance = Math.abs(sample.frameMs - last.targetMs);
              if (playing)
                distance = Math.min(distance, Math.abs(sample.frameMs - last.targetMs - afterLast));
              if (distance <= toleranceMs) lastTargetSeen = true;
              if (!sender.isAlive() && lastTargetSeen && afterLast >= 1000) break;
            }
          } else {
            invalidCaptureCount++;
            CaptureIssue issue =
                new CaptureIssue(
                    frame.captureStarted - origin,
                    frame.captureEnded - origin,
                    frame.code == null ? "noCode" : "spanBelowMain",
                    frame.code == null ? null : frame.code.span);
            // Keep timestamps bounded, plus exactly one existing bitmap after the finalDOWN.
            // Ownership transfers without copying; compression and disk writes happen in finally.
            if (invalidCaptures.size() < 300) invalidCaptures.add(issue);
            Tap last = taps[taps.length - 1];
            if (invalidAfterLastDownProof == null
                && last.down > 0
                && frame.captureStarted >= last.down) {
              invalidAfterLastDownProof = frame.bitmap;
              retainedInvalidCapture = issue;
              frame.retained = true;
            }
          }
        }
      }
      sender.join(2000);
      assertFalse("Input sender did not finish", sender.isAlive());
      if (injectionFailure.get() != null)
        throw new AssertionError("Input injection failed", injectionFailure.get());
      result
          .put("captureCount", captureCount)
          .put("validCaptureCount", samples.size())
          .put("meanCaptureMs", captureCount == 0 ? 0 : (double) captureTotal / captureCount)
          .put("maxCaptureMs", maxCapture)
          .put("maxDecodeMs", maxDecode)
          .put("status", lastTargetSeen ? "observed" : "last-target-unobserved");
      summarize(result, taps, samples, origin, playing, toleranceMs, fixture.durationMs);
      result.put(
          "nativeRuntimeAfter", NativeBenchmarkEvidence.capture(instrumentation, packageName));
      try (Capture proof = capture()) {
        save(proof.bitmap, "after-burst.png");
      }
      assertTrue(
          "No verified picture near the last request; partial timeline is saved", lastTargetSeen);
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
      if (sender != null && sender.isAlive()) {
        sender.interrupt();
        sender.join(2000);
      }
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

      accessibility.flags = originalFlags;
      automation.setServiceInfo(accessibility);
      try {
        if (beforeProof != null) save(beforeProof, "before-burst-down.png");
        if (invalidAfterLastDownProof != null) {
          save(invalidAfterLastDownProof, "invalid-after-last-down.png");
          result.put(
              "invalidAfterLastDownProof",
              retainedInvalidCapture.toJson().put("file", "invalid-after-last-down.png"));
        }
      } finally {
        if (beforeProof != null) beforeProof.recycle();
        if (invalidAfterLastDownProof != null) invalidAfterLastDownProof.recycle();
      }
      JSONArray invalidTimeline = new JSONArray();
      for (CaptureIssue issue : invalidCaptures) invalidTimeline.put(issue.toJson());
      result
          .put("taps", tapsJson(taps, origin))
          .put("timeline", samplesJson(samples))
          .put("invalidCaptures", invalidTimeline)
          .put("invalidCaptureCount", invalidCaptureCount)
          .put("invalidCaptureTimestampLimit", 300)
          .put("invalidCaptureTimestampsOmitted", invalidCaptureCount - invalidCaptures.size());
      write(result);
      Log.i("OpticalSeekBurst", "Results: " + new File(output, "results.json"));
    }
  }

  private static final class CaptureIssue {
    final long start, end;
    final String reason;
    final Float span;

    CaptureIssue(long start, long end, String reason, Float span) {
      this.start = start;
      this.end = end;
      this.reason = reason;
      this.span = span;
    }

    JSONObject toJson() throws Exception {
      return new JSONObject()
          .put("captureStartMs", start)
          .put("captureEndMs", end)
          .put("reason", reason)
          .put("span", span == null ? JSONObject.NULL : span);
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

  private static void waitUntil(long uptime) throws InterruptedException {
    long delay;
    while ((delay = uptime - SystemClock.uptimeMillis()) > 0) Thread.sleep(delay);
  }

  private static void summarize(
      JSONObject result,
      Tap[] taps,
      ArrayList<Sample> samples,
      long origin,
      boolean playing,
      int tolerance,
      long durationMs)
      throws Exception {
    Tap last = taps[taps.length - 1];
    long lastDown = last.down - origin;
    result.put("lastDownMs", lastDown).put("lastTargetMs", last.targetMs);
    Sample beforeLast = null;
    for (Sample sample : samples) if (sample.end <= lastDown) beforeLast = sample;
    long nonTargetStart = lastDown;
    boolean nearSeen = false, changeSeen = false;
    long nonChangeStart = lastDown;
    for (Sample sample : samples) {
      if (sample.end < lastDown) continue;
      long elapsed = sample.end - lastDown;
      long distance = Math.abs(sample.frameMs - last.targetMs);
      if (playing)
        distance = Math.min(distance, Math.abs(sample.frameMs - last.targetMs - elapsed));
      if (!nearSeen && distance <= tolerance) {
        nearSeen = true;
        result
            .put("nearLastTargetLowerMs", Math.max(0, nonTargetStart - lastDown))
            .put("nearLastTargetUpperMs", elapsed)
            .put("nearLastTargetFrameMs", sample.frameMs);
      } else if (!nearSeen) nonTargetStart = Math.max(lastDown, sample.start);
      if (beforeLast != null && !changeSeen) {
        long expected = beforeLast.frameMs + (playing ? sample.end - beforeLast.end : 0);
        long delta = Math.abs((sample.frameMs - expected) % durationMs);
        boolean changed = Math.min(delta, durationMs - delta) > 750;
        if (changed) {
          changeSeen = true;
          result
              .put("firstChangeAfterLastLowerMs", Math.max(0, nonChangeStart - lastDown))
              .put("firstChangeAfterLastUpperMs", elapsed)
              .put("firstChangeAfterLastFrameMs", sample.frameMs);
        } else nonChangeStart = Math.max(lastDown, sample.start);
      }
    }
    if (samples.isEmpty()) return;
    int end = samples.size() - 1, first = end;
    Sample landed = samples.get(end);
    while (first > 0) {
      Sample earlier = samples.get(first - 1);
      if (earlier.end < lastDown) break;
      long predicted = earlier.frameMs + (playing ? landed.end - earlier.end : 0);
      long delta = Math.abs((landed.frameMs - predicted) % durationMs);
      if (Math.min(delta, durationMs - delta) > (playing ? 120 : 0)) break;
      first--;
    }
    String prefix = playing ? "finalConsistentTrajectory" : "finalStable";
    result
        .put(prefix + "FrameMs", landed.frameMs)
        .put(prefix + "DurationMs", landed.end - samples.get(first).start)
        .put(
            prefix + "FirstLowerMs",
            first == 0 ? 0 : Math.max(0, samples.get(first - 1).start - lastDown))
        .put(prefix + "FirstUpperMs", Math.max(0, samples.get(first).end - lastDown))
        .put("lastObservedAfterLastMs", landed.end - lastDown);
  }

  private static JSONArray tapsJson(Tap[] taps, long origin) throws Exception {
    JSONArray array = new JSONArray();
    for (int i = 0; i < taps.length; i++) {
      Tap tap = taps[i];
      if (tap == null) continue;
      array.put(
          new JSONObject()
              .put("index", i)
              .put("fraction", tap.fraction)
              .put("requestedMs", tap.targetMs)
              .put("inferredInputRequestMs", tap.inferredRequestMs)
              .put("inputQuantizationErrorMs", tap.inferredRequestMs - tap.targetMs)
              .put("x", tap.x)
              .put("y", tap.y)
              .put("plannedDownMs", i * 120)
              .put("downMs", tap.down == 0 ? JSONObject.NULL : tap.down - origin)
              .put("upMs", tap.up == 0 ? JSONObject.NULL : tap.up - origin)
              .put("downUpMs", tap.up == 0 ? JSONObject.NULL : tap.up - tap.down)
              .put(
                  "inputDispatchMs",
                  tap.dispatchEnd == 0 ? JSONObject.NULL : tap.dispatchEnd - tap.down));
    }
    return array;
  }

  private static JSONArray samplesJson(ArrayList<Sample> samples) throws Exception {
    JSONArray array = new JSONArray();
    for (Sample sample : samples)
      array.put(
          new JSONObject()
              .put("captureStartMs", sample.start)
              .put("captureEndMs", sample.end)
              .put("frameMs", sample.frameMs)
              .put("span", sample.span));
    return array;
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
            new FileOutputStream(new File(output, "frames.csv")), StandardCharsets.UTF_8)) {
      writer.write("captureStartMs,captureEndMs,frameMs,span\n");
      JSONArray timeline = result.getJSONArray("timeline");
      for (int i = 0; i < timeline.length(); i++) {
        JSONObject sample = timeline.getJSONObject(i);
        writer.write(
            sample.getLong("captureStartMs")
                + ","
                + sample.getLong("captureEndMs")
                + ","
                + sample.getLong("frameMs")
                + ","
                + sample.getDouble("span")
                + "\n");
      }
    }
  }

  private static final class Tap {
    final double fraction;
    final long targetMs;
    final double inferredRequestMs;
    final float x, y;
    volatile long down, up, dispatchEnd;

    Tap(double fraction, long targetMs, float x, float y, double inferredRequestMs) {
      this.fraction = fraction;
      this.targetMs = targetMs;
      this.inferredRequestMs = inferredRequestMs;
      this.x = x;
      this.y = y;
    }
  }

  private static final class Sample {
    final long start, end, frameMs;
    final float span;

    Sample(long start, long end, long frameMs, float span) {
      this.start = start;
      this.end = end;
      this.frameMs = frameMs;
      this.span = span;
    }
  }

  // Foreground-app helpers are intentionally local while the isolated-tap benchmark is running.
  // The validated optical decoder itself is shared, so both measurements use the same pixel oracle.
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
    Code code = OpticalSeekBenchmark.decode(screen);
    return new Capture(
        screen, code, started, ended, ended - started, SystemClock.uptimeMillis() - decodeStarted);
  }

  private void save(Bitmap bitmap, String name) throws Exception {
    try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
    }
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
