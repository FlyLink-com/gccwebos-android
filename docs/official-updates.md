# 官网更新检查（M1）

对接合同固定为官网 PR #22 commit `1838d23e83d052c1013c36ddb568d30f5f9e8182`：

- `docs/ANDROID_RELEASES.md` 和 `src/company-site-page.tsx` 定义 schemaVersion 1、android、stable
- `index.html` canonical origin 为 `https://gccwebos.com/`
- 固定公开请求：`https://gccwebos.com/android/releases/stable.json`
- 当前仓库清单是 `status: unavailable`；实现代码不代表官网已部署合同或已发布 APK

## 安全与状态

仅用户点击“我的／检查更新”时查询。专用 OkHttp 客户端不带账户会话、Cookie 或订阅信息，禁止所有 HTTP 重定向，HTTPS 默认系统证书校验，连接/读取 8 秒、总请求 15 秒。取消请求会取消底层 Call。响应限 32 KiB、application/json 和有效 UTF-8；严格窄 JSON 解析拒绝重复键、隐式类型转换、未知字段、非法日期、错误平台/频道及不安全路径。schemaVersion 变更必须显式升级解析器。

只比较数值 versionCode，显示未发布／已最新／有新版本／低于最低支持版本／无效清单／网络失败。minimum support 提示可关闭，不在尚未定义的合同上擅自阻断账户或业务。并发检查合并；关闭/离开页面、取消和后续新检查会隔离旧结果。

Debug applicationId 带 `.debug`，不属于正式升级链，返回明确测试包提示且不访问官网。正式 applicationId 固定为 `com.gccwebos.android`。清单 schema 1 没有 packageName、minSdk 或签名证书字段，本实现不伪造这类校验；首次正式发布仍须验证 APK 实际包名、签名、SDK 兼容、递增版本和旧版安装升级。

## 浏览器边界

新版本弹窗仅在用户点击后对精确同源 `/android/releases/<filename>.apk` URL 发出 ACTION_VIEW + CATEGORY_BROWSABLE，不使用 WebView、不下载 APK 到应用目录、不执行安装器、不新增 REQUEST_INSTALL_PACKAGES 或存储权限。初始 URL 不允许查询串、片段、编码路径、目录穿越或外域。交给外部浏览器以后，网页/APK 的重定向、下载和 Android 安装由浏览器/系统管理；App 不能验证下载文件，也不会把展示的 SHA-256 文本声称为已验证。正式发布流程须验证公网 APK 终点和签名链。

## 验证

24 个新增 JVM JUnit 用例通过 Kotlin 2.3.10 CLI / JVM17 target / coroutines1.10.2 / OkHttp4.12.0：版本和最低支持边界、debug 隔离、无发布、schema/JSON/UTF-8/日期/路径恶意输入、重复点击、取消/迟到结果、Http Call取消、重定向和错误终点、Content-Type、超大/无长度响应。

Compose 屏幕/Android Lint/SDK 构建/模拟器和真实浏览器安装链需要主工程 CI；本机 JVM 通过不能替代这些阶段。无生产登录、订阅、更新清单写入或正式下载请求。
