# 上下滑动卡在某位置问题：Legado 对比分析

## 结论摘要

当前现象不是界面卡死，而是阅读器进入了“仍可向上退，但向下被判定为已到底/无下一页”的状态。结合代码对比，最可疑且最符合现象的根因在 MoRealm 的连续滚动实现：

1. MoRealm 将多章内容拼接成一个大 `content`，然后整段重新分页；Legado 则始终维护“当前页/下一页/下下一页”的窗口，跨页时通过 `moveToNext(upContent = true)` 切换数据源。
2. MoRealm 在 `ScrollRenderer.applyScroll()` 中只有一次边界迁移判断。快速 fling 的单帧 delta 可能跨过多页，但代码最多只移动一页，留下越界的 `pageOffset`。下一次向下滑时更容易被底部保护逻辑截住。
3. MoRealm 追加下一章是异步的，但滚动层没有“下一章正在追加/排版中”的状态。用户快速滑到底时，`currentPageIndex >= pageCount - 1` 会按“真实底部”处理，将 `pageOffset` clamp 到最后一页底部；如果追加尚未完成或分页尚未完成，就表现为“只能向上，不能继续向下”。
4. 日志目前只记录 `Loaded chapter` / `Appended chapter`，没有记录滚动状态机关键变量，所以出现“向下被挡住”的当下没有日志，这是诊断缺口。

## Legado 的严肃分析

### 1. 滚动不是一个无限长文档

Legado 的核心实现位于：

- `D:\temp_build\sigma\legado\app\src\main\java\io\legado\app\ui\book\read\page\delegate\ScrollPageDelegate.kt`
- `D:\temp_build\sigma\legado\app\src\main\java\io\legado\app\ui\book\read\page\ContentTextView.kt`
- `D:\temp_build\sigma\legado\app\src\main\java\io\legado\app\ui\book\read\page\provider\TextPageFactory.kt`

`ScrollPageDelegate` 只负责触摸和惯性。真正改变阅读位置的是 `ContentTextView.scroll(mOffset)`。它维护一个 `pageOffset`，规则是：

- `pageOffset` 变小：内容向上，读者向后读。
- `pageOffset` 变大：内容向下，读者向前退。
- 当 `pageOffset < -textPage.height` 时，调用 `pageFactory.moveToNext(upContent = true)`。
- 当 `pageOffset > 0` 时，调用 `pageFactory.moveToPrev(true)`。

Legado 的关键点是：`pageFactory` 不是简单的 `List<TextPage>` 索引，而是和章节数据源绑定。`TextPageFactory.hasNext()` 返回：

```kotlin
hasNextChapter() || currentChapter?.isLastIndex(pageIndex) != true
```

也就是说，Legado 判断“还能不能向下滚”时，不只看当前已排版页列表，还看下一章是否存在、下一章是否已完成/可用。

### 2. Legado 有明确的边界保护

`ContentTextView.scroll()` 的边界顺序非常重要：

1. 没有上一页且 `pageOffset > 0`：回弹到顶部并中止动画。
2. 没有下一页且最后页内容不足一屏：clamp 到最后可见位置并中止动画。
3. `pageOffset > 0`：尝试 `moveToPrev(true)`。
4. `pageOffset < -textPage.height`：尝试 `moveToNext(upContent = true)`。

这套逻辑的前提是 `pageFactory.hasNext()` 的语义可靠：如果下一章存在但还没准备好，`moveToNext()` 会返回 false，动画会被中止；但数据源准备好后，`hasNext()`/`nextPage` 又能自然恢复。它没有把多个章节拼成一个不断变化的大字符串。

### 3. Legado 的绘制窗口是有限的

`ContentTextView.drawPage()` 最多绘制：

- 当前页 `textPage`
- 下一页 `nextPage`
- 下下一页 `nextPlusPage`

它不是在当前章节内容末尾追加字符串，也不会因追加章节导致整个章节重新分页、页数和页高大范围重算。

## MoRealm 当前实现

相关文件：

- `app/src/main/java/com/morealm/app/ui/reader/renderer/ScrollRenderer.kt`
- `app/src/main/java/com/morealm/app/ui/reader/renderer/CanvasRenderer.kt`
- `app/src/main/java/com/morealm/app/presentation/reader/ReaderViewModel.kt`
- `app/src/main/java/com/morealm/app/domain/render/ChapterProvider.kt`

### 1. 多章被拼接成一个大章节

`ReaderViewModel.loadChapter()` 在滚动模式下：

- 清空 `continuousContent`
- 添加当前章 `buildChapterBlock(...)`
- 设置 `_loadedChapterRange = index..index`
- 立即调用 `appendNextChapterForScroll(index + 1)`

`appendNextChapterForScroll()` 之后继续把下一章 HTML 追加到同一个 `continuousContent`，并只更新：

```kotlin
_renderedChapter.value = _renderedChapter.value.copy(content = renderedContent)
```

这意味着 UI 看到的是同一个 `chapterIndex` 下的内容变长。`CanvasRenderer` 对 `content` 改变后会重新执行 `layoutChapterAsync()`，把“多章合集”当成一个 `TextChapter` 重新分页。

这和 Legado 的当前页窗口模型完全不同。

### 2. `ScrollRenderer` 只做一次边界迁移

`ScrollRenderer.applyScroll(delta)` 中：

```kotlin
pageOffset += delta
...
else if (pageOffset < -pageHeight(currentPageIndex)) {
    val height = pageHeight(currentPageIndex)
    if (currentPageIndex < pageCount - 1) {
        currentPageIndex++
        pageOffset += height
    } else {
        pageOffset = -height
        onNearBottom()
        return
    }
}
```

这里和快速滑动的冲突是：一次 fling 帧的 `delta` 可能远大于一页高度，但代码最多把 `currentPageIndex` 加 1。假设用户快速向下滑，`pageOffset` 可能从 `-100` 直接变成 `-2600`，一页高约 900，则代码只移动到下一页并变成 `-1700`，仍然越过了新页底部。下一帧再继续处理时，可能反复处于越界状态，直到遇到当前 `pageCount - 1`，然后被 clamp 成最后页底部。

Legado 原始代码也是单次判断，但它的 `Scroller`/`setTouchPoint` 是 View 事件驱动，且 `moveToNext()` 同步更新当前页窗口；MoRealm 用 Compose `Animatable.animateDecay`，单帧 delta 更容易很大，并且 `pages` 列表还会被异步追加/重新排版。

因此 MoRealm 不能照搬单次 `if`，需要用 `while` 或把 delta 切片，直到 `pageOffset` 回到当前页合法区间，或明确遇到真实边界。

### 3. “底部”判断和异步追加竞争

`applyScroll()` 在最后一页向下时：

```kotlin
currentPageIndex >= pageCount - 1 && pageOffset < 0f && pageOffset + pageHeight(currentPageIndex) < viewHeight
```

满足后会：

- clamp 到最后页底部
- `onNearBottom()`
- `onReachedBottom()`
- `return`

问题是此时的 `pageCount` 只是当前 `TextChapter` 已排出的页数，不等于书籍真实可向下滚动范围。下一章可能正在：

- `appendNextChapterForScroll()` 读取内容；
- `_renderedChapter.content` 已变但 `ChapterProvider.layoutChapterAsync()` 还没排完；
- `chapter?.isCompleted != true`，`CanvasRenderer` 又把 `layoutCompleted` 传给 `ScrollRenderer`，进度回调被抑制。

用户快速滑到排版尾部时，滚动层没有“正在加载下一章，请先不要当成真实底部”的概念，于是会把位置锁在当前已排版最后页。追加完成后，如果 `LaunchedEffect` 没有触发重新恢复合法滚动区，或者 `lastNearBottomRequestPageCount` 已经去重，就会出现“向上可以、向下不动”的体验。

### 4. `onNearBottom` 去重可能放大问题

`ScrollRenderer` 有两个去重变量：

- `lastNearBottomRequestPageCount`
- `lastNearBottomPageCount`

它们以 `pageCount` 为 key。问题是 `pageCount` 是重新分页后的页数，不是“已加载到第几章”。如果一次请求发生在旧 `pageCount`，但追加失败、追加慢、或排版期间又到达底部，同一个 `pageCount` 下不会再次触发 `onNearBottom()`。日志里只看到已经成功 append 的记录，看不到“本次 nearBottom 被去重、没有请求下一章”的情况。

### 5. 当前章节索引没有随滚动进入新章更新

`ReaderViewModel.onVisibleChapterChanged(index)` 存在，但当前滚动路径没有看到 `ScrollRenderer` 根据 `TextPage.chapterIndex` 回调它。因为多章被拼成同一个 `TextChapter(chapterIndex, title, chaptersSize)`，`ChapterProvider.layoutInternal()` 给每一页写入的 `chapterIndex` 仍是初始章节索引。

所以即使视觉上滚到了追加章节，ViewModel 的 `_currentChapterIndex` 也可能仍停留在起始章。这会影响：

- 进度保存；
- 下一章按钮行为；
- 预加载策略；
- 日志判断；
- 未来修复时的边界判断。

这不是“向下划不动”的唯一直接原因，但它说明 MoRealm 目前的“连续滚动多章模型”没有建立完整的章节状态机。

## 为什么 txt 和 epub 都会出现

该问题在 txt、epub 都存在，说明根因不在格式解析层，而在统一的阅读渲染/滚动层：

- txt 和 epub 最终都会进入 `ReaderViewModel` 的 `continuousContent` 拼接逻辑；
- 都会经过 `CanvasRenderer` 的 `layoutChapterAsync()`；
- 都会使用 `ScrollRenderer.applyScroll()` 的边界判断；
- 所以格式无关。

## 为什么日志没有记录核心错误

当前日志记录点主要是：

- `Loaded chapter X`
- `Appended chapter X for continuous scroll`
- `Failed to append chapter X`

但“无法继续向下滑”的关键状态发生在 `ScrollRenderer.applyScroll()`，这里没有日志。缺少的信息包括：

- 当前 `delta`
- `currentPageIndex`
- `pageOffset`
- `pageCount`
- 当前页高度 `pageHeight(currentPageIndex)`
- 是否触发 last-page clamp
- 是否调用 `onNearBottom()`
- 是否被 `lastNearBottomRequestPageCount` 去重
- `layoutCompleted`
- `_loadedChapterRange`
- `isAppendingChapter`
- `pendingAppendChapterIndex`

因此日志页只显示“成功追加过章节”，但不会显示真正卡住时滚动状态机为何拒绝继续向下。

## 真正问题定位

最可能的主因是两个问题叠加：

### 主因 A：快速滑动 delta 跨多页，但只迁移一页

`applyScroll()` 必须保证执行后状态满足以下不变量：

```text
-pageHeight(currentPageIndex) <= pageOffset <= 0
```

或在最后一页短页时满足：

```text
pageOffset >= min(0, viewHeight - pageHeight(lastPage))
```

当前实现不能保证这个不变量。快速 fling 后 `pageOffset` 可以长时间越界，从而在接近末尾时被错误 clamp。

### 主因 B：把“当前已排版页尾”误判为“全书/已加载内容真实底部”

MoRealm 的下一章追加和分页都是异步的，但 `ScrollRenderer` 没有收到“还有下一章正在加载/可加载”的信息。它只能看 `pageCount`，于是会在当前列表末尾执行底部保护。

Legado 不会只看当前页列表，它通过 `TextPageFactory.hasNext()` 同时看章节数据源。

## 建议修复方向

### 必须修复 1：`applyScroll()` 使用循环处理跨页 delta

把单次边界判断改成循环，直到 offset 回到合法范围。伪代码：

```kotlin
pageOffset += delta
var guard = 0
while (guard++ < 20) {
    val height = pageHeight(currentPageIndex)
    when {
        currentPageIndex == 0 && pageOffset > 0f -> {
            pageOffset = 0f
            abortFling()
            break
        }
        pageOffset > 0f && currentPageIndex > 0 -> {
            currentPageIndex--
            pageOffset -= pageHeight(currentPageIndex)
        }
        pageOffset < -height && currentPageIndex < pageCount - 1 -> {
            currentPageIndex++
            pageOffset += height
        }
        pageOffset < -height && currentPageIndex >= pageCount - 1 -> {
            requestMoreOrClamp()
            break
        }
        else -> break
    }
}
```

重点不是照抄伪代码，而是恢复 Legado 的状态不变量。

### 必须修复 2：区分“可加载更多”和“真实到底”

`ScrollRenderer` 需要一个来自 ViewModel 的状态，例如：

- `canLoadMoreChapters: Boolean`
- `isAppendingChapter: Boolean`
- `loadedChapterRange: IntRange`

当处于当前 `pageCount` 末尾时：

- 如果 `canLoadMoreChapters || isAppendingChapter`，应该触发 `onNearBottom()`，但不要立刻当成真实底部；
- 可以 clamp 到当前临时尾部，但需要在 `pageCount` 增长后允许再次向下滚；
- 不应触发 `onReachedBottom()`，除非 `_loadedChapterRange.last == chapters.lastIndex` 且布局完成。

### 必须修复 3：追加成功后重置 near-bottom 去重状态

去重 key 不应只用 `pageCount`。建议改为使用已加载章节末尾：

```text
lastNearBottomRequestLoadedLastIndex
```

或者在 `pageCount` 增长、`content` 变化、`loadedChapterRange` 变化时重置去重状态。

### 必须修复 4：记录滚动状态机日志

建议新增 debug 日志，只在关键分支记录，避免刷屏：

- 进入最后页 clamp：记录 `pageIndex/pageCount/pageOffset/pageHeight/viewHeight/layoutCompleted`。
- `onNearBottom()` 被触发：记录当前 `loadedRange`、`isAppendingChapter`、`pendingAppendChapterIndex`。
- `onNearBottom()` 被去重跳过：记录 skip 原因。
- `appendNextChapterForScroll()` 入口：记录 `nextIndex/range/isAppending/pending`。
- 追加成功：记录 `nextIndex/range/contentLength/renderedContentLength`。
- 追加失败：保留 error。
- `layoutChapterAsync` 完成：记录 `chapterIndex/contentHash/pageCount/isCompleted`。

这样下次复现时，日志页能直接看到是：

- append 没触发；
- append 触发但未完成；
- layout 未完成；
- pageOffset 越界；
- 被 last-page clamp；
- 被 near-bottom 去重。

## 不建议的修复

不建议只做以下表面修复：

1. 单纯增大预加载章节数。快速滑动仍可能越过当前已排版页尾。
2. 只在卡住时调用 `nextChapter()`。这会绕开连续滚动模型，仍然丢失滚动位置。
3. 只删掉 `lastNearBottomRequestPageCount`。可能造成重复追加、并发追加和内容乱序。
4. 只把 `onReachedBottom()` 注释掉。它不是主因，主因是底部判断和 offset 状态不变量。

## 推荐验证路径

1. 在 `ScrollRenderer.applyScroll()` 的最后页 clamp 分支打日志。
2. 快速向下滑动复现，确认是否出现：

```text
currentPageIndex == pageCount - 1
pageOffset + pageHeight < viewHeight
loadedRange.last < chapters.lastIndex 或 isAppendingChapter == true
```

如果出现，说明确实是“把临时已排版尾部当成真实底部”。

3. 再记录每次 `applyScroll()` 后的不变量：

```text
pageOffset < -pageHeight(currentPageIndex)
```

如果快速 fling 后出现该状态，说明跨多页 delta 没被完整消费。

## 与截图现象对应

截图日志显示：

```text
Loaded chapter 0: 前言
Appended chapter 1 for continuous scroll
Appended chapter 2 for continuous scroll
Appended chapter 3 for continuous scroll
```

这只证明“曾经成功追加过章节”，不能证明滚动层没有卡在当前已排版列表尾部。当前日志缺少 clamp、nearBottom skip、layout completed、pageCount 等信息，所以无法从日志页看出真正卡住原因。

用户描述“向下不动，但向上可以”正好符合 `ScrollRenderer` 被最后页底部保护 clamp 的行为，而不是主线程 ANR 或 Canvas 绘制卡死。
