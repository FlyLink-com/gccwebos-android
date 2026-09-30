# clash飞连vpn: subscription and wallet draft

This is a **stacked draft** on PR #6's immutable `739647072c3fdc30b52ae519bbbf491f31b2575b` source snapshot. It does not change PR #6, merge, deploy, sign a production release, or enable production VPN. Application IDs remain `com.gccwebos.android` / debug suffix; versionCode 5 identifies this new app source rather than the prior APK.

## Source of truth

Read-only source verification on 2026-09-30:

- [mobile master 091ee6e](https://github.com/gccltd/gccwebos-mobile/tree/091ee6e82bb768333dcc23eaaf725c1fd07ba243), especially `src/api/upstream/{commerce,wallet,recharge}`, `src/data/{purchase,recharge}.mjs`, `src/api/public-runtime.mjs`
- [backend master 2dea45f](https://github.com/gccwebos-com/proxy-gateway-minimal/tree/2dea45fcbac1e330c76678b49124e0fcb1f630ac), portal-coin-commerce/wallet/recharge routes, production catalog and CNY recharge price book

The source catalog contains three plans: 30 days / 20 Coins, 30 days / 30 Coins, 365 days / 200 Coins. Their server quota terms remain factual card/confirmation details, not plan headings. The Android app has **no hardcoded catalog, price, balance or recharge amount**: actual cards require successful authenticated responses. Deployed configuration and availability were not queried in this work.

## UI

- Exact requested name `clash飞连vpn` in release/debug launcher labels and accessible header. Adaptive launcher icon references the existing authorized robot avatar pixels without repainting them.
- Connection state distinguishes signed out, waiting, loading, error, reconciliation and authoritative `Resource.None`. No-subscription guidance appears once per authenticated account/session on Connection; Back, dismissal, navigation and refresh do not create a modal loop.
- Available resources show server duration only in headings, nonnegative live time remaining, local-zone expiration and factual total/used/remaining traffic. Unknown, pending, stale and expired are never converted to zero, unlimited or active entitlement.
- My wallet has current-response Coins balance and held/pending buckets, recharge offers, order status and explicit payment review. Unknown/error is never zero.

## Money and recovery boundary

- Resolve `legacy` versus `wallet_first_unique_v1` from the authenticated server. Legacy quotes/orders and wallet-first quotes/deferred transitions have separate routes and state labels.
- Quote details are server-derived and expiration is checked at confirmation. The app never confirms automatically after a return, background/resume, refresh or restored selection.
- Persist public recovery references and idempotency key in account-hashed no-backup AtomicFile storage, flush the file and directory, verify readback, then send the first mutation. No payment URL, session credential, price or balance is persisted there.
- Purchase key uses `Idempotency-Key`; recharge key uses the exact request body. Timeout/cancellation/recreation retain the same key. Resume performs only GETs for known results; ambiguous creation requires an explicit restore action using the original key.
- Server-confirmed pre-order rejection status/code pairs can clear a rejected purchase: 409 expired/stale/unavailable offer; 423 insufficient Coins/inactive wallet. Transaction/replay ordering was verified in both backend stores. Unknown outcomes/reconciliation retain recovery.
- Account epoch checks plus expected-session hash validation **inside** the portal mutex prevent old work using a replacement login; late old errors cannot sign out a new session.
- No frontend inference equates order creation, payment verification, Coins credit, resource fulfillment or deferred settlement. Unsettled records remain visible. Cancellation has its own confirmation, server window check and stable derived key.
- Only source-confirmed XSD/alipay handoff is enabled, matching mobile support. Other methods, including PayPal sandbox/capture, are not offered as usable native payment methods. Payment URLs require a strict adapter-specific HTTPS origin allowlist. An explicit review displays current actual order amount, Coins, platform, method, adapter and destination host; click-time checks require the same current-session order and unexpired action. No Portal headers are passed to the external browser.
- There is no invented native payment deep link, automatic provider capture, arbitrary WebView or simulated payment success. External browser return triggers read-only reconciliation.

## Verification boundaries

All added unit and UI fixtures are synthetic/offline and fail if a UI test makes a network call. No production login, quote, purchase, top-up, provider capture or backend mutation was performed.

The fixed-artifact `android-native-runtime-api33.yml` verifies the **older** immutable native APK and is not evidence for this new app source. This slice does not change that workflow or its watched harness files. Standard Android and native-build workflows build fresh artifacts for this commit. The native-build workflow runs its own newly compiled APK/test APK with the already verified API33 script and checks the APK hashes before/after execution; it never invokes the old-artifact materializer. Standard UI coverage remains on API36. Source validation and offline JVM tests are not substitutes for Gradle/Lint, Android instrumentation, JNI runtime or a funded physical-device commerce test.

No paid/funded account or real VPN/TUN traffic was tested. Resource-to-engine automatic production import remains a subsequent integration gate; existing secure setup delivery is preserved.

Local diagnostic result: Kotlin 2.3.10/JVM 17 compilation and **193 JUnit tests passed**, including 19 commerce repository recovery tests, 18 strict commerce projection tests and existing account/core/update/VPN safety tests. Reproduce with `KOTLIN_TOOLS_DIR=<verified jars> bash scripts/test-domain-offline.sh`. Android-specific Compose/AtomicFile code is excluded from this fallback and must pass the normal Gradle/Lint/instrumentation gate.

Five additional Android-only persistence fixtures exercise recreation/readback, result update/removal, corruption/size rejection and storage path boundaries. These are authored tests awaiting CI, not claims of device success or simulated fsync failure coverage.
