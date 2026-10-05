# 官网更新检查

对接合同固定为官网 PR #22 commit `1838d23e83d052c1013c36ddb568d30f5f9e8182`：

- `docs/ANDROID_RELEASES.md` 和 `src/company-site-page.tsx` 定义 schemaVersion 1、android、stable
- `index.html` canonical origin 为 `https://gccwebos.com/`
- 固定公开请求：`https://gccwebos.com/android/releases/stable.json`
- 该合同最初以 `status: unavailable` 接入；当前公网发布状态以实际清单为准，客户端实现不代表官网已部署或已发布 APK

## 安全与状态

正式包（`com.gccwebos.android` 且 `BuildConfig.DEBUG=false`）在首次进入前台及随后回到前台时静默检查。独立控制器安装在 MainActivity 的账户和连接服务之外，不等待或修改登录、连接、套餐状态。检查期间不展示加载、失败或重试组件，失败不会阻断原页面。没有后台服务、定时任务或后台下载；离开前台会取消本次检查，取消后迟到的结果不能弹窗。

自动检查将尝试时间持久化到独立 SharedPreferences，正常系统时间下最多每 24 小时尝试一次，包括失败尝试；重建 Activity 或重启应用不会绕过限频。系统时间回拨允许一次新尝试并校正旧时间。负数、错误类型和超出清单版本上限的本地值按缺失处理；存储读取/提交失败静默停止本次自动检查，不影响手动入口。持久化只包含尝试时间和已展示的最高 versionCode，不保存账户、配置或清单正文；读取和同步提交均在 IO dispatcher 上完成。

新版本只有在前台实际渲染提示后才记录已提示版本，后台完成或被手动页面遮蔽的结果不提前去重。已经展示的相同或更低版本不再自动弹出，包含按 Home 离开后返回的情况；更高版本仍可提示。提示可关闭，只有用户明确点击“前往官网下载”才交给浏览器。提示去重持久化失败时仍能立即关闭；这种情况下仅本次进程保证不重复，重启后可能再次提示。

“我的”中的手动更新入口保留原查询行为，用户可明确重新检查已提示过的版本。手动更新页存在期间抑制自动弹窗；手动实际展示的新版本会撤销同版本的自动待提示，避免两个更新框叠加。手动查询仍展示该入口原有结果，自动检查不会向其他页面注入错误提示。

两种入口复用原专用 OkHttp 客户端，不带账户会话、Cookie 或订阅信息，禁止所有 HTTP 重定向，HTTPS 默认系统证书校验，连接/读取 8 秒、总请求 15 秒。取消请求会取消底层 Call。响应限 32 KiB、application/json 和有效 UTF-8；严格窄 JSON 解析拒绝重复键、隐式类型转换、未知字段、非法日期、错误平台/频道及不安全路径。schemaVersion 变更必须显式升级解析器。

只比较数值 versionCode，显示未发布／已最新／有新版本／低于最低支持版本／无效清单／网络失败。minimum support 提示可关闭，不在尚未定义的合同上擅自阻断账户或业务。并发检查合并；关闭/离开页面、取消和后续新检查会隔离旧结果。

Debug / candidate 不属于正式升级链，自动入口同时检查包名与 DEBUG，不读更新调度存储、不请求官网、不弹正式升级提示；手动入口仍显示测试包提示。正式 applicationId 固定为 `com.gccwebos.android`。清单 schema 1 没有 packageName、minSdk 或签名证书字段，本实现不伪造这类校验；首次正式发布仍须验证 APK 实际包名、签名、SDK 兼容、递增版本和旧版安装升级。

## 浏览器边界

新版本弹窗仅在用户点击后对精确同源 `/android/releases/<filename>.apk` URL 发出 ACTION_VIEW + CATEGORY_BROWSABLE，不使用 WebView、不下载 APK 到应用目录、不执行安装器、不新增 REQUEST_INSTALL_PACKAGES 或存储权限。初始 URL 不允许查询串、片段、编码路径、目录穿越或外域。交给外部浏览器以后，网页/APK 的重定向、下载和 Android 安装由浏览器/系统管理；App 不能验证下载文件，也不会把展示的 SHA-256 文本声称为已验证。正式发布流程须验证公网 APK 终点和签名链。

## 验证

原 M1 阶段的 24 个 JVM JUnit 用例通过 Kotlin 2.3.10 CLI / JVM17 target / coroutines1.10.2 / OkHttp4.12.0：版本和最低支持边界、debug 隔离、无发布、schema/JSON/UTF-8/日期/路径恶意输入、重复点击、取消/迟到结果、Http Call取消、重定向和错误终点、Content-Type、超大/无长度响应。此历史结果不作为本轮新增自动检查的验收结果。

本轮先增加 `AutomaticUpdateControllerTest` 13 项 JVM 边界测试，以及 `AutomaticUpdateUiTest` 5 项和 `AndroidAutomaticUpdateStoreTest` 3 项 Android 测试。覆盖持久化限频/跨实例、时间回拨与坏值、IO 调度、取消/迟到结果、非正式包隔离、实际展示后去重、前后台切换、手动弹窗互斥、显式浏览器点击、静默失败及原页面可操作性。全部使用合成清单和注入浏览器，偏好存储测试仅使用 instrumentation APK 自有 Context。

本轮定向 JVM 共 37 项通过、0 失败、0 跳过（新增自动检查 13 项和既有 parser/repository 24 项）；release APK、普通 debug APK、Android 测试 APK 构建及 release Lint 通过，Lint 0 error / 27 warning / 6 hint。独立源码复核已完成，修复了损坏的超上界持久化版本值和 Home 返回重复提示边界。

OPPO 当前锁屏，新增 8 项 Android 测试及既有更新 UI 回归尚未实机执行；等待解锁和修正提交的完整 CI。当前 release 产物未签名，不能安装或称为已发布；签名及官网下载链另外验收。本轮不修改生产清单 URL、schema 或账户存储，没有增加权限或执行真实下载/安装。记录见 [正式发布准备](stable-release-preparation.md)。
