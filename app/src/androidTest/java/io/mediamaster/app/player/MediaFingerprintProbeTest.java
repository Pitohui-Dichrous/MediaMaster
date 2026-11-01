package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.media.VideoItem;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in, header/index-only probe. Reports never contain media identifiers or encoded samples. */
@RunWith(AndroidJUnit4.class)
public final class MediaFingerprintProbeTest {
  @Test
  public void inspectExplicitMediaCohort() throws Exception {
    String supplied = InstrumentationRegistry.getArguments().getString("benchUris", "").trim();
    assumeTrue("Supply an explicit media cohort with benchUris", !supplied.isEmpty());
    String[] values = supplied.split(",", -1);
    assertTrue(
        "A cohort must contain between one and 48 items",
        values.length >= 1 && values.length <= 48);
    List<Uri> selected = new ArrayList<>();
    HashSet<Uri> unique = new HashSet<>();
    for (String value : values) {
      Uri uri = Uri.parse(value.trim());
      List<String> path = uri.getPathSegments();
      assertTrue(
          "Only exact local MediaStore video items are allowed",
          "content".equals(uri.getScheme())
              && "media".equals(uri.getAuthority())
              && uri.getQuery() == null
              && uri.getFragment() == null
              && path.size() == 4
              && path.get(0).matches("[A-Za-z0-9_-]+")
              && "video".equals(path.get(1))
              && "media".equals(path.get(2))
              && path.get(3).matches("[1-9][0-9]*"));
      assertTrue("The explicit cohort must not contain duplicates", unique.add(uri));
      selected.add(uri);
    }
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    Method headers =
        NativeDecoderSelector.class.getDeclaredMethod(
            "readHeaders", Context.class, VideoItem.class);
    headers.setAccessible(true);
    JSONArray fingerprints = new JSONArray();
    try {
      for (int index = 0; index < selected.size(); index++) {
        JSONObject row = new JSONObject().put("ordinal", index + 1);
        fingerprints.put(row);
        try {
          Uri uri = selected.get(index);
          long durationMs, bytes;
          int indexedWidth, indexedHeight;
          // No name, path, relative path, title, provider ID, or thumbnail is requested.
          try (Cursor cursor =
              context
                  .getContentResolver()
                  .query(
                      uri,
                      new String[] {"duration", "_size", "width", "height"},
                      null,
                      null,
                      null)) {
            if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst())
              throw new IllegalStateException();
            durationMs = Math.max(0, cursor.getLong(0));
            bytes = Math.max(0, cursor.getLong(1));
            indexedWidth = Math.max(0, cursor.getInt(2));
            indexedHeight = Math.max(0, cursor.getInt(3));
          }
          VideoItem item =
              new VideoItem(
                  "probe-" + (index + 1),
                  "",
                  uri.toString(),
                  "",
                  bytes,
                  durationMs,
                  0,
                  0,
                  indexedWidth,
                  indexedHeight);
          NativeDecoderPolicy.Metadata metadata =
              (NativeDecoderPolicy.Metadata) headers.invoke(null, context, item);
          String codec = metadata.mime;
          row.put(
                  "codec",
                  codec != null && codec.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                      ? codec
                      : JSONObject.NULL)
              .put("profile", metadata.profile)
              .put("width", metadata.width)
              .put("height", metadata.height)
              .put(
                  "fps",
                  Double.isFinite(metadata.frameRate) && metadata.frameRate > 0
                      ? metadata.frameRate
                      : JSONObject.NULL)
              .put("hdr", metadata.hdr)
              .put("syncGapMs", metadata.syncGapMs)
              .put("durationMs", durationMs)
              // Container average, including audio/overhead; never label this as video peak
              // bitrate.
              .put(
                  "containerAverageBitrate",
                  durationMs > 0 && bytes > 0
                      ? Math.round(bytes * 8000d / durationMs)
                      : JSONObject.NULL);
          if (codec == null) row.put("probeStatus", "headers-unavailable");
        } catch (Exception error) {
          Throwable cause =
              error instanceof InvocationTargetException
                      && ((InvocationTargetException) error).getCause() != null
                  ? ((InvocationTargetException) error).getCause()
                  : error;
          // Framework exception messages may contain private URIs; report only the class.
          row.put("errorClass", cause.getClass().getSimpleName());
        }
      }
    } finally {
      Bundle status = new Bundle();
      status.putString("fingerprints", fingerprints.toString());
      instrumentation.sendStatus(2, status);
    }
  }
}
