# Manual configuration import: approved scope and original inventory

Date: 2026-10-04. Worktree: `C:\Users\g\.codex\worktrees\pr-acceptance-oct3\gccwebos-android`.

This records the **A-class preparation and approved implementation scope**, not release acceptance.
The menu, document picker and camera presentation are B-class work; explicit adoption of a
new capability, local-source authority and persistent source selection affect admission
semantics and use the A-class gate. The operator approved continuing this scope with
“同意批准继续” on 2026-10-04. Implementation and isolated validation may proceed under
the boundaries below. This approval is not a production/backend change or normal-app migration approval.
The following inventory describes its original preparation snapshot; its line references are historical.

## Verified inventory

The reviewed Git HEAD is `ab41da21ef81111848b0ffd5db4c8605a9f6c1af`.
The source dry-run reads current worktree bytes, not an assumed clean checkout. Its receipt
records SHA-256 for each of 11 inputs and comparison against HEAD after newline normalization.
`AccountPanel.kt` differed from HEAD during this inventory; the other 10 inputs matched.
These facts do not establish current-head CI or device acceptance.

| Existing code | Verified boundary |
| --- | --- |
| `portal/PortalModels.kt:78`, `portal/AccountPanel.kt:73` | Official subscription origin is `https://sub.gccwebos.com`; HTTPS and `/sub/a/<capability>[.yaml]` only. Query, fragment, userinfo, foreign origin and alternate ports are rejected. |
| `portal/PortalTransport.kt:57` and `:112` | Subscription HTTP uses an isolated client, no Portal session/cookies/cache, no redirects. Response bodies are bounded; expiry is read from `X-Subscription-Valid-Until` at `:142`. |
| `portal/SubscriptionImport.kt:12` | `SubscriptionRead` owns raw bytes and optional explicit expiry. `SubscriptionSource` is a URL-reading boundary, not a local-file reader. |
| `portal/LocalSubscription.kt:252`, `:312` | Capability selection currently uses the encrypted profile URL, then acknowledged delivery. `remember` requires the existing capability binding; it is not a new-capability import API. Unfinished legacy-recovery journal blocks capability use. |
| `portal/LocalSubscription.kt:494`, `:570` | Each prepare refreshes through `source.read`, validates all nodes, and rechecks epoch, capability, selected opaque identity and expiry before one-shot activation. |
| `portal/OneClickConfigGateway.kt:18` | First discovery and subsequent selected preparation retain epoch fences. Changed registries, cancellation or manual choices cannot acquire a replacement connection intent. |
| `portal/LocalProfileStore.kt:16`, `:51` | `portal-profile.bin` is AES-GCM plus `AtomicFile`; v1 has a strict field set and separate AAD. No source discriminator or local-file activation source exists. |
| `vpn/SafeProxyConfig.kt:33`, `:125`, `:159` | Strict UTF-8, at most 64 KiB and 128 entries, complete existing schema/protocol validation. Raw YAML is not passed directly to the native engine. |
| `ConnectionOverview.kt:74` | Remote readiness requires a real future expiry. Missing expiry cannot be replaced with a file timestamp or a synthetic future date. |
| `portal/AccountPanel.kt:459` | Account refresh preserves an already saved local VPN profile; it does not silently replace it with wallet delivery. |
| `app/build.gradle.kts:113`, main manifest | No barcode/camera library or CAMERA permission currently exists. minSdk 26, target/compileSdk 36. |

Paths in the table are under `app/src/main/java/com/gccwebos/android/` unless otherwise stated.
Only source, public documentation and Git metadata were read. No customer file, URL, token,
account, environment secret, private app store or device material was read.

## Complete proposed behavior

The node-page file icon opens exactly three working entries. Each result goes through one
bounded import coordinator and requires an explicit import action. Import never starts a VPN.

1. **Scan:** an in-app camera scanner decodes QR text locally. A decoded official subscription
   URL enters the same URL path as manual input. A decoded strict YAML payload can enter the
   local-file validation path if it fits the same limit. Unsupported payloads fail with a fixed
   message; no arbitrary URL is opened, uploaded or executed.
2. **Import configuration file:** Android SAF selects one user-chosen `content://` document.
   Bounded UTF-8 contents are classified as one official subscription URL or raw supported YAML.
   YAML is copied into the independent encrypted store after full validation. The original
   document is not edited and is not a live or persistently granted connection source.
3. **Import subscription link:** explicit text entry/paste accepts only the existing official
   origin/path contract. It downloads and validates before commit. It does not broaden
   `OriginPolicy`, manufacture a delivery receipt/ACK, sign in, or send Portal credentials.

Supporting a URL text file alone would be a smaller feature, but **would not complete the
requested raw configuration-file import**. The approved full scope below includes raw YAML.

For a new URL, use expiry returned by the subscription response. Prior expiry can only be
retained for the exact same capability under the existing rules. A URL lacking authoritative
future expiry must not become remotely connectable by borrowing account or other-profile
metadata. Preserve the prior active source on rejection and give a fixed, non-secret reason.

## Separate encrypted source store and atomic selection

Proposed names below are design names, not files or schemas already implemented.

Add `ManualSourceStore` / `EncryptedManualSourceStore` using one new app-private no-backup
`manual-source.bin`, a separate Keystore alias and separate AAD/version. **Do not modify the
format, key, AAD or import-time contents of `portal-profile.bin` v1.** Manual official URLs,
their preview bytes and local YAML all live in the new sidecar. Do not split the active selector
from its selected material into independently committed files.

One authenticated encrypted envelope atomically contains:

- schema version and fresh opaque source revision;
- active kind: `LegacyRemote`, `ImportedRemote`, `ImportedLocal`, or `None`;
- for `ImportedRemote`: exact validated capability in a redacted secret holder, raw preview
  bytes, explicit expiry and observed status;
- for `ImportedLocal`: the owned raw YAML bytes and their content identity; no account
  binding, subscription URL, server-validity claim or fabricated expiry;
- for `LegacyRemote` / `None`: no imported credentials or YAML payload.

`LegacyRemote` delegates to the unchanged existing profile/delivery precedence and recovery
checks. `ImportedRemote` exclusively selects its own capability; `ImportedLocal` exclusively
selects its own content. `None` is a durable tombstone and authorizes nothing. There is no
fallback from an invalid/expired/unreadable selected source to another source.

First successful initialization of the new store writes `LegacyRemote` atomically while
leaving the old v1 file untouched. Existing old data need not be decrypted/copied for that
initial selector write. An existing sidecar that is corrupt, undecryptable, unknown-version
or unexpectedly missing during a live process is a storage error, not permission to fall
back. Ordinary clear/import code must never delete the selector to mean "no source".
This is not a defense against an attacker able to delete all private app state; do not claim
cross-process evidence that can distinguish a never-created file from such external deletion.

Before a source commit, validate and stage without mutating current authority. At commit,
recheck the coordinator generation, operation epoch, clear version and current active-source
revision under the shared authority lock/mutex. Atomically write and sync the entire envelope,
read back its identity, then publish the new registry/state. Fence pending admissions even
if final persistence/readback fails. A failed commit leaves a storage block until verified
recovery; it must not silently authorize either the attempted source or a guessed fallback.

Initialization/write/readback interruption tests are required. They must prove that old or
new whole envelopes are observed, never a selector for one source with another source's
payload. Source replacement is allowed only after the existing connection owner reports
disconnected with cleanup complete; the import must not rely only on a UI phase label.
If a stop is needed, use the existing stop/cleanup contract and await it before commit.

## Migration, clear and rollback semantics

This is an additive sidecar initialization, not a rewrite of v1. Old APKs ignore the new
sidecar. Manual import/refresh never overwrites the old v1 profile or delivery receipt.
Existing account delivery can keep its existing writes only when `LegacyRemote` is selected;
it must not replace an active imported source during login or resource refresh.

Separate explicit actions are necessary:

- **Remove imported source:** commit `None`, invalidate epoch/opaque selections, and stop the
  matching owner. Retain no imported payload in that tombstone. Do not expose the old account
  source implicitly after removal.
- **Use account subscription:** explicitly choose `LegacyRemote` under the same source-change
  fences. The unchanged profile/delivery checks still decide whether it can be used.
- **Clear all local connection data:** advance epoch/clear and notify the connection owner;
  first commit/verify `None`, then run the existing profile/delivery clear. Retain the sidecar
  tombstone. Failure is an explicit storage/cleanup block. An old APK rollback is forbidden
  while clear is incomplete, since an old APK cannot understand the tombstone and could expose
  an uncleared old v1 profile. Do not report clear complete until all required stores and
  owned prepared material are verified absent and cleanup is complete.
- Account sign-out remains distinct from explicit local clear and must not erase imported
  local material or invent a new source selection.

For rollback, prepare a baseline APK from reviewed source with the **same isolated fixture
applicationId and signing certificate** before installing the candidate. Record both APK
hashes, certificate digests, versionCodes and source commits. If a versionCode downgrade
would be rejected, rebuild the old source with a compatible non-decreasing versionCode;
do not uninstall or clear data to force installation. Never claim a baseline APK exists or
matches until those facts are recorded.

After candidate cleanup and successful full-clear checks where applicable, stop the owned
fixture, install the same-signature rollback APK, verify package/certificate/APK identity,
launch and verify the original synthetic v1 fixture is unchanged and readable when it was
intentionally retained. That rollback intentionally returns to the old account source;
it does not preserve imported-source functionality. The old APK must never activate sidecar
bytes. If the sidecar remains for a later upgrade, record that fact; use explicit new-version
clear or final owned-fixture removal to dispose of it. Do not copy or decrypt customer data
to perform rollback verification.

## Connection authority that must remain intact

Remote source: every admitted Connect/Reconnect/Switch still obtains current remote bytes,
validates every node and produces fresh one-shot `AuthorizedConfig`. No offline fallback is
added. `ImportedRemote` changes only how the explicitly adopted capability is stored/selected.

Local source: the user's explicit import authorizes only that local source. Every admitted
Connect/Reconnect/Switch re-reads/decrypts the selected envelope, fully validates it and binds
the new one-shot material to source revision, raw digest, projection schema, operation epoch,
clear version and exact opaque selection. A changed source/content invalidates old selections,
including A-to-B-to-A replacements. Local validation must not call the remote downloader or
present cached remote material as a newly read artifact.

Local validity is a separate typed state, such as `LocalNoServerExpiry`; it is not remote
`READY` plus a made-up date. File mtime, import time, current account expiry and `Instant.MAX`
must never grant local authority. Display "本地配置" and unknown server expiry/rights honestly.
The existing core/service representation of an absent resource expiry must be explicitly
verified before using it for this new type. Keep the finite elapsed admission deadline,
one-shot consumption, permission, foreground-service and engine acceptance fences even when
the source has no server expiry. Do not change native protocol projection or JNI to implement
source selection. If the existing service cannot safely represent this distinction, stop at
the contract test and revise the proposal rather than silently relaxing it.

Both paths retain strict YAML/protocol limits, complete validation before selection,
physical-network hostname preparation, no implicit VPN start, cancellation propagation,
single coordinator owner, failed-cleanup latch, private atomic prepared-file replacement,
prepared-byte identity checks and socket/TUN lifetime rules. Node latency must also follow
the selected source identity and invalidate old readings; it cannot grant connection authority.
Camera/file results arriving after navigation cancellation, clear or a superseding import
must be discarded. Raw input is not stored in SavedState, logs, analytics, exception messages,
clipboard history by this app, screenshots or test receipts. Owned byte buffers are wiped;
do not promise erasure of immutable strings or platform/parser internal copies.

## Planned implementation footprint

| Area | Proposed files / changes |
| --- | --- |
| Model and storage | New source envelope/types and encrypted sidecar store; new store tests. Existing `LocalProfileStore.kt` v1 format and alias stay unchanged. |
| Import parsing and transaction | New bounded URL/YAML parser and import coordinator; shared full validation, atomic adoption and cancellation tests. |
| Authority and connection | `LocalSubscription.kt`, `OneClickConfigGateway.kt`, account/controller wiring and source-aware gateway tests; preserve current epoch and one-shot paths. |
| Presentation | `NodeSelectionPanel.kt`, new import menu/dialog, `MainActivity.kt`, local-source presentation in `ConnectionOverview.kt`; explicit source/remove actions with accurate labels. |
| Platform | SAF adapter, in-app QR activity/adapter, manifest CAMERA/activity entries, pinned Gradle dependency and license notice. |
| Compatibility | `AccountPanel.kt` / repository wiring as needed to preserve selected source during account restore, plus `NodeProbeTargets` / latency source identity and related tests. |
| Documentation | This plan, source/clear contract, runtime evidence and rollback record. |

Do not modify backend endpoints, origin allowlists, Portal authentication, delivery ACK
semantics, purchases, native schema/JNI, production infrastructure, HP server or other apps.
Avoid adding generic remote URL support or an unbounded general-purpose configuration engine.

## Scanner and document implementation choice

Package QR decoding and camera UI **inside this APK**. A pinned ZXing Android Embedded
dependency is the minimal candidate: the project's documentation provides an in-app
`ScanContract` and currently documents `4.3.0` with default SDK 24+. Pin/review its resolved
dependency graph and notices before use; current app minSdk 26 meets that stated floor.
It still requires real API 33/36 compatibility and camera lifecycle validation. Do not call
an optional separately installed scanner or consider its absence a completed feature.
[ZXing Android Embedded documentation](https://github.com/journeyapps/zxing-android-embedded).

Ask for CAMERA only when the user selects Scan. Denial, permanent denial, no camera,
navigation/back, activity recreation and late scan results need defined error/cancel paths.
Do not persist barcode images or display decoded secret text in scanner results. Bundled
decoding must work without GMS, login, a model download or a third-party network request.
Google Code Scanner delegates to Play services and requires a downloaded module, so it does
not satisfy that runtime-dependency boundary.
[Google Code Scanner documentation](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner).

System camera delegation only captures an image and still needs a local decoder; it is not
itself a QR result API. The proposed default is the packaged scanner to avoid relying on an
optional external camera/scanner activity.
[Android camera intents](https://developer.android.com/media/camera/camera-intents).
SAF uses `ACTION_OPEN_DOCUMENT` for one selected document; no storage-tree or broad storage
permission is needed. Bounded reads must not trust extension, MIME or declared file size.
[Android document access](https://developer.android.com/training/data-storage/shared/documents-files).

## Completed source-only dry-run

Result: **19/19 PASS**, source inspection only. Script:
`.build-output/quiet-import-oct4/import-boundary-dry-run.py`.
Receipt: `.build-output/quiet-import-oct4/import-boundary-dry-run.json`.
Receipt SHA-256: `003d0d67cf12bc6090fdb2384eccb001e152876d165d4371eb3be2be064b900b`.

The self-contained Python script reads only the named source files and Git HEAD, records
input hashes and fixed PASS/FAIL identifiers, and writes that JSON receipt. It checks existing
origin/client boundaries, explicit expiry, v1 encryption/shape, capability matching, fresh
reads, epoch/clear/schema binding, one-shot activation, strict parser limits, remote UI expiry,
account-source preservation and absence of the not-yet-added scanner feature. Re-run using:

```powershell
python .build-output/quiet-import-oct4/import-boundary-dry-run.py
```

Text assertions prove the checked source constructs were present, **not** execution correctness.
No Gradle/JVM tests, Android tests, APK build/install, ADB, camera, device network request,
private storage read, migration or source import ran in this dry-run. Public documentation
was separately browsed for the platform/dependency inventory. The dry-run script itself
makes no network requests. PASS is neither operator approval nor implementation acceptance.

## Post-approval offline validation checklist

This original checklist is retained for scope comparison. Executed coverage, failed
attempts and remaining gaps are recorded in [manual-import-validation.md](manual-import-validation.md);
in particular, a behavioral RED was not established for every original input path.

- [ ] TDD RED for all three real input paths, then GREEN with the same assertions.
- [ ] Synthetic QR encode/decode round trip plus invalid/multiple payload handling; no GMS or
  external scanner fallback. File URL and genuine raw-YAML fixtures both reach real validation.
- [ ] Strict UTF-8, 64 KiB/128-node boundaries, unsupported mixed nodes, malformed metadata,
  forbidden origin/query/redirect and zero credential forwarding to subscription HTTP.
- [ ] Remote re-download on every connection; failure never starts cached/local fallback;
  absent/expired expiry cannot be replaced with import time, mtime or account expiry.
- [ ] Local source parsed anew per attempt; digest/schema/revision/clear/opaque mismatch fails;
  no remote call, no fake server expiry, no native projection changes.
- [ ] First initialization preserves v1 bytes; save/readback failure, interrupted atomic write,
  corrupt/unknown envelope, tombstone, source switch and interrupted full clear fail closed.
- [ ] Cancel/clear/logout/import-A/import-B/late camera-file result races; no duplicate import
  or start; in-flight connection/cleanup prevents source adoption until safely resolved.
- [ ] Restart restores only selected source evidence, never starts a VPN; account refresh
  cannot silently replace it; latency readings and opaque choices invalidate on source change.
- [ ] Existing suites remain: `SubscriptionOriginPolicyTest`, `LocalSubscriptionTest`,
  `OneClickConfigGatewayTest`, `NodeSelectionControllerTest`, `ConnectionCoordinatorTest`,
  `SafeProxyPreparationTest`, `SafeProxyRejectionReasonTest`, `ProtocolContractTest`,
  `NodeProbeTargetsTest`, and affected account/latency tests. Add focused new source/store/import
  tests instead of substituting these. Run `EncryptedLocalProfileStoreTest` on Android for v1.
- [ ] Preserve the known Windows POSIX-file-permission limitation as an environment failure;
  do not relax 0600 production behavior or claim unrun tests passed. Use existing memory-store
  fixtures for pure admission tests and Android/Linux for the actual file-permission boundary.

## Isolated OPPO validation and rollback gate

The steps below define the approved validation gate; completed execution and remaining gaps
are recorded in [the implementation receipt](manual-import-validation.md). Use a newly owned, distinguishable fixture
applicationId and signing key on the authorized OPPO; do not touch the ordinary installed app.

1. Inventory device transport/API/unlocked state and exclusive test ownership. Before creating
   the fixture, check exact `pm -u` identity and expected private file/directory metadata; if
   pre-existing unowned data is found, stop without clearing it. Do not read customer content.
2. Prepare verified candidate and same-signature rollback APKs for this fixture, record source,
   SHA-256, signer, package, version, native library identities and build flags. No installation
   is acceptance by itself. Native changes are out of scope.
3. Install the old-source fixture first and seed only owned synthetic v1 material. Record
   synthetic fixture hashes. Upgrade without clearing; verify sidecar initialization and
   manual imports do not change that v1 hash or permit automatic VPN start.
4. Exercise link input, SAF file URL, SAF genuine YAML and **camera scanning a synthetic QR**.
   Image-decoder tests or tapping the menu do not replace the actual camera test. Cover cancel,
   denied permission, back/recreation, bad/oversized input and concurrent clear/source change.
5. Run synthetic authority/store/UI tests and finite permission/admission/cleanup tests. An
   approved controlled test subscription or owned local test endpoint is required to claim
   actual tunnel traffic success; none is assumed or borrowed from a real user's profile.
   If unavailable, record that end-to-end tunnel acceptance remains unverified.
6. Perform the same-signature rollback procedure above. Verify original retained synthetic v1
   readability and no sidecar activation. Test clear/tombstone interruption separately and
   never use rollback to bypass incomplete clear or cleanup.
7. For final fixture cleanup, verify exact owned package/APK hashes, stop all owned work and
   inspect only expected private path existence/types, extended to include sidecar files.
   Any unexpected content or identity stops cleanup. Remove only the owned fixture/test APKs
   after the established private-data guards pass; leave the normal app and device available.

Record fixed test names/counts, failure reasons, artifact hashes and metadata-only before/after
checks. Do not capture real accounts, subscription URLs, raw configuration, camera frames or
unfiltered instrumentation/logcat output. A failure is recorded, not converted to a skip.

## Exact operator approval scope

The received approval authorizes **local Android code, tests, bundled scanner dependency/license and
documentation changes**, implementing all three inputs and the additive encrypted sidecar
source model described here, plus builds and **isolated OPPO fixture** installation/testing
and same-signature rollback. It includes only synthetic or explicitly owned test materials.
It does not authorize production/backend changes, deployments, new origin support, purchases,
credential extraction, ordinary-app data migration/replacement or normal-app installation.

The implementation approval was received on 2026-10-04. Runtime evidence and independent review
must still be assessed before claiming acceptance; approval alone does not satisfy those checks.
Any need to modify native admission encoding, original v1 schema, backend contracts, or the
normal installed app is outside this scope and must be brought back as a concrete change.
