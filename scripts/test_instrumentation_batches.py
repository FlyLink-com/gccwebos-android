#!/usr/bin/env python3
"""Offline transcript, partition and shared-budget tests; never invokes ADB/emulators."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('batches', ROOT / 'verify_instrumentation_batches.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
BASH = os.environ.get('M0_TEST_BASH') or shutil.which('bash')
HELPER = ROOT / 'instrumentation-batches.sh'
MANUAL = {'com.gccwebos.android.ManualImportDeviceFlowTest',
          'com.gccwebos.android.portal.ManualImportUpgradeTest'}
REAL_TUN = 'com.gccwebos.android.vpn.RealTunAcceptanceTest'


def transcript(cases, terminal=True):
    lines = []
    for index, (name, method, code) in enumerate(cases, 1):
        fields = [f'INSTRUMENTATION_STATUS: class={name}', f'INSTRUMENTATION_STATUS: test={method}',
                  f'INSTRUMENTATION_STATUS: current={index}', f'INSTRUMENTATION_STATUS: numtests={len(cases)}']
        if code != -3:
            lines += fields + ['INSTRUMENTATION_STATUS_CODE: 1']
        lines += fields + [f'INSTRUMENTATION_STATUS_CODE: {code}']
    lines += [f'OK ({len(cases)} tests)']
    if terminal:
        lines += ['INSTRUMENTATION_CODE: -1']
    return '\n'.join(lines) + '\n'


FRESH = [(verify.FRESH_CLASS, method, 0) for method in sorted(verify.FRESH_METHODS)]


class TranscriptTests(unittest.TestCase):
    def test_exact_fresh_pair_accepts_crlf_and_only_executed_passes(self):
        self.assertEqual(len(verify.fresh(transcript(FRESH).replace('\n', '\r\n'))), 2)

    def test_fresh_rejects_skips_failures_unknown_codes_and_wrong_identity(self):
        variants = [[(*FRESH[0][:2], code), FRESH[1]] for code in (-1, -2, -3, -4, 7)]
        variants += [[('other.Class', FRESH[0][1], 0), FRESH[1]],
                     [(verify.FRESH_CLASS, 'otherMethod', 0), FRESH[1]],
                     [FRESH[0], FRESH[0]], FRESH[:1], FRESH + [('other.Class', 'extra', 0)]]
        for cases in variants:
            with self.subTest(cases=cases), self.assertRaises(AssertionError):
                verify.fresh(transcript(cases))

    def test_count_or_completion_mismatch_never_becomes_success(self):
        good = transcript(FRESH)
        bad = [transcript(FRESH, terminal=False), good + 'OK (2 tests)\n',
               good.replace('numtests=2', 'numtests=3', 1),
               good.replace('current=2', 'current=1'),
               good.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: 1', 1),
               good.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
               good + 'INSTRUMENTATION_FAILED: synthetic\n', good + 'FAILURES!!!\n',
               good.replace('OK (2 tests)', 'OK (1 test)')]
        for value in bad:
            with self.subTest(value=value), self.assertRaises(AssertionError):
                verify.fresh(value)

    def test_main_skip_policy_is_preserved_and_skips_are_not_counted_as_passes(self):
        main = transcript([('ordinary.Class', 'pass', 0), ('ordinary.Class', 'assumption', -4),
                           ('ordinary.Class', 'ignored', -3)])
        result = verify.summary(transcript(FRESH), main, {verify.FRESH_CLASS} | MANUAL)
        self.assertEqual(result['total'], {'reported': 5, 'started': 4, 'passed': 3, 'skipped': 2, 'failed': 0})
        without_ignored_in_run_count = main.replace('OK (3 tests)', 'OK (2 tests)')
        self.assertEqual(verify.summary(transcript(FRESH), without_ignored_in_run_count,
                                        {verify.FRESH_CLASS} | MANUAL), result)

    def test_main_rejects_excluded_tests_duplicate_results_and_incomplete_batches(self):
        excluded = {verify.FRESH_CLASS} | MANUAL | {REAL_TUN}
        for name in excluded:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                verify.summary(transcript(FRESH), transcript([(name, 'test', 0)]), excluded)
        with self.assertRaises(AssertionError):
            verify.summary(transcript(FRESH), transcript([('other.Class', 'same', 0)] * 2), excluded)
        with self.assertRaises(AssertionError):
            verify.summary(transcript(FRESH), transcript([('other.Class', 'test', 0)]).replace('numtests=1', 'numtests=2'), excluded)


class BudgetAndPartitionTests(unittest.TestCase):
    def shell(self, body, directory):
        return subprocess.run([BASH, '-c', 'set -euo pipefail\nsource "$1"\nOUT="$2"\n' + body,
                               '--', HELPER.as_posix(), Path(directory).as_posix()],
                              capture_output=True, text=True)

    def test_exact_exclusions_preserve_all_other_tests_without_overlap(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.shell('instrumentation_exclusions\ninstrumentation_exclusions "' + REAL_TUN + '"\n', directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        m0, native = [set(line.split(',')) for line in result.stdout.splitlines()]
        self.assertEqual(m0, {verify.FRESH_CLASS} | MANUAL)
        self.assertEqual(native, m0 | {REAL_TUN})
        eligible = {verify.FRESH_CLASS, 'other.Class', 'native.JniClass'}
        self.assertEqual((eligible - native) | {verify.FRESH_CLASS}, eligible)
        self.assertFalse((eligible - native) & {verify.FRESH_CLASS})

    def test_first_batch_and_middle_launch_both_consume_one_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.shell('''SECONDS=100
instrumentation_begin
SECONDS=1000
instrumentation_remaining
SECONDS=1500
timeout() { printf '%s\\n' "$1"; }
instrumentation_timeout 1800 ignored
instrumentation_timeout 45 ignored
instrumentation_finish
instrumentation_timeout 360 ignored
''', directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ['900', '400', '45', '360'])

    def test_exhausted_budget_prevents_another_command_or_success_receipt(self):
        for seconds in (1900, 1901):
            with tempfile.TemporaryDirectory() as directory:
                result = self.shell(f'''SECONDS=100
instrumentation_begin
SECONDS={seconds}
timeout() {{ printf 'MUST_NOT_RUN\\n'; }}
instrumentation_timeout 1800 ignored
instrumentation_finish
''', directory)
                self.assertEqual(result.returncode, 124, result.stderr)
                self.assertNotIn('MUST_NOT_RUN', result.stdout)
                self.assertNotIn('elapsed_seconds=', (Path(directory) / 'instrumentation-budget.log').read_text())

    def test_batch_failure_is_fatal_and_partial_log_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.shell('''instrumentation_begin
ADB=not-a-device-command
TEST_RUNNER=synthetic.runner
timeout() { printf 'partial synthetic output\\n'; return 17; }
instrumentation_batch fresh -e class "$FRESH_STORE_CLASS"
printf 'MUST_NOT_CONTINUE\\n'
''', directory)
            self.assertEqual(result.returncode, 17)
            self.assertNotIn('MUST_NOT_CONTINUE', result.stdout)
            for name in ('instrumentation-fresh.log', 'instrumentation-all.log'):
                self.assertEqual((Path(directory) / name).read_text(), 'partial synthetic output\n')

    def test_batches_keep_individual_logs_and_append_cumulative_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.shell('''instrumentation_begin
ADB=not-a-device-command
TEST_RUNNER=synthetic.runner
timeout() { printf '%s\\n' "$SYNTHETIC_OUTPUT"; }
SYNTHETIC_OUTPUT=first
instrumentation_batch fresh -e class "$FRESH_STORE_CLASS"
SYNTHETIC_OUTPUT=second
instrumentation_batch main -e notClass "$(instrumentation_exclusions)"
''', directory)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((Path(directory) / 'instrumentation-all.log').read_text(), 'first\nsecond\n')
            self.assertEqual((Path(directory) / 'instrumentation.log').read_text(), 'second\n')

    def test_both_entrypoints_run_fresh_before_normal_launch_and_keep_later_gates(self):
        for name in ('m0-emulator-smoke.sh', 'real-tun-runtime-api33.sh'):
            text = (ROOT / name).read_text(encoding='utf-8')
            positions = [text.index(marker) for marker in ('grep -q \'Success\' "$OUT/install-test.log"',
                'instrumentation_begin', 'instrumentation_batch fresh', 'shell am start -W',
                'verify_foreground launch', 'instrumentation_batch main',
                'scripts/verify_native_instrumentation.py', 'instrumentation_finish', "log 'Relaunching after tests'")]
            self.assertEqual(positions, sorted(positions), name)
            self.assertIn('verify_ui_xml "$OUT/window.xml"', text)
            if name == 'real-tun-runtime-api33.sh':
                self.assertIn('MAIN_EXCLUSIONS=$(instrumentation_exclusions "$REAL_TUN_CLASS")', text)
                self.assertLess(text.index('instrumentation_finish'), text.index('timeout 360'))
                self.assertIn('scripts/verify_real_tun_acceptance.py', text)
            else:
                self.assertIn('MAIN_EXCLUSIONS=$(instrumentation_exclusions)', text)


if __name__ == '__main__':
    unittest.main()
