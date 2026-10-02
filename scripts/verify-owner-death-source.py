#!/usr/bin/env python3
"""B2 source isolation and Java checks; never builds or executes an Android package.

The default runs source checks and the pure JVM ledger smoke. With an explicitly
supplied existing SDK it also compiles Android Java sources against platform 36.
Android compilation is not a merged-manifest, signing, IPC, death or device test.
No tool, SDK package, dependency or executable is downloaded by this script.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "ownerDeathLab"
PACKAGE = "com.gccwebos.android.ipcqa.b2"
REL_PACKAGE = "com/gccwebos/android/ipcqa/b2"
B1_ROOT = ROOT / "copyBoundaryLab/src/main/java/com/gccwebos/android/ipcqa"
ANDROID = "{http://schemas.android.com/apk/res/android}"
B1_SHA256_LF = {
    "Wire.java": "68a03e92068cdd83ff3c933229bc19a477918851667195a2e5595801c3fd2e74",
    "CopyLedger.java": "44813e17a186109648efdd8aa7611d14eb91a8f4302c434cbb43d7277f53d87b",
    "RemoteCopyClient.java": "add91c6b992b08f98d7364e08f5f6cefc05fa392834e608d2510259a88d76a80",
}
SMOKE_CLASS = PACKAGE + ".DeathRecoveryLedgerSmoke"
MAIN_REQUIRED = (
    "B2Control.java", "DeathQaClient.java", "DeathRecoveryLedger.java",
    "DeathSessionGuard.java", "EndpointWatch.java",
    "OwnerDeathQaService.java", "WorkerDeathQaService.java",
)
TEST_REQUIRED = ("OwnerDeathRunner.java", "OwnerDeathIpcTest.java")


class VerificationFailure(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationFailure(message)


def text(path: Path) -> str:
    require(path.is_file(), "Missing source: " + str(path.relative_to(ROOT)))
    return path.read_text(encoding="utf-8")


def sha256(path: Path, *, lf: bool = False) -> str:
    data = path.read_bytes()
    if lf:
        data = data.replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def check_manifest(path: Path, *, target: bool) -> None:
    root = ET.fromstring(text(path))
    require(root.tag == "manifest", "Manifest root must be manifest")
    require(list(root.attrib) == [], "Source manifest must not override package identity")
    require([node.tag for node in root] == ["application"],
            "Source manifest permits only application; no permissions or extra declarations")
    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "allowBackup") == "false" and
            app.get(ANDROID + "usesCleartextTraffic") == "false", "Application isolation flags changed")
    allowed_app = {ANDROID + "allowBackup", ANDROID + "usesCleartextTraffic"}
    if target:
        allowed_app.add(ANDROID + "label")
    require(set(app.attrib) <= allowed_app, "Unexpected application attributes")
    expected = {
        ".OwnerDeathQaService": ":ownerqa",
        ".WorkerDeathQaService": ":workerqa",
    } if target else {}
    require(len(app) == len(expected), "Unexpected application component count")
    seen = set()
    for node in app:
        name = node.get(ANDROID + "name")
        require(node.tag == "service" and name in expected and name not in seen,
                "Only the two private ordinary QA services are admitted")
        seen.add(name)
        require(node.attrib == {
            ANDROID + "name": name,
            ANDROID + "process": expected[name],
            ANDROID + "exported": "false",
            ANDROID + "stopWithTask": "false",
        }, "Service process, privacy or attributes changed")
        require(len(node) == 0, "No intent filters, launcher or service metadata are admitted")


def static_checks() -> dict:
    check_manifest(MODULE / "src/main/AndroidManifest.xml", target=True)
    check_manifest(MODULE / "src/androidTest/AndroidManifest.xml", target=False)
    build = text(MODULE / "build.gradle.kts")
    declarations = (
        'plugins { id("com.android.application") }',
        'namespace = "' + PACKAGE + '"',
        'applicationId = "' + PACKAGE + '"',
        'testApplicationId = "' + PACKAGE + '.test"',
        'testInstrumentationRunner = "' + PACKAGE + '.OwnerDeathRunner"',
        'compileSdk = 36', 'buildToolsVersion = "36.0.0"',
        'minSdk = 26', 'targetSdk = 36', 'versionCode = 1',
        'versionName = "0.1-e2b-b2-lab"',
        'sourceCompatibility = JavaVersion.VERSION_17',
        'targetCompatibility = JavaVersion.VERSION_17',
        'sourceSets.getByName("main").java.srcDir(generatedB1Sources.get().asFile)',
        'tasks.register<Sync>("syncSharedB1Sources")',
        'from(rootProject.layout.projectDirectory.dir("copyBoundaryLab/src/main/java"))',
        'include(sharedB1Files)', 'into(generatedB1Sources)',
        'tasks.named("preBuild").configure { dependsOn(syncSharedB1Sources) }',
        'beforeVariants(selector().withBuildType("release")) { it.enable = false }',
    )
    for declaration in declarations:
        require(declaration in build, "Missing B2 build boundary: " + declaration)
    require(not re.search(r"\bdependencies\s*\{", build), "B2 must have no dependency block")
    require(not re.search(r"\b(?:externalNativeBuild|jniLibs|implementation|api|runtimeOnly)\b", build),
            "Application, native or library dependency leaked into B2 build")
    shared = re.search(r"val sharedB1Files = listOf\((.*?)\)", build, re.S)
    require(shared is not None, "Missing explicit shared source allowlist")
    listed = re.findall(r'"([^"]+)"', shared.group(1))
    expected_shared = ["com/gccwebos/android/ipcqa/" + name for name in B1_SHA256_LF]
    require(listed == expected_shared, "B2 must reuse exactly Wire, CopyLedger and RemoteCopyClient")
    for name, expected in B1_SHA256_LF.items():
        require(sha256(B1_ROOT / name, lf=True) == expected,
                "Shared B1 source changed (LF-normalized SHA256): " + name)

    settings = text(ROOT / "settings.gradle.kts")
    require(re.search(
        r'if \(providers\.gradleProperty\("ownerDeathLab"\)\.orNull == "true"\)\s*'
        r'\{\s*include\(":ownerDeathLab"\)\s*\}', settings) is not None,
        "B2 inclusion must require the explicit ownerDeathLab=true property")
    require(settings.count('include(":ownerDeathLab")') == 1, "Duplicate B2 inclusion")
    for relative, required in (("main", MAIN_REQUIRED), ("androidTest", TEST_REQUIRED)):
        directory = MODULE / "src" / relative / "java" / REL_PACKAGE
        for name in required:
            text(directory / name)
    smoke = MODULE / "src/test/java" / REL_PACKAGE / "DeathRecoveryLedgerSmoke.java"
    text(smoke)
    for path in MODULE.rglob("*.java"):
        if "build" in path.relative_to(MODULE).parts:
            continue
        source = text(path)
        require(re.search(r"^package " + re.escape(PACKAGE) + r";", source, re.M) is not None,
                "B2 sources must preserve the separate B2 package: " + path.name)
        require(not re.search(r"^import (?:java\.net\.|javax\.net\.|android\.net\.)", source, re.M),
                "Network or VPN import in B2 source: " + path.name)
        require(not re.search(r"(?:System\.load(?:Library)?\(|Runtime\.getRuntime\(\)\.exec\(|new ProcessBuilder\()", source),
                "Native loader or shell subprocess in B2 source: " + path.name)
    ledger = text(MODULE / "src/main/java" / REL_PACKAGE / "DeathRecoveryLedger.java")
    require(not re.search(r"^import android\.", ledger, re.M), "Recovery ledger must be pure JVM")
    require(not any(path.is_file() for path in MODULE.rglob("*.so")), "B2 must contain no native library")
    for folder in ("jni", "jniLibs", "cpp"):
        require(not (MODULE / "src/main" / folder).exists(), "B2 must contain no native source set")

    workflow = text(ROOT / ".github/workflows/owner-death-source.yml")
    require("branches: [codex/lucky-b2-preparation]" in workflow and
            "if: github.ref == 'refs/heads/codex/lucky-b2-preparation'" in workflow,
            "Source-only workflow must be scoped to its exact preparation branch")
    require("pull_request:" not in workflow and "workflow_dispatch:" not in workflow,
            "Source-only workflow must not add PR or dispatch triggers")
    require(not re.search(r"\b(?:sdkmanager|emulator|adb|gradlew)\b", workflow),
            "Source-only workflow contains Android provisioning, package or device operations")
    checked_sources = sorted((MODULE / "src").rglob("*.java")) + [
        MODULE / "src/main/AndroidManifest.xml", MODULE / "src/androidTest/AndroidManifest.xml",
        MODULE / "build.gradle.kts", ROOT / "settings.gradle.kts",
        ROOT / ".github/workflows/owner-death-source.yml", Path(__file__).resolve(),
    ]
    return {
        "status": "passed",
        "package": PACKAGE,
        "test_package": PACKAGE + ".test",
        "version_code": 1,
        "version_name": "0.1-e2b-b2-lab",
        "permissions_in_source_manifests": 0,
        "shared_b1_sha256_lf": B1_SHA256_LF,
        "source_sha256": {path.relative_to(ROOT).as_posix(): sha256(path) for path in checked_sources},
        "manifest_evidence": "source XML only; merged APK manifests and signatures not produced",
    }


def tool(name: str, explicit: str | None) -> str:
    found = explicit or shutil.which(name)
    require(found is not None and Path(found).is_file(), "Existing tool not found: " + name)
    return str(Path(found).resolve())


def run(command: list[str], receipt: dict, stage: str) -> None:
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    receipt.setdefault("commands", []).append(command)
    receipt.setdefault("logs", {})[stage] = {
        "exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
    }
    if result.stdout:
        print(result.stdout.rstrip())
    if result.stderr:
        print(result.stderr.rstrip(), file=sys.stderr)
    require(result.returncode == 0, stage + " failed")


def host_checks(args: argparse.Namespace, output: Path, receipt: dict) -> None:
    javac, java = tool("javac", args.javac), tool("java", args.java)
    sources = [
        B1_ROOT / "CopyLedger.java",
        MODULE / "src/main/java" / REL_PACKAGE / "DeathRecoveryLedger.java",
        MODULE / "src/test/java" / REL_PACKAGE / "DeathRecoveryLedgerSmoke.java",
    ]
    with tempfile.TemporaryDirectory(prefix="host-classes-", dir=output) as temp:
        run([javac, "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-proc:none", "-d", temp]
            + [str(path) for path in sources], receipt, "host_javac")
        run([java, "-cp", temp, SMOKE_CLASS], receipt, "host_jvm_smoke")
    receipt["host"] = {"status": "passed", "smoke_class": SMOKE_CLASS,
                       "evidence": "synthetic pure JVM ownership only; no Android Binder or process evidence"}


def existing_junit(args: argparse.Namespace, sdk: Path) -> Path | None:
    if args.junit_jar:
        candidate = Path(args.junit_jar).resolve()
        require(candidate.is_file() and candidate.suffix == ".jar", "Explicit existing JUnit JAR not found")
        return candidate
    candidates = [sdk / "platforms/android-36/optional/junit.jar",
                  Path("/usr/share/java/junit4.jar"), Path("/usr/share/java/junit.jar")]
    shared_java = Path("/usr/share/java")
    if shared_java.is_dir():
        candidates.extend(sorted(shared_java.glob("junit*.jar")))
    return next((path.resolve() for path in candidates if path.is_file()), None)


def android_checks(args: argparse.Namespace, output: Path, receipt: dict) -> None:
    if not args.android_sdk:
        require(not args.require_android, "Existing SDK path required for requested Android Java compilation")
        receipt["android"] = {"status": "not_run", "reason": "No explicit existing SDK supplied"}
        return
    sdk = Path(args.android_sdk).resolve()
    android_jar = sdk / "platforms/android-36/android.jar"
    require(android_jar.is_file(), "Existing platform-36 android.jar missing; no SDK installer is used")
    javac = tool("javac", args.javac)
    classpath = [android_jar]
    for name in ("android.test.base.jar", "android.test.runner.jar", "android.test.mock.jar"):
        optional = android_jar.parent / "optional" / name
        if optional.is_file():
            classpath.append(optional)
    main_sources = list((MODULE / "src/main/java").rglob("*.java"))
    main_sources += [B1_ROOT / name for name in B1_SHA256_LF]
    test_sources = list((MODULE / "src/androidTest/java").rglob("*.java"))
    needs_junit = any(re.search(r"\b(?:org\.junit|junit\.framework)\.", text(path))
                      for path in test_sources)
    junit = existing_junit(args, sdk) if args.junit_jar or needs_junit else None
    with tempfile.TemporaryDirectory(prefix="android-classes-", dir=output) as temp:
        run([javac, "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-proc:none",
             "-cp", os.pathsep.join(str(path) for path in classpath), "-d", temp]
            + [str(path) for path in sorted(main_sources)], receipt, "android_main_javac")
        receipt["android"] = {"status": "main_compiled_tests_not_run",
                              "android_jar_sha256": sha256(android_jar)}
        if junit is None and needs_junit:
            require(not args.require_android,
                    "Existing JUnit JAR missing; Android test Java compilation is blocked, no dependency is installed")
            receipt["android"]["reason"] = "No existing JUnit JAR for test Java compilation"
            return
        test_classpath = [temp] + [str(path) for path in classpath]
        if junit is not None:
            test_classpath.append(str(junit))
        run([javac, "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-proc:none",
             "-cp", os.pathsep.join(test_classpath),
             "-d", temp] + [str(path) for path in sorted(test_sources)], receipt, "android_test_javac")
        receipt["android"] = {"status": "main_and_test_java_compiled",
                              "android_jar_sha256": sha256(android_jar),
                              "junit_jar_sha256": sha256(junit) if junit else None,
                              "evidence": "Java API compilation only; no APK, signing, Binder or device execution"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--static-only", action="store_true", help="Do not invoke Java tools")
    parser.add_argument("--android-sdk", help="An existing SDK with platform-36 android.jar")
    parser.add_argument("--junit-jar", help="An existing JUnit JAR; never downloaded")
    parser.add_argument("--require-android", action="store_true", help="Fail if Android main/test Java cannot compile")
    parser.add_argument("--javac", help="Existing javac executable")
    parser.add_argument("--java", help="Existing java executable")
    parser.add_argument("--output-dir", type=Path, default=ROOT / ".build-output/owner-death-source")
    args = parser.parse_args()
    if args.static_only and (args.android_sdk or args.require_android or args.junit_jar):
        parser.error("static-only cannot be combined with Android compilation options")
    if args.junit_jar and not args.android_sdk:
        parser.error("junit-jar requires an explicit existing android-sdk")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    receipt = {"schema": 1, "kind": "B2 source-only preparation", "status": "failed",
               "host": {"status": "not_run"}, "android": {"status": "not_run"},
               "not_run": ["Gradle", "APK build", "merged APK manifests", "signing", "installation",
                           "Android instrumentation", "process death", "Binder IPC", "VPN", "network"]}
    try:
        git = shutil.which("git")
        if git:
            commit = subprocess.run([git, "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
            if commit.returncode == 0:
                receipt["source_commit"] = commit.stdout.strip()
            status = subprocess.run([git, "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True)
            if status.returncode == 0:
                receipt["source_has_uncommitted_changes"] = bool(status.stdout.strip())
        receipt["source"] = static_checks()
        print("PASS B2 static module, source-manifest isolation and unchanged shared B1 source checks")
        if not args.static_only:
            host_checks(args, output, receipt)
            android_checks(args, output, receipt)
        for name, expected in receipt["source"]["source_sha256"].items():
            require(sha256(ROOT / name) == expected, "Source changed during verification: " + name)
        receipt["status"] = "passed"
        print("PASS B2 source-only preparation; no Android package was built or executed")
    except (VerificationFailure, OSError, ET.ParseError) as failure:
        receipt["failure"] = str(failure)
        print("FAIL B2 source-only preparation: " + str(failure), file=sys.stderr)
    finally:
        (output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
