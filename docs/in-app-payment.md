# 应用内付款页（支付宝）

状态：草稿，下个版本。后端、订单、回调、轮询均不变。

## 做了什么

点「支付宝支付」后，不再用 `ACTION_VIEW` 跳到系统浏览器，而是打开应用内的 `PaymentActivity`（私有，`exported=false`）：

- 只加载后端返回、并已通过 `CommerceJson.trustedPaymentRedirect(url, "xsd_v1")` 校验的网关 https 地址；Activity 启动时再校验一次，失败直接关闭。
- 页面里的每次跳转都经过 `PaymentNavigationPolicy`（纯函数，有单元测试）：
  - 同一 https 来源（`www.xsd8866.com`）：继续在页面内。
  - `alipays:`／`alipay:`：交给支付宝（`ACTION_VIEW` + `setPackage("com.eg.android.AlipayGphone")`，不走系统选择器，其他 App 即使注册了同名 scheme 也收不到）。
  - `intent://…#Intent;scheme=alipays;package=…;end`：只读 scheme 与 package，映射成普通 `alipays:` URI；package 不是支付宝、含 component／selector／action／extra 等字段一律拒绝；从不调用 `Intent.parseUri`，`browser_fallback_url` 只丢弃不跟随。
  - 其他一切（其他 https 来源、http、file、content、javascript、data、about、blob 等）：拦截。
- 没装支付宝：网关页面保持可见（可用它的网页／二维码方式），顶部给出一句大白话提示。
- 返回键或左上角返回：关闭付款页回到应用，应用恢复前台时沿用原有的钱包刷新和每 10 秒订单轮询（`CommercePanel`），到账仍以服务器查询为准。
- 右上角「浏览器打开」：页面在应用内无法使用时，用原来的系统浏览器方式打开同一个订单地址。
- 确认弹窗不再显示任何域名（去掉「将打开：…」「portal.gccwebos.com」），来源校验仍在后台生效：`canOpenPayment` 不通过时按钮不可用。

## WebView 设置

JavaScript 开启（网关页需要它发起支付宝跳转）；DOM Storage、文件访问、content 访问、地理位置、多窗口、`addJavascriptInterface`、调试均关闭；混合内容一律拒绝；Safe Browsing 开启；证书错误直接取消（不提供「继续」）；缓存关闭；第三方 Cookie 关闭，页面开始前和关闭后清空全部 Cookie、WebStorage、缓存；`FLAG_SECURE`（禁止截屏和最近任务缩略图）。下载、文件选择、权限请求一律拒绝。

## 清单与发布检查

- `AndroidManifest.xml`：新增 `<queries>`，只含支付宝包名和 `alipays`／`alipay` 两个 scheme；不使用 `QUERY_ALL_PACKAGES`。
- `scripts/verify_source.py` 新增：`PaymentActivity` 不导出且无 intent-filter、`queries` 恰为上述三项、WebView 加固项仍在源码中、调试测试活动不得出现在主清单。

## 调试专用测试接缝（不进 release）

`app/src/debug/.../payment/PaymentTestActivity`：真实 `PaymentActivity` 加上本地 HTML（以网关来源为基址加载，不访问网络）；`FakeAlipayActivity`：代替支付宝显示收到的链接。用于不接触真实网关和支付宝时验证拦截与交接。

## 没有验证的

真实 `www.xsd8866.com` 付款页在 WebView 里的行为（是否用脚本跳转支付宝、是否依赖 DOM Storage、是否经过其他 https 域名中转）和真实支付宝交接。如果真实页面经过其他 https 域名，会被拦截；届时需要把确认过的支付宝来源加入白名单，或用「浏览器打开」。
