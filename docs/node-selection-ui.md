# Local configuration import and node selection

This slice inherits the accepted UI tree 5996b51dc6ac62b8d903ec817f943c05cbde0741
and reuses the verified five-file f0eb0657b7f3cc0de7b8ca608f6075fa4aacc81b lifecycle contract.
The production engine, Connect button, VPN lab flags, manifests and signing are unchanged.
No permission, service, tunnel or traffic-probe adapter is instantiated by the selection UI.

## Input and output

Input is an existing acknowledged local subscription capability. An explicit Refresh uses
the existing isolated SubscriptionSource once, including when bytes are identical. It does
not use PortalRepository.acquire, setup/ack, server subscription rotation or reset. Visiting
the node page or selecting a row does not fetch configuration. Missing capability and known
Resource.None are distinct from expired, invalid-link, unsupported and storage states.

The complete YAML must pass SafeProxyConfig projection before it is persisted or selectable.
Mixed unsupported artifacts have no selectable rows. Raw configuration, endpoints, passwords,
subscription URL, capability binding and digests never enter UI state. ConnectionChoice exposes
only a name, protocol and opaque process-local object. Display ordinals disambiguate duplicate
names; row callbacks capture the opaque object and never resolve a name or ordinal back to it.

The shared registry binds objects to capability, raw-content revision, projection schema and
clear generation. Identical validated fresh content retains legal objects and selection.
Any content edit, including ordering and comments, destroys that selection and requires a new
explicit choice. Shared status and expiry prevent another manager's newer blocked observation
from appearing as an older READY snapshot. Choices publish after durable profile save succeeds.
Known blocked observations cannot be cleared by re-reading older cached bytes.

Cancel and account/session changes synchronously invalidate registry/write admission, then
cancel only the node controller's own job. They preserve the encrypted profile and acknowledged
delivery. Cached restoration may show metadata but cannot resurrect a cancelled selection.
The controller fences late success/error with its own request generation, authentication
generation and the manager's shared epoch. It never cancels the supplied parent scope, login
or MFA jobs. Leaving the node page cancels an in-flight read; idle navigation retains selection.

Selection is UI state, never connection admission. The inherited prepare contract still fetches
fresh content and revalidates the current opaque object before its one-use acceptance boundary.
That prepare path is not called by the new UI. Current connection status remains Disconnected
and the Connect control stays disabled.

## Offline verification boundary

Targeted JVM classes are LocalConnectionLifecycleTest, NodeSelectionControllerTest and
AccountChallengeLifecycleTest. New Compose fixtures cover duplicate-object selection,
busy/cancel reachability and unsupported/expired/missing-plan states. Local AndroidTest
compilation does not mean those UI methods ran on a device or emulator; ordinary APK CI is
coordinated separately and must use the final source and fresh app/test artifacts.

The transferred lifecycle evidence was produced on Linux and is historical evidence. On NTFS,
the production AtomicPreparedConfigStore rejects POSIX initial permissions as designed. Its
secure create, fsync and ATOMIC_MOVE rules remain unchanged. Pure lifecycle tests inject a
synthetic marker writer through PreparedConfigStore; an injected write failure must produce
typed STORAGE and zero engine acceptance. This tests lifecycle fencing, not Android secure
filesystem behavior. The initial Windows failure log and actual JDK filesystem probe are kept
separately in output/config-node-selection; no failed tests are skipped or reclassified as passes.
