#!/usr/bin/env python3
"""Read only the fixed Ubuntu archive keyring and its registered package metadata."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time

REPOSITORY = "gccwebos-com/gccwebos-android"
BRANCH = "refs/heads/codex/lucky-b2-keyring-diagnostic"
BASE_COMMIT = "8fc22c28107b4125995883337b5a448e007a57b7"
KEYRING = Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg")
OUTPUT = ".build-output/b2-keyring-diagnostic"
MAX_RAW_TOTAL = 1024 * 1024
MAX_KEYRING = 2 * 1024 * 1024
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C", "LANG": "C"}
COMMANDS = {
    "keyring-package-owner": ["/usr/bin/dpkg-query", "-S", str(KEYRING)],
    "keyring-package-status": ["/usr/bin/dpkg-query", "-W",
                               "-f=${binary:Package}\t${Version}\t${Architecture}\t${db:Status-Abbrev}\n", "ubuntu-keyring"],
    "keyring-package-verify": ["/usr/bin/dpkg", "-V", "ubuntu-keyring"],
}


class Stop(Exception):
    pass


def need(ok, code):
    if not ok:
        raise Stop(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def nonlink(path):
    need(path.is_absolute() and all(not candidate.is_symlink() for candidate in (path, *path.parents)),
         "STOP_CONTEXT_PATH_SYMLINK")


def write_json(path, value):
    path.write_bytes((json.dumps(value, sort_keys=True, indent=2) + "\n").encode())


def stat_record(info):
    kind = "regular" if stat.S_ISREG(info.st_mode) else "directory" if stat.S_ISDIR(info.st_mode) else "symlink" if stat.S_ISLNK(info.st_mode) else "other"
    return {"type": kind, "uid": info.st_uid, "gid": info.st_gid, "root_uid": info.st_uid == 0,
            "mode": stat.S_IMODE(info.st_mode), "mode_octal": format(stat.S_IMODE(info.st_mode), "04o"),
            "dev": info.st_dev, "ino": info.st_ino, "nlink": info.st_nlink, "bytes": info.st_size,
            "mtime_ns": info.st_mtime_ns, "ctime_ns": info.st_ctime_ns,
            "group_writable": bool(info.st_mode & 0o020), "other_writable": bool(info.st_mode & 0o002)}


def metadata(path):
    row = {"path": str(path), "exists": False, "lstat": None, "canonical_path": None,
           "readable_by_job": os.access(path, os.R_OK, effective_ids=True),
           "writable_by_job": os.access(path, os.W_OK, effective_ids=True),
           "executable_or_searchable_by_job": os.access(path, os.X_OK, effective_ids=True), "errors": []}
    try:
        info = path.lstat()
        row["exists"] = True
        row["lstat"] = stat_record(info)
    except OSError as error:
        row["errors"].append("LSTAT_" + type(error).__name__)
    try:
        resolved = path.resolve(strict=True)
        row["canonical_path"] = str(resolved)
        # Reading a public key only stays in the fixed keyring tree. A link to
        # any other target is recorded, never followed for bytes or metadata.
        if path == KEYRING and not resolved.is_relative_to(KEYRING.parent):
            row["errors"].append("CANONICAL_OUTSIDE_FIXED_KEYRING_TREE")
        else:
            row["follow_stat"] = stat_record(path.stat())
    except OSError as error:
        row["errors"].append("RESOLVE_" + type(error).__name__)
    except RuntimeError:
        row["errors"].append("RESOLVE_SYMLINK_LOOP")
    return row


def keyring_hash(row):
    row["sha256"] = None
    row["content_exported"] = False
    row["hash_status"] = "NOT_READ"
    if not row["exists"] or row["lstat"]["type"] != "regular":
        row["hash_status"] = "NOT_REGULAR_OR_MISSING"
        return
    if any(path.is_symlink() for path in (KEYRING, *KEYRING.parents)) or row["canonical_path"] != str(KEYRING):
        row["hash_status"] = "NOT_READ_NONCANONICAL_OR_SYMLINK_CHAIN"
        return
    try:
        fd = os.open(KEYRING, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
        try:
            before = os.fstat(fd)
            need(stat.S_ISREG(before.st_mode) and before.st_size <= MAX_KEYRING, "STOP_KEYRING_HASH_TYPE_OR_SIZE")
            row["metadata_at_hash"] = stat_record(before)
            digest = hashlib.sha256()
            count = 0
            while True:
                block = os.read(fd, 65536)
                if not block:
                    break
                count += len(block)
                need(count <= MAX_KEYRING, "STOP_KEYRING_HASH_SIZE")
                digest.update(block)
            after = os.fstat(fd)
            fields = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")
            current = KEYRING.lstat()
            need(all(getattr(before, field) == getattr(after, field) == getattr(current, field) for field in fields)
                 and before.st_dev == row["lstat"]["dev"] and before.st_ino == row["lstat"]["ino"]
                 and count == before.st_size, "STOP_KEYRING_CHANGED_DURING_HASH")
            row.update(sha256=digest.hexdigest(), hashed_bytes=count, hash_status="HASHED_STABLE_OBSERVATION")
        finally:
            os.close(fd)
    except Exception as error:
        row["hash_status"] = str(error) if isinstance(error, Stop) else "HASH_" + type(error).__name__


def original_predicate(row, parent_rows):
    # Original 8fc metadata gate: stat.S_ISREG(key_stat.st_mode) and
    # key_stat.st_uid == 0 and not key_stat.st_mode & 0o022.
    # Each term is retained even when false. This diagnoses only the new host.
    followed = row.get("follow_stat")
    result = {"code": "STOP_UBUNTU_KEYRING_METADATA", "stat_available": followed is not None,
              "is_regular": None, "uid_equals_zero": None, "group_write_bit_clear": None,
              "other_write_bit_clear": None, "mode_022_clear": None, "predicate_pass": False,
              "preceding_nonlink_chain_pass": all(item["lstat"] is not None and item["lstat"]["type"] != "symlink"
                                                   for item in [row, *parent_rows]), "failed_terms": []}
    if followed is not None:
        result.update(is_regular=followed["type"] == "regular", uid_equals_zero=followed["uid"] == 0,
                      group_write_bit_clear=not bool(followed["mode"] & 0o020),
                      other_write_bit_clear=not bool(followed["mode"] & 0o002),
                      mode_022_clear=not bool(followed["mode"] & 0o022))
        result["predicate_pass"] = result["is_regular"] and result["uid_equals_zero"] and result["mode_022_clear"]
        result["failed_terms"] = [name for name in ("is_regular", "uid_equals_zero", "mode_022_clear") if result[name] is False]
    else:
        result["failed_terms"] = ["STAT_UNAVAILABLE"]
    return result


def command_once(name, argv, out, deadline, remaining):
    need(time.monotonic() < deadline, "STOP_DIAGNOSTIC_TIME_BUDGET")
    program = Path(argv[0]).resolve(strict=True)
    nonlink(program)
    info = program.stat()
    need(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022, "STOP_READONLY_TOOL_IDENTITY")
    started = time.monotonic()
    child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             cwd="/", env=ENV, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ, "stdout")
    selector.register(child.stderr, selectors.EVENT_READ, "stderr")
    captured = {"stdout": bytearray(), "stderr": bytearray()}
    reason = None
    try:
        while selector.get_map():
            if time.monotonic() - started > 45 or time.monotonic() >= deadline:
                reason = "TIMEOUT"
                break
            for key, _ in selector.select(0.25):
                block = os.read(key.fileobj.fileno(), 65536)
                if not block:
                    selector.unregister(key.fileobj)
                    continue
                size = sum(len(value) for value in captured.values())
                if size + len(block) > remaining:
                    captured[key.data].extend(block[:max(0, remaining - size)])
                    reason = "AGGREGATE_RAW_LIMIT"
                    break
                captured[key.data].extend(block)
            if reason:
                break
    finally:
        selector.close()
        if reason:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        try:
            code = child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            code = child.wait(timeout=5)
            reason = reason or "WAIT_TIMEOUT"
        child.stdout.close()
        child.stderr.close()
    record = {"argv": argv, "exit": code, "termination_reason": reason,
              "tool_canonical_path": str(program), "tool_metadata": stat_record(info),
              "elapsed_seconds": round(time.monotonic() - started, 3)}
    for stream, raw in captured.items():
        path = out / (name + "." + stream + ".log")
        path.write_bytes(raw)
        record[stream] = {"path": path.name, "bytes": len(raw), "sha256": sha(raw)}
    return record, bytes(captured["stdout"]), remaining - sum(len(value) for value in captured.values())


def context():
    need(sys.platform == "linux" and os.environ.get("GITHUB_ACTIONS") == "true"
         and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and os.environ.get("RUNNER_OS") == "Linux"
         and os.uname().machine == "x86_64" and os.getuid() == os.geteuid() != 0, "STOP_HOST_CONTEXT")
    need(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and os.environ.get("GITHUB_REF") == BRANCH
         and os.environ.get("GITHUB_RUN_ATTEMPT") == "1", "STOP_REPOSITORY_BRANCH_ATTEMPT")
    head = os.environ.get("APPROVED_DIAGNOSTIC_COMMIT", "")
    need(re.fullmatch(r"[0-9a-f]{40}", head) and os.environ.get("GITHUB_SHA") == head, "STOP_APPROVED_HEAD")
    need(re.fullmatch(r"[1-9][0-9]*", os.environ.get("GITHUB_RUN_ID", "")), "STOP_RUN_ID")
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    source = Path(__file__).absolute()
    nonlink(workspace)
    nonlink(source)
    need(source == workspace / "runtime-proposals/b2/keyring-diagnostic.py", "STOP_DIAGNOSTIC_LOCATION")
    expected = os.environ.get("APPROVED_DIAGNOSTIC_SHA256", "")
    need(re.fullmatch(r"[0-9a-f]{64}", expected) and sha(source.read_bytes()) == expected, "STOP_APPROVED_SOURCE_HASH")
    return workspace, head


def main():
    workspace, head = context()
    out = workspace / OUTPUT
    nonlink(out)
    need(not out.exists(), "STOP_OUTPUT_ALREADY_EXISTS")
    out.mkdir(parents=True)
    state = {"schema": 1, "status": "FAILED", "capture_complete": False,
             "scope": "read-only fixed Ubuntu archive keyring metadata and dpkg verification; B2 runtime NOT_RUN",
             "run_id": int(os.environ["GITHUB_RUN_ID"]), "head_sha": head, "diagnostic_commit": head,
             "run_attempt": 1, "repository": REPOSITORY, "branch": BRANCH,
             "base_commit": BASE_COMMIT, "original_failed_run_reference": 37021592775,
             "observation_boundary": "this new diagnostic host only; does not reconstruct the earlier removed runner",
             "observed_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
             "host_identity": {"uid": os.getuid(), "euid": os.geteuid(), "gid": os.getgid(), "egid": os.getegid(),
                               "supplementary_groups": os.getgroups(), "machine": os.uname().machine},
             "diagnostic_sha256": sha(Path(__file__).read_bytes()), "keyring": None, "parent_directories": [],
             "original_predicate": None, "commands": {}, "raw_total_bytes": 0, "errors": [],
             "mutation_counters": {"apt": 0, "sdk": 0, "sudo": 0, "kvm": 0, "installation": 0,
                                   "permission_changes": 0, "cache_copy": 0, "metadata_repairs": 0, "keyring_content_exports": 0},
             "sdk_executions": 0, "harness_invocations": 0, "android_executions": 0, "observed_faults": 0,
             "runtime_status": "NOT_RUN", "package_verification_exit_is_not_runtime_result": True}
    receipt_path = out / "keyring-diagnostic.json"
    deadline = time.monotonic() + 240
    remaining = MAX_RAW_TOTAL
    write_json(receipt_path, state)
    try:
        record, stdout, remaining = command_once("checkout-head", ["/usr/bin/git", "--no-optional-locks", "-C", str(workspace), "rev-parse", "HEAD"], out, deadline, remaining)
        state["commands"]["checkout-head"] = record
        need(record["exit"] == 0 and record["termination_reason"] is None and stdout.decode().strip() == head, "STOP_CHECKOUT_HEAD")
        # Metadata is saved before either the original predicate or dpkg tools.
        state["parent_directories"] = [metadata(path) for path in reversed(KEYRING.parents)]
        state["keyring"] = metadata(KEYRING)
        write_json(receipt_path, state)
        keyring_hash(state["keyring"])
        write_json(receipt_path, state)
        state["original_predicate"] = original_predicate(state["keyring"], state["parent_directories"])
        write_json(receipt_path, state)
        for name, argv in COMMANDS.items():
            record, _, remaining = command_once(name, argv, out, deadline, remaining)
            state["commands"][name] = record
            write_json(receipt_path, state)
            need(record["termination_reason"] is None, "STOP_COMMAND_CAPTURE")
        state["capture_complete"] = True
        state["status"] = "DIAGNOSTIC_CAPTURED"
    except Exception as error:
        state["errors"].append(str(error) if isinstance(error, Stop) else "STOP_METADATA_" + type(error).__name__)
    state["raw_total_bytes"] = MAX_RAW_TOTAL - remaining
    write_json(receipt_path, state)
    (out / "diagnostic-commit.txt").write_bytes((head + "\n").encode())
    return 0 if state["capture_complete"] else 1


if __name__ == "__main__":
    sys.exit(main())
