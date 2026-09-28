# 工具链来源与验证范围

本轮核对日期：2026-09-28。以下是外部官方资料，不是用户源包内容，也不构成依赖下载成功、Android 编译成功或 Google 审核证据。

- AGP 9.2 兼容信息：Gradle 9.4.1、Build Tools 36.0.0、JDK 17、最高 API 37；发行记录包含 Kotlin 2.3.10 更新。本工程选择稳定渠道的 SDK Platform 36 作为 compileSdk，与 targetSdk 36 保持一致；此前 CI 对 `platforms;android-37` 的安装失败。
  https://developer.android.com/build/releases/agp-9-2-0-release-notes
- SDK Platform 36 的稳定渠道发布记录：
  https://developer.android.com/tools/releases/platforms
- AGP 9 的内置 Kotlin：
  https://developer.android.com/build/migrate-to-built-in-kotlin
- Compose Compiler 与 Kotlin 版本对应；本轮官方说明中的稳定 BOM 为 2026.06.01：
  https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- Gradle 官方校验值：
  https://gradle.org/release-checksums/
  - 9.4.1 bin ZIP：`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`
  - 9.4.1 Wrapper JAR：`55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`
- Wrapper 引导固定来源：
  https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar
  下载后必须匹配校验值；二进制未装入本次 ZIP。
- CI Actions 的 v4 标签本轮通过 GitHub 连接读取后固定到 SHA，不在执行时追踪可变标签：
  - actions/checkout：`11d5960a326750d5838078e36cf38b85af677262`
  - actions/setup-java：`cf277c60eb25467037889841efdb72551f06f6c3`
  - actions/upload-artifact：`ea165f8d65b6e75b540449e92b4886f43607fa02`

依赖许可证由各上游项目提供。本包没有重分发 Android SDK、Gradle 分发、Kotlin/Compose 二进制、真实代理内核或任何签名密钥。没有为用户自有源码虚构开源授权；公开发布前另行确定本项目 LICENSE 与依赖 notice 清单。
