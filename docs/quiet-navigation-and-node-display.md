# 静默导航与节点展示验收

日期：2026-10-04。最终应用代码提交：`2ec826adc8f00767997a6df8d2957686822a38cc`；本轮前一阶段为 `a4c98245e6dd8bd6c1fcdec17a0135e44c39e360`。

记录状态：本轮本地验证回执已收口。前台节点恢复补充修复及其 GREEN 回归、最终无诊断 APK 安装和公开节点界面复核均已记录；下述未验收项仍保持开放。

本轮是 B 档应用 UI 与只读刷新修复。前一阶段相关 JVM 113 项通过；本轮实机 UI 汇总 185 个唯一用例的最后结果全部通过。最终提交的普通 QA2 已覆盖安装并核对 APK 字节。真实节点数值延迟尚未验收，PR 保持 Draft。

## 行为与边界

- 进入或再次点击连接、节点、套餐、我的及其相关只读页面时，按页面读取相应数据。等待账户工作结束后使用最新连接状态判断；节点导航不能使已接受的连接准备失效，也不能隐式开通、确认交付或启动 VPN。
- 前台 session 校验替换 opaque 节点身份后，已挂载的节点页自动重新读取；恢复到节点 tab 也不要求再次点击导航。连接处于连接中、已连接或断开中时仍禁止该刷新。
- 普通读取不再常驻显示加载标题、进度提示或专用刷新/取消按钮。读取失败采用短暂反馈或原有受控恢复入口，页面再次进入可以重试；账户、订单、购买和连接动作的未验证、忙碌、取消与过期门禁仍保留。
- 节点页没有搜索和筛选。选择仍使用原 opaque identity，只在控制器接受后返回连接页；无 Ping 回应不影响合法节点选择，不支持的整份配置和过期配置仍不可选。
- 真实服务仓库向界面传入 Ping probe。只有有效读数显示数值 `ms`；无回应、不可用、网络改变和未测量统一显示 `— ms`。Ping 是物理网络到节点主机的往返诊断，不是完整代理 RTT，也不授予隧道连接权限。
- 地区先使用保守的显式名称标签，并补充独立字母边界内的 `NRT`、`SGP`。六个已核对配置名称以精确 UTF-8 SHA-256 映射补齐 JP/SG 展示；来源是后端提交 `d49121264f5227338f39729ea34b2dd898a0abf3`。映射不按节点序号或端点推断，改名不命中，显式标签冲突仍为中性图标。它表示已知配置标签，不是 IP 地理定位。

这些修改不更改认证模型、加密 profile 格式、订阅准入、节点协议或 native 生产边界。地区与 Ping 原始实现范围见 [节点地区、延迟与官网入口](node-region-latency-and-website.md)。

## 本地验证

证据目录为 `.build-output/quiet-import-oct4/`；可提交的结构化摘要见 [verification.json](evidence/quiet-ui/2026-10-04/verification.json)。其中保留固定用例名称、各批次计数、失败历史和来源回执 SHA-256，不包含真实节点名称、地址、订阅链接或账户信息。

| 验证项 | 结果及范围 |
| --- | --- |
| JVM | `jvm-final.json`：前一阶段 `a4c9824` 的 12 个测试类，共 113 项，0 failure、0 error。前台补修未更改这些相关代码；没有把这份记录改称最终 HEAD 的重跑结果。 |
| 实机 UI | 物理 OPPO / API 33；`ui-outcomes.json` 汇总 185 个唯一用例，每个用例最后一次记录均 PASS，无未解决用例。最终提交的前台恢复及相关 48 项回归为 48/48。 |
| 覆盖边界 | 包括静默导航、等待后的最新连接 owner、账户恢复、节点选择/过期/不支持协议、Ping 展示、支付只读与事务门禁、窄屏和 200% 字号。合成用例不代替真实账户或 VPN 转发验收。 |
| fixture 排除 | 仅排除 `SubscriptionWalletUiTest#launcherAndAccessibleHeaderUseExactBrandNameWithoutChangingPackage`，因为隔离包使用独立包名；原标准包名断言未修改，该用例不计入 185 项。 |
| 构建 | `build-foreground-green.log`：最终实现的 Debug、AndroidTest、unsigned Release 与 `lintDebug` 构建成功；修正测试 finder 后 `build-foreground-green-corrected.log` 的 Debug/AndroidTest 也成功；`build-normal-final.log`：最终普通 QA2 Debug 构建成功。 |
| lint | 0 error、30 warning、6 hint。 |
| native 包装 | 沿用来源提交 `98ffd6bae926e3d5f7c2a60b2b21af1278874436` 的既有产物；双 ABI ELF LOAD 与 ZIP 16 KiB 对齐检查通过，必需 notices、生产 service 和 debug lab 禁用检查通过。没有重新宣称 native 行为已验收。 |
| 临时资源 | 隔离 fixture 隐藏 launcher，安装前自有包不存在、私有路径为空；结束后仅移除本次自有 fixture app/test，私有路径为空。 |

这 185 项是本轮选定批次按固定用例名取**最后结果**的聚合，不是一次完整 suite，也不是当前 HEAD 的 hosted CI 通过证明。Android 16 模拟器本轮未启动。

前轮失败保留如下；后续同名用例全部获得 PASS，不将这些批次改写成通过：

| 批次 | 当次通过 / 启动 | 当次失败 |
| --- | --- | --- |
| `connection-account` | 44 / 49 | 5 |
| `commerce` | 38 / 41 | 3 |
| `profile-auth` | 48 / 49 | 1 |
| `connection-account-final` | 46 / 49 | 3 |

`navigation-final` 为 58/58，`commerce-final` 为 41/41，`profile-auth-final` 为 49/49，`visual` 为 8/8；它们与其余最后成功记录共同形成上述聚合。`final` 文件名本身不表示整批通过，结果以回执字段为准。

### 前台节点恢复的独立 TDD 基线

补充的 `NodeForegroundRecoveryUiTest` 共三项，覆盖同 session 前台校验后已挂载节点页恢复、恢复到节点 tab 后无需再次导航、以及连接三个活动阶段禁止刷新。首轮 `foreground-red` 的 0/3 来自测试 finder 未查询 unmerged semantics；修正 finder 后，旧实现的 `foreground-red-corrected` 为 1/3：连接阶段守卫通过，两个恢复场景分别在 10 秒等待后失败。这是该补充修复的 RED 基线，单独保留，不混入最终代码回归结果。

最终实现的 `foreground-green` 为 48/48，耗时 270.076 秒，无失败或超时；包括新增三项及 QuietNavigation、AutomaticSubscription、AppShell、NodeRegionLatency、NodeSelection 五类相关用例。系统重复启动提示已处理，没有导致该批次失败。按固定用例名称合并后，实际汇总为 185 项最后结果全部通过。

## 普通 QA2 安装及公开界面观察

普通包为 `com.gccwebos.android.candidate.acceptance.bbr20261004v2`，versionCode 6，签名 SHA-256 为 `08a952d8f10486ff79cb7b00e58ee8618425bf3286e778147da1a8b008a373d4`。最终 `2ec826a` APK SHA-256 为 `5ffa0ad6ab34fb126362355be31c5af4738647ec5824eba2f016831996255165`，已同签名覆盖安装，安装后读取的 APK 与本地候选一致。

前一阶段 `a4c9824` APK 的 SHA-256 为 `60191d6907816cfc7a743f1f44f635d547942ef1daddc9b98e153b4649683552`，该阶段安装也曾核对一致；此哈希仅保留为历史，不是最终交付包。

`VPN_ENGINE_PACKAGED` 与 `CONNECTION_ENDPOINT_HOSTNAME` 为 true；`CONNECTION_SYNTHETIC_ACCEPTANCE`、`STORAGE_SYNTHETIC_ACCEPTANCE`、`NATIVE_VPN_LAB`、`NATIVE_TUN_ACCEPTANCE` 均为 false。安装前后 15 个预定义私有路径的存在性和类型一致，没有读取内容或清除应用数据；这不等同于私有文件内容逐字节比对。

最终无诊断 `5ffa0a…` 包的 `normal-final-node-display.json` 完整观察到六条地区展示与已核对配置一致，为 JP 三条、SG 三条；数值读数为 0/6，未知为 6/6。前一阶段普通包和临时诊断包也曾完成相同六条地区匹配，分别保留为阶段历史。可见行子集不计作六条完整观察；名称和图标匹配不证明节点物理位置。

本轮完整观察中，**真实数值延迟为 0/6，六条均为未知**。独立的临时固定枚举诊断确认 `capture/READY/6/true` 一次、`result/NoReply` 六次：六个目标有效、到期时间已知，均已调用测量并返回 `NoReply`，没有可显示的 RTT，并非 UI 漏显已有读数。回执为 `node-diagnostic-result.json`，没有记录目标名称、地址或配置。

该临时诊断 APK 的 SHA-256 为 `516f808e565a8eb2009b034f8c13bc6371a4d1300953cd66e16a35346127e95b`，来源是前一阶段 `a4c9824` 加本地 ignored 诊断 overlay；普通 profile 未清除。诊断源码随后已恢复，诊断包不作为正式交付，最终无诊断包已覆盖安装。

`NoReply` 也可能包含整体 deadline、ICMP poll 超时或错误；本次未证实具体失败环节，不保证六个 ICMP 包均已发出，不推断服务端禁 Ping，也不能归因于服务端或网络路径。不能把合成数值展示通过或既往 ICMP 环回成功写成这六条真实节点延迟通过，也不能据此判断节点不可连接。本记录不宣称真实账户 VPN 转发、重启或异常恢复通过。

## 保留的未完成项

- [手动配置导入方案](manual-config-import-plan.md) 仍是 A 档准备材料，operator approval 未答。扫码、导入文件、链接导入订阅三个完整入口及独立本地来源 authority/store **均未实现**，没有以本轮 B 档验收代替批准。
- B2 P7 超时仍未归零，P8/P9 依失败停止规则未运行，B2 未通过。B1 历史六项通过结果独立保留。
- 2026-10-04 本轮 push 前只读复核时，远端 [PR #21](https://github.com/gccwebos-com/gccwebos-android/pull/21) 为 OPEN / Draft，head 为 `ab41da21ef81111848b0ffd5db4c8605a9f6c1af`；这是 push 前快照，不是后续远端当前状态声明。该 HEAD 较近的 [Android M0 run 37227157159](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37227157159) 的 `validate` 失败：全 suite 仍触发 720 秒上限；日志退出码 124，未出现 instrumentation 完成结果，精确提交中的 `am instrument` guard 为 720 秒。同 HEAD 的 [native run 37227157185](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37227157185) / `native-lab` 成功；这不等于 M0 全 suite 或真实 VPN 验收通过。
- 更早 HEAD `bd5ff024bcf4f829497b9a85056bf5830b13da76` 的 hosted run `37224246307` 曾出现旧订单加载文案断言失败及全套 720 秒超时。该文案断言已有本地修复验证；这是较早历史，不能替代上一项较近 CI 状态。全套超时不据此归零，本地新 HEAD 的 hosted CI 结果也不能由选定批次推断。
- PR 保持 Draft。本轮固定枚举只读诊断已完成；`NoReply` 具体环节和真实数值 RTT 仍待后续验证，再按实际证据更新状态，不改变既有真实账户/流量、B2 和生产边界。
