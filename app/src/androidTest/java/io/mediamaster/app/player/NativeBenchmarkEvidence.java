package io.mediamaster.app.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.SurfaceView;
import android.view.View;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Runtime configuration evidence only; none of these fields establishes decoded-frame timing. */
@androidx.media3.common.util.UnstableApi
final class NativeBenchmarkEvidence {
  static JSONObject capture(Instrumentation instrumentation, String packageName) throws Exception {
    JSONObject evidence =
        new JSONObject()
            .put("nativeSoftware", JSONObject.NULL)
            .put("surfaceType", JSONObject.NULL)
            .put("configuredFfmpegThreads", JSONObject.NULL)
            .put(
                "ffmpegThreadEvidence",
                "Not exposed by the runtime Java player API; inspect the tested build source")
            .put("source", "unavailable-external-process");
    if (!packageName.equals(instrumentation.getTargetContext().getPackageName())) return evidence;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            evidence.put("source", "resumed-currentPage-fields-and-live-view");
            for (Activity activity :
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
              if (!activity.getClass().getName().equals("io.mediamaster.app.player.PlayerActivity"))
                continue;
              Object page = read(activity, "currentPage");
              if (page == null) continue;
              Object player = read(page, "vlc");
              if (player == null) {
                evidence.put("source", "current-page-is-not-LibVLC");
                return;
              }
              evidence
                  .put("nativeSoftware", readOptional(page, "nativeSoftware"))
                  .put("nativeSurfacePlayback", readOptional(page, "nativeSurfacePlayback"))
                  .put("nativePlayerIdentity", System.identityHashCode(player));
              View root = (View) read(page, "vlcView");
              View surface = NativeFrameProbe.findVideoSurface(root);
              if (surface != null) {
                evidence
                    .put("surfaceType", surface.getClass().getSimpleName())
                    .put("surfaceIdentity", System.identityHashCode(surface));
                if (surface instanceof SurfaceView)
                  evidence.put(
                      "surfaceHolderIdentity",
                      System.identityHashCode(((SurfaceView) surface).getHolder()));
              }
              return;
            }
            evidence.put("source", "no-resumed-own-player");
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) evidence.put("metadataError", failure.get().toString());
    return evidence;
  }

  private static Object readOptional(Object instance, String name) throws Exception {
    try {
      return read(instance, name);
    } catch (NoSuchFieldException absentInOldBuild) {
      return JSONObject.NULL;
    }
  }

  private static Object read(Object instance, String name) throws Exception {
    Field field = instance.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  private NativeBenchmarkEvidence() {}
}
