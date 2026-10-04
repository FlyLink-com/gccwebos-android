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
        'INFO | Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)\n'
        'INFO | Graphics API Version OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader)\n')
VALUES = {'api': '36', 'size': 'Physical size: 1080x2400', 'density': 'Physical density: 420',
          'boot-vulkan': '', 'vulkan': '', 'hwui': 'skiagl', 'gles': '196608'}


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


if __name__ == '__main__':
    unittest.main()
