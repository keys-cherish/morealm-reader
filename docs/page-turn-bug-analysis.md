# 翻页模式 Bug 分析与修复记录

日期：2026-04-27
对照项目：sigma/legado (io.legado.app)

## 症状

1. **滑动翻页瞬移到其他章节** — 翻页后跳到章节开头或其他位置
2. **仿真翻页重复同一页** — 翻页动画正常播放但页面不变，始终在第1→2页循环
3. **卡在一页无法翻动** — 有动画但页面不前进，后续点击翻页全部无效
4. **仿真翻页问题穿透到其他模式** — 从仿真模式切换到滑动模式后出现异常
5. **仿真翻页往前（PREV）方向动画卡住** — 动画停在中间不自动完成

## 第一层根因（表层问题）

### 根因 A: `pageDelegateState.isRunning` 卡死

**文件:** `CanvasRenderer.kt`

点击(tap)翻页不调用 `onDown()` 重置状态，如果上一次动画的 `isRunning` 未清除，所有后续 tap 被拒绝。

**修复:** tap 手势入口添加 `pageDelegateState.onDown()`。

### 根因 B: 仿真翻页 `fillPageFrom` 返回 null 回退到同一页

**文件:** `PageAnimations.kt`

`params.onFillPage(...) ?: startDisplayIndex` 在章末/布局未完成时回退到同一页。

**修复:** 返回 null 时跳过 `pagerState.scrollToPage`，不做无效回退。

### 根因 C: 翻页状态不随模式切换重置

**文件:** `CanvasRenderer.kt`

`remember(chapterIndex)` 缺少 `pageAnimType` key，模式切换不重建状态。

**修复:** 所有翻页相关状态的 `remember` key 加入 `pageAnimType`。

### 根因 D: `lastSettledDisplayPage` 与 `pagerState.currentPage` 失同步

**文件:** `CanvasRenderer.kt`

多处独立更新 `lastSettledDisplayPage`，与 pagerState 脱节导致翻页从错误起点开始。

**修复:** `animateByDirection` / `dragByDirection` 入口同步。

---

## 第二层根因（核心问题）— 仿真翻页循环 bug

修复 A-D 后，仿真翻页仍然在第1→2页循环。日志确认：

```
fillPageFrom ENTER | displayIndex=0 | lastSettled=1 | pager=0 | readerPageIndex=1
fillPageFrom(start=0, committed=1)  ← 永远从 0 开始，永远提交到 1
```

### 根因 E: Compose `pointerInput` 闭包值捕获陈旧（最核心问题）

**文件:** `PageAnimations.kt` — SimulationPager

**机制:**
```kotlin
val displayPage = params.currentDisplayIndex()  // 组合时正确 = 1
.pointerInput(displayPage, pageCount) {
    detectDragGestures(
        onDragStart = {
            turnStartDisplayIndex = displayPage  // ← 闭包捕获的是旧值 0！
        }
    )
}
```

Compose `pointerInput` 的闭包在创建时**值捕获**局部变量。即使 `displayPage` 在重组时更新为 1，已运行的 `pointerInput` 协程中的闭包仍然持有旧值 0。`pointerInput` 虽然以 `displayPage` 为 key 触发重建，但由于时序问题（翻页动画还在运行时 `dragState != IDLE`，新协程的 `onDragStart` 被守卫跳过），实际效果是旧值一直被使用。

**Legado 为什么没有这个问题：**
Legado 使用 Android View 系统，状态存储在类字段（`mDirection`、`isMoved`、`isRunning`）中，通过 `onTouch()` 事件直接读取，不存在闭包捕获问题。

**修复（标准 Compose 模式）：**
```kotlin
val currentPage by rememberUpdatedState(displayPage)
val currentParams by rememberUpdatedState(params)

.pointerInput(Unit) {  // 不再以 displayPage 为 key
    detectDragGestures(
        onDragStart = {
            turnStartDisplayIndex = currentPage  // 通过 State 始终读最新值
        }
    )
}
```

### 根因 F: `onDragStart` 用 `if (dragState == IDLE)` 守卫，脏状态延续

**文件:** `PageAnimations.kt`

```kotlin
onDragStart = { offset ->
    if (dragState == DragState.IDLE) {  // ← 前一个动画的 dragState 仍为 DRAGGING_NEXT
        turnStartDisplayIndex = displayPage  // 被跳过！
    }
}
```

如果上一次翻页的协程动画尚未完成（`dragState` 仍为 `DRAGGING_NEXT`），新手势的 `onDragStart` 不会更新 `turnStartDisplayIndex`，导致下次翻页使用旧起点。

**Legado 对照（关键区别）：**
```kotlin
// HorizontalPageDelegate.onTouch()
ACTION_DOWN -> {
    abortAnim()   // ← 无条件中止动画
}

// abortAnim()
override fun abortAnim() {
    isStarted = false
    isMoved = false
    isRunning = false
    if (!scroller.isFinished) {
        scroller.abortAnimation()
        if (!isCancel) readView.fillPage(mDirection)  // 强制提交
    }
}
```

**Legado 的设计：每次新触摸都无条件 abort + 全量重置。** 不存在 "上一个动画的脏状态阻塞下一个手势" 的问题。

**修复（移植 Legado 模式）：**
```kotlin
onDragStart = { offset ->
    // 无条件 abort（匹配 Legado ACTION_DOWN → abortAnim() + onDown()）
    turnJob?.cancel()
    isAnimating = false
    dragState = DragState.IDLE
    isPointerDown = true
    turnStartDisplayIndex = currentPage
    clearTurnBitmaps()
    // ...
}
```

---

## 修改清单

| 文件 | 修改 | 对应根因 |
|------|------|---------|
| `CanvasRenderer.kt` | tap 手势添加 `onDown()` | A |
| `CanvasRenderer.kt` | 翻页状态 key 加入 `pageAnimType` | C |
| `CanvasRenderer.kt` | `lastSettledDisplayPage` 同步 pagerState | D |
| `PageAnimations.kt` | 4处 `?: startDisplayIndex` 回退改为 null 检查 | B |
| `PageAnimations.kt` | `rememberUpdatedState` 包装 `displayPage`/`params` | E |
| `PageAnimations.kt` | `pointerInput` key 改为 `Unit`（不再依赖 displayPage） | E |
| `PageAnimations.kt` | `onDragStart`/`animatePageTurn` 无条件 abort + 重置 | F |
| `PageAnimations.kt` | `SimulationPager` 新增 `currentDisplayPage` 直接参数 | E |

---

## 复盘：为什么修了好几轮才修好

### 问题定位困难的根本原因

1. **Compose 闭包陈旧值问题极其隐蔽。** 代码在静态审查中看起来完全正确（`displayPage` 确实在重组时更新了），但运行时 `pointerInput` 协程持有的是旧闭包。这类 bug 在传统 View 系统中不存在（Legado 免疫），是 Compose 特有的陷阱。

2. **状态来源过于分散。** `displayPage` 的值经过 `lastSettledDisplayPage` → `simulationParams.currentDisplayIndex()` (lambda 闭包) → `SimulationPager.displayPage` → `pointerInput` 闭包 → `turnStartDisplayIndex` 五层传递。任何一层都可能引入陈旧值。Legado 只有 `ReadBook.durPageIndex` → `pageFactory.pageIndex` 两层。

3. **缺少关键状态的日志。** 第一轮修复时没有在 `fillPageFrom` 和 `animatePageTurn` 中打印 `displayPage`/`lastSettled`/`pagerState` 的完整快照。加了日志后，问题在一轮内定位到。

### 经验教训

| 教训 | 具体措施 |
|------|---------|
| **Compose `pointerInput` 中永远用 `rememberUpdatedState`** | 所有在 `pointerInput` 闭包内读取的外部值，必须通过 `rememberUpdatedState` 包装。这是 Compose 官方推荐模式。 |
| **移植 Legado 时尊重其状态管理哲学** | Legado 用"每次触摸无条件全量重置"避免脏状态，MoRealm 不应用条件守卫"优化"这个行为。 |
| **先加日志再修代码** | 面对状态追踪类 bug，第一步应该是加关键状态快照日志，而不是基于推理直接修改代码。 |
| **状态传递层数越少越好** | 五层传递的值比两层更容易出 bug。后续应考虑减少中间层（如直接传 `lastSettledDisplayPage` 的 State 对象，而非通过 lambda 闭包间接读取）。 |

---

## 验证方式

1. 仿真模式连续向后翻页 10+ 次，页码递增不循环
2. 仿真模式连续向前翻页回到第一页
3. 从仿真切换到滑动模式后翻页正常
4. 滑动模式翻页不瞬移
5. 快速连续点击翻页不卡住
