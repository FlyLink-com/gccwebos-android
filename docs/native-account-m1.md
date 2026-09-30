# M1 原生账户与自动交付

## 已实现

- 密码登录前展示服务端人机验证题，支持 TOTP 二次验证；挑战到期后重新登录
- legacy `X-Portal-Session`，没有 Cookie/browser context/Bearer 混用；会话仅在内存，按服务端 expiresAt 和单调时钟共同到期；没有虚构 refresh/logout API
- 严格 Current Resource DTO，区分 none／待对账／pending／实测零／stale；字节用 Long，零额度不显示为无限
- 登录后自动查询资源；服务器返回可领取的已发放资源后自动 setup，无需用户导入 URL
- 加密保存同一 operationKey 后请求 setup；保存 URL、handoff 和 pendingAck 完成后才 ack；失败重试同键、同 receipt
- Android Keystore AES-GCM、noBackupFilesDir、AtomicFile 备份恢复、显式 fsync、解密读回与大小限制；不保存原始 session bearer，仅保存 SHA-256 绑定
- 明确 API 与订阅 HTTPS origins；不跟随重定向，不使用 cookie/cache/body logger；订阅 client 不接收 Portal token
- 中文账户页面的验证／加载／错误／刷新／本地退出与取消 TOTP；官网更新功能见 [更新说明](official-updates.md)

## 源码依据与环境

后台合同基线为 `gccwebos-com/proxy-gateway-minimal@2dea45fcbac1e330c76678b49124e0fcb1f630ac`，mobile 为 `gccltd/gccwebos-mobile@091ee6e82bb768333dcc23eaaf725c1fd07ba243`。

- API origin `https://portal.gccwebos.com`：后台 `docs/PORTAL_STATIC_PRODUCTION_RELEASE_RUNBOOK.md` 与 `docs/GITHUB_DEPLOYMENT_RUNBOOK.md`
- Subscription origin `https://sub.gccwebos.com`：后台 `docs/PRODUCTION_EDGE_FLEET.md` 与 `ops/nginx/proxy-gateway-public.conf`

这些是源码中的生产地址，不是本轮连通性验收。创建客户端和启动 App 不发生产请求；仅用户明确点击获取验证题／登录／刷新才访问。自动测试只使用 `.invalid` 合成材料与模拟 transport，没有生产账号、实际订阅、节点或支付操作。

## 必须保留的限制

- 会话不跨进程保存。进程死亡后重新登录；若有未 ack 交付，它仍绑定旧会话，显示待恢复，不盲目换键或清除材料领取。后台尚无通用 native renew、logout/revoke 和 ack 后丢失恢复合同，这是正式 beta 的阻塞项
- 已 ack 材料仅限相同账号和 resourceRef 重新使用；新账号成功登录立即清除旧账号材料。本地退出删除材料，明确不等于服务端撤销，可能需要服务端重新交付支持才能恢复
- 当前仅安全接收订阅 URL。下载客户端返回不透明 Clash/Mihomo YAML，尚未解析或应用；没有 sing-box JSON 适配、没有运行内核、没有 TUN。真实 VPN 按钮仍禁用
- 购买／钱包／订单尚未接入。本切片保持「服务端核验并发放资源→自动领取」方向，不让客户端把付款回跳当权益凭证
- GPL 内核与分发源码/许可告知方案待确定；没有把 Mihomo 或 sing-box 下载嵌入本次包
- 生产 collector/billing 恢复和获准测试线路是独立门槛；不因原生测试通过而解除

## 验证

本地 JDK21 + Kotlin CLI2.3.10/JUnit4.13.2：101 项通过（原协调器53、账户交付24、更新24）。这是真实 Kotlin/JUnit 运行，但不替代 Android Gradle/SDK 编译。

新增3项 Android instrumentation：Keystore 加密往返与退出清理、AtomicFile `.bak` 崩溃恢复、超大记录不覆盖旧值。原2项 UI 测试保留。CI 将执行 Debug 编译、全部 JUnit、Lint、测试 APK 编译、模拟器安装和 instrumentation；以精确提交的运行结果为准。未运行真机/生产端到端/VPN。
