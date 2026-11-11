# Android 品牌素材字节哈希核验记录

- 审计日期：2026-11-10（PST）
- 仓库：`FlyLink-com/gccwebos-android`
- 精确对象：`4fb8179a7dd82560c4a523d897417550a8bef06f`
- 范围：`app/src/main/res/drawable/` 与 `app/src/main/res/drawable-nodpi/` 中资产权利登记表列出的全部 23 条路径
- 方法：读取该 commit 的实际文件字节，重新计算 SHA-256；Git blob SHA 不作为文件 SHA-256 使用。

## 结论

- 登记路径：23
- 实际存在并完成字节哈希：22
- 登记路径不存在：1（`brand_wordmark.png`）
- 两份 manifest 声明冲突：2（`brand_robot_welcome.webp`、`brand_lounge.webp`）
- 所有资产权利状态仍为 HOLD；本报告不授予版权、再分发、修改或商标许可。

## 逐项核验

| 仓库路径 | 实际字节数 | 实际 SHA-256 | 结果 |
|---|---:|---|---|
| `app/src/main/res/drawable-nodpi/launcher_user_robot.webp` | 235,380 | `db10674181c719f7092ac79877e6c07d28e51d07784c11d6854efac201c4b704` | matches branding-assets.json |
| `app/src/main/res/drawable-nodpi/brand_robot_welcome.webp` | 188,612 | `233826f3ff968ae47d1cdb2ac09deca9fa9c45544a5a2868c14ab1605451acbd` | matches branding-assets.json; conflicts with native-ui-assets.json |
| `app/src/main/res/drawable-nodpi/brand_lounge.webp` | 279,522 | `c65141cff0eade9152db6be673e53d8537e77948cd8d2291d6a54b7157fe8134` | matches branding-assets.json; conflicts with native-ui-assets.json |
| `app/src/main/res/drawable-nodpi/brand_robot_avatar.png` | 57,119 | `c93f7529fe0b8cf419f1f77f6680ca0c25dd8c2c1bf4abd6b649408684f0757a` | matches declared hash |
| `app/src/main/res/drawable-nodpi/brand_wordmark.png` | — | `—` | MISSING; declared size 19,710 bytes in native-ui-assets.json |
| `app/src/main/res/drawable/brand_arrow.xml` | 502 | `7a2606ccb728f8737fdbc785f9a37047239761604aba21f847959df30b80281b` | matches declared hash |
| `app/src/main/res/drawable/brand_globe.xml` | 994 | `01b2fb12a6097dd4298d0175fdcbea4c08fb81b79651edfe91a3681a22035a1b` | matches declared hash |
| `app/src/main/res/drawable/brand_lock.xml` | 827 | `547ec3d94684fdc2160005c551aff388e2752619d3745e5f3e4d6bcfe8648428` | matches declared hash |
| `app/src/main/res/drawable/brand_plane.xml` | 516 | `bbaa5885756b0ada92d0de583bf58a0052c87565c6f9ba2701d5e7c1a2ade258` | matches declared hash |
| `app/src/main/res/drawable/brand_receipt.xml` | 825 | `3c78c9a5adb5ac572efc1636c9ffdee3c3e1dc35507b40548aa04c0a15773290` | matches declared hash |
| `app/src/main/res/drawable/brand_shield.xml` | 728 | `e2be147e2d3271d03857b5d332674062957ba7b33c8c37a115bab6bc78c82553` | matches declared hash |
| `app/src/main/res/drawable/brand_user.xml` | 749 | `5375dd92a23e2a97bea7196aa71210fbb60ad129509edc30e516df2a42589670` | matches declared hash |
| `app/src/main/res/drawable/brand_alipay.xml` | 1,658 | `4f60ec4e5521bd9dc74032af00b4adc2a24dd86b60b0b4ba1e02b5f28b3bfd4f` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_help.xml` | 786 | `5aaa77b48c514776fdf91c9df39f1eb217a9c35135880159192b2ef0b33bfeb5` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_lightning.xml` | 466 | `3a6a88c9f355b308ddbfaabe05d71d28101b9e6b2e22f8418c4c472fac94e53e` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_link.xml` | 862 | `49ea228a40940a2e1bfd32e3b57e9c7646f2dd1e2da6af61e291267d4a97614b` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_paper_plane.xml` | 648 | `9e2d67d044800cb6190acdca6e4125094c88c151620e09aa2c0aafb2a47c0042` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_refresh.xml` | 530 | `f10190e9e8d2f44e4bb27c66d9b8b1a3dc820cceb1a1876b6856b40deccf6289` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_share.xml` | 1,230 | `abe42a69680f03899dd0d9436dcb39276853470eeae37806e126ee780953a0d0` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_telegram.xml` | 1,083 | `c9301553538ba936740f99962da38fc5de7071866104004fb350d9515243b90a` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable/brand_wallet.xml` | 795 | `6497e0a1479163d7b8e717303ccb831d3eb5da7ac71600b6ff567f9c09e061ea` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable-nodpi/brand_wallet_coin.webp` | 53,228 | `0850e23ec65b32ae26eb24dc56f0e79f7562c35eff589c74d530120989f2580e` | newly hashed; no prior hash declaration |
| `app/src/main/res/drawable-nodpi/brand_welcome_wave.webp` | 360,442 | `7775cd97a77448abae9e5108affda0ceb26a257100e7f1bcf3958dd871a8eb02` | newly hashed; no prior hash declaration |

## 两项 manifest 冲突的精确结论

### `brand_robot_welcome.webp`

- 实际文件：188,612 bytes
- 实际 SHA-256：`233826f3ff968ae47d1cdb2ac09deca9fa9c45544a5a2868c14ab1605451acbd`
- `docs/branding-assets.json`：匹配实际文件
- `docs/native-ui-assets.json`：`4274b8e42cd16acf3281b187481715b73f75b596d474733710f5d4821f6c354c`，不匹配；其记录大小为 360,442 bytes

### `brand_lounge.webp`

- 实际文件：279,522 bytes
- 实际 SHA-256：`c65141cff0eade9152db6be673e53d8537e77948cd8d2291d6a54b7157fe8134`
- `docs/branding-assets.json`：匹配实际文件
- `docs/native-ui-assets.json`：`f5a891007163f856822ece297a51802f46c3f865a5a971407096dd2bdcf8d5f0`，不匹配；其记录大小为 293,430 bytes

## 缺失路径

`docs/native-ui-assets.json` 将 `app/src/main/res/drawable-nodpi/brand_wordmark.png` 记录为 19,710 bytes，并声明 SHA-256 `86d0228b90c47fed1bbd136bbf7f584da30e3a325bbc9b662ca217d36f36fbb4`。在该 commit 的完整 Git tree 及对应资源目录中均未找到该路径。因此当前不能把它当作 Android 已打包资产；也不推断它被删除、重命名或替换。

## 权利边界与后续处理

1. 两项冲突已由实际字节定位：`branding-assets.json` 的值匹配当前二进制；`native-ui-assets.json` 的对应哈希和大小需要后续单独修订。
2. 本 PR 仅在 `docs/compliance/` 记录证据，不修改原始素材清单或应用资源，保持本轮变更范围不变。
3. 原始 `exec-*.png` 文件不在 Android 仓库树内；本轮没有重新计算这些源文件的 `sourceSha256`。
4. 对没有历史哈希声明的素材，本报告记录当前字节哈希，但不把它们解释为已获得授权。
5. 来自私有 `gccwebos-mobile` 的角色、字标及矢量素材仍需创作者/权利人、权利转让或书面许可、公开再分发范围和修改权限证据。
6. 支付宝、Telegram 等可能涉及第三方品牌标识的资源，仍需单独完成商标与品牌使用审查。

**最终状态：BYTE HASH AUDIT RECORDED / BRAND RIGHTS HOLD。**
