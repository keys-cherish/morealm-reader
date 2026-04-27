# MVVM 架构重构规划

## 为什么要重构

### 真实案例

**背景色 bug（4轮修复）：**
- 症状：所有翻页模式背景白色
- 根因：`activeStyle.bgColor`（白色）优先于主题色（米色），一行代码的优先级问题
- 状态传递链：`activeStyle → readerBg → backgroundColor → bgArgb → bgColor → Canvas`（7层）
- 困难：没有中间层日志，无法快速断定是哪一层传了白色
- 如果有日志：`[Theme] fallback=#FFFFFCF6 | [Style] override=#FFFFFFFF` → 一眼定位

**仿真翻页循环 bug（4轮修复）：**
- 症状：翻页永远在第1→2页循环
- 根因：Compose `pointerInput` 闭包值捕获陈旧
- 困难：`displayPage` 经过 `lastSettledDisplayPage → simulationParams.currentDisplayIndex (lambda) → displayPage → pointerInput closure → turnStartDisplayIndex` 5 层传递
- 如果有日志：`[PageTurn] displayPage=0 but lastSettled=1 ⚠️ DESYNC` → 一眼定位

### 当前架构问题量化

| 指标 | 当前值 | 目标值 |
|------|--------|--------|
| ReaderViewModel | 1,349行 / 12职责 | ~150行胶水 + 8个Controller各100-300行 |
| CanvasRenderer | 1,840行 / 13局部函数 | ~800行 + PageTurnCoordinator ~300行 |
| 状态传递最大层数 | 7层 | 3层（Controller → ViewModel → UI） |
| bug定位平均轮数 | 3-4轮 | 1轮（看日志前缀直接定位模块） |

---

## Part 1: 结构化日志系统

### 设计原则（借鉴 Legado + 补足其不足）

Legado 用 `AppLog.putDebug("action-state")` 自由格式。优点是简单，缺点是：
- 无模块前缀 → 大量日志中难以过滤
- 无结构化字段 → 无法程序化分析
- 无异常值检测 → 不会主动发现状态不一致

MoRealm 的日志系统在 Legado 基础上增加 **模块前缀 + 关键值对 + 异常值告警**：

### 日志格式

```
[模块名] 操作 | key1=value1 | key2=value2
```

### 模块前缀表

| 前缀 | 模块 | 关键日志点 |
|------|------|-----------|
| `[Chapter]` | ReaderChapterController | loadChapter, preload, cache hit/miss |
| `[Progress]` | ReaderProgressController | saveProgress, restorePosition |
| `[Nav]` | ReaderNavigationController | nextChapter, prevChapter, linkedBook |
| `[PageTurn]` | PageTurnCoordinator | fillPageFrom, animateByDirection, onPageSettled |
| `[Scroll]` | ScrollRenderer | boundary commit, cross-chapter |
| `[Simulation]` | SimulationReadView | drag start/end, animation commit |
| `[Theme]` | ReaderScreen 颜色解析 | readerBg, readerFg, activeStyle override |
| `[Search]` | ReaderSearchController | searchFullText, resultCount |
| `[TTS]` | ReaderTtsController | play/pause, chapterFinish |
| `[Settings]` | ReaderSettingsController | mode change, style switch |

### 只加能解决真实 bug 的日志

过去的 bug 证明这些日志点如果存在就能一轮定位：

| 真实 bug | 缺失的日志 | 应加的日志 |
|---------|-----------|----------|
| 背景色白色 | 不知道 readerBg 的实际 ARGB 值 | `[Theme] readerBg=#FFFFFCF6 source=theme/style` |
| 翻页循环 | 不知道 displayPage 和 lastSettled 是否一致 | `[PageTurn] fillPageFrom display=X lastSettled=Y` |
| dragState 卡死 | 不知道 dragState 没被重置 | `[Simulation] onDragStart resetState prev=DRAGGING_NEXT` |
| 进度丢失 | 不知道 saveProgress 用了哪个 chapterPosition | `[Progress] save chapter=4 position=321 scroll=2%` |

**原则：不加"以防万一"的日志。只加"如果没有这个日志，上次 bug 就多修了 2 轮"的日志。**

### AppLog 接口（已有，不需要改）

```kotlin
// 已有的 AppLog.debug(tag, message) 完全够用
// 只需统一 tag 命名规范为模块前缀即可
AppLog.debug("Chapter", "loadChapter | index=4/12 | cached=true")
AppLog.debug("PageTurn", "fillPageFrom | display=0 | lastSettled=1 | direction=NEXT")
```

不新增 `warn`/`info` 级别——Android 的 `Log.d` 配合模块前缀已经足够过滤。

---

## Part 2: PageTurnCoordinator 提取

### 从 CanvasRenderer 提取的内容

**状态（移入 PageTurnCoordinator）：**
- `lastSettledDisplayPage`
- `pendingSettledDirection`
- `pendingTurnStartDisplayPage`
- `ignoredSettledDisplayPage`
- `lastReaderContent`
- `pageDelegateState`

**函数（移入 PageTurnCoordinator，重命名为语义化）：**

| 当前名（Legado 风格） | 新名（语义化） | 职责 |
|---|---|---|
| `fillPageFrom()` | `commitPageTurn()` | 提交翻页，更新当前页索引 |
| `animateByDirection()` | `turnPageByTap()` | 点击触发的翻页 |
| `dragByDirection()` | `turnPageByDrag()` | 拖拽触发的翻页 |
| `fillScrollBoundaryPage()` | `commitScrollChapterBoundary()` | 滚动模式跨章提交 |
| `readerPageStateFor()` | `createPageState()` | 构建页面状态快照 |
| `upProgressFrom()` | `reportProgress()` | 向 ViewModel 报告进度 |
| `pageForDisplay()` | `getPageAt()` | 按显示索引获取页面 |
| `relativePageForDisplay()` | `getRelativePage()` | 获取相对位置页面 |
| `onPageSettled()` | `handlePagerSettled()` | Pager 动画完成处理 |

**CanvasRenderer 保留：**
- Paint/Layout 创建
- ChapterProvider 布局
- 页面缓存管理（prelayoutCache）
- Composable 组装（根据 pageAnimType 分发）
- Header/Footer overlay
- 文本选择

### 文件

- 新建：`renderer/PageTurnCoordinator.kt`（已创建，需接入）
- 修改：`renderer/CanvasRenderer.kt`（1840行 → ~800行）

### renderer/ 文件重命名计划

| 当前名 | 新名 | 理由 |
|--------|------|------|
| `PageAnimations.kt` | `PageAnimationPagers.kt` | 实际包含 5 种 Pager 实现 |
| `PageCanvas.kt` | `PageContentDrawer.kt` | 职责是绘制页面内容到 Canvas |
| `ReaderPageDelegateState.kt` | `PageTurnGateState.kt` | "Delegate"是 Legado 术语，"Gate"更直观 |
| `ReaderPageState.kt` | `PageTurnStateMachine.kt` | 职责是翻页状态机 |
| `ReaderDataSource.kt` | `ChapterDataSource.kt` | 提供章节数据，不是通用数据源 |
| `SimulationDrawHelper.kt` | 保留 | 名字够清晰 |
| `SimulationReadView.kt` | 保留 | 名字够清晰 |
| `ScrollRenderer.kt` | 保留 | 名字够清晰 |
| `CanvasRenderer.kt` | 保留 | 名字够清晰 |
| `TextSelection.kt` | 保留 | 名字够清晰 |

---

## Part 3: 动画模式隔离

### 隔离机制

```kotlin
val coordinator = remember(chapterIndex, pageAnimType) {
    PageTurnCoordinator(...)
}
```

切换翻页模式 → coordinator 整体重建 → 所有状态清零 → 无穿透

### 模式间防火墙

| 模式 | 手势处理 | 状态作用域 | 隔离级别 |
|------|---------|-----------|---------|
| SLIDE/COVER | Compose pointerInput | coordinator 内 | remember key |
| SCROLL | ScrollRenderer 内部 | ScrollRenderer state | 独立 Composable |
| SIMULATION | SimulationReadView.onTouchEvent | View 字段 | AndroidView 完全隔离 |
| NONE | Compose pointerInput | coordinator 内 | remember key |

### 优雅降级

仿真翻页 OOM 时自动降级到 SLIDE，不影响其他模式：

```kotlin
view.bitmapProvider = { relativePos, w, h ->
    try {
        renderPageToBitmap(...)
    } catch (e: OutOfMemoryError) {
        AppLog.error("Simulation", "bitmap OOM, degrading to SLIDE", e)
        onFallbackToSlide()
        null
    }
}
```

---

## Part 4: ReaderViewModel 拆分

### Controller 架构

```
ReaderViewModel (胶水层 ~150行)
├── ReaderChapterController     章节加载/缓存/预加载/Web书源    ~300行
├── ReaderProgressController    进度保存/恢复/统计              ~150行
├── ReaderNavigationController  翻章/关联书/方向                ~100行
├── ReaderSearchController      全文搜索/结果/选择              ~100行
├── ReaderBookmarkController    书签增删/跳转                   ~80行
├── ReaderContentEditController 内容编辑/EPUB缓存写入          ~60行
├── ReaderSettingsController    设置/样式                      (已有)
└── ReaderTtsController         TTS                           (已有)
```

### ViewModel 胶水层职责

仅处理**跨 Controller 协调**：
- 切换中文模式 → 清 chapter 缓存 + 重新加载
- 章节加载完成 → 通知 progress 恢复位置
- TTS 章节结束 → 通知 navigation 翻下一章

### Legado 架构对照

| Legado | 职责 | MoRealm 对应 |
|--------|------|-------------|
| `ReadBook` 单例 | 三面板 + 章节状态 | ReaderChapterController |
| `ReadBookViewModel` | UI 协调 | ReaderViewModel 胶水层 |
| `ContentProcessor` | 替换规则/净化 | ReaderChapterController 内 |
| `TextPageFactory` | 页面状态机 | ReaderPageFactory (已有) |
| `PageDelegate` 层级 | 动画生命周期 | PageTurnCoordinator |
| `ReadView` | 渲染 + 手势分发 | CanvasRenderer (瘦身后) |

---

## 执行顺序

| 步骤 | 任务 | 风险 | 依赖 |
|------|------|------|------|
| 1 | Part 1: 日志系统 | 低 | 无 |
| 2 | Part 2: PageTurnCoordinator 接入 | 中 | 步骤1（日志） |
| 3 | Part 3: 模式隔离 | 低 | 步骤2 |
| 4 | Part 4: ViewModel 拆分 | 高 | 步骤1（日志） |

每步完成后编译 + 运行验证，确保无回归。

---

---

## Part 5: Legado 功能差距（需后续补齐）

通过直接对比两个代码库确认的差距：

### 缺失功能（❌）

| 功能 | Legado 实现 | MoRealm 状态 | 优先级 |
|------|------------|-------------|--------|
| 换源（已加入书架的书换书源） | `ChangBookSourceDialog` + `ChangeChapterSourceDialog` | 完全缺失 | P0 |
| RSS/订阅阅读 | `RssSource` + `RssArticle` + 完整 UI | 完全缺失 | P1 |
| Web 端阅读服务 | `HttpReadAloudService` + NanoHTTPD | 有依赖但无实现 | P2 |
| 阅读进度云同步 | `AppWebDav` 进度同步 | 有 WebDAV 备份但无进度同步 | P1 |
| 词典规则 | `DictRule` + 划词查询 | 完全缺失 | P2 |

### 部分实现（🟡）

| 功能 | 差距 | 优先级 |
|------|------|--------|
| 书源调试 UI | 有 `SourceDebug.kt` 后端但 UI 不完整 | P0 |
| 书源登录/验证码 | 有 Cookie 管理但无 WebView 登录 UI | P0 |
| 发现页/探索 | 有 `ExploreRule` 但 UI 未完整暴露 | P1 |
| 替换规则导入导出 | 有管理 UI 但缺少批量导入导出 | P1 |
| TXT 目录规则管理 | 有自动检测但缺少手动规则管理 UI | P1 |

### 已完成（✅）

书架、搜索、本地书导入（TXT/EPUB/PDF/MOBI/CBZ）、阅读器核心、TTS、
书源导入（Legado 格式兼容）、主题导入导出、WebDAV 备份恢复、替换规则管理、
WiFi 传书、年度报告

---

## 验证清单

- [ ] 日志：每个模块前缀在 logcat 中可过滤
- [ ] 日志：异常值告警能检测到已知 bug 模式
- [ ] PageTurnCoordinator：所有翻页模式功能正常
- [ ] 模式隔离：SIMULATION → SLIDE 切换无穿透
- [ ] ViewModel：每个 Controller 的 StateFlow 独立可测
- [ ] 背景色：主题切换后所有模式即时生效
- [ ] 仿真翻页：连续翻 20 页无循环/卡住
- [ ] 滚动模式：跨章无断裂
- [ ] 进度：退出重进恢复到正确位置
