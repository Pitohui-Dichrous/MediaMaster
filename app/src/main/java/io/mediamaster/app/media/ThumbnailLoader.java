package io.mediamaster.app.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Five-second thumbnails: visible rows, two decoders, and a 12 MiB memory-only LRU. */
public final class ThumbnailLoader implements AutoCloseable {
  private static final int WIDTH = 384;
  private static final int HEIGHT = 240;
  private static final int MAX_MEMORY_BYTES = 12 * 1024 * 1024;
  private static final int MAX_PENDING = 32;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();
  // View bindings are main-thread-only and never retain a recycled view through their values.
  private final Map<ImageView, Binding> bindings = new WeakHashMap<>();
  private final Map<String, Task> tasks = new LinkedHashMap<>();
  private final LinkedHashMap<String, Task> pending = new LinkedHashMap<>();
  private final LruCache<String, Bitmap> memory =
      new LruCache<String, Bitmap>(MAX_MEMORY_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getAllocationByteCount();
        }
      };
  private final LruCache<String, Boolean> unavailable = new LruCache<>(1024);
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "video-thumbnail");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          });
  private final FrameDecoder decoder;
  private volatile boolean closed;
  private volatile boolean paused;

  interface FrameDecoder {
    Bitmap decode(VideoItem item);
  }

  public ThumbnailLoader(Context context) {
    this(context, null);
  }

  // Injectable decoding keeps cancellation and cache-budget tests deterministic.
  ThumbnailLoader(Context context, FrameDecoder decoder) {
    this.context = context.getApplicationContext();
    this.decoder = decoder;
    workers.execute(
        () -> {
          clearLegacyDiskCache();
          work();
        });
    workers.execute(this::work);
  }

  /** Call on the main thread. Decoding starts only when the bound row is attached. */
  public void bind(VideoItem item, ImageView image) {
    unbind(image);
    if (closed) return;
    Binding binding = new Binding(item, image);
    bindings.put(image, binding);
    image.addOnAttachStateChangeListener(binding);
    if (image.isAttachedToWindow()) request(binding, image);
  }

  /** Clears the row immediately; an in-flight decode cannot repopulate an unbound view. */
  public void unbind(ImageView image) {
    Binding previous = bindings.remove(image);
    if (previous != null) {
      image.removeOnAttachStateChangeListener(previous);
      cancel(previous);
    }
    image.setImageDrawable(null);
  }

  private void request(Binding binding, ImageView image) {
    if (closed || paused || bindings.get(image) != binding || !image.isAttachedToWindow()) return;
    Bitmap immediate = memory.get(binding.key);
    image.setImageBitmap(immediate);
    if (immediate != null || unavailable.get(binding.key) != null) return;
    synchronized (lock) {
      if (closed || paused || binding.task != null) return;
      Task task = tasks.get(binding.key);
      if (task == null) {
        // A single bounded queue serves both workers; fling events cannot accumulate jobs.
        if (pending.size() >= MAX_PENDING) {
          Task oldest = pending.values().iterator().next();
          cancelTaskLocked(oldest);
        }
        task = new Task(binding.item, binding.key);
        tasks.put(task.key, task);
        pending.put(task.key, task);
      }
      task.waiters.add(binding);
      binding.task = task;
      lock.notifyAll();
    }
  }

  private void cancel(Binding binding) {
    synchronized (lock) {
      Task task = binding.task;
      binding.task = null;
      if (task == null) return;
      task.waiters.remove(binding);
      if (task.waiters.isEmpty()) cancelTaskLocked(task);
    }
  }

  private void cancelTaskLocked(Task task) {
    task.canceled = true;
    if (tasks.get(task.key) == task) tasks.remove(task.key);
    if (pending.get(task.key) == task) pending.remove(task.key);
    for (Binding binding : task.waiters) binding.task = null;
    task.waiters.clear();
  }

  private void work() {
    while (!Thread.currentThread().isInterrupted()) {
      Task task;
      synchronized (lock) {
        while (!closed && pending.isEmpty()) {
          try {
            lock.wait();
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        if (closed) return;
        task = pending.values().iterator().next();
        pending.remove(task.key);
      }
      Bitmap bitmap = null;
      try {
        if (!canceled(task))
          bitmap =
              decoder == null
                  ? extract(task.item, () -> canceled(task))
                  : decoder.decode(task.item);
      } catch (Exception | LinkageError | OutOfMemoryError unsupported) {
        Log.d("VideoThumbnails", "Thumbnail unavailable");
      }
      final Bitmap ready = bitmap;
      final ArrayList<Binding> waiting;
      synchronized (lock) {
        if (canceled(task)) {
          if (ready != null) ready.recycle();
          continue;
        }
        if (tasks.get(task.key) == task) tasks.remove(task.key);
        waiting = new ArrayList<>(task.waiters);
        for (Binding binding : waiting) binding.task = null;
        task.waiters.clear();
        if (ready != null) memory.put(task.key, ready);
        else unavailable.put(task.key, Boolean.TRUE);
      }
      if (ready != null)
        main.post(
            () -> {
              if (closed || paused) return;
              for (Binding binding : waiting) {
                ImageView image = binding.image.get();
                if (image != null && bindings.get(image) == binding && image.isAttachedToWindow())
                  image.setImageBitmap(ready);
              }
            });
    }
  }

  private boolean canceled(Task task) {
    return closed || paused || task.canceled || Thread.currentThread().isInterrupted();
  }

  /** Main-thread lifecycle hook: a covered video directory must not compete with playback. */
  public void setPaused(boolean paused) {
    if (closed || this.paused == paused) return;
    this.paused = paused;
    for (Map.Entry<ImageView, Binding> entry : bindings.entrySet()) {
      if (paused) {
        cancel(entry.getValue());
        entry.getKey().setImageDrawable(null);
      } else {
        request(entry.getValue(), entry.getKey());
      }
    }
  }

  // Kept separate from worker scheduling so metadata/codec tests can inspect real frames.
  private Bitmap extract(VideoItem item) {
    return extract(item, () -> closed || Thread.currentThread().isInterrupted());
  }

  private Bitmap extract(VideoItem item, BooleanSupplier canceled) {
    if (canceled.getAsBoolean()) return null;
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    Bitmap bitmap = null;
    try {
      retriever.setDataSource(context, Uri.parse(item.uri));
      long timeMs = item.durationMs > 0 ? Math.min(5000, Math.max(0, item.durationMs - 150)) : 5000;
      if (!canceled.getAsBoolean()) {
        if (Build.VERSION.SDK_INT >= 27) {
          bitmap =
              retriever.getScaledFrameAtTime(
                  timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, WIDTH, HEIGHT);
        } else {
          bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
        }
      }
      if (bitmap == null && !canceled.getAsBoolean())
        bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      if (bitmap != null && (bitmap.getWidth() > WIDTH || bitmap.getHeight() > HEIGHT)) {
        float scale =
            Math.min((float) WIDTH / bitmap.getWidth(), (float) HEIGHT / bitmap.getHeight());
        Bitmap scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)),
                true);
        if (scaled != bitmap) bitmap.recycle();
        bitmap = scaled;
      }
    } catch (Exception | LinkageError | OutOfMemoryError unsupported) {
      Log.d("VideoThumbnails", "Using compatibility decoder for " + item.extension());
    } finally {
      try {
        retriever.release();
      } catch (Exception ignored) {
      }
    }
    if (canceled.getAsBoolean()) {
      if (bitmap != null) bitmap.recycle();
      return null;
    }
    long timeMs = item.durationMs > 0 ? Math.min(5000, Math.max(0, item.durationMs - 150)) : 5000;
    return bitmap != null ? bitmap : VlcFrameExtractor.extractAt(context, item, timeMs, canceled);
  }

  /** Removes only the flat, app-owned JPEG cache used by older releases; never follows links. */
  private void clearLegacyDiskCache() {
    try {
      File cache = context.getCacheDir().getCanonicalFile();
      File old = new File(cache, "video-thumbnails-v1");
      if (!old.getCanonicalFile().equals(old.getAbsoluteFile())) return;
      File[] files = old.listFiles();
      if (files == null) return;
      for (File file : files) {
        if (closed) return;
        if (file.getCanonicalFile().getParentFile().equals(old) && file.isFile()) file.delete();
      }
      old.delete();
    } catch (Exception ignored) {
      // Cache cleanup must never block the video list or prevent playback.
    }
  }

  private static String cacheKey(VideoItem item) {
    return item.uri + '|' + item.modifiedMs + '|' + item.size + "|frame@5000-v3";
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      for (Task task : tasks.values()) task.canceled = true;
      tasks.clear();
      pending.clear();
      lock.notifyAll();
    }
    workers.shutdownNow();
    main.removeCallbacksAndMessages(null);
    for (Map.Entry<ImageView, Binding> entry : bindings.entrySet()) {
      entry.getKey().removeOnAttachStateChangeListener(entry.getValue());
      entry.getKey().setImageDrawable(null);
    }
    bindings.clear();
    memory.evictAll();
    unavailable.evictAll();
  }

  private final class Binding implements View.OnAttachStateChangeListener {
    final VideoItem item;
    final String key;
    final WeakReference<ImageView> image;
    Task task;

    Binding(VideoItem item, ImageView image) {
      this.item = item;
      key = cacheKey(item);
      this.image = new WeakReference<>(image);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
      request(this, (ImageView) view);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
      cancel(this);
      ((ImageView) view).setImageDrawable(null);
    }
  }

  private static final class Task {
    final VideoItem item;
    final String key;
    final ArrayList<Binding> waiters = new ArrayList<>();
    volatile boolean canceled;

    Task(VideoItem item, String key) {
      this.item = item;
      this.key = key;
    }
  }
}
