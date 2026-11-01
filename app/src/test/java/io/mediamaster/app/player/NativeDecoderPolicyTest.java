package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeDecoderPolicyTest {
  private static final long MIB = 1024 * 1024L;
  private static final NativeDecoderPolicy.Resources AVAILABLE =
      new NativeDecoderPolicy.Resources(1024 * MIB, 128 * MIB, false, false);

  private static NativeDecoderPolicy.Metadata media(
      String mime, int width, int height, double fps, int profile, boolean hdr) {
    return new NativeDecoderPolicy.Metadata(mime, width, height, fps, profile, hdr);
  }

  private static boolean automatic(NativeDecoderPolicy.Metadata metadata) {
    return NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.AUTO, false, metadata, AVAILABLE);
  }

  @Test
  public void defaultTriesCommonEightBitStreamsThroughFullHdSixtyInEitherOrientation() {
    String[] codecs = {
      "video/avc",
      "video/hevc",
      "video/x-vnd.on2.vp8",
      "video/x-vnd.on2.vp9",
      "video/mp4v-es",
      "video/mpeg2",
      "video/3gpp"
    };
    int[] profiles = {8, 1, -1, 1, 1, 1, -1};
    for (int i = 0; i < codecs.length; i++) {
      assertTrue(codecs[i], automatic(media(codecs[i], 1920, 1080, 60, profiles[i], false)));
      assertTrue(codecs[i], automatic(media(codecs[i], 1080, 1920, 59.94, profiles[i], false)));
    }
  }

  @Test
  public void defaultDoesNotRoundOverBudgetDimensionsOrRatesDown() {
    assertFalse(automatic(media("video/avc", 1920, 1080, Math.nextUp(60d), 8, false)));
    assertFalse(automatic(media("video/avc", 1921, 1080, 30, 8, false)));
    assertFalse(automatic(media("video/avc", 1440, 1440, 30, 8, false)));
    assertFalse(automatic(media("video/avc", 3840, 2160, 30, 8, false)));
    for (double fps : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
      assertFalse(automatic(media("video/avc", 640, 360, fps, 8, false)));
    assertFalse(automatic(media("video/avc", 0, 1080, 30, 8, false)));
  }

  @Test
  public void defaultRetainsQualityAndUnknownHeaderGuards() {
    assertFalse(automatic(null));
    assertFalse(automatic(media("video/avc", 1280, 720, 30, 8, true)));
    assertFalse(automatic(media("video/avc", 1280, 720, 30, 16, false)));
    assertFalse(automatic(media("video/avc", 1280, 720, 30, -1, false)));
    assertFalse(automatic(media("video/hevc", 1280, 720, 30, 2, false)));
    assertFalse(automatic(media("video/x-vnd.on2.vp9", 1280, 720, 30, 4, false)));
    assertFalse(automatic(media("video/mpeg2", 1280, 720, 30, -1, false)));
    assertTrue(automatic(media("video/mpeg2", 1280, 720, 30, 0, false)));
    assertFalse(automatic(media("video/unknown", 1280, 720, 30, 1, false)));
  }

  @Test
  public void defaultUsesActualMemoryHeadroomAndThermalPressure() {
    NativeDecoderPolicy.Metadata video = media("video/avc", 1920, 1080, 60, 8, false);
    assertTrue(automatic(video));
    assertFalse(
        NativeDecoderPolicy.useSoftware(
            0,
            false,
            video,
            new NativeDecoderPolicy.Resources(1024 * MIB, 128 * MIB, true, false)));
    assertFalse(
        NativeDecoderPolicy.useSoftware(
            0,
            false,
            video,
            new NativeDecoderPolicy.Resources(1024 * MIB, 128 * MIB, false, true)));
    assertFalse(
        NativeDecoderPolicy.useSoftware(
            0,
            false,
            video,
            new NativeDecoderPolicy.Resources(200 * MIB, 128 * MIB, false, false)));
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, video, null));
    assertFalse(
        NativeDecoderPolicy.useSoftware(
            0, false, video, new NativeDecoderPolicy.Resources(0, 0, false, false)));
  }

  @Test
  public void softwarePreferenceIgnoresPixelRateQualityResourceAndOldSlowHistoryGates() {
    NativeDecoderPolicy.Metadata demanding = media("video/hevc", 7680, 4320, 120, 2, true);
    NativeDecoderPolicy.Resources pressured =
        new NativeDecoderPolicy.Resources(0, 128 * MIB, true, true);
    assertTrue(
        NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.SOFTWARE, true, demanding, pressured));
    assertTrue(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.SOFTWARE, true, null, null));
  }

  @Test
  public void hardwarePreferenceAndDefaultHistoryRemainDistinct() {
    NativeDecoderPolicy.Metadata video = media("video/avc", 640, 360, 24, 8, false);
    assertFalse(
        NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.HARDWARE, false, video, AVAILABLE));
    assertFalse(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.AUTO, true, video, AVAILABLE));
    assertTrue(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.AUTO, false, video, AVAILABLE));
  }

  @Test
  public void onlyDefaultMayChangeEnginesBecauseOfSustainedPoorPlayback() {
    assertTrue(NativeDecoderPolicy.allowsPerformanceFallback(NativeDecoderPolicy.AUTO));
    assertFalse(NativeDecoderPolicy.allowsPerformanceFallback(NativeDecoderPolicy.SOFTWARE));
    assertFalse(NativeDecoderPolicy.allowsPerformanceFallback(NativeDecoderPolicy.HARDWARE));
  }

  @Test
  public void explicitNativePreferencesCannotBeBypassedByOldCompatibleEngineHistory() {
    assertFalse(NativeDecoderPolicy.prefersNativeBeforeHistory(NativeDecoderPolicy.AUTO));
    assertTrue(NativeDecoderPolicy.prefersNativeBeforeHistory(NativeDecoderPolicy.SOFTWARE));
    assertTrue(NativeDecoderPolicy.prefersNativeBeforeHistory(NativeDecoderPolicy.HARDWARE));
  }

  @Test
  public void realSoftwareFailureTriesHardwareThenCompatibleAtMostOnce() {
    assertEquals(
        NativeDecoderPolicy.HARDWARE,
        NativeDecoderPolicy.afterNativeFailure(true, true, false, false));
    assertEquals(
        NativeDecoderPolicy.COMPATIBLE,
        NativeDecoderPolicy.afterNativeFailure(false, true, true, false));
    assertEquals(
        NativeDecoderPolicy.NONE, NativeDecoderPolicy.afterNativeFailure(false, true, true, true));
  }

  @Test
  public void realHardwareFailureKeepsCompatibleHardwareAheadOfSoftwareFallback() {
    assertEquals(
        NativeDecoderPolicy.COMPATIBLE,
        NativeDecoderPolicy.afterNativeFailure(false, false, true, false));
    assertEquals(
        NativeDecoderPolicy.SOFTWARE,
        NativeDecoderPolicy.afterNativeFailure(false, false, true, true));
    assertEquals(
        NativeDecoderPolicy.NONE, NativeDecoderPolicy.afterNativeFailure(true, true, true, true));
  }

  @Test
  public void invalidPreferencesNormalizeToDefaultWithoutNewPersistedValues() {
    assertEquals(0, NativeDecoderPolicy.normalizeMode(-1));
    assertEquals(0, NativeDecoderPolicy.normalizeMode(3));
    assertEquals(1, NativeDecoderPolicy.normalizeMode(1));
    assertEquals(2, NativeDecoderPolicy.normalizeMode(2));
  }

  private static NativeDecoderPolicy.Metadata longGop(int width, int height, double fps, long gap) {
    return new NativeDecoderPolicy.Metadata("video/avc", width, height, fps, 8, false, gap);
  }

  @Test
  public void heavyIndexedAvcGetsOnlyEightWorkersWithResourcesAndEnoughCpus() {
    assertEquals(
        8, NativeDecoderPolicy.softwareThreads(longGop(1920, 1080, 60, 10000), AVAILABLE, 8));
    assertEquals(
        8, NativeDecoderPolicy.softwareThreads(longGop(1080, 1920, 48, 4000), AVAILABLE, 64));
    assertEquals(
        0, NativeDecoderPolicy.softwareThreads(longGop(1920, 1080, 60, 10000), AVAILABLE, 7));
  }

  @Test
  public void missingOrLightMediaKeepsNativeThreadDefaults() {
    assertEquals(0, NativeDecoderPolicy.softwareThreads(null, AVAILABLE, 8));
    assertEquals(
        0,
        NativeDecoderPolicy.softwareThreads(
            media("video/avc", 1920, 1080, 60, 8, false), AVAILABLE, 8));
    assertEquals(
        0, NativeDecoderPolicy.softwareThreads(longGop(1920, 1080, 60, 3999), AVAILABLE, 8));
    assertEquals(
        0, NativeDecoderPolicy.softwareThreads(longGop(1280, 720, 60, 10000), AVAILABLE, 8));
    for (double rate : new double[] {24, 47.99, Double.NaN, Double.POSITIVE_INFINITY})
      assertEquals(
          0, NativeDecoderPolicy.softwareThreads(longGop(1920, 1080, rate, 10000), AVAILABLE, 8));
    assertEquals(
        0,
        NativeDecoderPolicy.softwareThreads(
            new NativeDecoderPolicy.Metadata("video/hevc", 1920, 1080, 60, 1, false, 10000),
            AVAILABLE,
            8));
    assertEquals(
        0,
        NativeDecoderPolicy.softwareThreads(
            new NativeDecoderPolicy.Metadata("video/avc", 1920, 1080, 60, 16, false, 10000),
            AVAILABLE,
            8));
    assertEquals(
        0,
        NativeDecoderPolicy.softwareThreads(
            new NativeDecoderPolicy.Metadata("video/avc", 1920, 1080, 60, 8, true, 10000),
            AVAILABLE,
            8));
  }

  @Test
  public void workerAdmissionUsesLargerMemoryReserveAndPreservesExplicitSoftware() {
    NativeDecoderPolicy.Metadata video = longGop(1920, 1080, 60, 10000);
    NativeDecoderPolicy.Resources tight =
        new NativeDecoderPolicy.Resources(300 * MIB, 128 * MIB, false, false);
    assertTrue(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.AUTO, false, video, tight));
    assertEquals(0, NativeDecoderPolicy.softwareThreads(video, tight, 8));
    for (NativeDecoderPolicy.Resources pressure :
        new NativeDecoderPolicy.Resources[] {
          null,
          new NativeDecoderPolicy.Resources(1024 * MIB, 128 * MIB, true, false),
          new NativeDecoderPolicy.Resources(1024 * MIB, 128 * MIB, false, true)
        }) {
      assertEquals(0, NativeDecoderPolicy.softwareThreads(video, pressure, 8));
      assertTrue(
          NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.SOFTWARE, true, video, pressure));
    }
    assertFalse(
        NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.HARDWARE, false, video, AVAILABLE));
    assertTrue(NativeDecoderPolicy.demandingLongGop(video));
  }

  @Test
  public void unknownDecisionCannotInvertAnExplicitDecoderPreference() {
    assertTrue(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.SOFTWARE, true, null, null));
    assertFalse(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.HARDWARE, false, null, null));
    assertFalse(NativeDecoderPolicy.useSoftware(NativeDecoderPolicy.AUTO, false, null, null));
    assertEquals(
        0,
        NativeDecoderPolicy.softwareThreads(
            longGop(Integer.MAX_VALUE, Integer.MAX_VALUE, 60, 10000), AVAILABLE, 8));
  }
}
