package io.mediamaster.app.player;

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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import io.mediamaster.app.R;
import io.mediamaster.app.TaskIdentity;
import io.mediamaster.app.Ui;
import io.mediamaster.app.media.VideoItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Local playback with an uninterrupted Exo repeat pipeline and VLC codec fallback. */
@UnstableApi
public final class PlayerActivity extends Activity {
  private static final int ACCENT = Ui.ACCENT, TEXT = 0xfffaf9fc, MUTED = 0xffd1ced8;
  private static final String EXTRA_QUEUE = "queue",
      EXTRA_INDEX = "index",
      EXTRA_SHUFFLE = "shuffle",
      EXTRA_START_PORTRAIT = "start_portrait";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ArrayList<VideoItem> queue = new ArrayList<>();
  private PlaybackOrder order;
  private PlaybackStatsStore playbackStats;
  private final Runnable capacityNotice = this::showCapacityNotice;
  private PlaybackVoteSession voteSession;
  private Object appliedWeightGeneration;
  private PlaybackStatsStore.PreparedWeights queueWeights;
  private boolean weightsLoading;
  private final ArrayList<Runnable> weightWaiters = new ArrayList<>();
  private final PlaybackWatchTracker watchTracker = new PlaybackWatchTracker();
  private boolean mutatingPageWindow, resumed, watchStateSaved;
  private boolean navigationPending;
  private int navigationRequestGeneration;
  private String queueToken;
  private FrameLayout root, videoStage;
  private PlayerView exoView;
  private VLCVideoLayout vlcView;
  private LinearLayout topBar, bottomBar;
  private TextView title, subtitle, currentTime, totalTime, previewTime, hudValue;
  private ImageView previewImage, hudIcon;
  private LinearLayout seekPreview, gestureHud;
  private SeekPreviewLoader previewLoader;
  private VideoPage scrubbingPage;
  private VideoPage syncedPage;
  private long syncedPageGeneration = -1;
  private boolean seekTouchCanceled;
  private final Runnable refreshScrubPreview =
      new Runnable() {
        @Override
        public void run() {
          if (!started || !(scrubbing || gestureSeeking) || scrubbingPage != currentPage) return;
          Bitmap frame = currentPage == null ? null : currentPage.captureScrubFrame();
          if (frame != null && seekPreview.getVisibility() == View.VISIBLE) {
            // The preview shares the active decoder. A slow file never starts a competing decoder.
            previewImage.setImageBitmap(frame);
            previewTime.setText(
                formatTime(currentPage.lastScrubPreviewPositionMs)
                    + " / "
                    + formatTime(duration()));
          }
          handler.postDelayed(this, 80);
        }
      };
  private View touchLayer;
  private static final int GESTURE_NONE = 0,
      GESTURE_SEEK = 1,
      GESTURE_BRIGHTNESS = 2,
      GESTURE_VOLUME = 3,
      GESTURE_BOOST = 4,
      GESTURE_PAGE = 5;
  private int gestureKind;
  private boolean touchDown, boosting, boostWasPlaying, tapCanceled;
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
  private ImageButton playButton,
      modeButton,
      rotateButton,
      portraitButton,
      likeButton,
      dislikeButton;
  private LikeEffectView likeEffect;
  private boolean portraitMode, pageTransitioning, pageSuspended;
  private int pageGeneration;
  private long completionGeneration, pendingCompletionGeneration = -1;
  private VelocityTracker pageVelocity;
  private ThreeWindowPager pager;
  private VideoPage previousPage, currentPage, nextPage;
  private final Runnable preloadWindow = this::ensurePortraitWindow;
  private final Runnable automaticAdvance =
      () -> {
        if (this.handlingEnd && pendingCompletionGeneration == completionGeneration)
          advanceAutomatically();
      };
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
  private AudioManager audioManager;
  private AudioFocusRequest vlcFocus;
  private long vlcFocusGeneration;
  private boolean useVlc,
      started,
      shouldPlay = true,
      chromeVisible = true,
      scrubbing,
      gestureSeeking,
      orientationOverride;
  private boolean noisyRegistered, focusPaused, handlingEnd;
  private boolean playVisualReady, lastPlayVisual;
  private long restorePosition, gestureOrigin, gestureTarget;
  private float touchX, touchY, sensitivity = 1f, speed = 1f;
  private int desiredOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
  private final Runnable hideChrome = () -> setChrome(false);
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (!started) return;
          syncWeightReset();
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
    startPlayback(activity, videos, index, shuffle, null);
  }

  public static void launch(
      Activity activity,
      ArrayList<VideoItem> videos,
      int index,
      boolean shuffle,
      boolean startPortrait) {
    startPlayback(activity, videos, index, shuffle, startPortrait);
  }

  private static void startPlayback(
      Activity activity,
      ArrayList<VideoItem> videos,
      int index,
      boolean shuffle,
      Boolean startPortrait) {
    if (videos == null || videos.isEmpty()) return;
    String token = QueueStore.put(activity.getApplicationContext(), videos);
    Intent intent =
        new Intent(activity, PlayerActivity.class)
            .putExtra(EXTRA_QUEUE, token)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_SHUFFLE, shuffle);
    if (startPortrait != null) intent.putExtra(EXTRA_START_PORTRAIT, startPortrait.booleanValue());
    activity.startActivity(intent);
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    TaskIdentity.update(this);
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
    playbackStats = PlaybackStatsStore.get(this);
    appliedWeightGeneration = playbackStats.voteGeneration();
    Object retained = getLastNonConfigurationInstance();
    voteSession =
        retained instanceof PlaybackVoteSession
            ? (PlaybackVoteSession) retained
            : new PlaybackVoteSession(playbackStats);
    watchTracker.restore(
        state != null ? state.getLong("play_entry_watched_ms", 0) : 0,
        state != null && state.getBoolean("play_entry_recorded", false));
    order = new PlaybackOrder(queue.size(), index, mode, new Random(), i -> queueWeights.weight(i));
    ensureQueueWeights(null);
    sensitivity = getSharedPreferences("player", MODE_PRIVATE).getFloat("seek_sensitivity", 1f);
    portraitMode =
        state != null
            ? state.getBoolean("portrait_mode", false)
            : getIntent().getBooleanExtra(EXTRA_START_PORTRAIT, false);
    if (state != null) {
      restorePosition = state.getLong("position");
      shouldPlay = state.getBoolean("playing", true);
      orientationOverride = state.getBoolean("orientation_override");
      desiredOrientation = state.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      useVlc = state.getBoolean("vlc");
      speed = state.getFloat("speed", 1f);
    }
    int navigationMode =
        getSharedPreferences("player", MODE_PRIVATE)
            .getInt(
                "portrait_navigation_mode",
                mode == PlaybackOrder.SHUFFLE ? PlaybackOrder.SHUFFLE : PlaybackOrder.SEQUENTIAL);
    if (getIntent().getBooleanExtra(EXTRA_SHUFFLE, false)) navigationMode = PlaybackOrder.SHUFFLE;
    if (state != null) navigationMode = state.getInt("portrait_navigation_mode", navigationMode);
    order.setPortraitNavigationMode(navigationMode);
    order.setPortraitMode(portraitMode);
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
    pager = new ThreeWindowPager(this);
    pager.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    videoStage.addView(pager, new FrameLayout.LayoutParams(-1, -1));
    previousPage = createVideoPage();
    currentPage = createVideoPage();
    nextPage = createVideoPage();
    pager.setPages(previousPage, currentPage, nextPage);
    syncCurrentPage();
    touchLayer = new View(this);
    touchLayer.setContentDescription("视频画面，点按显示控制，双击播放暂停，长按临时两倍速");
    touchLayer.setOnTouchListener(this::onVideoTouch);
    root.addView(touchLayer, new FrameLayout.LayoutParams(-1, -1));
    likeEffect = new LikeEffectView(this);
    root.addView(likeEffect, new FrameLayout.LayoutParams(-1, -1));

    topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(8), dp(4), dp(8), dp(4));
    ImageButton back = icon(PlayerIcons.BACK, "返回", 48, TEXT);
    back.setOnClickListener(v -> finish());
    topBar.addView(back);
    title = text(15, TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setMaxLines(1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setPadding(dp(8), 0, dp(8), 0);
    topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    subtitle = text(12, MUTED);
    subtitle.setSingleLine(true);
    subtitle.setMaxWidth(dp(64));
    subtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    subtitle.setPadding(dp(4), 0, dp(8), 0);
    topBar.addView(subtitle);
    likeButton = feedbackButton("点赞");
    likeButton.setOnClickListener(v -> toggleFeedback(1));
    topBar.addView(likeButton);
    dislikeButton = feedbackButton("点踩");
    dislikeButton.setOnClickListener(v -> toggleFeedback(-1));
    topBar.addView(dislikeButton);
    rotateButton = icon(PlayerIcons.ROTATE, "旋转", 48, TEXT);
    rotateButton.setContentDescription("旋转画面，长按恢复自动方向");
    rotateButton.setOnClickListener(v -> toggleOrientation());
    rotateButton.setOnLongClickListener(
        v -> {
          orientationOverride = false;
          adaptOrientation(videoWidth(), videoHeight());
          return true;
        });
    topBar.addView(rotateButton);
    ImageButton more = icon(PlayerIcons.MORE, "播放设置", 48, TEXT);
    more.setOnClickListener(v -> showSettings());
    topBar.addView(more);
    root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    bottomBar = new LinearLayout(this);
    bottomBar.setOrientation(LinearLayout.VERTICAL);
    bottomBar.setPadding(dp(16), dp(8), dp(16), dp(8));
    LinearLayout times = new LinearLayout(this);
    times.setGravity(Gravity.CENTER_VERTICAL);
    currentTime = text(12, TEXT);
    currentTime.setTypeface(Typeface.MONOSPACE);
    currentTime.setSingleLine(true);
    totalTime = text(12, TEXT);
    totalTime.setTypeface(Typeface.MONOSPACE);
    totalTime.setSingleLine(true);
    totalTime.setGravity(Gravity.RIGHT);
    times.addView(currentTime, new LinearLayout.LayoutParams(0, -2, 1));
    times.addView(totalTime);
    bottomBar.addView(times);
    seek = new SeekBar(this);
    seek.setMax(10000);
    seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
    seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66ffffff));
    seekThumb = new DirectionalThumbDrawable(this);
    seek.setThumb(seekThumb);
    seek.setSplitTrack(false);
    if (Build.VERSION.SDK_INT >= 29) {
      seek.setMinHeight(dp(3));
      seek.setMaxHeight(dp(3));
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
            beginPlayerScrub();
            updateLoadingIndicator();
            handler.removeCallbacks(hideChrome);
            showSeekPreview(gestureTarget);
          }

          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            if (user) {
              if (seekTouchCanceled) return;
              gestureTarget = duration() * progress / 10000L;
              if (!scrubbing) {
                // Accessibility and keyboard changes do not open a touch tracking session.
                seekTo(gestureTarget);
                return;
              }
              if (scrubbingPage != null) scrubbingPage.scrubTo(gestureTarget);
              currentTime.setText(formatTime(gestureTarget));
              showSeekPreview(gestureTarget);
            }
          }

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            if (!scrubbing) return;
            hideSeekPreview();
            endPlayerScrub(gestureTarget, true);
            scrubbing = false;
            updateProgress();
            updateLoadingIndicator();
            showChrome();
          }
        });
    seek.setOnTouchListener(
        (view, event) -> {
          int action = event.getActionMasked();
          if (action == MotionEvent.ACTION_DOWN) seekTouchCanceled = false;
          if (seekTouchCanceled) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
              seekTouchCanceled = false;
            return true;
          }
          if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelInteractions();
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
              MotionEvent cancel = MotionEvent.obtain(event);
              cancel.setAction(MotionEvent.ACTION_CANCEL);
              view.onTouchEvent(cancel);
              cancel.recycle();
              seekTouchCanceled = true;
              return true;
            }
          }
          return false;
        });
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER);
    modeButton = icon(PlayerIcons.REPEAT, "播放模式", 48, TEXT);
    modeButton.setOnClickListener(v -> cycleMode());
    modeButton.setOnLongClickListener(
        v -> {
          choosePlaybackMode();
          return true;
        });
    controls.addView(modeButton);
    space(controls);
    ImageButton previous = icon(PlayerIcons.PREVIOUS, "上一条", 48, TEXT);
    previous.setOnClickListener(v -> navigateRelative(false));
    controls.addView(previous);
    playButton = icon(PlayerIcons.PAUSE, "暂停", 56, TEXT);
    playButton.setPadding(dp(14), dp(14), dp(14), dp(14));
    playButton.setBackground(ripple(0x33272637));
    playButton.setOnClickListener(
        v -> {
          setPlaying(!playRequested());
          showChrome();
        });
    LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(56), dp(56));
    playParams.setMargins(dp(8), 0, dp(8), 0);
    controls.addView(playButton, playParams);
    ImageButton next = icon(PlayerIcons.NEXT, "下一条", 48, TEXT);
    next.setOnClickListener(v -> navigateRelative(true));
    controls.addView(next);
    space(controls);
    portraitButton = icon(PlayerIcons.PORTRAIT_MODE, "竖屏播放", 48, TEXT);
    portraitButton.setOnClickListener(v -> setPortraitMode(!portraitMode));
    controls.addView(portraitButton);
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
    gestureHud.setBackground(rounded(0xb3201d2c, 10));
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
          topBar.setPadding(dp(8) + left, dp(4) + top, dp(8) + right, dp(4));
          int gutter = getResources().getConfiguration().screenWidthDp < 360 ? dp(8) : dp(16);
          bottomBar.setPadding(gutter + left, dp(8), gutter + right, dp(8) + bottom);
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
          if (!portraitMode
              && started
              && resumed
              && !gestureSeeking
              && gestureKind == GESTURE_NONE) {
            if (chromeVisible) setChrome(false);
            else showChrome();
          }
          return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
          if (!portraitMode) {
            setPlaying(!playRequested());
            showChrome();
          }
          return true;
        }
      };
  private GestureDetector taps;
  private PortraitTapSequence portraitTaps;
  private final Runnable confirmPortraitSingle =
      () -> {
        if (portraitTaps != null && portraitTaps.confirmSingle(SystemClock.uptimeMillis()))
          togglePortraitTapPlayback();
      };

  private void togglePortraitTapPlayback() {
    if (portraitMode
        && started
        && resumed
        && !touchDown
        && !gestureSeeking
        && gestureKind == GESTURE_NONE
        && !pageTransitioning) {
      setPlaying(!playRequested());
      showChrome();
    }
  }

  private void finishPortraitTap(MotionEvent event) {
    if (portraitTaps == null || !started || !resumed || gestureSeeking || pageTransitioning) return;
    PortraitTapSequence.Release release =
        portraitTaps.up(event.getEventTime(), event.getX(), event.getY());
    if (release == PortraitTapSequence.Release.PENDING_SINGLE) {
      handler.postAtTime(confirmPortraitSingle, portraitTaps.singleDeadline());
    } else if (release == PortraitTapSequence.Release.LIKE) {
      if (!voteSession.setVote(currentItem(), 1)) {
        toast("记录未就绪，请稍后重试");
        return;
      }
      refreshFeedbackUi();
      likeEffect.showAt(event.getX(), event.getY());
      feedbackHaptic();
    }
  }

  private boolean onVideoTouch(View view, MotionEvent event) {
    if (pageTransitioning) return true;
    if (!portraitMode && taps == null) taps = new GestureDetector(this, tapListener);
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      recyclePageVelocity();
      if (portraitMode) pageVelocity = VelocityTracker.obtain();
    }
    if (pageVelocity != null) pageVelocity.addMovement(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (portraitMode) {
          if (portraitTaps == null)
            portraitTaps =
                new PortraitTapSequence(
                    ViewConfiguration.getDoubleTapTimeout(),
                    ViewConfiguration.getLongPressTimeout(),
                    ViewConfiguration.get(this).getScaledDoubleTapSlop());
          handler.removeCallbacks(confirmPortraitSingle);
          if (portraitTaps.down(event.getEventTime(), event.getX(), event.getY()))
            togglePortraitTapPlayback();
        }
        touchDown = true;
        tapCanceled = false;
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
        if (!portraitMode) taps.onTouchEvent(event);
        handler.removeCallbacks(longPress);
        if (touchX > dp(20) && touchX < view.getWidth() - dp(20))
          handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!touchDown) return true;
        float dx = event.getX() - touchX, dy = event.getY() - touchY;
        if (Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) {
          handler.removeCallbacks(longPress);
          if (!tapCanceled) cancelTapRecognition();
        }
        if (gestureKind == GESTURE_NONE && touchX > dp(20) && touchX < view.getWidth() - dp(20)) {
          if (Math.abs(dx) > dp(14) && Math.abs(dx) > Math.abs(dy) * 1.3f && duration() > 0) {
            gestureKind = GESTURE_SEEK;
            gestureSeeking = true;
            beginPlayerScrub(event.getDownTime());
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
            if (gestureKind == GESTURE_PAGE) suspendForPageScroll();
          }
        }
        if (gestureKind == GESTURE_SEEK) {
          long delta =
              GestureSeek.deltaMs(
                  dx, Math.max(dp(240), view.getWidth() - dp(40)), duration(), sensitivity);
          gestureTarget = GestureSeek.targetMs(gestureOrigin, delta, duration());
          if (scrubbingPage != null) scrubbingPage.scrubTo(gestureTarget);
          showSeekPreview(gestureTarget);
        } else if (gestureKind == GESTURE_BRIGHTNESS || gestureKind == GESTURE_VOLUME) {
          updateVerticalGesture(dy, Math.max(1, view.getHeight()));
        } else if (gestureKind == GESTURE_PAGE) {
          pager.dragTo(dy, canRevealPrevious(), canRevealNext());
        } else if (gestureKind == GESTURE_NONE && !tapCanceled && !portraitMode)
          taps.onTouchEvent(event);
        return true;
      case MotionEvent.ACTION_UP:
        if (!touchDown) return true;
        touchDown = false;
        handler.removeCallbacks(longPress);
        // Some input sources coalesce movement into UP without a final MOVE event.
        if (!tapCanceled
            && Math.hypot(event.getX() - touchX, event.getY() - touchY)
                > ViewConfiguration.get(this).getScaledTouchSlop()) cancelTapRecognition();
        int finished = gestureKind;
        if (finished == GESTURE_SEEK) {
          hideSeekPreview();
          endPlayerScrub(gestureTarget, true);
          gestureSeeking = false;
          updateProgress();
          updateLoadingIndicator();
          // A screen swipe returns straight to the video once its seek is committed.
          // SeekBar dragging retains its separate, visible-controls completion behavior.
          setChrome(false);
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
          if (!tapCanceled) {
            if (portraitMode) finishPortraitTap(event);
            else taps.onTouchEvent(event);
          }
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
    tapCanceled = true;
    handler.removeCallbacks(confirmPortraitSingle);
    if (portraitTaps != null) portraitTaps.cancel();
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
    order.setPortraitMode(enabled);
    applyRepeatPolicy();
    if (enabled) ensurePortraitWindow();
    else {
      previousPage.release();
      nextPage.release();
    }
    if (enabled) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    else if (orientationOverride) setRequestedOrientation(desiredOrientation);
    else adaptOrientation(videoWidth(), videoHeight());
    updatePresentationUi();
    updateModeUi();
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
            ? "竖屏视频，单击播放暂停，双击点赞，左右滑动调进度，上滑下一条，下滑上一条，长按两倍速"
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
    pageTransitioning = false;
    if (pager != null) pager.cancelAndCenter();
    if (pageSuspended) {
      pageSuspended = false;
      if (started) applyCurrentPlayback(shouldPlay);
    }
  }

  private boolean canRevealPrevious() {
    if (mutatingPageWindow || order.mode() == PlaybackOrder.SHUFFLE && queueWeights == null)
      return false;
    return queue.size() > 1
        && order.hasPrevious()
        && previousPage.index == order.peekPrevious()
        && pageReady(previousPage);
  }

  private boolean canRevealNext() {
    if (mutatingPageWindow || order.mode() == PlaybackOrder.SHUFFLE && queueWeights == null)
      return false;
    return queue.size() > 1 && nextPage.index == order.peekNext() && pageReady(nextPage);
  }

  private boolean pageReady(VideoPage page) {
    return page != null
        && page.index >= 0
        && (page.failed
            || (page.firstFrameReady
                && (page.exo == null || page.exo.getPlaybackState() == Player.STATE_READY)));
  }

  private void suspendForPageScroll() {
    if (pageSuspended) return;
    stopWatching();
    shouldPlay = playRequested();
    pageSuspended = true;
    // Close all audio gates before the viewport leaves its anchor. Decoders and surfaces stay.
    currentPage.setPlayback(true, false, false);
    previousPage.setPlayback(false, false, false);
    nextPage.setPlayback(false, false, false);
  }

  private void finishPortraitSwipe(float dy, float velocity, float height) {
    int direction =
        PortraitSwipe.direction(dy, velocity, height, getResources().getDisplayMetrics().density);
    if ((direction > 0 && !canRevealNext()) || (direction < 0 && !canRevealPrevious()))
      direction = 0;
    final int chosen = direction;
    final int generation = ++pageGeneration;
    pageTransitioning = true;
    handler.removeCallbacks(hideChrome);
    pager.settle(
        chosen,
        () -> {
          if (generation != pageGeneration || !started || !portraitMode) return;
          // Incoming already owns a prepared decoder and rendered first frame. Never reopen it
          // here.
          if (chosen != 0) {
            stopWatching();
            if (chosen > 0) order.next(false);
            else order.previous();
            adoptPreparedPage(chosen);
            touchLayer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
          } else {
            pageTransitioning = false;
            pageSuspended = false;
            applyCurrentPlayback(shouldPlay);
            showChrome();
          }
        });
  }

  /** Promote by rotating references. All three native video surfaces remain attached. */
  private void adoptPreparedPage(int direction) {
    stopWatching();
    boolean wasMutating = mutatingPageWindow;
    mutatingPageWindow = true;
    watchTracker.reset();
    try {
      invalidateCompletion();
      currentPage.setPlayback(false, false, false);
      currentPage.rewindIfEnded();
      if (direction > 0) {
        VideoPage far = previousPage;
        previousPage = currentPage;
        currentPage = nextPage;
        nextPage = far;
      } else {
        VideoPage far = nextPage;
        nextPage = currentPage;
        currentPage = previousPage;
        previousPage = far;
      }
      abandonVlcFocus();
      pager.setPages(previousPage, currentPage, nextPage);
      pageTransitioning = false;
      pageSuspended = false;
      handlingEnd = false;
      shouldPlay = true;
      restorePosition = currentPage.position();
      syncCurrentPage();
      refreshVideoLabels();
      currentPage.setSpeed(playbackRate());
      if (exo != null && exo.getPlaybackState() == Player.STATE_ENDED) currentPage.seekTo(0);
      applyCurrentPlayback(true);
      showLoading(currentPage.buffering, "");
      showChrome();
      if (currentPage.failed) playbackFailed();
    } finally {
      mutatingPageWindow = wasMutating;
    }
    recordStartedPlayback();
    // Only the far, now invisible window is recycled. The promoted page is never prepared again.
    ensurePortraitWindow();
  }

  private void ensurePortraitWindow() {
    if (!started || !portraitMode || mutatingPageWindow) return;
    if (order.mode() == PlaybackOrder.SHUFFLE && queueWeights == null) return;
    if (queue.size() < 2) {
      previousPage.release();
      nextPage.release();
      return;
    }
    if (currentPage == null
        || currentPage.index != order.current()
        || (!currentPage.failed && (!currentPage.firstFrameReady || currentPage.buffering))
        || scrubbingPage != null) return;
    // Prime one neighbor at a time after the visible frame, keeping decoder setup away from seeks.
    prepareNeighbor(nextPage, order.peekNext());
    if (pageReady(nextPage)) {
      if (order.hasPrevious()) prepareNeighbor(previousPage, order.peekPrevious());
      else previousPage.release();
    }
  }

  private void prepareNeighbor(VideoPage page, int index) {
    page.setPlayback(false, false, false);
    if (page.index == index && (page.exo != null || page.vlc != null || page.failed)) return;
    page.prepare(queue.get(index), index, 0, false, order.repeatsCurrent(), speed, false);
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
    long previewPosition =
        currentPage != null && currentPage.lastScrubPreviewPositionMs >= 0
            ? currentPage.lastScrubPreviewPositionMs
            : at;
    previewTime.setText(formatTime(previewPosition) + " / " + formatTime(duration()));
    // The same player supplies both live scrubbing and the preview. Only use the metadata
    // extractor if there is no playback engine; never compete for codecs while it is seeking.
    if (currentPage != null && (currentPage.exo != null || currentPage.vlc != null)) return;
    String identity = currentItem().id;
    previewLoader.requestInteractive(
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
    handler.removeCallbacks(refreshScrubPreview);
    if (seekPreview != null) seekPreview.setVisibility(View.GONE);
    if (previewImage != null) previewImage.setImageDrawable(null);
    if (previewLoader != null) previewLoader.reset();
  }

  private void beginPlayerScrub() {
    beginPlayerScrub(0);
  }

  private void beginPlayerScrub(long inputDownTimeMs) {
    stopWatching();
    invalidateCompletion();
    handler.removeCallbacks(preloadWindow);
    if (previewLoader != null) previewLoader.reset();
    scrubbingPage = currentPage;
    if (scrubbingPage != null) scrubbingPage.beginScrub(inputDownTimeMs);
    handler.removeCallbacks(refreshScrubPreview);
    handler.post(refreshScrubPreview);
  }

  private void endPlayerScrub(long target, boolean commit) {
    handler.removeCallbacks(refreshScrubPreview);
    VideoPage page = scrubbingPage;
    scrubbingPage = null;
    invalidateCompletion();
    if (page != null) page.endScrub(target, commit);
    // A no-op seek may not emit another ready event; defer preload work until after finger-up.
    handler.removeCallbacks(preloadWindow);
    handler.postDelayed(preloadWindow, 180);
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
    if (currentPage != null) currentPage.setSpeed(playbackRate());
  }

  private void cancelInteractions() {
    cancelTapRecognition();
    if (likeEffect != null) likeEffect.clear();
    boolean cancelScreenSeek = gestureSeeking || gestureKind == GESTURE_SEEK;
    if (scrubbingPage != null) endPlayerScrub(gestureOrigin, false);
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
    if (cancelScreenSeek) setChrome(false);
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
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (playbackStats != null) playbackStats.addCapacityListener(capacityNotice);
    watchStateSaved = false;
    TaskIdentity.update(this);
    syncWeightReset();
    refreshFeedbackUi();
    recordStartedPlayback();
  }

  @Override
  protected void onPause() {
    stopWatching();
    cancelTapRecognition();
    if (likeEffect != null) likeEffect.clear();
    resumed = false;
    if (playbackStats != null) playbackStats.removeCapacityListener(capacityNotice);
    super.onPause();
  }

  @Override
  protected void onStop() {
    stopWatching();
    navigationRequestGeneration++;
    navigationPending = false;
    invalidateCompletion();
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
    if (playbackStats != null) playbackStats.removeCapacityListener(capacityNotice);
    if (previewLoader != null) previewLoader.close();
    if (isFinishing()) QueueStore.forget(queueToken);
    super.onDestroy();
  }

  private void showCapacityNotice() {
    if (resumed && !isFinishing() && !isDestroyed() && playbackStats.consumeCapacityNotice())
      Toast.makeText(this, R.string.playback_database_full, Toast.LENGTH_LONG).show();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    // Rotation/recreation is the same window. A fresh launch starts a new voting session.
    return voteSession;
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    stopWatching();
    // Freeze this entry at its snapshot. An old instance must not count after saving a Bundle
    // that would let its replacement count the same viewing interval again.
    watchStateSaved = true;
    super.onSaveInstanceState(state);
    if (order == null) return;
    state.putString(EXTRA_QUEUE, queueToken);
    state.putInt(EXTRA_INDEX, order.current());
    state.putInt("mode", order.fullScreenMode());
    state.putInt("portrait_navigation_mode", order.portraitNavigationMode());
    state.putLong("position", started ? position() : restorePosition);
    state.putBoolean("playing", started ? playRequested() : shouldPlay);
    state.putBoolean("orientation_override", orientationOverride);
    state.putInt("orientation", desiredOrientation);
    state.putBoolean("vlc", useVlc);
    state.putFloat("speed", speed);
    state.putBoolean("portrait_mode", portraitMode);
    state.putBoolean("play_entry_recorded", watchTracker.recorded());
    state.putLong("play_entry_watched_ms", watchTracker.watchedMs());
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

  private VideoPage createVideoPage() {
    return new VideoPage(
        this,
        this::compatibilityEngine,
        new VideoPage.Listener() {
          @Override
          public void onChanged(VideoPage page) {
            if (!started || mutatingPageWindow) return;
            if (page != currentPage) {
              // A paused neighbor may render its first frame before it reaches STATE_READY.
              if (portraitMode && page.firstFrameReady && !page.buffering) ensurePortraitWindow();
              return;
            }
            MediaPlayer previousNative = vlc;
            if (syncedPage == page
                && syncedPageGeneration == page.generation
                && (exo != null || vlc != null)
                && (exo != page.exo || vlc != page.vlc)) shouldPlay = page.playRequested;
            syncCurrentPage();
            if (vlc != null && vlc != previousNative) applyCurrentPlayback(shouldPlay);
            showLoading(page.buffering, "");
            updateProgress();
            if (!page.buffering && portraitMode) ensurePortraitWindow();
          }

          @Override
          public void onFirstFrame(VideoPage page) {
            if (mutatingPageWindow) return;
            if (page == currentPage && started) {
              syncCurrentPage();
              showLoading(page.buffering, "");
              updateProgress();
            }
            if (started && portraitMode) ensurePortraitWindow();
          }

          @Override
          public void onEnded(VideoPage page) {
            if (page != currentPage || !started || mutatingPageWindow || handlingEnd) return;
            stopWatching();
            handlingEnd = true;
            final long generation = page.generation;
            final long completion = ++completionGeneration;
            pendingCompletionGeneration = completion;
            handler.post(
                () -> {
                  if (started
                      && currentPage == page
                      && page.generation == generation
                      && completionGeneration == completion) advanceAutomatically();
                });
          }

          @Override
          public void onFailed(VideoPage page) {
            if (page == currentPage && started) playbackFailed();
            if (started && portraitMode) ensurePortraitWindow();
          }

          @Override
          public void onVideoSize(VideoPage page, int width, int height) {
            if (page == currentPage && !orientationOverride) adaptOrientation(width, height);
          }
        });
  }

  private LibVLC compatibilityEngine() {
    if (libVlc == null) {
      ArrayList<String> options = new ArrayList<>();
      options.add("--no-video-title-show");
      options.add("--aout=android_audiotrack");
      options.add("--file-caching=750");
      libVlc = new LibVLC(this, options);
    }
    return libVlc;
  }

  private void syncCurrentPage() {
    if (currentPage == null) return;
    if (vlc != currentPage.vlc) abandonVlcFocus();
    exo = currentPage.exo;
    vlc = currentPage.vlc;
    exoView = currentPage.exoView;
    vlcView = currentPage.vlcView;
    useVlc = vlc != null;
    syncedPage = currentPage;
    syncedPageGeneration = currentPage.generation;
  }

  private void startCurrent(long start) {
    handlingEnd = false;
    refreshVideoLabels();
    prepareCurrent(start, useVlc);
  }

  private void startExo(long start) {
    prepareCurrent(start, false);
  }

  private void startVlc(long start) {
    prepareCurrent(start, true);
  }

  private void prepareCurrent(long start, boolean forceVlc) {
    stopWatching();
    abandonVlcFocus();
    currentPage.prepare(
        currentItem(),
        order.current(),
        start,
        forceVlc,
        order.repeatsCurrent(),
        playbackRate(),
        true);
    syncCurrentPage();
    applyCurrentPlayback(shouldPlay);
    showLoading(currentPage.buffering, "");
    ensurePortraitWindow();
  }

  private void applyCurrentPlayback(boolean play) {
    if (currentPage == null) return;
    syncCurrentPage();
    boolean canPlay = play && started && !pageSuspended && !currentPage.failed;
    if (canPlay && vlc != null && !requestVlcFocus()) {
      shouldPlay = false;
      canPlay = false;
    }
    if (!canPlay) stopWatching();
    currentPage.setPlayback(true, canPlay, !pageSuspended);
    recordStartedPlayback();
  }

  private void abandonVlcFocus() {
    vlcFocusGeneration++;
    focusPaused = false;
    if (vlcFocus != null && audioManager != null) {
      audioManager.abandonAudioFocusRequest(vlcFocus);
      vlcFocus = null;
    }
  }

  private void releasePlayers() {
    showLoading(false, "");
    if (currentPage != null) currentPage.release();
    if (previousPage != null) previousPage.release();
    if (nextPage != null) nextPage.release();
    syncCurrentPage();
    if (libVlc != null) {
      libVlc.release();
      libVlc = null;
    }
    abandonVlcFocus();
  }

  private void playbackFailed() {
    stopWatching();
    resetPageMotion();
    showLoading(false, "");
    shouldPlay = false;
    updatePlayUi();
    showChrome();
    new AlertDialog.Builder(this, Ui.dialogTheme(this))
        .setTitle("无法播放")
        .setMessage("文件可能尚未下载完成、已移动，或使用了设备无法解码的格式。可选择下一个视频继续观看。")
        .setNegativeButton("返回", (d, w) -> finish())
        .setPositiveButton("下一条", (d, w) -> navigateRelative(true))
        .show();
  }

  private boolean requestVlcFocus() {
    if (vlcFocus != null) {
      boolean granted =
          audioManager.requestAudioFocus(vlcFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
      if (granted) currentPage.setNativeDucked(false);
      return granted;
    }
    final MediaPlayer focusOwner = vlc;
    final long focusGeneration = ++vlcFocusGeneration;
    vlcFocus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (vlcFocusGeneration != focusGeneration
                      || vlc != focusOwner
                      || currentPage == null
                      || currentPage.vlc != focusOwner
                      || !started) return;
                  if (change == AudioManager.AUDIOFOCUS_LOSS
                      || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stopWatching();
                    focusPaused = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && shouldPlay;
                    if (change == AudioManager.AUDIOFOCUS_LOSS) shouldPlay = false;
                    currentPage.setPlayback(true, false, false);
                    currentPage.setNativeDucked(false);
                  } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    currentPage.setNativeDucked(true);
                  } else if (change == AudioManager.AUDIOFOCUS_GAIN && vlc != null) {
                    boolean resume = (focusPaused && shouldPlay) || currentPage.playRequested;
                    focusPaused = false;
                    currentPage.setNativeDucked(false);
                    currentPage.setPlayback(true, resume && !pageSuspended, !pageSuspended);
                  }
                  recordStartedPlayback();
                  updatePlayUi();
                })
            .build();
    boolean granted =
        audioManager.requestAudioFocus(vlcFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    if (granted) currentPage.setNativeDucked(false);
    return granted;
  }

  private Uri playbackUri(VideoItem item) {
    if (item.uri != null && !item.uri.isEmpty()) return Uri.parse(item.uri);
    return Uri.fromFile(new File(item.path));
  }

  private void navigateRelative(boolean forward) {
    navigateRelative(forward, false);
  }

  private void navigateRelative(boolean forward, boolean automatic) {
    if (order.mode() == PlaybackOrder.SHUFFLE && queueWeights == null) {
      if (navigationPending) return;
      navigationPending = true;
      final int request = ++navigationRequestGeneration;
      final int index = order.current();
      ensureQueueWeights(
          () -> {
            if (request != navigationRequestGeneration) return;
            navigationPending = false;
            if (started && !isFinishing() && !isDestroyed() && order.current() == index)
              navigateRelative(forward, automatic);
          });
      return;
    }
    stopWatching();
    if (!automatic && queue.size() == 1) watchTracker.reset();
    changeVideo(forward ? order.next(false) : order.previous(), forward ? 1 : -1);
  }

  /** One queue-sized snapshot keeps historical cache misses away from the render thread. */
  private void ensureQueueWeights(Runnable afterReady) {
    if (queueWeights != null) {
      if (afterReady != null) afterReady.run();
      return;
    }
    if (afterReady != null) weightWaiters.add(afterReady);
    if (weightsLoading) return;
    weightsLoading = true;
    playbackStats.prepareWeights(
        queue,
        prepared -> {
          weightsLoading = false;
          ArrayList<Runnable> callbacks = new ArrayList<>(weightWaiters);
          weightWaiters.clear();
          if (isFinishing() || isDestroyed()) return;
          queueWeights = prepared;
          if (prepared == null) {
            navigationPending = false;
            toast("无法读取播放记录，请重试");
            return;
          }
          refreshFeedbackUi();
          ensurePortraitWindow();
          for (Runnable callback : callbacks) callback.run();
        });
  }

  private void changeVideo(int index) {
    changeVideo(index, 0);
  }

  private void changeVideo(int index, int direction) {
    stopWatching();
    boolean wasMutating = mutatingPageWindow;
    mutatingPageWindow = true;
    if (currentPage == null || currentPage.index != index) watchTracker.reset();
    try {
      invalidateCompletion();
      cancelInteractions();
      if (portraitMode && currentPage.index != index) {
        // Both neighbors may contain the same video. Keep the requested side so returning can
        // reuse the page just left instead of replacing its decoder while rebuilding neighbors.
        VideoPage preferred = direction < 0 ? previousPage : nextPage;
        if (direction != 0 && preferred.index == index && pageReady(preferred)) {
          adoptPreparedPage(direction);
          return;
        }
        if (nextPage.index == index && pageReady(nextPage)) {
          adoptPreparedPage(1);
          return;
        }
        if (previousPage.index == index && pageReady(previousPage)) {
          adoptPreparedPage(-1);
          return;
        }
      }
      shouldPlay = true;
      restorePosition = 0;
      useVlc = false;
      if (!portraitMode) orientationOverride = false;
      adaptOrientation(currentItem().width, currentItem().height);
      startCurrent(0);
      showChrome();
    } finally {
      mutatingPageWindow = wasMutating;
      recordStartedPlayback();
      ensurePortraitWindow();
    }
  }

  private void invalidateCompletion() {
    completionGeneration++;
    pendingCompletionGeneration = -1;
    handlingEnd = false;
    handler.removeCallbacks(automaticAdvance);
  }

  private void advanceAutomatically() {
    if (!started) return;
    if (!playRequested()) {
      invalidateCompletion();
      return;
    }
    if (scrubbing
        || gestureSeeking
        || (portraitMode && (gestureKind == GESTURE_PAGE || pageTransitioning))) {
      handler.removeCallbacks(automaticAdvance);
      handler.postDelayed(automaticAdvance, 150);
      return;
    }
    if (order.repeatsCurrent()) {
      // input-repeat normally handles this internally; this only handles EOF from a
      // demuxer that ignores input-repeat. Keep the existing decoder/view alive.
      handlingEnd = false;
      currentPage.seekTo(0);
      applyCurrentPlayback(shouldPlay);
      return;
    }
    navigateRelative(true, true);
  }

  private boolean isPlaying() {
    return currentPage != null && currentPage.isPlaying();
  }

  private boolean playRequested() {
    return pageSuspended ? shouldPlay : exo != null ? exo.getPlayWhenReady() : shouldPlay;
  }

  private long position() {
    return Math.max(
        0,
        currentPage != null && currentPage.index >= 0 ? currentPage.position() : restorePosition);
  }

  private long duration() {
    long length = currentPage != null ? currentPage.duration() : 0;
    return length > 0 ? length : currentItem().durationMs;
  }

  private int videoWidth() {
    return exo != null ? exo.getVideoSize().width : currentItem().width;
  }

  private int videoHeight() {
    return exo != null ? exo.getVideoSize().height : currentItem().height;
  }

  private void seekTo(long at) {
    stopWatching();
    boolean resume = playRequested();
    boolean completing = handlingEnd;
    invalidateCompletion();
    currentPage.seekTo(GestureSeek.targetMs(0, at, duration()));
    // VLC closes its native playback gate when reporting EOF. A deliberate rewind supersedes
    // that completion and restores the user's earlier play/pause choice.
    if (completing && vlc != null) applyCurrentPlayback(resume);
    updateProgress();
  }

  private void setPlaying(boolean play) {
    if (!play) stopWatching();
    if (!play) invalidateCompletion();
    shouldPlay = play;
    if (play && exo != null && exo.getPlaybackState() == Player.STATE_ENDED) currentPage.seekTo(0);
    applyCurrentPlayback(play);
    updatePlayUi();
  }

  private void updateProgress() {
    recordStartedPlayback();
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

  private void recordStartedPlayback() {
    updateWatchTime(
        playbackStats != null
            && order != null
            && started
            && resumed
            && !watchStateSaved
            && !mutatingPageWindow
            && !pageSuspended
            && !pageTransitioning
            && !handlingEnd
            && !scrubbing
            && !gestureSeeking
            && scrubbingPage == null
            && currentPage != null
            && currentPage.index == order.current()
            && currentPage.active
            && currentPage.playRequested
            && currentPage.firstFrameReady
            && !currentPage.failed
            && !currentPage.buffering
            && !currentPage.isSeekingForWatchTime()
            && currentPage.isPlaying());
  }

  private void stopWatching() {
    updateWatchTime(false);
  }

  private void updateWatchTime(boolean eligible) {
    if (watchTracker.update(eligible, SystemClock.elapsedRealtime())
        && playbackStats != null
        && currentPage != null
        && currentPage.item != null) {
      // A navigation callback may already have advanced the order. The settling interval still
      // belongs to the page on screen, never the incoming index or a paused preload neighbor.
      playbackStats.recordPlay(currentPage.item);
    }
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
      playButton.setTooltipText(playing ? "暂停" : "播放");
      if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private void refreshVideoLabels() {
    if (title == null) return;
    cancelTapRecognition();
    if (likeEffect != null) likeEffect.clear();
    if (seekThumb != null) seekThumb.setDirection(true);
    title.setText(currentItem().name);
    subtitle.setText(String.format(Locale.CHINA, "%d / %d", order.current() + 1, queue.size()));
    subtitle.setContentDescription("视频 " + subtitle.getText());
    refreshFeedbackUi();
    updateModeUi();
  }

  private void toggleFeedback(int requested) {
    if (pageTransitioning || currentPage == null || currentPage.index != order.current()) return;
    int current = voteSession.vote(currentItem());
    if (!voteSession.setVote(currentItem(), current == requested ? 0 : requested)) {
      toast("记录未就绪，请稍后重试");
      return;
    }
    if (voteSession.vote(currentItem()) != 1) likeEffect.clear();
    refreshFeedbackUi();
    feedbackHaptic();
    showChrome();
  }

  private void refreshFeedbackUi() {
    if (likeButton == null || order == null) return;
    int feedback = voteSession.vote(currentItem());
    updateFeedbackButton(
        likeButton, feedback == 1, "点赞", R.drawable.ic_thumb_up, R.drawable.ic_thumb_up_outline);
    updateFeedbackButton(
        dislikeButton,
        feedback == -1,
        "点踩",
        R.drawable.ic_thumb_down,
        R.drawable.ic_thumb_down_outline);
  }

  private void updateFeedbackButton(
      ImageButton button, boolean selected, String action, int filled, int outline) {
    button.setImageResource(selected ? filled : outline);
    button.setImageTintList(ColorStateList.valueOf(TEXT));
    button.setSelected(selected);
    button.setContentDescription(selected ? "取消" + action : action);
    button.setTooltipText(selected ? "取消" + action : action);
    if (Build.VERSION.SDK_INT >= 30)
      button.setStateDescription(selected ? "已" + action : "未" + action);
  }

  private void feedbackHaptic() {
    if (!touchLayer.isHapticFeedbackEnabled()
        || !touchLayer.hasWindowFocus()
        || Settings.System.getInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
            == 0) return;
    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
    try {
      if (vibrator != null && vibrator.hasVibrator()) {
        vibrator.vibrate(
            VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE),
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build());
        return;
      }
    } catch (SecurityException unavailable) {
      // Keep Android's native feedback available to restricted installations.
    }
    touchLayer.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
  }

  private void resetWeights() {
    RandomWeightsDialog.show(this, playbackStats, this::syncWeightReset);
  }

  /** Reconcile a completed reset even if its original dialog's Activity was recreated. */
  private void syncWeightReset() {
    if (!started
        || !resumed
        || playbackStats == null
        || order == null
        || currentPage == null
        || mutatingPageWindow
        || pageTransitioning
        || touchDown
        || scrubbing
        || gestureSeeking
        || pageSuspended) return;
    Object generation = playbackStats.voteGeneration();
    if (generation == appliedWeightGeneration) return;
    appliedWeightGeneration = generation;
    cancelInteractions();
    order.refreshWeights();
    boolean wasMutating = mutatingPageWindow;
    mutatingPageWindow = true;
    try {
      previousPage.release();
      nextPage.release();
    } finally {
      mutatingPageWindow = wasMutating;
    }
    refreshFeedbackUi();
    ensurePortraitWindow();
  }

  private void cycleMode() {
    setPlaybackMode(
        portraitMode
            ? (order.mode() == PlaybackOrder.SHUFFLE
                ? PlaybackOrder.SEQUENTIAL
                : PlaybackOrder.SHUFFLE)
            : (order.mode() + 1) % 3);
  }

  private void setPlaybackMode(int mode) {
    if (mode == order.mode()) {
      showChrome();
      return;
    }
    cancelInteractions();
    shouldPlay = playRequested();
    order.setMode(mode);
    getSharedPreferences("player", MODE_PRIVATE)
        .edit()
        .putInt("playback_mode", order.fullScreenMode())
        .putInt("portrait_navigation_mode", order.portraitNavigationMode())
        .apply();
    applyRepeatPolicy();
    applyCurrentPlayback(shouldPlay);
    ensurePortraitWindow();
    modeButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    updateModeUi();
    showChrome();
  }

  private void applyRepeatPolicy() {
    boolean single = order.repeatsCurrent();
    currentPage.setRepeat(single);
    previousPage.setRepeat(single);
    nextPage.setRepeat(single);
    syncCurrentPage();
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
    modeButton.setTooltipText(
        portraitMode ? (mode == PlaybackOrder.SHUFFLE ? "随机切集" : "顺序切集") : name);
    modeButton.setContentDescription(
        portraitMode
            ? "当前视频循环，" + (mode == PlaybackOrder.SHUFFLE ? "随机" : "顺序") + "切集，点击切换"
            : "当前" + name + "，点击切换播放模式");
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
    cancelTapRecognition();
    handler.removeCallbacks(hideChrome);
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    labels.add("倍速 · " + speed + "×");
    actions.add(this::chooseSpeed);
    labels.add("灵敏度");
    actions.add(this::chooseSensitivity);
    labels.add(portraitMode ? "切集顺序" : "播放模式");
    actions.add(this::choosePlaybackMode);
    labels.add(portraitMode ? "全屏播放" : "竖屏播放");
    actions.add(() -> setPortraitMode(!portraitMode));
    if (!portraitMode) {
      labels.add("自动方向");
      actions.add(
          () -> {
            orientationOverride = false;
            adaptOrientation(videoWidth(), videoHeight());
            toast("自动适配");
          });
    }
    labels.add("手势说明");
    actions.add(this::showGestureHelp);
    labels.add("重置权重");
    actions.add(this::resetWeights);
    new AlertDialog.Builder(this, Ui.dialogTheme(this))
        .setTitle("设置")
        .setItems(labels.toArray(new String[0]), (d, item) -> actions.get(item).run())
        .setOnDismissListener(d -> scheduleHide())
        .show();
  }

  private void showGestureHelp() {
    new AlertDialog.Builder(this, Ui.dialogTheme(this))
        .setTitle("手势说明")
        .setMessage(
            "横滑调进度，短滑微调、长滑加速；松手定位。长按临时两倍速。\n\n"
                + "全屏：左侧上下调亮度，右侧调音量。\n\n"
                + "竖屏：当前视频循环，上滑下一条、下滑上一条；切集可选顺序或随机。\n\n"
                + "全屏单击显示控件，双击播放或暂停；竖屏单击播放或暂停，双击点赞。\n\n"
                + "同一播放窗口中，每个视频只计一次赞或踩；再次点按取消，重新打开可再次计入。近期赞踩与观看的比例影响随机权重，并以7天为半衰期减弱。\n\n"
                + "手机与展开图标切换模式；旋转键切换方向，长按恢复自动适配。")
        .setPositiveButton("关闭", (dialog, which) -> showChrome())
        .show();
  }

  private void choosePlaybackMode() {
    final boolean navigation = portraitMode;
    String[] choices =
        navigation ? new String[] {"顺序", "随机"} : new String[] {"顺序连播", "单集循环", "随机播放"};
    int selected = navigation ? (order.mode() == PlaybackOrder.SHUFFLE ? 1 : 0) : order.mode();
    new AlertDialog.Builder(this, Ui.dialogTheme(this))
        .setTitle(navigation ? "切集顺序" : "播放模式")
        .setSingleChoiceItems(
            choices,
            selected,
            (dialog, choice) -> {
              setPlaybackMode(
                  navigation
                      ? (choice == 1 ? PlaybackOrder.SHUFFLE : PlaybackOrder.SEQUENTIAL)
                      : choice);
              dialog.dismiss();
            })
        .setNegativeButton("取消", (dialog, which) -> showChrome())
        .show();
  }

  private void chooseSpeed() {
    float[] values = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
    String[] labels = {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"};
    int selected = 2;
    for (int i = 0; i < values.length; i++) if (values[i] == speed) selected = i;
    new AlertDialog.Builder(this, Ui.dialogTheme(this))
        .setTitle("倍速")
        .setSingleChoiceItems(
            labels,
            selected,
            (dialog, item) -> {
              speed = values[item];
              currentPage.setSpeed(playbackRate());
              previousPage.setSpeed(speed);
              nextPage.setSpeed(speed);
              dialog.dismiss();
              showChrome();
            })
        .setNegativeButton("取消", (d, w) -> showChrome())
        .show();
  }

  private void chooseSensitivity() {
    float[] values = {.65f, 1f, 1.5f};
    int selected = sensitivity < .9f ? 0 : sensitivity > 1.2f ? 2 : 1;
    new AlertDialog.Builder(this, Ui.dialogTheme(this))
        .setTitle("灵敏度")
        .setSingleChoiceItems(
            new String[] {"精细", "均衡", "快速"},
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
    if (!focus) cancelTapRecognition();
    if (!focus && touchDown) cancelInteractions();
    if (focus && root != null) immersive();
  }

  private ImageButton icon(int glyph, String label, int size, int color) {
    ImageButton button = new ImageButton(this);
    button.setImageDrawable(new PlayerIcons(glyph, color));
    button.setContentDescription(label);
    button.setTooltipText(label);
    button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    button.setPadding(dp(12), dp(12), dp(12), dp(12));
    button.setBackground(ripple(Color.TRANSPARENT));
    button.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    return button;
  }

  private ImageButton feedbackButton(String label) {
    ImageButton button = icon(PlayerIcons.PLAY, label, 48, TEXT);
    button.setPadding(dp(12), dp(12), dp(12), dp(12));
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
        ColorStateList.valueOf(0x33ffffff), rounded(base, 12), rounded(Color.WHITE, 12));
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
