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



    def test_pending_trademark_review_blocks_even_if_asset_is_otherwise_approved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            compliance = root / "docs/compliance"
            generated = compliance / "generated"
            generated.mkdir(parents=True)
            policy = {
                "targetProjectLicense": "GPL-3.0-only",
                "publicationStatus": "NOT_PUBLISHED",
                "copyrightHolder": {"legalName": None, "verificationStatus": "UNVERIFIED"},
                "codeOwnership": {},
                "commercialRelicensing": {},
                "releaseRequirements": {
                    "brandTrademarkReviewApproved": False,
                    "contributorCommercialRelicensingAuthorityVerified": False,
                    "correspondingSourcePackageVerified": False,
                    "gplv3InstallationInformationReviewed": False,
                    "legalReviewApproved": False,
                },
            }
            assets = {
                "approvalFieldsRequired": [],
                "assets": [{
                    "path": "app/src/main/res/drawable/brand_telegram.xml",
                    "rightsStatus": "APPROVED",
                    "publicRedistributionAuthorized": True,
                    "trademarkReview": "PENDING",
                }],
            }
            audit = {
                "sbomScope": "manifest-only",
                "unknownLicenseCount": 1,
                "assetManifestConflicts": [],
                "assetHashChecks": [],
                "unregisteredBrandAssets": [],
                "registeredAssetChecks": [],
            }
            (compliance / "license-policy.json").write_text(json.dumps(policy), encoding="utf-8")
            (compliance / "asset-rights-register.json").write_text(json.dumps(assets), encoding="utf-8")
            (generated / "license-audit.json").write_text(json.dumps(audit), encoding="utf-8")

            result = evaluate(root)

            self.assertEqual(result["status"], "BLOCKED")
            self.assertIn("BRAND_TRADEMARK_REVIEW_NOT_APPROVED", result["blockers"])
            self.assertEqual(result["pendingTrademarkReviewCount"], 1)

    def test_bridge_source_file_without_spdx_blocks_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            compliance = root / "docs/compliance"
            generated = compliance / "generated"
            generated.mkdir(parents=True)
            policy = {
                "targetProjectLicense": "GPL-3.0-only",
                "publicationStatus": "NOT_PUBLISHED",
                "copyrightHolder": {"legalName": None, "verificationStatus": "UNVERIFIED"},
                "codeOwnership": {},
                "commercialRelicensing": {},
                "releaseRequirements": {
                    "bridgeSourceFilesSpdxComplete": False,
                    "nativePatchOwnershipVerified": False,
                    "brandTrademarkReviewApproved": True,
                    "contributorCommercialRelicensingAuthorityVerified": False,
                    "correspondingSourcePackageVerified": False,
                    "gplv3InstallationInformationReviewed": False,
                    "legalReviewApproved": False,
                },
            }
            assets = {"approvalFieldsRequired": [], "assets": []}
            audit = {
                "sbomScope": "manifest-only",
                "unknownLicenseCount": 0,
                "bridgeSourceFiles": [
                    {"path": "native/bridge/callbacks.h", "license": "NOASSERTION"}
                ],
                "assetManifestConflicts": [],
                "assetHashChecks": [],
                "unregisteredBrandAssets": [],
                "registeredAssetChecks": [],
            }
            (compliance / "license-policy.json").write_text(json.dumps(policy), encoding="utf-8")
            (compliance / "asset-rights-register.json").write_text(json.dumps(assets), encoding="utf-8")
            (generated / "license-audit.json").write_text(json.dumps(audit), encoding="utf-8")

            result = evaluate(root)

            self.assertEqual(result["status"], "BLOCKED")
            self.assertIn("BRIDGE_SOURCE_FILE_LICENSE_UNDECLARED", result["blockers"])
            self.assertIn("NATIVE_PATCH_RIGHTS_UNVERIFIED", result["blockers"])
            self.assertEqual(result["bridgeSourceLicenseFailureCount"], 1)
            self.assertFalse(result["nativePatchOwnershipVerified"])

    def test_alipay_icon_requires_mit_notice_in_android_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            compliance = root / "docs/compliance"
            generated = compliance / "generated"
            generated.mkdir(parents=True)
            alipay = root / "app/src/main/res/drawable/brand_alipay.xml"
            alipay.parent.mkdir(parents=True)
            alipay.write_text("<vector/>", encoding="utf-8")
            policy = {
                "targetProjectLicense": "GPL-3.0-only",
                "publicationStatus": "NOT_PUBLISHED",
                "copyrightHolder": {"legalName": None, "verificationStatus": "UNVERIFIED"},
                "codeOwnership": {},
                "commercialRelicensing": {},
                "releaseRequirements": {
                    "brandTrademarkReviewApproved": False,
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
            (compliance / "license-policy.json").write_text(json.dumps(policy), encoding="utf-8")
            (compliance / "asset-rights-register.json").write_text(json.dumps(assets), encoding="utf-8")
            (generated / "license-audit.json").write_text(json.dumps(audit), encoding="utf-8")

            result = evaluate(root)
            self.assertIn("ALIPAY_ASSET_MIT_NOTICE_NOT_PACKAGED", result["blockers"])
            self.assertFalse(result["alipayAssetLicenseNoticePackaged"])

            notice = root / "app/src/main/assets/licenses/Ant-Design-Alipay-MIT.txt"
            notice.parent.mkdir(parents=True)
            notice.write_text("MIT License\nCopyright (c) 2018-present Ant UED\n", encoding="utf-8")
            result_with_notice = evaluate(root)
            self.assertNotIn("ALIPAY_ASSET_MIT_NOTICE_NOT_PACKAGED", result_with_notice["blockers"])
            self.assertTrue(result_with_notice["alipayAssetLicenseNoticePackaged"])

if __name__ == "__main__":
    unittest.main()
