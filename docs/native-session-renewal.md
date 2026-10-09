# Native session renewal

Risk A: authentication and encrypted credential storage. This change is local preparation until the backend contract, focused tests, full CI and independent review pass. It does not deploy the backend, extend a bearer locally, store passwords, or adopt browser cookies.

Intent: keep an authenticated native account available across access expiry using a server-issued, rotating refresh grant. Access remains capped at 30 minutes. The grant expires at its original family deadline, at most seven days after the fresh password/TOTP authentication; renewal cannot slide that deadline.

Contract:

- Fresh password/TOTP success may exchange once at `POST /api/portal/native-session`, with the access header and exact `{}`. The response contains the original native session and `refreshGrant` (`refreshToken`, `issuedAt`, `expiresAt`). A failed/unsupported exchange preserves the original short session and never invents a grant.
- Renewal uses `POST /api/portal/native-session/refresh`, exact `{refreshToken}`, without an access header. Both access and grant rotate. The account and grant family dates must remain unchanged.
- Logout clears local credentials before suspension, then attempts `POST /api/portal/native-session/logout` with the captured grant. Server revocation is best effort when the network is unavailable.
- Mutations are not automatically retried. A lost rotation response can require a fresh login because the previous grant has already been consumed. No client replay grace is invented.

Startup, foreground return, proactive expiry and protected-request expiry use the same serialized restoration/renewal path. Temporary failure retains the encrypted grant but does not publish authentication. Explicit logout and authoritative invalid-grant responses revoke restoration; late responses cannot undo logout or replace a newer login.

The encrypted record stores the access/grant pair atomically and reads existing v1 access-only records. The original access TTL and token-based operation fences remain. Known account-scoped orders and acknowledged subscription capabilities survive token rotation. Pending wallet-first delivery remains bound to its original access session: renewal does not silently rebind, delete, ACK or repeat a claim. Such a receipt still needs the existing recovery path; the backend has not authorized cross-session claims.

Existing v1 installations keep their valid short session. They obtain a grant only after a new successful password/TOTP login; restoration never upgrades an old bearer through the fresh-login exchange. Production client wiring enables the contract, while the paired backend enablement gate remains a separate rollout decision. No production gate is enabled by this PR.

Validation on 2026-10-06:

- Behavioral RED then GREEN for proactive renewal, foreground retry after temporary failure, and protected-request 401 without replaying the failed operation. A real backend DTO fixture exposed the initially incomplete mock; the parser was corrected and now accepts exchange, rotation and the final one-second access session while validating all seven session fields and four false safety flags.
- 23 new JVM tests cover native renewal, v1/v2 records, absolute expiry/clock rollback, canonical token format, unchanged family/account, token rotation, single flight, storage failure, logout/read failure, old 401/new login, committed rotation followed by overview failure, lost response, busy deferral, TOTP admission and preserved account-scoped recovery/delivery bindings.
- Full Windows JVM run: 1,150 tests, 1,142 passed, 8 failed, 0 skipped. The eight failures are the existing prepared-config POSIX initial-permission limitation (seven `SubscriptionImportTest` cases and `LocalSubscriptionTest.everyPreparationDownloadsAgainAndWorksWithoutPortalToken`), unchanged from the base behavior. Production file permissions were not relaxed. Linux exact-head CI remains required; this local run is not reported as all green.
- `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` completed. The combined command exits nonzero because of the eight JVM failures above.
- Root executed `NativeSessionStorageTest` on the OPPO: 3 passed, 0 failed (0.353 seconds). Each case uses an isolated debug-owned directory: actual v1 ciphertext compatibility, v2 encrypted pair round-trip/clear, and production factory renewal wiring without HTTP. The two debug/test packages were absent before installation and were removed afterwards; existing formal/candidate packages and their data were retained.
- Independent read-only review found and closed the logout read-failure blocker with a focused RED/GREEN test; subsequent static review found no further blocker. Device storage validation is not a real-account renewal end-to-end test.

Remaining gates: exact-head Linux JVM/Android CI, paired-backend staging with the gate explicitly enabled, real exchange/expiry/rotation/logout acceptance, then a separately approved release. Existing B2 P7 recovery timeout remains unresolved; its following two checks were not run and are not covered by this PR.

Rollout: deploy the reviewed backend contract before distributing the client. Old servers retain short-session behavior. Roll back server enablement rather than altering TTL checks; do not clear user configuration data or downgrade an incompatible encrypted record. A client rollback must use a reviewed compatible higher version. Release signing, installation, merge and publication are separate gates.
