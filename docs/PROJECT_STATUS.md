# Android 当前进度与验收边界

核对时间：**2026-10-14 UTC**。这是带日期、精确源码身份的快照；后续合并状态以 GitHub 为准，不能自动沿用旧测试结论。

## 公开发布与源码身份

| 层次 | 核实结果 | 边界 |
| --- | --- | --- |
| 公开 APK | [v0.4.3](https://github.com/FlyLink-com/feilian-vpn-releases/releases/tag/v0.4.3)，2026-10-14 09:02:34 UTC 发布 | 版本 `0.4.3` / `10`，包名 `com.gccwebos.android` |
| 发布源码 | `495779cf900c69046423c9161a21a10d24da2b99`，tree `c8fc35a844be813ce692ed4cfbcfd7a2f690d62d` | [精确提交](https://github.com/FlyLink-com/gccwebos-android/commit/495779cf900c69046423c9161a21a10d24da2b99)；之后的主干合并不改变该 APK |
| APK 摘要 | `836e967b02475bc57cb33003c9d33bea087c37d44af6129b18d31903388c8340` | 来自公开 Release，本次文档更新未下载重算 |
| 对应源码 | `feilian-vpn-v0.4.3-corresponding-source.zip`，SHA-256 `d4a28d772074b39862a575113610eb8421dbd2a6b0e2165e4be736436fb95f26` | 使用专用附件；GitHub 自动 ZIP/TAR 不包含依赖源码 |
| 历史版本 | [v0.4.2](release-0.4.2.md) 和 [v0.4.1](account-source-recovery-release.md) | 旧设备、构建和失败记录按原版本保留 |

本轮文档更新核对 GitHub 元数据与源码；未重新签名、安装、付款、操作生产或重新验证公开 APK 的设备行为。

## v0.4.3 已包含的功能

- 配置管理、扫码／相册／文件／链接导入及已实现的 SS / VLESS / Hysteria2 / TUIC 子集；不承诺任意第三方格式、所有协议参数或所有节点可互通。
- 节点选择持久化、来源管理、导入读取预算和节点延迟展示改进。
- 应用内更新下载、统一弹窗、应用内支付页、套餐与充值流程改进。
- 记住账号与系统自动填充、FlinkVPN 英文品牌和中文桌面名称。
- 记住账号只保存账号输入；已发布 APK 仍按原访问凭据有效期恢复会话，最长 30 分钟且服务端可更短。自动续期 #26 尚未包含在此 APK。

PR 源码合并不等于完整用户流程验收；真实支付网关、系统安装器、设备与协议覆盖仍看各自验收记录。

## 本地合并验收与待交付范围

用户于本轮授权：云端 CI 受额度限制时，改为本地检查通过后合并。按精确候选提交记录命令、通过／失败／未执行项，不修改保护规则或制造绿色 CI。

- [#26 会话续期](https://github.com/FlyLink-com/gccwebos-android/pull/26) 配套 [后端 #755](https://github.com/FlyLink-com/proxy-gateway-minimal/pull/755)。旧 head `49892a7` 的两路 CI 通过仅证明旧提交；冲突解决后的源码重新验收。服务端开关默认关闭，源码合并不代表已开启，真实 exchange/expiry/rotation/logout 与 staging 尚需单独验收。
- [#48 LAN DIRECT](https://github.com/FlyLink-com/gccwebos-android/pull/48) 同时改变 Kotlin 投影与原生 bridge/schema；不能只凭 UI 编译宣称路由可用，需原生合同与构建、路由边界验证。
- [#83 月卡升降级](https://github.com/FlyLink-com/gccwebos-android/pull/83) 依赖已合并后端 #772 的默认关闭功能。可选读取不应注销账户；真实服务端报价、付款与生效另行联调。
- #86–#97 覆盖退出清理、延迟、节点读取、恢复、导入、时钟和日志。各 PR 的本地验证与合并回执分别记录，不将开放清单写死为长期事实。
- [#98 发布策略基线](https://github.com/FlyLink-com/gccwebos-android/pull/98) 修正 `versionCode` 检查仍为 9 而正式源码已为 10 的冲突；本地验证后合并，不发布 APK。

云端已核实的预算阻塞表示任务未开始；runner 排队／超时取消也不是代码执行后的失败。实际测试失败必须单独修复，不能统一归因为额度。

## 保留的验收缺口

- v0.4.2 发布记录中的 13 次冷启动为 11 次成功、2 次读取失败；这是历史数据，不能未经新版本复现就认定当前同一根因，也不能悄然删除失败记录。
- 原 B2 P7 异常恢复超时尚无关闭证据，随后 P8/P9 未运行；局部恢复单测不能替代实机完整矩阵。
- 合成 SS 流量、隔离存储或单设备结果，不等于真实账户续期、全协议、DNS／IPv6 泄漏及多设备全面验收。
- 后端生产开关、schema 状态与运行版本未由本次 Android 源码合并动作验证；官网、公开附件与服务端部署分别核对。
- 正式签名、同签名覆盖升级、匿名下载、对应源码及官网下载通道属于下一版独立发布流程。

## 导航

[README](../README.md) · [TASKS](../TASKS.md) · [BUILD_STATUS](BUILD_STATUS.md) · [roadmap](roadmap.md) · [CHANGELOG](../CHANGELOG.md)
