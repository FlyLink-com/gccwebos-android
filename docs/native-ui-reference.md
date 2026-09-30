# 原生 UI 适配与验证

## 来源与范围

主要视觉依据：用户指定的 `gccltd/gccwebos-mobile`，2026-09-30 核对的 master 为 `091ee6e82bb768333dcc23eaaf725c1fd07ba243`。

- `src/style.css`：深蓝底、浅色正文、加强的数据卡片、22px圆角、紧凑欢迎区
- `src/components/button-tokens.css`：主按钮 `#007da4 → #235bed → #6940e7`，渐变只用于主操作
- `src/components/AppFrame.jsx`：品牌角色与字标、线性导航图标、安全区
- `src/home.jsx` / `src/api/current-resource.mjs`：服务器资源快照，以及 pending、measured_zero、measured、stale 与未知额度区别

这次是 Android Jetpack Compose 的原生适配，不嵌入网页，也不将演示截图作为功能页面。Android 保留已有“连接／线路／套餐／我的”职责；网站的购买／订单／钱包功能未在原生端接通前不伪装成可用入口。

## 资产与使用依据

本次依用户明确要求复用其资料库与指定项目现有资产；没有重新生成品牌角色，没有修改原始图片像素。仓库 metadata 未声明开源许可证，因此这不是对第三方公开再分发权或商标权的证明。正式外部分发前由项目所有者确认完整权利链。

直接从上述 GitHub commit 取得：

- `public/complete/brand/gccwebos-wordmark.png` → `brand_wordmark.png`，Git blob `9256d1d0c4024ea30a28da1d2e49f467ab9c8ad8`
- `public/complete/characters/robot-welcome.webp` → `brand_robot_welcome.webp`
- `public/scenes/home-lounge.webp` → `brand_lounge.webp`，Git blob `8d3c46bf99b07d5c8c44cc88558a6d86e941ed93`

资料库补充来源：

- `gccwebos-ui-source.zip`，Library `libfile_bcdfea45572c81919fa8d0d0a568b58d`：`assets/characters/robot-avatar.png`、7个线性SVG图标
- 图标仅做机械格式转换为 Android VectorDrawable，路径/形状保留，使用主题色着色
- 实际查看参考 `gccwebos-ui-preview.jpg`，Library `libfile_a2f0fc1a63788191a1d5a0a60a4948f7`
- 实际查看 `scene-welcome.png`，Library `libfile_e3f5fb6c7a708191b87a843a7602ae1c`，仅研究参考，未打包
- 实际查看 `robot-master.png`，Library `libfile_aacf28275800819183da366ece1febba`，仅研究参考，未打包

`docs/native-ui-assets.json` 记录打包资产 SHA-256。背景为独立装饰层，数据卡保持不透明；大字模式省略欢迎区装饰角色，保留所有功能内容。图片没有价格、余额或用户数据。

## 状态与安全

- 原有 AccountController、API端点、认证、加密存储、领取、ack、取消和清除逻辑保持不变。DTO额外保留既有严格校验的 durationDays / monthlyTrafficGb，展示真实套餐规格，不新增接口或重新计算商品参数
- 输入与账号控制器位于导航之外，切换入口不丢失在途登录状态；页面滚动位置随入口重置
- 本地清除仍需确认；取消 TOTP 保留既有本地订阅；退出不宣称服务器撤销
- 用量按服务器真实状态显示。pending 绝不显示成0；stale 的已用和剩余都明确标为过期；未设置额度不推断成无限
- 无测量速度、假节点、假110 Coins、假商品价格或示例订单
- 生产连接按钮保持禁用，保留 `vpn-connect` 测试标签。PR5的实验室 VPN 功能仍仅 Debug 且默认关闭

## 验证方式

- `scripts/verify_source.py`：源码、XML、Wrapper校验及debug/release边界
- `ResourcePresentationTest`：11项真实/未知/过期/退出/服务端套餐参数显示映射用例
- `BrandUiTest`：真实Compose渲染四入口截图、重复导航与账号保留、清除取消、TOTP取消保留订阅与确认清除、85%/200%字体及动作可达性
- 模拟器脚本额外捕获320dp窄屏与约1067dp平板。核验顶层窗口、应用UI包与错误弹窗，不以进程存在或测试绿灯代替前景截图
- 所有UI用例使用离线状态或明确测试输入，不登录真实账户，不连接生产 VPN

本地无 Android SDK。源码检查可本地运行；完整 Gradle、APK、instrumentation 与实际截图由此精确提交的CI产生。没有CI结果及人工图片检查前不可宣称视觉验收通过。正式签名、物理真机、真实账号和生产VPN不属于本次验证结论。
