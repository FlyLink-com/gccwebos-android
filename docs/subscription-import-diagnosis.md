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

## Remaining acceptance

1. Commit the complete compatibility change and verify the matching source, binaries, app and tests. Existing source/artifact gates must remain enforced.
2. Use an Android emulator while OPPO is unavailable, as requested by the user. Test synthetic subscription import, node selection, VPN admission, traffic proof, disconnect and reconnect, recording the exact candidate identity.
3. With a user-entered login on the normal candidate, refresh the actual subscription and require the imported state before connecting. Record only fixed public status categories. If another option is rejected, audit its exact semantics rather than silently deleting arbitrary fields.

Actual configuration values, complete import success and endpoint reachability remain unknown. An emulator pass does not establish OPPO-specific lifecycle behavior. Existing logged-in application data remains protected; rollback uses the retained same-package/same-signer normal candidate without clearing data.
