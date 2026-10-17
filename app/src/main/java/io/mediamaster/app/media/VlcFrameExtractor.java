package io.mediamaster.app.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/** Bounded, silent software decoding for containers the Android thumbnail reader cannot open. */
public final class VlcFrameExtractor {
  private static final ReentrantLock DECODER_LOCK = new ReentrantLock();

  private VlcFrameExtractor() {}

  static Bitmap extract(Context context, VideoItem item) {
    long target = item.durationMs > 0 ? Math.min(5000, Math.max(0, item.durationMs - 300)) : 5000;
    try {
      DECODER_LOCK.lockInterruptibly();
      try {
        return extractFrame(context, item, target, () -> false, 384, 240, 2800);
      } finally {
        DECODER_LOCK.unlock();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Decodes a small arbitrary-position preview without audio or disk files. Call off the UI thread.
   * A new gesture position cancels lock waits and frame polling; a native operation in progress
   * finishes before its own objects are released. Nothing touches the visible playback decoder.
   */
  public static Bitmap extractAt(
      Context context, VideoItem item, long targetMs, BooleanSupplier canceled) {
    BooleanSupplier cancellation = canceled != null ? canceled : () -> false;
    long deadline = SystemClock.elapsedRealtime() + 2800;
    boolean locked = false;
    try {
      while (!isCanceled(cancellation) && SystemClock.elapsedRealtime() < deadline) {
        if (DECODER_LOCK.tryLock(24, TimeUnit.MILLISECONDS)) {
          locked = true;
          if (isCanceled(cancellation)) return null;
          long target = Math.max(0, targetMs);
          if (item.durationMs > 0) target = Math.min(target, Math.max(0, item.durationMs - 50));
          return extractFrame(
              context,
              item,
              target,
              cancellation,
              256,
              144,
              Math.min(1300, Math.max(0, deadline - SystemClock.elapsedRealtime())));
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      if (locked) DECODER_LOCK.unlock();
    }
    return null;
  }

  private static boolean isCanceled(BooleanSupplier canceled) {
    return Thread.currentThread().isInterrupted() || canceled.getAsBoolean();
  }

  private static Bitmap extractFrame(
      Context context,
      VideoItem item,
      long target,
      BooleanSupplier canceled,
      int maxWidth,
      int maxHeight,
      long timeoutMs) {
    if (isCanceled(canceled) || timeoutMs <= 0) return null;
    long deadline = SystemClock.elapsedRealtime() + timeoutMs;
    LibVLC engine = null;
    Media media = null;
    MediaPlayer player = null;
    ParcelFileDescriptor descriptor = null;
    ImageReader reader = null;
    try {
      engine =
          new LibVLC(
              context,
              new ArrayList<>(
                  Arrays.asList(
                      "--quiet",
                      "--no-audio",
                      "--no-spu",
                      "--no-video-title-show",
                      "--vout=android-display",
                      "--avcodec-hw=none",
                      "--android-display-chroma=RGBA")));
      if (isCanceled(canceled)) return null;
      if (!item.path.isEmpty() && new File(item.path).canRead()) {
        media = new Media(engine, item.path);
      } else {
        descriptor = context.getContentResolver().openFileDescriptor(Uri.parse(item.uri), "r");
        if (descriptor == null) return null;
        media = new Media(engine, descriptor.getFileDescriptor());
      }
      media.setHWDecoderEnabled(false, false);
      media.addOption(":no-audio");
      media.addOption(":no-spu");
      media.addOption(":start-time=" + String.format(Locale.ROOT, "%.3f", target / 1000.0));
      int width = maxWidth, height = maxHeight;
      if (item.width > 0 && item.height > 0) {
        float scale = Math.min((float) maxWidth / item.width, (float) maxHeight / item.height);
        width = Math.max(2, Math.round(item.width * scale));
        height = Math.max(2, Math.round(item.height * scale));
      }
      reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
      player = new MediaPlayer(engine);
      player.setMedia(media);
      player.getVLCVout().setVideoSurface(reader.getSurface(), null);
      player.getVLCVout().setWindowSize(width, height);
      // android-display requires a layout callback, even for a surface without a View.
      player
          .getVLCVout()
          .attachViews(
              (output, videoWidth, videoHeight, visibleWidth, visibleHeight, sarNum, sarDen) -> {});
      if (isCanceled(canceled) || SystemClock.elapsedRealtime() >= deadline) return null;
      player.play();
      int frames = 0;
      while (!isCanceled(canceled) && SystemClock.elapsedRealtime() < deadline) {
        try (Image image = reader.acquireLatestImage()) {
          if (image != null && ++frames >= 2 && player.getTime() >= Math.max(0, target - 250)) {
            Bitmap bitmap = copyImage(image, maxWidth, maxHeight);
            if (bitmap != null) {
              if (!isCanceled(canceled)) return bitmap;
              bitmap.recycle();
              return null;
            }
          }
        }
        Thread.sleep(24);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError | OutOfMemoryError unsupported) {
      if (!isCanceled(canceled))
        Log.w(
            "VideoThumbnails",
            "Compatibility frame extraction failed for " + item.extension(),
            unsupported);
    } finally {
      if (player != null) {
        player.setEventListener(null);
        try {
          player.stop();
        } catch (Exception ignored) {
        }
        try {
          player.getVLCVout().detachViews();
        } catch (Exception ignored) {
        }
        try {
          player.release();
        } catch (Exception ignored) {
        }
      }
      if (media != null)
        try {
          media.release();
        } catch (Exception ignored) {
        }
      if (descriptor != null)
        try {
          descriptor.close();
        } catch (Exception ignored) {
        }
      if (reader != null)
        try {
          reader.close();
        } catch (Exception ignored) {
        }
      if (engine != null)
        try {
          engine.release();
        } catch (Exception ignored) {
        }
    }
    return null;
  }

  private static Bitmap copyImage(Image image, int maxWidth, int maxHeight) {
    Image.Plane[] planes = image.getPlanes();
    if (planes.length == 0 || planes[0].getPixelStride() < 4) return null;
    Image.Plane plane = planes[0];
    ByteBuffer data = plane.getBuffer();
    Rect crop = image.getCropRect();
    int width = crop.width(), height = crop.height();
    int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
    int[] pixels = new int[width * height];
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int offset = (y + crop.top) * rowStride + (x + crop.left) * pixelStride;
        int r = data.get(offset) & 255;
        int g = data.get(offset + 1) & 255;
        int b = data.get(offset + 2) & 255;
        pixels[y * width + x] = 0xff000000 | r << 16 | g << 8 | b;
      }
    Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    if (width > maxWidth || height > maxHeight) {
      float scale = Math.min((float) maxWidth / width, (float) maxHeight / height);
      Bitmap scaled =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.max(1, Math.round(width * scale)),
              Math.max(1, Math.round(height * scale)),
              true);
      if (scaled != bitmap) bitmap.recycle();
      bitmap = scaled;
    }
    return bitmap;
  }
}
