# MoRealm Issues 待修复/待实现

## 🌟 MoRealm 核心优势（vs Legado / 静读天下）

| 特性 | MoRealm | Legado | 静读天下 |
|------|---------|--------|---------|
| 主题切换 | 150ms 动画渐变，全局同步 | 硬切/Activity 重建 | 静态切换 |
| 阅读引擎 | WebView（完整 CSS/HTML/字体） | 自定义 Canvas（需手动实现排版） | 私有原生引擎 |
| 神经语音 | Edge TTS 21 个中文语音，零配置 | 仅系统 TTS 或用户配置 HTTP | 仅系统/Google TTS |
| 日志诊断 | 多 Sink 架构 + ANR 看门狗 + 崩溃报告 + 生命周期监控 | 100 条内存列表，无持久化 | 无 |
| TTS 架构 | 类型安全双向 SharedFlow | 全局 EventBus + 静态状态 | N/A |
| 阅读预设 | 30+ 字段，自动日/夜切换，Room 持久化 | 编号槽位，手动配日/夜 | 预设非数据库 |
| UI 框架 | Compose + HorizontalPager + 自适应布局 | XML Views + Fragments | XML Views |
| EPUB 解析 | epublib 随机访问（ParcelFileDescriptor） | epublib 随机访问 | 私有引擎 |

---

## 🔴 严重问题（需立即修复）

### TXT 解析性能
- [x] **块读取** — 512KB 块读取替代逐行扫描
- [x] **skip 跳转** — readChapter 用 skip() 直接定位，O(1)
- [x] **超大章节拆分** — >200KB 自动按 100KB 分段
- [ ] **TOC 规则可配置** — 用户自定义规则库（当前仅 7 个硬编码正则 + 1 个自定义）
- [ ] **编码检测增强** — 引入 juniversalchardet

### PDF 处理
- [x] **PdfRenderer 集成** — 逐页渲染为 JPEG，10 页一章
- [x] **封面提取** — 首页渲染为 JPEG 封面
- [x] **页码显示** — 当前/总页数

### 书源规则引擎
- [x] **RuleEngine 多模式** — CSS/XPath/JSONPath/Regex 四种模式
- [x] **XPath 支持** — JsoupXpath 库
- [x] **JSONPath 支持** — Jayway JsonPath 库
- [ ] **JS 引擎** — 引入 Rhino 或 QuickJS（大部分高级书源需要）
- [ ] **完整管道** — 搜索 → 书籍详情 → 目录 → 正文 → 下一页

### 阅读器缺失功能
- [x] **繁简转换** — quick-transfer-core，3 模式切换
- [x] **替换规则超时保护** — 3 秒超时 + 正则验证 + 标题/正文区分
- [x] **EPUB 图片查看修复** — 图片缓存路径正确传递
- [x] **阅读进度保存** — onCleared 时保存进度
- [x] **TTS 跳过图片** — 移除 img/svg 标签
- [x] **TTS 自动回退** — Edge TTS 失败自动切系统 TTS
- [x] **双页模式** — 平板横屏 CSS column-count:2
- [x] **点击区域自定义** — 4 区域（左上/右上/左下/右下）可配 prev/next/menu/tts/bookmark/none
- [x] **页眉页脚自定义** — 6 槽位，可选 time/battery/chapter/progress/page/none
- [x] **正文编辑** — 编辑章节内容并持久化到缓存

---

## 🟡 重要问题

### WebDAV 增强
- [x] PROPFIND XML 请求体
- [x] 文件列表浏览（parsePropfindResponse）
- [x] 异步 IO（withContext(Dispatchers.IO)）
- [x] DELETE 支持
- [ ] WebDAV 备份文件选择 UI

### 书源完整管道
- [x] WebBookEngine.fetchBookInfo
- [x] WebBookEngine.fetchToc（支持翻页）
- [x] WebBookEngine.fetchContent（支持翻页 + replaceRegex）
- [ ] 书籍详情 UI 接入
- [ ] 在线阅读章节内容接入
- [ ] JS 引擎（Rhino）

### 备份恢复增强
- [x] 主题/阅读预设备份恢复
- [x] 自动备份偏好设置
- [ ] 备份加密（AES）
- [ ] 自动备份定时执行

### TXT 解析增强
- [x] 块读取 + skip 跳转 + 自动拆分
- [x] TxtTocRule 实体 + DAO + 6 个内置规则
- [ ] TXT TOC 规则管理 UI
- [ ] 自动选择最佳匹配规则

### TTS 增强
- [x] HttpTts 实体 + DAO + HttpTtsEngine
- [ ] HTTP TTS 管理 UI（添加/编辑/测试）
- [ ] 朗读时翻页动画

---

## 🟢 已完成功能

### EPUB 解析 ✅（epublib 随机访问）
- [x] epublib 集成（ParcelFileDescriptor + AndroidZipFile）
- [x] 元数据 + 封面单次提取（~100ms vs 之前 ~10s）
- [x] 章节随机访问（不再流式扫描）
- [x] 图片随机访问（resources.getByHref）

### 阅读器 ✅
- [x] 阅读亮度调节（独立于系统，支持跟随系统）
- [x] 书签功能（Room 持久化，快速跳转）
- [x] 全文搜索（所有章节关键词搜索）
- [x] 自动翻页（可设间隔）
- [x] 选中文字操作（复制/分享/朗读）
- [x] 正文图片查看器（WebView 全屏）
- [x] 目录搜索（章节名过滤）
- [x] 正文替换净化（Room + 正则）
- [x] 段间距/页边距精细调节
- [x] 翻页动画（仿真/覆盖/滑动/淡入淡出/无）
- [x] 阅读界面切换主题
- [x] 自定义 CSS 注入
- [x] 阅读器预设样式（5 个内置 + 自定义，自动日/夜切换）

### 书架 ✅
- [x] Grid/List 视图切换
- [x] 批量管理（多选删除/移动分组）
- [x] 书籍分组（创建/重命名/移动）
- [x] 文件夹封面拼图（显示内部书籍封面缩略图）
- [x] 默认名称排序
- [x] 置顶功能（书籍/文件夹）
- [x] 两阶段导入（即时显示 + 后台补全元数据）

### TTS ✅
- [x] Edge TTS（21 个中文神经语音，零配置）
- [x] 系统 TTS + 语音角色切换
- [x] 朗读规则（跳过特定内容）
- [x] 自动翻页跟随
- [x] 通知栏控制 + 音频焦点
- [x] 睡眠定时器

### 日志系统 ✅
- [x] 多 Sink 架构（Logcat/Memory/RollingFile）
- [x] 异步文件写入（专用线程 + BlockingQueue）
- [x] ANR 看门狗 + 崩溃报告
- [x] 应用内日志查看器
- [x] 6 级日志（VERBOSE/DEBUG/INFO/WARN/ERROR/FATAL）

### 其他 ✅
- [x] 快捷方式（长按图标打开最近阅读）
- [x] 导出书籍为 TXT
- [x] CBZ/CBR 漫画格式
- [x] WebDAV 基础同步
- [x] 本地备份/恢复
- [x] 阅读时间统计
- [x] 120fps 高刷新率
- [x] 安全导航（防预测性返回崩溃）
- [x] 数据库迁移（不再丢数据）



























问题追加

title=null, nextContentUrl=null, webJs=, sourceRegex=null, replaceRegex=null, imageStyle=0, imageDecode=null, payAction=null), ruleReview=null) 中找不到函数 getSource 。 (<Unknown Source>#1)
	at org.mozilla.javascript.ScriptRuntime.constructError(ScriptRuntime.java:5235)
	at org.mozilla.javascript.ScriptRuntime.constructError(ScriptRuntime.java:5216)
	at org.mozilla.javascript.ScriptRuntime.typeError(ScriptRuntime.java:5248)
	at org.mozilla.javascript.ScriptRuntime.typeErrorById(ScriptRuntime.java:5253)
	at org.mozilla.javascript.ScriptRuntime.notFunctionError(ScriptRuntime.java:5367)
	at org.mozilla.javascript.ScriptRuntime.getPropFunctionAndThisHelper(ScriptRuntime.java:2939)
	at org.mozilla.javascript.ScriptRuntime.getPropFunctionAndThisInner(ScriptRuntime.java:2907)
	at org.mozilla.javascript.ScriptRuntime.getPropFunctionAndThis(ScriptRuntime.java:2892)
	at org.mozilla.javascript.Interpreter.interpretLoop(Interpreter.java:1872)
	at org.mozilla.javascript.Interpreter.interpret(Interpreter.java:1158)
	at org.mozilla.javascript.InterpretedFunction.call(InterpretedFunction.java:87)
	at org.mozilla.javascript.ContextFactory.doTopCall(ContextFactory.java:383)
	at com.script.rhino.RhinoScriptEngine$1.superDoTopCall(RhinoScriptEngine.kt:386)
	at com.script.rhino.RhinoScriptEngine$1.doTopCall(RhinoScriptEngine.kt:363)
	at org.mozilla.javascript.ScriptRuntime.doTopCall(ScriptRuntime.java:4449)
	at org.mozilla.javascript.InterpretedFunction.exec(InterpretedFunction.java:100)
	at com.script.rhino.RhinoCompiledScript.eval(RhinoCompiledScript.kt:68)
	... 10 more

[11:18:16.150] W/Search [SearchPool-4]: 🌸红袖招 failed: Failed to connect to hongxiub.com/23.224.228.84:443
java.net.ConnectException: Failed to connect to hongxiub.com/23.224.228.84:443
	at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:297)
	at okhttp3.internal.connection.RealConnection.connect(RealConnection.kt:207)
	at okhttp3.internal.connection.ExchangeFinder.findConnection(ExchangeFinder.kt:226)
	at okhttp3.internal.connection.ExchangeFinder.findHealthyConnection(ExchangeFinder.kt:106)
	at okhttp3.internal.connection.ExchangeFinder.find(ExchangeFinder.kt:74)
	at okhttp3.internal.connection.RealCall.initExchange$okhttp(RealCall.kt:255)
	at okhttp3.internal.connection.ConnectInterceptor.intercept(ConnectInterceptor.kt:32)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.cache.CacheInterceptor.intercept(CacheInterceptor.kt:95)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.BridgeInterceptor.intercept(BridgeInterceptor.kt:83)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.kt:76)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at com.morealm.app.domain.http.OkHttpUtilsKt$okHttpClient_delegate$lambda$2$$inlined$-addInterceptor$1.intercept(OkHttpClient.kt:1092)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain$okhttp(RealCall.kt:201)
	at okhttp3.internal.connection.RealCall$AsyncCall.run(RealCall.kt:517)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1167)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:641)
	at java.lang.Thread.run(Thread.java:764)
Caused by: java.net.ConnectException: failed to connect to hongxiub.com/23.224.228.84 (port 443) from /172.16.1.15 (port 47874) after 15000ms: isConnected failed: ECONNREFUSED (Connection refused)
	at libcore.io.IoBridge.isConnected(IoBridge.java:278)
	at libcore.io.IoBridge.connectErrno(IoBridge.java:187)
	at libcore.io.IoBridge.connect(IoBridge.java:129)
	at java.net.PlainSocketImpl.socketConnect(PlainSocketImpl.java:137)
	at java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:390)
	at java.net.AbstractPlainSocketImpl.connectToAddress(AbstractPlainSocketImpl.java:230)
	at java.net.AbstractPlainSocketImpl.connect(AbstractPlainSocketImpl.java:212)
	at java.net.SocksSocketImpl.connect(SocksSocketImpl.java:436)
	at java.net.Socket.connect(Socket.java:621)
	at okhttp3.internal.platform.AndroidPlatform.connectSocket(AndroidPlatform.kt:63)
	at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:295)
	... 20 more
Caused by: android.system.ErrnoException: isConnected failed: ECONNREFUSED (Connection refused)
	at libcore.io.IoBridge.isConnected(IoBridge.java:267)
	... 30 more

[11:18:16.153] W/Search [SearchPool-4]: 📥搬书匠 failed: unexpected end of stream on http://www.banshujiang.cn/...
java.io.IOException: unexpected end of stream on http://www.banshujiang.cn/...
	at okhttp3.internal.http1.Http1ExchangeCodec.readResponseHeaders(Http1ExchangeCodec.kt:210)
	at okhttp3.internal.connection.Exchange.readResponseHeaders(Exchange.kt:110)
	at okhttp3.internal.http.CallServerInterceptor.intercept(CallServerInterceptor.kt:93)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.connection.ConnectInterceptor.intercept(ConnectInterceptor.kt:34)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.cache.CacheInterceptor.intercept(CacheInterceptor.kt:95)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.BridgeInterceptor.intercept(BridgeInterceptor.kt:83)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.kt:76)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at com.morealm.app.domain.http.OkHttpUtilsKt$okHttpClient_delegate$lambda$2$$inlined$-addInterceptor$1.intercept(OkHttpClient.kt:1092)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain$okhttp(RealCall.kt:201)
	at okhttp3.internal.connection.RealCall$AsyncCall.run(RealCall.kt:517)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1167)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:641)
	at java.lang.Thread.run(Thread.java:764)
Caused by: java.io.EOFException: \n not found: limit=0 content=…
	at okio.RealBufferedSource.readUtf8LineStrict(RealBufferedSource.kt:339)
	at okhttp3.internal.http1.HeadersReader.readLine(HeadersReader.kt:29)
	at okhttp3.internal.http1.Http1ExchangeCodec.readResponseHeaders(Http1ExchangeCodec.kt:180)
	... 18 more

[11:18:16.154] W/Search [SearchPool-4]: 🎈1gtxt failed: Unable to resolve host "www.haitangzhu.com": No address associated with hostname
java.net.UnknownHostException: Unable to resolve host "www.haitangzhu.com": No address associated with hostname
	at java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:157)
	at java.net.Inet6AddressImpl.lookupAllHostAddr(Inet6AddressImpl.java:105)
	at java.net.InetAddress.getAllByName(InetAddress.java:1154)
	at okhttp3.Dns$Companion$DnsSystem.lookup(Dns.kt:49)
	at okhttp3.internal.connection.RouteSelector.resetNextInetSocketAddress(RouteSelector.kt:169)
	at okhttp3.internal.connection.RouteSelector.nextProxy(RouteSelector.kt:131)
	at okhttp3.internal.connection.RouteSelector.next(RouteSelector.kt:73)
	at okhttp3.internal.connection.ExchangeFinder.findConnection(ExchangeFinder.kt:205)
	at okhttp3.internal.connection.ExchangeFinder.findHealthyConnection(ExchangeFinder.kt:106)
	at okhttp3.internal.connection.ExchangeFinder.find(ExchangeFinder.kt:74)
	at okhttp3.internal.connection.RealCall.initExchange$okhttp(RealCall.kt:255)
	at okhttp3.internal.connection.ConnectInterceptor.intercept(ConnectInterceptor.kt:32)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.cache.CacheInterceptor.intercept(CacheInterceptor.kt:95)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.BridgeInterceptor.intercept(BridgeInterceptor.kt:83)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.kt:76)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at com.morealm.app.domain.http.OkHttpUtilsKt$okHttpClient_delegate$lambda$2$$inlined$-addInterceptor$1.intercept(OkHttpClient.kt:1092)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain$okhttp(RealCall.kt:201)
	at okhttp3.internal.connection.RealCall$AsyncCall.run(RealCall.kt:517)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1167)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:641)
	at java.lang.Thread.run(Thread.java:764)
Caused by: android.system.GaiException: android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)
	at libcore.io.Linux.android_getaddrinfo(Native Method)
	at libcore.io.BlockGuardOs.android_getaddrinfo(BlockGuardOs.java:172)
	at java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:137)
	... 25 more

[11:18:18.706] W/Search [SearchPool-4]: 🌸笔趣阁② failed: Failed to connect to m.ibiquge.net/156.251.255.148:443
java.net.ConnectException: Failed to connect to m.ibiquge.net/156.251.255.148:443
	at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:297)
	at okhttp3.internal.connection.RealConnection.connect(RealConnection.kt:207)
	at okhttp3.internal.connection.ExchangeFinder.findConnection(ExchangeFinder.kt:226)
	at okhttp3.internal.connection.ExchangeFinder.findHealthyConnection(ExchangeFinder.kt:106)
	at okhttp3.internal.connection.ExchangeFinder.find(ExchangeFinder.kt:74)
	at okhttp3.internal.connection.RealCall.initExchange$okhttp(RealCall.kt:255)
	at okhttp3.internal.connection.ConnectInterceptor.intercept(ConnectInterceptor.kt:32)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.cache.CacheInterceptor.intercept(CacheInterceptor.kt:95)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.BridgeInterceptor.intercept(BridgeInterceptor.kt:83)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.kt:76)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at com.morealm.app.domain.http.OkHttpUtilsKt$okHttpClient_delegate$lambda$2$$inlined$-addInterceptor$1.intercept(OkHttpClient.kt:1092)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.kt:109)
	at okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain$okhttp(RealCall.kt:201)
	at okhttp3.internal.connection.RealCall$AsyncCall.run(RealCall.kt:517)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1167)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:641)
	at java.lang.Thread.run(Thread.java:764)
Caused by: java.net.ConnectException: failed to connect to m.ibiquge.net/156.251.255.148 (port 443) from /172.16.1.15 (port 34048) after 15000ms: isConnected failed: ECONNREFUSED (Connection refused)
	at libcore.io.IoBridge.isConnected(IoBridge.java:278)
	at libcore.io.IoBridge.connectErrno(IoBridge.java:187)
	at libcore.io.IoBridge.connect(IoBridge.java:129)
	at java.net.PlainSocketImpl.socketConnect(PlainSocketImpl.java:137)
	at java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:390)
	at java.net.AbstractPlainSocketImpl.connectToAddress(AbstractPlainSocketImpl.java:230)
	at java.net.AbstractPlainSocketImpl.connect(AbstractPlainSocketImpl.java:212)
	at java.net.SocksSocketImpl.connect(SocksSocketImpl.java:436)
	at java.net.Socket.connect(Socket.java:621)
	at okhttp3.internal.platform.AndroidPlatform.connectSocket(AndroidPlatform.kt:63)
	at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:295)
	... 20 more
Caused by: android.system.ErrnoException: isConnected failed: ECONNREFUSED (Connection refused)
	at libcore.io.IoBridge.isConnected(IoBridge.java:267)
	... 30 more
