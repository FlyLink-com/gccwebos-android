#!/usr/bin/env python3
"""Evaluate the FlyLink Android open-source release gate; expected to be BLOCKED."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(root: Path) -> dict:
    compliance = root / "docs/compliance"
    policy = load(compliance / "license-policy.json")
    assets = load(compliance / "asset-rights-register.json")
    audit_path = compliance / "generated/license-audit.json"
    if not audit_path.exists():
        subprocess.run(
            [sys.executable, str(compliance / "tools/audit.py"), "--root", str(root)],
            check=True,
        )
    audit = load(audit_path)
    requirements = policy["releaseRequirements"]
    blockers = []

    holder = policy.get("copyrightHolder", {})
    if not holder.get("legalName") or holder.get("verificationStatus") != "VERIFIED":
        blockers.append("LEGAL_ENTITY_UNVERIFIED")
    code = policy.get("codeOwnership", {})
    if code.get("status") != "VERIFIED" or not code.get("coversAllOriginalFiles"):
        blockers.append("ORIGINAL_CODE_RIGHTS_UNVERIFIED")
    if not code.get("employeeAndContractorAssignmentsVerified"):
        blockers.append("EMPLOYEE_CONTRACTOR_ASSIGNMENTS_UNVERIFIED")
    if not code.get("historicalContributorsReviewed"):
        blockers.append("HISTORICAL_CONTRIBUTORS_UNVERIFIED")
    commercial = policy.get("commercialRelicensing", {})
    if commercial.get("futureOptionPreserved") and not requirements.get("contributorCommercialRelicensingAuthorityVerified"):
        blockers.append("COMMERCIAL_RELICENSING_AUTHORITY_UNVERIFIED")
    if not (root / "LICENSE").is_file():
        blockers.append("ROOT_LICENSE_NOT_PUBLISHED")
    if audit.get("sbomScope") != "release-resolved":
        blockers.append("EXACT_RELEASE_SBOM_NOT_GENERATED")
    if audit.get("unknownLicenseCount", 0) > 0:
        blockers.append("DEPENDENCY_LICENSES_UNRESOLVED")
    if audit.get("assetManifestConflicts"):
        blockers.append("ASSET_MANIFEST_HASH_CONFLICT")
    bad_hashes = [x for x in audit.get("assetHashChecks", []) if x.get("status") != "MATCH"]
    if bad_hashes:
        blockers.append("ASSET_HASH_VERIFICATION_FAILED")
    if audit.get("unregisteredBrandAssets"):
        blockers.append("UNREGISTERED_BRAND_ASSETS")
    registered_asset_failures = [
        x for x in audit.get("registeredAssetChecks", [])
        if x.get("status") != "MATCH"
    ]
    if registered_asset_failures:
        blockers.append("REGISTERED_ASSET_FILE_OR_HASH_UNVERIFIED")

    required_asset_fields = assets.get("approvalFieldsRequired", [])
    incomplete_approved_assets = []
    for asset in assets.get("assets", []):
        if asset.get("rightsStatus") == "APPROVED":
            missing = [field for field in required_asset_fields if not asset.get(field)]
            if missing:
                incomplete_approved_assets.append({
                    "path": asset.get("path"),
                    "missingFields": missing,
                })
    if incomplete_approved_assets:
        blockers.append("ASSET_APPROVAL_EVIDENCE_INCOMPLETE")
    held_assets = [
        x.get("path") for x in assets.get("assets", [])
        if x.get("rightsStatus") != "APPROVED" or x.get("publicRedistributionAuthorized") is not True
    ]
    if held_assets:
        blockers.append("BRAND_ASSET_RIGHTS_NOT_APPROVED")
    if not requirements.get("correspondingSourcePackageVerified"):
        blockers.append("CORRESPONDING_SOURCE_PACKAGE_NOT_VERIFIED")
    if not requirements.get("gplv3InstallationInformationReviewed"):
        blockers.append("GPLV3_INSTALLATION_INFORMATION_NOT_REVIEWED")
    if not requirements.get("legalReviewApproved"):
        blockers.append("LEGAL_REVIEW_NOT_APPROVED")

    return {
        "status": "PASS" if not blockers else "BLOCKED",
        "targetLicense": policy.get("targetProjectLicense"),
        "publicationStatus": policy.get("publicationStatus"),
        "blockers": sorted(set(blockers)),
        "heldAssetCount": len(held_assets),
        "unresolvedDependencyLicenseCount": audit.get("unknownLicenseCount", 0),
        "assetHashFailureCount": len(bad_hashes),
        "assetManifestConflictCount": len(audit.get("assetManifestConflicts", [])),
        "registeredAssetCheckFailureCount": len(registered_asset_failures),
        "missingRegisteredAssetCount": sum(1 for x in audit.get("registeredAssetChecks", []) if x.get("status") == "MISSING"),
        "approvedAssetEvidenceFailureCount": len(incomplete_approved_assets),
        "unregisteredBrandAssetCount": len(audit.get("unregisteredBrandAssets", [])),
        "note": "A BLOCKED result is expected until legal rights, exact release SBOM, assets and corresponding-source evidence are approved.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    result = evaluate(args.root.resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
