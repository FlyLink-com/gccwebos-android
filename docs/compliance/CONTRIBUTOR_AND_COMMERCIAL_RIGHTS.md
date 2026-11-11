# 贡献者权利与未来商业再授权

## 目标

保留未来对 FlyLink 原创代码进行商业授权或双重授权的可能性，同时不削弱公众依 GPL-3.0-only 获得的权利，也不覆盖第三方组件。

## 公开维护组织与版权主体区分

- `FlyLink-com` 是公开 GitHub 组织及项目维护入口，当前角色限定为 `PUBLIC_PROJECT_MAINTAINER_ONLY`。
- 不据此声称 FlyLink-com 拥有项目版权、构成法定版权主体，或与 `gccltd` 属于同一法律主体。
- 本 Draft 暂不公开法定版权主体名称和司法辖区；政策记录中的 `legalName`、`jurisdiction` 保持空值，公开披露状态为 `NOT_PUBLICLY_DISCLOSED`，核验状态继续为 `UNVERIFIED`。
- 暂不公开不等于免除内部权属核验。正式发行前仍须以可核验证据确认实际权利人、权利范围及再授权权限；敏感主体文件不放入公开仓库。
- 发行时应如何提供版权声明、许可证页面及权利人标示，留待适用司法辖区的最终法律审查确定。

## 版权主体先决条件

正式发布前必须在内部核实：

- 法定公司或个人名称、注册地/司法辖区及可验证的主体资料；该核验不要求在本 Draft 中公开披露。
- Android、Gateway、Site、Mobile 各仓库的权利归属；
- 代码作者、雇员、外包商、合作方和历史贡献者的权利链；
- 品牌角色、Logo、字标、字体、场景和其他美术资产的著作权及商标权。

GitHub 组织名、仓库可见性、提交账号或项目内部决定本身均不能替代版权转让或许可证明。

## Android 仓库历史贡献与权属核验（2026-11-10 初审）

以下统计基于 `main @ c19d5bf57388b427a5e8248d838cbf3e87b39d47` 的 GitHub 历史快照，不等同于版权归属结论：

- 检查了 315 条 main 提交，时间范围为 2026-09-30 至 2026-10-15。
- 306 条提交关联到同一个 GitHub 账号 `gccltd`；另有 9 条提交作者没有关联 GitHub 账号。
- 9 条未关联提交的作者元数据为：Codex 2 条、Codex Local UI/Codex local UI 3 条、Grok Bot 2 条、merge-check 1 条、Ethan 1 条。其中 8 条涉及产品源码/测试，Ethan 的提交只修改 `docs/release-0.4.2.md`。
- 仓库共检索到 105 个 PR，其中 82 个已合并；PR 作者账号均为 `gccltd`。PR 作者和提交作者是不同概念，不能因 PR 由该账号创建就推断所有提交的法律权利都归该账号或某个公司。
- 相关未关联提交分别出现在 PR #17（Codex/Codex Local UI）、PR #21（Codex Local UI）、PR #47（merge-check）、PR #59（Grok Bot）。Ethan 的文档提交没有关联 PR。
- 抽查 PR #17、#21、#47、#59：#17、#21、#59 未发现 review timeline；#47 只有 `gccltd` 的 comment-only review，不是独立批准。
- 仓库树中未找到 `CODEOWNERS`、`CONTRIBUTING`、DCO、CLA、AUTHORS 或 `.mailmap` 文件。

GitHub 账号、提交者邮箱、AI 工具名和 PR 作者只能证明版本控制元数据。它们不能证明自然人作者身份、雇佣/外包成果归属、版权转让或商业再授权权利。尤其是工具标记为 Codex、Grok Bot、merge-check 的提交，需要补齐实际操作人、生成/修改来源、人工审查和是否包含第三方代码的记录；不能把工具名直接当作版权主体。

### 原生代码范围与许可证头

- 基线树共识别 414 个代码文件：391 个 Kotlin、10 个 Java、4 个 Gradle Kotlin DSL、6 个 Go、2 个 C、1 个 C header。
- `app/src/main` 的 Kotlin/Java 文件没有找到 SPDX 或版权归属头；不能仅凭 GitHub 提交账号补写版权声明。
- `native/bridge` 有 9 个代码文件，其中 8 个已有 `GPL-3.0-or-later` SPDX 头；`native/bridge/callbacks.h` 没有 SPDX 头，也没有版权人声明。
- `native/patches/0001-owned-tun-fd.patch` 在 Git 历史中由 `gccltd` 账号提交，但补丁文件本身没有作者、版权或 SPDX 声明；它修改固定版本的 GPL-3.0 Mihomo 源码，补丁权利链仍需核验。
- Mihomo 源码并未提交在 Git 路径 `native/third_party/mihomo`。该目录由 `native/scripts/prepare.sh` 根据 `native/source-lock.json` 下载固定上游归档并生成，且被 `native/.gitignore` 排除。准确上游 commit 为 `ab405bad5beeeac8b003bb01f60f134f6df54471`，归档 SHA-256 为 `adb7a6e4207e8ca44567330e1d563163112787d3044d848305b2bfeff0282590`。
- `cmfa_reference` 在 source lock 中标记为 `compiled: false`；保留 GPL 文本不代表 CMFA 源码被编译或分发。

### 当前结论

- 原创代码权属：UNVERIFIED。
- 雇员/承包商成果转让：UNVERIFIED。
- 历史贡献者审查：未完成。
- AI/自动化工具提交的实际操作人及来源记录：未完成。
- 商业再授权：NOT AUTHORIZED。
- 新增代码贡献：在贡献协议和权利链规则建立前，不接受会改变版权归属或商业授权范围的实质性贡献。

## DCO 与 CLA

DCO 主要是贡献者对其提交权利和来源作出声明；它本身不当然授予项目主体将贡献改用闭源商业许可证再授权的权利。

如需双重授权，建议在未来贡献开放前采用经法律审阅的 CLA 或等效书面协议，明确授予：

1. 以 GPL-3.0-only 或其他开源许可证复制、修改、分发和再许可的权利；
2. 在约定范围内向企业客户提供单独商业许可的权利；
3. 对贡献进行修改、合并、再分发和维护的权利；
4. 贡献者保留权利、专利授权、来源保证及适用法律允许范围内的责任条款。

CLA 不得声称贡献者拥有其实际不拥有的第三方代码、雇主成果或外包成果。

## 商业授权边界

可考虑商业再授权的范围仅限于：

- 由已核实版权主体原创并拥有完整权利的代码；
- 已取得所有相关共同作者或权利人明确商业再授权许可的贡献；
- 已完成权利转让或书面许可且范围明确的外包/员工成果。

默认排除：

- Mihomo 及其上游/传递依赖；
- ZXing、AndroidX、OkHttp、SnakeYAML 等第三方依赖；
- 仅按 MIT、Apache、BSD、MPL、LGPL、EPL 或其他第三方许可证使用的组件；
- 未取得再授权权利的外部贡献；
- 未获公开再分发或商标授权的品牌素材。

GPL 允许收费分发、商业使用和收费支持。商业双重授权不是绕开第三方 copyleft 义务的工具，也不能撤销已经向公众授予的 GPL 权利。

## 当前状态

- 法定版权主体：UNVERIFIED
- 历史贡献者/雇佣/外包权利链：UNVERIFIED
- 商业再授权 CLA：NOT DRAFTED / NOT SIGNED
- 未来商业再授权：保留可能性，但当前未授权
- 新贡献接收策略：在正式贡献协议确定前，不接受会改变版权归属或商业授权范围的实质性代码贡献

本文件为工程治理草案，须由适用司法辖区的律师审阅后方可作为正式协议依据。
