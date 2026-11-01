package io.mediamaster.app.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ScrubbingModeParameters;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.ui.PlayerView;
import io.mediamaster.app.R;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

/** A permanently attached page with its own decoder and retained video surface. */
@UnstableApi
final class VideoPage extends FrameLayout {
  interface Listener {
    void onChanged(VideoPage page);

    void onFirstFrame(VideoPage page);

    void onEnded(VideoPage page);

    void onFailed(VideoPage page);

    void onVideoSize(VideoPage page, int width, int height);
  }

  final PlayerView exoView;
  VLCVideoLayout vlcView;
  ExoPlayer exo;
  MediaPlayer vlc;
  int index = -1, prepareCount;
  volatile long generation;
  long pendingSeek;
  private boolean pendingSeekSet;
  boolean firstFrameReady, failed, buffering, active, playRequested;
  VideoItem item;

  private static final AudioAttributes MOVIE_AUDIO =
      new AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
          .build();
  private final Supplier<LibVLC> vlcSupplier;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final TextView errorLabel;
  private ParcelFileDescriptor descriptor;
  private NativePlayerRetirement.Lease nativeLease;
  private Runnable frameProbe;
  private Bitmap probeBitmap;
  private final NativeSurfaceFrames nativeSurfaceFrames;
  private boolean nativeSurfacePlayback, nativeSurfaceReattachPosted;
  private boolean nativeSurfacePauseRestore;
  private long nativeSurfacePauseTarget;
  private boolean single, foreground, audible, warmingVlc, ended, vlcRunningRequested;
  private boolean nativeDucked;
  private float speed = 1;
  private float appliedNativeRate = Float.NaN;
  private long vlcTextureBaseline, warmupDeadline;
  private long vlcPreviousTime = -1, vlcSeekFenceTarget = -1, vlcSeekFenceDeadline;
  private boolean vlcNearEndArmed;
  private static final long SCRUB_INTERVAL_MS = 80;
  private static final long FIRST_SCRUB_DELAY_MS = 50;
  private static final long SCRUB_TOLERANCE_MS = 250;
  private final Runnable dispatchScrub = this::dispatchScrub;
  private final NativeSeekQueue nativeSeekQueue = new NativeSeekQueue();
  private final Runnable nativeSeekProbe = this::probeNativeSeek;
  private static final long NATIVE_SEEK_WATCHDOG_MS = 2000;
  private static final long PREFERRED_SOFTWARE_NO_PROGRESS_MS = 10000;
  private final NativeDecodeProgress nativeDecodeProgress = new NativeDecodeProgress();
  private long nextNativeProgressStatsAt;
  private long nativeSeekFrameBaseline, nativeSeekTextureBaseline, nativeSeekDeadline;
  private long nativeSeekSubmittedAt;
  private long nativeSeekClockStepMs = 33;
  private float nativeSeekClockRate = 1;
  private boolean nativeSeekAwaitingPause;
  private boolean scrubbing, scrubPending, triedExo, triedExoHardware, triedVlc;
  private boolean rememberVlcFailureOnExoFrame;
  private boolean nativeSoftware,
      triedNativeHardware,
      triedNativeSoftware,
      rememberSoftwareFailureOnHardwareFrame;
  private int decoderPreference = NativeDecoderPolicy.AUTO;
  private boolean forceNativeEngine;
  private long nativeSelectionRequest;
  // User intent outlives an engine replacement and remains valid while header selection is pending.
  private long explicitSeekSerial, explicitSeekTarget;
  private final SoftwareDecodeHealthPolicy nativeHealth = new SoftwareDecodeHealthPolicy();
  private final Runnable nativeHealthProbe = this::sampleNativeHealth;
  private boolean nativeHealthScheduled;
  private long scrubOrigin, scrubTarget, scrubStartedAt, lastScrubDispatch, scrubTextureBaseline;
  private long capturedTextureTimestamp, scrubFrameBaseline;
  private long scrubSessionFrameBaseline, scrubSessionTextureBaseline;
  private final long[] scrubSubmittedTargets = new long[8];
  private int scrubSubmittedCount;
  long lastScrubPreviewPositionMs = -1;
  private volatile long engineFrameGeneration;
  volatile long lastRenderedPositionUs = C.TIME_UNSET;
  volatile long lastRenderedFrameSequence;
  private volatile long lastFrameReleaseTimeNs;
  private static final ScrubbingModeParameters EXO_SCRUB_PARAMETERS =
      new ScrubbingModeParameters.Builder().setFractionalSeekTolerance(null, null).build();
  volatile boolean exoHardwareConfigured;
  volatile boolean exoSoftwareConfigured;
  private long exoCodecGeneration;
  private volatile ExoExactHold exoExactHold;
  private Runnable exoExactTimeout;
  private Runnable exoOutputProbe;
  private ExoNoOutputWatchdog exoOutputWatchdog;
  private boolean exoOutputProbeScheduled;
  private long exoOutputSettledSeekSerial;

  private static final class ExoExactHold {
    final ExoPlayer source;
    final long pageToken, frameToken, targetUs, frameBaseline;
    // Main also checks the barrier before accepting READY/ENDED as optimization completion.
    volatile boolean armed;
    // These two fields are owned only by the playback thread.
    boolean eligibleFrameSeen, completionPosted;

    ExoExactHold(ExoPlayer source, long pageToken, long frameToken, long targetUs, long baseline) {
      this.source = source;
      this.pageToken = pageToken;
      this.frameToken = frameToken;
      this.targetUs = targetUs;
      frameBaseline = baseline;
    }
  }

  private final PlaybackEngineHistory engineHistory;
  private SeekEnginePolicy enginePolicy;

  VideoPage(Context context, Supplier<LibVLC> vlcSupplier, Listener listener) {
    super(context);
    this.vlcSupplier = vlcSupplier;
    this.listener = listener;
    nativeSurfaceFrames =
        Build.VERSION.SDK_INT >= 34
            ? new NativeSurfaceFrames(
                new NativeSurfaceFrames.Listener() {
                  @Override
                  public void onSurfaceAvailable() {
                    primeNativeSurface();
                  }

                  @Override
                  public void onSurfaceUnavailable() {
                    if (!nativeSurfacePlayback || vlc == null) return;
                    long at = position();
                    boolean restoreSeek = nativeSeekQueue.hasWork();
                    resetNativeSeekQueue();
                    if (restoreSeek) {
                      pendingSeek = at;
                      pendingSeekSet = true;
                    }
                    if (firstFrameReady && !(active && playRequested)) {
                      nativeSurfacePauseTarget = at;
                      nativeSurfacePauseRestore = true;
                    }
                    firstFrameReady = false;
                    buffering = true;
                    warmingVlc = false;
                    if (frameProbe != null) main.removeCallbacks(frameProbe);
                    applyVlcPlayback(vlc);
                    VideoPage.this.listener.onChanged(VideoPage.this);
                  }

                  @Override
                  public void onFrameAvailable() {
                    if (nativeSurfacePlayback
                        && vlc != null
                        && warmingVlc
                        && getWindowVisibility() == VISIBLE) probeVlcFrame(generation, vlc);
                    if (nativeSeekQueue.hasWork()) {
                      main.removeCallbacks(nativeSeekProbe);
                      main.post(nativeSeekProbe);
                    }
                  }
                })
            : null;
    engineHistory = PlaybackEngineHistory.get(context);
    setBackgroundColor(Color.BLACK);
    setClipChildren(true);
    exoView =
        (PlayerView) LayoutInflater.from(context).inflate(R.layout.video_page_exo, this, false);
    exoView.setKeepContentOnPlayerReset(true);
    exoView.setShutterBackgroundColor(Color.TRANSPARENT);
    addView(exoView, new LayoutParams(-1, -1));
    vlcView = new VLCVideoLayout(context);
    vlcView.setVisibility(INVISIBLE);
    addView(vlcView, new LayoutParams(-1, -1));
    errorLabel = new TextView(context);
    errorLabel.setText("无法播放，可滑动切集");
    errorLabel.setTextColor(0xffd1ced8);
    errorLabel.setTextSize(14);
    errorLabel.setGravity(Gravity.CENTER);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    errorLabel.setPadding(padding, padding, padding, padding);
    errorLabel.setVisibility(GONE);
    addView(errorLabel, new LayoutParams(-1, -2, Gravity.CENTER));
  }

  void prepare(
      VideoItem item,
      int index,
      long start,
      boolean forceVlc,
      boolean single,
      float speed,
      boolean foreground) {
    release();
    forceNativeEngine = forceVlc;
    this.item = item;
    this.index = index;
    this.single = single;
    this.speed = Math.max(.1f, speed);
    this.foreground = foreground;
    decoderPreference = NativeDecoderSelector.preference(getContext());
    enginePolicy = engineHistory.forItem(item);
    pendingSeek = Math.max(0, start);
    pendingSeekSet = pendingSeek > 0;
    prepareCount++;
    buffering = true;
    final long token = generation;
    int engineChoice = PlaybackEngineChoice.get(getContext());
    boolean preferVlc =
        engineChoice == PlaybackEngineChoice.VLC
            || (!forceVlc
                && engineChoice == PlaybackEngineChoice.AUTO
                && enginePolicy.chooseVlc(System.currentTimeMillis()));
    if (NativeDecoderPolicy.prefersNativeBeforeHistory(decoderPreference) || forceVlc || preferVlc)
      startVlc(token, pendingSeek);
    else startExo(token, pendingSeek);
  }

  private void startExo(long token, long start) {
    startExo(token, start, false, false);
  }

  @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.ExperimentalApi.class)
  private void startExo(
      long token, long start, boolean strictHardware, boolean hardwareFallbackSoftware) {
    if (generation != token || item == null) return;
    nativeSelectionRequest++;
    final long codecToken = resetExoCodecState();
    if (strictHardware) triedExoHardware = true;
    else triedExo = true;
    firstFrameReady = false;
    buffering = true;
    failed = false;
    if (scrubbing) start = scrubTarget;
    pendingSeek = Math.max(0, start);
    pendingSeekSet = pendingSeek > 0;
    exoView.setVisibility(VISIBLE);
    vlcView.setVisibility(INVISIBLE);
    DefaultLoadControl.Builder buffer = new DefaultLoadControl.Builder();
    if (foreground) {
      buffer
          .setBufferDurationsMs(15000, 90000, 250, 500)
          .setBackBuffer(15000, true)
          .setTargetBufferBytes(64 * 1024 * 1024);
    } else {
      buffer
          .setBufferDurationsMs(500, 4000, 250, 500)
          .setBackBuffer(0, false)
          .setTargetBufferBytes(8 * 1024 * 1024);
    }
    try {
      DefaultRenderersFactory renderers =
          new DefaultRenderersFactory(getContext()) {
            @Override
            protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
              MediaCodecAdapter.Factory normal = super.getCodecAdapterFactory();
              return configuration -> {
                MediaCodecAdapter adapter = normal.createAdapter(configuration);
                if (configuration.format.sampleMimeType != null
                    && configuration.format.sampleMimeType.startsWith("video/")) {
                  boolean hardware = configuration.codecInfo.hardwareAccelerated;
                  boolean software = configuration.codecInfo.softwareOnly;
                  main.post(
                      () -> {
                        if (exoCodecGeneration != codecToken) return;
                        exoHardwareConfigured = hardware;
                        exoSoftwareConfigured = software;
                      });
                }
                return adapter;
              };
            }
          }.setEnableDecoderFallback(true);
      if (strictHardware) renderers.setMediaCodecSelector(strictHardwareSelector());
      if (Build.VERSION.SDK_INT >= 34)
        renderers.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(true);
      ExoPlayer source =
          new ExoPlayer.Builder(getContext(), renderers).setLoadControl(buffer.build()).build();
      exo = source;
      final long frameToken = ++engineFrameGeneration;
      lastRenderedPositionUs = C.TIME_UNSET;
      source.setVideoFrameMetadataListener(
          (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
            if (engineFrameGeneration != frameToken) return;
            lastRenderedPositionUs = presentationTimeUs;
            lastFrameReleaseTimeNs = releaseTimeNs;
            lastRenderedFrameSequence++;
            ExoExactHold hold = exoExactHold;
            long frameUs =
                format.frameRate > 0 ? (long) Math.ceil(1_000_000d / format.frameRate) : 100_000;
            if (hold != null
                && hold.source == source
                && hold.frameToken == frameToken
                && !hold.completionPosted
                && lastRenderedFrameSequence > hold.frameBaseline
                && (hold.armed
                    || Math.abs(presentationTimeUs - hold.targetUs)
                        <= Math.max(1000, Math.min(100_000, frameUs)))) {
              // After the command barrier any real output can end the optimization. Sparse/VFR
              // media need not contain a frame near the requested time. This is never an accuracy
              // acknowledgement; EXACT remains in force and the last requested seek is preserved.
              // Before the barrier, retain only the cached matching-frame race recovery.
              hold.eligibleFrameSeen = true;
              maybeFinishExoHoldOnPlaybackThread(hold);
            }
          });
      source.setAudioAttributes(MOVIE_AUDIO, false);
      source.setVolume(0);
      source.setPlayWhenReady(false);
      source.setHandleAudioBecomingNoisy(true);
      // Official AUDIO/METADATA suppression permits same-GOP forward decoder-state reuse.
      source.setScrubbingModeParameters(EXO_SCRUB_PARAMETERS);
      source.setSeekParameters(scrubbing ? coarseSeekParameters() : SeekParameters.EXACT);
      source.setScrubbingModeEnabled(scrubbing);
      source.setRepeatMode(single ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
      source.setPlaybackSpeed(speed);
      exoView.setPlayer(source);
      source.addListener(
          new Player.Listener() {
            private boolean current() {
              return generation == token && exo == source;
            }

            @Override
            public void onPlaybackStateChanged(int state) {
              if (!current()) return;
              buffering = state == Player.STATE_BUFFERING;
              syncExoOutputProbe();
              if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                ExoExactHold hold = exoExactHold;
                if (hold != null) main.post(() -> finishExoHoldIfReady(hold));
              }
              listener.onChanged(VideoPage.this);
              if (state == Player.STATE_ENDED) checkExoEndedAfterCommands(token, source);
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
              if (current()) listener.onChanged(VideoPage.this);
            }

            @Override
            public void onRenderedFirstFrame() {
              if (!current()) return;
              pendingSeek = 0;
              pendingSeekSet = false;
              markFirstFrame();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
              if (current()) listener.onVideoSize(VideoPage.this, size.width, size.height);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
              if (!current()) return;
              long at = Math.max(0, source.getCurrentPosition());
              long errorSeekSerial = explicitSeekSerial;
              main.post(
                  () -> {
                    if (current()) {
                      exoAttemptFailed(
                          token,
                          errorSeekSerial != explicitSeekSerial ? explicitSeekTarget : at,
                          strictHardware,
                          hardwareFallbackSoftware);
                    }
                  });
            }
          });
      source.setMediaItem(MediaItem.fromUri(playbackUri()), start);
      source.prepare();
      if (strictHardware) beginExoOutputProbe(token, frameToken, source, hardwareFallbackSoftware);
      listener.onChanged(this);
      applyPlayback();
    } catch (RuntimeException error) {
      if (generation == token && exoCodecGeneration == codecToken) {
        exoAttemptFailed(token, start, strictHardware, hardwareFallbackSoftware);
      }
    }
  }

  private long resetExoCodecState() {
    exoHardwareConfigured = false;
    exoSoftwareConfigured = false;
    return ++exoCodecGeneration;
  }

  private static MediaCodecSelector strictHardwareSelector() {
    return (mime, secure, tunneled) -> {
      List<MediaCodecInfo> available =
          MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled);
      if (!mime.startsWith("video/")) return available;
      ArrayList<MediaCodecInfo> hardware = new ArrayList<>();
      for (MediaCodecInfo codec : available) {
        if (codec.hardwareAccelerated && !codec.softwareOnly) hardware.add(codec);
      }
      return Collections.unmodifiableList(hardware);
    };
  }

  private void exoAttemptFailed(
      long token, long position, boolean strictHardware, boolean hardwareFallbackSoftware) {
    if (generation != token) return;
    // Preserve the original resolved choice and the unrestricted compatible-engine attempt.
    if (strictHardware && hardwareFallbackSoftware && !triedNativeSoftware)
      startVlc(token, position, true);
    else if (strictHardware && !hardwareFallbackSoftware && !triedNativeHardware)
      startVlc(token, position, false);
    else compatibleFailed(token, position);
  }

  private void beginExoOutputProbe(
      long token, long frameToken, ExoPlayer source, boolean fallbackSoftware) {
    stopExoOutputProbe();
    ExoNoOutputWatchdog watchdog = new ExoNoOutputWatchdog();
    exoOutputWatchdog = watchdog;
    exoOutputSettledSeekSerial = explicitSeekSerial;
    exoOutputProbe =
        () -> {
          exoOutputProbeScheduled = false;
          if (generation != token || exo != source || engineFrameGeneration != frameToken) return;
          boolean opening = !firstFrameReady;
          if (!ExoNoOutputWatchdog.canObserve(
              isAttachedToWindow(), getWindowVisibility() == VISIBLE, active, opening)) {
            watchdog.reset();
            return;
          }
          boolean pending =
              active
                  && (source.getPlaybackState() == Player.STATE_BUFFERING || exoExactHold != null);
          // READY with no hold is completed work, including sparse video/audio tails. Merely
          // holding a paused thumb or revisiting the same coarse frame must not fail a decoder.
          if (!opening && !pending) {
            watchdog.reset();
            if (!scrubbing) exoOutputSettledSeekSerial = explicitSeekSerial;
            return;
          }
          if (watchdog.expired(
              SystemClock.uptimeMillis(),
              opening,
              pending,
              lastRenderedFrameSequence,
              lastRenderedPositionUs)) {
            long target =
                scrubbing
                    ? scrubTarget
                    : explicitSeekSerial != exoOutputSettledSeekSerial
                        ? explicitSeekTarget
                        : Math.max(0, source.getCurrentPosition());
            // This is a no-output failure, not a measured latency preference. The fallback choice
            // belongs to this original attempt and must not be re-read from changing preferences.
            exoAttemptFailed(token, target, true, fallbackSoftware);
            return;
          }
          exoOutputProbeScheduled = true;
          main.postDelayed(exoOutputProbe, 250);
        };
    syncExoOutputProbe();
  }

  private void syncExoOutputProbe() {
    if (exoOutputProbe == null || exoOutputWatchdog == null) return;
    boolean eligible =
        exo != null
            && ExoNoOutputWatchdog.canObserve(
                isAttachedToWindow(), getWindowVisibility() == VISIBLE, active, !firstFrameReady);
    boolean pending =
        eligible
            && (!firstFrameReady
                || active
                    && (exo.getPlaybackState() == Player.STATE_BUFFERING || exoExactHold != null));
    if (!pending) {
      main.removeCallbacks(exoOutputProbe);
      exoOutputProbeScheduled = false;
      exoOutputWatchdog.reset();
      if (eligible
          && !scrubbing
          && (exo.getPlaybackState() == Player.STATE_READY
              || exo.getPlaybackState() == Player.STATE_ENDED))
        exoOutputSettledSeekSerial = explicitSeekSerial;
    } else if (!exoOutputProbeScheduled) {
      exoOutputProbeScheduled = true;
      main.post(exoOutputProbe);
    }
  }

  private void stopExoOutputProbe() {
    if (exoOutputProbe != null) main.removeCallbacks(exoOutputProbe);
    exoOutputProbe = null;
    exoOutputWatchdog = null;
    exoOutputProbeScheduled = false;
  }

  private void startVlc(long token, long start) {
    if (generation != token || item == null) return;
    long selection = ++nativeSelectionRequest;
    long selectedSeekSerial = explicitSeekSerial;
    boolean avoidSoftware =
        decoderPreference != NativeDecoderPolicy.SOFTWARE
            && enginePolicy != null
            && enginePolicy.shouldAvoidSoftware(System.currentTimeMillis());
    NativeDecoderSelector.resolve(
        getContext(),
        item,
        avoidSoftware,
        software -> {
          if (generation != token || nativeSelectionRequest != selection || item == null) return;
          // A new seek/cancel can be handled by the old engine while header selection is pending.
          // Preserve that newer explicit intent, including zero, before replacing the old engine.
          // Without a newer request, keep the supplied fallback position instead of reviving an
          // unrelated earlier seek merely because the page remembers it.
          long selectedStart =
              explicitSeekSerial != selectedSeekSerial
                  ? explicitSeekTarget
                  : exo == null && vlc == null && pendingSeekSet ? pendingSeek : start;
          boolean tryHardwareExo =
              !forceNativeEngine
                  && !triedExoHardware
                  && !triedExo
                  && !triedVlc
                  && exo == null
                  && vlc == null
                  && NativeDecoderPolicy.useMedia3HardwareForLongGop(
                      decoderPreference,
                      PlaybackEngineChoice.get(getContext()),
                      software,
                      NativeDecoderSelector.cachedLongGop(item));
          // cachedLongGop consumes this delivered header decision, including a timed-out null.
          if (tryHardwareExo) {
            boolean fallbackSoftware =
                NativeDecoderPolicy.softwareAfterMedia3HardwareFailure(decoderPreference, software);
            startExo(token, selectedStart, true, fallbackSoftware);
          } else startVlc(token, selectedStart, software);
        });
  }

  private void startVlc(long token, long start, boolean software) {
    if (generation != token || item == null) return;
    nativeSelectionRequest++;
    releaseEngineForReplacement();
    if (scrubbing) start = scrubTarget;
    triedVlc = true;
    nativeSoftware = software;
    if (software) triedNativeSoftware = true;
    if (!software) triedNativeHardware = true;
    firstFrameReady = false;
    buffering = true;
    failed = false;
    pendingSeek = Math.max(0, start);
    pendingSeekSet = pendingSeek > 0;
    exoView.setVisibility(INVISIBLE);
    nativeSurfacePlayback = nativeSurfaceFrames != null;
    if (nativeSurfacePlayback) {
      // A recycled far slot gets a fresh buffer queue. Promotion and mode switches never do this.
      replaceVlcLayout();
    }
    vlcView.setVisibility(VISIBLE);
    try {
      nativeLease = NativePlayerRetirement.tryAcquire();
      if (nativeLease == null) {
        // Active plus retiring native inputs have a shared bound. Do not create another codec
        // while blocked teardown workers still own all available reservations.
        if (!triedExo) startExo(token, start);
        else fail(token);
        return;
      }
      LibVLC library = vlcSupplier.get();
      MediaPlayer source = new MediaPlayer(library);
      vlc = source;
      appliedNativeRate = Float.NaN;
      source.setVolume(0);
      source.attachViews(vlcView, null, false, !nativeSurfacePlayback);
      source.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
      vlcTextureBaseline = textureTimestamp(findTexture(vlcView));
      source.setEventListener(
          event ->
              main.post(
                  () -> {
                    if (generation != token || vlc != source) return;
                    switch (event.type) {
                      case MediaPlayer.Event.Playing:
                      case MediaPlayer.Event.Paused:
                      case MediaPlayer.Event.SeekableChanged:
                        applyPendingSeek(source);
                        if (event.type == MediaPlayer.Event.Paused) nativeSeekAwaitingPause = false;
                        if (event.type == MediaPlayer.Event.Playing) {
                          applyVlcRate(source);
                          Media.VideoTrack track = source.getCurrentVideoTrack();
                          if (track != null)
                            listener.onVideoSize(VideoPage.this, track.width, track.height);
                        }
                        if (firstFrameReady) applyVlcPlayback(source);
                        dispatchNativeSeek();
                        listener.onChanged(VideoPage.this);
                        break;
                      case MediaPlayer.Event.Buffering:
                        buffering = event.getBuffering() < 100;
                        syncNativeHealthProbe();
                        listener.onChanged(VideoPage.this);
                        break;
                      case MediaPlayer.Event.EndReached:
                        vlcRunningRequested = false;
                        if (active
                            && playRequested
                            && !ended
                            && !scrubbing
                            && !nativeSeekQueue.hasWork()) {
                          notifyVlcEnd(source);
                        }
                        break;
                      case MediaPlayer.Event.TimeChanged:
                        observeVlcRepeatBoundary(source);
                        break;
                      case MediaPlayer.Event.EncounteredError:
                        vlcFailed(token, Math.max(0, source.getTime()), true);
                        break;
                      default:
                        break;
                    }
                  }));
      Uri uri = playbackUri();
      Media media;
      if ("content".equals(uri.getScheme())) {
        descriptor = getContext().getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("Video descriptor unavailable");
        media = new Media(library, descriptor.getFileDescriptor());
      } else media = new Media(library, uri);
      try {
        // Set this on Media before enabling HW decoding; otherwise LibVLC adds 1500ms here.
        media.addOption(":file-caching=250");
        int threads =
            software ? NativeDecoderSelector.cachedSoftwareThreads(getContext(), item) : 0;
        if (threads > 0) media.addOption(":avcodec-threads=" + threads);
        media.setHWDecoderEnabled(!software, false);
        // LibVLC 3 has no live input-repeat setter. Keep its decoder looping and change
        // only our
        // completion policy, so a portrait/full-screen toggle never reopens the current
        // input.
        media.addOption(":input-repeat=65535");
        source.setMedia(media);
      } finally {
        media.release();
      }
      applyVlcRate(source);
      warmingVlc = true;
      // Muted temporary playback primes a real frame, including an offscreen page.
      source.play();
      vlcRunningRequested = true;
      bindNativeSurface();
      listener.onChanged(this);
      applyPlayback();
      beginVlcFrameProbe(token, source);
    } catch (Exception | UnsatisfiedLinkError error) {
      // Missing/unreadable input is not evidence that another decoder suits this file better.
      vlcFailed(
          token, start, !(error instanceof IOException || error instanceof SecurityException));
    }
  }

  private void beginVlcFrameProbe(long token, MediaPlayer source) {
    if (frameProbe != null) main.removeCallbacks(frameProbe);
    long now = SystemClock.uptimeMillis();
    warmupDeadline = now + 12000;
    if (prefersUnthrottledSoftware()) resetNativeDecodeProgress(now);
    frameProbe = () -> probeVlcFrame(token, source);
    main.postDelayed(frameProbe, 30);
  }

  private void probeVlcFrame(long token, MediaPlayer source) {
    if (generation != token || vlc != source || firstFrameReady || failed) return;
    if (nativeSurfacePlayback && getWindowVisibility() != VISIBLE) return;
    if (prefersUnthrottledSoftware()) {
      observeNativeDecodeProgress(source, SystemClock.uptimeMillis());
      warmupDeadline = nativeDecodeProgress.deadline(12000);
    }
    try {
      applyPendingSeek(source);
      if (nativeSurfacePlayback) {
        bindNativeSurface();
        if (nativeSurfaceFrames.isReady() && !pendingSeekSet) {
          if (nativeSurfacePauseRestore && !(active && playRequested)) {
            // Re-enabling a detached video track can advance its muted priming frame. Once that
            // track actually renders, pause and accurately restore the saved pause position on
            // this same input; only a new post-seek buffer may complete readiness.
            long restore = nativeSurfacePauseTarget;
            nativeSurfacePauseRestore = false;
            warmingVlc = false;
            applyVlcPlayback(source);
            pendingSeek = restore;
            pendingSeekSet = true;
            fenceVlcSeek(restore);
            if (source.isSeekable()) {
              pendingSeek = 0;
              pendingSeekSet = false;
              source.setTime(restore, false);
            }
          } else {
            nativeSurfacePauseRestore = false;
            warmingVlc = false;
            buffering = false;
            applyVlcPlayback(source);
            markFirstFrame();
            return;
          }
        }
        nativeSurfaceFrames.requestReadyProbe();
      } else {
        TextureView texture = findTexture(vlcView);
        if (texture != null && texture.isAvailable()) {
          if (probeBitmap == null) probeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
          // getBitmap updates the hardware texture even while its attached page is clipped
          // offscreen.
          Bitmap frame = texture.getBitmap(probeBitmap);
          long timestamp = textureTimestamp(texture);
          if (frame != null
              && timestamp > 0
              && timestamp != vlcTextureBaseline
              && !pendingSeekSet) {
            warmingVlc = false;
            buffering = false;
            applyVlcPlayback(source);
            markFirstFrame();
            return;
          }
        }
      }
    } catch (RuntimeException ignored) {
      // A texture can briefly be unavailable while an orientation change updates its bounds.
    }
    if (SystemClock.uptimeMillis() >= warmupDeadline) {
      // Surface availability and decoder pressure during preloading are transient, not a file rule.
      vlcFailed(token, position(), false);
      return;
    }
    if (frameProbe != null) {
      main.removeCallbacks(frameProbe);
      main.postDelayed(frameProbe, 50);
    }
  }

  private void bindNativeSurface() {
    if (!nativeSurfacePlayback || nativeSurfaceFrames == null) return;
    SurfaceView surface = findSurface(vlcView);
    if (surface != null && !nativeSurfaceFrames.isBoundTo(surface)) {
      nativeSurfaceFrames.bind(surface);
      if (scrubbing) nativeSurfaceFrames.beginPreview();
    }
  }

  private void primeNativeSurface() {
    if (!nativeSurfacePlayback
        || nativeSurfaceFrames == null
        || vlc == null
        || !nativeSurfaceFrames.hasSurface()
        || getWindowVisibility() != VISIBLE) return;
    if (!vlc.getVLCVout().areViewsAttached()) {
      reconnectNativeSurface();
      return;
    }
    warmingVlc = true;
    firstFrameReady = false;
    buffering = true;
    applyVlcPlayback(vlc);
    beginVlcFrameProbe(generation, vlc);
  }

  private void replaceVlcLayout() {
    int viewIndex = indexOfChild(vlcView);
    removeView(vlcView);
    vlcView = new VLCVideoLayout(getContext());
    addView(vlcView, viewIndex, new LayoutParams(-1, -1));
  }

  private void reconnectNativeSurface() {
    if (nativeSurfaceReattachPosted || vlc == null) return;
    nativeSurfaceReattachPosted = true;
    final long token = generation;
    MediaPlayer source = vlc;
    VLCVideoLayout oldLayout = vlcView;
    // LibVLC detaches all holder callbacks after surfaceDestroyed. Reconnect outside that
    // callback/bind stack; retain the native input, position, rate and playback intent.
    main.post(
        () -> {
          if (generation != token || vlc != source || vlcView != oldLayout) return;
          nativeSurfaceReattachPosted = false;
          if (!nativeSurfacePlayback
              || !nativeSurfaceFrames.hasSurface()
              || getWindowVisibility() != VISIBLE) return;
          if (source.getVLCVout().areViewsAttached()) {
            primeNativeSurface();
            return;
          }
          long at = scrubbing ? scrubTarget : position();
          try {
            source.setVolume(0);
            firstFrameReady = false;
            buffering = warmingVlc = true;
            nativeSurfacePauseRestore = !(active && playRequested);
            nativeSurfacePauseTarget = Math.max(0, at);
            nativeSurfaceFrames.unbind();
            source.detachViews();
            // Drop the detached helper's layout listener with its old view tree. A fresh queue
            // also prevents that surface's old pixels from satisfying the new first-frame gate.
            replaceVlcLayout();
            vlcView.setVisibility(VISIBLE);
            source.attachViews(vlcView, null, false, false);
            source.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            pendingSeek = Math.max(0, at);
            pendingSeekSet = true;
            bindNativeSurface();
            applyPendingSeek(source);
            applyVlcPlayback(source);
            beginVlcFrameProbe(token, source);
            listener.onChanged(this);
          } catch (RuntimeException unavailable) {
            vlcFailed(token, at, false);
          }
        });
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    syncExoOutputProbe();
  }

  @Override
  protected void onDetachedFromWindow() {
    // isAttachedToWindow() can remain true until this callback returns. Suspend explicitly rather
    // than relying on that flag during removal; keep the probe so reattachment can resume it.
    if (exoOutputProbe != null) main.removeCallbacks(exoOutputProbe);
    exoOutputProbeScheduled = false;
    if (exoOutputWatchdog != null) exoOutputWatchdog.reset();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility != VISIBLE) cancelExoExactHold(true);
    else if (exo != null && exoExactHold == null) {
      // Visibility cancellation muted the old hold; restore gain without changing play intent.
      exo.setVolume(active && exo.getPlayWhenReady() && audible && !scrubbing ? 1 : 0);
    }
    syncExoOutputProbe();
    if (vlc == null) return;
    syncNativeHealthProbe();
    if (visibility != VISIBLE) main.removeCallbacks(nativeSeekProbe);
    else {
      armNativeSeekWatchdog(SystemClock.uptimeMillis());
      if (nativeSeekQueue.hasWork()) {
        dispatchNativeSeek();
        main.post(nativeSeekProbe);
      }
    }
    if (!nativeSurfacePlayback) return;
    if (visibility != VISIBLE) {
      if (frameProbe != null) main.removeCallbacks(frameProbe);
      applyVlcPlayback(vlc);
    } else {
      if (!firstFrameReady) primeNativeSurface();
      else applyVlcPlayback(vlc);
    }
  }

  private void markFirstFrame() {
    if (firstFrameReady) return;
    firstFrameReady = true;
    if (enginePolicy != null) {
      if (vlc != null) {
        boolean changed = enginePolicy.recordVlcSuccess();
        if (nativeSoftware) changed |= enginePolicy.recordSoftwareSuccess();
        else if (rememberSoftwareFailureOnHardwareFrame) {
          enginePolicy.recordSoftwareFallback(System.currentTimeMillis());
          changed = true;
        }
        if (changed) engineHistory.changed();
      } else if (exo != null && rememberVlcFailureOnExoFrame) {
        enginePolicy.recordCompatibleFallback(System.currentTimeMillis());
        engineHistory.changed();
      }
    }
    rememberVlcFailureOnExoFrame = false;
    rememberSoftwareFailureOnHardwareFrame = false;
    syncNativeHealthProbe();
    failed = false;
    errorLabel.setVisibility(GONE);
    listener.onChanged(this);
    listener.onFirstFrame(this);
    if (nativeSeekQueue.hasWork()) {
      dispatchNativeSeek();
      main.post(nativeSeekProbe);
    }
  }

  private void submitNativeSeek(long target, boolean fast) {
    MediaPlayer source = vlc;
    if (source == null) return;
    if (nativeSurfacePauseRestore) nativeSurfacePauseTarget = target;
    nativeSeekQueue.offer(target, fast);
    // A current priming/recovery seek must produce its first output before another flush.
    // New gestures replace this one pending intent, including an explicit target of zero.
    if (!firstFrameReady || warmingVlc || !source.isSeekable()) return;
    dispatchNativeSeek();
  }

  private void dispatchNativeSeek() {
    MediaPlayer source = vlc;
    if (source == null
        || !active
        || !firstFrameReady
        || warmingVlc
        || nativeSeekAwaitingPause
        || getWindowVisibility() != VISIBLE
        || !source.isSeekable()) return;
    long now = SystemClock.uptimeMillis();
    NativeSeekQueue.Request request = nativeSeekQueue.take(now);
    if (request == null) return;
    nativeSeekSubmittedAt = now;
    nativeSeekClockRate = speed;
    armNativeSeekWatchdog(now);
    nativeSeekClockStepMs = 33;
    if (!nativeSoftware) {
      IMedia.VideoTrack track = source.getCurrentVideoTrack();
      if (track != null && track.frameRateNum > 0 && track.frameRateDen > 0)
        nativeSeekClockStepMs =
            Math.max(
                1,
                Math.min(
                    100,
                    (1000L * track.frameRateDen + track.frameRateNum - 1) / track.frameRateNum));
    }
    nativeSeekFrameBaseline = nativeSurfaceFrames == null ? 0 : nativeSurfaceFrames.frameSequence();
    nativeSeekTextureBaseline = textureTimestamp(currentTexture());
    fenceVlcSeek(request.positionMs);
    // Register before setTime: a fast paused seek may submit its only buffer before setTime
    // returns. The clock check below still rejects obviously unrelated pre-seek output.
    if (nativeSurfacePlayback && nativeSurfaceFrames != null)
      nativeSurfaceFrames.observeNextFrame();
    source.setTime(request.positionMs, request.fast);
    main.removeCallbacks(nativeSeekProbe);
    main.postDelayed(nativeSeekProbe, 16);
  }

  private void probeNativeSeek() {
    main.removeCallbacks(nativeSeekProbe);
    MediaPlayer source = vlc;
    NativeSeekQueue.Request request = nativeSeekQueue.submitted();
    if (source == null || failed || !active || getWindowVisibility() != VISIBLE) return;
    if (!firstFrameReady || warmingVlc) return;
    long now = SystemClock.uptimeMillis();
    if (prefersUnthrottledSoftware()) {
      observeNativeDecodeProgress(source, now);
      nativeSeekDeadline = nativeDecodeProgress.deadline(PREFERRED_SOFTWARE_NO_PROGRESS_MS);
      if (nativeSurfacePlayback && nativeSurfaceFrames != null)
        nativeSurfaceFrames.observeNextFrame();
    }
    if (nativeSeekAwaitingPause) {
      if (now >= nativeSeekDeadline)
        vlcFailed(generation, nativeSeekQueue.latestPositionMs(), false);
      else main.postDelayed(nativeSeekProbe, 16);
      return;
    }
    if (request == null) return;
    boolean fresh;
    if (nativeSurfacePlayback && nativeSurfaceFrames != null) {
      fresh = nativeSurfaceFrames.frameSequence() > nativeSeekFrameBaseline;
      if (!fresh) nativeSurfaceFrames.observeNextFrame();
    } else {
      long timestamp = textureTimestamp(currentTexture());
      fresh = timestamp > 0 && timestamp != nativeSeekTextureBaseline;
    }
    // Neither the native input clock nor Buffering=100 means that a frame was displayed. Require
    // fresh surface output as well. Fast seeks can legitimately land at an earlier keyframe;
    // final accurate requests retain their own queue entry until the target clock also converges.
    long clock = source.getTime();
    long distance = Math.abs(clock - request.positionMs);
    long length = duration();
    if (length > 0 && distance <= length) distance = Math.min(distance, length - distance);
    // A temporary speed increase may already have advanced this clock before returning to 1x.
    // Use this request's highest rate as a conservative bound, without changing paused tolerance.
    long allowance =
        200
            + (active && playRequested
                ? Math.round(
                    Math.max(0, now - nativeSeekSubmittedAt) * (double) nativeSeekClockRate)
                : 0);
    boolean clockAdvanced = true;
    if (!nativeSoftware && active && playRequested && !request.fast) {
      // Hardware output can still contain a buffer queued before setTime. Its immediate target
      // clock assignment is not enough to release another accurate flush. Keep the first seek
      // immediate, but coalesce subsequent taps until this playing clock actually advances.
      long forward = clock - request.positionMs;
      if (forward < 0 && length > 0) forward += length;
      clockAdvanced = forward >= nativeSeekClockStepMs && forward <= allowance;
    }
    if (fresh && clock >= 0 && clockAdvanced && (request.fast || distance <= allowance)) {
      nativeSeekQueue.acknowledge(request.id);
      dispatchNativeSeek();
      if (!nativeSeekQueue.hasWork()) syncNativeHealthProbe();
      return;
    }
    if (now >= nativeSeekDeadline) {
      // A timer is never permission to flush this decoder again. Preserve only the latest intent
      // and use the existing bounded engine fallback, rather than leaving a wedged input forever.
      long target = nativeSeekQueue.latestPositionMs();
      vlcFailed(generation, target, false);
      return;
    }
    main.postDelayed(nativeSeekProbe, 16);
  }

  private void resetNativeSeekQueue() {
    main.removeCallbacks(nativeSeekProbe);
    nativeSeekQueue.reset();
    nativeSeekAwaitingPause = false;
    nativeSeekDeadline = 0;
  }

  private boolean prefersUnthrottledSoftware() {
    return decoderPreference == NativeDecoderPolicy.SOFTWARE && nativeSoftware;
  }

  private long nativeFrameProgressToken() {
    return nativeSurfacePlayback && nativeSurfaceFrames != null
        ? nativeSurfaceFrames.frameSequence()
        : textureTimestamp(currentTexture());
  }

  private void resetNativeDecodeProgress(long now) {
    nativeDecodeProgress.reset(now, nativeFrameProgressToken());
    nextNativeProgressStatsAt = 0;
  }

  private void armNativeSeekWatchdog(long now) {
    nativeSeekDeadline =
        now
            + (prefersUnthrottledSoftware()
                ? PREFERRED_SOFTWARE_NO_PROGRESS_MS
                : NATIVE_SEEK_WATCHDOG_MS);
    if (prefersUnthrottledSoftware()) resetNativeDecodeProgress(now);
  }

  private void observeNativeDecodeProgress(MediaPlayer source, long now) {
    long decoded = -1, displayed = -1;
    if (now >= nextNativeProgressStatsAt) {
      nextNativeProgressStatsAt = now + 250;
      IMedia media = null;
      try {
        media = source.getMedia();
        IMedia.Stats stats = media == null ? null : media.getStats();
        if (stats != null) {
          decoded = stats.decodedVideo;
          displayed = stats.displayedPictures;
        }
      } catch (RuntimeException unavailable) {
        // Missing statistics cannot establish poor performance; actual frame activity also counts.
      } finally {
        if (media != null) media.release();
      }
    }
    nativeDecodeProgress.observe(now, decoded, displayed, nativeFrameProgressToken());
  }

  private void applyPendingSeek(MediaPlayer source) {
    if (!pendingSeekSet || !source.isSeekable()) return;
    long target = pendingSeek;
    pendingSeek = 0;
    pendingSeekSet = false;
    vlcTextureBaseline = textureTimestamp(findTexture(vlcView));
    // A native input-repeat must return to zero, not reapply a media-level start-time offset.
    fenceVlcSeek(target);
    source.setTime(target, scrubbing);
  }

  private void resetVlcRepeatObservation() {
    vlcPreviousTime = -1;
    vlcNearEndArmed = false;
    vlcSeekFenceTarget = -1;
    vlcSeekFenceDeadline = 0;
  }

  private void fenceVlcSeek(long target) {
    if (nativeSurfacePauseRestore) nativeSurfacePauseTarget = Math.max(0, target);
    nativeHealth.reset();
    if (nativeSurfacePlayback && nativeSurfaceFrames != null) {
      bindNativeSurface();
      nativeSurfaceFrames.beforeSeek(!firstFrameReady);
    }
    vlcPreviousTime = -1;
    vlcNearEndArmed = false;
    vlcSeekFenceTarget = Math.max(0, target);
    vlcSeekFenceDeadline = SystemClock.uptimeMillis() + 1500;
  }

  /**
   * Native input-repeat seeks inside the same decoder instead of sending EndReached. Observe that
   * real clock wrap for non-repeat playback. VLC 3 publishes times about every 250 ms, so
   * compatible decoding can advance that much later than the exact boundary; Exo uses its native
   * repeat policy.
   */
  private void observeVlcRepeatBoundary(MediaPlayer source) {
    if (!active
        || !playRequested
        || warmingVlc
        || !firstFrameReady
        || pendingSeekSet
        || nativeSeekQueue.hasWork()
        || ended
        || scrubbing) {
      vlcPreviousTime = -1;
      vlcNearEndArmed = false;
      return;
    }
    // Read the current native clock, rather than an older queued event's timestamp. A manual
    // seek
    // can happen while TimeChanged events are waiting on the main looper.
    long at = source.getTime();
    long length = source.getLength();
    if (length <= 0 && item != null) length = item.durationMs;
    if (at < 0 || length <= 0) return;
    long minimumDrop = Math.max(40, Math.min(400, length / 4));
    if (vlcSeekFenceTarget >= 0) {
      // Even for sub-second files, accepting the pre-seek clock must not allow the subsequent
      // seek displacement itself to satisfy the natural-wrap distance test.
      long tolerance = Math.max(10, Math.min(350, minimumDrop - 1));
      if (Math.abs(at - vlcSeekFenceTarget) > tolerance
          && SystemClock.uptimeMillis() < vlcSeekFenceDeadline) return;
      // The first acknowledged post-seek sample is an anchor, never a natural completion.
      vlcSeekFenceTarget = -1;
      vlcPreviousTime = at;
      vlcNearEndArmed = false;
    }
    long boundaryWindow = Math.max(120, Math.min(1500, length / 3));
    boolean wrapped =
        vlcNearEndArmed
            && vlcPreviousTime >= 0
            && vlcPreviousTime - at > minimumDrop
            && at <= boundaryWindow;
    vlcPreviousTime = at;
    if (wrapped) {
      nativeHealth.reset();
      vlcNearEndArmed = false;
      if (!single) {
        notifyVlcEnd(source);
        return;
      }
    }
    if (at >= Math.max(0, length - boundaryWindow)) vlcNearEndArmed = true;
  }

  private void notifyVlcEnd(MediaPlayer source) {
    if (ended || !active || !playRequested) return;
    ended = true;
    playRequested = false;
    audible = false;
    vlcRunningRequested = false;
    source.setVolume(0);
    source.pause();
    listener.onEnded(this);
  }

  void setPlayback(boolean active, boolean play, boolean audible) {
    boolean promoted = active && !this.active;
    boolean pausingSubmittedSeek =
        vlc != null
            && this.active
            && playRequested
            && !(active && play)
            && !warmingVlc
            && nativeSeekQueue.hasWork();
    if (pausingSubmittedSeek) {
      // Pause may discard the only output that would acknowledge the old playing seek. Hand
      // just the latest target to the paused input once Paused is observed, rather than waiting
      // for a cancelled old buffer or flushing on every subsequent tap.
      long latest = nativeSeekQueue.latestPositionMs();
      resetNativeSeekQueue();
      nativeSeekQueue.offer(latest, false);
      nativeSeekAwaitingPause = true;
      armNativeSeekWatchdog(SystemClock.uptimeMillis());
    } else if (active && play) nativeSeekAwaitingPause = false;
    if (!active && (scrubbing || exoExactHold != null)) clearScrubState();
    if (active && play) rewindIfEnded();
    this.active = active;
    playRequested = play;
    if (active && play) nativeSurfacePauseRestore = false;
    this.audible = audible;
    if (!active || !play) {
      vlcPreviousTime = -1;
      vlcNearEndArmed = false;
    }
    applyPlayback();
    syncExoOutputProbe();
    if (!active) main.removeCallbacks(nativeSeekProbe);
    else if (nativeSeekQueue.hasWork()) {
      if (promoted) armNativeSeekWatchdog(SystemClock.uptimeMillis());
      dispatchNativeSeek();
      if (promoted || nativeSeekAwaitingPause) main.post(nativeSeekProbe);
    }
  }

  /** Re-primes an ended page before it becomes a paused neighbor, without replacing its decoder. */
  void rewindIfEnded() {
    if (!ended && (exo == null || exo.getPlaybackState() != Player.STATE_ENDED)) return;
    ended = false;
    playRequested = false;
    audible = false;
    applyPlayback();
    pendingSeek = 0;
    pendingSeekSet = false;
    firstFrameReady = false;
    if (exo != null) {
      exo.seekTo(0);
    } else if (vlc != null) {
      MediaPlayer source = vlc;
      vlcTextureBaseline = textureTimestamp(findTexture(vlcView));
      fenceVlcSeek(0);
      pendingSeekSet = true;
      applyPendingSeek(source);
      warmingVlc = true;
      applyVlcPlayback(source);
      beginVlcFrameProbe(generation, source);
    }
  }

  private void applyPlayback() {
    if (exo != null) {
      exo.setVolume(0);
      exo.setAudioAttributes(MOVIE_AUDIO, active && audible);
      exo.setPlayWhenReady(active && playRequested);
      exo.setVolume(
          active && playRequested && audible && !scrubbing && exoExactHold == null ? 1 : 0);
    }
    if (vlc != null) applyVlcPlayback(vlc);
  }

  private void applyVlcPlayback(MediaPlayer source) {
    source.setVolume(0);
    // Preserve the native playback clock while seeking; muting a playing scrub avoids the
    // pause/seek/play restart cycle. An originally paused or inactive page still stays paused.
    boolean playing = active && playRequested;
    boolean run =
        (playing || warmingVlc) && (!nativeSurfacePlayback || getWindowVisibility() == VISIBLE);
    if (run != vlcRunningRequested) {
      vlcRunningRequested = run;
      if (run) source.play();
      else source.pause();
    }
    if (run) {
      applyVlcRate(source);
      if (playing
          && audible
          && !scrubbing
          && (!nativeSurfacePlayback || (!warmingVlc && nativeSurfaceFrames.isReady())))
        source.setVolume(nativeDucked ? 25 : 100);
    }
    syncNativeHealthProbe();
  }

  void setNativeDucked(boolean ducked) {
    if (nativeDucked == ducked) return;
    nativeDucked = ducked;
    // Focus attenuation is a gain, not permission to make an inactive/warming/scrubbing page
    // audible. Every native volume change follows the same eligibility gate.
    if (vlc != null) applyVlcPlayback(vlc);
  }

  private boolean canObserveNativeHealth() {
    return vlc != null
        && NativeDecoderPolicy.allowsPerformanceFallback(decoderPreference)
        && nativeSoftware
        && active
        && playRequested
        && audible
        && firstFrameReady
        && !warmingVlc
        && !buffering
        && !scrubbing
        && !ended
        && !pendingSeekSet
        && !nativeSeekQueue.hasWork()
        && Math.abs(speed - 1f) < .001f
        && getWindowVisibility() == VISIBLE;
  }

  private void syncNativeHealthProbe() {
    if (!canObserveNativeHealth()) {
      main.removeCallbacks(nativeHealthProbe);
      nativeHealthScheduled = false;
      nativeHealth.reset();
    } else if (!nativeHealthScheduled) {
      nativeHealthScheduled = true;
      main.postDelayed(nativeHealthProbe, 1000);
    }
  }

  private void sampleNativeHealth() {
    nativeHealthScheduled = false;
    if (!canObserveNativeHealth()) {
      nativeHealth.reset();
      return;
    }
    MediaPlayer source = vlc;
    boolean slow = false;
    IMedia media = null;
    try {
      media = source.getMedia();
      IMedia.Stats stats = media == null ? null : media.getStats();
      IMedia.VideoTrack track = source.getCurrentVideoTrack();
      if (stats == null || track == null || track.frameRateDen <= 0) nativeHealth.reset();
      else
        slow =
            nativeHealth.update(
                SystemClock.uptimeMillis(),
                stats.decodedVideo,
                stats.displayedPictures,
                stats.lostPictures,
                (float) track.frameRateNum / track.frameRateDen);
    } catch (RuntimeException unavailable) {
      nativeHealth.reset();
    } finally {
      if (media != null) media.release();
    }
    if (slow && !triedNativeHardware) {
      // A sustained, eligible playback deficit is evidence; a seek or absent statistic is not.
      // Persist this choice only if the hardware retry actually produces its first frame.
      rememberSoftwareFailureOnHardwareFrame = true;
      startVlc(generation, Math.max(0, source.getTime()), false);
    } else syncNativeHealthProbe();
  }

  private void applyVlcRate(MediaPlayer source) {
    if (source != vlc || appliedNativeRate == speed) return;
    // Even identical rates enqueue native SET_RATE commands between pending seeks. The Java
    // binding returns void, so cache only after it returns normally; exceptions remain retryable.
    source.setRate(speed);
    appliedNativeRate = speed;
  }

  void setSpeed(float speed) {
    this.speed = Math.max(.1f, speed);
    if (nativeSeekQueue.submitted() != null)
      nativeSeekClockRate = Math.max(nativeSeekClockRate, this.speed);
    if (exo != null) exo.setPlaybackSpeed(this.speed);
    if (vlc != null) applyVlcRate(vlc);
    syncNativeHealthProbe();
  }

  void setRepeat(boolean single) {
    if (this.single == single) return;
    this.single = single;
    if (exo != null) exo.setRepeatMode(single ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    else if (vlc != null) {
      // Policy-only change: preserve the MediaPlayer, texture, native input, time, pause and
      // speed.
      // Begin observing from the current clock so a previously queued wrap does not count
      // again.
      vlcPreviousTime = -1;
      vlcNearEndArmed = false;
      observeVlcRepeatBoundary(vlc);
    }
  }

  /** Uses the visible decoder for interactive frames; no second thumbnail decoder is needed. */
  void beginScrub() {
    if (scrubbing || failed || item == null) return;
    cancelExoExactHold(false);
    scrubOrigin = position();
    scrubTarget = scrubOrigin;
    scrubFrameBaseline = lastRenderedFrameSequence;
    scrubTextureBaseline = textureTimestamp(currentTexture());
    scrubSessionFrameBaseline = scrubFrameBaseline;
    scrubSessionTextureBaseline = scrubTextureBaseline;
    scrubSubmittedCount = 0;
    lastScrubPreviewPositionMs = -1;
    capturedTextureTimestamp = 0;
    scrubStartedAt = SystemClock.uptimeMillis();
    lastScrubDispatch = 0;
    scrubPending = false;
    scrubbing = true;
    syncNativeHealthProbe();
    if (nativeSurfacePlayback && nativeSurfaceFrames != null) {
      bindNativeSurface();
      nativeSurfaceFrames.beginPreview();
    }
    if (exo != null) {
      playRequested = exo.getPlayWhenReady();
      exo.setVolume(0);
      // Prepare the scrub renderer state on touch-down; a tap still issues only its final seek.
      exo.setScrubbingModeParameters(EXO_SCRUB_PARAMETERS);
      exo.setSeekParameters(coarseSeekParameters());
      exo.setScrubbingModeEnabled(true);
    }
    // Keep the native clock and play intent; all scrub requests stay muted until the finger lifts.
    if (vlc != null) vlc.setVolume(0);
  }

  void scrubTo(long position) {
    if (!scrubbing) return;
    long target = clampPosition(position);
    recordSeekIntent(target);
    if (target == scrubTarget && !scrubPending) return;
    scrubTarget = target;
    if (nativeSurfacePauseRestore) nativeSurfacePauseTarget = target;
    if (exo == null && vlc == null) {
      pendingSeek = target;
      pendingSeekSet = true;
      scrubPending = false;
      return;
    }
    scrubPending = true;
    main.removeCallbacks(dispatchScrub);
    long earliest =
        scrubSubmittedCount == 0
            ? scrubStartedAt + FIRST_SCRUB_DELAY_MS
            : lastScrubDispatch + SCRUB_INTERVAL_MS;
    long delay = Math.max(0, earliest - SystemClock.uptimeMillis());
    if (delay == 0) dispatchScrub();
    else main.postDelayed(dispatchScrub, delay);
  }

  private void dispatchScrub() {
    if (!scrubbing || !scrubPending || failed) return;
    long now = SystemClock.uptimeMillis();
    // While a decoder is still catching up, replace the pending target rather than repeatedly
    // flushing it. A bounded deadline still lets a very slow/failed request be superseded.
    if (exo != null
        && scrubSubmittedCount > 0
        && lastRenderedFrameSequence <= scrubFrameBaseline
        && now - lastScrubDispatch < 180) {
      main.postDelayed(dispatchScrub, 180 - (now - lastScrubDispatch));
      return;
    }
    scrubPending = false;
    lastScrubDispatch = now;
    scrubFrameBaseline = lastRenderedFrameSequence;
    scrubTextureBaseline = textureTimestamp(currentTexture());
    long target = scrubTarget;
    scrubSubmittedTargets[scrubSubmittedCount % scrubSubmittedTargets.length] = target;
    scrubSubmittedCount++;
    ended = false;
    if (exo != null) {
      exo.setScrubbingModeParameters(EXO_SCRUB_PARAMETERS);
      exo.setSeekParameters(coarseSeekParameters());
      if (!exo.isScrubbingModeEnabled()) exo.setScrubbingModeEnabled(true);
      exo.seekTo(target);
    } else if (vlc != null) {
      applyVlcPlayback(vlc);
      submitNativeSeek(target, true);
    }
  }

  void endScrub(long position, boolean commit) {
    if (!scrubbing) {
      if (commit) seekTo(position);
      return;
    }
    long target = clampPosition(commit ? position : scrubOrigin);
    recordSeekIntent(target);
    main.removeCallbacks(dispatchScrub);
    scrubPending = false;
    scrubbing = false;
    if (nativeSurfaceFrames != null) nativeSurfaceFrames.endPreview();
    ended = false;
    if (exo != null) {
      // Focus loss/noisy handling can change this while scrub suppression is active.
      playRequested = exo.getPlayWhenReady();
      submitExoExactSeek(target);
    } else if (vlc != null) {
      submitNativeSeek(target, false);
      // VLC's clock is not a frame PTS. Do not invent comparable latency samples from it.
    } else if (item != null) {
      pendingSeek = target;
      pendingSeekSet = true;
    }
    applyPlayback();
  }

  /** Keep Media3's seek boost through final decoding, without changing the playback intent. */
  private void submitExoExactSeek(long target) {
    ExoPlayer source = exo;
    ExoExactHold previous = exoExactHold;
    if (previous != null && previous.source == source && previous.targetUs == target * 1000) return;
    cancelExoExactHold(false);
    // Non-null fractional tolerance overrides EXACT while scrubbing; always disable it here.
    source.setScrubbingModeParameters(EXO_SCRUB_PARAMETERS);
    source.setSeekParameters(SeekParameters.EXACT);
    if (!active
        || getWindowVisibility() != VISIBLE
        || (source.getPlaybackState() == Player.STATE_READY
            && source.getCurrentPosition() == target)) {
      // Media3 may treat an unchanged position as a no-op with no new frame callback.
      if (active) playRequested = source.getPlayWhenReady();
      source.seekTo(target);
      source.setScrubbingModeEnabled(false);
      applyPlayback();
      return;
    }
    ExoExactHold hold =
        new ExoExactHold(
            source, generation, engineFrameGeneration, target * 1000, lastRenderedFrameSequence);
    exoExactHold = hold;
    source.setVolume(0);
    source.setScrubbingModeEnabled(true);
    source.seekTo(target);
    source
        .createMessage(
            (type, payload) -> {
              if (exoExactHold != hold || engineFrameGeneration != hold.frameToken) return;
              hold.armed = true;
              maybeFinishExoHoldOnPlaybackThread(hold);
              // READY may precede this message, and an audio tail may have no video callback.
              main.post(() -> finishExoHoldIfReady(hold));
            })
        .send();
    // Timeout releases only the optimization; it never acknowledges accurate frame delivery.
    exoExactTimeout = () -> finishExoExactHold(hold);
    main.postDelayed(exoExactTimeout, 2000);
  }

  private void maybeFinishExoHoldOnPlaybackThread(ExoExactHold hold) {
    if (exoExactHold != hold
        || engineFrameGeneration != hold.frameToken
        || !hold.armed
        || !hold.eligibleFrameSeen
        || hold.completionPosted) return;
    hold.completionPosted = true;
    main.post(() -> finishExoExactHold(hold));
  }

  private void finishExoHoldIfReady(ExoExactHold hold) {
    if (exoExactHold != hold
        || exo != hold.source
        || generation != hold.pageToken
        || engineFrameGeneration != hold.frameToken
        || !hold.armed) return;
    int state = hold.source.getPlaybackState();
    // Media3 masks READY to BUFFERING on seek submission. After the playback-thread command
    // barrier, READY/ENDED can safely release this optimization even without another video frame.
    // Neither state proves target-frame accuracy; this never changes the requested exact target.
    if (state == Player.STATE_READY || state == Player.STATE_ENDED) finishExoExactHold(hold);
  }

  private void checkExoEndedAfterCommands(long pageToken, ExoPlayer source) {
    if (generation != pageToken
        || exo != source
        || exoExactHold != null
        || source.getPlaybackState() != Player.STATE_ENDED) return;
    // Ending with AUDIO temporarily disabled is not necessarily the end of the complete media.
    // Recheck after queued track restoration and seek commands, never inside their earlier event.
    source
        .createMessage(
            (type, payload) ->
                main.post(
                    () -> {
                      if (generation != pageToken
                          || exo != source
                          || exoExactHold != null
                          || source.getPlaybackState() != Player.STATE_ENDED
                          || !active
                          || !playRequested
                          || ended
                          || scrubbing) return;
                      ended = true;
                      listener.onEnded(VideoPage.this);
                    }))
        .send();
  }

  private void finishExoExactHold(ExoExactHold hold) {
    if (exoExactHold != hold
        || exo != hold.source
        || generation != hold.pageToken
        || engineFrameGeneration != hold.frameToken) return;
    // Audio-focus and noisy events can revoke playWhenReady while suppression is active.
    playRequested = hold.source.getPlayWhenReady();
    cancelExoExactHold(true);
    applyPlayback();
    syncExoOutputProbe();
    listener.onChanged(this);
    checkExoEndedAfterCommands(hold.pageToken, hold.source);
  }

  private void cancelExoExactHold(boolean releaseSuppression) {
    ExoExactHold hold = exoExactHold;
    exoExactHold = null;
    if (exoExactTimeout != null) main.removeCallbacks(exoExactTimeout);
    exoExactTimeout = null;
    if (releaseSuppression && hold != null && exo == hold.source) {
      exo.setVolume(0);
      exo.setSeekParameters(SeekParameters.EXACT);
      exo.setScrubbingModeEnabled(false);
    }
  }

  /** Caller owns the result. Null means no confirmed new frame is available yet. */
  Bitmap captureScrubFrame() {
    if (!scrubbing || scrubSubmittedCount == 0) return null;
    if (nativeSurfacePlayback && vlc != null && nativeSurfaceFrames != null) {
      if (buffering) return null;
      NativeSurfaceFrames.Preview preview = nativeSurfaceFrames.takePreview();
      // Native time is an approximate caption; a frame-bound commit does not expose media PTS.
      nativeSurfaceFrames.requestPreview(Math.max(0, vlc.getTime()));
      if (preview == null) return null;
      lastScrubPreviewPositionMs = preview.approximatePositionMs;
      return preview.bitmap;
    }
    TextureView texture = currentTexture();
    if (texture == null || !texture.isAvailable()) return null;
    if (exo != null) {
      long pts = lastRenderedPositionUs;
      if (pts == C.TIME_UNSET
          || lastRenderedFrameSequence <= scrubSessionFrameBaseline
          || !matchesSubmittedTarget(pts / 1000)
          || System.nanoTime() < lastFrameReleaseTimeNs) return null;
    } else if (vlc != null) {
      // Native fast seek may stop at a distant keyframe. A fresh rendered texture is still useful;
      // the VLC clock is only an approximate caption, not proof of an exact decoded frame PTS.
      if (buffering) return null;
    } else return null;
    long timestamp = textureTimestamp(texture);
    if (timestamp <= 0
        || timestamp == scrubSessionTextureBaseline
        || timestamp == capturedTextureTimestamp) return null;
    int width = texture.getWidth(), height = texture.getHeight();
    if (width <= 0 || height <= 0) return null;
    float scale = Math.min(256f / width, 144f / height);
    try {
      Bitmap bitmap =
          texture.getBitmap(
              Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
      if (bitmap != null) {
        capturedTextureTimestamp = textureTimestamp(texture);
        lastScrubPreviewPositionMs =
            exo != null ? lastRenderedPositionUs / 1000 : Math.max(0, vlc.getTime());
      }
      return bitmap;
    } catch (RuntimeException | OutOfMemoryError unavailable) {
      return null;
    }
  }

  private boolean matchesSubmittedTarget(long ptsMs) {
    for (int i = 0; i < Math.min(scrubSubmittedCount, scrubSubmittedTargets.length); i++)
      if (Math.abs(ptsMs - scrubSubmittedTargets[i]) <= SCRUB_TOLERANCE_MS + 100) return true;
    return false;
  }

  private TextureView currentTexture() {
    return findTexture(exo != null ? exoView : vlcView);
  }

  private long clampPosition(long position) {
    long target = Math.max(0, position), length = duration();
    return length > 0 ? Math.min(target, Math.max(0, length - 1)) : target;
  }

  private void recordSeekIntent(long target) {
    explicitSeekSerial++;
    explicitSeekTarget = target;
  }

  private void clearScrubState() {
    cancelExoExactHold(true);
    main.removeCallbacks(dispatchScrub);
    if (nativeSurfaceFrames != null) nativeSurfaceFrames.endPreview();
    scrubPending = false;
    if (!scrubbing) return;
    scrubbing = false;
    if (exo != null) {
      try {
        exo.setSeekParameters(SeekParameters.EXACT);
        exo.setScrubbingModeEnabled(false);
      } catch (RuntimeException ignored) {
        // Engine teardown can also follow a decoder failure.
      }
    }
  }

  private static SeekParameters coarseSeekParameters() {
    return new SeekParameters(SCRUB_TOLERANCE_MS * 1000, SCRUB_TOLERANCE_MS * 1000);
  }

  private void vlcFailed(long token, long position, boolean nativeError) {
    if (generation != token) return;
    if (nativeSeekQueue.hasWork()) position = nativeSeekQueue.latestPositionMs();
    else if (pendingSeekSet) position = pendingSeek;
    else if (nativeSurfacePauseRestore) position = nativeSurfacePauseTarget;
    int fallback =
        NativeDecoderPolicy.afterNativeFailure(
            nativeSoftware, triedNativeSoftware, triedNativeHardware, triedExo);
    if (fallback == NativeDecoderPolicy.HARDWARE) {
      rememberSoftwareFailureOnHardwareFrame =
          SeekEnginePolicy.shouldRememberVlcFailure(foreground, active, nativeError);
      startVlc(token, position, false);
      return;
    }
    if (fallback == NativeDecoderPolicy.SOFTWARE) {
      rememberSoftwareFailureOnHardwareFrame = false;
      startVlc(token, position, true);
      return;
    }
    rememberSoftwareFailureOnHardwareFrame = false;
    rememberVlcFailureOnExoFrame =
        !triedExo && SeekEnginePolicy.shouldRememberVlcFailure(foreground, active, nativeError);
    if (fallback == NativeDecoderPolicy.COMPATIBLE) {
      releaseEngineForReplacement();
      startExo(token, position);
    } else fail(token);
  }

  private void compatibleFailed(long token, long position) {
    if (generation != token) return;
    if (!triedVlc) startVlc(token, position);
    else if (!triedNativeHardware) startVlc(token, position, false);
    else if (!triedNativeSoftware) startVlc(token, position, true);
    else fail(token);
  }

  void seekTo(long position) {
    clearScrubState();
    long at = Math.max(0, position);
    long length = duration();
    if (length > 0) at = Math.min(at, Math.max(0, length - 1));
    recordSeekIntent(at);
    ended = false;
    if (exo != null) submitExoExactSeek(at);
    else if (vlc != null) {
      submitNativeSeek(at, false);
    } else if (item != null) {
      pendingSeek = at;
      pendingSeekSet = true;
    }
  }

  long position() {
    if (nativeSeekQueue.hasWork()) return nativeSeekQueue.latestPositionMs();
    if (pendingSeekSet) return pendingSeek;
    if (nativeSurfacePauseRestore) return nativeSurfacePauseTarget;
    if (exo != null) return Math.max(0, exo.getCurrentPosition());
    if (vlc != null) return Math.max(0, vlc.getTime());
    return 0;
  }

  long duration() {
    long length = exo != null ? exo.getDuration() : vlc != null ? vlc.getLength() : 0;
    return length > 0 ? length : item != null ? item.durationMs : 0;
  }

  boolean isPlaying() {
    return active
        && playRequested
        && (exo != null ? exo.isPlaying() : vlc != null && vlc.isPlaying());
  }

  boolean isScrubbing() {
    return scrubbing;
  }

  void release() {
    generation++;
    main.removeCallbacksAndMessages(null);
    releaseEngine();
    engineHistory.flush();
    enginePolicy = null;
    rememberVlcFailureOnExoFrame = false;
    rememberSoftwareFailureOnHardwareFrame = false;
    triedNativeHardware = triedNativeSoftware = nativeSoftware = false;
    triedExo = triedExoHardware = triedVlc = false;
    active = playRequested = audible = false;
    nativeDucked = false;
    firstFrameReady = failed = buffering = ended = false;
    pendingSeek = 0;
    pendingSeekSet = false;
    item = null;
    index = -1;
    errorLabel.setVisibility(GONE);
    if (probeBitmap != null) {
      probeBitmap.recycle();
      probeBitmap = null;
    }
  }

  private void releaseEngine() {
    stopExoOutputProbe();
    resetExoCodecState();
    resetNativeSeekQueue();
    main.removeCallbacks(nativeHealthProbe);
    nativeHealthScheduled = false;
    nativeHealth.reset();
    clearScrubState();
    pendingSeek = 0;
    pendingSeekSet = false;
    engineFrameGeneration++;
    lastRenderedPositionUs = C.TIME_UNSET;
    resetVlcRepeatObservation();
    if (frameProbe != null) main.removeCallbacks(frameProbe);
    frameProbe = null;
    warmingVlc = false;
    vlcRunningRequested = false;
    appliedNativeRate = Float.NaN;
    nativeSurfacePlayback = false;
    nativeSurfaceReattachPosted = false;
    nativeSurfacePauseRestore = false;
    nativeSurfacePauseTarget = 0;
    if (nativeSurfaceFrames != null) nativeSurfaceFrames.unbind();
    if (exo != null) {
      ExoPlayer source = exo;
      exo = null;
      exoView.setPlayer(null);
      source.setVolume(0);
      source.release();
    }
    if (vlc != null) {
      MediaPlayer source = vlc;
      vlc = null;
      NativePlayerRetirement.Lease lease = nativeLease;
      nativeLease = null;
      ParcelFileDescriptor ownedDescriptor = descriptor;
      descriptor = null;
      boolean detached = false;
      try {
        source.setEventListener(null);
        source.setVolume(0);
        // Both Java view ownership layers must be detached on main before the worker calls
        // release. Its duplicate detach then takes the already-detached no-op branch.
        source.detachViews();
        source.getVLCVout().detachViews();
        detached = true;
      } catch (RuntimeException | LinkageError unavailable) {
        // A worker must never touch a possibly attached Android view. Keep that exceptional
        // player and descriptor within the same resource bound instead of guessing ownership.
        lease.quarantineUnreleased(source::release, ownedDescriptor);
      }
      if (detached) {
        // Every successfully constructed native player owns its reservation from before creation.
        // Keep its file descriptor alive until native stop/join actually finishes off the UI
        // thread.
        lease.retire(source::release, ownedDescriptor);
      }
    } else if (nativeLease != null) {
      // Library/player construction failed before a native player could take ownership.
      nativeLease.abandon();
      nativeLease = null;
    }
    if (descriptor != null) {
      try {
        descriptor.close();
      } catch (IOException ignored) {
      }
      descriptor = null;
    }
  }

  /** The gesture belongs to the page, so an error fallback must keep its mute and cancel origin. */
  private void releaseEngineForReplacement() {
    boolean retainScrub = scrubbing;
    if (retainScrub && exo != null) playRequested = exo.getPlayWhenReady();
    releaseEngine();
    if (!retainScrub) return;
    scrubbing = true;
    scrubFrameBaseline = lastRenderedFrameSequence;
    scrubSessionFrameBaseline = scrubFrameBaseline;
    scrubTextureBaseline = textureTimestamp(currentTexture());
    scrubSessionTextureBaseline = scrubTextureBaseline;
    capturedTextureTimestamp = 0;
    lastScrubPreviewPositionMs = -1;
    // The replacement starts at the latest target, preserving play intent and the scrub mute.
    scrubSubmittedTargets[0] = scrubTarget;
    scrubSubmittedCount = 1;
    lastScrubDispatch = SystemClock.uptimeMillis();
  }

  private void fail(long token) {
    if (generation != token) return;
    releaseEngine();
    failed = true;
    firstFrameReady = buffering = false;
    errorLabel.setVisibility(VISIBLE);
    listener.onChanged(this);
    listener.onFailed(this);
  }

  private Uri playbackUri() {
    return item.uri != null && !item.uri.isEmpty()
        ? Uri.parse(item.uri)
        : Uri.fromFile(new File(item.path));
  }

  private static TextureView findTexture(View view) {
    if (view instanceof TextureView) return (TextureView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextureView found = findTexture(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static SurfaceView findSurface(View view) {
    if (view instanceof SurfaceView) return (SurfaceView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        SurfaceView found = findSurface(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static long textureTimestamp(TextureView texture) {
    if (texture == null || !texture.isAvailable()) return 0;
    SurfaceTexture surface = texture.getSurfaceTexture();
    try {
      return surface == null ? 0 : surface.getTimestamp();
    } catch (RuntimeException unavailable) {
      return 0;
    }
  }
}
