package io.mediamaster.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

/** Refreshes persisted recent-task branding after an app name or icon update. */
public final class TaskIdentity {
  private static Bitmap legacyIcon;

  private TaskIdentity() {}

  @SuppressWarnings("deprecation")
  public static void update(Activity activity) {
    String name = activity.getString(R.string.app_name);
    activity.setTitle(name);
    ActivityManager.TaskDescription description;
    if (Build.VERSION.SDK_INT >= 33) {
      description =
          new ActivityManager.TaskDescription.Builder()
              .setLabel(name)
              .setIcon(R.mipmap.ic_launcher)
              .build();
    } else if (Build.VERSION.SDK_INT >= 28) {
      description = new ActivityManager.TaskDescription(name, R.mipmap.ic_launcher);
    } else {
      // Android 8 accepts only a bitmap. Render the adaptive drawable, including its mask,
      // rather than trying to decode the XML resource as a bitmap or flattening the source PNG.
      if (legacyIcon == null) {
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        int size = manager.getLauncherLargeIconSize();
        legacyIcon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable icon = activity.getDrawable(R.mipmap.ic_launcher);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(legacyIcon));
      }
      description = new ActivityManager.TaskDescription(name, legacyIcon);
    }
    activity.setTaskDescription(description);
  }
}
