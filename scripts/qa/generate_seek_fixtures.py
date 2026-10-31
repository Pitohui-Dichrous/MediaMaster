#!/usr/bin/env python3
"""Generate and verify the B2 optical seek fixtures; Python standard library + FFmpeg."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


FPS = 25
DURATION = 30
SAMPLES = (0, 1, 24, 25, 85, 249, 250, 501, 749)
CASES = (
    ("mm_seek_b2_h264_720_gop1.mp4", 1280, 720, "libx264", 25),
    ("mm_seek_b2_h264_1080_gop10.mp4", 1920, 1080, "libx264", 250),
    ("mm_seek_b2_hevc_1080_gop2.mp4", 1920, 1080, "libx265", 50),
)


def executable(value):
    found = shutil.which(value)
    path = Path(found or value).expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"Executable not found: {value}")
    return str(path)


def barcode(width, height):
    """The original B2 geometry, including isolated red/blue guards and black backplate."""
    cell = width // 20
    left, top, band_height = width // 10, height // 4, height // 2

    def box(index, color, enable=None, cells=1):
        result = (
            f"drawbox=x={left + index * cell}:y={top}:"
            f"w={cell * cells}:h={band_height}:color={color}:t=fill"
        )
        return result if enable is None else result + f":enable='{enable}'"

    backplate = (
        f"drawbox=x={width * 8 // 100}:y={height * 23 // 100}:"
        f"w={width * 84 // 100}:h={height * 54 // 100}:color=black:t=fill"
    )
    filters = [backplate, box(0, "black", cells=16), box(0, "red"), box(1, "white")]
    bits = [f"mod(floor(n/{2**k}),2)" for k in range(12)]
    filters += [box(k + 2, "white", expression) for k, expression in enumerate(bits)]
    filters += [box(14, "white", "mod(" + "+".join(bits) + ",2)"), box(15, "blue")]
    return ",".join(filters)


def command(ffmpeg, output, width, height, codec, gop, overwrite):
    result = [
        ffmpeg, "-hide_banner", "-y" if overwrite else "-n", "-v", "warning",
        "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate={FPS}:duration={DURATION}",
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={DURATION}",
        "-vf", barcode(width, height), "-c:v", codec, "-preset", "veryfast", "-crf", "22",
        "-pix_fmt", "yuv420p", "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0",
        "-c:a", "aac", "-b:a", "96k", "-shortest", "-movflags", "+faststart",
    ]
    if codec == "libx265":
        result += ["-x265-params", (
            f"keyint={gop}:min-keyint={gop}:scenecut=0:"
            "pools=4:frame-threads=2:log-level=error"
        )]
    else:
        result += ["-threads", "4"]
    return result + [str(output)]


def black_white(rgb):
    if min(rgb) > 150:
        return 1
    if max(rgb) < 80:
        return 0
    raise ValueError(f"Barcode cell is neither black nor white: {tuple(rgb)}")


def verify_barcode(ffmpeg, video):
    """Decode nine frames at three rows after scaling, without image libraries."""
    selected = "+".join(f"eq(n,{frame})" for frame in SAMPLES)
    raw = subprocess.run(
        [ffmpeg, "-hide_banner", "-v", "error", "-i", str(video), "-an",
         "-vf", f"select='{selected}',scale=256:144", "-vsync", "0",
         "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
        check=True, capture_output=True,
    ).stdout
    frame_bytes = 256 * 144 * 3
    if len(raw) != len(SAMPLES) * frame_bytes:
        raise ValueError(f"Expected {len(SAMPLES)} sampled frames, got {len(raw) / frame_bytes}")
    for index, expected in enumerate(SAMPLES):
        frame = memoryview(raw)[index * frame_bytes:(index + 1) * frame_bytes]
        for y in (43, 72, 100):
            def pixel(x):
                start = (y * 256 + round(x)) * 3
                return frame[start:start + 3]

            def cell(number):
                return pixel(256 * (.1 + (number + .5) * .05))

            red, blue = cell(0), cell(15)
            if not (red[0] > 150 and red[1] < 100 and red[2] < 100
                    and blue[2] > 150 and blue[0] < 100 and blue[1] < 130):
                raise ValueError(f"Invalid color guards at frame{expected}, row{y}")
            if black_white(pixel(256 * .0875)) or black_white(pixel(256 * .9125)):
                raise ValueError("Missing black guard isolation")
            if black_white(cell(1)) != 1:
                raise ValueError("Missing white sync cell")
            bits = [black_white(cell(k + 2)) for k in range(12)]
            decoded = sum(bit << k for k, bit in enumerate(bits))
            if decoded != expected or black_white(cell(14)) != sum(bits) % 2:
                raise ValueError(f"Barcode mismatch: expected{expected}, decoded{decoded}, row{y}")
    return {"sampled_frames": list(SAMPLES), "rows_per_frame": 3, "scaled_size": [256, 144]}


def verify_keyframes(ffprobe, video, gop):
    data = json.loads(subprocess.run(
        [ffprobe, "-v", "error", "-skip_frame", "nokey", "-select_streams", "v:0",
         "-show_frames", "-show_entries", "frame=best_effort_timestamp_time",
         "-of", "json", str(video)], check=True, capture_output=True, text=True,
    ).stdout)
    times = [float(frame["best_effort_timestamp_time"]) for frame in data["frames"]]
    expected = [frame / FPS for frame in range(0, FPS * DURATION, gop)]
    if len(times) != len(expected) or any(abs(a - b) > .001 for a, b in zip(times, expected)):
        raise ValueError(f"Unexpected keyframe times: {times}; expected{expected}")
    return times


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True, help="FFmpeg executable with libx264/libx265/AAC")
    parser.add_argument("--ffprobe", help="Optional FFprobe executable to validate keyframe spacing")
    parser.add_argument("--output", required=True, type=Path, help="Fixture output directory")
    parser.add_argument("--overwrite", action="store_true", help="Explicitly replace known fixture outputs")
    parser.add_argument("--verify-only", action="store_true", help="Read existing fixtures; write nothing")
    args = parser.parse_args()
    ffmpeg = executable(args.ffmpeg)
    ffprobe = executable(args.ffprobe) if args.ffprobe else None
    output = args.output.expanduser().resolve()
    manifest = output / "fixtures.json"
    if not args.verify_only:
        targets = [manifest]
        for name, *_ in CASES:
            targets += [output / name, output / (name + ".encoding.log")]
        existing = [str(path) for path in targets if path.exists()]
        if existing and not args.overwrite:
            raise ValueError("Existing outputs will not be replaced; use --overwrite explicitly:\n" + "\n".join(existing))
        output.mkdir(parents=True, exist_ok=True)
    version = subprocess.run([ffmpeg, "-version"], check=True, capture_output=True, text=True).stdout.splitlines()[0]
    report = {
        "protocol": "B2-red-white-12LSB-parity-blue-black-backplate",
        "ffmpeg_version": version,
        "frame_pts_ms": "frame_index * 40",
        "note": "Encoder versions can change bytes/hashes; barcode PTS and GOP protocol stay fixed.",
        "fixtures": [],
    }
    for name, width, height, codec, gop in CASES:
        video = output / name
        argv = command(ffmpeg, video, width, height, codec, gop, args.overwrite)
        if not args.verify_only:
            print(f"Generating {name}", flush=True)
            with (output / (name + ".encoding.log")).open("w" if args.overwrite else "x", encoding="utf-8") as log:
                subprocess.run(argv, stdout=log, stderr=log, check=True)
        print(f"Verifying {name}", flush=True)
        verification = verify_barcode(ffmpeg, video)
        if ffprobe:
            verification["keyframe_seconds"] = verify_keyframes(ffprobe, video, gop)
        digest = hashlib.sha256()
        with video.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        report["fixtures"].append({
            "name": name, "width": width, "height": height, "codec": codec,
            "fps": FPS, "duration_seconds": DURATION, "frames": FPS * DURATION,
            "gop_frames": gop, "sha256": digest.hexdigest(), "size": video.stat().st_size,
            "verification": verification, "command": argv,
        })
    encoded = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.verify_only:
        print(encoded, end="")
    else:
        with manifest.open("w" if args.overwrite else "x", encoding="utf-8") as stream:
            stream.write(encoded)
        print(f"Verified all fixtures: {manifest}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Fixture generation/verification failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            details = error.stderr.decode("utf-8", errors="replace") if isinstance(error.stderr, bytes) else error.stderr
            print(details[-4000:], file=sys.stderr)
        sys.exit(1)
