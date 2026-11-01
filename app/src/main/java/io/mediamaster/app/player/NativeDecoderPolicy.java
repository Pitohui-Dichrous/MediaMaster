package io.mediamaster.app.player;

/** Stream/resource eligibility and bounded preference fallbacks, without device identity rules. */
final class NativeDecoderPolicy {
  static final int AUTO = 0, HARDWARE = 1, SOFTWARE = 2;
  static final int COMPATIBLE = 3, NONE = -1;
  private static final long MIB = 1024 * 1024L;

  static final class Metadata {
    final String mime;
    final int width, height, profile;
    final double frameRate;
    final boolean hdr;

    Metadata(String mime, int width, int height, double frameRate, int profile, boolean hdr) {
      this.mime = mime;
      this.width = width;
      this.height = height;
      this.frameRate = frameRate;
      this.profile = profile;
      this.hdr = hdr;
    }
  }

  static final class Resources {
    final long availableBytes, lowMemoryThresholdBytes;
    final boolean lowMemory, severeThermalPressure;

    Resources(
        long availableBytes,
        long thresholdBytes,
        boolean lowMemory,
        boolean severeThermalPressure) {
      this.availableBytes = availableBytes;
      this.lowMemoryThresholdBytes = thresholdBytes;
      this.lowMemory = lowMemory;
      this.severeThermalPressure = severeThermalPressure;
    }
  }

  static int normalizeMode(int mode) {
    return mode >= AUTO && mode <= SOFTWARE ? mode : AUTO;
  }

  static boolean useSoftware(
      int mode, boolean avoidSoftware, Metadata metadata, Resources resources) {
    mode = normalizeMode(mode);
    // An explicit preference is an instruction to try this decoder, not a performance prediction.
    if (mode == SOFTWARE) return true;
    if (mode == HARDWARE || avoidSoftware || metadata == null) return false;
    return metadata.width > 0
        && metadata.height > 0
        && Math.max(metadata.width, metadata.height) <= 1920
        && Math.min(metadata.width, metadata.height) <= 1080
        && metadata.frameRate > 0
        && metadata.frameRate <= 60
        && !metadata.hdr
        && isOrdinaryEightBitStream(metadata)
        && hasMemoryAndThermalHeadroom(metadata, resources);
  }

  static boolean allowsPerformanceFallback(int mode) {
    return normalizeMode(mode) == AUTO;
  }

  /** Native preference is honored before history chooses a different engine. */
  static boolean prefersNativeBeforeHistory(int mode) {
    return normalizeMode(mode) != AUTO;
  }

  /** Call only for an actual error/no-progress failure, never merely a dropped-frame sample. */
  static int afterNativeFailure(
      boolean software, boolean triedSoftware, boolean triedHardware, boolean triedCompatible) {
    if (software && !triedHardware) return HARDWARE;
    if (!triedCompatible) return COMPATIBLE;
    if (!triedSoftware) return SOFTWARE;
    return NONE;
  }

  private static boolean hasMemoryAndThermalHeadroom(Metadata metadata, Resources resources) {
    if (resources == null || resources.lowMemory || resources.severeThermalPressure) return false;
    // Approximate native reference/output buffers plus working space. This admission guard uses
    // currently available RAM; it does not label CPU count or a codec-capability entry as a speed
    // test.
    long workingBytes = 32 * MIB + (long) metadata.width * metadata.height * 48;
    long reserve = Math.max(64 * MIB, resources.lowMemoryThresholdBytes);
    return resources.availableBytes > reserve && resources.availableBytes - reserve >= workingBytes;
  }

  private static boolean isOrdinaryEightBitStream(Metadata metadata) {
    if (metadata.mime == null) return false;
    int profile = metadata.profile;
    switch (metadata.mime) {
      case "video/avc":
        return isEightBitAvcProfile(profile);
      case "video/hevc":
        return profile == 1; // Main; Main10/HDR retain hardware preference in AUTO.
      case "video/x-vnd.on2.vp8":
      case "video/3gpp":
        return true; // These codec formats use 8-bit samples.
      case "video/x-vnd.on2.vp9":
        return profile == 1 || profile == 2; // Profiles 0/1; profiles 2/3 are high bit depth.
      case "video/mp4v-es":
        return profile > 0 && profile <= 0x8000 && (profile & (profile - 1)) == 0;
      case "video/mpeg2":
        return profile == 0 || profile == 1; // Simple/Main; an absent header profile is -1.
      default:
        return false;
    }
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
