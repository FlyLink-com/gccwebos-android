#!/usr/bin/env python3
"""Read-only hosted library inventory and libpulse0 APT simulation; no runtime."""
import datetime
import hashlib
import json
import os
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

REPOSITORY = "gccwebos-com/gccwebos-android"
BRANCH = "refs/heads/codex/lucky-b2-library-plan"
OUTPUT = ".build-output/b2-library-plan"
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C", "LANG": "C"}
MAX_FILE = 4 * 1024 * 1024
MAX_COMMAND = 1024 * 1024
COMMANDS = {
    "host-dpkg-architecture": ["/usr/bin/dpkg-query", "-W", "-f=${Architecture}\n", "dpkg"],
    "libpulse-package-status": ["/usr/bin/dpkg-query", "-W", "-f=${binary:Package}\t${Version}\t${Architecture}\t${db:Status-Abbrev}\n", "libpulse0"],
    "libpulse-package-files": ["/usr/bin/dpkg-query", "-L", "libpulse0"],
    "libpulse-policy": ["/usr/bin/apt-cache", "-o", "Dir::Cache::pkgcache=", "-o", "Dir::Cache::srcpkgcache=", "policy", "libpulse0"],
    "libpulse-dependencies": ["/usr/bin/apt-cache", "-o", "Dir::Cache::pkgcache=", "-o", "Dir::Cache::srcpkgcache=", "depends", "--important", "libpulse0"],
    "libpulse-simulation": ["/usr/bin/apt-get", "-s", "-o", "Debug::NoLocking=true",
                           "-o", "Dir::Cache::pkgcache=", "-o", "Dir::Cache::srcpkgcache=",
                           "-o", "APT::Get::List-Cleanup=false", "--no-install-recommends", "--no-upgrade", "install", "libpulse0"],
    "loader-cache": ["/usr/sbin/ldconfig", "-p"],
}


class Stop(Exception):
    pass


def need(value, code):
    if not value:
        raise Stop(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def write_json(path, value):
    path.write_bytes((json.dumps(value, sort_keys=True, indent=2) + "\n").encode())


def regular_metadata(path, limit=MAX_FILE, roots=None):
    result = {"path": str(path), "exists": path.exists(), "is_symlink": path.is_symlink()}
    if not path.exists() and not path.is_symlink():
        return result, None
    resolved = path.resolve(strict=True)
    if roots is not None:
        need(any(resolved == root or root in resolved.parents for root in roots), "STOP_LIBRARY_PATH_ESCAPE")
    info = resolved.stat()
    need(stat.S_ISREG(info.st_mode), "STOP_NONREGULAR_METADATA")
    result.update({"canonical_path": str(resolved), "bytes": info.st_size, "uid": info.st_uid,
                   "gid": info.st_gid, "mode": stat.S_IMODE(info.st_mode), "dev": info.st_dev, "ino": info.st_ino})
    need(info.st_size <= limit, "STOP_METADATA_SIZE")
    raw = resolved.read_bytes()
    need(len(raw) == info.st_size and resolved.stat().st_mtime_ns == info.st_mtime_ns, "STOP_METADATA_CHANGED_DURING_READ")
    result["sha256"] = sha(raw)
    return result, raw


def command_once(name, argv, out, deadline):
    # Fixed cached OS programs only. There is no shell, sudo, SDK, loader target
    # execution, apt update/download, package installation or child retry.
    need(time.monotonic() < deadline, "STOP_DIAGNOSTIC_TIME_BUDGET")
    program = Path(argv[0]).resolve(strict=True)
    info = program.stat()
    need(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022, "STOP_OS_TOOL_IDENTITY")
    tool_hash = sha(program.read_bytes())
    started = time.monotonic()
    child = subprocess.Popen(argv, cwd="/", env=ENV, stdin=subprocess.DEVNULL,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ, "stdout")
    selector.register(child.stderr, selectors.EVENT_READ, "stderr")
    captured = {"stdout": bytearray(), "stderr": bytearray()}
    stopped_reason = None
    try:
        while selector.get_map():
            if time.monotonic() - started > 45 or time.monotonic() >= deadline:
                stopped_reason = "TIMEOUT"
                break
            for key, _ in selector.select(0.25):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                if len(captured[key.data]) + len(chunk) > MAX_COMMAND:
                    stopped_reason = "OUTPUT_LIMIT"
                    break
                captured[key.data].extend(chunk)
            if stopped_reason:
                break
    finally:
        selector.close()
        if stopped_reason:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        try:
            exit_code = child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            exit_code = child.wait(timeout=5)
            stopped_reason = stopped_reason or "STOP_TIMEOUT"
        child.stdout.close()
        child.stderr.close()
    record = {"argv": argv, "exit": exit_code, "termination_reason": stopped_reason,
              "tool_canonical_path": str(program), "tool_sha256": tool_hash, "elapsed_seconds": round(time.monotonic() - started, 3)}
    for stream, raw in captured.items():
        path = out / (name + "." + stream + ".log")
        path.write_bytes(raw)
        record[stream] = {"path": path.name, "bytes": len(raw), "sha256": sha(raw)}
    return record, bytes(captured["stdout"])


def source_provenance():
    files = [Path("/etc/apt/sources.list"), Path("/etc/apt/apt-mirrors.txt")]
    directory = Path("/etc/apt/sources.list.d")
    if directory.is_dir():
        entries = sorted(directory.iterdir())
        need(len(entries) <= 128, "STOP_SOURCE_COUNT")
        files.extend(p for p in entries if p.suffix in (".list", ".sources"))
    result = []
    for path in files:
        metadata, raw = regular_metadata(path, 128 * 1024)
        if raw is not None:
            uris = []
            for match in re.findall(rb"https?://[^\s\]\"']+", raw):
                url = urllib.parse.urlsplit(match.decode("ascii", "strict"))
                need(not url.username and not url.password and not url.query and not url.fragment, "STOP_SOURCE_CREDENTIAL_OR_QUERY")
                uris.append({"scheme": url.scheme, "host": url.hostname, "path": url.path,
                             "official_ubuntu_host": url.hostname in ("archive.ubuntu.com", "security.ubuntu.com", "azure.archive.ubuntu.com", "ports.ubuntu.com")})
            metadata["repository_uris"] = uris
            metadata["raw_config_retained"] = False
        result.append(metadata)
    releases = []
    release_bytes = 0
    lists = Path("/var/lib/apt/lists")
    if lists.is_dir():
        entries = sorted(lists.iterdir())
        need(len(entries) <= 512, "STOP_APT_LIST_COUNT")
        for path in entries:
            if path.name.endswith(("_InRelease", "_Release")):
                metadata, raw = regular_metadata(path)
                release_bytes += len(raw)
                need(len(releases) < 64 and release_bytes <= 16 * 1024 * 1024, "STOP_RELEASE_AGGREGATE_SIZE")
                metadata["release_fields"] = {key: re.findall(rb"(?m)^" + key.encode() + rb":([^\r\n]*)", raw)[0].decode("ascii", "replace").strip()
                                              for key in ("Origin", "Label", "Suite", "Codename", "Architectures", "Date")
                                              if re.findall(rb"(?m)^" + key.encode() + rb":([^\r\n]*)", raw)}
                metadata["signature_independently_verified_here"] = False
                releases.append(metadata)
    return {"source_configs": result, "cached_releases": releases,
            "scope": "hashes and sanitized metadata only; current local APT policy must establish the candidate origin; no index refresh"}


def library_inventory():
    # Fixed system locations only; no recursive scan, SDK reads/copy or custom
    # library injection. Symlinks are observed and canonical targets bounded.
    roots = [Path("/usr/lib/x86_64-linux-gnu").resolve(), Path("/lib/x86_64-linux-gnu").resolve()]
    paths = {Path("/usr/lib/x86_64-linux-gnu/libpulse.so.0"), Path("/lib/x86_64-linux-gnu/libpulse.so.0")}
    for directory in (Path("/usr/lib/x86_64-linux-gnu"), Path("/lib/x86_64-linux-gnu"),
                      Path("/usr/lib/x86_64-linux-gnu/pulseaudio"), Path("/lib/x86_64-linux-gnu/pulseaudio")):
        if directory.is_dir():
            entries = sorted(directory.iterdir())
            need(len(entries) <= 8192, "STOP_LIBRARY_DIRECTORY_COUNT")
            paths.update(p for p in entries if re.fullmatch(r"libpulse(?:common|-mainloop-glib)?(?:-[0-9.]+)?\.so(?:\.[0-9]+)*", p.name))
    need(len(paths) <= 64, "STOP_LIBRARY_COUNT")
    result = []
    for path in sorted(paths):
        metadata, raw = regular_metadata(path, 16 * 1024 * 1024, roots)
        if raw is not None:
            metadata["elf_header"] = {"is_elf": raw.startswith(b"\x7fELF"), "class": raw[4] if len(raw) > 4 else None,
                                      "endianness": raw[5] if len(raw) > 5 else None,
                                      "machine": int.from_bytes(raw[18:20], "little") if len(raw) >= 20 and raw[5] == 1 else None}
            metadata["library_executed_or_loaded"] = False
        result.append(metadata)
    return result


def guard(here):
    need(sys.platform == "linux" and os.uname().machine == "x86_64" and os.getuid() == os.geteuid() != 0 and os.getgid() == os.getegid(), "STOP_HOST_IDENTITY")
    context = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux",
               "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_REF": BRANCH, "GITHUB_RUN_ATTEMPT": "1", "ImageOS": "ubuntu24"}
    need(all(os.environ.get(k) == v for k, v in context.items()), "STOP_HOST_CONTEXT")
    head = os.environ.get("APPROVED_DIAGNOSTIC_COMMIT", "")
    need(re.fullmatch(r"[0-9a-f]{40}", head) and os.environ.get("GITHUB_SHA") == head, "STOP_FULL_HEAD")
    expected = os.environ.get("APPROVED_DIAGNOSTIC_SHA256", "")
    need(re.fullmatch(r"[0-9a-f]{64}", expected) and sha(Path(__file__).read_bytes()) == expected, "STOP_SCRIPT_HASH")
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    need(workspace.is_absolute() and all(not p.is_symlink() for p in (workspace, *workspace.parents)), "STOP_WORKSPACE_PATH")
    need(here == workspace / "runtime-proposals/b2", "STOP_SOURCE_LOCATION")
    need(re.fullmatch(r"[0-9]+", os.environ.get("GITHUB_RUN_ID", "")), "STOP_RUN_ID")
    actual = subprocess.run(["/usr/bin/git", "--no-optional-locks", "rev-parse", "HEAD"], cwd=here, env=ENV,
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=True).stdout.decode().strip()
    need(actual == head, "STOP_CHECKOUT_HEAD")
    return workspace, head


def main():
    deadline = time.monotonic() + 360
    workspace, head = guard(Path(__file__).resolve().parent)
    out = workspace / OUTPUT
    need(not out.exists() and not out.is_symlink() and not out.parent.is_symlink(), "STOP_OUTPUT_PREEXISTS")
    out.mkdir(parents=True)
    (out / "diagnostic-commit.txt").write_bytes((head + "\n").encode())
    state = {"schema": 1, "status": "FAILED_CAPTURE", "capture_complete": False, "scope": "readonly_libpulse_metadata_and_apt_simulation",
             "run_id": int(os.environ["GITHUB_RUN_ID"]), "run_attempt": 1, "head_sha": head, "harness_commit": head,
             "repository": REPOSITORY, "branch": BRANCH, "observed_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
             "original_failed_run_reference": 37012696115, "is_original_failed_host_reconstruction": False,
             "host_identity": {"uid": os.getuid(), "euid": os.geteuid(), "gid": os.getgid(), "egid": os.getegid(), "machine": os.uname().machine},
             "commands": {}, "errors": [], "software_installed": 0, "package_downloads": 0, "apt_updates": 0,
             "sudo_invocations": 0, "permission_changes": 0, "sdk_invocations": 0, "emulator_invocations": 0,
             "adb_invocations": 0, "kvm_accesses": 0, "apk_installations": 0, "case_invocations": 0,
             "runtime_status": "NOT_RUN", "apt_simulation_is_installation_authority": False,
             "simulation_boundary": "current cached indexes and nonroot readable APT config only; no locks/index refresh/download; plan can drift before any later installation"}
    write_json(out / "library-preparation-diagnostic.json", state)
    try:
        os_meta, raw = regular_metadata(Path("/etc/os-release"), 128 * 1024)
        fields = dict(re.findall(r'^([A-Z_]+)=(.*)$', raw.decode("utf-8"), re.MULTILINE))
        state["os_release"] = {"metadata": os_meta, "id": fields.get("ID", "").strip('"'), "version_id": fields.get("VERSION_ID", "").strip('"')}
        need(state["os_release"]["id"] == "ubuntu" and state["os_release"]["version_id"] == "24.04", "STOP_DISTRO")
        state["repository_provenance"] = source_provenance()
        state["library_inventory"] = library_inventory()
        for name, argv in COMMANDS.items():
            record, stdout = command_once(name, argv, out, deadline)
            state["commands"][name] = record
            if name == "libpulse-simulation":
                text = stdout.decode("utf-8", "replace")
                state["simulated_dpkg_operations"] = [line for line in text.splitlines() if re.match(r"^(Inst|Conf|Remv) ", line)]
                planned = sorted({line.split()[1] for line in text.splitlines() if line.startswith("Inst ")})
                need(len(planned) <= 32 and all(re.fullmatch(r"[a-z0-9][a-z0-9+.-]*(?::amd64)?", package) for package in planned), "STOP_PLANNED_PACKAGE_SCOPE")
                state["planned_install_packages"] = planned
                state["simulation_plan_available"] = record["exit"] == 0
            need(record["termination_reason"] is None, "STOP_COMMAND_CAPTURE_" + name.upper().replace("-", "_"))
        state["planned_package_queries"] = {}
        for index, package in enumerate(state["planned_install_packages"]):
            queries = {
                "policy": ["/usr/bin/apt-cache", "-o", "Dir::Cache::pkgcache=", "-o", "Dir::Cache::srcpkgcache=", "policy", package],
                "installed-status": ["/usr/bin/dpkg-query", "-W", "-f=${binary:Package}\t${Version}\t${Architecture}\t${db:Status-Abbrev}\n", package],
            }
            state["planned_package_queries"][package] = {}
            for role, argv in queries.items():
                record, _ = command_once("planned-" + str(index) + "-" + role, argv, out, deadline)
                state["planned_package_queries"][package][role] = record
                need(record["termination_reason"] is None, "STOP_PLANNED_PACKAGE_QUERY_CAPTURE")
        state["capture_complete"] = True
        state["status"] = "DIAGNOSTIC_CAPTURED"
    except Exception as error:
        state["errors"].append(str(error) if isinstance(error, Stop) else "STOP_METADATA_" + type(error).__name__)
    write_json(out / "library-preparation-diagnostic.json", state)
    write_json(out / "result.json", {"status": state["status"], "capture_complete": state["capture_complete"], "scope": state["scope"], "runtime_status": "NOT_RUN", "errors": state["errors"]})
    return 0 if state["status"] == "DIAGNOSTIC_CAPTURED" else 1


if __name__ == "__main__":
    sys.exit(main())
