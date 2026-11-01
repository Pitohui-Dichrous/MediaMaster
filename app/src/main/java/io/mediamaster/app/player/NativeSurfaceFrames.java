package io.mediamaster.app.player;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.RequiresApi;

/** Main-thread surface bookkeeping. Copies stay bounded and never create a second decoder. */
final class NativeSurfaceFrames {
  interface Listener {
    void onSurfaceAvailable();

    void onSurfaceUnavailable();

    void onFrameAvailable();
  }

  static final class Preview {
    final Bitmap bitmap;
    final long approximatePositionMs;

    Preview(Bitmap bitmap, long approximatePositionMs) {
      this.bitmap = bitmap;
      this.approximatePositionMs = approximatePositionMs;
    }
  }

  // Kept separate from VideoPage's handler: an in-flight copy always owns its bitmap until
  // callback.
  private final Handler callbacks = new Handler(Looper.getMainLooper());
  private final Listener listener;
  private SurfaceView view;
  private long binding, surfaceEpoch, readyRequest, previewSession, watchSerial;
  private long frameSequence, readyAfterSequence, previewAfterSequence, copiedPreviewSequence;
  private int surfaceWidth, surfaceHeight;
  private boolean available, ready, firstBufferAllowed, previewEnabled, watchPending, copyInFlight;
  private Preview completedPreview;

  private final SurfaceHolder.Callback holderCallback =
      new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
          if (view != null && holder == view.getHolder()) created();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
          if (view == null || holder != view.getHolder()) return;
          boolean resized =
              surfaceWidth > 0
                  && surfaceHeight > 0
                  && (surfaceWidth != width || surfaceHeight != height);
          surfaceWidth = width;
          surfaceHeight = height;
          if (resized && available) {
            beforeSeek(true);
            listener.onSurfaceUnavailable();
            listener.onSurfaceAvailable();
          }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
          if (view == null || holder != view.getHolder()) return;
          invalidateSurface();
          listener.onSurfaceUnavailable();
        }
      };

  NativeSurfaceFrames(Listener listener) {
    this.listener = listener;
  }

  boolean isBoundTo(SurfaceView surface) {
    return view == surface;
  }

  void bind(SurfaceView surface) {
    if (view == surface) return;
    unbind();
    view = surface;
    if (surface == null) return;
    surface.getHolder().addCallback(holderCallback);
    if (surface.getHolder().getSurface().isValid()) created();
  }

  void unbind() {
    binding++;
    if (view != null) view.getHolder().removeCallback(holderCallback);
    view = null;
    previewEnabled = false;
    invalidateSurface();
    // Do not clear copyInFlight: a previous binding's PixelCopy still owns its destination.
  }

  private void created() {
    invalidateSurface();
    available = true;
    firstBufferAllowed = true;
    listener.onSurfaceAvailable();
  }

  private void invalidateSurface() {
    surfaceEpoch++;
    readyRequest++;
    previewSession++;
    watchSerial++;
    available = ready = firstBufferAllowed = watchPending = false;
    surfaceWidth = surfaceHeight = 0;
    frameSequence = readyAfterSequence = previewAfterSequence = copiedPreviewSequence = 0;
    discardCompletedPreview();
  }

  boolean hasSurface() {
    return available && view != null && view.getHolder().getSurface().isValid();
  }

  boolean isReady() {
    return hasSurface() && ready;
  }

  /** Fresh output marker for seek backpressure; this is not the media presentation timestamp. */
  long frameSequence() {
    return frameSequence;
  }

  /** Shares the single existing watch with previews and first-frame readiness. */
  void observeNextFrame() {
    armNextFrame();
  }

  /** Existing buffers cannot satisfy a rewind/re-prime; wait for fresh surface activity. */
  void requireFreshFrame() {
    readyRequest++;
    ready = false;
    firstBufferAllowed = false;
    readyAfterSequence = frameSequence;
    armNextFrame();
  }

  void beginPreview() {
    previewSession++;
    previewEnabled = true;
    previewAfterSequence = copiedPreviewSequence = frameSequence;
    discardCompletedPreview();
  }

  void endPreview() {
    previewSession++;
    previewEnabled = false;
    discardCompletedPreview();
  }

  /** Call immediately before submitting a native seek, never from the playback clock callback. */
  void beforeSeek(boolean reprime) {
    // A previous frame's transaction may already be committed while its main-thread callback is
    // still queued. It cannot acknowledge a new seek whose native clock is assigned immediately.
    // Give each submitted seek its own watch; late callbacks keep their old serial and are ignored.
    watchSerial++;
    watchPending = false;
    previewSession++;
    previewAfterSequence = copiedPreviewSequence = frameSequence;
    discardCompletedPreview();
    if (reprime) requireFreshFrame();
    else if (previewEnabled) armNextFrame();
  }

  void requestReadyProbe() {
    if (!hasSurface() || ready || copyInFlight) return;
    if (!firstBufferAllowed && frameSequence <= readyAfterSequence) {
      armNextFrame();
      return;
    }
    requestCopy(false, 0);
  }

  Preview takePreview() {
    Preview result = completedPreview;
    completedPreview = null;
    return result;
  }

  void requestPreview(long approximatePositionMs) {
    if (!previewEnabled || !isReady() || copyInFlight || completedPreview != null) return;
    if (frameSequence <= Math.max(previewAfterSequence, copiedPreviewSequence)) {
      armNextFrame();
      return;
    }
    requestCopy(true, approximatePositionMs);
  }

  private void requestCopy(boolean preview, long approximatePositionMs) {
    SurfaceView surface = view;
    if (surface == null || copyInFlight) return;
    int width = preview ? surface.getWidth() : 8;
    int height = preview ? surface.getHeight() : 8;
    if (width <= 0 || height <= 0) return;
    if (preview) {
      float scale = Math.min(1f, Math.min(256f / width, 144f / height));
      width = Math.max(1, Math.round(width * scale));
      height = Math.max(1, Math.round(height * scale));
    }
    final Bitmap bitmap;
    try {
      bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    } catch (OutOfMemoryError unavailable) {
      return;
    }
    final long copyBinding = binding, copySurface = surfaceEpoch, copyReady = readyRequest;
    final long copySession = previewSession, copySequence = frameSequence;
    copyInFlight = true;
    try {
      PixelCopy.request(
          surface,
          bitmap,
          result -> {
            copyInFlight = false;
            boolean current =
                view == surface
                    && binding == copyBinding
                    && surfaceEpoch == copySurface
                    && hasSurface();
            if (result != PixelCopy.SUCCESS
                || !current
                || (preview
                    ? !previewEnabled || previewSession != copySession
                    : readyRequest != copyReady)) {
              bitmap.recycle();
              return;
            }
            if (preview) {
              copiedPreviewSequence = copySequence;
              discardCompletedPreview();
              completedPreview = new Preview(bitmap, approximatePositionMs);
            } else {
              bitmap.recycle();
              ready = true;
              if (firstBufferAllowed) frameSequence++;
              firstBufferAllowed = false;
              listener.onFrameAvailable();
            }
          },
          callbacks);
    } catch (RuntimeException unavailable) {
      copyInFlight = false;
      bitmap.recycle();
    }
  }

  /** A committed buffer is freshness evidence only; this API does not expose media PTS. */
  private void armNextFrame() {
    if (!hasSurface() || watchPending || Build.VERSION.SDK_INT < 34) return;
    SurfaceView surface = view;
    final long watchedBinding = binding, watchedSurface = surfaceEpoch, watch = ++watchSerial;
    watchPending = true;
    try {
      Api34.watchNextFrame(
          surface,
          callbacks,
          () -> {
            if (view != surface
                || binding != watchedBinding
                || surfaceEpoch != watchedSurface
                || watchSerial != watch) return;
            watchPending = false;
            frameSequence++;
            listener.onFrameAvailable();
          });
    } catch (RuntimeException unavailable) {
      watchPending = false;
    }
  }

  private void discardCompletedPreview() {
    if (completedPreview != null) {
      completedPreview.bitmap.recycle();
      completedPreview = null;
    }
  }

  @RequiresApi(34)
  private static final class Api34 {
    static void watchNextFrame(SurfaceView surface, Handler handler, Runnable callback) {
      SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
      try {
        transaction.addTransactionCommittedListener(
            command -> handler.post(command), callback::run);
        // The system owns the accepted transaction. Closing it here would invalidate that transfer.
        surface.applyTransactionToFrame(transaction);
      } catch (RuntimeException error) {
        transaction.close();
        throw error;
      }
    }
  }
}
