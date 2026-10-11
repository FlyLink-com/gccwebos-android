# Android 当前进度与验收边界

核对时间：**2026-10-11 UTC**。这是带版本的状态快照；后续更新应重新核对主干、PR、产物与验证记录，不自动继承本页结论。

## 1. 基线与证据等级

| 层次 | 本次核实结果 | 证据／边界 |
| --- | --- | --- |
| 主干源码 | `main@724fb7f04c24573a15e50c64b843b39d0012a0e3`，tree `ad8156aa7f60b0430489458ae6669b6c2f83ab18`；默认分支为 `main` | [精确提交](https://github.com/gccwebos-com/gccwebos-android/commit/724fb7f04c24573a15e50c64b843b39d0012a0e3)，#69 于 2026-10-11 14:12:03 UTC 合并 |
| 版本字段 | 正式包名 `com.gccwebos.android`；源码仍为 versionName `0.4.2` / versionCode `9` | [构建配置](../app/build.gradle.kts)。字段未升版不代表主干等同已发布 APK |
| 公开 APK | [v0.4.2](https://github.com/gccwebos-com/feilian-vpn-releases/releases/tag/v0.4.2)，2026-10-11 07:54:39 UTC 正式发布；发布源码 `0b94a8cee2517624eb6d5a3ac61366ea4d96d361` | `draft=false`、`prerelease=false`，五个附件存在；哈希和身份见 [发布记录](release-0.4.2.md)。本轮未下载重算或重装 APK |
| 本地构建／测试 | 公开版的发布说明记录 1,386 项 JVM 测试、`lintVitalRelease`、源码／发布策略检查及签名验证通过 | 这是已有发布记录，不是本轮重跑。后续 PR 的测试数字属于各分支或合成集成树，不能套给最新主干 |
| 托管 CI | 精确主干 `724fb7f` 未查到 Actions 运行；#60/#61/#64–#69 最新 head 的 `validate` 仍为 `queued` | 不等于通过，也不据此断言正在执行。发布说明记录本月 Actions 额度耗尽；本轮不触发／重跑 CI |
| 物理设备 | 发布说明记录 OPPO 正式 v0.4.2 保留数据覆盖升级、配置与节点选择保留、连接及断开通过 | 冷启动重复 13 次：11 次连接、2 次读取失败。不是全协议／全节点或多设备验收；没有本轮新设备测试 |
| 生产与官网 | 本轮未核实后端生产开关、schema migration 或官网 stable 清单实际切换 | 客户端合并、公开 Release、官网下载通道和服务端部署是不同事实 |

PR 的正文中可能仍有“Draft／未合并”等旧描述；是否合并以 GitHub 的 `merged_at` 与 merge commit 为准。测试和设备描述若仅来自 PR／Release 回执，会明确标为记录，不能当作本轮独立运行。

## 2. 已合并与已发布的范围

### 已进入公开 v0.4.2 的能力

- 工程、原生账户／资源、钱包／套餐／订单界面、第三方导入、真实连接与配置管理均已有合入，当前不再是 M0 空壳。早期切片仍保留各自的关闭／实验室边界，不能据此否定后续主干能力。
- v0.4.1 发布基线为 `5e9014d168b39e34ee069fff6ffbc360d57d661a`（#25），对应历史 [账户来源恢复](account-source-recovery-release.md) 与 [公开 Release](https://github.com/gccwebos-com/feilian-vpn-releases/releases/tag/v0.4.1)。
- v0.4.2 发布源码到 #58 为止，包含订阅用量展示、节点选择持久化、统一来源与节点选择、连接／套餐／我的等界面调整、到期／低流量提醒和冷启动读取改进。详见 [正式发布说明](https://github.com/gccwebos-com/feilian-vpn-releases/releases/tag/v0.4.2)。
- 会话仅能在原有效期内恢复，最长 30 分钟且服务端可更早到期；自动续期尚在 #26。正式版更新检查仍由浏览器下载，#59 的应用内下载尚未合并。
- 第三方配置只支持已实现的 SS / VLESS / Hysteria2 / TUIC 子集；导入、解析或一次流量成功不证明所有协议与节点互通。

### 已合并、晚于公开 v0.4.2 源码的改动

以下八个 PR 于 2026-10-11 14:10–14:12 UTC 合并，**不在现有公开 v0.4.2 APK 中**。部分 PR 标题／正文的旧“Draft”字样尚存，不改变已合并事实。

| PR | 合入内容 | Merge commit |
| --- | --- | --- |
| [#60](https://github.com/gccwebos-com/gccwebos-android/pull/60) | 导入阶段进度、跨来源延迟测试、节点页来源页签与命名、未知节点选择提示 | `d96a77c44c10b8332cdf1d8977d83fb5500db446` |
| [#65](https://github.com/gccwebos-com/gccwebos-android/pull/65) | 最后一份配置删除后账户来源的加载、失败状态与有限重试 | `cb5755a3e688b72086fbf9a71db9ef1a9d3476fa` |
| [#61](https://github.com/gccwebos-com/gccwebos-android/pull/61) | 第三方导入 60 秒预算／15 秒对冲、逐地址回退与脱敏 Debug 阶段诊断 | `0ec7c3f9ba4ecf206f9ad5f0ee23fdeaed7a7ee4` |
| [#66](https://github.com/gccwebos-com/gccwebos-android/pull/66) | 已保存第三方来源的激活／页面刷新沿用 60 秒导入预算；连接读取仍为 30 秒 | `1815a75abe4f0969c0ce94a70296aa999aa2362e` |
| [#64](https://github.com/gccwebos-com/gccwebos-android/pull/64) | 系统 Photo Picker 选择图片并识别二维码，复用既有导入验证 | `f836407349ef738eb2a8a559eac3c0b7feb0992a` |
| [#67](https://github.com/gccwebos-com/gccwebos-android/pull/67) | 套餐卡片更不透明、提升文字对比度 | `788debdf4ae3d66cfe73e1344a8a7be0b5fdf98f` |
| [#68](https://github.com/gccwebos-com/gccwebos-android/pull/68) | 连接页流量卡片先于连接／节点卡片 | `2c5d5711792a2693da039ad1e0fb8a26781ebd33` |
| [#69](https://github.com/gccwebos-com/gccwebos-android/pull/69) | 记住账号（不保存密码）、系统自动填充提示、登录入场调整 | `724fb7f04c24573a15e50c64b843b39d0012a0e3` |

源码入口：[配置库与节点](configuration-library-plan.md)、[读取预算实现](../app/src/main/java/com/gccwebos/android/portal/ManualSubscriptionTransport.kt)、[记住账号存储](../app/src/main/java/com/gccwebos/android/portal/RememberedAccountStore.kt)、[登录说明](mobile-login-account-parity.md)。

### 新合入改动的验证边界

- #60 记录了 #60+#61+#64+#65+#66 某一合成集成树的 1,456 项 JVM / 0 失败、OPPO 54/54 通过；后续仍有提交，不能把这些数字写成最新主干全量验收。
- #61 记录 OPPO 合成慢响应在首字节等待 20.8 秒后导入成功；真实订阅和死首地址的实机回退未验证。导入／页面预算与一键连接预算要分别看待。
- #64 记录 OPPO decoder 4/4 和系统相册选择路径通过；真实拍摄屏幕、HEIC、Android 14+ 差异未验证。相册入口仍受既有相机权限流程约束。
- #65 的 PR 记录账户加载 UI 编译／单测；真实已登录账户场景未独立验收。#67/#68 的设备结果来自合成预览页面。
- #69 记录分支 JVM 1,395 / 0、UI 测试编译与源码校验；记住账号预填、系统自动填充与真实登录入场尚无实机验收。

## 3. 尚未合并的 Draft

截至本次核对，开放功能 PR 为以下五个。`dirty` 表示当前有合并冲突；`unstable` 不是验证通过。下列状态可能随主干和 PR 更新变化。

| PR / 精确 head | 依赖与缺口 |
| --- | --- |
| [#26 会话自动续期](https://github.com/gccwebos-com/gccwebos-android/pull/26) · `49892a71592e9cc932166ec03a8c74ecd757ae59` | Draft / dirty；配套 [后端 #755](https://github.com/gccwebos-com/proxy-gateway-minimal/pull/755) 仍 Draft。该旧 head 的 [validate](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37446586486/job/112212787867) 和 [native-lab](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37446586534/job/112212788217) 已于 10 月 6 日成功，优先于 PR 正文的旧待验说法；冲突处理后的新 head 仍须重验，真实续期 E2E、后端 staging 与生产启用未证实 |
| [#48 LAN DIRECT](https://github.com/gccwebos-com/gccwebos-android/pull/48) · `42ed492630dff00b5a0c657b28f57532d7b52713` | Draft / dirty；有定向 Kotlin／Go 测试记录，host TUN／OPPO LAN 验证未执行；托管结果为 validate cancelled、native-lab failure |
| [#59 应用内更新](https://github.com/gccwebos-com/gccwebos-android/pull/59) · `87f9cf7e9086af336004379384313eb6d45f792e` | Draft / unstable；记录下载／验证／系统设置入口的设备测试，但安装器由记录桩替代；未真正启动系统安装器或覆盖安装，同签名更高版本端到端验证尚缺。validate queued、native-lab failure |
| [#62 统一弹窗](https://github.com/gccwebos-com/gccwebos-android/pull/62) · `5a27c8e4830c09c25eec3b00acb3c92ef599ee5a` | Draft / unstable；组件测试和截图记录不等于真实登录态退出／套餐／余额流程通过；消息宿主尚未接入，后续页面迁移待做。validate queued |
| [#63 应用内支付页](https://github.com/gccwebos-com/gccwebos-android/pull/63) · `c6b3d971797c4791db70f8792dcdba477527dd00` | Draft / unstable；真实网关 WebView 行为、支付宝交接与设备用例尚未验证。validate queued、native-lab failure |

失败／排队状态不自动归因于应用代码，也不在缺少日志时统一归因为额度。以上 Draft 能力均不能写为当前正式版可用。

## 4. CI 与下一轮验收

[#27](https://github.com/gccwebos-com/gccwebos-android/pull/27) 已于 2026-10-09 合并为 `dea268c43237d0db92432ae4cede4cb836ac8288`。当前工作流已经去掉合并后的重复 `push` 构建，保留 PR／手动入口、纯文档过滤、原生路径过滤与缩短的 artifact 保留期。

这不等于已加入 Draft guard、`ready_for_review` 事件或完整的“所有检查后才 assemble”改造；当前 `.github/workflows/` 中没有这些完整设置。本轮仅更新 Markdown，不调整 CI、不重试排队／失败运行，也不发布 APK。

下一步按 [TASKS](../TASKS.md) 补齐：

1. 对准备交付的精确集成 SHA 分别记录本地构建、单测、Lint、托管 CI 与产物身份，不能用分支和旧 SHA 结果替代。
2. 关闭冷启动读取偶发失败与历史异常恢复证据缺口；历史 B2 P7 超时未查明，P8/P9 未运行。旧截图或旧测试失败不能未经复现直接当作当前缺陷。
3. 按协议、节点、设备与网络变化补验；合成 SS real-TUN 结果不等于生产全协议、DNS／IPv6 防泄漏或真实账户验收。
4. 下一版的签名、覆盖升级、完整对应源码、匿名下载、官网通道与生产状态分别核验；不得将 Draft 和未发布源码提前记入公开 APK。

## 5. 文档导航

- [README](../README.md)：项目与当前版本入口
- [TASKS](../TASKS.md)：当前待办与历史 M0 任务快照
- [BUILD_STATUS](BUILD_STATUS.md)：当前证据入口和保留的 M0 构建记录
- [roadmap](roadmap.md)：当前阶段与最初验收规划
- [CHANGELOG](../CHANGELOG.md)：公开版本与尚未发布的主干改动
- [0.4.2 发布记录](release-0.4.2.md)：已发布事实、哈希及早期准备快照

本次对齐只核对仓库／PR／Release 元数据与现有源码、文档。未执行应用构建、签名、设备测试、真实付款、生产变更或仓库可见性调整。
