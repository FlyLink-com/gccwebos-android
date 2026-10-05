# 节点传输延迟与失败重试

日期：2026-10-05。风险 B 档，基线 `00e949e`。本轮修改只读节点探测、结果展示和缓存重试，不改变配置存储格式、认证模型、连接授权或服务器配置。

## 问题与证据边界

用户在自行安装配置库普通 candidate 后反馈：节点测速没有数值，本轮实机 UI 也已观察到所有节点无数值。旧实现仅发送主机 ICMP Ping，未测量、无响应和不可用均显示 `— ms`。既往独立诊断曾记录六个有效目标均返回 `NoReply`，但未确定是总 deadline、ICMP 等待还是其他环节；不能据此认定服务端禁 Ping、网络阻断或节点不能连接。历史记录见 [静默导航与节点显示](quiet-navigation-and-node-display.md)，其计数不并入本轮；本轮没有重新安装临时 ICMP 诊断包，未取得新的失败原因分类证据。

本轮以配置中的实际端口选择传输探测，并区分简短失败状态。数值只说明手机到目标端点的传输往返时间；不证明代理认证、TLS 验证、网站请求或 VPN 转发成功。

## 探测设计

| 节点协议 | 探测路径 | 成功条件 |
| --- | --- | --- |
| Shadowsocks、VLESS | 配置中的 TCP 端口 | 公开 `SocketChannel.connect` 完成，或连接就绪事件后 `finishConnect` 成功。拒绝连接不生成毫秒值。 |
| TUIC、未混淆 Hysteria2 | 配置中的 UDP 端口，QUIC Version Negotiation | 发送 1200 字节保留版本请求，收到完整 VN 回包；版本字段、版本列表及本次随机双连接 ID 的反向对应全部匹配。任意 UDP 回包不算成功。 |
| 启用混淆的 Hysteria2 | 原有主机 ICMP Ping | 保留随机 nonce、echo 类型、序号与 payload 校验；不向混淆端口发送明文 QUIC 请求。 |

QUIC 请求和回包结构依据 [RFC 9000 §6.3](https://www.rfc-editor.org/rfc/rfc9000.html#section-6.3) 与 [§17.2.1](https://www.rfc-editor.org/rfc/rfc9000.html#section-17.2.1)。该交换不发送订阅凭证，也不完成代理握手。

- TCP 连接等待和 QUIC 回包等待上限各为 1500 ms；包含网络租约及 DNS 的单次传输探测外层上限 3500 ms，控制器仍保留 4000 ms 上限。并发最多 4 项，取消后的旧批次也共享此限制。
- DNS 和 socket 均绑定独立的当前物理网络租约，不回退到默认或 VPN 路由。只取经物理网络解析的 IPv4；API 30 以下不启用这条物理网络探测路径。
- 目标从整份通过校验的配置中提取，携带 opaque selection、主机、端口、协议和探测类型。探测前后均重新核验源内容及目标；显示缓存不能生成连接权限。
- 每次发布前复核账户代次、批次、前台状态、物理网络和配置来源。网络变化、来源变更和取消后的迟到结果不得显示旧毫秒值。
- socket、租约和临时数据各自按所有权在 `finally` 释放；轮询最长 75 ms 后再次检查取消。

实现入口为 `AndroidNodeLatencyProbe`，目标提取在 `SafeProxyConfig.probeEndpoints` 与 `LocalSubscriptionManager`，调度和缓存仍由 `NodeLatencyController` 管理。

## 展示与重试

保持闪电图标和原有触区，不新增按钮或长说明。成功显示 `N ms`，无回应显示“超时”，不可用显示“不可测”，未测量显示 `— ms`；徽标组件对网络变化状态使用“网络变化”。控制器仍拒绝发布失去网络归属的结果，该徽标映射不放宽网络门禁。无障碍标签使用“节点延迟”，不再把 TCP／QUIC 结果标为 Ping。

| 结果 | 内存有效期 | 手动重测限频 |
| --- | --- | --- |
| 成功 | 120 秒，保持原行为 | 30 秒，保持原行为 |
| 无回应或不可用 | 10 秒；到期后可由页面自动重测 | 从失败完成时起 2 秒防抖，之后闪电可重新测量 |

页面每秒检查只清理内存中的过期观察。完整成功或失败缓存尚有效时，不重复读取配置或发起探测；失败的短重试不会使同批成功节点提前失效。测速不更改所选节点、不下载订阅、不启动 VPN。

## 本轮验证记录

当前结果逐项记录；未完成项保持开放。本文件不将历史环回、合成 UI 或配置库测试视为本轮通过。

| 验证范围 | 当前记录 |
| --- | --- |
| JVM：QUIC 包格式、完整端点提取、端口／协议／transport 绑定、TTL 与失败重试 | 80 项定向 JVM 测试通过，0 失败 / 0 跳过。 |
| Android：实际应用 TCP／UDP 自有环回、错误回包拒绝、关闭／取消／网络变化 | OPPO 9 项 TCP／QUIC 与 5 项 ICMP 测试通过，共 14 项。 |
| 节点 UI：短状态、无障碍、闪电重测及既有布局 | OPPO 延迟 UI、配置库 UI／流程共 19 项通过。 |
| 最终构建、Lint、源码与 APK 哈希 | 普通候选和隔离包构建通过；Lint 0 error / 23 warning / 6 hint；source verifier 与 diff 检查通过。普通 APK 同签名覆盖安装且设备字节哈希核对一致，详见下文。 |
| 普通 OPPO 应用的真实节点数值 | 6 条节点实测：VLESS、Hysteria2、TUIC 各 1 条显示数值，另外 3 条出现超时；VPN 连接状态下也观察到更新后的数值。 |

生产源码入口已切换到 `AndroidNodeLatencyProbe`，`MainActivity` 不含临时 `GccLatencyDiag` 日志；构建和合成验收不等于普通 APK 已安装或真实 RTT 验收。最终普通包与隔离测试包分别记录哈希、安装和结果，不输出账号、订阅链接、配置正文或目标地址。

### Windows 全量测试的基线限制

初轮全量 JVM 执行 1111 项，9 项失败：其中新增 DTO 反射测试误把 Compose 生成的静态 `$stable` 字段算作实例字段，已修正并纳入上述定向绿色结果。其余 8 项为既有 Windows POSIX 初始文件权限限制。本轮将 `00e949e` 纯 Git archive 放入独立目录重新执行 `LocalSubscriptionTest` 和 `SubscriptionImportTest`，59 项中同样 8 项失败，名称逐项一致；`Files.createTempFile` 的 `posix:permissions` 异常有 XML 堆栈证据。相关存储实现和这两份测试对基线均无修改，没有放宽文件权限来让 Windows 测试变绿。不能将本轮全量测试记为通过。证据在 `.local-tests/baseline-00e949e/baseline-evidence.json`；当前测试日志在 `.build-output/node-latency/`。

### OPPO 隔离测试的前置网络状态

首批隔离测试曾失败。固定枚举定位样本确认租约已取得，绑定物理网络 socket 返回 `SocketException / errno=1（EPERM）`，结果为 `Unavailable`；当时普通 candidate 的 VPN 已连接。隔离 fixture 使用另一 UID，可见物理网络不等于允许绕过 VPN；此样本不解释所有旧 ICMP 失败，也不证明节点对端无回应。第一次 UI 断开操作后未证实 VPN 已停止，该失败批次保留为无效前置状态记录。随后停止并重新打开普通 candidate，确认连接页不再显示已连接，再复测 TCP 单项和完整 14 项，均通过。未清数据、未卸载普通应用，未修改 `allowBypass` 或共享网络租约门禁。

最后通过批次为 `oppo-ui.log`（19 项）和 `oppo-probes-final.log`（14 项）；早期失败、前台规则补充、20 秒测试期限及 UDP responder 清理过程均独立保留，不累加重复测试。普通应用所属 UID 下的真实节点及 VPN 开启时结果另行记录。

### 普通应用安装和真实节点

已同签名覆盖安装 `70bac49d40cd9ad0540624d0b40b8a8c8df10223`，APK SHA-256 为 `b23a10ffcd6ab205b8ce935be99e1956f2761e10a7aa568a2e9abfa3ddd0425a`，安装后读取的 APK 字节与交付包一致。交付路径 `D:\Downloads\feilian-vpn-node-latency-70bac49.apk`。未卸载普通应用、未清登录或配置；合成/lab 开关全部关闭，原生库哈希保持不变，签名与 16 KiB 对齐已核验。

普通节点页实测得到 VLESS / Hysteria2 / TUIC 各一条有效数值，初次快照为 120 / 161 / 120 ms，闪电重测后数值改变。其余三条本轮多次超时，不推断对端禁 Ping或不可连接。之后连接页确认“已连接”、`:vpn` 进程存在，再进入节点页并重测，观察到更新后的 167 / 122 / 120 ms；这些只属于端点传输探测，不计作真实 VPN 转发或完整代理认证验收。连接状态切换过程中曾短暂显示不可测，后续自动重试恢复数值。

本次自有 fixture app/test 已卸载并核对不存在，普通 candidate 保留。展示设置的最终读数与测试前相同。最终逐项结果见 [验收摘要](evidence/node-latency/2026-10-05/validation.json)，原始本地构建与合成日志位于 `.build-output/node-latency/`；未保存节点名称、地址、订阅链接或账号信息。
