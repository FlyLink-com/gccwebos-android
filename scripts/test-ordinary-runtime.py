#!/usr/bin/env python3
"""Reject incomplete/skipped suites and artifact provenance substitution."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
v = load('verifier', 'verify_ordinary_instrumentation.py')
m = load('materializer', 'materialize-ordinary-runtime.py')
def fixture():
    lines = []
    for i in range(59):
        cls, name = v.SKIP if i == 0 else ('synthetic.Test', 'case' + str(i))
        for code in (1, -4 if i == 0 else 0):
            lines += [f'INSTRUMENTATION_STATUS: class={cls}', 'INSTRUMENTATION_STATUS: numtests=59',
                      f'INSTRUMENTATION_STATUS: test={name}', f'INSTRUMENTATION_STATUS_CODE: {code}']
    return '\n'.join(lines) + '\nOK (59 tests)\nINSTRUMENTATION_CODE: -1\n'
class Guards(unittest.TestCase):
    def test_exact_suite(self):
        self.assertEqual(v.verify(fixture())['passed'], 58)
    def test_unexpected_skip(self):
        with self.assertRaises(AssertionError): v.verify(fixture().replace('CODE: 0', 'CODE: -4', 1))
    def test_missing_case(self):
        with self.assertRaises(AssertionError): v.verify(fixture().replace('case58', 'case57'))
    def test_native_case_falsely_passes(self):
        with self.assertRaises(AssertionError): v.verify(fixture().replace('CODE: -4', 'CODE: 0'))
    def test_failure(self):
        with self.assertRaises(AssertionError): v.verify(fixture().replace('CODE: 0', 'CODE: -2', 1))
    def test_wrong_artifact(self):
        with self.assertRaises(AssertionError): m.validate_archive(Path('/unused'), {'id': 0})
    def test_wrong_archive_digest(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / 'wrong.zip'
            p.write_bytes(b'x' * m.ZIP_SIZE)
            metadata = {'id': m.ARTIFACT_ID, 'expired': False, 'size_in_bytes': m.ZIP_SIZE,
                        'digest': 'sha256:' + m.ZIP_SHA, 'workflow_run': {
                            'id': m.RUN_ID, 'head_sha': m.SOURCE, 'repository_id': 1393547812}}
            with self.assertRaisesRegex(AssertionError, 'Archive identity mismatch'): m.validate_archive(p, metadata)
if __name__ == '__main__': unittest.main()
