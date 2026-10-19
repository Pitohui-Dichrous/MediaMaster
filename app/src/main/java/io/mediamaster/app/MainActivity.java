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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
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
  // Task IDs survive configuration changes but change after the user dismisses the recent task.
  private static int lastAutoScanTaskId = -1;
  private boolean startupRefreshPending;
  private RecyclerView list;
  private LibraryHeader libraryHeader;
  private LibraryFastScroller fastScroller;
  private VideoAdapter adapter;
  private SwipeRefreshLayout refresh;
  private TextView count, status, sortButton, emptyTitle, emptyDetail;
  private LinearLayout empty;
  private Button emptyAction;
  private EditText search;
  private ImageButton scanButton;
  private boolean scanning, scanAgain, active, destroyed;
  private int sort = 0;
  private String query = "";
  private VideoItem pendingItem;
  private String pendingName;
  private boolean pendingDelete, pendingConsentCompletes, mutationInFlight, pendingWaitConsent;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    repository = new VideoRepository(this);
    thumbnails = new ThumbnailLoader(this);
    sort = getPreferences(0).getInt("sort", 0);
    if (state != null && state.getBoolean("pending_consent")) {
      pendingItem = (VideoItem) state.getSerializable("pending_item");
      pendingName = state.getString("pending_name");
      pendingDelete = state.getBoolean("pending_delete");
      pendingConsentCompletes = state.getBoolean("consent_completes");
      pendingWaitConsent = pendingItem != null;
      mutationInFlight = pendingWaitConsent;
    }
    startupRefreshPending = lastAutoScanTaskId != getTaskId();
    if (startupRefreshPending) lastAutoScanTaskId = getTaskId();
    buildUi();
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
    titleRow.setPadding(d(24), d(18), d(24), d(18));
    LinearLayout brand = column();
    titleRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    TextView wordmark = Ui.text(this, "映序", 30, Ui.TEXT);
    wordmark.setTypeface(null, Typeface.BOLD);
    brand.addView(wordmark);
    TextView english = Ui.text(this, "MEDIAMASTER  /  本地视频", 10, Ui.MUTED);
    english.setLetterSpacing(.14f);
    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
    ep.topMargin = d(4);
    brand.addView(english, ep);
    ImageButton folders = Ui.icon(this, "folder", "管理扫描文件夹");
    titleRow.addView(folders, new LinearLayout.LayoutParams(d(48), d(48)));
    folders.setOnClickListener(v -> sources());
    ImageButton settings = Ui.icon(this, "settings", "播放设置与说明");
    LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(d(48), d(48));
    sep.leftMargin = d(8);
    titleRow.addView(settings, sep);
    settings.setOnClickListener(v -> settings());
    LinearLayout searchSection = column();
    searchSection.setBackgroundColor(Ui.BG);
    searchSection.setPadding(d(24), d(8), d(24), d(12));
    LinearLayout searchBar = row();
    Ui.surface(searchBar, Ui.SURFACE, 16);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, d(52));
    searchSection.addView(searchBar, sp);
    ImageView glass = new ImageView(this);
    glass.setImageDrawable(new Ui.Icon("search", Ui.MUTED));
    glass.setPadding(d(14), d(14), d(10), d(14));
    searchBar.addView(glass, new LinearLayout.LayoutParams(d(48), d(52)));
    glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    search = new EditText(this);
    search.setTextSize(15);
    search.setTextColor(Ui.TEXT);
    search.setHintTextColor(Ui.MUTED);
    search.setHint("搜索视频名称");
    search.setContentDescription("按文件名搜索视频");
    search.setSingleLine(true);
    search.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    search.setPadding(0, 0, d(8), 0);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    searchBar.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int c) {
            query = s.toString();
            filter();
          }

          public void afterTextChanged(Editable e) {}
        });
    ImageButton clear = Ui.icon(this, "close", "清空搜索");
    clear.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    searchBar.addView(clear, new LinearLayout.LayoutParams(d(48), d(48)));
    clear.setOnClickListener(v -> search.setText(""));
    LinearLayout randomSection = column();
    randomSection.setBackgroundColor(Ui.BG);
    randomSection.setPadding(d(24), d(4), d(24), d(16));
    LinearLayout random = row();
    random.setPadding(d(18), d(16), d(18), d(16));
    Ui.ripple(random, android.graphics.Color.rgb(30, 58, 51), 20);
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    randomSection.addView(random, rp);
    random.setContentDescription("播放随机视频，自动开启随机连播");
    random.setOnClickListener(v -> playRandom());
    LinearLayout randomCopy = column();
    random.addView(randomCopy, new LinearLayout.LayoutParams(0, -2, 1));
    TextView randomTitle = Ui.text(this, "播放随机视频", 18, Ui.ACCENT);
    randomTitle.setTypeface(null, Typeface.BOLD);
    randomCopy.addView(randomTitle);
    TextView randomSub = Ui.text(this, "换一个片段，发现一点新鲜。", 12, Ui.TEXT);
    LinearLayout.LayoutParams rs = new LinearLayout.LayoutParams(-1, -2);
    rs.topMargin = d(6);
    randomCopy.addView(randomSub, rs);
    ImageView shuffle = new ImageView(this);
    shuffle.setImageDrawable(new Ui.Icon("shuffle", Ui.ACCENT));
    shuffle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    random.addView(shuffle, new LinearLayout.LayoutParams(d(28), d(28)));
    LinearLayout librarySection = column();
    librarySection.setBackgroundColor(Ui.BG);
    librarySection.setPadding(d(24), d(4), d(24), d(10));
    LinearLayout library = row();
    librarySection.addView(library, new LinearLayout.LayoutParams(-1, -2));
    TextView title = Ui.text(this, "全部视频", 18, Ui.TEXT);
    title.setTypeface(null, Typeface.BOLD);
    library.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    sortButton = Ui.text(this, "", 12, Ui.MUTED);
    sortButton.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
    sortButton.setPadding(d(12), 0, d(12), 0);
    sortButton.setGravity(Gravity.CENTER);
    Ui.ripple(sortButton, Ui.SURFACE, 12);
    library.addView(sortButton, new LinearLayout.LayoutParams(-2, d(48)));
    sortButton.setOnClickListener(v -> sortDialog());
    scanButton = Ui.icon(this, "scan", "立即扫描视频");
    LinearLayout.LayoutParams scanp = new LinearLayout.LayoutParams(d(48), d(48));
    scanp.leftMargin = d(8);
    library.addView(scanButton, scanp);
    scanButton.setOnClickListener(v -> scan(true));
    count = Ui.text(this, "正在读取视频目录…", 12, Ui.MUTED);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
    cp.topMargin = d(2);
    librarySection.addView(count, cp);
    libraryHeader.setSections(titleRow, searchSection, randomSection, librarySection);
    FrameLayout body = new FrameLayout(this);
    refresh = new SwipeRefreshLayout(this);
    refresh.setColorSchemeColors(Ui.ACCENT);
    refresh.setProgressBackgroundColorSchemeColor(Ui.SURFACE);
    body.addView(refresh, new FrameLayout.LayoutParams(-1, -1));
    refresh.setOnRefreshListener(() -> scan(true));
    list = new RecyclerView(this);
    list.setLayoutManager(new LinearLayoutManager(this));
    list.setClipToPadding(false);
    list.setPadding(d(16), 0, d(20), d(16));
    list.setItemAnimator(null);
    adapter = new VideoAdapter();
    list.setAdapter(adapter);
    refresh.addView(list);
    refresh.setOnChildScrollUpCallback((parent, child) -> list.canScrollVertically(-1));
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
    FrameLayout.LayoutParams scrollbar = new FrameLayout.LayoutParams(d(48), -1, Gravity.RIGHT);
    body.addView(fastScroller, scrollbar);
    libraryHeader.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) ->
            fastScroller.setTopInset(
                Math.round(libraryHeader.collapseRange() - libraryHeader.collapseOffset())));
    empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(d(32), d(8), d(32), d(24));
    body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    ImageView emptyIcon = new ImageView(this);
    emptyIcon.setImageDrawable(new Ui.Icon("video", Ui.MUTED));
    empty.addView(emptyIcon, new LinearLayout.LayoutParams(d(48), d(48)));
    emptyTitle = Ui.text(this, "把你的片库带进来", 19, Ui.TEXT);
    emptyTitle.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams et = new LinearLayout.LayoutParams(-1, -2);
    et.topMargin = d(18);
    empty.addView(emptyTitle, et);
    emptyDetail = Ui.text(this, "允许读取视频后，自动发现手机中的本地视频。", 13, Ui.MUTED);
    emptyDetail.setGravity(Gravity.CENTER);
    emptyDetail.setLineSpacing(d(5), 1);
    LinearLayout.LayoutParams ed = new LinearLayout.LayoutParams(-1, -2);
    ed.topMargin = d(10);
    empty.addView(emptyDetail, ed);
    emptyAction = new Button(this);
    emptyAction.setText("允许读取视频");
    emptyAction.setTextColor(Ui.INK);
    emptyAction.setTextSize(14);
    emptyAction.setAllCaps(false);
    emptyAction.setPadding(d(24), 0, d(24), 0);
    Ui.ripple(emptyAction, Ui.ACCENT, 14);
    LinearLayout.LayoutParams ea = new LinearLayout.LayoutParams(-2, d(50));
    ea.topMargin = d(22);
    empty.addView(emptyAction, ea);
    emptyAction.setOnClickListener(
        v -> {
          if (!mediaPermission() && !partialPermission()) permissions();
          else sources();
        });
    LibraryViewport viewport = new LibraryViewport(this, libraryHeader, body, list);
    root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
    status = Ui.text(this, "首次使用请允许读取视频", 11, Ui.MUTED);
    status.setGravity(Gravity.CENTER);
    status.setMinimumHeight(d(32));
    status.setPadding(d(24), d(6), d(24), d(8));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status, new LinearLayout.LayoutParams(-1, -2));
    filter();
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
      scan(false);
      if (!mediaPermission() && !partialPermission())
        new AlertDialog.Builder(this)
            .setTitle("尚未获得视频权限")
            .setMessage("可以在系统设置中允许访问视频，也可以添加一个文件夹来授权读取。所有视频都保留在手机本地。")
            .setPositiveButton(
                "系统设置",
                (a, b) ->
                    startActivityForResult(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())),
                        44))
            .setNeutralButton("添加文件夹", (a, b) -> pickFolder())
            .setNegativeButton("稍后", null)
            .show();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    thumbnails.setPaused(false);
    if (startupRefreshPending) {
      startupRefreshPending = false;
      if (!scanning && !mutationInFlight) scan(false);
    }
  }

  @Override
  protected void onPause() {
    active = false;
    scanAgain = false;
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
    handler.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    thumbnails.close();
    super.onDestroy();
  }

  private void scan(boolean manual) {
    if (destroyed) return;
    if (manual
        && !mediaPermission()
        && !partialPermission()
        && repository.getSources().isEmpty()
        && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())) {
      refresh.setRefreshing(false);
      permissions();
      return;
    }
    if (scanning) {
      scanAgain = true;
      return;
    }
    scanning = true;
    scanButton.setEnabled(false);
    status.setText("正在扫描本地视频…");
    if (manual) refresh.setRefreshing(true);
    worker.execute(
        () -> {
          try {
            List<VideoItem> items = repository.scan();
            repository.saveCache(items);
            List<String> warnings = repository.getWarnings();
            handler.post(
                () -> {
                  if (destroyed) return;
                  all.clear();
                  all.addAll(items);
                  scanning = false;
                  refresh.setRefreshing(false);
                  scanButton.setEnabled(true);
                  filter();
                  boolean limited = partialPermission() && !mediaPermission();
                  String note =
                      !warnings.isEmpty()
                          ? "部分目录未读取，点此查看"
                          : limited ? "当前只获准读取所选视频 · 点此扩大授权" : "已更新 · 下拉或点扫描按钮刷新";
                  if (!mediaPermission() && !partialPermission() && items.isEmpty())
                    note = "允许读取视频，或添加扫描文件夹";
                  status.setText(note);
                  status.setOnClickListener(
                      v -> {
                        if (limited) permissions();
                        else if (!warnings.isEmpty())
                          new AlertDialog.Builder(this)
                              .setTitle("扫描提示")
                              .setMessage(android.text.TextUtils.join("\n\n", warnings))
                              .setPositiveButton("知道了", null)
                              .show();
                        else sources();
                      });
                  if (scanAgain && active) {
                    scanAgain = false;
                    scan(false);
                  }
                });
          } catch (Exception error) {
            handler.post(
                () -> {
                  scanning = false;
                  if (destroyed) return;
                  refresh.setRefreshing(false);
                  scanButton.setEnabled(true);
                  status.setText("扫描未完成，点击重新扫描");
                  status.setOnClickListener(v -> scan(true));
                  toast("扫描失败：" + safeMessage(error));
                });
          }
        });
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
      option.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
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
    new AlertDialog.Builder(this)
        .setTitle("视频排序")
        .setView(panel)
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
    visible.clear();
    String needle = query.trim().toLowerCase(Locale.ROOT);
    for (VideoItem v : all) if (v.name.toLowerCase(Locale.ROOT).contains(needle)) visible.add(v);
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
    visible.sort(comparator.thenComparing(v -> v.id));
    if (adapter == null) return;
    adapter.notifyDataSetChanged();
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
    empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    if (!needle.isEmpty()) {
      emptyTitle.setText("没有找到相关视频");
      emptyDetail.setText("试试更短的关键词，或清空搜索查看全部视频。");
      emptyAction.setVisibility(View.GONE);
    } else {
      emptyTitle.setText(mediaPermission() || partialPermission() ? "视频库还是空的" : "把你的片库带进来");
      emptyDetail.setText(
          mediaPermission() || partialPermission()
              ? "下拉刷新，或添加视频所在的文件夹。\nMP4、MKV、AVI 等本地视频均可加入。"
              : "允许读取视频后，自动发现手机中的本地视频。");
      emptyAction.setText(mediaPermission() || partialPermission() ? "添加视频文件夹" : "允许读取视频");
      emptyAction.setVisibility(View.VISIBLE);
    }
  }

  private void open(int index, boolean shuffle) {
    if (visible.isEmpty()) {
      toast("请先添加一些视频");
      return;
    }
    search.clearFocus();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(search.getWindowToken(), 0);
    PlayerActivity.launch(this, new ArrayList<>(visible), index, shuffle);
  }

  private void playRandom() {
    if (visible.isEmpty()) {
      if (!mediaPermission() && !partialPermission()) permissions();
      else toast("视频库为空，请先添加视频");
      return;
    }
    open(new Random().nextInt(visible.size()), true);
  }

  private void sources() {
    List<String> items = repository.getSources();
    String[] options = new String[4 + items.size()];
    options[0] = "选择文件夹（推荐）";
    options[1] = "手动输入文件夹路径";
    options[2] = "全盘访问与扫描";
    options[3] = "视频访问权限";
    for (int i = 0; i < items.size(); i++) options[4 + i] = "管理：" + Uri.decode(items.get(i));
    new AlertDialog.Builder(this)
        .setTitle("扫描位置")
        .setItems(
            options,
            (dialog, which) -> {
              if (which == 0) pickFolder();
              else if (which == 1) pathDialog();
              else if (which == 2) allFiles();
              else if (which == 3) permissions();
              else {
                String source = items.get(which - 4);
                new AlertDialog.Builder(this)
                    .setTitle("移除扫描位置？")
                    .setMessage(Uri.decode(source) + "\n\n只移除扫描授权记录，视频文件不会删除。媒体库仍可能显示这个位置的视频。")
                    .setPositiveButton(
                        "移除",
                        (a, b) -> {
                          repository.removeSource(source);
                          scan(true);
                        })
                    .setNegativeButton("取消", null)
                    .show();
              }
            })
        .setNegativeButton("关闭", null)
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
    wrap.addView(Ui.text(this, "填写完整路径；需要全盘访问权限。", 13, Ui.MUTED));
    wrap.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("添加文件夹路径")
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
                          allFiles();
                          return;
                        }
                        repository.addPath(path);
                        dialog.dismiss();
                        scan(true);
                      } catch (Exception e) {
                        input.setError(safeMessage(e));
                      }
                    }));
    dialog.show();
  }

  private void allFiles() {
    if (Build.VERSION.SDK_INT >= 30) {
      if (Environment.isExternalStorageManager()) {
        scan(true);
        toast("全盘访问已开启，正在扫描共享存储空间");
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("开启全盘视频扫描")
          .setMessage(
              "下一步在系统页面允许“管理所有文件”。映序会扫描手机共享存储和可访问的存储卡，用于发现未被媒体库收录的视频，也支持手动路径。\n\n"
                  + "安卓限制的其他应用私有目录无法读取。")
          .setPositiveButton(
              "前往授权",
              (a, b) -> {
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
    } else permissions();
  }

  private void settings() {
    String[] choices = {"滑动进度手感", "扫描位置与权限", "使用说明", "关于映序"};
    new AlertDialog.Builder(this)
        .setTitle("设置")
        .setItems(
            choices,
            (a, b) -> {
              if (b == 0) {
                float current = getSharedPreferences("player", 0).getFloat("seek_sensitivity", 1f);
                int selected = current < .8f ? 0 : current > 1.2f ? 2 : 1;
                new AlertDialog.Builder(this)
                    .setTitle("滑动进度手感")
                    .setSingleChoiceItems(
                        new String[] {"细腻 · 更容易定位到秒", "均衡 · 推荐", "敏捷 · 更快跳过长片段"},
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
              } else if (b == 1) sources();
              else if (b == 2)
                new AlertDialog.Builder(this)
                    .setTitle("随手就能用")
                    .setMessage(
                        "视频库\n"
                            + "关闭最近任务后，第一次启动应用时自动更新一次；从播放器返回、切回前台都不会扫描。其余时间可下拉刷新或点扫描按钮。更多视频可在文件夹按钮中添加。\n\n"
                            + "播放\n"
                            + "点按显示控制；双击播放/暂停；左右短滑精细调整，长滑逐渐加速，松手后跳转。拖动时显示画面预览；长按临时倍速，松手恢复。左侧上下滑调亮度，右侧上下滑调音量。可在设置中调整进度手感。\n\n"
                            + "连播\n"
                            + "模式按钮切换顺序连播、单集循环、随机连播。方向按钮一键切换竖屏/横屏，始终按原比例适配。\n\n"
                            + "文件管理\n"
                            + "点视频右侧菜单，可查看详情、重命名或删除。安卓可能要求再次确认。\n\n"
                            + "保存时间是媒体库收录时间；文件夹来源没有该记录时使用修改时间。")
                    .setPositiveButton("知道了", null)
                    .show();
              else
                new AlertDialog.Builder(this)
                    .setTitle("映序  1.3.0")
                    .setMessage(
                        "MediaMaster\n"
                            + "你的本地片库，随心播放。\n\n"
                            + "离线运行，无广告，无账号，无上传。\n"
                            + "支持安卓 8.0 及以上，为安卓 14 设计。\n\n"
                            + "播放器使用 AndroidX Media3（Apache 2.0）和 LibVLC（LGPL"
                            + " 2.1）。源码包包含许可与可重建工程。\n\n"
                            + "应用与界面图标使用 Google Material Icons（Apache 2.0）。")
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
                    .setTitle("删除这个视频？")
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
            .setTitle("重命名视频")
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
      toast("正在等待文件操作完成");
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
            else repository.rename(item, name);
            handler.post(
                () -> {
                  mutationInFlight = false;
                  if (destroyed || isFinishing()) return;
                  pendingItem = null;
                  toast(delete ? "视频已删除" : "名称已更新");
                  scan(false);
                });
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
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  mutationInFlight = false;
                  if (destroyed || isFinishing()) return;
                  pendingItem = null;
                  new AlertDialog.Builder(this)
                      .setTitle(delete ? "未能删除视频" : "未能重命名")
                      .setMessage(safeMessage(e) + "\n\n可尝试在“扫描位置”中重新授权文件夹或开启全盘访问。")
                      .setPositiveButton("管理权限", (a, b) -> sources())
                      .setNegativeButton("关闭", null)
                      .show();
                });
          }
        });
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
        scan(true);
      } catch (Exception e) {
        toast("文件夹授权失败：" + safeMessage(e));
      }
    } else if (request == 43 || request == 44) {
      // Returning from an explicitly requested permission flow is a user action, not a resume scan.
      if (mediaPermission()
          || partialPermission()
          || (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())) scan(true);
    } else if (request == 42) {
      pendingWaitConsent = false;
      mutationInFlight = false;
      VideoItem item = pendingItem;
      if (result == RESULT_OK && item != null) {
        if (pendingConsentCompletes) {
          pendingItem = null;
          toast("视频已删除");
          scan(false);
        } else mutate(item, pendingName, pendingDelete);
      } else {
        pendingItem = null;
        toast("已取消文件操作");
      }
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
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
    public VideoHolder onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout line = row();
      line.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
      line.setPadding(d(8), d(12), 0, d(12));
      line.setMinimumHeight(d(104));
      Ui.ripple(line, Ui.BG, 14);
      FrameLayout art = new FrameLayout(MainActivity.this);
      line.addView(art, new LinearLayout.LayoutParams(d(106), d(68)));
      ImageView image = new ImageView(MainActivity.this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      Ui.surface(image, Ui.SURFACE, 10);
      image.setClipToOutline(true);
      image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      art.addView(image, new FrameLayout.LayoutParams(-1, -1));
      TextView duration = Ui.text(MainActivity.this, "", 10, Ui.TEXT);
      duration.setPadding(d(5), d(2), d(5), d(2));
      Ui.surface(duration, 0xcc101416, 4);
      FrameLayout.LayoutParams dura =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
      dura.setMargins(0, 0, d(4), d(4));
      art.addView(duration, dura);
      LinearLayout copy = column();
      LinearLayout.LayoutParams copyP = new LinearLayout.LayoutParams(0, -2, 1);
      copyP.leftMargin = d(16);
      line.addView(copy, copyP);
      TextView title = Ui.text(MainActivity.this, "", 15, Ui.TEXT);
      title.setMaxLines(2);
      title.setEllipsize(TextUtils.TruncateAt.END);
      title.setTypeface(null, Typeface.BOLD);
      copy.addView(title);
      TextView date = Ui.text(MainActivity.this, "", 11, Ui.MUTED);
      LinearLayout.LayoutParams dateP = new LinearLayout.LayoutParams(-1, -2);
      dateP.topMargin = d(7);
      copy.addView(date, dateP);
      TextView size = Ui.text(MainActivity.this, "", 11, Ui.MUTED);
      LinearLayout.LayoutParams sizeP = new LinearLayout.LayoutParams(-1, -2);
      sizeP.topMargin = d(3);
      copy.addView(size, sizeP);
      ImageButton menu = Ui.icon(MainActivity.this, "more", "视频操作");
      menu.setBackgroundColor(android.graphics.Color.TRANSPARENT);
      line.addView(menu, new LinearLayout.LayoutParams(d(48), d(56)));
      return new VideoHolder(line, image, duration, title, date, size, menu);
    }

    public void onBindViewHolder(VideoHolder h, int pos) {
      VideoItem v = visible.get(pos);
      h.title.setText(v.name);
      long date = sort / 2 == 1 ? v.addedMs : v.modifiedMs;
      h.date.setText(
          (sort / 2 == 1 ? "保存 " : "修改 ")
              + new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA).format(new Date(date)));
      h.size.setText(Ui.size(v.size) + "  ·  " + v.extension().toUpperCase(Locale.ROOT));
      h.duration.setText(v.durationMs > 0 ? Ui.duration(v.durationMs) : "视频");
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
    }

    @Override
    public void onViewRecycled(VideoHolder holder) {
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
