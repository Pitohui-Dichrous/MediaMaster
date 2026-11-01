#!/usr/bin/env python3
"""Generate a public 8-second H.264 720p60 / GOP-250 / B-frame seek stress fixture.

This is a separate protocol. The original 30-second, 25 fps B2 fixtures and their
generator defaults remain unchanged. Only synthetic FFmpeg sources are used.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode = True

from generate_seek_fixtures import barcode, black_white, executable


NAME = "mm_seek_stress_720p60_gop250.mp4"
WIDTH, HEIGHT, FPS, SECONDS, GOP = 1280, 720, 60, 8, 250
SAMPLES = (0, 1, 59, 60, 85, 249, 250, 401, 479)


def command(ffmpeg, video, overwrite):
    return [
        ffmpeg, "-hide_banner", "-v", "warning", "-y" if overwrite else "-n",
        "-f", "lavfi", "-i", f"testsrc2=size={WIDTH}x{HEIGHT}:rate={FPS}:duration={SECONDS}",
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=44100:duration={SECONDS}",
        "-vf", barcode(WIDTH, HEIGHT), "-c:v", "libx264", "-preset", "veryfast",
        "-profile:v", "high", "-crf", "22", "-pix_fmt", "yuv420p", "-threads", "4",
        "-x264-params", f"keyint={GOP}:min-keyint={GOP}:scenecut=0:bframes=3:b-adapt=0:open-gop=0",
        "-frames:v", str(FPS * SECONDS), "-c:a", "aac", "-b:a", "96k", "-shortest",
        "-movflags", "+faststart", str(video),
    ]


def verify_pixels(ffmpeg, video):
    selected = "+".join(f"eq(n,{frame})" for frame in SAMPLES)
    raw = subprocess.run(
        [ffmpeg, "-hide_banner", "-v", "error", "-i", str(video), "-an",
         "-vf", f"select='{selected}',scale=256:144", "-vsync", "0",
         "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
        check=True, capture_output=True,
    ).stdout
    frame_bytes = 256 * 144 * 3
    if len(raw) != len(SAMPLES) * frame_bytes:
        raise ValueError("Incorrect number of sampled frames")
    for index, expected in enumerate(SAMPLES):
        frame = memoryview(raw)[index * frame_bytes:(index + 1) * frame_bytes]
        for y in (43, 72, 100):
            def pixel(x):
                offset = (y * 256 + round(x)) * 3
                return frame[offset:offset + 3]

            def cell(number):
                return pixel(256 * (.1 + (number + .5) * .05))

            red, blue = cell(0), cell(15)
            if not (red[0] > 150 and red[1] < 100 and red[2] < 100
                    and blue[2] > 150 and blue[0] < 100 and blue[1] < 130):
                raise ValueError(f"Color guard mismatch at frame {expected}, row {y}")
            if black_white(pixel(256 * .0875)) or black_white(pixel(256 * .9125)):
                raise ValueError("Barcode backplate isolation mismatch")
            if black_white(cell(1)) != 1:
                raise ValueError("Barcode sync cell mismatch")
            bits = [black_white(cell(k + 2)) for k in range(12)]
            decoded = sum(bit << k for k, bit in enumerate(bits))
            if decoded != expected or black_white(cell(14)) != sum(bits) % 2:
                raise ValueError(f"Frame/parity mismatch at frame {expected}, row {y}")
    return {"sampled_frames": list(SAMPLES), "rows_per_frame": 3, "scaled_size": [256, 144]}


def verify_stream(ffmpeg, ffprobe, video):
    if ffprobe:
        data = json.loads(subprocess.run(
            [ffprobe, "-v", "error", "-select_streams", "v:0", "-show_streams", "-show_frames",
             "-show_entries", "stream=codec_name,profile,width,height,pix_fmt,avg_frame_rate:frame=key_frame,pict_type,best_effort_timestamp_time",
             "-of", "json", str(video)], check=True, capture_output=True, text=True,
        ).stdout)
        backend = "ffprobe-decoded-frames"
    else:
        # showinfo reports decoded presentation timestamps, picture types and keyframe flags.
        # It reads the complete short synthetic clip without storing any decoded image files.
        decoded = subprocess.run(
            [ffmpeg, "-hide_banner", "-v", "info", "-i", str(video), "-map", "0:v:0",
             "-an", "-vf", "showinfo", "-fps_mode", "passthrough", "-f", "null", "-"],
            check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
        ).stderr
        if not re.search(r"Stream #[^\r\n]*Video:\s*h264\s*\(High\)", decoded):
            raise ValueError("FFmpeg input metadata does not establish H.264 High profile")
        if not re.search(r"config in time_base:\s*\d+/\d+,\s*frame_rate:\s*60/1", decoded):
            raise ValueError("FFmpeg showinfo does not establish a 60/1 input frame rate")
        frames = []
        for line in decoded.splitlines():
            if "showinfo" not in line or not re.search(r"\bn:\s*\d+\s+pts:", line):
                continue
            fields = re.search(
                r"\bn:\s*(\d+)\s+pts:\s*-?\d+\s+pts_time:\s*([-+\d.eE]+).*?"
                r"\bfmt:(\S+).*?\bs:(\d+)x(\d+).*?\biskey:(\d+).*?\btype:([IPB])", line)
            if not fields:
                raise ValueError("Unexpected showinfo frame record; no partial verification accepted")
            index, pts, pixel_format, width, height, key, picture = fields.groups()
            if int(index) != len(frames):
                raise ValueError("Missing or duplicate showinfo frame index")
            if (int(width), int(height), pixel_format) != (WIDTH, HEIGHT, "yuv420p"):
                raise ValueError("Unexpected decoded frame geometry or pixel format")
            frames.append({"best_effort_timestamp_time": pts, "pict_type": picture, "key_frame": int(key)})
        data = {"streams": [{"codec_name": "h264", "profile": "High", "width": WIDTH,
                             "height": HEIGHT, "pix_fmt": "yuv420p", "avg_frame_rate": "60/1"}],
                "frames": frames}
        backend = "ffmpeg-input-metadata-and-full-showinfo"
    streams = data.get("streams", [])
    if len(streams) != 1:
        raise ValueError("Expected exactly one selected video stream")
    stream = streams[0]
    expected = {"codec_name": "h264", "profile": "High", "width": WIDTH,
                "height": HEIGHT, "pix_fmt": "yuv420p", "avg_frame_rate": "60/1"}
    for key, value in expected.items():
        if stream.get(key) != value:
            raise ValueError(f"Stream {key}: expected {value}, found {stream.get(key)}")
    frames = data.get("frames", [])
    if len(frames) != FPS * SECONDS:
        raise ValueError(f"Expected {FPS * SECONDS} frames, found {len(frames)}")
    counts = {kind: sum(frame.get("pict_type") == kind for frame in frames) for kind in "IPB"}
    if counts["B"] == 0:
        raise ValueError("Stress case requires actual decoded B frames")
    for index, frame in enumerate(frames):
        if abs(float(frame["best_effort_timestamp_time"]) - index / FPS) > .001:
            raise ValueError(f"Nonuniform decoded timestamp at frame {index}")
    keys = [index for index, frame in enumerate(frames) if frame.get("key_frame") == 1]
    if keys != list(range(0, FPS * SECONDS, GOP)):
        raise ValueError(f"Unexpected keyframe indices: {keys}")
    return {"backend": backend, "stream": stream, "frame_count": len(frames), "picture_types": counts,
            "keyframe_indices": keys, "keyframe_seconds": [frame / FPS for frame in keys]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True, help="FFmpeg with libx264 and AAC")
    parser.add_argument("--ffprobe", help="Optional FFprobe; otherwise FFmpeg showinfo verifies all frames")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--verify-only", action="store_true", help="Read and validate only; write nothing")
    args = parser.parse_args()
    ffmpeg = executable(args.ffmpeg)
    ffprobe = executable(args.ffprobe) if args.ffprobe else None
    output = args.output.expanduser().resolve()
    video = output / NAME
    manifest = output / "stress-fixture.json"
    log_path = output / (NAME + ".encoding.log")
    if not args.verify_only:
        existing = [str(path) for path in (video, manifest, log_path) if path.exists()]
        if existing and not args.overwrite:
            raise ValueError("Refusing to replace existing outputs; use --overwrite explicitly:\n" + "\n".join(existing))
        output.mkdir(parents=True, exist_ok=True)
    argv = command(ffmpeg, video, args.overwrite)
    if not args.verify_only:
        with log_path.open("w" if args.overwrite else "x", encoding="utf-8") as log:
            subprocess.run(argv, check=True, stdout=log, stderr=log)
    pixels = verify_pixels(ffmpeg, video)
    stream = verify_stream(ffmpeg, ffprobe, video)
    digest = hashlib.sha256()
    with video.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    report = {
        "protocol": "B2-stress-60fps-8seconds-gop250-bframes",
        "name": NAME, "fps": FPS, "duration_seconds": SECONDS, "gop_frames": GOP,
        "frame_pts_ms": "frame_index * 1000 / 60; not frame_index * 40",
        "ffmpeg_version": subprocess.run([ffmpeg, "-version"], check=True, capture_output=True, text=True).stdout.splitlines()[0],
        "ffprobe_version": subprocess.run([ffprobe, "-version"], check=True, capture_output=True, text=True).stdout.splitlines()[0] if ffprobe else None,
        "sha256": digest.hexdigest(), "size": video.stat().st_size,
        "pixel_verification": pixels, "stream_verification": stream,
        "generation_command": argv if not args.verify_only else None,
        "note": "Synthetic stress case, not a reproduction of any private media. Encoder versions may change output bytes. The original 30-second/25-fps optical benchmark does not accept this protocol.",
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.verify_only:
        print(encoded, end="")
    else:
        with manifest.open("w" if args.overwrite else "x", encoding="utf-8") as destination:
            destination.write(encoded)
        print(f"Verified stress fixture: {manifest}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Stress fixture generation/verification failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            details = error.stderr.decode("utf-8", errors="replace") if isinstance(error.stderr, bytes) else error.stderr
            print(details[-4000:], file=sys.stderr)
        sys.exit(1)
