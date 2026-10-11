# Dialog system (design/dialog)

One set of components for every dialog, sheet, menu and transient message, so new surfaces match the glass design
used by the pages behind them. Package `com.gccwebos.android.design.dialog`.

| Component | Use |
|---|---|
| `GccDialog` | Centred confirmations. Title, optional icon badge, plain `body` and/or a scrolling `content` slot, then `primary` / `secondary` / `tertiary` `GccDialogAction`s. `tone = Danger` makes the primary red. |
| `GccSheet` | Detail views and light flows (was `GccBottomSheet`). Header with optional ✕, scrolling content, optional pinned `actions` bar. |
| `GccMenu` + `GccMenuItem` | Dropdowns. `destructive = true` is red. |
| `GccMessageHost` + `GccMessages` | One transient message bar ("已复制", read errors with 重试). Never clips at a comma. |

Rules that are unit-tested (`GccDialogRulesTest`): one or two short actions share a row (primary on the right);
three actions or any label wider than 6 full-width characters stack, primary first; reduce motion ("remove
animations") shows the dialog at full opacity on the first frame with no scale; message text is shown whole.

* **Secure surfaces.** Any dialog or sheet that can show a subscription link, node or configuration detail must pass
  `secure = true`; it keeps FLAG_SECURE on that window (`GccDialogSystemUiTest` checks the flag).
* **Tags.** `GccDialog(testTag = "x")` tags the surface and gives `x-title` / `x-body`; actions carry their own
  `GccDialogAction.testTag`, so existing UI tests keep their handles.
* **Wording.** Titles are short nouns/questions ("退出登录？"), bodies say the real effect in plain words. Payment
  dialogs must not show the portal host.
* **Not wired yet.** `GccMessageHost` is not mounted in the app shell in this PR (MainActivity is also touched by
  other open work); the three existing snackbar hosts keep working until the messages PR.

Migrated here: the logout dialog and the two sheets that used `GccBottomSheet` (套餐时间, 余额明细). Everything else
moves in follow-up PRs, see the audit plan.
