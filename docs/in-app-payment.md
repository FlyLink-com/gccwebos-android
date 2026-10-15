# 应用内付款页（支付宝）

状态：草稿，下个版本。后端、订单、回调、轮询均不变。

## 做了什么

点「支付宝支付」后，不再用 `ACTION_VIEW` 跳到系统浏览器，而是打开应用内的 `PaymentActivity`（私有，`exported=false`）：

- 只加载后端返回、并已通过 `CommerceJson.trustedPaymentRedirect(url, "xsd_v1")` 校验的网关 https 地址；Activity 启动时再校验一次，失败直接关闭。
- 页面里的每次跳转都经过 `PaymentNavigationPolicy`（纯函数，有单元测试）：
  - 以网关入口（`www.xsd8866.com`）为起点的顶层跳转链（见下节「跳转链与安全取舍」）：继续在页面内，全程不显示任何域名。
  - `alipays:`／`alipay:`：交给支付宝（`ACTION_VIEW` + `setPackage("com.eg.android.AlipayGphone")`，不走系统选择器，其他 App 即使注册了同名 scheme 也收不到）。
  - `intent://…#Intent;scheme=alipays;package=…;end`：只读 scheme 与 package，映射成普通 `alipays:` URI；package 不是支付宝、含 component／selector／action／extra 等字段一律拒绝；从不调用 `Intent.parseUri`，`browser_fallback_url` 只丢弃不跟随。
  - 其他一切（链外 https、http、file、content、javascript、data、about、blob 等）：拦截，显示整页提示和「浏览器打开」，提示里不写域名。
- 没装支付宝：网关页面保持可见（可用它的网页／二维码方式），顶部给出一句大白话提示。
- 返回键或左上角返回：关闭付款页回到应用，应用恢复前台时沿用原有的钱包刷新和每 10 秒订单轮询（`CommercePanel`），到账仍以服务器查询为准。
- 右上角「浏览器打开」：页面在应用内无法使用时，用原来的系统浏览器方式打开同一个订单地址。
- 确认弹窗不再显示任何域名（去掉「将打开：…」「portal.gccwebos.com」），来源校验仍在后台生效：`canOpenPayment` 不通过时按钮不可用。

## 跳转链与安全取舍

网关会把每个订单跳转到不同的第三方 https 页面（域名轮换），每个页面上都有「立即支付」按钮可拉起支付宝，所以无法用按域名白名单。改为信任「链」而不是域名：

- 起点必须是已校验的网关入口；之后顶层页面可以沿 https 跳转到其他主机（服务器重定向，或脚本/meta 无用户手势的跳转）。
- 只允许 https：http 一律拦截；证书错误取消、不允许混合内容。
- 链上最多新增 5 个主机（`MAX_HOPS`），顶层导航总数最多 25 次（防循环）；超出即拦截。
- 用户点链接跳向链外的新主机：拦截（服务器重定向除外）；子框架不能扩展链，只能加载链内主机。
- 主机必须是普通域名：不接受带 userinfo、非 443 端口、IP、单标签主机、结尾带点的地址。
- 弹窗/新窗口不开（`onCreateWindow` 返回 false，多窗口关闭）；`target=_blank` 在原视图内走同一套规则。
- 用户界面不显示任何域名；等待时只显示「正在前往支付宝…」。调试构建的 `GCCPay` 日志仍只记录主机/scheme（不含路径、参数、Cookie）。
- 手动「重试」会重新开始一条新链。

取舍：任何能被网关重定向到的 https 站点都能在应用内显示（但没有 JS 桥、没有文件/应用 Cookie、不能截屏、不能弹窗、不能由用户点击带出链外），支付宝交接始终只发给 `com.eg.android.AlipayGphone`。风险比「只允许一个域名」大，换来每笔订单都能完成；页面一旦失败有「浏览器打开」兜底。

## WebView 设置

JavaScript 开启（网关页需要它发起支付宝跳转）；DOM Storage、文件访问、content 访问、地理位置、多窗口、`addJavascriptInterface`、调试均关闭；混合内容一律拒绝；Safe Browsing 开启；证书错误直接取消（不提供「继续」）；缓存关闭；第三方 Cookie 关闭，页面开始前和关闭后清空全部 Cookie、WebStorage、缓存；`FLAG_SECURE`（禁止截屏和最近任务缩略图）。下载、文件选择、权限请求一律拒绝。

## 清单与发布检查

- `AndroidManifest.xml`：新增 `<queries>`，只含支付宝包名和 `alipays`／`alipay` 两个 scheme；不使用 `QUERY_ALL_PACKAGES`。
- `scripts/verify_source.py` 新增：`PaymentActivity` 不导出且无 intent-filter、`queries` 恰为上述三项、WebView 加固项仍在源码中、调试测试活动不得出现在主清单。

## 调试专用测试接缝（不进 release）

`app/src/debug/.../payment/PaymentTestActivity`：真实 `PaymentActivity` 加上本地 HTML（以网关来源为基址加载，不访问网络）；`FakeAlipayActivity`：代替支付宝显示收到的链接。用于不接触真实网关和支付宝时验证拦截与交接。

## 没有验证的

真实付款页链在 WebView 里的最终表现（落地页是否能渲染、是否需要用户点「立即支付」、支付宝交接）；见 PR 评论中的真机结果。
