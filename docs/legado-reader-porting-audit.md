# Legado 阅读器移植审计文档

更新时间：2026-04-26

本文档只记录 MoRealm 阅读器相对 Legado 阅读系统的移植状态。状态分为：

- **已移植**：核心语义已按 Legado 对齐，并已接入当前代码路径。
- **部分移植**：已有对应实现，但仍存在职责未完全收敛或功能缺口。
- **未移植**：尚未建立可靠等价实现。

## 当前总体结论

MoRealm 阅读器已经完成第一轮核心翻页状态迁移：普通点击、滑动、覆盖、仿真、无动画、音量键、自动翻页都已经进入 `ReaderPageDirection -> fillPage(direction) -> upContent()` 链路。滚动模式也已经补入 Legado 的可见页扫描、保留一行翻页、跨页循环推进语义。

但还不能宣布“阅读器全部完整移植完成”。目前最主要的剩余差异是：滚动模式还没有完整抽成 Legado 式 `ScrollPageDelegate + ContentTextView + PageFactory` 三层，TTS/朗读推进还未纳入统一阅读状态，排版层也还没有逐项完整对齐 Legado 的全部 HTML/Span/图片/段落逻辑。

## 已移植项目

### DataSource / PageFactory 构造方式

Legado 来源：

- `api/DataSource.kt`
- `api/PageFactory.kt`
- `provider/TextPageFactory.kt`

MoRealm 文件：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderDataSource.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageFactory.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`

完成内容：

- 新增 `ReaderDataSource`，字段对齐 Legado：`pageIndex`、`currentChapter`、`nextChapter`、`prevChapter`、`isScroll`。
- 新增 `hasNextChapter()`、`hasPrevChapter()`、`upContent(relativePosition, resetPageOffset)`。
- 新增 `SnapshotReaderDataSource`，作为当前 MVVM 数据源实现。
- `ReaderPageFactory` 已改为依赖 `ReaderDataSource`，对齐 Legado `PageFactory(dataSource)` 构造方式。
- 跨章移动按 Legado 的章节可用性判断；上一章/下一章预览页不再进入普通 `pages` 列表。

当前状态：**已移植核心接口，后续继续把快照 pageIndex 改为统一持久状态**。

### TextPageFactory 核心语义

Legado 来源：`provider/TextPageFactory.kt`

MoRealm 文件：`app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageFactory.kt`

完成内容：

- 已实现 `curPage`、`prevPage`、`nextPage`、`nextPlusPage`。
- 已实现 `moveToFirst()`、`moveToLast()`。
- 已实现 `moveToPrev(displayIndex)`、`moveToNext(displayIndex)`。
- 已实现 `hasPrev(displayIndex)`、`hasNext(displayIndex)`、`hasNextPlus(displayIndex)`。
- 普通分页窗口只包含当前章节页；上一章末页、下一章首页只作为 `prevPage` / `nextPage` 预览，不进入 `pages` / display index。
- 章首向前、章末向后不再由动画组件直接跳章节，而是由 PageFactory/ReadViewState 提交边界。

当前状态：**已移植核心翻页语义；当前章节页列表已和预览页分离，避免显示索引污染章节状态**。

### ReadView.fillPage / upContent / upProgress

Legado 来源：`ReadView.kt`

MoRealm 文件：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageState.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageFactory.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`

完成内容：

- 新增 `ReaderPageDirection`，对应 Legado `PageDirection`。
- 新增 `ReaderPageState.fillPage(direction)`，对应 Legado `ReadView.fillPage(direction)`。
- 新增 `ReaderPageState.upContent()`，统一返回 `currentPage`、`prevPage`、`nextPage`、`nextPlusPage`。
- `fillPage(direction)` 成功提交后调用 `ReaderPageFactory.upContent(relativePosition, resetPageOffset=false)`。
- `CanvasRenderer` 已保存 `ReaderPageContent` 快照。
- 渲染和点击命中已优先使用 `ReaderPageContent.pageForDisplay()`，不是单纯读取 Pager 窗口索引。
- `fillPage` 后会用当前真实 `TextPage` 更新进度和可见页信息，对齐 Legado `upProgress()` 使用 `pageFactory.curPage` 的语义。

当前状态：**已移植主要提交链路，仍保留 Compose Pager 作为显示容器**。

### 普通分页动画提交链路

Legado 来源：

- `delegate/PageDelegate.kt`
- `delegate/HorizontalPageDelegate.kt`
- `delegate/SlidePageDelegate.kt`
- `delegate/CoverPageDelegate.kt`
- `delegate/SimulationPageDelegate.kt`
- `delegate/NoAnimPageDelegate.kt`

MoRealm 文件：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/PageAnimations.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageDelegateState.kt`

完成内容：

- 点击上一页/下一页不再直接跳章。
- 点击翻页只计算目标页并启动动画。
- 动画 settle 后通过 `fillPage(direction)` 提交。
- 修复了动画前预提交 `fillPage()` 的问题。
- 修复了 Pager settle 后不能用已经变化的 `pagerState.currentPage` 作为提交起点的问题。
- `SlidePager`、`VerticalSlidePager`、`CoverPager` 已在 settle 后提交。
- `SimulationPager` 已通过 `onFillPage(ReaderPageDirection)` 提交。
- `SimulationParams` 已删除 `onNextChapter/onPrevChapter`，防止仿真翻页绕过 `fillPage(direction)`。
- `NONE` 无动画模式已加入 settle 回调，对齐 Legado `NoAnimPageDelegate` 的立即提交语义。
- 新增 `ReaderPageDelegateState`，承载 Legado `PageDelegate` 的 `isMoved`、`noNext`、`mDirection`、`isCancel`、`isRunning`、`isStarted`、`isAbortAnim` 状态语义。
- 普通点击翻页、音量键/自动翻页命令已通过 `keyTurnPage(direction)` 门禁，避免动画运行中重复启动翻页。
- `fillPage` 完成后调用 `stopScroll()`，对齐 Legado 动画结束后清理运行状态的行为。

当前状态：**已移植核心提交语义与 PageDelegate 基础状态机，具体动画绘制仍由 Compose 组件承载**。

### 音量键和自动翻页

Legado 来源：

- `PageDelegate.keyTurnPage(direction)`
- `AutoPager.kt`

MoRealm 文件：

- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`

完成内容：

- 音量键不再直接调用 `nextChapter()` / `prevChapter()`。
- 音量键发送 `ReaderPageDirection.NEXT/PREV`。
- 自动翻页不再直接调用 `nextChapter()`。
- `ReaderViewModel` 只保留自动翻页速度/开关，实际翻页交给阅读器内部 `ReaderAutoPagerState`。
- 非滚动模式由 `CanvasRenderer` 执行方向命令。
- 滚动模式由 `ScrollRenderer` 执行方向命令。

当前状态：**已移植普通推进语义和 AutoPager 完整运行模型**。

### 滚动可见页与保留一行翻页

Legado 来源：

- `ContentTextView.scroll(mOffset)`
- `ContentTextView.getCurVisiblePage()`
- `ScrollPageDelegate.calcNextPageOffset()`
- `ScrollPageDelegate.calcPrevPageOffset()`

MoRealm 文件：`app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`

完成内容：

- 新增 `getVisibleLines()`，扫描当前页及后续页实际进入屏幕的行。
- 新增 `getCurVisiblePageIndex()`，页眉页脚、章节标题、底部进度从实际可见页取值。
- 可见行采用 Legado 的 60% 可见阈值，避免半行露出时提前切页。
- `calcNextPageOffset()` / `calcPrevPageOffset()` 改为基于实际可见首行/末行。
- 点击上一屏/下一屏不再简单滚动固定屏幕比例。
- `applyScroll()` 跨页推进改为循环处理，匹配一次 fling 可跨多页的 Legado 滚动语义。
- 滚动模式已接收 `ReaderPageDirection.NEXT/PREV`。
- 滚动模式已接入 `ReaderPageDelegateState`，拖动开始对应 `onDown()`，拖动中对应 `markMoved()`，fling/命令翻页对应 `startAnim/keyTurnPage()`，取消对应 `abortAnim()`，结束对应 `stopScroll()`。
- 点击滚动前会停止 fling 并标记 abort，避免上一段惯性动画继续影响新滚动。

当前状态：**已移植关键滚动语义与 Delegate 运行/取消状态，View/PageFactory 三层仍需继续收敛**。

### 页眉页脚进度

Legado 来源：`PageView.setProgress(textPage)`

MoRealm 文件：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`

完成内容：

- 页眉页脚使用真实 `TextPage.title`、`chapterIndex`、`index`、`pageSize`、`readProgress`。
- 普通分页不再使用预览窗口索引当页码。
- 滚动模式不再只看 `currentPageIndex`，而是使用实际可见页。
- `cover`、短章节、跨章边界页的标题/进度已按真实 `TextPage` 同步。

当前状态：**已移植核心进度来源**。

## 部分移植项目

### PageDelegate 独立状态机

Legado 来源：`delegate/PageDelegate.kt`

当前状态：**已移植基础状态机，后续继续拆分每种具体动画 Delegate**。

已完成：

- 普通翻页已收敛到方向命令和 `fillPage(direction)`。
- 动画组件不再直接跳章节。
- `ReaderPageDelegateState` 已对齐 Legado `PageDelegate` 的基础状态字段和 `keyTurnPage/onDown/abortAnim/stopScroll` 语义。

仍需继续：

- `PageAnimations.kt` 仍承载具体动画绘制；后续可以继续按 Legado 的 `SlidePageDelegate/CoverPageDelegate/SimulationPageDelegate` 拆分具体 Delegate 类。

### ScrollPageDelegate / ContentTextView 三层结构

Legado 来源：

- `delegate/ScrollPageDelegate.kt`
- `ContentTextView.kt`
- `PageFactory.kt`

当前状态：**部分移植，高优先级继续项**。

已完成：

- 已移植可见页扫描。
- 已移植保留一行翻页。
- 已移植滚动跨页循环推进。

仍需继续：

- 目前滚动当前页推进仍在 `ScrollRenderer` 内维护 `currentPageIndex/pageOffset`。
- Legado 中滚动跨页最终由 `pageFactory.moveToNext(true)` / `moveToPrev(true)` 提交，MoRealm 还需要继续收敛到统一阅读状态层。
- `abortAnim()`、`onScrollAnimStart()`、`onScrollAnimStop()`、Scroller 取消状态还没有完整等价。

### ReaderDataSource 持久状态

Legado 来源：`DataSource.pageIndex` 与 `ReadBook.durPageIndex`

当前状态：**部分移植**。

已完成：

- 已有 `ReaderDataSource` 接口。
- `ReaderPageFactory` 已依赖该接口。

仍需继续：

- `SnapshotReaderDataSource.pageIndex` 目前仍是渲染快照，尚未成为统一可变阅读状态。
- 章节加载、预排版、进度保存仍由 ViewModel 和渲染层共同参与，需要继续明确边界。

### AutoPager 完整行为

Legado 来源：`AutoPager.kt`

当前状态：**已移植完整运行模型和非滚动覆盖绘制**。

已完成：

- 新增 `ReaderAutoPagerState`，字段对齐 Legado：`progress`、`isRunning`、`isPausing`、`scrollOffsetRemain`、`scrollOffset`、`lastTimeMillis`。
- 自动翻页从 ViewModel 简单定时 tick 移入阅读器内部，按帧计算偏移。
- 非滚动模式按 Legado `computeOffset()` 累加 `progress`，到达整屏高度后通过 `fillPage(NEXT)` 翻页并 `reset()`。
- 滚动模式按 Legado `onDraw()` 的滚动语义，把时间计算出的偏移交给 `ScrollRenderer.applyScroll()`。
- ViewModel 只保留自动翻页速度/开关状态，不再负责发翻页 tick。
- 非滚动模式已绘制下一页裁剪覆盖和进度线，对齐 Legado `AutoPager.onDraw()` 的视觉行为。

仍需继续：

- 文本选择禁用/恢复还需与现有选择系统打通。

### 排版系统

Legado 来源：

- `provider/ChapterProvider.kt`
- `provider/TextChapterLayout.kt`
- `provider/TextMeasure.kt`
- `provider/ZhLayout.kt`

当前状态：**部分移植**。

已完成：

- MoRealm 已有 `ChapterProvider`、`TextMeasure`、`ZhLayout`。
- 基础分页、标题样式、中文排版、图片列、页面录制器已存在。

仍需继续：

- HTML span、图片样式、段落编号、VIP/付费、异常恢复、布局监听、更多 column 类型仍未完整核对。

### 文本选择 / 图片点击 / 朗读位置

Legado 来源：`ContentTextView.kt`、`ReadView.kt`

当前状态：**部分移植**。

已完成：

- 已有长按选词、选择工具栏、图片点击、搜索高亮、朗读行高亮基础能力。

仍需继续：

- 选择跨相对页、朗读位置 `getReadAloudPos()`、长截图、bookmark position 等细节还未完整对齐。

## 未移植或待专项移植项目

### TTS / 朗读推进链路

Legado 来源：

- `ReadBook.moveToNextPage()`
- `ReadBook.moveToNextChapterAwait()`
- `ReadBook.readAloud(...)`
- 朗读相关控制器

当前状态：**已移植推进入口、章节字符位置和页面朗读高亮**。

问题：

- TTS 完章、上一章、下一章事件不再直接调用 `nextChapter()` / `prevChapter()`。
- `ReaderViewModel.readAloudPageTurn` 发送方向事件。
- `ReaderScreen` 将朗读方向事件转换为 `ReaderPageDirection.PREV/NEXT`。
- 阅读器通过现有 `pageTurnCommand -> keyTurnPage -> fillPage(direction)` 推进，跨章也由阅读状态处理。
- `ReaderTtsController` 输出当前朗读段落在章节内的字符位置。
- `TextPage.upPageAloudSpan()` 已按 Legado 逻辑移植，能标记当前朗读段落所在整段文本行。
- `PageCanvas` 已根据 `TextLine.isReadAloud` 绘制朗读高亮。
- `CanvasRenderer` 已按章节字符位置定位页，并调用 `upPageAloudSpan()`。

要求：

- 后续继续专项对照 Legado 的服务通知控制。

## 当前允许保留的直接章节动作

以下是用户显式章节动作，暂时允许直接调用章节切换；它们不等同普通翻页：

- 上一章按钮
- 下一章按钮
- 目录选章
- 章节列表跳转

普通翻页、音量键、自动翻页、动画结束、滚动翻页不允许绕过 `fillPage(direction)` 或等价阅读状态入口。

## 当前全量审查矩阵

| Legado 层/文件 | MoRealm 对应文件 | 状态 | 结论 |
| --- | --- | --- | --- |
| `api/DataSource.kt` | `ReaderDataSource.kt`、`CanvasRenderer.kt` | 已移植核心接口 | 接口字段已对齐；后续要做持久 pageIndex。 |
| `api/PageFactory.kt` | `ReaderPageFactory.kt` | 已移植核心接口 | `cur/prev/next/nextPlus`、`moveTo*`、`has*` 已有。 |
| `provider/TextPageFactory.kt` | `ReaderPageFactory.kt` | 已移植核心翻页语义 | 跨章节边界已通过 PageFactory/ReadViewState 提交。 |
| `ReadView.fillPage/upContent/upProgress` | `ReaderPageState.kt`、`CanvasRenderer.kt` | 已移植主要链路 | `fillPage -> upContent -> upProgress` 已接入；仍保留 Pager 显示容器。 |
| `PageView.setProgress` | `PageReaderInfoOverlay` | 已移植 | 标题、页码、进度来自真实 `TextPage`。 |
| `delegate/PageDelegate.kt` | `ReaderPageDelegateState.kt` | 已移植基础状态机 | `isMoved/isCancel/isRunning/isStarted/isAbortAnim/mDirection/keyTurnPage/onDown/abortAnim/stopScroll` 已有 Compose/MVVM 对应。 |
| `delegate/SlidePageDelegate.kt` | `SlidePager` | 已移植提交语义 | settle 后提交；动画由 Compose Pager 承载。 |
| `delegate/CoverPageDelegate.kt` | `CoverPager` | 已移植提交语义 | settle 后提交；阴影和具体绘制细节仍可继续优化。 |
| `delegate/SimulationPageDelegate.kt` | `SimulationPager` | 已移植提交语义 | 完成动画后 `onFillPage`；不再直跳章节。 |
| `delegate/NoAnimPageDelegate.kt` | `PageAnimType.NONE` | 已移植提交语义 | 无动画也走 settle/fillPage。 |
| `delegate/ScrollPageDelegate.kt` | `ScrollRenderer.kt`、`ReaderPageDelegateState.kt` | 部分移植 | 关键滚动语义、运行状态、取消状态已移植；View/PageFactory 三层仍需收敛。 |
| `ContentTextView.getCurVisiblePage` | `ScrollRenderer.getVisibleLines` | 已移植核心语义 | 已使用可见行扫描和 60% 阈值。 |
| `AutoPager.kt` | `ReaderAutoPagerState.kt`、`CanvasRenderer.kt`、`ScrollRenderer.kt` | 已移植完整运行模型 | 按帧计算偏移、非滚动裁剪覆盖/进度线、整屏翻页、滚动像素推进已接入。 |
| `TextPage.kt` / `TextChapter.kt` | `PageLayout.kt` | 部分移植 | 基础页/章模型已存在；部分 span/朗读/段落能力未完整。 |
| `ChapterProvider.kt` / `TextChapterLayout.kt` | `ChapterProvider.kt` | 部分移植 | 基础排版已存在；HTML/图片/异常恢复仍需核对。 |
| 朗读/TTS 推进与高亮 | `ReaderTtsController.kt`、`ReaderViewModel.kt`、`ReaderScreen.kt`、`PageLayout.kt`、`PageCanvas.kt`、`CanvasRenderer.kt` | 已移植主要阅读链路 | 完章/上下章事件已走 `ReaderPageDirection`；章节字符位置和 `upPageAloudSpan()` 页面高亮已接入。 |

## 最近一次验证

- 构建命令：`./gradlew.bat assembleDebug`
- 构建结果：通过
- APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 2026-04-26 新增移植记录

### PageDelegate 基础状态机

Legado 对照来源：`delegate/PageDelegate.kt`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageDelegateState.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`

完成内容：

- 新增 `ReaderPageDelegateState`。
- 字段对齐：`isMoved`、`noNext`、`direction`、`isCancel`、`isRunning`、`isStarted`、`isAbortAnim`。
- 方法对齐：`onDown()`、`setDirection()`、`keyTurnPage()`、`abortAnim()`、`stopScroll()`。
- 普通翻页入口改为先经过 `keyTurnPage(direction)`，防止运行中重复启动动画。
- 滚动拖动/惯性/取消已接入 `onDown/markMoved/startAnim/abortAnim/stopScroll`。

剩余差异：

- Legado 每种动画都有独立 Delegate 类并持有 Scroller；MoRealm 当前仍使用 Compose Pager / Animatable 承载具体动画，只把基础状态机独立出来。

### AutoPager 运行模型

Legado 对照来源：`AutoPager.kt`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderAutoPagerState.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`

完成内容：

- 自动翻页运行状态已从 ViewModel 定时器迁入阅读器内部。
- `ReaderAutoPagerState.computeOffset()` 按 Legado `height / readTime * elapsedTime` 计算偏移。
- 非滚动模式按 `progress >= height` 后调用 `fillPage(NEXT)`，失败时停止自动翻页。
- 滚动模式把偏移传入 `ScrollRenderer`，由滚动器执行像素级 `applyScroll(-offset)`。
- `stop/reset/pause/resume` 状态已具备。
- 非滚动模式已绘制下一页裁剪覆盖和进度线。

剩余差异：

- 文本选择可用状态还未和 AutoPager 启停联动。

### TTS / 朗读推进入口

Legado 对照来源：

- `ReadView.aloudStartSelect()`
- `ReadBook.moveToNextPage()`
- `ReadBook.moveToNextChapterAwait()`
- `ReadBook.readAloud(...)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`

完成内容：

- TTS 完章回调不再直接调用 `nextChapter()`。
- TTS 上一章/下一章事件不再直接调用 `prevChapter()` / `nextChapter()`。
- 新增 `ReaderViewModel.readAloudPageTurn`，朗读层只发方向事件。
- `ReaderScreen` 将朗读方向事件转成 `ReaderPageDirection.PREV/NEXT`。
- 朗读推进进入现有 `pageTurnCommand -> keyTurnPage(direction) -> fillPage(direction)` 链路，跨章由阅读状态统一处理。

剩余差异：

- 服务通知控制仍需继续对照 Legado 朗读服务。

### 选择位置起读

Legado 对照来源：

- `ReadView.aloudStartSelect()`
- `TextPage.getPosByLineColumn(lineIndex, columnIndex)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/domain/render/PageLayout.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderTtsController.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`

完成内容：

- `TextPage.getPosByLineColumn()` 已按 Legado 移植。
- 选择工具栏“朗读”不再只朗读选中文本，而是把选择起点转换为章节字符位置。
- `ReaderViewModel.readAloudFromPosition()` 以章节字符位置启动朗读。
- `ReaderTtsController.readAloudFrom()` 支持从章节字符位置计算段落索引并开始朗读。
- 起读后继续复用现有朗读高亮链路：章节位置 -> `upPageAloudSpan()` -> `TextLine.isReadAloud`。
- `TextChapter.getNeedReadAloud(pageSplit)`、`getParagraphs(pageSplit)`、`getParagraphNum()`、`getLastParagraphPosition()` 已按 Legado 补齐。
- `CanvasRenderer` 将排版后的真实段落章节位置回传给 `ReaderViewModel`。
- `ReaderTtsController` 使用真实段落章节位置计算当前朗读位置，不再只靠文本行顺序累加。

剩余差异：

- Legado 支持选择跨相对页时先推进内部页状态再取起点；MoRealm 当前选择主要发生在当前显示页，跨相对页选择后续继续核对。

## 下一步优先级

1. 将滚动模式继续收敛为 `ScrollPageDelegate + ContentTextView + PageFactory` 三层结构。
2. 将 `SnapshotReaderDataSource.pageIndex` 演进为统一可变阅读状态，减少 Pager index 作为状态源。
3. 专项核对 TTS/朗读高亮、起始位置、选择跨页位置。
4. 对排版层逐项核对 Legado `ChapterProvider` / `TextChapterLayout` / column 类型。
5. 继续检查是否存在普通翻页路径绕过 `fillPage(direction)`。

## 2026-04-26 继续对照记录：TextPageFactory 取页语义

Legado 对照来源：

- `app/src/main/java/io/legado/app/ui/book/read/page/provider/TextPageFactory.kt`
- `app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageFactory.kt`

本次确认的问题：

- MoRealm 原 `ReaderPageFactory.nextPlusPage` 只把下一章第一页作为边界页，未按 Legado 区分“当前章倒数第二页”和“当前章最后一页”。
- MoRealm 原 `nextPage/prevPage/nextPlusPage` 未统一执行 `removePageAloudSpan()`，相邻页复用时可能把朗读高亮带入预览页或动画页。
- MoRealm 原 `nextPlusPageForDisplay()` 主要依赖显示窗口 `displayIndex + 2`，跨章节时缺少 Legado 的第二页读取和“继续滑动以加载下一章…”提示页。

完成内容：

- `curPage`、`nextPage`、`prevPage`、`nextPlusPage` 已按 Legado `TextPageFactory` 的分支顺序重写。
- `nextPage`：当前章未到末页时取当前章下一页；当前章完成且到末页时取下一章第一页；没有内容时返回格式化空页。
- `prevPage`：当前章非首页时取当前章上一页；当前章首页且上一章完成时取上一章最后一页；没有内容时返回格式化空页。
- `nextPlusPage`：当前章还剩至少两页时取当前章后两页；当前章倒数第二页时取下一章第一页；当前章最后一页时取下一章第二页；不存在下一章第二页时显示 Legado 同语义的继续滑动提示页。
- 所有预览/相邻页读取统一清理 `removePageAloudSpan()`，避免动画预览页继承当前朗读高亮。
- `hasNextPlus(displayIndex)` 已按当前显示页重新判断，不再只看窗口内是否有 `displayIndex + 2`。

当前状态：**已移植 Legado `TextPageFactory` 的取页边界语义**。

仍需继续对照：

- `ReadView.upContent(relativePosition, resetPageOffset)` 在 Legado 中直接写入固定 `prevPage/curPage/nextPage` 三个 `PageView`；MoRealm 仍由 Compose Pager/窗口页渲染承载，需要继续收敛到等价的固定三页内容状态。
- `ContentTextView.scroll()` 在 Legado 中由 `PageFactory.moveToNext(true)` 直接推进统一页状态；MoRealm 滚动模式仍保留 `currentPageIndex/pageOffset` 局部状态，需要继续改造成统一阅读状态。
- `ContentTextView.relativePage(0..2)`、触摸、选择、朗读位置都基于相对页；MoRealm 的跨相对页选择和朗读位置还要继续检查并补齐。

## 2026-04-26 验证记录

- 构建命令：`./gradlew.bat assembleDebug`
- 构建结果：通过
- APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 2026-04-26 继续移植记录：统一页索引与滚动窗口

Legado 对照来源：

- `page/api/DataSource.kt`
- `page/provider/TextPageFactory.kt`
- `page/ReadView.kt`
- `page/ContentTextView.kt`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`

本次确认的问题：

- `SnapshotReaderDataSource.pageIndex` 之前固定传 `0`，这不符合 Legado `DataSource.pageIndex get() = ReadBook.durPageIndex` 的统一阅读页索引语义。
- 普通分页虽然有 `ReaderPageFactory`，但 `PageFactory` 每次重建仍以首页作为当前页，后续主要依赖 Compose `PagerState.currentPage`，这会让 `curPage/nextPage/prevPage` 在跨页后与 Legado 的状态源不同。
- 滚动模式之前只把当前章 `currentChapterPages` 传给 `ScrollRenderer`，没有使用 `PageFactory.pages` 的 `prev/current/next` 边界窗口，因此没有完整进入 Legado `relativePage(0..2)` 模型。

完成内容：

- 新增 `readerPageIndex` 作为 Compose/MVVM 中的统一当前页索引，对应 Legado `ReadBook.durPageIndex`。
- `SnapshotReaderDataSource.pageIndex` 改为读取 `readerPageIndex`，不再固定为 `0`。
- 普通分页 `fillPage(direction)` 成功后，按 `pageFactory.currentLocalIndex()` 回写 `readerPageIndex`。
- 恢复阅读进度时同步设置 `readerPageIndex`，保证 `curPage/nextPage/prevPage/nextPlusPage` 以恢复页作为状态源。
- 滚动模式 `pages` 改为使用 `pageFactory.pages`，即同一窗口内包含上一章末页、当前章页、下一章首页。
- `ScrollRenderer` 增加 `initialPageIndex`，使带有上一章边界页时仍能恢复到当前章真实显示页。
- 滚动可见页变化时把显示索引映射回当前章本地页索引并回写 `readerPageIndex`。
- 滚动到 `prev/next` 边界页时按 `pageFactory.isPrevBoundary/isNextBoundary` 触发章节提交，避免滚动模式继续只在当前章页列表内自循环。

当前状态：**已移植统一页索引状态源，并让滚动模式进入 PageFactory 边界窗口**。

仍需继续对照：

- Legado `ReadView.upContent()` 是固定三个 `PageView` 直接 setContent；MoRealm 当前已经有统一页索引和 `ReaderPageContent`，但具体绘制容器仍是 Compose Pager，需要继续把可绘制内容收敛成固定 `prev/cur/next` 语义。
- Legado `ContentTextView.scroll()` 在越界时直接调用 `pageFactory.moveToPrev(true)` / `moveToNext(true)`；MoRealm 当前滚动已经使用 PageFactory 窗口并提交边界章节，但 `ScrollRenderer` 内仍保留 `currentPageIndex/pageOffset`，下一步继续把越界推进封装成和 Legado 同名同义的滚动状态方法。
- `ContentTextView.touch/touchRough/getReadAloudPos()` 的相对页选择与朗读位置还需要继续逐项移植到滚动模式。

## 2026-04-26 验证记录（二）

- 构建命令：`./gradlew.bat assembleDebug`
- 构建结果：通过
- APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 2026-04-26 继续移植记录：滚动相对页触摸与选择

Legado 对照来源：

- `page/ContentTextView.kt`
  - `relativeOffset(relativePos)`
  - `relativePage(relativePos)`
  - `touch(...)`
  - `touchRough(...)`
  - `longPress(...)`
  - `click(...)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/TextSelection.kt`

本次确认的问题：

- MoRealm 滚动模式此前只负责滚动绘制，触摸命中没有按 Legado `relativePage(0..2)` 扫描当前页、下一页、下下页。
- 滚动模式长按选择没有携带 `TextPos.relativePagePos`，跨相对页时选择起点会退化成当前页坐标。
- 滚动模式图片点击没有复用相对页命中，下一页已经显示在屏幕内时，图片点击和长按选择可能落不到正确页。

完成内容：

- `ScrollRenderer` 新增 `relativePage(relativePos)`，对应 Legado `ContentTextView.relativePage(relativePos)`。
- `ScrollRenderer` 新增 `relativeOffset(relativePos)`，对应 Legado `ContentTextView.relativeOffset(relativePos)`。
- `ScrollRenderer` 新增 `touchRelativePage(x, y)`，按 `0..2` 相对页扫描，并在后续页入口超过屏幕高度时停止扫描。
- 滚动模式点击图片已通过相对页命中触发 `onImageClick(src)`。
- 滚动模式长按文本已通过相对页命中回传 `TextPage + TextPos + Offset`，再由 `CanvasRenderer` 执行单词选择。
- `findWordRange()` 保留 `tapPos.relativePagePos`，不再把相对页选择强制写成 `0`。

当前状态：**已移植滚动模式的 relativePage 触摸入口与长按选择起点语义**。

仍需继续对照：

- 选择拖动光标跨相对页的 `touchRough()` 行为还没有完整实现。
- `getReadAloudPos()` 对当前可见朗读行的相对页定位还需要继续移植。
- `ContentTextView.scroll()` 的越界推进方法还要继续从局部 `currentPageIndex/pageOffset` 收敛成 PageFactory 语义方法。

## 2026-04-26 验证记录（三）

- 构建命令：`./gradlew.bat assembleDebug`
- 构建结果：通过
- APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 2026-04-26 继续移植记录：滚动朗读起点、相对页选择绘制、scroll 边界推进

Legado 对照来源：

- `page/ContentTextView.kt`
  - `scroll(mOffset)`
  - `getReadAloudPos()`
  - `touchRough(...)`
  - `selectStartMove(...)`
  - `selectEndMove(...)`
- `page/ReadView.kt`
  - `getReadAloudPos()`
  - `aloudStartSelect()`
- `ReadBookActivity.kt`
  - `onClickReadAloud()` 中滚动模式从当前可见行起读

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/TextSelection.kt`

完成内容：

- `ScrollRenderer` 新增 `getReadAloudPos()` 同义实现，按 `relativePage(0..2)` 扫描当前可见的朗读行。
- 滚动模式可见朗读行会回传 `page.chapterIndex + line.chapterPosition`。
- `ReaderViewModel.ttsPlayPause()` 在未播放时优先使用滚动可见朗读位置作为 `startChapterPosition`，对齐 Legado 滚动模式点击朗读从可见行开始。
- `CanvasRenderer` 为选择增加 `selectedTextPage`，工具栏复制/朗读不再强行使用 `pagerState.currentPage`，滚动相对页选择可以取到正确页。
- `PageContentBox` 普通分页选择绘制只接受 `relativePagePos == 0`，避免滚动相对页坐标误画到普通当前页。
- `ScrollRenderer` 选择绘制已按当前绘制页的 `relativePos` 拆出局部 `TextPos(0, line, column)`，相对页选择不会丢失高亮。
- `ScrollRenderer.applyScroll()` 已拆出 `clampAtFirstPage()`、`clampAtLastPage()`、`moveToPrevPageByScroll()`、`moveToNextPageByScroll()`，对应 Legado `ContentTextView.scroll()` 中的顶部夹紧、底部夹紧、向前页推进、向后页推进语义。
- 到达首尾页时调用 `scrollDelegateState.abortAnim()`，对应 Legado `pageDelegate?.abortAnim()`。

当前状态：**已移植滚动朗读可见起点、相对页选择绘制、scroll 边界推进函数化语义**。

仍需继续对照：

- Legado `selectStartMove/selectEndMove` 的光标拖动反转逻辑还未完整接入 Compose 光标拖动 UI；当前项目的选择光标拖动能力本身不完整，后续要补实际拖动入口。
- Legado 搜索结果跨页选择会调用 `selectEndMoveIndex(1, 0, charIndex2)`，MoRealm 搜索高亮/选择结果还要继续对照。
- `ReadView.upContent()` 固定三页 `PageView` 结构仍需继续收敛，不能停在 Compose Pager 显示窗口。

注意：本轮按要求暂未构建，等待继续移植更多阅读系统差异后统一构建汇报。

## 2026-04-26 继续移植记录：TextPos 与选择移动状态

Legado 对照来源：

- `page/entities/TextPos.kt`
- `page/ContentTextView.kt`
  - `selectStartMoveIndex(...)`
  - `selectEndMoveIndex(...)`
  - `upSelectChars()`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/domain/render/PageLayout.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/TextSelection.kt`

完成内容：

- `TextPos` 补齐 Legado 的 `compare(TextPos)`。
- `TextPos` 补齐 Legado 的 `compare(relativePos, lineIndex, charIndex)`。
- `TextPos.EMPTY` 改成未选中坐标 `TextPos(0, -1, -1)`，并补 `isSelected()`。
- `SelectionState` 增加 `reverseStartCursor`、`reverseEndCursor`。
- `SelectionState` 增加 `selectStartMoveIndex()`、`selectEndMoveIndex()`。
- `SelectionState` 增加 `selectStartMove()`、`selectEndMove()`，具备 Legado 光标拖动越过另一端时反转选择端点的状态语义。

当前状态：**TextPos 比较和选择端点移动状态已按 Legado 补齐**。

仍需继续对照：

- Compose UI 目前没有完整的起止光标拖动入口，后续要把 `CursorHandle` 接入 `selectStartMove/selectEndMove`。
- `upSelectChars()` 在 Legado 会直接标记每个 `TextBaseColumn.selected`；MoRealm 当前绘制层按 `TextPos` 动态画选区，后续继续判断是否需要把 column 级 selected/searchResult 状态也完整同步。

注意：本轮继续按要求暂未构建。

## 2026-04-26 继续移植记录：选择光标拖动入口

Legado 对照来源：

- `page/PageView.kt`
  - `selectStartMove(x, y)`
  - `selectEndMove(x, y)`
- `page/ContentTextView.kt`
  - `selectStartMove(x, y)`
  - `selectEndMove(x, y)`
  - `touchRough(x, y)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`

完成内容：

- 滚动模式新增起点/终点 `CursorHandle`，拖动时通过 `touchRelativePage()` 命中 `relativePage(0..2)`。
- 滚动模式起点拖动接入 `SelectionState.selectStartMove()`。
- 滚动模式终点拖动接入 `SelectionState.selectEndMove()`。
- 普通分页 `PageContentBox` 新增起点/终点 `CursorHandle`。
- 普通分页光标拖动通过 `hitTestPage()` 重新计算 `TextPos`，接入同一套 `selectStartMove/selectEndMove`。
- 拖动时同步 `selectedTextPage`，确保复制/朗读/查询使用实际选择所在页。

当前状态：**选择光标拖动入口已接入普通分页和滚动分页**。

仍需继续对照：

- Legado `touchRough()` 支持行外点击时返回 `charIndex = -1` 或 `lastIndex + 1`，MoRealm 普通 `hitTestPage()` 仍偏向最近列，需要继续补边界列语义。
- Legado `upSelectChars()` 会把选区直接写入 column selected/searchResult，MoRealm 仍以绘制时动态计算为主，后续继续对照搜索选区。

注意：仍按要求暂未构建。

## 2026-04-26 LDPlayer 阅读器专项测试补记：高强度滑动、进出书籍、菜单与主题切换

测试环境：

- 模拟器：LDPlayer 9，设备 `emulator-5554`
- ADB：`D:\LDPlayer9\adb.exe`
- 包名：`com.morealm.app.debug`
- 主要测试书籍：`Overlord 03 鲜血的战争少女`、`[OVERLORD不死者之王_第十四卷]`
- 测试证据目录：`test-artifacts/continued/`

### 已确认问题 1：跨章追加时主线程崩溃，直接退回桌面

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado 的 `current/prev/next` 相对页窗口模型，取消连续滚动里把下一章正文拼进当前章的字符串追加模型。
- `TextChapter.pages` 不再直接暴露后台排版中的可变 `ArrayList`，渲染侧通过 `snapshotPages()` 读取稳定快照。
- `ReaderPageFactory` 构造时固定当前章、上一章、下一章页面快照，避免 Compose 重组期间和异步排版/跨章预载同时遍历同一集合。
- `assembleDebug` 已通过；原崩溃栈 `ReaderPageFactory.<init>` 的并发遍历路径已被切断。

复现路径：

- 打开 0 进度 EPUB `Overlord 03 鲜血的战争少女`。
- 从 `106/128 8.3%` 开始连续上滑。
- 页码推进到 `125/128 9.8%` 附近后继续滑动。
- 日志显示开始追加并完成追加第 2 章：
  - `Start appending chapter 2 for continuous scroll`
  - `Appended chapter 2 for continuous scroll`
- 随后应用主线程崩溃并退回系统桌面。

关键日志：

```text
FATAL EXCEPTION: main
Process: com.morealm.app.debug
java.util.ConcurrentModificationException
  at java.util.ArrayList$Itr.next(ArrayList.java:860)
  at kotlin.collections.builders.ListBuilder.addAllInternal(ListBuilder.kt:210)
  at kotlin.collections.builders.ListBuilder.addAll(ListBuilder.kt:92)
  at com.morealm.app.ui.reader.renderer.ReaderPageFactory.<init>(ReaderPageFactory.kt:28)
  at com.morealm.app.ui.reader.renderer.CanvasRendererKt.CanvasRenderer-ADlR6KM(CanvasRenderer.kt:360)
```

证据文件：

- `test-artifacts/continued/long-scroll-20260426-095541/long-scroll-logcat.txt`
- `test-artifacts/continued/long-scroll-20260426-095541/ui-000.xml` 到 `ui-040.xml`
- `test-artifacts/continued/long-scroll-20260426-095541/long-scroll-000.png` 到 `long-scroll-200.png`

结论：

- 用户反馈的“跨章后回到顶部/闪退”不是偶发现象，跨章追加第 2 章时已稳定暴露并发修改风险。
- 崩溃点在 Compose 重组期间构造 `ReaderPageFactory`，同时底层章节/页面集合仍在被追加或重排。
- 初步判断：当前阅读器数据结构仍暴露可变 `ArrayList` 给渲染层，异步排版、连续滚动追加、Compose 重组同时访问同一批 `pages/lines/paragraphs`，不符合 Legado 相对页窗口的稳定数据快照模型。

### 已确认问题 2：崩溃后继续阅读进度没有保住，重新打开回到顶部

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 根因崩溃路径已按问题 1 修复，避免跨章追加崩溃打断正常持久化。
- 对照 Legado 翻页/滚动中频繁保存阅读进度的做法，`ReaderViewModel` 已改为可见页或整数进度变化即排队保存，并在阅读器 Back / lifecycle pause 时强制保存。
- 连续滚动边界预览页不会提前改写当前章节索引，避免预览下一章时把进度写到错误章节。

复现路径：

- 触发上面的跨章追加崩溃。
- 从书架首页点击“继续阅读 Overlord 03 鲜血的战争少女”。
- 阅读器重新打开后显示：

```text
《OVERLORD 第三卷 鲜血的战争少女》
1/128  0.1%
```

证据文件：

- `test-artifacts/continued/after-crash-continue-reader-ui.xml`
- `test-artifacts/continued/after-crash-continue-reader.png`
- `test-artifacts/continued/after-crash-continue-reader-logcat.txt`

结论：

- 崩溃前已经滚动到 `125/128` 附近，但重新进入后恢复到 `1/128 0.1%`。
- 这确认了“进度没有保住/回到顶部”的用户反馈。
- 当前进度保存可能依赖正常退出或正常滚动回调，崩溃路径没有把最后可见位置持久化。

### 已确认问题 3：同一本书反复进入时页数分母不稳定

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 已移除旧的连续滚动跨章正文追加模型，当前章总页数不再受后续章节追加影响。
- `ReaderPageFactory` 的 `remember` key 已纳入当前/相邻章节页数与完成态，异步排版完成后页面窗口会重建。
- 页数分母应回到“当前章节排版页数”语义，而不是在 `128/318` 之间混入已追加章节页数。

复现路径：

- 从书架首页反复点击“继续阅读 Overlord 03 鲜血的战争少女”，进入阅读页后按返回退出。
- 运行到第 39 轮附近时，中途多次出现进入阅读器后页数分母不同。

观测结果：

```text
1/128  0.1%  出现 23 次
1/318  0.0%  出现 5 次
```

证据文件：

- `test-artifacts/continued/enter-exit-20260426-1008/enter-exit-summary.tsv`
- `test-artifacts/continued/enter-exit-20260426-1008/ui-*.xml`

结论：

- 同一本书、同一入口、同一进度，在反复进入时总页数会在 `128` 和 `318` 之间跳变。
- 这会直接导致进度百分比和底部进度条不可信。
- 初步判断仍和连续滚动自动追加章节、章节排版缓存、当前页工厂重建时机有关。

### 已确认问题 4：反复进入阅读器时会间歇性直接退回桌面

修复状态：**已修复已知崩溃路径和任务栈风险，待 LDPlayer 多轮回归验证**。

- 已修复问题 1/5 的 `ConcurrentModificationException` 路径，覆盖本轮“PID 消失/回桌面”中有明确崩溃栈的部分。
- 对照 Legado 的阅读 Activity 任务栈策略，`MainActivity` 已改为 `singleTask` 并启用 `alwaysRetainTaskState`，重复从桌面/快捷入口唤起时复用同一任务。
- `MainActivity.onNewIntent()` 已处理继续阅读 intent，避免 `singleTask` 后新的继续阅读请求被旧 `intent` 状态吞掉。
- 阅读器返回时 `safePopBackStackOrHome()` 会在导航栈异常或无上一层时回到 `main_tabs`，避免直接把任务退到桌面。

复现路径：

- 从书架首页反复点击“继续阅读”进入同一本书，再返回书架。
- 第 1 到第 39 轮附近，多次点击进入后前台不是阅读器，而是 `com.android.launcher3` 桌面。
- 观察到 PID 变空或 PID 变化，说明进程确实退出或被重启，不是单纯 UI 识别失败。

样本现象：

```text
entered -> launcher
entered-wait -> launcher
exited -> launcher
pid empty
```

已记录的高风险轮次包括第 1、24、31、34、36 轮附近。第 25、27、28、29、30、32、33、35、37、38 轮能进入阅读器，但页数分母仍有 `128/318` 波动。

证据文件：

- `test-artifacts/continued/enter-exit-20260426-1008/enter-exit-summary.tsv`
- `test-artifacts/continued/enter-exit-20260426-1008/ui-034-entered-wait.xml`
- `test-artifacts/continued/enter-exit-20260426-1008/ui-036-entered-wait.xml`

结论：

- “莫名其妙闪退回桌面”已复现。
- 这一类还需要后续用更轻量的单轮脚本继续抓干净的 `FATAL EXCEPTION`，避免 UIAutomator 采样超时干扰。
- 目前不能把它归因成脚本误点，因为已有多轮 PID 消失或前台直接变成桌面。

### 已确认问题 5：快速主题切换会崩溃

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- `TextChapter.paragraphs/pageParagraphs` 已改为完成后缓存、未完成时基于页面快照即时计算，避免主题切换重绘时 lazy 缓存遍历正在变化的 `pages`。
- `getReadLength/getPageIndexByCharIndex/getContent/getUnRead/getNeedReadAloud/searchSelectionRange` 等路径已改为快照读取。
- 这和问题 1 共用同一类“渲染层读可变集合，后台同时写集合”的根因修复。

复现路径：

- 打开 `Overlord 03 鲜血的战争少女`。
- 小幅上滑进入正文。
- 点中间区域打开阅读菜单，进入“设置/阅读样式”。
- 快速点击主题色块：`纸质 -> 护眼 -> 海蓝 -> 暖黄 -> 墨白`。
- 第 5 次切换后应用退回桌面，PID 消失。

关键日志：

```text
FATAL EXCEPTION: main
Process: com.morealm.app.debug
java.util.ConcurrentModificationException
  at java.util.ArrayList$Itr.next(ArrayList.java:860)
  at com.morealm.app.domain.render.TextChapter.paragraphs_delegate$lambda$2(PageLayout.kt:527)
  at kotlin.SynchronizedLazyImpl.getValue(LazyJVM.kt:83)
  at com.morealm.app.domain.render.TextChapter.getParagraphs(PageLayout.kt:525)
  at com.morealm.app.ui.reader.renderer.CanvasRendererKt$CanvasRenderer$25$1.invokeSuspend(CanvasRenderer.kt:391)
```

证据文件：

- `test-artifacts/continued/theme-stress-20260426-1018/theme-stress-summary.tsv`
- `test-artifacts/continued/theme-stress-20260426-1018/theme-stress-logcat.txt`
- `test-artifacts/continued/theme-stress-20260426-1018/theme-stress-final.png`

结论：

- 主题切换会触发阅读器重绘/重新计算段落，和异步排版或章节集合变更发生并发冲突。
- 这和跨章追加崩溃虽然栈不同，但都是 `ConcurrentModificationException`，根因很可能同属“渲染层读可变集合，后台/状态变更同时写集合”。
- 主题切换过渡效果本轮没有完整观察完，因为第 5 次快速切换即崩溃。

### 当前优先级

1. 最高优先级：修复所有 `ConcurrentModificationException`，尤其是 `ReaderPageFactory.kt:28`、`CanvasRenderer.kt:360/391`、`PageLayout.kt:525/527` 相关路径。
2. 高优先级：修复崩溃后进度丢失，至少保证最后稳定可见位置可以在进入阅读器后恢复。
3. 高优先级：修复同一本书重复进入时总页数 `128/318` 波动。
4. 高优先级：修复反复进入阅读器时随机回桌面/PID 消失，并补单轮干净崩溃日志。

### 2026-04-26 修复记录：LDPlayer 专项问题 1-7

本轮已完成问题 1-7 的代码路径修复，并通过 `assembleDebug`。其中问题 1、3、5 按 Legado 的相对页窗口和稳定页面快照模型修复；问题 2 按 Legado 高频保存阅读进度的思路补齐可见页/Back/pause 保存；问题 4 先修复明确崩溃栈，并按 Legado `singleTask` 阅读界面策略补任务栈兜底；问题 6、7 按用户预期和 Legado Back/自动翻页状态机补交互优先级。

### 后续继续测试计划

下一轮继续按以下顺序执行：

1. 逐本打开 `overlord1-14` 的 17 本 EPUB：打开一本，快速上滑，立即退出，再换下一本。
2. 逐本打开 `txt_converted` 的 TXT 版书籍，对比 EPUB 与 TXT 阅读器稳定性。
3. 继续补 200 次级别的“新书轮换 + 打开后快速滚动 + 退出”压力测试。
4. 进入发现/搜索书源，搜索关键词并逐个点击结果，记录书源搜索、详情页、加入书架、阅读入口暴露的问题。
5. 单独重跑主题切换压力，尝试保存更完整的过渡截图和崩溃前最后一帧。

注意：原 LDPlayer 测试轮次只记录问题和测试证据；本次 2026-04-26 修复已开始并已通过 `assembleDebug`，仍需继续做 LDPlayer 回归验证。

### 2026-04-26 10:31-10:46 补充测试：书架搜索尾部、轮换脚本与启动/返回异常

本轮继续执行“换书打开 -> 立即快速滑动 -> 退出 -> 换下一本”的 EPUB 轮换测试。测试入口先使用书架搜索 `Overlord` 精确点击书名，避免文件夹滚动坐标误差。

证据目录：

- `test-artifacts/continued/rotation-20260426-continue/epub-20260426-103115/`
- `test-artifacts/continued/rotation-20260426-continue/shelf-search-probe/`
- `test-artifacts/continued/rotation-20260426-continue/user-reported-last-search-click/`
- `test-artifacts/continued/rotation-20260426-continue/shelf-search-tail-clean/`

轮换测试已完成/部分完成的样本：

```text
1  Overlord 01 不死者之王              -> 打开后快速滑动，停在 3/199 9.2%
2  Overlord 03 鲜血的战争少女          -> 打开后快速滑动，停在 1/165 0.1%
3  Overlord 04 蜥蜴人勇者们            -> 打开后快速滑动，停在 1/46 0.2%
4  Overlord 05 王国好汉 上             -> 打开后快速滑动，停在 1/43 0.2%
5  Overlord 06 王国好汉 下             -> 书架搜索结果中找不到/不可达
6  Overlord 07 大坟墓的入侵者          -> 打开后快速滑动，停在 1/56 0.2%
7  Overlord 08 两位领导者              -> 书架搜索结果中找不到/不可达
8  Overlord 09 破军的魔法吟唱者        -> 书架搜索结果中找不到/不可达
9  Overlord 10 谋略的统治者            -> 书架搜索结果中找不到/不可达
10 Overlord 11 矮人工匠                -> 打开后 UIAutomator 出现 null root，后续手动确认阅读页显示 1/41 0.2%
```

#### 已确认问题 9：快速上滑在部分 EPUB 首页被判定为上一页，导致无法推进

修复状态：**2026-04-26 当前 debug APK 回归未完全通过，保留问题；本轮已按 Legado `ReadView + PageDelegate` 二次修复代码路径，待 LDPlayer 复测**。

- 对照 Legado 手势语义，向上滑动应推进阅读内容，向下滑动回退。
- MoRealm 普通分页/仿真分页已补垂直快速滑动识别：上滑映射 `ReaderPageDirection.NEXT`，下滑映射 `PREV`，避免首页快速上滑落到 `PREV rejected at display=0`。
- 连续滚动模式原本已按 `dragAmount < 0 -> NEXT` 的方向启动滚动状态，本轮保留该方向并只补中心点击优先级。
- 2026-04-26 回归复测中，`Overlord Prologue` 从 `1/4 2.5%` 快速上滑 8 次后可见进度推进到 `Prologue 上 5/178 10.3%`，但 logcat 仍出现 `keyTurnPage(PREV) rejected at display=0`，说明用户可见阻塞有所改善，方向误判日志仍未清干净。
- 本轮新证据：`test-artifacts/continued/retest-fixed-20260426-continue/15-vertical-up-before.xml`、`15-vertical-up-after8.xml`、`15-vertical-up-logcat.txt`。
- 新证据根目录：`test-artifacts/continued/retest-fixed-20260426-continue/`。
- 本轮继续对照 Legado：`ReadView.onTouchEvent()` 先由当前 `PageDelegate` 独占触摸事件，`HorizontalPageDelegate` 在移动开始时明确设置 `PREV/NEXT`，`PageDelegate.keyTurnPage()` 也只接受显式方向。
- MoRealm 已禁用普通分页 `HorizontalPager/VerticalPager` 的用户手势，改由 `CanvasRenderer` 在 drag-end 根据主轴和位移阈值映射为一次 `ReaderPageDirection.NEXT/PREV`，再调用 `ReaderPageFactory.moveToNext/moveToPrev`。
- `onPageSettled` 已取消“按 settled 页码差兜底推断方向”的逻辑；没有显式方向时只同步当前页内容和进度，不再调用 `fillPage(PREV/NEXT)`，用于清理快速上滑时夹入的伪 `PREV` 日志。

在 `Overlord 11 矮人工匠` 打开后，本轮脚本连续执行 12 次从下往上的快速滑动：

```text
input swipe 540 1510 540 320 85
```

日志连续出现：

```text
D Reader  : keyTurnPage(PREV) rejected at display=0
```

最终阅读页仍显示：

```text
《OVERLORD 第十一卷 矮人工匠》
1/41  0.2%
```

证据文件：

- `test-artifacts/continued/rotation-20260426-continue/epub-20260426-103115/010-logcat.txt`
- `test-artifacts/continued/rotation-20260426-continue/user-reported-last-search-click/current-ui.xml`
- `test-artifacts/continued/rotation-20260426-continue/user-reported-last-search-click/current-reader.png`

结论：

- 在用户语义里，向上滑动应推进阅读内容；当前某些 EPUB 首页会把该手势判为 `PREV`，并因为 `display=0` 被拒绝。
- 这会让“打开后立刻快速滚动”的真实用户路径表现为无响应或卡在首页。
- 需要核对滚动/分页模式下手势方向映射，尤其是封面页、首页、连续滚动追加后首次滑动的方向判断。

#### 已确认问题 10：阅读器 Back/重启后 Activity 不稳定，进程存在但前台回桌面

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado manifest，`MainActivity` 已增加 `android:launchMode="singleTask"` 和 `android:alwaysRetainTaskState="true"`，降低 LDPlayer 重复启动/返回时任务被关闭或前台回桌面的概率。
- `onNewIntent()` 已显式接收新的继续阅读请求，配合 `ShelfScreen` 的递增 request id，重复点击继续阅读不会依赖旧 intent。
- 阅读器 Back 会先保存进度，再通过 `safePopBackStackOrHome()` 回到导航首页；若 back stack 已异常为空，会显式导航到 `main_tabs`，而不是让 Activity 直接退到桌面。

用户之前反馈“多次进入阅读器时直接退到桌面”。本轮又复现了两个相关现象：

1. 从 `Overlord 11 矮人工匠` 阅读页按 Back，预期回书架，但实际进入 LDPlayer 桌面。
2. 随后执行 `am force-stop` + `am start`，日志显示 `MainActivity` 启动并创建进程，甚至后台触发 EPUB 解析，但前台仍停在桌面。

关键日志：

```text
ActivityManager: START u0 {flg=0x10000000 cmp=com.morealm.app.debug/com.morealm.app.ui.navigation.MainActivity}
ActivityManager: Start proc ... com.morealm.app.debug ... MainActivity
App     : MoRealm started
ActivityManager: Activity pause timeout for ActivityRecord{... com.morealm.app.debug/com.morealm.app.ui.navigation.MainActivity ...}
CoreService: notifyClosing notify tab closed taskPackageName:com.morealm.app.debug
SurfaceFlinger: Attempting to destroy on removed layer: AppWindowToken{... MainActivity ...}
```

同时 `dumpsys activity activities` 只显示桌面为 resumed：

```text
mResumedActivity: ActivityRecord{... com.android.launcher3/.Launcher ...}
```

但进程仍存在：

```text
u0_a71 ... com.morealm.app.debug
```

证据文件：

- `test-artifacts/continued/rotation-20260426-continue/user-reported-last-search-click/after-reader-back.xml`
- `test-artifacts/continued/rotation-20260426-continue/user-reported-last-search-click/after-search-repro-logcat.txt`
- `test-artifacts/continued/rotation-20260426-continue/shelf-search-tail-clean/clean-search-tail-logcat.txt`
- `test-artifacts/continued/rotation-20260426-continue/shelf-search-tail-clean/00-launch-ui.xml`

结论：

- 这不是单纯崩溃日志缺失的问题：存在“进程还在，但 Activity 被关闭/前台回桌面”的路径。
- 需要检查 `MainActivity`、导航栈、Back 处理、启动 intent、LDPlayer 多窗口/任务栈行为，以及阅读器退出时是否触发了异常的 task close。
- 对用户表现就是“莫名其妙退桌面”，且会打断连续进出新书测试。

### 2026-04-26 修复记录：LDPlayer 专项问题 8-10

本轮已补齐问题 8-10 的代码路径修复，并再次通过 `assembleDebug`。问题 8 改为可滚动搜索结果列表；问题 9 对照 Legado 的移动判定语义补垂直上滑推进；问题 10 对照 Legado 的 `singleTask`/任务保持策略补 MainActivity 启动复用、继续阅读 intent 事件化，以及阅读器返回的导航兜底。仍需在 LDPlayer 上重跑“搜索尾部点击、首页快速上滑、反复进出阅读器/force-stop 后启动”三组回归。

#### 本轮新增待办

1. 已修复书架搜索弹窗的可滚动性和最后一项点击区域，待 LDPlayer 回归。
2. 已修复阅读器首页/封面页快速上滑被判定为 `PREV` 的方向问题，待 LDPlayer 回归。
3. 已按 Legado `singleTask`/任务保持策略修复 `MainActivity` 重启与返回兜底，仍需单独重跑“进出阅读器 + 桌面前台”脚本确认。
4. Activity 稳定性回归后继续 TXT/EPUB 200 次轮换测试，否则自动化测试仍可能被桌面状态污染。

### 2026-04-26 10:51-11:02 补充测试：文件夹直进 EPUB 轮换与闪烁反馈

为绕开“书架搜索尾部不可达”对测试入口的影响，本轮改为直接进入 `overlord1-14` 文件夹，从当前可见书籍节点逐本打开，进入阅读器后立即执行快速上下滑动，再返回文件夹。

证据目录：

- `test-artifacts/continued/rotation-20260426-continue/folder-visible-20260426-105139/`
- `test-artifacts/continued/rotation-20260426-continue/flicker-user-report-20260426-1102/`

本轮文件夹直进已覆盖 14 本 EPUB：

```text
1  [丸山くがね]OVERLORD 13[台\繁]      -> 1/8 4.3%
2  OVERLORD                             -> 1/12 0.8%
3  OVERLORD                             -> 1/12 0.8%（同名/重复节点）
4  [OVERLORD不死者之王_第十四卷]       -> 2/2 8.3%
5  [丸山くがね]OVERLORD 13[台\繁]      -> 1/8 4.3%（重复节点）
6  Overlord 01 不死者之王              -> 2/5 3.6%
7  Overlord 03 鲜血的战争少女          -> 2/5 4.0%
8  Overlord 04 蜥蜴人勇者们            -> 2/5 3.6%
9  Overlord 05 王国好汉 上             -> 2/6 3.3%
10 Overlord 06 王国好汉 下             -> 6/6 9.1%
11 Overlord 07 大坟墓的入侵者          -> 2/6 3.3%
12 Overlord 08 两位领导者              -> 2/6 2.2%
13 Overlord 09 破军的魔法吟唱者        -> 2/5 3.6%
14 Overlord 10 谋略的统治者            -> 5/5 11.1%
```

观察点：

- 从文件夹直进能打开之前书架搜索不可达的 `Overlord 06/08/09/10`，说明书籍本身可读，入口问题集中在搜索弹窗。
- 多本 EPUB 快速上下滑动后停在 `2/5` 或 `2/6`，但 `Overlord 06` 停在 `6/6 9.1%`、`Overlord 10` 停在 `5/5 11.1%`，页码很短且快速触底，需要继续确认是否章节窗口/分页总数异常。
- 第 14 本之后脚本尝试继续恢复文件夹时，前台再次变成 LDPlayer 桌面；进程仍存在，和“Activity 前台回桌面”问题一致。

关键日志：

```text
Reader  : Opened: Overlord 10 谋略的统治者 (EPUB)
Reader  : Loaded chapter 0: 《OVERLORD 第十卷 谋略的统治者》
Reader  : upContent(relativePosition=-1, resetPageOffset=false)
Reader  : upContent(relativePosition=1, resetPageOffset=false)
Reader  : upContent(relativePosition=-1, resetPageOffset=false)
Reader  : upContent(relativePosition=1, resetPageOffset=false)
CoreService: notifyClosing notify tab closed taskPackageName:com.morealm.app.debug
```

#### 已确认问题 11：快速翻动时出现可见闪烁/白屏帧

用户现场反馈：“而且我发现了闪烁的情况”。

已补 20 秒 `screenrecord` 复现。录屏路径：

- `test-artifacts/continued/rotation-20260426-continue/flicker-screenrecord-20260426-1108/morealm-flicker-1108.mp4`

从录屏抽取 4fps 帧后，已确认存在白屏/loading 帧：

```text
frames/frame-006.png  -> 阅读器白屏，只显示 loading spinner，底部为 1/1 0.0%
frames/frame-007.png  -> 下一帧才出现《OVERLORD 第十卷 谋略的统治者》标题页，底部为 1/5 2.2%
```

这说明用户看到的“闪烁”不是单纯主观感受，而是进入阅读器/快速操作过程中确实会出现短暂空白或 loading 中间态。

该反馈发生在文件夹直进 EPUB 轮换和快速上下滑动期间。同期日志显示阅读器在短时间内反复切换相对页：

```text
upContent(relativePosition=-1, resetPageOffset=false)
upContent(relativePosition=1, resetPageOffset=false)
upContent(relativePosition=-1, resetPageOffset=false)
upContent(relativePosition=1, resetPageOffset=false)
```

当前判断：

- 白屏/loading 闪烁至少在阅读器进入和初次排版时可复现，快速滑动时还会叠加 `prev/current/next` 相对页内容替换、Compose 重组、Canvas 重绘。
- 如果页面内容层先清空再重绘，或相对页缓存还未稳定，用户会看到空白/跳帧/明暗闪烁。
- 这类问题不一定伴随 `FATAL EXCEPTION`，但 logcat 已出现 `Skipped 33 frames`、`Skipped 39 frames`、`Davey! duration=714ms/799ms/827ms`，说明主线程在进入阅读器/排版期间明显卡顿。

证据文件：

- `test-artifacts/continued/rotation-20260426-continue/folder-visible-20260426-105139/summary.tsv`
- `test-artifacts/continued/rotation-20260426-continue/folder-visible-20260426-105139/batch-logcat.txt`
- `test-artifacts/continued/rotation-20260426-continue/flicker-user-report-20260426-1102/current-ui.xml`
- `test-artifacts/continued/rotation-20260426-continue/flicker-user-report-20260426-1102/current-logcat.txt`
- `test-artifacts/continued/rotation-20260426-continue/flicker-screenrecord-20260426-1108/morealm-flicker-1108.mp4`
- `test-artifacts/continued/rotation-20260426-continue/flicker-screenrecord-20260426-1108/frames/frame-006.png`
- `test-artifacts/continued/rotation-20260426-continue/flicker-screenrecord-20260426-1108/frames/frame-007.png`
- `test-artifacts/continued/rotation-20260426-continue/flicker-screenrecord-20260426-1108/screenrecord-logcat.txt`

后续验证要求：

1. 修复后继续用同样的 `screenrecord + ffmpeg 抽帧` 对比，确认不再出现 `1/1 0.0%` 的白屏/loading 中间帧。
2. 对比快速滑动期间是否仍出现空白帧、整屏闪烁、页眉页脚先出现正文后出现、或背景色短暂跳变。
3. 若闪烁和 `upContent(relativePosition=...)` 强相关，优先检查 `ReaderPageContent` 的相对页替换是否有不可见中间态。
4. 若闪烁主要来自进入阅读器初排版，优先避免清空阅读页后再等待排版，可保留旧内容/骨架页直到新页快照可绘制。

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado `ContentTextView.setContent()` 的行为，非滚动翻页需要保留可绘制内容并避免空页中间态；MoRealm 已调整 `ReaderScreen`，只有存在真实正文快照时才进入 `CanvasRenderer`，避免初始加载阶段渲染 `1/1 0.0%` 的空章节。
- loading spinner 已限制为初始无内容状态；章节切换或重排期间如果已有正文，继续保留当前阅读面，不再用全屏 loading 覆盖。
- 后续回归仍需按原证据路径用 `screenrecord + ffmpeg 抽帧` 验证是否还存在白屏/空页帧。

### 2026-04-26 11:12-11:14 补充测试：`txt_converted` TXT 阅读器对照

本轮进入 `txt_converted` 文件夹，打开 `overlord_` TXT 书籍，进入阅读器后立即连续快速上滑。由于脚本变量名误用了 PowerShell 内置 `$PID`，summary 中 `Pid` 列显示的是宿主 PowerShell PID，实际 app PID 通过 `pidof` 确认为 `22363`。

证据目录：

- `test-artifacts/continued/rotation-20260426-continue/txt-visible-20260426-111226/`

已覆盖样本：

```text
1 overlord_00 Prologue        -> 第1节，9/25 1.6%
2 overlord_01 不死者之王      -> 前言，9/24 3.4%
3 overlord_03 鲜血的战争      -> 第一章 捕食者集团，9/159 9.6%
4 overlord_04 蜥蜴人勇者们    -> 前言，6/50 1.1%
5 overlord_01 不死者之王      -> 前言，9/24 3.4%（重复可见节点）
6 overlord_03 鲜血的战争      -> 第一章 捕食者集团，9/159 9.6%（重复可见节点）
7 overlord_04 蜥蜴人勇者们    -> 第一章 启程，2/133 9.2%
```

观测结果：

- 这 7 次 TXT 打开 + 快速上滑没有出现 `FATAL EXCEPTION`、`ConcurrentModificationException`、退桌面或 PID 消失。
- TXT 快速上滑能推进页码，且 `overlord_04 蜥蜴人勇者们` 成功从 `前言` 推进到下一章 `第一章 启程`。
- 日志出现跨章提交：

```text
Reader  : Commit next chapter from fillPage boundary: 1
Reader  : Loaded chapter 1: 第一章　启程
```

仍需注意：

- 跨章提交之后仍出现一次：

```text
Reader  : keyTurnPage(PREV) rejected at display=0
```

- 这说明 TXT 的整体稳定性明显好于 EPUB，但跨章/边界后仍可能有一次方向或页窗口状态错误，需要和 EPUB 的方向修复一起回归。
- 文件夹页可见节点会重复出现同一本 TXT，测试脚本后续需要改成按数据库 id 或书名去重，避免重复打开同一本。

证据文件：

- `test-artifacts/continued/rotation-20260426-continue/txt-visible-20260426-111226/summary.tsv`
- `test-artifacts/continued/rotation-20260426-continue/txt-visible-20260426-111226/batch-logcat.txt`
- `test-artifacts/continued/rotation-20260426-continue/txt-visible-20260426-111226/001-after.xml` 到 `007-after.xml`
- `test-artifacts/continued/rotation-20260426-continue/txt-visible-20260426-111226/001-after.png` 到 `007-after.png`

### 2026-04-26 11:15-11:30 补充测试：发现页书源搜索与结果列表

本轮进入“发现”页，搜索关键词 `Overlord`，用于覆盖用户要求的“搜索书源、点击每一本书暴露问题”路径。该路径目前还没有进入逐个远程结果点击阶段，因为搜索状态和结果列表本身已暴露可达性问题。

证据目录：

- `test-artifacts/continued/source-search-20260426-1115/`
- `test-artifacts/continued/source-search-20260426-1130/`

#### 已确认问题 12：发现页书源搜索卡在接近完成状态，远程结果不可稳定访问

实测状态：

```text
正在检索书源…
找到 48 条 · 失败 34 个
71/72
98%
```

观察点：

- 等待 15 秒后仍停留在 `71/72`、`98%`，页面继续显示“正在检索书源…”，没有切到明确完成态。
- 失败数达到 `34`，但失败详情没有直接暴露为可读、可定位的错误列表，无法判断是书源失效、网络超时、解析失败还是并发取消。
- 可见结果区域首先只出现本地 `书架 (35)` 结果，例如 `overlord_04 蜥蜴人勇者们`、`overlord_03 鲜血的战争`，远程书源结果在当前可见区域内不可达。
- 展开书源区域后，页面显示大量书源 chip，并出现 `+60`，但连续滑动后 UI tree 基本不变，远程结果列表仍没有稳定露出。
- 部分书源名称以 HTML 实体原样展示，例如 `&#127991;晋江文学`、`&#128304;悸花乐读`、`&#127800;七猫`，说明书源名称渲染没有进行实体解码。
- 当前阶段未捕获到 `FATAL EXCEPTION` 或主进程退出，但该搜索页状态会阻断“逐个点击远程结果”的后续压力测试。

证据文件：

- `test-artifacts/continued/source-search-20260426-1115/03-results.xml`
- `test-artifacts/continued/source-search-20260426-1115/04-results-wait15.xml`
- `test-artifacts/continued/source-search-20260426-1115/04-results-wait15.png`
- `test-artifacts/continued/source-search-20260426-1115/05-expanded.xml`
- `test-artifacts/continued/source-search-20260426-1115/06-scroll-0.xml` 到 `06-scroll-3.xml`
- `test-artifacts/continued/source-search-20260426-1130/00-current.xml`
- `test-artifacts/continued/source-search-20260426-1130/00-current.png`
- `test-artifacts/continued/source-search-20260426-1130/00-current-logcat.txt`

当前判断：

- 发现页搜索需要明确“完成/部分完成/失败”三种状态，不能长期停留在接近完成的 loading 文案。
- 书源 chip/失败面板和搜索结果列表需要独立可滚动或正确参与同一个滚动容器，否则上半屏展开区域会压缩或遮挡结果列表。
- 本地书架结果和远程书源结果需要有清晰分区、可达滚动和可点击验证路径；否则无法完成“点击每一本搜索结果”的高强度测试。
- 书源名称应在入库或展示前解码 HTML entity，避免用户看到 `&#127800;` 这类原始编码。

后续验证要求：

1. 折叠书源面板后继续滚动，确认远程结果是否能露出。
2. 点击本地 `书架 (35)` 结果，确认是打开本地阅读器、详情页，还是触发重复加入书架。
3. 换中文关键词继续搜索，排除 `Overlord` 远程命中偏少导致的假阴性。
4. 捕获失败书源的具体异常摘要，至少区分超时、解析异常、HTTP 错误和空结果。

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado `SearchModel` 的 `withTimeout(30000L)`、`onSearchFinish/onSearchCancel` 收束语义，MoRealm 已补每书源 30s 超时、全局搜索 45s 收束，并在 `finally` 中把 `WAITING/SEARCHING` 源标记为失败，避免长期停在 `71/72 98%`。
- 对照 Legado `BookSourceType.default` 作为小说文本书源入口，发现页搜索现在只启用 `bookSourceType == 0` 的文本书源，非文本书源不再参与小说搜索。
- 书源名已在 ViewModel 映射阶段解码 HTML entity，进度卡展开态会显示失败摘要；有结果时进度卡压缩高度，减少固定占屏对远程结果列表的遮挡。

#### 11:23-11:32 追加验证：远程结果可见，但入口代价高且结果类型错误

补充操作：

- 在折叠书源面板后，对下方结果列表连续上滑。
- 先经过本地 `书架 (35)` 的 TXT/EPUB 结果，再继续滚动到底部远程结果。
- 点击第一个远程结果 `Overlord / 丸山くがね / 来源：&#127911;猫耳FM`。

补充结论：

- 折叠状态下结果列表可以滚动，能看到本地 `Overlord 10/09/08/07/06/05/04/03/01`、`OVERLORD(WEB版)`、`Overlord 12`、`Overlord Prologue` 等结果。
- 远程结果不是完全缺失，但需要连续滚过大量本地 `书架 (35)` 结果后才露出；顶部书源状态卡片仍固定占用大量屏幕。
- 点击远程 `猫耳FM` 结果后，没有进入详情确认页，而是直接加入书架并打开阅读器：

```text
Search  : Added to shelf: Overlord from 🎧猫耳FM
Reader  : Opened: Overlord (WEB)
Reader  : Fetched book info, tocUrl=https://www.missevan.com/dramaapi/getdrama?drama_id=11284
Reader  : Parsed 526 chapters
Reader  : Loaded chapter 0: 1
```

#### 已确认问题 13：远程书源结果直接打开后，阅读页显示音频 m3u8 URL 和 token

实际表现：

- 阅读器标题：`Overlord`
- 当前章标题：`25`
- 正文直接显示：

```text
https://www.missevan.com/x/sound/hls.m3u8?sound_id=641847&quality_id=128&expire_time=...&token=...
```

当前判断：

- `猫耳FM` 搜索结果更像音频/广播剧资源，不是小说正文资源，当前被当成 WEB 书加入书架并打开阅读器。
- 阅读页把资源 URL、过期时间和 token 当正文渲染，既不是可读内容，也暴露了临时访问参数。
- 对书源搜索来说，这不是单纯 UI 问题，而是书源类型过滤、详情确认、正文解析和阅读器兜底文案共同缺失。

证据文件：

- `test-artifacts/continued/source-search-20260426-1130/05-collapsed-result-scroll-more-14.xml`
- `test-artifacts/continued/source-search-20260426-1130/06-after-remote-result-tap-logcat.txt`
- `test-artifacts/continued/source-search-20260426-1130/07-after-remote-result-tap-wait.xml`
- `test-artifacts/continued/source-search-20260426-1130/07-after-remote-result-tap-wait.png`
- `test-artifacts/continued/source-search-20260426-1130/09-reader-center-menu.png`

修复方向：

- 书源搜索结果应先进入详情页，展示来源、类型、章节/目录状态和加入书架/开始阅读按钮，避免一点击就污染书架并打开无效阅读器。
- 对音频、漫画、图片集、外链等非文本资源进行类型过滤或使用独立打开方式，不应进入小说阅读器。
- 正文解析结果如果是 URL、m3u8、空文本或疑似 token 链接，应展示“该书源不是文本内容/解析失败”的错误态，不应把链接当正文排版。

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado `BookSourceType.default/audio/image/file/video` 的类型隔离，MoRealm 搜索结果新增 `sourceType`，`addToShelfAndRead()` 会阻断非文本结果进入小说阅读器。
- `ReaderViewModel.loadWebBookChapters()` 和 `loadWebChapterContent()` 已增加非文本书源兜底；即使旧书架里已有音频 WEB 书，也只显示“非文本内容”错误态，不再请求并排版 m3u8/token 链接。
- WEB 正文缓存读取和新解析结果都会经过媒体 URL/token 检测，疑似 `.m3u8`、`expire_time`、`token`、音视频链接不会写入章节缓存。

#### 已确认问题 14：WEB 书快速上滑跨章时仍会发生方向反跳

复现路径：

- 打开 `猫耳FM` 结果生成的 `Overlord (WEB)`。
- 当前页脚从 `1/1 0.2%` 开始，连续执行 60 次快速上滑。

实测结果：

- 没有捕获到 `FATAL EXCEPTION`，进程保持 `22363`。
- 阅读器从章节 `1` 快速推进到章节 `25`，页脚为 `1/1 4.8%`。
- 但全程只执行上滑前进，日志仍多次出现 `keyTurnPage(PREV) rejected at display=0`、`fillPage(PREV) rejected at display=0`。
- 更严重的是出现一次实际反向提交：

```text
Reader  : upContent(relativePosition=-1, resetPageOffset=false)
Reader  : Commit prev chapter from fillPage boundary: 23
Reader  : Loaded chapter 23: 24
Reader  : upContent(relativePosition=1, resetPageOffset=false)
Reader  : Commit next chapter from fillPage boundary: 24
Reader  : Loaded chapter 24: 25
```

当前判断：

- 快速上滑跨章在 WEB 书上没有崩溃，但相对页/方向状态仍会反跳。
- 这和前面 EPUB/TXT 中偶发的 `PREV rejected` 属于同一类边界状态问题：用户只做前进手势时，阅读器内部不应产生上一页/上一章方向动作。

证据文件：

- `test-artifacts/continued/source-search-20260426-1130/08-remote-reader-rapid-scroll.xml`
- `test-artifacts/continued/source-search-20260426-1130/08-remote-reader-rapid-scroll.png`
- `test-artifacts/continued/source-search-20260426-1130/08-remote-reader-rapid-scroll-logcat.txt`
- `test-artifacts/continued/source-search-20260426-1130/08-remote-reader-rapid-scroll-pid.txt`

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado `PageDelegate -> PageFactory.moveToNext/moveToPrev` 的明确方向模型，MoRealm 已让 `CanvasRenderer` 在点击、竖向上滑、音量键、自动翻页等主动翻页入口记录 `pendingSettledDirection`，`onPageSettled` 优先使用该方向，不再仅靠 settled 页码大小反推 `PREV/NEXT`。
- 由页窗口 offset 调整、进度恢复或主题重排触发的 `scrollToPage()` 会标记为忽略的 settled 事件，并同步 `lastSettledDisplayPage`，避免主题切换或边界窗口变化被误判为上一章提交。
- 非主动翻页的异常大跨度 settled 事件只同步可见页，不调用 `fillPage(PREV/NEXT)`，降低快速重排期间出现 `Commit prev chapter` 的风险。

#### 已确认问题 15：阅读器菜单/设置面板可打开，但存在可访问性缺口和重排卡顿信号

已验证：

- 正文页中间点按能打开菜单。
- topbar 显示书名 `Overlord`，返回、书签、更多按钮可见。
- bottombar 显示 `25 · 4.8%`，`上一章` 和 `下一章` 可点击。
- `下一章` 能从 `25 · 4.8%` 跳到 `26 · 4.9%`，再点 `上一章` 能回到 `25 · 4.8%`。
- 右下角 `Tt` 能打开“阅读样式”面板，包含 `纸质/护眼/海蓝/暖黄/墨白`、亮度、字号、字体等设置。
- 快速切换 5 个阅读样式 8 轮后，本轮未崩溃，进程仍存在。

问题点：

- 菜单中多个图标按钮在 UI tree 中只有 `clickable=true` bounds，没有文本或 content-desc；自动化和无障碍侧很难判断按钮含义。
- 打开设置面板时出现 `Skipped 31 frames`，快速切换主题时也触发大量 `upContent(relativePosition=...)` 和 `fillPage(PREV) rejected` 日志。
- 这说明主题切换当前会引发阅读器内容层频繁重排/相对页更新；虽然本轮没有崩溃，但仍可能和用户看到的闪烁、快速切换卡顿相关。
- 退出设置面板后，阅读器从切换前的 `25 · 4.8%` 变成 `24 · 4.6%`；快速切主题期间没有手动翻页，但阅读位置实际后退了一章。
- 从该阅读器按 Back 第 1 次回到发现搜索页，再按 Back 会回到 LDPlayer 桌面，`com.morealm.app.debug` 进程仍存在；这次不是进程崩溃，但仍属于用户看到的“退到桌面”路径，需要和任务栈修复一起回归。

关键日志：

```text
Reader  : Loaded chapter 23: 24
Reader  : fillPage(PREV) rejected at display=0
Reader  : upContent(relativePosition=-1, resetPageOffset=false)
Reader  : upContent(relativePosition=1, resetPageOffset=false)
CoreService: notifyClosing notify tab closed taskPackageName:com.morealm.app.debug
```

证据文件：

- `test-artifacts/continued/source-search-20260426-1130/09-reader-center-menu.xml`
- `test-artifacts/continued/source-search-20260426-1130/09-reader-center-menu.png`
- `test-artifacts/continued/source-search-20260426-1130/10-reader-menu-next-chapter.xml`
- `test-artifacts/continued/source-search-20260426-1130/11-reader-menu-prev-chapter.xml`
- `test-artifacts/continued/source-search-20260426-1130/12-reader-settings-panel.xml`
- `test-artifacts/continued/source-search-20260426-1130/12-reader-settings-panel.png`
- `test-artifacts/continued/source-search-20260426-1130/13-reader-theme-fast-switch.xml`
- `test-artifacts/continued/source-search-20260426-1130/13-reader-theme-fast-switch-logcat.txt`
- `test-artifacts/continued/source-search-20260426-1130/14-back-exit-step-0.xml`
- `test-artifacts/continued/source-search-20260426-1130/14-back-exit-step-1.xml`
- `test-artifacts/continued/source-search-20260426-1130/14-back-exit-step-2.xml`
- `test-artifacts/continued/source-search-20260426-1130/14-back-exit-logcat.txt`

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 阅读器 topbar/bottombar 的图标按钮已补 `contentDescription` 和 `Role.Button` 语义，阅读样式、主题色块也补了可访问标签，便于 UIAutomator 和无障碍服务识别。
- 主题快速切换导致的“无手动翻页却后退一章”已归入问题 14 的方向状态修复：重排触发的 settled 事件不再提交 `PREV`。
- Back 回桌面路径已有前一轮 `singleTask`、`alwaysRetainTaskState`、`safePopBackStackOrHome()` 修复，本条仍需 LDPlayer 重跑“搜索页 -> WEB 阅读器 -> 菜单/设置 -> Back 两次”确认。

## 2026-04-26 LDPlayer 续测补充：启动恢复与左右翻页校准

### 补充问题 16：从桌面启动后短时黑屏/空 UI tree，启动恢复耗时过长

复现路径：

- 从 LDPlayer 桌面启动 `com.morealm.app.debug/com.morealm.app.ui.navigation.MainActivity`。
- 启动后约 8 秒内抓取 UI tree 和截图。
- 继续等待后，书架才恢复可见。

实测表现：

- `uiautomator dump` 返回 `ERROR: null root node returned by UiTestAutomationBridge`。
- 截图为黑屏，仅状态栏可见。
- logcat 显示 Activity 最终 `Displayed ... +11s394ms`，中间伴随 `Skipped 75 frames`、`Skipped 325 frames`、`Skipped 50 frames` 和 `Launch timeout has expired, giving up wake lock!`。
- 启动阶段同时出现 EPUB 解析日志，说明恢复/解析工作仍可能阻塞首屏。

当前判断：

- 这不是阅读器正文内的翻页问题，但会影响“反复进入新书然后退出”的压力测试：用户可能看到黑屏或误以为闪退。
- 该现象需要和后续“打开书本后立即快速滚动再退出”的测试一起回归，确认是否由启动恢复、书籍解析或 reader state restore 引起。

证据文件：

- `test-artifacts/continued/resume-20260426-1148/`

修复状态：**2026-04-26 当前 debug APK 回归失败，保留问题；本轮继续改进启动关键路径，待 LDPlayer 复测**。

- 对照 Legado 首屏先恢复主界面、重解析任务不抢占启动路径的行为，`ShelfViewModel` 已把 EPUB/PDF 失效封面刷新从启动关键路径延后 8 秒，并限制单次最多处理 12 本，避免启动阶段集中解析 EPUB。
- `ShelfScreen` 的“继续阅读/恢复上次阅读”现在等书架数据已加载并至少绘制一帧后再延迟跳转阅读器，避免桌面启动后立即进入重解析阅读页导致黑屏或空 UI tree。
- `ReaderScreen` 根节点补了稳定语义 `阅读器`，用于降低快速启动/切换期间 UIAutomator 无可读节点的概率；仍需用 `resume-20260426-1148` 路径重跑启动 0-12 秒抓图和 UI tree。
- 2026-04-26 当前 debug APK 复测仍失败：桌面冷启动后约 2 秒、5 秒 UIAutomator 均返回 `null root`，约 8 秒才看到书架；logcat 仍有 `Launch timeout has expired`、`Displayed ... +11s351ms` 和多次 skipped frames。
- 本轮新证据：`test-artifacts/continued/retest-fixed-20260426-1330/01-start-2s.xml`、`01-start-5s.xml`、`01-start-8s.xml`、`01-start-logcat.txt`。
- 新证据根目录：`test-artifacts/continued/retest-fixed-20260426-1330/`。
- 本轮继续对照 Legado 首屏只绘制当前阅读/主界面、不预载无关页的做法，`MoRealmNavHost` 已把主 tab 缓存从“启动即组合书架/发现/听书/我的全部页面”改成“只组合当前书架 tab，点击或横向切换时再懒加载目标 tab”。
- `ShelfViewModel` 的书籍排序、分组名、文件夹计数和封面 URL 派生已移到 `Dispatchers.Default`，避免大书架列表在主线程完成排序/分组。

### 补充记录：纵向滑动压力测试不等价于左右翻页测试

前一轮在 `txt_converted` 的可见 6 本 TXT 上执行过纵向快速滑动压力测试，结果是进程稳定、未回桌面，页码能推进：

```text
1 classroom_欢迎来到实力至上主义的教室 00      -> 前言，8/486 0.8%
2 classroom_欢迎来到实力至上主义的教室 1       -> 第1节，13/31 1.1%
3 classroom_欢迎来到实力至上主义的教室 2       -> 第1节，11/29 1.0%
4 classroom_欢迎来到实力至上主义的教室 3 (1)   -> 第1节，13/29 1.1%
5 imouto_如果有妹妹就好了 1                    -> 第1节，13/32 1.6%
6 imouto_如果有妹妹就好了 10                   -> 前言，13/114 0.7%
```

但用户确认当前阅读器主要翻页方式是左右滑动，因此上述结果只能作为纵向拖动/压力输入证据，不能证明左右翻页模式已经完整稳定。

证据文件：

- `test-artifacts/continued/rotation-20260426-1216/`

### 补充记录：TXT 左右滑动翻页批次基本稳定，但仍出现一次空 UI tree

复现路径：

- 进入 `txt_converted`。
- 依次打开当前可见 6 本 TXT。
- 每本书执行 20 次左滑前进：`input swipe 900 960 160 960 90`。
- 再执行 8 次右滑后退：`input swipe 160 960 900 960 90`。
- 每本书返回文件夹后换下一本。

实测结果：

```text
1 txt_visible_1: open 前言 5/486 0.5% -> left 18/486 1.9% -> right 10/486 1.0%
2 txt_visible_2: open 第1节 6/31 0.5% -> left 21/31 1.7% -> right 15/31 1.2%
3 txt_visible_3: open 第1节 10/29 0.9% -> left 22/29 1.9% -> right 14/29 1.2%
4 txt_visible_4: open dump null-root -> left 第1节 25/29 2.1% -> right 18/29 1.5%
5 txt_visible_5: open 第1节 12/32 1.4% -> left 24/32 2.9% -> right 18/32 2.2%
6 txt_visible_6: open 前言 12/114 0.6% -> left 25/114 1.3% -> right 19/114 1.0%
```

当前判断：

- 在这 6 本 TXT 上，左右滑动方向与页码变化整体一致：左滑推进，右滑回退。
- 本批次没有复现崩溃、退桌面或 PID 消失。
- 第 4 本打开后立即抓取 UI tree 时出现 `null-root`，随后继续滑动又恢复可读，说明“打开后立即操作/抓取”仍存在短时 UI 不稳定窗口。
- 本批次的 logcat 中出现 `upContent(relativePosition=-1)` 不能单独判定为 bug，因为测试流程里确实包含右滑后退；后续需要在“只左滑前进”的批次里单独检查是否仍出现非预期 `PREV`。

证据文件：

- `test-artifacts/continued/horizontal-page-20260426-1222/horizontal-visible-txt-summary.tsv`
- `test-artifacts/continued/horizontal-page-20260426-1222/`

### 补充问题 17：EPUB 左右滑动跨章基本可用，但快速回退中仍出现空 UI tree

复现路径：

- 打开 `overlord1-14/Overlord 01 不死者之王`。
- 初始阅读页为 `《OVERLORD 第一卷 不死者之王》 2/5 3.6%`。
- 执行 20 次左滑前进，再执行 8 次右滑回退。

实测状态序列：

```text
left-1   《OVERLORD 第一卷 不死者之王》 | 3/5    5.5%
left-5   Prologue                     | 3/12   11.4%
left-10  Prologue                     | 8/12   15.2%
left-15  Prologue                     | 10/12  16.7%
left-20  第一章 开始与结束             | 3/188  18.3%
right-1  第一章 开始与结束             | 2/188  18.3%
right-4  uiautomator null-root
right-8  Prologue                     | 9/12   15.9%
```

当前判断：

- EPUB 的左右滑动方向整体正确：左滑能从封面跨到 `Prologue` 和 `第一章`，右滑能回到上一章。
- 本批次没有崩溃、没有退桌面，PID 保持 `26590`。
- `right-4` 抓取 UI tree 时返回 `ERROR: null root node returned by UiTestAutomationBridge`，说明快速翻页/跨章回退期间仍有短时 UI 不稳定窗口。
- logcat 中左滑阶段为 `upContent(relativePosition=1)` 和 `Commit next chapter`，右滑阶段为 `upContent(relativePosition=-1)` 和 `Commit prev chapter`；这批次的方向日志与实际左右手势一致，不能当作反向误判。

证据文件：

- `test-artifacts/continued/horizontal-epub-20260426-1228/epub-horizontal-summary.tsv`
- `test-artifacts/continued/horizontal-epub-20260426-1228/right-04.xml`
- `test-artifacts/continued/horizontal-epub-20260426-1228/right-08-logcat.txt`

修复状态：**已改进代码路径，待 LDPlayer 回归验证**。

- EPUB 左右方向本轮实测已基本正确；代码侧补充了阅读器根语义和问题 21 的页状态稳定修复，减少跨章回退时 Compose 可访问树短时丢失的触发面。
- 该问题没有新的崩溃点，回归重点是 `right-4` 快速回退抓取时是否还出现 `null-root`。

### 补充问题 19：`仿真` 和 `无动画` 模式左滑后出现短时 null-root

复现路径：

- 在同一本 EPUB 中依次切换 `仿真`、`无动画`。
- 每次关闭设置面板后执行 6 次左滑，再执行 3 次右滑。

实测结果：

```text
仿真：
pre          Prologue 9/12 15.9%
afterSwitch  Prologue 9/12 15.9%
afterLeft6   uiautomator null-root
afterRight3  Prologue 12/12 18.2%

无动画：
pre          Prologue 12/12 18.2%
afterSwitch  Prologue 12/12 18.2%
afterLeft6   uiautomator null-root
afterRight3  第一章 开始与结束 3/188 18.3%
```

当前判断：

- 本批次未崩溃，PID 保持 `26590`。
- `仿真` 和 `无动画` 在左滑后均出现 `ERROR: null root node returned by UiTestAutomationBridge`，随后继续右滑能恢复可见内容。
- 这类空 UI tree 与启动黑屏、打开书后立即抓取空根、快速回退空根现象一致：不一定是进程崩溃，但代表快速交互期间 Compose/窗口可访问树短时不可用，用户侧可能表现为闪烁、白帧、黑帧或“点了没反应”。

证据文件：

- `test-artifacts/continued/page-mode-switch-20260426-1230/07-simulation-after-left6.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/08-none-after-left6.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/07-simulation-logcat.txt`
- `test-artifacts/continued/page-mode-switch-20260426-1230/08-none-logcat.txt`

修复状态：**已改进代码路径，待 LDPlayer 回归验证**。

- `ReaderScreen` 已补稳定根语义，`CanvasRenderer` 已减少动画切换导致的页状态重建；这两处主要缓解 `仿真/无动画` 快速滑动后的短时 UI tree 空根。
- 该问题仍需以 `07-simulation-after-left6.xml`、`08-none-after-left6.xml` 同路径复测确认，因为空根可能还受 LDPlayer UIAutomation 采样时机影响。

### 补充问题 20：`上下滚动` 动画模式下，纯横向滑动仍会推进页码

复现路径：

- 切换 `翻页动画 -> 上下滚动`。
- 先执行 4 次纯横向左滑。
- 再执行 6 次纵向上滑、3 次纵向下滑。

实测结果：

```text
pre               第一章 开始与结束 3/188  18.3%
afterSwitch       第一章 开始与结束 3/188  18.3%
horizontal-left4  第一章 开始与结束 7/188  18.5%
vertical-up6      第一章 开始与结束 13/188 18.8%
vertical-down3    第一章 开始与结束 10/188 18.7%
```

当前判断：

- `上下滚动` 模式下纵向拖动能推进/回退内容，这是符合滚动模式预期的。
- 但纯横向左滑也能从 `3/188` 推到 `7/188`，说明滚动模式和左右翻页模式之间的手势边界不清。
- 如果设计目标是 Legado 风格的滚动阅读，进入 `SCROLL` 后横向翻页手势不应继续像左右翻页模式一样推进页码；否则用户在横向误触时仍会触发翻页。

证据文件：

- `test-artifacts/continued/page-mode-switch-20260426-1230/09-vertical-scroll-after-horizontal-left4.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/09-vertical-scroll-after-vertical-up6.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/09-vertical-scroll-after-vertical-down3.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/09-vertical-scroll-logcat.txt`

修复状态：**2026-04-26 当前 debug APK 回归失败，保留问题；本轮已按 Legado `ScrollPageDelegate` 二次修复代码路径，待 LDPlayer 复测**。

- 对照 Legado `ScrollPageDelegate.onScroll()` 只处理纵向主轴移动的行为，`ScrollRenderer` 已从 `detectVerticalDragGestures` 改为主轴判定的 `detectDragGestures`。
- 横向位移占优时会消费本次拖动但不调用 `applyScroll()`，既不改变 `pageOffset`，也不把纯横向手势继续交给外层主 tab/分页手势；`上下滚动` 模式下纯横向左滑不应再推进页码。
- 纵向拖动仍按 Legado `ContentTextView.scroll()` 的 `pageOffset` 语义推进/回退。
- 2026-04-26 当前 debug APK 复测仍失败：切到 `上下滚动` 后，纯横向左滑 4 次从 `第一章 捕食者集团 6/161 10.4%` 推进到 `10/161 10.6%`。
- 本轮新证据：`test-artifacts/continued/retest-fixed-20260426-continue/08-scroll-mode-before-horizontal.xml`、`08-scroll-mode-after-horizontal4.xml`、`08-scroll-mode-after-horizontal4.png`。
- 新证据根目录：`test-artifacts/continued/retest-fixed-20260426-continue/`。

### 补充记录：快速切换 5 种翻页动画 6 轮未崩溃，菜单层仍可打开

测试路径：

- 当前页：`第一章 开始与结束 11/188 18.7%`。
- 打开 `阅读设置 -> 翻页动画`。
- 连续 6 轮快速点击 `平移 -> 覆盖 -> 仿真 -> 上下滚动 -> 无动画`。
- 退出设置面板，重新点按中间区域打开阅读器菜单。

实测结果：

- 快速切换后仍停留在 `第一章 开始与结束 11/188 18.7%`。
- PID 保持 `26590`，本轮未崩溃、未退桌面。
- 中间菜单可打开，topbar 显示 `Overlord 01 不死者之王`，bottombar 显示 `第一章 开始与结束 · 18.7%`。
- 当前 APK 的 UI tree 中，topbar/bottombar 图标已能看到 `返回书架`、`添加书签`、`更多操作`、`目录`、`全文搜索`、`朗读`、`自动翻页`、`阅读设置` 等 `content-desc`，这点比前一轮菜单可访问性记录有改善；但仍需以重新打包后的目标 APK 做最终回归确认。

证据文件：

- `test-artifacts/continued/page-mode-switch-20260426-1230/11-rapid-mode-switch-reader-pre.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/11-rapid-mode-switch-settings-before.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/11-rapid-mode-switch-settings-after.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/11-rapid-mode-switch-reader-after.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/12-menu-after-mode-stress.xml`
- `test-artifacts/continued/page-mode-switch-20260426-1230/11-rapid-mode-switch-logcat.txt`

### 补充问题 21：换新 EPUB 后“只左滑前进”仍会夹入反向 `relativePosition=-1`

复现路径：

- 从 `Overlord 01 不死者之王` 退出到 `overlord1-14` 列表。
- 打开新书 `Overlord 03 鲜血的战争少女`。
- 打开后约 850ms 即开始快速左滑 12 次。
- 再右滑 4 次并立即 Back 退出。

实测状态：

```text
quick-open       《OVERLORD 第三卷 鲜血的战争少女》 | 2/5   4.0%
after-left12     第一章 捕食者集团                  | 6/161 10.4%
after-right4     第一章 捕食者集团                  | 3/161 10.2%
after-back       回到 overlord1-14 列表
PID              26590
```

关键日志：

```text
Reader: upContent(relativePosition=1, resetPageOffset=false)
Reader: upContent(relativePosition=1, resetPageOffset=false)
Reader: upContent(relativePosition=-1, resetPageOffset=false)
Reader: upContent(relativePosition=1, resetPageOffset=false)
Reader: Commit next chapter from fillPage boundary: 1
Reader: Loaded chapter 1: 第一章 捕食者集团
Reader: upContent(relativePosition=1, resetPageOffset=false)
Reader: upContent(relativePosition=-1, resetPageOffset=false)
Reader: upContent(relativePosition=1, resetPageOffset=false)
```

当前判断：

- `relativePosition=-1` 出现在 12 次左滑前进阶段内，早于后续 4 次右滑，因此这次不能解释为用户主动回退。
- UI 最终仍推进到第一章，未崩溃，但内部 settled/方向状态在快速左滑中发生反跳。
- 这与前面 WEB/TXT/EPUB 边界测试里看到的 `PREV` 误触发属于同类问题，需要继续回归“只前进输入不产生 PREV/relativePosition=-1”。

证据文件：

- `test-artifacts/continued/new-epub-after-mode-20260426-1241/03-overlord03-quick-open.xml`
- `test-artifacts/continued/new-epub-after-mode-20260426-1241/04-overlord03-after-fast-left12.xml`
- `test-artifacts/continued/new-epub-after-mode-20260426-1241/05-overlord03-after-fast-right4.xml`
- `test-artifacts/continued/new-epub-after-mode-20260426-1241/06-overlord03-after-immediate-back.xml`
- `test-artifacts/continued/new-epub-after-mode-20260426-1241/06-overlord03-logcat.txt`

修复状态：**2026-04-26 当前 debug APK 回归失败，保留问题；本轮已按 Legado `PageDelegate -> PageFactory` 二次修复代码路径，待 LDPlayer 复测**。

- 对照 Legado `HorizontalPageDelegate` 在拖动开始即固定 `PREV/NEXT`、再由 `PageFactory.moveToNext/moveToPrev` 提供相对页窗口的做法，MoRealm 已禁用 Compose Pager 的用户滚动，避免 `pagerState.targetPage` 和 Compose settle 过程反向推断方向。
- `CanvasRenderer` 现在只在自有手势识别完成后写入 `pendingSettledDirection` 并执行一次程序化 `animateScrollToPage()`；`onPageSettled` 只接受这个显式方向，不再用页码差兜底生成 `PREV`。
- 没有显式方向的 settled 事件只执行 `upContent()` 同步可见页和进度，不调用 `fillPage(PREV/NEXT)`，用于避免“只左滑前进”阶段夹入非用户输入的 `relativePosition=-1`。
- 仍需重跑 `Overlord 03` 打开后 850ms 左滑 12 次，确认左滑阶段 logcat 不再出现非预期 `upContent(relativePosition=-1)`。
- 2026-04-26 当前 debug APK 复测仍失败：`Overlord 03 鲜血的战争少女` 打开后快速左滑 12 次，最终可见进度到 `第一章 捕食者集团 6/161 10.4%`，但左滑阶段 logcat 仍多次出现 `upContent(relativePosition=-1, resetPageOffset=false)`。
- 本轮新证据：`test-artifacts/continued/retest-fixed-20260426-continue/04-overlord03-quick-open.xml`、`04-overlord03-after-left12.xml`、`04-overlord03-left12-logcat.txt`。
- 新证据根目录：`test-artifacts/continued/retest-fixed-20260426-continue/`。

### 补充问题 23：书源搜索失败率高，失败面板会挤占结果列表

复现路径：

- 进入 `发现` 页。
- 使用 `overlord` 搜索，再使用 `love` 搜索以产生足够多的在线书源结果。

实测表现：

```text
overlord:
18 秒后：找到 37 条 · 失败 26 个，59/64
完成后：找到 37 条 · 失败 30 个，64/64
结果构成：书架 (36)，在线结果不足，不能满足 10 条在线书源点击。

love:
25 秒后：找到 96 条 · 失败 28 个，62/64
完成后：找到 96 条 · 失败 30 个，64/64
重跑后：找到 123 条 · 失败 29 个，64/64
```

高频失败类型：

```text
晋江文学: org.jsoup.nodes.Element cannot be cast to java.lang.String
悸花乐读: org.mozilla.javascript.EcmaError: TypeError
mjj: TypeError: 无法读取 null 的属性 “1”
七猫/小说路上/起舞中文: analyzeRule TypeError
红袖招: Failed to connect to hongxiub.com/23.224.228.84:443
番茄小说: json string can not be null or empty
玫瑰小说: Unable to resolve host
```

UI 问题：

- 搜索进度卡片的失败来源标签非常多，`展开/收起` 的视觉语义容易混乱。
- `展开` 状态下才比较容易看到结果列表；`收起` 状态仍会展示大量失败标签，常规滑动会优先停留在失败列表区域，用户很难直接到达在线结果。
- 这是书源搜索的可用性问题：搜索完成后应该优先让用户看到“在线结果”，失败详情应该折叠到二级区域。

证据文件：

- `test-artifacts/continued/source-books-20260426-qa10/03-search-overlord-after18s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/04-after-toggle-failures.png`
- `test-artifacts/continued/source-books-20260426-qa10/12-overlord-complete-expanded.xml`
- `test-artifacts/continued/source-books-20260426-qa10/14-search-love-complete.xml`
- `test-artifacts/continued/source-books-20260426-qa10/20-love-rerun-expanded.xml`

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado `SearchModel` 的在线结果流合并语义，MoRealm `SearchScreen` 已改为优先展示“在线”分组，再展示本地书架结果，避免本地书架大量命中时挤掉在线结果。
- 搜索进度卡片已改为完成且已有结果时保持紧凑：失败明细默认折叠，只显示“失败详情已折叠，展开后查看”；展开后才展示有限高度的失败来源列表。
- 失败来源 chip 不再拼接长错误栈，避免 `SelectorParseException`、`UnknownHost` 等错误文本占满列表首屏。

### 补充问题 24：在线书源搜索结果点击 10 本，只有 2 本完整进入可读正文

测试路径：

- 关键词：`love`。
- 结果分组：`在线 (96)`，重跑后总结果增加到 `123`。
- 点击方式：点击在线结果卡片左侧正文区域，避开右侧下载按钮。
- 每次点击后等待约 9-10 秒，抓 UI tree、截图和 logcat，再 Back 返回搜索页继续下一条。

有效点击结果：

```text
01 love / 素橘 / 长佩文学
   结果：黑屏，无文本；Back 返回搜索。
   日志：Added to shelf -> Opened WEB -> Fetched tocUrl -> No chapters found。

02 love / 第一次写文好紧张(苍蝇搓手) / 长佩文学
   结果：黑屏，无文本。
   日志：No chapters found。

03 love is love / 小俞 / 长佩文学
   结果：黑屏，无文本。
   日志：No chapters found。

04 欢娱场·love nest / 弥胧 / 豆瓣阅读
   结果：黑屏/无正文。
   日志：Failed to load book；SelectorParseException: Could not parse query '$.list[*]'。

05 【柱斑向/宇智波斑生贺】I love you so / 生物 / Lofter
   结果：进入阅读器 1/1，但正文加载失败。
   日志：Parsed 1 chapters；Failed to load chapter 0；startBrowser not found。

06 [网王]for love / 拿铁不加冰 / 腐小说
   结果：成功进入阅读器，显示 1/28。
   日志：Parsed 23 chapters；Loaded chapter 0 第1页。

07 [综]妈妈love you! / 蝎言蝎语 / 四零二零
   结果：进入阅读器但正文 URL 错。
   日志：Parsed 221 chapters；UnknownHostException: 17488649.html。

08 LOVE / 凌星玥 / 长佩文学
   结果：黑屏，无文本。
   日志：No chapters found。

09 【milklove】差等生（纯百） / 气氛为负 / 若晨文学
   结果：成功进入阅读器，显示 1/24。
   日志：Parsed 39 chapters；Loaded chapter 0。

10 纯恋love史 / 晴泡泡 / 四零二零
   结果：进入阅读器但正文 URL 错。
   日志：Parsed 40 chapters；UnknownHostException: 4429739.html。
```

当前判断：

- 10 本在线书源结果中，只有 2 本在 10 秒内完整进入可读正文。
- 3 本长佩文学结果会被加入书架并打开 WEB 阅读器，但没有章节，随后阅读器黑屏且 UI tree 无文本。
- 豆瓣阅读、Lofter、四零二零等来源能进入不同阶段，但规则兼容性不足：JSONPath 被当 CSS selector、缺 `startBrowser()` 扩展函数、相对章节 URL 被当 hostname。
- 这些失败不应直接落入黑屏阅读器；至少应该在阅读器或详情页显示“章节为空/正文加载失败/书源规则错误”，并提供返回或换源入口。

证据文件：

- `test-artifacts/continued/source-books-20260426-qa10/source-online-click10-summary-clean.tsv`
- `test-artifacts/continued/source-books-20260426-qa10/15-click01-after16s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click02-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click03-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click04-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click05-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click06-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click07-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click08-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/22-click09-valid-after10s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/25-click10-valid-after10s.xml`

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- 对照 Legado `ReadBookViewModel.loadBookInfo/loadChapterListAwait` 失败时 `ReadBook.upMsg(...)` 的处理方式，MoRealm 不再让 WEB 书源的目录解析失败、章节为空直接落入空白阅读器。
- `ReaderViewModel.loadBook()` 对 WEB 书源目录加载异常会发布“书源加载失败”错误页；目录为空会发布“书源无章节”错误页，正文区域可见具体书名、来源和原因。
- `ReaderViewModel.loadChapter()` 对正文加载失败会同步更新 `chapterContent` 和 `renderedChapter`，避免保留空内容或上一章内容导致用户看到黑屏/假成功。

### 补充问题 25：书源点击失败后，阅读器黑屏但进程仍存活

复现路径：

- 在 `love` 在线结果中点击长佩文学结果，例如 `love / 素橘`、`love is love / 小俞`、`LOVE / 凌星玥`。

实测表现：

- logcat 显示已 `Added to shelf`，随后 `Reader: Opened ... (WEB)`。
- `Fetched book info` 能拿到 `tocUrl=https://webapi.gongzicp.com/novel/chapterGetList?...`。
- 随后 `No chapters found`。
- 9-16 秒后截图为黑屏，UI tree 无文本节点，PID 仍为 `26590`。
- 按 Back 可以回到搜索页。

当前判断：

- 这是“未崩溃但不可用”的书源加载失败：用户看到的是阅读器黑屏，而不是明确错误。
- 对照阅读器体验，WEB 书籍在章节为空时应显示明确错误页，而不是进入没有任何可访问文本的黑屏状态。

证据文件：

- `test-artifacts/continued/source-books-20260426-qa10/15-click01-after8s.png`
- `test-artifacts/continued/source-books-20260426-qa10/15-click01-after16s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click02-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click03-after9s.xml`
- `test-artifacts/continued/source-books-20260426-qa10/18-click08-after9s.xml`

修复状态：**已修复代码路径，待 LDPlayer 回归验证**。

- WEB 书源无章节时现在会构造一个不写入数据库的临时错误章节，并把它作为当前可渲染章节发布给 `CanvasRenderer`。
- 临时错误章节使用 `morealm:error:` URL 前缀和 `BookChapter.variable` 携带错误正文，后续从目录重新点入也会显示错误文本，而不是空 UI tree。
- 错误页保留阅读器 Back 语义，用户可以直接返回搜索页换源。

### 2026-04-26 14:18-14:45 补充测试：有进度书反向跨章、书源下载、净化规则与自定义主题

测试环境：

- 模拟器：LDPlayer / `emulator-5554`
- 测试包：`com.morealm.app.debug`
- 证据目录：`test-artifacts/continued/progress-source-theme-clean-20260426-141805/`

#### 补充问题 26：有进度 EPUB 反向跨章后退出重进，恢复位置会跳到上一章节 title 页并短时丢失 UI root

复现路径：

- 打开已有进度的 `Overlord 03 鲜血的战争少女`。
- 初始位置在 `第一章 捕食者集团`，约 `5/161 10.3%`。
- 普通分页模式下右滑上一页 5 次，同章内从 `5/161` 回到 `2/161`，退出重进后仍保持 `2/161 10.1%`，同章内恢复暂未复现明显错乱。
- 切到 `上下滚动` 后连续向上滑动 10 次，退出重进从 `12/161 10.7%` 回到 `11/161 10.7%`，存在 1 页漂移，但标题和章节未乱。
- 重点复现路径：从 `第一章 捕食者集团 11/161 10.7%` 连续右滑/上一页约 18 次，触发反向跨章，画面跳到上一章节/标题页 `《OVERLORD 第三卷 鲜血的战争少女》 1/5 2.0%`。退出后再进入同一本书，首次 UIAutomator dump 出现 `ERROR: null root node returned by UiTestAutomationBridge`，数秒后 UI tree 恢复，位置仍为标题页 `1/5 2.0%`。

logcat 关键线索：

- `upContent(relativePosition=-1, resetPageOffset=false)`
- `Commit prev chapter from fillPage boundary: 0`
- `Loaded chapter 0: 《OVERLORD 第三卷 鲜血的战争少女》`
- 退出重进后再次 `Opened: Overlord 03 鲜血的战争少女 (EPUB)` 并加载 chapter 0。

当前判断：

- 用户反馈的“有阅读进度时往上翻页，退出再进去直接乱套”在反向跨章压力路径下有支撑证据。
- 如果最后可见页确实已经进入上一章节标题页，那么持久化到 chapter 0 本身可能符合当前提交逻辑；但从用户角度看，阅读进度从 `第一章 11/161 10.7%` 突然回到标题页 `1/5 2.0%`，再叠加重进时 UI tree 短暂为空，体验上就是“位置乱套”。
- 后续需要继续拆分：确认边界页提交是否过早、上一章 title 页是否应作为可恢复进度、退出时保存的是动画中间态还是稳定态。

证据文件：

- `02-overlord03-open.xml` / `02-overlord03-open.png`
- `03-overlord03-after-prev5.xml` / `03-overlord03-after-prev5.png`
- `05-overlord03-reopen.xml` / `05-overlord03-reopen.png`
- `08-scrollmode-after-up10.xml` / `08-scrollmode-after-up10.png`
- `10-scrollmode-reopen.xml` / `10-scrollmode-reopen.png`
- `12-after-cross-prev18.xml` / `12-after-cross-prev18.png`
- `14-after-cross-prev-reopen.xml` / `14-after-cross-prev-reopen.png`
- `15-cross-prev-reopen-retry.xml` / `15-cross-prev-reopen-retry.png`
- `14-cross-prev-reopen-logcat.txt`

修复状态：**未修复，需继续定位持久化章节/页索引与跨章提交时机**。

#### 补充问题 27：书源名称 HTML 实体未解码，错误页原因文本横向截断

复现路径：

- 进入发现页搜索 `love`。
- 搜索结束后显示 `搜索完成`、`找到 126 条 · 失败 29 个`、`64/64`、`在线 (116)`。
- 点击第一条 `love / 9ci / 来源：📖Lofter` 的在线结果。

实测表现：

- 相比早先黑屏问题，本轮点击后会进入可见阅读器错误页，不再是纯黑屏。
- 错误页显示 `正文加载失败`、`书名：love`、`来源：📖Lofter`，进度为 `1/2 50.0%`。
- logcat 显示目录/正文加载链路失败原因是 Rhino JS 扩展缺失：`TypeError ... 找不到函数 startBrowser`。
- 搜索列表中的部分来源名仍直接显示 HTML numeric entity，例如 `来源：&#128214;Lofter`、`来源：&#128304;长佩文学`。
- 错误页的长异常原因在横向上被截断，截图中无法完整阅读；错误正文应换行、可滚动或可复制。

当前判断：

- `补充问题 25` 的“失败后黑屏”在该 Lofter 样本上已改善为可见错误页。
- 仍存在两个问题：书源兼容层缺少 `startBrowser()`，以及失败原因 UI 没有做长文本换行/布局适配。
- HTML 实体未解码属于搜索结果可见质量问题，会让用户误以为书源名乱码。

证据文件：

- `17-love-search-after55s.xml` / `17-love-search-after55s.png`
- `18-love-click01-after15s.xml` / `18-love-click01-after15s.png`
- `18-love-click01-logcat.txt`

修复状态：**未修复，需补书源 JS API 兼容与错误页长文本布局**。

#### 补充问题 28：在线结果缓存下载失败只在日志中可见，列表没有持久失败状态

复现路径：

- 在 `love` 搜索结果中点击第一条 Lofter 结果右侧缓存按钮。
- 再点击第二条 `love / 素橘 / 长佩文学` 右侧缓存按钮。

实测表现：

- 点击缓存按钮后列表停留在搜索页，只有短暂 toast，例如 `开始缓存: love`。
- Lofter 样本 logcat 显示：
  - `Already on shelf: love`
  - `CacheService: Download ch0 failed: ... startBrowser not found`
  - `CacheService: Download complete: 0 ok, 1 failed, 0 cached`
- 长佩文学样本 logcat 显示：
  - `Search: Already on shelf: love`
  - `CacheService: No chapters for book: ...`
- 搜索结果列表没有保留“缓存失败/无章节/书源不兼容”的可见状态，用户只能从日志知道失败。

当前判断：

- 下载/缓存链路没有崩溃，但失败反馈不足。
- 对阅读器完整移植而言，离线缓存是高频入口；至少应在结果项或缓存任务列表中保留终态，避免用户反复点击同一本失败源。

证据文件：

- `20-love-download01-after12s.xml` / `20-love-download01-after12s.png`
- `20-love-download01-logcat.txt`
- `21-love-download02-after18s.xml` / `21-love-download02-after18s.png`
- `21-love-download02-logcat.txt`

修复状态：**未修复，需补缓存任务状态和失败原因展示**。

#### 补充问题 29：正文替换净化规则保存可用，但 WEB 错误页/缓存失败路径未应用替换

复现路径：

- 进入 `正文替换净化`。
- 新增规则：
  - 规则名称：`codexrule`
  - 匹配内容：`love`
  - 替换为：`LUV`
  - 未启用正则。
- 保存后列表显示 `codexrule` 和 `love → LUV`。
- 回到搜索结果并再次打开 `love` WEB 错误页。

实测表现：

- 替换规则创建、保存、列表展示均可用。
- 再次打开失败的 WEB 书源后，错误页仍显示 `love`，包括标题和 `书名：love`，没有替换为 `LUV`。
- logcat 显示仍从缓存/书源失败路径加载，随后因 `startBrowser` 缺失失败。

当前判断：

- 这不能直接证明正文替换对正常章节失效，因为本样本是错误页/失败章节。
- 但可以确认：替换净化没有作用于 WEB 错误页或缓存失败提示页。
- 静态代码显示 `ReaderViewModel` 初始化时加载 `cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)`，正文加载时调用 `applyReplaceRules()`；如果用户在阅读器已打开时新增/修改规则，当前阅读器实例很可能需要重新加载章节或刷新规则缓存，否则不会实时生效。
- 后续必须用可成功加载正文的本地 EPUB/TXT 或稳定 WEB 书源继续复测，确认正常正文替换、正则替换、删除匹配内容、规则启停、阅读中修改规则后的刷新行为。

证据文件：

- `23-replace-rules.xml` / `23-replace-rules.png`
- `23-replace-rules-retry.xml`
- `24-replace-add-dialog.xml` / `24-replace-add-dialog.png`
- `25-replace-rule-added.xml` / `25-replace-rule-added.png`
- `27-love-after-replace-rule.xml` / `27-love-after-replace-rule.png`
- `27-love-after-replace-rule-logcat.txt`

修复状态：**部分可用，正常正文场景待复测；错误页/失败页未净化**。

#### 补充问题 30：测试期间多次出现 UIAutomator null-root，需排查窗口/可访问性树稳定性

复现路径：

- 反向跨章后退出并重新进入阅读器。
- 从 Profile 进入 `正文替换净化` 页面。

实测表现：

- 屏幕截图显示页面已经可见，但 UIAutomator dump 偶发返回 `ERROR: null root node returned by UiTestAutomationBridge`。
- 等待数秒后重试，UI tree 恢复正常。

当前判断：

- 这不等价于普通用户必然看到崩溃；本轮对应截图中页面可见，进程仍存活。
- 但它说明 Activity/window/Compose 可访问性树在页面切换或阅读器重进时存在短时不可观测窗口，会影响自动化测试，也可能对应用户看到的短时闪烁/卡顿。
- 需要结合 logcat crash buffer、主线程调度和 Compose transition 继续验证，避免把它误判成真正闪退，也不能完全忽略。

证据文件：

- `14-after-cross-prev-reopen.xml`
- `15-cross-prev-reopen-retry.xml`
- `23-replace-rules.xml`
- `23-replace-rules-retry.xml`

修复状态：**待定位，需要继续结合 logcat 与重复进入/退出压力测试**。

#### 补充验证：自定义主题默认创建/保存可用，CSS/阅读器生效仍待继续测试

复现路径：

- 进入 Profile 顶部主题区域。
- 可见内置主题：`墨境`、`纸上`、`赛博朋克`、`森林`、`深夜`、`墨水屏`。
- 下滑到 `自定义主题（长按删除）`，点击 `自定义主题`。
- 编辑器可见 `主题名称`、`暗色主题`、`颜色配置`、`阅读 CSS`、`自定义 CSS`、`保存并应用`。
- 未修改内容直接点击 `保存并应用`。

实测表现：

- 编辑器打开正常，保存后返回 Profile。
- 自定义主题列表出现 `我的主题`。
- 本轮没有崩溃。

仍需继续测：

- 修改颜色后是否立即影响阅读器。
- `自定义 CSS` 是否合并到 reader canvas 渲染。
- 导出/导入 JSON 是否可用。
- 长按删除是否可用。
- 反复切换主题是否闪烁、卡顿或崩溃。

证据文件：

- `28-profile-top-theme.xml` / `28-profile-top-theme.png`
- `29-profile-theme-custom-visible.xml`
- `30-theme-editor-open.xml` / `30-theme-editor-open.png`
- `31-theme-after-save.xml` / `31-theme-after-save.png`
- `31-theme-after-save-logcat.txt`

验证状态：**默认创建/保存通过；CSS 与阅读器生效仍未完成验证**。

### 2026-04-26 15:00-15:28 补充测试：UIAutomator2 高速控制、正文净化、书源 10 连点、离线缓存与主题压力

测试方式：

- 控制流改为 Python + UIAutomator2，通过 atx-agent 执行 `dump_hierarchy()`、`click()`、`swipe()` 和 `screenshot()`。
- ADB 仅保留给后台 `logcat -v threadtime`、`logcat -b crash`、进程/窗口状态兜底。
- 关键节点均保存 U2 截图、XML、节点文本和 logcat。
- 证据目录：`test-artifacts/continued/postdoc-qa-20260426-1450/`

#### 补充验证：自定义主题 CSS 已确认作用到阅读器正文，但保存会生成重复同名主题

复现路径：

- 进入 `自定义主题` 编辑器。
- 在 `阅读 CSS / 自定义 CSS` 区域点击预设 `首行缩进2字`。
- UI tree 显示 CSS 输入框内容为 `text-indent: 2em;`，并显示 `已识别: text-indent`。
- 点击 `保存并应用` 后回到 Profile。
- 打开 `Overlord 01 不死者之王`。

实测表现：

- 阅读器截图中正文首行已经出现明显缩进，说明自定义主题 CSS 已合并到 reader canvas 渲染路径。
- Profile 的自定义主题列表出现两个同名 `我的主题`，说明在已有 `我的主题` 后再次保存并应用更像是新增主题，而不是更新当前主题。
- 随后用 U2 真实点击 36 次内置主题和自定义主题，未捕获 crash buffer，页面仍可操作。

当前判断：

- 自定义 CSS 到阅读器生效这一点可标记为已验证。
- 同名主题重复保存是新的主题管理问题：用户无法区分两个 `我的主题`，后续导出/删除/切换时容易误操作。
- 主题快速切换本轮未复现崩溃，但重复同名主题仍需修。

证据文件：

- `05-css-preset-indent.xml`
- `06-theme-css-save-after.xml` / `06-theme-css-save-after.png`
- `07-profile-after-css-save-u2.xml`
- `09-reader-overlord01-after-theme-css.png`
- `49-theme-rapid-real-step-36.png`
- `49-theme-rapid-real-crash.txt`

验证状态：**CSS 生效通过；重复同名主题未修复；导入/导出/长按删除仍待测**。

#### 补充验证：正文替换净化在正常 EPUB 正文中生效，但长英文替换串会硬换行并改变总页数

复现路径：

- 在 `正文替换净化` 添加规则 `codexgame`：
  - 匹配内容：`游戏`
  - 替换为：`GAMEGAME`
  - 未启用正则。
- 回到书架打开 `Overlord 01 不死者之王` 当前进度页。

实测表现：

- 规则列表显示 `游戏 → GAMEGAME`。
- 阅读器截图中原正文的 `游戏` 被替换为 `GAMEGAME`。
- 替换后页码从 `7/188 18.5%` 变化到 `5/189 18.4%`，说明替换后的内容重新参与分页。
- 英文替换串被硬拆到多行，例如截图中出现 `GA / MEGAME`、`GAMEGAM / E`，这可能是当前 Canvas 排版的长英文/无空格单词换行策略。

当前判断：

- 正常 EPUB 正文替换可标记为已验证通过。
- 仍需测试正则替换、删除匹配内容、规则禁用/启用、阅读器已打开时新增规则是否实时刷新。
- 长英文替换串换行需要产品判断：如果要对齐 Legado，需要继续核对 Legado 对英文长词、URL、连续字母串的断行策略。

证据文件：

- `37-02-after-codexgame-save.xml`
- `37-05-reader-after-codexgame-correct.png`
- `37-u2-codexgame-corrected-logcat-snapshot.txt`
- `37-u2-codexgame-corrected-crash-snapshot.txt`

验证状态：**正常 EPUB 正文替换通过；错误页替换和高级规则仍待测**。

#### 补充问题 31：`love` 书源搜索结果 10 次点击，0 次确认成功加载正文，多数为可见错误页

复现路径：

- 发现页搜索 `love`。
- 搜索完成状态：`找到 171 条 · 失败 30 个`、`64/64`、`在线 (160)`。
- 通过 U2 连续点击 10 条在线结果，点击位置避开右侧 `缓存下载` 按钮。

10 次点击结果：

```text
1  love / 9ci / Lofter                       可见阅读器页，但 log 为正文加载失败 / startBrowser 缺失
2  love / 抬头是树 / 长佩文学                 书源无章节
3  love / islet / Lofter                     正文加载失败
4  love / 素橘 / 长佩文学                    书源无章节
5  love is love / 小俞 / 长佩文学             书源无章节
6  loveing网站的人妻们 / 今天超冷 / 黑粉小说  书源无章节
7  love song / 九棠                          书源无章节
8  love song / 九棠 / 黑粉小说                书源无章节
9  love的紫藤园 / 素夕丹 / 四零二零           正文加载失败
10 欢娱场·love nest / 弥胧 / 豆瓣阅读         书源加载失败，JSONPath 被 JSoup selector 解析
```

关键 logcat：

- Lofter：`Failed to load chapter`，原因仍与 `startBrowser` 缺失有关。
- 长佩文学 / 黑粉小说：`No chapters found` 或进入 `书源无章节` 临时错误页。
- 豆瓣阅读：`org.jsoup.select.Selector$SelectorParseException: Could not parse query '$.list[*]'`，说明规则里的 JSONPath 被当前 JSoup selector 分支解析。

当前判断：

- 失败后黑屏的问题在这些样本中大多已改善为可见错误页，进程未崩溃，`logcat -b crash` 为空。
- 但书源兼容性仍很低：本轮 10 次点击没有确认到一条可正常阅读正文。
- 错误页对用户仍不够可诊断：用户看到的是“书源无章节/书源加载失败/正文加载失败”，但搜索列表没有提前标记此源不可用，也没有推荐换源。
- 搜索结果来源名在 UIAutomator tree 中仍表现为 `来源：..Lofter`、`来源：..长佩文学` 等，图标/emoji 或来源名展示仍有可见质量问题。

证据文件：

- `39-u2-source10-summary.tsv`
- `42-u2-source-cont-summary.tsv`
- `39-01-after-click.png`
- `42-01-after.png`
- `42-07-after.png`
- `42-08-after.png`
- `42-08-after-logcat.txt`
- `42-u2-source-cont-final-crash.txt`

修复状态：**未修复，需继续补 Legado 书源规则兼容层，尤其 JS 扩展、章节规则解析和 JSONPath/JSoup 分流**。

#### 补充问题 32：缓存下载和离线缓存页仍没有展示失败原因，用户只能从 logcat 看到 `No chapters`

复现路径：

- 在 `love` 搜索结果中点击 `love song / 九棠 / 黑粉小说` 的右侧 `缓存下载`。
- 等待 15 秒。
- 进入 Profile -> `离线缓存`。
- 点击 `love song / 九棠` 条目，展开 `全部缓存 / 从当前章 / 清除`。
- 点击 `全部缓存`。

实测表现：

- 搜索结果页点击缓存后停留在列表，仅短暂 toast `开始缓存: love song`。
- 15 秒后列表没有显示成功、失败、无章节或不可缓存状态。
- logcat 显示 `CacheService: No chapters for book: 39fd5e45-80cc-4515-b547-d79b2b198047`。
- 离线缓存页显示 `共 19 本网络书籍`，展开后只有 `全部缓存 / 从当前章 / 清除`。
- 点击 `全部缓存` 后，列表显示 `已缓存 0/0 (0%)`，但没有展示“无章节”或具体失败原因。
- `logcat -b crash` 为空。

当前判断：

- 缓存任务没有崩溃，但用户完全不知道为什么缓存失败。
- `0/0 (0%)` 对无章节/规则失败的书没有诊断意义，容易让用户反复点击。
- 建议离线缓存条目至少显示：章节数为 0、最近失败原因、最近失败时间、重试入口或换源提示。

证据文件：

- `43-03-cache-click-after15s.png`
- `43-u2-cache-logcat-snapshot.txt`
- `45-03-offline-cache-screen.xml`
- `46-01-offline-cache-love-song-click.png`
- `47-02-offline-cache-all-click-after11s.png`
- `47-offline-cache-all-logcat.txt`
- `47-offline-cache-all-crash.txt`

修复状态：**未修复，需补缓存任务持久状态和离线缓存失败原因展示**。

#### 作废记录：重传 APK 导致进程被杀，不能作为阅读器退桌面 bug

背景：

- 计划用 U2 跑“进入书籍 -> 快速左右滑动 -> 退出”的 50 次压力测试。
- 脚本启动前没有先从阅读器态退出，`app_start(stop=false)` 后当前页面已经是阅读器黑屏。
- 用户随后确认：测试期间刚刚重新上传/安装了软件，所有进程会被杀掉。

实测表现：

- U2 截图为纯黑页面。
- UI tree 只有一个节点：`content-desc=阅读器`，没有章节标题、正文、时间、页码或菜单文本。
- PID 起初仍为 `9511`，压力脚本没有真正找到书卡，50 条均为 `no_cards`，因此这 50 条不能算有效进入/退出测试。
- 随后的系统 logcat 出现：
  - `kill(-9511, 9) failed: No such process`
  - `InputDispatcher: channel '... com.morealm.app.debug/com.morealm.app.ui.navigation.MainActivity ...' Consumer closed input channel`
- 再次检查前台窗口时，`mCurrentFocus` 已经变为 `com.android.launcher3/com.android.launcher3.Launcher`。
- `pidof com.morealm.app.debug` 为空。
- `logcat -b crash` 没有 Java crash 堆栈。

当前判断：

- 该样本作废，不能作为阅读器退桌面缺陷证据。
- `50-entry-stress-001-050.tsv` 与随后 `51-070` 小轮脚本结果也不纳入有效压力测试统计。
- 后续需要在 APK 安装/上传完成、进程稳定后重新开始进入/退出压力测试，并在脚本开头显式记录 APK 安装时间、当前 PID 和前台 Activity。

证据文件：

- `50-entry-stress-001-ensure-folder.png`
- `50-entry-stress-001-ensure-folder-nodes.txt`
- `50-entry-stress-001-050.tsv`
- `50-entry-stress-001-050-main-live.txt`
- `50-entry-stress-001-050-final-crash.txt`
- `51-current-after-pid-loss.xml`
- `51-current-after-pid-loss.png`

状态：**作废，不计入缺陷；需重新跑稳定环境下的压力测试**。

#### 补充验证：稳定环境下 20 次进入阅读器、快速滑动、退出未复现 crash

复现路径：

- 用户确认 APK 重传/安装已经完成后，重新启动 `com.morealm.app.debug`。
- 等待冷启动稳定到书架页。
- 进入 `overlord1-14` 文件夹。
- 用 U2 执行 20 次循环：
  - 点击一本可见书籍。
  - 进入阅读器后立即左右快速滑动。
  - 抓取 UI tree 和截图。
  - Back 退出阅读器。

实测表现：

- 20 次全部返回 `reader_visible`。
- 覆盖书籍包括 `[OVERLORD不死者之王_第十四卷]`、`OVERLORD 13`、`Overlord 01`、`Overlord 03`、`Overlord 04`、`Overlord 05`、`Overlord 06`、`Overlord 07`。
- 进程 PID 稳定为 `14616`。
- `logcat -b crash` 为空。

当前判断：

- 在 APK 重传完成、冷启动稳定后的 20 次小样本中，未复现退桌面或 Java crash。
- 这只能说明当前小样本稳定；用户要求的 200 次压力测试仍未完成，需要继续扩大次数并加入换书、打开后立即滚动/退出、主题切换和书源入口。

证据文件：

- `50-entry-stress-071-090.tsv`
- `50-entry-stress-071-090-final-crash.txt`
- `50-entry-stress-080-reader.png`
- `50-entry-stress-090-reader.png`

验证状态：**20 次小样本通过；200 次完整压力测试待继续**。

#### 补充验证：长按文字选择与 mini menu 基础入口可触发，但存在明显渲染和交互问题

复现路径：

- 当前书籍：`Overlord 08 两位领导者`。
- 当前页：`《OVERLORD 第八卷 两位领导者》`，页脚约 `5/6 5.6%`。
- 在目录/正文文字附近长按，唤起选择工具条。
- 分别测试 `复制`、`朗读`、`更多`、`翻译`、`分享`、`查词`。

实测可用项：

- 长按文字可以唤起 mini menu。
- mini menu 主行可见：`复制`、`朗读`、`更多`。
- 点击 `更多` 后可见第二行：`翻译`、`分享`、`查词`。
- `复制` 可写入剪贴板。本轮样本复制结果为 `1-4`，证明剪贴板写入成功；但复制后没有明显 toast/snackbar 反馈。
- `朗读` 可从选区起点启动 TTS 面板，状态栏出现 `墨境通知：朗读中`，底部 TTS 面板显示 `3 / 16 段`、播放/暂停、上一段/下一段、语速、语音、定时关闭、停止等控件。
- `分享` 可打开引用卡片弹窗，弹窗显示选中文本、书名作者、`取消`、`保存并分享` 和关闭按钮。
- `查词` 会外跳到 Via 浏览器并打开 Google 搜索。本轮 URL 为 `https://www.google.com/search?q=2`，说明使用了当前短选区文本。
- `翻译` 会外跳到 Via 浏览器并打开 Google Translate URL；2 秒样本中浏览器页面仍是白屏，未捕获 MoRealm crash。
- `logcat -b crash` 在复制、朗读、分享、翻译、查词动作后均为空。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/55-01-after-recover.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-copy-after-clipboard.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-speak-after.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-speak-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-share-dialog.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-after.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-current-app.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-lookup-after.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-lookup-current-app.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-*-crash.txt`

验证状态：**基础入口可触发，未见 crash；渲染、反馈、外跳体验和长选区仍未达到 Legado 级别完整移植**。

#### 补充问题 33：mini menu 展开后接近全宽，遮挡正文并覆盖选区附近内容

复现路径：

- 在 `Overlord 08 两位领导者` 页内长按目录/正文文字。
- 点击 `更多` 展开第二行按钮。

实测表现：

- 展开后的 mini menu 从屏幕左侧几乎铺到右侧，视觉上变成一块大面板，而不是贴近选区的小型浮动菜单。
- 面板覆盖了 `1-4`、`第二章 纳萨力克的一天` 等正文区域。
- 菜单底部箭头落在正文行附近，和正文内容发生视觉冲突。
- 当前截图中选区高亮不明显，用户很难判断实际选中了哪段文字。
- 用户在 2026-04-26 16:07 左右同步指出“渲染有问题”，本地 U2 复现截图与用户截图一致。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/56-share-save-selection-before.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-share-save-selection-before.xml`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-share-save-selection-before-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/58-user-reported-mini-menu-render-current.png`

源码归因：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/TextSelection.kt` 的 `SelectionToolbar` 使用 `Surface + Row/Column` 组合，但宽度没有按浮动菜单最大宽度约束。
- 横向定位使用 `(xDp - 120.dp).coerceIn(8.dp, 240.dp)`，是假定菜单宽度较小的写法；展开两行后实际宽度接近整屏，这个定位不再成立。
- 纵向定位只有 `coerceAtLeast(8.dp)`，没有 bottom collision avoidance；当选区靠近下半屏时，菜单会直接压到正文和页脚区域。

建议修复方向：

- 给 mini menu 明确最大宽度和内容自适应策略，展开态不应自动撑到接近整屏。
- 根据选区位置做上下避让：优先显示在选区上方，空间不足时显示在下方，并保持箭头指向真实选区。
- 菜单展示期间需要保证选区高亮和光标仍可见，避免菜单完全遮住被选择文字。
- 展开态可考虑横向分页、更多菜单弹层或紧凑网格，不要用全宽大面板覆盖正文。

修复状态：**未修复，新增渲染问题**。

#### 补充问题 34：短文本选区的两个选择光标重叠，拖动起止点不易识别

复现路径：

- 在 `Overlord 08 两位领导者` 的目录页长按短文本，例如 `1-2`。

实测表现：

- 选中短文本时，两个紫色选择手柄几乎重叠在一起。
- 选区范围很短时，手柄和 mini menu 箭头、正文数字会挤在同一区域。
- 对用户来说，不容易判断当前是单词选择、行选择，还是只是命中了目录编号。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-long-selection-try1-260-900.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-long-selection-try1-260-900.xml`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-long-selection-try1-260-900-nodes.txt`

建议修复方向：

- 短选区时给起点/终点手柄增加最小分离距离，或采用上下错位绘制。
- 选区过短时仍要有清晰高亮底色，不能只靠两个重叠手柄表达。
- 对目录编号、标题、正文分别确认 `findWordRange()` 的选择粒度，避免目录页测试中只能选中 `1-2` 这类极短 token。

修复状态：**未修复，需补选择手柄布局与短选区高亮**。

#### 补充问题 35：翻译/查词依赖外部浏览器，翻译页短时白屏，阅读器内没有状态反馈

复现路径：

- 长按选中文字。
- 点击 `更多 -> 翻译`。
- 返回后再次长按选中文字。
- 点击 `更多 -> 查词`。

实测表现：

- `翻译` 使用 `Intent.ACTION_VIEW` 打开 Via 浏览器，URL 指向 Google Translate。
- 2 秒等待后 Via 页面仍为空白，仅可见系统状态栏；未看到翻译结果或加载失败提示。
- `查词` 使用 `Intent.ACTION_WEB_SEARCH` 打开 Via 浏览器并进入 Google 搜索页，本轮短选区为 `2`，URL 为 `https://www.google.com/search?q=2`。
- 从阅读器角度看，翻译/查词都会离开当前应用；阅读器自身没有加载中、失败原因、返回提示或内置结果面板。
- 本轮未捕获 MoRealm crash，但这与 Legado 阅读器内较完整的选区工具体验仍有差距。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-after.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-current-app.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-translate-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-lookup-after.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-lookup-current-app.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-lookup-logcat.txt`

建议修复方向：

- 若目标是 Legado 式完整迁移，应补内置翻译/查词面板或至少补可配置的翻译/词典服务。
- 外跳浏览器前应有稳定的选中文本确认，避免短选区误跳 `q=2` 这种低价值查询。
- 外跳失败或 WebView/浏览器白屏时，阅读器应能提示失败原因，并保持返回阅读器后的选区状态可恢复。

修复状态：**未修复，当前仅是外部 Intent 兜底，不算完整移植**。

#### 补充问题 36：复制成功但缺少用户反馈，自动化/无障碍树中 menu 节点也不稳定

复现路径：

- 长按文字唤起 mini menu。
- 点击 `复制`。
- 通过 U2 读取剪贴板。

实测表现：

- 剪贴板从空值变为 `1-4`，说明复制动作成功。
- 复制后 mini menu 消失，但没有明显 toast/snackbar/复制成功提示。
- UIAutomator XML 中 `复制`、`朗读`、`更多`、`翻译`、`分享`、`查词` 的节点多次显示 `click=false`，自动化点击需要坐标兜底；这至少是测试可达性问题，也可能影响无障碍语义。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/56-copy-before-clipboard.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-copy-after-clipboard.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-copy-after.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/56-copy-selection-before-nodes.txt`

建议修复方向：

- 复制成功后补明确反馈，例如 `已复制` toast/snackbar。
- 给 menu 按钮补稳定的 semantics/clickable 暴露，方便 UIAutomator、TalkBack 和后续回归测试定位。

修复状态：**复制功能通过；反馈和无障碍/自动化可达性未修复**。

#### 补充问题 37：有阅读进度的书退出再进入，会出现 1 页左右的回退

复现路径：

- 在阅读器内停留于 `Overlord 05 王国好汉 上`。
- 当前页显示为 `Prologue`，页脚为 `37/38  19.7%`。
- 按 Back 返回书架。
- 书架卡片显示 `Overlord 05 王国好汉 上`，进度条值约 `0.194`。
- 重新点击同一本书进入阅读器。

实测表现：

```text
退出前：Prologue 37/38  19.7%
书架卡片：Overlord 05 王国好汉 上，ProgressBar = 0.194
重进后：Prologue 36/38  19.5%
PID：14616，未重启
crash buffer：空
```

结论：

- 用户反馈的“又出现了回退”在本轮已复现。
- 这不是 APK 重传或进程被杀造成的无效样本；同一 PID 存活，`logcat -b crash` 为空。
- 当前样本表现为退出/重进后回退 1 页，属于进度持久化/恢复精度问题。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/59-user-reported-regression-current.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-user-reported-regression-current.xml`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-progress-reentry-00-before-exit.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-progress-reentry-01-after-back.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-progress-reentry-02-after-open-overlord05.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-progress-reentry-summary.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-progress-reentry-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/59-progress-reentry-crash.txt`

源码归因：

- `ReadProgress` 虽然定义了 `chapterPosition`、`chapterOffset`，但 `ReaderViewModel.saveProgress()` 当前只保存 `chapterIndex`、`totalProgress`、`scrollProgress`，没有保存真实可见行的章节字符位置或页内 offset。
- `ScrollRenderer` 上报进度时用整数百分比：`((chapterPageIndex + visiblePageFraction) * 100f / (chapterPageSize - 1)).roundToInt()`。
- 恢复滚动进度时使用 `((initialProgress / 100f) * (pageCount - 1)).toInt()`，`toInt()` 会向下取整。
- 这会把高页码处的保存进度恢复到前一页附近；本轮 `37/38` 重进后变成 `36/38` 与该逻辑吻合。

建议修复方向：

- 不要只用整数百分比恢复阅读位置。至少保存 `chapterIndex + chapterPosition + chapterOffset/pageOffset`。
- 滚动模式恢复应优先按可见首行的 `chapterPosition` 定位，再计算页和 offset，而不是按整数百分比反推页号。
- 如仍保留百分比兜底，恢复时不要简单 `toInt()` 向下取整，应结合保存时的页索引/offset 或使用更高精度浮点。
- 对退出、重进、向前翻页后退出、跨章后退出四类场景分别加回归测试。

修复状态：**未修复，已复现用户反馈的回退现象**。

#### 作废记录：`91-130` 压力轮次启动状态错误，不能计入 200 次进入/退出测试

背景：

- 计划在稳定 PID `14616` 下继续执行 `91-130` 轮进入阅读器、快速滑动、退出测试。
- 脚本启动时设备仍停留在阅读器，未可靠回到书籍列表。

实测表现：

- `50-entry-stress-091-130.tsv` 中 40 次均为 `no_cards`。
- 该轮没有实际完成“点击书籍进入 -> 快速滑动 -> 退出”的循环，不能计入有效压力样本。
- 因为脚本在 `no_cards` 分支仍执行了列表恢复滑动，实际变成了在阅读器内连续上滑，日志显示从 `Overlord 05 王国好汉 上` 章节 0 推进到 `Prologue`。
- 未捕获 crash，`50-entry-stress-091-130-final-crash.txt` 为空。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-091-130.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-091-130-main-live.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-091-130-final-crash.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-091-startup-initial.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-091-ensure-folder.png`

后续脚本要求：

- 压力脚本在开始循环前必须断言当前 UI tree 中存在可点击书籍卡片。
- 若当前仍是 `阅读器`，必须显式 Back 到书架并重新 dump；无法进入列表时直接终止，不允许继续把阅读器内滑动误记成压力轮次。
- `no_cards` 分支不能在阅读器内执行列表恢复滑动，否则会污染阅读进度和压力测试数据。

状态：**作废，不计入有效 200 次压力测试统计**。

#### 补充验证：修正脚本后 40 次进入阅读器、快速左右滑动、退出未复现 crash

复现路径：

- 修正 `u2_reader_entry_stress.py`：
  - 如果已经位于 `overlord1-14` 文件夹，不再误点面包屑里的 `overlord1-14`。
  - 如果当前仍在 `阅读器` 且没有书籍卡片，直接 abort，不再继续在阅读器内执行列表恢复滑动。
- 重新执行 `131-170` 共 40 次循环：
  - 点击一本可见书籍。
  - 进入阅读器后立即快速左右滑动。
  - 抓取 UI tree / 按序号截图。
  - Back 返回书架/文件夹。

实测表现：

- 40/40 次状态为 `reader_visible`。
- 覆盖书籍包括 `Overlord 01`、`Overlord 03`、`Overlord 04`、`Overlord 05`、`Overlord 06`、`Overlord 07`、`OVERLORD`、`OVERLORD 13`、`[OVERLORD不死者之王_第十四卷]`。
- PID 全程为 `14616`。
- crash buffer 手动抓取为空。
- 主日志可见每轮 `Opened:` 和 `Loaded chapter`，没有 `FATAL EXCEPTION` 或 `ConcurrentModificationException`。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-131-170.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-131-170-main-live.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-131-170-final-crash-manual.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-140-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-150-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-160-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-170-reader.png`

当前判断：

- 稳定环境下有效进入/退出压力样本累计为 `20 + 40 = 60` 次。
- 用户要求的 200 次仍未完成，后续至少还需补 140 次有效样本。
- 本轮没有复现退桌面或 Java crash，但已另行复现退出重进回退 1 页的问题。

验证状态：**40 次有效样本通过；200 次完整压力测试待继续**。

#### 补充验证：继续 50 次进入阅读器、快速左右滑动、退出未复现 crash

复现路径：

- 继续使用修正后的 `u2_reader_entry_stress.py`。
- 执行 `171-220` 共 50 次循环。
- 每轮点击一本可见书籍，进入阅读器后立即执行快速左右滑动，再 Back 返回。

实测表现：

- 50/50 次状态为 `reader_visible`。
- 覆盖书籍包括 `Overlord 01`、`Overlord 03`、`Overlord 04`、`OVERLORD`、`OVERLORD 13`、`[OVERLORD不死者之王_第十四卷]`。
- PID 全程为 `14616`。
- `logcat -b crash` 为空。
- 主日志未出现 `FATAL EXCEPTION`、`ConcurrentModificationException` 或 ANR。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-171-220.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-171-220-main-live.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-171-220-final-crash.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-200-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-210-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-220-reader.png`

当前判断：

- 稳定环境下有效进入/退出压力样本累计为 `20 + 40 + 50 = 110` 次。
- 用户要求的 200 次仍未完成，后续至少还需补 90 次有效样本。
- 本轮没有复现退桌面或 Java crash，但用户观察到快速滑动期间存在闪烁，已在下一条单独记录。

验证状态：**50 次有效样本通过；200 次完整压力测试待继续**。

#### 补充问题 38：快速左右滑动时出现页面叠加/信息栏重复的闪烁帧

复现路径：

- 当前文件夹：`overlord1-14`。
- 打开 `Overlord 01 不死者之王`。
- 进入阅读器后快速连续执行左右滑动。
- 用 U2 连续截图 36 帧，并计算帧亮度和帧间差异。

实测表现：

- 本轮没有捕获纯白屏或纯黑屏 crash 帧。
- 但捕获到明显视觉撕裂/闪烁中间帧：
  - `60-flicker-frame-02.png`：正文页，页脚 `2/189 18.3%`。
  - `60-flicker-frame-03.png`：下一帧切到插图页，页脚变为 `3/189 18.3%`。
  - `60-flicker-frame-06.png`：左侧残留正文页，右侧显示插图页，页面中间出现双页叠加。
  - `60-flicker-frame-07.png`：插图页上方/下方信息栏出现重影，底部电池/时间显示重复。
  - `60-flicker-frame-08.png`：又回到正文页，页脚 `2/189 18.3%`。
- 帧差指标显示多次大幅跳变：
  - `frame 8` 相对上一帧 `diff_mean = 86.85`
  - `frame 3` 相对上一帧 `diff_mean = 86.55`
  - `frame 6` 相对上一帧 `diff_mean = 80.79`
  - `frame 7` 相对上一帧 `diff_mean = 73.85`
- `logcat` 同时可见快速边界推进：
  - `upContent(relativePosition=1, resetPageOffset=false)`
  - `upContent(relativePosition=-1, resetPageOffset=false)`
  - `Commit prev chapter from fillPage boundary: 1`
  - `Loaded chapter 1: Prologue`
  - `dragTurnPage(NEXT) rejected at display=13`
- `60-flicker-crash.txt` 为空，说明这是视觉层/翻页动画状态问题，不是进程崩溃。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/u2_flicker_capture.py`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-capture-start.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-frame-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-frame-02.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-frame-03.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-frame-06.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-frame-07.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-frame-08.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/60-flicker-crash.txt`

当前判断：

- 用户反馈的“闪烁”是有效问题，本轮已抓到静态帧证据。
- 这不是崩溃，也不是单纯截图误差；快速翻页期间实际存在左右页同时残留、信息栏重复和图文页快速来回切换。
- 该问题会影响阅读器的高级翻页体验，尤其是图文混排页和章节边界附近。

建议修复方向：

- 检查快速连续手势时 `ReaderPageDelegateState` / 动画状态是否允许新旧动画重入。
- 翻页动画中应只绘制一套 header/footer，避免当前页和目标页各自绘制信息栏后叠加。
- 当 `dragTurnPage(...) rejected` 时，要确认渲染状态是否回滚到稳定页，而不是保留半张上一帧 bitmap。
- 图文页和正文页切换时，图片预解码/Canvas 录制应与动画帧提交同步，避免旧页 bitmap 和新页内容混合显示。

修复状态：**未修复，已复现用户观察到的闪烁/页面叠加问题**。

#### 补充验证：`221-310` 继续 90 次进入阅读器、快速左右滑动、退出未复现 crash，200 次压力目标已达成

复现路径：

- 使用修正后的 `u2_reader_entry_stress.py`。
- 执行 `221-310` 共 90 次循环。
- 每轮点击一本可见书籍，进入阅读器后立即执行快速左右滑动，再 Back 返回。

实测表现：

- 90/90 次状态为 `reader_visible`。
- 覆盖书籍包括 `Overlord 01`、`Overlord 03`、`Overlord 04`、`OVERLORD`、`OVERLORD 13`、`[OVERLORD不死者之王_第十四卷]`。
- PID 全程为 `14616`。
- `logcat -b crash` 为空。
- 主日志未出现 `FATAL EXCEPTION`、`ConcurrentModificationException`、`SIGSEGV` 或 ANR。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-221-310.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-221-310-main-live.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-221-310-final-crash.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-230-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-240-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-250-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-260-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-270-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-280-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-290-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-300-reader.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/50-entry-stress-310-reader.png`

当前总计：

```text
有效小样本 071-090：20 次通过
有效样本 131-170：40 次通过
有效样本 171-220：50 次通过
有效样本 221-310：90 次通过
合计：200 次有效进入阅读器/快速滑动/退出，0 crash
作废样本：001-050、051-070、091-130，不计入统计
```

当前判断：

- 用户要求的“反复进入新书然后退出 200 次”在稳定环境下已经完成，未复现退桌面、主进程崩溃或 Java crash。
- 这不代表阅读器完整通过：同一轮测试已经确认仍有退出重进回退 1 页、快速左右滑动闪烁/页面叠加、mini menu 遮挡等问题。
- `221-310` 覆盖的是当前可见书籍循环，不等同于 200 本不同新书；后续如要继续“每次换新书”，需要扩大书架列表、导入更多本或滚动全列表去重。

验证状态：**200 次有效进入/退出压力通过；视觉闪烁和进度恢复问题仍未修复**。

## 2026-04-26 LDPlayer 阅读器专项测试记录：翻页、快速滑动与跨章

测试环境：

- 模拟器：`D:\LDPlayer9\dnplayer.exe` / `emulator-5554`
- 设备信息：Android 9，`GM1900`，`1080x1920`，density `480`
- 测试包：`com.morealm.app.debug`
- 测试方式：LDPlayer 自带 `adb.exe` 启动、点击、滑动、抓取 UI tree、截图和 logcat。
- 证据文件：`test-artifacts/` 下的 `scroll-swipe-forward.tsv`、`scroll-rapid-stress.tsv`、`morealm-reader-entry-logcat.txt`、`scroll-rapid-stress-logcat.txt`、`user-opened-zero.png` 等。
- 限制：模拟器内没有安装 Legado APK，本轮动态对比以 MoRealm 实测 + `D:\temp_build\sigma\legado` 源码语义为基准。

### 问题 1：滚动模式跨章追加后，当前进度被重新解释为顶部附近

复现路径：

- 打开已有书籍 `[OVERLORD不死者之王_第十四卷]`。
- 当前阅读模式是上下滚动。
- 从封面章节开始，连续向上滑动进入后续正文。

实测状态序列：

```text
0  initial             cover  1/4    2.1%
1  swipe-up-forward    cover  3/4    6.3%
5  swipe-up-forward    cover  6/6    8.3%
6  swipe-up-forward    cover  4/324  0.1%
8  swipe-up-forward    cover  6/324  0.2%
14 swipe-up-forward    cover  11/324 0.3%
```

实际表现：

- 从 `6/6 8.3%` 继续向下进入追加后的正文时，页数突然扩展成 `324`。
- 进度从 `8.3%` 回落到 `0.1%` 附近，表现为“翻到下一章时进度像被重置到顶部”。
- 截图中正文已经进入 `序章 / Prologue`，但页眉仍显示 `cover`。
- 页脚页码和百分比使用了重新排版后的大章节页数，因此用户看到的是“当前位置被重新映射到了拼接内容顶部附近”。

源码归因：

- `ReaderViewModel.loadChapter()` 在滚动模式下把当前章包装成 `continuousContent`，随后 `appendNextChapterForScroll()` 继续把下一章追加到同一个字符串。
- `CanvasRenderer` 对变化后的 `content` 重新执行 `layoutChapterAsync()`，得到一个新的大 `TextChapter`。
- `ChapterProvider.layoutInternal()` 在 `finalizePage()` 中统一写入同一个 `chapterIndex/title`，所以追加后所有页仍继承起始章节标题，例如 `cover`。
- `ScrollRenderer` 的 `currentPageIndex/pageOffset` 是页列表内局部状态；当总页数从 6 变成 324 后，原索引被当成新大列表里的顶部页解释。

Legado 对照：

- Legado 的滚动模式不是把多个章节拼成一个大章节后重排。
- `ContentTextView.scroll(mOffset)` 通过 `PageFactory.moveToNext(upContent = true)` 推进阅读窗口。
- 当前可见页标题、章节索引、页内进度来自真实 `TextPage`，而不是拼接后起始章节的固定标题。

当前结论：

**这是未完整移植项，不是单纯显示问题。**滚动跨章时，MoRealm 仍在用“拼接内容重新排版”的模型模拟 Legado 的 `ContentTextView + PageFactory`，导致章节身份、页数分母和当前页索引都不稳定。

建议修复方向：

- 不要让滚动模式把多个章节长期合并成一个无章节边界的大 `TextChapter`。
- 至少需要在排版层保留每个 `chapter-block data-index` 的原始章节索引和标题，并让生成的 `TextPage.chapterIndex/title/pageSize` 对应真实章节。
- 更接近 Legado 的方案是恢复 `ScrollPageDelegate + ContentTextView + PageFactory` 语义：滚动层只维护当前页窗口，通过 `PageFactory` 跨章推进。
- 追加章节后必须用稳定位置恢复滚动状态，例如 `chapterIndex + chapterPosition/pageOffset`，不能只沿用重排前的局部 `currentPageIndex`。

### 问题 2：0 进度 EPUB 快速打开/切换时发生主进程崩溃

复现路径：

- 用户打开一本 0 进度书。
- logcat 显示先打开 `Overlord 03 鲜血的战争少女`，随后又打开 `Overlord 04 蜥蜴人勇者们`。
- 两本书都是 EPUB，并且进入后会立即触发滚动模式的下一章预追加。

关键日志：

```text
I Reader  : Opened: Overlord 03 鲜血的战争少女 (EPUB)
I Reader  : Parsed 10 chapters
D Reader  : Loaded chapter 0: 《OVERLORD 第三卷 鲜血的战争少女》
D Reader  : Start appending chapter 1 for continuous scroll
D Reader  : Appended chapter 1 for continuous scroll
D Reader  : Start appending chapter 2 for continuous scroll
D Reader  : Appended chapter 2 for continuous scroll

I Reader  : Opened: Overlord 04 蜥蜴人勇者们 (EPUB)
I Reader  : Parsed 11 chapters
D Reader  : Loaded chapter 0: 《OVERLORD 第四卷 蜥蜴人勇者们》
D Reader  : Start appending chapter 1 for continuous scroll
D Reader  : Appended chapter 1 for continuous scroll
E AndroidRuntime: FATAL EXCEPTION: main
E AndroidRuntime: java.util.ConcurrentModificationException
E AndroidRuntime:     at java.util.ArrayList$Itr.next(ArrayList.java:860)
E AndroidRuntime:     at com.morealm.app.ui.reader.renderer.CanvasRendererKt$CanvasRenderer$22$1.invokeSuspend(CanvasRenderer.kt:1344)
```

实际表现：

- 应用回到桌面，`mCurrentFocus` 变成 `com.android.launcher3/.Launcher`。
- 这是主线程崩溃，不是单纯渲染闪烁。

源码归因：

- `ChapterProvider.layoutChapterAsync()` 在 `Dispatchers.Default` 中持续向同一个 `TextChapter` 添加页面。
- `TextChapter.pages` 暴露的是内部 `ArrayList` 的只读视图，但底层仍是可变集合。
- `CanvasRenderer` 直接读取 `chapter.pages` 作为 `currentChapterPages`，并传给 `ReaderPageFactory`、`ScrollRenderer`、`PageCanvas`。
- `PageCanvas.drawPageContent()` 会遍历 `page.lines` 和 `line.columns`。
- 当异步排版、追加章节、Compose 重组/绘制同时发生时，UI 可能遍历正在被排版线程修改的集合，触发 `ConcurrentModificationException`。

Legado 对照：

- Legado 的 `TextPageFactory` 和 `ContentTextView` 工作在较稳定的 View/分页窗口模型中，跨章推进时通过 PageFactory 切换当前页对象。
- MoRealm 当前的异步流式排版把未完成的可变 `TextChapter` 直接暴露给 Compose 绘制层，线程安全边界比 Legado 更弱。

当前结论：

**这是 P0/P1 级稳定性问题。**只要 0 进度书打开后触发预追加/重排，用户快速切书或 UI 同步读取页面，就可能崩溃。

建议修复方向：

- 排版线程不要直接发布仍在继续变更的 `TextChapter.pages` 给 UI 绘制。
- 可以在每次 `onPageReady` 发布不可变快照，例如 `pages.toList()`，或让 `TextChapter.addPage()` 只在主线程可见状态中提交。
- `TextPage.lines`、`TextLine.columns` 也需要冻结或快照化，避免绘制时遍历可变集合。
- `CanvasRenderer` 中 `lastRenderablePages = chapter.pages` 这类引用应改成复制后的稳定列表。
- 切换书籍时应取消上一轮 `layoutChapterAsync()` 和滚动追加任务，避免旧任务继续写入已经被新书 UI 观察的状态。

### 问题 3：快速滑动没有立刻卡死，但章节标题仍错误

复现路径：

- 在已进入 `324` 页连续滚动内容后，执行 25 次快速向上滑动，再执行 12 次快速向下滑动。

实测状态：

```text
before-rapid-forward  cover  11/324 0.3%
after-rapid-forward   cover  44/324 1.1%
after-rapid-backward  cover  28/324 0.7%
```

实际表现：

- 本轮没有捕获到 crash。
- 快速向上和向下滑动能改变页码，暂未复现“完全只能上不能下”的卡死。
- 但标题始终停留在 `cover`，说明跨章可见页身份仍未正确更新。

当前结论：

快速滑动的跨页循环推进比早期状态有改善，但还不能视为 Legado 滚动模式完整等价。当前仍缺少真实章节边界、真实可见页标题、稳定进度分母和跨章状态恢复。

### 问题 4：封面大图会优先命中图片点击，影响点按翻页测试

复现路径：

- 在封面页右侧区域点按。

实际表现：

- 没有触发“下一页”。
- 打开了图片预览层，UI tree 出现 `ImageView` 和 `关闭` 按钮。

源码归因：

- `CanvasRenderer` 在非滚动点按处理中先调用 `hitTestColumn(page, offset.x, offset.y)`。
- 命中 `ImageColumn` 时优先执行 `onImageClick(src)`，然后直接返回，不再进入九宫格翻页动作。

当前结论：

这可能符合 Legado 的图片点击优先级，但在封面/插图占满大部分页面时，右侧点按翻页区域会被图片命中吞掉。后续需要明确产品行为：图片点击优先是否只在长按/中心区域生效，还是继续保持 Legado 式图片优先。

### 本轮动态测试总判断

阅读器还不能宣布“完整移植 Legado 阅读器部分”。

已验证可用的部分：

- 阅读器能启动并渲染 EPUB 封面和正文。
- 滚动模式能从封面滚入正文。
- 快速滑动能推进和回退页码，未在该轮快速滑动中直接卡死。
- 连续滚动会触发下一章预追加。

本轮确认的问题：

- 滚动模式跨章追加后，页数分母扩展导致当前进度回跳到顶部附近。
- 可见正文进入下一章后，页眉标题仍停留在起始章节 `cover`。
- 0 进度 EPUB 快速打开/切换时出现 `ConcurrentModificationException` 主线程崩溃。
- 当前实现仍把滚动跨章建立在连续字符串重排上，与 Legado 的 `PageFactory` 跨章窗口模型不等价。

后续优先级：

1. 先修复异步排版/绘制并发崩溃，避免 0 进度书打开即 crash。
2. 再修复滚动模式跨章身份：`TextPage.chapterIndex/title/pageSize/readProgress` 必须来自真实章节。
3. 再修复跨章追加后的滚动位置稳定性，避免 `6/6 8.3% -> 4/324 0.1%` 这类回跳。
4. 最后补自动化回归脚本：0 进度书打开、慢速跨章、快速滑动跨章、反向滑回上一章、连续切书。

## 2026-04-26 继续移植记录：touchRough 行首行尾边界

Legado 对照来源：

- `page/ContentTextView.kt`
  - `touchRough(x, y)` 行首返回 `charIndex = -1`
  - `touchRough(x, y)` 行尾返回 `charIndex = columns.lastIndex + 1`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/TextSelection.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/domain/render/PageLayout.kt`

完成内容：

- 新增 `hitTestPageRough()`，按 Legado `touchRough()` 返回行首/行尾边界位置。
- 普通分页选择光标拖动改用 `hitTestPageRough()`。
- 滚动分页新增 `touchRelativePageRough()`，按 `relativePage(0..2)` 使用 rough 命中。
- 滚动分页选择光标拖动改用 `touchRelativePageRough()`。
- `TextPage.getTextBetween()` 支持 `columnIndex = -1` 和 `columns.lastIndex + 1` 边界。
- `TextPage.getPosByLineColumn()` 支持 Legado 边界列坐标，不因越界列崩溃。

当前状态：**touchRough 行首/行尾边界语义已移植到普通分页和滚动分页选择拖动**。

仍需继续对照：

- 搜索结果跨页选择还未完整移植。
- 固定三页 `prev/cur/next PageView` 等价结构还要继续收敛。

注意：仍按要求暂未构建。

## 2026-04-26 继续移植记录：固定相对页内容容器

Legado 对照来源：

- `page/ReadView.kt`
  - `prevPage`
  - `curPage`
  - `nextPage`
  - `upContent(relativePosition, resetPageOffset)`
- `page/ContentTextView.kt`
  - `relativePage(relativePos)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ReaderPageState.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`

完成内容：

- `ReaderPageContent` 新增 `relativePage(relativePos)`，明确承载 `prevPage/currentPage/nextPage/nextPlusPage`。
- `CanvasRenderer.pageForDisplay()` 优先从 `ReaderPageContent` 取固定相对页，不再直接信任渲染窗口索引。
- `CanvasRenderer.relativePageForDisplay()` 用当前 `ReaderPageContent` 暴露固定相对页内容。
- 自动翻页下一页预览改用固定 `relativePage(1)`，对应 Legado `readView.nextPage`。

当前状态：**已开始把 Compose Pager 显示页收敛到 Legado 固定相对页内容容器**。

仍需继续对照：

- `AnimatedPageReader` 的 slide/cover 仍由 Pager 组件承载，还没有完全替换成只绘制固定 `prev/cur/next` 三个内容层。
- `SimulationPager` 虽已使用 `onFillPage(direction)`，仍需继续核对它的位图生命周期和 Legado `SimulationPageDelegate`。

注意：仍按要求暂未构建。

## 2026-04-26 继续移植记录：搜索结果页内定位与跨页选区

Legado 对照来源：

- `ReadBookViewModel.searchResultPositions(...)`
- `ReadBookActivity` 搜索结果点击后：
  - `ReadBook.skipToPage(pageIndex)`
  - `selectStartMoveIndex(0, lineIndex, charIndex)`
  - `selectEndMoveIndex(0/1, lineIndex, charIndex2)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/domain/render/PageLayout.kt`

完成内容：

- `ReaderViewModel.SearchResult` 增加 `query`、`queryIndexInChapter`、`queryLength`。
- 新增 `ReaderViewModel.SearchSelection`，用于把搜索结果点击转换为页内选区请求。
- 搜索结果点击不再只 `loadChapter(result.chapterIndex)`，改为 `openSearchResult(result)`。
- `TextChapter.searchSelectionRange()` 按 Legado `searchResultPositions()` 计算 `pageIndex/lineIndex/charIndex/addLine/charIndex2/queryLength`。
- `CanvasRenderer` 在章节排版完成后消费 `pendingSearchSelection`，设置选区并跳到对应页。
- 支持 Legado 的 `addLine = -1` 情况：搜索词跨到下一页时，终点使用 `TextPos(1, 0, charIndex2)`。

当前状态：**搜索结果已从章节级跳转推进到 Legado 页内定位与跨页选区语义**。

仍需继续对照：

- 搜索结果 UI 高亮显示仍不是 Legado HTML 高亮样式。
- `upSelectChars()` 的 column 级 `isSearchResult` 标记还需继续决定是否迁移到绘制数据层。

注意：仍按要求暂未构建。

## 2026-04-26 文件级对照矩阵更新

| Legado 文件 | MoRealm 对应文件 | 当前状态 | 本轮变化 |
| --- | --- | --- | --- |
| `api/DataSource.kt` | `ReaderDataSource.kt`、`CanvasRenderer.kt` | 部分完成 | 已引入统一 `readerPageIndex`，不再固定首页。 |
| `api/PageFactory.kt` | `ReaderPageFactory.kt`、`ReaderPageState.kt` | 部分完成 | `ReaderPageContent.relativePage()` 已建立固定相对页容器。 |
| `provider/TextPageFactory.kt` | `ReaderPageFactory.kt` | 已完成主要边界 | `cur/prev/next/nextPlus` 取页语义已按 Legado 分支重写。 |
| `ReadView.kt` | `CanvasRenderer.kt`、`ReaderPageState.kt` | 部分完成 | `fillPage(direction)`、搜索跳页、固定相对页容器已接入；具体三 PageView 结构仍需继续。 |
| `PageView.kt` | `PageContentBox`、`PageReaderInfoOverlay` | 部分完成 | 普通分页选择光标拖动入口已接入。 |
| `ContentTextView.kt` | `ScrollRenderer.kt`、`TextSelection.kt`、`PageCanvas.kt` | 部分完成 | `relativePage`、`relativeOffset`、`touch`、`touchRough`、`getReadAloudPos`、`scroll` 边界推进已移植。 |
| `delegate/PageDelegate.kt` | `ReaderPageDelegateState.kt` | 部分完成 | 基础状态字段与入口已移植。 |
| `delegate/ScrollPageDelegate.kt` | `ScrollRenderer.kt` | 部分完成 | `calcNextPageOffset/calcPrevPageOffset`、滚动动画状态、边界推进函数已移植。 |
| `AutoPager.kt` | `ReaderAutoPagerState.kt`、`CanvasRenderer.kt`、`ScrollRenderer.kt` | 部分完成 | 运行模型已接入，选择可用状态/服务控制仍需继续。 |
| `entities/TextPos.kt` | `PageLayout.kt` | 已完成主要语义 | `compare/isSelected/EMPTY` 已按 Legado 补齐。 |
| `entities/TextPage.kt` | `PageLayout.kt` | 部分完成 | `upPageAloudSpan/getPosByLineColumn/searchSelectionRange` 等已补；渲染缓存与 column 类型仍需继续。 |
| `entities/column/*` | `PageLayout.kt` | 未完成 | 仍缺 `TextHtmlColumn/ButtonColumn/ReviewColumn/TextBaseColumn` 完整结构。 |
| `provider/TextChapterLayout.kt` | `ChapterProvider.kt`、`TextMeasure.kt`、`ZhLayout.kt` | 部分完成 | 需继续逐函数核对。 |

注意：本轮继续按要求暂未构建。

## 2026-04-26 继续移植记录：column 类型层

Legado 对照来源：

- `entities/column/BaseColumn.kt`
- `entities/column/TextBaseColumn.kt`
- `entities/column/TextColumn.kt`
- `entities/column/TextHtmlColumn.kt`
- `entities/column/ImageColumn.kt`
- `entities/column/ButtonColumn.kt`
- `entities/column/ReviewColumn.kt`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/domain/render/PageLayout.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/PageCanvas.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/TextSelection.kt`

完成内容：

- `BaseColumn` 增加 `textLine` 引用，`TextLine.addColumn()` 自动回填。
- 新增 `TextBaseColumn`，统一 `charData/selected/isSearchResult`。
- `TextColumn` 改为实现 `TextBaseColumn`。
- 新增 `TextHtmlColumn`，保留 `textSize/textColor/linkUrl/selected/isSearchResult`。
- `ImageColumn` 增加 `height/width/click/textLine`，并按 Legado 放宽触摸尾部范围。
- 新增 `ButtonColumn`。
- 新增 `ReviewColumn` 和 `countText`。
- 文本长度、选中文本、位置计算从只识别 `TextColumn` 改为识别 `TextBaseColumn`。
- `PageCanvas` 支持绘制 `TextHtmlColumn` 的字号和颜色。
- `TextSelection.findWordRange()` 改为识别 `TextBaseColumn`。

当前状态：**column 数据类型层已按 Legado 补齐主要结构**。

仍需继续对照：

- `ButtonColumn/ReviewColumn` 的具体点击行为和 Review 绘制还需继续接入。
- HTML span 到 `TextHtmlColumn` 的排版生成链路仍需继续对照 `TextChapterLayout`。

注意：仍按要求暂未构建。

## 2026-04-26 继续移植记录：跨相对页选中文本提取

Legado 对照来源：

- `ContentTextView.selectedText`
- `ReadView.aloudStartSelect()`
- `TextPos.compare(...)`

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`

完成内容：

- `ReaderSelectionToolbar` 增加 `relativePageProvider(relativePos)`。
- 复制/翻译/分享/查询的选中文本提取不再只比较 `lineIndex/columnIndex`，改用 `TextPos.compare()`。
- 同一相对页选区按原页提取。
- 跨相对页选区会提取起始页剩余文本 + 结束页开头文本。
- “从这里朗读”使用实际起点所在相对页的 `chapterPosition + getPosByLineColumn()`，对应 Legado `aloudStartSelect()` 的相对页起点语义。

当前状态：**普通分页和搜索跨页选区的文本提取/起读位置已接入相对页模型**。

本轮继续完成：

- 对照 Legado `ContentTextView.relativePage(0..2)`，`ScrollRenderer` 新增 `onRelativePagesChanged`，把当前滚动窗口内的相对页 `0/1/2` 回传给外层。
- `CanvasRenderer` 新增 `scrollRelativePages` 状态，滚动长按、选择起点拖动、选择终点拖动都会按 `TextPos.relativePagePos` 更新对应真实 `TextPage`。
- `ReaderSelectionToolbar.relativePageProvider` 在滚动模式下优先读取 `scrollRelativePages[relativePos]`，复制、翻译、分享、查词和“从这里朗读”不再只依赖单个 `selectedTextPage`。

当前状态：**滚动模式跨相对页选中文本提取已接入 Legado 相对页模型**。

验证：`assembleDebug` 已通过。

## 2026-04-26 继续修复记录：LDPlayer 新增问题与 Legado Column/书源兼容对照

Legado 对照来源：

- `ContentTextView.click(x, y)`：先由阅读页/九宫格翻页区域处理页面动作，未消费时再进入 `ButtonColumn`、`ReviewColumn`、`ImageColumn`、`TextHtmlColumn` 的内容点击。
- `JsExtensions.startBrowser/startBrowserAwait`：书源 JS 可调用浏览器验证 API；MoRealm 当前没有完整验证 UI，本轮先补同名 API 和后台 WebView/HTTP fallback，避免 `startBrowser is not a function` 直接打断书源链路。
- Legado 书源/缓存错误路径会保留可见状态；MoRealm 本轮把失败原因从 logcat 前移到错误页和离线缓存 UI。

MoRealm 修改：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/domain/analyzeRule/JsExtensions.kt`
- `app/src/main/java/com/morealm/app/domain/analyzeRule/AnalyzeUrl.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/service/CacheBookService.kt`
- `app/src/main/java/com/morealm/app/ui/cache/CacheBookScreen.kt`
- `app/src/main/java/com/morealm/app/domain/repository/ThemeRepository.kt`

完成内容：

- 针对“问题 4：封面大图吞掉右侧点按翻页”，滚动模式点按已改为先处理中心菜单、上/下区域滚动，再进入内容 Column 点击；普通分页已有九宫格翻页优先逻辑。封面/大图不应再吞掉明确的页面区域操作。
- `TextHtmlColumn.linkUrl` 点击会按系统 `ACTION_VIEW` 打开链接；`ButtonColumn`、`ReviewColumn` 会消费点击并给出暂不支持提示，避免继续误触翻页。
- 针对“补充问题 27/31：Lofter 等书源缺 `startBrowser`”，`JsExtensions` 和 `AnalyzeUrl` 均补入 `startBrowser/startBrowserAwait` 兼容入口；`startBrowserAwait` 会退化为后台 WebView 或普通 HTTP 获取，先避免书源 JS 因缺函数直接失败。
- 针对“补充问题 27/29：错误页来源 HTML 实体和长原因不可读、错误页未应用净化规则”，阅读器错误页会先 HTML unescape，再应用当前书籍正文替换规则，并对超长无空格错误段落做硬换行。
- 针对“补充问题 28/32：缓存失败只在 logcat 可见”，缓存服务在书源不存在、无章节、部分章节失败时保留 `DownloadProgress.message`；离线缓存页会展示“未获取到章节/缓存失败原因”，不再只显示 `0/0 (0%)`。
- 针对主题压力测试新增问题“保存生成重复同名 `我的主题`”，自定义主题保存时会复用已有同名自定义主题 id，改为更新并激活，不再继续插入重复同名卡片。

当前状态：

- **问题 4 修复代码路径，待 LDPlayer 回归验证**。
- **补充问题 27 部分修复代码路径，待 LDPlayer 回归验证**：已补 HTML 实体、错误页换行、`startBrowser/startBrowserAwait` fallback；完整人工验证浏览器流程仍未移植。
- **补充问题 28/32 修复代码路径，待 LDPlayer 回归验证**：缓存失败原因已进入服务状态和离线缓存 UI；搜索结果项内持久失败标签仍可继续细化。
- **补充问题 29 部分修复代码路径，待 LDPlayer 回归验证**：错误页会应用已加载的正文替换规则；阅读器已打开后新增规则的实时刷新仍需继续单独对照。
- **补充问题 31 部分修复代码路径，待 LDPlayer 回归验证**：书源 JS 缺函数直失败路径已补；JSONPath/JSoup 分流代码此前已存在，仍需用豆瓣阅读样本复测。
- **自定义主题重复同名问题修复代码路径，待 LDPlayer 回归验证**。

验证：`git diff --check` 通过；`assembleDebug` 已通过。

## 2026-04-26 继续修复记录：阅读进度恢复改为 Legado durChapterPos 模型

问题：

- MoRealm 之前保存的是整数 `_scrollProgress`，恢复时使用 `initialProgress / 100f * (pageCount - 1)` 再取整。
- 高页码处会因为百分比量化丢精度，例如 `37/38` 保存成约 `97%` 后，恢复可能落到 `36/38`。

Legado 对照来源：

- `model/ReadBook.kt`
  - `durChapterPos`
  - `setPageIndex(index)` 中通过 `curTextChapter?.getReadLength(index)` 保存章节内字符位置。
  - `durPageIndex` 通过 `curTextChapter?.getPageIndexByCharIndex(durChapterPos)` 从字符位置反查页码。

MoRealm 修改：

- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/ui/reader/ReaderScreen.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`

完成内容：

- `RenderedReaderChapter` 增加 `initialChapterPosition`，作为恢复锚点传入渲染器。
- `VisibleReaderPage` 增加 `chapterPosition`，当前页变化时回传 `TextPage.chapterPosition`。
- `saveProgress()` 保存 `ReadProgress.chapterPosition`，并同步写入 `Book.lastReadPosition`。
- 加载书籍时优先读取 `ReadProgress.chapterPosition`，没有记录时使用 `Book.lastReadPosition`。
- `CanvasRenderer` 恢复时优先调用 `TextChapter.getPageIndexByCharIndex(initialChapterPosition)`，对齐 Legado `durPageIndex`。
- `ScrollRenderer` 恢复时优先用 `initialChapterPosition` 在当前章节页窗口中反查页索引。
- 整数百分比 `_scrollProgress` 只保留为旧数据兜底和进度条显示；旧数据兜底从向下取整改为四舍五入，避免旧记录继续明显前跳。

当前状态：**代码路径已按 Legado `durChapterPos -> getPageIndexByCharIndex()` 模型修复，但 LDPlayer 高页码回归未通过；`37/38` 退出重进仍会回退，详见后续回归记录**。

验证：`git diff --check` 通过；`assembleDebug` 已通过。

## 2026-04-26 LDPlayer 回归记录：五种翻页模式与仿真翻页过渡

测试环境：

- 模拟器：LDPlayer，`emulator-5554`
- 包名：`com.morealm.app.debug`
- 进程：本轮测试前后均为 `14616`
- 自动化：UIAutomator2 负责极速 dump/截图；输入使用 `adb shell input` 兜底，避免 U2 注入权限异常影响结果。
- 测试脚本：`test-artifacts/continued/postdoc-qa-20260426-1450/u2_page_mode_regression.py`

覆盖模式：

- `平移`
- `覆盖`
- `仿真`
- `上下滚动`
- `无动画`

测试动作：

- 从书架进入 `Overlord 01 不死者之王`。
- 打开阅读设置，依据 XML 中真实文字节点 bounds 点击 `平移/覆盖/仿真/上下滚动/无动画`，避免固定坐标误点。
- 每种模式切换后执行快速翻页手势。
- 每种模式执行后再次点击中间区域，检查 topbar/bottom bar/menu 是否还能唤起。
- 全程保留 `logcat -v threadtime` 与 `logcat -b crash -v threadtime`。

结果摘要：

| 模式 | 阅读器仍可见 | 菜单仍可唤起 | 进程 | crash buffer |
| --- | --- | --- | --- | --- |
| 平移 | 是 | 是 | `14616` | 空 |
| 覆盖 | 是 | 是 | `14616` | 空 |
| 仿真 | 是 | 是 | `14616` | 空 |
| 上下滚动 | 是 | 是 | `14616` | 空 |
| 无动画 | 是 | 是 | `14616` | 空 |

说明：

- 本轮没有复现退桌面、主进程消失或 Java crash。
- 截图帧差中 `bright_blank` 指标在白底标题页/插图页上会产生误报，不能单独视为白屏 crash；最终以人工视觉帧和 crash buffer 共同判断。
- 五种模式的菜单入口在快速手势后仍可唤起，topbar/bottom bar 没有在该轮压力中永久消失。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-summary.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-final-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-final-crash.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-slide-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-cover-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-simulation-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-vertical-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-none-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-simulation-sheet-valid.png`

### 补充问题 39：仿真翻页折角出现后缺少自然衔接，后续直接跳到下一页

复现路径：

- 在阅读器中切换到 `仿真` 翻页动画。
- 位于 `Overlord 01 不死者之王` 图文页/标题页附近。
- 快速左右滑动触发仿真翻页。

用户同步反馈：

- “这个仿真翻页；出现这个角后；后面没用自然的衔接过渡；导致出现了角然后直接显示下一页；生硬。”

实测表现：

- 仿真翻页能够绘制出折角、背面阴影和下一页局部内容。
- 但折角出现后的 settle 阶段不连续：中间态之后没有继续沿贝塞尔折页轨迹自然滑出，也没有平滑回弹，而是直接切换到目标页。
- 本轮帧序列中，`62-page-mode-simulation-frame-03.png` 仍处于大折角覆盖状态；下一帧 `62-page-mode-simulation-frame-04.png` 已直接显示标题页，缺少可见过渡。
- 该问题会让仿真模式看起来像“先出现一张折角贴图，然后瞬间跳页”，与 Legado 仿真翻页的连续拖拽/动画体验不一致。
- `62-page-mode-simulation-metrics.tsv` 中多帧出现大幅跳变，最大 `diff_mean = 106.90`，和视觉上的硬切换一致。
- `logcat -b crash` 为空，说明这是仿真翻页动画状态/绘制连续性问题，不是崩溃。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-simulation-frame-03.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-simulation-frame-04.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-simulation-sheet-valid.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-simulation-metrics.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/62-page-mode-final-crash.txt`

建议修复方向：

- 对照 Legado `SimulationPageDelegate` 的 `onAnimStart/onAnimRunning/onAnimStop` 状态推进，确认 MoRealm 当前是否只绘制了拖拽中间帧，而没有完整执行完成动画。
- 仿真翻页释放手指后，应继续驱动 touch point / corner point 到屏幕边界，直到折角完全退出或回弹完成，再提交当前页索引。
- 页索引提交不要早于动画收尾，否则会出现折角帧后直接换页。
- 图文页 bitmap 与标题页 bitmap 的切换要和动画帧提交同步，避免中间折角里出现旧页/新页混合后瞬间硬切。

修复回归：

- 已修改 `PageAnimations.kt`：仿真翻页收尾绘制不再把动画触摸点强行夹在屏幕内，允许 touch point 像 Legado `Scroller` 一样继续移动到屏幕外。
- 已修改 `SimulationDrawHelper.kt`：`setTouchPoint` 只规避精确 `0f`，不再把负数或超出屏幕的动画坐标夹回 `0.1f`。
- 翻页完成时先把目标页 bitmap 提升为静态页，再退出拖拽绘制状态，避免动画结束后的旧页回闪。
- `:app:assembleDebug` 已通过，并已重新安装到 LDPlayer。
- U2 截图采样太慢，单帧截图无法稳定覆盖 500ms 收尾动画；因此追加 `screenrecord` 视频抽帧验证。
- `68-simulation-after-fix-sheet.png` 显示 009-019 帧连续折页推进，020 帧才进入目标页，不再是折角帧后直接硬切。
- `68-simulation-after-fix-crash.txt` 为空；仿真模式回归后 topbar/bottombar 仍可唤起。

新增证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/67-simulation-postfix/67-simulation-postfix-summary.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/67-simulation-postfix/67-page-mode-simulation-sheet.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/67-simulation-postfix/62-page-mode-final-crash.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/68-simulation-video/68-simulation-after-fix.mp4`
- `test-artifacts/continued/postdoc-qa-20260426-1450/68-simulation-video/68-simulation-after-fix-sheet.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/68-simulation-video/68-simulation-after-fix-crash.txt`

修复状态：**已修复并通过本轮 LDPlayer 仿真翻页回归；后续仍建议在普通正文页、图文页、章节边界各追加一次慢速/快速手势复测**。

## 2026-04-26 LDPlayer 回归记录：阅读进度退出重进

测试环境：

- 模拟器：LDPlayer，`emulator-5554`
- 包名：`com.morealm.app.debug`
- 进程：本轮进度复测前后均为 `14616`
- 自动化：UIAutomator2 dump/截图，输入使用 `adb shell input`

### 窄样本：`Overlord 01 不死者之王` 中低页码退出重进通过

测试脚本：

- `test-artifacts/continued/postdoc-qa-20260426-1450/u2_progress_reentry_current_book.py`

测试路径：

- 当前阅读器内打开 `Overlord 01 不死者之王`。
- 翻到非首页。
- 退出到书架/文件夹。
- 点击同一本书重进。

实测结果：

- 退出前：`3/5  5.5%`
- 重进后：`3/5  5.5%`
- 状态：`same_page`
- `logcat -b crash` 为空。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/63-progress-reentry-current-summary.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/63-progress-reentry-01-before-exit.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/63-progress-reentry-03-after-reopen.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/63-progress-reentry-current-crash.txt`

结论：

- 低页码/短章节样本可以保住进度。
- 该样本不能证明此前 `37/38 -> 36/38` 高页码问题已经修复。

### 高页码样本：`Overlord 05 王国好汉 上` 接近章节尾部退出重进仍回退

测试脚本：

- `test-artifacts/continued/postdoc-qa-20260426-1450/u2_progress_reentry_named_book.py`

测试命令：

```powershell
$env:PYTHONIOENCODING='utf-8'
python 'D:\temp_build\MoRealm\test-artifacts\continued\postdoc-qa-20260426-1450\u2_progress_reentry_named_book.py' --book 'Overlord 05' --target-page 37 --max-turns 50
```

测试路径：

- 从 `overlord1-14` 文件夹打开 `Overlord 05 王国好汉 上`。
- 打开时已有记录：`16/38  14.2%`。
- 连续翻到接近章节尾部：`37/38  19.7%`。
- 停留并等待进度保存。
- 返回文件夹。
- 点击同一本书重进。

实测结果：

- 退出前：`37/38  19.7%`
- 重进后：`31/38  18.2%`
- 状态：`regressed`
- 同一主进程仍为 `14616`，`logcat -b crash` 为空。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/64-progress-named-summary.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/64-progress-named-before-exit.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/64-progress-named-after-reopen.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/64-progress-named-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/64-progress-named-crash.txt`

当前判断：

- 用户反馈的“有进度的书退出再进入直接乱套/回退”在当前 debug APK 仍然存在。
- 新的 `chapterPosition` 保存/恢复代码没有覆盖该高页码场景，或者最终落库的 `chapterPosition` 仍然滞后于真实可见页。
- 本轮从 `37/38` 回退到 `31/38`，比此前 `37/38 -> 36/38` 样本更明显。
- 低页码样本通过，说明问题不是所有退出重进都失败，而是更集中在快速翻页后、接近章节尾部、或当前页位置保存时机滞后的场景。

建议继续排查：

- 检查 `ReaderViewModel.onVisiblePageChanged(...)` 是否在每次 `upContent(relativePosition=1)` 后立刻收到最新 `TextPage.chapterPosition`。
- 检查 `queueProgressSave(force = false)` 的 debounce 是否导致退出前只保存了旧页位置。
- 退出阅读器、Activity pause/stop、返回书架时应强制保存当前可见页 `chapterPosition`，不能只依赖最近一次异步队列。
- 对 `PageAnimType.NONE/SIMULATION/SLIDE` 分别验证 `VisibleReaderPage.chapterPosition` 是否和页脚 `37/38` 一致。

修复状态：**回归未通过，不能从待修复列表中删除**。

## 2026-04-26 LDPlayer 回归记录：书源搜索、10 个结果点击与最后可见项点击

测试环境：

- 模拟器：LDPlayer，`emulator-5554`
- 包名：`com.morealm.app.debug`
- 进程：本轮书源测试前后均为 `14616`
- 自动化：UIAutomator2 dump/截图，输入使用 `adb shell input`
- 测试脚本：`test-artifacts/continued/postdoc-qa-20260426-1450/u2_source_search_click_10.py`

测试路径：

- 进入底部 `发现`。
- 搜索关键词：`love`。
- 等待搜索完成。
- 顺序点击在线结果列表中的 10 个结果。
- 额外点击当前屏幕最后一个可见结果，覆盖“最后一本点击不了”的可见底部项路径。

搜索结果概况：

- 搜索完成：是。
- 搜索页显示：`找到 113 条 · 失败 30 个`。
- 书源进度：`64/64`。
- 在线结果：`在线 (98)`。
- `logcat -b crash` 为空。

10 个结果点击概况：

| 序号 | 来源 | 点击后状态 | 备注 |
| --- | --- | --- | --- |
| 1 | `..Lofter` | 进入阅读器 | 标题 `love`，`1/2 50.0%`。 |
| 2 | `..Lofter` | 进入阅读器 | 标题 `love`，`1/2 50.0%`。 |
| 3 | `..Lofter` | 进入阅读器 | 标题 `love`，`1/2 50.0%`。 |
| 4 | `..长佩文学` | 进入阅读器错误页 | 标题 `书源无章节`，`1/1 100.0%`。 |
| 5 | `..长佩文学` | 进入阅读器错误页 | 标题 `书源无章节`，`1/1 0.0%`。 |
| 6 | `..长佩文学` | 进入阅读器错误页 | 标题 `书源无章节`，`1/1 100.0%`。 |
| 7 | `..长佩文学` | 进入阅读器错误页 | 标题 `书源无章节`，`1/1 100.0%`。 |
| 8 | `..四零二零` | 进入阅读器 | 标题 `素夕丹的作品列表`，`1/2 0.2%`。 |
| 9 | `..Lofter` | 进入阅读器 | 标题 `【艾灰/羊守5】 I know you don't`，`1/2 50.0%`。 |
| 10 | `..Lofter` | 进入阅读器 | 标题 `【柱斑向/宇智波斑生贺】I love you so`，`1/2 50.0%`。 |

最后可见项点击：

- 当前屏最后可见结果：`<p id="p_ulmje3p0eo">参考官方问答</p>`，来源 `..Lofter`。
- 点击坐标：`(540, 1446)`。
- 点击后进入阅读器，标题 `I love you`，`1/2 50.0%`。
- 本轮未复现“当前屏最后可见结果点击不了”。
- 注意：这只覆盖“当前屏最后可见项”，还没有滚到完整搜索列表的最后一条结果。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/65-source-search-click-10-summary.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-search-complete.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-search-complete-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-search-click-01-after-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-search-click-04-after-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-search-click-10-after-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-search-last-visible-after-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-source-search-click-10-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/65-source-search-click-10-crash.txt`

当前判断：

- `Lofter` 结果点击进入阅读器路径已有改善，本轮没有复现此前 `startBrowser is not a function` 直接打断的路径。
- 搜索结果卡片点击区域在本轮可用，包括当前屏最后一个可见项。
- 但“点击能进入阅读器”不等于书源完整可读：`长佩文学` 的多条结果仍落到 `书源无章节` 错误页。
- 搜索阶段仍有 30 个书源失败，数量偏高。

### 补充问题 40：书源 JS API 兼容仍有缺口，`ajax/post/md5Encode/encodeURI` 等函数缺失

本轮日志新增暴露：

- `TypeError: 在对象 AnalyzeUrl 中找不到函数 ajax`
- `TypeError: 在对象 AnalyzeUrl 中找不到函数 post`
- `TypeError: 在对象 AnalyzeUrl 中找不到函数 md5Encode`
- `TypeError: 在对象 AnalyzeUrl 中找不到函数 encodeURI`
- `json string can not be null or empty`
- `java.security.cert.CertPathValidatorException: Trust anchor for certification path not found`
- `Unable to resolve host`

影响：

- 当前 `startBrowser/startBrowserAwait` 缺口已经不再是本轮 `love` 搜索的主要失败项。
- 书源兼容仍未达到 Legado 级别，很多书源依赖的 JS 辅助函数、编码/摘要函数、HTTP post/ajax 入口仍缺。
- 搜索页只显示失败数量，用户需要展开才能看到细节；点击结果后如果无章节，会进入阅读器错误页，仍不是完整的成功加载。

建议修复方向：

- 对照 Legado 的 JS 扩展集合继续补齐 `ajax`、`post`、`md5Encode`、`encodeURI` 等常用函数。
- 对失败书源做聚类统计，把“缺函数”“证书失败”“DNS 失败”“空 JSON”分开展示，便于判断是兼容缺口还是网络/站点问题。
- 对 `书源无章节` 错误页保留原始来源、书源名、bookUrl 和章节解析错误，避免只显示泛化标题。

修复状态：**部分改善但未完全修复；10 个结果点击路径可达，书源兼容和无章节错误仍存在**。

## 2026-04-26 LDPlayer 补充回归：书源缓存下载与离线缓存页

测试脚本：

- `test-artifacts/continued/postdoc-qa-20260426-1450/u2_source_download_smoke.py`

测试路径：

- 在 `love` 搜索结果页点击前两条可见结果右侧的 `缓存下载`。
- 从 `我的/个人` 页进入 `离线缓存`。
- 展开第一本网络书籍，点击 `全部缓存`。
- 采集 UI tree、截图、普通 logcat 和 crash buffer。

自动化注意事项：

- 脚本点击搜索结果页右侧 `缓存下载` 按钮后，搜索页没有明显成功/失败反馈，只能从后续离线缓存列表确认是否加入。
- 脚本第一次进入离线缓存页时点到了文字中心，未触发卡片；手动点击卡片区域后成功进入 `离线缓存`。这说明个人页列表项实际可点区域和文字节点中心仍可能不一致，后续可单独做命中区域检查。
- 初始 `66-source-download-smoke-crash.txt` 中出现 `FORTIFY: pthread_mutex_lock called on a destroyed mutex`，PID 为 `23962`，不是当时 MoRealm 主进程 `14616`；结合手动复测后的 crash buffer 为空，本条不计为 MoRealm 闪退。

实测 UI：

- 离线缓存页可打开，显示 `共 21 本网络书籍`。
- 列表中能看到搜索添加的网络书籍，包括 `I love you`、`【柱斑向/宇智波斑生贺】I love you so`、`【艾灰/羊守5】 I know you don't love me`、`love的紫藤园` 等。
- 展开第一项后显示 `全部缓存`、`从当前章`、`清除`。
- 点击第一项 `全部缓存` 后，UI 仍显示 `已缓存 0/1 (0%)`。
- 列表中还存在 `已缓存 0/224 (0%)`、`已缓存 0/0 (0%)` 等状态，但没有在列表项上直接展示失败原因。

日志结果：

- `CacheService: Download ch0 failed: ... JsExtensions ... 找不到函数 startBrowser`
- `CacheService: Download complete: 0 ok, 1 failed, 0 cached`
- 手动缓存复测后的 `66-download-cache-after-all-crash.txt` 为空。

证据文件：

- `test-artifacts/continued/postdoc-qa-20260426-1450/66-source-download-smoke-summary.tsv`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-open-manual.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-open-manual.xml`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-expanded.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-after-all.png`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-after-all-nodes.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-after-all-logcat.txt`
- `test-artifacts/continued/postdoc-qa-20260426-1450/66-download-cache-after-all-crash.txt`

### 补充问题 41：缓存下载路径仍缺 `JsExtensions.startBrowser`，失败原因没有落到 UI

当前判断：

- 搜索/阅读路径已经部分绕过或补齐 `startBrowser/startBrowserAwait`，但缓存下载服务使用的 JS 环境仍不完整。
- 缓存服务调用同一书源脚本时仍报 `JsExtensions.startBrowser` 缺失，导致章节缓存 `0 ok, 1 failed, 0 cached`。
- 用户在离线缓存页只能看到 `已缓存 0/1 (0%)`，无法知道是书源函数缺失、章节为空、网络错误还是站点失败。
- 这会影响“下载书本”功能的可用性：书能加入缓存列表，但不代表章节实际缓存成功。

建议修复方向：

- 统一搜索、阅读、缓存三条路径使用的 JS 扩展环境，避免 `CacheBookService` 和 `AnalyzeUrl` 可用函数集不一致。
- 补齐或代理 `JsExtensions.startBrowser/startBrowserAwait` 在缓存场景下的行为；如果缓存后台不应打开浏览器，也应返回兼容值或明确失败类型。
- `CacheBookScreen` 对失败任务展示最近失败原因、失败章节数和重试入口，不要只停留在 `0%`。
- 对 `0/0`、`0/1`、`0/224` 三类列表项分别复测：无章节、单章节、多章节缓存失败时 UI 表达应不同。

修复状态：**未修复；缓存页可打开，网络书籍可加入，但实际章节缓存失败且失败原因未展示给用户**。

## 2026-04-26 修复记录：按 Legado 反转章节边界页污染

触发背景：

- 用户澄清此前连续 `Loaded chapter ...` 日志不是自动连跳，而是多次点击章节按钮后画面没有稳定切换。
- 同一批回归中还出现过页眉页脚丢失、上一章/下一章按钮不生效、无法向上翻页、滚动模式被污染、进度退出重进回退等问题。

Legado 对照结论：

- Legado `TextPageFactory` 的普通页状态只持有当前章节的 `pageIndex`。
- `prevPage` / `nextPage` 可以返回上一章末页或下一章首页，用于 `ReadView` 的相邻页面视图和动画截图。
- 上一章/下一章预览页不会被插入当前章节的页列表。
- `ReadView.fillPage(direction)` 是动画结束后的唯一提交路径；跨章时由 `moveToPrev()` / `moveToNext()` 直接更新章节状态，再 `upContent()`。

MoRealm 本次定位到的污染点：

- `ReaderPageFactory.pages` 之前把上一章末页、当前章节页、下一章首页混在一个 display list 里。
- `CanvasRenderer`、`SimulationPager`、普通 `HorizontalPager`、滚动模式和进度保存都可能读取这套混合 display index。
- 即使 ViewModel 已经加载新章节，渲染层仍可能用旧 `lastReaderContent` 或边界 display index 回写可见页/进度，造成“点了章节但画面没切换”、回退、页眉页脚异常。

本次修复：

- `ReaderPageFactory.pages` 改为只暴露当前章节页；上一章/下一章只通过 `prevPage`、`nextPage`、`nextPlusPage` 和 `pageForTurn()` 作为预览页。
- 删除 `currentOffset`、`prevBoundaryPage`、`nextBoundaryPage` 这类会改变 display index 的边界页模型。
- `ReaderPageState.fillPage()` 在章首/章末跨章时返回 `boundaryDirection`，只触发章节提交，不再把旧章节内容写入 `lastReaderContent`。
- `CanvasRenderer.fillPageFrom()` 收到边界提交后立即停止 delegate 状态，不再调用 `upProgressFrom()` 保存旧章节页。
- 非滚动模式在章节 key 变化时强制清空旧 `lastReaderContent`，并把 Compose Pager 拉回 0，避免上一章的越界页索引继续参与新章绘制。
- `SimulationParams` 新增 `pageForTurn()`，仿真翻页可以继续用 `prevPage` / `nextPage` 渲染预览 bitmap，但预览页不进入普通页列表。
- 滚动模式继续使用 `currentChapterPages`，不再接触跨章预览 display list。

已验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- APK 已覆盖安装到 LDPlayer：`D:\temp_build\MoRealm\app\build\outputs\apk\debug\app-debug.apk`。

待复测重点：

- 底部 `上一章` / `下一章` 按钮：点击一次应只提交一个章节，画面标题和正文必须跟随切换。
- 普通翻页在章末/章首跨章：不应保存旧页进度，不应回到旧章节顶部。
- 有进度书籍向上翻页后退出重进：重点确认此前 `37/38 -> 31/38` 类回退是否消失。
- 仿真翻页章末/章首：预览动画可以使用相邻章 bitmap，但提交后不能依赖预览页 display index。
- 上下滚动模式：只能在当前章节页内滚动，触底后由章节提交逻辑切换，不应被上一章/下一章预览页污染。

修复状态：**已修复并打包安装，仍需按上述路径做 LDPlayer 高强度复测后再从待测列表删除相关问题**。
