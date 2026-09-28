# M0 构建收口记录

范围：Android 工程、Debug 构建、JUnit、Lint、测试包编译、签名与安装启动；不接入真实 VPN 或后台。基线为 PR #1 的 `dbd4b86700b4ca88eaa0b9bb64caf6201ee6d753`。修复集中在 [PR #3](https://github.com/gccwebos-com/gccwebos-android/pull/3)，不自动合并 main。

## 已确认的过程证据

| 源码／运行 | 实际结果 |
| --- | --- |
| [main 初次运行](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36472813849) | SDK Manager 找不到 `platforms;android-37`，未进入编译 |
| [7de92e47](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36474064401) | SDK 36 与 Wrapper 校验成功；Gradle DSL 的顶层 `platform()` 无法解析 |
| [1c34cd59](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36474548312) | Debug 构建、53 项 JUnit（0 失败／错误／跳过）和 Lint 通过；Lint 0 错误、9 警告；测试包编译发现无效 `assertExists` import |
| [Wrapper 物化回执](https://github.com/gccwebos-com/gccwebos-android/actions/runs/36474548451) | 官方字节经 SHA-256 校验后存入不可变 Git blob；无分支修改，临时写入工作流随后删除 |

本分支已将 `platform()` 改为 `dependencies.platform()`，并删除无效 `assertExists` 导入，保留成员方法调用。Wrapper JAR 已入库且本地重新计算 SHA-256 与官方值一致：`55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`。

## 最终验收门槛

以 PR #3 **最新精确 head 的 CI 与验证回执**为准，不把上述中间成功项合并成一个虚构的全通过运行：

- `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebugAndroidTest` 全部成功。
- 实际 APK 与测试 APK 签名验证、文件大小和 SHA-256 可追溯。
- 同一 APK 安装、启动、进程存活、instrumentation 通过，有真实截图和日志。
- 模拟器记录 API／镜像指纹，不冒称物理真机或真实 VPN 验证。

所有步骤成功前保持 PR Draft；未执行项、残留警告、CI 链接和实际 APK 校验值在 PR 回执中列明。源码变化后的新运行使用新 source SHA，既有失败证据不会覆盖。

## 产物与非目标

CI artifacts 保留 Debug APK、测试 APK、构建 manifest、签名、SHA-256、JUnit XML、Lint XML/HTML、安装日志与截图。源码身份记录为 checkout 的真实 head，而非混用 GitHub 合并测试 ref。

不发布 Release、不上传 Google Play、不接生产节点、不访问真实客户或付款资料。Debug 自检是假网关；正式连接仍禁用。

[初始 ZIP 的离线诊断与构建阻塞历史](../evidence/initial-build-status.md) 原文保留；其中“未取得 Wrapper／未运行 CI”等属于导入当时的历史，不代表本分支最新状态。
