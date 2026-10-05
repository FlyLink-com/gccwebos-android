#!/usr/bin/env python3
"""Offline renderer readback rejection tests; never starts an emulator or ADB."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

CHECK = Path(__file__).with_name('verify-m0-renderer.sh')
BASH = os.environ.get('M0_TEST_BASH') or shutil.which('bash')
HOST = ("DEBUG | [FeatureControl] Feature 'Vulkan' (7) state set to disabled\n"
        'INFO | emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle\n'
        'INFO | Graphics Adapter Android Emulator OpenGL ES Translator '
        '(ANGLE (Google, Vulkan 1.2.0 (SwiftShader Device (Subzero) (0x0000C0DE)), '
        'SwiftShader driver-5.0.0))\n'
        'INFO | Graphics API Version OpenGL ES 3.0 (OpenGL ES 3.1.0 '
        '(ANGLE 2.1.17841 git hash: 5fa2e61da947))\n')
VALUES = {'api': '36', 'size': 'Physical size: 1080x2400', 'density': 'Physical density: 420',
          'boot-vulkan': '', 'vulkan': '', 'hwui': 'skiagl', 'gles': '196609'}


class RendererTests(unittest.TestCase):
    def check(self, values=None, host=HOST, missing=None):
        with tempfile.TemporaryDirectory(prefix='m0-renderer-test-') as directory:
            root = Path(directory)
            for name, value in (VALUES if values is None else values).items():
                if name != missing:
                    (root / f'first-boot-{name}.log').write_text(value + '\r\n', encoding='utf-8')
            (root / 'emulator.log').write_text(host, encoding='utf-8')
            return subprocess.run([BASH, CHECK.as_posix(), root.as_posix(), 'first-boot'],
                                  capture_output=True, text=True).returncode

    def test_disabled_vulkan_and_actual_gles_readbacks_are_accepted(self):
        self.assertEqual(self.check(), 0)

    def test_current_binary_override_marker_is_accepted(self):
        host = HOST.replace("DEBUG | [FeatureControl] Feature 'Vulkan' (7) state set to disabled",
                            "INFO | Feature 'Vulkan' (21) is overridden to 'disabled'")
        self.assertEqual(self.check(host=host), 0)

    def test_gles3_minor_versions_require_matching_host_and_guest(self):
        for minor in (0, 1, 2):
            values = VALUES.copy()
            values['gles'] = str(196608 + minor)
            with self.subTest(minor=minor):
                self.assertEqual(self.check(values, HOST.replace('OpenGL ES 3.1.0',
                                                                 f'OpenGL ES 3.{minor}.0')), 0)

    def test_gles_mismatch_unknown_minor_and_later_version_change_are_rejected(self):
        for guest, host in (('196608', HOST), ('196610', HOST),
                            ('196611', HOST.replace('OpenGL ES 3.1.0', 'OpenGL ES 3.3.0')),
                            ('196609', HOST.replace('OpenGL ES 3.1.0', 'OpenGL ES 3.0.0')),
                            ('196609', HOST + 'INFO | Graphics API Version OpenGL ES 2.0\n')):
            values = VALUES.copy()
            values['gles'] = guest
            with self.subTest(guest=guest, host=host):
                self.assertNotEqual(self.check(values, host), 0)

    def test_translator_wrapper_malformed_or_non_angle_versions_are_rejected(self):
        version = ('OpenGL ES 3.0 (OpenGL ES 3.1.0 '
                   '(ANGLE 2.1.17841 git hash: 5fa2e61da947))')
        for wrong in ('OpenGL ES 3.1 (ANGLE)',
                      'OpenGL ES 3.1 (OpenGL ES 2.0 (ANGLE 2.1))',
                      'OpenGL ES 2.0 (OpenGL ES 3.1.0 (ANGLE 2.1))',
                      'OpenGL ES 3.0 (OpenGL ES 3.1.0 (Other 2.1))',
                      'OpenGL ES 3.0 (OpenGL ES 3.10.0 (ANGLE 2.1))',
                      'OpenGL ES 3.0 (OpenGL ES 3.1.0 (ANGLE 2.1)',
                      version + ' unexpected',
                      version + '\nINFO | Graphics API Version OpenGL ES 3.1 (ANGLE)'):
            with self.subTest(wrong=wrong):
                self.assertNotEqual(self.check(host=HOST.replace(version, wrong)), 0)

    def test_each_wrong_guest_readback_is_rejected(self):
        for name, value in {'api': '33', 'size': 'Physical size: 540x1200',
                            'density': 'Physical density: 210', 'boot-vulkan': 'ranchu',
                            'vulkan': 'ranchu', 'hwui': 'skiavk', 'gles': '131072'}.items():
            values = VALUES.copy()
            values[name] = value
            with self.subTest(name=name):
                self.assertNotEqual(self.check(values), 0)

    def test_missing_readback_and_wm_override_are_rejected(self):
        for name in VALUES:
            with self.subTest(name=name):
                self.assertNotEqual(self.check(missing=name), 0)
        values = VALUES.copy()
        values['size'] += '\nOverride size: 1080x2400'
        self.assertNotEqual(self.check(values), 0)

    def test_missing_or_later_enabled_feature_and_vulkan_initialization_are_rejected(self):
        for host in (HOST.replace('state set to disabled', 'state set to enabled'),
                     HOST.replace("Feature 'Vulkan'", "Feature 'Other'"),
                     HOST + "DEBUG | [FeatureControl] Feature 'Vulkan' (7) state set to enabled\n",
                     HOST + "DEBUG | [FeatureControl] Feature 'Vulkan' (7) state set to unknown\n",
                     HOST + "DEBUG | [FeatureControl] Feature 'Vulkan' (7) state set to disabled extra\n",
                     HOST + "INFO | Feature 'Vulkan' (21) is overridden to 'enabled'\n",
                     HOST + "INFO | Feature 'Vulkan' (21) is overridden to 'unknown'\n",
                     HOST + "INFO | Feature 'Vulkan' (21) is overridden to 'disabled' extra\n",
                     HOST + 'Bad feature name: Vulkan\n', HOST + 'Initializing VkEmulation features:\n',
                     HOST + 'Selecting Vulkan device: SwiftShader\n',
                     HOST + 'androidboot.hardware.vulkan=ranchu\n'):
            with self.subTest(host=host):
                self.assertNotEqual(self.check(host=host), 0)

    def test_missing_positive_gles_initialization_is_rejected(self):
        for marker in ('Graphics Adapter', 'Graphics API Version'):
            with self.subTest(marker=marker):
                self.assertNotEqual(self.check(host=HOST.replace(marker, 'Missing')), 0)

    def test_other_backends_missing_identity_and_later_fallback_are_rejected(self):
        selection = ('INFO | emuglConfig_init: vulkan_mode_selected:swiftshader '
                     'gles_mode_selected:swangle\n')
        old_adapter = ('INFO | Graphics Adapter Android Emulator OpenGL ES Translator '
                       '(Google SwiftShader)\n')
        for host in (HOST.replace(selection, ''),
                     HOST.replace('gles_mode_selected:swangle', 'gles_mode_selected:swiftshader'),
                     HOST.replace('vulkan_mode_selected:swiftshader', 'vulkan_mode_selected:host'),
                     HOST + selection.replace('gles_mode_selected:swangle', 'gles_mode_selected:host'),
                     HOST.replace('ANGLE (Google', 'Other (Google'),
                     HOST.replace('SwiftShader', 'Hardware GPU'),
                     HOST.replace('OpenGL ES 3.1.0', 'OpenGL ES 2.0.0'),
                     HOST + old_adapter):
            with self.subTest(host=host):
                self.assertNotEqual(self.check(host=host), 0)


if __name__ == '__main__':
    unittest.main()
