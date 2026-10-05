#!/usr/bin/env python3
"""Bounded diagnostics for the owned disposable API36 AVD, before APK install.

Called only after the boot failure verdict. Collection is not an acceptance test
and must never turn an ANR failure into a pass. No root, dialog dismissal or reboot.
"""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile


def collect(adb: str, destination: Path) -> list[dict]:
    destination.mkdir(parents=True, exist_ok=True)
    captures = (
        ('system-app-anr', ['shell', 'dumpsys', 'dropbox', '--print', 'system_app_anr'], 20),
        ('bugreport', ['bugreport', str(destination / 'bugreport.zip')], 90),
    )
    results = []
    for name, arguments, deadline in captures:
        entry = {'capture': name, 'timeout_seconds': deadline, 'returncode': None}
        with (destination / f'{name}.log').open('wb') as output:
            try:
                process = subprocess.run(
                    [adb, '-s', 'emulator-5554', *arguments], stdout=output,
                    stderr=subprocess.STDOUT, timeout=deadline, check=False)
                entry.update(status='completed', returncode=process.returncode)
            except subprocess.TimeoutExpired:
                entry['status'] = 'timeout'
            except OSError as error:
                entry.update(status='os_error', error_type=type(error).__name__)
        if name == 'bugreport':
            archive = destination / 'bugreport.zip'
            entry['zip_exists'] = archive.is_file()
            # Container presence is not a claim of complete/usable ANR traces.
            entry['zip_container_valid'] = zipfile.is_zipfile(archive)
        results.append(entry)
        # Keep the first receipt even if the job is interrupted during bugreport.
        (destination / 'collection.json').write_text(
            json.dumps(results, indent=2) + '\n', encoding='utf-8')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(collect(args.adb, args.out)))
