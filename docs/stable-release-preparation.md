# 飞连 VPN 0.4.0 正式发布准备

本文件保留 0.4.0 发布前的历史准备与验证记录。0.4.0 仍为草稿；后续配置来源恢复修复及 0.4.1 候选见 [账户订阅恢复](account-source-recovery-release.md)。

2026-10-05；风险 A 档。用户已明确要求合并 Android PR #22/#23、发布正式 App、更新官网下载，并补充自动更新。此文件区分已完成合并、可验证候选与实际公开发布，不能作为已发布证明。

## 已完成合并

- #22：验收 head `f428e45f87d3b069cbc63599724179e7770e8038`，合并 `4e8f1e3b19fa44578b2ab2d1762eab296b876d72`。
- #23：验收 head `a05e0d2f366f5118e5ae116850fd8394330b93f9`，合并 `233abfa4bd7d51aaee27a1bd34d61b2c24fa254d`；先将依赖 base 调整至 main，dry merge 无冲突。
- 最终 main tree 为 `3b4c015710caea855adcd4ec3da1c8c0322a45b8`，与 #23 验收 head 完全相同。两份 PR 无 review thread 或待解决评论；本次合并依据用户明确指令和最新验证，不把无评论称为正式审查批准。
- #22 M0：277 通过、1 跳过、0 失败；Native：278 通过、0 跳过，另有独立 Real TUN 1 项通过。
- #23 M0：311 通过、1 跳过、0 失败；Native：312 通过、0 跳过，另有独立 Real TUN 1 项通过。
- M0 的跳过项是无内核 APK 的 JNI 项；专用 copy-boundary job 因分支条件跳过。Native 的真实 TUN 使用合成 SS TCP/UDP/DNS 与生命周期场景，不证明所有真实第三方订阅可转发。

精确验证：[PR22 M0](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37312066851)、[PR22 Native](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37312066917)、[PR23 M0](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37351832033)、[PR23 Native](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37351831910)。后续发布准备修改不继承这些测试结果。

## 正式产物

正式包名沿用既定 `com.gccwebos.android`，计划 versionCode `7` / versionName `0.4.0`，标签 `v0.4.0`。release 不可调试、无调试实验室组件，启用已验证的域名准备路径；synthetic、storage acceptance、native lab、TUN acceptance 均关闭。双 ABI 原生库与许可文件必须匹配固定摘要，release 构建不允许缺失内核。

当前 `.candidate` 测试包与正式包身份不同。首装正式包需要用户在 App 内自行登录；本轮不复制测试包数据库、Keystore、账户会话或订阅链接，也不卸载测试包。此后正式升级保持包名与发布签名，并增加 versionCode。

正式签名尚待用户确认已有密钥或新建方案。工程保持 unsigned release 可构建，由外部 `apksigner` 对最终产物签名；不会在 Git、构建日志或发布包中放置私钥和口令。未得到签名材料不能声称 APK 可安装或正式发布。

如需首次建立签名，建议专用、仓库外、受限 ACL 的签名目录，仅保管发布 keystore；口令通过操作系统凭据保管机制或操作者交互输入，不写 Gradle/脚本/命令行。另需用户控制的加密备份和恢复验证，才能形成长期升级链。方案尚未执行，也没有创建或移动密钥。[Android 签名与密钥保管要求](https://developer.android.com/studio/publish/app-signing)。

## 自动更新与分发

使用现有正式包更新合同：`https://gccwebos.com/android/releases/stable.json`，schemaVersion 1；仅接受同源 `/android/releases/<filename>.apk`。自动检查的目标行为和验收见 [官网更新](official-updates.md)。后台检测与用户确认下载/系统安装分开，不宣称普通 App 能静默安装自身。

Android GitHub 仓库当前为 private；私有 Release 附件不是公开官网下载地址。官网下载需先部署可匿名取得的版本化 APK、完整对应源码和校验摘要，再使 stable.json 为 available。不得将未签名包或测试 candidate 填入 stable 清单。现有官网 PR #22 提供 unavailable 清单与验证器，可复用并重新验证。

只读盘点时官网 master 为 `8d1ed3e4141128f6df11845dfbb638088d471ab1`，双域名正常，stable.json 返回 404。正式站项目为 `proxy-gateway-minimal`；当前部署 `dpl_8MPS65uDCxxEsQqgvMdbn9fCm8iD` 可作为回滚锚点，执行前仍须复核未漂移。大文件托管与可重复部署方案需要在最终 APK/源码大小确定后验收，当前未上传资产、未切换清单或域名。

## 首轮准备验证

- 定向更新 JVM 37 项、发布门禁脚本 18 项通过，均 0 失败 / 0 跳过。release dry-run 包含原生校验依赖，实际原生校验任务通过。
- `assembleRelease`、`lintRelease`、`assembleDebug`、`assembleDebugAndroidTest` 通过。release Lint 0 error / 27 warning / 6 hint，包含 KTX 风格建议；未声称零警告。
- 未签名 APK SHA-256：`92781f801064ab521491490820e46a8ab6a07d3625e4b8f4a708e63100aca025`，139,985,834 bytes。实际包名/版本、DEX 开关、非调试、实验组件排除、许可资产、双 ABI 固定摘要、ZIP CRC 和 16 KiB ZIP/ELF 对齐均核对通过；确认为未签名，未安装或上传发布。
- 独立只读复核通过；新增自动更新代码处理已发现的持久化版本上界、Home 返回去重和生命周期类型问题。首轮构建的 lifecycle 类型错误保留为失败记录，不计入通过结果。
- OPPO 只读检查显示锁屏，本轮未安装新包、未改变现有 candidate 或其数据。8 项新增 Android 测试与既有更新 UI 回归待解锁执行；新提交完整 CI 尚待完成。
- 结构化记录见 [validation.json](evidence/stable-release/2026-10-05/validation.json)，本地原始日志在 `.build-output/stable-release/`。

## cc3c1e5 CI 修正与实机回归

[M0 run 37360418624](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37360418624) 的构建/JVM/Lint 通过，主 UI 批次报告 318 项、6 次失败。实际是三个新增存储用例分别在 `@Before` 和 `@After` 失败，测试体未进入；logcat 显示测试 APK 的 `shared_prefs` 目录创建返回 ENOENT。不是六个不同业务用例失败，也没有证明账户存储故障。fixture 改用 target UID 下的 UUID 文件名，保留成功提交断言和精确清理，生产存储代码未改。

[Native run 37360418454](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37360418454) 的原生输入哈希、编译及 1124 项 JVM 通过；失败于 release APK 内核字节摘要校验，后续 JNI/UI/Real TUN 未运行。Linux 日志确实执行 NDK strip，本地 Windows 则原样打包。已取得的 Linux debug APK 两个 ABI 均与原始输入等长、可执行段一致，但 `.shstrtab` 及 ELF 索引元数据发生变化，完整 SHA-256 随之改变；旧 artifact 未保留 release APK，其具体改写路径是由共同打包步骤推断，直接观察到的是 release 摘要不符。

按 [AGP 9.2 原生库打包 API](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/JniLibsPackaging)，仅为已由 Go `-s -w` 处理的 `**/libgccmihomo.so` 禁用 AGP 二次 strip，各 variant 保留固定原始字节。不扩大到其他库、不改变 release 可调试标志，也不放宽原始/包内摘要、许可或 ELF 门禁。

- OPPO 旧包 RED：3 个存储 case / 6 个 hook 失败；修正后存储 3、自动 UI 5、原手动更新 UI 3，共 **11 项通过，0 失败 / 0 跳过**。
- 发布门禁新增缺失/扩大/条件化 strip 例外边界，RED 后 **21 项通过**；独立只读复核及源码/diff 检查通过。
- release/debug/test APK 与 release Lint 再构建通过，Lint 0 error / 27 warning / 6 hint。未签名 release 和普通 debug APK 在本地均与修正前字节一致，测试 APK 更新；release 实际 flags、双 ABI 摘要及 ZIP/ELF 对齐重新核验。
- 自有 debug app/test 已卸载并确认不存在；普通 candidate 仍匹配既有交付 SHA-256，未重装、清数据或修改显示设置。该合成 UI 批次不算正式签名产物安装或升级验收。
- 原始日志、CI artifact 对比和设备结果位于 `.build-output/ci-cc3c1e5/`，摘要见 [ci-fix-validation.json](evidence/stable-release/2026-10-05/ci-fix-validation.json)。新的完整 CI 仍须验证 Linux 打包与全部运行时用例。

## 发布验证与回滚

1. 完成源码、针对性测试、完整 CI、release 构建和 Lint；独立验证最终 APK 包名/版本、非调试、DEX 开关、组件与权限、固定双 ABI、16 KiB 对齐、签名证书及 ZIP 完整性。
2. OPPO 保留现有 candidate，安装正式签名产物，验证启动、正式登录/配置/连接及再次进入；升级演练须使用自有旧正式测试安装，不能清除用户数据代替升级。
3. 由最终源码提交重建对应源码包。旧 rc.1 的同版本依赖源码可核验复用，项目源码必须重新导出并逐 blob 校验；依赖图、原生锁与许可说明必须与产物一致。
4. 官网候选先验证 available/ unavailable/非法清单和双语下载；实际下载字节、版本、证书与正式产物一致。公开 APK 与源码均可匿名取得后，再发布稳定清单。
5. 记录实际发布 commit/tree、APK 与源码 SHA-256、证书摘要、Release URL、官网 deployment ID、双域名检查及回滚锚点。

官网回滚恢复上一个已验证部署及 stable 清单；已安装 APK 用同包同签名、更高 versionCode 的前向修复。配置库 v3 写入后旧 APK 不可直接读取，不能通过降级或清数据作为正常回滚。

历史 OPPO B2 P7 超时仍未查明，P8/P9 未运行；端点延迟不代表完整代理认证或 VPN 转发。这些边界继续保留在最终发布说明，不能因本轮合并或合成测试通过而抹去。
