# 原创代码权属与历史贡献者初审报告

审计日期：2026-11-10（PST）  
审计基线：`main @ c19d5bf57388b427a5e8248d838cbf3e87b39d47`  
仓库：`FlyLink-com/gccwebos-android`  
范围：GitHub main 提交历史、PR 元数据、仓库治理文件、代码文件头、native 源码准备/补丁路径。

## 一、执行结论

**代码权属仍为 UNVERIFIED，商业再授权仍未获授权。**

仓库的 Git 历史可以提供提交来源线索，但不能替代自然人作者确认、雇员/外包成果转让、贡献者许可或商业再授权协议。

## 二、Git 历史与 PR 贡献者

### main 历史快照

- 检查 main 历史共 315 条提交，时间范围 2026-09-30 至 2026-10-15。
- 306 条提交关联到同一个 GitHub 账号 `gccltd`；另有 9 条提交作者未关联 GitHub 账号。
- 这 9 条未关联提交作者元数据：
  - Codex：2 条，涉及产品代码/测试。
  - Codex Local UI / Codex local UI：3 条，涉及产品代码/测试。
  - Grok Bot (local test merge)：2 条，其中 1 条实现了 APK 更新功能，另 1 条修改设备验收测试。
  - merge-check：1 条，修改 MainActivity 和节点选择逻辑。
  - Ethan：1 条，只修改 `docs/release-0.4.2.md`，未关联 PR。
- 仓库共检索到 105 个 PR，其中 82 个已合并；105 个 PR 的作者账号均为 `gccltd`。

### 未关联作者提交明细

| 作者元数据 | Commit | 关联 PR | 影响范围 |
|---|---|---|---|
| Grok Bot | `3092be8e`, `87f9cf7e` | #59 | APK 下载/校验/安装交接源码及设备验收测试 |
| merge-check | `1dc7f9ad` | #47 | MainActivity、节点选择控制器/面板及测试 |
| Codex Local UI | `7ab50f36` | #21 | 连接页、钱包、账户、UI、测试及品牌资源 |
| Codex local UI | `ebd90b2c`, `b526a28d` | #17 | 四入口导航、连接页、钱包及测试 |
| Codex | `0f5f28c3`, `6a1cffc0` | #17 | RemoteCopySupervisor、连接页及测试 |
| Ethan | `1bc34c20` | 无 | 仅更新 0.4.2 发布草案文档 |

这些作者字段没有关联到 GitHub 用户身份。工具名不等于自然人作者或版权主体；需要记录实际操作人、生成/修改来源、人工审查，以及是否包含从第三方复制的代码。Ethan 的文档提交也需要确认其贡献关系和许可范围。

### PR 审查抽样

- PR #17：7 commits / 20 files；未发现 review timeline。
- PR #21：55 commits / 326 files；未发现 review timeline。
- PR #47：1 commit / 6 files；只发现 `gccltd` 的 comment-only review，不是独立批准。
- PR #59：4 commits / 18 files；未发现 review timeline。

以上是指定 PR 的抽样结果，不代表对所有 PR 的审查情况作出推断。

## 三、贡献治理文件

在基线 Git tree 中未找到：

- `CODEOWNERS`
- `CONTRIBUTING.md`
- DCO 签署流程或 DCO 记录
- CLA / 版权转让协议
- `AUTHORS`
- `.mailmap`

因此当前没有仓库内可复核的贡献者声明、签署记录或统一的版权归属规则。PR 作者账号、提交者邮箱和合并记录不能自动产生商业再授权权限。

## 四、代码范围与文件级许可声明

基线树共识别 414 个代码文件：

| 类型 | 数量 |
|---|---:|
| Kotlin | 391 |
| Java | 10 |
| Gradle Kotlin DSL | 4 |
| Go | 6 |
| C | 2 |
| C header | 1 |
| **合计** | **414** |

### Android Kotlin / Java

- 对 `SPDX-License-Identifier` 和版权声明的仓库搜索没有找到 Android Kotlin/Java 应用源码的文件级声明。
- 这不代表这些文件没有作者，只表示当前仓库没有可复核的文件级版权/许可证声明。
- 不应在版权主体和权利链未确认前，批量给这些文件补写 GPL 头。

### Native bridge

`native/bridge` 有 9 个代码文件，其中 8 个有 `GPL-3.0-or-later` SPDX 头；`native/bridge/callbacks.h` 没有 SPDX 头，也没有版权人声明。

现有 SPDX 表明文件声明的许可证范围，不证明谁拥有版权或有权进行商业再授权。

### Mihomo 源码与本地补丁

- Mihomo 源码不在当前 Git tree 中；`native/third_party/mihomo` 是构建时生成目录。
- `native/scripts/prepare.sh` 根据 `native/source-lock.json` 下载固定上游归档、验证 SHA-256、解压并应用补丁。
- Mihomo：v1.19.31；commit `ab405bad5beeeac8b003bb01f60f134f6df54471`；archive SHA-256 `adb7a6e4207e8ca44567330e1d563163112787d3044d848305b2bfeff0282590`；上游许可证 GPL-3.0。
- `native/patches/0001-owned-tun-fd.patch` 是本项目应用到上游的 TUN FD 所有权补丁；补丁文件没有作者、版权或 SPDX 头。其 Git 提交由 `gccltd` 账号完成，但提交账号不足以证明补丁作者和权利归属。
- `native/.gitignore` 排除了生成的 `/third_party/`；因此政策文件不应将 `native/third_party/mihomo` 描述为已跟踪的仓库源码路径。
- `cmfa_reference` 在 source lock 中标记为 `compiled: false`。保留 CMFA GPL 文本不等于编译或分发 CMFA 代码。

## 五、当前权利链阻断

1. `gccltd` GitHub 账号与实际法定版权主体之间的关系尚未由内部文件证明。
2. Codex、Grok Bot、merge-check 的 8 条源码/测试提交没有关联到 GitHub 人类作者身份；需要确认实际操作人、生成过程和第三方代码来源。
3. Ethan 的文档提交未关联 PR；需要确认其是否为员工、承包商或授权协作者，以及相关成果归属。
4. 401 个 Kotlin/Java 文件没有文件级版权声明；不能仅凭提交历史断言全部归某个主体。
5. `native/bridge/callbacks.h` 缺少 SPDX 声明。
6. Native TUN FD 补丁的作者、许可和权利范围未形成独立证据。
7. 没有已签署 CLA 或等效商业再授权协议。

## 六、发行门禁

- 新增 `BRIDGE_SOURCE_FILE_LICENSE_UNDECLARED`：bridge 文件出现 `NOASSERTION` 或人工确认字段未通过时阻断。
- 新增 `NATIVE_PATCH_RIGHTS_UNVERIFIED`：TUN FD 补丁的作者/权利链未核验时阻断。
- 对应回归测试已添加，尚未执行；本轮未触发 CI、Gradle、Go build 或 APK 编译。

## 七、需要补齐的材料

- 实际人类作者、雇员/外包关系、成果转让或许可协议。
- 对 Codex/Grok/merge-check 相关提交的操作人和来源记录，以及第三方代码审查记录。
- 对 Ethan 文档贡献的关系和授权确认。
- Native bridge 的完整版权人和文件级许可声明。
- TUN FD patch 的作者、来源、权利和 GPL 兼容性说明。
- 后续贡献的 CLA/DCO 流程和独立代码审查规则。

## 八、最终裁决

本轮仅完成 Git 历史和代码来源盘点，没有把任何 GitHub 账号、AI 工具或仓库组织认定为版权主体。原创代码权属、历史贡献者审查、员工/承包商转让和商业再授权继续 BLOCKED。根目录 LICENSE 不发布；PR #105 继续 Draft。


## GitHub API 独立交叉核验（2026-11-10 PST）

### 提交与 PR 统计复核

- 通过 GitHub commits API 分页读取基线 `main @ c19d5bf57388b427a5e8248d838cbf3e87b39d47` 的完整历史，共 315 条提交：306 条关联 GitHub 账号 `gccltd`，9 条提交作者没有关联 GitHub 用户账号。
- 通过 GitHub pulls API 分页读取全部 PR，共 105 个；其中 82 个已合并。105 个 PR 的作者账号均为 `gccltd`。PR 作者账号不等于每个 commit 的作者，也不能替代作者/雇员/外包权利证明。
- 对 PR #17、#21、#47、#59 的 review submissions 进行独立复核：#17 0 条、#21 0 条、#47 1 条 `COMMENTED`（由 PR 作者账号提交，不是独立批准）、#59 0 条。该抽样不代表对其他 PR 的审查情况作出推断。
- 主分支树中未找到 `CODEOWNERS`、`CONTRIBUTING.md`、DCO、CLA、`AUTHORS` 或 `.mailmap`；存在 `native/LICENSE`，但没有仓库根目录 `LICENSE`。

### 9 条未关联 GitHub 用户账号的提交完整 SHA

| 作者元数据 | 完整 Commit | 影响范围摘要 | 关联 PR |
|---|---|---|---|
| Grok Bot (local test merge) | `3092be8e4ee82b1d0f262579f8a830921cfd68cc` | 新增 APK 下载/续传、SHA-256/包名/版本/签名校验及系统安装交接；涉及应用源码、测试、Manifest、更新文档和发布校验脚本 | #59 |
| Grok Bot (local test merge) | `87f9cf7e9086af336004379384313eb6d45f792e` | APK 更新设备验收测试 | #59 |
| merge-check | `1dc7f9ad661d01c47ff879cbda030b36f218b56f` | MainActivity、本地订阅、节点选择控制器/面板及测试 | #47 |
| Codex Local UI | `7ab50f36275c1f7af53c0a4fc1a20cd7df097083` | 欢迎/用量 UI、账户/钱包/订单页面、品牌资源和相关测试 | #21 |
| Codex local UI | `ebd90b2cab495989d441def84a69598cc713dcb3` | 四入口导航、连接页、节点选择和测试 | #17 |
| Codex local UI | `b526a28d8aa2c0741691838cb1cc4408e5ce123b` | 连接核心组件、钱包/我的页和测试 | #17 |
| Codex | `0f5f28c3c9e94bb9c64e8b97d521bf2d2cd37bd0` | RemoteCopySupervisor、相关测试及文档 | #17 |
| Codex | `6a1cffc06b10d543d114cc072c0537395186a7fc` | 连接页、水滴入口、钱包/邀请页和 UI 测试 | #17 |
| Ethan | `1bc34c20fb30e3abb35e1e56de38e0da907f691c` | 仅更新 `docs/release-0.4.2.md` 发布草案 | 无 |

### 文件级许可证声明复核

- 对基线仓库进行 `SPDX-License-Identifier` 搜索，命中 8 个 `native/bridge` 代码文件，声明为 `GPL-3.0-or-later`。
- `native/bridge/callbacks.h` 是第 9 个 bridge 代码文件；实际文件内容没有 SPDX 或版权人声明。
- 对 `Copyright` 搜索未发现 Android Kotlin/Java 应用源码文件头的版权声明。该搜索结果不能证明作者身份，也不能作为补写版权头的依据。
- `native/patches/0001-owned-tun-fd.patch` 的作者、版权和许可证仍需单独确认；提交账号仅是 Git 元数据，不是补丁作者或权利人的证明。

### 裁决不变

- `gccltd` 账号的 306 条提交仍需内部确认实际操作人、其与法定版权主体的关系及成果归属。
- Codex、Codex Local UI、Grok Bot、merge-check 的 8 条源码/测试提交必须确认实际人类操作人、AI 工具使用/生成来源、人工修改审查及第三方代码来源；不能把工具名当作版权主体。
- Ethan 的文档贡献需要确认其身份关系及该文档的许可/成果归属范围；该提交没有被归类为产品源码贡献。
- 在取得员工/承包商成果转让、贡献者书面授权或等效权利证明前，Android 原创代码权属保持 `UNVERIFIED`，商业再授权保持 `NOT AUTHORIZED`。
- 本轮只补充审计证据，不修改任何应用源码、native bridge、补丁或许可证头；不运行 CI、Gradle、Go build 或 APK 编译。
