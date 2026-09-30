# 自动订阅导入：安全客户端切片与后端恢复合同

> 后续离线协议合同 v1 扩展了受限支持形态；当前准确边界见 [离线协议合同](offline-protocol-contract.md)。下文“仅 SS”是 PR #8 历史基线，不代表后续全协议互通或生产 VPN 已开放。

## 后续连接准备纠正

PR #8 的「新 session + pendingAck 一律需要恢复」只描述当时统一的保守导入路径，
不是 legacy 后端能力限制。新的[每次连接准备边界](fresh-connection-preparation.md)在
同一认证事务内读取现有 `/api/portal/commerce/model`，严格区分：

- `legacy`：每次重新调用 current/setup，取得当前权威地址与当前 session 的 handoff，
  ACK 后下载最新 YAML；不依赖历史 receipt、历史 request 列表或旧配置。Legacy ACK
  不消费材料，故本轮 receipt 可只存于尝试内存；不覆盖可能尚待恢复的 wallet receipt
- `wallet_first_unique_v1`：保留加密持久 URL，每次重新下载最新 YAML；同 session 的 lease/key/ACK
  重试合同保持不变。新 session 的 pending ACK、地址彻底丢失仍不能伪装成恢复成功
- 模型未知、查询失败或会话失效：停止准备，不猜测模型，不调用重新签发/轮换接口

查询模型复用已存在的认证接口，不新增后端字段或 endpoint。现有登录后的预览导入仍是
保守兼容性预览，不是生产连接路径。后端恢复 API 提案暂停；不作为普通 fresh download
的前置条件，也未获得实现/部署授权。生产 VPN、稳定线路身份和真实 TUN 验收仍未开放。

## 本次范围

客户端自动完成「登录 → 当前资源 → 持久交付 receipt → ACK → 下载 → 受限配置投影」。没有手填 URL、生产请求测试、购买、充值、签发、轮换、后台部署或 VPN 启动。

这不是完整生产 VPN，也不是对所有服务器配置的兼容声明。现有 `SafeProxyConfig` 与 native bridge 仍只支持静态 Shadowsocks AEAD / IPv4 literal；VLESS Reality、Hysteria2、TUIC、主机名、provider 配置明确阻断。输入中的 DNS、规则、分组、监听设置不能直接进入内核。

### 四个不同状态

1. **安全收到交付**：operation key 先落盘，URL / handoff / pendingAck 经现有 Keystore AES-GCM + AtomicFile 保存，然后 ACK，最后保存 acknowledged。ACK 保持原 M1 的「持久收到材料」语义；不意味着已导入、已支持或已连接。
2. **配置兼容性校验**：独立、无 Portal token 的 HTTPS client 下载原始字节；原样 UTF-8 校验、限长、禁 redirect、cookie 和 cache。严格投影失败会保留持久 URL，报告“不兼容”，不把原 YAML 交给内核。
3. **已自动导入**：仅验证通过的默认第 0 个代理被转成内部 `AuthorizedConfig`，置于进程内。所有输入代理仍需通过验证；不静默跳过不支持的线路。URL 保持加密持久化，进程重启后重新登录并下载、验证，而不是保存明文配置。
4. **连接可用**：本次永远不声称达到。生产 VPN 继续禁用，没有 TUN、系统 VPN permission 或真实流量验证；没有把生产订阅传入 Debug lab。

默认第 0 个代理只是本次导入选择，并非服务端推荐线路，也不具备稳定线路 ID 语义。`copyImportedConfig()` 返回独立副本，调用方必须 close；本次生产 UI 没有连接它。

## 客户端分支

| 权威资源与本地情况 | 本次行为 |
| --- | --- |
| none | 显示无已发放资源；不 setup、不下载，不自动购买 |
| reconciliation_required | 停止导入；保留旧 receipt 供恢复 |
| 到期 / 已知剩余为 0 | 不领取或下载；不把未知用量误认为 0 |
| available + 无本地 receipt + setupAvailable=true | 保存新 operation key 后领取、保存、ACK、下载、投影 |
| available + 无本地 receipt + setupAvailable=false | 等待交付提示；不能从 false 区分开通中、已交付、其他租约或 escrow 问题 |
| 同账号、同 resourceRef、已确认 URL | 可以重新下载，包括 setupAvailable=false 和重新登录后的新 session |
| 同账号、同 resourceRef、同 session 的未完成 key | 使用相同 key 重试 setup，即使 setupAvailable=false |
| 同账号、同 resourceRef、同 session 的 pendingAck | 原 receipt 重试 ACK，不再次领取，即使 setupAvailable=false |
| 新 session + pendingAck / 未获得 URL | recovery_required；不盲目换 key、重领或清除 receipt |
| resourceRef 改变 | 隔离旧 profile、保留 receipt；recovery_required，不推断续期等于新交付 |
| 成功登录另一个账号 | 清除旧账号材料与进程内 profile，然后再查询新账号 |
| 刷新失败 / 会话过期 / 403 / 410 / 配置不支持 | 旧 profile 不再暴露；保留交付材料，显示对应错误或兼容性状态 |
| 本地退出 | 取消旧 generation，擦除进程内配置并清除持久 receipt；不是服务器撤销 |

整个 refresh/import transaction 使用同一个 generation。退出不能在 overview 与 setup 之间被当成新一轮授权；挂起返回的旧字节不得发布。原始下载和投影 byte arrays 在成功、失败与取消时尽量清零，包括 `withContext(IO)` 取消时未交给 caller 的结果；JVM/解析器/HTTP 库内部不可控副本不承诺物理擦除。

## 已核实源码合同

审计基线：Android `1b7612872baef003ae7974678e0c40d3db37ab7c`；backend `2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf`；mobile `091ee6e82bb768333dcc23eaaf725c1fd07ba243`。源码分支不是当前线上部署证明，本轮未访问生产 API。

- [当前资源与路由](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/routes/user-portal.mjs)：GET `/api/portal/resources/current/overview`；POST `.../setup` body 恰好 `{idempotencyKey}`；POST `.../setup/ack` body 恰好 `{handoffRef}`，成功分别 200 与 204
- [legacy resource service](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/platform/portal-current-resource.mjs)：只重读已发放 URL，不签发/轮换；handoff 绑定 session 与资源；新 session 必须取得对应 handoff。客户端目前保守隔离跨 session 的未完成 receipt，没有绕过该约束
- [wallet-first claim/ack](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/platform/subscription-control-db.mjs#L4249-L4640)：10 分钟租约；同 session/key 可重读 leased setup；ACK 成功但响应丢失可以同 session/handoff 重试；delivered 后不再释放 URL
- [setup delivery adapter](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/platform/wallet-first-current-resource-setup-delivery.mjs#L536-L664)：跨 session ack 为 409；claim_lost 等内部故障可能归并为外部 503，不能据此发明客户端恢复成功
- [续期保留初始 handoff 测试](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/tests/wallet-first-setup-delivery-handoffs.test.mjs#L657-L687)：resourceRef 可变，交付可能未变。以前遇 ref 变化直接 clear 的行为在本次改为保留并阻断
- [订阅路由](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/routes/subscription.mjs#L125-L204)：artifact/tenant 独立授权；revoked/rotated 为 410，disabled/expired/node unavailable 为 403。Portal token 不发往订阅 origin
- [Clash renderer](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/clash-subscription.mjs)：完整 inline Clash YAML 可含 ss/vless/hysteria2/tuic；provider-only 形式另有路径，本次不接受 provider 下载
- [mobile subscription flow](https://github.com/gccltd/gccwebos-mobile/blob/091ee6e82bb768333dcc23eaaf725c1fd07ba243/src/data/subscription.mjs)：复制/二维码与用户确认不是 native durable importer，不原样移植其 setupAvailable UI gate

## 后端需另行批准的最小恢复合同（提案，尚未实现）

这里定义语义，不虚构已经存在的 endpoint，也不改变现有严格 DTO。需版本化能力协商，再由 Android 显式支持；不能直接向现有 overview 添加字段而让严格客户端解析失败。

### A. 稳定交付身份与连续性

服务端在已认证、已核验账户归属的响应中提供不含秘密的稳定 `deliveryId`、`resourceRef`、`deliveryRevision` 及明确 continuity。续期保持原 artifact 时 `deliveryId` 不变；轮换/替换必须增加 revision，并明确旧材料是否撤销。`resourceRef` 不再承担稳定交付 ID 的职责。客户端不能凭本地 ID 请求或推断其他账号的交付。

验收：续期改变 resourceRef 但保留 URL；真实换线路/轮换不能误用旧材料；账户切换不认领旧 ID；撤销后 download 与状态一致拒绝；并发 transition 不提供矛盾的 revision。

### B. 只读交付状态查询

绑定已认证账号，安全返回 `not_issued / claim_pending / receipt_acked / recovery_required / revoked` 等规范状态、稳定身份、可重试原因及可选 `retryAfter`。可查询旧 operation/receipt 的结果，但旧 handoff 自身不能替代身份验证，响应不回显 URL、令牌、节点凭据或另一个 session 的秘密。

验收：ACK 已提交但响应丢失；app 被杀再登录；租约未到期/已到期；租约被第二 session 替代；escrow cleanup 部分失败；未知 receipt 与跨账号 receipt 不泄漏是否存在。刷新错误不能转换成 `none` 或 `receipt_acked`。

### C. 明确授权的恢复 / 重新交付

单独设计同账户恢复，而不是让 setup、refresh 或租约重试悄悄执行轮换。需确定持有设备材料的重新绑定和材料彻底丢失的 re-delivery 两种不同风险；要求相应新鲜认证/必要二次验证、幂等 key、并发 CAS、审计与限流。不能把新 session 的旧 handoff 当作合法 ACK。

验收：delivered 后重装/换机且 URL 丢失；有 URL 但 ACK 结果未知；已撤销或转移的资源；两设备同时恢复；网络超时后相同 key 重试；恢复拒绝不删除客户端旧材料；成功恢复不重复购买/开通或生成第二份权益。是否复用或轮换凭据属于显式服务器策略与批准范围。

## 完整生产协议支持的后续门槛

1. 从 backend renderer 的全合成 fixtures 建立字段白名单和能力协商，覆盖 SS AEAD、VLESS Reality、Hysteria2、TUIC；核对当前 pinned Mihomo 与 native bridge 的确切支持，不直接透传 YAML
2. 针对 nested TLS/Reality、证书/验证开关、UDP、混合协议、主机名 bootstrap、IPv6、provider/path/URL 等逐项定义投影或明确拒绝；DNS 必须防止隧道外泄漏，不借本切片扩展系统网络权限
3. 服务端线路身份与客户端选择/切换、缓存时效、配置撤销/轮换及后台恢复合同一并测试
4. 使用获准隔离测试环境端到端验证内核、TUN、真实隧道流量、取消/重连/切换/失败清理，再单独决定生产 VPN 开关；不能把“导入完成”当连接成功

## 本次测试与剩余验证

本地 JVM 离线用例覆盖：无套餐、对账、到期、耗尽/未知额度、首次导入、setup 丢响应同 key、ACK 丢响应同 receipt、setupAvailable=false、同账号重新登录/进程重启、跨 session pending、resourceRef 改变保留、账号隔离、失败刷新、撤销、未知协议/非法 UTF-8、下载取消/退出竞争、并发刷新和 commerce 401。新增 4 个 Android controller→Compose 离线 instrumentation 用例覆盖 no-plan、支持、不支持、下载失败状态。

实际结果以该 PR 精确提交的 CI 为准。历史 main 的 API36 runner 有启动 Launcher3/SystemUI ANR 记录，不能把构建前环境失败说成测试通过。API33 native lane 仍可补充实际运行证据，不改变 compileSdk/targetSdk36，也不能替代未通过的 API36 覆盖。

未验证：生产帐号/后台交付、真实协议组合、真机、正式签名、真实 VPN、服务端恢复 API。本文与实现均不承诺这些已完成。
