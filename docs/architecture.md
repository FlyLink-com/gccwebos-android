> 本轮已实现的协调器语义、取消/资源清理边界及与原方案的差异，以 [connection-coordinator.md](connection-coordinator.md) 为准。此页保留整体目标；VpnService、热切换、网络事件和业务 API 尚未实现。

# 架构与状态

## 数据流

```text
Compose 页面 → ViewModel → Repository → 控制面 API
                            ↓
                       权益/线路/配置
                            ↓
连接协调器 → VpnService → 协议内核 → 获准节点
```

商城 API 不承载用户隧道流量。账户令牌和配置凭据不得进入分析事件、崩溃日志、通知文本、剪贴板或普通持久化偏好。

## 连接状态机

`Disconnected → CheckingAccount → CheckingEntitlement → FetchingConfig → AwaitingVpnPermission → StartingTunnel → VerifyingTraffic → Connected`。

`Connected → Reconnecting → VerifyingTraffic → Connected`；主动断开和服务停止均进入 `Disconnected`；失败进入 `Failed(reason, retryable)`。业务阻断作为独立原因：`Unauthenticated`、`NoPlan`、`Provisioning`、`Expired`、`QuotaExhausted`、`NoAuthorizedLine`。网络／设备失败原因：`PermissionDenied`、`ConfigUnavailable`、`CoreStartFailed`、`HandshakeFailed`、`TrafficCheckFailed`、`NetworkLost`。

断开可从任何连接阶段触发，并取消旧任务；重入连接使用单一协调器串行化。切换线路先验证新线路可用，再按内核能力安全切换或停止旧隧道后启动新隧道。进程重建后从系统 VPN 状态及服务端权益重新恢复，不能只读页面缓存。

## Android 实现边界

- Activity 请求 `VpnService.prepare()` 授权；服务负责 TUN、前台通知、生命周期与网络变化监听。
- 内核适配层提供 `start(config, tunFd)`、`stop()`、`health()` 和事件流；适配层必须处理保护外连 socket、防止流量回环，以及资源释放。
- 首版先选一个经过验证的内核与最小协议集合。用测试节点核对 SS、VLESS、Hysteria2、TUIC 各自配置能力；不能因后台能导出格式就声明 App 已支持。
- 自动线路选择只在服务端授权集合内按维护状态与测得结果选择；不把节点管理信息展示给用户。
- 敏感会话放 Android Keystore 支持的安全存储方案；退出撤销本地凭据并调用服务端注销／撤销合同。后台未提供的撤销能力列为阻塞项。

## 购买和推荐

商品列表只读服务端可售目录与报价；购买请求使用幂等键，随后轮询／刷新商品订单、资源交付和权益状态。充值订单单独展示。推荐链接通过验证的 App Links 打开；安装前归因由服务端签发和校验，邀请码为兜底，奖励不由客户端计算。

Play 版本是单独的支付适配：商品映射、Play Billing 购买、服务端验证、状态通知、退款与到期同步。不能将官网版的钱包充值界面直接搬进 Play 版本。

## 安全与可观测性

HTTPS 严格校验；不关闭证书验证。仅记录状态转换、错误分类、脱敏节点 ID、耗时和网络类型。诊断导出需用户主动操作，过滤邮箱、令牌、配置、IP 和支付凭据。发布构建禁止调试日志与测试节点。
