package io.mediamaster.app.player;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Header-only selection for requested pages. Never scans the library or opens a decoder. */
final class NativeDecoderSelector {
  private static final int MAX_ENTRIES = 128;
  private static final long DECISION_TIMEOUT_MS = 150;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final NativeDecoderPolicy.Metadata UNKNOWN =
      new NativeDecoderPolicy.Metadata(null, 0, 0, 0, 0, false);
  // These two maps and all Request/Probe subscriber lists are accessed only on the main thread.
  private static final LinkedHashMap<String, NativeDecoderPolicy.Metadata> CACHE =
      new LinkedHashMap<>(16, .75f, true);
  private static final Map<String, Probe> IN_FLIGHT = new HashMap<>();
  private static final ThreadPoolExecutor WORKER =
      new ThreadPoolExecutor(
          1,
          1,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          runnable -> {
            Thread thread = new Thread(runnable, "native-media-headers");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /**
   * Delivers exactly one decision on main: true for software, false for hardware-preferred. The
   * caller must check its page generation before using it. A late header result is cached but never
   * switches an already selected player. Timeout is a UI decision deadline, not an I/O
   * interruption.
   */
  static void resolve(
      Context context, VideoItem item, boolean avoidSoftware, Consumer<Boolean> onMainSoftware) {
    Context app = context.getApplicationContext();
    Runnable resolve = () -> resolveOnMain(app, item, avoidSoftware, onMainSoftware);
    if (Looper.myLooper() == Looper.getMainLooper()) resolve.run();
    else MAIN.post(resolve);
  }

  private static void resolveOnMain(
      Context context, VideoItem item, boolean avoidSoftware, Consumer<Boolean> callback) {
    int mode = context.getSharedPreferences("player", 0).getInt("native_decoder_mode", 0);
    if (mode < NativeDecoderPolicy.AUTO || mode > NativeDecoderPolicy.SOFTWARE) {
      mode = NativeDecoderPolicy.AUTO;
    }
    if (avoidSoftware
        || mode != NativeDecoderPolicy.AUTO
        || !NativeDecoderPolicy.validatedDevice("reference-device", Build.VERSION.SDK_INT)) {
      callback.accept(
          NativeDecoderPolicy.useSoftware(
              mode, avoidSoftware, "reference-device", Build.VERSION.SDK_INT, null));
      return;
    }
    if (item == null) {
      callback.accept(false);
      return;
    }
    String key =
        ("api-" + Build.VERSION.SDK_INT)
            + '|'
            + item.id
            + '|'
            + item.uri
            + '|'
            + item.path
            + '|'
            + item.size
            + '|'
            + item.modifiedMs;
    NativeDecoderPolicy.Metadata cached = CACHE.get(key);
    if (cached != null) {
      callback.accept(eligible(cached));
      return;
    }
    Probe probe = IN_FLIGHT.get(key);
    boolean submit = probe == null;
    if (submit) {
      probe = new Probe(context, item, key);
      IN_FLIGHT.put(key, probe);
    }
    Request request = new Request(probe, callback);
    probe.requests.add(request);
    MAIN.postAtTime(request, request.deadline);
    if (submit) {
      try {
        WORKER.execute(probe);
      } catch (RejectedExecutionException full) {
        IN_FLIGHT.remove(key);
        // Queue pressure is not an unreadable-media result; a future request may try again.
        request.finish(false);
      }
    }
  }

  private static boolean eligible(NativeDecoderPolicy.Metadata metadata) {
    return NativeDecoderPolicy.useSoftware(
        NativeDecoderPolicy.AUTO, false, "reference-device", Build.VERSION.SDK_INT, metadata);
  }

  private static final class Request implements Runnable {
    final Probe probe;
    final long deadline = SystemClock.uptimeMillis() + DECISION_TIMEOUT_MS;
    Consumer<Boolean> callback;

    Request(Probe probe, Consumer<Boolean> callback) {
      this.probe = probe;
      this.callback = callback;
    }

    @Override
    public void run() {
      finish(false);
    }

    void finish(boolean software) {
      if (callback == null) return;
      MAIN.removeCallbacks(this);
      probe.requests.remove(this);
      Consumer<Boolean> result = callback;
      callback = null;
      result.accept(software);
    }
  }

  private static final class Probe implements Runnable {
    final Context context;
    final VideoItem item;
    final String key;
    final ArrayList<Request> requests = new ArrayList<>();

    Probe(Context context, VideoItem item, String key) {
      this.context = context;
      this.item = item;
      this.key = key;
    }

    @Override
    public void run() {
      NativeDecoderPolicy.Metadata metadata = readHeaders(context, item);
      MAIN.post(
          () -> {
            IN_FLIGHT.remove(key);
            CACHE.put(key, metadata);
            while (CACHE.size() > MAX_ENTRIES) CACHE.remove(CACHE.keySet().iterator().next());
            for (Request request : new ArrayList<>(requests)) {
              request.finish(SystemClock.uptimeMillis() < request.deadline && eligible(metadata));
            }
          });
    }
  }

  private static NativeDecoderPolicy.Metadata readHeaders(Context context, VideoItem item) {
    MediaExtractor extractor = null;
    try {
      Uri uri = item.uri.isEmpty() ? Uri.fromFile(new File(item.path)) : Uri.parse(item.uri);
      if (!"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme())) return UNKNOWN;
      extractor = new MediaExtractor();
      extractor.setDataSource(context, uri, null);
      MediaFormat video = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("video/")) continue;
        if (video != null) return UNKNOWN; // Do not guess which video track LibVLC will choose.
        video = format;
      }
      if (video == null || !"video/avc".equals(video.getString(MediaFormat.KEY_MIME)))
        return UNKNOWN;
      int transfer = integer(video, MediaFormat.KEY_COLOR_TRANSFER);
      boolean hdr =
          transfer == MediaFormat.COLOR_TRANSFER_ST2084
              || transfer == MediaFormat.COLOR_TRANSFER_HLG
              || video.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)
              || video.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO);
      return new NativeDecoderPolicy.Metadata(
          video.getString(MediaFormat.KEY_MIME),
          integer(video, MediaFormat.KEY_WIDTH),
          integer(video, MediaFormat.KEY_HEIGHT),
          frameRate(video),
          integer(video, MediaFormat.KEY_PROFILE),
          hdr);
    } catch (Exception unreadable) {
      return UNKNOWN;
    } finally {
      if (extractor != null) {
        try {
          extractor.release();
        } catch (RuntimeException ignored) {
          // Failed header probing must not stop the bounded worker from handling later pages.
        }
      }
    }
  }

  private static int integer(MediaFormat format, String key) {
    return format.containsKey(key) ? format.getInteger(key) : 0;
  }

  private static double frameRate(MediaFormat format) {
    if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return 0;
    try {
      return format.getInteger(MediaFormat.KEY_FRAME_RATE);
    } catch (ClassCastException floatingPoint) {
      return format.getFloat(MediaFormat.KEY_FRAME_RATE);
    }
  }

  private NativeDecoderSelector() {}
}
