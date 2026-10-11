# One-click connect: fresh subscription read budget

Symptom (0.4.2, OPPO): tapping 一键连接 shortly after a cold start showed 连接中 for about 20 s and then
读取失败 (`PREPARATION_FAILED`/`TIMEOUT` from the CONFIG step). An immediate second tap connected in 1–2 s.

Measurements (same phone, same subscription):

- With a local-file configuration (no network read) the whole CONFIG step, including the physical-network
  lease and the node DNS lookup, takes 0.1–0.9 s on a cold process, 5 cold starts out of 5, for configs of up to
  the 64 KiB / 128 node limit. Lease, DNS and YAML parsing are not the stall.
- With a third-party remote subscription the time from the tap to the VPN process start varied between
  4 s and more than 20 s for the same code and the same host. The only network wait in CONFIG is the fresh read
  that every attempt must make (`docs/offline-protocol-contract.md`; cached bytes never substitute).

The read had two weaknesses:

1. One request on one connection. A stalled connection or slow answer had no recovery, although a new request
   made a moment later succeeded.
2. The coordinator gave the whole CONFIG step 20 s, while the transport itself allows a read 30 s, so a read that
   was still legitimately in progress was cancelled.

Changes (the fresh-read rule is unchanged):

- `ManualSubscriptionTransport` hedges the read: if the first request has not finished after 5 s, or it failed
  with a network error, one independent second request is made (different address family first when the DNS
  answer mixes families). The first success wins; the other is cancelled and its bytes wiped. A definitive server
  answer (revoked, unavailable, protocol, redirect, bad metadata) is never retried. The overall 30 s bound stays.
- `ConnectionTimeouts.configMillis` (defaults to `stepMillis`) lets the owner give CONFIG
  30 s read + 5 s DNS + 2 s lease + slack = 38 s. All other steps keep 20 s.
- Debug builds only: `GCCPrep` log marks (`PrepTrace`) with fixed phase names, no URL, host or config bytes.
