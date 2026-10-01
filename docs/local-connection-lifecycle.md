# Local connection lifecycle contract (offline slice)

This slice is pure Kotlin and uses fake engine adapters only. Ordinary APK production VPN
remains disabled. UI, brand, manifests, Android permission handling, native packaging,
backend contracts, wallet/payment gates, and the accepted SS/TUN lab are unchanged.

## Local admission, separate from account actions

Construct `ConnectionCoordinator(LocalSubscriptionConfigGateway(...), permission, engine,
probe, serviceScope)` for the local capability path. It has no AccountGateway or
EntitlementGateway and makes no Portal requests. Account-only commerce still uses its
existing authenticated repository. The legacy constructor/diagnostic APIs remain intact.

`start(SelectionIntent.Choose)` downloads and strictly validates all nodes, then returns
`Failed(SELECTION_REQUIRED, choices=...)`. It does not silently select entry zero. The UI
can submit one returned opaque `ConnectionSelection` using `start(Selected(...))` or
`switchSelection(...)`. These commands, including `restart()`, each download and strictly
validate again; cached bytes never authorize activation.

A bounded in-memory registry binds selections to capability binding, raw artifact SHA-256,
current projection code/schema, and current entry. Only registry-issued object identities
are accepted; UI callers cannot supply a digest/binding/index tuple. Identical fresh bytes
preserve selection. Every content change requires explicit reselection, including reordering,
deletion, duplicate-name changes, password/endpoint/TLS edits and harmless comments.
Every validated remember/refresh updates the shared revision registry, including reads by
another manager; A → B → A cannot revive an old handle. Known expiry is recovered from
durable metadata bound to that capability even when prepare runs before restore.
Raw hashing is intentionally conservative; it is not a backend node ID. Handles are neither
persisted nor logged; cross-capability, cleared and reconstructed-process handles fail closed.

Failures preserve MISSING, EXPIRED, INVALID_LINK, NETWORK, TIMEOUT, UNSUPPORTED, STORAGE
and STALE categories. TIMEOUT identifies the configured preparation deadline; the existing
HTTP transport still normalizes socket IO failures to NETWORK. Unsupported mixed artifacts fail before any selection. Cancellation
remains cancellation, never a network failure. Known expiry at activation is checked again.

## One service owner and command lifetime

One coordinator owns one engine and serializes connect/reconnect/switch/stop. The local
subscription authority is process-wide by default, sharing an operation epoch, write lock,
refresh mutex and exclusive connection-owner lease across repository/manager recreation.
Tests can inject an isolated authority. A future multi-profile application must deliberately
provide one shared authority per storage domain; independently constructed authorities for
the same live stores violate this contract. This is not an Android multiprocess lock.

Content revision is separate from the monotonically advancing operation epoch. A new
command or stop invalidates pending preparation before cancelling the old worker. Clear
also revokes selections and notifies the owner outside the capability lock. New starts wait
for prior cleanup. Uncertain cleanup latches admission and retains the owner lease, including
after close; explicit successful cleanup is needed before another owner can exist.

The non-suspending `start`/`restart`/`switchSelection`/`stop` APIs return a Command.
Cancelling an observer's `Command.await()` merely detaches that observer; `Command.cancel()`,
`stop()` or `close()` explicitly cancels work. The legacy suspend APIs retain their documented
caller-cancellation behavior. UI collects StateFlow and must not replay an old command result
as current state. Construction always starts Disconnected, without reading/starting a cached
file, replaying a command, or requesting permission automatically.

Every engine acceptance receives a generation-bound terminal callback. Native death or
permission revocation cancels/cleans the current generation; callbacks from superseded or
stopped generations cannot alter a newer connection. Synchronous death during acceptance
cannot publish Connected. Owner scope cancellation also cleans up.

## Acceptance and storage boundary

`LifecycleTunnel.accept` must synchronously accept/copy the prepared bytes before returning.
It runs inside the command and capability fences. Posting an Intent, starting a service, or
queueing work is not accepted engine activation. No production adapter implements this API.
The config is one-use, rechecks capability/epoch/expiry, atomically replaces the app-private
no-backup prepared file, then calls the bounded engine acceptance function. The file has no
load/start API. Stop does not treat any leftover file as authority. Secret temporary copies
are wiped; failed/stale preparation cannot write or activate after the stop/clear fence.

An Android adapter still needs its own bounded cancellation/watchdog and fd ownership:
coroutine cancellation cannot forcibly interrupt arbitrary blocking native work. Platform
permission/foreground-service lifecycle, native death delivery, revocation, service/process
restoration, protected sockets, tunnel-path probing, real device and release packaging checks
remain production blockers. There is no new backend identity/limit/reset/rotation dependency.

## Verification

Run the repository's offline diagnostic JVM suite using an existing verified tool directory:
`KOTLIN_TOOLS_DIR=/path/to/verified-kotlin-tools bash scripts/test-domain-offline.sh`.
The directory supplies Kotlin 2.3.10 compiler/stdlib, JUnit, coroutines, JSON, OkHttp/Okio and
SnakeYAML jars; this command neither downloads dependencies nor replaces missing libraries
with stubs. Bash and Python 3 are required; set `PYTHON=/path/to/python3` when it is not on PATH.

The script declares 26 pure-JVM main sources, 19 JVM test sources and one synthetic acceptance
fixture in its arrays, writing the exact lists to `.local-tests/domain-sources.txt` and
`.local-tests/domain-test-sources.txt`. It deliberately excludes Android/Compose UI/controllers
and their 14 controller/integration test files at this slice's baseline; those remain in the
pinned Gradle suite. New Compose lifecycle tests, such as WalletForegroundResumeTest, also
belong to Gradle and do not enter the fixed diagnostic arrays automatically.
The local SS fixture uses localhost TCP/UDP sockets and never forwards to external destinations.
Production atomic-file tests require a filesystem supporting POSIX initial permissions and
atomic replacement; NTFS failures are platform evidence, not skipped or replaced by fake passes.

For only the standalone core cases, use the same verified tool directory with
`KOTLIN_TOOLS_DIR=/path/to/verified-kotlin-tools bash scripts/test-core-offline.sh`.
Alternatively that script accepts an existing `kotlinc` and optional `COROUTINES_JAR`.
On Windows use Git Bash with its `/usr/bin` tools on the process PATH and Bash-style tool paths;
an existing Windows Python executable may be supplied through `PYTHON`. Keep the Bash scripts
and protocol fixtures in an LF checkout: some negative protocol tests replace LF-specific
fragments, so CRLF fixtures make those mutations no-ops. The scripts do not rewrite fixtures.
Neither script
validates Android secure storage, Compose runtime, permissions, native TUN or production traffic.

The pure-JVM domain tests cover snapshot selection, typed failures, repeated fresh reads,
UI detachment, stop/clear races, stale callbacks, timeout/cancellation, cleanup latching and
new-owner Disconnected restoration. This does not substitute for Gradle/Lint/instrumentation
or production VPN verification. No CI job is required or triggered by the local suite.
