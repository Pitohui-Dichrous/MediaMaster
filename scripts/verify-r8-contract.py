#!/usr/bin/env python3
"""Read-only verification of LibVLC JNI and saved-state contracts in actual APK DEX.

The reference must be an unminified APK built with the same LibVLC version.
Examining string tables or mapping.txt alone cannot prove that a method survived:
this verifier reads encoded class definitions and exact method/field descriptors.
It does not execute native callbacks or replace playback testing.
"""

import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path


VLC = "Lorg/videolan/libvlc/"
VIDEO_ITEM = "Lio/mediamaster/app/media/VideoItem;"
NATIVE = 0x0100
STATIC = 0x0008

# Names resolved by libvlcjni.so and libvlc.so, beyond exported native methods.
CALLBACK_NAMES = {
    "VLCObject": {"dispatchEventFromNative"},
    "Media": {
        "createAudioTrackFromNative", "createVideoTrackFromNative",
        "createSubtitleTrackFromNative", "createUnknownTrackFromNative",
        "createSlaveFromNative", "createStatsFromNative",
    },
    "MediaPlayer": {
        "createTitleFromNative", "createChapterFromNative",
        "createTrackDescriptionFromNative",
    },
    "MediaDiscoverer": {"createDescriptionFromNative"},
    "RendererDiscoverer": {"createDescriptionFromNative", "createItemFromNative"},
    "Dialog": {
        "displayErrorFromNative", "displayLoginFromNative",
        "displayQuestionFromNative", "displayProgressFromNative",
        "cancelFromNative", "updateProgressFromNative",
    },
    "AWindow": {
        "getVideoSurface", "getSubtitlesSurface", "registerNative",
        "unregisterNative", "setBuffersGeometry", "setVideoLayout",
        "SurfaceTexture_attachToGLContext", "SurfaceTexture_detachFromGLContext",
        "SurfaceTexture_waitAndUpdateTexImage", "SurfaceTexture_getSurface",
    },
}


class Definitions:
    def __init__(self):
        self.classes = set()
        self.methods = {}  # (owner, name, descriptor) -> access flags
        self.fields = {}

    def add_dex(self, data):
        if not re.match(rb"dex\n0(?:35|37|38|39|40)\x00", data[:8]):
            raise ValueError("Expected ordinary DEX 035-040")

        def u32(offset):
            return struct.unpack_from("<I", data, offset)[0]

        def u16(offset):
            return struct.unpack_from("<H", data, offset)[0]

        def leb(offset):
            value = 0
            for shift in range(0, 35, 7):
                byte = data[offset]
                offset += 1
                value |= (byte & 0x7f) << shift
                if not byte & 0x80:
                    return value, offset
            raise ValueError("Invalid DEX ULEB128")

        if u32(40) != 0x12345678 or u32(32) != len(data):
            raise ValueError("Unsupported endian tag or truncated DEX")
        strings = []
        for index in range(u32(56)):
            _, start = leb(u32(u32(60) + index * 4))
            # All checked identifiers/descriptors are ASCII. Unrelated MUTF-8
            # strings may contain surrogate code points or encoded NULs.
            strings.append(data[start:data.index(b"\0", start)].decode("utf-8", "replace"))
        types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
        protos = []
        for index in range(u32(72)):
            offset = u32(76) + index * 12
            parameters = u32(offset + 8)
            args = "" if not parameters else "".join(
                types[u16(parameters + 4 + i * 2)] for i in range(u32(parameters))
            )
            protos.append("(" + args + ")" + types[u32(offset + 4)])
        fields, methods = [], []
        for index in range(u32(80)):
            offset = u32(84) + index * 8
            fields.append((types[u16(offset)], strings[u32(offset + 4)], types[u16(offset + 2)]))
        for index in range(u32(88)):
            offset = u32(92) + index * 8
            methods.append((types[u16(offset)], strings[u32(offset + 4)], protos[u16(offset + 2)]))
        for index in range(u32(96)):
            offset = u32(100) + index * 32
            owner = types[u32(offset)]
            if owner in self.classes:
                raise ValueError("Duplicate DEX class: " + owner)
            self.classes.add(owner)
            position = u32(offset + 24)
            if not position:
                continue
            counts = []
            for _ in range(4):
                count, position = leb(position)
                counts.append(count)
            for group, count in enumerate(counts):
                member_index = 0
                for _ in range(count):
                    diff, position = leb(position)
                    flags, position = leb(position)
                    member_index += diff
                    table = fields if group < 2 else methods
                    member = table[member_index]
                    if member[0] != owner:
                        raise ValueError("Invalid DEX member owner")
                    destination = self.fields if group < 2 else self.methods
                    if member in destination:
                        raise ValueError("Duplicate DEX member")
                    destination[member] = flags
                    if group >= 2:
                        _, position = leb(position)  # code_off

    @classmethod
    def from_apk(cls, path):
        result = cls()
        with zipfile.ZipFile(path) as archive:
            dex_files = [n for n in archive.namelist() if re.fullmatch(r"classes(?:[2-9]|[1-9]\d+)?\.dex", n)]
            if not dex_files:
                raise ValueError("APK has no root DEX files: " + str(path))
            for name in dex_files:
                result.add_dex(archive.read(name))
        return result


def java_name(descriptor):
    return descriptor[1:-1].replace("/", ".")


def verify(reference, candidate, mapping_path):
    failures = []
    expected_methods = {
        key: flags for key, flags in reference.methods.items()
        if key[0].startswith(VLC) and flags & NATIVE
    }
    native_count = len(expected_methods)
    if native_count < 100:
        failures.append("Reference lacks the expected unminified LibVLC native contract")
    callback_count = 0
    for short_owner, names in CALLBACK_NAMES.items():
        owner = VLC + short_owner + ";"
        for name in names:
            matches = {key: flags for key, flags in reference.methods.items() if key[:2] == (owner, name)}
            if len(matches) != 1:
                failures.append("Reference callback absent/ambiguous: " + owner + "." + name)
            expected_methods.update(matches)
            callback_count += len(matches)
    expected_fields = {
        (VLC + "VLCObject;", "mInstance", "J"): None,
        (VLC + "MediaPlayer$Equalizer;", "mInstance", "J"): None,
    }
    saved_fields = {key: flags for key, flags in reference.fields.items() if key[0] == VIDEO_ITEM}
    if len(saved_fields) != 11 or (VIDEO_ITEM, "serialVersionUID", "J") not in saved_fields:
        failures.append("Reference VideoItem serialization schema changed; review its keep contract")
    expected_fields.update(saved_fields)
    expected_classes = {VIDEO_ITEM}
    for key, flags in expected_methods.items():
        owner, name, descriptor = key
        expected_classes.add(owner)
        expected_classes.update(t for t in re.findall(r"L[^;]+;", descriptor) if t in reference.classes)
        if key not in candidate.methods:
            failures.append("Missing/renamed method: " + owner + "." + name + descriptor)
        elif (candidate.methods[key] ^ flags) & (NATIVE | STATIC):
            failures.append("Changed native/static binding: " + owner + "." + name + descriptor)
    for key, flags in expected_fields.items():
        expected_classes.add(key[0])
        if key not in reference.fields:
            failures.append("Reference field absent: " + repr(key))
        elif key not in candidate.fields:
            failures.append("Missing/renamed field: " + repr(key))
        elif (candidate.fields[key] ^ reference.fields[key]) & STATIC:
            failures.append("Changed static field binding: " + repr(key))
    for owner in sorted(expected_classes):
        if owner not in candidate.classes:
            failures.append("Missing/renamed class definition: " + owner)
    mappings = {}
    for line in mapping_path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match:
            mappings[match[1]] = match[2]
    for owner in sorted(expected_classes):
        name = java_name(owner)
        if mappings.get(name) != name:
            failures.append("Mapping lacks stable contract class: " + name)
    changed = sum(name != target for name, target in mappings.items())
    if not changed:
        failures.append("Mapping shows no renamed classes; expected a real R8 build")
    return {
        "passed": not failures,
        "native_methods_checked": native_count,
        "java_callbacks_checked": callback_count,
        "fields_checked": len(expected_fields),
        "stable_classes_checked": len(expected_classes),
        "classes_renamed_by_r8": changed,
        "reference_defined_classes": len(reference.classes),
        "candidate_defined_classes": len(candidate.classes),
        "failures": failures,
        "scope": "DEX definitions, signatures, binding flags and mapping; not runtime callback execution",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-apk", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = verify(Definitions.from_apk(args.baseline_apk), Definitions.from_apk(args.apk), args.mapping)
        result["apk_sha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["passed"] else 1
    except (OSError, ValueError, struct.error, IndexError, zipfile.BadZipFile) as error:
        print(json.dumps({"passed": False, "error": str(error)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
