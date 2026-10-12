# Windows Android 开发工具链

2026-10-02 本机补齐并核验。用户已授权安装工具链、构建 APK 和在 OPPO 测试机安装调试。

| 工具 | 固定版本 / 本机位置 |
| --- | --- |
| Java | Microsoft JDK 17.0.20.1；`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` |
| Android SDK | `C:\Users\g\AppData\Local\Android\Sdk` |
| Command-line tools | 22.0，官方 ZIP SHA-256 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` |
| Platform / Build Tools | android-36 revision 2 / 36.0.0 |
| Platform Tools / ADB | 37.0.1 / 1.0.41 |
| NDK | 29.0.14206865 |
| Gradle / AGP / Kotlin | Wrapper 9.4.1 / 9.2.0 / 2.3.10 |

用户环境已设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 和相应 PATH。已开启的 PowerShell 会话需载入本机 helper：

```powershell
. 'C:\Users\g\AppData\Local\Android\android-dev-env.ps1'
.\gradlew.bat --version
```

helper 同时把 Windows PowerShell 5 系统模块路径放在 `PSModulePath` 首位：`gradlew.bat` 的 checksum bootstrap 会调用 Windows PowerShell 5，不能继承仅适用于 PowerShell 7 的 Utility 模块。

Gradle 分发包和 Wrapper JAR 均按仓库固定 SHA-256 校验。已执行联网任务图解析，随后 offline dry-run 成功；插件与 JVM 测试依赖已缓存。离线 dry-run 不等于设备测试。

```powershell
.\gradlew.bat --no-daemon --console=plain --offline --dry-run :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
python scripts/verify_source.py
```

VPN 候选先调用 `scripts/materialize-native-libraries.py` 核验原生源码身份、artifact metadata、ZIP 摘要与两个 ABI，再构建当前 APK。`-PconnectionCandidate=true` 使用独立 `com.gccwebos.android.candidate`，并再次检查两个原生库的固定摘要和许可证。不得用旧 APK 替代当前源码构建。

```powershell
.\gradlew.bat --no-daemon --console=plain -PconnectionCandidate=true :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

`-PconnectionSyntheticAcceptance=true` 只能与独立候选包同时使用；它加入一次性 SS fixture 验收，并将 TUN 范围限定为候选 App。默认产品路由与该验收范围分开记录。

Windows 基线的 484 个 JUnit 测试在修正 LF fixture checkout 后仍有 8 个失败，来自 NTFS 不支持 POSIX 初始文件权限；生产存储继续 fail closed，没有跳过测试或放宽 0600/atomic replacement。Android 的真实私有存储、TUN 与流量证据须另行取得。完整结果见本轮连接开发记录。

OPPO 只记录型号 PFJM10、Android 13/API 33；不记录 serial、账号、订阅 URL、配置或凭据。普通飞连VPN 包与临时独立候选分开，禁止以卸载旧包或清除旧数据解决签名冲突。
