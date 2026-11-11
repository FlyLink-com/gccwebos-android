#!/usr/bin/env python3
"""Generate a conservative manifest-level SPDX SBOM for FlyLink Android.

Uses only the Python standard library. It does not resolve dependencies or infer
licenses from package names. Unknown licenses remain NOASSERTION.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import quote


TOOL_VERSION = "0.1.0"
GRADLE_CONFIGS = {
    "implementation": "runtime",
    "api": "runtime",
    "runtimeOnly": "runtime",
    "compileOnly": "compileOnly",
    "testImplementation": "test",
    "androidTestImplementation": "androidTest",
    "debugImplementation": "debug",
}
KNOWN_MAVEN_LICENSES = {
    "com.journeyapps:zxing-android-embedded": "Apache-2.0",
    "com.google.zxing:core": "Apache-2.0",
    "org.json:json": "NOASSERTION",
    "junit:junit": "EPL-1.0",
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def stable_id(prefix: str, value: str) -> str:
    return prefix + "-" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def normalize_spdx(value: str | None) -> str:
    if not value:
        return "NOASSERTION"
    value = value.strip()
    # SPDX 2.3 replaced the deprecated GPL-3.0 identifier with GPL-3.0-only.
    if value == "GPL-3.0":
        return "GPL-3.0-only"
    return value


def parse_gradle_dependencies(text: str) -> list[dict]:
    result = []
    bom_match = re.search(r'val\s+composeBom\s*=\s*"([^"]+)"', text)
    bom_coordinate = bom_match.group(1) if bom_match else None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        match = re.search(
            r'\b(implementation|api|runtimeOnly|compileOnly|testImplementation|androidTestImplementation|debugImplementation)\s*\(\s*"([^"]+)"\s*\)',
            stripped,
        )
        if match:
            configuration, coordinate = match.groups()
            parts = coordinate.split(":")
            if len(parts) < 2:
                continue
            group, artifact = parts[0], parts[1]
            version = parts[2] if len(parts) > 2 and parts[2] else None
            key = group + ":" + artifact
            result.append({
                "ecosystem": "maven",
                "name": key,
                "group": group,
                "artifact": artifact,
                "version": version,
                "scope": GRADLE_CONFIGS[configuration],
                "direct": True,
                "license": KNOWN_MAVEN_LICENSES.get(key, "NOASSERTION"),
                "licenseStatus": {
                    "com.journeyapps:zxing-android-embedded": "VERIFIED_BUNDLED_APACHE_LICENSE",
                    "com.google.zxing:core": "VERIFIED_BUNDLED_APACHE_LICENSE",
                    "org.json:json": "VERIFIED_UPSTREAM_PUBLIC_DOMAIN",
                    "junit:junit": "VERIFIED_UPSTREAM_EPL_METADATA",
                }.get(key, "PENDING_UPSTREAM_VERIFICATION"),
                "source": "app/build.gradle.kts",
            })
        if "platform(composeBom)" in stripped and bom_coordinate:
            parts = bom_coordinate.split(":")
            if len(parts) == 3:
                result.append({
                    "ecosystem": "maven",
                    "name": parts[0] + ":" + parts[1],
                    "group": parts[0],
                    "artifact": parts[1],
                    "version": parts[2],
                    "scope": "platform",
                    "direct": True,
                    "license": "NOASSERTION",
                    "licenseStatus": "PENDING_UPSTREAM_VERIFICATION",
                    "source": "app/build.gradle.kts Compose BOM",
                })
    return result


def parse_go_mod(text: str, source_lock: dict | None = None) -> list[dict]:
    modules = []
    in_require = False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        if stripped == "require (":
            in_require = True
            continue
        if in_require and stripped == ")":
            in_require = False
            continue
        candidate = stripped
        if candidate.startswith("require "):
            candidate = candidate[len("require "):]
        elif not in_require:
            continue
        if candidate.startswith("(") or candidate.startswith("replace "):
            continue
        parts = candidate.split()
        if len(parts) < 2 or not parts[1].startswith("v"):
            continue
        module, version = parts[0], parts[1]
        indirect = len(parts) > 2 and parts[2] == "//" and len(parts) > 3 and parts[3] == "indirect"
        modules.append({
            "ecosystem": "golang",
            "name": module,
            "version": version,
            "scope": "indirect" if indirect else "direct",
            "direct": not indirect,
            "license": "NOASSERTION",
            "licenseStatus": "PENDING_UPSTREAM_VERIFICATION",
            "source": "native/go.mod",
        })

    if source_lock and isinstance(source_lock.get("mihomo"), dict):
        item = source_lock["mihomo"]
        module_name = "github.com/metacubex/mihomo"
        found = next((x for x in modules if x["name"] == module_name), None)
        license_id = normalize_spdx(item.get("license"))
        if found:
            found["license"] = license_id
            found["licenseStatus"] = "PINNED_UPSTREAM_LICENSE_RECORDED"
            found["upstreamCommit"] = item.get("commit")
            found["sourceArchiveSha256"] = item.get("sha256")
        else:
            modules.append({
                "ecosystem": "golang",
                "name": module_name,
                "version": item.get("version", "NOASSERTION"),
                "scope": "runtime",
                "direct": True,
                "license": license_id,
                "licenseStatus": "PINNED_UPSTREAM_LICENSE_RECORDED",
                "upstreamCommit": item.get("commit"),
                "sourceArchiveSha256": item.get("sha256"),
                "source": "native/source-lock.json",
            })
    return modules


def read_json(path: Path, default):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def asset_manifest_checks(root: Path) -> tuple[list[dict], list[dict]]:
    expected_by_path: dict[str, list[dict]] = {}
    branding = read_json(root / "docs/branding-assets.json", [])
    native_ui = read_json(root / "docs/native-ui-assets.json", {"assets": []})

    for item in branding:
        asset = item.get("asset")
        if not asset:
            continue
        path = "app/src/main/res/drawable-nodpi/" + asset
        if item.get("bundledSha256"):
            expected_by_path.setdefault(path, []).append({
                "manifest": "docs/branding-assets.json",
                "expectedSha256": item["bundledSha256"],
            })
    for item in native_ui.get("assets", []):
        path = item.get("path")
        digest = item.get("sha256")
        if path and digest:
            expected_by_path.setdefault(path, []).append({
                "manifest": "docs/native-ui-assets.json",
                "expectedSha256": digest,
            })

    checks = []
    conflicts = []
    for path, declarations in sorted(expected_by_path.items()):
        values = sorted({x["expectedSha256"] for x in declarations})
        if len(values) > 1:
            conflicts.append({
                "path": path,
                "declaredHashes": values,
                "manifests": declarations,
                "status": "CONFLICT",
            })
        file_path = root / path
        actual = sha256_bytes(file_path.read_bytes()) if file_path.is_file() else None
        for declaration in declarations:
            checks.append({
                "path": path,
                "manifest": declaration["manifest"],
                "expectedSha256": declaration["expectedSha256"],
                "actualSha256": actual,
                "status": "MISSING" if actual is None else (
                    "MATCH" if actual == declaration["expectedSha256"] else "MISMATCH"
                ),
            })
    return checks, conflicts


def registered_asset_file_checks(root: Path, register: dict) -> list[dict]:
    """Verify that every registered bundled asset exists and matches its pinned hashes."""
    checks = []
    hash_fields = (
        "declaredBundledSha256",
        "secondManifestSha256",
        "declaredSha256",
        "verifiedBundledSha256",
    )
    for item in register.get("assets", []):
        path = item.get("path")
        if not path:
            continue
        file_path = root / path
        expected_hashes = [
            {"field": field, "sha256": item[field]}
            for field in hash_fields
            if item.get(field)
        ]
        if not file_path.is_file():
            actual = None
            size = None
            status = "MISSING"
        else:
            actual = sha256_bytes(file_path.read_bytes())
            size = file_path.stat().st_size
            if not expected_hashes:
                status = "UNPINNED"
            elif any(actual != entry["sha256"] for entry in expected_hashes):
                status = "MISMATCH"
            else:
                status = "MATCH"
        checks.append({
            "path": path,
            "actualSha256": actual,
            "actualSizeBytes": size,
            "expectedHashes": expected_hashes,
            "status": status,
        })
    return checks


def find_unregistered_brand_assets(root: Path, register: dict) -> list[str]:
    registered = {x.get("path") for x in register.get("assets", [])}
    discovered = set()
    for folder in (
        root / "app/src/main/res/drawable",
        root / "app/src/main/res/drawable-nodpi",
    ):
        if folder.exists():
            for path in folder.glob("brand_*"):
                if path.is_file():
                    discovered.add(path.relative_to(root).as_posix())
    return sorted(discovered - registered)


def component_purl(component: dict) -> str | None:
    if component["ecosystem"] == "maven":
        base = "pkg:maven/" + quote(component["group"], safe=".-_") + "/" + quote(component["artifact"], safe=".-_")
        return base + ("@" + quote(component["version"], safe=".-_+") if component.get("version") else "")
    if component["ecosystem"] == "golang":
        # Go module paths escape uppercase ASCII as ! followed by lowercase.
        escaped = "".join(("!" + ch.lower()) if ch.isupper() else ch for ch in component["name"])
        base = "pkg:golang/" + quote(escaped, safe="/.-_~!")
        return base + ("@" + quote(component["version"], safe=".-_+") if component.get("version") else "")
    return None


def to_spdx_package(component: dict) -> dict:
    identity = component["ecosystem"] + ":" + component["name"] + "@" + str(component.get("version") or "UNPINNED")
    package = {
        "name": component["name"],
        "SPDXID": stable_id("SPDXRef-Package", identity),
        "downloadLocation": "NOASSERTION",
        "filesAnalyzed": False,
        "licenseConcluded": "NOASSERTION",
        "licenseDeclared": component.get("license", "NOASSERTION"),
        "copyrightText": "NOASSERTION",
        "primaryPackagePurpose": "LIBRARY",
        "comment": "scope=" + component.get("scope", "unknown") + "; licenseStatus=" + component.get("licenseStatus", "UNKNOWN") + "; source=" + component.get("source", "unknown"),
    }
    if component.get("version"):
        package["versionInfo"] = component["version"]
    purl = component_purl(component)
    if purl:
        package["externalRefs"] = [{
            "referenceCategory": "PACKAGE-MANAGER",
            "referenceType": "purl",
            "referenceLocator": purl,
        }]
    return package


def build_spdx(root: Path, components: list[dict], source_files: list[dict]) -> dict:
    git_sha = "unknown"
    try:
        git_sha = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        pass
    package_ids = []
    packages = []
    for component in components:
        package = to_spdx_package(component)
        packages.append(package)
        package_ids.append(package["SPDXID"])
    files = []
    file_ids = []
    for item in source_files:
        path = root / item["path"]
        if not path.is_file():
            continue
        digest = sha256_bytes(path.read_bytes())
        file_id = stable_id("SPDXRef-File", item["path"])
        files.append({
            "fileName": "./" + item["path"],
            "SPDXID": file_id,
            "checksums": [{"algorithm": "SHA256", "checksumValue": digest}],
            "licenseInfoInFiles": [item["license"]],
            "licenseConcluded": item["license"],
            "copyrightText": "NOASSERTION",
        })
        file_ids.append(file_id)
    namespace_seed = git_sha + "|" + "|".join(sorted(package_ids + file_ids))
    namespace_hash = sha256_bytes(namespace_seed.encode("utf-8"))
    relationships = [
        {"spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES", "relatedSpdxElement": item}
        for item in package_ids + file_ids
    ]
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "FlyLink VPN Android manifest-level dependency SBOM",
        "documentNamespace": "https://spdx.org/spdxdocs/flylink-android-manifest-" + namespace_hash,
        "creationInfo": {
            "creators": ["Tool: flylink-compliance-audit-" + TOOL_VERSION],
            "created": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        },
        "comment": "Manifest-only inventory. Not a resolved releaseRuntimeClasspath, ABI-specific native binary SBOM, or legal license approval. Unknown license fields are intentionally NOASSERTION.",
        "packages": packages,
        "files": files,
        "relationships": relationships,
    }


def run_audit(root: Path, out_dir: Path) -> dict:
    gradle_path = root / "app/build.gradle.kts"
    go_mod_path = root / "native/go.mod"
    source_lock_path = root / "native/source-lock.json"
    if not gradle_path.is_file() or not go_mod_path.is_file() or not source_lock_path.is_file():
        raise FileNotFoundError("Expected app/build.gradle.kts, native/go.mod and native/source-lock.json")

    source_lock = read_json(source_lock_path, {})
    gradle = parse_gradle_dependencies(gradle_path.read_text(encoding="utf-8"))
    go_modules = parse_go_mod(go_mod_path.read_text(encoding="utf-8"), source_lock)
    components = gradle + go_modules

    bridge_files = []
    bridge_root = root / "native/bridge"
    if bridge_root.exists():
        for path in sorted(bridge_root.iterdir()):
            if not path.is_file() or path.suffix not in (".go", ".c", ".h"):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            match = re.search(r"SPDX-License-Identifier:\s*([^\s*]+)", text)
            bridge_files.append({
                "path": path.relative_to(root).as_posix(),
                "license": normalize_spdx(match.group(1)) if match else "NOASSERTION",
            })

    asset_checks, asset_conflicts = asset_manifest_checks(root)
    register = read_json(root / "docs/compliance/asset-rights-register.json", {"assets": []})
    unregistered_assets = find_unregistered_brand_assets(root, register)
    registered_asset_checks = registered_asset_file_checks(root, register)

    unknown_components = [
        {"ecosystem": x["ecosystem"], "name": x["name"], "version": x.get("version"), "scope": x["scope"]}
        for x in components if x.get("license", "NOASSERTION") == "NOASSERTION"
    ]
    audit = {
        "schemaVersion": "1.0.0",
        "repository": "FlyLink-com/gccwebos-android",
        "sbomScope": "manifest-only",
        "baselineCommit": "c19d5bf57388b427a5e8248d838cbf3e87b39d47",
        "componentCount": len(components),
        "gradleDeclaredComponentCount": len(gradle),
        "goModuleCount": len(go_modules),
        "unknownLicenseCount": len(unknown_components),
        "unknownLicenseComponents": unknown_components,
        "bridgeSourceFiles": bridge_files,
        "assetHashChecks": asset_checks,
        "assetManifestConflicts": asset_conflicts,
        "registeredAssetChecks": registered_asset_checks,
        "unregisteredBrandAssets": unregistered_assets,
        "limitations": [
            "Gradle dependencies are declared coordinates, not a resolved releaseRuntimeClasspath.",
            "Go components are go.mod module requirements, not an ABI/build-tag exact binary closure.",
            "NOASSERTION is not an open-source license and must be resolved from upstream evidence.",
            "Asset hashes prove byte identity only; they do not prove copyright ownership or redistribution permission.",
        ],
    }
    sbom = build_spdx(root, components, bridge_files)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "android-manifest-sbom.spdx.json").write_text(
        json.dumps(sbom, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (out_dir / "license-audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return audit


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--out-dir", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    out_dir = (args.out_dir or (root / "docs/compliance/generated")).resolve()
    audit = run_audit(root, out_dir)
    print(json.dumps({
        "status": "GENERATED_MANIFEST_ONLY",
        "sbom": str(out_dir / "android-manifest-sbom.spdx.json"),
        "audit": str(out_dir / "license-audit.json"),
        "componentCount": audit["componentCount"],
        "unknownLicenseCount": audit["unknownLicenseCount"],
        "assetHashChecks": len(audit["assetHashChecks"]),
        "assetManifestConflicts": len(audit["assetManifestConflicts"]),
        "unregisteredBrandAssets": len(audit["unregisteredBrandAssets"]),
        "registeredAssetCheckFailures": sum(1 for x in audit["registeredAssetChecks"] if x["status"] != "MATCH"),
        "missingRegisteredAssets": sum(1 for x in audit["registeredAssetChecks"] if x["status"] == "MISSING"),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
