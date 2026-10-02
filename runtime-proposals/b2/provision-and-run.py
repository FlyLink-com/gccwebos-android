#!/usr/bin/env python3
"""One approved hosted preparation and one invocation of the immutable B2 harness.

This wrapper provisions two official packages. The original cached-image receipt
describes only the inner harness; it does not describe this preparation scope.
No runtime claim is made by parsing or reviewing this source on another host.
"""
import hashlib
import json
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

REPOSITORY = "gccwebos-com/gccwebos-android"
BRANCH = "refs/heads/codex/lucky-b2-provision-runtime"
PACKAGES = ("emulator", "system-images;android-33;default;x86_64")
METHODS = ("p7OwnerDeathKeepsWorkerAndQuarantine", "p8ExactEvidenceWaitsForCallAndDisposition", "p9WorkerDeathAndStaleEvidenceStayScoped")
CATALOGS = (
    "https://dl.google.com/android/repository/repository2-3.xml",
    "https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml",
)
PREFIX = "gccwebos-b2-sdk-prep."
CORE = {
    "b2-runtime-api33.sh": "cc13dcd3b88b54077e4c51da572e644d72818fd95e26dcbbe0c753140ce8cff6",
    "b2-runtime-evidence.py": "cc3cbe2fcc0c81191c40ee64c23e8f9848a62107600d947f1fa439b9709397b0",
    "proposed-workflow.yml.disabled": "a63894550d16b799b9a7298d932e744fc171c4f486764c142a5b9ab94466a10c",
    "freeze.json": "f2371983e682dc91227b82bceff360e8633566685dfdf168c47dc272f45677ad",
}
CACHE_TREES = ("cmdline-tools/latest", "platform-tools", "build-tools/36.0.0", "licenses")
NS_ROOT = (
    "http://schemas.android.com/sdk/android/repo/repository2/03",
    "http://schemas.android.com/sdk/android/repo/sys-img2/03",
)
NS_ALLOWED = set(NS_ROOT) | {
    "http://schemas.android.com/repository/android/common/01",
    "http://schemas.android.com/repository/android/common/02",
    "http://schemas.android.com/repository/android/generic/01",
    "http://schemas.android.com/repository/android/generic/02",
    "http://schemas.android.com/sdk/android/repo/sys-img2/01",
    "http://schemas.android.com/sdk/android/repo/sys-img2/02",
    "http://www.w3.org/2001/XMLSchema-instance",
}


class Stop(Exception):
    pass


def need(ok, code):
    if not ok:
        raise Stop(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        need(key not in obj, "STOP_DUPLICATE_JSON_KEY")
        obj[key] = value
    return obj


def read_json(path):
    return json.loads(path.read_bytes(), object_pairs_hook=unique_object)


def write_json(path, obj):
    raw = (json.dumps(obj, sort_keys=True, indent=2) + "\n").encode()
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("xb") as stream:
        stream.write(raw)
    os.replace(temporary, path)


def nonlink_chain(path):
    need(path.is_absolute(), "STOP_NONABSOLUTE_PATH")
    need(all(not p.is_symlink() for p in (path, *path.parents)), "STOP_PATH_SYMLINK")


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


def official_url(value):
    parts = urllib.parse.urlsplit(value)
    need(parts.scheme == "https" and parts.hostname == "dl.google.com" and parts.port in (None, 443)
         and not parts.username and not parts.password and not parts.query and not parts.fragment
         and parts.path.startswith("/android/repository/") and ".." not in parts.path.split("/"), "STOP_OFFICIAL_URL")
    return value


class OfficialRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        official_url(urllib.parse.urljoin(request.full_url, newurl))
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def fetch_catalog(url):
    official_url(url)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), OfficialRedirect())
    with opener.open(urllib.request.Request(url, headers={"User-Agent": "B2-approved-preparation/1"}), timeout=30) as response:
        official_url(response.geturl())
        raw = response.read(8 * 1024 * 1024 + 1)
    need(len(raw) <= 8 * 1024 * 1024, "STOP_CATALOG_SIZE")
    return raw


def select_package(root, wanted, catalog_url):
    licenses = {}
    for item in nodes(root, "license"):
        key = item.get("id")
        need(key and key not in licenses and item.text and item.text.strip(), "STOP_CATALOG_LICENSE")
        licenses[key] = sha(item.text.encode())
    candidates = []
    for package in nodes(root, "remotePackage"):
        if package.get("path") != wanted:
            continue
        if package.get("obsolete") not in (None, "false"):
            continue
        channel = one(package, "channelRef", True)
        # repository2/03 and sys-img2/03 use stable as the absent channelRef default.
        if channel is not None and channel.get("ref") != "channel-0":
            continue
        rev_node = one(package, "revision")
        if number(rev_node, "preview", 0) != 0:
            continue
        candidates.append((revision(package), package))
    need(candidates, "STOP_OFFICIAL_PACKAGE_MISSING")
    highest = max(rev for rev, _ in candidates)
    choices = [pkg for rev, pkg in candidates if rev == highest]
    need(len(choices) == 1, "STOP_DUPLICATE_STABLE_REVISION")
    package = choices[0]
    use = one(package, "uses-license")
    need(use.get("ref") in licenses, "STOP_UNRESOLVED_LICENSE")
    if wanted == PACKAGES[1]:
        details = one(package, "type-details")
        image_tag = one(details, "tag")
        need(number(details, "api-level") == 33 and text_of(details, "abi") == "x86_64"
             and text_of(image_tag, "id") == "default", "STOP_IMAGE_DETAILS")
    compatible = []
    for archive in nodes(one(package, "archives"), "archive"):
        host = text_of(archive, "host-os", True)
        bits = text_of(archive, "host-bits", True)
        arch = text_of(archive, "host-arch", True)
        if (host == "linux" if wanted == "emulator" else host in (None, "linux")):
            if bits in (None, "64") and arch in (None, "x86_64"):
                compatible.append(archive)
    need(len(compatible) == 1, "STOP_ARCHIVE_SELECTION")
    archive = compatible[0]
    complete = one(archive, "complete")
    # Complete and patch descriptors may coexist. Baseline has no emulator or
    # image package; only the complete archive is selected here.
    checksum = one(complete, "checksum")
    # The recognized Android repository schema defines absent checksum type as SHA1.
    algorithm = checksum.get("type", "sha1")
    digest = (checksum.text or "").strip().lower()
    need(algorithm in ("sha1", "sha256") and re.fullmatch(r"[0-9a-f]{" + ("40" if algorithm == "sha1" else "64") + "}", digest), "STOP_ARCHIVE_CHECKSUM")
    size = number(complete, "size")
    need(0 < size <= 4 * 1024 * 1024 * 1024, "STOP_ARCHIVE_SIZE")
    return {
        "package": wanted, "revision": list(highest), "dependencies": {k: list(v) for k, v in dependencies(package).items()},
        "license_ref": use.get("ref"), "license_body_sha256": licenses[use.get("ref")],
        "archive_url": official_url(urllib.parse.urljoin(catalog_url, text_of(complete, "url"))),
        "archive_bytes": size, "catalog_checksum_algorithm": algorithm, "catalog_checksum": digest,
        "checksum_evidence_scope": "selected official catalog metadata; downloaded ZIP bytes not independently observed",
    }


def safe_cache_member(path, cache):
    resolved = path.resolve(strict=True)
    need(resolved == cache or cache in resolved.parents, "STOP_CACHE_LINK_ESCAPE")
    return resolved


def tree_bytes(path, cache=None):
    result = {}
    active = set()

    def walk(current, relative):
        target = safe_cache_member(current, cache) if cache else current
        if cache is None:
            need(not current.is_symlink(), "STOP_PRIVATE_CACHE_SYMLINK")
        mode = target.stat().st_mode
        need(not mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX), "STOP_CACHE_SPECIAL_MODE")
        if stat.S_ISDIR(mode):
            identity = (target.stat().st_dev, target.stat().st_ino)
            need(identity not in active, "STOP_CACHE_LINK_CYCLE")
            active.add(identity)
            result[relative + "/"] = {"kind": "directory"}
            for member in sorted(target.iterdir(), key=lambda p: p.name):
                walk(member, relative + "/" + member.name)
            active.remove(identity)
        else:
            need(stat.S_ISREG(mode), "STOP_CACHE_SPECIAL_FILE")
            raw = target.read_bytes()
            result[relative] = {"kind": "file", "bytes": len(raw), "sha256": sha(raw)}
    walk(path, ".")
    return result


def copy_tree(source, destination, cache, readonly=False, active=None):
    active = set() if active is None else active
    source = safe_cache_member(source, cache)
    info = source.stat()
    need(not info.st_mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX), "STOP_CACHE_SPECIAL_MODE")
    if stat.S_ISDIR(info.st_mode):
        key = (info.st_dev, info.st_ino)
        need(key not in active, "STOP_CACHE_LINK_CYCLE")
        active.add(key)
        destination.mkdir(mode=0o700, parents=True, exist_ok=False)
        for child in sorted(source.iterdir(), key=lambda p: p.name):
            copy_tree(child, destination / child.name, cache, readonly, active)
        active.remove(key)
    else:
        need(stat.S_ISREG(info.st_mode), "STOP_CACHE_SPECIAL_FILE")
        need(not readonly or not source.is_symlink(), "STOP_LICENSE_SYMLINK")
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
        mode = 0o400 if readonly else stat.S_IMODE(info.st_mode) & 0o777
        with os.fdopen(os.open(destination, flags, mode), "wb") as output, source.open("rb") as stream:
            shutil.copyfileobj(stream, output)
        need(destination.stat().st_ino != info.st_ino or destination.stat().st_dev != info.st_dev, "STOP_CACHE_HARDLINK")


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


def dependency_gate(selected, cached):
    need(set(selected) == set(PACKAGES) and not set(PACKAGES) & set(cached), "STOP_PACKAGE_SCOPE")
    available = {**cached, **selected}
    visited = set()
    active = set()

    def visit(key):
        need(key in available, "STOP_EXTRA_SOFTWARE_DEPENDENCY")
        need(key not in active, "STOP_DEPENDENCY_CYCLE")
        if key in visited:
            return
        active.add(key)
        for child, minimum in available[key]["dependencies"].items():
            need(child in available, "STOP_EXTRA_SOFTWARE_DEPENDENCY")
            need(tuple(available[child]["revision"]) >= tuple(minimum), "STOP_CACHED_DEPENDENCY_TOO_OLD")
            visit(child)
        active.remove(key)
        visited.add(key)
    for key in PACKAGES:
        visit(key)


def kvm_snapshot():
    # Nonroot listxattr can hide trusted.*. This isolated cached-Python helper is
    # privileged read-only, has a literal path, and emits only hashes of raw
    # metadata. No environment, token, xattr value or SDK command is emitted.
    code = r'''
import hashlib,json,os,stat,sys
try:
    path='/dev/kvm'
    a=os.lstat(path)
    assert os.geteuid()==0 and stat.S_ISCHR(a.st_mode)
    assert not a.st_mode & (stat.S_ISUID|stat.S_ISGID|stat.S_ISVTX)
    assert os.path.realpath(path)==path
    names=os.listxattr(path,follow_symlinks=False)
    assert 'security.capability' not in names
    xattrs={}
    for name in sorted(names):
        raw=os.getxattr(path,name,follow_symlinks=False)
        xattrs[name]={'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
    b=os.lstat(path)
    assert (a.st_dev,a.st_ino,a.st_rdev,a.st_mode,a.st_uid,a.st_gid)==(b.st_dev,b.st_ino,b.st_rdev,b.st_mode,b.st_uid,b.st_gid)
    print(json.dumps(dict(dev=b.st_dev,ino=b.st_ino,rdev=b.st_rdev,mode=b.st_mode,uid=b.st_uid,gid=b.st_gid,ctime_ns=b.st_ctime_ns,xattrs=xattrs)))
except Exception:
    sys.exit(1)
'''
    need(Path(sys.executable).is_absolute(), "STOP_CACHED_PYTHON_PATH")
    completed = subprocess.run(["sudo", "-n", "--", sys.executable, "-I", "-S", "-B", "-c", code], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd="/", env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}, timeout=20, check=False)
    need(completed.returncode == 0 and len(completed.stdout) <= 1024 * 1024, "STOP_KVM_PRIVILEGED_METADATA_INCOMPLETE")
    result = json.loads(completed.stdout, object_pairs_hook=unique_object)
    need(set(result) == {"dev", "ino", "rdev", "mode", "uid", "gid", "ctime_ns", "xattrs"}
         and all(type(result[key]) is int for key in result if key != "xattrs") and isinstance(result["xattrs"], dict), "STOP_KVM_METADATA_SCHEMA")
    need("security.capability" not in result["xattrs"] and all(isinstance(name, str) and set(value) == {"bytes", "sha256"}
         and type(value["bytes"]) is int and value["bytes"] >= 0 and re.fullmatch(r"[0-9a-f]{64}", value["sha256"])
         for name, value in result["xattrs"].items()), "STOP_KVM_XATTR_SCHEMA")
    return {**result, "root_read_exit": completed.returncode}


def same_kvm(before, after, owner):
    need(after["uid"] == owner and all(before[key] == after[key] for key in ("dev", "ino", "rdev", "mode", "gid", "xattrs")), "STOP_KVM_METADATA_CHANGED")


def kvm_minimal_identity():
    # Restoration must remain possible if unrelated mode/gid/xattrs drifted.
    # This helper deliberately reads no xattrs and refuses only a changed node.
    code = r'''
import json,os,stat,sys
try:
    path='/dev/kvm'
    a=os.lstat(path)
    assert os.geteuid()==0 and stat.S_ISCHR(a.st_mode)
    assert os.path.realpath(path)==path
    print(json.dumps(dict(dev=a.st_dev,ino=a.st_ino,rdev=a.st_rdev,uid=a.st_uid,gid=a.st_gid,mode=a.st_mode)))
except Exception:
    sys.exit(1)
'''
    completed = subprocess.run(["sudo", "-n", "--", sys.executable, "-I", "-S", "-B", "-c", code],
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd="/",
                               env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}, timeout=20, check=False)
    need(completed.returncode == 0 and len(completed.stdout) <= 4096, "STOP_KVM_MINIMAL_IDENTITY_INCOMPLETE")
    result = json.loads(completed.stdout, object_pairs_hook=unique_object)
    need(set(result) == {"dev", "ino", "rdev", "uid", "gid", "mode"} and all(type(value) is int for value in result.values()), "STOP_KVM_MINIMAL_IDENTITY_SCHEMA")
    return {**result, "root_read_exit": completed.returncode}


def restore_owner_only(original, output, env, grant_attempted):
    current = kvm_minimal_identity()
    need(all(current[key] == original[key] for key in ("dev", "ino", "rdev")), "STOP_KVM_REPLACED_NODE_NO_REPAIR")
    need(current["uid"] in (original["uid"], os.getuid()), "STOP_KVM_FOREIGN_OWNER_NO_REPAIR")
    if current["uid"] != original["uid"]:
        need(grant_attempted and current["uid"] == os.getuid(), "STOP_KVM_UNEXPECTED_OWNER_NO_REPAIR")
        # Only revoke our own owner grant on the same character device. Group,
        # mode and xattrs may have drifted; never repair those or a replacement.
        with output.open("xb") as stream:
            completed = subprocess.run(["sudo", "-n", "chown", "--no-dereference", "--from=" + str(os.getuid()), str(original["uid"]), "/dev/kvm"],
                                       stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, env=env, timeout=20, check=False)
        need(completed.returncode == 0, "STOP_KVM_OWNER_ROLLBACK_COMMAND")
    after = kvm_minimal_identity()
    need(after["uid"] == original["uid"] and all(after[key] == original[key] for key in ("dev", "ino", "rdev")), "STOP_KVM_OWNER_ROLLBACK_UNPROVED")
    need(after["gid"] == current["gid"] and after["mode"] == current["mode"], "STOP_KVM_ROLLBACK_ADDITIONAL_DRIFT")
    return after


def kvm_evidence(snapshot):
    return {**snapshot, "xattr_read_scope": "complete root-readable attribute set; raw-value byte lengths and SHA256 match, not raw-byte memcmp",
            "ctime_equality_required": False}


def change_owner(before, old_owner, new_owner, output, env):
    # Trusted fixed hosted /dev/kvm boundary: lstat/check then pathname chown is
    # not atomic against privileged device replacement; never claim otherwise.
    same_kvm(before, kvm_snapshot(), old_owner)
    with output.open("xb") as stream:
        completed = subprocess.run(["sudo", "-n", "chown", "--no-dereference", "--from=" + str(old_owner) + ":" + str(before["gid"]), str(new_owner), "/dev/kvm"],
                                   stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, env=env, timeout=20, check=False)
    need(completed.returncode == 0, "STOP_KVM_CHOWN")
    same_kvm(before, kvm_snapshot(), new_owner)


def child_once(command, env, log, timeout, state, key):
    need(state[key + "_invocations"] == 0, "STOP_CHILD_RETRY")
    state[key + "_invocations"] = 1
    child = None
    normal_wait = False
    with log.open("xb") as stream:
        try:
            child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, env=env)
            state[key + "_stopped"] = False
            result = child.wait(timeout=timeout)
            normal_wait = True
            return result
        finally:
            if child is not None and child.poll() is None:
                # Signal only our direct Popen child. The harness owns its own
                # shutdown trap; no emulator, ADB or unrelated numeric PID kill.
                child.terminate()
                try:
                    child.wait(timeout=90)
                except subprocess.TimeoutExpired:
                    pass
            direct_stopped = child is not None and child.poll() is not None
            state[key + "_normal_wait_completed"] = normal_wait
            # SDK launchers can leave Java descendants on interrupted completion.
            # Direct-child exit alone is not installer-tree stop evidence. Retain
            # the SDK after timeout/signal/unknown spawn state rather than delete.
            state[key + "_stopped"] = direct_stopped and (normal_wait or key == "harness")


def teardown_proved(result):
    return (result.get("emulator_shutdown") == "STOPPED"
            and all(result.get(key) is True for key in (
                "owned_child_stopped", "owned_serial_absent", "owned_temporary_directory_removed", "private_adb_stopped"))
            and all(str(result.get(key)) == "0" for key in (
                "console_shutdown_exit", "owned_child_wait_exit", "private_adb_shutdown_exit", "private_adb_wait_exit")))


def remove_owned(root, parent, identity):
    nonlink_chain(parent)
    nonlink_chain(root)
    need(root.parent == parent and root.name.startswith(PREFIX) and root.resolve(strict=True) == root,
         "STOP_SDK_DELETE_PATH")
    info = root.lstat()
    need(stat.S_ISDIR(info.st_mode) and (info.st_dev, info.st_ino) == identity, "STOP_SDK_DELETE_IDENTITY")
    need(shutil.rmtree.avoids_symlink_attacks, "STOP_RMTREE_NOT_FD_SAFE")
    # fd-safe rmtree does not follow internal symlinks and never targets cache.
    shutil.rmtree(root)
    need(not root.exists() and not root.is_symlink(), "STOP_SDK_DELETE_REMAINS")


def host_guard(here):
    need(sys.platform == "linux" and os.getuid() == os.geteuid() != 0 and os.getgid() == os.getegid(), "STOP_HOST_PLATFORM")
    required = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux",
                "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_REF": BRANCH, "GITHUB_RUN_ATTEMPT": "1"}
    need(all(os.environ.get(key) == value for key, value in required.items()), "STOP_HOST_CONTEXT")
    need(os.environ.get("ImageOS") == "ubuntu24", "STOP_HOST_IMAGE")
    head = os.environ.get("APPROVED_HARNESS_COMMIT", "")
    need(re.fullmatch(r"[0-9a-f]{40}", head) and os.environ.get("GITHUB_SHA") == head, "STOP_FULL_HEAD_INPUT")
    actual = subprocess.run(["git", "--no-optional-locks", "rev-parse", "HEAD"], cwd=here,
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=True).stdout.decode().strip()
    need(actual == head, "STOP_CHECKOUT_HEAD")
    need(re.fullmatch(r"[0-9]+", os.environ.get("GITHUB_RUN_ID", "")), "STOP_RUN_ID")
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    nonlink_chain(workspace)
    need(here == workspace / "runtime-proposals/b2", "STOP_SOURCE_LOCATION")
    need(not any(os.environ.get(name) for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "SDKMANAGER_OPTS", "AVDMANAGER_OPTS", "REPO_OS_OVERRIDE")), "STOP_JAVA_OR_SDK_OPTION_ENV_PRESENT")
    for name, digest in CORE.items():
        need(sha((here / name).read_bytes()) == digest, "STOP_CORE_HASH")
    for name, variable in (("environment-approval.json", "APPROVED_ENVIRONMENT_SHA256"), ("harness-sha256.json", "APPROVED_HARNESS_MANIFEST_SHA256"), ("freeze.json", "APPROVED_FREEZE_SHA256")):
        expected = os.environ.get(variable, "")
        need(re.fullmatch(r"[0-9a-f]{64}", expected) and sha((here / name).read_bytes()) == expected, "STOP_POLICY_MANIFEST_HASH")
    manifest_record = read_json(here / "harness-sha256.json")
    need(manifest_record.get("schema") == 1 and manifest_record.get("runtime_authorized") is True, "STOP_HARNESS_MANIFEST_POLICY")
    manifest = manifest_record.get("files", {})
    need(isinstance(manifest, dict) and len(manifest) == 8 and all(isinstance(k, str) and "/" not in k and "\\" not in k and k not in (".", "..", "harness-sha256.json") for k in manifest), "STOP_HARNESS_MANIFEST_SCOPE")
    need(set(manifest) == set(CORE) | {"README.md", "provision-and-run.py", "environment-approval.json", "ENVIRONMENT-README.md"}, "STOP_HARNESS_MANIFEST_MEMBERS")
    for name, digest in manifest.items():
        need(re.fullmatch(r"[0-9a-f]{64}", digest) and sha((here / name).read_bytes()) == digest, "STOP_HARNESS_MEMBER_HASH")
    policy = read_json(here / "environment-approval.json")
    need(policy.get("schema") == 1 and policy.get("runtime_authorized") is True and policy.get("package_ids") == list(PACKAGES)
         and policy.get("no_other_software") is True and policy.get("repository") == REPOSITORY
         and policy.get("branch") == BRANCH.removeprefix("refs/heads/") and policy.get("job_attempts") == 1
         and policy.get("case_attempts") == 1 and policy.get("catalog_urls") == list(CATALOGS), "STOP_ENVIRONMENT_APPROVAL")
    kvm = policy.get("kvm", {})
    need(all(kvm.get(k) == v for k, v in {"mechanism": "temporary_owner_only", "target": "/dev/kvm", "grantee": "current_job_uid_only", "restore_required": True, "mode_group_acl_changes_allowed": False}.items()), "STOP_KVM_APPROVAL")
    need(policy.get("licenses", {}).get("existing_only") is True and policy["licenses"].get("accept_new_terms") is False
         and policy["licenses"].get("immutable_existing_bytes") is True, "STOP_LICENSE_APPROVAL")
    need(policy.get("cleanup", {}).get("sdk_root_prefix") == PREFIX and policy["cleanup"].get("private_sdk_only") is True
         and policy["cleanup"].get("after_harness_requires_actual_owned_teardown") is True, "STOP_CLEANUP_APPROVAL")
    frozen = read_json(here / "freeze.json")
    need(frozen.get("runtime_authorized") is True and frozen.get("identity_frozen") is True
         and policy.get("freeze_sha256") == CORE["freeze.json"], "STOP_FROZEN_AUTHORIZATION")
    for key in ("source_commit", "source_tree", "apks", "certificate_sha256", "fault_budget"):
        need(policy.get(key) == frozen.get(key), "STOP_FROZEN_POLICY_IDENTITY")
    need(policy.get("test_cases") == list(METHODS) and policy.get("fault_budget") == {"owner": 2, "worker": 1}, "STOP_FIXED_TEST_BUDGET")
    return workspace, head, policy


def main():
    started = time.monotonic()
    here = Path(__file__).resolve().parent
    workspace, head, policy = host_guard(here)
    out = workspace / ".build-output/b2-runtime"
    nonlink_chain(out)
    need(not out.exists(), "STOP_OUTPUT_ALREADY_EXISTS")
    out.mkdir(parents=True)
    (out / "harness-commit.txt").write_bytes((head + "\n").encode())
    for name in ("environment-approval.json", "harness-sha256.json"):
        (out / name).write_bytes((here / name).read_bytes())
    state = {"schema": 1, "status": "FAILED", "overall_exit": 1, "run_id": int(os.environ["GITHUB_RUN_ID"]),
             "harness_commit": head, "head_sha": head, "run_attempt": 1, "repository": REPOSITORY, "branch": BRANCH,
             "host_identity": {"uid": os.getuid(), "euid": os.geteuid(), "gid": os.getgid(), "egid": os.getegid(), "supplementary_groups": os.getgroups()},
             "environment_approval_sha256": sha((here / "environment-approval.json").read_bytes()),
             "freeze_sha256": CORE["freeze.json"], "harness_manifest_sha256": sha((here / "harness-sha256.json").read_bytes()),
             "fault_budget": policy["fault_budget"], "fault_count_evidence": "budget only; observed counts require original raw test traces",
             "sdk_preparation_status": "FAILED", "sdkmanager_invocations": 0, "sdkmanager_stopped": True,
             "harness_invocations": 0, "harness_stopped": True, "harness_exit": None, "harness_result_status": None,
             "harness_cleanup_confirmed": False, "sdk_root_removed": False, "cached_sources_unchanged": False,
             "license_bytes_unchanged": False, "software_scope_exact": False, "errors": [],
             "kvm": {"initial_rw": None, "grant_attempted": False, "restoration_required": False, "owner_change_confirmed": False,
                     "owner_changed": False, "owner_rollback_verified": False, "metadata_restore_verified": False,
                     "restored": False, "restore_status": "NOT_ATTEMPTED",
                     "owner_control_boundary": "temporary owner also controls the node; wrapper only changes/restores owner",
                     "identity_check_use_boundary": "trusted fixed hosted device; pathname check/chown is not atomic",
                     "ctime_equality_required": False},
             "trust_boundary": "wrapper provisions two official packages; cached-image.json no-provisioning wording applies only to the unchanged inner harness"}
    root = None
    identity = None
    parent = None
    cache = None
    source_before = {}
    copied_before = {}
    kvm_before = None
    harness_result = None
    cleanup_errors = []
    preparation_env = None

    def interrupted(signum, frame):
        raise Stop("STOP_SIGNAL_" + str(signum))

    previous_handlers = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        parent = Path(os.environ["RUNNER_TEMP"])
        nonlink_chain(parent)
        need(parent.is_dir() and parent.resolve(strict=True) == parent, "STOP_RUNNER_TEMP")
        root = Path(tempfile.mkdtemp(prefix=PREFIX, dir=parent))
        info = root.lstat()
        identity = (info.st_dev, info.st_ino)
        state["sdk_preparation_root"] = str(root)
        sdk = root / "sdk"
        sdk.mkdir(mode=0o700)
        cache_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        need(cache_value, "STOP_CACHED_SDK_MISSING")
        cache = Path(cache_value).resolve(strict=True)
        need(cache.is_dir() and cache != sdk and cache not in root.parents and root not in cache.parents, "STOP_CACHED_SDK_PATH")
        java = Path(os.environ.get("JAVA_HOME_17_X64", ""))
        need(java.is_absolute() and (java / "bin/java").is_file() and java not in root.parents, "STOP_CACHED_JDK17")
        need(re.search(r'^JAVA_VERSION="17(?:\.|\")', (java / "release").read_text(), re.MULTILINE), "STOP_CACHED_JDK_VERSION")
        for relative in CACHE_TREES:
            source = cache / relative
            if relative == "licenses":
                need(not source.is_symlink() and all(not p.is_symlink() and p.is_file() for p in source.iterdir()), "STOP_LICENSE_COLLECTION")
            source_before[relative] = tree_bytes(source, cache)
            destination = sdk / relative
            destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            copy_tree(source, destination, cache, readonly=relative == "licenses")
            copied_before[relative] = tree_bytes(destination)
            need(source_before[relative] == copied_before[relative], "STOP_CACHE_COPY_BYTES")
        need(len(source_before["licenses"]) > 1, "STOP_EXISTING_LICENSES_MISSING")
        for relative in ("platform-tools/adb", "cmdline-tools/latest/bin/sdkmanager", "cmdline-tools/latest/bin/avdmanager", "build-tools/36.0.0/aapt2", "build-tools/36.0.0/apksigner"):
            need((sdk / relative).is_file() and os.access(sdk / relative, os.X_OK), "STOP_CACHED_TOOL_MISSING")
        before_packages = inventory(sdk)
        cmdline_names = [name for name in before_packages if re.fullmatch(r"cmdline-tools;(?:latest|[0-9]+(?:\.[0-9]+)*)", name)]
        need(len(cmdline_names) == 1 and set(before_packages) == {cmdline_names[0], "platform-tools", "build-tools;36.0.0"}, "STOP_CACHED_PACKAGE_INVENTORY")
        state["cached_packages"] = before_packages
        write_json(out / "cached-byte-inventory.json", {"source_sdk": str(cache), "source_before": source_before,
                                                        "private_copy_before": copied_before, "copy_scope": "independent regular bytes; no cache hardlinks or symlinks"})
        state["cached_byte_inventory_sha256"] = sha((out / "cached-byte-inventory.json").read_bytes())
        selected = {}
        raw_total = 0
        for index, url in enumerate(CATALOGS):
            raw = fetch_catalog(url)
            raw_total += len(raw)
            need(raw_total < 28 * 1024 * 1024, "STOP_METADATA_TOTAL_SIZE")
            name = "official-repository.xml" if index == 0 else "official-system-image.xml"
            (out / name).write_bytes(raw)
            selected[PACKAGES[index]] = select_package(xml(raw, NS_ROOT[index]), PACKAGES[index], url)
        dependency_gate(selected, before_packages)
        state["selected_official_packages"] = selected
        write_json(out / "official-package-selection.json", selected)
        private_home = root / "java-user-home"
        private_android = root / "android-user-home"
        private_home.mkdir(mode=0o700)
        private_android.mkdir(mode=0o700)
        preparation_env = os.environ.copy()
        preparation_env.pop("GH_TOKEN", None)
        preparation_env.pop("GITHUB_TOKEN", None)
        preparation_env.update({"ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": str(sdk), "ANDROID_USER_HOME": str(private_android),
                                "ANDROID_EMULATOR_HOME": str(private_android), "ANDROID_AVD_HOME": str(private_android / "avd"),
                                "ANDROID_SDK_HOME": str(root), "JAVA_HOME": str(java), "JAVA_OPTS": "-Duser.home=" + str(private_home),
                                "PATH": str(java / "bin") + os.pathsep + os.environ.get("PATH", "")})
        state["sdkmanager_exit"] = child_once([str(sdk / "cmdline-tools/latest/bin/sdkmanager"), "--sdk_root=" + str(sdk), "--channel=0", "--install", *PACKAGES],
                                               preparation_env, out / "sdkmanager.log", 600, state, "sdkmanager")
        need(state["sdkmanager_exit"] == 0 and state["sdkmanager_stopped"], "STOP_SDKMANAGER_FAILED")
        after_packages = inventory(sdk)
        need(set(after_packages) == set(before_packages) | set(PACKAGES), "STOP_INSTALLED_SOFTWARE_SCOPE")
        need(all(after_packages[key] == value for key, value in before_packages.items()), "STOP_CACHED_PACKAGE_CHANGED")
        for name in PACKAGES:
            need(after_packages[name]["revision"] == selected[name]["revision"], "STOP_INSTALLED_REVISION_CHANGED")
            need(after_packages[name]["dependencies"] == selected[name]["dependencies"], "STOP_INSTALLED_DEPENDENCIES_CHANGED")
        actual_metadata = {}
        for role, name in (("emulator", PACKAGES[0]), ("api33-image", PACKAGES[1])):
            actual_metadata[name] = {}
            for metadata_name in ("package.xml", "source.properties"):
                origin = sdk.joinpath(*name.split(";")) / metadata_name
                need(origin.is_file() and not origin.is_symlink(), "STOP_INSTALLED_METADATA_MISSING")
                raw = origin.read_bytes()
                saved_name = "installed-" + role + "-" + metadata_name
                (out / saved_name).write_bytes(raw)
                actual_metadata[name][metadata_name] = {"path": saved_name, "bytes": len(raw), "sha256": sha(raw)}
        state["installed_metadata"] = actual_metadata
        image = sdk / "system-images/android-33/default/x86_64"
        local_image = one(xml((image / "package.xml").read_bytes()), "localPackage")
        details = one(local_image, "type-details")
        need(number(details, "api-level") == 33 and text_of(details, "abi") == "x86_64"
             and text_of(one(details, "tag"), "id") == "default", "STOP_INSTALLED_IMAGE_DETAILS")
        need((image / "system.img").is_file() and not (image / "system.img").is_symlink() and (image / "system.img").stat().st_size > 0
             and (sdk / "emulator/emulator").is_file() and not (sdk / "emulator/emulator").is_symlink()
             and os.access(sdk / "emulator/emulator", os.X_OK), "STOP_INSTALLED_PAYLOAD_MISSING")
        for relative in CACHE_TREES:
            need(tree_bytes(cache / relative, cache) == source_before[relative] and tree_bytes(sdk / relative) == copied_before[relative], "STOP_CACHE_OR_LICENSE_BYTES_CHANGED")
        state.update(sdk_preparation_status="VERIFIED_TWO_OFFICIAL_PACKAGES", installed_packages=after_packages,
                     software_scope_exact=True, cached_sources_unchanged=True, license_bytes_unchanged=True)
        kvm_before = kvm_snapshot()
        need(kvm_before["uid"] == 0 and kvm_before["mode"] & 0o600 == 0o600, "STOP_KVM_INITIAL_OWNER_MODE")
        state["kvm"]["before"] = kvm_evidence(kvm_before)
        state["kvm"]["initial_rw"] = os.access("/dev/kvm", os.R_OK | os.W_OK)
        if not state["kvm"]["initial_rw"]:
            # Mark restoration required before invoking a mutating subprocess:
            # even an interrupted chown may have changed the owned device.
            state["kvm"]["grant_attempted"] = True
            state["kvm"]["restoration_required"] = True
            change_owner(kvm_before, 0, os.getuid(), out / "kvm-grant.log", preparation_env)
            state["kvm"]["owner_change_confirmed"] = True
            state["kvm"]["owner_changed"] = True
        else:
            same_kvm(kvm_before, kvm_snapshot(), 0)
        need(os.access("/dev/kvm", os.R_OK | os.W_OK), "STOP_KVM_ACCESS")
        state["kvm"]["after_grant"] = kvm_evidence(kvm_snapshot())
        harness_env = os.environ.copy()
        harness_env.update({key: value for key, value in preparation_env.items() if key in (
            "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_USER_HOME", "ANDROID_EMULATOR_HOME", "ANDROID_AVD_HOME", "ANDROID_SDK_HOME", "JAVA_HOME", "JAVA_OPTS", "PATH")})
        # Apply an internal conservative 30-minute budget, leaving five minutes
        # for owned-child shutdown, KVM restoration and evidence. This is shorter
        # than the outer workflow timeout; it is not a claimed user time limit.
        # A short remainder stops before invoking the fault harness.
        harness_timeout = min(1200, int(1500 - (time.monotonic() - started)))
        need(harness_timeout >= 600, "STOP_INSUFFICIENT_CLEANUP_TIME_BUDGET")
        state["harness_timeout_seconds"] = harness_timeout
        state["harness_exit"] = child_once(["bash", str(here / "b2-runtime-api33.sh")], harness_env, out / "harness-wrapper.log", harness_timeout, state, "harness")
        need(state["harness_stopped"], "STOP_HARNESS_CHILD_NOT_STOPPED")
        result_path = out / "result.json"
        need(result_path.is_file() and not result_path.is_symlink(), "STOP_HARNESS_RESULT_MISSING")
        result_raw = result_path.read_bytes()
        (out / "harness-result.json").write_bytes(result_raw)
        state["harness_result_sha256"] = sha(result_raw)
        harness_result = read_json(result_path)
        state["harness_result_status"] = harness_result.get("status")
        state["harness_cleanup_confirmed"] = teardown_proved(harness_result)
        need(state["harness_cleanup_confirmed"], "STOP_HARNESS_TEARDOWN_UNPROVED")
        need(state["harness_exit"] == 0 and harness_result.get("status") == "PASS" and harness_result.get("overall_exit") == 0, "STOP_HARNESS_FAILED")
    except Exception as exc:
        state["errors"].append(str(exc) if isinstance(exc, Stop) else "STOP_EXCEPTION_" + type(exc).__name__)
    finally:
        # Avoid a second signal interrupting restoration. Do not send arbitrary
        # signals to emulator/ADB; only the immutable harness owns their teardown.
        for sig in previous_handlers:
            signal.signal(sig, signal.SIG_IGN)
        if kvm_before is not None:
            try:
                if state["kvm"]["restoration_required"]:
                    minimal = restore_owner_only(kvm_before, out / "kvm-restore.log", preparation_env, state["kvm"]["grant_attempted"])
                else:
                    # No grant means no restoration mutation, even if an outside
                    # actor subsequently changed ownership to this job UID.
                    minimal = kvm_minimal_identity()
                    need(minimal["uid"] == kvm_before["uid"] and all(minimal[key] == kvm_before[key] for key in ("dev", "ino", "rdev")), "STOP_KVM_UNGRANTED_IDENTITY_CHANGED")
                state["kvm"].update(owner_rollback_verified=True, owner_rollback_identity=minimal,
                                     owner_rollback_command_invoked=(out / "kvm-restore.log").is_file(),
                                     restore_status="OWNER_ROLLBACK_VERIFIED_FULL_METADATA_PENDING")
                restored_snapshot = kvm_snapshot()
                same_kvm(kvm_before, restored_snapshot, kvm_before["uid"])
                state["kvm"].update(restored=True, metadata_restore_verified=True, restore_status="VERIFIED_ORIGINAL_OWNER_GROUP_MODE_XATTRS", after_restore=kvm_evidence(restored_snapshot))
            except Exception as exc:
                cleanup_errors.append(str(exc) if isinstance(exc, Stop) else "STOP_KVM_RESTORE_EXCEPTION")
                state["kvm"]["restore_status"] = "OWNER_ROLLBACK_VERIFIED_METADATA_NOT_RESTORED" if state["kvm"]["owner_rollback_verified"] else "FAILED"
        # A timeout/interruption can bypass normal result handling. Preserve its
        # raw receipt before writing a global result and only use actual teardown.
        if state["harness_invocations"] == 1 and harness_result is None:
            try:
                candidate = out / "result.json"
                if candidate.is_file() and not candidate.is_symlink():
                    raw = candidate.read_bytes()
                    (out / "harness-result.json").write_bytes(raw)
                    state["harness_result_sha256"] = sha(raw)
                    harness_result = read_json(candidate)
                    state["harness_result_status"] = harness_result.get("status")
                    state["harness_cleanup_confirmed"] = teardown_proved(harness_result)
            except Exception:
                cleanup_errors.append("STOP_HARNESS_RESULT_CAPTURE")
        if root is not None:
            try:
                if cache is not None and len(source_before) == len(CACHE_TREES):
                    state["cached_sources_unchanged"] = all(tree_bytes(cache / relative, cache) == source_before[relative] for relative in CACHE_TREES)
                    state["license_bytes_unchanged"] = (tree_bytes(cache / "licenses", cache) == source_before["licenses"]
                                                        and tree_bytes(root / "sdk/licenses") == copied_before["licenses"])
                    need(state["cached_sources_unchanged"] and state["license_bytes_unchanged"], "STOP_FINAL_CACHE_LICENSE_CHANGED")
            except Exception as exc:
                cleanup_errors.append(str(exc) if isinstance(exc, Stop) else "STOP_FINAL_CACHE_LICENSE_EXCEPTION")
            # Verification failures still fail the result, but do not suppress
            # independently safe removal of this minted, fully stopped root.
            try:
                stopped = state["sdkmanager_stopped"] and state["harness_stopped"]
                allowed = state["harness_invocations"] == 0 or state["harness_cleanup_confirmed"]
                need(stopped and allowed, "STOP_RETAIN_SDK_UNPROVED_TEARDOWN")
                remove_owned(root, parent, identity)
                state["sdk_root_removed"] = True
            except Exception as exc:
                cleanup_errors.append(str(exc) if isinstance(exc, Stop) else "STOP_SDK_CLEANUP_EXCEPTION")
        state["errors"].extend(cleanup_errors)
        success = (not state["errors"] and state["sdk_preparation_status"] == "VERIFIED_TWO_OFFICIAL_PACKAGES"
                   and state["harness_invocations"] == 1 and state["harness_exit"] == 0 and state["harness_result_status"] == "PASS"
                   and state["harness_cleanup_confirmed"] and state["kvm"]["restored"] and state["sdk_root_removed"]
                   and state["cached_sources_unchanged"] and state["license_bytes_unchanged"] and state["software_scope_exact"])
        state["status"] = "PASS" if success else "FAILED"
        state["overall_exit"] = 0 if success else 1
        write_json(out / "environment-preparation.json", state)
        global_result = dict(harness_result or {})
        global_result.update(status=state["status"], overall_exit=state["overall_exit"], result_scope="harness plus approved environment preparation and outer cleanup",
                             environment_preparation={"path": "environment-preparation.json", "sha256": sha((out / "environment-preparation.json").read_bytes()),
                                                      "status": state["status"], "errors": state["errors"]},
                             original_harness_result={"path": "harness-result.json", "sha256": state.get("harness_result_sha256"), "status": state["harness_result_status"]})
        write_json(out / "result.json", global_result)
        for sig, handler in previous_handlers.items():
            signal.signal(sig, handler)
    return state["overall_exit"]


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        # Never stringify arbitrary errors (URLs/environment/token may appear).
        print(str(error) if isinstance(error, Stop) else "STOP_HOST_EXCEPTION_" + type(error).__name__, file=sys.stderr)
        sys.exit(1)
