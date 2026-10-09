# 第三方订阅导入：A 档方案与实施记录

日期：2026-10-05。只读代码基线：`df81bc9b48c8eed0683939c0e942aa8ae96877af`。

**状态：2026-10-05 用户明确要求“咱们支持解析第三方的配置”，已批准继续实施第三方来源支持。**
本轮已实现独立第三方下载路径、显式未知期限和手工来源 v2 存储，并根据真实响应补充受限 YAML 兼容。
真实响应解析、322 项定向 JVM 回归、候选 APK 构建和 Lint 已通过。Android staging、同签名升级/降级和真实 VPN 连通尚未完成，不能用历史发布或旧 CI 结果替代。

## 基线原因与本轮真实响应诊断

用户提交的二维码可以正常解码，其内容属于带查询参数的第三方 HTTPS 订阅链接。
最初的 B 档诊断只检查输入形状，未请求真实订阅端点；下表记录的是该阶段基线，不代表本轮已修改代码的行为。

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

本轮授权后，对用户二维码对应端点进行了内存内读取和脱敏检查，仅记录状态、大小、格式、协议计数与期限是否有效等汇总，不保存或输出订阅 URL、响应正文、节点地址、凭证或实际到期值。
诊断中原客户端请求形状返回 `403` / HTML，候选客户端请求形状返回 `200` / YAML。后者为 **20,791 字节、31 个 VLESS 节点**；`Subscription-Userinfo` 提供了有效且尚未到期的期限，未发现 `X-Subscription-Valid-Until`。
这证明该次响应包含 inline Clash YAML，不证明节点可连接或 APK 已成功导入。

元数据检查进一步定位到 10 个原先不接受的应用设置根键，以及 31 个节点上的显式 `encryption: none`。
所有 VLESS 节点的已检查选项为 TCP、TLS 和 Vision。修复后将新的真实响应仅经 stdin 送入实际编译的 `SafeProxyConfig`：完整解析接受 31 个节点，逐节点生成受控配置 31 次全部成功；没有用字段计数或内容类型代替解析验证。
诊断证据位于本地 `.build-output/external-import/probe-*-metadata.json`；原始输入与配置未作为测试 fixture 或发布附件。
最终静态解析回执为 `real-subscription-kotlin-validation.json`，只记录白名单汇总。投影使用合成解析地址，不解析实际节点域名、不启动隧道；该结果不能代替 Android 下载或真实 VPN 连通测试。

本机系统 DNS 返回了非公网的 fake-IP，独立公网 DNS 诊断用于取得上述响应；这属于诊断环境差异。
生产 `ManualSubscriptionTransport` 仍使用经过公网地址检查的系统解析结果，没有隐式公共 DNS 兜底或允许私网开关。Android 端 DNS 与真实下载仍需单独验收。

## 历史 B 档诊断修复

该阶段只修正文案：手工 URL 在下载前未通过当时的官方策略时，抛出无输入参数、固定消息的 `UnsupportedManualSubscriptionLink`，
界面显示“暂不支持此订阅链接”。服务器对已接受 URL 的拒绝仍走原服务器错误处理；当时没有放宽来源或存储规则。

本地 TDD：先运行 `ManualImportControllerTest`，15 项中新增用例失败 1 项，复现原来的笼统文案；
修改后运行 `ManualImportControllerTest`、`ManualSourceAuthorityTest` 和 `SingleQrDecoderTest`，共 50 项全部通过、零跳过。
新增覆盖三入口的第三方/query URL 拒绝、零下载/零提交且保留原来源与节点、扫码/文件官方 URL 成功及服务器拒绝分类。
`scripts/verify_source.py` 和 `git diff --check` 通过。证据在本地 `.build-output/qr-import-diagnosis/`。
这些是 B 档阶段的 JVM/源代码检查，不包含第三方成功导入。本轮真实 HTTP 诊断、后续第三方实现及其验证另行记录，不将这 50 项旧结果计作新功能验收。

## 已批准的首期功能

三个入口继续共用显式导入事务：扫码、文件读取和链接输入只暂存内容，点击“导入”后才下载、校验、提交；导入不自动启动 VPN。
已新增 `ManualSourceKind.ExternalRemote`，表示用户主动选择的手工第三方来源，不复用账户交付、官方 capability 或本地文件身份。

首期接受一条 HTTPS URL，允许真实订阅常用的路径与查询参数；下载结果必须是当前 `SafeProxyConfig` 能完整验证的 inline Clash YAML。
现有 `ss`、`vless`、`hysteria2`、`tuic` 仍只支持代码已经定义的选项子集。保持严格 UTF-8、64 KiB 解压后内容上限、128 个节点上限以及所有节点完整校验；任何不支持节点都不能被静默丢弃。

首期不解析 Base64 URI 列表、`ss://` 等单节点分享、`clash://` 等安装包装链接、VMess/Trojan 新协议，也不解析或执行 provider 引用及任意脚本。
不得按域名猜订阅协议、偷偷追加 `target=clash` 等参数、调用第三方转换服务或用内容类型/文件扩展名代替解析验证。
不支持的内容只返回固定分类错误，保留原来源与节点；需要扩大格式时另交具体投影与测试变更。
本轮新增以下兼容处理，限于不改变既有内核投影语义的输入默认项：

- `external-controller`、`find-process-mode`、`geo-auto-update`、`geox-url`、`keep-alive-idle`、`keep-alive-interval`、`ntp`、`rule-providers`、`sniffer`、`tun` 作为有界输入元数据接受并忽略。不会读取其中 URL、创建监听器、加载规则 provider、修改 TUN 或向内核转发这些设置。
- VLESS 的 `encryption` 缺省、空字符串和 `none` 与锁定 Mihomo 的默认行为一致；空字符串/`none` 在投影时移除。其他加密模式和错误类型仍拒绝，不能静默丢弃不支持的模式。

只从已验证的 inline `proxies` 生成受控的单节点 DTO 和固定规则；未知根键、未知节点选项及不支持节点仍拒绝整个导入。
64 KiB、128 节点、单 YAML 文档、最多 8 层嵌套和 8,192 个解析事件等边界保留；不放开重复键、锚点、别名或显式标签。

## 独立 URL 与下载边界

已增加独立的 `ManualSubscriptionUrlPolicy` 和 `ManualSubscriptionTransport`，只由显式手工第三方来源使用。
账户 `OriginPolicy.subscription()`、官方 `SubscriptionTransport`、Portal 会话、账户交付与 ACK 的现有规则不变。
旧 `ImportedRemote` 继续表示已有官方手工来源，不通过新策略扩大其能力。

首期 URL 规则：最多 8 KiB；只接受 HTTPS、有效主机、443 或默认端口；拒绝 userinfo、fragment、控制字符、反斜杠和解析歧义。
路径和 query 必须保留其有效字节语义，不能重排参数、解码后重新拼接、丢弃 token 或自动改写目标。
URL 全体按秘密处理；异常、日志、截图、UI 和测试回执不含 URL 或其路径/query。

下载使用独立无账户凭证客户端：不继承 Portal headers、CookieJar、Authenticator、HTTP cache、系统 HTTP 代理或分析拦截器，不携带 Referer。
用户在 URL query 中主动提供的订阅凭证仅发送到该次验证后的目标；不附加账户 token。
保留正常系统 TLS 信任和主机名验证，不增加信任所有证书选项。
生产请求使用固定、自描述的 `User-Agent: FeilianVPN/0.4 (Mihomo-compatible)` 和 YAML/plain-text `Accept`，声明本客户端支持的格式，不冒充其他产品。
服务端可能依据这些头决定响应格式或是否接受请求，因此不能假设更换请求头不影响响应；本客户端仍独立校验返回的完整内容，不按头部或扩展名直接信任。

首期不跟随任何重定向；3xx 返回独立错误，后续如需支持必须设计逐跳 URL、DNS、目标与凭证规则。
公网目标校验在实际建连路径执行：拒绝 localhost、回环、私网、链路本地、组播、未指定、保留范围及其 IPv4-mapped IPv6 变体。
域名解析结果逐个检查，混合公网/非公网结果整体拒绝；校验后的地址列表直接用于同一次连接，不能再独立解析造成检查与连接目标不一致。
客户端只连接已校验地址，仍按原主机执行 TLS/SNI。测试通过注入解析器/连接适配器验证边界，不给生产代码增加“允许私网”开关。

已配置 connect/read/call 的 15/20/30 秒超时，并以协程 30 秒总预算处理晚到回调。
成功响应读取流按解压后大小限流；错误响应正文不读取。拒绝或取消时先取消 call 再关闭响应，避免为复用连接而继续排空恶意正文；不能先完整读入再判定大小。
取消必须关闭当前请求/响应，擦除持有的可变字节。网络失败不重试为其他源、不使用缓存冒充新下载。

## 有效期与账户权益语义

实际类型为 `LocalSourceValidity.ExternalExpiry(until)` 与 `LocalSourceValidity.ExternalUnreported`，只用于 `ExternalRemote`，不借用 `LocalSourceValidity.LocalNoServerExpiry`。
缺少到期元数据的第三方订阅允许作为用户明确导入的来源；UI 显示“第三方订阅”和“未提供有效期”，不显示“永久有效”，也不从其头部推导官方套餐或账户剩余权益。
允许连接的依据是用户显式选中该来源、当次成功下载与完整配置校验，不能是伪造未来日期。
不得使用导入时间、文件时间、`Instant.MAX` 或账户到期日填补未知期限。

已实现解析响应 `Subscription-Userinfo` 中的 `expire` Unix 秒值，及独立解析 `X-Subscription-Valid-Until`。
单个相关头最多 1,024 字符，userinfo 最多 16 个字段，期限限定至公元 9999 年；重复头、重复字段、非法数字与格式错误拒绝，和字段缺失分别处理。
两个有效到期值同时存在时取更早者；明确已过期时拒绝导入/连接。`expire=0` 仅表示未提供可用期限，不推断永久有效。
首次没有有效到期值时保存显式未报告状态，对应 `ExternalUnreported`。同一确切 URL 曾记录真实期限后，后续缺失/零值不能把它升级为无限期：保留已知期限并继续到期拦截。
换 URL 不借用前一个来源期限；不从这些头推导官方账号身份或套餐。

### 订阅自报流量（Subscription-Userinfo upload/download/total）

更新：此前“首期不新增未验证的流量余额计算”的限制已由本规则取代。第三方（`ManualSubscriptionTransport`）与官方（`SubscriptionTransport`）下载共用同一个严格解析器 `SubscriptionUserinfoParser`，同时读取 `upload`、`download`、`total` 与 `expire`。

- **零值即未知**：三个计数器任一缺失、`total=0`（含全零 `upload=0; download=0; total=0`）一律视为“未知”（null），不显示为“剩余 0”。只有服务器报告的 `total > 0` 且已用量超过总量时才显示真实耗尽（剩余 0 GB）。
- **严格程度**：第三方下载对格式错误／重复头仍整体拒绝（与期限规则一致）；官方下载保持原有期限契约，用量头格式错误只视为未知，不影响导入。
- **按配置保存**：用量（附本地观测时间 `observedAt`）只保存在对应远程配置的加密 manual-source 记录中（记录格式 v4；无用量的记录仍写 v1/v2 原字节，旧记录读取后用量为 null）。本地文件导入永远没有用量。
- **写入时机**：仅在显式导入、显式刷新和连接/激活时的新鲜下载成功后写入；该次下载没有有效用量即写为未知。不从旧记录或其他 URL 继承；删除配置或清除全部时随记录一并清除；没有后台轮询。
- **展示**：未登录，或当前选中的是导入配置时，连接页显示“订阅提供 · 更新于 …”标注的剩余／已用／总量；没有用量时仍显示“暂未获取”。已登录且使用账户订阅时仍以 Portal 账户数据为准。
- **边界**：这些数值不进入 `AccountUsageController`、Portal `Resource`、套餐判断或连接准入；`toString` 均脱敏，不写日志。

English summary: subscription-reported usage is parsed from the source's own `Subscription-Userinfo`, stored per saved remote configuration, shown with a "subscription-provided" label when signed out or when an imported configuration is selected, treats missing/zero totals as unknown, and never feeds account, plan or admission logic.

官方账户与旧官方手工来源仍要求它们原有的有效期条件。
所有来源仍有有限的连接准备期限、一次性 activation 和运行期已知期限检查；第三方的资源有效期可为明确的 `ExternalUnreported`。
实现采用现有 manager 授权委托：类型在 snapshot/来源存储中明确区分，`AuthorizedConfig` 的一次性 activation 闭包继续校验真实来源类型、revision、内容摘要、epoch 与期限；gateway/服务仍传既有 nullable expiry（IPC 中为 0）。
没有新增 Binder 类型或把类型传播到 service，也没有放宽官方来源的未知期限接纳。服务已有有限 admission deadline 和一次性启动约束保持；回归验证官方缺期限拒绝、第三方明确未知期限通过，以及期限变化后旧准备对象失效、零文件写入。

## 存储兼容与原子提交方案

保持 `portal-profile.bin`、账户凭证、delivery receipt 的格式、key、AAD 和内容不变。
已扩展 `manual-source.bin` 的严格 codec 为可读 v1/v2；仅新的 `ExternalRemote` 编码为 v2，类型码为 4。
v2 额外保存明确的已知/未报告期限标记，并强制与 `validUntil` 是否存在一致；v1 拒绝第三方类型，v2 拒绝旧四类或未知类型，不因解码版本升级赋予旧来源更多权限。
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

截至本记录已确认的本轮结果：

- 第三方来源、下载边界、存储 codec、authority 与 UI 等定向 JVM 回归 **114 项全部通过**，证据为 `.build-output/external-import/green-focused-final.log` 与 `focused-green-xml/`。该轮通过发生在随后补充 YAML 兼容之前，不能代表包含解析器新改动的最终构建通过。
- `SafeProxyExternalCompatibilityTest` 的 TDD RED 为 **8 项中 3 项失败**，分别复现新增应用根设置、显式默认 VLESS encryption 及两者组合的拒绝；证据为 `red-parser.log` 与 `red-parser.xml`。
- 包含解析兼容、第三方来源及 core 启动/取消边界的新一轮 JVM GREEN 为 **320 项通过，零失败、零跳过**，证据为 `green-integration.log` 与 `integration-green-xml/`。
- 交叉审查后再补 2 项回归：持久失效来源缺失期限重导入不能清除已知期限，以及 `PortalRepository` 第三方入口不调用账户下载器/会话。候选构建 flags 下最终 **322 项通过，零失败、零跳过**，证据为 `green-candidate-final.log` 与 `candidate-green-xml/`。
- 真实响应通过实际 Kotlin 完整解析与全部 31 次节点投影，配置只在内存中处理；此项为解析验证，不是 Android HTTP 或 VPN 转发验收。
- 离线 `assembleDebug`、`lintDebug`、`assembleDebugAndroidTest` 通过；Lint 为 0 error/fatal、23 warning、6 hint。构建日志为 `candidate-build.log`，该次报告为 `candidate-lint.xml`。Android 的独立合成存储测试已补充 v2 已知/未知期限、v1 不改写、AtomicFile 各故障点、readback 不一致与显式转换回 v1，并已编译；编译不等于设备验证通过。
- 当前 ADB 无连接设备，也没有运行中的 Android 模拟器。未完成本轮 Android staging、同签名升级/降级或真实 VPN 连通；不得把真实 HTTP 200、JVM GREEN 或旧版 OPPO 结果写成这些阶段通过。

候选为 `com.gccwebos.android.candidate`、versionCode 6，开启 connection candidate 与 hostname preparation，关闭 connection/storage synthetic、native lab 和 native TUN acceptance。
本轮 native 源码未改；候选使用与基线源码相同且摘要经核对的 arm64-v8a/x86_64 库，不声称本机重新编译 native。
预备 APK 为本地 `.build-output/external-import/feilian-vpn-third-party-import-candidate.apk`；提交与包摘要、签名、ABI、开关及静态校验结果以该目录生成的 `candidate-static-validation.json` 为准。未发布新的 Release。

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
设备安装与 VPN 测试已有用户一般授权，不应重复索取该授权；新增第三方权限与存储语义的 A 档实现批准已由本轮用户指令给出。
独立测试仅使用合成或明确拥有的材料。不得为验证而复制现有普通应用的凭证、导出用户订阅、卸载普通应用或清除其数据。
新 APK 的构建、静态检查、设备测试和真实第三方响应验收分别记录，既有 CI/OPPO 结果不复用为新实现通过证据；B2 未解决问题也不随导入改动自动关闭。

## 回滚步骤

1. 实施前从上述基线准备同测试 appId、同签名且 Android 允许覆盖的基线 APK；记录源码、版本和摘要。不能靠卸载/清数据解决签名或 versionCode 不匹配。
2. 候选中先停止拥有的 VPN 并验证清理完成。若 v2 已采用，用户显式选择“移除导入配置”或“使用账户订阅”后，以新的 revision 原子写入可由旧版读取的 v1 `None` 或 `LegacyRemote`；不得自动恢复导入前来源。
3. readback 验证兼容 envelope、无 pending clear、无未完成清理，再覆盖安装基线。第三方功能不保留；旧版只能使用显式选定的兼容来源或保持无来源。
4. 若 candidate 无法启动/解密/完成转换，保留存储阻断并修复候选恢复入口；不要直接删除 sidecar 或绕过它回到旧账户缓存。未经转换直接降级的旧版应阻断，单独作为失败关闭测试。
5. 对完整清除，先完成现有跨 store 清除与 owner 清理并验证最终 tombstone，禁止从备份复活已清除资料。回滚验证只比较合成资料摘要，不读取客户内容。

## Operator 批准点

用户已批准的具体范围为：上述独立公网 HTTPS 手工来源、受限 inline YAML、第三方未知期限类型、手工 sidecar v2 兼容编码、对应自动测试/预备 APK/受控测试及同签名回滚。
批准后先完成代码与离线验证，再进入受控 staging；验收通过也不自动意味着发布或稳定版签名完成。
这不包含后台/生产部署、账户 origin 放宽、Portal auth 改造、生产节点改造、协议/JNI 扩展或新的外部转换服务。
实施批准已记录；实现 commit、构建产物和各阶段验收应在实际发生后补记，不能预填“已通过”。
