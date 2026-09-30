# Native local subscription and mobile account parity

Source baseline: Android main `011482ce57a7e966e50892b1f6f9bf1a0ebcd590`; mobile master/main [`091ee6e82bb768333dcc23eaaf725c1fd07ba243`](https://github.com/gccltd/gccwebos-mobile/tree/091ee6e82bb768333dcc23eaaf725c1fd07ba243). Backend contracts inspected at `2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf`. Source state does not prove a production deployment. No real account, payment, resource claim or production endpoint was exercised.

## Local VPN shell and account separation

- With no usable local subscription, launch shows login only. TOTP, cancel and Back cannot enter main accidentally
- After acknowledged delivery and strict import, a separate Android Keystore-encrypted record retains the subscription URL, binding, bounded configuration, expiry metadata and known terminal status. Cached bytes provide the node list and shell UI only
- A saved local subscription admits the VPN shell without a Portal token. Ordinary account expiry, logout and wallet-account changes preserve that explicitly separate local capability. Account changes do not silently replace it. An explicit confirmed clear removes both local profile and Portal delivery receipt under the activation/revision fence
- Each connection preparation downloads the saved subscription URL afresh, validates and projects the whole configuration, and atomically writes it before the guarded one-shot activation callback. Failure never falls back to cached configuration. The artifact client has no Portal credentials or cookies and rejects redirects
- Wallet, purchases, recharge, orders and ledger still require a valid Portal session. Foreground account validation cannot discard the local shell or silently authenticate commerce
- Production VPN remains disabled. The local preparation gateway has no production line resolver or service wiring. Metadata does not authorize connection; no fake connected state is shown

## Expiry and errors

Backend `server/routes/subscription.mjs` emits `X-Subscription-Valid-Until`. Backend subscription core distinguishes exact 403 `Subscription expired` from `Subscription link expired`. The former and authoritative elapsed validity show purchase/renew guidance; the latter shows an invalid-link recovery state. Other transport, format and unavailable errors remain unknown/retry/unsupported, not fabricated expiry. Known expiry/revocation is persisted for the exact capability binding so restarting cannot erase it. A successful fresh server read may update that observation.

## Account persistence and safety

Native bearer sessions are normally **30 minutes**. They survive restart only for their original server-bounded remaining lifetime; no password, invented refresh endpoint or client-extended TTL is stored. `SavedSession` includes the stable native account ID and bounded wall/elapsed lifetime. Restoration performs an authenticated overview read before publishing account state. Network failure remains retryable; invalid/expired sessions are removed. Foreground TOTP round trips preserve their bounded ticket; busy revalidation queues behind the current operation. Reset admission is undispatched, saved-token removal occurs before suspension, and admitted cleanup drains despite caller cancellation.

Session, local profile and delivery use distinct Keystore/AAD/file boundaries in `noBackupFilesDir`. Reads are bounded; writes use AtomicFile, fsync and readback verification. Errors and diagnostic representations do not expose tokens, subscription URLs or configuration secrets.

The browser's seven-day Cookie mode is a separate contract. Its DTO exposes context/userRef rather than a stable native accountId; userRef may be nonunique. It cannot replace existing payment/delivery identity bindings. No native refresh-grant backend change is included. Local VPN persistence does not claim permanent Portal login.

## Mobile business and layout contracts

- My shows the account welcome card, source robot and lounge, actual spendable balance, recharge/ledger actions, wallet, account security and signout. Screenshot identities and amounts are never copied into production fixtures
- Balance uses `wallet.spendableUnits`, not summed balances or CNY. Remaining time comes from `validUntil`, not a plan's total duration; pending/stale usage retains its semantics
- Lines show actual parsed names/protocol/compatibility without fake regions, latency, endpoints or credentials. Unsupported mixed configurations still fail whole-config projection
- Plans/data-pack tabs display only validated server-returned offers. No local catalog, invented price or unpublished package is seeded. Data packs have traffic GB without fabricated duration/monthly quota
- Shopping, recharge and plan-schedule history preserve payment/fulfillment distinctions. Legacy orders require `?limit=20` with paired `afterCreatedAt`/`afterOrderRef`. Wallet ledger requires `?limit=20` with paired `afterOccurredAt`/`afterEntryRef`; strict deltas, ordering and bound cursors are checked. No other query shapes are admitted
- Recharge retains version-2 offers, integer money/exponent formatting, original durable operation keys, explicit confirmation, trusted payment URLs and authoritative status refresh. Browser return never implies credited payment
- User-visible mark is `clash|飞连`; app label is `飞连vpn`. Real API domains, package IDs and technical/license identities remain unchanged

Sources: mobile `src/main.jsx`, `src/login.jsx`, `src/panels.jsx`, `src/mine.jsx`, `src/api/read-client.mjs`, `src/api/current-resource.mjs`, `src/api/upstream/commerce/commerce-client.ts`, and the wallet/recharge clients. Visual tokens remain grounded in `src/style.css`, `src/components/button-tokens.css` and `src/components/alipay-visual.css`; further overall concept design is separately reviewed.

Alipay uses unchanged source vector geometry from `src/components/alipay-mark.svg`, blob `cf10d378a798b1cafe0e38860c7fdef82c2a0b0c`. Wallet coin is the existing mobile artwork. The approved launcher, hoodie and lounge were mechanically resized/encoded with preserved aspect and alpha; identities are recorded in `branding-assets.json`. Native Canvas captures circle/squircle and 48dp launcher previews. Superseded Earth artwork and old raster wordmark are absent.

The official Telegram announcement link is [@flylinevpn](https://t.me/flylinevpn), verified in site [`8d1ed3e4141128f6df11845dfbb638088d471ab1`](https://github.com/gccltd/gccwebos-site/tree/8d1ed3e4141128f6df11845dfbb638088d471ab1), `src/site-growth-content.ts`. The native vector preserves `/assets/telegram/telegram-brand.svg` geometry (blob `791e8fb8ccab1b606c45676d050c4a675702fdf0`). This is a public announcement channel, not a claim of private customer support; no account/order/payment data is sent.

Backend PR753/mobile PR13 independent data-pack retrieval/rotation remain unpublished dependencies; their candidate endpoints are not called. Future system tile, production foreground/background recovery, contextual VPN/notification permissions and OPPO ColorOS 13.1 hardware validation remain outstanding. No extra broad permission or battery setting is changed.

## Verification required before merge

Offline tests cover bounded persistence, revocation, transient failure, cancellation, unlink/restore races, local capability/account separation, repeated fresh preparation, terminal-state restart, strict catalog/orders/ledger and display-only nodes. Android tests cover login/local-shell gates, account switching, foreground return, TOTP, delayed/failed restoration, expiry, encrypted storage/tamper/clear, synthetic business screens and icon masks.

Full exact-head Gradle/JUnit/lint, API36 instrumentation, freshly rebuilt JNI/API33 regression, independent review and actual screenshot inspection are required. Test sources or this document are not execution evidence; completed results belong in the PR receipt.

## Integrated mobile flow corrections

The follow-up integrates accepted main `cded4a5` without altering its debug-only native acceptance fixtures or provenance checks. Original head `5942a71d` failed Android Lint (`ContextCastToActivity`); lifecycle access now uses `LocalActivity`. That failure remains historical evidence, not a claimed pass.

- F1: load one authoritative commerce model; wallet-first history requests only deferred transitions, legacy only legacy orders. An unavailable model never falls back. The visible default/category follows that model
- F2: every ten seconds while the screen is RESUMED, an awaited, cancellable job rereads only an already-known recharge order. It stops polling credited/failed/expired/reconciliation states; cancellation and session fences discard late success/error. It never calls the submission retry method or creates an order automatically
- F3: native authenticated GET `/api/portal/security/current` shows strict server-returned authenticator/passkey/setup booleans with unknown/error/retry states. A 401 invalidates account state. No security mutation, secret or browser transport is introduced
- F4: the current app session retains only the selected offer reference across navigation and payment browser return. My provides a return action; Plans resolves the current server catalog and explicitly fetches a fresh quote before a separately confirmed purchase. Signout/account change clears the selection. Process death does not restore this convenience selection; durable pending purchase/recharge recovery remains unchanged. No price, balance, quote or purchase instruction is retained by this shortcut

These changes resolve the actionable native flow gaps within the stated scope. They do not claim pixel-identical frontend parity, process-persistent convenience selection, production payment completion, or passed Android instrumentation before its exact-head receipt.
