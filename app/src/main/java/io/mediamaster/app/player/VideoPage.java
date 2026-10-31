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
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.ui.PlayerView;
import io.mediamaster.app.R;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** A permanently attached page with its own decoder and retained video texture. */
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
  final VLCVideoLayout vlcView;
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
  private Runnable frameProbe;
  private Bitmap probeBitmap;
  private boolean single, foreground, audible, warmingVlc, ended, vlcRunningRequested;
  private float speed = 1;
  private long vlcTextureBaseline, warmupDeadline;
  private long vlcPreviousTime = -1, vlcSeekFenceTarget = -1, vlcSeekFenceDeadline;
  private boolean vlcNearEndArmed;
  private static final long SCRUB_INTERVAL_MS = 80;
  private static final long SCRUB_TOLERANCE_MS = 250;
  private final Runnable dispatchScrub = this::dispatchScrub;
  private final Runnable observeSeekFrame = this::observeSeekFrame;
  private boolean scrubbing, scrubPending, triedExo, triedVlc;
  private long scrubOrigin, scrubTarget, lastScrubDispatch, scrubTextureBaseline;
  private long capturedTextureTimestamp, scrubFrameBaseline;
  private long scrubSessionFrameBaseline, scrubSessionTextureBaseline;
  private final long[] scrubSubmittedTargets = new long[8];
  private int scrubSubmittedCount;
  long lastScrubPreviewPositionMs = -1;
  private long observedSeekTarget = -1,
      observedSeekStart,
      observedTextureBaseline,
      observedFrameBaseline;
  private long observedGeneration;
  private volatile long engineFrameGeneration;
  volatile long lastRenderedPositionUs = C.TIME_UNSET;
  volatile long lastRenderedFrameSequence;
  private volatile long lastFrameReleaseTimeNs;
  private final PlaybackEngineHistory engineHistory;
  private SeekEnginePolicy enginePolicy;

  VideoPage(Context context, Supplier<LibVLC> vlcSupplier, Listener listener) {
    super(context);
    this.vlcSupplier = vlcSupplier;
    this.listener = listener;
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
    this.item = item;
    this.index = index;
    this.single = single;
    this.speed = Math.max(.1f, speed);
    this.foreground = foreground;
    enginePolicy = engineHistory.forItem(item);
    pendingSeek = Math.max(0, start);
    pendingSeekSet = pendingSeek > 0;
    prepareCount++;
    buffering = true;
    final long token = generation;
    boolean trialBefore = enginePolicy.trialUsed;
    boolean preferVlc = !forceVlc && enginePolicy.chooseVlc(foreground);
    if (trialBefore != enginePolicy.trialUsed) engineHistory.changed();
    if (forceVlc || preferVlc) startVlc(token, pendingSeek);
    else startExo(token, pendingSeek);
  }

  private void startExo(long token, long start) {
    triedExo = true;
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
          new DefaultRenderersFactory(getContext()).setEnableDecoderFallback(true);
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
          });
      source.setAudioAttributes(MOVIE_AUDIO, false);
      source.setVolume(0);
      source.setPlayWhenReady(false);
      source.setHandleAudioBecomingNoisy(true);
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
              listener.onChanged(VideoPage.this);
              if (state == Player.STATE_ENDED && active && playRequested && !ended && !scrubbing) {
                ended = true;
                listener.onEnded(VideoPage.this);
              }
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
              if (enginePolicy != null
                  && active
                  && (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                      || error.errorCode
                          == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                      || error.errorCode
                          == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) {
                enginePolicy.exoFailed = true;
                engineHistory.changed();
              }
              main.post(
                  () -> {
                    if (current()) {
                      if (!triedVlc) startVlc(token, at);
                      else fail(token);
                    }
                  });
            }
          });
      source.setMediaItem(MediaItem.fromUri(playbackUri()), start);
      source.prepare();
      listener.onChanged(this);
      applyPlayback();
    } catch (RuntimeException error) {
      if (generation == token) {
        if (!triedVlc) startVlc(token, start);
        else fail(token);
      }
    }
  }

  private void startVlc(long token, long start) {
    if (generation != token || item == null) return;
    releaseEngineForReplacement();
    if (scrubbing) start = scrubTarget;
    triedVlc = true;
    firstFrameReady = false;
    buffering = true;
    failed = false;
    pendingSeek = Math.max(0, start);
    pendingSeekSet = pendingSeek > 0;
    exoView.setVisibility(INVISIBLE);
    vlcView.setVisibility(VISIBLE);
    try {
      LibVLC library = vlcSupplier.get();
      MediaPlayer source = new MediaPlayer(library);
      vlc = source;
      source.setVolume(0);
      source.attachViews(vlcView, null, false, true);
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
                        if (event.type == MediaPlayer.Event.Playing) {
                          source.setRate(speed);
                          Media.VideoTrack track = source.getCurrentVideoTrack();
                          if (track != null)
                            listener.onVideoSize(VideoPage.this, track.width, track.height);
                        }
                        if (firstFrameReady) applyVlcPlayback(source);
                        listener.onChanged(VideoPage.this);
                        break;
                      case MediaPlayer.Event.Buffering:
                        buffering = event.getBuffering() < 100;
                        listener.onChanged(VideoPage.this);
                        break;
                      case MediaPlayer.Event.EndReached:
                        vlcRunningRequested = false;
                        if (active && playRequested && !ended && !scrubbing) {
                          notifyVlcEnd(source);
                        }
                        break;
                      case MediaPlayer.Event.TimeChanged:
                        observeVlcRepeatBoundary(source);
                        break;
                      case MediaPlayer.Event.EncounteredError:
                        vlcFailed(token, Math.max(0, source.getTime()));
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
        media.setHWDecoderEnabled(true, false);
        // LibVLC 3 has no live input-repeat setter. Keep its decoder looping and change
        // only our
        // completion policy, so a portrait/full-screen toggle never reopens the current
        // input.
        media.addOption(":input-repeat=65535");
        source.setMedia(media);
      } finally {
        media.release();
      }
      source.setRate(speed);
      warmingVlc = true;
      // Muted temporary playback primes a real TextureView frame, including an offscreen
      // page.
      source.play();
      vlcRunningRequested = true;
      listener.onChanged(this);
      applyPlayback();
      beginVlcFrameProbe(token, source);
    } catch (Exception | UnsatisfiedLinkError error) {
      vlcFailed(token, start);
    }
  }

  private void beginVlcFrameProbe(long token, MediaPlayer source) {
    if (frameProbe != null) main.removeCallbacks(frameProbe);
    warmupDeadline = SystemClock.uptimeMillis() + 12000;
    frameProbe = () -> probeVlcFrame(token, source);
    main.postDelayed(frameProbe, 30);
  }

  private void probeVlcFrame(long token, MediaPlayer source) {
    if (generation != token || vlc != source || firstFrameReady || failed) return;
    try {
      applyPendingSeek(source);
      TextureView texture = findTexture(vlcView);
      if (texture != null && texture.isAvailable()) {
        if (probeBitmap == null) probeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        // getBitmap updates the hardware texture even while its attached page is clipped
        // offscreen.
        Bitmap frame = texture.getBitmap(probeBitmap);
        long timestamp = textureTimestamp(texture);
        if (frame != null && timestamp > 0 && timestamp != vlcTextureBaseline && !pendingSeekSet) {
          warmingVlc = false;
          buffering = false;
          applyVlcPlayback(source);
          markFirstFrame();
          return;
        }
      }
    } catch (RuntimeException ignored) {
      // A texture can briefly be unavailable while an orientation change updates its bounds.
    }
    if (SystemClock.uptimeMillis() >= warmupDeadline) {
      vlcFailed(token, pendingSeek);
      return;
    }
    if (frameProbe != null) main.postDelayed(frameProbe, 50);
  }

  private void markFirstFrame() {
    if (firstFrameReady) return;
    firstFrameReady = true;
    if (vlc != null && enginePolicy != null && (!enginePolicy.vlcReady || enginePolicy.vlcFailed)) {
      enginePolicy.vlcReady = true;
      enginePolicy.vlcFailed = false;
      engineHistory.changed();
    }
    failed = false;
    errorLabel.setVisibility(GONE);
    listener.onChanged(this);
    listener.onFirstFrame(this);
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
    if (!active && scrubbing) clearScrubState();
    if (active && play) rewindIfEnded();
    this.active = active;
    playRequested = play;
    this.audible = audible;
    if (!active || !play) {
      vlcPreviousTime = -1;
      vlcNearEndArmed = false;
    }
    applyPlayback();
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
      exo.setVolume(active && playRequested && audible && !scrubbing ? 1 : 0);
    }
    if (vlc != null) applyVlcPlayback(vlc);
  }

  private void applyVlcPlayback(MediaPlayer source) {
    source.setVolume(0);
    boolean playing = active && playRequested && !scrubbing;
    boolean run = playing || warmingVlc;
    if (run != vlcRunningRequested) {
      vlcRunningRequested = run;
      if (run) source.play();
      else source.pause();
    }
    if (run) {
      source.setRate(speed);
      if (playing && audible) source.setVolume(100);
    }
  }

  void setSpeed(float speed) {
    this.speed = Math.max(.1f, speed);
    if (exo != null) exo.setPlaybackSpeed(this.speed);
    if (vlc != null) vlc.setRate(this.speed);
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
    if (scrubbing || failed || (exo == null && vlc == null)) return;
    observedSeekTarget = -1;
    main.removeCallbacks(observeSeekFrame);
    scrubOrigin = position();
    scrubTarget = scrubOrigin;
    scrubFrameBaseline = lastRenderedFrameSequence;
    scrubTextureBaseline = textureTimestamp(currentTexture());
    scrubSessionFrameBaseline = scrubFrameBaseline;
    scrubSessionTextureBaseline = scrubTextureBaseline;
    scrubSubmittedCount = 0;
    lastScrubPreviewPositionMs = -1;
    capturedTextureTimestamp = 0;
    lastScrubDispatch = 0;
    scrubPending = false;
    scrubbing = true;
    if (exo != null) {
      // Scrubbing suppresses playback without losing the user's play/pause intent.
      playRequested = exo.getPlayWhenReady();
      exo.setVolume(0);
      exo.setSeekParameters(coarseSeekParameters());
      exo.setScrubbingModeEnabled(true);
    }
    if (vlc != null) applyVlcPlayback(vlc);
  }

  void scrubTo(long position) {
    if (!scrubbing) return;
    long target = clampPosition(position);
    if (target == scrubTarget && !scrubPending) return;
    scrubTarget = target;
    scrubPending = true;
    main.removeCallbacks(dispatchScrub);
    long delay = Math.max(0, lastScrubDispatch + SCRUB_INTERVAL_MS - SystemClock.uptimeMillis());
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
      exo.seekTo(target);
    } else if (vlc != null) {
      pendingSeek = target;
      pendingSeekSet = true;
      if (vlc.isSeekable()) {
        pendingSeek = 0;
        pendingSeekSet = false;
        fenceVlcSeek(target);
        vlc.setTime(target, true);
      }
    }
  }

  void endScrub(long position, boolean commit) {
    if (!scrubbing) {
      if (commit) seekTo(position);
      return;
    }
    long target = clampPosition(commit ? position : scrubOrigin);
    main.removeCallbacks(dispatchScrub);
    scrubPending = false;
    scrubbing = false;
    ended = false;
    if (exo != null) {
      // Focus loss/noisy handling can change this while scrub suppression is active.
      playRequested = exo.getPlayWhenReady();
      // Dispatch the accurate seek before releasing the playback suppression.
      exo.setSeekParameters(SeekParameters.EXACT);
      if (commit) beginSeekObservation(target);
      exo.seekTo(target);
      exo.setScrubbingModeEnabled(false);
    } else if (vlc != null) {
      pendingSeek = target;
      pendingSeekSet = true;
      fenceVlcSeek(target);
      if (vlc.isSeekable()) {
        pendingSeek = 0;
        pendingSeekSet = false;
        vlc.setTime(target, false);
      }
      // VLC's clock is not a frame PTS. Do not invent comparable latency samples from it.
    }
    applyPlayback();
  }

  /** Caller owns the result. Null means no confirmed new frame is available yet. */
  Bitmap captureScrubFrame() {
    if (!scrubbing || scrubSubmittedCount == 0) return null;
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

  private void clearScrubState() {
    main.removeCallbacks(dispatchScrub);
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

  private void beginSeekObservation(long target) {
    main.removeCallbacks(observeSeekFrame);
    observedSeekTarget = -1;
    if (!active || exo == null || enginePolicy == null) return;
    observedSeekTarget = target;
    observedSeekStart = SystemClock.uptimeMillis();
    observedGeneration = generation;
    observedTextureBaseline = textureTimestamp(currentTexture());
    observedFrameBaseline = lastRenderedFrameSequence;
    main.postDelayed(observeSeekFrame, 16);
  }

  private void observeSeekFrame() {
    if (observedSeekTarget < 0
        || observedGeneration != generation
        || exo == null
        || !active
        || scrubbing) return;
    long elapsed = SystemClock.uptimeMillis() - observedSeekStart;
    if (elapsed > 3000) {
      observedSeekTarget = -1;
      return; // No confirmed frame: unknown, not a fabricated slow measurement.
    }
    long pts = lastRenderedPositionUs;
    long timestamp = textureTimestamp(currentTexture());
    if (lastRenderedFrameSequence > observedFrameBaseline
        && pts != C.TIME_UNSET
        && Math.abs(pts / 1000 - observedSeekTarget) <= 100
        && System.nanoTime() >= lastFrameReleaseTimeNs
        && timestamp > 0
        && timestamp != observedTextureBaseline) {
      enginePolicy.recordExoSeek(elapsed);
      engineHistory.changed();
      observedSeekTarget = -1;
      return;
    }
    main.postDelayed(observeSeekFrame, 16);
  }

  private void vlcFailed(long token, long position) {
    if (generation != token) return;
    if (enginePolicy != null) {
      enginePolicy.recordVlcFailure();
      engineHistory.changed();
    }
    if (!triedExo) {
      releaseEngineForReplacement();
      startExo(token, position);
    } else fail(token);
  }

  void seekTo(long position) {
    clearScrubState();
    observedSeekTarget = -1;
    main.removeCallbacks(observeSeekFrame);
    long at = Math.max(0, position);
    long length = duration();
    if (length > 0) at = Math.min(at, Math.max(0, length - 1));
    ended = false;
    if (exo != null) exo.seekTo(at);
    else if (vlc != null) {
      fenceVlcSeek(at);
      pendingSeek = at;
      pendingSeekSet = true;
      applyPendingSeek(vlc);
    }
  }

  long position() {
    if (pendingSeekSet) return pendingSeek;
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

  void release() {
    generation++;
    main.removeCallbacksAndMessages(null);
    releaseEngine();
    engineHistory.flush();
    enginePolicy = null;
    triedExo = triedVlc = false;
    active = playRequested = audible = false;
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
    clearScrubState();
    pendingSeek = 0;
    pendingSeekSet = false;
    engineFrameGeneration++;
    lastRenderedPositionUs = C.TIME_UNSET;
    observedSeekTarget = -1;
    main.removeCallbacks(observeSeekFrame);
    resetVlcRepeatObservation();
    if (frameProbe != null) main.removeCallbacks(frameProbe);
    frameProbe = null;
    warmingVlc = false;
    vlcRunningRequested = false;
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
      source.setEventListener(null);
      source.setVolume(0);
      source.detachViews();
      source.release();
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
    // The replacement starts at this latest target, still paused/muted until the finger lifts.
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
