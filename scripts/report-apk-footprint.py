#!/usr/bin/env python3
"""Compare APK-held and extracted native bytes without guessing ART or user-data sizes."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

def analyze(path):
    path = Path(path)
    groups = {}
    native = []
    with zipfile.ZipFile(path) as apk:
        if apk.testzip() is not None:
            raise ValueError("Corrupt APK")
        for item in apk.infolist():
            if item.is_dir():
                continue
            name = item.filename
            group = ("native" if name.startswith("lib/") else
                     "dex" if name.endswith(".dex") and "/" not in name else
                     "resources" if name.startswith("res/") or name == "resources.arsc" else
                     "assets" if name.startswith("assets/") else "metadata")
            total = groups.setdefault(group, {"files": 0, "apkPayloadBytes": 0, "uncompressedBytes": 0})
            total["files"] += 1
            total["apkPayloadBytes"] += item.compress_size
            total["uncompressedBytes"] += item.file_size
            if group == "native":
                native.append({"name": name, "bytes": item.file_size, "apkPayloadBytes": item.compress_size,
                               "zipMethod": item.compress_type,
                               "sha256": hashlib.sha256(apk.read(item)).hexdigest()})
    apk_bytes = path.stat().st_size
    extracted = sum(item["bytes"] for item in native)
    return {"artifact": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "apkBytes": apk_bytes, "nativeExtractedBytes": extracted,
            "apkPlusExtractedNativeLogicalBytes": apk_bytes + extracted,
            "abis": sorted({item["name"].split("/")[1] for item in native}),
            "groups": groups, "nativeLibraries": native}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before, after = analyze(args.baseline), analyze(args.candidate)
    if before["abis"] != after["abis"] or len(before["abis"]) != 1:
        raise ValueError("Compare separate APKs of the same single ABI")
    if any(row["zipMethod"] != zipfile.ZIP_DEFLATED
           for sample in (before, after) for row in sample["nativeLibraries"]):
        raise ValueError("This comparison requires the unchanged legacy compressed-library packaging")
    delta = {key: before[key] - after[key] for key in
             ("apkBytes", "nativeExtractedBytes", "apkPlusExtractedNativeLogicalBytes")}
    result = {"baseline": before, "candidate": after, "savedBytes": delta,
              "measurementBoundary": "APK file plus extracted .so logical bytes; not total installed size.",
              "excluded": ["Filesystem block rounding and metadata", "ART oat/vdex/art and profiles",
                           "Application data and cache"],
              "nativePackagingChanged": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise ValueError("Preserve previous measurements; choose a new report path")
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"abi": after["abis"][0], "before": before["apkPlusExtractedNativeLogicalBytes"],
                      "after": after["apkPlusExtractedNativeLogicalBytes"], "saved": delta}, indent=2))

if __name__ == "__main__":
    main()
