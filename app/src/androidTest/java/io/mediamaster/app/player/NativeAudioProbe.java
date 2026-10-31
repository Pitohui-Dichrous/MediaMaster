package io.mediamaster.app.player;

import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.media.AudioManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads only the test process's live mixer rows; historical audio logs are not state evidence. */
final class NativeAudioProbe {
  static void assertAudible(Instrumentation instrumentation) throws Exception {
    assertOutput(instrumentation, false);
  }

  static void assertMuted(Instrumentation instrumentation) throws Exception {
    assertOutput(instrumentation, true);
  }

  private static void assertOutput(Instrumentation instrumentation, boolean muted)
      throws Exception {
    AudioManager audio =
        (AudioManager) instrumentation.getTargetContext().getSystemService(Context.AUDIO_SERVICE);
    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        || audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
      fail("Mixer verification requires a nonzero, unmuted system media volume");
    }
    String pid = Integer.toString(android.os.Process.myPid());
    long deadline = SystemClock.elapsedRealtime() + 5000;
    String evidence = "No AudioFlinger dump read";
    while (SystemClock.elapsedRealtime() < deadline) {
      String dump;
      try (InputStream input =
              new ParcelFileDescriptor.AutoCloseInputStream(
                  instrumentation
                      .getUiAutomation()
                      .executeShellCommand("dumpsys media.audio_flinger"));
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        dump = new String(output.toByteArray(), StandardCharsets.UTF_8);
      }
      boolean inLiveTable = false, anyAudible = false, allSilent = true;
      int ownTracks = 0, tables = 0;
      StringBuilder ownRows = new StringBuilder();
      for (String line : dump.split("\\r?\\n")) {
        String trimmed = line.trim();
        if (trimmed.startsWith("Type")
            && trimmed.contains("Active Client Session")
            && trimmed.contains("G db  L dB  R dB")) {
          inLiveTable = true;
          tables++;
          continue;
        }
        if (!inLiveTable) continue;
        String[] cells = trimmed.split("\\s+");
        int active =
            cells.length > 1 && ("yes".equals(cells[1]) || "no".equals(cells[1]))
                ? 1
                : cells.length > 2 && ("yes".equals(cells[2]) || "no".equals(cells[2])) ? 2 : -1;
        if (active < 0 || cells.length <= active + 14) {
          inLiveTable = false;
          continue;
        }
        if (!pid.equals(cells[active + 1])) continue;
        ownTracks++;
        ownRows.append(trimmed).append('\n');
        if (!"yes".equals(cells[active])) continue;
        String gain = cells[active + 12], left = cells[active + 13], right = cells[active + 14];
        anyAudible |= finiteDb(gain) && finiteDb(left) && finiteDb(right);
        allSilent &= mutedDb(gain) || (mutedDb(left) && mutedDb(right));
      }
      evidence = "liveTables=" + tables + ", ownTracks=" + ownTracks + ", ownRows=" + ownRows;
      if (ownTracks > 0 && (muted ? allSilent : anyAudible)) {
        Log.i("NativeAudioProbe", (muted ? "Muted " : "Audible ") + "pid=" + pid + ": " + evidence);
        return;
      }
      SystemClock.sleep(100);
    }
    fail(
        "No verified "
            + (muted ? "silent" : "active unmuted")
            + " AudioFlinger track state for test process pid="
            + pid
            + "; "
            + evidence);
  }

  private static boolean finiteDb(String value) {
    try {
      return Double.isFinite(Double.parseDouble(value));
    } catch (NumberFormatException unsupported) {
      return false;
    }
  }

  private static boolean mutedDb(String value) {
    return "-inf".equalsIgnoreCase(value) || "-infinity".equalsIgnoreCase(value);
  }
}
