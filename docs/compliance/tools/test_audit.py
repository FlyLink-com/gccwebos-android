import json
import tempfile
import unittest
from pathlib import Path

from audit import (
    asset_manifest_checks,
    normalize_spdx,
    parse_go_mod,
    parse_gradle_dependencies,
)


class AuditParserTests(unittest.TestCase):
    def test_legacy_gpl_identifier_is_normalized(self):
        self.assertEqual(normalize_spdx("GPL-3.0"), "GPL-3.0-only")
        self.assertEqual(normalize_spdx("GPL-3.0-or-later"), "GPL-3.0-or-later")
        self.assertEqual(normalize_spdx(None), "NOASSERTION")

    def test_gradle_scopes_and_bom(self):
        text = '''
val composeBom = "androidx.compose:compose-bom:2026.06.01"
dependencies {
    implementation(platform(composeBom))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
'''
        items = parse_gradle_dependencies(text)
        by_name = {x["name"]: x for x in items}
        self.assertEqual(by_name["com.squareup.okhttp3:okhttp"]["scope"], "runtime")
        self.assertEqual(by_name["junit:junit"]["scope"], "test")
        self.assertEqual(by_name["androidx.test:runner"]["scope"], "androidTest")
        self.assertEqual(by_name["androidx.compose:compose-bom"]["version"], "2026.06.01")

    def test_go_module_direct_and_indirect(self):
        text = '''
module example.invalid/app
go 1.27.1
require (
    github.com/example/direct v1.2.3
    github.com/example/indirect v2.0.0 // indirect
)
'''
        items = parse_go_mod(text, {})
        by_name = {x["name"]: x for x in items}
        self.assertTrue(by_name["github.com/example/direct"]["direct"])
        self.assertEqual(by_name["github.com/example/indirect"]["scope"], "indirect")

    def test_asset_manifest_hash_conflicts_are_visible(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs").mkdir()
            (root / "app/src/main/res/drawable-nodpi").mkdir(parents=True)
            (root / "app/src/main/res/drawable-nodpi/brand_robot_welcome.webp").write_bytes(b"fixture")
            (root / "docs/branding-assets.json").write_text(json.dumps([
                {"asset": "brand_robot_welcome.webp", "bundledSha256": "a" * 64}
            ]), encoding="utf-8")
            (root / "docs/native-ui-assets.json").write_text(json.dumps({
                "assets": [{
                    "path": "app/src/main/res/drawable-nodpi/brand_robot_welcome.webp",
                    "sha256": "b" * 64
                }]
            }), encoding="utf-8")
            checks, conflicts = asset_manifest_checks(root)
            self.assertEqual(len(checks), 2)
            self.assertEqual(len(conflicts), 1)
            self.assertEqual(conflicts[0]["status"], "CONFLICT")


if __name__ == "__main__":
    unittest.main()
