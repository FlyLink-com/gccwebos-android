# 飞连 VPN 四页 UI 与 v2 预览对齐

## 范围与依据

本轮按 B 档处理原生 Compose 界面、局部展示状态和相关测试，覆盖连接、节点、套餐、我的四个主页面。本文记录实现事实、局部验收与验证边界；不替代 PR 的整体合并门禁。

视觉依据是用户提供的 `D:\Downloads\feilian-vpn-app-ui-preview-v2.html`，SHA-256：

`bc7df82386ebadbdd69f651b0411ba10f515190644cbb329eb987d449cddc1ae`

HTML 的卡片层级、布局、间距与字体用于原生适配；其中的账号、余额、价格、套餐、日期与节点示例不进入产品数据。用户随后明确要求节点页去掉搜索和筛选，因此最终节点页不保留这两项预览控件。

历史适配记录 [native-ui-reference.md](native-ui-reference.md) 保留原状。本轮视觉基准以本文件所述 v2 HTML 和用户补充要求为准，不把历史记录中的 SDK、连接或测试状态视为当前状态。

## 资产与共享布局

- 连接页与我的页都直接复用已有 `app/src/main/res/drawable-nodpi/brand_robot_welcome.webp`。其字节与 HTML 中相应机器人资源一致，SHA-256 为 `233826f3ff968ae47d1cdb2ac09deca9fa9c45544a5a2868c14ab1605451acbd`。
- 不增加重复的 `brand_preview_welcome` 资源，不生成新的品牌图片；欢迎区不再裁切旧图来遮挡标识。
- 主页面保留四项等宽导航、系统安全区和可滚动内容；调整页面边距、卡片间距、圆角与标题层级。导航和动作使用最小高度，放大字体时允许换行和增高。
- 继续使用现有玻璃材质实现与降级路径，不更换背景渲染或引入外部 UI 运行依赖。

## 四页实现

| 页面 | 实现事实 | 数据与交互约束 |
| --- | --- | --- |
| 连接 | 欢迎区、突出显示的剩余流量、已用/总量、用量条、套餐周期与有效期组成统一账户卡；保留当前节点入口与连接动作。新增连接页专用用量展示和日期格式化组件。 | 用量仍来自已验证的账户快照；未知、未采集、同步延迟、周期结束和服务到期分别展示。缩短日期显示时保留账户时区对应的日历日期，不编造时间。连接按钮继续由现有状态与明确用户动作驱动。 |
| 节点 | 当前选择概览加真实节点列表；保留原节点选择标签和选中语义。移除搜索框、协议筛选及相关本地过滤状态。 | 列表使用原不透明选择对象，选择节点本身不启动 VPN。读取失败仍走原恢复入口；不为复刻 HTML 增加常驻刷新、自动择优、延迟或测速结果。只读及不兼容信息不获得可连接语义。 |
| 套餐 | 商品改为纵向宽卡，展示服务端商品名称、流量、Coins 与期限；提供月卡、年卡、流量包，存在非标准期限商品时显示“其他套餐”。购买摘要与“查看购买确认”位于主导航上方，离开套餐页时释放该页摘要与动作。 | 30 天归月卡，365 天归年卡，其他期限保持真实值并可访问。移除套餐页末尾重复的“当前资源／年卡365天”账户资源卡；真实可购买的年卡商品继续保留。 |
| 我的 | 欢迎卡显示账户身份、登录状态、当前套餐与有效期；首页余额突出单一可用金额，下方为钱包充值与账单入口。主菜单、邀请横幅和功能卡分组；“关于与更新”纳入功能卡并打开独立子页。 | 身份只使用现有安全显示字段；套餐来自 owner 已验证的 `accountUsage` 快照，验证中不回填旧账户资源。首页未知余额保持 `— Coins`；完整钱包仍保留可用、已购、赠送、奖励、预留、待确认六个独立字段，冻结与错误提示不被隐藏。关于入口不悬浮在钱包等子页下方。 |

主要实现位置：

- `MainActivity.kt`、`ConnectionOverview.kt`、`ConnectionUsageCard.kt`、`ConnectionUsageFormatting.kt`
- `portal/NodeSelectionPanel.kt`
- `commerce/CommercePanel.kt`、`commerce/CatalogPeriod.kt`、`commerce/CatalogPurchaseDock.kt`
- `commerce/MineWelcomeCard.kt`、`commerce/WalletPanel.kt`、`design/BrandTheme.kt`

以上路径均相对于 `app/src/main/java/com/gccwebos/android/`。

## 保留的业务边界

- 本轮未修改原生内核来源、ABI 或构建边界，也未修改认证模型、账户持久化实现和连接 owner 生命周期。
- 界面读取原有控制器状态。页面重排不授予登录、领取配置、建立连接、下单或付款权限；欢迎卡本身不触发额外账户读取。
- 购买栏只持有当前页面的展示摘要与受保护的预览动作。明确点击后仍进入原报价/确认流程；提交前继续检查当前会话、完整商品快照、忙碌状态与未决订单。页面销毁或摘要不可用时，旧动作不能继续执行。
- 保留原购买、充值及未决订单恢复流程；不从流量资源推算余额，不把预留或待确认款项加到可用金额。
- 关于页保留原更新组件，展示页面不等于检查更新；专用四页截图测试不点击检查更新、购买确认、VPN、注册或外部支付操作。

## 验证设计与证据边界

下面列出本轮测试的覆盖目标，不能据此认定已经完成运行或视觉验收。

| 测试 | 验证目标 |
| --- | --- |
| `ConnectionUsageFormattingTest` / `ConnectionUsageCardUiTest` | 日期缩写、无效输入保留、用量与未知状态展示、原动作回调及窄屏字体边界。 |
| `CatalogPeriodTest` / `CatalogDockStateTest` | 非标准期限保留；购买栏替换、禁用、隐藏与页面所有权释放后不重放旧动作。 |
| `SubscriptionWalletUiTest` / `WalletCompactUiTest` | 套餐页重复资源卡移除、真实年卡与非标准期限可达、服务端流量/价格展示、首页单一余额与完整钱包六项明细。 |
| `NodeSelectionUiTest` / `MineReferralUiTest` | 原节点选择及只读约束、菜单分组、关于子页边界、未知余额、操作尺寸及无额外请求。 |
| `PreviewV2LayoutUiTest` | 320dp、390dp 正常字体四页层级与截图；320dp/200% 字体导航及关键动作可达；离开套餐页后购买栏消失；我的功能卡与子页归属。 |

`PreviewV2LayoutUiTest` 使用真实 Compose `AppShell`，注入合成 `AccountController` 和 `CommerceController`。账户恢复通过纯内存 `PortalTransport` 返回的两次合成 overview 响应，走实际解析与 owner 验证；fixture 时间使用协议要求的固定三位毫秒 UTC 格式，并先经 `PortalJson.resource` 检查。节点使用保存在内存中的 TEST-NET 配置生成真实选择对象；认证使旧选择失效后，再显式准备这份合成观察结果。

该测试不发出真实 HTTP 或订阅下载请求。其余传输、外部支付、注册、VPN 回调和恢复存储写操作均设为失败条件。账号会话、资源、商品和钱包使用合成值；不读取真实账户或订阅文件。fixture 显式输出的文件仅为截图，写入隔离测试包的 `targetContext.getExternalFilesDir(null)/ui-evidence/preview-v2`。

逻辑宽度通过测试密度设置为 320dp 或 390dp，使整个设备视口参与布局，避免把宽组件直接裁在较窄设备外；200% 字体独立设置。截图需逐页核对文字、层级、裁切和操作可达性，测试断言不能代替视觉检查。

这些合成 UI 证据不证明真实账户登录恢复、真实订阅导入、真实节点流量、VPN 内核连接或 OPPO 异常恢复 B2 已验收。既有 native、连接与异常恢复记录仍按各自源码身份和实测证据管理。

## 2026-10-04 本地与 OPPO 验证回执

最终应用源码为 `eb2777799c5bdeeb9ef27494da85bcab30d406fc`。后续本文件和 JSON 回执提交只记录结果。

| 项目 | 实际结果 |
| --- | --- |
| 源码检查 | `scripts/verify_source.py`、`git diff --check` 通过；native 四个边界与 source-98 相同，固定库允许复用。 |
| JVM | Catalog、ConnectionUsage、AccountUsage、Glass、ConnectionOverview 相关 11 个 suite，共 101 项通过，0 failure/error/skip。 |
| 构建 | 普通候选 Debug、AndroidTest、unsigned release 均完成；Lint 0 error、27 warning、3 hint。 |
| 原生打包 | 两个 ABI 的 JNI、ELF/APK 16 KiB 对齐、许可证及 release 实验室组件排除检查通过。native producer 仍为 `98ffd6bae926e3d5f7c2a60b2b21af1278874436`，未改内核。 |
| OPPO UI 回归 | API33 独立合成包运行 9 个测试类中 70 个用例，70 通过，123.972 秒；该轮使用后来去重的相同字节图片别名。 |
| 最终资产复测 | 删除重复图片、改为复用已有资源后，重新构建并覆盖更新隔离包；3 个四页/大字体用例全部通过，31.82 秒。 |
| 截图 | 收集 16 张合成截图；人工检查正常字体的四页、我的功能卡，以及窄屏和大字体代表图。页面层级符合本轮要求，未将合成数值写入产品。 |
| 普通候选安装 | 同包名、同签名覆盖升级；安装后 APK SHA-256 与本地一致；15 个预定义私有路径的存在/类型结果一致，未读取内容。Activity 启动成功。 |
| 清理 | 私有路径检查后仅卸载本轮自建的隐藏 UI app/test 包；普通候选及用户数据保留，Android 16 模拟器未启动。 |

标准 `com.gccwebos.android.debug` 包名断言 `SubscriptionWalletUiTest#launcherAndAccessibleHeaderUseExactBrandNameWithoutChangingPackage` 不适用于隔离包，因此在上述 70 项选择中明确排除；源码中的原断言保留，不能把此记录写成全部测试通过。首轮 68 项中其余 67 项通过，这一个因隔离包名失败；首次截图 fixture 的时间格式和后续合并语义树查询失败也保留原日志。修正的是合成输入和测试查询，没有放宽账户校验或产品断言。

正常字号下连接与我的页保留纵向滚动；320dp 下部分内容及连接动作需要滚动。套餐确认栏位于导航上方，200% 字体时允许自身滚动以保持按钮可达。本轮是原生层级与布局适配，不声称不同尺寸下像素逐一相同；玻璃背景继续沿用原实现。

普通候选包名：`com.gccwebos.android.candidate.acceptance.bbr20261004v2`，显示名“飞连 VPN 测试”，versionCode 6。安装 APK SHA-256：

`46c16e322f2bdb8d681d76814e3384eef0baa8bfbac1d81755be25f5b711abc0`

synthetic/storage/lab/TUN acceptance 开关均为 false。回滚保留上一个同包同签名 APK（SHA-256 `3320d04ab2342a08e65ca2d167956c410cddea375e6ad8e1c59dfbbd4cf6cd60`），使用覆盖安装，不清数据。本轮未点击真实购买、更新检查或 VPN；真实账户重启恢复、实际节点流量和 B2 仍按原验收边界处理。

结构化计数和安装身份见 [verification.json](evidence/ui-preview-v2/2026-10-04/verification.json)。完整本机日志、APK、16 张图片位于工作树 `.build-output/ui-v2-layout/`，图片在 `screens/`，不含用户账户截图。普通构建命令使用工具链环境与已有 `normal-logged.init.gradle` 保持原包名：

```powershell
.\gradlew.bat --init-script .build-output/user-config-diagnosis/normal-logged.init.gradle -PconnectionCandidate=true :app:testDebugUnitTest --tests '*Catalog*Test' --tests '*ConnectionUsage*Test' --tests '*AccountUsage*Test' --tests '*Glass*Test' --tests '*ConnectionOverview*Test' :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease
python scripts/verify_native_apks.py
```

PR21 保持 Draft；本记录不宣称最终 PR head 的托管 CI 已通过。
