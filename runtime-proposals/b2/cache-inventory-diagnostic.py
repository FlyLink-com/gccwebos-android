#!/usr/bin/env python3
"""Hosted read-only cache metadata diagnostic. CI success means capture only.

No SDK tool, KVM, sudo, Android or old harness is called. The original inventory
parser is copied verbatim and sees a virtual independent-copy directory shape.
Only fixed cached metadata and license hashes are read, never license raw output.
"""
import sys
sys.dont_write_bytecode = True
import ast
import hashlib
import json
import os
import re
import stat
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

REPOSITORY = "gccwebos-com/gccwebos-android"
BRANCH = "refs/heads/codex/lucky-b2-inventory-diagnostic"
FROZEN_DRIVER_SHA256 = "17fe13aabd50fd97e386a091b34bfea6834188c054dbff517575c5e81dedf683"
PARSER_FUNCTIONS = ("need", "sha", "tag", "nodes", "one", "text_of", "number", "revision", "xml", "package_path", "dependencies", "inventory")
ROOT_TREES = ("cmdline-tools/latest", "platform-tools", "build-tools/36.0.0")
XML_LIMIT = 8 * 1024 * 1024
RAW_LIMIT = 512 * 1024
RAW_TOTAL_LIMIT = 4 * 1024 * 1024
XML_TOTAL_LIMIT = 16 * 1024 * 1024
XML_COUNT_LIMIT = 64


class Stop(Exception):
    pass


NS_ROOT = ('http://schemas.android.com/sdk/android/repo/repository2/03', 'http://schemas.android.com/sdk/android/repo/sys-img2/03')
NS_ALLOWED = {'http://schemas.android.com/sdk/android/repo/repository2/03', 'http://schemas.android.com/sdk/android/repo/sys-img2/01', 'http://schemas.android.com/repository/android/common/02', 'http://schemas.android.com/repository/android/generic/02', 'http://schemas.android.com/sdk/android/repo/sys-img2/03', 'http://schemas.android.com/sdk/android/repo/sys-img2/02', 'http://schemas.android.com/repository/android/common/01', 'http://schemas.android.com/repository/android/generic/01', 'http://www.w3.org/2001/XMLSchema-instance'}


def need(ok, code):
    if not ok:
        raise Stop(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tag(element):
    value = element.tag
    if value.startswith("{"):
        namespace, value = value[1:].split("}", 1)
        need(namespace in NS_ALLOWED, "STOP_XML_NAMESPACE")
    return value


def nodes(element, name):
    return [child for child in element if tag(child) == name]


def one(element, name, optional=False):
    matches = nodes(element, name)
    need(len(matches) == 1 or (optional and not matches), "STOP_XML_FIELD_" + name.upper().replace("-", "_"))
    return matches[0] if matches else None


def text_of(element, name, optional=False):
    child = one(element, name, optional)
    return (child.text or "").strip() if child is not None else None


def number(element, name, default=None):
    value = text_of(element, name, default is not None)
    if value is None:
        return default
    need(re.fullmatch(r"[0-9]+", value) is not None, "STOP_XML_NUMBER")
    return int(value)


def revision(element):
    rev = one(element, "revision")
    values = (number(rev, "major"), number(rev, "minor", 0), number(rev, "micro", 0))
    need(number(rev, "preview", 0) == 0, "STOP_PREVIEW_REVISION")
    need(all(tag(c) in {"major", "minor", "micro", "preview"} for c in rev), "STOP_REVISION_FIELD")
    return values


def xml(raw, root_namespace=None):
    need(len(raw) <= 8 * 1024 * 1024, "STOP_XML_SIZE")
    need(b"\x00" not in raw, "STOP_XML_NUL_ENCODING")
    upper = raw.upper()
    need(b"<!DOCTYPE" not in upper and b"<!ENTITY" not in upper, "STOP_XML_ENTITY")
    root = ET.fromstring(raw)
    if root_namespace:
        names = ("sdk-repository",) if root_namespace == NS_ROOT[0] else ("sdk-sys-img", "sdk-repository")
        need(root.tag in {"{" + root_namespace + "}" + name for name in names}, "STOP_XML_ROOT")
    for element in root.iter():
        tag(element)
        for key in element.attrib:
            if key.startswith("{"):
                need(key[1:].split("}", 1)[0] in NS_ALLOWED, "STOP_XML_ATTRIBUTE_NAMESPACE")
    return root


def package_path(value):
    need(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]+(?:;[A-Za-z0-9_.-]+)*", value), "STOP_PACKAGE_PATH")
    need(all(part not in (".", "..") for part in value.split(";")), "STOP_PACKAGE_COMPONENT")
    return value


def dependencies(package):
    parent = one(package, "dependencies", True)
    result = {}
    for dep in [] if parent is None else list(parent):
        need(tag(dep) == "dependency", "STOP_DEPENDENCY_FIELD")
        key = package_path(dep.get("path"))
        need(key not in result, "STOP_DUPLICATE_DEPENDENCY")
        minimum = one(dep, "min-revision", True)
        if minimum is None:
            result[key] = (0, 0, 0)
        else:
            need(number(minimum, "preview", 0) == 0, "STOP_DEPENDENCY_PREVIEW")
            result[key] = (number(minimum, "major"), number(minimum, "minor", 0), number(minimum, "micro", 0))
    return result


def inventory(sdk):
    result = {}
    for path in sorted(sdk.rglob("package.xml")):
        need(not path.is_symlink(), "STOP_PACKAGE_XML_LINK")
        raw = path.read_bytes()
        root = xml(raw)
        package = one(root, "localPackage")
        name = package_path(package.get("path"))
        location_matches = path.parent == sdk.joinpath(*name.split(";"))
        # Cached cmdline-tools/latest is a conventional alias directory; bind
        # its actual localPackage version rather than inventing a latest ID.
        if re.fullmatch(r"cmdline-tools;(?:latest|[0-9]+(?:\.[0-9]+)*)", name):
            location_matches = path.parent == sdk / "cmdline-tools/latest"
        need(name not in result and location_matches, "STOP_PACKAGE_INVENTORY")
        result[name] = {"revision": list(revision(package)), "package_xml_sha256": sha(raw), "dependencies": {k: list(v) for k, v in dependencies(package).items()}}
    return result


def nonlink_chain(path):
    need(path.is_absolute() and all(not p.is_symlink() for p in (path, *path.parents)), "DIAGNOSTIC_PATH_BOUNDARY")


def safe_target(path, cache):
    target = path.resolve(strict=True)
    need(target == cache or cache in target.parents, "STOP_CACHE_LINK_ESCAPE")
    return target


def error_code(error):
    return str(error) if isinstance(error, Stop) else "STOP_EXCEPTION_" + type(error).__name__


def write_json(path, value):
    with path.open("xb") as stream:
        stream.write((json.dumps(value, sort_keys=True, indent=2) + "\n").encode())


class CapturedXml:
    def __init__(self, relative, raw, context):
        self.relative = relative
        self.raw = raw
        self.context = context
        self.virtual = PurePosixPath("/virtual-independent-sdk") / relative

    @property
    def parent(self):
        return self.virtual.parent

    def is_symlink(self):
        # The original copy_tree dereferences bounded cache aliases into regular
        # private files. Source symlink facts are recorded separately.
        return False

    def read_bytes(self):
        self.context["last_package_xml"] = self.relative
        if self.raw is None:
            raise IsADirectoryError()
        return self.raw

    def __lt__(self, other):
        return self.virtual < other.virtual


class VirtualSdk:
    def __init__(self, metadata):
        self.metadata = metadata
        self.virtual = PurePosixPath("/virtual-independent-sdk")

    def rglob(self, pattern):
        need(pattern == "package.xml", "DIAGNOSTIC_VIRTUAL_GLOB_SCOPE")
        return self.metadata

    def joinpath(self, *parts):
        return self.virtual.joinpath(*parts)

    def __truediv__(self, part):
        return self.virtual / part


def capture_file(path, relative, cache, out, capture, license_file=False):
    target = safe_target(path, cache)
    before = target.stat()
    need(stat.S_ISREG(before.st_mode), "STOP_CACHE_SPECIAL_FILE")
    need(not before.st_mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX), "STOP_CACHE_SPECIAL_MODE")
    flags = os.O_RDONLY | os.O_NOFOLLOW
    digest = hashlib.sha256()
    length = 0
    prefix = bytearray()
    is_xml = PurePosixPath(relative).name == "package.xml"
    prefix_limit = XML_LIMIT + 1 if is_xml else (0 if license_file else RAW_LIMIT + 1)
    with os.fdopen(os.open(target, flags), "rb") as stream:
        opened = os.fstat(stream.fileno())
        need((opened.st_dev, opened.st_ino) == (before.st_dev, before.st_ino), "DIAGNOSTIC_FILE_IDENTITY_CHANGED")
        while True:
            chunk = stream.read(65536)
            if not chunk:
                break
            digest.update(chunk)
            length += len(chunk)
            if len(prefix) < prefix_limit:
                prefix.extend(chunk[:prefix_limit - len(prefix)])
        after = os.fstat(stream.fileno())
    need((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
         and length == after.st_size, "DIAGNOSTIC_METADATA_CHANGED_DURING_READ")
    entry = {"exists": True, "relative_path": relative, "source_is_symlink": path.is_symlink(), "regular_file": True,
             "bytes": length, "sha256": digest.hexdigest(), "raw_retained": False}
    fixed_raw = any(relative == root + "/" + name for root in ROOT_TREES for name in ("package.xml", "source.properties"))
    if not license_file and fixed_raw and length <= RAW_LIMIT and capture["raw_retained_bytes"] + length <= RAW_TOTAL_LIMIT:
        saved = "cached-metadata-" + str(capture["raw_file_count"]) + "-" + PurePosixPath(relative).name
        with (out / saved).open("xb") as stream:
            stream.write(prefix)
        capture["raw_retained_bytes"] += length
        capture["raw_file_count"] += 1
        entry.update(raw_retained=True, raw_path=saved)
    elif not license_file:
        entry["raw_retention_reason"] = "not a fixed root metadata file or exceeds bounded raw retention"
    capture["license_files" if license_file else "metadata"][relative] = entry
    if is_xml:
        need(capture["xml_input_count"] < XML_COUNT_LIMIT and capture["xml_input_bytes"] + len(prefix) <= XML_TOTAL_LIMIT,
             "DIAGNOSTIC_XML_AGGREGATE_LIMIT")
        capture["xml_input_count"] += 1
        capture["xml_input_bytes"] += len(prefix)
        capture["xml_inputs"].append(CapturedXml(relative, bytes(prefix), capture["replay_context"]))


def collect_cached_metadata(cache, out):
    capture = {"metadata": {}, "license_files": {}, "tree_roots": {}, "errors": [], "xml_inputs": [],
               "replay_context": {}, "raw_retained_bytes": 0, "raw_file_count": 0, "visited_nodes": 0,
               "xml_input_count": 0, "xml_input_bytes": 0}
    active = set()

    def walk(path, relative):
        capture["visited_nodes"] += 1
        need(capture["visited_nodes"] <= 100000, "DIAGNOSTIC_DIRECTORY_ENTRY_LIMIT")
        target = safe_target(path, cache)
        info = target.stat()
        need(not info.st_mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX), "STOP_CACHE_SPECIAL_MODE")
        if stat.S_ISDIR(info.st_mode):
            if PurePosixPath(relative).name in ("package.xml", "source.properties"):
                capture["metadata"][relative] = {"exists": True, "relative_path": relative, "source_is_symlink": path.is_symlink(),
                                                 "regular_file": False, "is_directory": True, "raw_retained": False}
                if PurePosixPath(relative).name == "package.xml":
                    # Path.rglob also includes matching directories. The copied
                    # original inventory then raises IsADirectoryError on read.
                    need(capture["xml_input_count"] < XML_COUNT_LIMIT, "DIAGNOSTIC_XML_AGGREGATE_LIMIT")
                    capture["xml_input_count"] += 1
                    capture["xml_inputs"].append(CapturedXml(relative, None, capture["replay_context"]))
            key = (info.st_dev, info.st_ino)
            need(key not in active, "STOP_CACHE_LINK_CYCLE")
            active.add(key)
            try:
                for member in sorted(target.iterdir(), key=lambda p: p.name):
                    walk(member, relative + "/" + member.name)
            finally:
                active.remove(key)
        else:
            need(stat.S_ISREG(info.st_mode), "STOP_CACHE_SPECIAL_FILE")
            if (PurePosixPath(relative).name == "package.xml"
                    or any(relative == root + "/source.properties" for root in ROOT_TREES)):
                capture_file(path, relative, cache, out, capture)

    for relative in ROOT_TREES:
        source = cache / relative
        capture["tree_roots"][relative] = {"exists": source.exists(), "source_is_symlink": source.is_symlink()}
        try:
            walk(source, relative)
        except Exception as error:
            capture["errors"].append({"relative_path": relative, "code": error_code(error)})
        for name in ("package.xml", "source.properties"):
            key = relative + "/" + name
            if key not in capture["metadata"]:
                path = source / name
                capture["metadata"][key] = {"exists": path.exists(), "source_is_symlink": path.is_symlink(), "regular_file": path.is_file(),
                                             "relative_path": key, "raw_retained": False, "capture_complete": False}
    licenses = cache / "licenses"
    try:
        need(not licenses.is_symlink() and licenses.is_dir(), "STOP_LICENSE_COLLECTION")
        need(not licenses.stat().st_mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX), "STOP_CACHE_SPECIAL_MODE")
        for path in sorted(licenses.iterdir(), key=lambda p: p.name):
            need(len(capture["license_files"]) < 256, "DIAGNOSTIC_LICENSE_COUNT_LIMIT")
            need(not path.is_symlink() and path.is_file(), "STOP_LICENSE_COLLECTION")
            capture_file(path, "licenses/" + path.name, cache, out, capture, license_file=True)
        need(capture["license_files"], "STOP_EXISTING_LICENSES_MISSING")
    except Exception as error:
        capture["errors"].append({"relative_path": "licenses", "code": error_code(error)})
    return capture


def verify_context(here):
    need(sys.platform == "linux" and os.getuid() == os.geteuid() != 0, "DIAGNOSTIC_HOST_PLATFORM")
    required = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux",
                "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_REF": BRANCH, "GITHUB_RUN_ATTEMPT": "1"}
    need(all(os.environ.get(k) == v for k, v in required.items()), "DIAGNOSTIC_HOST_CONTEXT")
    head = os.environ.get("APPROVED_DIAGNOSTIC_COMMIT", "")
    expected = os.environ.get("APPROVED_DIAGNOSTIC_SHA256", "")
    need(re.fullmatch(r"[0-9a-f]{40}", head) and os.environ.get("GITHUB_SHA") == head, "DIAGNOSTIC_FULL_HEAD_INPUT")
    need(re.fullmatch(r"[0-9a-f]{64}", expected) and sha(Path(__file__).read_bytes()) == expected, "DIAGNOSTIC_SCRIPT_HASH")
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    nonlink_chain(workspace)
    need(here == workspace / "runtime-proposals/b2", "DIAGNOSTIC_SOURCE_LOCATION")
    git = ["git", "--no-optional-locks", "-c", "core.autocrlf=false", "-c", "core.eol=lf"]
    actual = subprocess.run(git + ["rev-parse", "HEAD"], cwd=workspace, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=True).stdout.decode().strip()
    need(actual == head, "DIAGNOSTIC_CHECKOUT_HEAD")
    frozen_raw = subprocess.run(git + ["show", "HEAD:runtime-proposals/b2/provision-and-run.py"], cwd=workspace, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=True).stdout
    need(sha(frozen_raw) == FROZEN_DRIVER_SHA256, "DIAGNOSTIC_FROZEN_DRIVER_BINDING")
    frozen_ast, current_ast = ast.parse(frozen_raw), ast.parse(Path(__file__).read_bytes())
    for name in PARSER_FUNCTIONS:
        old = next(n for n in frozen_ast.body if isinstance(n, ast.FunctionDef) and n.name == name)
        new = next(n for n in current_ast.body if isinstance(n, ast.FunctionDef) and n.name == name)
        need(ast.dump(old, include_attributes=False) == ast.dump(new, include_attributes=False), "DIAGNOSTIC_PARSER_AST_BINDING")
    roots_node = next(n.value for n in frozen_ast.body if isinstance(n, ast.Assign)
                      and any(isinstance(t, ast.Name) and t.id == "NS_ROOT" for t in n.targets))
    namespaces_node = next(n.value for n in frozen_ast.body if isinstance(n, ast.Assign)
                           and any(isinstance(t, ast.Name) and t.id == "NS_ALLOWED" for t in n.targets))
    need(ast.literal_eval(roots_node) == NS_ROOT and set(NS_ROOT) | ast.literal_eval(namespaces_node.right) == NS_ALLOWED, "DIAGNOSTIC_NAMESPACE_BINDING")
    need(re.fullmatch(r"[0-9]+", os.environ.get("GITHUB_RUN_ID", "")), "DIAGNOSTIC_RUN_ID")
    return workspace, head


def main():
    here = Path(__file__).resolve().parent
    workspace, head = verify_context(here)
    out = workspace / ".build-output/b2-inventory-diagnostic"
    nonlink_chain(out)
    need(not out.exists(), "DIAGNOSTIC_OUTPUT_ALREADY_EXISTS")
    out.mkdir(parents=True)
    result = {"schema": 1, "status": "DIAGNOSTIC_CAPTURED", "scope": "new hosted runner read-only metadata capture; never runtime PASS",
              "run_id": int(os.environ["GITHUB_RUN_ID"]), "run_attempt": 1, "head_sha": head, "repository": REPOSITORY, "branch": BRANCH,
              "observed_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "reference_failed_run": 37004797145,
              "host_identity": {"uid": os.getuid(), "euid": os.geteuid(), "gid": os.getgid(), "egid": os.getegid()},
              "runner_os": os.environ.get("RUNNER_OS"), "runner_environment": os.environ.get("RUNNER_ENVIRONMENT"),
              "reference_boundary": "this new runner observation cannot reconstruct the deleted cache of run 37004797145",
              "virtual_replay_scope": "same frozen parser and predicate over bounded metadata in projected independent-copy directory shape; nonmetadata payloads are unread, no actual SDK copy or full-cache byte-identity proof",
              "frozen_driver_sha256": FROZEN_DRIVER_SHA256, "parser_ast_binding": "PASS",
              "sdkmanager_invocations": 0, "harness_invocations": 0, "kvm_accesses": 0, "sudo_invocations": 0,
              "android_execution": "NOT_RUN", "licenses_raw_exported": False, "sdk_copied_or_deleted": False,
              "predicate_pass": False, "predicate_evaluated": False, "capture_complete": False, "errors": []}
    try:
        value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        need(value, "STOP_CACHED_SDK_MISSING")
        need(Path(value).is_absolute(), "DIAGNOSTIC_CACHED_SDK_NONABSOLUTE")
        cache = Path(value).resolve(strict=True)
        need(cache.is_absolute() and cache.is_dir(), "DIAGNOSTIC_CACHED_SDK_PATH")
        result["source_sdk"] = str(cache)
        capture = collect_cached_metadata(cache, out)
        result.update({k: v for k, v in capture.items() if k not in ("xml_inputs", "replay_context")})
        result["capture_complete"] = not capture["errors"]
        license_canonical = json.dumps(capture["license_files"], sort_keys=True, separators=(",", ":")).encode()
        result["license_collection_sha256"] = sha(license_canonical)
        result["license_collection_hash_scope"] = "canonical filename/observed type/length/full-SHA records; no raw license content exported"
        if capture["errors"]:
            result["inventory_replay_status"] = "NOT_REPLAYED_CAPTURE_FAILED"
        else:
            try:
                before_packages = inventory(VirtualSdk(capture["xml_inputs"]))
                cmdline_names = [name for name in before_packages if re.fullmatch(r"cmdline-tools;(?:latest|[0-9]+(?:\.[0-9]+)*)", name)]
                predicate_pass = len(cmdline_names) == 1 and set(before_packages) == {cmdline_names[0], "platform-tools", "build-tools;36.0.0"}
                expected = ({cmdline_names[0], "platform-tools", "build-tools;36.0.0"} if len(cmdline_names) == 1 else None)
                result.update(cached_packages=before_packages, actual_package_ids=sorted(before_packages), cmdline_matching_ids=cmdline_names,
                              predicate_pass=predicate_pass, predicate_evaluated=True,
                              inventory_replay_status="ORIGINAL_PREDICATE_PASS" if predicate_pass else "STOP_CACHED_PACKAGE_INVENTORY",
                              mismatch={"cmdline_match_count": len(cmdline_names), "required_cmdline_match_count": 1,
                                        "required_fixed_ids": ["platform-tools", "build-tools;36.0.0"],
                                        "missing_fixed_ids": sorted({"platform-tools", "build-tools;36.0.0"} - set(before_packages)),
                                        "missing_exact_ids": sorted(expected - set(before_packages)) if expected is not None else None,
                                        "unexpected_exact_ids": sorted(set(before_packages) - expected) if expected is not None else None})
            except Exception as error:
                result["inventory_replay_status"] = error_code(error)
                result["inventory_replay_error_path"] = capture["replay_context"].get("last_package_xml")
    except Exception as error:
        result["errors"].append({"code": error_code(error)})
    write_json(out / "cache-inventory-diagnostic.json", result)
    (out / "diagnostic-commit.txt").write_bytes((head + "\n").encode())
    # Capture success is not validation or runtime success. A rejected original
    # predicate is deliberately retained and exits zero for diagnostic upload.
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(error_code(error), file=sys.stderr)
        sys.exit(1)
