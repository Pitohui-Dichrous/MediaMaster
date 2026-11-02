package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.EditText;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.mediamaster.app.MainActivity;
import io.mediamaster.app.media.VideoItem;
import io.mediamaster.app.media.VideoRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Homepage entry/consent wiring; player launches are intercepted and never read real media. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class MainSearchFeedbackTest {
  @Rule
  public ActivityTestRule<MainActivity> rule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private Instrumentation instrumentation;
  private Context context, repositoryContext;
  private MainActivity activity;
  private PlaybackStatsStore store, originalStore;
  private Field singleton;
  private String databaseName, preferenceName;
  private File repositoryFiles;
  private VideoRepository repository;
  private boolean installed;
  private Boolean randomPortrait;
  private Instrumentation.ActivityMonitor playerMonitor;
  private final ConcurrentLinkedQueue<Intent> launches = new ConcurrentLinkedQueue<>();
  private final List<String> queueTokens = new ArrayList<>();
  private final List<VideoItem> videos = new ArrayList<>();

  @Before
  public void isolateStatisticsAndInterceptPlayback() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    String unique = UUID.randomUUID().toString();
    databaseName = "main-search-feedback-" + unique + ".db";
    preferenceName = "main-search-repository-" + unique;
    repositoryFiles = new File(context.getCacheDir(), "main-search-repository-" + unique);
    assertTrue(repositoryFiles.mkdir());
    repositoryContext =
        new ContextWrapper(context) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getFilesDir() {
            return repositoryFiles;
          }

          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return context.getSharedPreferences(preferenceName, mode);
          }
        };
    repository = new VideoRepository(repositoryContext);
    store =
        new PlaybackStatsStore(
            context,
            databaseName,
            100,
            Executors.newSingleThreadExecutor(),
            () -> 1_700_000_000_000L);
    store.awaitIdle();
    SharedPreferences preferences = context.getSharedPreferences("player", 0);
    randomPortrait =
        preferences.contains("random_start_portrait")
            ? preferences.getBoolean("random_start_portrait", true)
            : null;
    playerMonitor =
        new Instrumentation.ActivityMonitor() {
          @Override
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (intent.getComponent() != null
                && PlayerActivity.class.getName().equals(intent.getComponent().getClassName())) {
              launches.add(new Intent(intent));
              return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
            return null;
          }
        };
    instrumentation.addMonitor(playerMonitor);
    onMain(
        () -> {
          singleton = PlaybackStatsStore.class.getDeclaredField("instance");
          singleton.setAccessible(true);
          originalStore = (PlaybackStatsStore) singleton.get(null);
          singleton.set(null, store);
          installed = true;
          return null;
        });
    for (String name : new String[] {"series-01.mp4", "series-02.mp4", "other.mp4"}) {
      File synthetic = new File(repositoryFiles, name);
      videos.add(
          new VideoItem(
              "main-search-" + unique + "-" + name,
              name,
              Uri.fromFile(synthetic).toString(),
              synthetic.getAbsolutePath(),
              1000,
              10_000,
              0,
              0,
              640,
              360));
    }
    activity = rule.launchActivity(new Intent(context, MainActivity.class));
    isolateCatalog();
  }

  @After
  public void restoreOnlyOwnedState() throws Exception {
    try {
      if (activity != null) {
        onMain(
            () -> {
              activity.finish();
              return null;
            });
        await(() -> onMain(() -> activity.isDestroyed()));
        rule.finishActivity();
      }
    } finally {
      if (playerMonitor != null) instrumentation.removeMonitor(playerMonitor);
      if (installed) {
        onMain(
            () -> {
              assertSame(store, singleton.get(null));
              singleton.set(null, originalStore);
              return null;
            });
      }
      if (store != null) store.close();
      if (context != null && databaseName != null) context.deleteDatabase(databaseName);
      if (context != null && preferenceName != null)
        context.deleteSharedPreferences(preferenceName);
      if (context != null) {
        SharedPreferences.Editor preferences = context.getSharedPreferences("player", 0).edit();
        if (randomPortrait == null) preferences.remove("random_start_portrait");
        else preferences.putBoolean("random_start_portrait", randomPortrait);
        assertTrue(preferences.commit());
      }
      if (!queueTokens.isEmpty()) {
        Field queueIo = QueueStore.class.getDeclaredField("IO");
        queueIo.setAccessible(true);
        ((ExecutorService) queueIo.get(null)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        for (String token : queueTokens) {
          QueueStore.forget(token);
          new File(new File(context.getCacheDir(), "player-queues"), token + ".json").delete();
          new File(new File(context.getCacheDir(), "player-queues"), token + ".tmp").delete();
        }
      }
      if (repositoryFiles != null) {
        assertEquals(
            context.getCacheDir().getCanonicalFile(),
            repositoryFiles.getCanonicalFile().getParentFile());
        File[] ownedFiles = repositoryFiles.listFiles();
        if (ownedFiles != null)
          for (File owned : ownedFiles) {
            assertEquals(
                repositoryFiles.getCanonicalFile(), owned.getCanonicalFile().getParentFile());
            assertTrue("Only test-owned regular files are cleaned", owned.isFile());
            assertTrue(owned.delete());
          }
        assertTrue(repositoryFiles.delete());
      }
    }
  }

  @Test
  public void repeatedSearchResultEntriesReuseTheRoundAndDoNotSelectManualLike() throws Exception {
    search("series");
    Intent first = open(false);
    assertSearchQueue(first, 2);
    String round = first.getStringExtra("search_round");
    PlaybackExtraFeedbackSession scope = SearchFeedbackRounds.resolve(store, round);
    PlaybackVoteSession firstWindow = new PlaybackVoteSession(store, scope);
    assertTrue(firstWindow.applyAutomaticVote(videos.get(0), 1));
    assertEquals(
        "Hidden inferred feedback does not select the button", 0, firstWindow.vote(videos.get(0)));
    Intent reopened = open(false);
    assertEquals(round, reopened.getStringExtra("search_round"));
    PlaybackVoteSession secondWindow =
        new PlaybackVoteSession(store, SearchFeedbackRounds.resolve(store, round));
    assertFalse(
        "Reopening a result does not grant another extra like",
        secondWindow.applyAutomaticVote(videos.get(0), 1));
    assertTrue(
        "A fresh window still permits its separate manual vote",
        secondWindow.setVote(videos.get(0), 1));
  }

  @Test
  public void normalizedQueryKeepsTheRoundButEditingOrClearingStartsAnother() throws Exception {
    search("series");
    String first = open(false).getStringExtra("search_round");
    search("  SERIES  ");
    assertEquals(first, open(false).getStringExtra("search_round"));
    search("series-02");
    String changed = open(false).getStringExtra("search_round");
    assertNotEquals(first, changed);
    search("");
    Intent ordinary = open(false);
    assertFalse(ordinary.getBooleanExtra("from_search", true));
    assertNull(ordinary.getStringExtra("search_round"));
    search("series-02");
    assertNotEquals(changed, open(false).getStringExtra("search_round"));
  }

  @Test
  public void sortingFilteringAndMediaIndexRefreshDoNotResetTheSearchRound() throws Exception {
    search("series");
    String round = open(false).getStringExtra("search_round");
    onMain(
        () -> {
          write(activity, "sort", 5);
          invoke(activity, "filter", new Class<?>[0]);
          return null;
        });
    assertEquals(round, open(false).getStringExtra("search_round"));
    onMain(
        () -> {
          invoke(activity, "scan", new Class<?>[] {boolean.class}, false);
          return null;
        });
    await(() -> onMain(() -> !(boolean) read(activity, "scanning")));
    onMain(
        () -> {
          assertEquals(round, read(activity, "searchRound"));
          replaceVisibleCatalog();
          return null;
        });
    assertEquals(round, open(false).getStringExtra("search_round"));
  }

  @Test
  public void recreationRetainsTheQueryRoundAndItsConsumedExtraLike() throws Exception {
    search("series");
    String round = open(false).getStringExtra("search_round");
    PlaybackExtraFeedbackSession scope = SearchFeedbackRounds.resolve(store, round);
    assertTrue(new PlaybackVoteSession(store, scope).applyAutomaticVote(videos.get(0), 1));
    Bundle state = new Bundle();
    onMain(
        () -> {
          invoke(activity, "onSaveInstanceState", new Class<?>[] {Bundle.class}, state);
          return null;
        });
    assertEquals(round, state.getString("library_search_round"));
    assertEquals("series", state.getString("library_query"));
    MainActivity original = activity;
    AtomicReference<MainActivity> restored = new AtomicReference<>();
    ActivityLifecycleCallback callback =
        (candidate, stage) -> {
          if (candidate instanceof MainActivity && candidate != original && stage == Stage.RESUMED)
            restored.set((MainActivity) candidate);
        };
    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
    try {
      onMain(
          () -> {
            original.recreate();
            return null;
          });
      await(() -> restored.get() != null);
      activity = restored.get();
      isolateCatalog();
    } finally {
      ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
    }
    Intent reopened = open(false);
    assertEquals(round, reopened.getStringExtra("search_round"));
    assertSame(scope, SearchFeedbackRounds.resolve(store, round));
    assertFalse(new PlaybackVoteSession(store, scope).applyAutomaticVote(videos.get(0), 1));
  }

  @Test
  public void filteredRandomEntryInheritsSearchScopeAndThePortraitPreference() throws Exception {
    search("series");
    for (boolean portrait : new boolean[] {true, false}) {
      assertTrue(
          context
              .getSharedPreferences("player", 0)
              .edit()
              .putBoolean("random_start_portrait", portrait)
              .commit());
      Intent launched = open(true);
      assertSearchQueue(launched, 2);
      assertTrue(launched.getBooleanExtra("shuffle", false));
      assertEquals(portrait, launched.getBooleanExtra("start_portrait", !portrait));
    }
    search("");
    Intent ordinary = open(true);
    assertFalse(ordinary.getBooleanExtra("from_search", true));
    assertNull(ordinary.getStringExtra("search_round"));
    assertEquals(3, QueueStore.get(context, ordinary.getStringExtra("queue")).size());
    assertFalse(ordinary.getBooleanExtra("start_portrait", true));
  }

  @Test
  public void completedLocalDeleteRemovesOnlyThatVideosPersistedStatistics() throws Exception {
    VideoItem removed = createOwnedVideo(0), retained = videos.get(1);
    seedStatistics(removed, retained);
    onMain(
        () -> {
          write(activity, "active", false); // Prevent an observer refresh from changing test data.
          invoke(
              activity,
              "mutate",
              new Class<?>[] {VideoItem.class, String.class, boolean.class},
              removed,
              null,
              true);
          return null;
        });
    await(() -> onMain(() -> !(boolean) read(activity, "mutationInFlight")));
    assertFalse(new File(Uri.parse(removed.uri).getPath()).exists());
    assertRemovedOnly(removed, retained);
  }

  @Test
  public void completedSystemConsentDeletesStatsWhileCancelledConsentKeepsThem() throws Exception {
    VideoItem removed = createOwnedVideo(0), retained = videos.get(1);
    seedStatistics(removed, retained);
    onMain(
        () -> {
          write(activity, "active", false);
          write(activity, "pendingItem", removed);
          write(activity, "pendingDelete", true);
          write(activity, "pendingWaitConsent", true);
          write(activity, "pendingConsentCompletes", true);
          invoke(
              activity,
              "onActivityResult",
              new Class<?>[] {int.class, int.class, Intent.class},
              42,
              Activity.RESULT_CANCELED,
              null);
          return null;
        });
    store.awaitIdle();
    assertEquals(1, store.playCount(removed));
    assertTrue(new File(Uri.parse(removed.uri).getPath()).exists());
    assertTrue(new File(Uri.parse(removed.uri).getPath()).delete());
    onMain(
        () -> {
          write(activity, "pendingItem", removed);
          write(activity, "pendingDelete", true);
          write(activity, "pendingWaitConsent", true);
          write(activity, "pendingConsentCompletes", true);
          invoke(
              activity,
              "onActivityResult",
              new Class<?>[] {int.class, int.class, Intent.class},
              42,
              Activity.RESULT_OK,
              null);
          return null;
        });
    await(() -> onMain(() -> !(boolean) read(activity, "mutationInFlight")));
    assertRemovedOnly(removed, retained);
  }

  private void seedStatistics(VideoItem first, VideoItem second) throws Exception {
    repository.saveCache(Arrays.asList(first, second));
    store.recordPlay(first);
    store.recordPlay(second);
    store.awaitIdle();
    PlaybackVoteSession votes = new PlaybackVoteSession(store);
    assertTrue(votes.setVote(first, 1));
    assertTrue(votes.setVote(second, -1));
    store.awaitIdle();
  }

  private VideoItem createOwnedVideo(int index) throws Exception {
    VideoItem item = videos.get(index);
    File target = new File(Uri.parse(item.uri).getPath());
    assertEquals(repositoryFiles.getCanonicalFile(), target.getCanonicalFile().getParentFile());
    assertFalse(target.exists());
    try (FileOutputStream output = new FileOutputStream(target)) {
      output.write(new byte[] {0, 0, 0, 0});
    }
    return item;
  }

  private void assertRemovedOnly(VideoItem removed, VideoItem retained) throws Exception {
    store.awaitIdle();
    assertEquals(0, store.playCount(removed));
    assertEquals(1, store.playCount(retained));
    try (SQLiteDatabase db =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).getPath(),
                null,
                SQLiteDatabase.OPEN_READONLY);
        Cursor cursor = db.rawQuery("SELECT media_key FROM plays", null)) {
      assertEquals("No empty tombstone row replaces deleted weights", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      assertEquals(PlaybackStatsStore.key(retained), cursor.getString(0));
    }
  }

  private void assertSearchQueue(Intent intent, int size) {
    assertTrue(intent.getBooleanExtra("from_search", false));
    assertNotNull(intent.getStringExtra("search_round"));
    List<VideoItem> queue = QueueStore.get(context, intent.getStringExtra("queue"));
    assertEquals(size, queue.size());
    for (VideoItem item : queue) assertTrue(item.name.startsWith("series-"));
  }

  private void isolateCatalog() throws Exception {
    await(() -> onMain(() -> !(boolean) read(activity, "scanning")));
    ((ExecutorService) onMain(() -> read(activity, "worker")))
        .submit(() -> {})
        .get(15, TimeUnit.SECONDS);
    instrumentation.waitForIdleSync();
    onMain(
        () -> {
          invoke(read(activity, "mediaObserver"), "stop", new Class<?>[0]);
          ((Handler) read(activity, "handler"))
              .removeCallbacks((Runnable) read(activity, "reconcileMediaIndex"));
          invoke(read(activity, "thumbnails"), "setPaused", new Class<?>[] {boolean.class}, true);
          ((RecyclerView) read(activity, "list")).setItemAnimator(null);
          write(activity, "repository", repository);
          replaceVisibleCatalog();
          return null;
        });
  }

  @SuppressWarnings("unchecked")
  private void replaceVisibleCatalog() throws Exception {
    List<VideoItem> all = (List<VideoItem>) read(activity, "all");
    all.clear();
    all.addAll(videos);
    invoke(activity, "filter", new Class<?>[0]);
  }

  private void search(String query) throws Exception {
    onMain(
        () -> {
          ((EditText) read(activity, "search")).setText(query);
          return null;
        });
  }

  private Intent open(boolean random) throws Exception {
    assertTrue(launches.isEmpty());
    // A blocked launch can still produce framework resume/index callbacks. Our nonexistent
    // fixture paths must be restored after that read-only refresh, without replacing the query
    // or its round. Real media is never handed to the intercepted player intent.
    isolateCatalog();
    onMain(
        () -> {
          @SuppressWarnings("unchecked")
          List<VideoItem> visible = (List<VideoItem>) read(activity, "visible");
          assertFalse("Synthetic search matches must still be present", visible.isEmpty());
          for (VideoItem item : visible) assertTrue(videos.contains(item));
          assertTrue(
              "Random selection requires the resumed homepage", (boolean) read(activity, "active"));
          if (random) invoke(activity, "playRandom", new Class<?>[0]);
          else invoke(activity, "open", new Class<?>[] {int.class, boolean.class}, 0, false);
          return null;
        });
    await(() -> !launches.isEmpty());
    Intent launched = launches.remove();
    String token = launched.getStringExtra("queue");
    assertNotNull(token);
    queueTokens.add(token);
    return launched;
  }

  private static Object read(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void write(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object invoke(Object target, String name, Class<?>[] parameters, Object... values)
      throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, parameters);
    method.setAccessible(true);
    return method.invoke(target, values);
  }

  private <T> T onMain(Callable<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            result.set(task.call());
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() instanceof Error) throw (Error) failure.get();
    if (failure.get() instanceof Exception) throw (Exception) failure.get();
    return result.get();
  }

  private void await(Callable<Boolean> condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 15_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      SystemClock.sleep(30);
    }
    fail("Homepage feedback condition did not become true");
  }
}
