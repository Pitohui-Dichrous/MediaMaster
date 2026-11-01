package io.mediamaster.app.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import io.mediamaster.app.Ui;

/** The explicit, confirmed reset is shared by the library and playback settings. */
public final class RandomWeightsDialog {
  private RandomWeightsDialog() {}

  public static void show(Activity activity, PlaybackStatsStore store, Runnable onReset) {
    AlertDialog dialog =
        new AlertDialog.Builder(activity, Ui.dialogTheme(activity))
            .setTitle("重置权重")
            .setMessage("清除由于观看和点赞/点踩产生的随机播放排序权重")
            .setNegativeButton("取消", null)
            .setPositiveButton("重置", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                      dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                      dialog.setCancelable(false);
                      dialog.setMessage("重置中…");
                      store.resetWeights(
                          success -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            if (success) {
                              onReset.run();
                              dialog.dismiss();
                              Toast.makeText(activity, "已重置", Toast.LENGTH_SHORT).show();
                            } else {
                              dialog.setMessage("无法保存重置结果，请检查可用存储后重试。");
                              dialog.setCancelable(true);
                              dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                              dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            }
                          });
                    }));
    dialog.show();
  }
}
