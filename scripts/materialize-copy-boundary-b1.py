#!/usr/bin/env python3
"""Materialize and verify only the frozen B1 APKs; parse one native runner case.

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

HEAD = "e3e67b7dbd3b01e59ccb41a8f81ac13007717f19"
ARTIFACT_ID = 11190823908
RUN_ID = 36920009030
ARTIFACT_NAME = "copy-boundary-b1-" + HEAD
ARCHIVE_SHA256 = "fe12ed1872e1ec9c15b87d1ce6a3feeec6d87944f06a3d5eddc31a6df2889aed"
ARCHIVE_BYTES = 997489
CERTIFICATE = "f33953453f9ebd37815050a50bf62cb474cec4b68c911e52c33e7537b73c3661"
PACKAGE = "com.gccwebos.android.ipcqa"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = PACKAGE + ".CopyBoundaryRunner"
TEST_CLASS = PACKAGE + ".CopyBoundaryIpcTest"
METHODS = (
    "p1RemoteIdentity", "p2OwnedCopyAndWipe", "p3BoundedActualParcels",
    "p4ClearBeforeCopyAndLateAck", "p5OriginalStopTicketAcrossGeneration",
    "p6UnknownKeepsClaimUntilCallReturns",
)
APKS = (
    {"role": "target", "package": PACKAGE,
     "path": "copyBoundaryLab/build/outputs/apk/debug/copyBoundaryLab-debug.apk",
     "bytes": 920243,
     "sha256": "8e7875cbefbef040a41e76e7aaa5ea4f4b092491c00d1cc376ad5635413a02a1"},
    {"role": "test", "package": TEST_PACKAGE,
     "path": "copyBoundaryLab/build/outputs/apk/androidTest/debug/copyBoundaryLab-debug-androidTest.apk",
     "bytes": 50828,
     "sha256": "2e061fcf66726e7b537edb3dc4c8f3e728c19af70c28c8323f16405cc9e621a1"},
)


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
    for relative in ("artifact-metadata.json", "provenance.json"):
        require(not output_file(out, relative).exists(), "PROVENANCE_ALREADY_EXISTS")
    # All members and hashes are checked before any APK is written. Never extractall.
    for destination, content in extracted:
        write_bytes(destination, content)
    write_bytes(output_file(out, "artifact-metadata.json"), raw_metadata)
    provenance = {
        "artifact_id": ARTIFACT_ID, "run_id": RUN_ID, "artifact_name": ARTIFACT_NAME,
        "source_commit": HEAD, "archive_sha256": ARCHIVE_SHA256,
        "archive_bytes": ARCHIVE_BYTES, "metadata_sha256": digest(raw_metadata),
        "created_at": meta.get("created_at"), "expires_at": meta["expires_at"],
        "apks": list(APKS), "android_execution": "NOT_RUN",
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
                and attrs.get("android:versionName") == "0.1-e2b-lab", "MANIFEST_VERSION")
        require([child["name"] for child in app["children"]] == ["service"],
                "MANIFEST_TARGET_SERVICE_ONLY")
        service = app["children"][0]
        required = {"android:name": PACKAGE + ".CopyBoundaryQaService",
                    "android:process": ":ipcqa", "android:exported": "false",
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
    require(re.findall(r"^minSdkVersion:'([^']*)'$", text, re.MULTILINE) == ["26"]
            and re.findall(r"^targetSdkVersion:'([^']*)'$", text, re.MULTILINE) == ["36"],
            "BADGING_SDK")
    require(not re.search(r"^uses-permission(?:[-:]|\s)|^launchable-activity:", text, re.MULTILINE),
            "BADGING_PERMISSION_ACTIVITY")
    if spec["role"] == "target":
        require(packages[0][1:] == ("1", "0.1-e2b-lab"), "BADGING_VERSION")


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
    provenance = json.loads(read_bounded(output_file(out, "provenance.json"), 1024 * 1024),
                            object_pairs_hook=unique_object)
    require(isinstance(provenance, dict), "PROVENANCE_OBJECT")
    expected = {"artifact_id": ARTIFACT_ID, "run_id": RUN_ID, "artifact_name": ARTIFACT_NAME,
                "source_commit": HEAD, "archive_sha256": ARCHIVE_SHA256,
                "archive_bytes": ARCHIVE_BYTES, "metadata_sha256": digest(raw), "apks": list(APKS)}
    require(all(provenance.get(key) == value for key, value in expected.items()), "PROVENANCE")
    aapt2, apksigner = sdk_tool(sdk, "aapt2"), sdk_tool(sdk, "apksigner")
    identities = []
    for spec in APKS:
        apk = checked_apk(out, spec)
        with zipfile.ZipFile(apk) as bundle:
            native = [entry.filename for entry in bundle.infolist()
                      if not entry.is_dir() and (entry.filename.startswith("lib/")
                                                or entry.filename.lower().endswith(".so"))]
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
                "target_version_code": 1, "target_version_name": "0.1-e2b-lab",
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
    if not nonempty or nonempty[-1] != "B1_CALL_EXIT=0":
        error("HOST_EXIT_NOT_LAST_ZERO")
    if re.search(r"\b(?:timeout|timed\s*out|crash(?:ed)?|aborted)\b|INSTRUMENTATION_FAILED", text,
                 re.IGNORECASE):
        error("CRASH_TIMEOUT_OR_ABORT")
    for line in text.splitlines():
        if line.lstrip().startswith("B1_CALL_EXIT"):
            match = re.fullmatch(r"B1_CALL_EXIT=(\d+)", line)
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
    required = {"id": "CopyBoundaryRunner", "class": TEST_CLASS, "test": method,
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
                errors=errors, evidence_boundary="synthetic B1 Binder assertions only")


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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    material = commands.add_parser("materialize")
    material.add_argument("archive"); material.add_argument("metadata"); material.add_argument("out")
    verifier = commands.add_parser("verify")
    verifier.add_argument("sdk"); verifier.add_argument("out")
    runner = commands.add_parser("parse")
    runner.add_argument("method"); runner.add_argument("log"); runner.add_argument("result_json")
    args = parser.parse_args(argv)
    try:
        if args.command == "materialize":
            materialize(args.archive, args.metadata, args.out)
        elif args.command == "verify":
            verify(args.sdk, args.out)
        else:
            result = parse(args.method, args.log, args.result_json)
            print(result["status"] + ": " + result["reason"])
            return 0 if result["status"] == "PASS" else 1
        print(args.command.upper() + ": VERIFIED frozen B1 identity")
        return 0
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile, subprocess.SubprocessError):
        # Keep CLI diagnostics safe and bounded; raw evidence remains in named files.
        print(args.command.upper() + ": FAILED input, identity or tool verification", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
