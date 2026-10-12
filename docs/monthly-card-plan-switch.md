# Monthly card plan change (renew / upgrade / scheduled downgrade)

Client side of the server feature `proxy-gateway-minimal` #772 (`COINS_COMMERCE_PLAN_SWITCH_ENABLED`, default off).
The server prices, decides and re-checks everything; the app only displays the server's answer and never prices a change.

## Server contract used

| Piece | Use |
| --- | --- |
| `GET /api/portal/commerce/plan-switch/state` | `{ current, pendingSwitch, offers[{offerRef, action, chargeUnits, creditUnits, effectiveAt, reasonCode}] }`. Advisory display state (card labels, banner). `chargeUnits` is the price difference **before** the account discount. |
| `POST checkout-preview` | Quote gains optional `planSwitch {kind, creditUnits, chargeUnits, effectiveAt, cancelsPendingDowngrade}`; `amountUnits` is the discounted change charge. |
| `POST orders` | Order gains the same optional `planSwitch`. |
| 409 codes | `plan_switch_*` (see below) on preview or confirm, always before an order exists. |

## Behaviour

- **Upgrade** (monthly→monthly, monthly→yearly): card shows `升级 · 补差价 N Coins · 立即生效`; dock button 立即升级; the confirmation says 立即生效, 已用流量不清零, and for a yearly upgrade that the 365 days restart today. 不退款.
- **Downgrade**: card shows `降级 · 到期后生效`; dock button 预约降级; the confirmation says 到期后生效（date）and that the current card and traffic stay unchanged until then. After payment the plans page shows a **待生效** banner until the date.
- **Renew** is unchanged; while a downgrade is scheduled it says the renewal cancels it.
- A change the server refuses (reason code) is shown as 暂不可切换 with a plain sentence and cannot be selected.
- The confirmation uses the shared `GccDialog`. Ordinary purchases keep their existing dialog.
- A quote that is not the change the card promised (e.g. the card said upgrade but the quote is a plain full-price purchase) can never be confirmed: the dialog says the plan state changed and keeps 确认 disabled.

## Feature off / unsure

The state read is **best effort and optional**: a 404, the protected-fallback 401, any other non-200, a malformed body or a
non-legacy commerce model all mean "no plan change" and the ordinary catalog is shown exactly as before. That read goes through
`PortalRepository.commerceOptional`, which never ends the sign-in on a 401 (the ordinary `commerce` call does, by design);
the catalog reads themselves still prove the session.

## Errors (HTTP 409, plain Chinese, no codes or hosts shown)

`plan_switch_upgrade_window_closed` 剩余有效期不足 · `plan_switch_cycle_misaligned` 暂不能升级到年度套餐 ·
`plan_switch_state_changed` 套餐状态已变化 · `plan_switch_already_scheduled` · `plan_switch_application_pending` ·
`plan_switch_credit_exceeds_price` · `plan_switch_precheck_unavailable` · the three authority/model codes (暂不支持更换套餐).
All are definitive pre-order refusals, so the durable create key is cleared; the plan-change state is re-read afterwards.

## Not in this change

- No timer re-reads the state at the scheduled date (the banner just stops showing once the date passes; the next catalog read refreshes).
- Order-history detail keeps its generic wording for plan-change orders.
- The ordinary purchase dialog still uses the old `AlertDialog`.
