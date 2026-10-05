#!/usr/bin/env python3
"""Offline boundary tests for stable build policy and exact native payload checks."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

import verify_release_policy as policy


class ReleasePolicyTests(unittest.TestCase):
    source = (policy.ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')

    def test_current_source_is_a_stable_release_configuration(self):
        policy.verify_configuration(self.source)

    def test_release_cannot_revert_to_candidate_version(self):
        for old, new in [('versionCode = 7', 'versionCode = 6'),
                         ('versionName = "0.4.0"', 'versionName = "0.4.0-rc.1"')]:
            with self.subTest(new=new), self.assertRaises(AssertionError):
                policy.verify_configuration(self.source.replace(old, new))

    def test_every_release_flag_is_enforced(self):
        prefix, release = self.source.split('        release {', 1)
        for name, expected in policy.FLAGS.items():
            changed = release.replace(f'"{name}", "{expected}"', f'"{name}", "{str(expected == "false").lower()}"')
            with self.subTest(flag=name), self.assertRaises(AssertionError):
                policy.verify_configuration(prefix + '        release {' + changed)

    def test_debuggable_or_test_package_release_is_rejected(self):
        for old, new in [('isDebuggable = false', 'isDebuggable = true'),
                         ('        release {', '        release {\napplicationIdSuffix = ".candidate"'),
                         ('        release {', '        release {\nsigningConfig = signingConfigs.getByName("debug")')]:
            with self.subTest(new=new), self.assertRaises(AssertionError):
                policy.verify_configuration(self.source.replace(old, new))

    def test_release_native_gate_cannot_be_removed(self):
        with self.assertRaises(AssertionError):
            policy.verify_configuration(self.source.replace('it.name == "preReleaseBuild"', 'it.name == "unused"'))

    def test_candidate_gate_cannot_mask_missing_release_dependency(self):
        opening = 'tasks.matching { it.name == "preReleaseBuild" }.configureEach {\n    dependsOn(verifiedConnectionLibraries)'
        with self.assertRaises(AssertionError):
            policy.verify_configuration(self.source.replace(opening, 'tasks.matching { it.name == "preReleaseBuild" }.configureEach {'))

    def test_native_gate_cannot_be_registered_only_for_candidates(self):
        conditional = self.source.replace('val verifiedConnectionLibraries =', 'if (providers.gradleProperty("connectionCandidate").orNull == "true") {\nval verifiedConnectionLibraries =') + '\n}'
        with self.assertRaises(AssertionError):
            policy.verify_configuration(conditional)

    def test_ordinary_debug_is_not_made_native_dependent(self):
        with self.assertRaises(AssertionError):
            policy.verify_configuration(self.source + '\ntasks.named("preBuild").configure { dependsOn(verifiedConnectionLibraries) }')

    def test_gradle_cannot_drift_from_pinned_library_or_notice_identity(self):
        for expected in set(policy.LIBRARIES.values()) | set(policy.NOTICES.values()):
            with self.subTest(expected=expected), self.assertRaises(AssertionError):
                policy.verify_configuration(self.source.replace(expected, '0' * 64))

    def fixture(self, apk):
        libraries = {abi: hashlib.sha256(abi.encode()).hexdigest() for abi in policy.LIBRARIES}
        notice_data = {name: name.encode() + b'\nLicense fixture\n' for name in policy.NOTICES}
        notices = {name: hashlib.sha256(data).hexdigest() for name, data in notice_data.items()}
        values = {(f'lib/{abi}/libgccmihomo.so' if apk else f'native/build/jniLibs/{abi}/libgccmihomo.so'): abi.encode() for abi in libraries}
        values.update({(f'assets/{name}' if apk else f'native/notices/{name}'): notice_data[name] for name in notices})
        return libraries, notices, values

    def test_exact_payload_passes_for_inputs_and_apk_paths(self):
        for apk in (False, True):
            libraries, notices, values = self.fixture(apk)
            with self.subTest(apk=apk):
                policy.verify_payload(values.__getitem__, libraries, notices, apk=apk)

    def test_windows_checkout_license_line_endings_preserve_verified_text(self):
        for apk in (False, True):
            libraries, notices, values = self.fixture(apk)
            values = {name: data.replace(b'\n', b'\r\n') if name.endswith('.txt') else data for name, data in values.items()}
            with self.subTest(apk=apk):
                policy.verify_payload(values.__getitem__, libraries, notices, apk=apk)

    def test_either_abi_or_license_missing_fails(self):
        for apk in (False, True):
            libraries, notices, values = self.fixture(apk)
            for missing in values:
                remaining = {name: data for name, data in values.items() if name != missing}
                with self.subTest(apk=apk, missing=missing), self.assertRaises(KeyError):
                    policy.verify_payload(remaining.__getitem__, libraries, notices, apk=apk)

    def test_any_changed_abi_or_license_fails(self):
        for apk in (False, True):
            libraries, notices, values = self.fixture(apk)
            for changed in values:
                corrupt = {**values, changed: b'wrong identity'}
                with self.subTest(apk=apk, changed=changed), self.assertRaises(AssertionError):
                    policy.verify_payload(corrupt.__getitem__, libraries, notices, apk=apk)

    def apk(self, manifest, *, duplicate=False, omit=None, corrupt=None):
        libraries, notices, values = self.fixture(True)
        values['AndroidManifest.xml'] = manifest
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'release.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                for name, value in values.items():
                    if name != omit:
                        archive.writestr(name, b'wrong bytes' if name == corrupt else value)
                if duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore', UserWarning)
                        archive.writestr('AndroidManifest.xml', manifest)
            policy.verify_apk(path, libraries, notices)

    def test_release_container_with_both_exact_libraries_and_notices_passes(self):
        self.apk(b'ProductionVpnService')

    def test_duplicate_apk_entry_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.apk(b'ProductionVpnService', duplicate=True)

    def test_apk_with_missing_or_corrupt_library_or_license_is_rejected(self):
        _, _, values = self.fixture(True)
        for name in values:
            with self.subTest(missing=name), self.assertRaises(KeyError):
                self.apk(b'ProductionVpnService', omit=name)
            with self.subTest(corrupt=name), self.assertRaises(AssertionError):
                self.apk(b'ProductionVpnService', corrupt=name)

    def test_debug_lab_manifest_markers_are_rejected_in_both_encodings(self):
        for marker in ('LabVpnService', 'NativeVpnLabActivity', 'FOREGROUND_SERVICE_SPECIAL_USE'):
            for encoding in ('utf-8', 'utf-16le'):
                with self.subTest(marker=marker, encoding=encoding), self.assertRaises(AssertionError):
                    self.apk(('ProductionVpnService ' + marker).encode(encoding))

    def test_apk_without_production_service_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.apk(b'OtherService')


if __name__ == '__main__':
    unittest.main()
