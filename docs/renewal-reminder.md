# 账户订阅续费提醒（弹窗、顶部光效条、每日 20:00 通知）

范围：只针对**账户订阅**（`Resource.Available`，来自服务器已验证的账户资源）。导入的第三方订阅、本地文件配置不触发。没有真实到期数据时不显示任何提醒，不使用推测值。

## 规则
- 提醒窗口：到期前 7 天内（`RenewalSpec.WINDOW_DAYS`）。已到期仍显示「套餐已到期」（弹窗/光效条，不发每日通知）。
- 流量：仅当服务器**新鲜测量**（`measured`/`measured_zero`，非 `stale`/`pending`）且剩余 ≤ 10% 时提示；到期提醒优先。通知只针对到期窗口（流量只在 App 打开时才知道）。
- 弹窗：可关闭，含到期时间与「去续费」→ 套餐页。关闭后当天顶部显示细光效条，点击 → 套餐页；光效条可关闭，**当天不再出现，次日重新从弹窗开始**（日界线为设备本地时区）。
- 系统通知：每天北京时间 20:00（`Asia/Shanghai`），仅在窗口内，点击 → 套餐页；续费（到期时间被服务器更新到窗口外）后自动停止并撤回已发通知；退出登录/清除本地数据时清除存储的到期时间并取消闹钟。
- POST_NOTIFICATIONS（Android 13+）：第一次显示弹窗时请求一次，启动时不请求；被拒绝则只有 App 内提示。
- 减少动画（系统「移除动画」）时光效条是静态渐变；页面不在前台时不跑动画。

## 实现
- `renewal/RenewalReminderLogic.kt`：纯规则（窗口、流量、每日状态机、20:00 北京时间槽位、文案），JVM 单测。
- `renewal/RenewalReminderCoordinator.kt`：把服务器资源 → 存储的到期时间 + 单个闹钟；在 JVM 用假闹钟测试。
- `renewal/RenewalReminderAndroid.kt`：`AlarmManager.setAndAllowWhileIdle`（不精确，无 SCHEDULE_EXACT_ALARM、无唤醒锁、无前台服务；每天最多一个闹钟，且只在窗口开始后才武装）+ 非导出 `RenewalReminderReceiver`（只用存储的到期时间，不联网、不读会话）。重启/升级后由 `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` 重新武装（新增普通权限 `RECEIVE_BOOT_COMPLETED`）。
- 存储 `SharedPreferences("renewal_reminder")`：只含到期时间戳、当日关闭状态、两个标志；不含账号、邮箱、令牌、订阅链接。
- UI：`RenewalPopup`（AlertDialog）、`RenewalBanner`（Scaffold `topBar`，含状态栏 inset；无提醒时不占空间）。通知点击以 `ACTION_OPEN_PLANS` 打开 `MainActivity`，Activity 重建不会重放。
