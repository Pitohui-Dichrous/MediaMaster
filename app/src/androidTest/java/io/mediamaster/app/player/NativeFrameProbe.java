package io.mediamaster.app.player;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Device-test-only evidence tools. PixelCopy/TextureView read native decoder surfaces, whereas
 * View.draw() can produce a false black success for SurfaceView video. These methods do not run in
 * production, and evidence is written only when a test explicitly requests it.
 */
@UnstableApi
final class NativeFrameProbe {
  private NativeFrameProbe() {}

  static Bitmap capturePlayerSurface(Instrumentation instrumentation, PlayerView playerView)
      throws Exception {
    View videoSurface = onMain(instrumentation, playerView::getVideoSurfaceView);
    if (videoSurface == null) throw new AssertionError("PlayerView has no native video surface");
    return captureSurface(instrumentation, videoSurface);
  }

  static Bitmap captureSurface(Instrumentation instrumentation, View videoSurface)
      throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Capture native frames from the instrumentation thread");
    int[] dimensions =
        onMain(
            instrumentation, () -> new int[] {videoSurface.getWidth(), videoSurface.getHeight()});
    if (dimensions[0] <= 0 || dimensions[1] <= 0)
      throw new AssertionError("Native video surface has not been laid out");
    float scale = Math.min(1f, 384f / Math.max(dimensions[0], dimensions[1]));
    int width = Math.max(1, Math.round(dimensions[0] * scale));
    int height = Math.max(1, Math.round(dimensions[1] * scale));
    if (videoSurface instanceof TextureView) {
      Bitmap bitmap =
          onMain(instrumentation, () -> ((TextureView) videoSurface).getBitmap(width, height));
      if (bitmap == null) throw new AssertionError("TextureView has not received a decoded frame");
      return bitmap;
    }
    if (!(videoSurface instanceof SurfaceView))
      throw new AssertionError("Unsupported native surface: " + videoSurface.getClass().getName());
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    AtomicInteger status = new AtomicInteger(-1);
    CountDownLatch copied = new CountDownLatch(1);
    try {
      onMain(
          instrumentation,
          () -> {
            PixelCopy.request(
                (SurfaceView) videoSurface,
                bitmap,
                result -> {
                  status.set(result);
                  copied.countDown();
                },
                new Handler(Looper.getMainLooper()));
            return null;
          });
      if (!copied.await(5, TimeUnit.SECONDS))
        throw new AssertionError("Timed out copying the native decoder surface");
      if (status.get() != PixelCopy.SUCCESS)
        throw new AssertionError(
            "PixelCopy did not receive a decoded frame: status=" + status.get());
      return bitmap;
    } catch (Exception | AssertionError error) {
      // PixelCopy can still own its destination after a timeout. Do not recycle in that case.
      if (copied.getCount() == 0) bitmap.recycle();
      throw error;
    }
  }

  static Bitmap captureCompositedScreen(Instrumentation instrumentation) {
    Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
    if (screen == null) throw new AssertionError("Android did not provide a composited screenshot");
    if (screen.getConfig() != Bitmap.Config.HARDWARE) return screen;
    Bitmap readable = screen.copy(Bitmap.Config.ARGB_8888, false);
    screen.recycle();
    if (readable == null) throw new AssertionError("Cannot read the composited screenshot pixels");
    return readable;
  }

  static Bitmap referenceFirstFrame(Context context, VideoItem item) throws IOException {
    MediaMetadataRetriever decoder = new MediaMetadataRetriever();
    try {
      decoder.setDataSource(context, Uri.parse(item.uri));
      Bitmap frame =
          Build.VERSION.SDK_INT >= 27
              ? decoder.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, 256, 256)
              : decoder.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST);
      if (frame == null)
        throw new AssertionError("Fixture has no decodable first frame: " + item.name);
      return frame;
    } finally {
      decoder.release();
    }
  }

  /**
   * Removes only the centered letterbox implied by the actual video track's FIT geometry. This does
   * not inspect pixel colors or alter production video layout, so a missing/black frame still fails
   * the image-content assertions.
   */
  static Bitmap cropExpectedFitContent(Bitmap viewport, int videoWidth, int videoHeight) {
    if (videoWidth <= 0 || videoHeight <= 0)
      throw new IllegalArgumentException("Video track dimensions must be positive");
    double fit =
        Math.min(
            viewport.getWidth() / (double) videoWidth, viewport.getHeight() / (double) videoHeight);
    int width = Math.max(1, Math.min(viewport.getWidth(), (int) Math.round(videoWidth * fit)));
    int height = Math.max(1, Math.min(viewport.getHeight(), (int) Math.round(videoHeight * fit)));
    return Bitmap.createBitmap(
        viewport,
        (viewport.getWidth() - width) / 2,
        (viewport.getHeight() - height) / 2,
        width,
        height);
  }

  /**
   * Average absolute channel difference on normalized pixels; zero means identical image content.
   */
  static double averageRgbDistance(Bitmap reference, Bitmap actual) {
    Bitmap left = Bitmap.createScaledBitmap(reference, 64, 64, true);
    Bitmap right = Bitmap.createScaledBitmap(actual, 64, 64, true);
    try {
      int[] first = new int[4096], second = new int[4096];
      left.getPixels(first, 0, 64, 0, 0, 64, 64);
      right.getPixels(second, 0, 64, 0, 0, 64, 64);
      long total = 0;
      for (int i = 0; i < first.length; i++) {
        total += Math.abs(((first[i] >> 16) & 255) - ((second[i] >> 16) & 255));
        total += Math.abs(((first[i] >> 8) & 255) - ((second[i] >> 8) & 255));
        total += Math.abs((first[i] & 255) - (second[i] & 255));
      }
      return total / (4096d * 3);
    } finally {
      if (left != reference) left.recycle();
      if (right != actual) right.recycle();
    }
  }

  /** Excludes a fake all-black placeholder while allowing ordinary black letterboxing. */
  static double nonBlackFraction(Bitmap frame) {
    Bitmap sample = Bitmap.createScaledBitmap(frame, 64, 64, true);
    try {
      int[] pixels = new int[4096];
      sample.getPixels(pixels, 0, 64, 0, 0, 64, 64);
      int visible = 0;
      for (int pixel : pixels)
        if (Math.max((pixel >> 16) & 255, Math.max((pixel >> 8) & 255, pixel & 255)) > 18)
          visible++;
      return visible / 4096d;
    } finally {
      if (sample != frame) sample.recycle();
    }
  }

  static File writeEvidence(Instrumentation instrumentation, String name, Bitmap frame)
      throws IOException {
    if (!name.matches("[A-Za-z0-9_-]+"))
      throw new IllegalArgumentException("Invalid evidence name");
    File parent = instrumentation.getTargetContext().getExternalFilesDir(null);
    if (parent == null) throw new IOException("Target application external files are unavailable");
    File directory = new File(parent, "test-only/native-paging-proof");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create test-only evidence directory");
    File destination = new File(directory, name + ".png");
    try (FileOutputStream stream = new FileOutputStream(destination)) {
      if (!frame.compress(Bitmap.CompressFormat.PNG, 100, stream))
        throw new IOException("Cannot encode native video evidence");
    }
    return destination;
  }

  private static <T> T onMain(Instrumentation instrumentation, Callable<T> action)
      throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
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
    if (error instanceof Exception) throw (Exception) error;
    if (error instanceof AssertionError) throw (AssertionError) error;
    if (error != null) throw new RuntimeException(error);
    return result.get();
  }
}
