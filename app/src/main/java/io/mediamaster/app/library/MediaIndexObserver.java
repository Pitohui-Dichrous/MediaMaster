package io.mediamaster.app.library;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.MediaStore;
import java.util.LinkedHashSet;
import java.util.Set;

/** Watches published media changes while the library is in the foreground. Never polls files. */
public final class MediaIndexObserver {
  private final Context context;
  private final ContentResolver resolver;
  private final ContentObserver observer;
  private boolean registered;

  public MediaIndexObserver(Context context, Handler handler, Runnable onChanged) {
    this.context = context.getApplicationContext();
    resolver = this.context.getContentResolver();
    observer =
        new ContentObserver(handler) {
          @Override
          public void onChange(boolean selfChange) {
            onChanged.run();
          }

          @Override
          public void onChange(boolean selfChange, Uri uri) {
            onChanged.run();
          }
        };
  }

  public void start() {
    if (registered) return;
    Set<String> volumes = new LinkedHashSet<>();
    volumes.add("external");
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        volumes.addAll(MediaStore.getExternalVolumeNames(context));
      } catch (SecurityException | IllegalArgumentException ignored) {
        // The aggregate external collection remains a valid fallback.
      }
    }
    for (String volume : volumes) {
      register(MediaStore.Files.getContentUri(volume));
      register(
          Build.VERSION.SDK_INT >= 29
              ? MediaStore.Video.Media.getContentUri(volume)
              : MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
    }
  }

  private void register(Uri collection) {
    try {
      resolver.registerContentObserver(collection, true, observer);
      registered = true;
    } catch (SecurityException | IllegalArgumentException ignored) {
      // Limited permissions or a provider without this volume still allow resume reconciliation.
    }
  }

  public void stop() {
    if (!registered) return;
    resolver.unregisterContentObserver(observer);
    registered = false;
  }
}
