import json
import tempfile
import unittest
from pathlib import Path

from release_gate import evaluate


class ReleaseGateTests(unittest.TestCase):
    def test_public_organization_does_not_replace_verified_copyright_holder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            compliance = root / "docs/compliance"
            generated = compliance / "generated"
            generated.mkdir(parents=True)

            policy = {
                "targetProjectLicense": "GPL-3.0-only",
                "publicationStatus": "NOT_PUBLISHED",
                "publicProjectOrganization": {
                    "name": "FlyLink-com",
                    "identityType": "GitHub organization account",
                    "role": "PUBLIC_PROJECT_MAINTAINER_ONLY",
                    "copyrightOwnershipClaimed": False,
                    "legalEntityIdentityClaimed": False,
                },
                "copyrightHolder": {
                    "displayName": None,
                    "legalName": None,
                    "jurisdiction": None,
                    "verificationStatus": "UNVERIFIED",
                    "publicDisclosureStatus": "NOT_PUBLICLY_DISCLOSED",
                    "evidence": [],
                },
                "codeOwnership": {
                    "status": "UNVERIFIED",
                    "coversAllOriginalFiles": False,
                    "employeeAndContractorAssignmentsVerified": False,
                    "historicalContributorsReviewed": False,
                },
                "commercialRelicensing": {"futureOptionPreserved": True},
                "releaseRequirements": {
                    "contributorCommercialRelicensingAuthorityVerified": False,
                    "correspondingSourcePackageVerified": False,
                    "gplv3InstallationInformationReviewed": False,
                    "legalReviewApproved": False,
                },
            }
            assets = {"approvalFieldsRequired": [], "assets": []}
            audit = {
                "sbomScope": "manifest-only",
                "unknownLicenseCount": 1,
                "assetManifestConflicts": [],
                "assetHashChecks": [],
                "unregisteredBrandAssets": [],
                "registeredAssetChecks": [],
            }
            (compliance / "license-policy.json").write_text(
                json.dumps(policy), encoding="utf-8"
            )
            (compliance / "asset-rights-register.json").write_text(
                json.dumps(assets), encoding="utf-8"
            )
            (generated / "license-audit.json").write_text(
                json.dumps(audit), encoding="utf-8"
            )

            result = evaluate(root)

            self.assertEqual(result["status"], "BLOCKED")
            self.assertIn("LEGAL_ENTITY_UNVERIFIED", result["blockers"])
            self.assertEqual(
                policy["publicProjectOrganization"]["role"],
                "PUBLIC_PROJECT_MAINTAINER_ONLY",
            )
            self.assertFalse(
                policy["publicProjectOrganization"]["copyrightOwnershipClaimed"]
            )


if __name__ == "__main__":
    unittest.main()
