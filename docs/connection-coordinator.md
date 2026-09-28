# ConnectionCoordinator 合同与接入

## 生命周期所有者

构造时新增 `parentScope: CoroutineScope`，该 scope 必须携带 Job。M1 应由 VPN 服务／应用级连接所有者持有，不能以临时页面作用域持有真实连接。Debug 自检故意使用页面作用域，离开页面即清理模拟资源。

```kotlin
val coordinator = ConnectionCoordinator(
    account, entitlement, config, permission, tunnel, probe,
    parentScope = serviceScope,
)
val outcome = coordinator.connect() // 等到失败/阻断/探测成功，不等待隧道整个生命周期
coordinator.switchLine(authorizedLineId)
coordinator.reconnect()
coordinator.disconnect() // 等待旧任务清理完成
coordinator.close() // 不再接受新命令，可重复调用
```

真实 UI 使用 `state: StateFlow`，不以旧请求的返回值覆写最新连接状态。相同在途 connect 会共享尝试，任何一个共享等待者主动取消会取消该共享尝试；需要多个无取消耦合订阅者时，只观察 StateFlow，不重复调用 connect。

## 命令顺序与资源

`connect`／`switchLine`／`reconnect`／`disconnect` 采用最新已提交命令优先。命令身份只在短时 monitor 内修改；完整网络检查与已连接生命周期持有的是另一把资源锁。断开先取消旧 Job，因此不会等着旧网络请求自然结束才有机会取消。

旧任务在释放资源锁前完成 stop。新任务不能与旧 finally 同时操作全局 TunnelGateway。已连接任务保持存活直到取消，所以 owner scope 结束也能触发清理。

换线采用**先停旧连接，再重新检查账户、权益与配置并启动新线路**的保守策略。本轮没有内核热切换，也没有“切换失败自动恢复旧节点”的承诺。新的配置必须与指定线路匹配；自动选择只消费网关返回的获准线路。`reconnect()` 重用上次选择；自动选择的上次值为 null，会重新请求自动线路。

## 超时及取消合作约束

每个普通阶段默认 20 秒，权限阶段 120 秒，清理阶段 5 秒，均可注入测试配置。这是本次工程默认值，不是已测得的服务性能或后台限流规则。

所有 Gateway 必须支持协程取消。`withTimeout` 不能强行终止任意阻塞或忽略取消的 native 调用；M1 内核适配必须有自己的 socket 超时、fd 关闭或隔离进程终止机制。未完成 native 验证前，不宣称任何阶段在真实设备上都有硬实时停止保证。

`TunnelGateway.start` 可能在返回 false、抛错或被取消前分配资源，stop 必须能清理这种半启动状态。`stop()` 要幂等，只有确认资源已释放才正常返回。stop 失败保留安全锁存，客户端显示 `CLEANUP_FAILED`，后续明确的操作会先重试清理，成功前不启动新隧道。

## 配置字节

`AuthorizedConfig` 接管构造输入的副本。内核适配在 start 期间用 `copyMaterial()` 取一次临时副本，读完后自行清零；不要保留对象用于之后再次读取。协调器在 start 返回后立即 close 自己的配置对象，错误和取消路径同样 close。

Gateway 在未能成功返回配置对象时，必须自行清理它已分配但尚未交付的字节；协调器无法擦除从未拿到的对象。任何层都不得将真实配置、token、邮箱、IP 或请求异常详情写入日志／分析／分享。

## 尚未接入的 Android 事实

`PermissionGateway` 不是系统权限实现；`TrafficProbe` 不是普通 HTTPS 成功即视为 VPN 成功。真实实现仍需 VpnService.prepare、前台服务、TUN/内核、保护外连 socket、路径绑定探测、系统撤销事件及网络变更恢复。本次测试只运行内存网关。
