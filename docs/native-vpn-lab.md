# M2：原生 VPN 隔离实验（开发中）

## 当前范围

沿用原生 Kotlin/Compose 工程，叠加在 M1 账户／资源／官网更新之上。生产“连接”仍禁用。本切片不使用账户订阅、不接生产节点、不修改后台或服务端配置。

原生依赖目标：官方 Mihomo `v1.19.31`，commit `ab405bad5beeeac8b003bb01f60f134f6df54471`；CMFA 源码参考 `559594d4f99600d592757b23b2ee8e9b924512e4`。不使用同名默认分支、不下载第三方未知预编译 AAR。原生 bridge、固定工具链、GPL notices 和构建／包路径证据已保存于 native/。两个ABI已本地交叉构建；这不代表Android设备VPN验收完成。

## 安全门

- VpnService、Activity、前台服务权限仅存在于 debug source set；默认禁用，必须显式 `-PnativeVpnLab=true`
- 真实生产连接入口不接通。服务只读代码内的 loopback 假数据；不接受 Intent、IPC、存储或账号订阅传入的凭据与配置
- Android 系统 VPN 权限回调不会启动隧道；用户必须再次显式点击。停止、离开、旋转后的迟到结果不能启动 VPN
- 仅捕获本应用 UID 流量，IPv4 全路由与 IPv6 捕获；不调用 allowBypass/allowFamily。原生层必须显式拒绝 IPv6 并禁用 ICMP DIRECT 转发
- 固定 MTU1500、IPv4 172.19.0.1/30、DNS172.19.0.2，IPv6捕获地址fdfe:dcba:9876::1/126
- 先验证物理 underlying network 的 INTERNET+NOT_VPN；protect失败或bindSocket失败即拒绝socket。使用临时dup绑定物理network，不夺走core FD；物理network丢失即停止
- 专用 :vpn 进程串行管理JNI生命周期；native复制并拥有TUN FD，Java拥有原PFD。撤销权限、页面退出、任务移除、服务销毁均停止；4秒独立看门狗终止原生阻塞进程，不能依靠协程超时中断JNI
- 状态“内核运行但未验流量”与真实连通明确区分；不使用ping证明代理可用
- 状态广播需signature权限；新系统同时NOT_EXPORTED；不广播配置、URL或异常原文

## 配置投影

SafeProxyConfig 严格解析有界UTF-8 YAML（64KiB、深度8、事件8192、最多128条proxy），拒绝tag/alias/anchor/重复键和多文档。首片仅允许Shadowsocks AEAD、IPv4字面量endpoint、整型端口；不支持插件、动态provider、脚本或任意文件。展示名称/数组位置不是授权lineId。

只向core传受控最小JSON：一个固定名字的静态SS条目以及固定MATCH规则。原订阅DNS、rules、groups、端口等永远不合并或执行。native必须独立再校验并重建所有Tun/DNS/监听配置；不能暴露管理API或自动下载geodata/provider/rules。此第一片并不声称支持全部现有后台订阅。

## 验证与未完成项

已通过21项新增JVM用例：恶意YAML与敏感信息脱敏、配置投影、取消/迟到权限、TUN和native部分启动清理、停机失败保险与FD所有权。

已通过真实gVisor→SS AEAD回环fixture的TCP HTTP、UDP echo与代理DNS包路径；拒绝protect时无外连，IPv6/ICMP不创建外连socket。arm64-v8a与x86_64 .so已构建并校验JNI导出和16KiB ELF对齐。回执见native/evidence。

仍需完整Android编译/Lint、Android模拟器JNI加载/TUN/权限/停止、物理设备DNS/IPv6/Wi-Fi蜂窝切换。当前“源码检查通过”不能替代这些验证。尤其Android将loopback socket绑定物理Network的兼容性需实测；失败时保留fail closed，不能为了跑通删除protect。

GPL客户端分发时的完整对应源码、许可证及第三方notice必须随发布准备；用户许可方向确认不代表可以将所有仓库公开。正式包签名和公开发布仍另行审批。
