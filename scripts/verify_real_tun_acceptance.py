#!/usr/bin/env python3
"""Require actual emulator TUN packet proofs and lifecycle cases, never an assumed JNI pass."""
import json
import re
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
name = 'realTunTcpUdpDnsStopRevokeLatePermissionAndProcessCleanup'
codes = re.findall(r'INSTRUMENTATION_STATUS: test=' + name + r'\r?\nINSTRUMENTATION_STATUS_CODE: (-?\d+)', text)
assert codes == ['1', '0'], f'Real TUN case must execute and pass, not skip: {codes}'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[34]|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', text)
report = json.loads(Path(sys.argv[2]).read_text())
assert report['productionVpnEnabled'] is False and report['otherProtocolsTested'] is False
cases = {c['case']: c for c in report['cases']}
for name in ('first-start', 'fresh-process-restart', 'before-platform-revocation'):
    case = cases[name]
    assert all(case[k] is True for k in ('tcpProof', 'udpProof', 'dnsThroughProxy'))
    assert all(case[k] > 0 for k in ('tcpRequests', 'udpRequests', 'dnsRequests'))
    assert case['unexpectedErrors'] == []
    assert case['privateDnsProbesRejected'] >= 0
assert cases['first-start']['pid'] != cases['fresh-process-restart']['pid']
assert cases['explicit-stop']['tunGone'] and cases['explicit-stop']['processGone']
assert cases['process-death']['tunGone'] and cases['process-death']['noAutomaticRestart']
assert cases['process-death']['quietMillis'] >= 10000 and cases['process-death']['pendingRestart'] is False
assert cases['platform-revoke']['onRevoke'] and cases['platform-revoke']['tunGone']
assert cases['late-permission']['grantedAfterCancel'] and cases['late-permission']['noAutomaticStart']
print('PASS: real app-only emulator TUN carried synthetic SS AEAD TCP/UDP/DNS; stop/restart/revoke/late-permission/process-death verified')
print('NOT PROVEN: other protocols, production service, other app/default traffic, full-device leak prevention')
