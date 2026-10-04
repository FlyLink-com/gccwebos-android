# Subscription import diagnosis — 2026-10-04

Risk B: bounded client compatibility and tests in the existing PR. No account/auth model, server schema, backend deployment or node changes are required for this fix.

## Confirmed failure boundary

The normal QA2 candidate at `d051b95fe3b9ba74bb27f9ad2a8f0cc6128df446` was upgraded with data retained. The user logged in, and the public account/resource surface showed authenticated state. The subsequent fixed import result was `UNSUPPORTED_CONFIGURATION`, with `NodeOptionHeartbeatInterval`. Earlier software had reported `NodeOptionRequestTimeout`.

`PortalRepository.importFresh` downloads the subscription before calling `SafeProxyConfig.validateForPreparation`/`project`. The rejected configuration returns before the durable profile commit and `IMPORTED` result. Login, entitlement/resource display and subscription delivery therefore do not establish a usable VPN profile. The current configuration has not reached native admission or connection testing.

The parser checks option keys before values and validates every node. An unsupported option in one node can reject the entire import, including attempts to select another node. A changed first rejection category does not prove the preceding option value was valid or that all remaining options will pass. Customer configuration contents and values were not read or recorded.

The immediate compatibility gap is between the renderer's optional TUIC fields and the client's bounded projection. The UI's general “configuration incompatible with the kernel” text includes this earlier client rejection; it is not evidence that the Mihomo core received and failed to parse the actual configuration.

## Minimal fix and evidence

Request-timeout already has strict TUIC v5 format compatibility for integer 0..30000. The follow-up accepts six proven default-equivalent TUIC v5 options listed in [the protocol contract](offline-protocol-contract.md). Each value is validated before omission from the native DTO. Non-default behavior, TLS bypass, v4/token, unknown keys and invalid nodes remain rejected.

TDD: the new default-option class failed 10 of 19 cases before implementation, then passed 19/19. Request-timeout regression passed 10/10; shared Kotlin contracts failed 2 of 14 cases before implementation, then passed 14/14 and exported 56 synthetic DTOs. The original 48 golden bytes remain identical. Pinned Go passed all 28 top-level tests, including 112 committed/actual golden subcases and 36 direct-DTO rejection subcases. Android app/test compilation and lint also passed. These are local source/contract results, not an installed fix or actual account connection acceptance.

## Current source and Android 16 evidence

The compatibility change is committed as `98ffd6bae926e3d5f7c2a60b2b21af1278874436`. Local `5154405f50f06c4344e0cff7482a1aaa1bf89f24` adds only two automatic-import UI tests; all four native/projector source boundaries remain identical. Matching native artifact provenance is recorded in [real-TUN acceptance](real-tun-acceptance.md).

The local Android 16/API36 isolated UI package at `5154405` passed 10 automatic-subscription cases and 6 setup-flow cases with zero failures/skips. The new cases verify default-option import and rejection of non-default heartbeat while retaining the delivery receipt. The package used synthetic controllers and contained no VPN JNI. The owned app and instrumentation package were removed after confirming the predefined private-store and resource paths were absent.

The same source's ordinary debug package then completed the full local API36 suite: 187 started and finished, 186 passes, zero failures, and exactly one expected assumption skip for `NativeBridgeInstrumentedTest.actualJniLibraryLoadsAndRejectsUnsafeInputWithoutTakingCallerFd`, because this package does not contain VPN JNI. JUnit time was 332.992 seconds within the unchanged 720-second deadline. App SHA256 is `4499b4031e6307aa3fd40c1aeb45d94571b71f10d52172ed0aac9064b7448ddb`; test APK SHA256 is `49d1de826f92e6733aeb286e9cccd4584a2f404e2d1460c2be73f58a6072b040`. Both installed hashes matched before cleanup. The initial cleanup guard found an empty service-created preparation directory; only that exact empty directory was removed non-recursively, then all 15 private-file and two resource-path absence checks passed before removing the owned app/test. These results do not prove native VPN traffic or actual-account import.

Source-98 [M0 run37206973350](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37206973350), attempts 1 and 2, each passed build/lint/test-APK compilation and 841 JVM tests with zero failures/errors/skips. Both stopped at `ANR_AFTER_SINGLE_PREINSTALL_WARM_BOOT`, identifying Android SystemUI before application installation. Neither started instrumentation; both ran zero device tests and did not reach the suite deadline. These remain failed platform gates, without establishing an application or subscription-import failure. The local full-suite result is recorded separately and does not rewrite either hosted failure.

## Packaged native candidate on Android 16

Application source `dedb9facca59b128fb094ce0250c396f0f2f1c9b` used the independently verified source-98 native artifact. Fresh candidate app/test builds and lint passed. The hidden, newly owned one-click package passed `OneClickVpnInstrumentedTest` on local API36: exactly one start/pass, no skips or failures, 9.852 seconds. It exercised the actual Activity, owner, permission flow, production service and remote JNI with a synthetic loopback SS peer. TCP, UDP and DNS traffic passed; navigation and Activity recreation retained the same connection; disconnect removed the remote/TUN; reconnect required a fresh remote and Network and another successful traffic probe. This is real native packet-path evidence, not a real account or TUIC endpoint handshake.

The installed fixture app SHA256 was `5cfae180f8a1cf72daad86aa07c91ae9f797ab648f2033baad90ff5abcff753a`; test APK SHA256 was `2a20b94040334bc2613573e784724a627c7fa6c8a8d921afab6987ebf143fc6b`. Both installed hashes matched before and after the test. All 15 predefined private-file paths and both resource directories were absent before and after; the owned app/test were then removed with package absence confirmed.

The separately rebuilt normal candidate has all synthetic/storage/lab/TUN acceptance switches disabled. Debug, release compilation, lint, both ABI/16-KiB packaging and release lab exclusion passed. Its APK SHA256 is `8e2c69febea326fecb4b82f453b62810753df8e335dab74bcd6a3d553fb41601`; the installed API36 copy matched, and its login page was opened for the user. Real subscription import and connection still require user-entered login. This normal package is retained and protected from uninstall or data clearing.

## Remaining acceptance

1. Finish the current PR's hosted CI with all existing source/artifact checks enforced. The local acceptance above does not change the status of a hosted run.
2. With a user-entered login on the normal candidate, refresh the actual subscription and require the imported state before connecting, disconnecting and reconnecting. Record only fixed public status categories. If another option is rejected, audit its exact semantics rather than silently deleting arbitrary fields.

Actual configuration values, complete import success and endpoint reachability remain unknown. An emulator pass does not establish OPPO-specific lifecycle behavior. Existing logged-in application data remains protected; rollback uses the retained same-package/same-signer normal candidate without clearing data.
