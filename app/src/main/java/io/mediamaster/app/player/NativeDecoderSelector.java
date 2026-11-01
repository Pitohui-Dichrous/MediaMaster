package io.mediamaster.app.player;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
  private static final long INDEX_PROBE_BUDGET_MS = 150;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final NativeDecoderPolicy.Metadata UNKNOWN =
      new NativeDecoderPolicy.Metadata(null, 0, 0, 0, -1, false);
  // These two maps and all Request/Probe subscriber lists are accessed only on the main thread.
  private static final LinkedHashMap<String, NativeDecoderPolicy.Metadata> CACHE =
      new LinkedHashMap<>(16, .75f, true);
  private static final Map<String, Probe> IN_FLIGHT = new HashMap<>();
  private static Decision deliveringDecision;
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
    int mode = preference(context);
    if (item == null) {
      callback.accept(NativeDecoderPolicy.useSoftware(mode, avoidSoftware, null, null));
      return;
    }
    String key = cacheKey(item);
    NativeDecoderPolicy.Metadata cached = CACHE.get(key);
    if (cached != null) {
      deliver(context, key, mode, avoidSoftware, cached, callback);
      return;
    }
    Probe probe = IN_FLIGHT.get(key);
    boolean submit = probe == null;
    if (submit) {
      probe = new Probe(context, item, key);
      IN_FLIGHT.put(key, probe);
    }
    Request request = new Request(probe, mode, avoidSoftware, callback);
    probe.requests.add(request);
    MAIN.postAtTime(request, request.deadline);
    if (submit) {
      try {
        WORKER.execute(probe);
      } catch (RejectedExecutionException full) {
        IN_FLIGHT.remove(key);
        // Queue pressure is not an unreadable-media result; a future request may try again.
        request.finish(null);
      }
    }
  }

  static int preference(Context context) {
    return NativeDecoderPolicy.normalizeMode(
        context
            .getSharedPreferences("player", 0)
            .getInt("native_decoder_mode", NativeDecoderPolicy.AUTO));
  }

  private static String cacheKey(VideoItem item) {
    return item.id + '|' + item.uri + '|' + item.path + '|' + item.size + '|' + item.modifiedMs;
  }

  /** Main-thread lookup only. A timed-out selection must not consume a just-arrived late result. */
  private static NativeDecoderPolicy.Metadata selectedMetadata(VideoItem item) {
    if (item == null) return null;
    String key = cacheKey(item);
    if (deliveringDecision != null && deliveringDecision.key.equals(key))
      return deliveringDecision.metadata;
    return CACHE.get(key);
  }

  static int cachedSoftwareThreads(Context context, VideoItem item) {
    NativeDecoderPolicy.Metadata metadata = selectedMetadata(item);
    if (!NativeDecoderPolicy.demandingLongGop(metadata)) return 0;
    return NativeDecoderPolicy.softwareThreads(
        metadata, resources(context), Runtime.getRuntime().availableProcessors());
  }

  static boolean cachedLongGop(VideoItem item) {
    return NativeDecoderPolicy.demandingLongGop(selectedMetadata(item));
  }

  private static void deliver(
      Context context,
      String key,
      int mode,
      boolean avoidSoftware,
      NativeDecoderPolicy.Metadata metadata,
      Consumer<Boolean> callback) {
    Decision previous = deliveringDecision;
    deliveringDecision = new Decision(key, metadata);
    try {
      callback.accept(
          NativeDecoderPolicy.useSoftware(
              mode,
              avoidSoftware,
              metadata,
              mode == NativeDecoderPolicy.AUTO && !avoidSoftware && metadata != null
                  ? resources(context)
                  : null));
    } finally {
      deliveringDecision = previous;
    }
  }

  private static final class Decision {
    final String key;
    final NativeDecoderPolicy.Metadata metadata;

    Decision(String key, NativeDecoderPolicy.Metadata metadata) {
      this.key = key;
      this.metadata = metadata;
    }
  }

  private static NativeDecoderPolicy.Resources resources(Context context) {
    long available = 0, threshold = 0;
    boolean lowMemory = true, thermalPressure = false;
    try {
      ActivityManager manager = context.getSystemService(ActivityManager.class);
      if (manager != null) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        available = memory.availMem;
        threshold = memory.threshold;
        lowMemory = memory.lowMemory;
      }
    } catch (RuntimeException unavailable) {
      // Unknown memory headroom keeps AUTO conservative; explicit software ignores this guard.
    }
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        PowerManager power = context.getSystemService(PowerManager.class);
        thermalPressure =
            power != null && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE;
      } catch (RuntimeException unavailable) {
        // Thermal reporting is optional; no device identity is used as a substitute.
      }
    }
    return new NativeDecoderPolicy.Resources(available, threshold, lowMemory, thermalPressure);
  }

  private static final class Request implements Runnable {
    final Probe probe;
    final int mode;
    final boolean avoidSoftware;
    final long deadline = SystemClock.uptimeMillis() + DECISION_TIMEOUT_MS;
    Consumer<Boolean> callback;

    Request(Probe probe, int mode, boolean avoidSoftware, Consumer<Boolean> callback) {
      this.probe = probe;
      this.mode = mode;
      this.avoidSoftware = avoidSoftware;
      this.callback = callback;
    }

    @Override
    public void run() {
      finish(null);
    }

    void finish(NativeDecoderPolicy.Metadata metadata) {
      if (callback == null) return;
      MAIN.removeCallbacks(this);
      probe.requests.remove(this);
      Consumer<Boolean> result = callback;
      callback = null;
      deliver(probe.context, probe.key, mode, avoidSoftware, metadata, result);
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
              request.finish(SystemClock.uptimeMillis() < request.deadline ? metadata : null);
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
      int videoTrack = -1;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("video/")) continue;
        if (video != null) return UNKNOWN; // Do not guess which video track LibVLC will choose.
        video = format;
        videoTrack = i;
      }
      if (video == null) return UNKNOWN;
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
          video.containsKey(MediaFormat.KEY_PROFILE) ? integer(video, MediaFormat.KEY_PROFILE) : -1,
          hdr,
          // Slow header I/O must not silently consume the separate index-inspection budget.
          // The original UI decision deadline and once-only callback remain unchanged.
          syncGapMs(
              extractor, videoTrack, video, SystemClock.uptimeMillis() + INDEX_PROBE_BUDGET_MS));
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

  /** At most three index locations, without reading samples or opening any decoder. */
  private static long syncGapMs(
      MediaExtractor extractor, int videoTrack, MediaFormat format, long deadline) {
    long largestUs = 0;
    try {
      if (!format.containsKey(MediaFormat.KEY_DURATION)) return 0;
      long durationUs = format.getLong(MediaFormat.KEY_DURATION);
      if (durationUs <= 1) return 0;
      extractor.selectTrack(videoTrack);
      long[] positions = {durationUs / 5, durationUs / 2, durationUs - durationUs / 5};
      for (long position : positions) {
        if (SystemClock.uptimeMillis() >= deadline) break;
        extractor.seekTo(position, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long previous = extractor.getSampleTime();
        boolean previousSync = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        // The final GOP may have no following sync sample. A valid earlier sync point already
        // establishes this much dependency distance without pretending to know the full interval.
        if (previousSync && previous >= 0 && previous <= position)
          largestUs = Math.max(largestUs, position - previous);
        if (SystemClock.uptimeMillis() >= deadline) break;
        // Offset by one microsecond so an exact index hit can still reveal its following gap.
        extractor.seekTo(Math.min(durationUs - 1, position + 1), MediaExtractor.SEEK_TO_NEXT_SYNC);
        long next = extractor.getSampleTime();
        boolean nextSync = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        if (previousSync && nextSync && previous >= 0 && next > previous && next <= durationUs)
          largestUs = Math.max(largestUs, next - previous);
      }
    } catch (RuntimeException unavailableIndex) {
      // Optional index probing must not discard already-readable codec/size/rate information.
    }
    return largestUs / 1000;
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
