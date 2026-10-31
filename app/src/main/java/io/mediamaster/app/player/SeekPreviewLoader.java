package io.mediamaster.app.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import io.mediamaster.app.media.VideoItem;
import io.mediamaster.app.media.VlcFrameExtractor;
import java.io.File;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Small, memory-only position previews. A single decoder consumes the latest request rather than
 * queuing every finger movement. The retriever and its file descriptor remain on that worker.
 */
public final class SeekPreviewLoader implements AutoCloseable {
  public interface Callback {
    /**
     * Called on the main thread through the latest request's callback. The time identifies the
     * decoded preview; during continuous motion the next frame catches up without blocking input.
     */
    void onFrame(Bitmap bitmap, long positionMs);
  }

  private static final int WIDTH = 256;
  private static final int HEIGHT = 144;
  private static final long FRAME_BUCKET_MS = 200;
  private static final long IDLE_RELEASE_MS = 2000;
  private static final long MOTION_WARMUP_MS = 120;
  private static final long MOTION_RECENCY_MS = 48;
  private static final long MAX_MOVING_FRAME_AGE_MS = 600;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();
  private final ScheduledThreadPoolExecutor worker =
      new ScheduledThreadPoolExecutor(
          1,
          runnable -> {
            Thread thread = new Thread(runnable, "seek-preview");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          });
  private final LruCache<String, Bitmap> memory =
      new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
      };

  // Guarded by lock. One scheduled/running pump and one replaceable pending request at most.
  private long generation;
  private long sequence;
  private long lastStarted;
  private long lastCompleted;
  private final PreviewCadence cadence = new PreviewCadence();
  private String requestedSourceKey;
  private ScheduledFuture<?> idleRelease;
  private boolean scheduled;
  private boolean closed;
  private Request pending;
  private Request latest;
  private long motionStarted;
  private long deliveredSequence;

  // Worker-only state, including all native retriever lifecycle calls.
  private MediaMetadataRetriever retriever;
  private String sourceKey;
  private boolean androidUnavailable;
  private boolean preferSync;

  public SeekPreviewLoader(Context context) {
    this.context = context.getApplicationContext();
    worker.setRemoveOnCancelPolicy(true);
  }

  public void request(VideoItem item, long positionMs, Callback callback) {
    request(item, positionMs, callback, true);
  }

  /**
   * Interactive fallback for when the main player's texture is unavailable. Never starts the
   * separate VLC software decoder while the playback decoder is scrubbing.
   */
  public void requestInteractive(VideoItem item, long positionMs, Callback callback) {
    request(item, positionMs, callback, false);
  }

  private void request(
      VideoItem item, long positionMs, Callback callback, boolean allowCompatibilityDecoder) {
    if (item == null || callback == null) return;
    long position = Math.max(0, positionMs);
    if (item.durationMs > 0) position = Math.min(position, Math.max(0, item.durationMs - 1));
    synchronized (lock) {
      if (closed) return;
      cancelIdleReleaseLocked();
      long now = SystemClock.elapsedRealtime();
      String source = sourceKey(item);
      if (!source.equals(requestedSourceKey)) {
        requestedSourceKey = source;
        cadence.reset();
        lastStarted = 0;
        lastCompleted = 0;
      }
      if (latest == null
          || !latest.sourceKey.equals(source)
          || (latest.allowCompatibilityDecoder && !allowCompatibilityDecoder)) {
        // Switching away and back must not resurrect a frame from the earlier video session.
        generation++;
        motionStarted = now;
        deliveredSequence = 0;
      }
      latest =
          new Request(
              item, position, callback, generation, ++sequence, now, allowCompatibilityDecoder);
      pending = latest;
      if (!scheduled) scheduleLocked();
    }
  }

  /**
   * Cancels pending callbacks immediately. Briefly retains the same-source decoder for another
   * drag; idle cleanup runs on the worker, never on the UI thread or underneath a native decode.
   */
  public void reset() {
    synchronized (lock) {
      if (closed) return;
      generation++;
      sequence++;
      pending = null;
      latest = null;
      motionStarted = 0;
      deliveredSequence = 0;
      main.removeCallbacksAndMessages(null);
      cancelIdleReleaseLocked();
      long idleGeneration = generation;
      idleRelease =
          worker.schedule(
              () -> {
                synchronized (lock) {
                  if (closed || generation != idleGeneration || latest != null) return;
                  idleRelease = null;
                }
                releaseRetriever();
                memory.evictAll();
              },
              IDLE_RELEASE_MS,
              TimeUnit.MILLISECONDS);
    }
  }

  private void scheduleLocked() {
    scheduled = true;
    worker.schedule(
        this::pump,
        cadence.delayMs(SystemClock.elapsedRealtime(), lastStarted, lastCompleted),
        TimeUnit.MILLISECONDS);
  }

  private void pump() {
    Request request;
    synchronized (lock) {
      request = pending;
      pending = null;
      lastStarted = SystemClock.elapsedRealtime();
    }
    try {
      if (request == null || canceled(request)) return;
      Bitmap bitmap = memory.get(request.cacheKey);
      if (bitmap == null) {
        long decodeStarted = SystemClock.elapsedRealtime();
        bitmap = decode(request);
        long finished = SystemClock.elapsedRealtime();
        synchronized (lock) {
          if (!canceledLocked(request)) {
            cadence.onDecode(finished - decodeStarted);
            lastCompleted = finished;
          }
        }
        if (bitmap != null) {
          synchronized (lock) {
            if (closed || request.generation != generation) {
              bitmap.recycle();
              return;
            }
          }
          // Decoding can be slower than finger events. Retain completed frames, then consume the
          // one latest pending target; never restart native decode for every MotionEvent.
          memory.put(request.cacheKey, bitmap);
        }
      }
      final Bitmap ready = bitmap;
      if (!canceled(request))
        main.post(
            () -> {
              synchronized (lock) {
                if (canceledLocked(request) || request.sequence <= deliveredSequence) return;
                Request newest = latest;
                boolean exact = newest.sequence == request.sequence;
                boolean nearby = Math.abs(newest.positionMs - request.frameMs) <= FRAME_BUCKET_MS;
                long now = SystemClock.elapsedRealtime();
                boolean moving =
                    newest.requestedAt - motionStarted >= MOTION_WARMUP_MS
                        && now - newest.requestedAt <= MOTION_RECENCY_MS
                        && now - request.requestedAt <= MAX_MOVING_FRAME_AGE_MS;
                // Strict sequence equality would starve a 50 ms decoder under 16 ms move events.
                // Near-target frames can serve the current position; while moving quickly, show
                // the newest completed frame and immediately decode the newest pending target.
                // A settled, distant target waits for its own frame instead of flashing old work.
                if (!exact && (ready == null || (!nearby && !moving))) return;
                deliveredSequence = request.sequence;
                newest.callback.onFrame(
                    ready, exact || nearby ? newest.positionMs : request.positionMs);
              }
            });
    } finally {
      synchronized (lock) {
        scheduled = false;
        if (!closed && pending != null) scheduleLocked();
      }
    }
  }

  private Bitmap decode(Request request) {
    if (!request.sourceKey.equals(sourceKey)) {
      releaseRetriever();
      // Retain at most one video's previews. A file change must not keep stale native state.
      memory.evictAll();
      sourceKey = request.sourceKey;
      androidUnavailable = false;
      // Previews are nearby pictures, not the final playback position. Avoid decoding an entire
      // long GOP on each gesture's first request and competing with the playback decoder.
      preferSync = true;
      try {
        retriever = new MediaMetadataRetriever();
        if (!request.item.path.isEmpty() && new File(request.item.path).canRead())
          retriever.setDataSource(request.item.path);
        else retriever.setDataSource(context, Uri.parse(request.item.uri));
      } catch (Exception | LinkageError | OutOfMemoryError unsupported) {
        releaseRetriever();
        sourceKey = request.sourceKey;
        androidUnavailable = true;
      }
    }
    if (canceled(request)) return null;
    Bitmap bitmap = null;
    if (!androidUnavailable && retriever != null) {
      long start = SystemClock.elapsedRealtime();
      int option =
          preferSync
              ? MediaMetadataRetriever.OPTION_CLOSEST_SYNC
              : MediaMetadataRetriever.OPTION_CLOSEST;
      try {
        if (Build.VERSION.SDK_INT >= 27)
          bitmap = retriever.getScaledFrameAtTime(request.frameMs * 1000, option, WIDTH, HEIGHT);
        else bitmap = retriever.getFrameAtTime(request.frameMs * 1000, option);
        if (bitmap == null && !canceled(request) && !preferSync)
          bitmap =
              Build.VERSION.SDK_INT >= 27
                  ? retriever.getScaledFrameAtTime(
                      request.frameMs * 1000,
                      MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                      WIDTH,
                      HEIGHT)
                  : retriever.getFrameAtTime(
                      request.frameMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      } catch (Exception | LinkageError | OutOfMemoryError unsupported) {
        androidUnavailable = true;
      }
      if (SystemClock.elapsedRealtime() - start > 180) preferSync = true;
      // Containers without an Android decoder should go directly to VLC on later requests.
      if (bitmap == null && !canceled(request)) androidUnavailable = true;
    }
    if (bitmap != null) {
      try {
        return fit(bitmap);
      } catch (OutOfMemoryError exhausted) {
        bitmap.recycle();
        return null;
      }
    }
    if (canceled(request) || !request.allowCompatibilityDecoder) return null;
    return VlcFrameExtractor.extractAt(
        context, request.item, request.frameMs, () -> canceled(request));
  }

  private static Bitmap fit(Bitmap bitmap) {
    if (bitmap.getWidth() <= WIDTH && bitmap.getHeight() <= HEIGHT) return bitmap;
    float scale = Math.min((float) WIDTH / bitmap.getWidth(), (float) HEIGHT / bitmap.getHeight());
    Bitmap scaled =
        Bitmap.createScaledBitmap(
            bitmap,
            Math.max(1, Math.round(bitmap.getWidth() * scale)),
            Math.max(1, Math.round(bitmap.getHeight() * scale)),
            true);
    if (scaled != bitmap) bitmap.recycle();
    return scaled;
  }

  private boolean canceled(Request request) {
    synchronized (lock) {
      return canceledLocked(request) || Thread.currentThread().isInterrupted();
    }
  }

  private boolean canceledLocked(Request request) {
    return closed
        || request.generation != generation
        || latest == null
        || !request.sourceKey.equals(latest.sourceKey);
  }

  private void releaseRetriever() {
    if (retriever != null) {
      try {
        retriever.release();
      } catch (Exception ignored) {
      }
      retriever = null;
    }
    sourceKey = null;
  }

  private void cancelIdleReleaseLocked() {
    if (idleRelease != null) {
      idleRelease.cancel(false);
      idleRelease = null;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      generation++;
      sequence++;
      pending = null;
      latest = null;
      cancelIdleReleaseLocked();
      main.removeCallbacksAndMessages(null);
      // Do not release a retriever from underneath its native decode. Cleanup runs immediately
      // after the canceled decode returns; neither close nor a screen rotation blocks the UI.
      worker.execute(
          () -> {
            releaseRetriever();
            memory.evictAll();
          });
      worker.shutdown();
    }
  }

  private static final class Request {
    final VideoItem item;
    final long positionMs;
    final long frameMs;
    final Callback callback;
    final long generation;
    final long sequence;
    final long requestedAt;
    final String sourceKey;
    final String cacheKey;
    final boolean allowCompatibilityDecoder;

    Request(
        VideoItem item,
        long positionMs,
        Callback callback,
        long generation,
        long sequence,
        long requestedAt,
        boolean allowCompatibilityDecoder) {
      this.item = item;
      this.positionMs = positionMs;
      this.frameMs = positionMs / FRAME_BUCKET_MS * FRAME_BUCKET_MS;
      this.callback = callback;
      this.generation = generation;
      this.sequence = sequence;
      this.requestedAt = requestedAt;
      this.allowCompatibilityDecoder = allowCompatibilityDecoder;
      sourceKey = sourceKey(item);
      cacheKey = sourceKey + '|' + frameMs;
    }
  }

  private static String sourceKey(VideoItem item) {
    return item.uri + '|' + item.path + '|' + item.modifiedMs + '|' + item.size;
  }
}
