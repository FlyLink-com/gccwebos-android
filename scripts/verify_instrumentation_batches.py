#!/usr/bin/env python3
"""Validate the fresh-store partition and account for completed batch results, offline."""
import argparse
import json
import re
from pathlib import Path

FRESH_CLASS = 'com.gccwebos.android.SelectedNodeActivityRecreationTest'
FRESH_METHODS = {
    'changedSameNameCachedContentClearsSelectionDuringRealActivityRecreationAndRejectsOldOpaque',
    'selectedSameNameSecondNodeSurvivesRealActivityRecreationWithTheSameOpaqueIdentity',
}


def parse(text):
    fields, started, completed = {}, [], []
    active = None
    expected = None
    terminal, summaries = [], []
    for line in text.splitlines():
        assert not any(marker in line for marker in (
            'FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed',
            'INSTRUMENTATION_RESULT: shortMsg=')), 'Runner failure marker'
        match = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test|current|numtests)=(.+)', line)
        if match:
            assert match[1] not in fields, 'Duplicate status field'
            fields[match[1]] = match[2]
        match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            assert fields.keys() == {'class', 'test', 'current', 'numtests'}, 'Incomplete test identity'
            identity = (fields['class'], fields['test'], int(fields['current']))
            count = int(fields['numtests'])
            assert count > 0 and (expected is None or count == expected), 'Inconsistent test count'
            expected = count
            code = int(match[1])
            if code == 1:
                assert active is None and identity[2] == len(completed) + 1, 'Duplicate or missing test start'
                assert identity[:2] not in [item[0][:2] for item in completed], 'Repeated test method'
                active = identity
                started.append(identity)
            else:
                assert code in (0, -3, -4), 'Failed or unknown result code'
                # AndroidJUnitRunner may report @Ignore directly, without a start.
                # Keep M0's existing skip policy; the native verifier rejects every skip.
                ignored = code == -3 and active is None
                assert active == identity or (ignored and identity[2] == len(completed) + 1), 'Result does not match its start'
                assert identity[:2] not in [item[0][:2] for item in completed], 'Repeated test method'
                completed.append((identity, code, not ignored))
                active = None
            fields = {}
        match = re.fullmatch(r'OK \((\d+) tests?\)', line)
        if match:
            summaries.append(int(match[1]))
        match = re.fullmatch(r'INSTRUMENTATION_CODE: (-?\d+)', line)
        if match:
            terminal.append(int(match[1]))
    assert not fields and active is None, 'Truncated instrumentation'
    assert expected and len(completed) == expected, 'Incomplete batch'
    # JUnit may omit @Ignore from runCount even though Android advertised/reported it.
    # Neither form grants a pass: ignored tests remain separately counted below.
    run_count = expected - sum(code == -3 for _, code, _ in completed)
    assert len(summaries) == 1 and summaries[0] in {expected, run_count} and terminal == [-1], 'Missing or conflicting runner completion'
    return completed


def fresh(text):
    results = parse(text)
    assert len(results) == 2, 'Fresh batch must contain exactly two tests'
    assert {identity[0] for identity, _, _ in results} == {FRESH_CLASS}, 'Unexpected fresh class'
    assert {identity[1] for identity, _, _ in results} == FRESH_METHODS, 'Unexpected fresh methods'
    assert all(code == 0 and started for _, code, started in results), 'Fresh tests must execute and pass, never skip'
    return results


def summary(fresh_text, main_text, exclusions):
    before = fresh(fresh_text)
    after = parse(main_text)
    assert FRESH_CLASS in exclusions, 'Fresh class must be excluded from the main batch'
    assert all(identity[0] not in exclusions for identity, _, _ in after), 'Excluded class ran in main batch'
    assert not ({identity[:2] for identity, _, _ in before} &
                {identity[:2] for identity, _, _ in after}), 'A test ran in both batches'
    def counts(results):
        return {'reported': len(results), 'started': sum(started for _, _, started in results),
                'passed': sum(code == 0 for _, code, _ in results),
                'skipped': sum(code in (-3, -4) for _, code, _ in results), 'failed': 0}
    return {'fresh': counts(before), 'main': counts(after), 'total': counts(before + after),
            'main_excluded_classes': sorted(exclusions)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fresh_log', type=Path)
    parser.add_argument('--main-log', type=Path)
    parser.add_argument('--excluded', default='')
    args = parser.parse_args()
    text = args.fresh_log.read_text(encoding='utf-8')
    if args.main_log:
        result = summary(text, args.main_log.read_text(encoding='utf-8'), set(args.excluded.split(',')))
    else:
        fresh(text)
        result = {'fresh': {'started': 2, 'passed': 2, 'skipped': 0, 'failed': 0}}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
