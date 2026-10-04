# Endpoint address preparation

The operator approved the Android address preparation and physical-network binding scope on 2026-10-04, after the inventory and design dry-run. Server changes, formal release signing and production deployment are outside this scope.

The candidate validates every node before accepting an artifact. The preparation validator additionally accepts bounded ASCII hostnames; all protocol, TLS, option, parsing and size restrictions remain enforced. The existing literal-only `project` and native schema v1/JNI contract remain unchanged. `projectResolved` replaces only the selected server with a validated canonical IPv4 and preserves SNI/servername and other protocol fields.

Encrypted profiles contain the original subscription bytes. Their raw SHA-256 continues to identify the selection registry. Prepared engine bytes are a separate, attempt-owned object; a changed DNS answer does not change the original registry or become a reusable subscription authorization. Hostname import creates no cached engine preview.

The connection owner reserves a finite physical `INTERNET + NOT_VPN` network lease for both literal and hostname preparation. A hostname receives an asynchronous A-record lookup on that network. The preparation deadline is an operation budget, not a DNS TTL or resource expiry. DNS waits hold neither the subscription monitor nor the operation mutex. Cancellation, changed capability/selection/revision, expiry or a revoked lease prevents activation.

The same network handle and original preparation deadline cross the private binding and COPY_START boundaries. A leased binding rejects legacy or mismatched COPY_START fields. The service independently checks and observes the pinned physical network, protects and binds outbound sockets to it, and owns its runtime monitor after admission. Closing prepared bytes releases the owner's monitor without closing an accepted service session. The preparation deadline does not become a running session deadline; resource expiry remains independently enforced.

## Candidate switch and rollback

`CONNECTION_ENDPOINT_HOSTNAME` is enabled only for a debug connection candidate unless `-PconnectionEndpointHostname=false` is supplied. Ordinary debug and release builds keep the literal-only path. No storage schema or native library identity changes are needed.

Rollback uses a previously verified, same-signer candidate APK with `adb install -r`, preserving application data. Alternatively, rebuild the candidate with the switch disabled. Do not clear data or discard recovery journals to hide a failure. A disabled candidate rejects hostname configurations and retains the existing literal-only admission behavior.

## Validation boundary

Parser fixtures cover hostname syntax, rejected address aliases, all-node validation, selected-only projection, unchanged literal goldens and preserved TLS names. Manager fixtures cover cancelled/late resolution, changed selection/capability/revision, resource expiry, revoked leases, write-boundary rechecks, raw identity stability and secret-free diagnostics. Transport tests cover exact binding identity and finite admission deadlines.

The focused JVM suite passed all 259 tests. Normal connection-candidate App and Test APK builds and lint passed. On an actual OPPO device, `AndroidAddressLeaseInstrumentedTest` passed all 7 tests in the separately owned acceptance package. Public DNS and physical-network checks are distinct from synthetic proxy TCP/UDP/DNS traffic checks; these results do not establish full VPN connectivity.

Initial OneClick attempts timed out before subscription download or traffic probing (`downloads=0`, `probes=0`). An OPPO startup prompt was observed, and review also found obsolete button expectations: the V2 UI exposes `一键连接` / `断开`, while the test expected the old model labels. The test now handles the allowlisted system prompt before querying Compose and asserts the actual rendered labels. Fresh-data guards, enabled-state checks and traffic assertions remain intact. Separate retries rejected non-fresh staging data before connection; their data-lifetime cause was not established.

The corrected `OneClickVpnInstrumentedTest` passed on OPPO in a fresh owned package, using the production owner/service/JNI path and a loopback synthetic SS peer. It proves proxied TCP, UDP and DNS, navigation, Activity recreation, exact remote death, file cleanup and a fresh reconnect. The 7 address/lease tests passed again on that package. This combines real public DNS/lease checks with literal-endpoint native traffic acceptance; it does not establish real-account hostname interoperability. All four temporary App/Test packages were uninstalled and absence was checked including uninstalled package records. The original stay-awake setting was restored.

The prior B2 P7 result remains failed, and B2 P8/P9 have not run. Those results are separate from this address-preparation validation and are not reclassified by the passing tests above.

This validation covers the Android client scope approved on 2026-10-04. Formal release and server changes remain outside that approval.

The normal candidate APK was installed with the existing candidate signer and its installed artifact hash matched the local APK. After the operator logged in on the device, the first public diagnostic reported `NodeOptions` (unsupported node options). A later same-signer diagnostic update identified the fixed public category `NodeOptionBbrProfile`. No customer value, protocol, controller, endpoint or artifact was read or recorded. The actual artifact was still rejected before VPN admission; real-account hostname/connection interoperability remains unproven. PR #21 remains Draft for that verification, fresh combined-head CI and the remaining UI/recovery gates.

The operator subsequently authorized the BBR-only client/native adaptation with “开始修复” on 2026-10-04. That additional scope accepts only the three exact BBR-profile strings on HY2/TUIC, preserves missing values and the existing congestion controller, and leaves the other option/TLS/auth restrictions in place. Changed bridge inputs require freshly built and verified native libraries; the earlier address-preparation library identities do not establish BBR compatibility.

Review also reproduced a separate cached-restore classification error: a readable but incompatible cached artifact was reported as `STORAGE_ERROR`. The fix maps only cached parser rejection to `UNSUPPORTED`, retaining the profile/capability and denying node selection. Genuine store failures remain storage blocks; expired/revoked durable observations and existing admission fences remain enforced. The six synthetic tests failed five cases before the fix and passed all six afterward. This explains a possible cause of the earlier UI symptom without establishing the exact cause of that customer's transient storage message. No customer store, recovery journal or KeyStore key was cleared.
