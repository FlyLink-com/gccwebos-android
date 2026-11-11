# 品牌素材权利链初审报告

审计日期：2026-11-10（PST）  
审计基线：Android PR #105，Head `d15fe95ccb057dfb9265891512fc1c7bf9413ea5`  
范围：`docs/compliance/asset-rights-register.json` 登记的 23 项 Android 品牌/装饰资源，以及当前可访问的项目素材包、来源仓库和历史来源记录。

## 一、结论

- 登记资产：23 项。
- Android 当前树中存在：22 项。
- 登记路径缺失：1 项（`brand_wordmark.png`）。
- 版权/公开再分发状态：23 项全部保持 `HOLD`。
- `publicRedistributionAuthorized`：23 项全部为 `false`。
- 发现两项有明确上游版权许可证证据的第三方图形：Ant Design Alipay（MIT）和 Simple Icons Telegram（CC0-1.0）。两者均不能因此自动取得相应品牌商标权。
- 本报告是工程证据盘点，不构成法律意见，也不批准任何素材进入公开发行包。

## 二、来源仓库与素材包

### 私有项目仓库

| 仓库 | 核验结果 | 含义 |
|---|---|---|
| `gccltd/gccwebos-mobile` | Private；GitHub 未识别仓库许可证；审计提交 `091ee6e82bb768333dcc23eaaf725c1fd07ba243` 的根目录没有 LICENSE/COPYING | 能证明部分素材在该仓库的存在和提交历史，不能证明作者身份、版权转让或 GPL 公开再分发授权 |
| `gccltd/gccwebos-site` | Private；GitHub 未识别仓库许可证；根目录没有通用 LICENSE | 特定 Telegram 图形另有 Simple Icons CC0 来源记录；不能将其扩展到其他站点或品牌素材 |

Git 提交作者字段（例如 `gccltd.co`）只是提交元数据，不是创作者身份证明、雇佣/外包权利转让或版权授权文件。

### 已核验的项目素材包

| 素材包 | SHA-256 | 许可证/权利文件情况 |
|---|---|---|
| `gccwebos-ui-source.zip` | `587fea436107222e290566d7855ec4c38c23b1f2481298ce1c0ca24539d6c8d6` | 未发现通用 LICENSE/NOTICE/权利转让文件 |
| `gccwebos-ui-complete.zip` | `b745151a666c6495ab5bb3a5299a498efa9c64bbd4e30cda08ca6171f72da3a4` | 未发现通用 LICENSE/NOTICE/权利转让文件 |
| `gccwebos_robot_source.zip` | `3584b13aa0faf49465e9fa3cf661d5a2a285fe7152f68fe6fe29a1dbce5d3512` | 说明是基于用户提供参考图重建提示词；未包含原始生成记录或通用授权文件 |
| `gccwebos-alipay-visual-kit.zip` | `40b42b01ff99cc1a002becbaa476043c4430836df2608502e04f620bbb913c49` | 包含 Ant Design 上游 SVG 和 MIT LICENSE 副本 |

素材包存在、文件可读取或 SHA-256 匹配，只能建立来源/字节连续性，不能单独证明素材作者或版权归属。

## 三、逐项来源核验

### A. 机器人、场景与字标

| Android 资源 | 找到的来源 | 字节核验 | 当前判断 |
|---|---|---|---|
| `launcher_user_robot.webp` | Library 原图 `exec-f4715170-d344-42a8-a784-96641cb37afa.png` | 原图 SHA-256 `23aee444e690afa3be9a42d36d226648d218c884ccd7aad5e26f0558afcc351a` 与 branding manifest 的 sourceSha256 一致；源图 1,481,957 bytes，打包资源 235,380 bytes | 找到原始文件并确认来源哈希；创作者、转让/许可、商标权仍未知 |
| `brand_robot_welcome.webp` | 登记的原图 `exec-dc856b78-f156-4cbc-b9c5-99f97890d7f6.png` 尚未找到 | Android 文件 188,612 bytes；原始 sourceSha256 尚未独立复核 | 目前没有可靠来源链。此前候选私有仓库文件实际对应 `brand_welcome_wave.webp`，不能再归到本资源 |
| `brand_lounge.webp` | `gccwebos-mobile@091ee6e8/public/scenes/home-lounge.webp`；Git blob `8d3c46bf99b07d5c8c44cc88558a6d86e941ed93` | 源文件 293,430 bytes；Android 文件 279,522 bytes，非字节一致 | 源路径可追溯，但转换记录和登记的 `exec-bec4...` 原图尚未复核 |
| `brand_robot_avatar.png` | `gccwebos-ui-source.zip/gccwebos-ui-kit/assets/characters/robot-avatar.png` | 源文件 57,119 bytes，SHA-256 `c93f7529fe0b8cf419f1f77f6680ca0c25dd8c2c1bf4abd6b649408684f0757a`，与 Android 文件完全一致 | 已修正原登记的来源映射。私有仓库中同名头像是另一份 57,624-byte 文件，不是本 Android 文件的字节来源 |
| `brand_wordmark.png` | `gccwebos-mobile@091ee6e8/public/complete/brand/gccwebos-wordmark.png`；Git blob `9256d1d0c4024ea30a28da1d2e49f467ab9c8ad8` | 源文件 19,710 bytes；Android 登记路径不存在 | 源文件存在，但当前 Android 包内缺失；需确认是否仍计划打包 |

机器人提示词源码包记录五张参考图是用户提供的原图副本，且说明其工作是基于图片重建提示词，并非原始生成记录。Library 中的 `未来霓虹科技吉祥机器人.png` 实际 SHA-256 与其 source_manifest 一致（`f6f0ad6245ec62cabc545cb0cfc35cba939e135ee0bc95548464417d319933d1`）。这些记录没有确认参考图最初的作者或权利人。

### B. 私有 mobile 仓库中的线性图标

以下 11 项可追溯到 `gccltd/gccwebos-mobile@091ee6e8` 的 SVG 路径；其中 7 项也在 `gccwebos-ui-source.zip` 中找到对应图形。Android 资源是 VectorDrawable 格式转换，不是 SVG 字节副本。

- `brand_arrow.xml` → `public/complete/icons/arrow.svg`
- `brand_globe.xml` → `public/complete/icons/globe.svg`
- `brand_lock.xml` → `public/complete/icons/lock.svg`
- `brand_plane.xml` → `public/complete/icons/plane.svg`
- `brand_receipt.xml` → `public/complete/icons/receipt.svg`
- `brand_shield.xml` → `public/complete/icons/shield.svg`
- `brand_user.xml` → `public/complete/icons/user.svg`
- `brand_help.xml` → `public/complete/icons/help.svg`
- `brand_refresh.xml` → `public/complete/icons/refresh.svg`
- `brand_share.xml` → `public/complete/icons/share.svg`
- `brand_wallet.xml` → `public/complete/icons/wallet.svg`

上述私有仓库没有检测到通用开源许可证，也没有找到允许这些图标公开再分发的独立书面许可。来源路径已补齐，但全部继续 HOLD。

### C. 第三方品牌图形

| Android 资源 | 上游来源与许可证 | 已发现证据 | 未解决事项 |
|---|---|---|---|
| `brand_alipay.xml` | Ant Design Alipay SVG path；MIT | `gccwebos-alipay-visual-kit.zip/upstream/ant-design-alipay.svg` 和 `upstream/LICENSE-ant-design.txt` 已找到；MIT 版权声明为 Ant UED | Android `app/src/main/assets/licenses` 当前没有该 MIT notice；支付宝商标使用须独立审查 |
| `brand_telegram.xml` | Simple Icons Telegram SVG；CC0-1.0 | 私有 site 仓库的 `docs/TELEGRAM_CHANNEL_ASSETS.md` 与 `Simple-Icons-NOTICE.txt` 记录了上游图形和 CC0 来源 | CC0 不授予 Telegram 商标权；不得暗示 Telegram 官方背书，品牌使用仍待审查 |

### D. 项目素材包中的独立插画

| Android 资源 | 来源 | 字节核验 | 当前判断 |
|---|---|---|---|
| `brand_wallet_coin.webp` | `gccwebos-ui-complete.zip/assets/icons/wallet-coin.webp` | SHA-256 `274cbeb16788b1f0d08cd6a663d2e1a52485d90a7687d2d84bcc6a185ad72d22`，与 Android 文件完全一致 | 来源包说明为新绘制的独立矢量 UI 资产；没有创作者/权利转让/许可证明 |
| `brand_welcome_wave.webp` | `gccwebos-ui-complete.zip/assets/characters/robot-welcome.webp`；同时对应 `gccwebos-mobile@091ee6e8/public/complete/characters/robot-welcome.webp`（Git blob `be17a2b3cbafbc8a3876122584489a08ebeb31b0`） | SHA-256 `4274b8e42cd16acf3281b187481715b73f75b596d474733710f5d4821f6c354c`，与 Android 文件完全一致；同一源文件也与私有仓库 Git blob 完全一致 | 来源包说明它由 `robot-wave-static.png` 上半身裁切得到；技术来源已交叉确认，但源图作者和权利链仍未证明 |

这也确认了 manifest 冲突：360,442-byte / `4274b8...` 的文件来自私有 mobile 仓库的 `robot-welcome.webp`，实际对应 Android `brand_welcome_wave.webp`，而不是当前 188,612-byte 的 `brand_robot_welcome.webp`。后者登记的 `exec-dc856...` 原图仍未找到。

### E. 尚未识别来源的自绘矢量

- `brand_lightning.xml`
- `brand_link.xml`
- `brand_paper_plane.xml`

目前只找到 Android VectorDrawable 文件本身，没有找到原始 SVG、设计源文件、作者记录或第三方许可证。不能仅凭图形简单就推定为可自由再分发；继续 HOLD。

## 四、发行门禁已明确的新增条件

1. 只要 APK 中包含 `brand_alipay.xml`，发行包必须在 `app/src/main/assets/licenses/Ant-Design-Alipay-MIT.txt` 提供包含 MIT License 和 Ant UED 版权声明的许可证文本。
2. 所有资产的 `trademarkReview` 必须为 `APPROVED` 或明确的 `NOT_APPLICABLE`；`PENDING` 和 `NOT_APPLICABLE_PENDING_REVIEW` 均阻断发行。
3. 资产版权授权、公开再分发范围、修改权限和权利链仍需逐项证据；哈希匹配不能代替这些证据。

## 五、需要补齐的权利材料

- 对私有 mobile/site 仓库素材：实际创作者/权利人声明、雇员或外包成果归属、书面转让或许可，以及明确允许 GPL 发行、修改和商业使用的范围。
- 对机器人原图、场景图和独立插画：原始生成/设计记录、创作者身份及相应权利文件。
- 对 Alipay：将 MIT notice 纳入 Android 应用许可证目录，并完成支付宝商标使用审查。
- 对 Telegram：保留 Simple Icons CC0 证据，完成 Telegram 商标使用审查。
- 对缺少来源的三个自绘图标：补齐原始设计文件和创作者/权利归属，或替换为许可证清晰的图标资源。
- 对 wordmark：确认它是否应进入 Android 包；若应当打包，先解决路径缺失和权利链。

## 六、最终裁决

本轮只建立来源证据和发行门禁，不批准任何品牌素材公开再分发。23 项全部继续 HOLD。根目录 LICENSE 不发布；PR #105 保持 Draft；不合并、不部署、不签名、不发布 APK。
