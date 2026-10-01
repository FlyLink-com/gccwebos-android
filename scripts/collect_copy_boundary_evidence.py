"""Inspect compiled B1 APKs only. Never installs or runs an Android package."""
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path


def capture(command, output):
    value = subprocess.run([str(v) for v in command], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
    output.write_text(value, encoding="utf-8")
    return value


def main():
    sdk, output = Path(sys.argv[1]), Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    tools = sdk / "build-tools" / "36.0.0"
    suffix = ".exe" if sys.platform == "win32" else ""
    signer = tools / ("apksigner.bat" if sys.platform == "win32" else "apksigner")
    aapt = tools / ("aapt2" + suffix)
    candidates = [
        ("target", Path("copyBoundaryLab/build/outputs/apk/debug/copyBoundaryLab-debug.apk"),
         "com.gccwebos.android.ipcqa"),
        ("test", Path("copyBoundaryLab/build/outputs/apk/androidTest/debug/copyBoundaryLab-debug-androidTest.apk"),
         "com.gccwebos.android.ipcqa.test"),
    ]
    evidence = {"source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "android_execution": "NOT_RUN", "installations": 0, "process_exits": 0, "apks": []}
    certificates = []
    for role, apk, expected in candidates:
        badging = capture([aapt, "dump", "badging", apk], output / (role + "-badging.txt"))
        manifest = capture([aapt, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
                           output / (role + "-manifest.txt"))
        signature = capture([signer, "verify", "--verbose", "--print-certs", apk],
                            output / (role + "-signature.txt"))
        package = re.search(r"package: name='([^']+)'", badging).group(1)
        assert package == expected, "Unexpected package"
        permissions = re.findall(r"^uses-permission[^:]*:.*$", badging, re.MULTILINE)
        assert not permissions, "Permission-free candidate gate failed"
        assert not re.search(r"E: (uses-permission[^ ]*|permission|permission-group)\b", manifest), "Manifest permission gate failed"
        assert not re.search(r"E: (activity|activity-alias|receiver|provider)\b", manifest), "Unexpected component"
        assert "sharedUserId" not in manifest, "Shared UID gate failed"
        if role == "target":
            assert len(re.findall(r"E: service\b", manifest)) == 1, "Expected one service"
            assert "com.gccwebos.android.ipcqa.CopyBoundaryQaService" in manifest, "Service identity"
            assert 'Raw: ":ipcqa"' in manifest, "Service process"
            assert re.search(r"android:exported[^\n]*=\s*(?:false|\(type 0x12\)0x0)\s*$", manifest,
                             re.MULTILINE), "Service exported"
            assert "versionCode='1'" in badging and "versionName='0.1-e2b-lab'" in badging, "Version identity"
        else:
            assert "CopyBoundaryRunner" in manifest and 'Raw: "com.gccwebos.android.ipcqa"' in manifest, "Runner target"
            assert not re.search(r"E: service\b", manifest), "Unexpected test service"
        certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signature).group(1).lower()
        certificates.append(certificate)
        with zipfile.ZipFile(apk) as archive:
            native = [name for name in archive.namelist() if name.startswith("lib/") or name.endswith(".so")]
        assert not native, "Native library gate failed"
        evidence["apks"].append({"role": role, "path": str(apk), "package": package,
                                 "size_bytes": apk.stat().st_size,
                                 "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                 "certificate_sha256": certificate, "permissions": [], "native_libraries": []})
    assert certificates[0] == certificates[1], "Target/test signer mismatch"
    (output / "candidate.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("B1 compile candidate identity/manifest/signature gates: PASS; Android tests NOT_RUN")


if __name__ == "__main__":
    main()
