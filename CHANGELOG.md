# 变更记录

## 已合并，未进入公开 v0.4.2 APK · 2026-10-11

核对主干 `724fb7f04c24573a15e50c64b843b39d0012a0e3`；源码仍使用 `0.4.2` / versionCode `9`。以下改动晚于已发布 APK 的源码 `0b94a8c`，下一版身份与验收另行确定。

- #60：导入阶段进度、跨来源延迟测试、节点页来源页签与命名、未知节点选择提示
- #61、#66：第三方导入及页面加载采用 60 秒预算／15 秒对冲；连接步骤仍保留原有 30 秒读取预算
- #64、#65：相册二维码导入；空账户来源的加载状态、重试与删除最后一份配置后的恢复
- #67、#68：套餐卡片更不透明；连接页流量卡片移到连接／节点卡片上方
- #69：记住账号（不保存密码）、系统自动填充提示与登录入场调整

PR 合并、本地验证、实机验证与公开发布分别记账，详见 [当前进度](docs/PROJECT_STATUS.md)。#26、#48、#59、#62、#63 仍为 Draft，不列入已合并功能。

## 0.4.2 · 2026-10-11

[公开 Release](https://github.com/gccwebos-com/feilian-vpn-releases/releases/tag/v0.4.2)，versionCode `9`；发布源码 `0b94a8cee2517624eb6d5a3ac61366ea4d96d361`。

- 订阅提供的流量、节点选择持久化、统一节点来源及配置管理改进
- 连接／套餐／我的／钱包／订单／邀请页与全局动效调整
- 到期与低流量提醒；冷启动订阅读取重试与准备预算改进
- 修复 YAML 表情解析；正式包与对应源码已公开。已记录的冷启动偶发读取失败、会话到期仍需登录等限制见 [发布记录](docs/release-0.4.2.md)

## 0.4.1 · 2026-10-06

[公开 Release](https://github.com/gccwebos-com/feilian-vpn-releases/releases/tag/v0.4.1)，versionCode `8`；发布源码 `5e9014d168b39e34ee069fff6ffbc360d57d661a`。包含多配置管理、第三方导入、账户来源恢复、有效期内会话恢复与官网更新检查。历史实机及协议限制见 [账户来源恢复记录](docs/account-source-recovery-release.md)。

## 更早版本（历史原文）

## 0.2.1-ui · 2026-09-30

- 使用 gccwebos-mobile@091ee6e8 实际tokens、字标与场景进行原生Compose适配
- 保留认证／资源／订阅状态语义；DTO保留服务器已校验的套餐天数与每月流量
- 加入真实显示分支单元测试、字体与取消路径的原生UI测试及可核验设备截图
- 生产VPN依旧禁用；Debug实验室门控不变，未增加支付或购买行为

# 0.1.1-m0 · 2026-09-28

## 输入与边界

输入：`gccwebos-android-starter (1).zip`，SHA-256 `62ad7f4667bd313f9e0530af95ff9ef0bc466c9f8d02e1424f20f5209b85289e`。原 ZIP 未覆盖。

本次是本地 Android 客户端源码实现；不新建或修改真实后台、账户、价格、订单、节点、密钥或线上部署。仓库只在工作目录初始化本地基线用于 diff，无远程提交。

## 代码修复

- 短时命令锁与隧道生命周期互斥分离。连接时的长挂起不再挡住取消命令。
- 新命令取消旧任务；旧任务完成资源清理后，下一任务才能接管全局隧道。
- 排队阶段就被取消的任务会等待其**具体前驱**完成清理，不等待或误停更晚的新连接。
- 对部分启动失败、探测异常、调用方取消、生命周期取消与每阶段超时补齐清理。
- 清理超时／异常保留“可能仍在运行”的安全状态，显示 `CLEANUP_FAILED`，不能假称已断开，也不能直接启动另一个隧道。
- 新增 `switchLine()`、`reconnect()`、`close()`；相同有效连接的普通 connect 保持幂等。
- `AuthorizedConfig` 不再是暴露 ByteArray 默认字符串输出的 data class；增加安全拷贝、redacted 字符串和 close 清零。
- 原有四入口保留，追加可保存导航、滚动布局、资源字符串和测试标签。
- 仅 Debug 源集添加有显著“模拟／未建立 VPN”标识的本地诊断，不开放首页真实连接按钮。

## 构建变化（本轮新增的实现选择）

- Compose Compiler 从 2.2.10 改为 2.3.10，对齐 AGP 9.2 的内置 Kotlin；没有添加重复 `kotlin-android` 插件。
- Compose BOM 从原包 2026.09.00 改为官方说明可确认的 2026.06.01。未成功读取 Maven 元数据，不宣称原版本不存在，也未验证新依赖解析通过。
- compileSdk 37、targetSdk 36、minSdk 26、AGP 9.2.0、Gradle 9.4.1、Activity 1.12.4、coroutines 1.10.2 保留。
- versionCode 改为 2；versionName 改为 `0.1.1-m0`；debug 加 `.debug` 应用 ID 后缀及 `-debug` 版本后缀。
- 添加 Windows/Linux 构建与 Wrapper 校验引导。官方二进制 JAR 未能下载，不制造替代／假 JAR。
- CI Action 固定到本轮读取的 commit；权限仅 contents:read，无发布、部署或生产签名动作。CI 未执行。

## 验证

- 本轮编译生产协调器并执行 53 项同源离线 JVM 行为用例，见构建报告。
- 两项 Android UI 测试、Gradle/JUnit/Lint、APK 签名和真机测试未运行。
- 实际 Android 构建在 Wrapper 下载阶段失败：`curl: (6) Could not resolve host: raw.githubusercontent.com`。
- M0 仍为**实现已推进、Android 构建与接口合同门槛未完成**。

