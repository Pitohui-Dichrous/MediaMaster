package io.mediamaster.app.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit cleanup only: ordinary test discovery must not change the device's volume. */
@RunWith(AndroidJUnit4.class)
public final class DeviceTestCleanup {
  @Test
  public void setLowestAudibleMediaVolume() {
    assumeTrue(
        "Explicit setup for mixer verification only",
        "lowest".equals(InstrumentationRegistry.getArguments().getString("benchMediaVolume")));
    AudioManager audio =
        (AudioManager)
            InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getSystemService(Context.AUDIO_SERVICE);
    int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    int requested = Math.max(1, maximum / 15);
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0);
    Bundle status = new Bundle();
    status.putInt("mediaVolumeMaximum", maximum);
    status.putInt("mediaVolumeRequested", requested);
    status.putInt("mediaVolumeActual", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    assertEquals(requested, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
  }

  @Test
  public void restoreSilentMediaVolume() {
    assumeTrue(
        "Only run when the recorded pre-test media volume was zero",
        "0".equals(InstrumentationRegistry.getArguments().getString("restoreMediaVolume")));
    AudioManager audio =
        (AudioManager)
            InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getSystemService(Context.AUDIO_SERVICE);
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
    assertEquals(
        "Restored recorded media volume", 0, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
  }
}
