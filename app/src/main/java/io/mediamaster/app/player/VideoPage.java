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
  long generation, pendingSeek;
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

  VideoPage(Context context, Supplier<LibVLC> vlcSupplier, Listener listener) {
    super(context);
    this.vlcSupplier = vlcSupplier;
    this.listener = listener;
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
    errorLabel.setText("此视频暂时无法播放\n可继续滑动查看其他视频");
    errorLabel.setTextColor(0xffb6c4bf);
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
    pendingSeek = Math.max(0, start);
    prepareCount++;
    buffering = true;
    final long token = generation;
    if (forceVlc) startVlc(token, pendingSeek);
    else startExo(token, pendingSeek);
  }

  private void startExo(long token, long start) {
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
      source.setAudioAttributes(MOVIE_AUDIO, false);
      source.setVolume(0);
      source.setPlayWhenReady(false);
      source.setHandleAudioBecomingNoisy(true);
      source.setSeekParameters(SeekParameters.EXACT);
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
              if (state == Player.STATE_ENDED && active && playRequested && !ended) {
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
              main.post(
                  () -> {
                    if (current()) startVlc(token, at);
                  });
            }
          });
      source.setMediaItem(MediaItem.fromUri(playbackUri()), start);
      source.prepare();
      listener.onChanged(this);
      applyPlayback();
    } catch (RuntimeException error) {
      if (generation == token) startVlc(token, start);
    }
  }

  private void startVlc(long token, long start) {
    if (generation != token || item == null) return;
    releaseEngine();
    firstFrameReady = false;
    buffering = true;
    failed = false;
    pendingSeek = Math.max(0, start);
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
                        if (active && playRequested && !ended) {
                          ended = true;
                          listener.onEnded(VideoPage.this);
                        }
                        break;
                      case MediaPlayer.Event.EncounteredError:
                        fail(token);
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
        media.setHWDecoderEnabled(true, false);
        if (single) media.addOption(":input-repeat=65535");
        source.setMedia(media);
      } finally {
        media.release();
      }
      source.setRate(speed);
      warmingVlc = true;
      // Muted temporary playback primes a real TextureView frame, including an offscreen page.
      source.play();
      vlcRunningRequested = true;
      listener.onChanged(this);
      applyPlayback();
      beginVlcFrameProbe(token, source);
    } catch (Exception | UnsatisfiedLinkError error) {
      fail(token);
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
        // getBitmap updates the hardware texture even while its attached page is clipped offscreen.
        Bitmap frame = texture.getBitmap(probeBitmap);
        long timestamp = textureTimestamp(texture);
        if (frame != null && timestamp > 0 && timestamp != vlcTextureBaseline && pendingSeek == 0) {
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
      fail(token);
      return;
    }
    if (frameProbe != null) main.postDelayed(frameProbe, 50);
  }

  private void markFirstFrame() {
    if (firstFrameReady) return;
    firstFrameReady = true;
    failed = false;
    errorLabel.setVisibility(GONE);
    listener.onChanged(this);
    listener.onFirstFrame(this);
  }

  private void applyPendingSeek(MediaPlayer source) {
    if (pendingSeek <= 0 || !source.isSeekable()) return;
    long target = pendingSeek;
    pendingSeek = 0;
    vlcTextureBaseline = textureTimestamp(findTexture(vlcView));
    // A native input-repeat must return to zero, not reapply a media-level start-time offset.
    source.setTime(target);
  }

  void setPlayback(boolean active, boolean play, boolean audible) {
    if (active && play) rewindIfEnded();
    this.active = active;
    playRequested = play;
    this.audible = audible;
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
    firstFrameReady = false;
    if (exo != null) {
      exo.seekTo(0);
    } else if (vlc != null) {
      MediaPlayer source = vlc;
      vlcTextureBaseline = textureTimestamp(findTexture(vlcView));
      source.setTime(0);
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
      exo.setVolume(active && playRequested && audible ? 1 : 0);
    }
    if (vlc != null) applyVlcPlayback(vlc);
  }

  private void applyVlcPlayback(MediaPlayer source) {
    source.setVolume(0);
    boolean playing = active && playRequested;
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
    else if (vlc != null && item != null) {
      VideoItem savedItem = item;
      int savedIndex = index;
      long at = position();
      boolean savedActive = active, savedPlay = playRequested, savedAudible = audible;
      prepare(savedItem, savedIndex, at, true, single, speed, foreground);
      setPlayback(savedActive, savedPlay, savedAudible);
    }
  }

  void seekTo(long position) {
    long at = Math.max(0, position);
    long length = duration();
    if (length > 0) at = Math.min(at, Math.max(0, length - 1));
    ended = false;
    if (exo != null) exo.seekTo(at);
    else if (vlc != null) {
      pendingSeek = at;
      if (at == 0) vlc.setTime(0);
      else applyPendingSeek(vlc);
    }
  }

  long position() {
    if (pendingSeek > 0) return pendingSeek;
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
    active = playRequested = audible = false;
    firstFrameReady = failed = buffering = ended = false;
    pendingSeek = 0;
    item = null;
    index = -1;
    errorLabel.setVisibility(GONE);
    if (probeBitmap != null) {
      probeBitmap.recycle();
      probeBitmap = null;
    }
  }

  private void releaseEngine() {
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
