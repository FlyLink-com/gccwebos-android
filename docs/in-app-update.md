# 应用内更新下载（in-app update）

状态：草案 PR，面向下一个正式版（不属于 0.4.2）。正式包（`com.gccwebos.android` 且非 DEBUG）获得「下载并安装」；测试包行为不变。

## 做什么、不做什么

- 做：用户在更新弹窗点「下载并安装」后，App 自己下载官网 `stable.json` 指向的 APK（带进度、可取消、可续传），校验后交给**系统安装程序**由用户确认安装。
- 不做：不静默安装、不后台下载、不自启动下载（只有点击才开始）、不使用 root / Shizuku / 无障碍、不读剪贴板、不新增账户信息上传。
- 保留：「前往官网下载」浏览器入口（弹窗内始终可用；下载失败时也提示）。清单展示逻辑（版本比较、minSupported、24 小时自动检查限频、已提示去重）不变。

## 流程

1. 弹窗 `Idle`：显示版本说明和「下载并安装 / 前往官网下载 / 稍后」。
2. 点「下载并安装」：若当前网络为计费网络（`ConnectivityManager.isActiveNetworkMetered`），先用 HEAD 取真实大小并提示「是否继续」；用户确认前不发 GET。
3. 下载到 `cacheDir/update/feilian-vpn-<versionCode>-<sha前12位>.apk.part`，完成后原子改名为 `.apk`。进度条显示 已下载 / 总量 / 百分比；无总量时只显示已下载量。可随时「取消」，已下载部分保留。
4. 校验（全部通过才继续，任一失败即删除文件并提示）：
   - 大小：实际接收字节必须等于服务器声明的 `Content-Length` / `Content-Range` 总量（见「已知限制」：清单本身没有大小字段）；超过 512 MiB 上限直接拒绝；
   - SHA-256：完整文件的 SHA-256 必须等于 `stable.json` 的 `sha256`；
   - 包名：`PackageManager.getPackageArchiveInfo` 读出的包名必须是 `com.gccwebos.android`；
   - 版本：`versionCode` 必须严格高于已安装版本，且等于清单宣布的版本；
   - 签名：安装包当前签名证书集合（SHA-256）必须与已安装 App 的**完全一致**（API 28+ 用 `GET_SIGNING_CERTIFICATES`，26–27 用 `GET_SIGNATURES`）。
5. 若 `PackageManager.canRequestPackageInstalls()` 为 false：提示并用 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（`package:` 本应用）带用户去「安装未知应用」开关；返回前台（`ON_RESUME`）后自动继续，已校验的文件仍在。
6. 点「安装」：用 `FileProvider` 的 `content://` URI + `ACTION_VIEW`（`application/vnd.android.package-archive`，`FLAG_GRANT_READ_URI_PERMISSION`）打开系统安装程序，由用户确认。结果由系统管理；弹窗显示「已打开系统安装程序」。

## 安全

- 只下载 `https://gccwebos.com` + 清单中通过 `OfficialReleases.isDownloadPath` 校验的 `/android/releases/<name>.apk`，不接受清单给出的其他主机；请求前后都核对最终 URL。
- 专用 OkHttp 客户端：不带 Cookie / 账户会话，禁止所有重定向（3xx 一律拒绝），`Accept-Encoding: identity`，系统证书校验。
- 续传只用 `Range: bytes=N-`；服务器不支持（返回 200）则从 0 重来；`Content-Range` 起点不等于已有字节数则丢弃部分文件并拒绝；416 时清除部分文件并只重试一次。最终仍以完整 SHA-256 为准，所以被替换/拼错的片段不可能通过。
- 文件只在应用私有缓存 `cache/update/`。`FileProvider`（`${applicationId}.updatefiles`）`exported=false`、`grantUriPermissions=true`，路径配置只含 `<cache-path path="update/">`；每次只对系统安装程序授予该文件的单 URI 只读权限。`scripts/verify_release_policy.py` 与测试固定了这一点（唯一 provider、不导出、路径只有 `update/`、禁止存储类权限）。
- 新增权限只有 `REQUEST_INSTALL_PACKAGES`（`scripts/verify_source.py` 白名单已更新）。没有存储权限、没有 `INSTALL_PACKAGES`、没有静默安装能力。
- 系统安装程序仍会在签名不一致时拒绝覆盖安装；应用内签名校验是为了更早、更清楚地提示，不是唯一一道防线。

## 隐私

- 请求只有对官网 APK URL 的 GET/HEAD（与浏览器下载同一地址），不带账户、订阅、设备标识；不记录下载内容。
- 不自动读取剪贴板；不新增持久化（除缓存目录里的 APK / `.part`）。
- 动画：进度条为确定进度，无无限动画；`Verifying` 只显示文字，因此减少动画设置下不会出现持续动效。

## 失败与清理

| 情况 | 行为 |
|---|---|
| 无网/中断 | 「下载中断，已保留已下载部分，点「重试」可继续」；重试用 Range 续传 |
| HTTP 非 200/206 | 提示状态码，可重试，不留文件 |
| 重定向/最终 URL 不符/不安全路径 | 拒绝，不可重试，提示改用官网下载 |
| 大小与声明不符/超上限 | 删除；大小不符可重试 |
| 存储空间不足 | 开始写入前按剩余量 + 16 MiB 余量检查，提示清理后重试 |
| SHA-256 不符 | 删除，可重试（重新完整下载） |
| 非官方包名/版本不高于当前/版本与清单不符/签名不一致/无法解析 | 删除，不会安装；签名与版本类不可重试 |
| 无「安装未知应用」权限 | 引导去设置；返回后继续 |
| 系统安装程序无法启动 | 删除文件，提示改用官网下载 |
| 离开弹窗（稍后/取消） | 下载中：保留 `.part` 以便续传；已完成/失败：删除文件；已交给安装程序：保留到下次启动 |
| 下次启动 | `cleanup`：删除版本 ≤ 当前已安装版本的文件、无法识别的文件、超过 7 天的文件（安装成功后第一次启动即清除） |

## 已知限制

- `stable.json` schema 1 没有文件大小字段（本 PR 不改清单合同）。大小校验因此是「实际字节数与服务器声明一致」，真正的完整性依据是清单里的 SHA-256。若以后清单增加 `bytes`，只需在 `ApkDownloader` 增加一次比较。
- 使用 `ACTION_VIEW` 而不是 `PackageInstaller` 会话：不需要额外的结果广播接收器，但应用拿不到安装成功/失败的回调，只能在下次启动时清理缓存。安装过程中应用会被系统替换并重启。
- 签名比较要求当前签名证书集合完全相同；若将来做密钥轮换，需要同步放宽为 lineage 校验。
- 下载中如果进程被系统杀死，`.part` 保留，下次点击会续传。
