#!/usr/bin/env python3
"""Offline collection failure tests: no ADB, emulator or network access."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location(
    'anr_diagnostics', Path(__file__).with_name('collect_m0_anr.py'))
diagnostics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostics)
BASH = os.environ.get('M0_TEST_BASH') or shutil.which('bash')


class AnrDiagnosticsTests(unittest.TestCase):
    def collect(self, side_effect):
        with tempfile.TemporaryDirectory(prefix='m0-anr-test-') as directory:
            destination = Path(directory) / 'anr'
            with patch.object(diagnostics.subprocess, 'run', side_effect=side_effect) as run:
                result = diagnostics.collect('fake-adb', destination)
            return result, run.call_args_list, {
                path.name: path.read_bytes() for path in destination.iterdir()
            }

    def test_success_retains_raw_output_and_uses_only_owned_emulator(self):
        def success(command, **kwargs):
            kwargs['stdout'].write(b'synthetic stack evidence\n')
            if 'bugreport' in command:
                with zipfile.ZipFile(command[-1], 'w') as archive:
                    archive.writestr('synthetic.txt', 'fixture; not a real ANR')
            return subprocess.CompletedProcess(command, 0)

        result, calls, files = self.collect(success)
        self.assertEqual([entry['status'] for entry in result], ['completed', 'completed'])
        self.assertEqual([entry['returncode'] for entry in result], [0, 0])
        self.assertEqual(len(calls), 2)
        for call, deadline in zip(calls, (20, 90)):
            self.assertEqual(call.args[0][:3], ['fake-adb', '-s', 'emulator-5554'])
            self.assertEqual(call.kwargs['timeout'], deadline)
            self.assertEqual(call.kwargs['stderr'], subprocess.STDOUT)
            self.assertNotIn('shell', call.kwargs)
        self.assertEqual(calls[0].args[0][3:],
                         ['shell', 'dumpsys', 'dropbox', '--print', 'system_app_anr'])
        self.assertEqual(calls[1].args[0][3], 'bugreport')
        self.assertEqual(Path(calls[1].args[0][4]).name, 'bugreport.zip')
        self.assertEqual(files['system-app-anr.log'], b'synthetic stack evidence\n')
        self.assertIn('collection.json', files)
        self.assertTrue(result[1]['zip_exists'])
        self.assertTrue(result[1]['zip_container_valid'])

    def test_nonzero_dropbox_does_not_prevent_bugreport_or_become_success(self):
        result, calls, _ = self.collect([
            subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0)])
        self.assertEqual(len(calls), 2)
        self.assertEqual(result[0]['returncode'], 1)
        self.assertEqual(result[1]['returncode'], 0)
        self.assertFalse(result[1]['zip_exists'])
        self.assertFalse(result[1]['zip_container_valid'])

    def test_timeout_retains_partial_output_and_still_attempts_next_capture(self):
        def timeout_then_success(command, **kwargs):
            if 'dumpsys' in command:
                kwargs['stdout'].write(b'partial synthetic evidence\n')
                raise subprocess.TimeoutExpired(command, kwargs['timeout'])
            return subprocess.CompletedProcess(command, 0)

        result, calls, files = self.collect(timeout_then_success)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result[0]['status'], 'timeout')
        self.assertIsNone(result[0]['returncode'])
        self.assertEqual(result[1]['returncode'], 0)
        self.assertEqual(files['system-app-anr.log'], b'partial synthetic evidence\n')

    def test_both_captures_timing_out_remain_diagnostic_failures(self):
        result, calls, _ = self.collect(subprocess.TimeoutExpired('fake-adb', 20))
        self.assertEqual(len(calls), 2)
        self.assertEqual([entry['status'] for entry in result], ['timeout', 'timeout'])
        self.assertEqual([entry['returncode'] for entry in result], [None, None])

    def test_missing_adb_is_recorded_without_aborting_receipts(self):
        result, calls, files = self.collect(FileNotFoundError('synthetic missing adb'))
        self.assertEqual(len(calls), 2)
        self.assertEqual([entry['status'] for entry in result], ['os_error', 'os_error'])
        self.assertIn('collection.json', files)

    def test_bugreport_error_or_truncated_archive_is_not_reported_valid(self):
        for returncode in (0, 1):
            def truncated(command, **kwargs):
                if 'bugreport' in command:
                    Path(command[-1]).write_bytes(b'PK incomplete synthetic archive')
                return subprocess.CompletedProcess(command, returncode)

            with self.subTest(returncode=returncode):
                result, _, _ = self.collect(truncated)
                self.assertEqual(result[1]['returncode'], returncode)
                self.assertTrue(result[1]['zip_exists'])
                self.assertFalse(result[1]['zip_container_valid'])

    def test_actual_boot_failure_branches_preserve_exit_and_success_does_not_collect(self):
        script = Path(__file__).with_name('m0-emulator-smoke.sh').read_text(encoding='utf-8')
        function = 'collect_boot_anr() {' + script.split('collect_boot_anr() {', 1)[1].split(
            '\nverify_renderer()', 1)[0]
        gates = script.split('verify_renderer post-warmboot\n', 1)[1].split(
            "printf '%s\\n' 'One pre-install", 1)[0]
        for event_anr, error_window, collection_status in (
                (True, False, 0), (True, False, 1), (True, False, 124),
                (False, True, 0), (False, True, 1), (False, False, 1)):
            with self.subTest(event_anr=event_anr, error_window=error_window,
                              collection_status=collection_status), tempfile.TemporaryDirectory() as d:
                root = Path(d)
                (root / 'post-warmboot-logcat.log').write_text(
                    'am_anr : synthetic\n' if event_anr else 'ordinary log\n')
                (root / 'post-warmboot-window.log').write_text(
                    'mCurrentFocus=Application Not Responding\n' if error_window
                    else 'mCurrentFocus=Launcher\n')
                harness = ('set -euo pipefail\nOUT="$1"\nADB=fake-adb\n'
                           'log() { :; }\n'
                           f'python3() {{ echo called >> "$OUT/calls"; return {collection_status}; }}\n'
                           + function + '\n' + gates + '\nexit 0\n')
                run = subprocess.run([BASH, '-s', '--', root.as_posix()], input=harness,
                                     capture_output=True, text=True, timeout=10)
                self.assertEqual(run.returncode, 1 if event_anr or error_window else 0,
                                 run.stderr)
                self.assertEqual((root / 'calls').exists(), event_anr or error_window)


if __name__ == '__main__':
    unittest.main()
