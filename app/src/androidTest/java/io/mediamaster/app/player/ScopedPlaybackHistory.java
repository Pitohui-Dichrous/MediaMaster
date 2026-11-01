package io.mediamaster.app.player;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Test-only history sandbox. The user's history preference file is never opened or rewritten. */
final class ScopedPlaybackHistory {
  private static final String HISTORY_NAME = "player_engine_history";
  private static final String TEST_HISTORY_NAME = "test_seek_engine_history";
  private static ScopedPlaybackHistory activeScope;

  private final Field instanceField;
  private final PlaybackEngineHistory original, isolated;
  private final SharedPreferences testPreferences;
  private boolean closed;

  /** Call on main before launching any player activity; preserve an uninitialized singleton too. */
  static ScopedPlaybackHistory open(Context context) throws Exception {
    requireMainThread();
    if (activeScope != null || hasPlayerActivity())
      throw new IllegalStateException("History isolation requires no active player or other scope");
    Context app = context.getApplicationContext();
    SharedPreferences testPreferences =
        app.getSharedPreferences(TEST_HISTORY_NAME, Context.MODE_PRIVATE);
    if (!testPreferences.edit().clear().commit())
      throw new IllegalStateException("Could not clear the test-only history");
    Context sandbox =
        new ContextWrapper(app) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return HISTORY_NAME.equals(name)
                ? testPreferences
                : super.getSharedPreferences(name, mode);
          }
        };
    Field instanceField = PlaybackEngineHistory.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    // Do not call get(): it could initialize or migrate the real preference file.
    PlaybackEngineHistory original = (PlaybackEngineHistory) instanceField.get(null);
    Constructor<PlaybackEngineHistory> constructor =
        PlaybackEngineHistory.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    PlaybackEngineHistory isolated;
    try {
      isolated = constructor.newInstance(sandbox);
    } catch (Exception error) {
      testPreferences.edit().clear().commit();
      throw error;
    }
    ScopedPlaybackHistory scope =
        new ScopedPlaybackHistory(instanceField, original, isolated, testPreferences);
    instanceField.set(null, isolated);
    activeScope = scope;
    return scope;
  }

  private ScopedPlaybackHistory(
      Field instanceField,
      PlaybackEngineHistory original,
      PlaybackEngineHistory isolated,
      SharedPreferences testPreferences) {
    this.instanceField = instanceField;
    this.original = original;
    this.isolated = isolated;
    this.testPreferences = testPreferences;
  }

  /** The caller requests activity.finish first. A wedged activity keeps its isolated history. */
  void closeAfterActivityDestroyed(Activity ownedActivity) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Waiting for activity destruction must not block main");
    Handler main = new Handler(Looper.getMainLooper());
    long deadline = SystemClock.uptimeMillis() + 3000;
    while (SystemClock.uptimeMillis() < deadline) {
      FutureTask<Boolean> task =
          new FutureTask<>(
              () -> {
                if (closed) return true;
                if ((ownedActivity != null && !ownedActivity.isDestroyed()) || hasPlayerActivity())
                  return false;
                closeOnMain();
                return true;
              });
      main.post(task);
      try {
        if (task.get(1000, TimeUnit.MILLISECONDS)) return;
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new RuntimeException(cause);
      } catch (Exception error) {
        task.cancel(false);
        throw error;
      }
      SystemClock.sleep(20);
    }
    throw new IllegalStateException("Player activity did not close; history isolation retained");
  }

  private void closeOnMain() throws Exception {
    requireMainThread();
    if (closed) return;
    if (hasPlayerActivity())
      throw new IllegalStateException("Close the player before restoring its history");
    if (activeScope != this || instanceField.get(null) != isolated)
      throw new IllegalStateException("History scope ownership changed unexpectedly");
    boolean cleared;
    try {
      // Teardown flushes target this sandbox. commit orders the clear after preceding apply calls.
      cleared = testPreferences.edit().clear().commit();
    } finally {
      instanceField.set(null, original);
      activeScope = null;
      closed = true;
    }
    if (!cleared) throw new IllegalStateException("Could not clear the test-only history");
  }

  private static boolean hasPlayerActivity() {
    requireMainThread();
    for (Stage stage : Stage.values()) {
      if (stage == Stage.DESTROYED) continue;
      for (Activity activity :
          ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))
        if (activity instanceof PlayerActivity && !activity.isDestroyed()) return true;
    }
    return false;
  }

  private static void requireMainThread() {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("History singleton changes must run on main");
  }
}
