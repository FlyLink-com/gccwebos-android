# 工具链来源与验证范围

核对日期：2026-09-28。保留 PR #2 已合并的 SDK 36 决定；构建实测以 PR #3 的精确 head 和 CI 回执为准。

- AGP 9.2 兼容信息：Gradle 9.4.1、Build Tools 36.0.0、JDK 17、最高 API 37；发行记录包含 Kotlin 2.3.10 更新。本工程选择 SDK Platform 36 作为 compileSdk，与 targetSdk 36 保持一致；此前 CI 对 `platforms;android-37` 的安装失败。
  https://developer.android.com/build/releases/agp-9-2-0-release-notes
- SDK Platform 36 的稳定渠道发布记录：
  https://developer.android.com/tools/releases/platforms
- AGP 9 的内置 Kotlin：
  https://developer.android.com/build/migrate-to-built-in-kotlin
- Compose Compiler 与 Kotlin 版本对应；本工程固定 BOM 为 2026.06.01：
  https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- Gradle 官方校验值：
  https://gradle.org/release-checksums/
  - 9.4.1 bin ZIP：`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`
  - 9.4.1 Wrapper JAR：`55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`
- Wrapper 固定来源：
  https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar
  PR #3 已取得并入库校验后的 48,966 字节 JAR。引导脚本与源码检查均验证 SHA-256；Gradle 分发包下载另有 distributionSha256Sum 校验。
- CI Actions 固定到完整 SHA，不在执行时追踪可变标签：
  - actions/checkout：`11d5960a326750d5838078e36cf38b85af677262`
  - actions/setup-java：`cf277c60eb25467037889841efdb72551f06f6c3`
  - actions/upload-artifact：`ea165f8d65b6e75b540449e92b4886f43607fa02`

Wrapper JAR 的 META-INF/LICENSE 随二进制保留；其它依赖许可由上游提供。本项目不重分发 Android SDK、完整 Gradle 分发、真实代理内核或签名私钥。不为用户自有源码虚构开源授权；公开发布前另行确定本项目 LICENSE 与依赖 notice 清单。
