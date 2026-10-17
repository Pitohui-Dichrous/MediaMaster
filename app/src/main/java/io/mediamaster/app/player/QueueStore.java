package io.mediamaster.app.player;

import android.content.Context;
import io.mediamaster.app.media.VideoItem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pass a tiny token through Binder, even when a library contains thousands of videos. */
final class QueueStore {
  private static final ConcurrentHashMap<String, ArrayList<VideoItem>> MEMORY =
      new ConcurrentHashMap<>();
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();

  private QueueStore() {}

  static String put(Context context, ArrayList<VideoItem> videos) {
    String token = UUID.randomUUID().toString();
    ArrayList<VideoItem> copy = new ArrayList<>(videos);
    MEMORY.put(token, copy);
    File directory = new File(context.getCacheDir(), "player-queues");
    IO.execute(
        () -> {
          try {
            directory.mkdirs();
            JSONArray array = new JSONArray();
            for (VideoItem item : copy) {
              JSONObject o = new JSONObject();
              o.put("id", item.id);
              o.put("name", item.name);
              o.put("uri", item.uri);
              o.put("path", item.path);
              o.put("size", item.size);
              o.put("duration", item.durationMs);
              o.put("modified", item.modifiedMs);
              o.put("added", item.addedMs);
              o.put("width", item.width);
              o.put("height", item.height);
              array.put(o);
            }
            File target = new File(directory, token + ".json");
            File temporary = new File(directory, token + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
              out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
            temporary.renameTo(target);
            File[] files = directory.listFiles();
            if (files != null)
              for (File file : files) {
                if (!file.getName().startsWith(token)
                    && System.currentTimeMillis() - file.lastModified() > 7 * 24 * 60 * 60 * 1000L)
                  file.delete();
              }
          } catch (Exception ignored) {
            /* The in-process handoff remains usable if cache is full. */
          }
        });
    return token;
  }

  static ArrayList<VideoItem> get(Context context, String token) {
    if (token == null || !token.matches("[a-f0-9-]{36}")) return new ArrayList<>();
    ArrayList<VideoItem> cached = MEMORY.get(token);
    if (cached != null) return new ArrayList<>(cached);
    ArrayList<VideoItem> items = new ArrayList<>();
    try (FileInputStream in =
        new FileInputStream(
            new File(new File(context.getCacheDir(), "player-queues"), token + ".json"))) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
      JSONArray array = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) {
        JSONObject o = array.getJSONObject(i);
        items.add(
            new VideoItem(
                o.optString("id"),
                o.optString("name"),
                o.getString("uri"),
                o.optString("path"),
                o.optLong("size"),
                o.optLong("duration"),
                o.optLong("modified"),
                o.optLong("added"),
                o.optInt("width"),
                o.optInt("height")));
      }
    } catch (Exception ignored) {
    }
    return items;
  }

  static void forget(String token) {
    if (token != null) MEMORY.remove(token);
  }
}
