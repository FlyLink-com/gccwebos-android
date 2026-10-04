#!/usr/bin/env python3
"""Bound the disposable API36 framebuffer; reject drift before app installation."""
from pathlib import Path
import re
import struct
import sys


def configure(text):
    targets = {
        'hw.lcd.width': ('1080', '540'),
        'hw.lcd.height': ('2400', '1200'),
        'hw.lcd.density': ('420', '210'),
        'skin.name': (None, '540x1200'),
        'skin.path': (None, '540x1200'),
    }
    # Validate every key before writing. The three LCD keys must come from Pixel7.
    for key, (original, _) in targets.items():
        values = re.findall(r'^[ \t]*' + re.escape(key) + r'[ \t]*=[ \t]*([^\r\n]*)', text, re.M)
        values = [value.strip() for value in values]
        if len(values) > 1 or (original is not None and values != [original]):
            raise ValueError(f'Unexpected or duplicate AVD key: {key}')
    for key, (_, target) in targets.items():
        pattern = r'^[ \t]*' + re.escape(key) + r'[ \t]*=[^\r\n]*'
        if re.search(pattern, text, re.M):
            text = re.sub(pattern, key + '=' + target, text, flags=re.M)
        else:
            text = text.rstrip('\n') + '\n' + key + '=' + target + '\n'
    return text


def verify(size, density, api, font_scale):
    # Exact single-line readbacks reject wm overrides and unexpected image defaults.
    expected = ('Physical size: 540x1200', 'Physical density: 210', '36')
    if tuple(value.strip() for value in (size, density, api)) != expected:
        raise ValueError('Unexpected physical display, override, or API level')
    if font_scale.strip() not in ('1', '1.0'):
        raise ValueError('Unexpected font scale')


def verify_png(header):
    if (len(header) != 24 or header[:8] != b'\x89PNG\r\n\x1a\n'
            or header[8:16] != b'\x00\x00\x00\rIHDR'
            or struct.unpack('>II', header[16:24]) != (540, 1200)):
        raise ValueError('Unexpected screenshot framebuffer')


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == 'configure':
        path = Path(sys.argv[2])
        path.write_text(configure(path.read_text(encoding='utf-8')), encoding='utf-8')
    elif len(sys.argv) == 6 and sys.argv[1] == 'verify':
        verify(*(Path(name).read_text(encoding='utf-8') for name in sys.argv[2:]))
    elif len(sys.argv) == 3 and sys.argv[1] == 'verify-png':
        with Path(sys.argv[2]).open('rb') as stream:
            verify_png(stream.read(24))
    else:
        raise SystemExit('Usage: m0_display.py configure CONFIG | verify SIZE DENSITY API FONT | verify-png PNG')
