# MoRealm vs Legado — 书源系统深度对比（修订版）

> 分析日期：2026-04-30
> 修订依据：`D:\temp_build\sigma\legado` 的 Legado 源码 + 当前 `D:\temp_build\MoRealm` 源码
> 范围：书源导入、规则引擎、URL 解析、网络层、JS 桥接、搜索/详情/目录/正文解析管线
> 结论：原文大方向成立，但若直接按“字段是否存在”判断兼容性，会高估 MoRealm 对 Legado 书源的兼容程度。

---

## 0. 本次审阅修正摘要

### 0.1 原文中需要修正的错误表述

| 原表述 | 修正后判断 | 说明 |
|---|---|---|
| MoRealm `preUpdateJs` 有字段但未使用 | **错误/过时**：MoRealm 已执行 `preUpdateJs`，但语义不完整 | 当前只是简单 `evalJS`，缺 Legado 的 book/ruleData 上下文和 `reGetBook()` / `refreshTocUrl()` |
| MoRealm `formatJs` 有字段但未使用 | **错误/过时**：MoRealm 已逐章执行 `formatJs` | 但只支持返回值式改标题，缺 Legado 的 `chapter` 绑定和副作用式修改 |
| MoRealm 不支持 `isVolume` | **错误/过时**：MoRealm 已解析 `isVolume` | 需要继续核验与 Legado 在卷 URL、tag、正文跳过等细节上的一致性 |
| MoRealm `AnalyzeUrl` 不支持 `charset` | **错误**：当前 MoRealm 已支持 URL option `charset` | 实现细节仍比 Legado 简化 |
| MoRealm `AnalyzeUrl` 不支持 `retry` | **错误**：当前 MoRealm 已支持 URL option `retry` | `newCallStrResponse(retry)` / `newCallByteArrayResponse(retry)` 已接入 |
| MoRealm `RuleAnalyzer` 基于简单正则拆分 | **错误**：当前 MoRealm `RuleAnalyzer` 已有平衡括号/引号/转义处理 | 仍需复杂规则样例回归测试，不能直接定性为简单正则 |
| MoRealm `JsExtensions` 是 Legado 的方法级真子集 | **不严谨**：方法名不是严格真子集，语义能力接近子集 | MoRealm 有一些同名方法，但为空实现、弱实现或仅日志占位 |
| MoRealm 有 `CocktailBlocker` JS 沙箱优势 | **未核实**：当前源码未找到该实现 | 除非另有分支/模块，否则该优势项应删除 |

### 0.2 本次已开始修复的直接故障

“导入书源后搜索全部失败”的高概率根因不只是字段缺失，而是 **Legado URL option 兼容不足**：

1. 很多 Legado 书源的 `searchUrl` 仍包含旧占位符：`searchKey` / `searchPage`。
2. 很多 Legado 书源的 URL option 中 `body` 是 JSON object，例如：
   ```json
   {"method":"POST","body":{"title":"searchKey","pageNum":1}}
   ```
   原 MoRealm `AnalyzeUrl.UrlOption.body` 是 `String?`，遇到 object 会导致整个 option 解析失败，最终 POST/body 丢失，搜索请求自然失败。
3. `BookSourceImporter` 原先没有导入 `jsLib` / `enabledCookieJar`，会让依赖共享 JS 库或 Cookie 语义的书源导入后能力下降。

本次代码修复方向：

- `AnalyzeUrl` 增加 `searchKey` / `searchPage` JS 绑定别名；
- `AnalyzeUrl.UrlOption.body` / `headers` / `webView` / `retry` 改为兼容 `JsonElement`，支持 object/array/string/boolean/int/string-int；
- URL 和 URL option 中的旧占位符 `searchKey` / `searchPage` 做兼容替换；
- `BookSourceImporter` 导入 `jsLib` / `enabledCookieJar`。

---

## 1. 数据模型与导入持久化

### 1.1 BookSource 顶层字段

| 字段 | Legado | MoRealm 实体 | MoRealm 导入 | 评估 |
|---|---:|---:|---:|---|
| `bookSourceUrl` | ✓ | ✓ | ✓ | 持平 |
| `bookSourceName` | ✓ | ✓ | ✓ | 持平 |
| `bookSourceGroup` | ✓ | ✓ | ✓ | 持平 |
| `bookSourceType` | 0-4，含 video | 0-3，无 video | ✓ | Legado 多视频源类型 |
| `bookUrlPattern` | ✓ | ✓ | ✓ | 持平 |
| `enabled` / `enabledExplore` | ✓ | ✓ | ✓ | 持平 |
| `enabledCookieJar` | ✓ | ✓ | **修复前未导入；本次补上** | 字段存在不等于语义完整，网络层仍需按字段控制 Cookie |
| `concurrentRate` | ✓ | ✓ | ✓ | 持平 |
| `header` | ✓ | ✓ | ✓ | 基本持平 |
| `loginUrl` / `loginUi` / `loginCheckJs` | ✓ | ✓ | ✓ | 流程语义仍有差异，见下文 |
| `coverDecodeJs` | ✓ | ✓ | ✓ | 字段有，执行链路需继续核验 |
| `bookSourceComment` / `variableComment` | ✓ | ✓ | ✓ | 持平 |
| `jsLib` | ✓ | ✓ | **修复前未导入；本次补上** | 这是共享 JS 兼容的关键字段 |
| `exploreUrl` / `searchUrl` | ✓ | ✓ | ✓ | 字段持平，但 URL option 语义需兼容 |
| `ruleSearch` / `ruleBookInfo` / `ruleToc` / `ruleContent` / `ruleExplore` | ✓ | ✓ | ✓ | 字段大体持平，但执行语义不完全一致 |
| `ruleReview` | ✓ | ✓ | ✗ | MoRealm 当前 TypeConverter 永远返回 null，实际不可用 |
| `weight` / `respondTime` / `lastUpdateTime` | ✓ | ✓ | ✓ | 持平 |
| `exploreScreen` | ✓ | ✗ | ✗ | Legado 独有，探索筛选缺失 |
| `eventListener` | ✓ | ✗ | ✗ | Legado 独有，事件回调体系缺失 |
| `customButton` | ✓ | ✗ | ✗ | Legado 独有，自定义按钮缺失 |

> 关键修正：不能只看实体字段。MoRealm 有些字段存在，但导入器没导入、TypeConverter 不持久化、解析流程没执行，都应视为兼容缺口。

### 1.2 规则子实体

| 规则类 | 字段层面对比 | 实际兼容性评估 |
|---|---|---|
| `SearchRule` | 字段基本相同 | `checkKeyWord` 在 MoRealm 中未见完整使用，不能称“完全相同” |
| `BookInfoRule` | 字段基本相同 | MoRealm 当前未完整执行 `downloadUrls` / `canReName` / `updateTime` 等语义 |
| `TocRule` | 字段相同 | MoRealm 已执行 `preUpdateJs` / `formatJs` / `isVolume`，但语义弱于 Legado |
| `ExploreRule` | 字段相同 | 基本发现列表规则相同，但缺 `exploreScreen` 筛选体系 |
| `ReviewRule` | 字段相同 | MoRealm 当前不能持久化/导入/执行，实际段评兼容性近似无 |
| `ContentRule` | MoRealm 缺 `subContent` / `callBackJs` | 另有多个字段存在但未接入正文流程 |

### 1.3 ContentRule 差异

```kotlin
// Legado                          // MoRealm
var content: String?               var content: String?
var subContent: String?   ← 缺失   —
var title: String?                 var title: String?       // 字段有，但正文流程未见执行
var nextContentUrl: String?        var nextContentUrl: String?
var webJs: String?                 var webJs: String?       // 字段有，但正文请求未接入
var sourceRegex: String?           var sourceRegex: String? // 字段有，但正文请求未接入
var replaceRegex: String?          var replaceRegex: String?
var imageStyle: String?            var imageStyle: String?  // 字段有，阅读图像语义需补
var imageDecode: String?           var imageDecode: String? // 字段有，图片解密链路需补
var payAction: String?             var payAction: String?   // 字段有，付费动作链路需补
var callBackJs: String?   ← 缺失   —
```

`subContent` 在 Legado 中不只是“正文后拼接”：

- 在线 TXT：可附加到正文；
- 音频源：可作为歌词写入章节变量；
- 视频源：可作为弹幕写入章节变量。

`callBackJs` 与 `BookSource.eventListener` 配合，支撑点击作者、点击书名、自定义按钮、书架刷新、开始阅读/结束阅读等事件回调。

---

## 2. 规则引擎 AnalyzeRule

### 2.1 解析模式

| 模式 | Legado | MoRealm | 说明 |
|---|---:|---:|---|
| CSS / JSoup | ✓ | ✓ | 基本持平 |
| XPath | ✓ | ✓ | 基本持平 |
| JSONPath | ✓ | ✓ | 基本持平 |
| Regex | ✓ | ✓ | 基本持平 |
| JavaScript `<js>` / `@js:` | ✓ | ✓ | 基本持平，但 JS binding 有差异 |
| 规则链 `<webJs>` | ✓ | ✗ | Legado 独有，MoRealm 缺规则级 WebView JS 模式 |

注意区分三种 WebJs：

1. URL option `webJs`：MoRealm 支持；
2. `ContentRule.webJs`：MoRealm 字段存在，但正文请求流程未接入；
3. 规则链 `<webJs>`：MoRealm 当前不支持。

### 2.2 JS 缓存

| 维度 | Legado | MoRealm | 风险 |
|---|---|---|---|
| 脚本缓存范围 | `AnalyzeRule` 实例级 | 全局 `ConcurrentHashMap` | MoRealm 跨源复用更强，但无上限 |
| 缓存上限 | `getOrPutLimit(..., 16)` | 无 | MoRealm 长期使用可能内存增长 |
| 线程安全 | 非并发 Map | ConcurrentHashMap | MoRealm 更适合并发，但仍需 LRU |

建议：MoRealm 的 JS 编译缓存应改为有界 LRU，例如 128/256 条，而不是无限增长。

### 2.3 变量查找链

Legado `AnalyzeRule.get()` 查找链：

```text
chapter → book → ruleData → source
```

MoRealm `AnalyzeRule.get()` 当前主要是：

```text
ruleData only
```

MoRealm `JsExtensions.get()` 可退回 source 级变量，但仍缺 Legado 的 chapter/book/ruleData/source 完整链。依赖 `@get:{}` 或跨章节变量传递的书源可能不兼容。

### 2.4 RuleAnalyzer

原文称 MoRealm RuleAnalyzer “基于正则拆分”不准确。当前 MoRealm `RuleAnalyzer` 已包含：

- 平衡括号/方括号解析；
- 单引号/双引号处理；
- 转义字符处理；
- `{{...}}` 内部规则解析。

更合理的结论：MoRealm RuleAnalyzer 已具备基础复杂规则拆分能力，但是否 100% 兼容 Legado 需要用复杂书源规则回归测试。

---

## 3. AnalyzeUrl 与网络层

### 3.1 URL option 功能对比

| 功能 | Legado | MoRealm 当前 | 修正说明 |
|---|---:|---:|---|
| `{{key}}` / `{{page}}` | ✓ | ✓ | 基本支持 |
| 旧占位符 `searchKey` / `searchPage` | 导入旧源时会转换 | **修复前不足；本次补运行时兼容** | 这是“搜索全部失败”的关键根因之一 |
| GET | ✓ | ✓ | 持平 |
| POST | ✓ | ✓ | 修复前 body object 解析失败；本次补 object/array/string |
| HEAD URL option | ✓ | ✗ | MoRealm 只有 JS 扩展 `head()`，URL option method 还缺 HEAD |
| headers object | ✓ | **本次增强** | 原先只支持 `Map<String,String>`，现支持 JsonElement |
| body string | ✓ | ✓ | 持平 |
| body object/array | ✓ | **本次增强** | 原先 `body: String?` 会导致解析失败 |
| `charset` | ✓ | ✓ | 原文误写为 MoRealm 不支持 |
| `retry` | ✓ | ✓ | 原文误写为 MoRealm 不支持 |
| URL option `webView` | ✓ | ✓ | 本次增强 string/boolean 兼容 |
| URL option `webJs` | ✓ | ✓ | 持平 |
| `bodyJs` | ✓ | ✗ | MoRealm 缺响应二次处理 JS |
| `dnsIp` | ✓ | ✗ | MoRealm 缺自定义 DNS IP |
| `proxy` | ✓ | ✗ | MoRealm 缺 HTTP/SOCKS 代理支持 |
| `serverID` | ✓ | ✗ | MoRealm 缺多服务器区分 |
| `webViewDelayTime` | ✓ | ✗ | MoRealm 缺 WebView 延迟等待参数 |
| `data:` URI 字节处理 | ✓ | ✗ | MoRealm 缺 data URI byte/inputStream 分支 |

### 3.2 HTTP 客户端

| 维度 | Legado | MoRealm | 评估 |
|---|---:|---:|---|
| OkHttp | ✓ | ✓ | 持平 |
| SSL 策略 | 信任所有/unsafe | 标准验证 | Legado 兼容性更强，MoRealm 更安全 |
| Cronet / HTTP3 / QUIC | ✓ | ✗ | MoRealm 缺 |
| DecompressInterceptor | ✓ | ✗ | MoRealm 缺显式 gzip/deflate 解压拦截器 |
| Proxy HTTP/SOCKS | ✓ | ✗ | MoRealm 缺 |
| 异常拦截 | 专门 `OkHttpExceptionInterceptor` | 基础异常日志包装 | MoRealm 不是完全没有，但不等价 |
| Cookie 网络拦截 | ✓ | 简化 | MoRealm Cookie 行为未严格受 `enabledCookieJar` 控制 |

### 3.3 Cookie 管理

| 维度 | Legado | MoRealm | 评估 |
|---|---:|---:|---|
| 持久化 Cookie | ✓ | ✓ | 持平 |
| Session Cookie 分离 | ✓ | ✗ | MoRealm 缺 |
| Cookie 截断 >4096 | ✓ | ✗ | MoRealm 缺 |
| WebView Cookie 同步 | ✓ | ✗ | MoRealm 缺 |
| 按源启用 CookieJar | ✓ | 字段有，但网络层未完整接入 | 不能视为持平 |

---

## 4. JS 桥接与扩展方法

### 4.1 架构差异

```text
Legado:  AnalyzeRule implements JsExtensions
         BaseSource implements JsExtensions
         JsExtensions extends JsEncodeUtils

MoRealm: AnalyzeRule 创建 bindings，将 java 绑定到独立 object JsExtensions
```

Legado 的 JS 能力来源不只 `JsExtensions.kt`，还包括 `JsEncodeUtils.kt`。因此原文只列少量加密方法会低估缺口。

### 4.2 方法兼容应按语义分级

| 类型 | 例子 | MoRealm 状态 |
|---|---|---|
| 方法名和基础语义都有 | `ajax`、`connect`、`base64Encode`、`md5Encode` 等 | 基本可用 |
| 方法名存在但弱实现/空实现 | `getZipStringContent`、`getRarStringContent`、`get7zStringContent`、`openUrl`、`getReadBookConfig`、`getThemeConfig` | 不能算功能兼容 |
| Legado 有，MoRealm 缺 | `createAsymmetricCrypto`、`createSign`、`digestBase64Str`、`HMacHex`、`HMacBase64`、大量 AES/DES/3DES 便捷函数 | 需要补 |

建议不要写“补 5 个 JS 加密方法”，而应写：

> 对齐 Legado `JsEncodeUtils` 的常用加密/摘要/HMAC/签名方法，优先补实际书源高频方法。

---

## 5. 搜索 / 详情 / 目录 / 正文流程

### 5.1 搜索流程

| 步骤 | Legado | MoRealm 修复前 | 当前修复方向 |
|---|---:|---:|---|
| URL 构建 | AnalyzeUrl | AnalyzeUrl | 保持 |
| 旧占位符兼容 | 导入旧源时转换 | 不足 | 运行时兼容 `searchKey` / `searchPage` |
| POST body object | ✓ | ✗ | 支持 JsonElement body |
| loginCheck 成功响应 | ✓ | ✓ | 持平 |
| loginCheck 错误响应 | ✓ | ✗ | 待补 |
| loginCheck 恢复后重试/返回 | ✓ | 弱 | 待补 |
| 重定向日志 | ✓ | 空实现 | 待补 |

### 5.2 目录流程

| 功能 | Legado | MoRealm | 修正说明 |
|---|---:|---:|---|
| `preUpdateJs` | ✓ | ✓/弱 | MoRealm 已执行，但缺 `reGetBook()` / `refreshTocUrl()` 和完整 book 上下文 |
| `formatJs` | ✓ | ✓/弱 | MoRealm 已执行，但缺 `chapter` binding |
| `isVolume` | ✓ | ✓ | 原文误写为不支持 |
| 多目录页串行 next | ✓ | ✓ | 基本持平 |
| 多目录页并发分支 | ✓ | ✗ | MoRealm 当前较弱 |

### 5.3 正文流程

| 功能 | Legado | MoRealm | 评估 |
|---|---:|---:|---|
| 基础正文规则 `content` | ✓ | ✓ | 持平 |
| `nextContentUrl` 单链分页 | ✓ | ✓ | 持平 |
| 多 URL 并发分页 | ✓ | ✗ | 性能/兼容缺口 |
| `ContentRule.webJs` 请求渲染 | ✓ | 字段有但未接入 | 重要缺口 |
| `sourceRegex` | ✓ | 字段有但未接入正文请求 | 重要缺口 |
| `subContent` | ✓ | ✗ | 重要缺口 |
| `ContentRule.title` | ✓ | 字段有但未见执行 | 缺口 |
| `replaceRegex` 全文替换 | ✓ | ✓ | 基本持平 |
| `imageDecode` / `imageStyle` | ✓ | 字段有但语义不足 | 图片/漫画源缺口 |
| `payAction` | ✓ | 字段有但语义不足 | 付费章节操作缺口 |

---

## 6. 书源管理与生态

| 功能 | Legado | MoRealm | 评估 |
|---|---:|---:|---|
| JSON 导入 | ✓ | ✓ | 本次增强 Legado option 兼容 |
| URL 订阅导入 | ✓ | ✓ | MoRealm 有手动 URL 导入 |
| 文件导入 | ✓ | ✓ | 持平 |
| 内置书源 | ✓ | ✗ | MoRealm 暂无 assets 默认源 |
| 书源订阅自动更新 RuleSub | ✓ | ✗ | 缺 |
| 二维码分享 | ✓ | ✗ | 缺 |
| 直链分享 | ✓ | ✗ | 缺 |
| 分步调试 | ✓ | ✓ | MoRealm 有 `SourceDebug`，能力待扩展 |
| 浏览器反爬验证 | ✓ | ✗ | 缺 `SourceVerificationHelp` 等价能力 |
| 事件响应调试 | ✓ | ✗ | 缺 `eventListener` / `callBackJs` |

---

## 7. RSS 系统

Legado 有完整 RSS 子系统，MoRealm 当前基本没有：

- `RssSource.kt`
- `RssArticle.kt`
- `RssFavorite.kt`
- `RssSourceExtensions.kt`
- `RssStar.kt`
- `RssParserByRule.kt`

RSS 对小说基础搜索不是 P0，但属于 Legado 生态兼容差异。

---

## 8. 缺失总结与影响评估

### 8.1 P0/P1：直接影响“导入后能不能搜索/解析”的缺口

| # | 缺失/问题 | 当前状态 | 影响 |
|---|---|---|---|
| 1 | URL option `body` object/array 不兼容 | **本次修复** | POST 搜索源大量失败 |
| 2 | 旧占位符 `searchKey` / `searchPage` 不兼容 | **本次修复** | 导入旧/默认 Legado 源后搜索请求参数错误 |
| 3 | `jsLib` 导入丢失 | **本次修复导入字段** | 依赖共享 JS 库的书源失败 |
| 4 | `enabledCookieJar` 导入丢失 | **本次修复导入字段** | Cookie 语义进一步对齐的前置条件 |
| 5 | `enabledCookieJar` 未控制网络层 Cookie 行为 | 待修 | Cookie 被无条件读写，和 Legado 语义不一致 |
| 6 | `loginCheckJs` 不处理错误响应 | 待修 | 登录恢复能力弱 |
| 7 | `ContentRule.webJs` / `sourceRegex` 未接入正文请求 | 待修 | 动态正文源/反爬源失效 |
| 8 | `ContentRule.subContent` 缺失 | 待修 | 音频歌词/视频弹幕/副文不兼容 |
| 9 | `ContentRule.callBackJs` + `eventListener` 缺失 | 待修 | 事件驱动源不可用 |
| 10 | `ruleReview` 不能持久化 | 待修 | 段评规则实际不可用 |

### 8.2 P2：功能完整性缺口

| # | 缺失/问题 | 影响 |
|---|---|---|
| 11 | `preUpdateJs` 缺 `reGetBook()` / `refreshTocUrl()` | 部分目录 URL 动态更新源失效 |
| 12 | `formatJs` 缺 `chapter` binding | 依赖副作用改章节对象的规则不兼容 |
| 13 | 变量链短 | 章节/书籍/书源变量共享能力弱 |
| 14 | 规则级 `<webJs>` 缺失 | 规则链中混合 WebView DOM 解析失败 |
| 15 | `AnalyzeUrl` 缺 HEAD / bodyJs / dnsIp / proxy / serverID / data URI | 特殊站点兼容差 |
| 16 | JS 加密/签名/压缩包读取能力不足 | 加密源、压缩包源不兼容 |
| 17 | JS 缓存无上限 | 长期运行内存风险 |

### 8.3 P3/P4：生态和体验缺口

| # | 缺失/问题 | 影响 |
|---|---|---|
| 18 | 正文/目录多 URL 并发 | 性能弱 |
| 19 | `exploreScreen` | 探索筛选缺失 |
| 20 | 书源订阅自动更新 | 维护不便 |
| 21 | 二维码/直链分享 | 生态分享能力弱 |
| 22 | RSS 系统 | 内容源生态缺失 |
| 23 | 视频源完整支持 | `bookSourceType=4` 生态不兼容 |

---

## 9. MoRealm 优势（修订）

| 优势项 | 修订后说明 |
|---|---|
| `kotlinx.serialization` | 规则实体序列化更现代，但整体引擎仍依赖 Android/Room/WebView/Rhino |
| Compose + Material 3 | UI 技术栈更现代 |
| Coroutine 风格 | MoRealm 整体更偏 coroutine，但 JS 同步桥接仍有 `runBlocking`，不能夸大 |
| 全局 JS 编译缓存 | 有跨源复用收益，但必须加容量上限后才适合作为优势 |

删除/待核实：`CocktailBlocker`。当前源码未找到该实现。

---

## 10. 修订后的优先级建议

### P0：先让导入后的书源能正常搜索

| 项目 | 状态 | 理由 |
|---|---|---|
| 兼容 URL option `body` object/array/string | 已开始修复 | POST 搜索大量依赖 |
| 兼容 `searchKey` / `searchPage` | 已开始修复 | 旧/默认 Legado 源常见 |
| 导入 `jsLib` / `enabledCookieJar` | 已开始修复 | 避免导入即丢关键能力 |
| 修复 `ruleReview` TypeConverter | 待做 | 避免字段存在但永远 null |

### P1：核心解析链路语义对齐

| 项目 | 理由 |
|---|---|
| `loginCheckJs` 错误响应处理 | 登录源恢复能力 |
| `ContentRule.webJs` / `sourceRegex` 接入正文请求 | 动态正文源兼容 |
| `preUpdateJs` 完整上下文 + `reGetBook()` / `refreshTocUrl()` | 动态目录源兼容 |
| `formatJs` 增加 `chapter` binding | 标题格式化兼容 |
| 变量链补齐 chapter → book → ruleData → source | 复杂规则兼容 |

### P2：JS 和 URL 高级能力

| 项目 | 理由 |
|---|---|
| 对齐常用 `JsEncodeUtils` 加密/签名/HMAC 方法 | 加密源兼容 |
| 空实现方法补真实实现或显式标记不支持 | 防止“方法存在但功能缺失”误判 |
| `AnalyzeUrl` 补 HEAD / bodyJs / dnsIp / proxy / serverID / data URI | 特殊站点兼容 |
| JS 编译缓存改 LRU | 内存安全 |

### P3/P4：生态增强

| 项目 | 理由 |
|---|---|
| 多页并发解析 | 性能 |
| `exploreScreen` | 探索 UX |
| `eventListener` / `customButton` | Legado 高级交互源 |
| 书源订阅自动更新 | 维护便利 |
| RSS / 视频源完整支持 | 生态完整性 |
