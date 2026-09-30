# Fresh connection preparation (offline integration boundary)

Every **admitted** Connect, Reconnect and Switch runs authoritative current-resource overview,
checks validity/known quota, acquires permitted delivery material, downloads current YAML,
validates every node with the strict schema projector, obtains an explicitly resolved line,
and replaces the prepared file atomically before engine acceptance. An identical active Connect
is still a coalesced command; reconnect/switch/disconnect-then-connect are new attempts.

`FreshConnectionConfigGateway` plugs into `ConnectionCoordinator`'s existing ConfigGateway.
It never calls `copyImportedConfig`, which is a cached offline preview only. A receipt URL can
be retained without retaining configuration freshness: each attempt downloads new bytes.
The current authenticated `/api/portal/commerce/model` discriminant chooses delivery semantics.
Legacy obtains current setup and a new session-bound handoff each attempt, then non-consuming
ACK and download. It never adopts an old handoff and leaves any older durable receipt untouched.
Wallet-first retains durable receipt/URL, same-session retry keys and pending-ACK rules. An
unknown model or model read failure stops the attempt. There is no historical-request guess,
material rotation, or newly invented backend recovery endpoint.

Neither an existing file nor an old preview is a fallback when resource refresh, download,
validation, permission, or write fails. Existing file contents may remain on disk, **inactive**.
There is no file-load/start API and a process restart cannot resurrect admission.

## Activation contract and races

Fresh `AuthorizedConfig` carries a one-use admission guard. `ConnectionCoordinator` requires
an `AtomicActivationTunnel`; the legacy suspendable tunnel path cannot consume fresh material.
At handoff, the coordinator serializes latest-command identity, then the repository checks
account generation, valid session, resource/configuration revision and resource expiration.
The private file replacement and synchronous adapter acceptance occur under those fences.
Caller cancellation is rechecked after the write and before acceptance. A replaced/cancelled
attempt waiting on network or permission cannot write or start. New login, logout, account
cancellation, resource refresh, and observed protected 401 invalidate earlier preparation.

The adapter's synchronous method must **copy/accept before returning**, must not defer resource
acquisition to an unguarded background job, and must be bounded. Partial/failed acceptance is
still cleaned up through the coordinator's existing stop/cleanup latch. This testable contract
is not a production native adapter. File sync/rename also runs under the fences and must not
execute on the main thread; cancellation/timeouts cannot interrupt an arbitrary stalled filesystem.
Production storage and acceptance require an independently bounded handoff/watchdog. In particular, real native blocking, TUN ownership, watchdog, revocation
and asynchronous startup need separately verified integration. A server revocation not yet
observed by the client cannot be inferred from local generation checks.

Atomic storage stages a permission-0600 file in a supplied app-private no-backup directory,
flushes it, then uses atomic replacement with no non-atomic fallback. The old file is preserved
until replacement. Failed staging removes its temporary file. Projected/raw owned byte arrays
are wiped; parser, JVM and filesystem internal copies cannot promise physical erasure. The
file is never an authorization ticket and no active state is persisted across process death.

## Explicit remaining activation boundary

- No authoritative stable line identity/selection contract has been wired. `ConnectionLineResolver`
  has **no production implementation**; fixture mappings are synthetic, never index-derived authority
- No production `AtomicActivationTunnel`, VpnService, permission flow or UI connection button is enabled
- Debug loopback VPN lab does not consume subscription material
- No live account, download, purchase, backend mutation, OS VPN or external-network test was performed
- Client/device counts are not limited by this slice. Login does not kick other clients, and
  ordinary reconnect never invokes reset/refreshSubscription/credential rotation
- This slice demonstrates preparation ordering only, not functional VPN or protocol interoperability

## Verification

Synthetic integration tests exercise coordinator Connect/Reconnect/Switch, coalescing, downloaded
byte changes, download failure with an existing file, superseded permission, logout/new session/
resource invalidation before activation, restart with an old file, unresolved identity, atomic
write failure and cleanup. Existing retry-key/pending-ACK/session tests remain applicable.
Pinned Android Gradle/Lint/instrumentation CI is still required on the exact published commit;
offline JVM checks do not replace those lanes.
