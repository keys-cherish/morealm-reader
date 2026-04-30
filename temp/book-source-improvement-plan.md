# MoRealm 书源系统完善方案 & 闪烁漏洞分析

## 一、章节回翻闪烁漏洞分析

### 1.1 问题现象

在MoRealm阅读器中，当用户处于**新章节开头，向回翻页到上一章最后一页**时，会短暂闪过上一章的**第一页**，然后才跳到最后一页。

### 1.2 Legado 对比：同步模型

Legado (`ReadBook.kt`) 使用**全局单例 + 直接变量赋值**的架构：

```kotlin
// Legado ReadBook.kt — 全局单例，状态直接可变
object ReadBook : CoroutineScope by MainScope() {
    var curTextChapter: TextChapter? = null
    var prevTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var durChapterIndex = 0

    fun loadContent(...) {
        // 1. 同步获取章节文本
        // 2. 同步或异步排版
        // 3. 排版完成后直接赋值 curTextChapter = newChapter
        // 4. ReadView 在 draw() 时直接读取这些变量
    }
}
```

**关键差异：**
- Legado 的 `ReadView.draw()` 每次绘制时直接读 `ReadBook.curTextChapter`，**没有框架层延迟**
- 章节切换时 `curTextChapter` 原子替换，阅读器立即渲染正确内容
- **不存在"先渲染placeholder再layout"的问题**

### 1.3 MoRealm 问题：异步 StateFlow 管道

MoRealm 的数据流：

```
loadChapter(prevIdx)             Compose重组                CanvasRenderer
    │                               │                          │
    ├─ IO协程                        │                          │
    │  ├─ prevChapterCache (内容)     │                          │
    │  ├─ _renderedChapter.value = X │                          │
    │  │  ──────────────────────────▶│                          │
    │  │                             ├─ chapterIndex 变化       │
    │  │                             ├─ content 变化            │
    │  │                             │  ├─ remember(chapterIdx) │
    │  │                             │  │  readerPageIndex = 0  │  ← 第一帧：第0页！
    │  │                             │  │                       │
    │  │                             │  ├─ remember(key)        │
    │  │                             │  │  textChapter = ?      │
    │  │                             │  │                       │
    │  │                             │  └─ CanvasRenderer重组   │
    │  │                             │     → 渲染 page 0        │  ← 用户看到闪烁
    │  │                             │                          │
    │  │                             ├─ LaunchedEffect(key)     │
    │  │                             │  prelayoutCache命中?     │
    │  │                             │  textChapter = cached    │
    │  │                             │  pageCount = correct     │  ← 第二帧：正确内容
    │  │                             │                          │
    │  │                             ├─ LaunchedEffect(progress)│
    │  │                             │  scrollToPage(lastPage)  │  ← 第三帧：跳到末页
    └──┴─────────────────────────────┴──────────────────────────┘
```

### 1.4 根本原因（三层）

**第一层：remember(chapterIndex) 与 LaunchedEffect 的帧差**

`remember(chapterIndex)` 在 Compose 重组时**同步**重置状态，但 `LaunchedEffect(currentChapterKey)` 在**下一帧**才执行。这中间至少有一帧的 gap。

```kotlin
// CanvasRenderer.kt:426 — chapterIndex变化时同步重置为0
var readerPageIndex by remember(chapterIndex) {
    val initialPage = if (startFromLastPage) {
        val cached = prelayoutCache[currentChapterKey]  // ← 此时currentChapterKey已变
        (cached?.pageSize?.minus(1))?.coerceAtLeast(0) ?: 0  // ← 但cache中可能无此key
    } else 0
    mutableIntStateOf(initialPage)
}
```

**问题：** `currentChapterKey` 的计算依赖 `content`（新章节内容），但 `prelayoutCache` 中的 key 是用旧的 `prevChapterContent` 算的。虽然两个内容文本相同（都是prevCached），但 `remember(currentChapterKey)` 和 `remember(chapterIndex)` **在同一个重组帧中计算**，`currentChapterKey` 是新的，prelayoutCache 可能有也可能没有匹配项。

**第二层：LaunchedEffect 先设 placeholder 再异步 layout**

```kotlin
// CanvasRenderer.kt:372 — LaunchedEffect 中
LaunchedEffect(currentChapterKey, layoutInputs) {
    val cachedChapter = prelayoutCache[currentChapterKey]
    if (cachedChapter != null) {
        textChapter = cachedChapter     // 缓存命中 → 立即正确
        return@LaunchedEffect
    }
    textChapter = placeholderChapter()  // 缓存未命中 → 先设placeholder（1页）
    pageCount = 1
    // 然后异步layout...               // 下一帧才出结果
}
```

**问题：** prelayoutCache 的内容由 `LaunchedEffect(nextChapterContent, prevChapterContent, ...)` 填充。当 `_prevPreloadedChapter` 被清空后，`prevChapterContent` 变成空字符串，prelayout 的输入参数变了，缓存可能失效。

**第三层：ReaderChapterController 过早清除旧数据**

```kotlin
// ReaderChapterController.kt:362-364 — 在IO协程中清除prevPreloadedChapter
prevCached != null && index == prevIndex - 1 -> {
    prevChapterCache = null
    _prevPreloadedChapter.value = null  // ← 在_renderedChapter发布前清除
    prevCached
}
```

虽然第二次修复已经将清除延迟到 `_renderedChapter` 发布之后，但 CanvasRenderer 仍面临前两层问题。

### 1.5 修复方案

**方案A：同步布局在 remember 中（推荐）**

在 `remember(currentChapterKey)` 中，如果 prelayoutCache 未命中，则**同步**执行 `layoutChapter()`（不经过 LaunchedEffect），确保 `textChapter` 始终有正确的初始值：

```kotlin
var textChapter by remember(currentChapterKey) {
    val cached = prelayoutCache[currentChapterKey]
    if (cached != null) {
        mutableStateOf<TextChapter?>(cached)
    } else if (content.isNotBlank()) {
        // 同步布局，避免placeholder帧
        val chapter = layoutInputs.provider.layoutChapter(
            title = chapterTitle, content = content,
            chapterIndex = chapterIndex, chaptersSize = chaptersSize
        )
        prelayoutCache[currentChapterKey] = chapter
        mutableStateOf<TextChapter?>(chapter)
    } else {
        mutableStateOf<TextChapter?>(placeholderChapter())
    }
}
```

**方案B：使用 remember 的 key 解耦**

将 `textChapter` 的初始化与 `pageCount` 分开管理，让 `readerPageIndex` 的初始值基于传入的 `initialProgress` 而非 prelayoutCache：

```kotlin
var readerPageIndex by remember(chapterIndex, startFromLastPage, initialChapterPosition) {
    mutableIntStateOf(
        when {
            startFromLastPage -> Int.MAX_VALUE  // 哨兵值，LaunchedEffect中修正
            initialChapterPosition > 0 -> initialChapterPosition
            else -> 0
        }
    )
}
```

### 1.6 闪烁修复的第一步实施

结合三种方案，最直接的修复是在 `remember` 初始化时**同步执行布局**，消除 placeholder 帧。具体改动点：

1. `CanvasRenderer.kt:336-339` — `remember(currentChapterKey)` 中增加同步布局路径
2. `CanvasRenderer.kt:372-382` — 移除 `LaunchedEffect` 中的 `placeholderChapter()` 回退
3. `CanvasRenderer.kt:426-432` — `readerPageIndex` 使用 `initialChapterPosition` 作为初始值

---

## 二、书源系统：MoRealm vs Legado 对比

### 2.1 实体层对比

| 功能 | Legado | MoRealm | 状态 |
|------|--------|---------|------|
| BookSource 基础字段 | 完整 | 完整 | ✅ |
| jsLib (共享JS库) | ✅ | ✅ (SharedJsScope) | ✅ |
| enabledCookieJar | ✅ (Boolean) | ✅ (Boolean?) | ✅ |
| concurrentRate | ✅ | ✅ | ✅ |
| header (@js:支持) | ✅ | ✅ | ✅ |
| loginUrl/loginUi/loginCheckJs | ✅ | ✅（已修） | ✅ |
| coverDecodeJs | ✅ | ✅ | ✅ |
| loginInfo (CacheManager) | ✅ | ✅ | ✅ |
| setVariable/getVariable | ✅ | ✅ | ✅ |
| eventListener/customButton | ✅ | ❌ | 缺失（非关键） |
| 分组管理 (addGroup/removeGroup) | ✅ | ❌ | 缺失 |
| exploreScreen (发现筛选) | ✅ | ❌ | 缺失 |

### 2.2 规则实体对比

**SearchRule:**
| 字段 | Legado | MoRealm |
|------|--------|---------|
| bookList | ✅ | ✅ |
| name/author/intro/kind | ✅ | ✅ |
| lastChapter/updateTime | ✅ | ✅ |
| bookUrl/coverUrl/wordCount | ✅ | ✅ |
| checkKeyWord | ✅ | ✅ |

**ContentRule:**
| 字段 | Legado | MoRealm |
|------|--------|---------|
| content | ✅ | ✅ |
| nextContentUrl | ✅ | ✅ |
| webJs | ✅ | ✅ |
| sourceRegex | ✅ | ✅ |
| replaceRegex | ✅ | ✅ |
| imageStyle | ✅ | ❌ **关键缺失** |
| imageDecode | ✅ | ❌ **关键缺失** |
| subContent | ✅ | ❌ |
| title | ✅ | ❌ |
| payAction | ✅ | ❌ |

**缺失关键字段分析：**
- `imageStyle`: 控制正文中图片的显示样式（居中/全宽等），缺失会导致图片显示异常
- `imageDecode`: 图片bytes二次解密JS，部分书源图片需要解密后才能显示

### 2.3 JS执行环境对比

| 绑定变量 | Legado AnalyzeRule | MoRealm AnalyzeRule | 状态 |
|----------|-------------------|---------------------|------|
| java (JsExtensions) | ✅ | ✅ | ✅ |
| source | ✅ | ✅ | ✅ |
| baseUrl | ✅ | ✅ | ✅ |
| result | ✅ | ✅ | ✅ |
| cookie | ✅ | ✅ | ✅ |
| cache | ✅ | ✅ | ✅ |
| book (RuleData) | ❌ | ✅ | MoRealm独有 |
| chapter | ❌ | ✅ | MoRealm独有 |
| title | ❌ | ✅ | MoRealm独有 |
| src/content | ✅(src) | ✅ | ✅ |
| nextChapterUrl | ✅ | ✅ | ✅ |
| **编译缓存 (CompiledScript)** | ✅ | ✅ | ✅ |
| **JS错误吞噬 (try-catch)** | ❌ (抛出) | ✅ (warn+null) | MoRealm更健壮 |
| **sharedScope (jsLib)** | ❌ | ✅ (SharedJsScope) | MoRealm独有 |

### 2.4 网络请求对比 (AnalyzeUrl)

| 功能 | Legado | MoRealm | 状态 |
|------|--------|---------|------|
| GET/POST | ✅ | ✅ | ✅ |
| 自定义Header | ✅ | ✅ | ✅ |
| Cookie管理 | ✅ (CookieStore) | ✅ (CookieStore) | ✅ |
| 并发限制 (ConcurrentRateLimiter) | ❌ | ✅ | MoRealm独有 |
| charset编码 (GBK等) | ✅ | ✅ | ✅ |
| URL选项 (method/headers/body/charset/webView) | ✅ | ✅ | ✅ |
| WebView渲染 (BackstageWebView) | ✅ | ✅ | ✅ |
| 图片下载 (getByteArray) | ✅ | ✅ | ✅ |
| startBrowser (备用请求) | ✅ | ✅ | ✅ |
| loginCheckJs (带错误恢复) | ✅ | ❌ 次健壮 | 可改进 |
| **ajax() JS方法** | ✅ (Legado: js扩展) | ✅ (AnalyzeUrl.ajax) | ✅ |

### 2.5 搜索流程对比

| 功能 | Legado | MoRealm | 状态 |
|------|--------|---------|------|
| 基础搜索 (searchBookAwait) | ✅ | ✅ | ✅ |
| 搜索过滤 | ✅ (filter回调) | ❌ | 缺失 |
| 分页搜索 | ✅ (<1,2,3> 语法) | ✅ | ✅ |
| 搜索超时 | ✅ (协程取消) | ✅ (协程取消) | ✅ |
| 多源并行搜索 | ✅ | ❌ | 缺失 |
| 搜索结果去重 | ✅ | ❌ | 缺失 |
| loginCheckJs错误恢复 | ✅ | ❌ | 可改进 |

### 2.6 发现/探索对比

| 功能 | Legado | MoRealm |
|------|--------|---------|
| exploreUrl | ✅ | ✅ |
| ruleExplore | ✅ (ExploreRule) | ✅ (ExploreRule) |
| exploreScreen (筛选) | ✅ | ❌ 缺失 |
| 发现分类 (ExploreKind) | ✅ | ❌ |

### 2.7 书源管理对比

| 功能 | Legado | MoRealm |
|------|--------|---------|
| 导入/导出 JSON | ✅ | ✅ |
| 启用/禁用 | ✅ | ✅ |
| 分组管理 | ✅ (多组) | ❌ (仅single group) |
| 测试搜索/详情/目录/正文 | ✅ | ❌ 缺失 |
| 登录状态展示 | ✅ | ✅ |

---

## 三、分步完善计划

### 第一步：ContentRule 补充（图片相关）

优先级：🔴 高 — 直接影响图片显示

1. 在 `ContentRule` 中添加 `imageStyle`、`imageDecode`、`subContent`、`title`、`payAction` 字段
2. 在 `ChapterProvider` 排版时使用 `imageStyle` 控制图片渲染样式
3. 在图片加载前执行 `imageDecode` JS 解密

### 第二步：修复章节回翻闪烁

优先级：🔴 高 — 用户体验核心问题

1. `CanvasRenderer.kt`: `remember(currentChapterKey)` 中增加同步布局路径
2. `CanvasRenderer.kt`: `readerPageIndex` 使用 `initialChapterPosition` 初始化
3. 移除 `LaunchedEffect` 中的 placeholder 回退逻辑

### 第三步：登录体系增强

优先级：🟡 中

1. `loginCheckJs` 增加错误恢复逻辑（参照 Legado getErrStrResponse）
2. 登录状态持久化展示

### 第四步：搜索增强

优先级：🟡 中

1. 多源并行搜索
2. 搜索结果去重
3. 搜索过滤回调

### 第五步：书源管理增强

优先级：🟢 低

1. 书源测试功能（测试搜索/目录/正文）
2. 多分组管理
3. 书源发现分类 (ExploreKind)

---

## 四、实施记录

| 步骤 | 内容 | 状态 |
|------|------|------|
| 1 | ContentRule 补充 imageStyle/imageDecode | ⏳ 待实施 |
| 2 | 修复章节回翻闪烁 | ⏳ 待实施 |
| 3 | 登录体系增强 | ⏳ 待实施 |
| 4 | 搜索增强 | ⏳ 待实施 |
| 5 | 书源管理增强 | ⏳ 待实施 |
