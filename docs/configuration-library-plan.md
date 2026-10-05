# 节点页配置库：A 档候选与验收边界

日期：2026-10-05。基于节点 UI / 本地加载提交 `3423fcd`，叠加 PR #22 的第三方导入。

## 状态与授权边界

用户已明确：节点页显示所有已有配置，新扫码新增而不是替换。本候选已完成代码和隔离合成验收，普通 OPPO 应用仍为 `3423fcd`，没有安装本候选或转换用户配置。正常包首次写 v3 及其降级限制仍待 operator 确认；产品行为澄清不是对真实数据转换的批准。APK 安装、隔离合成测试与 VPN 测试已有用户授权，不另设安装审批。

风险为 A 档：扩展加密配置文件的 schema；账户认证模型、服务器和生产部署不在本轮范围内。源码只读盘点的原始基线为 `f428e45`，实施和测试在同一个 PR 内完成。

## 已实现行为

- 账户订阅和当前保存的手工配置分别显示。每份配置可展开节点列表，当前活动配置置顶；以前被旧版覆盖、没有保留的配置无法恢复。
- 扫码、文件、链接三个入口均新增独立条目，包括同名配置；成功后选中新配置，不自动启动 VPN。
- 每个手工条目支持查看摘要、重命名、明确替换及确认删除。替换保留该条目的 ID 和名称，其他条目保留；输入失败不会写入新材料。账户行仅提供摘要查看及选择。
- 查询、展开和改名不下载订阅；节点摘要不授予连接权限。选择未活动的远程配置会重新下载并验证，本地文件则重新校验；实际连接仍走现有 fresh prepare。
- 活动配置删除后选择 None，不自动选择账户或其他配置。活动变更必须通过真实 connection owner 的 idle fence；改名和非活动条目删除不改变当前节点权限。
- 节点页保留全局导入和闪电测速图标。闪电测当前活动配置，正常节点行最小 56dp，触区最小 48dp；大字号可增高。
- 库摘要读取与解析、导入入口的版本捕获及提交前校验在 IO dispatcher 执行；平台选择器与结果展示仍回到主线程。页面保存当前内存展示，展开不重新读取；未实现跨进程摘要缓存或按 revision 的解析缓存。

套餐加载和其他本地展示优化见 [B 档实施记录](node-manager-and-local-loading.md)。套餐目录仍从服务端核验，缓存不作为购买授权。

## 存储与事务

`manual-source.bin` 从单活动记录扩展为有界 v3 envelope，继续使用原 Keystore key、AAD、AES-GCM、AtomicFile、文件/目录 fsync 与完整 readback。没有第二份库或活动指针侧车。

- 最多 20 个手工条目、解密明文总量 2 MiB；每份配置仍最多 64 KiB / 128 节点。
- selector 严格为 Account / None / Entry；条目有稳定 UUID、名称和独立内容 revision。账户 session、delivery、profile 不复制进库。
- 名称最多 48 个 Unicode code point / 192 UTF-8 bytes，拒绝控制字符、双向覆盖与 URI 形状；默认名称不取自秘密链接。
- v1/v2 首次读取只在内存归一化，不重写密文。首次显式配置变更才原子写 v3，保留原手工条目和独立账户缓存。
- 配置库 CRUD / 选择基于 library revision、目标 ID 和 clearVersion；活动修改另外核验 source revision / authority epoch。连接前远程刷新沿用来源与 epoch 校验，通过兼容 save 更新活动条目并保留其余条目。过期 handle 与提交前取消回调不得提交。
- 活动切换先撤销旧 selection / admission，再提交整库。提交后 readback 失败会阻止连接，不能声称必然保持旧状态。
- 显式全量清理先写空 v3 / None / clearPending，撤销迟到回调后完成空库提交。全量清理还按原流程清除账户 profile / delivery / session；普通退出账户不删除手工配置。
- 损坏、解密失败、未知格式、条目缺失或读回不一致均拒绝使用；不能静默初始化空库或回退账户来源。

UI DTO 仅有名称、类别、节点摘要、状态与进程内操作 handle；不包含链接、正文或文件 URI。临时配置字节按现有所有权规则清零，不输出实际用户材料。

## 无用户数据写入的推演与隔离验收

| 场景 | 已实现边界 |
| --- | --- |
| 只读旧 v1/v2 后退出 | 密文字节不变，不触发 HTTP。 |
| 导入/替换解析失败、提交前取消、旧 ticket 返回 | 不新增或覆盖条目；临时输入擦除。已经完成提交后再收到取消，不会自动回滚已保存新库。 |
| AtomicFile finish 前失败 | 旧完整 envelope 保留。 |
| finish 后 fsync/readback 失败 | 可能已提交完整新 envelope；当前流程报 STORAGE，重开重新验证。 |
| readback 返回另一份合法库 | 完整内容比较拒绝，包括名称、选择、条目和有效期。 |
| 活动变更时 owner 未清理 | fence 拒绝，不能按普通 UI 状态绕过。 |
| 非活动删除后旧回调到达 | entry/library/clear ticket 不匹配，不能恢复已删条目。 |
| 全量清理与操作并发 | pending 空库与 clearVersion 阻止恢复旧条目。 |
| v3 交给旧 decoder | 拒绝未知版本，不当作首次运行。 |

测试仅使用自有隔离 applicationId / Context 和合成配置。没有读取、复制、清理或转换普通应用的账户/配置；没有将合成 UI 与延迟测试计作真实用户 VPN 或节点 RTT 验收。

## 验证记录

本轮证据见 [validation.json](evidence/configuration-library/2026-10-05/validation.json)。测试批次、源文件摘要及 APK 哈希分别记录，不能将不同候选混写成一次测试。

- 最终定向 JVM 175 项通过；OPPO fresh 批次 2 项、主批次 71 项通过，共 73 项，0 失败 / 0 跳过。fixture 和普通候选 APK 构建通过；Lint 0 error / 23 warning / 6 hint，源码校验和 diff 检查通过。早期失败与更正过程独立记录。
- OPPO 覆盖加密存储、双配置新增/替换、列表操作、紧凑布局、静默导航及前台恢复；Activity 重建使用独立 fresh 批次。旧的 opt-in `ManualImportUpgradeTest` 已改为验证保留 profile 和多配置，并输出 `downgradeReady=false`；仅编译，本轮未操作其历史固定包或执行该用例。
- Windows 扩展 JVM 批次的 `LocalSubscriptionTest.everyPreparationDownloadsAgainAndWorksWithoutPortalToken` 因文件系统不支持 `posix:permissions` 失败，未弱化文件权限实现或删除该测试；完整 Linux CI 独立验收。
- `3423fcd` 两条 CI 均在 Activity 重建测试等待健康导航增加 HTTP 次数时失败。候选修正了这一过时断言，仍保留真实重建、同名节点对象身份及内容变化撤销测试；新 head 的 CI 状态须实时核对。

## 覆盖升级与回退

覆盖升级前核对普通 candidate 的 applicationId、签名、APK/source 哈希、原生库和全部合成/lab 开关。批准正常配置格式升级后才安装，不卸载或清数据；首次明确配置操作才写 v3。

尚未写 v3 时，可以同签名覆盖回退并继续读取 v1/v2。已经写 v3 后，旧 APK 不能读多配置库，直接回退会拒绝连接；**当前可用恢复路径是安装支持 v3 的修复版**。候选尚未实现“空库转换为 None v1”的降级维护 API，不能声称已经具备无损降级。

不得为回退删除文件、清 Keystore、卸载普通包、恢复陈旧备份或导出明文。需要旧版兼容转换时，应另行实现、合成验证并明确批准有损行为。合并与发布沿实际 CI / review 门禁执行，本候选通过本地验收不等于已发布。
