# 节点页配置管理：A 档实施方案

日期：2026-10-05。只读代码基线：`f428e45`。

状态：已完成代码盘点与无写入事务推演；本文件是待审批方案，没有实施配置库 schema、迁移或设备数据变更。用户已提出配置增删改查需求，具体 v3 存储转换及回退边界尚待 operator 确认。节点密度、闪电测速入口和页面刷新性能可按 B 档独立进行。

## 已知事实

| 位置 | 当前代码事实 |
| --- | --- |
| `portal/ManualSourceStore.kt` | `ManualSourceRecord` 仅保存一个活动来源。类型为账户来源、官方手工远程、本地文件、无来源、第三方远程；记录没有用户名称、配置列表或稳定配置 ID。 |
| `portal/ManualSourceStore.kt` | 现有 codec 对前四种类型写 v1，仅第三方远程写 v2。严格验证完整 envelope、内容 digest、已知版本、有效期标志、尾部字节以及 `clearPending`。 |
| `portal/EncryptedManualSourceStore.kt` | `noBackupFilesDir/manual-source.bin` 使用现有 Keystore key/AAD 的 AES-GCM；`AtomicFile` 写入、文件 fsync、目录 fsync、完整 readback。四个 fault point 能覆盖提交前后失败。 |
| `portal/LocalSubscription.kt` | 手工导入先下载及解析，随后在 owner fence 内复核 epoch、clearVersion 和 sourceRevision，才写入活动 envelope。仅恢复 UI 时读缓存；远程连接 prepare 重新下载。 |
| `portal/LocalSubscription.kt` | prepare/admission 绑定来源类型、revision、内容、期限、epoch 和一次性 material。`ConnectionSelection` 是进程内对象身份，不能用节点名、显示索引或持久化 ID 冒充连接授权。 |
| `portal/LocalSubscription.kt` | 全量清理先持久化 `None(clearPending=true)`，删除旧 profile/delivery，再等待 owner 清理，最后写最终 None。途中失败持续阻止连接，不回退账户配置。 |
| `portal/ManualImportController.kt` | 扫码、文件、链接共用导入事务；UI ticket 和 operation identity 阻止关闭对话框后或旧操作的回调提交。输入内容不进入普通 UI state。 |
| `vpn/ConnectionOwnerService.kt` | `commitSourceChange` 从真实 owner 获取 idle fence，并清除 prepared files。调用方不能凭 UI 显示“已断开”绕过服务状态。 |
| `core/ConnectionCoordinator.kt` | `withIdleSourceChange` 同步 commandLock、job 完成、lifecycle、tunnel、prepared material、owner 和 cleanup 状态；`stopAndAwaitClean` 后还会复核该 fence。 |
| `portal/PortalRepository.kt` | 账户会话、交付、profile 与手工来源有独立存储；退出账户与清除所有本地数据不同。 |

表内生产文件均位于 `app/src/main/java/com/gccwebos/android/`。以上是当前源码事实，不是 OPPO 执行结果。

## 选定的最小结构

扩展现有 `manual-source.bin` 为一个有界 v3 envelope。名称、列表、活动选择和来源材料共同原子提交，继续使用同一个加密边界、AtomicFile 和完整 readback。

不使用“配置库文件 + 活动指针文件”的双写方案。双写需要额外事务日志，而且旧 APK 清除本地数据时不认识新侧车，重新升级后可能重新出现已清除配置。单文件 v3 能让旧版拒绝未知版本，避免把不认识的配置状态解释成缺失或账户来源。

拟定模型：

- `ConfigurationLibrary`：随机 library revision、严格的 selector（Account / None / Entry(id)）、最多 20 个手工条目、`clearPending`。
- `ConfigurationEntry`：稳定随机 UUID `id`、显示名称、`ManualSourceRecord` 等价的来源内容与内容 revision；仅允许 ImportedRemote / ImportedLocal / ExternalRemote。
- Account 是固定展示行，引用原来的账户本地订阅流程；不复制 session、账户 token、delivery 或 profile 到库里。None 不隐式选择其他条目。
- 每份原始配置仍最多 64 KiB、128 节点。v3 解密明文总量最多 2 MiB；读取密文先检查独立有界长度。数量、单项、累计大小均在分配前验证。
- 名称为用户输入的普通展示文本，trim 后 1–48 个 Unicode code point，最多 192 个 UTF-8 字节，拒绝换行、控制字符、双向覆盖字符及 URI 形状；重名允许，以 ID 区分。默认名称为“配置 1”等，不从 URL、查询参数、文件完整路径或节点凭证推导。
- 所有加密明文、临时 byte array、废弃记录在 finally/close 中擦除；`toString()` 和异常保持固定脱敏文本。名称只保存在加密库及必要展示内存，不进入日志。
- UI 列表 DTO 只有名称、来源类别、节点摘要、节点数、状态、是否活动和 opaque 操作 handle。不得包含 URL、配置正文、用户名、UUID 凭证或文件 URI。

存储接口增加整库读取与事务提交；不得将多次 `save(record)` 拼成一个伪事务。原 `ManualSourceStore.read/save` 的测试适配器可保留，但生产 manager 必须通过单个事务入口进行库操作。metadata revision 与 active content revision 分离：只改名不得使正在运行的连接换源，内容替换/激活则必须撤销旧 admission。

## CRUD 与节点分组行为

| 操作 | 可检查的最终行为 |
| --- | --- |
| 新增 | 扫码/文件/链接沿用三个入口。后台完整下载、解析并生成摘要后新增独立条目；首版新增成功会选择该条目但不启动 VPN。存在旧活动来源时，单个 v3 commit 同时保存新条目并改变 selector。失败不改变旧来源。 |
| 查询 | 首帧来自本地加密库的经过校验摘要。每个配置是一行标题，可展开紧凑节点列表；展开不发订阅请求、不生成连接权限。进程内按 library/content revision 缓存已解析摘要，IO 与解析不在 Compose 主线程执行。 |
| 改名 | 只更新名称和库 revision，保存完整 envelope；保留 entry ID、内容 revision、selector 和当前节点授权。输入去重不按名称合并不同配置。 |
| 替换链接或文件 | “编辑”包含改名与“替换配置”两个明确动作。替换重新输入链接或选择文件，输入框不预填秘密 URL。新材料经过相同来源策略和解析；成功保留 entry ID/名称、更新内容 revision 和摘要。失败保留完整旧条目。 |
| 删除非活动项 | 一次原子提交删除条目，不改活动来源，不触发任何网络请求。被删项的迟到编辑/导入结果由于 entry/library ticket 失效而不得恢复条目。 |
| 删除活动项 | 必须通过真实 owner idle fence；与条目删除在一个 envelope 中将 selector 改为 None，不自动连接或选择账户/其他配置。连接尚未清理时拒绝提交，保留原条目；前端可引导使用现有断开动作，不能用普通 UI 状态代替清理完成证明。 |
| 选择配置/节点 | 选择未活动远程配置时先 fresh HTTP 并完整校验，本地文件则重新校验本地材料；随后在 idle fence 内原子提交新 selector/内容，再从新材料生成 opaque 节点登记。不能按此前缓存列表的旧 index 或节点名直接授权；若新列表变化，展示新列表供选择。选节点后进入连接页，配置激活本身不启动 VPN。实际连接仍走 fresh prepare，远程失败不能用库缓存顶替。 |
| 更新订阅 | 仅更新指定条目，后台请求；使用捕获的 entry ID + content revision + library/clear ticket。全程保留当前可显示摘要，成功一次发布新摘要。不得为了展开或浏览多个配置逐项自动下载。 |
| 账户来源 | 固定行不可改为第三方、不可手工编辑账户凭证；选择它仍走现有 `useAccountSource` 语义。手工库 CRUD 不发账户 setup/ack/轮换请求。 |

首版不做秘密 URL 查看、复制、二维码分享、配置正文编辑或导出；“查”是列表与配置元数据详情。若以后增加敏感内容显示/导出，需单独定义显示和泄漏边界。

节点密度可独立降低：标题行 + 分隔线节点行，国旗、节点名、协议次行与 ms 右对齐；配置收起时不构造所有节点组件。闪电只有图标但有无障碍 `contentDescription`。本计划不改变现有延迟探测协议含义。

## authority 与并发规则

1. 新增/替换先在 IO 作用域完整暂存输入并校验；不持有 owner/authority lock 进行 DNS、HTTP 或文件选择。输入消耗和取消保持字节所有权。
2. 每次操作捕获 library revision、目标 entry ID/content revision、clearVersion；可影响活动内容的操作另捕获现有 active sourceRevision 与 authority epoch。提交时全部复核。
3. 活动变更锁顺序维持 connection owner publication/command lock → authority lock → 唯一存储 commit。改名/非活动条目修改不得反向获取 owner lock。
4. 激活、替换活动条目、删除活动条目先推进 epoch、失效旧 `ConnectionSelection`/prepared admission，再写完整新库。readback 失败阻止连接并明确保存结果未知，不回退任意旧 selector。原文件可能已完成提交，不能仅因调用抛错就声称“未改变”。
5. `sameCapability`、probe publication 与 activation closure 必须比对 selector、entry ID、content revision、完整来源绑定、有效期与 clearVersion。旧对象身份不能跨配置复用，同名节点也不能续接旧授权。
6. 本地库展示不是权限。切换到新条目后重新登记对应节点；不能直接把非活动分组的摘要索引传给当前活动配置进行连接。
7. 全量 clear 的第一个耐久 tombstone 是 v3 空库 + None + clearPending；清理完成仍写 v3 空库，防止对旧内容自动回迁。clearVersion 立即增长，所有导入、测速、刷新、文件/扫描回调随后均丢弃；清理期间非活动修改也不能获准。
8. 账户退出继续只撤销会话；用户明确“清除本地数据”才清空全部手工条目、旧 profile/delivery 和会话。不得把普通 logout 偷换成删除配置。
9. 已知有效期属于单个条目的同一 URL/content revision；不同配置不能借用有效期。ExternalUnreported 与官方必须提供期限的规则保持不变；终止状态不能通过改名、切换或重开库清除。

## v1/v2 兼容与转换

首次读取 v1/v2 只在内存归一化，不写回、不发 HTTP。单个手工来源映射为一个默认名称条目，使用原 UUID revision 生成稳定对应 ID；Account/None 映射为对应 selector 和空手工列表。原 `clearPending` 必须保留为阻止状态，不能当正常空库。

首次明确 CRUD/激活操作完成原来完整校验后，原子写 v3；迁移不触碰 `portal-profile.bin`、账户 session/delivery、原密钥 alias/AAD 或系统 VPN 授权。新 reader 保留严格 v1/v2 decoder 与原 golden fixtures；v3 reader 不接受未知版本/selector、缺失 active entry、重复 ID、重复 content revision 异常、非严格 UTF-8、超界数量/长度、尾部数据或不完整明文。

如果 v1/v2 已损坏、key 不可解密或读回失配，展示固定 STORAGE 错误并阻止提交；禁止把失败当不存在并初始化空库。v3 中损坏任一条目也不能静默丢弃它后继续连接。

## 无写入 dry-run：事务推演

以下是对现有 owner/storage 机制的逐场景推演，不是实现运行测试。审批前不创建 v3 文件、不调用设备迁移、不读取或保存用户配置内容。

| 注入点/场景 | 预期耐久状态与安全结果 |
| --- | --- |
| 从 v1/v2 读取后关闭页面 | 原密文字节不变，旧版仍可读；账户状态不改变。 |
| 下载/解析失败、取消或 UI ticket 被替换 | 原完整库/活动选择保持；临时字节擦除，无新增项。 |
| 新增/替换在 AtomicFile finish 前失败 | 原完整 envelope 保留；旧/新选择不能混合。 |
| finish 后、目录 fsync/readback 前失败 | 可能已是完整新 envelope；当前进程 STORAGE 阻止，重开必须重新校验，不能谎报原状态已恢复。 |
| readback 被替换成另一个有效加密库 | 完整库比较失败；不能只比较 selector 或 digest 数量后放行。 |
| 删除活动项时 owner 仍有 worker/tunnel/material | idle fence 拒绝提交，原库保持；不允许仅删除列表行而旧隧道继续运行。 |
| 连接 prepare 完成后另一个操作切换/删除/替换活动项 | 提交先撤销 epoch；旧 prepared token 在 activate 时拒绝，零新 prepared 文件写入。 |
| 删除非活动项后原导入/更新回调才返回 | entry/content/library ticket 不匹配；不得重新添加或写入新选择。 |
| clear 在下载、扫描、文件选择、测速或刷新期间发生 | 先写空库 pending tombstone；clearVersion 使所有旧回调作废，重启也不能恢复旧条目。 |
| 新版崩溃/强停后恢复 | 只接受一个完整旧库或新库；incomplete clear 持续阻止；没有跨文件指针修复或默认第一项。 |
| v3 直接交给旧 APK | 旧 decoder 拒绝版本；现有 readManualSource 将未知/损坏转为 STORAGE，不能认为首次运行并回退账户来源。 |

## 实施、验证与发布门禁

审批对象是上述单文件 v3、最多 20 项/2 MiB、新增成功选择而不自动连接、idle-only 活动修改、账户存储不迁移，以及下面明确的降级限制。审批后在同一个 PR 完成 model/codec、manager、UI、边界测试和说明，不拆成 spec-only PR。

先写红测试，至少覆盖：旧 v1/v2 逐字节兼容与只读不迁移；所有 selector 与条目边界；有效期跨条目隔离；同名配置/节点；改名不影响 active capability；活动切换/删除/替换撤销旧 admission；非活动修改与 active refresh 竞态；clear 与全部迟到回调；真实 owner cleanup 失败；每个 AtomicFile fault point；decode/readback/密钥失败；日志与 DTO 不包含输入。

Android instrumented staging 使用隔离 Context/合成材料验证 AES-GCM、目录 fsync、AtomicFile 故障矩阵与 v1/v2→v3→受控 None v1 回退；先编译运行再记录通过项。OPPO 安装已有授权，但不卸载、清除正常测试应用数据或复制用户加密文件。优先隔离合成 fixture；正式测试包覆盖升级前先验证签名、applicationId、旧版本与数据状态。正常用户配置是否能激活和真实 VPN 连通分开验收。

运行与变更匹配的 JVM/Android 测试、候选 assemble/lint、源码静态检查和 `git diff --check`；结果按实际执行记录，不能把旧 PR 的 322 项结果计作新库通过。正式合并/发布沿仓库实际门禁执行。

## 回滚与已知限制

还没写 v3 时：同签名覆盖回退应用即可继续使用原 v1/v2；无需迁移账户。

已经写 v3 时：旧版不认识多配置，因此**直接回退不能保持多配置可用**。受控“清空配置库并准备回退”维护操作明确告知会删除所有本地手工配置；经用户明确选择后停止并等待 owner 清理，推进 clearVersion，持久化 v3 空库 clearPending，再验证库为空、旧回调与 prepared material 已撤销，最后原子写兼容旧版的 None v1 并完整 readback。维护流程与“清除所有本地数据”不同，保留账户 session、delivery、profile、密钥和登录信息；None selector 继续阻止自动回退账户，只有以后用户明确选择账户来源才可使用账户存储。转换中任何失败保留/进入 STORAGE 阻止，禁止安装旧 APK 并宣称转换成功。

受控转换属于本次 v3 的回退准备，必须用合成材料验收后才能宣称具备回退路径。保留多配置的恢复方式是安装支持 v3 的修复版；不得自动执行降级转换或删除用户配置。直接覆盖旧 APK 的 fail-closed 结果与经过维护转换后的正常旧版启动是两项分别验证的结论。

禁止回滚时删除 `manual-source.bin`、清 Keystore、卸载正常包、恢复陈旧 `.bak`、静默选第一项、导出明文/秘密链接，或读取另一个 applicationId 的账户文件。若用户需要保留多配置，使用修复版前向恢复，不能在旧 APK 上伪造兼容。

## 本文件验证

当前只修改此说明文件；无库实现、无 schema 写入、无设备数据操作。文档检查使用 `git diff --check`。后续 operator 选择及真实测试结果应追加到本文件，不能在审批前标记为已实施或已验收。
