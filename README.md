# gccwebos Android · 0.1.1-m0

基于用户上传的 `gccwebos-android-starter (1).zip` 进行增量实现，保留单一 `app` 模块、四入口和 Gateway 分层。

**这是首轮构建源码包，不是 APK，也不是可连接的 VPN 产品。** 本轮已实现连接协调器修复、同源回归用例、Debug 工程自检和构建入口。Android 构建因当前环境无法解析依赖下载域名而停止；没有 Android SDK，未执行 Gradle 的编译／JUnit／Lint 阶段，未做真机验收。准确状态见 [BUILD_STATUS](docs/BUILD_STATUS.md)。M0 的“APK、CI 实际运行、接口合同确认”仍未完成。

## 本轮已有的代码

| 范围 | 实现 |
| --- | --- |
| 连接协调器 | 取消在途连接；按尝试隔离结果；异常与部分启动清理；换线；重连；生命周期取消时关闭连接；清理失败时阻止启动新隧道 |
| 配置材料 | 防御性字节拷贝、安全字符串表示、关闭后清零、禁止从已关闭对象取材料 |
| 四入口 | 连接／线路／套餐／我的；状态恢复、滚动布局与测试标签。真实连接仍明确禁用 |
| Debug 自检 | “我的”内提供**仅本地模拟**的工程控制：正常、配置等待、探测失败、无套餐；可运行、取消、换线、重连。无网络／TUN／真实账户 |
| Release 分离 | 模拟网关仅在 `src/debug`；release 源集为空实现，不带模拟控制或测试节点 |
| 测试 | 53 项同源行为用例，通过隔离 JVM 执行；JUnit 参数化入口已写好；另有 2 项 Android UI 测试源文件，尚未运行 |
| 构建 | 固定依赖版本、Windows/Linux 入口、官方 Wrapper 校验引导、CI 构建／报告／Debug APK 收集工作流 |

本轮没有创建远程仓库、提交 GitHub、启动远程 CI、修改后台、使用真实节点、操作支付或生产系统。

## 第一次构建（Windows）

准备 JDK 17、Android SDK Platform 36、Build Tools 36.0.0。通过 Android Studio SDK Manager 安装所需组件并自行确认 SDK 许可。设置 `JAVA_HOME`，SDK 通过 `ANDROID_HOME` 或本地 `local.properties` 指定。构建时需要访问 Gradle、Google Maven、Maven Central 与官方 Wrapper 来源。

在解压后的 `gccwebos-android` 目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

这仅对本次 PowerShell 进程使用执行策略，不修改系统永久策略。成功后才会存在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug 应用 ID 为 `com.gccwebos.android.debug`，版本为 `0.1.1-m0-debug`，避免覆盖未来正式版。Gradle 自动产生本地 Debug 签名；本包不包含签名私钥。不同机器生成的 Debug 签名可能不同，不能当成正式升级签名。

## 第一次构建（Linux／macOS）

```bash
chmod +x gradlew build.sh scripts/*.sh
./build.sh
```

实际执行：

```bash
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

有连接的设备或模拟器时，可另外执行 `./gradlew :app:connectedDebugAndroidTest`。这不是本轮已经运行的命令。

## Wrapper 的准确状态

`gradlew`、`gradlew.bat` 是本项目的校验引导启动器，并非假称为 Gradle 自动生成的原脚本。**二进制 `gradle-wrapper.jar` 因下载限制没有装入本包**；首次运行启动器将从 `gradle/gradle` 官方仓库下载它，先与固定 SHA-256 对比，再交给官方 Wrapper 下载校验过的 Gradle 9.4.1 分发包。已有文件校验不匹配时立即停止，不执行、不自动覆盖。

引导完成后可把已验证的 JAR 提交到 Android 客户端自己的仓库；后续可用 Gradle 9.4.1 的 `wrapper` 任务重新生成标准脚本。不能将“存在下载脚本”记作本轮已取得 JAR 或已运行 Gradle。

## 固定工具链

| 项目 | 本包配置 |
| --- | --- |
| Android Gradle Plugin | 9.2.0（保留原包） |
| Gradle 分发 | 9.4.1，固定 SHA-256 |
| Kotlin／Compose Compiler | AGP 内置 Kotlin 对齐 Compose Compiler 2.3.10 |
| Compose BOM | 2026.06.01：改用本轮官方文档可确认的版本，非“2026.09.00 不存在”的断言 |
| Activity Compose | 1.12.4（保留） |
| Coroutines | 1.10.2（保留） |
| compile / target / min SDK | 37 / 36 / 26（保留） |
| Java 字节码目标 | 17 |

当前网络环境不能完成 Maven 解析，以上是工程固定输入，不是已解析的构建依赖清单。变更理由和官方来源见 [CHANGELOG](CHANGELOG.md) 与 [工具链来源](docs/TOOLCHAIN_SOURCES.md)。

## 离线诊断与正式验证区别

有 Kotlin CLI 和本地 Coroutines JAR 时：

```bash
./scripts/test-core-offline.sh
```

脚本编译原始生产 `ConnectionCoordinator.kt` 和与 JUnit 共用的 `ConnectionCases.kt`，不会复制另一套协调器。输出 `.local-tests/core-tests.xml` 与日志。**本轮实际工具是 Kotlin 1.9.0／JDK 21.0.11 和本地协程 JAR，而不是目标 Kotlin 2.3.10／coroutines 1.10.2**。53/53 只证明该边界的控制流回归，不替代 Android Gradle、UI 或真机验收。准确 JAR 哈希随证据提供。

## 工程与后续范围

```text
app/src/main/.../core/         生产连接状态与网关合同
app/src/main/.../MainActivity  四入口，真实功能明确未接入
app/src/debug/.../             仅 Debug 的本地模拟控制
app/src/release/.../           排除模拟控制的实现
app/src/test/.../              同源行为用例与 JUnit 入口
app/src/androidTest/.../       真实设备／模拟器 UI 用例（未运行）
scripts/                      引导、验证与证据收集
.github/workflows/android.yml  准备好的 CI，未触发
```

M1 继续依赖获准的原生认证、账户权益、授权线路／配置、内核许可证与隔离测试节点。本包**没有** `VpnService`、协议内核、邮箱登录 API、真实套餐／支付／订单／推荐归因接入，不新增第二套后台或财务账本。自动网络恢复、系统撤销授权、DNS／IPv6／流量路径验证仍需 M1 真机完成。

- [连接协调器合同与迁移](docs/connection-coordinator.md)
- [原有接口合同](docs/backend-contracts.md)
- [原有开发里程碑](docs/roadmap.md)
- [任务清单](TASKS.md)
