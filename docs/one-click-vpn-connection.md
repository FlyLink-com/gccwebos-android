# 一键连接：实现与 OPPO 验证

2026-10-02（America/Los_Angeles）。一键配置解析和 Android VPN 连接已接入。OPPO PFJM10 / Android 13 / API 33 的完整 synthetic SS 用例已通过，使用实际 Activity、本地授权、系统 consent、跨进程 Binder、JNI、TUN 和 VPN Network；测试只允许候选应用流量。随后已安装不含测试 fixture 的独立候选包“飞连 VPN 测试”，普通飞连 VPN 保留。

这份证据不代表真实订阅节点或其他协议互通已通过；未正式签名发布或生产部署。B2 P7 仍未通过，P8/P9 未运行，其历史结果与本次连接验证分开记录。

## 一键解析与选择

`OneClickConfigGateway` 复用同一个 `LocalSubscriptionManager` 和本地 acknowledged capability，不要求账户 session 成为连接授权，也不向 Portal 增加账户/设备重置请求。

- 已有合法 opaque selection 时，每次连接仍重新下载、校验和投影整个 artifact。
- 首次仅一个合法节点、且没有旧 registry 内容变更时，选择唯一节点，再进行第二次新鲜读取；第二次读取仍能拒绝变化或失效。
- 多节点未选择、内容变化或旧 selection 失效时要求在节点页明确选择。不默认第 0 节点，不以名称、digest 或序号替代身份。
- 写入和同步 copy/start 位于 generation/capability fence 内。缓存预览、旧 prepared 文件、COPY ACK 和 native RUNNING 都不能单独产生 Connected。

现有严格投影保留 SS AEAD、VLESS TCP TLS/Reality、HY2、TUIC v5 的有限形状，未知字段或不支持形状继续整体拒绝。代理 endpoint 当前要求 literal IPv4。设备互通验证只覆盖 synthetic SS；其他形状的投影单测通过不等于 Android 互通通过。

## Android 生命周期与网络边界

`ConnectionOwnerService` 在主进程唯一持有 repository、本地 authority、coordinator、准备文件和 IO scope。MainActivity 等 owner 就绪后向现有 AppShell 注入同一个 repository、StateFlow 和 power callback，不创建第二份 registry。`:vpn` 进程只持有当次引擎/TUN，通过同 UID 私有 Binder 和原 owner 的 reverse binding 维持生命周期。

系统 consent 使用 generation/Activity attachment 绑定原请求，RESULT_OK 后再次检查 `VpnService.prepare`。取消或超时后旧 result 槽位不复用。Activity 的 onStop 保留绑定，onDestroy detach/unbind；重建使用独立 ActivityResult key。服务 START_NOT_STICKY，Intent 不承载配置或启动授权，always-on false。

运行入口在 API >= 30 启用，以 NetworkCapabilities.ownerUid 确认 VPN Network 属于当前应用。引擎有 arm64-v8a/x86_64 两个 ABI；本次实际执行 arm64。TUN 捕获 IPv4/IPv6 默认路由，固定 native IPv6 REJECT；上游 socket 在连接前 protect 并绑定指定物理网络，物理网络丢失停止会话。未在所有 API/OEM、Private DNS、通知、休眠及全设备路由场景验收。

默认流量探针通过新的、同 UID VPN Network 打开 HTTPS 204 请求，禁止 direct fallback、重定向和缓存；本次 opt-in 测试把探针替换为绑定该 Network 的 nonce TCP/UDP/DNS 流量与 SS peer 计数校验。仅探针通过且当次 endpoint/Network 仍有效时发布 Connected。正常候选包恢复默认 HTTPS 探针和全应用默认路由，没有 fixture。

## 接收与清理

COPY_START 的私有字段为 owner Binder、generation、expiry epoch、elapsed admission deadline、最多 64 KiB byte[]。服务保留 same-UID、descriptor、字段完整性、单次 admission 和配置边界检查。正常远端调用可携带平台辅助 flags，只拒绝 oneway 或缺失 reply。OPPO 实际记录 flags=18；旧 `flags != 0` 判断会误拒绝正常调用，已由红绿测试与真机定位修复。

copy 等待预算 1500 ms。callee 拒绝过期 copy 和过期 worker take，并擦除 pending material；已经 take 的运行只受资源 expiry 控制。此边界不保证 JNI 实际调用发生在 reply 或短 deadline 之前。BoundedVpnCall 超时不 cancel Future，不以逻辑取消伪造真实调用返回。

transport binding 在 activation fence 外准备，coordinator 在可能分配资源前记录 ownership。取消后的迟到 Binder 仍提供 cleanup receipt，不能重新激活。成功 bound 而尚未取得 receipt 的 endpoint 保留清理债务。

停止顺序为取得 receipt → 有界 STOP → 解除 AUTO_CREATE 绑定 → 等待同一 Binder 的真实死亡 → 等待实际 IPC 调用返回 → 清理准备文件。ServiceConnection loss 不是死亡证明；没有 receipt/death/调用返回或文件删除失败时保持 CLEANUP_FAILED，禁止新连接。原生停止有独立 self-process watchdog，不杀外部 PID。

AtomicPreparedConfigStore 使用 no-backup 私有目录、0600、fsync 与原子替换。固定 prepared 文件实际写在 transport 成功后的 `AuthorizedConfig.activate` 内；clear 删除固定 target 和 writer 的普通直接 scratch 文件，不遍历目录/link。独立材料债务也覆盖未进入引擎的取消、部分写入和删除失败，不把文件存在当连接授权。

## 验证回执

完整 OPPO 用例：`OneClickVpnInstrumentedTest#oneClickRealNativeTrafficSurvivesNavigationAndRecreationThenReconnectsFresh`。

| 检查 | 实际结果 |
| --- | --- |
| instrumentation | 1 test / 0 failures，status [1, 0]，OK (1 test)，32.733 秒；host 未超时 |
| 第一次连接 | downloads=2、probes=1；SS peer TCP/UDP/DNS 均收到 nonce 请求后 Connected |
| 导航与 Activity 重建 | 原 Binder/Network 保持；无新读取或 probe；重复 TCP/UDP/DNS 成功 |
| 断开与重连 | 原 Binder 确实死亡、TUN 消失、准备文件已清；重连再次读取，使用新 Binder 和新 VPN Network |
| 最终计数 | downloads=3、probes=2、probeFailures=0、TCP=3、UDP=3、DNS=3、Portal requests=0 |
| 测试清理 | engine/owner/VPN Network 均消失；在测试目录删除前已断言 production prepared target/scratch 不存在；测试 app/test APK 及数据卸载 |
| 普通版本 | package/version 保持原值；没有卸载、清数据或覆盖 |
| 正常候选包 | synthetic=false，fixture/test 类未打入 app APK；独立安装成功，远端 APK SHA-256 与本地一致 |

重复流量验证曾遇到 fixture 的 DNS 答案与 literal 流量目标都为同一个地址，触发 native DNSMapping 回填 hostname，严格 IPv4 fixture 拒绝后续请求。新测试将 DNS 答案末段设为 .11、目标保留 .10；默认 B1 fixture 和路由保持原值。nonce/counter 校验未放宽。

- [OPPO 通过回执](evidence/one-click-vpn/2026-10-02/oppo-synthetic-ss-acceptance.json)
- [脱敏阶段与计数](evidence/one-click-vpn/2026-10-02/oppo-phases.txt)
- [正常候选包身份/安装回执](evidence/one-click-vpn/2026-10-02/normal-candidate.json)
- [Windows 全量单测](evidence/one-click-vpn/2026-10-02/windows-junit-summary.json)
- [Lint](evidence/one-click-vpn/2026-10-02/lint-summary.json)

最终全量 JVM：537 项，529 通过、8 项原 NTFS/POSIX 初始权限失败、0 error、0 skip。未放宽 0600/atomic fail-closed 行为。完整 assembleDebug、assembleDebugAndroidTest、assembleRelease 和 Lint 完成，全量命令因这 8 项失败返回失败；正常候选包 assembleDebug + Lint 单独 BUILD SUCCESSFUL，Lint 0 errors / 28 warnings。

TDD 证据另存于 ignored `.build-output/`：permission tickets 8 red/1 fail→8 green；prepared-material 3 red/3 fail→3 green；binding receipt 7 red/4 fail→7 green；deadline 10 red/3 fail→10 green；Binder flags 4 red/1 fail→4 green；DNS fixture 7 red/1 fail→7 green。它们不替代 Android 故障注入或 B2。

正常 APK：`.build-output/connection/feilianvpn-connection-candidate.apk`，SHA-256 `76536c1ce2d9878460f7644f3b7dee31f3ee0d6c5a6f539baa33a6d1bc8b3a30`。包名 `com.gccwebos.android.candidate`，versionCode 6、versionName `0.4.0-mobile-parity-connection-candidate`。本地 signer 与普通已装版不同，使用独立包名；本记录不推导可以签名兼容地覆盖普通版。

## 重建

先使用 [统一 Android 工具链](android-development-toolchain.md)，并通过 source-lock/materialize 工具取得校验通过的 native libraries。

```powershell
. 'C:\Users\g\AppData\Local\Android\android-dev-env.ps1'
# 独立正常候选包
.\gradlew.bat --no-daemon -PconnectionCandidate=true :app:assembleDebug :app:lintDebug
# opt-in，候选应用流量范围的 synthetic SS 设备验收
.\gradlew.bat --no-daemon -PconnectionCandidate=true -PconnectionSyntheticAcceptance=true :app:assembleDebug :app:assembleDebugAndroidTest
```

仅在已授权、空闲的测试机上安装 app/test APK，选择上述 instrumentation 单一用例；不得覆盖现有候选数据。native preBuild 校验两个 ABI 固定摘要与 GPL notices，APK 检查确认两个 ABI 的 ELF >=16 KiB alignment，unsigned release 含 production JNI 且排除 Lab 组件。正式 release signing、全协议真实节点互通和 B2 仍是独立门禁。
