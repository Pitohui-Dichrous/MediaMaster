package io.mediamaster.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.recyclerview.widget.*;
import io.mediamaster.app.library.*;
import io.mediamaster.app.media.*;
import io.mediamaster.app.player.PlayerActivity;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity {
  private VideoRepository repository;
  private ThumbnailLoader thumbnails;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<VideoItem> all = new ArrayList<>(), visible = new ArrayList<>();
  private static final int INDEX_SCAN = 0, FOLDER_SCAN = 1, FULL_SCAN = 2;
  private MediaIndexObserver mediaObserver;
  private int queuedScan = -1;
  private boolean queuedScanFeedback, scanShowingStatus, fullScanAfterPermission;
  private RecyclerView list;
  private LibraryHeader libraryHeader;
  private LibraryFastScroller fastScroller;
  private VideoAdapter adapter;
  private PullRefreshLayout refresh;
  private TextView count, status, sortButton, emptyTitle, emptyDetail;
  private LinearLayout empty;
  private Button emptyAction;
  private EditText search;
  private ImageButton scanButton;
  private boolean scanning, scanAgain, active, destroyed;
  private int sort = 0;
  private int renderedDateKind = -1;
  private String query = "";
  private Parcelable restoredListState;
  private VideoItem pendingItem;
  private String pendingName;
  private boolean pendingDelete, pendingConsentCompletes, mutationInFlight, pendingWaitConsent;
  private final Runnable reconcileMediaIndex =
      () -> {
        if (active && !destroyed) scan(false);
      };
  private final Runnable clearRefreshStatus =
      () -> {
        if (!scanShowingStatus
            && status != null
            && ("刷新成功".contentEquals(status.getText()) || "扫描完成".contentEquals(status.getText())))
          libraryStatus("", null);
      };

  @Override
  public void onCreate(Bundle state) {
    Ui.configure(this);
    setTheme(Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(state);
    TaskIdentity.update(this);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    updateSystemBarAppearance();
    repository = new VideoRepository(this);
    mediaObserver = new MediaIndexObserver(this, handler, this::scheduleMediaReconcile);
    thumbnails = new ThumbnailLoader(this);
    sort = getPreferences(0).getInt("sort", 0);
    if (state != null) {
      query = state.getString("library_query", "");
      restoredListState = state.getParcelable("library_scroll");
      fullScanAfterPermission = state.getBoolean("full_scan_after_permission");
    }
    if (state != null && state.getBoolean("pending_consent")) {
      pendingItem = (VideoItem) state.getSerializable("pending_item");
      pendingName = state.getString("pending_name");
      pendingDelete = state.getBoolean("pending_delete");
      pendingConsentCompletes = state.getBoolean("consent_completes");
      pendingWaitConsent = pendingItem != null;
      mutationInFlight = pendingWaitConsent;
    }
    buildUi();
    search.setText(query);
    search.setSelection(search.length());
    worker.execute(
        () -> {
          List<VideoItem> cached = repository.loadCache();
          handler.post(
              () -> {
                if (!destroyed && all.isEmpty()) {
                  all.addAll(cached);
                  filter();
                }
              });
        });
  }

  private int d(float n) {
    return Ui.dp(this, n);
  }

  private LinearLayout row() {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    return r;
  }

  private LinearLayout column() {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.VERTICAL);
    return r;
  }

  private void buildUi() {
    LinearLayout root = column();
    root.setBackgroundColor(Ui.BG);
    root.setFitsSystemWindows(true);
    setContentView(root);
    libraryHeader = new LibraryHeader(this);

    LinearLayout titleRow = row();
    titleRow.setBackgroundColor(Ui.BG);
    titleRow.setPadding(d(16), d(8), d(8), 0);
    TextView wordmark = Ui.text(this, "映序", 20, Ui.TEXT);
    wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    titleRow.addView(
        wordmark,
        new LinearLayout.LayoutParams(0, Math.max(d(48), wordmark.getLineHeight() + d(8)), 1));
    ImageButton folders = Ui.icon(this, "folder", "扫描文件夹");
    titleRow.addView(folders, new LinearLayout.LayoutParams(d(48), d(48)));
    folders.setOnClickListener(v -> sources());
    ImageButton settings = Ui.icon(this, "settings", "设置");
    LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(d(48), d(48));
    sep.leftMargin = d(8);
    titleRow.addView(settings, sep);
    settings.setOnClickListener(v -> settings());

    LinearLayout searchSection = column();
    searchSection.setBackgroundColor(Ui.BG);
    searchSection.setPadding(d(16), d(8), d(16), d(8));
    LinearLayout searchBar = row();
    Ui.surface(searchBar, Ui.SURFACE, 8);
    searchSection.addView(searchBar, new LinearLayout.LayoutParams(-1, d(48)));
    ImageView glass = new ImageView(this);
    glass.setImageDrawable(new Ui.Icon("search", Ui.MUTED));
    glass.setPadding(d(12), d(12), d(12), d(12));
    searchBar.addView(glass, new LinearLayout.LayoutParams(d(48), d(48)));
    glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    search = new EditText(this);
    search.setTextSize(14);
    search.setTextColor(Ui.TEXT);
    search.setHintTextColor(Ui.MUTED);
    search.setHint("搜索视频");
    search.setContentDescription("按文件名搜索视频");
    search.setSingleLine(true);
    search.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    search.setPadding(0, 0, d(8), 0);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    searchBar.getLayoutParams().height = Math.max(d(48), search.getLineHeight() + d(16));
    searchBar.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
    ImageButton clear = Ui.icon(this, "close", "清空搜索");
    clear.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int c) {
            query = s.toString();
            clear.setVisibility(s.length() == 0 ? View.INVISIBLE : View.VISIBLE);
            filter();
          }

          public void afterTextChanged(Editable e) {}
        });
    searchBar.addView(clear, new LinearLayout.LayoutParams(d(48), d(48)));
    clear.setOnClickListener(v -> search.setText(""));

    LinearLayout randomSection = column();
    randomSection.setBackgroundColor(Ui.BG);
    randomSection.setPadding(d(16), 0, d(16), d(8));
    Button random = new PressFeedbackButton(this);
    random.setText("随机播放");
    random.setTextSize(14);
    random.setSingleLine(true);
    random.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    random.setTextColor(Ui.ON_ACCENT);
    random.setAllCaps(false);
    random.setGravity(Gravity.CENTER);
    random.setPadding(d(16), 0, d(16), 0);
    random.setMinWidth(0);
    random.setMinimumWidth(d(128));
    random.setStateListAnimator(null);
    random.setElevation(0);
    Ui.Icon shuffle = new Ui.Icon("shuffle", Ui.ON_ACCENT);
    shuffle.setBounds(0, 0, d(20), d(20));
    random.setCompoundDrawables(shuffle, null, null, null);
    random.setCompoundDrawablePadding(d(8));
    Ui.surface(random, Ui.ACCENT, 8);
    random.setContentDescription("随机播放，开启随机连播");
    random.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          playRandom();
        });
    randomSection.addView(
        random, new LinearLayout.LayoutParams(-1, Math.max(d(48), random.getLineHeight() + d(16))));

    LinearLayout librarySection = column();
    librarySection.setBackgroundColor(Ui.BG);
    librarySection.setPadding(d(16), 0, d(8), d(8));
    LinearLayout library = row();
    library.setMinimumHeight(d(48));
    librarySection.addView(library, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout summary = column();
    summary.setGravity(Gravity.CENTER_VERTICAL);
    library.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
    TextView title = Ui.text(this, "全部视频", 16, Ui.TEXT);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    title.setSingleLine(true);
    summary.addView(title);
    count = Ui.text(this, "", 12, Ui.MUTED);
    count.setMaxLines(2);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
    cp.topMargin = d(4);
    summary.addView(count, cp);
    sortButton = Ui.text(this, "", 12, Ui.TEXT);
    sortButton.setPadding(d(12), 0, d(12), 0);
    sortButton.setGravity(Gravity.CENTER);
    sortButton.setSingleLine(true);
    Ui.ripple(sortButton, android.graphics.Color.TRANSPARENT, 8);
    int sortHeight = Math.max(d(48), sortButton.getLineHeight() + d(16));
    library.addView(sortButton, new LinearLayout.LayoutParams(-2, sortHeight));
    library.setMinimumHeight(sortHeight);
    sortButton.setOnClickListener(v -> sortDialog());
    scanButton = Ui.icon(this, "scan", "刷新视频目录");
    LinearLayout.LayoutParams scanp = new LinearLayout.LayoutParams(d(48), d(48));
    scanp.leftMargin = d(8);
    library.addView(scanButton, scanp);
    scanButton.setOnClickListener(v -> scan(true));
    libraryHeader.setSections(titleRow, searchSection, randomSection, librarySection);

    FrameLayout body = new FrameLayout(this);
    refresh = new PullRefreshLayout(this);
    refresh.setColors(Ui.CONTROL, Ui.BG);
    body.addView(refresh, new FrameLayout.LayoutParams(-1, -1));
    refresh.setOnRefreshListener(() -> scan(true));
    list = new RecyclerView(this);
    list.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    list.setLayoutManager(new LinearLayoutManager(this));
    list.setClipToPadding(false);
    // Reserve a separate edge lane so the full-size row menu never sits under the fast handle.
    list.setPadding(d(8), 0, d(24), d(16));
    list.setItemAnimator(null);
    adapter = new VideoAdapter();
    list.setAdapter(adapter);
    refresh.addView(list);
    refresh.setOnChildScrollUpCallback(() -> list.canScrollVertically(-1));
    list.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(RecyclerView view, int dx, int dy) {
            libraryHeader.setCollapseOffset(list.computeVerticalScrollOffset());
            if (fastScroller != null)
              fastScroller.setTopInset(
                  Math.round(libraryHeader.collapseRange() - libraryHeader.collapseOffset()));
          }
        });
    fastScroller = new LibraryFastScroller(this, list);
    body.addView(fastScroller, new FrameLayout.LayoutParams(d(24), -1, Gravity.RIGHT));
    libraryHeader.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          fastScroller.setTopInset(
              Math.round(libraryHeader.collapseRange() - libraryHeader.collapseOffset()));
          refresh.setIndicatorInset(libraryHeader.collapseRange());
        });

    empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(d(24), d(8), d(24), d(24));
    body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    emptyTitle = Ui.text(this, "暂无视频", 16, Ui.TEXT);
    emptyTitle.setGravity(Gravity.CENTER);
    empty.addView(emptyTitle, new LinearLayout.LayoutParams(-1, -2));
    emptyDetail = Ui.text(this, "", 13, Ui.MUTED);
    emptyDetail.setGravity(Gravity.CENTER);
    emptyDetail.setVisibility(View.GONE);
    LinearLayout.LayoutParams ed = new LinearLayout.LayoutParams(-1, -2);
    ed.topMargin = d(8);
    empty.addView(emptyDetail, ed);
    emptyAction = new Button(this);
    emptyAction.setText("授权");
    emptyAction.setTextColor(Ui.ON_ACCENT);
    emptyAction.setTextSize(14);
    emptyAction.setAllCaps(false);
    emptyAction.setPadding(d(16), 0, d(16), 0);
    emptyAction.setStateListAnimator(null);
    emptyAction.setElevation(0);
    Ui.ripple(emptyAction, Ui.ACCENT, 8);
    LinearLayout.LayoutParams ea =
        new LinearLayout.LayoutParams(-2, Math.max(d(48), emptyAction.getLineHeight() + d(16)));
    ea.topMargin = d(16);
    empty.addView(emptyAction, ea);
    emptyAction.setOnClickListener(
        v -> {
          if (!hasLibraryAccess()) permissions();
          else sources();
        });
    root.addView(
        new LibraryViewport(this, libraryHeader, body, list),
        new LinearLayout.LayoutParams(-1, 0, 1));
    status = Ui.text(this, "", 12, Ui.MUTED);
    status.setGravity(Gravity.CENTER);
    status.setPadding(d(16), d(8), d(16), d(8));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    status.setVisibility(View.GONE);
    root.addView(status, new LinearLayout.LayoutParams(-1, -2));
    filter();
  }

  private void libraryStatus(String message, View.OnClickListener action) {
    handler.removeCallbacks(clearRefreshStatus);
    status.setText(message);
    status.setOnClickListener(action);
    status.setMinimumHeight(d(action == null ? 32 : 48));
    status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private boolean mediaPermission() {
    return Build.VERSION.SDK_INT >= 33
        ? checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
            == PackageManager.PERMISSION_GRANTED
        : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
  }

  private boolean partialPermission() {
    return Build.VERSION.SDK_INT >= 34
        && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            == PackageManager.PERMISSION_GRANTED;
  }

  /** Access checks only: never enumerate files or start a library scan. */
  private boolean hasLibraryAccess() {
    if (mediaPermission()
        || partialPermission()
        || (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())) return true;
    List<UriPermission> grants = null;
    for (String source : repository.getSources()) {
      if (source.startsWith("content://")) {
        if (grants == null) grants = getContentResolver().getPersistedUriPermissions();
        Uri configured = Uri.parse(source);
        for (UriPermission grant : grants)
          if (grant.isReadPermission() && configured.equals(grant.getUri())) return true;
      } else {
        try {
          java.io.File directory = new java.io.File(source);
          if (directory.isDirectory() && directory.canRead()) return true;
        } catch (SecurityException inaccessible) {
          // A configured path alone does not prove that its permission is still valid.
        }
      }
    }
    return false;
  }

  private void permissions() {
    if (Build.VERSION.SDK_INT >= 34)
      requestPermissions(
          new String[] {
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
          },
          40);
    else if (Build.VERSION.SDK_INT >= 33)
      requestPermissions(new String[] {Manifest.permission.READ_MEDIA_VIDEO}, 40);
    else
      requestPermissions(
          new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
          },
          40);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    super.onRequestPermissionsResult(code, p, r);
    if (code == 40) {
      if (active) {
        mediaObserver.stop();
        mediaObserver.start();
      }
      if (fullScanAfterPermission && mediaPermission()) {
        fullScanAfterPermission = false;
        deepScan(true);
      } else {
        fullScanAfterPermission = false;
        scan(false);
      }
      if (!hasLibraryAccess())
        new AlertDialog.Builder(this)
            .setTitle("视频权限")
            .setMessage("在系统设置中允许访问视频，或选择要授权的文件夹。")
            .setPositiveButton(
                "系统设置",
                (a, b) ->
                    startActivityForResult(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())),
                        44))
            .setNeutralButton("添加目录", (a, b) -> pickFolder())
            .setNegativeButton("稍后", null)
            .show();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    TaskIdentity.update(this);
    active = true;
    thumbnails.setPaused(false);
    mediaObserver.start();
    // Query only the published media index; this also covers changes missed while backgrounded.
    handler.removeCallbacks(reconcileMediaIndex);
    scan(false);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) updateSystemBarAppearance();
  }

  private void updateSystemBarAppearance() {
    androidx.core.view.WindowInsetsControllerCompat bars =
        androidx.core.view.WindowCompat.getInsetsController(
            getWindow(), getWindow().getDecorView());
    boolean light = !Ui.isDark(this);
    bars.setAppearanceLightStatusBars(light);
    bars.setAppearanceLightNavigationBars(light);
  }

  @Override
  protected void onPause() {
    active = false;
    mediaObserver.stop();
    handler.removeCallbacks(reconcileMediaIndex);
    super.onPause();
  }

  @Override
  protected void onStop() {
    thumbnails.setPaused(true);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    mediaObserver.stop();
    handler.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    thumbnails.close();
    super.onDestroy();
  }

  private void scan(boolean manual) {
    requestLibraryScan(INDEX_SCAN, manual);
  }

  private void scanFolders(boolean manual) {
    requestLibraryScan(FOLDER_SCAN, manual);
  }

  private void deepScan(boolean manual) {
    requestLibraryScan(FULL_SCAN, manual);
  }

  private void scheduleMediaReconcile() {
    if (!active || destroyed) return;
    // A download/rename may publish several notifications. One trailing query handles the batch.
    handler.removeCallbacks(reconcileMediaIndex);
    handler.postDelayed(reconcileMediaIndex, 250);
  }

  private void requestLibraryScan(int mode, boolean feedback) {
    if (destroyed) return;
    if (feedback && !hasLibraryAccess()) {
      refresh.setRefreshing(false);
      permissions();
      return;
    }
    if (scanning || mutationInFlight) {
      queuedScan = Math.max(queuedScan, mode);
      queuedScanFeedback |= feedback;
      scanAgain = true;
      return;
    }
    final int runMode = Math.max(mode, queuedScan);
    final boolean showFeedback = feedback || queuedScanFeedback;
    queuedScan = -1;
    queuedScanFeedback = false;
    scanAgain = false;
    scanning = true;
    scanShowingStatus = showFeedback;
    if (showFeedback) {
      scanButton.setEnabled(false);
      libraryStatus(runMode == INDEX_SCAN ? "正在刷新" : "正在扫描", null);
      refresh.setRefreshing(true);
    }
    worker.execute(
        () -> {
          try {
            List<VideoItem> items =
                runMode == FULL_SCAN
                    ? repository.scan()
                    : runMode == FOLDER_SCAN
                        ? repository.scanFolders()
                        : repository.refreshMediaIndex();
            List<String> warnings = repository.getWarnings();
            handler.post(
                () -> {
                  if (destroyed) return;
                  all.clear();
                  all.addAll(items);
                  scanning = false;
                  scanShowingStatus = false;
                  if (showFeedback) refresh.setRefreshing(false);
                  scanButton.setEnabled(true);
                  filter();
                  showScanResult(items, warnings, showFeedback, runMode);
                  runQueuedScan();
                });
            // Publishing rows does not wait for persistence. Unchanged snapshots do not rewrite it.
            repository.saveCache(items);
          } catch (Exception error) {
            handler.post(
                () -> {
                  scanning = false;
                  scanShowingStatus = false;
                  if (destroyed) return;
                  if (showFeedback) refresh.setRefreshing(false);
                  scanButton.setEnabled(true);
                  libraryStatus(
                      runMode == INDEX_SCAN ? "更新失败" : "扫描失败",
                      v -> requestLibraryScan(runMode, true));
                  if (showFeedback) toast("更新失败：" + safeMessage(error));
                  runQueuedScan();
                });
          }
        });
  }

  private void runQueuedScan() {
    if (active && !destroyed && !scanning && !mutationInFlight && queuedScan >= 0)
      requestLibraryScan(queuedScan, queuedScanFeedback);
  }

  private void showScanResult(
      List<VideoItem> items, List<String> warnings, boolean feedback, int mode) {
    if (!warnings.isEmpty()) {
      libraryStatus(
          "部分目录未读",
          v ->
              new AlertDialog.Builder(this)
                  .setTitle("扫描提示")
                  .setMessage(android.text.TextUtils.join("\n\n", warnings))
                  .setPositiveButton("知道了", null)
                  .show());
    } else if (partialPermission() && !mediaPermission()) {
      libraryStatus("部分视频授权", v -> permissions());
    } else if (items.isEmpty() && !hasLibraryAccess()) {
      libraryStatus("未授权", v -> permissions());
    } else if (feedback) {
      libraryStatus(mode == INDEX_SCAN ? "刷新成功" : "扫描完成", null);
      handler.postDelayed(clearRefreshStatus, 1800);
    } else if ("部分目录未读".contentEquals(status.getText())
        || "部分视频授权".contentEquals(status.getText())
        || "未授权".contentEquals(status.getText())
        || "更新失败".contentEquals(status.getText())) {
      libraryStatus("", null);
    }
    if (feedback && !warnings.isEmpty()) toast("刷新完成，请查看扫描提示");
  }

  private static final String[] SORT_FIELDS = {"修改时间", "保存时间", "视频名称", "视频时长", "文件大小"};

  private void sortDialog() {
    LinearLayout panel = column();
    panel.setPadding(d(24), d(8), d(24), 0);
    RadioGroup fields = new RadioGroup(this);
    fields.setOrientation(RadioGroup.VERTICAL);
    for (int index = 0; index < SORT_FIELDS.length; index++) {
      RadioButton option = new RadioButton(this);
      option.setId(View.generateViewId());
      option.setText(SORT_FIELDS[index]);
      option.setTextColor(Ui.TEXT);
      option.setTextSize(16);
      option.setMinHeight(d(52));
      option.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.CONTROL));
      fields.addView(option, new RadioGroup.LayoutParams(-1, -2));
    }
    fields.check(fields.getChildAt(sort / 2).getId());
    panel.addView(fields);
    View divider = new View(this);
    divider.setBackgroundColor(Ui.BORDER);
    LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, d(1));
    gap.topMargin = d(12);
    gap.bottomMargin = d(12);
    panel.addView(divider, gap);
    Switch reverse = new Switch(this);
    reverse.setText("倒序排列");
    reverse.setTextColor(Ui.TEXT);
    reverse.setTextSize(16);
    reverse.setPadding(0, d(8), 0, d(8));
    reverse.setMinHeight(d(56));
    reverse.setContentDescription("倒序排列，关闭时使用正序");
    reverse.setChecked(sort / 2 < 2 ? sort % 2 == 0 : sort % 2 == 1);
    panel.addView(reverse, new LinearLayout.LayoutParams(-1, -2));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(true);
    scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
    new AlertDialog.Builder(this)
        .setTitle("视频排序")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (dialog, which) -> {
              int field =
                  fields.indexOfChild(fields.findViewById(fields.getCheckedRadioButtonId()));
              sort =
                  field * 2
                      + (field < 2 ? (reverse.isChecked() ? 0 : 1) : (reverse.isChecked() ? 1 : 0));
              getPreferences(0).edit().putInt("sort", sort).apply();
              filter();
            })
        .show();
  }

  private void filter() {
    List<VideoItem> oldVisible = new ArrayList<>(visible);
    List<VideoItem> nextVisible = new ArrayList<>();
    String needle = query.trim().toLowerCase(Locale.ROOT);
    for (VideoItem v : all)
      if (v.name.toLowerCase(Locale.ROOT).contains(needle)) nextVisible.add(v);
    Comparator<VideoItem> comparator;
    switch (sort / 2) {
      case 1:
        comparator = Comparator.comparingLong(v -> v.addedMs);
        break;
      case 2:
        Collator col = Collator.getInstance(Locale.CHINA);
        comparator = (a, b) -> col.compare(a.name, b.name);
        break;
      case 3:
        comparator = Comparator.comparingLong(v -> v.durationMs);
        break;
      case 4:
        comparator = Comparator.comparingLong(v -> v.size);
        break;
      default:
        comparator = Comparator.comparingLong(v -> v.modifiedMs);
    }
    boolean reverse = sort < 4 ? sort % 2 == 0 : sort % 2 == 1;
    if (reverse) comparator = comparator.reversed();
    nextVisible.sort(comparator.thenComparing(v -> v.id));
    if (adapter == null) return;
    int dateKind = sort / 2 == 1 ? 1 : 0;
    boolean datePresentationChanged = renderedDateKind != dateKind;
    renderedDateKind = dateKind;
    boolean same = !datePresentationChanged && oldVisible.size() == nextVisible.size();
    for (int index = 0; same && index < oldVisible.size(); index++)
      same = sameVideoContent(oldVisible.get(index), nextVisible.get(index));
    if (!same) {
      LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
      boolean atTop = !list.canScrollVertically(-1);
      int first = layout.findFirstVisibleItemPosition();
      VideoItem anchor = first >= 0 && first < oldVisible.size() ? oldVisible.get(first) : null;
      View firstView = first >= 0 ? layout.findViewByPosition(first) : null;
      int offset = firstView == null ? 0 : layout.getDecoratedTop(firstView) - list.getPaddingTop();
      DiffUtil.DiffResult diff =
          DiffUtil.calculateDiff(
              new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                  return oldVisible.size();
                }

                @Override
                public int getNewListSize() {
                  return nextVisible.size();
                }

                @Override
                public boolean areItemsTheSame(int before, int after) {
                  return videoIdentity(oldVisible.get(before))
                      .equals(videoIdentity(nextVisible.get(after)));
                }

                @Override
                public boolean areContentsTheSame(int before, int after) {
                  return !datePresentationChanged
                      && sameVideoContent(oldVisible.get(before), nextVisible.get(after));
                }
              },
              false);
      visible.clear();
      visible.addAll(nextVisible);
      diff.dispatchUpdatesTo(adapter);
      if (atTop && restoredListState == null && !visible.isEmpty()) {
        layout.scrollToPositionWithOffset(0, 0);
      } else if (anchor != null && restoredListState == null) {
        for (int index = 0; index < visible.size(); index++) {
          VideoItem candidate = visible.get(index);
          if (videoIdentity(anchor).equals(videoIdentity(candidate))
              || (!anchor.path.isEmpty() && anchor.path.equals(candidate.path))) {
            layout.scrollToPositionWithOffset(index, offset);
            break;
          }
        }
      }
    }
    if (restoredListState != null && !visible.isEmpty()) {
      list.getLayoutManager().onRestoreInstanceState(restoredListState);
      restoredListState = null;
    }
    long bytes = 0;
    for (VideoItem v : visible) bytes += v.size;
    count.setText(
        needle.isEmpty()
            ? all.size() + " 个视频  ·  " + Ui.size(bytes)
            : "找到 " + visible.size() + " / " + all.size() + " 个视频");
    sortButton.setText(
        new String[] {
              "修改时间 ↓", "修改时间 ↑", "保存时间 ↓", "保存时间 ↑", "名称 A–Z", "名称 Z–A", "时长 ↑", "时长 ↓", "大小 ↑",
              "大小 ↓"
            }
            [sort]);
    sortButton.setContentDescription("排序：" + sortButton.getText());
    sortButton.setTooltipText("视频排序");
    empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    emptyDetail.setText("");
    emptyDetail.setVisibility(View.GONE);
    if (!needle.isEmpty()) {
      emptyTitle.setText("无搜索结果");
      emptyAction.setVisibility(View.GONE);
    } else {
      boolean accessible = !visible.isEmpty() || hasLibraryAccess();
      emptyTitle.setText(accessible ? "暂无视频" : "未授权");
      emptyAction.setText(accessible ? "添加目录" : "授权");
      emptyAction.setVisibility(View.VISIBLE);
    }
  }

  private static String videoIdentity(VideoItem item) {
    return item.id.isEmpty() ? item.uri : item.id;
  }

  private static boolean sameVideoContent(VideoItem a, VideoItem b) {
    return videoIdentity(a).equals(videoIdentity(b))
        && a.name.equals(b.name)
        && a.uri.equals(b.uri)
        && a.path.equals(b.path)
        && a.size == b.size
        && a.durationMs == b.durationMs
        && a.modifiedMs == b.modifiedMs
        && a.addedMs == b.addedMs
        && a.width == b.width
        && a.height == b.height;
  }

  private void open(int index, boolean shuffle) {
    if (visible.isEmpty()) {
      toast("暂无视频");
      return;
    }
    search.clearFocus();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(search.getWindowToken(), 0);
    PlayerActivity.launch(this, new ArrayList<>(visible), index, shuffle);
  }

  private void playRandom() {
    if (visible.isEmpty()) {
      if (!hasLibraryAccess()) permissions();
      else toast("暂无视频");
      return;
    }
    open(new Random().nextInt(visible.size()), true);
  }

  private void sources() {
    List<String> items = repository.getSources();
    String[] options = new String[5 + items.size()];
    options[0] = "选择文件夹";
    options[1] = "输入路径";
    options[2] = "扫描文件夹";
    options[3] = "全盘扫描";
    options[4] = "视频权限";
    for (int i = 0; i < items.size(); i++) options[5 + i] = Uri.decode(items.get(i));
    new AlertDialog.Builder(this)
        .setTitle("扫描位置")
        .setItems(
            options,
            (dialog, which) -> {
              if (which == 0) pickFolder();
              else if (which == 1) pathDialog();
              else if (which == 2) scanFolders(true);
              else if (which == 3) allFiles();
              else if (which == 4) permissions();
              else {
                String source = items.get(which - 5);
                new AlertDialog.Builder(this)
                    .setTitle(Uri.decode(source))
                    .setItems(
                        new String[] {"重新扫描", "移除目录"},
                        (a, b) -> {
                          if (b == 0) scanFolders(true);
                          else confirmRemoveSource(source);
                        })
                    .setNegativeButton("取消", null)
                    .show();
              }
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  private void confirmRemoveSource(String source) {
    new AlertDialog.Builder(this)
        .setTitle("移除目录？")
        .setMessage(Uri.decode(source) + "\n\n只移除扫描授权记录，视频文件不会删除。媒体库仍可能显示这个位置的视频。")
        .setPositiveButton(
            "移除",
            (a, b) ->
                worker.execute(
                    () -> {
                      repository.removeSource(source);
                      handler.post(() -> scanFolders(true));
                    }))
        .setNegativeButton("取消", null)
        .show();
  }

  private void pickFolder() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    try {
      startActivityForResult(i, 41);
    } catch (Exception e) {
      toast("此设备没有文件夹选择器，请尝试手动输入路径");
    }
  }

  private void pathDialog() {
    EditText input = new EditText(this);
    input.setTextColor(Ui.TEXT);
    input.setHint("/storage/emulated/0/Download");
    input.setText("/storage/emulated/0/Download");
    input.setSingleLine();
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    LinearLayout wrap = column();
    wrap.setPadding(d(24), d(8), d(24), 0);
    wrap.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("输入路径")
            .setView(wrap)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    x -> {
                      String path = input.getText().toString().trim();
                      if (!path.startsWith("/") || path.contains("\u0000")) {
                        input.setError("请输入以 / 开始的完整路径");
                        return;
                      }
                      try {
                        if (Build.VERSION.SDK_INT >= 30
                            && !Environment.isExternalStorageManager()) {
                          requestAllFiles(false);
                          return;
                        }
                        repository.addPath(path);
                        dialog.dismiss();
                        scanFolders(true);
                      } catch (Exception e) {
                        input.setError(safeMessage(e));
                      }
                    }));
    dialog.show();
  }

  private void allFiles() {
    requestAllFiles(true);
  }

  private void requestAllFiles(boolean scanAfterGrant) {
    if (Build.VERSION.SDK_INT >= 30) {
      if (Environment.isExternalStorageManager()) {
        if (scanAfterGrant) deepScan(true);
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle(scanAfterGrant ? "全盘扫描" : "文件访问")
          .setMessage(
              (scanAfterGrant
                      ? "下一步在系统页面允许“管理所有文件”，然后扫描手机共享存储和可访问的存储卡，查找未被媒体库收录的视频。\n\n"
                      : "下一步在系统页面允许“管理所有文件”，然后可按完整路径添加文件夹。\n\n")
                  + "安卓限制的其他应用私有目录无法读取。")
          .setPositiveButton(
              "前往授权",
              (a, b) -> {
                fullScanAfterPermission |= scanAfterGrant;
                try {
                  startActivityForResult(
                      new Intent(
                          Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                          Uri.parse("package:" + getPackageName())),
                      43);
                } catch (Exception e) {
                  startActivityForResult(
                      new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 43);
                }
              })
          .setNegativeButton("取消", null)
          .show();
    } else if (mediaPermission()) {
      if (scanAfterGrant) deepScan(true);
    } else {
      fullScanAfterPermission |= scanAfterGrant;
      permissions();
    }
  }

  private void settings() {
    String[] choices = {"外观", "灵敏度", "解码", "扫描设置", "使用说明", "关于映序"};
    new AlertDialog.Builder(this)
        .setTitle("设置")
        .setItems(
            choices,
            (a, b) -> {
              if (b == 0) {
                appearance();
              } else if (b == 1) {
                float current = getSharedPreferences("player", 0).getFloat("seek_sensitivity", 1f);
                int selected = current < .8f ? 0 : current > 1.2f ? 2 : 1;
                new AlertDialog.Builder(this)
                    .setTitle("灵敏度")
                    .setSingleChoiceItems(
                        new String[] {"精细", "均衡", "快速"},
                        selected,
                        (dialog, which) -> {
                          getSharedPreferences("player", 0)
                              .edit()
                              .putFloat("seek_sensitivity", new float[] {.65f, 1f, 1.5f}[which])
                              .apply();
                          dialog.dismiss();
                        })
                    .setNegativeButton("取消", null)
                    .show();
              } else if (b == 2) decoder();
              else if (b == 3) sources();
              else if (b == 4)
                new AlertDialog.Builder(this)
                    .setTitle("使用说明")
                    .setMessage(
                        "点按：显示或隐藏控制\n"
                            + "双击：播放或暂停\n"
                            + "左右滑动：调整进度\n"
                            + "长按：临时倍速\n"
                            + "全屏左侧上下滑动：亮度\n"
                            + "全屏右侧上下滑动：音量\n"
                            + "竖屏上下滑动：切集；当前视频循环\n\n"
                            + "媒体库中的新视频与改名会自动更新；下拉或点刷新只核对媒体索引。未入库的视频可在“扫描设置”中扫描文件夹或全盘。\n\n"
                            + "保存时间为媒体库收录时间；无记录时使用修改时间。")
                    .setPositiveButton("知道了", null)
                    .show();
              else
                new AlertDialog.Builder(this)
                    .setTitle("映序  " + appVersion())
                    .setMessage(
                        "MediaMaster\nAndroid 8.0+\n\n"
                            + "AndroidX Media3 · Apache 2.0\n"
                            + "LibVLC · LGPL 2.1\n"
                            + "Google Material Icons · Apache 2.0")
                    .setNeutralButton("开源许可", (x, y) -> showLicenses())
                    .setNegativeButton(
                        "图标来源",
                        (x, y) -> {
                          try {
                            startActivity(
                                new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://fonts.google.com/icons")));
                          } catch (ActivityNotFoundException unavailable) {
                            toast("可访问 fonts.google.com/icons 查看图标来源");
                          }
                        })
                    .setPositiveButton("关闭", null)
                    .show();
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  private void decoder() {
    int current = getSharedPreferences("player", 0).getInt("native_decoder_mode", 0);
    int selected = current == 2 ? 0 : current == 1 ? 2 : 1;
    int[] modes = {2, 0, 1};
    new AlertDialog.Builder(this)
        .setTitle("解码")
        .setSingleChoiceItems(
            new String[] {"优先软解", "默认解码", "优先硬解"},
            selected,
            (dialog, choice) -> {
              getSharedPreferences("player", 0)
                  .edit()
                  .putInt("native_decoder_mode", modes[choice])
                  .apply();
              dialog.dismiss();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void appearance() {
    int selected = Ui.isDark(this) ? 1 : 0;
    new AlertDialog.Builder(this)
        .setTitle("外观")
        .setSingleChoiceItems(
            new String[] {"浅色", "深色"},
            selected,
            (dialog, choice) -> {
              dialog.dismiss();
              boolean next = choice == 1;
              if (next == Ui.isDark(this)) return;
              Ui.setDark(this, next);
              recreate();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private String appVersion() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException unavailable) {
      return "";
    }
  }

  private void showLicenses() {
    try {
      StringBuilder text = new StringBuilder();
      for (String asset : new String[] {"NOTICE.txt", "Apache-2.0.txt", "LGPL-2.1.txt"}) {
        try (java.io.InputStream in = getAssets().open("licenses/" + asset)) {
          java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
          byte[] buffer = new byte[4096];
          int n;
          while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
          text.append(out.toString("UTF-8")).append("\n\n");
        }
      }
      TextView body = Ui.text(this, text.toString(), 12, Ui.TEXT);
      body.setTextIsSelectable(true);
      body.setPadding(d(24), d(8), d(24), d(16));
      ScrollView scroll = new ScrollView(this);
      scroll.addView(body);
      new AlertDialog.Builder(this)
          .setTitle("开源许可")
          .setView(scroll)
          .setPositiveButton("关闭", null)
          .show();
    } catch (Exception e) {
      toast("无法打开许可文件");
    }
  }

  private void videoMenu(VideoItem item) {
    new AlertDialog.Builder(this)
        .setTitle(item.name)
        .setItems(
            new String[] {"播放", "重命名", "文件详情", "删除视频"},
            (a, b) -> {
              if (b == 0) {
                int index = visible.indexOf(item);
                if (index >= 0) open(index, false);
              } else if (b == 1) renameDialog(item);
              else if (b == 2) details(item);
              else
                new AlertDialog.Builder(this)
                    .setTitle("删除视频？")
                    .setMessage(item.name + "\n\n此操作会删除手机中的原始文件，无法在映序内撤销。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (x, y) -> mutate(item, null, true))
                    .show();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void renameDialog(VideoItem item) {
    EditText input = new EditText(this);
    input.setText(item.name);
    input.setSingleLine();
    input.setSelectAllOnFocus(true);
    LinearLayout wrap = column();
    wrap.setPadding(d(24), d(12), d(24), 0);
    wrap.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(wrap)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    x -> {
                      String name = input.getText().toString().trim();
                      if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                        input.setError("名称不能为空，也不能包含路径分隔符");
                        return;
                      }
                      String ext = item.extension();
                      if (!name.toLowerCase(Locale.ROOT).endsWith("." + ext)) name += "." + ext;
                      dialog.dismiss();
                      mutate(item, name, false);
                    }));
    dialog.show();
  }

  private void mutate(VideoItem item, String name, boolean delete) {
    if (mutationInFlight) {
      toast("操作处理中");
      return;
    }
    mutationInFlight = true;
    pendingItem = item;
    pendingName = name;
    pendingDelete = delete;
    worker.execute(
        () -> {
          try {
            if (delete) repository.delete(item);
            VideoItem replacement = delete ? null : repository.rename(item, name);
            persistMutation(item, replacement, delete ? "已删除" : "已重命名");
          } catch (MutationConsentException consent) {
            handler.post(
                () -> {
                  if (destroyed || isFinishing()) return;
                  pendingWaitConsent = true;
                  pendingConsentCompletes = consent.operationCompletedByConsent;
                  try {
                    startIntentSenderForResult(consent.intentSender, 42, null, 0, 0, 0);
                  } catch (Exception e) {
                    toast("无法打开系统确认：" + safeMessage(e));
                    pendingItem = null;
                    pendingWaitConsent = false;
                    mutationInFlight = false;
                    runQueuedScan();
                  }
                });
          } catch (Exception e) {
            handler.post(() -> mutationFailed(delete, e));
          }
        });
  }

  /** Runs on the serial library worker, before any queued observer reconciliation. */
  private void persistMutation(VideoItem original, VideoItem replacement, String message) {
    repository.rememberMutation(original, replacement);
    List<VideoItem> items = repository.snapshot();
    handler.post(
        () -> {
          mutationInFlight = false;
          pendingWaitConsent = false;
          pendingItem = null;
          if (destroyed || isFinishing()) return;
          all.clear();
          all.addAll(items);
          filter();
          toast(message);
          runQueuedScan();
          scheduleMediaReconcile();
        });
  }

  private void mutationFailed(boolean deleting, Exception error) {
    mutationInFlight = false;
    pendingWaitConsent = false;
    pendingItem = null;
    if (destroyed || isFinishing()) return;
    new AlertDialog.Builder(this)
        .setTitle(deleting ? "未能删除视频" : "未能重命名")
        .setMessage(safeMessage(error) + "\n\n可尝试在“扫描位置”中重新授权文件夹或开启全盘访问。")
        .setPositiveButton("管理权限", (a, b) -> sources())
        .setNegativeButton("关闭", null)
        .show();
    runQueuedScan();
  }

  private void details(VideoItem item) {
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    String info =
        "名称\n"
            + item.name
            + "\n\n时长  "
            + Ui.duration(item.durationMs)
            + "\n大小  "
            + Ui.size(item.size)
            + "\n分辨率  "
            + (item.width > 0 ? item.width + " × " + item.height : "未提供")
            + "\n修改时间  "
            + df.format(new Date(item.modifiedMs))
            + "\n保存时间  "
            + df.format(new Date(item.addedMs))
            + "\n\n位置\n"
            + (!item.path.isEmpty() ? item.path : Uri.decode(item.uri));
    TextView detail = Ui.text(this, info, 14, Ui.TEXT);
    detail.setTextIsSelectable(true);
    detail.setPadding(d(24), d(12), d(24), d(16));
    ScrollView sc = new ScrollView(this);
    sc.addView(detail);
    new AlertDialog.Builder(this).setTitle("文件详情").setView(sc).setPositiveButton("关闭", null).show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 41 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      try {
        repository.addTree(uri);
        scanFolders(true);
      } catch (Exception e) {
        toast("文件夹授权失败：" + safeMessage(e));
      }
    } else if (request == 43) {
      boolean deep = fullScanAfterPermission;
      fullScanAfterPermission = false;
      if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
        if (deep) deepScan(true);
        else scan(true);
      }
    } else if (request == 44) {
      if (hasLibraryAccess()) scan(true);
    } else if (request == 42) {
      pendingWaitConsent = false;
      mutationInFlight = false;
      VideoItem item = pendingItem;
      if (result == RESULT_OK && item != null) {
        if (pendingConsentCompletes) {
          mutationInFlight = true;
          worker.execute(
              () -> {
                try {
                  persistMutation(item, null, "已删除");
                } catch (Exception error) {
                  handler.post(() -> mutationFailed(true, error));
                }
              });
        } else mutate(item, pendingName, pendingDelete);
      } else {
        pendingItem = null;
        toast("已取消");
        runQueuedScan();
        scheduleMediaReconcile();
      }
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("full_scan_after_permission", fullScanAfterPermission);
    state.putString("library_query", query);
    if (list != null && list.getLayoutManager() != null)
      state.putParcelable("library_scroll", list.getLayoutManager().onSaveInstanceState());
    if (pendingWaitConsent && pendingItem != null) {
      state.putBoolean("pending_consent", true);
      state.putSerializable("pending_item", pendingItem);
      state.putString("pending_name", pendingName);
      state.putBoolean("pending_delete", pendingDelete);
      state.putBoolean("consent_completes", pendingConsentCompletes);
    }
  }

  private String safeMessage(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private void toast(String s) {
    if (!destroyed) Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private final class VideoAdapter extends RecyclerView.Adapter<VideoHolder> {
    private final Map<String, Long> stableIds = new HashMap<>();
    private long nextStableId;

    VideoAdapter() {
      setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
      String identity = videoIdentity(visible.get(position));
      Long value = stableIds.get(identity);
      if (value == null) {
        value = nextStableId++;
        stableIds.put(identity, value);
      }
      return value;
    }

    public VideoHolder onCreateViewHolder(ViewGroup parent, int type) {
      PressFeedbackLayout line = new PressFeedbackLayout(MainActivity.this);
      line.setOrientation(LinearLayout.HORIZONTAL);
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
      line.setPadding(d(8), d(8), 0, d(8));
      line.setMinimumHeight(d(88));
      Ui.surface(line, Ui.BG, 8);
      FrameLayout art = new FrameLayout(MainActivity.this);
      line.addView(art, new LinearLayout.LayoutParams(d(96), d(60)));
      ImageView image = new ImageView(MainActivity.this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      Ui.surface(image, Ui.SURFACE, 8);
      image.setClipToOutline(true);
      image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      art.addView(image, new FrameLayout.LayoutParams(-1, -1));
      TextView duration = Ui.text(MainActivity.this, "", 11, Ui.ON_ACCENT);
      duration.setPadding(d(5), d(2), d(5), d(2));
      Ui.surface(duration, 0xcc1c1c1c, 4);
      FrameLayout.LayoutParams dura =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
      dura.setMargins(0, 0, d(4), d(4));
      art.addView(duration, dura);
      LinearLayout copy = column();
      LinearLayout.LayoutParams copyP = new LinearLayout.LayoutParams(0, -2, 1);
      copyP.leftMargin = d(12);
      line.addView(copy, copyP);
      TextView title = Ui.text(MainActivity.this, "", 14, Ui.TEXT);
      title.setMaxLines(2);
      title.setEllipsize(TextUtils.TruncateAt.END);
      title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      copy.addView(title);
      TextView date = Ui.text(MainActivity.this, "", 12, Ui.MUTED);
      LinearLayout.LayoutParams dateP = new LinearLayout.LayoutParams(-1, -2);
      dateP.topMargin = d(4);
      copy.addView(date, dateP);
      TextView size = Ui.text(MainActivity.this, "", 12, Ui.MUTED);
      LinearLayout.LayoutParams sizeP = new LinearLayout.LayoutParams(-1, -2);
      sizeP.topMargin = d(4);
      copy.addView(size, sizeP);
      ImageButton menu = Ui.icon(MainActivity.this, "more", "视频操作");
      line.addView(menu, new LinearLayout.LayoutParams(d(48), d(48)));
      return new VideoHolder(line, image, duration, title, date, size, menu);
    }

    public void onBindViewHolder(VideoHolder h, int pos) {
      ((PressFeedbackLayout) h.itemView).resetPressFeedback();
      VideoItem v = visible.get(pos);
      h.title.setText(v.name);
      long date = sort / 2 == 1 ? v.addedMs : v.modifiedMs;
      h.date.setText(
          (sort / 2 == 1 ? "保存 " : "修改 ")
              + new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA).format(new Date(date)));
      h.size.setText(Ui.size(v.size) + "  ·  " + v.extension().toUpperCase(Locale.ROOT));
      h.duration.setText(v.durationMs > 0 ? Ui.duration(v.durationMs) : "—");
      thumbnails.bind(v, h.image);
      h.itemView.setOnClickListener(
          x -> {
            int index = h.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) open(index, false);
          });
      h.itemView.setOnLongClickListener(
          x -> {
            videoMenu(v);
            return true;
          });
      h.menu.setOnClickListener(x -> videoMenu(v));
      h.menu.setContentDescription("操作视频 " + v.name);
      h.menu.setTooltipText("视频操作");
    }

    @Override
    public void onViewRecycled(VideoHolder holder) {
      ((PressFeedbackLayout) holder.itemView).resetPressFeedback();
      thumbnails.unbind(holder.image);
      super.onViewRecycled(holder);
    }

    public int getItemCount() {
      return visible.size();
    }
  }

  private static final class VideoHolder extends RecyclerView.ViewHolder {
    final ImageView image;
    final TextView duration, title, date, size;
    final ImageButton menu;

    VideoHolder(
        View v, ImageView i, TextView du, TextView t, TextView da, TextView s, ImageButton m) {
      super(v);
      image = i;
      duration = du;
      title = t;
      date = da;
      size = s;
      menu = m;
    }
  }
}
