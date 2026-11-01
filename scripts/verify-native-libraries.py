#!/usr/bin/env python3
"""Verify APK native libraries retain their runtime bytes after symbol stripping."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


def require(value, message):
    if not value:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class Elf:
    def __init__(self, raw):
        self.raw = raw
        require(len(raw) >= 52 and raw[:4] == b"\x7fELF", "Expected an ELF image")
        require(raw[4] in (1, 2) and raw[5] == 1, "Only little-endian ELF32/ELF64 is supported")
        self.bits = 64 if raw[4] == 2 else 32
        fmt = "<HHIQQQIHHHHHH" if self.bits == 64 else "<HHIIIIIHHHHHH"
        self.header = struct.unpack_from(fmt, raw, 16)
        (self.kind, self.machine, unused_version, self.entry, phoff, shoff, self.flags,
         ehsize, phentsize, phnum, shentsize, shnum, shstrndx) = self.header
        require(ehsize == (64 if self.bits == 64 else 52), "Unexpected ELF header size")
        require(shnum > 0 and shstrndx < shnum, "Extended or absent section tables are not supported")
        pfmt = "<IIQQQQQQ" if self.bits == 64 else "<IIIIIIII"
        sfmt = "<IIQQQQIIQQ" if self.bits == 64 else "<IIIIIIIIII"
        require(phentsize == struct.calcsize(pfmt) and shentsize == struct.calcsize(sfmt),
                "Unexpected ELF table entry size")
        self.programs = []
        for index in range(phnum):
            offset = phoff + index * phentsize
            self.slice(offset, phentsize)
            row = struct.unpack_from(pfmt, raw, offset)
            if self.bits == 64:
                typ, flags, off, va, pa, filesz, memsz, align = row
            else:
                typ, off, va, pa, filesz, memsz, flags, align = row
            self.slice(off, filesz)
            self.programs.append((typ, off, va, pa, filesz, memsz, flags, align))
        sections = []
        for index in range(shnum):
            offset = shoff + index * shentsize
            self.slice(offset, shentsize)
            row = struct.unpack_from(sfmt, raw, offset)
            section = dict(zip(("name_offset", "type", "flags", "address", "offset", "size",
                                "link", "info", "alignment", "entry_size"), row))
            if section["type"] != 8:
                self.slice(section["offset"], section["size"])
            sections.append(section)
        strings = sections[shstrndx]
        names = self.slice(strings["offset"], strings["size"])
        self.sections = {}
        for section in sections:
            index = section.pop("name_offset")
            require(index < len(names), "Invalid section name offset")
            end = names.find(b"\0", index)
            require(end >= 0, "Unterminated section name")
            name = names[index:end].decode("utf-8")
            require(name not in self.sections, "Duplicate section name")
            section["data"] = b"" if section["type"] == 8 else self.slice(section["offset"], section["size"])
            self.sections[name] = section

    def slice(self, offset, size):
        require(offset >= 0 and size >= 0 and offset + size <= len(self.raw), "ELF range is out of bounds")
        return self.raw[offset:offset + size]

    def normalized_load_bytes(self, program):
        unused_type, offset, unused_va, unused_pa, size, unused_memsz, unused_flags, unused_align = program
        data = bytearray(self.slice(offset, size))
        # llvm-strip moves/recounts only the section table. These fields are not loaded code.
        ranges = ((40, 48), (60, 64)) if self.bits == 64 else ((32, 36), (48, 52))
        for start, end in ranges:
            for absolute in range(max(start, offset), min(end, offset + size)):
                data[absolute - offset] = 0
        return bytes(data)


def removable(name):
    return name.startswith((".debug_", ".zdebug_")) or name in (
        ".symtab", ".strtab", ".gdb_index", ".gnu_debuglink", ".gnu_debugdata",
    )


def compare_library(before, after):
    left, right = Elf(before), Elf(after)
    require(left.raw[:16] == right.raw[:16], "ELF identity or ABI changed")
    require(all(a == b for index, (a, b) in enumerate(zip(left.header, right.header))
                if index not in (5, 11, 12)), "Runtime ELF header fields changed")
    require(left.programs == right.programs, "Program layout, protections or alignment changed")
    loads = 0
    for program in left.programs:
        if program[0] == 1:
            require(left.normalized_load_bytes(program) == right.normalized_load_bytes(program),
                    "Loaded segment bytes changed beyond section-table metadata")
            loads += 1
    require(loads > 0, "ELF contains no loadable segment")
    removed = []
    for name, section in left.sections.items():
        other = right.sections.get(name)
        if name == ".shstrtab":
            continue
        if other is None:
            require(not section["flags"] & 2 and removable(name),
                    "Stripping removed a runtime or unrecognized section: " + name)
            removed.append(name)
            continue
        fields = ("type", "flags", "address", "size", "alignment", "entry_size", "data")
        if removable(name):
            require(not other["flags"] & 2, "Debug section became runtime allocated")
        else:
            require(all(section[field] == other[field] for field in fields),
                    "Retained section changed: " + name)
    require(set(right.sections).issubset(left.sections), "Unexpected native section added")
    require(not any(removable(name) for name in right.sections), "Debug/static symbol tables remain")
    runtime = sorted(name for name, section in right.sections.items() if section["flags"] & 2)
    # Exact allocated-section bytes include dynsym/dynstr, relocation tables, dynamic dependency
    # declarations, executable code, readonly data, unwind data, JNI exports and GNU build notes.
    return {
        "beforeBytes": len(before), "afterBytes": len(after), "savedBytes": len(before) - len(after),
        "beforeSha256": sha256(before), "afterSha256": sha256(after),
        "bits": left.bits, "machine": left.machine, "loadSegmentsVerified": loads,
        "runtimeSectionsVerified": runtime, "removedNonRuntimeSections": removed,
    }


def libraries(path, abi):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "Archive CRC check failed")
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate archive entries")
        result = {}
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) == 3 and parts[0] in ("lib", "jni") and parts[2].endswith(".so"):
                if abi and parts[1] != abi:
                    continue
                key = "/".join(parts[1:])
                require(key not in result, "Duplicate ABI/library")
                result[key] = (archive.read(entry), entry.compress_type, entry.compress_size)
        require(bool(result), "No matching native libraries")
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True, help="Original APK or upstream AAR")
    parser.add_argument("--candidate", type=Path, required=True, help="Built APK")
    parser.add_argument("--abi", choices=("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    baseline, candidate = libraries(args.baseline, args.abi), libraries(args.candidate, args.abi)
    require(baseline.keys() == candidate.keys(), "Native library/ABI coverage changed")
    rows = []
    for name, (old, old_method, old_compressed) in baseline.items():
        new, new_method, new_compressed = candidate[name]
        # An AAR is an upstream input, not an APK packaging baseline.
        if args.baseline.suffix.lower() == ".apk":
            require(old_method == new_method, "Native library compression/packaging mode changed")
        result = compare_library(old, new)
        result.update({"library": name, "apkCompressionMethod": new_method,
                       "beforeCompressedBytes": old_compressed, "afterCompressedBytes": new_compressed})
        rows.append(result)
    report = {
        "baseline": args.baseline.name, "candidate": args.candidate.name,
        "baselineSha256": sha256(args.baseline.read_bytes()),
        "candidateSha256": sha256(args.candidate.read_bytes()),
        "libraries": rows,
        "beforeNativeBytes": sum(row["beforeBytes"] for row in rows),
        "afterNativeBytes": sum(row["afterBytes"] for row in rows),
        "savedNativeBytes": sum(row["savedBytes"] for row in rows),
        "runtimeBytesPreserved": True,
        "limitations": [
            "Static equivalence of loaded native bytes is not a device playback/seek benchmark.",
            "Section-table location/count fields are excluded from loaded-segment comparisons.",
        ],
    }
    if args.output:
        require(not args.output.exists(), "Refusing to overwrite validation evidence")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, UnicodeError, zipfile.BadZipFile, struct.error) as error:
        print("Native verification failed: " + str(error), file=sys.stderr)
        sys.exit(1)
