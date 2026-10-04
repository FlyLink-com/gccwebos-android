#!/usr/bin/env python3
"""Offline rejection checks for emulator display drift; no ADB or SDK execution."""
import unittest
import struct
from m0_display import configure, verify, verify_png


class DisplayTests(unittest.TestCase):
    original = ('hw.lcd.width=1080\nhw.lcd.height=2400\nhw.lcd.density=420\n'
                'skin.name=1080x2400\nskin.path=_no_skin\nhw.cpu.ncore=2\n')

    def test_scales_framebuffer_and_magic_skin_without_changing_other_keys(self):
        result = configure(self.original)
        for line in ('hw.lcd.width=540', 'hw.lcd.height=1200', 'hw.lcd.density=210',
                     'skin.name=540x1200', 'skin.path=540x1200', 'hw.cpu.ncore=2'):
            self.assertEqual(result.splitlines().count(line), 1)

    def test_missing_optional_skin_keys_are_materialized_once(self):
        result = configure('\n'.join(self.original.splitlines()[:3]) + '\n')
        self.assertEqual(result.splitlines().count('skin.name=540x1200'), 1)
        self.assertEqual(result.splitlines().count('skin.path=540x1200'), 1)

    def test_missing_duplicate_and_drifted_required_keys_are_rejected(self):
        for line in self.original.splitlines()[:3]:
            for text in (self.original.replace(line + '\n', ''),
                         self.original + line + '\n', self.original.replace(line, line + '0')):
                with self.subTest(text=text), self.assertRaises(ValueError):
                    configure(text)

    def test_duplicate_skin_keys_are_rejected(self):
        for key in ('skin.name', 'skin.path'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                configure(self.original + key + '=unexpected\n')

    def test_indented_duplicate_and_empty_required_value_are_rejected(self):
        for text in (self.original + '  hw.lcd.width = 1080\n',
                     self.original.replace('hw.lcd.width=1080', 'hw.lcd.width=')):
            with self.subTest(text=text), self.assertRaises(ValueError):
                configure(text)

    def test_exact_device_readbacks_accept_crlf(self):
        verify('Physical size: 540x1200\r\n', 'Physical density: 210\r\n', '36\r\n', '1.0\r\n')

    def test_screenshot_size_accepts_actual_target_and_rejects_old_or_invalid_header(self):
        prefix = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'
        verify_png(prefix + struct.pack('>II', 540, 1200))
        for header in (prefix + struct.pack('>II', 1080, 2400), b'',
                       b'not PNG!' + prefix[8:] + struct.pack('>II', 540, 1200)):
            with self.subTest(header=header), self.assertRaises(ValueError):
                verify_png(header)

    def test_old_display_override_wrong_api_and_font_scale_are_rejected(self):
        expected = ['Physical size: 540x1200', 'Physical density: 210', '36', '1.0']
        for index, wrong in ((0, 'Physical size: 1080x2400'),
                             (0, expected[0] + '\nOverride size: 540x1200'),
                             (1, 'Physical density: 420'),
                             (1, expected[1] + '\nOverride density: 210'),
                             (2, '33'), (3, 'null'), (3, '1.3')):
            data = expected.copy()
            data[index] = wrong
            with self.subTest(index=index, wrong=wrong), self.assertRaises(ValueError):
                verify(*data)


if __name__ == '__main__':
    unittest.main()
