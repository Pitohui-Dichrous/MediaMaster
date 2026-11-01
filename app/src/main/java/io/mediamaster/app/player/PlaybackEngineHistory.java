package io.mediamaster.app.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import io.mediamaster.app.media.VideoItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Main-thread, bounded, local-only history. Persist only at page teardown, never per video frame.
 */
final class PlaybackEngineHistory {
  private static final int MAX_ENTRIES = 64;
  private static final String VERSION =
      "native-preferences-v3/media3-1.8.0/libvlc-3.7.7/sdk-" + Build.VERSION.SDK_INT;
  private static PlaybackEngineHistory instance;
  private final SharedPreferences preferences;
  private final LinkedHashMap<String, SeekEnginePolicy> entries =
      new LinkedHashMap<>(16, .75f, true);
  private boolean dirty;

  static PlaybackEngineHistory get(Context context) {
    if (instance == null) instance = new PlaybackEngineHistory(context.getApplicationContext());
    return instance;
  }

  private PlaybackEngineHistory(Context context) {
    preferences = context.getSharedPreferences("player_engine_history", Context.MODE_PRIVATE);
    if (!VERSION.equals(preferences.getString("version", ""))) {
      // Replace obsolete identity-bearing policy metadata immediately, without touching playback
      // preferences. A failed disk write remains dirty so ordinary teardown will retry it.
      dirty =
          !preferences
              .edit()
              .clear()
              .putString("version", VERSION)
              .putString("entries", "[]")
              .commit();
      return;
    }
    try {
      JSONArray rows = new JSONArray(preferences.getString("entries", "[]"));
      for (int i = Math.max(0, rows.length() - MAX_ENTRIES); i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        SeekEnginePolicy p = new SeekEnginePolicy();
        p.vlcFailureUntilMs = Math.max(0, row.optLong("vlcFailureUntil"));
        p.softwareAvoidUntilMs = Math.max(0, row.optLong("softwareAvoidUntil"));
        entries.put(row.getString("key"), p);
      }
    } catch (Exception invalid) {
      entries.clear();
    }
  }

  SeekEnginePolicy forItem(VideoItem item) {
    String key = key(item);
    SeekEnginePolicy policy = entries.get(key);
    if (policy == null) {
      policy = new SeekEnginePolicy();
      entries.put(key, policy);
      while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    }
    return policy;
  }

  void changed() {
    dirty = true;
  }

  void flush() {
    if (!dirty) return;
    try {
      JSONArray rows = new JSONArray();
      for (Map.Entry<String, SeekEnginePolicy> entry : entries.entrySet()) {
        SeekEnginePolicy p = entry.getValue();
        rows.put(
            new JSONObject()
                .put("key", entry.getKey())
                .put("vlcFailureUntil", p.vlcFailureUntilMs)
                .put("softwareAvoidUntil", p.softwareAvoidUntilMs));
      }
      preferences
          .edit()
          .putString("version", VERSION)
          .putString("entries", rows.toString())
          .apply();
      dirty = false;
    } catch (Exception ignored) {
      // A failed preference write must never interrupt playback.
    }
  }

  private static String key(VideoItem item) {
    String value =
        item.id + '|' + item.uri + '|' + item.path + '|' + item.size + '|' + item.modifiedMs;
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder key = new StringBuilder(64);
      for (byte b : bytes) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return key.toString();
    } catch (Exception unavailable) {
      return Integer.toHexString(value.hashCode());
    }
  }
}
