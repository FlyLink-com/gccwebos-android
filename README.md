# 飞连 VPN · Android

原生 Android 客户端，包含连接、节点、套餐、我的四入口。正式包名 `com.gccwebos.android`，最低 Android 8.0（API 26）；真实连接还受系统版本、内核打包与连接准入条件约束。

## 当前进度（2026-10-14 UTC）

- **已公开发布**：[v0.4.3](https://github.com/FlyLink-com/feilian-vpn-releases/releases/tag/v0.4.3)，versionCode `10`，发布源码 `495779cf900c69046423c9161a21a10d24da2b99`。完整对应源码使用 Release 的 `corresponding-source.zip` 附件。
- 已包含应用内更新下载、支付页、统一弹窗、记住账号及 FlinkVPN 品牌更新。记住账号仅保存账号输入，不代表访问会话自动续期；已发布版本会话到期仍需登录。
- 会话续期 #26、LAN DIRECT #48、月卡升降级 #83 及后续修复按各自 PR 验证；本地合并准备不等于这些能力已进入 v0.4.3，也不启用服务端开关。
- 发布、源码合并、构建、设备与生产验收分别记账。用户已授权以本地检查替代受预算限制的云端 CI；不伪造托管检查结果。

进度与证据见 [PROJECT_STATUS](docs/PROJECT_STATUS.md)，待办见 [TASKS](TASKS.md)，构建索引见 [BUILD_STATUS](docs/BUILD_STATUS.md)。下方旧切片仅描述当时范围。

## 历史连接候选记录

> One-click VPN candidate: the service-owned local subscription path now reaches Android consent, a one-shot native TUN process and a VPN-bound traffic probe. Engine libraries must be verified and packaged; connection is enabled on API 30+ where the VPN Network owner can be verified. [Implementation and validation](docs/one-click-vpn-connection.md) · [Windows toolchain](docs/android-development-toolchain.md).

The English product name is FlinkVPN. Chinese UI text stays 飞连VPN; the launcher name is 飞连VPN on Chinese-locale devices and FlinkVPN elsewhere (default). This repository is FlyLink-com/gccwebos-android (org renamed from gccwebos-com). The future open-source release repository will be FlyLink-com/flink-android; no git remote is changed here. Published APKs and corresponding-source archives are currently hosted in FlyLink-com/feilian-vpn-releases.

## 先前版本记录

以下内容保留各历史切片的状态；当前进度、版本与验收边界见 [PROJECT_STATUS](docs/PROJECT_STATUS.md)。

> 0.4.0 mobile parity baseline: encrypted local-subscription shell with account-only login gates, server-validated native-session restore, actual subscription-node metadata, server-driven wallet/catalog/orders and approved robot/FlinkVPN launcher artwork. [Scope and limits](docs/mobile-login-account-parity.md). Production VPN was disabled at this baseline.

> Offline protocol contract v1: bounded SS AEAD, VLESS TCP TLS/Reality, Hysteria2 and TUIC v5 projection; production VPN remains disabled. See [support limits and evidence](docs/offline-protocol-contract.md).

# 飞连VPN · 0.3.0-commerce-draft

独立叠加草稿：响应式订阅状态、钱包余额与充值、服务器三套餐报价及订单恢复。范围、来源与安全边界见 [订阅与钱包](docs/subscription-wallet-draft.md)。未合并、未发布，不开启生产 VPN；无真实付款测试。

## 历史基线

# gccwebos Android · 0.2.1-ui

在原生账户／订阅与隔离VPN实验室基础上，适配用户指定的 gccwebos-mobile 视觉和真实套餐参数。四入口均为 Jetpack Compose；原字标、机器人和场景保持来源像素；未知／过期用量保持明确状态。

设计来源、资产权利边界、测试与实际截图验收范围见 [原生UI适配](docs/native-ui-reference.md)。生产VPN与原生购买仍保持关闭，不把实验室包或截图视为上线承诺。当前提交的CI结果是验收依据，历史构建记录不替代它。

## 以下为历史 M1 记录

# gccwebos Android · 0.2.0-m1

原生账户、资源与自动订阅交付切片，叠加 PR #3 的已验证 M0 基线。新增密码／TOTP 登录、严格资源 DTO、Keystore 加密交付与确认、账户加载/重试、官网更新检查。真实 VPN 仍禁用；商品购买和协议内核未接入。

实现、安全边界、生产地址来源与测试说明见 [M1 账户集成](docs/native-account-m1.md) 和 [官网更新](docs/official-updates.md)。本分支不自动合并、部署或发布正式签名 APK。

## 以下为历史 M0 基线记录

# gccwebos Android · 0.1.1-m0

原生 Android M0 工程：单一 `app` 模块、连接／线路／套餐／我的四入口和 Gateway 分层。**本阶段只验证工程可构建、可安装、可启动，不代表 VPN 或后台业务已经接入。** 真实连接按钮保持禁用；Debug 自检明确是本地模拟。

M0 构建收口在 [PR #3](https://github.com/gccwebos-com/gccwebos-android/pull/3)；精确源码、CI、APK SHA-256 和设备结果见 PR 的最新验证回执及 [构建记录](docs/BUILD_STATUS.md)。`main` 合并状态与 PR 构建通过是不同事项，不自动发布或上架。

## M0 阶段代码（历史）

| 范围 | 实现 |
| --- | --- |
| 连接协调器 | 取消在途连接、隔离旧结果、异常与部分启动清理、换线、重连；清理失败时禁止启动新隧道 |
| 配置材料 | 防御性复制、安全字符串表示、关闭后清零 |
| 四入口 | 状态保存、滚动布局、资源文案和测试标签 |
| Debug 自检 | 本地模拟正常、配置等待、探测失败、无套餐；没有网络、TUN 或真实账户 |
| Release 分离 | 模拟网关仅在 `src/debug`；release 使用空实现 |
| 测试 | JUnit 参数化运行 53 项同源用例；2 项 Android UI 用例 |
| 构建 | 固定工具链、已入库并校验的 Wrapper JAR、Debug/JUnit/Lint/测试包编译、签名与模拟器证据 |

## 构建

准备 JDK 17、Android SDK Platform 36、Build Tools 36.0.0；通过 Android Studio SDK Manager 安装并确认 SDK 许可，设置 `JAVA_HOME`、`ANDROID_HOME` 或本地 `local.properties`。依赖下载仍需要联网。

Windows：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
.\gradlew.bat --no-daemon --console=plain :app:assembleDebugAndroidTest
```

Linux／macOS：

```bash
chmod +x gradlew build.sh scripts/*.sh
./build.sh
./gradlew --no-daemon --console=plain :app:assembleDebugAndroidTest
```

完整验收任务：

```bash
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

安装包路径：`app/build/outputs/apk/debug/app-debug.apk`。测试包路径：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

默认 Debug ID 为 `com.gccwebos.android.debug`；本次核对主干版本为 `0.4.2-debug` / versionCode `9`。M0 历史包版本为 `0.1.1-m0-debug`；构建变体以 `app/build.gradle.kts` 为准。Debug 签名由构建环境生成；无正式签名私钥入库。不同环境的 Debug 签名可能不同，不能视为正式升级签名。

## Wrapper

`gradle/wrapper/gradle-wrapper.jar` 已包含在源码中，48,966 字节。SHA-256：

```text
55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
```

JAR 来自 Gradle 官方仓库 `v9.4.1`，与 [Gradle 官方校验表](https://gradle.org/release-checksums/) 匹配；Gradle 9.4.1 分发包也固定 `distributionSha256Sum`。`scripts/verify_source.py` 要求 JAR 存在且校验正确；不再把下载引导当成已补齐的二进制。`gradlew`／`gradlew.bat` 仍是本项目的校验启动器，不冒称为自动生成的标准脚本。

一次性远程辅助任务仅创建经校验的不可变 Git blob，没有写分支或 main；取得二进制后该临时工作流已删除。最终 Android 工作流保留 `contents: read`，不具有源码写入或发布权限。

## 固定工具链

| 项目 | 配置 |
| --- | --- |
| AGP / Gradle | 9.2.0 / 9.4.1 |
| Kotlin / Compose Compiler | 2.3.10，AGP 内置 Kotlin |
| Compose BOM | 2026.06.01 |
| Activity Compose / Coroutines | 1.12.4 / 1.10.2 |
| compile / target / min SDK | 36 / 36 / 26 |
| Build Tools / Java 字节码目标 | 36.0.0 / 17 |

compileSdk 37 在初次 CI 的 SDK 安装阶段不可获取，因此本轮统一使用 36；targetSdk 和 minSdk 没有改变。构建依赖与平台更新提示不自动触发升级，Lint 错误仍会阻断。

## M0 安装启动与证据（历史）

CI 在 API 36 x86_64 临时模拟器安装同一个已编译 APK，执行显式 Activity 启动、进程检查和两项 instrumentation 用例，保存实际截图、UI XML、签名输出与安装包哈希。`scripts/m0-emulator-smoke.sh` 只适用于托管 Linux 测试机，需要 SDK 和 KVM；不是用户手机上的安装脚本。

`.build-output/build-manifest.json`、JUnit XML、Lint 报告和 `.build-output/device/` 是本轮构建证据。离线 Kotlin CLI 用例、编译成功、模拟器通过和物理真机通过分别记录，不能互相替代。

`evidence/` 与根目录 `file-inventory.json` 保留初始导入包的历史诊断和清单，不是当前 APK 的清单；历史构建记录位于 [原始记录](evidence/initial-build-status.md)。

## M0 当时的后续阶段（历史）

没有真实 `VpnService`、协议内核、邮箱登录 API、真实套餐／支付／订单／推荐归因接入。原生后台合同、真实 VPN 实验、产品接入、商业与推荐、签名测试版按 [TASKS.md](TASKS.md) 顺序进行；无后台、节点、生产或付款操作。

- [连接协调器合同](docs/connection-coordinator.md)
- [后台合同边界](docs/backend-contracts.md)
- [里程碑](docs/roadmap.md)

