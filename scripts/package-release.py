#!/usr/bin/env python3
"""Package the two verified ARM APKs from a committed main; never build or publish."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.mediamaster.app.preview"
CERTIFICATE = "3f369c08ebd862e0ebd6925244a58a3a8dd9a856f8e95f0094124eaa50527824"
LOCAL_ONLY = {"AGENTS.md", "docs/REA_SHARED_TOOLS.md"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(*args, raw_input=None):
    result = subprocess.run(
        [str(arg) for arg in args], cwd=ROOT, input=raw_input,
        capture_output=True, check=True, timeout=120,
    )
    return result.stdout.decode("utf-8", errors="strict")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_source_entry(name):
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
            "Unsafe source archive path")
    require(name not in LOCAL_ONLY, "Local instructions cannot enter the source archive")
    require(not any(part in (".git", ".tools", "dist", "build", ".gradle", ".idea")
                    for part in path.parts), "Source includes generated or local files")
    require(path.suffix.lower() not in (
        ".apk", ".apks", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key",
        ".mp4", ".mkv", ".avi", ".webm", ".mov", ".flv", ".wav", ".mp3", ".zip", ".7z",
    ) and path.name not in ("local.properties", "signing.properties")
            and not path.name.startswith(".env"), "Source includes private or packaged artifacts")


def source(commit):
    require(re.fullmatch(r"[a-f0-9]{40}", commit), "A full source commit is required")
    require(command("git", "rev-parse", "HEAD").strip() == commit
            and command("git", "branch", "--show-current").strip() == "main",
            "Packaging requires the selected committed main")
    require(not command("git", "diff", "HEAD", "--name-only").strip(),
            "Tracked source or index differs from HEAD")
    untracked = command("git", "ls-files", "--others", "--exclude-standard", "-z").split("\0")
    require(not any(name.startswith(("app/", "scripts/", "docs/")) and name not in LOCAL_ONLY
                    for name in untracked), "Uncommitted source, tests or documentation")
    files = {}
    for row in filter(None, command("git", "ls-tree", "-rz", commit).split("\0")):
        metadata, name = row.split("\t", 1)
        mode, kind, object_id = metadata.split()
        require(mode in ("100644", "100755") and kind == "blob",
                "Source includes a symlink or submodule")
        safe_source_entry(name)
        files[name] = object_id
    gradle = command("git", "show", commit + ":app/build.gradle")
    version = re.search(r"versionName\s+'([^']+)'", gradle).group(1)
    require(re.fullmatch(r"\d+\.\d+\.\d+", version), "Only a stable version can be packaged")
    code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
    minimum = int(re.search(r"minSdk\s+(\d+)", gradle).group(1))
    target = int(re.search(r"targetSdk\s+(\d+)", gradle).group(1))
    return files, gradle, version, code, minimum, target


def tool_paths(args):
    sdk = args.sdk or (Path(os.environ["ANDROID_HOME"]) if os.environ.get("ANDROID_HOME")
                       else ROOT / ".tools/android-sdk")
    candidates = [path for path in (sdk / "build-tools").iterdir()
                  if path.is_dir() and re.fullmatch(r"\d+\.\d+\.\d+", path.name)]
    require(bool(candidates), "Android build tools are required")
    build_tools = max(candidates, key=lambda path: tuple(map(int, path.name.split("."))))
    java = args.java
    if java is None:
        java_home = os.environ.get("JAVA_HOME")
        local = sorted((ROOT / ".tools").glob("jdk-17*"), reverse=True)
        require(bool(java_home or local), "Set JAVA_HOME or pass --java")
        java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java") if java_home else local[0] / "bin/java.exe"
    aapt = build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
    return java, aapt, build_tools / "lib/apksigner.jar"


def verify_apk(path, expected_hash, abi, version, code, minimum, target, tools):
    require(path.is_file() and not path.is_symlink(), "APK must be a regular file")
    require(re.fullmatch(r"[a-f0-9]{64}", expected_hash) and digest(path) == expected_hash,
            "APK differs from the explicitly selected validation hash")
    java, aapt, signer = tools
    badging = command(aapt, "dump", "badging", path.resolve())
    require(f"package: name='{PACKAGE}' versionCode='{code}' versionName='{version}'" in badging
            and "application-label:'映序'" in badging
            and f"sdkVersion:'{minimum}'" in badging and f"targetSdkVersion:'{target}'" in badging,
            "APK identity, version or SDK differs from the release source")
    signing = command(java, "-jar", signer, "verify", "--verbose", "--print-certs", path.resolve())
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", signing)
    require(len(certificates) == 1 and certificates[0].lower() == CERTIFICATE,
            "APK signing identity differs from the installed release family")
    shared = {}
    native_count = 0
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "APK CRC validation failed")
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entries")
        actual_abis = set()
        for item in archive.infolist():
            if item.is_dir():
                continue
            parts = PurePosixPath(item.filename).parts
            if parts and parts[0] == "lib":
                require(len(parts) == 3 and parts[2].endswith(".so"), "Unexpected native library entry")
                actual_abis.add(parts[1])
                with archive.open(item) as stream:
                    elf = stream.read(20)
                elf_class, machine = (2, 183) if abi == "arm64-v8a" else (1, 40)
                require(len(elf) == 20 and elf[:4] == b"\x7fELF" and elf[4] == elf_class
                        and elf[5] == 1 and int.from_bytes(elf[18:20], "little") == machine,
                        "Native library is not the selected ARM architecture")
                native_count += 1
            if re.fullmatch(r"classes\d*\.dex", item.filename) or item.filename == "resources.arsc" \
                    or item.filename.startswith(("res/", "assets/")):
                shared[item.filename] = hashlib.sha256(archive.read(item)).hexdigest()
        require(actual_abis == {abi} and native_count > 0,
                "Official APK must contain exactly one selected ARM ABI; universal/x86 is forbidden")
        require("classes.dex" in shared and "resources.arsc" in shared, "APK lacks code or resources")
    return shared, native_count


def verify_archive(destination, commit, version, expected_files, gradle):
    prefix = f"MediaMaster-{version}/"
    hash_type = command("git", "rev-parse", "--show-object-format").strip()
    require(hash_type in ("sha1", "sha256"), "Unsupported Git object format")
    found = set()
    with zipfile.ZipFile(destination) as archive:
        require(archive.testzip() is None, "Source ZIP CRC validation failed")
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate source ZIP entries")
        require(archive.read(prefix + "app/build.gradle").decode("utf-8") == gradle,
                "Archived version differs from the selected source")
        for entry in archive.infolist():
            require(entry.filename.startswith(prefix), "Unexpected source archive prefix")
            name = entry.filename[len(prefix):]
            if not name:
                continue
            safe_source_entry(name)
            if entry.is_dir():
                continue
            found.add(name)
            raw = archive.read(entry)
            blob = hashlib.new(hash_type, b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()
            if expected_files.get(name) != blob:
                # On Windows, git archive may honor committed end-of-line attributes.
                blob = command("git", "hash-object", "--stdin", "--path=" + name, raw_input=raw).strip()
            require(expected_files.get(name) == blob, "Archived file differs from the exact commit")
        require(found == set(expected_files), "Source archive does not cover the exact committed tree")


def archive_source(destination, commit, version, expected_files, gradle):
    command("git", "archive", "--format=zip", "--prefix=" + f"MediaMaster-{version}/",
            "--output=" + str(destination), commit)
    verify_archive(destination, commit, version, expected_files, gradle)


def verify_distribution(directory, commit, tools=None):
    """Read-only publication guard for the exact six-asset package; returns its SHA-256 mapping."""
    files, gradle, version, code, minimum, target = source(commit)
    if tools is None:
        tools = tool_paths(argparse.Namespace(sdk=None, java=None))
    directory = Path(directory).resolve()
    require(directory.is_relative_to(ROOT / "dist") and directory != ROOT / "dist",
            "Distribution must be inside dist")
    names = [f"MediaMaster-{version}-arm64.apk", f"MediaMaster-{version}-arm32.apk",
             f"MediaMaster-{version}-source.zip", "INSTALL.txt", "VERIFICATION.txt", "SHA256SUMS.txt"]
    require({path.name for path in directory.iterdir()} == set(names)
            and all((directory / name).is_file() and not (directory / name).is_symlink() for name in names),
            "Official distribution requires exactly six regular assets")
    hashes = {name: digest(directory / name) for name in names}
    require((directory / "SHA256SUMS.txt").read_text(encoding="ascii")
            == "".join(f"{hashes[name]}  {name}\n" for name in names[:-1]),
            "Distribution checksum manifest differs")
    shared = []
    for name, abi in zip(names[:2], ("arm64-v8a", "armeabi-v7a")):
        result, unused = verify_apk(directory / name, hashes[name], abi, version, code, minimum, target, tools)
        shared.append(result)
    require(shared[0] == shared[1], "ARM APK code, resources or artwork differ")
    verify_archive(directory / names[2], commit, version, files, gradle)
    require((directory / "INSTALL.txt").read_text(encoding="utf-8")
            == command("git", "show", commit + ":安装与使用.txt"), "Installation text differs from source")
    verification = (directory / "VERIFICATION.txt").read_text(encoding="utf-8")
    require(all(value in verification for value in (commit, hashes[names[0]], hashes[names[1]], CERTIFICATE)),
            "Verification record differs from the selected source or APKs")
    return hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--arm64", type=Path, required=True)
    parser.add_argument("--arm32", type=Path, required=True)
    parser.add_argument("--expected-arm64-sha256", required=True)
    parser.add_argument("--expected-arm32-sha256", required=True)
    parser.add_argument("--verification", type=Path, required=True,
                        help="UTF-8 notes describing only checks actually performed on these binaries")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--java", type=Path)
    args = parser.parse_args()
    files, gradle, version, code, minimum, target = source(args.source_commit)
    tools = tool_paths(args)
    arm64_shared, arm64_count = verify_apk(args.arm64, args.expected_arm64_sha256, "arm64-v8a",
                                          version, code, minimum, target, tools)
    arm32_shared, arm32_count = verify_apk(args.arm32, args.expected_arm32_sha256, "armeabi-v7a",
                                          version, code, minimum, target, tools)
    require(arm64_shared == arm32_shared, "ARM APK code, resources or artwork differ")
    notes = args.verification.read_text(encoding="utf-8-sig").strip()
    require(0 < len(notes) <= 20000, "Current verification notes are required")
    require(not re.search(r"[A-Za-z]:[\\/]|content://|file://", notes),
            "Public verification notes include a local path or media identifier")
    install = command("git", "show", args.source_commit + ":安装与使用.txt")
    names = [f"MediaMaster-{version}-arm64.apk", f"MediaMaster-{version}-arm32.apk",
             f"MediaMaster-{version}-source.zip", "INSTALL.txt", "VERIFICATION.txt", "SHA256SUMS.txt"]
    require(all(name in install for name in names[:3]), "Committed installation text is stale")
    output = (args.output or ROOT / "dist" / f"MediaMaster-{version}").resolve()
    require(output.is_relative_to(ROOT / "dist") and output != ROOT / "dist" and not output.exists(),
            "Output must be a new directory inside dist")
    source(args.source_commit)
    output.mkdir(parents=True, exist_ok=False)
    for incoming, name, expected in ((args.arm64, names[0], args.expected_arm64_sha256),
                                      (args.arm32, names[1], args.expected_arm32_sha256)):
        shutil.copy2(incoming, output / name)
        require(digest(output / name) == expected, "Copied APK differs")
    archive_source(output / names[2], args.source_commit, version, files, gradle)
    (output / "INSTALL.txt").write_text(install, encoding="utf-8", newline="\n")
    verification = (
        f"映序 {version}\n源码提交：{args.source_commit}\n"
        f"ARM64 SHA-256：{args.expected_arm64_sha256}\n"
        f"ARM32 SHA-256：{args.expected_arm32_sha256}\n"
        f"签名证书 SHA-256：{CERTIFICATE}\n\n"
        f"ARM64仅含arm64-v8a原生库（{arm64_count}个）；ARM32仅含armeabi-v7a原生库（{arm32_count}个）。\n"
        "两个安装包的DEX、资源和素材一致；包名、版本和签名校验通过。\n"
        "源码包来自上述精确提交，CRC、文件内容及本机文件排除检查通过。\n\n" + notes + "\n"
    )
    (output / "VERIFICATION.txt").write_text(verification, encoding="utf-8", newline="\n")
    require({path.name for path in output.iterdir()} == set(names[:-1]), "Unexpected packaged assets")
    (output / "SHA256SUMS.txt").write_text(
        "".join(f"{digest(output / name)}  {name}\n" for name in names[:-1]), encoding="ascii", newline="\n")
    print(f"Packaged {version}: two ARM APKs and four support files. No universal or x86 APK selected.")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print("Packaging stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, AttributeError, subprocess.SubprocessError, zipfile.BadZipFile, UnicodeError):
        print("Packaging stopped: an input, tool or archive check failed; no existing output was replaced.", file=sys.stderr)
        sys.exit(1)
