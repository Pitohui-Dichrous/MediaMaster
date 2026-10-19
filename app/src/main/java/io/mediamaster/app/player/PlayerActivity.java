package io.mediamaster.app.player;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
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
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Local playback with an uninterrupted Exo repeat pipeline and VLC codec fallback. */
@UnstableApi
public final class PlayerActivity extends Activity {
  private static final int ACCENT = 0xff60e0c0,
      INK = 0xff081310,
      TEXT = 0xfff4f8f7,
      MUTED = 0xffb6c4bf;
  private static final String EXTRA_QUEUE = "queue",
      EXTRA_INDEX = "index",
      EXTRA_SHUFFLE = "shuffle";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ArrayList<VideoItem> queue = new ArrayList<>();
  private PlaybackOrder order;
  private String queueToken;
  private FrameLayout root, videoStage;
  private PlayerView exoView;
  private VLCVideoLayout vlcView;
  private LinearLayout topBar, bottomBar;
  private TextView title, subtitle, currentTime, totalTime, previewTime, hudValue;
  private ImageView previewImage, hudIcon;
  private LinearLayout seekPreview, gestureHud;
  private SeekPreviewLoader previewLoader;
  private View touchLayer;
  private static final int GESTURE_NONE = 0,
      GESTURE_SEEK = 1,
      GESTURE_BRIGHTNESS = 2,
      GESTURE_VOLUME = 3,
      GESTURE_BOOST = 4,
      GESTURE_PAGE = 5;
  private int gestureKind;
  private boolean touchDown, boosting, boostWasPlaying;
  private float gestureStartBrightness;
  private int gestureStartVolume;
  private final Runnable hideHud =
      () -> {
        if (gestureHud != null) gestureHud.setVisibility(View.GONE);
      };
  private final Runnable longPress =
      () -> {
        if (!touchDown || gestureKind != GESTURE_NONE || this.scrubbing || !this.started) return;
        gestureKind = GESTURE_BOOST;
        cancelTapRecognition();
        beginBoost();
      };
  private ImageButton playButton, modeButton, rotateButton, portraitButton;
  private boolean portraitMode, pageTransitioning, pageAwaitingFrame;
  private int pageGeneration;
  private VelocityTracker pageVelocity;
  private final Runnable pageLoadFallback = this::finishPageEntrance;
  private final Runnable automaticAdvance = this::advanceAutomatically;
  private ProgressBar loading;
  private SeekBar seek;
  private DirectionalThumbDrawable seekThumb;
  private long thumbDirectionAnchor;
  private boolean buffering;
  private long bufferingSince;
  private final Runnable displayLoading = this::updateLoadingIndicator;
  private ExoPlayer exo;
  private LibVLC libVlc;
  private MediaPlayer vlc;
  private ParcelFileDescriptor vlcDescriptor;
  private AudioManager audioManager;
  private AudioFocusRequest vlcFocus;
  private boolean useVlc,
      started,
      shouldPlay = true,
      chromeVisible = true,
      scrubbing,
      gestureSeeking,
      orientationOverride;
  private boolean noisyRegistered, focusPaused, handlingEnd;
  private boolean playVisualReady, lastPlayVisual;
  private long restorePosition, gestureOrigin, gestureTarget, vlcPendingSeek;
  private float touchX, touchY, sensitivity = 1f, speed = 1f;
  private int desiredOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
  private final Runnable hideChrome = () -> setChrome(false);
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (!started) return;
          updateProgress();
          handler.postDelayed(this, 250);
        }
      };
  private final BroadcastReceiver noisy =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          setPlaying(false);
          showChrome();
        }
      };

  public static void launch(
      Activity activity, ArrayList<VideoItem> videos, int index, boolean shuffle) {
    if (videos == null || videos.isEmpty()) return;
    String token = QueueStore.put(activity.getApplicationContext(), videos);
    activity.startActivity(
        new Intent(activity, PlayerActivity.class)
            .putExtra(EXTRA_QUEUE, token)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_SHUFFLE, shuffle));
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams a = getWindow().getAttributes();
      a.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      getWindow().setAttributes(a);
    }
    queueToken =
        state != null ? state.getString(EXTRA_QUEUE) : getIntent().getStringExtra(EXTRA_QUEUE);
    queue = QueueStore.get(this, queueToken);
    if (queue.isEmpty() && getIntent().getData() != null) openExternal(getIntent().getData());
    if (queue.isEmpty()) {
      Toast.makeText(this, "视频列表已失效，请重新选择视频", Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    int mode =
        getSharedPreferences("player", MODE_PRIVATE)
            .getInt("playback_mode", PlaybackOrder.SEQUENTIAL);
    if (getIntent().getBooleanExtra(EXTRA_SHUFFLE, false)) mode = PlaybackOrder.SHUFFLE;
    if (state != null) mode = state.getInt("mode", mode);
    int index = state != null ? state.getInt(EXTRA_INDEX) : getIntent().getIntExtra(EXTRA_INDEX, 0);
    order = new PlaybackOrder(queue.size(), index, mode);
    sensitivity = getSharedPreferences("player", MODE_PRIVATE).getFloat("seek_sensitivity", 1f);
    if (state != null) {
      portraitMode = state.getBoolean("portrait_mode", false);
      restorePosition = state.getLong("position");
      shouldPlay = state.getBoolean("playing", true);
      orientationOverride = state.getBoolean("orientation_override");
      desiredOrientation = state.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      useVlc = state.getBoolean("vlc");
      speed = state.getFloat("speed", 1f);
    }
    audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    previewLoader = new SeekPreviewLoader(this);
    buildUi();
    refreshVideoLabels();
    if (portraitMode) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    else if (orientationOverride) setRequestedOrientation(desiredOrientation);
    else adaptOrientation(currentItem().width, currentItem().height);
  }

  private void openExternal(Uri uri) {
    if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) return;
    String name = uri.getLastPathSegment();
    long size = 0;
    try (Cursor cursor =
        getContentResolver()
            .query(
                uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
      if (cursor != null && cursor.moveToFirst()) {
        name = cursor.getString(0);
        size = cursor.isNull(1) ? 0 : cursor.getLong(1);
      }
    } catch (Exception ignored) {
    }
    if (name == null) name = "本地视频";
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".m3u8") || lower.endsWith(".m4u8")) {
      Toast.makeText(this, "映序仅播放本地视频文件", Toast.LENGTH_LONG).show();
      return;
    }
    queue.add(
        new VideoItem(
            uri.toString(),
            name,
            uri.toString(),
            "file".equals(uri.getScheme()) ? uri.getPath() : "",
            size,
            0,
            0,
            0,
            0,
            0));
    queueToken = QueueStore.put(getApplicationContext(), queue);
  }

  private void buildUi() {
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    videoStage = new FrameLayout(this);
    root.addView(videoStage, new FrameLayout.LayoutParams(-1, -1));
    exoView = new PlayerView(this);
    exoView.setUseController(false);
    exoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
    exoView.setKeepContentOnPlayerReset(true);
    exoView.setShutterBackgroundColor(Color.TRANSPARENT);
    exoView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
    exoView.setFocusable(false);
    exoView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    videoStage.addView(exoView, new FrameLayout.LayoutParams(-1, -1));
    vlcView = new VLCVideoLayout(this);
    vlcView.setVisibility(View.GONE);
    videoStage.addView(vlcView, new FrameLayout.LayoutParams(-1, -1));
    touchLayer = new View(this);
    touchLayer.setContentDescription("视频画面，点按显示控制，双击播放暂停，长按临时两倍速");
    touchLayer.setOnTouchListener(this::onVideoTouch);
    root.addView(touchLayer, new FrameLayout.LayoutParams(-1, -1));

    topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
    ImageButton back = icon(PlayerIcons.BACK, "返回视频库", 48, TEXT);
    back.setOnClickListener(v -> finish());
    topBar.addView(back);
    title = text(14, TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setMaxLines(1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setPadding(dp(8), 0, dp(8), 0);
    topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    subtitle = text(12, TEXT);
    subtitle.setPadding(dp(4), 0, dp(8), 0);
    topBar.addView(subtitle);
    ImageButton more = icon(PlayerIcons.MORE, "播放设置", 48, TEXT);
    more.setOnClickListener(v -> showSettings());
    topBar.addView(more);
    root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    bottomBar = new LinearLayout(this);
    bottomBar.setOrientation(LinearLayout.VERTICAL);
    bottomBar.setPadding(dp(20), dp(4), dp(20), dp(8));
    LinearLayout times = new LinearLayout(this);
    times.setGravity(Gravity.CENTER_VERTICAL);
    currentTime = text(12, TEXT);
    currentTime.setTypeface(Typeface.MONOSPACE);
    totalTime = text(12, TEXT);
    totalTime.setTypeface(Typeface.MONOSPACE);
    totalTime.setGravity(Gravity.RIGHT);
    times.addView(currentTime, new LinearLayout.LayoutParams(0, -2, 1));
    times.addView(totalTime);
    bottomBar.addView(times);
    seek = new SeekBar(this);
    seek.setMax(10000);
    seek.setProgressTintList(ColorStateList.valueOf(0xe6ffffff));
    seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55ffffff));
    seekThumb = new DirectionalThumbDrawable(this);
    seek.setThumb(seekThumb);
    seek.setSplitTrack(false);
    if (Build.VERSION.SDK_INT >= 29) {
      seek.setMinHeight(dp(2));
      seek.setMaxHeight(dp(2));
    }
    // Leave the full irregular artwork visible at both ends of the track.
    seek.setPadding(dp(18), 0, dp(18), 0);
    seek.setContentDescription("播放进度");
    bottomBar.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onStartTrackingTouch(SeekBar bar) {
            handler.removeCallbacks(longPress);
            endBoost();
            scrubbing = true;
            gestureOrigin = position();
            gestureTarget = gestureOrigin;
            thumbDirectionAnchor = gestureOrigin;
            updateLoadingIndicator();
            handler.removeCallbacks(hideChrome);
            showSeekPreview(gestureTarget);
          }

          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            if (user) {
              gestureTarget = duration() * progress / 10000L;
              currentTime.setText(formatTime(gestureTarget));
              showSeekPreview(gestureTarget);
            }
          }

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            hideSeekPreview();
            scrubbing = false;
            seekTo(gestureTarget);
            updateLoadingIndicator();
            showChrome();
          }
        });
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER);
    modeButton = icon(PlayerIcons.REPEAT, "切换播放模式", 48, TEXT);
    modeButton.setOnClickListener(v -> cycleMode());
    controls.addView(modeButton);
    space(controls);
    ImageButton previous = icon(PlayerIcons.PREVIOUS, "上一个视频", 48, TEXT);
    previous.setOnClickListener(v -> changeVideo(order.previous()));
    controls.addView(previous);
    playButton = icon(PlayerIcons.PAUSE, "暂停", 48, TEXT);
    playButton.setPadding(dp(9), dp(9), dp(9), dp(9));
    playButton.setOnClickListener(
        v -> {
          setPlaying(!playRequested());
          showChrome();
        });
    LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(48), dp(48));
    playParams.setMargins(dp(8), 0, dp(8), 0);
    controls.addView(playButton, playParams);
    ImageButton next = icon(PlayerIcons.NEXT, "下一个视频", 48, TEXT);
    next.setOnClickListener(v -> changeVideo(order.next(false)));
    controls.addView(next);
    space(controls);
    portraitButton = icon(PlayerIcons.PORTRAIT_MODE, "竖屏播放", 48, TEXT);
    portraitButton.setOnClickListener(v -> setPortraitMode(!portraitMode));
    controls.addView(portraitButton);
    rotateButton = icon(PlayerIcons.ROTATE, "切换横竖屏，长按恢复自动适配", 48, TEXT);
    rotateButton.setOnClickListener(v -> toggleOrientation());
    rotateButton.setOnLongClickListener(
        v -> {
          orientationOverride = false;
          adaptOrientation(videoWidth(), videoHeight());
          return true;
        });
    controls.addView(rotateButton);
    bottomBar.addView(controls);
    root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

    seekPreview = new LinearLayout(this);
    seekPreview.setOrientation(LinearLayout.VERTICAL);
    seekPreview.setGravity(Gravity.CENTER);
    seekPreview.setVisibility(View.GONE);
    seekPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    previewImage = new ImageView(this);
    previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
    GradientDrawable frame = rounded(0x44000000, 7);
    frame.setStroke(dp(1), 0xaaffffff);
    previewImage.setBackground(frame);
    previewImage.setClipToOutline(true);
    previewImage.setPadding(dp(1), dp(1), dp(1), dp(1));
    seekPreview.addView(previewImage, new LinearLayout.LayoutParams(dp(144), dp(81)));
    previewTime = text(13, TEXT);
    previewTime.setGravity(Gravity.CENTER);
    previewTime.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    previewTime.setPadding(0, dp(6), 0, 0);
    seekPreview.addView(previewTime);
    root.addView(seekPreview, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

    gestureHud = new LinearLayout(this);
    gestureHud.setGravity(Gravity.CENTER_VERTICAL);
    gestureHud.setPadding(dp(12), dp(8), dp(12), dp(8));
    gestureHud.setBackground(rounded(0x55000000, 12));
    gestureHud.setVisibility(View.GONE);
    hudIcon = new ImageView(this);
    gestureHud.addView(hudIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
    hudValue = text(14, TEXT);
    hudValue.setPadding(dp(8), 0, 0, 0);
    gestureHud.addView(hudValue);
    FrameLayout.LayoutParams hudParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    hudParams.topMargin = dp(62);
    root.addView(gestureHud, hudParams);
    loading = new ProgressBar(this);
    loading.setVisibility(View.GONE);
    loading.setIndeterminateTintList(ColorStateList.valueOf(TEXT));
    root.addView(loading, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int left = insets.getSystemWindowInsetLeft(), right = insets.getSystemWindowInsetRight();
          int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
          if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
            right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
            top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
          }
          topBar.setPadding(dp(8) + left, dp(6) + top, dp(8) + right, dp(6));
          int gutter = getResources().getConfiguration().screenWidthDp < 360 ? dp(8) : dp(20);
          bottomBar.setPadding(gutter + left, dp(4), gutter + right, dp(8) + bottom);
          FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) gestureHud.getLayoutParams();
          hp.topMargin = dp(62) + top;
          gestureHud.setLayoutParams(hp);
          return insets;
        });
    root.requestApplyInsets();
    updateModeUi();
    updatePresentationUi();
    immersive();
  }

  private final GestureDetector.SimpleOnGestureListener tapListener =
      new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
          return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
          if (!gestureSeeking && gestureKind == GESTURE_NONE) {
            if (chromeVisible) setChrome(false);
            else showChrome();
          }
          return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
          setPlaying(!playRequested());
          showChrome();
          return true;
        }
      };
  private GestureDetector taps;

  private boolean onVideoTouch(View view, MotionEvent event) {
    if (pageTransitioning) return true;
    if (taps == null) taps = new GestureDetector(this, tapListener);
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      recyclePageVelocity();
      if (portraitMode) pageVelocity = VelocityTracker.obtain();
    }
    if (pageVelocity != null) pageVelocity.addMovement(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        touchDown = true;
        touchX = event.getX();
        touchY = event.getY();
        gestureOrigin = position();
        thumbDirectionAnchor = gestureOrigin;
        gestureKind = GESTURE_NONE;
        gestureSeeking = false;
        gestureStartBrightness = windowBrightness();
        gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        handler.removeCallbacks(hideChrome);
        handler.removeCallbacks(hideHud);
        gestureHud.setVisibility(View.GONE);
        taps.onTouchEvent(event);
        handler.removeCallbacks(longPress);
        if (touchX > dp(20) && touchX < view.getWidth() - dp(20))
          handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!touchDown) return true;
        float dx = event.getX() - touchX, dy = event.getY() - touchY;
        if (Math.hypot(dx, dy) > dp(8)) handler.removeCallbacks(longPress);
        if (gestureKind == GESTURE_NONE && touchX > dp(20) && touchX < view.getWidth() - dp(20)) {
          if (Math.abs(dx) > dp(14) && Math.abs(dx) > Math.abs(dy) * 1.3f && duration() > 0) {
            gestureKind = GESTURE_SEEK;
            gestureSeeking = true;
            cancelTapRecognition();
            showChrome();
            updateLoadingIndicator();
          } else if (Math.abs(dy) > dp(14) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
            gestureKind =
                portraitMode
                    ? GESTURE_PAGE
                    : touchX < view.getWidth() / 2f ? GESTURE_BRIGHTNESS : GESTURE_VOLUME;
            cancelTapRecognition();
            setChrome(false);
          }
        }
        if (gestureKind == GESTURE_SEEK) {
          long delta =
              GestureSeek.deltaMs(
                  dx, Math.max(dp(240), view.getWidth() - dp(40)), duration(), sensitivity);
          gestureTarget = GestureSeek.targetMs(gestureOrigin, delta, duration());
          showSeekPreview(gestureTarget);
        } else if (gestureKind == GESTURE_BRIGHTNESS || gestureKind == GESTURE_VOLUME) {
          updateVerticalGesture(dy, Math.max(1, view.getHeight()));
        } else if (gestureKind == GESTURE_PAGE) {
          videoStage.setTranslationY(PortraitSwipe.offset(dy, view.getHeight()));
        } else if (gestureKind == GESTURE_NONE) taps.onTouchEvent(event);
        return true;
      case MotionEvent.ACTION_UP:
        if (!touchDown) return true;
        touchDown = false;
        handler.removeCallbacks(longPress);
        int finished = gestureKind;
        if (finished == GESTURE_SEEK) {
          hideSeekPreview();
          gestureSeeking = false;
          seekTo(gestureTarget);
          updateLoadingIndicator();
          showChrome();
        } else if (finished == GESTURE_BOOST) {
          endBoost();
        } else if (finished == GESTURE_PAGE) {
          float velocity = 0;
          if (pageVelocity != null) {
            pageVelocity.computeCurrentVelocity(1000);
            velocity = pageVelocity.getYVelocity();
          }
          finishPortraitSwipe(event.getY() - touchY, velocity, view.getHeight());
        } else if (finished == GESTURE_BRIGHTNESS || finished == GESTURE_VOLUME) {
          handler.postDelayed(hideHud, 400);
        } else {
          taps.onTouchEvent(event);
          scheduleHide();
        }
        gestureKind = GESTURE_NONE;
        recyclePageVelocity();
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_CANCEL:
        cancelInteractions();
        cancelTapRecognition();
        return true;
      default:
        return true;
    }
  }

  private void cancelTapRecognition() {
    if (taps == null) return;
    long now = SystemClock.uptimeMillis();
    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, touchX, touchY, 0);
    taps.onTouchEvent(cancel);
    cancel.recycle();
  }

  /** Presentation changes reuse the current decoder, position and queue. */
  private void setPortraitMode(boolean enabled) {
    if (portraitMode == enabled) return;
    cancelInteractions();
    cancelTapRecognition();
    portraitMode = enabled;
    if (enabled) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    else if (orientationOverride) setRequestedOrientation(desiredOrientation);
    else adaptOrientation(videoWidth(), videoHeight());
    updatePresentationUi();
    portraitButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    showChrome();
  }

  private void updatePresentationUi() {
    if (portraitButton == null) return;
    portraitButton.setImageDrawable(
        new PlayerIcons(portraitMode ? PlayerIcons.FULLSCREEN : PlayerIcons.PORTRAIT_MODE, TEXT));
    portraitButton.setContentDescription(portraitMode ? "全屏播放" : "竖屏播放");
    portraitButton.setTooltipText(portraitMode ? "全屏播放" : "竖屏播放");
    portraitButton.setSelected(portraitMode);
    rotateButton.setVisibility(portraitMode ? View.GONE : View.VISIBLE);
    touchLayer.setContentDescription(
        portraitMode
            ? "竖屏视频，左右滑动调进度，上滑下一条，下滑上一条，双击播放暂停，长按两倍速"
            : "视频画面，左右滑动调进度，左侧上下调亮度，右侧上下调音量，双击播放暂停，长按两倍速");
  }

  private void recyclePageVelocity() {
    if (pageVelocity != null) {
      pageVelocity.recycle();
      pageVelocity = null;
    }
  }

  private void resetPageMotion() {
    pageGeneration++;
    handler.removeCallbacks(pageLoadFallback);
    pageTransitioning = false;
    pageAwaitingFrame = false;
    if (videoStage != null) {
      videoStage.animate().withEndAction(null).cancel();
      videoStage.setTranslationY(0);
      videoStage.setAlpha(1);
    }
  }

  private void finishPortraitSwipe(float dy, float velocity, float height) {
    int direction =
        queue.size() < 2
            ? 0
            : PortraitSwipe.direction(
                dy, velocity, height, getResources().getDisplayMetrics().density);
    if (direction < 0 && !order.hasPrevious()) direction = 0;
    final int chosen = direction;
    final int generation = ++pageGeneration;
    pageTransitioning = true;
    handler.removeCallbacks(hideChrome);
    Runnable settled =
        () -> {
          if (generation != pageGeneration || !started || !portraitMode) return;
          if (chosen == 0) {
            pageTransitioning = false;
            showChrome();
            return;
          }
          int index = chosen > 0 ? order.next(false) : order.previous();
          changeVideo(index);
          // Frame callbacks complete the arrival. A bounded fallback leaves gestures responsive
          // even for a missing/damaged file; the existing delayed loading/error UI handles that
          // case.
          pageTransitioning = true;
          pageAwaitingFrame = true;
          videoStage.setTranslationY(ValueAnimator.areAnimatorsEnabled() ? chosen * dp(28) : 0);
          handler.postDelayed(pageLoadFallback, 1800);
          touchLayer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        };
    if (!ValueAnimator.areAnimatorsEnabled()) {
      videoStage.setTranslationY(0);
      settled.run();
    } else
      videoStage
          .animate()
          .translationY(chosen == 0 ? 0 : -chosen * height)
          .setDuration(160)
          .setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f))
          .withEndAction(settled)
          .start();
  }

  private void finishPageEntrance() {
    if (!pageAwaitingFrame || !started) return;
    pageAwaitingFrame = false;
    handler.removeCallbacks(pageLoadFallback);
    final int generation = pageGeneration;
    Runnable finished =
        () -> {
          if (generation != pageGeneration) return;
          pageTransitioning = false;
          showChrome();
        };
    if (!ValueAnimator.areAnimatorsEnabled()) {
      videoStage.setTranslationY(0);
      finished.run();
    } else
      videoStage
          .animate()
          .translationY(0)
          .setDuration(160)
          .setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f))
          .withEndAction(finished)
          .start();
  }

  private void showSeekPreview(long at) {
    if (previewLoader == null || duration() <= 0) return;
    seek.setProgress((int) Math.min(10000, at * 10000 / duration()));
    currentTime.setText(formatTime(at));
    totalTime.setText(formatTime(duration()));
    // A small dead zone keeps jitter around one timestamp from flickering directions.
    if (Math.abs(at - thumbDirectionAnchor) >= 120) {
      seekThumb.setDirection(at > thumbDirectionAnchor);
      thumbDirectionAnchor = at;
    }
    if (seekPreview.getVisibility() != View.VISIBLE) previewImage.setImageDrawable(null);
    seekPreview.setVisibility(View.VISIBLE);
    previewTime.setText(formatTime(at) + " / " + formatTime(duration()));
    String identity = currentItem().id;
    previewLoader.request(
        currentItem(),
        at,
        (bitmap, requested) -> {
          if (started
              && (scrubbing || gestureSeeking)
              && currentItem().id.equals(identity)
              && seekPreview.getVisibility() == View.VISIBLE) {
            previewImage.setImageBitmap(bitmap);
          }
        });
  }

  private void hideSeekPreview() {
    if (seekPreview != null) seekPreview.setVisibility(View.GONE);
    if (previewImage != null) previewImage.setImageDrawable(null);
    if (previewLoader != null) previewLoader.reset();
  }

  private float windowBrightness() {
    float value = getWindow().getAttributes().screenBrightness;
    if (value < 0) {
      try {
        value =
            Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
      } catch (Settings.SettingNotFoundException ignored) {
        value = .5f;
      }
    }
    return Math.max(.01f, Math.min(1f, value));
  }

  private void updateVerticalGesture(float dy, float height) {
    if (gestureKind == GESTURE_BRIGHTNESS) {
      float value = GestureLevels.adjust(gestureStartBrightness, dy, height, .01f);
      WindowManager.LayoutParams attributes = getWindow().getAttributes();
      attributes.screenBrightness = value;
      getWindow().setAttributes(attributes);
      showHud(
          PlayerIcons.BRIGHTNESS,
          Math.round(value * 100) + "%",
          "亮度 " + Math.round(value * 100) + "%");
    } else {
      int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
      int value =
          Math.round(GestureLevels.adjust(gestureStartVolume / (float) max, dy, height, 0f) * max);
      try {
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != value)
          audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
      } catch (SecurityException ignored) {
      }
      int actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
      int percent = Math.round(actual * 100f / max);
      showHud(
          actual == 0 ? PlayerIcons.VOLUME_OFF : PlayerIcons.VOLUME,
          percent + "%",
          "音量 " + percent + "%");
    }
  }

  private void showHud(int glyph, String value, String description) {
    handler.removeCallbacks(hideHud);
    hudIcon.setImageDrawable(new PlayerIcons(glyph, TEXT));
    hudValue.setText(value);
    gestureHud.setContentDescription(description);
    gestureHud.setVisibility(View.VISIBLE);
  }

  private void beginBoost() {
    if (boosting) return;
    boostWasPlaying = playRequested();
    boosting = true;
    setChrome(false);
    hideSeekPreview();
    applyPlaybackRate();
    if (!boostWasPlaying) setPlaying(true);
    showHud(PlayerIcons.FAST_FORWARD, "2×", "长按两倍速");
    touchLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
  }

  private void endBoost() {
    if (!boosting) return;
    boosting = false;
    applyPlaybackRate();
    if (!boostWasPlaying) setPlaying(false);
    if (gestureHud != null) gestureHud.setVisibility(View.GONE);
  }

  private float playbackRate() {
    return boosting ? 2f : speed;
  }

  private void applyPlaybackRate() {
    if (exo != null) exo.setPlaybackSpeed(playbackRate());
    else if (vlc != null) vlc.setRate(playbackRate());
  }

  private void cancelInteractions() {
    touchDown = false;
    handler.removeCallbacks(longPress);
    handler.removeCallbacks(hideHud);
    endBoost();
    gestureKind = GESTURE_NONE;
    resetPageMotion();
    recyclePageVelocity();
    gestureSeeking = false;
    scrubbing = false;
    hideSeekPreview();
    updateLoadingIndicator();
    if (gestureHud != null) gestureHud.setVisibility(View.GONE);
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (order == null) return;
    started = true;
    playVisualReady = false;
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          noisy,
          new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
          Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    noisyRegistered = true;
    startCurrent(restorePosition);
    handler.post(ticker);
    showChrome();
  }

  @Override
  protected void onStop() {
    cancelInteractions();
    if (order != null) {
      restorePosition = position();
      shouldPlay = playRequested();
    }
    started = false;
    handler.removeCallbacksAndMessages(null);
    releasePlayers();
    if (noisyRegistered) {
      unregisterReceiver(noisy);
      noisyRegistered = false;
    }
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (previewLoader != null) previewLoader.close();
    if (isFinishing()) QueueStore.forget(queueToken);
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (order == null) return;
    state.putString(EXTRA_QUEUE, queueToken);
    state.putInt(EXTRA_INDEX, order.current());
    state.putInt("mode", order.mode());
    state.putLong("position", started ? position() : restorePosition);
    state.putBoolean("playing", started ? playRequested() : shouldPlay);
    state.putBoolean("orientation_override", orientationOverride);
    state.putInt("orientation", desiredOrientation);
    state.putBoolean("vlc", useVlc);
    state.putFloat("speed", speed);
    state.putBoolean("portrait_mode", portraitMode);
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    cancelInteractions();
    root.requestApplyInsets();
    immersive();
  }

  private VideoItem currentItem() {
    return queue.get(order.current());
  }

  private void startCurrent(long start) {
    handlingEnd = false;
    refreshVideoLabels();
    if (useVlc) startVlc(start);
    else startExo(start);
  }

  private void startExo(long start) {
    releasePlayers();
    useVlc = false;
    exoView.setVisibility(View.VISIBLE);
    vlcView.setVisibility(View.GONE);
    showLoading(true, "正在打开视频");
    DefaultLoadControl buffer =
        new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 90000, 250, 500)
            .setBackBuffer(15000, true)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .build();
    DefaultRenderersFactory renderers =
        new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
    if (Build.VERSION.SDK_INT >= 34) {
      // Decode intervening GOP frames without presenting them, while retaining exact seek targets.
      renderers.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(true);
    }
    exo = new ExoPlayer.Builder(this, renderers).setLoadControl(buffer).build();
    exo.setAudioAttributes(
        new androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    exo.setHandleAudioBecomingNoisy(true);
    exo.setSeekParameters(SeekParameters.EXACT);
    exo.setRepeatMode(
        order.mode() == PlaybackOrder.SINGLE ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    exo.setPlaybackSpeed(playbackRate());
    exoView.setPlayer(exo);
    final ExoPlayer source = exo;
    exo.addListener(
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(int state) {
            if (exo != source) return;
            showLoading(state == Player.STATE_BUFFERING, "正在缓冲");
            if (state == Player.STATE_ENDED && !handlingEnd) {
              handlingEnd = true;
              handler.post(
                  () -> {
                    if (started && exo == source) advanceAutomatically();
                  });
            }
            updateProgress();
          }

          @Override
          public void onIsPlayingChanged(boolean playing) {
            if (exo == source) updatePlayUi();
          }

          @Override
          public void onRenderedFirstFrame() {
            if (exo == source) finishPageEntrance();
          }

          @Override
          public void onVideoSizeChanged(VideoSize size) {
            if (exo == source && !orientationOverride) adaptOrientation(size.width, size.height);
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            if (exo != source) return;
            long at = position();
            shouldPlay = playRequested();
            handler.post(
                () -> {
                  if (started && exo == source && !useVlc) {
                    useVlc = true;
                    startVlc(at);
                    toast("已切换兼容解码");
                  }
                });
          }
        });
    try {
      exo.setMediaItem(MediaItem.fromUri(playbackUri(currentItem())), Math.max(0, start));
      exo.prepare();
      exo.setPlayWhenReady(shouldPlay);
    } catch (Exception error) {
      useVlc = true;
      startVlc(start);
    }
    updatePlayUi();
  }

  private void startVlc(long start) {
    releasePlayers();
    useVlc = true;
    exoView.setVisibility(View.GONE);
    vlcView.setVisibility(View.VISIBLE);
    vlcPendingSeek = Math.max(0, start);
    showLoading(true, "正在使用兼容解码");
    try {
      ArrayList<String> options = new ArrayList<>();
      options.add("--no-video-title-show");
      options.add("--no-stats");
      options.add("--aout=opensles");
      options.add("--file-caching=750");
      libVlc = new LibVLC(this, options);
      vlc = new MediaPlayer(libVlc);
      vlc.attachViews(vlcView, null, false, false);
      vlc.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
      final MediaPlayer source = vlc;
      vlc.setEventListener(
          event ->
              handler.post(
                  () -> {
                    if (!started || vlc != source) return;
                    switch (event.type) {
                      case MediaPlayer.Event.Playing:
                        applyVlcResumeOnce(source);
                        showLoading(false, "");
                        finishPageEntrance();
                        updatePlayUi();
                        if (!shouldPlay) vlc.pause();
                        else vlc.setRate(playbackRate());
                        Media.VideoTrack track = vlc.getCurrentVideoTrack();
                        if (!orientationOverride && track != null)
                          adaptOrientation(track.width, track.height);
                        break;
                      case MediaPlayer.Event.Paused:
                        applyVlcResumeOnce(source);
                        updatePlayUi();
                        break;
                      case MediaPlayer.Event.SeekableChanged:
                        applyVlcResumeOnce(source);
                        break;
                      case MediaPlayer.Event.Stopped:
                        updatePlayUi();
                        break;
                      case MediaPlayer.Event.Buffering:
                        showLoading(event.getBuffering() < 100, "正在缓冲");
                        break;
                      case MediaPlayer.Event.EndReached:
                        if (!handlingEnd) {
                          handlingEnd = true;
                          advanceAutomatically();
                        }
                        break;
                      case MediaPlayer.Event.EncounteredError:
                        playbackFailed();
                        break;
                      default:
                        break;
                    }
                  }));
      Uri uri = playbackUri(currentItem());
      Media media;
      if ("content".equals(uri.getScheme())) {
        // Native VLC cannot resolve Android ContentProvider URIs itself. Keep the
        // granted descriptor open for the whole playback session, including repeats.
        vlcDescriptor = getContentResolver().openFileDescriptor(uri, "r");
        if (vlcDescriptor == null) throw new java.io.IOException("Video descriptor unavailable");
        media = new Media(libVlc, vlcDescriptor.getFileDescriptor());
      } else media = new Media(libVlc, uri);
      media.setHWDecoderEnabled(true, false);
      if (order.mode() == PlaybackOrder.SINGLE) media.addOption(":input-repeat=65535");
      if (shouldPlay && !requestVlcFocus()) shouldPlay = false;
      if (!shouldPlay) media.addOption(":start-paused");
      vlc.setMedia(media);
      media.release();
      vlc.setRate(playbackRate());
      vlc.play();
      if (!shouldPlay) {
        final MediaPlayer target = vlc;
        handler.postDelayed(
            () -> {
              if (vlc == target) vlc.pause();
            },
            200);
      }
    } catch (Exception | UnsatisfiedLinkError error) {
      playbackFailed();
    }
    updatePlayUi();
  }

  private void applyVlcResumeOnce(MediaPlayer source) {
    if (vlc != source || vlcPendingSeek <= 0 || !source.isSeekable()) return;
    long target = vlcPendingSeek;
    // A media-level start-time is reapplied by native input-repeat. Restore with a
    // one-shot seek instead, so every later loop starts from the actual beginning.
    vlcPendingSeek = 0;
    source.setTime(target);
  }

  private void releasePlayers() {
    showLoading(false, "");
    if (exo != null) {
      exoView.setPlayer(null);
      exo.release();
      exo = null;
    }
    if (vlc != null) {
      vlc.setEventListener(null);
      vlc.detachViews();
      vlc.release();
      vlc = null;
    }
    if (libVlc != null) {
      libVlc.release();
      libVlc = null;
    }
    if (vlcDescriptor != null) {
      try {
        vlcDescriptor.close();
      } catch (java.io.IOException ignored) {
      }
      vlcDescriptor = null;
    }
    if (vlcFocus != null && audioManager != null) {
      audioManager.abandonAudioFocusRequest(vlcFocus);
      vlcFocus = null;
    }
    vlcPendingSeek = 0;
  }

  private void playbackFailed() {
    resetPageMotion();
    showLoading(false, "");
    shouldPlay = false;
    updatePlayUi();
    showChrome();
    new AlertDialog.Builder(this)
        .setTitle("暂时无法播放")
        .setMessage("文件可能尚未下载完成、已移动，或使用了设备无法解码的格式。可选择下一个视频继续观看。")
        .setNegativeButton("返回视频库", (d, w) -> finish())
        .setPositiveButton("下一个", (d, w) -> changeVideo(order.next(false)))
        .show();
  }

  private boolean requestVlcFocus() {
    if (vlcFocus != null)
      return audioManager.requestAudioFocus(vlcFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    vlcFocus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change == AudioManager.AUDIOFOCUS_LOSS
                      || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    focusPaused = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && isPlaying();
                    if (change == AudioManager.AUDIOFOCUS_LOSS) shouldPlay = false;
                    if (vlc != null) vlc.pause();
                  } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (vlc != null) vlc.setVolume(25);
                  } else if (change == AudioManager.AUDIOFOCUS_GAIN && vlc != null) {
                    vlc.setVolume(100);
                    if (focusPaused) vlc.play();
                    focusPaused = false;
                  }
                  updatePlayUi();
                })
            .build();
    return audioManager.requestAudioFocus(vlcFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
  }

  private Uri playbackUri(VideoItem item) {
    if (item.uri != null && !item.uri.isEmpty()) return Uri.parse(item.uri);
    return Uri.fromFile(new File(item.path));
  }

  private void changeVideo(int index) {
    handler.removeCallbacks(automaticAdvance);
    cancelInteractions();
    shouldPlay = true;
    restorePosition = 0;
    useVlc = false;
    if (!portraitMode) orientationOverride = false;
    adaptOrientation(currentItem().width, currentItem().height);
    startCurrent(0);
    showChrome();
  }

  private void advanceAutomatically() {
    if (!started) return;
    if (portraitMode && (gestureKind == GESTURE_PAGE || pageTransitioning)) {
      handler.removeCallbacks(automaticAdvance);
      handler.postDelayed(automaticAdvance, 150);
      return;
    }
    if (order.mode() == PlaybackOrder.SINGLE && useVlc) {
      // input-repeat normally handles this internally; this only handles EOF from a
      // demuxer that ignores input-repeat. Keep the existing decoder/view alive.
      handlingEnd = false;
      if (vlc != null) {
        vlc.setTime(0);
        vlc.play();
      }
      return;
    }
    order.next(true);
    changeVideo(order.current());
  }

  private boolean isPlaying() {
    return exo != null ? exo.isPlaying() : vlc != null && vlc.isPlaying();
  }

  private boolean playRequested() {
    return exo != null ? exo.getPlayWhenReady() : shouldPlay;
  }

  private long position() {
    return Math.max(
        0,
        exo != null
            ? exo.getCurrentPosition()
            : vlc != null
                ? (vlcPendingSeek > 0 ? vlcPendingSeek : vlc.getTime())
                : restorePosition);
  }

  private long duration() {
    long duration = exo != null ? exo.getDuration() : vlc != null ? vlc.getLength() : 0;
    return duration > 0 ? duration : currentItem().durationMs;
  }

  private int videoWidth() {
    return exo != null ? exo.getVideoSize().width : currentItem().width;
  }

  private int videoHeight() {
    return exo != null ? exo.getVideoSize().height : currentItem().height;
  }

  private void seekTo(long at) {
    long target = GestureSeek.targetMs(0, at, duration());
    if (exo != null) exo.seekTo(target);
    else if (vlc != null) vlc.setTime(target);
    updateProgress();
  }

  private void setPlaying(boolean play) {
    shouldPlay = play;
    if (exo != null) {
      if (play && exo.getPlaybackState() == Player.STATE_ENDED) exo.seekTo(0);
      exo.setPlayWhenReady(play);
    } else if (vlc != null) {
      if (play) {
        if (requestVlcFocus()) vlc.play();
        else shouldPlay = false;
      } else vlc.pause();
    }
    updatePlayUi();
  }

  private void updateProgress() {
    if (order == null || seek == null) return;
    long duration = duration(), at = position();
    if (!scrubbing && !gestureSeeking) {
      seek.setProgress(duration > 0 ? (int) Math.min(10000, at * 10000 / duration) : 0);
      currentTime.setText(formatTime(at));
    }
    totalTime.setText(formatTime(duration));
    seek.setEnabled(duration > 0);
    updatePlayUi();
  }

  private void updatePlayUi() {
    if (playButton == null) return;
    boolean playing = playRequested();
    if (!playVisualReady || lastPlayVisual != playing) {
      playVisualReady = true;
      lastPlayVisual = playing;
      playButton.setImageDrawable(
          new PlayerIcons(playing ? PlayerIcons.PAUSE : PlayerIcons.PLAY, TEXT));
      playButton.setContentDescription(playing ? "暂停" : "播放");
      if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private void refreshVideoLabels() {
    if (title == null) return;
    if (seekThumb != null) seekThumb.setDirection(true);
    title.setText(currentItem().name);
    subtitle.setText(String.format(Locale.CHINA, "%d / %d", order.current() + 1, queue.size()));
    updateModeUi();
  }

  private void cycleMode() {
    setPlaybackMode((order.mode() + 1) % 3);
  }

  private void setPlaybackMode(int mode) {
    if (mode == order.mode()) {
      showChrome();
      return;
    }
    int previous = order.mode();
    order.setMode(mode);
    getSharedPreferences("player", MODE_PRIVATE)
        .edit()
        .putInt("playback_mode", order.mode())
        .apply();
    if (exo != null)
      exo.setRepeatMode(
          order.mode() == PlaybackOrder.SINGLE ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    // VLC's demuxer repeat is a media option and is configured once when the media
    // opens. Reopen only when changing that option, never at normal loop boundaries.
    if (vlc != null && (previous == PlaybackOrder.SINGLE || order.mode() == PlaybackOrder.SINGLE)) {
      long at = position();
      shouldPlay = playRequested();
      startVlc(at);
    }
    modeButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    updateModeUi();
    showChrome();
  }

  private void updateModeUi() {
    if (modeButton == null || order == null) return;
    int mode = order.mode();
    String name =
        mode == PlaybackOrder.SINGLE ? "单集循环" : mode == PlaybackOrder.SHUFFLE ? "随机播放" : "顺序连播";
    modeButton.setImageDrawable(
        new PlayerIcons(
            mode == PlaybackOrder.SINGLE
                ? PlayerIcons.REPEAT_ONE
                : mode == PlaybackOrder.SHUFFLE ? PlayerIcons.SHUFFLE : PlayerIcons.REPEAT,
            TEXT));
    modeButton.setContentDescription("当前" + name + "，点击切换播放模式");
  }

  private void toggleOrientation() {
    if (portraitMode) {
      setPortraitMode(false);
      return;
    }
    orientationOverride = true;
    boolean landscape =
        getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    desiredOrientation =
        landscape
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    setRequestedOrientation(desiredOrientation);
    rotateButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    showChrome();
  }

  private void adaptOrientation(int width, int height) {
    if (portraitMode) {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      return;
    }
    if (orientationOverride || width <= 0 || height <= 0) return;
    desiredOrientation =
        height > width
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    if (getRequestedOrientation() != desiredOrientation)
      setRequestedOrientation(desiredOrientation);
  }

  private void showSettings() {
    handler.removeCallbacks(hideChrome);
    new AlertDialog.Builder(this)
        .setTitle("播放设置")
        .setItems(
            new String[] {
              "播放速度 · " + speed + "×",
              "滑动灵敏度",
              "恢复自动横竖屏",
              "手势使用说明",
              "播放模式",
              portraitMode ? "切回全屏播放" : "竖屏播放"
            },
            (d, item) -> {
              if (item == 0) chooseSpeed();
              else if (item == 1) chooseSensitivity();
              else if (item == 2) {
                orientationOverride = false;
                adaptOrientation(videoWidth(), videoHeight());
                toast(portraitMode ? "退出竖屏模式后按视频方向适配" : "按视频方向自动适配");
              } else if (item == 4) choosePlaybackMode();
              else if (item == 5) setPortraitMode(!portraitMode);
              else
                new AlertDialog.Builder(this)
                    .setTitle("手势使用说明")
                    .setMessage(
                        "在画面中左右滑动调整进度，松手后跳转。\n\n"
                            + "小幅滑动按秒精调；距离越长，快进或后退的跨度越大。向回滑动即可缩小跨度。\n\n"
                            + "拖动进度条或左右滑动时显示附近画面预览。长按画面临时两倍速，松手恢复。\n\n"
                            + "全屏模式：左侧上下滑动调亮度，右侧上下滑动调音量。\n\n"
                            + "竖屏模式：上滑下一条，下滑上一条；左右滑动仍调进度。切集遵循顺序/随机模式，单集循环也允许手动切集。\n\n"
                            + "手机图标进入竖屏，展开图标切回全屏。单击显示控制，双击播放暂停。原方向键切换横竖屏，长按恢复自动适配。")
                    .setPositiveButton("知道了", (dialog, w) -> showChrome())
                    .show();
            })
        .setOnDismissListener(d -> scheduleHide())
        .show();
  }

  private void choosePlaybackMode() {
    new AlertDialog.Builder(this)
        .setTitle("播放模式")
        .setSingleChoiceItems(
            new String[] {"顺序连播", "单集循环", "随机播放"},
            order.mode(),
            (dialog, selected) -> {
              setPlaybackMode(selected);
              dialog.dismiss();
            })
        .setNegativeButton("取消", (dialog, which) -> showChrome())
        .show();
  }

  private void chooseSpeed() {
    float[] values = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
    String[] labels = {"0.5×", "0.75×", "1× 正常", "1.25×", "1.5×", "2×"};
    int selected = 2;
    for (int i = 0; i < values.length; i++) if (values[i] == speed) selected = i;
    new AlertDialog.Builder(this)
        .setTitle("播放速度")
        .setSingleChoiceItems(
            labels,
            selected,
            (dialog, item) -> {
              speed = values[item];
              if (exo != null) exo.setPlaybackSpeed(playbackRate());
              else if (vlc != null) vlc.setRate(playbackRate());
              dialog.dismiss();
              showChrome();
            })
        .setNegativeButton("取消", (d, w) -> showChrome())
        .show();
  }

  private void chooseSensitivity() {
    float[] values = {.65f, 1f, 1.5f};
    int selected = sensitivity < .9f ? 0 : sensitivity > 1.2f ? 2 : 1;
    new AlertDialog.Builder(this)
        .setTitle("滑动灵敏度")
        .setSingleChoiceItems(
            new String[] {"精细 · 更长的精调范围", "均衡 · 推荐", "快速 · 较短滑动跨越更多"},
            selected,
            (dialog, item) -> {
              sensitivity = values[item];
              getSharedPreferences("player", MODE_PRIVATE)
                  .edit()
                  .putFloat("seek_sensitivity", sensitivity)
                  .apply();
              dialog.dismiss();
              showChrome();
            })
        .setNegativeButton("取消", (d, w) -> showChrome())
        .show();
  }

  private void showLoading(boolean visible, String message) {
    if (loading == null) return;
    if (visible && !buffering) bufferingSince = SystemClock.uptimeMillis();
    buffering = visible;
    updateLoadingIndicator();
  }

  private void updateLoadingIndicator() {
    handler.removeCallbacks(displayLoading);
    if (loading == null) return;
    if (!buffering || !started || scrubbing || gestureSeeking) {
      loading.setVisibility(View.GONE);
      return;
    }
    long remaining = 1000 - (SystemClock.uptimeMillis() - bufferingSince);
    if (remaining <= 0) loading.setVisibility(View.VISIBLE);
    else {
      loading.setVisibility(View.GONE);
      handler.postDelayed(displayLoading, remaining);
    }
  }

  private void showChrome() {
    setChrome(true);
    scheduleHide();
  }

  private void scheduleHide() {
    handler.removeCallbacks(hideChrome);
    AccessibilityManager access = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
    if (!scrubbing
        && !gestureSeeking
        && playRequested()
        && (access == null || !access.isTouchExplorationEnabled()))
      handler.postDelayed(hideChrome, 3600);
  }

  private void setChrome(boolean visible) {
    chromeVisible = visible;
    handler.removeCallbacks(hideChrome);
    topBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    bottomBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    immersive();
  }

  private void immersive() {
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null) {
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  @Override
  public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (!focus && touchDown) cancelInteractions();
    if (focus && root != null) immersive();
  }

  private ImageButton icon(int glyph, String label, int size, int color) {
    ImageButton button = new ImageButton(this);
    button.setImageDrawable(new PlayerIcons(glyph, color));
    button.setContentDescription(label);
    button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    button.setPadding(dp(14), dp(14), dp(14), dp(14));
    button.setBackground(ripple(0x10000000));
    button.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    return button;
  }

  private void space(LinearLayout row) {
    row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
  }

  private TextView text(int size, int color) {
    TextView view = new TextView(this);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setFontFeatureSettings("tnum");
    view.setShadowLayer(dp(2), 0, dp(1), 0xaa000000);
    return view;
  }

  private GradientDrawable rounded(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private RippleDrawable ripple(int base) {
    return new RippleDrawable(
        ColorStateList.valueOf(0x4060e0c0), rounded(base, 60), rounded(Color.WHITE, 60));
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }

  static String formatTime(long milliseconds) {
    long seconds = Math.max(0, milliseconds / 1000), hours = seconds / 3600;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }
}
