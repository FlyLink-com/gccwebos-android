#!/usr/bin/env python3
"""Require all 59 original cases; only the ordinary variant's JNI assumption may skip."""
import json
from pathlib import Path
import re
import sys

SKIP = ('com.gccwebos.android.NativeBridgeInstrumentedTest',
        'actualJniLibraryLoadsAndRejectsUnsafeInputWithoutTakingCallerFd')

def verify(text):
    assert re.search(r'OK \(59 tests\)', text), 'Expected the complete 59-case suite'
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', text)
    assert re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', text, re.MULTILINE) == ['-1'], 'Instrumentation did not finish normally'
    pending = {}
    records = {}
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
            pending[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(': ')[2])
            assert code in (1, 0, -4), f'Unexpected status {code}'
            assert pending.get('numtests') == '59'
            key = (pending.get('class'), pending.get('test'))
            assert all(key), 'Missing case identity'
            records.setdefault(key, []).append(code)
            pending = {}
    assert len(records) == 59, 'Missing or duplicated suite cases'
    assert records.get(SKIP) == [1, -4], 'Only actual JNI assumption must skip in ordinary variant'
    assert all(codes == [1, 0] for key, codes in records.items() if key != SKIP), 'Non-JNI case skipped or failed'
    return {'tests': 59, 'passed': 58, 'assumption_skipped': 1, 'skipped_case': '#'.join(SKIP)}

if __name__ == '__main__':
    path = Path(sys.argv[1])
    result = verify(path.read_text())
    path.with_name('ordinary-test-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: 58 cases passed; exactly one named JNI assumption skipped; all 59 present')
