#!/usr/bin/env python3
"""Install one reviewed Ubuntu library closure, once, on the exact hosted job.

This source is not an installation receipt. Only a successful actual simulation,
installation wait, registered-package comparison, service comparison and static
file inspection can produce VERIFIED_STANDARD_RUNTIME_LIBRARIES. No SDK, loader,
repository update, package removal, retry or package rollback is performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import struct
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit

REPOSITORY = "gccwebos-com/gccwebos-android"
BRANCH = "refs/heads/codex/lucky-b2-system-runtime"
HOSTS = ["archive.ubuntu.com", "azure.archive.ubuntu.com", "security.ubuntu.com"]
KEYRING = Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg")
MIRROR_FILE = Path("/etc/apt/apt-mirrors.txt")
MIRROR_URI = "mirror+file:/etc/apt/apt-mirrors.txt"
REPOSITORY_URI_PATTERN = r"(?:https?://\S+|mirror\+file:/etc/apt/apt-mirrors\.txt)"
MEMBERS = {"ENVIRONMENT-README.md", "README.md", "b2-runtime-api33.sh",
           "b2-runtime-evidence.py", "environment-approval.json", "freeze.json",
           "proposed-workflow.yml.disabled", "provision-and-run.py",
           "prepare-system-libraries.py"}
FALSE_FLAGS = {"allow_index_update", "allow_upgrade", "allow_remove",
               "install_recommends", "allow_services", "accept_new_terms"}
PATH = "/usr/sbin:/usr/bin:/sbin:/bin"
CLEAN_ENV = {"PATH": PATH, "LC_ALL": "C", "LANG": "C"}
LIMIT = 4 * 1024 * 1024
STATE = None
OUTPUT = None
DEADLINE = None
ROOT_MARKER = "B2_SYSTEM_LIBRARY_ROOT_PROOF="
ROOT_PROGRAM = r'''
import hashlib,json,os,re,signal,stat,subprocess,sys
MARKER="B2_SYSTEM_LIBRARY_ROOT_PROOF="
VETO="/usr/sbin/policy-rc.d"
LITERAL=b"#!/bin/sh\nexit 101\n"
ENV={"PATH":"/usr/sbin:/usr/bin:/sbin:/bin","LC_ALL":"C","LANG":"C",
     "DEBIAN_FRONTEND":"noninteractive","SYSTEMD_OFFLINE":"1"}
OPTIONS=["-o","Dir::Cache::pkgcache=","-o","Dir::Cache::srcpkgcache=",
         "-o","Dir::Etc::Parts=/dev/null","-o","Dir::Etc::main=/dev/null",
         "-o","APT::Get::AllowUnauthenticated=false","-o","Acquire::AllowInsecureRepositories=false",
         "-o","Acquire::AllowDowngradeToInsecureRepositories=false","-o","APT::Get::allow-downgrades=false"]
proof={"schema":1,"protocol":"B2_SYSTEM_LIBRARY_ROOT_PROOF_V1","status":"FAILED",
       "approved_pins":sys.argv[1:],"root_uid":os.getuid(),"root_euid":os.geteuid(),
       "apt_spawn_attempted":False,"apt_install_invocations":0,"apt_exit":None,
       "apt_install_normal_wait_completed":False,"service_veto_verified":False,
       "service_veto_restored":False,"veto_created":False,"veto_retained":False,"errors":[]}
identity=None
class Stop(Exception):pass
def need(ok,code):
    if not ok:raise Stop(code)
def snapshot():
    info=os.lstat(VETO)
    need(stat.S_ISREG(info.st_mode) and info.st_uid==0 and info.st_gid==0
         and stat.S_IMODE(info.st_mode)==0o755 and info.st_nlink==1,"STOP_VETO_METADATA")
    fd=os.open(VETO,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC)
    try:
        opened=os.fstat(fd)
        need((opened.st_dev,opened.st_ino)==(info.st_dev,info.st_ino),"STOP_VETO_IDENTITY_RACE")
        raw=os.read(fd,len(LITERAL)+1)
        need(raw==LITERAL,"STOP_EXISTING_VETO_NOT_EXACT_DENY")
    finally:os.close(fd)
    return {"dev":info.st_dev,"ino":info.st_ino,"type":"regular","uid":info.st_uid,
            "gid":info.st_gid,"mode":stat.S_IMODE(info.st_mode),"nlink":info.st_nlink,
            "bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()}
def interrupted(signum,frame):raise Stop("STOP_ROOT_SIGNAL_"+str(signum))
signal.signal(signal.SIGTERM,interrupted)
signal.signal(signal.SIGINT,interrupted)
try:
    need(sys.platform=="linux" and os.getuid()==os.geteuid()==0 and os.getcwd()=="/","STOP_ROOT_CONTEXT")
    pins=sys.argv[1:]
    need(1<=len(pins)<=16 and len(set(pins))==len(pins),"STOP_ROOT_PIN_COUNT")
    names=set()
    for pin in pins:
        match=re.fullmatch(r"(lib[a-z0-9][a-z0-9+.-]*):amd64=([0-9][A-Za-z0-9.+:~_-]*)",pin)
        need(match is not None,"STOP_ROOT_PIN_FORMAT")
        name=match[1]
        need(name not in names and not re.search(r"(?:^|-)(?:dev|dbg|dbgsym|tools?|utils?|bin|data|doc|common|examples?|tests?|daemon|services?)(?:$|-)",name),"STOP_ROOT_PIN_SCOPE")
        names.add(name)
    need("libpulse0" in names,"STOP_ROOT_REQUESTED_PACKAGE")
    for directory in ("/","/usr","/usr/sbin"):
        info=os.lstat(directory)
        need(stat.S_ISDIR(info.st_mode) and info.st_uid==0 and not info.st_mode&0o022,"STOP_VETO_PARENT")
    try:
        os.lstat(VETO)
    except FileNotFoundError:
        fd=os.open(VETO,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW|os.O_CLOEXEC,0o755)
        proof["veto_created"]=True
        try:
            need(os.write(fd,LITERAL)==len(LITERAL),"STOP_VETO_SHORT_WRITE")
            os.fchown(fd,0,0)
            os.fchmod(fd,0o755)
            os.fsync(fd)
        finally:os.close(fd)
    identity=snapshot()
    proof["service_veto_verified"]=True
    proof["veto_identity"]=identity
    apt=["/usr/bin/apt-get",*OPTIONS,"--no-install-recommends","--no-upgrade","--no-remove","-y","install",*pins]
    proof["apt_spawn_attempted"]=True
    proof["apt_install_invocations"]=None
    try:
        child=subprocess.Popen(apt,stdin=subprocess.DEVNULL,stdout=sys.stdout,stderr=sys.stderr,
                               env=ENV,cwd="/",close_fds=True)
    except OSError:
        proof["apt_install_invocations"]=0
        raise Stop("STOP_ROOT_APT_SPAWN")
    proof["apt_install_invocations"]=1
    proof["apt_exit"]=child.wait(timeout=250)
    proof["apt_install_normal_wait_completed"]=True
    need(proof["apt_exit"]==0,"STOP_ROOT_APT_NONZERO")
except BaseException as error:
    proof["errors"].append(str(error) if isinstance(error,Stop) else "STOP_ROOT_EXCEPTION_"+type(error).__name__)
finally:
    # Unknown root APT completion retains the veto. No arbitrary PID/tree signal,
    # retry, uninstall or rollback can supply missing completion evidence.
    try:
        if identity is not None:
            need(snapshot()==identity,"STOP_VETO_CHANGED")
            if proof["apt_install_normal_wait_completed"]:
                if proof["veto_created"]:
                    os.unlink(VETO)
                    try:os.lstat(VETO)
                    except FileNotFoundError:pass
                    else:raise Stop("STOP_VETO_REMOVAL_NOT_CONFIRMED")
                else:need(snapshot()==identity,"STOP_EXISTING_VETO_CHANGED")
                proof["service_veto_restored"]=True
            else:proof["veto_retained"]=True
        elif proof["veto_created"]:
            proof["veto_retained"]=True
    except BaseException as error:
        proof["errors"].append(str(error) if isinstance(error,Stop) else "STOP_ROOT_RESTORE_"+type(error).__name__)
    if (not proof["errors"] and proof["apt_install_invocations"]==1 and proof["apt_exit"]==0
        and proof["apt_install_normal_wait_completed"] and proof["service_veto_verified"]
        and proof["service_veto_restored"]):proof["status"]="VERIFIED_VETO_AND_APT_WAIT"
    sys.stderr.write("\n"+MARKER+json.dumps(proof,sort_keys=True,separators=(",",":"))+"\n")
    sys.stderr.flush()
sys.exit(0 if proof["status"]=="VERIFIED_VETO_AND_APT_WAIT" else 1)
'''


def root_proof(raw, record):
    STATE["apt_install_invocations"] = None
    STATE["apt_install_normal_wait_completed"] = False
    STATE["service_veto_verified"] = False
    STATE["service_veto_restored"] = False
    markers = re.findall(rb"^B2_SYSTEM_LIBRARY_ROOT_PROOF=(\{[^\r\n]*\})$", raw, re.MULTILINE)
    need(len(markers) == 1, "STOP_ROOT_PROOF_MISSING_OR_DUPLICATE")
    proof = json.loads(markers[0], object_pairs_hook=unique)
    expected = [pin["name"] + ":amd64=" + pin["version"] for pin in STATE["approved_new_packages"]]
    need(isinstance(proof, dict) and proof.get("schema") == 1
         and proof.get("protocol") == "B2_SYSTEM_LIBRARY_ROOT_PROOF_V1"
         and proof.get("approved_pins") == expected
         and type(proof.get("root_uid")) is int and proof["root_uid"] == 0
         and type(proof.get("root_euid")) is int and proof["root_euid"] == 0,
         "STOP_ROOT_PROOF_IDENTITY")
    need(proof.get("apt_install_invocations") is None or (type(proof["apt_install_invocations"]) is int
         and proof["apt_install_invocations"] in (0, 1)), "STOP_ROOT_PROOF_APT_COUNT")
    need(proof.get("apt_exit") is None or type(proof["apt_exit"]) is int, "STOP_ROOT_PROOF_APT_EXIT")
    for key in ("apt_install_normal_wait_completed", "service_veto_verified", "service_veto_restored",
                "veto_created", "veto_retained", "apt_spawn_attempted"):
        need(type(proof.get(key)) is bool, "STOP_ROOT_PROOF_BOOLEAN")
    STATE["root_install_proof"] = {"record": proof, "marker_sha256": sha(markers[0]),
                                    "raw_stderr": record["stderr"]}
    for key in ("apt_install_invocations", "apt_install_normal_wait_completed",
                "service_veto_verified", "service_veto_restored"):
        STATE[key] = proof[key]
    STATE["apt_install_state"] = ("COMPLETED" if proof["apt_install_normal_wait_completed"]
                                  else "FAILED_OR_UNCONFIRMED")
    need(record["normal_wait_completed"] and record["output_bounded"] and record["exit"] == 0
         and proof.get("status") == "VERIFIED_VETO_AND_APT_WAIT" and proof.get("errors") == []
         and proof["apt_install_invocations"] == 1 and proof["apt_exit"] == 0
         and proof["apt_install_normal_wait_completed"] and proof["service_veto_verified"]
         and proof["service_veto_restored"], "STOP_ROOT_VETO_OR_INSTALL_PROOF_FAILED")


class Stop(Exception):
    pass


def need(ok, code):
    if not ok:
        raise Stop(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def nonlink(path):
    need(path.is_absolute() and all(not p.is_symlink() for p in (path, *path.parents)),
         "STOP_PATH_SYMLINK_OR_RELATIVE")


def bounded(path, limit=LIMIT):
    with path.open("rb") as stream:
        raw = stream.read(limit + 1)
    need(len(raw) <= limit, "STOP_INPUT_SIZE")
    return raw


def unique(pairs):
    obj = {}
    for key, value in pairs:
        need(key not in obj, "STOP_DUPLICATE_JSON_KEY")
        obj[key] = value
    return obj


def read_json(path):
    value = json.loads(bounded(path), object_pairs_hook=unique)
    need(isinstance(value, dict), "STOP_JSON_OBJECT")
    return value


def save(name, raw):
    need(re.fullmatch(r"system-library-[A-Za-z0-9_.-]+", name), "STOP_LOG_NAME")
    path = OUTPUT / name
    nonlink(path)
    with path.open("xb") as stream:
        stream.write(raw)
    return {"path": name, "bytes": len(raw), "sha256": sha(raw)}


def receipt():
    path = OUTPUT / "system-library-preparation.json"
    nonlink(path)
    temporary = path.with_name(path.name + ".tmp")
    nonlink(temporary)
    with temporary.open("xb") as stream:
        stream.write((json.dumps(STATE, sort_keys=True, indent=2) + "\n").encode())
    os.replace(temporary, path)


def command(label, argv, timeout=30, install=False, env=None):
    """Bound both raw streams. A timed-out root install is retained as UNKNOWN.

    No signal is sent to a root APT PID/tree. Waiting for the helper itself is
    not evidence that a timed-out sudo/APT tree completed normally.
    """
    need(re.fullmatch(r"[a-z0-9-]+", label), "STOP_COMMAND_LABEL")
    timeout = min(timeout, DEADLINE - time.monotonic())
    need(timeout > 0, "STOP_HELPER_DEADLINE")
    if install:
        STATE["root_wrapper_invocations"] += 1
        need(STATE["root_wrapper_invocations"] == 1, "STOP_INSTALL_REPEAT")
        STATE["apt_install_invocations"] = None
        STATE["apt_install_state"] = "START_ATTEMPTED"
        receipt()
    started = time.monotonic()
    process = None
    captured = [bytearray(), bytearray()]
    overflow = threading.Event()
    threads = []
    record = {"label": label, "argv": argv, "normal_wait_completed": False,
              "exit": None, "output_bounded": False, "installation": install}

    def drain(stream, sink):
        try:
            while True:
                block = stream.read(65536)
                if not block:
                    break
                remaining = LIMIT - len(sink)
                sink.extend(block[:max(0, remaining)])
                if len(block) > remaining:
                    overflow.set()
        finally:
            stream.close()

    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env=env or CLEAN_ENV,
                                   close_fds=True, cwd="/" if install else None)
        if install:
            STATE["apt_install_state"] = "RUNNING"
        for pipe, sink in zip((process.stdout, process.stderr), captured):
            worker = threading.Thread(target=drain, args=(pipe, sink), daemon=True)
            worker.start()
            threads.append(worker)
        while process.poll() is None:
            need(not overflow.is_set(), "STOP_COMMAND_OUTPUT_LIMIT")
            need(time.monotonic() - started < timeout, "STOP_COMMAND_TIMEOUT")
            time.sleep(0.05)
        record["exit"] = process.wait()
        record["normal_wait_completed"] = True
        for worker in threads:
            worker.join(5)
        need(all(not worker.is_alive() for worker in threads), "STOP_COMMAND_STREAM_NOT_CLOSED")
        need(not overflow.is_set(), "STOP_COMMAND_OUTPUT_LIMIT")
        record["output_bounded"] = True
        need(record["exit"] == 0, "STOP_COMMAND_NONZERO_" + label.upper().replace("-", "_"))
        return bytes(captured[0]).decode("utf-8", errors="strict")
    finally:
        if process is not None and process.poll() is None and not install:
            # These are our direct, unprivileged, read-only children only.
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if install:
            STATE["root_wrapper_normal_wait_completed"] = bool(
                record["normal_wait_completed"] and record["output_bounded"])
        record["stdout"] = save("system-library-" + label + "-stdout.log", bytes(captured[0]))
        record["stderr"] = save("system-library-" + label + "-stderr.log", bytes(captured[1]))
        STATE["commands"].append(record)
        if install:
            try:
                root_proof(bytes(captured[1]), record)
                STATE["root_proof_verified"] = True
            except Exception as error:
                STATE["root_proof_verified"] = False
                STATE["errors"].append(str(error) if isinstance(error, Stop)
                                       else "STOP_ROOT_PROOF_EXCEPTION_" + type(error).__name__)
        receipt()


def package_name(name):
    need(isinstance(name, str) and re.fullmatch(r"lib[a-z0-9][a-z0-9+.-]*", name),
         "STOP_NON_LIBRARY_PACKAGE_NAME")
    need(not re.search(r"(?:^|-)(?:dev|dbg|dbgsym|tools?|utils?|bin|data|doc|common|examples?|tests?|daemon|services?)(?:$|-)", name),
         "STOP_NON_RUNTIME_LIBRARY_PACKAGE")
    return name


def context(here, policy_path):
    need(sys.platform == "linux" and os.getuid() == os.geteuid() != 0
         and os.getgid() == os.getegid() and os.uname().machine == "x86_64", "STOP_HOST_PLATFORM")
    required = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                "RUNNER_OS": "Linux", "ImageOS": "ubuntu24", "GITHUB_REPOSITORY": REPOSITORY,
                "GITHUB_REF": BRANCH, "GITHUB_RUN_ATTEMPT": "1"}
    need(all(os.environ.get(k) == v for k, v in required.items()), "STOP_HOST_CONTEXT")
    need(not any(os.environ.get(k) for k in ("GH_TOKEN", "GITHUB_TOKEN", "LD_PRELOAD", "LD_LIBRARY_PATH")),
         "STOP_TOKEN_OR_LOADER_ENV")
    head = os.environ.get("APPROVED_HARNESS_COMMIT", "")
    need(re.fullmatch(r"[0-9a-f]{40}", head) and os.environ.get("GITHUB_SHA") == head, "STOP_HEAD")
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    nonlink(workspace)
    need(here == workspace / "runtime-proposals/b2" and policy_path == here / "environment-approval.json"
         and OUTPUT == workspace / ".build-output/b2-runtime", "STOP_SOURCE_OR_OUTPUT_LOCATION")
    need(re.fullmatch(r"[1-9][0-9]*", os.environ.get("GITHUB_RUN_ID", "")), "STOP_RUN_ID")
    STATE.update(run_id=int(os.environ["GITHUB_RUN_ID"]), head_sha=head, run_attempt=1)
    for name, variable in (("environment-approval.json", "APPROVED_ENVIRONMENT_SHA256"),
                           ("harness-sha256.json", "APPROVED_HARNESS_MANIFEST_SHA256")):
        path = here / name
        nonlink(path)
        expected = os.environ.get(variable, "")
        need(re.fullmatch(r"[0-9a-f]{64}", expected) and sha(bounded(path)) == expected, "STOP_APPROVED_HASH")
    STATE["environment_approval_sha256"] = sha(bounded(policy_path))
    manifest = read_json(here / "harness-sha256.json")
    need(manifest.get("schema") == 1 and manifest.get("runtime_authorized") is True
         and isinstance(manifest.get("files"), dict) and set(manifest["files"]) == MEMBERS,
         "STOP_NINE_FILE_MANIFEST")
    for name, expected in manifest["files"].items():
        path = here / name
        nonlink(path)
        need(isinstance(expected, str) and re.fullmatch(r"[0-9a-f]{64}", expected)
             and sha(bounded(path)) == expected, "STOP_HARNESS_MEMBER_HASH")
    need(command("checkout-head", ["/usr/bin/git", "--no-optional-locks", "-C", str(here),
                                   "rev-parse", "HEAD"]).strip() == head, "STOP_CHECKOUT_HEAD")
    policy = read_json(policy_path)
    need(policy.get("schema") == 1 and policy.get("runtime_authorized") is True
         and policy.get("repository") == REPOSITORY and policy.get("branch") == BRANCH.removeprefix("refs/heads/"),
         "STOP_ENVIRONMENT_POLICY")
    scope = policy.get("system_runtime_libraries")
    need(isinstance(scope, dict) and scope.get("authorized") is True, "STOP_LIBRARY_AUTHORIZATION")
    need(all(scope.get(k) is False for k in FALSE_FLAGS)
         and scope.get("allowed_existing_repository_hosts") == HOSTS
         and scope.get("requested_package") == "libpulse0:amd64", "STOP_LIBRARY_POLICY_SCOPE")
    distro = scope.get("distro")
    need(distro == {"id": "ubuntu", "version_id": "24.04", "architecture": "amd64", "machine": "x86_64"},
         "STOP_DISTRO_POLICY")
    actual = {}
    for line in bounded(Path("/etc/os-release"), 65536).decode().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            actual[key] = value.strip('"')
    need(actual.get("ID") == "ubuntu" and actual.get("VERSION_ID") == "24.04"
         and command("architecture", ["/usr/bin/dpkg", "--print-architecture"]).strip() == "amd64",
         "STOP_DISTRO_ACTUAL")
    pins = scope.get("packages")
    need(isinstance(pins, list) and 1 <= len(pins) <= 16, "STOP_LIBRARY_PINS")
    seen = set()
    for pin in pins:
        need(isinstance(pin, dict) and set(pin) == {"name", "version", "architecture"}, "STOP_PIN_SCHEMA")
        name = package_name(pin["name"])
        need(name not in seen and pin["architecture"] == "amd64"
             and isinstance(pin["version"], str) and re.fullmatch(r"[0-9][A-Za-z0-9.+:~_-]*", pin["version"]),
             "STOP_LIBRARY_PIN_IDENTITY")
        seen.add(name)
    need("libpulse0" in seen, "STOP_REQUESTED_LIBRARY_ABSENT")
    diagnostic = scope.get("source_diagnostic")
    need(isinstance(diagnostic, dict) and set(diagnostic) == {"run_id", "head_sha", "artifact_sha256"}
         and type(diagnostic["run_id"]) is int and diagnostic["run_id"] > 0
         and isinstance(diagnostic["head_sha"], str) and re.fullmatch(r"[0-9a-f]{40}", diagnostic["head_sha"])
         and isinstance(diagnostic["artifact_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", diagnostic["artifact_sha256"]),
         "STOP_SOURCE_DIAGNOSTIC")
    STATE.update(approved_new_packages=pins, source_diagnostic=diagnostic, distro=distro,
                 helper_sha256=sha(bounded(here / "prepare-system-libraries.py")))
    return pins


def inventory(label):
    text = command(label, ["/usr/bin/dpkg-query", "-W", "-f=${Package}\t${Architecture}\t${Version}\t${db:Status-Abbrev}\n"])
    rows = {}
    for line in text.splitlines():
        fields = line.split("\t")
        need(len(fields) == 4, "STOP_DPKG_INVENTORY_FORMAT")
        name, arch, version, status_value = fields
        key = name + ":" + arch
        need(key not in rows and re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name)
             and re.fullmatch(r"[a-z0-9-]+", arch) and len(status_value) == 3, "STOP_DPKG_INVENTORY_IDENTITY")
        rows[key] = {"name": name, "architecture": arch, "version": version, "status": status_value}
    STATE[label.replace("-", "_")] = {"raw_stdout_sha256": sha(text.encode()), "registered": rows}
    return rows


def services(label):
    text = command(label, ["/usr/bin/systemctl", "list-units", "--type=service", "--state=running",
                           "--no-legend", "--plain", "--no-pager"])
    units = set()
    for line in text.splitlines():
        fields = line.split(None, 4)
        need(len(fields) >= 4 and re.fullmatch(r"[A-Za-z0-9_.@\\:-]+\.service", fields[0])
             and fields[1:4] == ["loaded", "active", "running"], "STOP_SERVICE_INVENTORY_FORMAT")
        need(fields[0] not in units, "STOP_DUPLICATE_RUNNING_SERVICE")
        units.add(fields[0])
    STATE[label.replace("-", "_")] = sorted(units)
    return units


def source_url(value, official=False):
    if value == MIRROR_URI:
        return value
    parsed = urlsplit(value)
    need(parsed.scheme in ("http", "https") and parsed.hostname and parsed.username is None
         and parsed.password is None and parsed.port is None and not parsed.query and not parsed.fragment,
         "STOP_REPOSITORY_CREDENTIALS_OR_URL")
    if official:
        need(parsed.hostname in HOSTS and parsed.path.rstrip("/") == "/ubuntu", "STOP_NONOFFICIAL_UBUNTU_URI")
    return value.rstrip("/")


def insecure_source_options(fields):
    for key in ("trusted", "allow-insecure", "allow-weak"):
        need(key not in fields or fields[key] in ("false", "no", "0"), "STOP_INSECURE_REPOSITORY_OPTION")


def source_snapshot():
    paths = []
    primary = Path("/etc/apt/sources.list")
    if primary.exists():
        paths.append(primary)
    directory = Path("/etc/apt/sources.list.d")
    nonlink(directory)
    paths.extend(sorted(p for p in directory.iterdir() if p.suffix in (".list", ".sources")))
    sources = {}
    trusted = set()
    mirror_urls = []
    for path in paths:
        nonlink(path)
        source_info = path.stat()
        need(stat.S_ISREG(source_info.st_mode) and source_info.st_uid == 0
             and not source_info.st_mode & 0o022, "STOP_SOURCE_FILE_METADATA")
        raw = bounded(path, 1024 * 1024)
        sources[str(path)] = sha(raw)
        text = raw.decode("utf-8")
        entries = []
        if path.suffix == ".sources":
            for paragraph in re.split(r"\n\s*\n", text):
                fields = {}
                previous = None
                for line in paragraph.splitlines():
                    if not line.strip() or line.lstrip().startswith("#"):
                        continue
                    if line[:1].isspace():
                        need(previous is not None, "STOP_SOURCE_CONTINUATION")
                        fields[previous] += " " + line.strip()
                    else:
                        need(":" in line, "STOP_SOURCE_FORMAT")
                        key, value = line.split(":", 1)
                        key = key.lower()
                        need(key not in fields, "STOP_SOURCE_DUPLICATE_FIELD")
                        fields[key] = value.strip()
                        previous = key
                if fields.get("enabled", "yes").lower() == "no" or "deb" not in fields.get("types", "").split():
                    continue
                insecure_source_options(fields)
                entries.extend((uri, fields.get("signed-by"), fields.get("suites", "").split())
                               for uri in fields.get("uris", "").split())
        else:
            for line in text.splitlines():
                line = line.split("#", 1)[0].strip()
                if not line or not line.startswith("deb "):
                    continue
                match = re.fullmatch(r"deb\s+(?:\[([^]]*)\]\s+)?(\S+)\s+(\S+)\s+(.+)", line)
                need(match, "STOP_SOURCE_LIST_FORMAT")
                options = {}
                for pair in (match[1] or "").split():
                    need("=" in pair, "STOP_SOURCE_OPTION_FORMAT")
                    key, value = pair.split("=", 1)
                    need(key not in options and key == key.lower(), "STOP_SOURCE_DUPLICATE_OR_UNKNOWN_OPTION")
                    options[key] = value
                insecure_source_options(options)
                entries.append((match[2], options.get("signed-by"), [match[3]]))
        for uri, signed_by, suites in entries:
            normalized = source_url(uri)
            if uri == MIRROR_URI or urlsplit(uri).hostname in HOSTS:
                source_url(uri, official=True)
                need(signed_by == str(KEYRING) and suites and all(suite in
                     ("noble", "noble-updates", "noble-security", "noble-backports") for suite in suites),
                     "STOP_UBUNTU_SIGNED_SOURCE")
                trusted.add(normalized)
                if uri == MIRROR_URI:
                    nonlink(MIRROR_FILE)
                    info = MIRROR_FILE.stat()
                    need(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                         "STOP_EXISTING_MIRROR_FILE_METADATA")
                    mirror_raw = bounded(MIRROR_FILE, 65536)
                    sources[str(MIRROR_FILE)] = sha(mirror_raw)
                    for line in mirror_raw.decode("utf-8").splitlines():
                        line = line.split("#", 1)[0].strip()
                        if not line:
                            continue
                        parts = line.split()
                        need(1 <= len(parts) <= 2 and (len(parts) == 1 or
                             re.fullmatch(r"priority:[0-9]+", parts[1])), "STOP_MIRROR_FILE_FORMAT")
                        mirror_urls.append(source_url(parts[0], official=True))
                    need(mirror_urls and len(mirror_urls) <= 16, "STOP_EXISTING_MIRROR_LIST_EMPTY")
                    trusted.update(mirror_urls)
    need(trusted, "STOP_UBUNTU_SOURCE_ABSENT")
    nonlink(KEYRING)
    key_stat = KEYRING.stat()
    need(stat.S_ISREG(key_stat.st_mode) and key_stat.st_uid == 0 and not key_stat.st_mode & 0o022,
         "STOP_UBUNTU_KEYRING_METADATA")
    key_sha = sha(bounded(KEYRING, 2 * 1024 * 1024))
    for path in [Path("/etc/apt/auth.conf"), *Path("/etc/apt/auth.conf.d").glob("*")]:
        if path.is_file():
            nonlink(path)
            need(not bounded(path, 65536).strip(), "STOP_APT_AUTH_CONFIGURATION")
    inreleases = {}
    lists = Path("/var/lib/apt/lists")
    nonlink(lists)
    for path in sorted(lists.glob("*_InRelease")):
        nonlink(path)
        info = path.stat()
        need(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
             "STOP_CACHED_INRELEASE_METADATA")
        raw = bounded(path, 8 * 1024 * 1024)
        text = raw.decode("utf-8", errors="strict")
        fields = {}
        for key in ("Origin", "Label", "Suite", "Codename", "Architectures"):
            values = re.findall(r"^" + key + r": (.+)$", text, re.MULTILINE)
            if values:
                need(len(values) == 1, "STOP_INRELEASE_DUPLICATE_FIELD")
                fields[key] = values[0]
        if fields.get("Origin") == "Ubuntu" or fields.get("Label") == "Ubuntu":
            need(raw.startswith(b"-----BEGIN PGP SIGNED MESSAGE-----")
                 and b"-----BEGIN PGP SIGNATURE-----" in raw
                 and fields.get("Origin") == fields.get("Label") == "Ubuntu"
                 and fields.get("Codename") == "noble" and "amd64" in fields.get("Architectures", "").split()
                 and fields.get("Suite") in ("noble", "noble-updates", "noble-security", "noble-backports"),
                 "STOP_CACHED_UBUNTU_INRELEASE_IDENTITY")
            inreleases[str(path)] = {"sha256": sha(raw), "release": fields}
    need(inreleases, "STOP_EXISTING_SIGNED_UBUNTU_INDEX_ABSENT")
    return {"source_file_sha256": sources, "ubuntu_keyring_sha256": key_sha,
            "trusted_ubuntu_uris": sorted(trusted), "mirror_ubuntu_uris": sorted(set(mirror_urls)),
            "cached_ubuntu_inrelease": inreleases,
            "signature_trust_boundary": "existing root-owned APT cache and Ubuntu archive keyring; APT authentication remains enabled; no newly accepted key or repository"}


APT_OPTIONS = ["-o", "Dir::Cache::pkgcache=", "-o", "Dir::Cache::srcpkgcache=",
               "-o", "Dir::Etc::Parts=/dev/null", "-o", "Dir::Etc::main=/dev/null",
               "-o", "APT::Get::AllowUnauthenticated=false", "-o", "Acquire::AllowInsecureRepositories=false",
               "-o", "Acquire::AllowDowngradeToInsecureRepositories=false", "-o", "APT::Get::allow-downgrades=false"]


def global_release_records(global_policy, sources):
    """APT's mirror+file records can omit origin entirely when Site is empty."""
    releases = set()
    lines = global_policy.splitlines()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"\s+\d+\s+(" + REPOSITORY_URI_PATTERN + r")\s+(\S+)\s+amd64\s+Packages\s*", line)
        if not match or not (match[1] == MIRROR_URI or urlsplit(match[1]).hostname in HOSTS):
            continue
        uri = source_url(match[1], official=True)
        block = []
        for following in lines[index + 1:]:
            if re.match(r"\s+\d+\s+\S+", following) or (following and not following[:1].isspace()):
                break
            if following.strip():
                block.append(following.strip())
        release = [value[8:] for value in block if value.startswith("release ")]
        origin = [value[6:].strip() for value in block if value == "origin" or value.startswith("origin ")]
        need(len(release) == 1 and len(origin) <= 1
             and len(block) == 1 + len(origin), "STOP_APT_RELEASE_RECORD")
        if uri == MIRROR_URI:
            need(uri in sources["trusted_ubuntu_uris"] and sources["mirror_ubuntu_uris"]
                 and all(source_url(value, official=True) for value in sources["mirror_ubuntu_uris"]),
                 "STOP_MIRROR_ORIGIN_TRUST")
            allowed = {"", MIRROR_URI, *{urlsplit(value).hostname for value in sources["mirror_ubuntu_uris"]}}
            need(not origin or origin[0] in allowed, "STOP_APT_MIRROR_ORIGIN")
        else:
            need(origin == [urlsplit(uri).hostname], "STOP_APT_UBUNTU_ORIGIN")
        fields = {}
        for pair in release[0].split(","):
            need("=" in pair, "STOP_APT_RELEASE_FIELD")
            key, value = pair.split("=", 1)
            need(key not in fields, "STOP_APT_DUPLICATE_RELEASE_FIELD")
            fields[key] = value
        need(fields.get("o") == fields.get("l") == "Ubuntu" and fields.get("n") == "noble"
             and fields.get("b") == "amd64" and uri in sources["trusted_ubuntu_uris"], "STOP_APT_UBUNTU_RELEASE")
        suite = match[2].split("/", 1)[0]
        need(any(value["release"].get("Suite") == suite for value in sources["cached_ubuntu_inrelease"].values()),
             "STOP_CANDIDATE_CACHED_SIGNED_SUITE")
        releases.add((uri, match[2]))
    need(releases, "STOP_APT_UBUNTU_RELEASE_ABSENT")
    return releases


def candidate_policy(pins, sources):
    global_policy = command("apt-policy", ["/usr/bin/apt-cache", *APT_OPTIONS, "policy"])
    releases = global_release_records(global_policy, sources)
    for index, pin in enumerate(pins):
        package = pin["name"] + ":amd64"
        text = command("policy-%02d" % index, ["/usr/bin/apt-cache", *APT_OPTIONS, "policy", package])
        matches = re.findall(r"^\s*Candidate:\s*(\S+)\s*$", text, re.MULTILINE)
        need(matches == [pin["version"]], "STOP_CANDIDATE_VERSION")
        selected, repositories = False, []
        for line in text.splitlines():
            version = re.fullmatch(r"\s+(?:\*\*\*\s+)?(\S+)\s+\d+\s*", line)
            if version:
                selected = version[1] == pin["version"]
                continue
            match = re.fullmatch(r"\s+\d+\s+(" + REPOSITORY_URI_PATTERN + r")\s+(\S+)\s+amd64\s+Packages\s*", line)
            if selected and match:
                uri = source_url(match[1], official=True)
                need((uri, match[2]) in releases, "STOP_CANDIDATE_NON_UBUNTU_ORIGIN")
                repositories.append({"uri": uri, "distribution_component": match[2]})
        need(repositories, "STOP_CANDIDATE_SIGNED_ORIGIN_ABSENT")
        control = command("control-%02d" % index, ["/usr/bin/apt-cache", *APT_OPTIONS, "show", package + "=" + pin["version"]])
        records = [record for record in re.split(r"\n\s*\n", control) if record.strip()]
        need(len(records) == 1, "STOP_CANDIDATE_CONTROL_UNIQUENESS")
        fields = {}
        for line in records[0].splitlines():
            if line[:1].isspace():
                continue
            need(":" in line, "STOP_CONTROL_FORMAT")
            key, value = line.split(":", 1)
            need(key not in fields, "STOP_CONTROL_DUPLICATE_FIELD")
            fields[key] = value.strip()
        need(fields.get("Package") == pin["name"] and fields.get("Version") == pin["version"]
             and fields.get("Architecture") == "amd64" and fields.get("Section", "").split("/")[-1] in ("libs", "oldlibs"),
             "STOP_NON_LIBRARY_CONTROL_SCOPE")
        STATE["candidate_metadata"].append({"pin": pin, "origins": repositories,
                                             "control_sha256": sha(control.encode()), "section": fields["Section"]})


def simulation(pins):
    arguments = [pin["name"] + ":amd64=" + pin["version"] for pin in pins]
    text = command("apt-simulation", ["/usr/bin/apt-get", *APT_OPTIONS, "-s",
               "-o", "Debug::NoLocking=true", "--no-install-recommends", "--no-upgrade", "--no-remove", "install", *arguments], timeout=60)
    expected = {pin["name"]: pin["version"] for pin in pins}
    installed = {}
    configured = {}
    for line in text.splitlines():
        need(not line.startswith("Remv "), "STOP_SIMULATED_REMOVAL")
        if line.startswith("Inst "):
            match = re.fullmatch(r"Inst ([a-z0-9+.-]+)(?::amd64)? \((\S+) .*\[amd64\]\)", line)
            need(match and match[1] not in installed and expected.get(match[1]) == match[2],
                 "STOP_SIMULATED_UNAPPROVED_OR_UPGRADE")
            installed[match[1]] = match[2]
        if line.startswith("Conf "):
            match = re.fullmatch(r"Conf ([a-z0-9+.-]+)(?::amd64)? \((\S+) .*\[amd64\]\)", line)
            need(match and match[1] not in configured and expected.get(match[1]) == match[2],
                 "STOP_SIMULATED_OLD_OR_UNAPPROVED_CONFIGURATION")
            configured[match[1]] = match[2]
    summaries = re.findall(r"^(\d+) upgraded, (\d+) newly installed, (\d+) to remove and \d+ not upgraded\.$", text, re.MULTILINE)
    need(installed == configured == expected and summaries == [("0", str(len(pins)), "0")], "STOP_SIMULATED_PACKAGE_SCOPE")
    STATE["simulation_exact_new_packages"] = pins
    return arguments


def elf_metadata(raw):
    need(len(raw) >= 64 and raw[:7] == b"\x7fELF\x02\x01\x01", "STOP_ELF_HOST_FORMAT")
    header = struct.unpack_from("<HHIQQQIHHHHHH", raw, 16)
    kind, machine, version, _, phoff, _, _, _, phsize, phcount, _, _, _ = header
    need(kind == 3 and machine == 62 and version == 1 and phsize == 56 and phcount <= 1024
         and phoff + phsize * phcount <= len(raw), "STOP_NON_LIBRARY_ELF")
    loads, dynamics = [], []
    for index in range(phcount):
        kind, _, offset, vaddr, _, size, _, _ = struct.unpack_from("<IIQQQQQQ", raw, phoff + index * phsize)
        need(offset + size <= len(raw) and kind != 3, "STOP_ELF_INTERPRETER_OR_SEGMENT")
        if kind == 1:
            loads.append((vaddr, offset, size))
        if kind == 2:
            dynamics.append((offset, size))
    need(len(dynamics) == 1, "STOP_ELF_DYNAMIC_SEGMENT")
    dynamic = {}
    offset, size = dynamics[0]
    need(size % 16 == 0 and size <= 1024 * 1024, "STOP_ELF_DYNAMIC_SIZE")
    for current in range(offset, offset + size, 16):
        tag, value = struct.unpack_from("<qQ", raw, current)
        if tag == 0:
            break
        if tag in (5, 10, 14):
            need(tag not in dynamic, "STOP_ELF_DUPLICATE_DYNAMIC_FIELD")
            dynamic[tag] = value
    soname = None
    if 14 in dynamic:
        need(5 in dynamic and 10 in dynamic and dynamic[10] <= 8 * 1024 * 1024, "STOP_ELF_STRING_TABLE")
        matches = [file_offset + dynamic[5] - address for address, file_offset, file_size in loads
                   if address <= dynamic[5] and dynamic[5] + dynamic[10] <= address + file_size]
        need(len(matches) == 1 and dynamic[14] < dynamic[10], "STOP_ELF_SONAME_OFFSET")
        start = matches[0] + dynamic[14]
        end = raw.find(b"\0", start, min(start + 256, matches[0] + dynamic[10]))
        need(end > start, "STOP_ELF_SONAME_SIZE")
        soname = raw[start:end].decode("ascii")
        need(re.fullmatch(r"lib[A-Za-z0-9_+.-]+\.so(?:\.[A-Za-z0-9_.-]+)?", soname), "STOP_ELF_SONAME")
    return {"class": 64, "endian": "little", "machine": "EM_X86_64", "type": "ET_DYN",
            "header_sha256": sha(raw[:64]), "soname": soname, "loaded_or_executed": False}


def package_files(pins):
    all_sonames = set()
    total = 0
    for index, pin in enumerate(pins):
        package = pin["name"] + ":amd64"
        text = command("files-%02d" % index, ["/usr/bin/dpkg-query", "-L", package])
        paths = text.splitlines()
        need(len(paths) == len(set(paths)) and len(paths) <= 4096, "STOP_PACKAGE_FILE_LIST")
        rows = []
        elf_count = 0
        for value in sorted(paths):
            path = Path(value)
            need(path.is_absolute() and ".." not in path.parts and path.exists(), "STOP_PACKAGE_FILE_PATH")
            need(not re.search(r"/(?:systemd/(?:system|user)|init\.d|init|dbus-1/(?:system|services|system-services))/", value),
                 "STOP_PACKAGE_SERVICE_FILE")
            info = path.lstat()
            row = {"path": value, "mode": stat.S_IMODE(info.st_mode), "uid": info.st_uid,
                   "gid": info.st_gid, "size": info.st_size}
            if stat.S_ISLNK(info.st_mode):
                row.update(type="symlink", target=os.readlink(path))
            elif stat.S_ISDIR(info.st_mode):
                row["type"] = "directory"
            else:
                need(stat.S_ISREG(info.st_mode) and not info.st_mode & 0o6000 and info.st_size <= 128 * 1024 * 1024,
                     "STOP_PACKAGE_SPECIAL_OR_OVERSIZE_FILE")
                total += info.st_size
                need(total <= 512 * 1024 * 1024, "STOP_PACKAGE_FILE_COLLECTION_SIZE")
                raw = bounded(path, 128 * 1024 * 1024)
                row.update(type="regular", sha256=sha(raw))
                if raw.startswith(b"\x7fELF"):
                    need(".so" in path.name, "STOP_PACKAGE_EXECUTABLE_ELF")
                    row["elf"] = elf_metadata(raw)
                    elf_count += 1
                    if row["elf"]["soname"]:
                        all_sonames.add(row["elf"]["soname"])
            rows.append(row)
        controls = {}
        for stem in (pin["name"], package):
            for suffix in ("preinst", "postinst", "prerm", "postrm", "triggers"):
                path = Path("/var/lib/dpkg/info") / (stem + "." + suffix)
                if path.exists():
                    nonlink(path)
                    raw = bounded(path, 1024 * 1024)
                    need(not re.search(rb"\b(?:systemctl|invoke-rc\.d|deb-systemd-invoke|update-rc\.d|start-stop-daemon|service)\b", raw),
                         "STOP_PACKAGE_SERVICE_CONTROL")
                    controls[str(path)] = sha(raw)
        need(elf_count > 0, "STOP_PACKAGE_WITHOUT_LIBRARY_ELF")
        canonical = (json.dumps(rows, sort_keys=True, separators=(",", ":")) + "\n").encode()
        listing = save("system-library-file-collection-%02d.json" % index, canonical)
        STATE["package_file_collections"].append({"pin": pin, "collection": listing,
                                                "elf_count": elf_count, "control_sha256": controls})
    need("libpulse.so.0" in all_sonames, "STOP_LIBPULSE_SONAME_ABSENT")
    STATE["static_library_sonames"] = sorted(all_sonames)


def run(policy_path, here):
    pins = context(here, policy_path)
    for value in ("/usr/bin/apt-get", "/usr/bin/apt-cache", "/usr/bin/dpkg-query", "/usr/bin/systemctl",
                  "/usr/bin/sudo", "/usr/bin/env"):
        path = Path(value)
        need(path.is_file() and os.access(path, os.X_OK), "STOP_EXISTING_SYSTEM_TOOL")
    before = inventory("dpkg-before")
    for pin in pins:
        need(pin["name"] + ":amd64" not in before, "STOP_LIBRARY_ALREADY_REGISTERED")
    sources = source_snapshot()
    STATE["sources_before"] = sources
    need(command("keyring-owner", ["/usr/bin/dpkg-query", "-S", str(KEYRING)]).strip()
         == "ubuntu-keyring: " + str(KEYRING), "STOP_EXISTING_UBUNTU_KEYRING_OWNER")
    service_before = services("services-before")
    candidate_policy(pins, sources)
    arguments = simulation(pins)
    # One fixed elevated operation. Existing signed repository transport is APT's
    # responsibility; neither repositories nor terms nor package indexes change.
    cached_python = Path(sys.executable).resolve(strict=True)
    nonlink(cached_python)
    need(re.fullmatch(r"/usr/bin/python3\.[0-9]+", str(cached_python))
         or cached_python.is_relative_to(Path("/opt/hostedtoolcache/Python")),
         "STOP_CACHED_ROOT_PYTHON_LOCATION")
    for path in (cached_python, *cached_python.parents):
        info = path.stat()
        need(info.st_uid == 0 and not info.st_mode & 0o022, "STOP_CACHED_ROOT_PYTHON_TRUST")
    python_info = cached_python.stat()
    need(stat.S_ISREG(python_info.st_mode) and os.access(cached_python, os.X_OK), "STOP_CACHED_ROOT_PYTHON_FILE")
    python_raw = bounded(cached_python, 128 * 1024 * 1024)
    need(len(python_raw) >= 64 and python_raw[:7] == b"\x7fELF\x02\x01\x01"
         and struct.unpack_from("<HHI", python_raw, 16)[0] in (2, 3)
         and struct.unpack_from("<HHI", python_raw, 16)[1:] == (62, 1),
         "STOP_CACHED_ROOT_PYTHON_ELF")
    STATE["cached_root_python"] = {"path": str(cached_python), "uid": python_info.st_uid,
                                    "gid": python_info.st_gid, "mode": stat.S_IMODE(python_info.st_mode),
                                    "sha256": sha(python_raw),
                                    "selection": "canonical actual running sys.executable; no new interpreter"}
    STATE["root_program_sha256"] = sha(ROOT_PROGRAM.encode())
    installation = ["/usr/bin/sudo", "-n", str(cached_python), "-I", "-S", "-B", "-c", ROOT_PROGRAM, *arguments]
    command("apt-install", installation, timeout=300, install=True)
    need(STATE["root_proof_verified"] and STATE["apt_install_invocations"] == 1
         and STATE["apt_install_normal_wait_completed"] and STATE["service_veto_verified"]
         and STATE["service_veto_restored"], "STOP_ROOT_INSTALL_OR_VETO_NOT_VERIFIED")
    after = inventory("dpkg-after")
    expected_keys = {pin["name"] + ":amd64" for pin in pins}
    need({key: row for key, row in before.items() if key not in expected_keys}
         == {key: row for key, row in after.items() if key not in expected_keys}, "STOP_OLD_REGISTERED_PACKAGE_CHANGE")
    for pin in pins:
        need(after.get(pin["name"] + ":amd64") == {**pin, "status": "ii "}, "STOP_INSTALLED_PIN_MISMATCH")
    STATE["old_packages_unchanged"] = True
    STATE["old_packages_unchanged_boundary"] = "complete dpkg registered name/version/architecture/status map outside the approved new package set; not all old host file bytes"
    STATE["package_scope_exact"] = True
    STATE["sources_after"] = source_snapshot()
    need(STATE["sources_after"] == sources, "STOP_REPOSITORY_OR_KEYRING_CHANGE")
    service_after = services("services-after")
    need(not service_after - service_before, "STOP_NEW_RUNNING_SERVICE")
    STATE["no_new_running_services"] = True
    STATE["service_observation_boundary"] = "running systemd service sets immediately before and after installation; no claim about unobserved transient processes"
    package_files(pins)
    STATE.update(status="VERIFIED_STANDARD_RUNTIME_LIBRARIES", overall_exit=0,
                 installed_library_scope_verified=True)


def main():
    global OUTPUT, STATE, DEADLINE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    here = Path(__file__).absolute().parent
    OUTPUT = args.output.absolute()
    policy_path = args.policy.absolute()
    nonlink(here)
    nonlink(OUTPUT)
    nonlink(policy_path)
    need(OUTPUT.is_dir() and not (OUTPUT / "system-library-preparation.json").exists(),
         "STOP_OUTPUT_NOT_FRESH_FOR_LIBRARY_EVIDENCE")
    for path in OUTPUT.glob("system-library-*"):
        nonlink(path)
        info = path.stat()
        need(path.name == "system-library-wrapper.log" and stat.S_ISREG(info.st_mode)
             and info.st_uid == os.getuid() and info.st_size <= LIMIT,
             "STOP_OUTPUT_NOT_FRESH_FOR_LIBRARY_EVIDENCE")
    STATE = {"schema": 1, "status": "FAILED", "overall_exit": 1, "run_id": None, "head_sha": None,
             "environment_approval_sha256": None, "approved_new_packages": [],
             "package_scope_exact": False, "old_packages_unchanged": False,
             "no_new_running_services": False, "apt_install_invocations": 0,
             "apt_install_normal_wait_completed": False, "apt_install_state": "NOT_STARTED",
             "root_wrapper_invocations": 0, "root_wrapper_normal_wait_completed": False,
             "root_wrapper_count_boundary": "sudo/root-program invocation attempt; actual APT count requires the bound root proof",
             "root_proof_verified": False, "service_veto_verified": False, "service_veto_restored": False,
             "installed_library_scope_verified": False, "commands": [], "candidate_metadata": [],
             "package_file_collections": [], "errors": [], "package_cleanup_or_retry": "NOT_PERFORMED",
             "android_executions": 0, "sdk_executions": 0, "emulator_executions": 0,
             "runtime_evidence": "NOT_RUN_BY_THIS_HELPER"}
    DEADLINE = time.monotonic() + 360
    STATE["overall_deadline_seconds"] = 360

    def interrupted(signum, frame):
        raise Stop("STOP_HELPER_SIGNAL_" + str(signum))

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    signal.setitimer(signal.ITIMER_REAL, 360)
    receipt()
    try:
        run(policy_path, here)
    except BaseException as error:
        STATE["status"], STATE["overall_exit"] = "FAILED", 1
        STATE["errors"].append(str(error) if isinstance(error, Stop) else "STOP_EXCEPTION_" + type(error).__name__)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        receipt()
    print(STATE["status"])
    return STATE["overall_exit"]


if __name__ == "__main__":
    raise SystemExit(main())
