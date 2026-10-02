#!/usr/bin/env python3
"""Inspect actual B2 debug APKs without installing or executing either package.

Uses existing SDK-36 aapt2, apkanalyzer, apksigner, zipalign and optionally dexdump.
All subprocesses use argument arrays and shell=False. Missing tools are never
installed. Source XML is not substituted for the compiled binary manifests.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "ownerDeathLab"
PACKAGE = "com.gccwebos.android.ipcqa.b2"
RUNNER = PACKAGE + ".OwnerDeathRunner"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SHARED = {
    "Wire.java": "68a03e92068cdd83ff3c933229bc19a477918851667195a2e5595801c3fd2e74",
    "CopyLedger.java": "44813e17a186109648efdd8aa7611d14eb91a8f4302c434cbb43d7277f53d87b",
    "RemoteCopyClient.java": "add91c6b992b08f98d7364e08f5f6cefc05fa392834e608d2510259a88d76a80",
}
B1_BASES = tuple("Lcom/gccwebos/android/ipcqa/" + name[:-5] for name in SHARED)
B2_PREFIX = "Lcom/gccwebos/android/ipcqa/b2/"
MAIN_CLASSES = (
    "B2Control", "DeathQaClient", "DeathRecoveryLedger", "DeathSessionGuard",
    "EndpointWatch", "OwnerDeathQaService", "WorkerDeathQaService",
)
TEST_CLASSES = ("OwnerDeathIpcTest", "OwnerDeathRunner")


class EvidenceFailure(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceFailure(message)


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def existing(path: Path, label: str) -> Path:
    path = path.resolve()
    require(path.is_file(), "Missing existing " + label + ": " + str(path))
    return path


def read_log(path: Path, label: str) -> str:
    return existing(path, label).read_text(encoding="utf-8", errors="replace")


def git_value(*arguments: str) -> str:
    git = shutil.which("git")
    require(git is not None, "Existing git executable is required for source identity")
    result = subprocess.run([git, *arguments], cwd=ROOT, shell=False,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    require(result.returncode == 0, "Source identity command failed: git " + " ".join(arguments))
    return result.stdout.strip()


def capture(command: list[str | Path], path: Path, evidence: dict) -> str:
    args = [str(part) for part in command]
    require(not Path(args[0]).suffix.lower() in (".bat", ".cmd"),
            "Batch wrappers are not invoked by this shell-free collector; use existing Linux CI tools")
    result = subprocess.run(args, cwd=ROOT, shell=False,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    path.write_bytes(result.stdout)
    stderr_path = path.with_name(path.stem + "-stderr" + path.suffix)
    stderr_path.write_bytes(result.stderr)
    evidence.setdefault("tool_invocations", []).append({
        "command": args, "exit_code": result.returncode,
        "stdout_log": path.name, "stderr_log": stderr_path.name,
        "stdout_sha256": digest_bytes(result.stdout), "stderr_sha256": digest_bytes(result.stderr),
    })
    require(result.returncode == 0, "Inspection command failed; see " + path.name)
    return result.stdout.decode("utf-8", errors="replace")


def verify_source_and_build(args: argparse.Namespace, output: Path) -> dict:
    commit = git_value("rev-parse", "HEAD")
    tree = git_value("rev-parse", "HEAD^{tree}")
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None and
            re.fullmatch(r"[0-9a-f]{40}", tree) is not None, "Invalid source Git identity")
    require(git_value("status", "--porcelain") == "", "Source checkout is not clean")
    commit_log = args.source_commit_log or output / "source-commit.log"
    tree_log = args.source_tree_log or output / "source-tree.log"
    require(read_log(commit_log, "source commit log").strip() == commit, "Build source commit log differs from Git HEAD")
    require(read_log(tree_log, "source tree log").strip() == tree, "Build source tree log differs from Git tree")
    build_log = args.build_log or output / "build.log"
    build = read_log(build_log, "actual build log")
    require(re.search(r"^BUILD SUCCESSFUL\b", build, re.M) is not None and
            "BUILD FAILED" not in build, "Actual build did not record success")
    require(re.search(r"^> Task :app:", build, re.M) is None, "Ordinary app task ran in B2 build-only scope")
    wanted = ("assembleDebug", "assembleDebugAndroidTest", "lintDebug")
    for task in wanted:
        require(re.search(r"^> Task :ownerDeathLab:" + task + r"(?:\s+(?:UP-TO-DATE|FROM-CACHE))?\s*$", build, re.M) is not None,
                "Actual B2 build task not recorded as completed: " + task)
    release = read_log(args.release_proof, "actual B2 task listing")
    require(re.search(r"^BUILD SUCCESSFUL\b", release, re.M) is not None and
            "BUILD FAILED" not in release, "Task listing did not record success")
    require(re.search(r"^> Task :ownerDeathLab:tasks(?:\s+UP-TO-DATE)?\s*$", release, re.M) is not None and
            re.search(r"^Tasks runnable from project ':ownerDeathLab'\s*$", release, re.M) is not None,
            "Release proof is not bound to the actual ownerDeathLab task listing")
    for task in wanted[:2]:
        require(re.search(r"^" + task + r"(?:\s+-|\s*$)", release, re.M) is not None,
                "B2 task listing lacks " + task)
    listed_tasks = re.findall(r"^\s*([A-Za-z][A-Za-z0-9:_-]*)(?:[ \t]+-.*|[ \t]*)$", release, re.M)
    released_tasks = [task for task in listed_tasks if "release" in task.lower()]
    require(not released_tasks, "Release task(s) exposed: " + ", ".join(released_tasks))
    build_source = (MODULE / "build.gradle.kts").read_text(encoding="utf-8")
    require('beforeVariants(selector().withBuildType("release")) { it.enable = false }' in build_source,
            "Source release exclusion is missing")
    shared_directory = ROOT / "copyBoundaryLab/src/main/java/com/gccwebos/android/ipcqa"
    generated = MODULE / "build/generated/sources/b1/java"
    expected_generated = {"com/gccwebos/android/ipcqa/" + name for name in SHARED}
    require(generated.is_dir(), "Actual generated shared B1 source directory is missing")
    actual_generated = {path.relative_to(generated).as_posix() for path in generated.rglob("*") if path.is_file()}
    require(actual_generated == expected_generated, "Generated B1 source set differs from the exact three-file allowlist")
    shared_proof = {}
    for name, expected in SHARED.items():
        original = existing(shared_directory / name, "B1 shared source")
        copy = existing(generated / "com/gccwebos/android/ipcqa" / name, "generated B1 source")
        source = original.read_bytes()
        require(digest_bytes(source.replace(b"\r\n", b"\n")) == expected, "Shared B1 source changed: " + name)
        require(copy.read_bytes() == source, "Generated B1 source is not an unchanged byte copy: " + name)
        shared_proof[name] = {"sha256_lf": expected, "actual_source_sha256": digest_bytes(source),
                              "generated_sha256": digest_file(copy)}
    checked = [ROOT / "settings.gradle.kts", MODULE / "build.gradle.kts", Path(__file__).resolve()]
    checked += sorted((MODULE / "src").rglob("*.java"))
    checked += sorted((MODULE / "src").rglob("AndroidManifest.xml"))
    return {
        "source_commit": commit, "source_tree": tree, "clean_checkout": True,
        "source_file_sha256": {path.relative_to(ROOT).as_posix(): digest_file(path) for path in checked},
        "unchanged_b1_sources": shared_proof,
        "build_log": {"path": str(build_log), "sha256": digest_file(build_log), "required_tasks": list(wanted)},
        "release_exclusion": {"status": "verified_task_listing_and_source", "path": str(args.release_proof),
                              "sha256": digest_file(args.release_proof), "release_tasks": []},
        "source_commit_log_sha256": digest_file(commit_log), "source_tree_log_sha256": digest_file(tree_log),
    }


def canonical(name: str | None, package: str) -> str:
    require(bool(name), "Compiled component has no name")
    return package + name if name.startswith(".") else (package + "." + name if "." not in name else name)


def verify_manifest(xml: str, badging: str, xmltree: str, role: str) -> dict:
    root = ET.fromstring(xml)
    expected_package = PACKAGE if role == "target" else PACKAGE + ".test"
    require(root.tag == "manifest" and root.get("package") == expected_package, role + ": compiled manifest package mismatch")
    require(root.get(ANDROID + "compileSdkVersion") == "36", role + ": actual compiled SDK differs from 36")
    package_line = re.search(r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", badging, re.M)
    require(package_line is not None and package_line.group(1) == expected_package, role + ": badging package mismatch")
    require(not any("sharedUser" in key for key in root.attrib), role + ": shared UID or label declared")
    forbidden = {"permission", "permission-group", "permission-tree", "activity", "activity-alias", "receiver", "provider"}
    for node in root.iter():
        tag = node.tag.rsplit("}", 1)[-1]
        require(tag not in forbidden and not tag.startswith("uses-permission"), role + ": forbidden compiled declaration: " + tag)
        require(node.get(ANDROID + "permission") is None and node.get(ANDROID + "foregroundServiceType") is None,
                role + ": permission-protected or foreground component declared")
    require(re.search(r"^uses-permission[^:]*:", badging, re.M) is None, role + ": badging declares a permission")
    require(re.search(r"^launchable-activity:", badging, re.M) is None, role + ": launcher declared")
    require(not re.search(r"E: (?:uses-permission[^ ]*|permission(?:-group|-tree)?|activity(?:-alias)?|receiver|provider)\b", xmltree),
            role + ": aapt2 binary tree contains a forbidden declaration")
    sdk = root.find("uses-sdk")
    require(sdk is not None and sdk.get(ANDROID + "minSdkVersion") == "26" and
            sdk.get(ANDROID + "targetSdkVersion") == "36", role + ": compiled SDK levels differ")
    require(re.search(r"^sdkVersion:'26'\s*$", badging, re.M) is not None and
            re.search(r"^targetSdkVersion:'36'\s*$", badging, re.M) is not None, role + ": badging SDK levels differ")
    apps = root.findall("application")
    require(len(apps) == 1, role + ": expected one compiled application")
    app = apps[0]
    require(app.get(ANDROID + "allowBackup") == "false" and app.get(ANDROID + "usesCleartextTraffic") == "false",
            role + ": compiled backup or cleartext flags differ")
    require(app.get(ANDROID + "debuggable") == "true", role + ": APK is not the debug candidate")
    for key in ("name", "appComponentFactory", "backupAgent", "manageSpaceActivity", "networkSecurityConfig", "process"):
        require(app.get(ANDROID + key) is None, role + ": unexpected application hook: " + key)
    expected_services = {
        PACKAGE + ".OwnerDeathQaService": ":ownerqa",
        PACKAGE + ".WorkerDeathQaService": ":workerqa",
    } if role == "target" else {}
    services = app.findall("service")
    require(len(services) == len(expected_services), role + ": unexpected compiled service count")
    require(len(re.findall(r"E: service\b", xmltree)) == len(expected_services), role + ": binary service count differs")
    component_proof = []
    seen = set()
    for service in services:
        name = canonical(service.get(ANDROID + "name"), expected_package)
        require(name in expected_services and name not in seen, role + ": unexpected compiled service: " + name)
        seen.add(name)
        require(service.get(ANDROID + "process") == expected_services[name] and
                service.get(ANDROID + "exported") == "false" and
                service.get(ANDROID + "stopWithTask") == "false", role + ": service scope/export differs: " + name)
        require(service.get(ANDROID + "isolatedProcess") in (None, "false") and
                service.get(ANDROID + "externalService") in (None, "false"), role + ": service is not ordinary: " + name)
        require(len(service) == 0, role + ": service contains filters or metadata: " + name)
        component_proof.append({"name": name, "process": expected_services[name], "exported": False,
                                "stop_with_task": False, "ordinary_service": True})
    for child in app:
        require(child.tag in {"service", "uses-library"}, role + ": unexpected application element: " + child.tag)
        if child.tag == "uses-library":
            require(role == "test" and child.get(ANDROID + "name") in {
                "android.test.runner", "android.test.base", "android.test.mock"}, role + ": unexpected library declaration")
    instrumentation = root.findall("instrumentation")
    if role == "target":
        require(not instrumentation, "target: unexpected instrumentation")
        require(root.get(ANDROID + "versionCode") == "1" and root.get(ANDROID + "versionName") == "0.1-e2b-b2-lab" and
                package_line.group(2) == "1" and package_line.group(3) == "0.1-e2b-b2-lab", "target: compiled version differs")
    else:
        require(len(instrumentation) == 1, "test: expected one instrumentation")
        instrument = instrumentation[0]
        require(canonical(instrument.get(ANDROID + "name"), expected_package) == RUNNER and
                instrument.get(ANDROID + "targetPackage") == PACKAGE, "test: runner or target package differs")
        require(instrument.get(ANDROID + "targetProcesses") is None, "test: broad instrumentation processes declared")
        require(instrument.get(ANDROID + "functionalTest") in (None, "false") and
                instrument.get(ANDROID + "handleProfiling") in (None, "false"), "test: unexpected instrumentation mode")
    allowed_root_tags = {"uses-sdk", "application"} | ({"instrumentation"} if role == "test" else set())
    require(all(node.tag in allowed_root_tags for node in root), role + ": unexpected root manifest element")
    return {"package": expected_package, "min_sdk": 26, "target_sdk": 36, "compile_sdk": 36,
            "compile_sdk_codename": root.get(ANDROID + "compileSdkVersionCodename"),
            "platform_build_version_code": root.get("platformBuildVersionCode"),
            "platform_build_version_name": root.get("platformBuildVersionName"),
            "version_code": package_line.group(2), "version_name": package_line.group(3),
            "permissions": [], "custom_permissions": [], "services": component_proof,
            "allow_backup": False, "uses_cleartext_traffic": False, "debuggable": True,
            "instrumentation": {"runner": RUNNER, "target_package": PACKAGE} if role == "test" else None}


def verify_signature(signature: str, role: str) -> dict:
    matches = re.findall(r"^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", signature, re.M)
    require(len(matches) == 1 and matches[0][0] == "1", role + ": expected exactly one signing certificate")
    signers = re.search(r"^Number of signers:\s*(\d+)\s*$", signature, re.M)
    require(signers is not None and signers.group(1) == "1", role + ": expected exactly one signer")
    schemes = {}
    for line in signature.splitlines():
        match = re.match(r"Verified using (.+?):\s*(true|false)\s*$", line)
        if match:
            schemes[match.group(1)] = match.group(2) == "true"
    require(any(name.startswith("v2 scheme") and passed for name, passed in schemes.items()),
            role + ": APK Signature Scheme v2 did not verify")
    return {"signer_count": 1, "certificate_sha256": matches[0][1].lower(), "verified_schemes": schemes}


def uleb(data: bytes, position: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 35, 7):
        require(position < len(data), "Truncated DEX ULEB128")
        octet = data[position]
        position += 1
        value |= (octet & 0x7f) << shift
        if octet < 0x80:
            require(value <= 0xffffffff, "DEX ULEB128 exceeds uint32")
            return value, position
    raise EvidenceFailure("Invalid DEX ULEB128")


def dex_metadata(data: bytes) -> dict:
    require(len(data) >= 112 and data[:4] == b"dex\n" and data[4:8] in
            (b"035\0", b"037\0", b"038\0", b"039\0", b"040\0"), "Unsupported or malformed standard DEX")
    require(struct.unpack_from("<III", data, 32) == (len(data), 112, 0x12345678), "DEX size/header/endian mismatch")
    require(struct.unpack_from("<I", data, 8)[0] == zlib.adler32(data[12:]) & 0xffffffff and
            data[12:32] == hashlib.sha1(data[32:]).digest(), "DEX checksum/signature mismatch")

    def table(offset: int, width: int) -> tuple[int, int]:
        count, start = struct.unpack_from("<II", data, offset)
        require(start + count * width <= len(data) and (count == 0 or start >= 112), "DEX table bounds mismatch")
        return count, start

    string_count, string_start = table(56, 4)
    type_count, type_start = table(64, 4)
    method_count, method_start = table(88, 8)
    class_count, class_start = table(96, 32)

    def string(index: int) -> str:
        require(index < string_count, "DEX string index out of bounds")
        position = struct.unpack_from("<I", data, string_start + index * 4)[0]
        length, position = uleb(data, position)
        end = data.find(b"\0", position)
        require(end >= position and end - position <= 4096, "DEX descriptor/name string bound exceeded")
        raw = data[position:end]
        require(len(raw) == length, "Non-ASCII DEX descriptor/name is outside this synthetic lab contract")
        try:
            return raw.decode("ascii")
        except UnicodeDecodeError as error:
            raise EvidenceFailure("Non-ASCII DEX descriptor/name") from error

    types = [string(struct.unpack_from("<I", data, type_start + i * 4)[0]) for i in range(type_count)]
    classes = []
    native = []
    for i in range(class_count):
        class_index, _, _, _, _, _, class_data, _ = struct.unpack_from("<8I", data, class_start + i * 32)
        require(class_index < len(types), "DEX class type index out of bounds")
        descriptor = types[class_index]
        classes.append(descriptor)
        if class_data:
            sizes = []
            position = class_data
            for _ in range(4):
                size, position = uleb(data, position)
                sizes.append(size)
            require(sum(sizes) <= 131072, "DEX class data count is invalid")
            for _ in range(sizes[0] + sizes[1]):
                _, position = uleb(data, position)
                _, position = uleb(data, position)
            for _ in range(sizes[2] + sizes[3]):
                _, position = uleb(data, position)
                access, position = uleb(data, position)
                _, position = uleb(data, position)
                if access & 0x100:
                    native.append(descriptor)
    loader_refs = []
    for i in range(method_count):
        owner, _, name_index = struct.unpack_from("<HHI", data, method_start + i * 8)
        require(owner < len(types), "DEX method class index out of bounds")
        name = string(name_index)
        if (types[owner] in ("Ljava/lang/System;", "Ljava/lang/Runtime;") and
                name in ("load", "loadLibrary", "exec")):
            loader_refs.append(types[owner] + "->" + name)
    return {"version": data[4:7].decode("ascii"), "classes": sorted(set(classes)),
            "types": sorted(set(types)), "native_method_classes": sorted(set(native)),
            "native_or_shell_loader_references": sorted(set(loader_refs))}


def b1_type(descriptor: str) -> bool:
    return any(descriptor == base + ";" or descriptor.startswith(base + "$") for base in B1_BASES)


def b2_type(descriptor: str, names: tuple[str, ...]) -> bool:
    return any(descriptor == B2_PREFIX + name + ";" or descriptor.startswith(B2_PREFIX + name + "$")
               for name in names)


def inspect_dex(entries: dict[str, bytes], role: str, tool: Path | None, output: Path, evidence: dict) -> dict:
    classes, types, native, loaders = set(), set(), set(), set()
    files = []
    for name, data in sorted(entries.items()):
        metadata = dex_metadata(data)
        classes.update(metadata["classes"])
        types.update(metadata["types"])
        native.update(metadata["native_method_classes"])
        loaders.update(metadata["native_or_shell_loader_references"])
        item = {"name": name, "sha256": digest_bytes(data), "bytes": len(data), "version": metadata["version"]}
        if tool is not None:
            with tempfile.TemporaryDirectory(prefix="gcc-b2-dex-") as temp:
                path = Path(temp) / name
                path.write_bytes(data)
                dump = capture([tool, "-d", path], output / (role + "-" + name + "-dexdump.txt"), evidence)
            dumped_classes = set(re.findall(r"Class descriptor\s*:\s*'([^']+)'", dump))
            require(dumped_classes == set(metadata["classes"]), role + ": dexdump class definitions differ from actual DEX table")
            item["dexdump"] = "verified_definition_agreement"
        else:
            item["dexdump"] = "NOT_VERIFIED_TOOL_MISSING"
        files.append(item)
    allowed_definitions = MAIN_CLASSES if role == "target" else TEST_CLASSES
    allowed_references = MAIN_CLASSES if role == "target" else MAIN_CLASSES + TEST_CLASSES
    unexpected_classes = sorted(value for value in classes if not b2_type(value, allowed_definitions) and
                                not (role == "target" and b1_type(value)))
    unexpected_refs, network, shell_process = [], [], []
    for original in types:
        descriptor = original.lstrip("[")
        if not descriptor.startswith("L"):
            continue
        if descriptor.startswith(("Ljava/net/", "Ljavax/net/", "Landroid/net/")):
            network.append(original)
        if descriptor == "Ljava/lang/ProcessBuilder;":
            shell_process.append(original)
        if not (descriptor.startswith(("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/annotation/")) or
                b2_type(descriptor, allowed_references) or b1_type(descriptor)):
            unexpected_refs.append(original)
    wanted = {B2_PREFIX + name + ";" for name in allowed_definitions}
    if role == "target":
        wanted.update(base + ";" for base in B1_BASES)
    missing_classes = sorted(wanted - classes)
    failures = {"unexpected_defined_classes": unexpected_classes, "unexpected_type_references": sorted(unexpected_refs),
                "network_type_references": sorted(network), "native_method_classes": sorted(native),
                "shell_process_type_references": sorted(shell_process),
                "native_or_shell_loader_references": sorted(loaders), "missing_required_classes": missing_classes}
    bad = any(failures.values())
    return {"status": "FAILED" if bad else ("VERIFIED" if tool else "DEX_TABLE_VERIFIED_DEXDUMP_PENDING"),
            "defined_classes": sorted(classes), "type_reference_count": len(types), "files": files, **failures,
            "meaning": "Static classes and type/method references only; does not prove runtime network behavior or OS cleanup"}


def inspect_apk(role: str, apk: Path, tools: dict, output: Path, evidence: dict) -> dict:
    initial_sha = digest_file(apk)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and archive.testzip() is None, role + ": duplicate or corrupt ZIP entries")
        require("AndroidManifest.xml" in names and "classes.dex" in names, role + ": incomplete actual APK")
        libraries = sorted(name for name in names if name.startswith("lib/") or name.lower().endswith(".so"))
        require(not libraries, role + ": native library present: " + ", ".join(libraries))
        require(not any(re.search(r"(?:mihomo|gccmihomo|sing-box|v2ray)", name, re.I) for name in names), role + ": engine asset present")
        manifest = archive.read("AndroidManifest.xml")
        require(manifest[:4] == b"\x03\x00\x08\x00", role + ": manifest is not compiled Android binary XML")
        dex_names = [name for name in names if name.endswith(".dex")]
        require(all(re.fullmatch(r"classes(?:[1-9][0-9]*)?\.dex", name) is not None for name in dex_names), role + ": nonstandard embedded DEX")
        dex_entries = {name: archive.read(name) for name in dex_names}
    xml = capture([tools["apkanalyzer"], "manifest", "print", apk], output / (role + "-manifest.xml"), evidence)
    badging = capture([tools["aapt2"], "dump", "badging", apk], output / (role + "-badging.txt"), evidence)
    xmltree = capture([tools["aapt2"], "dump", "xmltree", apk, "--file", "AndroidManifest.xml"], output / (role + "-manifest-tree.txt"), evidence)
    identity = verify_manifest(xml, badging, xmltree, role)
    signature = capture([tools["apksigner"], "verify", "--verbose", "--print-certs", apk], output / (role + "-signature.txt"), evidence)
    signing = verify_signature(signature, role)
    capture([tools["zipalign"], "-c", "-P", "16", "-v", "4", apk], output / (role + "-zipalign.txt"), evidence)
    dex = inspect_dex(dex_entries, role, tools["dexdump"], output, evidence)
    require(digest_file(apk) == initial_sha, role + ": APK changed during inspection")
    return {"role": role, "path": apk.relative_to(ROOT).as_posix(), "bytes": apk.stat().st_size,
            "sha256": initial_sha, "compiled_manifest_sha256": digest_bytes(manifest),
            "decoded_manifest_sha256": digest_file(output / (role + "-manifest.xml")),
            "manifest": identity, "signature": signing, "zip_alignment": "verified_4_byte_and_16KiB_page_check",
            "native_libraries": [], "dex": dex}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk", type=Path, help="Existing SDK with build-tools/36.0.0")
    parser.add_argument("output", type=Path, help="Existing build evidence directory")
    parser.add_argument("--release-proof", type=Path, required=True, help="Actual ownerDeathLab tasks --all stdout")
    parser.add_argument("--build-log", type=Path, help="Default: OUTPUT/build.log")
    parser.add_argument("--source-commit-log", type=Path, help="Default: OUTPUT/source-commit.log")
    parser.add_argument("--source-tree-log", type=Path, help="Default: OUTPUT/source-tree.log")
    parser.add_argument("--apkanalyzer", type=Path, help="Explicit existing executable; default SDK cmdline-tools/latest/bin/apkanalyzer")
    parser.add_argument("--require-dexdump", action="store_true", help="Missing existing dexdump is a hard blocker")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    evidence = {"schema": 1, "kind": "B2 build-only APK candidate", "status": "FAILED",
                "created_at_utc": datetime.now(timezone.utc).isoformat(), "apks": [],
                "android_execution": "NOT_RUN", "installations": 0, "process_exits": 0,
                "not_run": ["APK execution", "instrumentation", "Binder IPC", "process death", "VPN", "network"]}
    try:
        evidence["source"] = verify_source_and_build(args, output)
        build_tools = args.sdk.resolve() / "build-tools/36.0.0"
        tools = {name: existing(build_tools / name, "SDK-36 " + name) for name in ("aapt2", "apksigner", "zipalign")}
        tools["apkanalyzer"] = existing(args.apkanalyzer or args.sdk / "cmdline-tools/latest/bin/apkanalyzer", "apkanalyzer")
        dump = build_tools / "dexdump"
        tools["dexdump"] = existing(dump, "SDK-36 dexdump") if dump.is_file() else None
        require(tools["dexdump"] is not None or not args.require_dexdump, "Existing dexdump missing; no tool is installed")
        evidence["existing_sdk_tools"] = {name: {"path": str(path), "sha256": digest_file(path)} if path else None for name, path in tools.items()}
        candidates = {
            "target": MODULE / "build/outputs/apk/debug/ownerDeathLab-debug.apk",
            "test": MODULE / "build/outputs/apk/androidTest/debug/ownerDeathLab-debug-androidTest.apk",
        }
        for role, apk in candidates.items():
            existing(apk, role + " actual APK")
        actual = {path.resolve() for path in (MODULE / "build/outputs/apk").rglob("*") if path.is_file() and path.suffix.lower() == ".apk"}
        require(actual == {path.resolve() for path in candidates.values()}, "APK output tree must contain exactly the two B2 debug APKs, no release or extra package")
        for role, apk in candidates.items():
            evidence["apks"].append(inspect_apk(role, apk, tools, output, evidence))
        certificates = [apk["signature"]["certificate_sha256"] for apk in evidence["apks"]]
        require(len(certificates) == 2 and certificates[0] == certificates[1], "Target and test signer certificates differ")
        failures = []
        for apk in evidence["apks"]:
            if apk["dex"]["status"] == "FAILED":
                for key in ("unexpected_defined_classes", "unexpected_type_references", "network_type_references",
                            "shell_process_type_references", "native_method_classes",
                            "native_or_shell_loader_references", "missing_required_classes"):
                    values = apk["dex"][key]
                    if values:
                        failures.append(apk["role"] + " " + key + ": " + ", ".join(values[:40]) +
                                        (" [" + str(len(values)) + " total]" if len(values) > 40 else ""))
        require(not failures, "Actual DEX gate failed: " + " | ".join(failures))
        require(git_value("rev-parse", "HEAD") == evidence["source"]["source_commit"] and
                git_value("rev-parse", "HEAD^{tree}") == evidence["source"]["source_tree"] and
                git_value("status", "--porcelain") == "", "Source identity or cleanliness changed during collection")
        for name, expected in evidence["source"]["source_file_sha256"].items():
            require(digest_file(ROOT / name) == expected, "Source changed during collection: " + name)
        evidence["status"] = "VERIFIED_BUILD_ONLY" if tools["dexdump"] else "MANIFEST_SIGNATURE_ALIGNMENT_VERIFIED_DEXDUMP_PENDING"
        evidence["single_signer_certificate_sha256"] = certificates[0]
        print("B2 actual APK manifest/signature/alignment/static DEX gates: " + evidence["status"] + "; Android execution NOT_RUN")
        result = 0
    except (EvidenceFailure, OSError, ET.ParseError, zipfile.BadZipFile, struct.error) as failure:
        evidence["failure"] = str(failure)
        print("FAIL B2 build-only evidence: " + str(failure), file=sys.stderr)
        result = 1
    finally:
        (output / "candidate.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
