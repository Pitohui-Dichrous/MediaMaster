package io.mediamaster.app.player;

/**
 * Conservative eligibility from inspected media headers; not a claim of measured fastest decoding.
 */
final class NativeDecoderPolicy {
  static final int AUTO = 0, HARDWARE = 1, SOFTWARE = 2;

  static final class Metadata {
    final String mime;
    final int width, height, avcProfile;
    final double frameRate;
    final boolean hdr;

    Metadata(String mime, int width, int height, double frameRate, int avcProfile, boolean hdr) {
      this.mime = mime;
      this.width = width;
      this.height = height;
      this.frameRate = frameRate;
      this.avcProfile = avcProfile;
      this.hdr = hdr;
    }
  }

  static boolean validatedDevice(String model, int sdk) {
    // This is the actual phone/API used for the software-versus-hardware comparisons.
    // Core count or a marketing chipset name alone does not establish software decode capacity.
    return sdk == 34 && "reference-device".equalsIgnoreCase(model);
  }

  static boolean useSoftware(
      int override, boolean avoidSoftware, String model, int sdk, Metadata metadata) {
    if (avoidSoftware || override == HARDWARE) return false;
    if (override == SOFTWARE)
      return true; // Internal benchmark override, never a library-wide scan.
    if (!validatedDevice(model, sdk) || metadata == null) return false;
    return "video/avc".equals(metadata.mime)
        && metadata.width > 0
        && metadata.height > 0
        && Math.max(metadata.width, metadata.height) <= 1920
        && Math.min(metadata.width, metadata.height) <= 1080
        && metadata.frameRate > 0
        && metadata.frameRate <= 30
        && isEightBitAvcProfile(metadata.avcProfile)
        && !metadata.hdr;
  }

  private static boolean isEightBitAvcProfile(int profile) {
    // MediaCodecInfo.CodecProfileLevel values: Baseline, Main, High, ConstrainedBaseline,
    // ConstrainedHigh. A missing profile is not assumed to be ordinary 8-bit AVC.
    return profile == 0x01
        || profile == 0x02
        || profile == 0x08
        || profile == 0x10000
        || profile == 0x80000;
  }

  private NativeDecoderPolicy() {}
}
