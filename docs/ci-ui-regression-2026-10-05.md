# PR #23 界面测试回归修正

日期：2026-10-05。风险 B 档。失败基线 `877c2ab7e85f55a7d6625d38d8bdbec3c22dd1c5`。本次只修改三份 `androidTest` 和验收记录，不修改应用源码、原生库或 CI 门禁。

## CI 结果与原因

[Android M0 run 37343423579](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37343423579) 与 [Native run 37343423556](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37343423556) 的通用 UI 批次均为 310 项、7 失败，失败方法完全一致。M0 构建、JVM、Lint 已通过；Native 编译、Go packet path、双 ABI/JNI 包装及两项 NativeBridge 测试已通过。后续 Real TUN 批次因通用 UI 步骤失败而未执行，不能记为通过。

| 范围 | 原因 | 最小修正 |
| --- | --- | --- |
| `CommerceEligibilityUiTest` 三项 | 套餐导航已只重读 commerce model/catalog，测试仍期待 wallet/recharge 在内的四次 GET。 | 精确断言两次 GET、所属合成会话和重复导航后的两次新请求；增加钱包及充值未加载断言，保留无自动下单检查。 |
| `SubscriptionWalletUiTest` 一项 | 异步门闩仍阻塞已不再发起的第二次 wallet 请求。 | 阻塞实际 model 请求；精确核对显式余额/model/catalog/显式余额顺序，保留恢复、busy、防循环、隐藏加载和无自动下单断言。 |
| `BrandUiTest` 三项 | 前序导入测试在进程共享 authority 中留下已准入节点；展示 fixture 未自行隔离。节点组又改为 IO 异步读取，Compose idle 不代表该读取完成。 | 在 fixture 的 `remember` 初始化内一次性撤销旧句柄；两个节点展示测试等待读取结束，确认 LegacyRemote 活跃组、空 choices 和空 selected，再检查只读节点。既有禁用、不可点击、过期和存储失败断言保留。 |

`AutomaticSubscriptionUiTest.supportedFixtureImportsButNeverClaimsVpnConnected` 后运行旧版三个 Brand 测试，三项均失败；独立运行旧版七项只复现套餐四项失败。两批合起来复现全部七项，重复方法不累计为额外覆盖。

## 修正后本地验证

- OPPO 隔离包先运行上述导入前置，再运行 Brand、CommerceEligibility、SubscriptionWallet 三个完整测试类：**50 项，49 通过、1 失败、0 跳过**。原 CI 七项全部通过，不能把整个批次记为绿色。
- 唯一失败为未修改的 `launcherAndAccessibleHeaderUseExactBrandNameWithoutChangingPackage`：断言标准 `com.gccwebos.android.debug`，实际为有意隔离的 `com.gccwebos.android.candidate.acceptance.latency20261005`。未放宽包名或品牌检查；该方法在上述两条原 CI 均已通过，新提交仍由标准 CI 包重新验证。
- 同一三文件源码的 app/test APK 构建及 Lint 通过（0 error / 23 warning / 6 hint）；校验源码哈希、APK 哈希、签名、目标包、关闭的合成/lab 开关、原生库和 16 KiB 对齐。
- 独立只读复核通过：未放宽 authority、busy、expiry 或禁止自动下单的断言。`git diff --check` 通过。
- 自有隔离 app/test 已卸载并核对不存在，亮屏设置读数恢复原值。普通 candidate APK 仍为已交付的 `70bac49`，字节哈希一致；未重装普通应用、未清其登录或配置数据。

结构化摘要见 [validation.json](evidence/ci-ui-regression/2026-10-05/validation.json)。完整本地合成日志和构建记录位于忽略目录 `.build-output/ci-877c2ab/`；其中 `oppo-green.log` 是历史文件名，实际批次仍有上述一项身份失败，以结果计数为准。

## 剩余门禁

本地隔离 UI 验证不代替修正提交的完整 CI，也不代替 Real TUN 验收。PR 保持 Draft，继续以最新提交的 Checks 为准；本次不包含合并或 Release 发布。用户版 APK 不需要因这次测试修正重新安装。
