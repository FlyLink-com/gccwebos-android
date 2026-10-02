# VPN 本地准备与执行记录

2026-10-02（America/Los_Angeles）。一键解析/连接实现已完成，OPPO 的 synthetic SS 真实 JNI/TUN 完整用例通过；不含 fixture 的正常独立候选包已安装。范围与证据见 [一键连接](one-click-vpn-connection.md)。真实订阅节点/其他协议、正式签名发布与生产部署未验证或未执行。

## 授权与风险

用户已授权继续开发、统一补齐工具链，并允许 OPPO 测试机直接安装 APK、调试和 VPN 测试，无需重复确认。本轮按 B 档将 intent、code、tests 和结果集中交付；权限、跨进程 ownership、凭证和清理使用严格边界。没有 AWS/Nginx/systemd、后端 auth/schema、节点或生产部署变更。

原工作目录 `D:\Projects\gccwebos-android` 的 main / `abb7b7c717a5911b88f1883d17cb99e28f3aaaea` 保持不动且 clean；实现位于 managed worktree `C:\Users\g\.codex\worktrees\vpn-production-prep\gccwebos-android`，分支 `codex/vpn-production-prep`，基线 `e4b3cf3a06e0e6a8add01ac895b3ed524847a845`。基线不是当前候选完整源码身份，应连同本分支源码、运行源文件摘要和实际 APK 回执审查。

SDK/Gradle 缺失、未实现 adapter、未构建等旧准备状态已被当前事实替代。JDK 17.0.20.1、Gradle 9.4.1、AGP 9.2.0、Kotlin 2.3.10、Platform 36、Build Tools 36.0.0、Platform Tools 37.0.1、NDK 29.0.14206865 已安装并校验；详情与 WinPS module path helper 见 [工具链](android-development-toolchain.md)。不依赖完整 Android Studio/emulator 安装来执行本地 OPPO 验证。

## 实现与验证

- 同一个本地 authority 负责 acknowledged capability、opaque selection、新鲜下载、严格投影与同步 activation。首次单节点便利选择需两次 fresh read，多节点/变更需明确选择。
- service-owned coordinator 与 permission tickets、exact Binder receipt、remote admission deadline、JNI/TUN、同 UID VPN Network probe、独立文件清理已接线。
- native 原始输入和 projector 未改；校验复用 artifact 的 arm64-v8a/x86_64 libraries，两个 ABI ELF >=16 KiB alignment，APK 含 GPL notices；release 排除 Lab 组件，正式 release 未签名发布。
- Binder flags 平台辅助位被旧同步 guard 误拒绝的问题已修复；OPPO 实际 flags=18，copy、JNI、探针与两次连接成功。
- 验收先通过一键连接、TCP/UDP/DNS，随后验证导航/Activity 重建保持原 Binder/Network、停止后真实死亡/TUN/材料清理、fresh download + 新 Binder/Network 重连。1 test / 0 failures，Portal requests=0，测试仅候选应用流量。
- fixture DNSMapping 碰撞通过仅本新用例的 DNS answer .11 解决，默认 B1/Lab fixture 保留 .10。未放宽 nonce/counter 或配置投影规则。
- 全量单测 537 项：529 pass / 8 原 Windows NTFS 权限失败 / 0 error / 0 skip。完整 APK/Lint 任务完成但全量命令仍因这 8 项失败返回失败；正常候选构建与 Lint 分别成功，0 lint errors / 28 warnings。

[OPPO 回执](evidence/one-click-vpn/2026-10-02/oppo-synthetic-ss-acceptance.json)、[阶段记录](evidence/one-click-vpn/2026-10-02/oppo-phases.txt)、[正常安装 APK](evidence/one-click-vpn/2026-10-02/normal-candidate.json)、[单测](evidence/one-click-vpn/2026-10-02/windows-junit-summary.json) 为本轮证据；较早失败仍保存在 ignored `.build-output/connection/oppo-attempt-*/`，不删除或标为通过。

## 设备保留与回退

普通 `com.gccwebos.android.debug` code 6 / `0.4.0-mobile-parity-debug` 保留，未卸载、清数据或覆盖。其 APK 曾只读拉取：14,096,175 bytes，SHA-256 `b7766768c6d9471c986e26272af833dacb9786e619ba4b4e04e42aef1ab7a1f4`，signer `d4bc605f2cc806806a24f3b818e8bda2791bcbc3bd2e383ca225145701424424`。本地候选 signer 为 `08a952d8f10486ff79cb7b00e58ee8618425bf3286e778147da1a8b008a373d4`，已通过 apksigner 核验；不同签名不推导为可兼容升级/回装。

synthetic app/test 包及数据在验证后已卸载；随后安装正常独立 `com.gccwebos.android.candidate`，显示“飞连 VPN 测试”。其 installed APK hash 与本地文件一致，synthetic flag=false、无 fixture/test class。回退此候选只需停止并卸载该候选包，不触碰普通 app/data；源码保留隔离分支，未 reset 原目录。没有宣称 ordinary APK 的回装验证通过。

## 未覆盖门禁

真实订阅节点、非 SS Android runtime、所有 API/OEM 的 FGS/通知/休眠、全设备 DNS/IPv6/Private DNS 防泄漏、正式 signing/distribution/release readiness 未通过本用例证明。Windows 8 项需在支持 POSIX 初始属性语义的环境验证，不用 mock/focused green 抹去。

用户补交 `engine-e2b-b2-oppo-actual-f1e6722.zip` SHA-256 `26f6b89b06906f42ff968f19a4742275c976c364b196a4a04767343dd3351b70` 保持原样；B2 P7 timeout，P8/P9 按失败停止规则未运行，B2 未通过。B1 六项历史通过单独记录。另建的 f1e6722 P7 diagnostic build 未安装/执行，本轮优先一键连接，不用本次成功解除 B2 门禁。
