package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeDecoderPolicyTest {
  private static NativeDecoderPolicy.Metadata media(
      String mime, int width, int height, double fps, int profile, boolean hdr) {
    return new NativeDecoderPolicy.Metadata(mime, width, height, fps, profile, hdr);
  }

  private static boolean automatic(NativeDecoderPolicy.Metadata metadata) {
    return NativeDecoderPolicy.useSoftware(0, false, "reference-device", 34, metadata);
  }

  @Test
  public void measuredDeviceAcceptsOrdinaryAvcInBothOrientations() {
    assertTrue(automatic(media("video/avc", 1920, 1080, 25, 8, false)));
    assertTrue(automatic(media("video/avc", 1080, 1920, 30, 8, false)));
    assertTrue(automatic(media("video/avc", 1280, 720, 29.97, 2, false)));
  }

  @Test
  public void smallerSixtyFpsAvcFitsThePixelBudgetInBothOrientations() {
    assertTrue(automatic(media("video/avc", 1280, 720, 60, 8, false)));
    assertTrue(automatic(media("video/avc", 720, 1280, 60, 8, false)));
    assertTrue(automatic(media("video/avc", 1280, 720, 59.94, 2, false)));
    assertTrue(automatic(media("video/avc", 1600, 900, 30, 2, false)));
  }

  @Test
  public void pixelBudgetIncludesItsBoundaryWithoutRoundingFrameRateDown() {
    assertTrue(automatic(media("video/avc", 1920, 1080, 30, 8, false)));
    assertFalse(automatic(media("video/avc", 1920, 1080, Math.nextUp(30d), 8, false)));
    assertTrue(automatic(media("video/avc", 960, 1080, 60, 8, false)));
    assertTrue(automatic(media("video/avc", 1080, 960, 60, 8, false)));
    assertFalse(automatic(media("video/avc", 961, 1080, 60, 8, false)));
    assertFalse(automatic(media("video/avc", 1080, 961, 60, 8, false)));
    assertFalse(automatic(media("video/avc", 1600, 900, 60, 8, false)));
    assertFalse(automatic(media("video/avc", 1920, 1080, 60, 8, false)));
  }

  @Test
  public void onlyPositivelyKnownEightBitProfilesAreEligible() {
    for (int profile : new int[] {1, 2, 8, 0x10000, 0x80000}) {
      assertTrue(automatic(media("video/avc", 1280, 720, 25, profile, false)));
    }
    // Unknown, Extended, High10, High422, High444 and an unrecognized value stay conservative.
    for (int profile : new int[] {0, -1, 4, 16, 32, 64, 999}) {
      assertFalse(automatic(media("video/avc", 1280, 720, 25, profile, false)));
    }
  }

  @Test
  public void unknownOrOverSixtyFrameRatesStayHardwareEvenForSmallPictures() {
    for (double fps :
        new double[] {
          0,
          -1,
          Math.nextUp(60d),
          60.01,
          120,
          Double.NaN,
          Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY
        }) {
      assertFalse(automatic(media("video/avc", 640, 360, fps, 8, false)));
    }
  }

  @Test
  public void dimensionsMustBeKnownAndWithinBothAxes() {
    int[][] sizes = {
      {0, 1080},
      {1920, 0},
      {-1, 720},
      {1280, -1},
      {1921, 1080},
      {1080, 1921},
      {1440, 1440},
      {3840, 2160},
      {Integer.MAX_VALUE, Integer.MAX_VALUE}
    };
    for (int[] size : sizes) {
      assertFalse(automatic(media("video/avc", size[0], size[1], 25, 8, false)));
    }
  }

  @Test
  public void codecAndHdrCannotBeInferredFromAnMp4Container() {
    assertFalse(automatic(null));
    for (String mime :
        new String[] {null, "video/mp4", "video/hevc", "video/av01", "video/x-vnd.on2.vp9"}) {
      assertFalse(automatic(media(mime, 1280, 720, 25, 8, false)));
    }
    assertFalse(automatic(media("video/avc", 1280, 720, 25, 8, true)));
  }

  @Test
  public void otherDevicesAndAndroidVersionsKeepHardwarePreference() {
    NativeDecoderPolicy.Metadata metadata = media("video/avc", 1280, 720, 25, 8, false);
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, "other-device", 34, metadata));
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, null, 34, metadata));
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, "reference-device", 33, metadata));
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, "reference-device", 35, metadata));
  }

  @Test
  public void sixtyFpsExpansionDoesNotBypassExistingSafetyGates() {
    NativeDecoderPolicy.Metadata metadata = media("video/avc", 1280, 720, 60, 8, false);
    assertFalse(NativeDecoderPolicy.useSoftware(0, true, "reference-device", 34, metadata));
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, "other-device", 34, metadata));
    assertFalse(NativeDecoderPolicy.useSoftware(0, false, "reference-device", 35, metadata));
    assertFalse(automatic(media("video/avc", 1280, 720, 60, 0, false)));
    assertFalse(automatic(media("video/avc", 1280, 720, 60, 16, false)));
    assertFalse(automatic(media("video/avc", 1280, 720, 60, 8, true)));
    assertFalse(automatic(media("video/hevc", 1280, 720, 60, 8, false)));
  }

  @Test
  public void internalOverridesAreExplicitButCannotDefeatFallbackGuard() {
    NativeDecoderPolicy.Metadata metadata = media("video/avc", 1280, 720, 25, 8, false);
    assertFalse(NativeDecoderPolicy.useSoftware(1, false, "reference-device", 34, metadata));
    assertTrue(NativeDecoderPolicy.useSoftware(2, false, "other", 26, null));
    assertFalse(NativeDecoderPolicy.useSoftware(2, true, "reference-device", 34, metadata));
    assertFalse(NativeDecoderPolicy.useSoftware(0, true, "reference-device", 34, metadata));
  }
}
