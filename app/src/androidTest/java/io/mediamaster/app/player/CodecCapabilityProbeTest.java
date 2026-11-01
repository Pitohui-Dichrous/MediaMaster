package io.mediamaster.app.player;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit fixture header/capability query; no codec instance, playback, or identity output. */
@RunWith(AndroidJUnit4.class)
public final class CodecCapabilityProbeTest {
  @Test
  public void reportLowLatencyCapabilitiesForExplicitFixture() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    String name = args.getString("benchFile");
    String relative = args.getString("benchRelativePath");
    Assume.assumeTrue("Explicit capability fixture required", name != null && relative != null);
    Assume.assumeTrue("Low-latency capability API required", Build.VERSION.SDK_INT >= 30);
    assertTrue("Invalid fixture name", name.matches("mm_[A-Za-z0-9_-]+\\.mp4"));
    assertTrue("Invalid fixture folder", relative.matches("Movies/MediaMaster[A-Za-z0-9_-]*/"));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    Uri uri;
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME
                    + " = ? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH
                    + " = ?",
                new String[] {name, relative},
                null)) {
      assertNotNull("Fixture query unavailable", cursor);
      assertTrue("Exactly one fixture required", cursor.getCount() == 1 && cursor.moveToFirst());
      uri =
          ContentUris.withAppendedId(
              MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
    }
    MediaExtractor extractor = new MediaExtractor();
    MediaFormat video = null;
    try {
      extractor.setDataSource(context, uri, null);
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("video/")) {
          assertTrue("Exactly one video track required", video == null);
          video = format;
        }
      }
    } catch (IOException | RuntimeException unavailable) {
      throw new AssertionError("Fixture headers unavailable");
    } finally {
      extractor.release();
    }
    assertNotNull("Video track required", video);
    String mime = video.getString(MediaFormat.KEY_MIME);
    JSONArray rows = new JSONArray();
    int ordinal = 0;
    for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (codec.isEncoder() || codec.isAlias()) continue;
      boolean matches = false;
      for (String supported : codec.getSupportedTypes()) matches |= supported.equals(mime);
      if (!matches) continue;
      JSONObject row =
          new JSONObject()
              .put("ordinal", ++ordinal)
              .put("hardware", codec.isHardwareAccelerated())
              .put("softwareOnly", codec.isSoftwareOnly())
              .put("profileKnown", video.containsKey(MediaFormat.KEY_PROFILE))
              .put("levelKnown", video.containsKey(MediaFormat.KEY_LEVEL))
              .put("frameRateKnown", video.containsKey(MediaFormat.KEY_FRAME_RATE));
      try {
        MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(mime);
        boolean formatSupported = caps.isFormatSupported(video);
        row.put("formatSupported", formatSupported)
            .put("isFormatSupported", formatSupported)
            .put("maxSupportedFrameRate", maxSupportedFrameRate(caps, video));
        MediaFormat lowLatency = new MediaFormat(video);
        lowLatency.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true);
        row.put(
                "lowLatencyAdvertised",
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
            .put("lowLatencyFormatSupported", caps.isFormatSupported(lowLatency))
            .put("queryFailed", false);
      } catch (RuntimeException unavailable) {
        row.put("queryFailed", true);
      }
      rows.put(row);
    }
    Log.i("MMCaps", "CAPS " + rows);
    Bundle report = new Bundle();
    report.putString("CAPS", rows.toString());
    InstrumentationRegistry.getInstrumentation().sendStatus(2, report);
    assertTrue("No matching decoder capabilities", ordinal > 0);
  }

  /** Declared capability for the fixture's actual dimensions, not measured decode throughput. */
  private static double maxSupportedFrameRate(
      MediaCodecInfo.CodecCapabilities caps, MediaFormat video) {
    try {
      if (!video.containsKey(MediaFormat.KEY_WIDTH) || !video.containsKey(MediaFormat.KEY_HEIGHT))
        return -1;
      int width = video.getInteger(MediaFormat.KEY_WIDTH);
      int height = video.getInteger(MediaFormat.KEY_HEIGHT);
      MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
      if (width <= 0 || height <= 0 || videoCaps == null) return -1;
      double rate = videoCaps.getSupportedFrameRatesFor(width, height).getUpper();
      return Double.isFinite(rate) && rate > 0 ? rate : -1;
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }
}
