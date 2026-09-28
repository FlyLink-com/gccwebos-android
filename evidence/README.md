# 本轮实际证据

`core-tests.*` 是最后一版核心源码的 53 项离线 JVM 用例；`repeat-1` 至 `repeat-5` 是同一编译产物复跑。不是 Android Gradle JUnit 执行报告，也不是实际 VPN 流量测试。

`toolchain.txt` 记录实际编译工具与本地协程 JAR 哈希；目标依赖版本尚未解析。

`android-build-attempt.log` 与退出码记录真实构建入口在下载 Wrapper 时的 DNS 失败。没有 APK。

`*-negative-test.log` 记录 Wrapper 校验与无 APK 时证据收集的失败保护。

`changes-from-starter.patch` 仅覆盖源文件、脚本、文档；不包含本证据目录、清单或缓存。原始基线为用户上传 ZIP，不是后台仓库。
