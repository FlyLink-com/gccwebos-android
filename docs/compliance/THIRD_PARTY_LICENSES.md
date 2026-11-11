# 第三方组件与许可证清单（Android）

状态：初步清单；不是完整 SBOM，也不是发行批准。

| 组件/范围 | 固定版本或来源 | 已知许可证 | 发行范围 | 当前动作 |
|---|---|---|---|---|
| Mihomo | v1.19.31，Commit ab405bad5beeeac8b003bb01f60f134f6df54471 | GPL-3.0-only（精确锁定上游 LICENSE） | 原生引擎随 APK 分发；本地补丁必须纳入对应源码 | 保留上游 LICENSE、精确 commit、archive SHA-256 和补丁 |
| Android bridge Go 文件 | native/bridge/*.go | GPL-3.0-or-later（文件 SPDX） | 项目原生桥接代码 | 不擅自修改现有 SPDX；核实各文件权利人 |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | app 运行时 | 已保存完整许可证与归属说明 |
| ZXing Core | 3.4.1 | Apache-2.0 | app 运行时 | 已保存 LICENSE、NOTICE 和归属说明 |
| org.json:json | 20240303 | Public Domain（精确上游 tag） | testImplementation | 不进入生产 APK；仍记录测试 SBOM |
| JUnit | 4.13.2 | EPL-1.0 | testImplementation | 核实不进入 releaseRuntimeClasspath |
| Compose / AndroidX | 由 Compose BOM 2026.06.01 和 AndroidX 固定版本管理 | 待按解析后的精确 POM/上游 LICENSE 复核 | runtime / androidTest / debug 分开 | 未解析传递依赖前不得标记全量通过 |
| kotlinx.coroutines | 1.10.2 / 相关 Android 与 test artifacts | 待精确上游核验 | runtime / test | 纳入 SPDX SBOM |
| OkHttp | 4.12.0 | 待精确上游核验 | runtime | 纳入 SPDX SBOM |
| SnakeYAML Engine | 2.9 | 待精确上游核验 | runtime | 纳入 SPDX SBOM |
| Go Modules | native/go.mod | 133 个模块版本条目；除 Mihomo 外许可证逐项待核 | 原生引擎依赖图 | 不以 go.mod 中存在版本号代替许可证扫描 |
| CMFA reference | source-lock 中 compiled=false | 参考源为 GPLv3；本工程保留了 GPL 文本 | 声明为 reference-only，未确认存在复制代码 | 对代码相似性及 APK notice 用途作独立核查 |

## 已存在的许可证材料

- native/LICENSE
- native/notices/Mihomo-GPL-3.0.txt
- native/notices/CMFA-GPL-3.0.txt
- app/src/main/assets/licenses/ZXing-Android-Embedded-4.3.0-Apache-2.0.txt
- app/src/main/assets/licenses/ZXing-Core-3.4.1-Apache-2.0.txt
- app/src/main/assets/licenses/ZXing-Core-3.4.1-NOTICE.txt
- app/src/main/assets/licenses/ZXing-Attribution.txt

## 重要限制

- source-lock.json 当前写作旧 SPDX 值 GPL-3.0；审计输出规范化为 GPL-3.0-only，不在本 PR 改动源锁文件。
- 不能把当前 Mihomo 默认分支的许可证追溯应用到固定的 GPLv3 commit。
- 不能因 GPL 组件存在，就未经法律分析断言所有独立 Android UI 文件必然具有相同版权范围；但 JNI 与原生引擎的组合分发须按保守口径准备对应源码。
- 开发、测试、可选、运行时依赖必须分层；锁文件包数量不等于 APK 实际打包数量。
- 任何许可证字段缺失或 NOASSERTION 均为待核项，不得自动推断为 MIT 或 Apache-2.0。


## 品牌图形素材的第三方许可

| 素材 | 上游来源 | 已知许可证 | 当前发行处理 |
|---|---|---|---|
| Alipay 图形路径（`brand_alipay.xml`） | Ant Design Alipay 原始 SVG 路径；来源包 `gccwebos-alipay-visual-kit.zip` | MIT；版权声明为 Ant UED | 已找到许可证文本，但 Android 的 `app/src/main/assets/licenses` 尚未包含该 MIT 声明；发布前必须随发行物附带。支付宝商标使用仍需独立审查，当前资产继续 HOLD。 |
| Telegram 图形（`brand_telegram.xml`） | Simple Icons Telegram SVG；官网来源记录及 NOTICE | CC0-1.0（按 Simple Icons 上游许可证记录） | CC0 仅涉及图形作品版权，不授予 Telegram 商标权；不得声称 Telegram 背书或认证，品牌使用审查仍待完成。 |

说明：上述第三方图形许可证只覆盖对应图形作品，不代表其商标、品牌名称或其他项目素材也获得授权。


## Mihomo 原生源码与补丁的实际布局

- `native/source-lock.json` 固定 Mihomo v1.19.31、上游 commit `ab405bad5beeeac8b003bb01f60f134f6df54471` 和归档 SHA-256 `adb7a6e4207e8ca44567330e1d563163112787d3044d848305b2bfeff0282590`。
- Mihomo 源码不在当前 Git tree 中。构建时由 `native/scripts/prepare.sh` 下载、校验并解压到 `native/third_party/mihomo`，再应用 `native/patches/0001-owned-tun-fd.patch`；`native/.gitignore` 明确排除生成的 `/third_party/`。
- 上游 Mihomo 的许可证为 GPL-3.0。补丁必须随对应源码包提供，并由法律审查确认其衍生作品范围和权利链。
- 补丁文件本身没有作者、版权或 SPDX 头；提交记录只能把它关联到 GitHub 账号，不能替代补丁作者/权利转让证明。
- `cmfa_reference` 在 source lock 中为 `compiled: false`；目前保留 `CMFA-GPL-3.0.txt` 不代表该参考源码被编译或随 APK 分发。



## 完整传递依赖 SBOM 范围复核（2026-11-10 PST）

### 静态清单统计（基线 main）

- `app/build.gradle.kts` 当前包含 17 条依赖声明，涉及 15 个唯一 Maven 坐标；Compose BOM 在 implementation 与 androidTestImplementation 中重复声明。
- 根 `build.gradle.kts` 另声明构建插件：Android Gradle Plugin `9.2.0`、Kotlin Compose plugin `2.3.10`；Gradle Wrapper 固定为 `9.4.1`（distribution SHA-256 已写入 Wrapper properties）。这些构建插件目前没有进入现有 SBOM 包清单。
- `native/go.mod` 有 133 条 require 版本条目：3 条 direct、130 条 indirect。
- `native/go.sum` 有 341 行、161 个不同模块路径。go.sum 中包含 go.mod-only 校验项和历史版本项，不能把其模块路径数量直接当作最终构建依赖数量。
- `native/source-lock.json` 固定 Go `1.27.1`、Mihomo `v1.19.31` 的 commit/archive SHA-256，以及 build tags `cmfa`、`with_gvisor`；CMFA reference 明确为 `compiled=false`。
- 主分支树没有发现 `gradle.lockfile` 或 Gradle dependency verification metadata；没有已保存的 Gradle 解析报告或 Go module graph 证据。

### 当前 audit.py 的实际能力边界

现有 `docs/compliance/tools/audit.py` 的源代码确认：

1. Gradle 部分只读取 `app/build.gradle.kts` 中可匹配的直接声明，并对少量 Maven 坐标使用硬编码许可证映射；不会执行 Gradle dependency resolution，不会读取完整 BOM/POM 传递依赖，也不会解析 Android Gradle Plugin/Kotlin Compose plugin 的构建依赖。
2. Go 部分只解析 `native/go.mod` 的 require 条目和 `native/source-lock.json` 中的 Mihomo 锁定记录；不读取 `native/go.sum`，不调用 `go list -m all`、`go mod graph` 或 `go mod download -json`，也不检查每个下载模块中的 LICENSE/COPYING/NOTICE 文件。
3. 除已知的少量 Maven 坐标和 Mihomo 外，许可证保持 `NOASSERTION`；不会通过包名猜测许可证。
4. SPDX 输出将包的 `licenseConcluded`、`copyrightText`、`downloadLocation` 保持为 `NOASSERTION`，关系目前只有文档描述关系，尚未生成真实的 `DEPENDS_ON` 依赖边。

因此当前产物是**manifest-level inventory**，不是完整 Gradle/Go 传递依赖 SBOM，也不是与实际 APK ABI 和构建标签一一对应的发行 SBOM。

### 完整 SBOM 的必需解析范围

正式生成前，必须在固定 commit 和可复核工具链下分别采集：

- Gradle：`releaseRuntimeClasspath`、`debugRuntimeClasspath`、`testRuntimeClasspath`、`androidTestRuntimeClasspath`、必要的 compile classpath，以及 root/app `buildEnvironment`；保存完整依赖树、精确版本、POM/上游许可证来源和解析日志。
- Go：使用锁定的 Go `1.27.1` 采集 `go list -m -json all`、`go mod graph`、`go mod download -json all`；按 `cmfa,with_gvisor` 构建标签核对实际模块图，并对下载模块逐项扫描 LICENSE/COPYING/NOTICE 与上游 SPDX/许可证元数据。
- Native：单独记录 Mihomo 固定 commit、归档 SHA-256、本地 TUN FD patch 的 SHA-256、GPL-3.0 上游许可证和 patch 权利状态；CMFA 继续按 reference-only 处理，除非后续证据证明实际编译或分发。
- SPDX：生成精确版本和 purl、直接/传递关系、scope、文件哈希、许可证原文来源及 NOASSERTION 未决列表；不得把 build/test/dev-only 组件混入 release runtime 组件，也不得把测试依赖误报为 APK 内组件。

### 本轮执行边界与状态

- 本轮只做静态清单和工具能力审查，没有执行 Gradle 依赖解析、Go module 下载或许可证扫描。
- 当前执行环境没有 Gradle 安装和外部网络；系统 Go 为 `1.23.2`，低于项目锁定的 `1.27.1`。因此不能用当前环境结果冒充精确工具链解析结果。
- 没有运行 CI、Gradle build、Go build、APK 编译，也没有修改依赖版本、go.mod/go.sum、source-lock 或构建脚本。
- 完整传递依赖 SBOM：`BLOCKED / NOT GENERATED`。当前 manifest-level SBOM 仅作为预审清单保留，所有未核许可证项继续 `NOASSERTION`。
