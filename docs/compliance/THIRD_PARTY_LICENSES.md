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
