# 构建与验收证据索引

## 当前入口（2026-10-14 UTC）

- [PROJECT_STATUS](PROJECT_STATUS.md) 区分源码、发布、构建、设备与线上证据。
- [v0.4.3](https://github.com/FlyLink-com/feilian-vpn-releases/releases/tag/v0.4.3) 已公开发布，对应 `495779cf900c69046423c9161a21a10d24da2b99` / versionCode 10；发布声明不等于本轮独立复跑。
- 云端任务的预算阻塞与 runner 等待分别记录；用户授权本地验证后合并。不把未启动、排队或跳过写成通过。
- 当前补验属于各 PR 的精确源码，不能套用到后续合成树。文档变更本身仅做 `git diff --check`。
- 以下完整保留 M0 的精确提交、哈希与失败历史，只描述当时范围。

# M0 构建收口记录（历史）

范围：Android 工程、Debug 构建、JUnit、Lint、测试包编译、签名与安装启动；不接入真实 VPN 或后台。起始基线为 PR #1 的 `dbd4b86700b4ca88eaa0b9bb64caf6201ee6d753`。PR #2 合入 `main@abb7b7c717a5911b88f1883d17cb99e28f3aaaea` 的修复已保留，M0 收口集中在 [PR #3](https://github.com/gccwebos-com/gccwebos-android/pull/3)。本记录不自动合并 main 或发布。

## 已验证的完整 M0 运行

- 验证源码：`bceabc27cd85acdea129e3294dc3527bd40a9e8a`。
- 源码 tree：`500ef1e574eb1b656e3b7073195479f5ec8a3179`。
- [CI 36481854447](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36481854447)，attempt 1，`success`；2026-09-28 20:49–20:59 UTC。
- [validate job 109129158659](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36481854447/job/109129158659)。checkout 为上述真实 PR head，不混用 GitHub 合并测试 ref。
- 环境：Ubuntu 24.04 hosted runner、Temurin JDK 17.0.20.1、Gradle 9.4.1、SDK Platform 36、Build Tools 36.0.0；应用 min/target/compile SDK 为 26/36/36。

| 验收项 | 同一次运行中的结果 |
| --- | --- |
| 已入库 Wrapper JAR | 48,966 字节；SHA-256 校验通过；分发包固定校验值 |
| `:app:assembleDebug` | 通过，生成实际 Debug APK |
| `:app:testDebugUnitTest` | 53 项，0 失败、0 错误、0 跳过 |
| `:app:lintDebug` | 通过，0 错误、9 警告；不是零警告 |
| `:app:assembleDebugAndroidTest` | 通过，生成测试 APK |
| APK 与测试 APK 签名 | `apksigner verify --verbose --print-certs` 均通过；v2=true；Android Debug 证书 |
| 安装／启动 | 两个 `adb install -r` 均为 Success；主 Activity 冷启动 Status: ok；进程存在 |
| instrumentation | API 36、google_apis/x86_64、Pixel 7 AVD；`OK (2 tests)` |
| APK 一致性 | 构建后与安装验证后的两份 APK SHA-256 分别一致 |

UI 用例为 `allFourDestinationsRemainAccessible` 和 `realVpnIsExplicitlyUnavailable`。启动与测试后截图由 emulator `screencap` 收集，路径为报告包内 `.build-output/device/launch.png` 和 `final.png`；此文不是对截图像素做过人工视觉验收的声明。模拟器不是物理真机，测试没有证明真实 VPN 可用。

## APK 身份与下载

以下哈希属于上述运行的具体文件，不是 ZIP 哈希，也不是未来重建文件的承诺。

| 文件 | 身份 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 11,456,549 字节；`com.gccwebos.android.debug`；`0.1.1-m0-debug` |
| Debug APK SHA-256 | `e0e273bf2c3226e887cd034cc4b1fe607f83d07f4502db5a0088ec56d08f0a2d` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` SHA-256 | `f035633ba0b863925c9c61d05a769745092713d8c14807a0f17dda3a464a5190` |
| Debug 签名证书 SHA-256 | `cf8823a036dceeacb505259dac36013ecf635512d398f3dc2b454d9d845d6363` |

- [Debug APK／测试 APK／签名／校验清单](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36481854447/artifacts/10997049620)。artifact ZIP SHA-256：`d7741d145444783d1e4939a172a9a0a7121db16d1d5744de12fe9d7cd64d69f2`。
- [JUnit、Lint、安装启动、UI 层级和截图报告](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36481854447/artifacts/10997461073)。artifact ZIP SHA-256：`1a69de796ad14a80b9ac1a5b708d63a31856e47f4cf126d3e1ef87f39d926ca2`。
- [精确源码归档与 Wrapper](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36481854447/artifacts/10997038493)。artifact ZIP SHA-256：`241fb6e336afd5b31ea2e66d90b1cd5377a42e049f12be1daf4d05d99873e3c1`。

三份 artifact 在本次读取时 `expired=false`，元数据到期时间为 2026-10-12 UTC，不能当作永久下载链接。私有仓库需有权限的 GitHub 登录。Debug 签名不作为正式升级签名；未发布 Release、未提供正式签名密钥。

## Wrapper 来源

源码内 `gradle/wrapper/gradle-wrapper.jar` 的 SHA-256：

```text
55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
```

与 [Gradle 官方校验表](https://gradle.org/release-checksums/) 的 9.4.1 Wrapper JAR 值一致。Gradle 9.4.1 binary ZIP 的固定 SHA-256 为 `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`。不把“引导脚本存在”当作 JAR 已取得；本次源码中存在实际 JAR，CI 使用并校验了它。

## 过程证据与修复

| 源码／运行 | 实际结果 |
| --- | --- |
| [main 初次运行](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36472813849) | SDK Manager 找不到 `platforms;android-37`，未进入编译 |
| [7de92e47](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36474064401) | SDK 36 与 Wrapper 校验成功；Gradle DSL 的顶层 `platform()` 无法解析 |
| [1c34cd59](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36474548312) | Debug、53 项 JUnit、Lint 通过；测试包编译发现无效 `assertExists` import；并非完整 CI 成功 |
| [Wrapper 物化回执](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36474548451) | 经校验的官方字节存入不可变 Git blob；临时写入工作流随后删除 |
| [bceabc27 最终完整运行](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36481854447) | 所有 M0 CI 步骤完成，结果为 success |

最终保留 PR #2 的修复：SDK Platform 36；在 `dependencies` 作用域内调用 `platform(composeBom)`；删除无效 `assertExists` import，保留成员调用。PR #3 补入 Wrapper JAR、精确提交取证和模拟器验证，并固定隔离 AVD 的创建／查找路径；仅在明确识别的 GitHub-hosted 临时 runner 上于 APK 构建后移除未使用的 NDK/CMake，解决模拟器镜像的磁盘不足。没有清理开发者机器或 self-hosted 环境。

## 验证复用与未执行项

本次收口读取并核对已有完整成功运行的源码、日志、artifact 元数据与官方 Wrapper 校验值，不将历史 CI 说成本地新跑。本地环境未安装 Android SDK 且 Gradle 域名解析失败；没有在本地下载 APK 重算哈希。上文 APK 哈希与签名来自同一次 CI 的实际输出；artifact ZIP digest 来自 GitHub 元数据。

本记录及 TASKS 的后续文档提交不改变已验证应用代码或工作流，但每个新 source SHA 的最新 CI 状态仍需独立核对。PR #3 的最新验证回执记录后续运行，不能将 `bceabc27` 的哈希套到其他运行。

保留 9 项 Lint 警告及日志中的 Kotlin annotation target、Compose 测试 API 弃用提示；没有通过关闭 Lint 隐藏它们。物理真机、真实 VPN／TUN、后台、节点、支付、推荐归因、正式签名和商店发布均未验收。本轮不自动合并或发布。

[初始 ZIP 的离线诊断与构建阻塞历史](../evidence/initial-build-status.md) 原文保留；其中“未取得 Wrapper／未运行 CI”等属于导入当时的历史，不代表本次已验证基线。
