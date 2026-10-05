# 第三方订阅导入：A 档准备与待批准方案

日期：2026-10-05。只读代码基线：`df81bc9b48c8eed0683939c0e942aa8ae96877af`。

**状态：准备方案，等待 operator 批准第三方来源权限与存储扩展后实施。第三方订阅当前尚不支持。**
本文件不改变现有权限边界，不代表 APK、迁移、真实订阅下载或 VPN 连通验收已经完成。
本轮可独立完成的 B 档修复是准确区分“不支持的订阅链接”等错误，不能把错误文案修正称为第三方导入已修好。

## 已证实的原因与证据边界

用户提交的二维码可以正常解码，其内容属于带查询参数的第三方 HTTPS 订阅链接。
本地只检查了输入形状，没有请求真实订阅端点，没有保存或输出解码内容。
因此，当前不能判断服务器响应格式、协议、到期元数据、重定向行为或连接可用性。

| 代码位置（基线） | 已验证行为 |
| --- | --- |
| `portal/LocalSubscription.kt:303-305` | 手工输入中的 URI 一律进入官方 `policy.subscription()`；不符合规则时，在下载前拒绝。 |
| `portal/PortalModels.kt:78-85` | 仅接受官方订阅 origin、HTTPS、无 query/fragment 及 `/sub/a/<capability>[.yaml]` 路径。 |
| `portal/ManualImportController.kt:98-102` | 原实现把这类拒绝折叠成“配置不可用”，容易被误认为二维码或 YAML 解析失败。 |
| `portal/PortalTransport.kt:57-64,123-142` | 下载时重复校验官方 URL，禁用重定向，只读取 `X-Subscription-Valid-Until` 作为有效期。 |
| `portal/ManualSourceStore.kt:44-46,90-96` | 已存手工远程来源也要求官方路径形状、无 query 和非空有效期。 |
| `portal/LocalSubscription.kt:184,503-507` | 恢复来源时再次通过官方 capability 校验。 |
| `vpn/SafeProxyConfig.kt:124-172` | 只支持有 inline `proxies` 的受限配置以及现有四种协议投影，不能视为通用代理配置引擎。 |

表内路径位于 `app/src/main/java/com/gccwebos/android/`。行号属于上述基线，后续文案修复可能使其移动。
既有 [手工导入计划](manual-config-import-plan.md) 明确限定官方链接；
[历史实测](manual-import-validation.md) 的扫码网络结果来自合成输入与注入下载器，不证明第三方 HTTP 或真实隧道可用。

## 已完成的 B 档诊断修复

手工 URL 在下载前未通过现有策略时，抛出无输入参数、固定消息的 `UnsupportedManualSubscriptionLink`，
界面显示“暂不支持此订阅链接”。服务器对已接受 URL 的拒绝仍走原服务器错误处理；没有放宽任何来源或存储规则。

本地 TDD：先运行 `ManualImportControllerTest`，15 项中新增用例失败 1 项，复现原来的笼统文案；
修改后运行 `ManualImportControllerTest`、`ManualSourceAuthorityTest` 和 `SingleQrDecoderTest`，共 50 项全部通过、零跳过。
新增覆盖三入口的第三方/query URL 拒绝、零下载/零提交且保留原来源与节点、扫码/文件官方 URL 成功及服务器拒绝分类。
`scripts/verify_source.py` 和 `git diff --check` 通过。证据在本地 `.build-output/qr-import-diagnosis/`。
这些是当前工作树的 JVM/源代码检查；未构建安装新 APK，未运行设备、实际订阅 HTTP 或 VPN 测试，也未发布新的 Release。

## 待批准的首期功能

三个入口继续共用显式导入事务：扫码、文件读取和链接输入只暂存内容，点击“导入”后才下载、校验、提交；导入不自动启动 VPN。
新增的 `ExternalRemote` 是用户主动选择的手工第三方来源，不能伪装成账户交付、官方 capability 或本地文件。

首期接受一条 HTTPS URL，允许真实订阅常用的路径与查询参数；下载结果必须是当前 `SafeProxyConfig` 能完整验证的 inline Clash YAML。
现有 `ss`、`vless`、`hysteria2`、`tuic` 仍只支持代码已经定义的选项子集。保持严格 UTF-8、64 KiB 解压后内容上限、128 个节点上限以及所有节点完整校验；任何不支持节点都不能被静默丢弃。

首期不解析 Base64 URI 列表、`ss://` 等单节点分享、`clash://` 等安装包装链接、VMess/Trojan 新协议、provider 引用或任意脚本。
不得按域名猜订阅协议、偷偷追加 `target=clash` 等参数、调用第三方转换服务或用内容类型/文件扩展名代替解析验证。
不支持的内容只返回固定分类错误，保留原来源与节点；需要扩大格式时另交具体投影与测试变更。
在取到并安全检查真实响应以前，不保证该用户订阅属于首期格式。

## 独立 URL 与下载边界

增加独立的 `ManualSubscriptionUrlPolicy` 和 `ManualSubscriptionTransport`，只由显式手工第三方来源使用。
账户 `OriginPolicy.subscription()`、官方 `SubscriptionTransport`、Portal 会话、账户交付与 ACK 的现有规则不变。
旧 `ImportedRemote` 继续表示已有官方手工来源，不通过新策略扩大其能力。

首期 URL 规则：最多 8 KiB；只接受 HTTPS、有效主机、443 或默认端口；拒绝 userinfo、fragment、控制字符、反斜杠和解析歧义。
路径和 query 必须保留其有效字节语义，不能重排参数、解码后重新拼接、丢弃 token 或自动改写目标。
URL 全体按秘密处理；异常、日志、截图、UI 和测试回执不含 URL 或其路径/query。

下载使用独立无账户凭证客户端：不继承 Portal headers、CookieJar、Authenticator、HTTP cache、系统 HTTP 代理或分析拦截器，不携带 Referer。
用户在 URL query 中主动提供的订阅凭证仅发送到该次验证后的目标；不附加账户 token。
保留正常系统 TLS 信任和主机名验证，不增加信任所有证书选项。固定非秘密 User-Agent/Accept，不能冒充另一客户端以隐式改变响应协议。

首期不跟随任何重定向；3xx 返回独立错误，后续如需支持必须设计逐跳 URL、DNS、目标与凭证规则。
公网目标校验在实际建连路径执行：拒绝 localhost、回环、私网、链路本地、组播、未指定、保留范围及其 IPv4-mapped IPv6 变体。
域名解析结果逐个检查，混合公网/非公网结果整体拒绝；校验后的地址列表直接用于同一次连接，不能再独立解析造成检查与连接目标不一致。
客户端只连接已校验地址，仍按原主机执行 TLS/SNI。测试通过注入解析器/连接适配器验证边界，不给生产代码增加“允许私网”开关。

保留有限 connect/read/总请求超时；首期可沿用现有 15/20/30 秒预算。
读取流按解压后大小限流，错误响应也有上限；到达上限立即关闭，不能先完整读入再判定。
取消必须关闭当前请求/响应，擦除持有的可变字节。网络失败不重试为其他源、不使用缓存冒充新下载。

## 有效期与账户权益语义

新增有区分度的 `ExternalRemoteValidity.KnownExpiry` 与 `ExternalRemoteValidity.Unreported`，不能借用 `LocalNoServerExpiry`。
缺少到期元数据的第三方订阅允许作为用户明确导入的来源；UI 只表达“未提供到期时间”，不能显示“永久有效”、官方套餐或账户剩余权益。
允许连接的依据是用户显式选中该来源、当次成功下载与完整配置校验，不能是伪造未来日期。
不得使用导入时间、文件时间、`Instant.MAX` 或账户到期日填补未知期限。

拟支持的到期元数据为响应 `Subscription-Userinfo` 中的 `expire` Unix 秒值，及独立解析的 `X-Subscription-Valid-Until`。
这里只是解析方案，不声称用户端点会返回这些头。限制头长度、字段数量和数字范围；重复或冲突字段拒绝，格式错误和字段缺失分开处理。
两个有效到期值同时存在时取更早者；明确已过期时拒绝导入/连接。`expire=0` 仅表示未提供可用期限，不推断永久有效。
首次没有有效到期值时保存 `Unreported`。同一确切 URL 曾记录真实期限后，后续缺失/零值不能把它升级为无限期：保留已知期限并继续到期拦截。
换 URL 不借用前一个来源期限；不从这些头推导官方账号身份或套餐。首期不新增未验证的流量余额计算。

官方账户与旧官方手工来源仍要求它们原有的有效期条件。
所有来源仍有有限的连接准备期限、一次性 activation 和运行期已知期限检查；只有资源有效期字段可为明确的 `Unreported`。
实现前用 contract tests 验证这一类型可跨 gateway、prepared artifact、service admission 传递；如现有边界无法区分未知来源类型，停止该实现并修订本方案，不能放宽所有远程来源。

## 存储兼容与原子提交方案

保持 `portal-profile.bin`、账户凭证、delivery receipt 的格式、key、AAD 和内容不变。
扩展现有 `manual-source.bin` 的严格 codec 为可读 v1/v2；新的 `ExternalRemote` 和有效期来源类型只编码为 v2。
继续使用现有独立手工存储 key/AAD 与 `AtomicFile`，版本号位于已认证密文内；不新增独立 selector 文件或明文 URL 索引。
key/AAD 字符串中现有的 `v1` 是既定加密域标识，不因增加内层 codec v2 擅自更名或迁移密钥。

v2 整体 envelope 保存来源类型、随机 revision、确切秘密 URL、受限配置、内容摘要、明确有效期类型、观察到的失效状态和 clearPending。
不存原始响应头、HTTP 日志或下载历史。保持现有总 envelope 上限；尺寸超限应明确失败。
新版本读取既有 v1 不主动改写；可用 v1 表达的原四种来源继续写 v1，避免无意义迁移。仅在成功显式采用第三方来源时原子写入 v2。

下载/解析在提交前暂存，提交时重新检查输入 ticket、operation epoch、clear generation、源 revision 以及真实 connection owner 清理完成状态。
完整写入、sync、readback 一致后才发布新节点和选择；失败时阻断新的 admission，不能把部分写入或旧节点当成功。
恢复 v2 时走独立第三方策略；禁止把 v1 旧官方来源升级为通用 URL 权限。
未知版本、损坏、丢失 key、异常缺失或 clearPending 均阻断连接，不退回账户来源。

旧 APK 对内层 v2 的严格版本检查预期失败并进入存储阻断；这必须在实际同签名降级测试中验证，不能只凭源码宣称可安全回滚。
不能同时维护一份可被旧 APK 隐式采用的旧 selector，也不能恢复过期备份绕过用户清除或来源撤销。

## 保留的所有者与取消约束

使用现有单 coordinator/ticket、真实 connection owner fence、mutex/epoch 和一次性 `AuthorizedConfig`。
每次第三方 Connect/Reconnect/Switch 都重新下载、验证全部节点，再以源 revision、URL identity、配置摘要、schema、clear generation 和 opaque selection 绑定准备材料。
下载失败、源切换或权限失效不使用缓存、其他手工来源或账户订阅兜底；A→B→A 不能复活旧选择或准备对象。

延迟探测只能读取当前已验证来源，并随来源变更失效，不能授予连接权限。
退出导入、页面销毁、清除、并发来源替换或 owner 改变后的晚到扫码/文件/HTTP 结果必须丢弃；不得在等待网络时长期持有 authority lock。
停止、清理失败锁存、私有 prepared 文件原子替换、物理网络解析、socket/TUN 生命周期及断线停止规则全部保留。
数组由明确所有者擦除；不承诺擦除 JVM 不可变字符串或平台内部副本。

## 批准后的验证与预备 APK

| 阶段 | 必须取得的证据 |
| --- | --- |
| TDD RED | 合成第三方 HTTPS query 从 Scan/Link/URL 文件到 manager 的失败，官方来源旧约束仍通过；当前 bug 不使用真实订阅作为 fixture。 |
| 策略/下载 GREEN | 原 URL/query 不泄露不变义；公网/私网/IPv6/DNS 重绑定与混合结果；3xx 不跟随；无账户 headers/cookies/代理继承；TLS 错误、超时、取消、大小边界、畸形 UTF-8。 |
| 格式与期限 | 四协议现有子集完整校验；不支持协议/混合节点/URI/Base64/provider 拒绝；期限缺失、零、过期、冲突、重复、溢出及已知→缺失转换。 |
| 存储与 authority | v1 保持、v2 round-trip、未知版本、每个 AtomicFile 故障点、readback 失败；清除/断开/晚回调并发；A→B→A 与一次性材料；失效时零 fallback、零自动 VPN。 |
| Android 实测 | API 33/36 独立测试包中真实扫码合成 QR、SAF 文件、链接导入、冷启动恢复与取消；受控 HTTPS 测试端点另验真实下载。不能把注入 downloader 当 HTTP 验收。 |
| 连接 | 用户已授权测试环境中的受控节点上确认流量与清理；未取得证据前，只报告导入/节点可用，不宣称该用户真实订阅已连通。 |
| APK 与回滚 | 精确提交、构建 flags、appId、versionCode、签名、APK/native SHA-256；同签名升级/回退、旧 v1 合成资料保留与不隐式采用来源。 |

先构建独立测试 appId 的基线与候选 APK，记录上述身份；普通候选 APK 必须关闭 lab/TUN/synthetic 注入开关。
设备安装与 VPN 测试已有用户一般授权，不应重复索取该授权；本文件等待的是新增第三方权限与存储语义这一 A 档实现批准。
独立测试仅使用合成或明确拥有的材料。不得为验证而复制现有普通应用的凭证、导出用户订阅、卸载普通应用或清除其数据。
新 APK 的构建、静态检查、设备测试和真实第三方响应验收分别记录，既有 CI/OPPO 结果不复用为新实现通过证据；B2 未解决问题也不随导入改动自动关闭。

## 回滚步骤

1. 实施前从上述基线准备同测试 appId、同签名且 Android 允许覆盖的基线 APK；记录源码、版本和摘要。不能靠卸载/清数据解决签名或 versionCode 不匹配。
2. 候选中先停止拥有的 VPN 并验证清理完成。若 v2 已采用，用户显式选择“移除导入配置”或“使用账户订阅”后，以新的 revision 原子写入可由旧版读取的 v1 `None` 或 `LegacyRemote`；不得自动恢复导入前来源。
3. readback 验证兼容 envelope、无 pending clear、无未完成清理，再覆盖安装基线。第三方功能不保留；旧版只能使用显式选定的兼容来源或保持无来源。
4. 若 candidate 无法启动/解密/完成转换，保留存储阻断并修复候选恢复入口；不要直接删除 sidecar 或绕过它回到旧账户缓存。未经转换直接降级的旧版应阻断，单独作为失败关闭测试。
5. 对完整清除，先完成现有跨 store 清除与 owner 清理并验证最终 tombstone，禁止从备份复活已清除资料。回滚验证只比较合成资料摘要，不读取客户内容。

## Operator 批准点

待批准的具体范围为：上述独立公网 HTTPS 手工来源、受限 inline YAML、第三方未知期限类型、手工 sidecar v2 兼容编码、对应自动测试/预备 APK/受控测试及同签名回滚。
批准后先完成代码与离线验证，再进入受控 staging；验收通过也不自动意味着发布或稳定版签名完成。
这不包含后台/生产部署、账户 origin 放宽、Portal auth 改造、生产节点改造、协议/JNI 扩展或新的外部转换服务。
本文件到此仅是可审批方案；批准记录、实现 commit、构建产物和各阶段验收应在实际发生后补记，不能预填“已通过”。
