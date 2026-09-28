# gccwebos Android 首轮构建交付记录

日期：2026-09-28
交付版本：`0.1.1-m0`
范围：从上传的 Android Starter 增量修改源码、构建入口和回归测试；不操作后台、账户、支付、节点或生产。

## 结论

**首轮源码实现与隔离 JVM 回归已完成；Android APK 构建受环境阻塞，M0 整体验收尚未完成。**

交付的是可继续进行 Android 构建的工程源码和证据，不是安装包。未生成 APK/AAB，未签发正式应用，未运行 GitHub Actions；不将构建脚本存在视为构建成功。

原始输入为 `gccwebos-android-starter (1).zip`，15 个实际文件。原包保持不变：

```text
SHA-256: 62ad7f4667bd313f9e0530af95ff9ef0bc466c9f8d02e1424f20f5209b85289e
```

## 本轮实际变更

| 范围 | 已写入源码的内容 | 验证边界 |
| --- | --- | --- |
| 连接取消 | 取消在途请求；旧任务不得回写新状态；资源操作按所有者串行 | 隔离 JVM 同源用例通过 |
| 失败清理 | 启动部分失败、探测异常、调用取消均进入清理；清理失败时阻止新隧道启动 | 隔离 JVM 同源用例通过；尚无真实 TUN |
| 换线与重连 | 新增 `switchLine`、`reconnect`、`disconnect`、`close` 生命周期合同 | 隔离 JVM 同源用例通过；不是实际网络换线 |
| 配置材料 | 防御性复制、日志字符串脱敏、关闭后清零并禁止再次读取 | 同源用例通过；不等于整台设备内存的安全保证 |
| 页面 | 保留连接／线路／套餐／我的，补状态保存、滚动、资源文案与测试标签 | 静态检查；未进行 Android 编译或视觉真机验收 |
| Debug 自检 | 仅 Debug 源集包含本地模拟面板；明确显示“未建立 VPN” | 源集分离检查；模拟网关不访问网络或生产 |
| 工具链 | 对齐 Compose Compiler 声明、采用可核对的 BOM、固定下载校验值 | 未完成 Maven 解析；详见 CHANGELOG 与工具链来源 |
| 构建入口 | Windows/Linux 脚本与校验引导；缺包或校验失败即停止 | Shell 语法及校验失败负例通过；PowerShell 未在 Windows 执行 |
| CI | 准备 Debug 编译、JUnit、Lint、测试包编译、签名校验及证据上传流程 | YAML 结构解析；未推送、未触发远程执行 |

## 本轮实际运行的验证

生产 `ConnectionCoordinator.kt` 与 JUnit 共用的 `ConnectionCases.kt` 使用同一份源码编译，独立 CLI 入口仅负责执行，不复制协调器实现。

| 检查 | 结果 |
| --- | --- |
| Kotlin CLI 编译核心源码和共用用例 | 完成 |
| 53 项独立用例 | 53 通过，0 失败 |
| 相同编译产物再次执行 5 轮 | 每轮均 53 通过，0 失败；不是新增 265 个独立用例 |
| 配置等待／系统授权等待等各阶段取消 | 同源用例覆盖 |
| 异常、超时、清理失败重试、旧结果返回、120 条并发命令资源互斥 | 同源用例覆盖 |
| 不匹配的 Wrapper JAR | 确认拒绝执行且不覆盖原文件 |
| 无 APK 时执行证据收集 | 确认失败退出，不创建成功清单 |
| XML／YAML 基础结构检查、Shell 语法、源码检查 | 完成；不等于 Android Lint 或 CI 运行 |
| 本地基线 `git diff --check` | 通过 |

实际诊断环境：

```text
OpenJDK 21.0.11
kotlinc-jvm 1.9.0
本地 kotlinx-coroutines-core-jvm.jar：语义版本未确认
JAR SHA-256: 78d6cc7135f84d692ff3752fcfd1fa1bbe0940d7df70652e4f1eaeec0c78afbb
```

这不是项目配置的 Kotlin 2.3.10／Coroutines 1.10.2 依赖解析结果。上述诊断只覆盖核心控制流，不能替代 Gradle/JUnit 任务、Compose 编译、Android UI、真实协议内核或网络可靠性验证。

## APK 构建的实际结果

已实际尝试：

```bash
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

入口在取得官方 Wrapper JAR 时停止，退出码 **6**，日志包含：

```text
curl: (6) Could not resolve host: raw.githubusercontent.com
```

当前环境没有 Android SDK，无法解析构建依赖下载域名。Gradle 本身未启动；编译、Android 单元测试与 Lint 任务没有开始。完整失败输出在 `evidence/android-build-attempt.log`，而不是“测试失败”或“编译通过”。

**`gradle-wrapper.jar` 二进制未包含在本包。** 已提供固定来源和 SHA-256 的首次运行引导，联网后下载并校验；`gradlew`/`gradlew.bat` 是项目引导入口，不冒称标准 Wrapper 二进制已经补齐。

## 在具备环境的电脑继续构建

准备 JDK 17、SDK Platform 37、Build Tools 36.0.0；使用 Android Studio SDK Manager 安装，并设置 `JAVA_HOME` 与 `ANDROID_HOME`，或使用本地 `local.properties` 指定 SDK。机器需要能访问 README 列出的官方依赖来源。

解压并进入 `gccwebos-android` 根目录，Windows：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Linux/macOS：

```bash
chmod +x gradlew build.sh scripts/*.sh
./build.sh
```

只有任务全部成功后，才以实际生成的 `app/build/outputs/apk/debug/app-debug.apk` 为安装包。不要把本 ZIP 改扩展名为 APK。首次完整依赖解析或 Android 编译仍可能暴露本环境未覆盖的问题。

## 仍未实现或未验证

没有真实 `VpnService`、TUN、协议内核、邮箱注册登录 API、商品／钱包／订单／推荐接口接入。真实连接按钮保持禁用；Debug 自检是假网关控制流演示，不能转发流量，也不会成为 release 功能。

M1 才接原生账户合同、授权配置与真实内核，并在获准的隔离环境验证连接、断开、换线、Wi-Fi/移动网络恢复、DNS/IPv6、系统撤销授权。协程超时不能强制打断不合作的 native 阻塞；内核适配必须落实自己的资源释放与超时边界。

没有运行 Windows PowerShell、Android 模拟器/真机、Google Play/签名发布、生产或支付验证。本轮未创建远程仓库、PR、提交或运行 GitHub 工作流。原包与其他项目文件未被覆盖。

## 文件与证据

工程内 `evidence/` 保存本轮最新 53 项结果、5 轮复跑、实际构建失败日志、环境信息和负例检查；不混入此前审查的 8 项复现或早期调试轮次。`evidence/changes-from-starter.patch` 记录源文件相对导入基线的差异。

`file-inventory.json` 保存交付文件相对路径、大小和 SHA-256。清单不列自身哈希，避免自引用；ZIP 总哈希另在附件校验文件中。工程不含本地缓存、`.git`、编译器 JAR、SDK、生产密钥或虚构 APK。
