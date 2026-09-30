#!/usr/bin/env python3
"""A green generic instrumentation run is insufficient if the JNI case was skipped."""
import re
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
name = 'actualJniLibraryLoadsAndRejectsUnsafeInputWithoutTakingCallerFd'
codes = re.findall(r'INSTRUMENTATION_STATUS: test=' + name + r'\r?\nINSTRUMENTATION_STATUS_CODE: (-?\d+)', text)
assert codes == ['1', '0'], f'Native JNI case must start and pass, not be missing/skipped/failed: {codes}'
assert 'INSTRUMENTATION_STATUS_CODE: -4' not in text, 'Native run must not skip test cases'
assert 'INSTRUMENTATION_STATUS_CODE: -3' not in text, 'Native run must not ignore test cases'
print('PASS: actual native JNI test executed and passed; no instrumentation skips')
