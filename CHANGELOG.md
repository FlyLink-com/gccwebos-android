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
