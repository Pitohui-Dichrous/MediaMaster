#!/usr/bin/env python3
"""Recompress PNG IDAT losslessly, preserving every other chunk and the exact filtered pixels.

Requires Pillow for an independent RGBA verification. Optional --zopfli uses the
zopfli Python package for a smaller standard zlib stream. Neither is an app dependency.
No color conversion, quantization, resizing, filtering or metadata removal is performed.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import zlib

ROOT = Path(__file__).resolve().parent.parent
ARTWORK = ROOT / "app/src/main/res/drawable-nodpi"
NAMES = ("ic_launcher_artwork.png", "like_effect.png",
         "scrubber_backward.png", "scrubber_forward.png")
SIGNATURE = b"\x89PNG\r\n\x1a\n"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def chunks(raw):
    require(raw.startswith(SIGNATURE), "Input is not a PNG")
    result, at = [], len(SIGNATURE)
    while at < len(raw):
        require(at + 12 <= len(raw), "Truncated PNG chunk")
        length = struct.unpack_from(">I", raw, at)[0]
        end = at + length + 12
        require(end <= len(raw), "Truncated PNG data")
        kind, payload = raw[at + 4:at + 8], raw[at + 8:end - 4]
        crc = struct.unpack_from(">I", raw, end - 4)[0]
        require(zlib.crc32(kind + payload) & 0xffffffff == crc, "PNG CRC mismatch")
        require(kind not in (b"acTL", b"fcTL", b"fdAT"), "Animated PNG is not supported")
        result.append((kind, payload, raw[at:end]))
        at = end
        if kind == b"IEND":
            break
    require(at == len(raw), "Trailing PNG data must not be silently discarded")
    require(result and result[0][0] == b"IHDR" and result[-1][0] == b"IEND",
            "Missing PNG boundaries")
    require(len(result[0][1]) == 13, "Invalid PNG header")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(
        ">IIBBBBB", result[0][1])
    require(width > 0 and height > 0 and depth == 8 and color in (2, 6)
            and compression == filtering == interlace == 0,
            "Only existing 8-bit noninterlaced RGB/RGBA artwork is supported")
    idat_indices = [index for index, chunk in enumerate(result) if chunk[0] == b"IDAT"]
    require(idat_indices and idat_indices == list(range(idat_indices[0], idat_indices[-1] + 1)),
            "PNG IDAT chunks must be consecutive")
    return result, (width, height, "RGB" if color == 2 else "RGBA")


def filtered(parts, dimensions):
    compressed = b"".join(payload for kind, payload, unused in parts if kind == b"IDAT")
    width, height, mode = dimensions
    expected = (width * (3 if mode == "RGB" else 4) + 1) * height
    require(expected <= 64 * 1024 * 1024, "Artwork exceeds the bounded decode size")
    stream = zlib.decompressobj()
    decoded = stream.decompress(compressed, expected + 1)
    require(len(decoded) == expected and stream.eof
            and not stream.unused_data and not stream.unconsumed_tail,
            "Unexpected PNG scanline stream")
    return decoded, compressed


def encode(parts, data):
    idat = (struct.pack(">I", len(data)) + b"IDAT" + data
            + struct.pack(">I", zlib.crc32(b"IDAT" + data) & 0xffffffff))
    output, replaced = bytearray(SIGNATURE), False
    for kind, unused, raw_chunk in parts:
        if kind == b"IDAT":
            if not replaced:
                output.extend(idat)
                replaced = True
        else:
            output.extend(raw_chunk)
    return bytes(output)


def verify(original, optimized):
    from PIL import Image
    before, dimensions = chunks(original)
    after, after_dimensions = chunks(optimized)
    require(dimensions == after_dimensions, "Dimensions or pixel mode changed")
    require([raw for kind, unused, raw in before if kind != b"IDAT"]
            == [raw for kind, unused, raw in after if kind != b"IDAT"],
            "A non-IDAT chunk changed")
    before_filtered, unused = filtered(before, dimensions)
    after_filtered, unused = filtered(after, dimensions)
    require(before_filtered == after_filtered, "Filtered scanline bytes changed")
    with Image.open(io.BytesIO(original)) as first, Image.open(io.BytesIO(optimized)) as second:
        first.load()
        second.load()
        before_pixels, after_pixels = first.convert("RGBA").tobytes(), second.convert("RGBA").tobytes()
        require(first.size == second.size and before_pixels == after_pixels,
                "Independently decoded RGBA pixels changed")
        require(first.info == second.info, "Decoded rendering metadata changed")
    return {"width": dimensions[0], "height": dimensions[1], "mode": dimensions[2],
            "rgbaSha256": sha(before_pixels), "nonIdatChunksIdentical": True,
            "filteredScanlinesIdentical": True, "renderingMetadataIdentical": True}


def optimize(raw, zopfli_compress):
    parts, dimensions = chunks(raw)
    pixels, existing = filtered(parts, dimensions)
    candidates = [(existing, "existing")]
    # PNGs frequently originate from a fast encoder. Do not assume the largest level wins.
    for memory in (8, 9):
        for strategy in (zlib.Z_DEFAULT_STRATEGY, zlib.Z_FILTERED):
            codec = zlib.compressobj(9, zlib.DEFLATED, 15, memory, strategy)
            data = codec.compress(pixels) + codec.flush()
            candidates.append((data, f"zlib-9-m{memory}-s{strategy}"))
    if zopfli_compress is not None:
        candidates.append((zopfli_compress(pixels, numiterations=15), "zopfli-15"))
    smallest, method = min(candidates, key=lambda candidate: len(candidate[0]))
    optimized = encode(parts, smallest)
    if len(optimized) >= len(raw):
        optimized, method = raw, "existing"
    details = verify(raw, optimized)
    details.update({"beforeBytes": len(raw), "afterBytes": len(optimized),
                    "savedBytes": len(raw) - len(optimized), "method": method,
                    "beforeSha256": sha(raw), "afterSha256": sha(optimized)})
    return optimized, details


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Replace only smaller, verified PNGs")
    parser.add_argument("--backup-dir", type=Path, help="New directory for exact originals; required with --apply")
    parser.add_argument("--report", type=Path, required=True, help="Write the UTF-8 verification report")
    parser.add_argument("--zopfli", action="store_true")
    parser.add_argument("--zopfli-package-dir", type=Path,
                        help="Optional isolated package directory containing zopfli")
    args = parser.parse_args()
    require(not args.apply or args.backup_dir is not None, "--apply requires --backup-dir")
    if args.apply:
        require(not args.backup_dir.resolve().exists(), "Original backup directory already exists")
    if args.zopfli_package_dir:
        sys.path.insert(0, str(args.zopfli_package_dir.resolve()))
    compressor = None
    zopfli_version = None
    if args.zopfli:
        import zopfli
        import zopfli.zlib
        compressor = zopfli.zlib.compress
        zopfli_version = getattr(zopfli, "__version__", "unknown")
    prepared, reports = [], []
    for name in NAMES:
        path = ARTWORK / name
        require(path.is_file() and not path.is_symlink(), "Artwork must be a regular file")
        raw = path.read_bytes()
        optimized, details = optimize(raw, compressor)
        details["name"] = name
        reports.append(details)
        prepared.append((path, raw, optimized))
        print(f"{name}: {len(raw)} -> {len(optimized)} bytes; exact pixels/metadata verified", flush=True)
    if args.apply:
        backup = args.backup_dir.resolve()
        require(not backup.exists(), "Original backup directory already exists")
        backup.mkdir(parents=True)
        for path, raw, unused in prepared:
            require(path.read_bytes() == raw, "Artwork changed during optimization")
            (backup / path.name).write_bytes(raw)
        for path, raw, optimized in prepared:
            if optimized == raw:
                continue
            require(path.read_bytes() == raw, "Artwork changed before replacement")
            handle, temp_name = tempfile.mkstemp(prefix=path.stem + "-", suffix=".png", dir=path.parent)
            try:
                with os.fdopen(handle, "wb") as stream:
                    stream.write(optimized)
                require(Path(temp_name).read_bytes() == optimized, "Temporary PNG differs")
                os.replace(temp_name, path)
            finally:
                if Path(temp_name).exists():
                    Path(temp_name).unlink()
    report = {"zlibVersion": zlib.ZLIB_RUNTIME_VERSION, "zopfliVersion": zopfli_version,
              "applied": args.apply, "images": reports,
              "beforeBytes": sum(item["beforeBytes"] for item in reports),
              "afterBytes": sum(item["afterBytes"] for item in reports)}
    report["savedBytes"] = report["beforeBytes"] - report["afterBytes"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Total saved: {report['savedBytes']} bytes. No resize, color conversion or metadata removal.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ImportError, zlib.error) as error:
        print("PNG optimization stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
