# 飞连 VPN Android · 开源许可合规基线

状态：Draft / BLOCKED（不是正式开源授权声明）

本目录建立 Android 仓库的许可证、第三方依赖、品牌资产权利链和发行放行门禁。目标主许可证为 **GPL-3.0-only**，但在版权主体、贡献者权利、依赖许可证和品牌素材授权全部核实前，不得添加根目录 LICENSE，不得把本仓库描述为已经完成 GPL 授权。

## 公开维护组织与版权主体区分

- 公开项目维护组织：`FlyLink-com`（GitHub organization account），角色仅为公开项目维护与协作入口。
- 该组织名称不构成版权归属证明，不代表已确认的法定主体，也不推断其与 `gccltd` 为同一法律主体。
- 本 Draft 暂不公开法定版权主体名称和司法辖区；`license-policy.json` 中 `copyrightHolder.legalName` 与 `jurisdiction` 保持 `null`，`publicDisclosureStatus` 为 `NOT_PUBLICLY_DISCLOSED`，`verificationStatus` 仍为 `UNVERIFIED`。
- 暂不公开是披露策略，不是权属核验豁免。正式发行前仍须通过适当的内部证据核实版权主体及其授权范围；不得把敏感主体文件提交到公开仓库。
- 发行时版权声明、许可证页面是否以及如何标示权利主体，须由最终法律审查确定。在审查批准前，不得把 `FlyLink-com` 写成版权所有者。

## 本次范围

- 仓库：FlyLink-com/gccwebos-android
- 基线：main，提交 c19d5bf57388b427a5e8248d838cbf3e87b39d47
- 本 PR：仅添加 docs/compliance/**，不修改 Android 源码、构建脚本、根 LICENSE、原生引擎或发布物。
- 本 PR 不代表其他 FlyLink 仓库已取得同一许可证或相同版权归属。

其他仓库（Gateway、Site、Mobile）须分别完成来源、版权主体、第三方依赖及素材审计，不能因组织或项目名称相近而自动并入本授权范围。

## 已知基线

1. native/source-lock.json 固定 Mihomo v1.19.31，Commit ab405bad5beeeac8b003bb01f60f134f6df54471。该精确上游版本的 LICENSE 是 GPL Version 3；source-lock 中旧 SPDX 值 GPL-3.0 在本目录报告中规范化为 GPL-3.0-only。
2. native/bridge 的 Go 文件存在 GPL-3.0-or-later 文件级声明。保留原声明；它表示可选择 GPLv3，不得未经版权核验擅自缩窄或改写。
3. ZXing Android Embedded 4.3.0 和 ZXing Core 3.4.1 的 Apache-2.0 许可证、NOTICE/归属文本已随工程保存。
4. docs/branding-assets.json 与 docs/native-ui-assets.json 对部分同名资产记录了不同的 bundled hash。工具会将实际文件与两份清单逐项比较；未核清前保持 HOLD。
5. native-ui-reference.md 指向私有 gccwebos-mobile 仓库和资料来源。来源记录不等于公开再分发授权。

## 工具（仅 Python 标准库）

从仓库根目录运行：

~~~bash
python3 docs/compliance/tools/audit.py
python3 -m unittest discover -s docs/compliance/tools -p 'test_*.py'
python3 docs/compliance/tools/release_gate.py
~~~

- audit.py：读取 Gradle 声明、Go module 清单、Mihomo source lock、SPDX 文件头和品牌资产清单；生成保守的 SPDX 2.3 manifest SBOM 与审计 JSON。
- release_gate.py：重新生成审计结果并检查放行条件。当前预期结果是 BLOCKED，退出码 1；这不是工具失败，而是权利链和许可证扫描尚未收口。
- 生成物写入 docs/compliance/generated/，不应被当作已验证的最终发行 SBOM。

## SBOM 范围限制

默认生成的是 manifest SBOM：Gradle 直接声明与 Go go.mod 模块图，不是经过 Gradle 实际解析、按 ABI/构建标签确认的最终 APK 运行时清单。未核实许可证的组件使用 NOASSERTION，不猜测许可证。正式 Release 还必须生成与精确 APK/ABI/Go build tags 对应的完整 SBOM，并保留解析日志、二进制哈希和源码对应关系。

## 放行原则

- 根 LICENSE 只在版权主体和全仓库权利链确认后添加。
- 品牌图、角色、字标、图标、字体和商标逐项审批；HOLD 素材不得进入公开发行物。
- 所有第三方组件保留原始许可证、NOTICE、版本、来源和修改记录。
- 商业双重授权仅能覆盖项目主体有权再授权的原创代码；不包含 Mihomo、ZXing 或其他第三方代码。
- GPLv3 对特定 User Product 的安装信息要求、APK 对应源码和构建/安装脚本，须在实际发行模式下单独验收。
- 本目录是工程合规控制，不替代适用司法辖区的法律意见。

## 当前状态

**BLOCKED**。版权主体法定名称、原创代码权属、历史贡献者/雇佣/外包协议、完整传递依赖许可证、品牌素材授权、对应源码发行包及法律审查仍待核验。


## 品牌素材字节核验

本目录的 `ASSET_HASH_RECONCILIATION.md` 记录了 PR 基线 Head 上全部登记素材的实际字节 SHA-256。审计工具同时检查登记路径是否存在、实际字节是否匹配已声明/核验哈希，以及是否存在未登记素材。哈希通过不代表取得版权、公开再分发、修改或商标授权；发行门禁仍要求独立的权利证据。
