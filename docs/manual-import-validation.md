# Manual import implementation and isolated validation

Date: 2026-10-04 local / 2026-10-05 UTC. Approved scope:
[manual-config-import-plan.md](manual-config-import-plan.md).

The node-page import button now opens bundled QR scanning, the Android document picker,
and subscription-link input. Input is staged until an explicit Import action. Import never
starts a VPN. Files and QR text accept supported YAML or an official subscription URL; the
link field accepts only HTTPS URL input, with the existing origin/path policy enforced by
the shared manager. No backend, original v1 encrypted format, JNI or native code changed.

## Admission and persistence

- `manual-source.bin` is an atomic encrypted sidecar with its own key/AAD/version. Its whole
  envelope selects LegacyRemote, ImportedRemote, ImportedLocal or None. Source changes
  preserve existing v1 bytes; account refresh cannot adopt a different source implicitly.
- Remote import needs its own future expiry, or previously known expiry for that exact
  capability. A pending legacy recovery journal cannot lend expiry. Local input uses typed
  `LocalNoServerExpiry`, without invented subscription dates or account entitlements.
- Each connection re-reads and validates its selected source. Remote attempts download
  again. Digest, revision, clear generation, opaque selection, schema and one-shot admission
  fences remain in force; invalid manual sources cannot fall back to a saved account source.
- A source commit requires the claimed connection owner's completed worker/cleanup and
  idle command lock. It also clears any owned scratch left by an earlier process before
  writing the source. A UI Disconnected snapshot alone is insufficient.
- Complete local clear first writes `None(clearPending=true)`, revokes saved login, removes
  old stores, and waits for actual owner cleanup. Only verified completion can clear the
  durable pending flag. Interrupted clear remains blocked after process recreation.
- UI ticket cancellation and source-version checks discard late camera/file/download
  results. Input bytes are erased on rejection, cancellation and completion. Foreground
  session validation defers during import and resumes after it settles.

The dependency is pinned to Android Embedded 4.3.0 / ZXing Core 3.4.1, matching the resolved
runtime graph. Scanner details and license sources are in
[manual-import-platform.md](manual-import-platform.md).

## Executed validation

- Initial focused JVM run: 170 tests, zero failures/errors/skips at its execution snapshot.
- Final review found that explicitly switching back to the account source left the node
  registry missing until another navigation refresh. Two new controller tests reproduced
  the failure (12 cases, 2 failures); replacing `restore()` with a fresh `refresh()` made
  both pass. The final focused run passed 172 tests with zero failures/errors/skips. Success
  publishes fresh selectable nodes; a failed download retains v1 bytes but publishes no
  selectable fallback. This explicit source switch never starts a VPN.
- The final APK was rebuilt with the same isolated UI identity and all lab/synthetic flags
  disabled. Its seven Host/account UI tests passed again; Debug/AndroidTest build and Lint
  also passed (zero errors, 31 warnings, 6 hints). The earlier 32-case, physical-platform,
  full-JVM and rollback results are retained at their earlier execution snapshots, not
  represented as full reruns after the controller follow-up.
- Full JVM run before that final one-line follow-up: 999 tests, 991 pass, 8 fail, zero
  errors/skips. The failing names remain the
  existing Windows POSIX initial-file-permission cases (one LocalSubscriptionTest and seven
  SubscriptionImportTest cases). Production 0600/atomic file behavior and those assertions
  were not weakened. This is not a fully green Windows test result.
- Debug app and Android test APKs build. Lint completes with zero errors, 31 warnings and
  6 hints. Runtime dependency insight confirms the pinned ZXing versions.
- OPPO API 33 isolated upgrade fixture: baseline seed, candidate upgrade/import and old-APK
  rollback verification each pass one explicit test. Original synthetic v1 ciphertext stays
  identical through local/remote import and removal. The old APK reads that original v1
  after same-signature, versionCode-6 replacement; sidecar remains untouched by the old APK.
- Both upgrade fixture packages were removed only after exact APK/hash and predefined
  private-file guards passed. Normal installed applications were not changed.
- OPPO isolated UI regression: 32/32 pass, covering import Host/platform/account behavior,
  encrypted sidecar and original v1 storage, node recreation, wallet resume and website UI.
- Four distinct real platform paths passed: SAF YAML, SAF URL document, physical camera QR,
  and camera permission denial. They use production Host/platform adapters and require
  explicit confirmation before import. The final denial rerun covers persistent denial and
  is not counted as a fifth distinct path. No raw input or camera image was retained in
  device receipts.

The initial test compile failed while new implementation APIs were absent; it was not a
behavioral TDD pass. A later integration run exposed a real synchronous-session-revocation
regression, which was fixed while retaining the original assertion. Independent review also
found final expiry/status rechecks and prepared-scratch cleanup gaps; fixes and focused
tests are included. The first baseline seeder was not packaged by the extra AGP source
directory; the empty owned fixture was repaired without data clearing and all three actual
upgrade phases then passed. These failed attempts are separate from the passing receipts.
The initial UI batch passed 19/21; two fixture assumptions about merged semantics and a
shared button tag were corrected without removing the authority assertions. An initial
camera run received a nonmatching payload, and two denial attempts entered the granted
camera path; all three remained failures. Controlled subsequent runs passed. The denial
automation initially missed Android's alternate denial-button ID; the actual dialog was
then denied, its child test passed, and a clean follow-up invocation also passed.

## Artifact and evidence boundary

Local receipts and APKs are under `.build-output/manual-import-oct4/`; the final account-switch
follow-up uses `.build-output/manual-import-closeout-oct5/`. Sanitized initial-stage receipts
are committed under [evidence/manual-import/2026-10-04](evidence/manual-import/2026-10-04/).
Raw logs, QR payloads, configuration files and APKs are not committed. Baseline source is
`eed8ed295b9e4f878b01de12a6b0586a48a76051`; candidate receipts bind that HEAD plus working-tree
diff and complete source-input hashes, including new files. They are not described as clean
HEAD builds. Both artifacts use the same verified debug signer and versionCode 6.

Upgrade application ID: `com.gccwebos.android.candidate.acceptance.manual20261004`.
UI/platform application ID: `com.gccwebos.android.candidate.acceptance.manualui20261004`.
Both have hidden launchers and the label `导入验收 勿登录`. Both fixture groups were removed
after their initial runs. The two owned Download documents were removed only after verifying
their exact previously recorded hashes. The final account-switch follow-up uses a fresh
installation of the same isolated UI identity with a separate ownership receipt.
That follow-up fixture has also been removed after exact installed-APK and private-path
guards passed. Final inventory confirms all four owned package identities are absent,
including retained-data identities. The ordinary installed candidate still has SHA-256
`5ffa0ad6ab34fb126362355be31c5af4738647ec5824eba2f016831996255165`; its storage was not read.
Final follow-up receipts, including the source-input hash inventory, are under
[evidence/manual-import/2026-10-05](evidence/manual-import/2026-10-05/).

This work does not establish real subscription HTTP or tunnel forwarding acceptance.
Synthetic camera/file tests use fixed owned inputs and an injected downloader. No real
customer endpoint or subscription is borrowed. Existing B2 P7 timeout and unrun P8/P9 remain
separate, unresolved gates. Normal-app installation/migration is outside this approval scope.

The hosted full-suite timeout evidence, corrected recreation/resume fixture contracts and
new suite budgets are recorded in [ci-ui-suite-budget-2026-10-04.md](ci-ui-suite-budget-2026-10-04.md).
The previous hosted runs remain failed; local validation is not a green exact-head CI result.
The first implementation push also exposed an outdated CAMERA permission allowlist in the
source checker before any hosted compilation. The linked CI note records that failure and
the strict allowlist/scanner-boundary correction. This checker-only follow-up does not change
the application or test source inputs used for the final APK validation.

The subsequent `fcddaa9` hosted run passed all 1001 JVM tests on Linux. Its device
failures and the test-only suite partition/no-engine fixture follow-up are recorded
in [ci-import-fixture-isolation-2026-10-05.md](ci-import-fixture-isolation-2026-10-05.md).
The prior OPPO receipts remain attached to their tested artifacts; they are not
presented as reruns of the amended test fixture.
