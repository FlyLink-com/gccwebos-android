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

Answers below come from the #772 code at master `63dd535` (not yet from production).

- **Upgrade** (monthly→monthly, monthly→yearly): the card shows `升级 · 补差价约 N Coins · 立即生效`. `N` is `chargeUnits`, which is **before** the account discount, hence 约. The dock button says 立即升级.
  The confirmation shows the real deduction from the quote, `本次实扣：N Coins` (with `补差价 X，折扣后实扣 N` when a discount applied), and 立即生效 plus:
  - 月升月: `到期日不变，已用流量不清零` (`validFrom`/`validUntil` unchanged, cap swaps to the new tier).
  - 月升年: `从今天起重新计 365 天，旧卡剩余已折价抵扣` (`validUntil` = payment time + 365 days, not appended to the old card; credit shown as `折价抵扣`), `已用流量不清零`.
  The app does not claim any cap on upgrade amounts.
- **Downgrade**: card shows `降级 · 到期后生效` (price `约 N Coins`); dock button 预约降级; the confirmation says 到期后生效（date） and that the current card and traffic stay unchanged until then. After payment the plans page shows a **待生效** banner until the date.
- **Renew** is unchanged; while a downgrade is scheduled it says the renewal cancels it.
- A refused change (reason code) is shown as 暂不可切换 with a plain sentence and cannot be selected.
- `action: unavailable` with **no** reason code means the tier is not part of the plan-change table, so it is an ordinary purchase (no card label). While a card is active the server refuses any other ordinary offer with 409 `offer_unavailable`; that reads 「当前套餐生效中，暂不能购买其他套餐。」 (also the note on an offer the state itself marks `offer_unavailable`).
- **Applying a scheduled downgrade**: once `pendingSwitch.effectiveAt` has passed but the server has not cleared it (state may answer every offer `plan_switch_application_pending`, preview/confirm return 409 the same code) the banner says **处理中**, all cards are locked with 「正在应用已预约的降级，请稍后。」, and the app quietly re-reads the state every 4 s for about a minute (no spinner, an unreadable answer keeps the screen). It stops when the server clears it or the page closes. If the server still has not cleared it 15 minutes after the date the app drops back to the ordinary catalog; the server's own 409 then gives the same sentence.
- The confirmation uses the shared `GccDialog`. Ordinary purchases keep their existing dialog.
- A quote that is not the change the card promised (e.g. the card said upgrade but the quote is a plain full-price purchase) can never be confirmed: the dialog says the plan state changed and keeps 确认 disabled.
- A plan-change order cancelled at capture time (monthly→yearly only: cycle misaligned, entitlement drift, expired, scheduled downgrade pending) shows 「套餐变更已取消，Coins 预留已释放，未扣款」. The app never says the upgrade window closed there (that check only runs at quote and confirm).

## Feature off / unsure

The state read is **best effort and optional**. With the flag off the server answers `401 {"error":"Unauthorized"}` for a valid session (a plain **string** error). That, 404, any other non-200, a malformed body, 409 `wallet_first_deferred_required` (flag on, wallet-first account), 409 `plan_switch_precheck_unavailable` (non-active account / unreadable precondition) or a non-legacy commerce model all mean "no plan change": the ordinary catalog is shown exactly as before, with no error toast.
The read goes through `PortalRepository.commerceOptional`, which never ends the sign-in on a 401 (the ordinary `commerce` call does, by design; the catalog reads themselves still prove the session) and never looks at the error body, so a string body cannot crash it.

## Errors (HTTP 409 on preview/confirm, plain Chinese, no codes or hosts shown)

| Code | Text |
| --- | --- |
| `plan_switch_upgrade_window_closed` | 剩余有效期不足 60 分钟，暂不能升级；请先续费，或到期后再购买。(only upgrades are blocked; same-tier renewal still works) |
| `plan_switch_credit_exceeds_price` | 旧卡剩余价值已不低于年卡价格，暂不能升级到年卡；请到期后再购买。 |
| `plan_switch_cycle_misaligned` | 暂时不能升级到年卡：升级会改变已用流量的统计周期。可在当前套餐到期后购买年卡。 |
| `plan_switch_application_pending` | 正在应用已预约的降级，请稍后。 |
| `plan_switch_already_scheduled`, `plan_switch_state_changed`, `plan_switch_precheck_unavailable`, the three authority/model codes | plain sentences |
| `offer_unavailable` | 当前套餐生效中，暂不能购买其他套餐。(only while a card is active) |

All are definitive pre-order refusals, so the durable create key is cleared; the plan-change state is re-read afterwards.

## Not in this change

- Order-history detail keeps its generic wording for plan-change orders (except the cancelled line above).
- The ordinary purchase dialog still uses the old `AlertDialog`.
- Nothing here has been run against a real #772 server; the wording follows the code at `63dd535`.
