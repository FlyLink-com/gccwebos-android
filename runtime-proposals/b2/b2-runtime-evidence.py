#!/usr/bin/env python3
"""Materialize and verify only the frozen B2 APKs; parse one native runner case.

No Android installation, execution, process control, network request or APK rebuild
is performed here. All inputs are local; Python's standard library is sufficient.
"""

import argparse
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

HEAD = ARTIFACT_ID = RUN_ID = ARTIFACT_NAME = None
ARCHIVE_SHA256 = ARCHIVE_BYTES = CERTIFICATE = None
MANIFEST_HASHES = {}
SOURCE_TREE = CANDIDATE_SHA = None
SOURCE_FILES = {}
CANDIDATE_PATH = ".build-output/owner-death-build/candidate.json"
PACKAGE = "com.gccwebos.android.ipcqa.b2"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = PACKAGE + ".OwnerDeathRunner"
TEST_CLASS = PACKAGE + ".OwnerDeathIpcTest"
METHODS = (
    "p7OwnerDeathKeepsWorkerAndQuarantine",
    "p8ExactEvidenceWaitsForCallAndDisposition",
    "p9WorkerDeathAndStaleEvidenceStayScoped",
)
APK_PATHS = (
    {"role": "target", "package": PACKAGE,
     "path": "ownerDeathLab/build/outputs/apk/debug/ownerDeathLab-debug.apk"},
    {"role": "test", "package": TEST_PACKAGE,
     "path": "ownerDeathLab/build/outputs/apk/androidTest/debug/ownerDeathLab-debug-androidTest.apk"},
)
APKS = ()


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def read_bounded(path, limit):
    with Path(path).open("rb") as handle:
        data = handle.read(limit + 1)
    require(len(data) <= limit, "INPUT_SIZE")
    return data


def load_freeze(path, require_authorization=False):
    """Only a separately reviewed freeze may select a saved build; no latest lookup."""
    global HEAD, ARTIFACT_ID, RUN_ID, ARTIFACT_NAME
    global ARCHIVE_SHA256, ARCHIVE_BYTES, CERTIFICATE, APKS, MANIFEST_HASHES
    global SOURCE_TREE, SOURCE_FILES, CANDIDATE_SHA
    value = json.loads(read_bounded(path, 65536), object_pairs_hook=unique_object)
    require(isinstance(value, dict) and value.get("repository") ==
            "gccwebos-com/gccwebos-android", "FREEZE_REPOSITORY")
    require(value.get("identity_frozen") is True, "BUILD_IDENTITY_NOT_FROZEN")
    budget = value.get("fault_budget")
    require(value.get("image") == "system-images;android-33;default;x86_64"
            and value.get("build_tools") == "36.0.0" and isinstance(budget, dict)
            and set(budget) == {"owner", "worker"}
            and all(type(budget[k]) is int for k in ("owner", "worker"))
            and budget == {"owner": 2, "worker": 1}, "FREEZE_RUNTIME_SCOPE")
    def hex_value(key, length):
        result = value.get(key)
        require(isinstance(result, str) and re.fullmatch(r"[0-9a-f]{%d}" % length, result),
                "FREEZE_" + key.upper())
        return result
    HEAD = hex_value("source_commit", 40)
    SOURCE_TREE = hex_value("source_tree", 40)
    require(value.get("source_candidate") == "8042ab7", "FREEZE_SOURCE_CANDIDATE")
    ARTIFACT_ID, RUN_ID = value.get("artifact_id"), value.get("build_run_id")
    require(type(ARTIFACT_ID) is int and ARTIFACT_ID > 0 and type(RUN_ID) is int
            and RUN_ID > 0, "FREEZE_BUILD_IDS")
    ARTIFACT_NAME = value.get("artifact_name")
    require(isinstance(ARTIFACT_NAME, str) and HEAD in ARTIFACT_NAME
            and re.fullmatch(r"[A-Za-z0-9._-]+", ARTIFACT_NAME), "FREEZE_ARTIFACT_NAME")
    ARCHIVE_SHA256 = hex_value("archive_sha256", 64)
    ARCHIVE_BYTES = value.get("archive_bytes")
    require(type(ARCHIVE_BYTES) is int and 0 < ARCHIVE_BYTES <= 32 * 1024 * 1024,
            "FREEZE_ARCHIVE_SIZE")
    CERTIFICATE = hex_value("certificate_sha256", 64)
    specs = value.get("apks")
    require(isinstance(specs, list) and len(specs) == 2, "FREEZE_APKS")
    for spec, fixed in zip(specs, APK_PATHS):
        require(isinstance(spec, dict) and all(spec.get(k) == v for k, v in fixed.items())
                and type(spec.get("bytes")) is int and 0 < spec["bytes"] <= ARCHIVE_BYTES
                and isinstance(spec.get("sha256"), str)
                and re.fullmatch(r"[0-9a-f]{64}", spec["sha256"]), "FREEZE_APK")
    APKS = tuple(dict(spec) for spec in specs)
    MANIFEST_HASHES = value.get("compiled_manifest_sha256")
    require(isinstance(MANIFEST_HASHES, dict) and set(MANIFEST_HASHES) == {"target", "test"}
            and all(isinstance(h, str) and re.fullmatch(r"[0-9a-f]{64}", h)
                    for h in MANIFEST_HASHES.values()), "FREEZE_COMPILED_MANIFESTS")
    candidate = value.get("build_candidate")
    require(isinstance(candidate, dict) and candidate.get("path") == CANDIDATE_PATH
            and isinstance(candidate.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", candidate["sha256"]), "FREEZE_BUILD_CANDIDATE")
    CANDIDATE_SHA = candidate["sha256"]
    SOURCE_FILES = value.get("source_file_sha256")
    required_java = {
        "ownerDeathLab/src/" + directory + "/java/com/gccwebos/android/ipcqa/b2/" + name + ".java"
        for directory, names in (("main", ("B2Control", "DeathQaClient", "DeathRecoveryLedger",
              "DeathSessionGuard", "EndpointWatch", "OwnerDeathQaService", "WorkerDeathQaService")),
              ("androidTest", ("OwnerDeathIpcTest", "OwnerDeathRunner"))) for name in names}
    require(isinstance(SOURCE_FILES, dict) and required_java.issubset(SOURCE_FILES)
            and all(isinstance(k, str) and not PurePosixPath(k).is_absolute()
                    and ".." not in PurePosixPath(k).parts and isinstance(v, str)
                    and re.fullmatch(r"[0-9a-f]{64}", v) for k, v in SOURCE_FILES.items()),
            "FREEZE_SOURCE_FILES")
    if require_authorization:
        require(value.get("runtime_authorized") is True
                and isinstance(value.get("approval_reference"), str)
                and value["approval_reference"].strip(), "RUNTIME_NOT_AUTHORIZED")
    return value


def reject_symlink_path(path):
    path = Path(path).absolute()
    for part in (path, *path.parents):
        require(not part.is_symlink(), "OUTPUT_SYMLINK")
    return path


def output_file(root, relative):
    root = reject_symlink_path(root)
    rel = PurePosixPath(relative)
    require(not rel.is_absolute() and all(x not in ("", ".", "..") for x in rel.parts),
            "OUTPUT_PATH")
    result = reject_symlink_path(root.joinpath(*rel.parts))
    require(result.is_relative_to(root), "OUTPUT_PATH")
    return result


def write_bytes(path, data):
    """Atomic replacement also prevents a failed parse from leaving stale PASS JSON."""
    path = reject_symlink_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(data)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def write_json(path, value):
    write_bytes(path, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def metadata(raw):
    value = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique_object)
    require(isinstance(value, dict), "METADATA_OBJECT")
    require(type(value.get("id")) is int and value["id"] == ARTIFACT_ID, "ARTIFACT_ID")
    require(value.get("name") == ARTIFACT_NAME, "ARTIFACT_NAME")
    require(value.get("expired") is False, "ARTIFACT_EXPIRED")
    require(type(value.get("size_in_bytes")) is int
            and value["size_in_bytes"] == ARCHIVE_BYTES, "ARTIFACT_SIZE")
    require(value.get("digest") == "sha256:" + ARCHIVE_SHA256, "ARTIFACT_DIGEST")
    run = value.get("workflow_run")
    require(isinstance(run, dict) and type(run.get("id")) is int
            and run["id"] == RUN_ID, "ARTIFACT_RUN")
    require(run.get("head_sha") == HEAD, "ARTIFACT_SOURCE")
    expires = value.get("expires_at")
    require(isinstance(expires, str), "ARTIFACT_EXPIRY")
    expiry = dt.datetime.fromisoformat(expires.replace("Z", "+00:00"))
    require(expiry.tzinfo is not None and expiry > dt.datetime.now(dt.timezone.utc),
            "ARTIFACT_EXPIRED")
    return value


def checked_apk(root, spec):
    path = output_file(root, spec["path"])
    content = read_bounded(path, spec["bytes"])
    require(len(content) == spec["bytes"] and digest(content) == spec["sha256"],
            spec["role"].upper() + "_APK_IDENTITY")
    return path


def check_candidate(raw):
    require(digest(raw) == CANDIDATE_SHA, "BUILD_CANDIDATE_HASH")
    value = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique_object)
    require(isinstance(value, dict) and type(value.get("schema")) is int and value["schema"] == 1
            and value.get("kind") == "B2 build-only APK candidate"
            and value.get("status") == "VERIFIED_BUILD_ONLY", "BUILD_CANDIDATE_STATUS")
    require(value.get("android_execution") == "NOT_RUN"
            and all(type(value.get(k)) is int and value[k] == 0 for k in ("installations", "process_exits")),
            "BUILD_CANDIDATE_ZERO_EXECUTION")
    source = value.get("source")
    require(isinstance(source, dict) and source.get("source_commit") == HEAD
            and source.get("source_tree") == SOURCE_TREE and source.get("clean_checkout") is True
            and source.get("source_file_sha256") == SOURCE_FILES, "BUILD_CANDIDATE_SOURCE")
    require(value.get("single_signer_certificate_sha256") == CERTIFICATE, "BUILD_CANDIDATE_CERTIFICATE")
    candidates = value.get("apks")
    require(isinstance(candidates, list) and len(candidates) == 2
            and all(isinstance(apk, dict) for apk in candidates), "BUILD_CANDIDATE_APKS")
    roles = {apk.get("role"): apk for apk in candidates}
    require(set(roles) == {"target", "test"}, "BUILD_CANDIDATE_APK_ROLES")
    for spec in APKS:
        apk = roles[spec["role"]]
        require(all(apk.get(k) == spec[k] for k in ("role", "path", "sha256"))
                and type(apk.get("bytes")) is int and apk["bytes"] == spec["bytes"]
                and apk.get("compiled_manifest_sha256") == MANIFEST_HASHES[spec["role"]], "BUILD_CANDIDATE_APK_IDENTITY")
        signature, manifest = apk.get("signature"), apk.get("manifest")
        require(isinstance(signature, dict) and type(signature.get("signer_count")) is int
                and signature["signer_count"] == 1 and signature.get("certificate_sha256") == CERTIFICATE,
                "BUILD_CANDIDATE_APK_CERTIFICATE")
        require(isinstance(manifest, dict) and manifest.get("package") == spec["package"]
                and manifest.get("permissions") == [] and manifest.get("custom_permissions") == []
                and apk.get("native_libraries") == [] and isinstance(apk.get("dex"), dict)
                and apk["dex"].get("status") == "VERIFIED", "BUILD_CANDIDATE_APK_POLICY")
    return value


def materialize(archive, meta_path, out):
    raw_metadata = read_bounded(meta_path, 1024 * 1024)
    meta = metadata(raw_metadata)
    raw_archive = read_bounded(archive, ARCHIVE_BYTES)
    require(len(raw_archive) == ARCHIVE_BYTES and digest(raw_archive) == ARCHIVE_SHA256,
            "ARCHIVE_IDENTITY")
    out = reject_symlink_path(out)
    require(not out.exists() or out.is_dir(), "OUTPUT_DIRECTORY")
    extracted = []
    with zipfile.ZipFile(io.BytesIO(raw_archive)) as bundle:
        candidates = [entry for entry in bundle.infolist() if entry.filename == CANDIDATE_PATH]
        require(len(candidates) == 1 and not candidates[0].is_dir()
                and not stat.S_ISLNK(candidates[0].external_attr >> 16)
                and candidates[0].file_size <= 2 * 1024 * 1024, "ARCHIVE_BUILD_CANDIDATE")
        raw_candidate = bundle.read(candidates[0])
        check_candidate(raw_candidate)
        for spec in APKS:
            matches = [entry for entry in bundle.infolist() if entry.filename == spec["path"]]
            require(len(matches) == 1, "ARCHIVE_APK_MEMBER")
            entry = matches[0]
            mode = entry.external_attr >> 16
            require(not entry.is_dir() and not stat.S_ISLNK(mode)
                    and entry.file_size == spec["bytes"], "ARCHIVE_APK_TYPE_SIZE")
            content = bundle.read(entry)
            require(len(content) == spec["bytes"] and digest(content) == spec["sha256"],
                    "ARCHIVE_APK_HASH")
            destination = output_file(out, spec["path"])
            require(not destination.exists(), "APK_OUTPUT_ALREADY_EXISTS")
            extracted.append((destination, content))
    for relative in ("artifact-metadata.json", "provenance.json", "build-candidate.json"):
        require(not output_file(out, relative).exists(), "PROVENANCE_ALREADY_EXISTS")
    # All members and hashes are checked before any APK is written. Never extractall.
    for destination, content in extracted:
        write_bytes(destination, content)
    write_bytes(output_file(out, "artifact-metadata.json"), raw_metadata)
    write_bytes(output_file(out, "build-candidate.json"), raw_candidate)
    provenance = {
        "artifact_id": ARTIFACT_ID, "run_id": RUN_ID, "artifact_name": ARTIFACT_NAME,
        "source_commit": HEAD, "archive_sha256": ARCHIVE_SHA256,
        "archive_bytes": ARCHIVE_BYTES, "metadata_sha256": digest(raw_metadata),
        "created_at": meta.get("created_at"), "expires_at": meta["expires_at"],
        "apks": list(APKS), "android_execution": "NOT_RUN",
        "source_tree": SOURCE_TREE, "source_file_sha256": SOURCE_FILES,
        "build_candidate": {"path": CANDIDATE_PATH, "sha256": CANDIDATE_SHA},
    }
    write_json(output_file(out, "provenance.json"), provenance)
    return provenance


def run_tool(command):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                            encoding="utf-8", errors="strict", timeout=60, check=False)
    require(result.returncode == 0, "SDK_TOOL_FAILED")
    return result.stdout, result.stderr


def sdk_tool(sdk, name):
    directory = Path(sdk) / "build-tools" / "36.0.0"
    suffixes = (".exe", "") if name == "aapt2" else (".bat", "")
    for suffix in suffixes:
        candidate = directory / (name + suffix)
        if candidate.is_file():
            return candidate
    raise ValueError("SDK_36_TOOL_MISSING")


def xmltree(text):
    """Parse aapt2's compiled Manifest tree, keeping attributes on their own node."""
    roots, stack = [], []
    for line in text.splitlines():
        element = re.fullmatch(r"(\s*)E: ([\w.-]+)(?: \(line=\d+\))?", line)
        if element:
            indent = len(element[1])
            node = {"name": element[2], "attributes": {}, "children": []}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            (stack[-1][1]["children"] if stack else roots).append(node)
            stack.append((indent, node))
            continue
        attribute = re.fullmatch(r"(\s*)A: ([^=]+)=(.*)", line)
        if attribute:
            require(stack and len(attribute[1]) > stack[-1][0], "MANIFEST_ATTRIBUTE")
            key = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", attribute[2])
            key = key.replace("http://schemas.android.com/apk/res/android:", "android:")
            require(key not in stack[-1][1]["attributes"], "MANIFEST_DUPLICATE_ATTRIBUTE")
            value = attribute[3]
            if value.startswith('"'):
                value, _ = json.JSONDecoder().raw_decode(value)
            stack[-1][1]["attributes"][key] = value
            continue
        require(not line.strip() or line.lstrip().startswith("N:"), "MANIFEST_TREE_FORMAT")
    require(len(roots) == 1 and roots[0]["name"] == "manifest", "MANIFEST_ROOT")
    return roots[0]


def all_nodes(node):
    yield node
    for child in node["children"]:
        yield from all_nodes(child)


def only_child(node, name):
    children = [child for child in node["children"] if child["name"] == name]
    require(len(children) == 1, "MANIFEST_" + name.upper() + "_COUNT")
    return children[0]


def check_manifest(tree, role):
    attrs = tree["attributes"]
    require(attrs.get("package") == (PACKAGE if role == "target" else TEST_PACKAGE),
            "MANIFEST_PACKAGE")
    for node in all_nodes(tree):
        require("permission" not in node["name"], "MANIFEST_PERMISSION")
        require(not any(key.split(":")[-1] in ("sharedUserId", "sharedUserMaxSdkVersion")
                        for key in node["attributes"]), "MANIFEST_SHARED_UID")
    expected = ["uses-sdk", "application"] if role == "target" else [
        "uses-sdk", "instrumentation", "application"]
    require(sorted(child["name"] for child in tree["children"]) == sorted(expected),
            "MANIFEST_COMPONENTS")
    sdk = only_child(tree, "uses-sdk")["attributes"]
    require(sdk.get("android:minSdkVersion") == "26"
            and sdk.get("android:targetSdkVersion") == "36", "MANIFEST_SDK")
    app = only_child(tree, "application")
    require(app["attributes"].get("android:allowBackup") == "false"
            and app["attributes"].get("android:usesCleartextTraffic") == "false",
            "MANIFEST_APPLICATION_POLICY")
    require("android:process" not in app["attributes"], "MANIFEST_APPLICATION_PROCESS")
    if role == "target":
        require(attrs.get("android:versionCode") == "1"
                and attrs.get("android:versionName") == "0.1-e2b-b2-lab", "MANIFEST_VERSION")
        require([child["name"] for child in app["children"]] == ["service", "service"],
                "MANIFEST_TARGET_SERVICES_ONLY")
        services = {s["attributes"].get("android:name"): s for s in app["children"]}
        require(set(services) == {PACKAGE + ".OwnerDeathQaService",
                                 PACKAGE + ".WorkerDeathQaService"}, "MANIFEST_SERVICES")
        for name, process in (("OwnerDeathQaService", ":ownerqa"),
                              ("WorkerDeathQaService", ":workerqa")):
            service = services[PACKAGE + "." + name]
            required = {"android:name": PACKAGE + "." + name,
                        "android:process": process, "android:exported": "false",
                        "android:stopWithTask": "false"}
            require(all(service["attributes"].get(k) == v for k, v in required.items())
                    and not service["children"], "MANIFEST_PRIVATE_SERVICE")
            require("android:permission" not in service["attributes"]
                    and service["attributes"].get("android:isolatedProcess", "false") == "false",
                    "MANIFEST_SERVICE_POLICY")
    else:
        require(not app["children"], "MANIFEST_TEST_COMPONENTS")
        instrument = only_child(tree, "instrumentation")
        require(instrument["attributes"].get("android:name") == RUNNER
                and instrument["attributes"].get("android:targetPackage") == PACKAGE
                and "android:targetProcesses" not in instrument["attributes"]
                and not instrument["children"], "MANIFEST_INSTRUMENTATION")


def check_badging(text, spec):
    packages = re.findall(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'",
                          text, re.MULTILINE)
    require(len(packages) == 1 and packages[0][0] == spec["package"], "BADGING_PACKAGE")
    require(re.findall(r"^sdkVersion:'([^']*)'$", text, re.MULTILINE) == ["26"]
            and re.findall(r"^targetSdkVersion:'([^']*)'$", text, re.MULTILINE) == ["36"],
            "BADGING_SDK")
    require(not re.search(r"^uses-permission(?:[-:]|\s)|^launchable-activity:", text, re.MULTILINE),
            "BADGING_PERMISSION_ACTIVITY")
    if spec["role"] == "target":
        require(packages[0][1:] == ("1", "0.1-e2b-b2-lab"), "BADGING_VERSION")


def check_signature(text):
    signers = re.findall(r"^Number of signers:\s*(\d+)\s*$", text, re.MULTILINE)
    hashes = re.findall(r"^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
                        text, re.MULTILINE)
    require(signers == ["1"] and len(hashes) == 1 and hashes[0][0] == "1"
            and hashes[0][1].lower() == CERTIFICATE
            and re.search(r"^Verifies\s*$", text, re.MULTILINE), "APK_SIGNATURE")


def verify(sdk, out):
    out = reject_symlink_path(out)
    raw = read_bounded(output_file(out, "artifact-metadata.json"), 1024 * 1024)
    metadata(raw)
    check_candidate(read_bounded(output_file(out, "build-candidate.json"), 2 * 1024 * 1024))
    provenance = json.loads(read_bounded(output_file(out, "provenance.json"), 1024 * 1024),
                            object_pairs_hook=unique_object)
    require(isinstance(provenance, dict), "PROVENANCE_OBJECT")
    expected = {"artifact_id": ARTIFACT_ID, "run_id": RUN_ID, "artifact_name": ARTIFACT_NAME,
                "source_commit": HEAD, "archive_sha256": ARCHIVE_SHA256,
                "archive_bytes": ARCHIVE_BYTES, "metadata_sha256": digest(raw), "apks": list(APKS),
                "source_tree": SOURCE_TREE, "source_file_sha256": SOURCE_FILES,
                "build_candidate": {"path": CANDIDATE_PATH, "sha256": CANDIDATE_SHA}}
    require(all(provenance.get(key) == value for key, value in expected.items()), "PROVENANCE")
    aapt2, apksigner = sdk_tool(sdk, "aapt2"), sdk_tool(sdk, "apksigner")
    identities = []
    for spec in APKS:
        apk = checked_apk(out, spec)
        with zipfile.ZipFile(apk) as bundle:
            native = [entry.filename for entry in bundle.infolist()
                      if not entry.is_dir() and (entry.filename.startswith("lib/")
                                                or entry.filename.lower().endswith(".so"))]
            manifests = [entry for entry in bundle.infolist()
                         if entry.filename == "AndroidManifest.xml"]
            require(len(manifests) == 1 and not manifests[0].is_dir()
                    and digest(bundle.read(manifests[0])) == MANIFEST_HASHES[spec["role"]],
                    "COMPILED_MANIFEST_IDENTITY")
        require(not native, "APK_NATIVE_CONTENT")
        manifest, manifest_err = run_tool([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
        badging, badging_err = run_tool([aapt2, "dump", "badging", apk])
        signature, signature_err = run_tool([apksigner, "verify", "--verbose", "--print-certs", apk])
        # Save fresh tool evidence, rather than relying on the archive's old reports.
        for name, content, error in (("manifest", manifest, manifest_err),
                                     ("badging", badging, badging_err),
                                     ("signature", signature, signature_err)):
            write_bytes(output_file(out, "verification/" + spec["role"] + "-" + name + ".txt"),
                        content.encode("utf-8"))
            if error:
                write_bytes(output_file(out, "verification/" + spec["role"] + "-" + name + "-stderr.txt"),
                            error.encode("utf-8"))
        check_manifest(xmltree(manifest), spec["role"])
        check_badging(badging, spec)
        check_signature(signature)
        # Recheck bytes after tools, so their identity and reports refer to the same APK.
        checked_apk(out, spec)
        identities.append(dict(spec, certificate_sha256=CERTIFICATE, permissions=[],
                               native_libraries=[], min_sdk=26, target_sdk=36))
    identity = {"status": "VERIFIED", "source_commit": HEAD, "artifact_id": ARTIFACT_ID,
                "run_id": RUN_ID, "build_tools": "36.0.0", "apks": identities,
                "target_version_code": 1, "target_version_name": "0.1-e2b-b2-lab",
                "instrumentation": RUNNER, "instrumentation_target": PACKAGE,
                "android_execution": "NOT_RUN"}
    write_json(output_file(out, "identity.json"), identity)
    return identity


def parse_log(method, text):
    """Accept exact Bundle status blocks and a complete single-method final result."""
    errors, blocks, fields, result_fields = [], [], {}, {}
    final_codes, host_codes = [], []
    in_results = False

    def error(reason):
        if reason not in errors:
            errors.append(reason)

    if method not in METHODS:
        error("UNKNOWN_METHOD")
    nonempty = [line for line in text.splitlines() if line.strip()]
    if not nonempty or nonempty[-1] != "B2_CALL_EXIT=0":
        error("HOST_EXIT_NOT_LAST_ZERO")
    if re.search(r"\b(?:timeout|timed\s*out|crash(?:ed)?|aborted)\b|INSTRUMENTATION_FAILED", text,
                 re.IGNORECASE):
        error("CRASH_TIMEOUT_OR_ABORT")
    for line in text.splitlines():
        if line.lstrip().startswith("B2_CALL_EXIT"):
            match = re.fullmatch(r"B2_CALL_EXIT=(\d+)", line)
            if not match:
                error("MALFORMED_HOST_EXIT"); continue
            if fields or final_codes != [-1]:
                error("HOST_EXIT_ORDER")
            if match[1] != str(int(match[1])):
                error("MALFORMED_HOST_EXIT")
            host_codes.append(int(match[1]))
        elif line.startswith("INSTRUMENTATION_STATUS: "):
            if in_results or final_codes:
                error("STATUS_AFTER_RESULT")
            match = re.fullmatch(r"INSTRUMENTATION_STATUS: ([A-Za-z0-9_]+)=(.*)", line)
            if not match:
                error("MALFORMED_STATUS"); continue
            key, value = match.groups()
            if key in fields:
                error("DUPLICATE_STATUS_KEY")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            match = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
            if not match:
                error("MALFORMED_STATUS_CODE"); continue
            if not fields or in_results or final_codes:
                error("UNBOUND_STATUS_CODE")
            if match[1] not in ("1", "0", "-2"):
                error("UNEXPECTED_STATUS_CODE")
            blocks.append({"code": int(match[1]), "fields": dict(fields)})
            fields.clear()
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            in_results = True
            if fields or final_codes:
                error("RESULT_ORDER")
            match = re.fullmatch(r"INSTRUMENTATION_RESULT: ([A-Za-z0-9_]+)=(.*)", line)
            if not match:
                error("MALFORMED_RESULT"); continue
            key, value = match.groups()
            if key in result_fields:
                error("DUPLICATE_RESULT_KEY")
            result_fields[key] = value
        elif line.startswith("INSTRUMENTATION_CODE:"):
            match = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", line)
            if not match:
                error("MALFORMED_FINAL_CODE"); continue
            if fields or not in_results:
                error("FINAL_ORDER")
            if match[1] != "-1":
                error("FINAL_CODE_TEXT")
            final_codes.append(int(match[1]))
        elif line.lstrip().startswith("INSTRUMENTATION_"):
            error("UNEXPECTED_INSTRUMENTATION_LINE")
    if fields:
        error("UNCLOSED_STATUS")
    required = {"id": "OwnerDeathRunner", "class": TEST_CLASS, "test": method,
                "numtests": "1", "current": "1"}
    allowed = set(required) | {"stream", "stack"}
    matches = []
    for block in blocks:
        correct = all(block["fields"].get(k) == value for k, value in required.items())
        if not correct:
            error("STATUS_IDENTITY_OR_COUNT")
        if not set(block["fields"]).issubset(allowed):
            error("UNEXPECTED_STATUS_FIELD")
        if "stack" in block["fields"] and block["code"] != -2:
            error("STACK_WITHOUT_FAILURE")
        matches.append(correct)
    codes = [block["code"] for block in blocks]
    started = bool(blocks and codes[0] == 1 and matches[0])
    completed = bool(started and len(blocks) >= 2 and codes[1] in (0, -2) and matches[1])
    if codes != [1, 0]:
        error("RUNNER_FAILURE" if -2 in codes else "STATUS_SEQUENCE")
    if final_codes != [-1]:
        error("FINAL_CODE")
    if host_codes != [0]:
        error("HOST_TIMEOUT" if host_codes == [124] else "HOST_EXIT")
    if not set(result_fields).issubset({"tests", "failures", "passed", "stream"}):
        error("UNEXPECTED_RESULT_FIELD")
    counts = {}
    for key, expected in (("tests", "1"), ("failures", "0"), ("passed", "1")):
        raw_count = result_fields.get(key)
        counts[key] = int(raw_count) if raw_count is not None and re.fullmatch(r"\d+", raw_count) else None
        if raw_count != expected:
            error("FINAL_COUNTS")
    passed = not errors and started and completed
    incomplete = not completed or not final_codes
    return dict(method=method, status="PASS" if passed else "INCOMPLETE" if incomplete else "FAILED",
                started=started, completed=completed, status_codes=codes,
                final_codes=final_codes, host_exit=host_codes[0] if len(host_codes) == 1 else None, **counts,
                reason="OK" if passed else errors[0] if errors else "INCOMPLETE",
                errors=errors, evidence_boundary="synthetic B2 Binder assertions only")


def parse(method, logfile, result_path):
    try:
        raw = read_bounded(logfile, 2 * 1024 * 1024)
        result = parse_log(method, raw.decode("utf-8-sig"))
        result["log_sha256"] = digest(raw)
    except (OSError, UnicodeError, ValueError):
        result = dict(method=method, status="FAILED", started=False, completed=False,
                      status_codes=[], final_codes=[], tests=None, failures=None, passed=None,
                      reason="LOG_NOT_READABLE", errors=["LOG_NOT_READABLE"])
    write_json(result_path, result)
    return result


def parse_trace(method, text):
    """Validate bounded, metadata-only B2QA rows emitted by the frozen fixture."""
    rows, errors = [], []
    def need(condition, reason):
        if not condition and reason not in errors:
            errors.append(reason)
    identity_keys = {"case", "stage", "pid_diagnostic_only", "harness_pid", "harness_uid",
                     "owner_pid", "owner_uid", "owner_caller_uid", "worker_pid", "worker_uid",
                     "worker_caller_uid", "owner_remote", "worker_remote"}
    state_keys = {"case", "stage", "local_claims", "extra_leases", "owner_dead", "worker_dead",
                  "sender_disposition", "receiver_disposition", "sender_scanned_zero",
                  "receiver_scanned_zero", "worker_COPY_quiescent", "reconciled"}
    worker_keys = {"case", "stage", "worker_actual_COPY_count", "receiver_held",
                   "receiver_scanned_zero", "receiver_released"}
    owner_keys = {"case", "stage", "owner_claims", "sender_held", "sender_scanned_zero"}
    death_keys = {"case", "stage", "role", "actual_callback_observed"}
    for line in text.splitlines():
        if not line.strip() or line.startswith("--------- beginning of "):
            continue
        match = re.fullmatch(r"I/B2QA\s*\(\s*(\d+)\): (.*)", line)
        if not match:
            need(False, "TRACE_FORMAT"); continue
        pairs = match[2].split()
        record = {}
        for pair in pairs:
            if pair.count("=") != 1:
                need(False, "TRACE_FIELD_FORMAT"); continue
            key, value = pair.split("=", 1)
            need(key not in record, "TRACE_DUPLICATE_KEY")
            record[key] = value
        need(record.get("case") == method, "TRACE_WRONG_CASE")
        need(set(record) in (identity_keys, state_keys, worker_keys, owner_keys, death_keys),
             "TRACE_UNEXPECTED_FIELDS")
        record["log_pid"] = int(match[1])
        rows.append(record)
    need(method in METHODS, "TRACE_UNKNOWN_METHOD")
    by_stage = {}
    for row in rows:
        by_stage.setdefault(row.get("stage"), []).append(row)
        for key, value in row.items():
            if key in ("case", "stage", "role", "log_pid"):
                continue
            if key.endswith("disposition"):
                need(value in ("PENDING", "WIPED", "HOST_GONE"), "TRACE_DISPOSITION")
            elif key.endswith(("pid", "uid", "claims", "leases", "count")):
                need(bool(re.fullmatch(r"0|[1-9]\d*", value)), "TRACE_NUMBER")
            else:
                need(value in ("true", "false"), "TRACE_BOOLEAN")
    identities = by_stage.get("three-processes", [])
    need(len(identities) == (2 if method == METHODS[2] else 1), "TRACE_PROCESS_COUNT")
    harness_pid = identities[0].get("harness_pid") if identities else None
    for row in rows:
        need(str(row["log_pid"]) == harness_pid, "TRACE_HARNESS_PID")
    for row in identities:
        numeric = [row.get(k, "") for k in identity_keys if k.endswith("pid") or k.endswith("uid")]
        need(all(re.fullmatch(r"[1-9]\d*", v) for v in numeric), "TRACE_PID_UID_FORMAT")
        need(len({row.get("harness_pid"), row.get("owner_pid"), row.get("worker_pid")}) == 3,
             "TRACE_THREE_PIDS")
        need(len({row.get(k) for k in ("harness_uid", "owner_uid", "owner_caller_uid",
                                     "worker_uid", "worker_caller_uid")}) == 1, "TRACE_SAME_UID")
        need(all(row.get(k) == "true" for k in ("pid_diagnostic_only", "owner_remote", "worker_remote")),
             "TRACE_REMOTE_PROXY")
    deaths = by_stage.get("awaited-binder-death", [])
    need(len(deaths) == 1 and deaths[0].get("role") ==
         ("worker" if method == METHODS[2] else "owner")
         and deaths[0].get("actual_callback_observed") == "true", "TRACE_EXACT_CALLBACK")
    def stage(name, expected):
        found = by_stage.get(name, [])
        need(len(found) == 1, "TRACE_STAGE_" + name)
        if len(found) == 1:
            need(all(found[0].get(k) == str(v) for k, v in expected.items()),
                 "TRACE_FACT_" + name)
    if method == METHODS[0]:
        stage("worker-gated-before-owner-loss", {"worker_actual_COPY_count": 1, "receiver_held": "true"})
        stage("owner-death-and-local-return", {"local_claims": 0, "owner_dead": "true",
              "worker_dead": "false", "sender_disposition": "PENDING", "receiver_disposition": "PENDING"})
        stage("worker-wiped-still-copy-inflight", {"worker_actual_COPY_count": 1,
              "receiver_held": "false", "receiver_scanned_zero": "true", "receiver_released": "true"})
        stage("receiver-zero-without-worker-drain", {"local_claims": 0,
              "receiver_disposition": "WIPED", "receiver_scanned_zero": "true", "worker_COPY_quiescent": "false"})
        stage("finally-explicitly-reconciled", {"local_claims": 0, "extra_leases": 0,
              "sender_disposition": "HOST_GONE", "receiver_disposition": "WIPED",
              "sender_scanned_zero": "false", "receiver_scanned_zero": "true",
              "worker_COPY_quiescent": "true", "reconciled": "true"})
    elif method == METHODS[1]:
        stage("owner-gated-after-worker-ACK", {"owner_claims": 1, "sender_held": "true", "sender_scanned_zero": "false"})
        stage("receiver-wiped-worker-copy-drained", {"worker_actual_COPY_count": 0,
              "receiver_scanned_zero": "true", "receiver_released": "true", "receiver_held": "false"})
        stage("receiver-only-cleanup", {"local_claims": 1, "extra_leases": 1,
              "sender_disposition": "PENDING", "receiver_disposition": "WIPED", "worker_COPY_quiescent": "true"})
        stage("sender-wipe-refused-while-SEND-inflight", {"owner_claims": 1, "sender_held": "true", "sender_scanned_zero": "false"})
        stage("actual-owner-loss-and-SEND-finally", {"local_claims": 0, "owner_dead": "true",
              "sender_disposition": "PENDING", "sender_scanned_zero": "false"})
        stage("owner-HOST_GONE-with-independent-lease", {"local_claims": 0, "extra_leases": 1,
              "sender_disposition": "HOST_GONE", "sender_scanned_zero": "false"})
    elif method == METHODS[2]:
        stage("actual-worker-loss-owner-still-live", {"local_claims": 0, "extra_leases": 1,
              "owner_dead": "false", "worker_dead": "true", "sender_disposition": "PENDING", "receiver_disposition": "PENDING"})
        stage("owner-scanned-WIPED-worker-HOST_GONE", {"sender_disposition": "WIPED",
              "sender_scanned_zero": "true", "receiver_disposition": "HOST_GONE",
              "receiver_scanned_zero": "false", "worker_COPY_quiescent": "false", "extra_leases": 1})
        stage("new-generation-after-old-evidence-refused", {"local_claims": 0,
              "owner_dead": "false", "worker_dead": "false", "sender_disposition": "PENDING",
              "receiver_disposition": "PENDING", "reconciled": "false"})
        if len(identities) == 2:
            need(identities[0].get("owner_pid") == identities[1].get("owner_pid"), "TRACE_SAME_OWNER")
    stage("finally-actual-worker-COPY-drained", {"worker_actual_COPY_count": 0})
    if method in METHODS[1:]:
        stage("finally-explicitly-reconciled", {"local_claims": 0, "extra_leases": 0,
              "sender_disposition": "WIPED", "receiver_disposition": "WIPED",
              "sender_scanned_zero": "true", "receiver_scanned_zero": "true",
              "worker_COPY_quiescent": "true", "reconciled": "true"})
    allowed_stages = {"three-processes", "awaited-binder-death", "finally-actual-worker-COPY-drained",
                      "finally-explicitly-reconciled"}
    allowed_stages.update((
        {"worker-gated-before-owner-loss", "owner-death-and-local-return",
         "worker-wiped-still-copy-inflight", "receiver-zero-without-worker-drain"},
        {"owner-gated-after-worker-ACK", "receiver-wiped-worker-copy-drained", "receiver-only-cleanup",
         "sender-wipe-refused-while-SEND-inflight", "actual-owner-loss-and-SEND-finally",
         "owner-HOST_GONE-with-independent-lease"},
        {"actual-worker-loss-owner-still-live", "owner-scanned-WIPED-worker-HOST_GONE",
         "new-generation-after-old-evidence-refused"})[METHODS.index(method)] if method in METHODS else set())
    need(set(by_stage).issubset(allowed_stages), "TRACE_UNEXPECTED_STAGE")
    expected_orders = (
        ["three-processes", "worker-gated-before-owner-loss", "awaited-binder-death",
         "owner-death-and-local-return", "worker-wiped-still-copy-inflight",
         "receiver-zero-without-worker-drain", "finally-actual-worker-COPY-drained", "finally-explicitly-reconciled"],
        ["three-processes", "owner-gated-after-worker-ACK", "receiver-wiped-worker-copy-drained",
         "receiver-only-cleanup", "sender-wipe-refused-while-SEND-inflight", "awaited-binder-death",
         "actual-owner-loss-and-SEND-finally", "owner-HOST_GONE-with-independent-lease",
         "finally-actual-worker-COPY-drained", "finally-explicitly-reconciled"],
        ["three-processes", "awaited-binder-death", "actual-worker-loss-owner-still-live",
         "owner-scanned-WIPED-worker-HOST_GONE", "three-processes", "new-generation-after-old-evidence-refused",
         "finally-actual-worker-COPY-drained", "finally-explicitly-reconciled"])
    need(method in METHODS and [r.get("stage") for r in rows] ==
         expected_orders[METHODS.index(method)], "TRACE_STAGE_ORDER")
    return {"method": method, "status": "PASS" if not errors else "FAILED", "rows": rows if not errors else [],
            "reason": errors[0] if errors else "OK", "errors": errors,
            "evidence_boundary": "frozen fixture metadata and actual awaited Binder callback; PID is diagnostic"}


def remove_temp(root, parent, stopped):
    """Delete only the exact minted subtree, after the caller's shutdown gate."""
    import shutil
    require(stopped == "STOPPED", "CLEANUP_NOT_STOPPED")
    require(Path(root).is_absolute() and Path(parent).is_absolute(), "CLEANUP_ABSOLUTE_ROOT")
    root, parent = reject_symlink_path(root), reject_symlink_path(parent)
    require(root.is_dir() and root.resolve().parent == parent.resolve()
            and re.fullmatch(r"gccwebos-b2-runtime\.[A-Za-z0-9]{6,}", root.name), "CLEANUP_ROOT")
    require(all(not p.is_symlink() for p in root.rglob("*")), "CLEANUP_MEMBER_SYMLINK")
    shutil.rmtree(root)
    require(not root.exists() and not root.is_symlink(), "CLEANUP_NOT_REMOVED")


def aggregate(out, original, shutdown, child_stopped, serial_absent, removed,
              shutdown_request_exit, child_wait_exit, adb_stopped, adb_shutdown_exit, adb_wait_exit):
    root = reject_symlink_path(out)
    records = []
    for method in METHODS:
        path = output_file(root, method + ".json")
        row = json.loads(read_bounded(path, 65536), object_pairs_hook=unique_object) if path.exists() else {
            "method": method, "status": "NOT_RUN", "started": False, "completed": False}
        trace_path = output_file(root, method + ".trace.json")
        trace_row = json.loads(read_bounded(trace_path, 1024 * 1024), object_pairs_hook=unique_object) if trace_path.exists() else {
            "method": method, "status": "NOT_RUN"}
        checks_path = output_file(root, method + ".host-checks.json")
        checks = json.loads(read_bounded(checks_path, 65536), object_pairs_hook=unique_object) if checks_path.exists() else {}
        record_bound = (row.get("method") == trace_row.get("method") == method
                        and row.get("started") is True and row.get("completed") is True
                        and all(type(row.get(k)) is int for k in ("host_exit", "tests", "passed", "failures"))
                        and all(isinstance(row.get(k), list) and all(type(v) is int for v in row[k])
                                for k in ("status_codes", "final_codes"))
                        and row.get("status_codes") == [1, 0] and row.get("final_codes") == [-1]
                        and row.get("host_exit") == 0 and row.get("tests") == row.get("passed") == 1
                        and row.get("failures") == 0)
        for record, suffix in ((row, ".log"), (trace_row, ".b2qa.log")):
            log = output_file(root, method + suffix)
            record_bound = record_bound and log.is_file() and record.get("log_sha256") == digest(
                read_bounded(log, 2 * 1024 * 1024))
        record_bound = (record_bound and checks.get("method") == method
                        and type(checks.get("invocation_count")) is int and checks["invocation_count"] == 1
                        and all(type(checks.get(k)) is int and checks[k] == 0 for k in (
                            "call_exit", "parse_exit", "trace_exit", "ownership_exit", "sample_exit", "services_exit", "crash_exit")))
        records.append({"runner": row, "telemetry": trace_row, "host_checks": checks, "record_bound": bool(record_bound)})
    passed = (int(original) == 0 and shutdown == "STOPPED"
              and shutdown_request_exit == child_wait_exit == adb_shutdown_exit == adb_wait_exit == "0") and all(
        value == "true" for value in (child_stopped, serial_absent, removed, adb_stopped)) and all(
        r["runner"].get("status") == r["telemetry"].get("status") == "PASS"
        and r["record_bound"] for r in records)
    result = {"status": "PASS" if passed else "FAILED", "overall_exit": 0 if passed else 1,
              "harness_exit": int(original), "emulator_shutdown": shutdown,
              "owned_child_stopped": child_stopped == "true", "owned_serial_absent": serial_absent == "true",
              "owned_temporary_directory_removed": removed == "true", "methods": records,
              "console_shutdown_exit": shutdown_request_exit, "owned_child_wait_exit": child_wait_exit,
              "private_adb_stopped": adb_stopped == "true", "private_adb_shutdown_exit": adb_shutdown_exit,
              "private_adb_wait_exit": adb_wait_exit,
              "fault_budget": {"owner": 2, "worker": 1}, "fault_authority": "frozen exact fixture only",
              "apk_rebuilt_in_runtime": False, "vpn_started": False, "physical_device_tested": False,
              "evidence_boundary": "synthetic private Java arrays and actual Binder callbacks; no OS memory-erasure proof"}
    write_json(output_file(root, "result.json"), result)
    return passed


def trace(method, logfile, result_path):
    try:
        raw = read_bounded(logfile, 2 * 1024 * 1024)
        result = parse_trace(method, raw.decode("utf-8-sig"))
        result["log_sha256"] = digest(raw)
    except (OSError, UnicodeError, ValueError):
        result = {"method": method, "status": "FAILED", "rows": [], "reason": "TRACE_NOT_READABLE"}
    write_json(result_path, result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    material = commands.add_parser("materialize")
    material.add_argument("freeze")
    material.add_argument("archive"); material.add_argument("metadata"); material.add_argument("out")
    verifier = commands.add_parser("verify")
    verifier.add_argument("freeze")
    verifier.add_argument("sdk"); verifier.add_argument("out")
    runner = commands.add_parser("parse")
    runner.add_argument("method"); runner.add_argument("log"); runner.add_argument("result_json")
    ready = commands.add_parser("check-ready")
    ready.add_argument("freeze")
    tracer = commands.add_parser("trace")
    tracer.add_argument("method"); tracer.add_argument("log"); tracer.add_argument("result_json")
    removal = commands.add_parser("remove-temp")
    removal.add_argument("root"); removal.add_argument("parent"); removal.add_argument("stopped")
    summary = commands.add_parser("aggregate")
    for name in ("out", "original", "shutdown", "child_stopped", "serial_absent", "removed",
                 "shutdown_request_exit", "child_wait_exit", "adb_stopped", "adb_shutdown_exit", "adb_wait_exit"):
        summary.add_argument(name)
    args = parser.parse_args(argv)
    try:
        if args.command == "materialize":
            load_freeze(args.freeze)
            materialize(args.archive, args.metadata, args.out)
        elif args.command == "verify":
            load_freeze(args.freeze)
            verify(args.sdk, args.out)
        elif args.command == "check-ready":
            load_freeze(args.freeze, require_authorization=True)
        elif args.command == "trace":
            result = trace(args.method, args.log, args.result_json)
            print(result["status"] + ": " + result["reason"])
            return 0 if result["status"] == "PASS" else 1
        elif args.command == "remove-temp":
            remove_temp(args.root, args.parent, args.stopped)
        elif args.command == "aggregate":
            return 0 if aggregate(args.out, args.original, args.shutdown, args.child_stopped,
                                  args.serial_absent, args.removed, args.shutdown_request_exit,
                                  args.child_wait_exit, args.adb_stopped, args.adb_shutdown_exit,
                                  args.adb_wait_exit) else 1
        else:
            result = parse(args.method, args.log, args.result_json)
            print(result["status"] + ": " + result["reason"])
            return 0 if result["status"] == "PASS" else 1
        print(args.command.upper() + ": VERIFIED frozen B2 identity")
        return 0
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile, subprocess.SubprocessError):
        # Keep CLI diagnostics safe and bounded; raw evidence remains in named files.
        print(args.command.upper() + ": FAILED input, identity or tool verification", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
