# Legado vs MoRealm 架构深度对比

> 分析日期：2026-04-29
> 范围：除阅读器外的核心架构、数据持久化、后台服务

---

## 执行摘要

MoRealm 在 **UI/UX 和阅读体验** 上领先 Legado，但在 **数据持久化、后台服务、业务逻辑完整性** 上落后 **2-3 个月**。

### 核心差距

| 维度 | Legado | MoRealm | 差距 |
|------|--------|---------|------|
| **数据库版本** | 89 | 15 | 74 个版本 |
| **数据表数量** | 21 | 14 | 缺 7 个表 |
| **后台服务** | 12 个 | 3 个 | 缺 9 个服务 |
| **业务逻辑模块** | 15+ | 6 | 缺 9 个模块 |
| **搜索历史持久化** | ✅ | ❌ | **P1** |
| **自动检查新章节** | ✅ | ❌ | **P1** |
| **Web 服务** | ✅ | ❌ | P5 |
| **RSS 阅读器** | ✅ | ❌ | P6 |

---

## 一、数据库架构对比

### Legado 数据库（版本 89）

```
21 个表的完整生态：

核心阅读
├─ books (书籍)
├─ book_chapters (章节)
├─ book_sources (书源)
├─ book_groups (分组)
└─ bookmarks (书签)

搜索与发现
├─ search_keywords (搜索历史) ⭐
├─ searchBooks (搜索结果缓存) ⭐
└─ readRecord (阅读统计) ⭐

内容处理
├─ replace_rules (替换规则)
├─ txtTocRules (TXT 目录规则)
└─ cache (缓存)

TTS 与音频
├─ httpTTS (HTTP TTS 源)
└─ cookies (Cookie 管理)

高级功能
├─ rssSources (RSS 源) ⭐
├─ rssArticles (RSS 文章) ⭐
├─ rssReadRecords (RSS 阅读记录)
├─ rssStars (RSS 收藏)
├─ ruleSubs (书源订阅) ⭐
├─ dictRules (词典规则) ⭐
├─ keyboardAssists (键盘辅助)
└─ servers (Web 服务配置)
```

**关键特性**：
- 89 次迭代，每个版本都有明确的迁移策略
- 使用 AutoMigration 简化升级
- 自定义迁移处理复杂的数据转换
- 完整的数据完整性约束（ForeignKey）

### MoRealm 数据库（版本 15）

```
14 个表的基础生态：

核心阅读
├─ books (书籍)
├─ book_chapters (章节)
├─ book_sources (书源)
├─ book_groups (分组)
└─ bookmarks (书签)

阅读进度与统计
├─ read_progress (阅读进度)
├─ read_stats (阅读统计)
└─ reader_styles (阅读样式)

内容处理
├─ replace_rules (替换规则)
├─ txtTocRules (TXT 目录规则)
└─ cache (缓存)

TTS 与音频
├─ httpTTS (HTTP TTS 源)
└─ cookies (Cookie 管理)

主题
└─ themes (主题)
```

**缺失的表**：
- ❌ search_keywords (搜索历史)
- ❌ searchBooks (搜索结果缓存)
- ❌ readRecord (阅读统计 — 有 read_stats 但功能不同)
- ❌ rssSources (RSS 源)
- ❌ rssArticles (RSS 文章)
- ❌ ruleSubs (书源订阅)
- ❌ dictRules (词典规则)

---

## 二、搜索功能架构对比

### Legado 搜索架构

```
SearchActivity (UI)
    ↓
SearchViewModel
    ├─ searchRepo.searchLocalBooks(keyword)
    ├─ searchRepo.searchOnlineSource(source, keyword)
    ├─ searchRepo.saveSearchKeyword(keyword)  ⭐ 持久化历史
    └─ searchRepo.saveSearchBook(searchBook)  ⭐ 缓存结果
    ↓
SearchRepository
    ├─ SearchKeywordDao.insert(keyword)
    ├─ SearchBookDao.insert(searchBook)
    └─ BookSourceDao.getEnabledSources()
    ↓
SQLite
    ├─ search_keywords 表
    │  ├─ word (PK, unique)
    │  ├─ usage (使用次数)
    │  └─ lastUseTime (最后使用时间)
    │
    └─ searchBooks 表
       ├─ bookUrl (PK, unique)
       ├─ origin (FK → book_sources)
       ├─ name, author, kind
       ├─ coverUrl, intro
       ├─ latestChapterTitle
       ├─ time (搜索时间)
       └─ respondTime (响应时间)
```

**关键特性**：
- 搜索历史自动保存，用户重启后可恢复
- 搜索结果缓存，避免重复搜索
- 按 usage 和 lastUseTime 排序，智能推荐
- 搜索结果与书源关联（ForeignKey）

### MoRealm 搜索架构

```
SearchScreen (UI)
    ↓
SearchViewModel
    ├─ searchRepo.searchLocalBooks(keyword)
    ├─ searchRepo.searchOnlineSource(source, keyword)
    └─ ❌ 无持久化逻辑
    ↓
SearchRepository
    ├─ 多源并行搜索
    ├─ 结果合并
    └─ ❌ 无 DAO 调用
    ↓
内存 (StateFlow)
    ├─ _results: MutableStateFlow<List<SearchResult>>
    ├─ _localResults: MutableStateFlow<List<Book>>
    └─ _sourceProgress: MutableStateFlow<List<SourceSearchProgress>>

❌ SQLite
    ├─ 无 search_keywords 表
    └─ 无 searchBooks 表
```

**问题**：
- 搜索历史仅在内存中，应用重启后丢失
- 搜索结果不缓存，重复搜索需重新请求
- 无法统计用户搜索行为
- 无法实现"搜索范围过滤"（需要历史数据）

---

## 三、后台服务架构对比

### Legado 后台服务生态（12 个服务）

```
BaseService (基类)
    ├─ CheckSourceService ⭐
    │  ├─ 定期校验书源有效性
    │  ├─ 并行检查多个书源
    │  ├─ 通知栏进度显示
    │  └─ 失败原因标记（js失效、网站失效等）
    │
    ├─ DownloadService
    │  ├─ 后台下载书籍
    │  ├─ 断点续传
    │  └─ 通知栏进度
    │
    ├─ CacheBookService
    │  ├─ 后台缓存书籍
    │  └─ 智能缓存策略
    │
    ├─ TTSReadAloudService
    │  ├─ 系统 TTS 朗读
    │  ├─ 后台播放
    │  └─ 通知栏控制
    │
    ├─ HttpReadAloudService
    │  ├─ HTTP TTS 朗读
    │  └─ 音频流处理
    │
    ├─ AudioPlayService
    │  ├─ 音频播放
    │  └─ 播放列表管理
    │
    ├─ ExportBookService
    │  ├─ 后台导出书籍
    │  └─ 多格式支持
    │
    ├─ WebService ⭐
    │  ├─ 内置 HTTP 服务器
    │  ���─ Web 阅读器
    │  └─ 远程 API
    │
    ├─ WebTileService
    │  ├─ 快捷方式 Tile
    │  └─ 快速启动
    │
    └─ ...
```

**架构特点**：
- 继承 BaseService，统一生命周期管理
- 使用 NotificationCompat 显示进度
- 支持前台服务（Android 8.0+）
- 事件总线通知 UI 更新

### MoRealm 后台服务（3 个服务）

```
BaseService (基类)
    ├─ TtsService
    │  ├─ TTS 朗读
    │  └─ Media3 通知栏
    │
    ├─ DownloadService
    │  └─ 后台下载
    │
    └─ ❌ 无 CheckSourceService
    └─ ❌ 无 WebService
    └─ ❌ 无后台定时任务框架
```

**缺失**：
- ❌ CheckSourceService（书源校验）
- ❌ CacheBookService（缓存管理）
- ❌ ExportBookService（导出）
- ❌ WebService（Web 服务器）
- ❌ WorkManager 集成（定时任务）

---

## 四、业务逻辑层对比

### Legado model/ 目录（15+ 个模块）

```
model/
├─ CheckSource.kt ⭐
│  ├─ 书源校验配置
│  ├─ 校验项选择（搜索、发现、正文等）
│  ├─ 超时设置
│  └─ 启动 CheckSourceService
│
├─ ReadBook.kt
│  ├─ 阅读逻辑
│  ├─ 章节加载
│  └─ 进度保存
│
├─ ReadAloud.kt
│  ├─ 朗读逻辑
│  ├─ TTS 管理
│  └─ 定时停止
│
├─ Download.kt
│  ├─ 下载逻辑
│  └─ 下载队列
│
├─ CacheBook.kt
│  ├─ 缓存逻辑
│  └─ 缓存策略
│
├─ RuleUpdate.kt ⭐
│  ├─ 书源更新
│  ├─ 自动检查新版本
│  └─ 增量更新
│
├─ Debug.kt
│  ├─ 调试信息
│  └─ 日志记录
│
├─ analyzeRule/ (规则引擎)
│  ├─ AnalyzeRule.kt
│  ├─ AnalyzeUrl.kt
│  ├─ AnalyzeByRegex.kt
│  ├─ AnalyzeByXPath.kt
│  ├─ AnalyzeByJSoup.kt
│  └─ AnalyzeByJSonPath.kt
│
├─ webBook/ (网络书籍)
│  ├─ WebBook.kt
│  ├─ BookList.kt
│  ├─ BookContent.kt
│  └─ BookChapterList.kt
│
├─ localBook/ (本地书籍)
│  ├─ LocalBook.kt
│  ├─ TxtBook.kt
│  └─ EpubBook.kt
│
├─ rss/ (RSS 处理) ⭐
│  ├─ Rss.kt
│  ├─ RssSource.kt
│  └─ RssArticle.kt
│
└─ remote/ (远程 API)
   ├─ RemoteBook.kt
   └─ RemoteApi.kt
```

### MoRealm presentation/ 目录（6 个模块）

```
presentation/
├─ reader/
│  ├─ ReaderViewModel.kt
│  ├─ ReaderChapterController.kt
│  ├─ ReaderTtsController.kt
│  └─ ReaderSearchController.kt
│
├─ search/
│  └─ SearchViewModel.kt
│
├─ shelf/
│  └─ ShelfViewModel.kt
│
├─ profile/
│  ├─ ProfileViewModel.kt
│  ├─ BookDetailViewModel.kt
│  └─ ListenViewModel.kt
│
├─ settings/
│  ├─ ReadingSettingsViewModel.kt
│  ├─ ReplaceRuleViewModel.kt
│  └─ ...
│
└─ cache/
   └─ CacheBookViewModel.kt
```

**缺失的模块**：
- ❌ CheckSource（书源校验）
- ❌ RuleUpdate（书源更新）
- ❌ RSS 处理
- ❌ 远程 API
- ❌ 本地书籍处理（仅有基础支持）

---

## 五、关键功能对比

### 1. ��索历史（P1）

**Legado**：
```kotlin
// 自动保存搜索历史
searchRepo.saveSearchKeyword(keyword)

// 查询历史
val history = searchKeywordDao.getAll()
    .sortedByDescending { it.lastUseTime }

// 数据结构
data class SearchKeyword(
    @PrimaryKey var word: String = "",
    var usage: Int = 1,
    var lastUseTime: Long = System.currentTimeMillis()
)
```

**MoRealm**：
```kotlin
// ❌ 无持久化
// 搜索结果仅在内存中
_results.value = emptyList()

// 应用重启后丢失
```

**工作量**：1-2 小时

---

### 2. 自动检查新章节（P1）

**Legado**：
```kotlin
// CheckSourceService 定期运行
// 检查书架中所有书籍的新章节
// 发现新章节时推送通知

object CheckSource {
    var timeout = 180000L  // 3 分钟超时
    var checkSearch = true
    var checkDiscovery = true
    var checkInfo = true
    var checkContent = true

    fun start(context: Context, sources: List<BookSourcePart>) {
        context.startService<CheckSourceService> {
            action = IntentAction.start
        }
    }
}
```

**MoRealm**：
```kotlin
// ❌ 无此功能
// 无后台定时任务框架
// 无新章节检查逻辑
```

**工作量**：2-3 天（需要 WorkManager 集成）

---

### 3. Web 服务（P5）

**Legado**：
```kotlin
// WebService 提供：
// 1. 内置 HTTP 服务器
// 2. Web 阅读器（浏览器访问）
// 3. 远程 API：
//    - GET /book/list (书籍列表)
//    - GET /book/{id} (书籍详情)
//    - POST /book/add (添加书籍)
//    - GET /source/list (书源列表)
//    - POST /source/add (添加书源)
//    - 等等
```

**MoRealm**：
```kotlin
// ❌ 完全缺失
```

**工作量**：5-7 天

---

### 4. RSS 阅读器（P6）

**Legado**：
```kotlin
// RssSource 表
data class RssSource(
    @PrimaryKey var sourceUrl: String = "",
    var sourceName: String = "",
    var sourceIcon: String? = null,
    var sourceGroup: String? = null,
    var enabled: Boolean = true,
    // ... 更多字段
)

// RssArticle 表
data class RssArticle(
    @PrimaryKey var link: String = "",
    var title: String = "",
    var pubDate: Long = 0L,
    var description: String? = null,
    var content: String? = null,
    var image: String? = null,
    var sourceUrl: String = "",
    // ... 更多字段
)
```

**MoRealm**：
```kotlin
// ❌ 完全缺失
```

**工作量**：3-5 天

---

## 六、数据库迁移策略对比

### Legado 迁移策略

```kotlin
@Database(
    version = 89,
    autoMigrations = [
        AutoMigration(from = 43, to = 44),
        AutoMigration(from = 44, to = 45),
        // ... 45 个自动迁移
        AutoMigration(from = 54, to = 55, spec = Migration_54_55::class),
        // ... 自定义迁移处理复杂逻辑
    ]
)
```

**特点**：
- 使用 AutoMigration 简化大部分升级
- 复杂迁移使用自定义 spec
- 每个版本都有明确的升级路径
- 支持向后兼容

### MoRealm 迁移策略

```kotlin
@Database(
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    // 无迁移定义
    // 依赖 fallbackToDestructiveMigrationFrom()
}
```

**问题**：
- 无迁移定义，新表添加时需要手动处理
- 可能导致数据丢失
- 升级路径不清晰

---

## 七、架构演进建议

### 第一阶段（v1.0 → v1.0.1）— 搜索完整性

**目标**：补齐搜索功能的数据持久化

```
数据库升级：v15 → v16
├─ 添加 SearchKeyword 表
├─ 添加 SearchKeywordDao
└─ 编写迁移脚本

SearchViewModel 改造：
├─ 搜索时自动保存关键词
├─ 显示搜索历史列表
└─ 支持历史项删除

SearchScreen 改造：
├─ 搜索框下方显示历史
├─ 点击历史项快��搜索
└─ 长按删除历史

工作量：1-2 天
```

### 第二阶段（v1.0.1 → v1.1）— 后台服务

**目标**：实现自动检查新章节

```
集成 WorkManager：
├─ 添加依赖
├─ 创建 CheckNewChaptersWorker
└─ 注册定时任务

CheckNewChaptersWorker 实现：
├─ 遍历书架所有书籍
├─ 调用 WebBook.getChapterListAwait()
├─ 比较章节数，发现新章节
└─ 推送通知

用户设置界面：
├─ 启用/禁用自动检查
├─ 检查间隔选择（1h/6h/12h/24h）
├─ 仅 WiFi 下检查
└─ 检查时间范围

工作量：2-3 天
```

### 第三阶段（v1.1 → v1.2）— 高级功能

**目标**：Web 服务、RSS 阅读器

```
Web 服务（5-7 天）：
├─ HTTP 服务器框架（Ktor）
├─ Web UI 前端
├─ API 端点设计
└─ 认证机制

RSS 阅读器（3-5 天）：
├─ 数据库表设计
├─ RSS 解析库集成
├─ 定时更新逻辑
└─ 阅读 UI

工作量：8-12 天
```

---

## 八、关键洞察

### MoRealm 的优势

1. **现代 UI 框架**
   - Jetpack Compose + Material 3
   - 响应式布局
   - 动态主题色
   - Legado 无法比拟

2. **性能优化**
   - Canvas 录制缓存
   - Alpha 羽化（滚动模式）
   - 流畅的翻页体验

3. **内置功能**
   - Edge TTS（��需配置）
   - 正则安全保护
   - 自动分组
   - 文件夹导入

### Legado 的优势

1. **功能完整性**
   - 21 个数据表 vs 14 个
   - 12 个后台服务 vs 3 个
   - 15+ 个业务逻辑模块 vs 6 个

2. **用户数据管理**
   - 搜索历史持久化
   - 阅读统计
   - RSS 订阅
   - 词典规则

3. **后台能力**
   - 自动检查新章节
   - 书源校验
   - Web 服务
   - 远程 API

### 缩小差距的策略

**不要盲目复制 Legado**，而是：

1. **优先补齐 P1 功能**（搜索历史、自动检查新章节）
   - 这两个功能对用户体验影响最大
   - 工作量相对较小（3-5 天）

2. **保持 UI/UX 优势**
   - 不要为了功能完整性而牺牲 UI
   - Compose + M3 是 MoRealm 的核心竞争力

3. **选择性实现高级功能**
   - Web 服务和 RSS 可以后置
   - 专注于核心阅读体验

4. **建立清晰的迁移路径**
   - 每个版本都有明确的升级策略
   - 避免数据丢失

---

## 九、总结

### 当前状态

MoRealm 已建立 **完整的阅读体验框架**，但在 **用户数据管理和后台服务** 方面与 Legado 仍有 **2-3 个月** 的差距。

### 建议行动

1. **立即启动 P1**（搜索历史 + 自动检查新章节）
   - 预计 3-5 天完成
   - 显著提升用户体验

2. **并行 P2**（备份加密、选择性恢复）
   - 预计 2-3 天完成
   - 提升数据安全性

3. **评估 P5**（Web 服务）
   - 决定是否纳入 v1.1 或后置
   - 需要 5-7 天

### 竞争优势保留

- Compose + Material 3 UI（Legado 无）
- Canvas 录制缓存（Legado 无）
- Edge TTS 内置（Legado 无）
- 正则安全保护（Legado 无）

这些优势足以在 **UI/UX 和性能** 上超越 Legado，即使功能完整度暂时落后。
