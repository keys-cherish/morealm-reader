package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.ScrollParagraph
import com.morealm.app.domain.render.ScrollParagraphType
import com.morealm.app.domain.render.TextBaseColumn
import com.morealm.app.domain.render.calcChapterProgress
import com.morealm.app.domain.render.calcNewAnchorAfterPrepend
import com.morealm.app.domain.render.chapterPositionToParagraphPos
import com.morealm.app.domain.render.findItemIndex
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

/**
 * LazyColumn 段落级瀑布流渲染器 —— 段落作为 LazyColumn item，靠 prepend/append + 锚点
 * 修正实现跨章无缝衔接。
 *
 * ── 设计基调 ──
 *
 *   1. **粒度**：段落（[ScrollParagraph]，由 [com.morealm.app.domain.render.toScrollParagraphs]
 *      构建）。比页更细，比行更粗，瀑布流语义最自然
 *   2. **跨章**：caller 把 prev+cur+next 的段落扁平拼成一个 list 喂进来；滚到顶/底触发
 *      [onNearTop] / [onNearBottom] 让 caller 异步加载邻章并 prepend/append；prepend
 *      后通过 [prependedCount] + [prependToken] 信号触发我们做 [calcNewAnchorAfterPrepend]
 *      锚定补偿，视觉零跳动
 *   3. **现代实践**：CompositionLocal 取主题、snapshotFlow 处理高频滚动状态、
 *      derivedStateOf 派生低频状态、contentType 复用 item 视图结构
 *
 * ── 不在本组件里做的事（Phase 4 / Phase 5 补）──
 *
 *   - 选区拖把手 / 长按选词
 *   - TTS 朗读高亮 + 自动跟随
 *   - 搜索结果高亮
 *   - 用户高亮 / 书签三角
 *   - 自动滚动（autoScroll）
 *   - 音量键翻页 / 键盘导航
 *
 * 上述功能依赖坐标系迁移（TextPos → ParagraphTextPos），独立 PR 处理。Phase 2 主体
 * 先把"无缝瀑布流 + 滚动状态机外包给 Compose"这条主线打通。
 *
 * @param paragraphs 已扁平的段落窗口（含 prev+cur+next 三章）。caller 维护
 * @param initialIdx [androidx.compose.foundation.lazy.LazyListState] 构造时的初始 item
 *        index。caller 必须在 paragraphs **包含锚点章后**才挂载本 Composable
 *        （配合 `key(paragraphsReady)`），否则锚点会被锁在 0。详见
 *        [com.morealm.app.domain.render.calcInitialListStateParams]。
 * @param initialOffsetPx LazyListState 构造时的初始 item 内偏移（像素）
 * @param prependedCount 上一次 prepend 操作向窗口顶部加入的段数（用于锚定补偿）
 * @param prependToken 每次 prepend 完成换新值（System.nanoTime() 即可），即使 prependedCount
 *        数值与上次相同也能触发补偿。null/0 表示无 prepend
 * @param droppedFromFrontCount 上一次 drop-from-front 操作（如 commitChapterShiftNext
 *        让 prev 章被丢弃）从窗口顶部移除的段数，用于反向锚定补偿。
 * @param dropToken 每次 drop 完成换新值（System.nanoTime()），同样用作幂等 key。
 *        和 prependToken 互斥触发（一次 paragraphs 变化只可能是 prepend 或 drop 之一）。
 * @param ttsHighlightChapterIndex 当前 TTS 朗读位置所在的章 idx；< 0 表示未在朗读。
 *        Phase 4 引入：TTS 推进段落时让 LazyColumn 自动滚到对应段。
 * @param ttsHighlightChapterPosition 当前 TTS 朗读段落的章内字符偏移；< 0 表示未在朗读。
 *        与 [ttsHighlightChapterIndex] 配套，用 [chapterPositionToParagraphPos] 解析为
 *        段编号，再 [findItemIndex] 拿到 LazyColumn item idx → animateScrollToItem。
 *        只在目标段当前**不在可见范围**时才滚，避免 TTS 跨段时反复扰动用户视野。
 * @param onNearTop 用户接近窗口顶 [nearStartThreshold] 段时触发，caller 应异步加载 prev 章并 prepend
 * @param onNearBottom 用户接近窗口底 [nearEndThreshold] 段时触发，caller 应异步加载 next 章并 append
 * @param onChapterProgressLive **UI 实时**进度回调：sample(150ms) 节流，fling 期间也持续上报
 *        让底栏百分比 / 进度条跟随手指。**不要**在此回调里写 DB——会被打爆。
 * @param onChapterProgressPersist **持久化**进度回调：debounce(800ms) 节流，停止滑动后才上报
 *        一次。caller 在此回调里写 DB / SP，安全。
 * @param onVisibleParagraphChanged (paragraph, scrollOffsetInItem) —— 可见首段变化时通知 caller，
 *        用于顶栏标题/章号更新、bookmark 持久化等
 * @param onScrollingChanged (scrolling) —— fling/idle 状态切换。caller 可在 idle 时启用
 *        TTS 高亮刷新等高开销 UI，scrolling 时降级
 * @param onTapCenter 点击空白区域 —— 切阅读器菜单
 * @param onLongPressParagraph Phase 4 引入：长按段落触发。caller 收到 `(paragraph, charOffset)`
 *        可走「复制段文本 / 朗读到此 / 加书签」等动作。**注意**：当前是段级粒度，不做
 *        词级选区拖把手；段内字符高亮 / 跨段选区留 Phase 5。
 *        默认 no-op，老 caller 不传时无功能但不会崩溃。
 * @param nearStartThreshold 章首预加载阈值（距窗口顶 N 段触发）。默认 15 —— 按用户建议
 *        预留 1/3 章空间，避免快速滑动时撞「空气墙」
 * @param nearEndThreshold 章末预加载阈值（距窗口底 N 段触发）。默认 15
 * @param chapterCharSizeProvider 给定 chapterIndex 返回该章总字符数，进度计算用
 */
@OptIn(FlowPreview::class)
@Composable
fun LazyScrollRenderer(
    paragraphs: List<ScrollParagraph>,
    initialIdx: Int,
    initialOffsetPx: Int,
    prependedCount: Int = 0,
    prependToken: Long = 0L,
    droppedFromFrontCount: Int = 0,
    dropToken: Long = 0L,
    ttsHighlightChapterIndex: Int = -1,
    ttsHighlightChapterPosition: Int = -1,
    onNearTop: () -> Unit = {},
    onNearBottom: () -> Unit = {},
    onChapterProgressLive: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    onChapterProgressPersist: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    onVisibleParagraphChanged: (paragraph: ScrollParagraph, scrollOffsetInItem: Int) -> Unit = { _, _ -> },
    onScrollingChanged: (scrolling: Boolean) -> Unit = {},
    onTapCenter: () -> Unit = {},
    onLongPressParagraph: (paragraph: ScrollParagraph, charOffsetInParagraph: Int) -> Unit = { _, _ -> },
    nearStartThreshold: Int = 15,
    nearEndThreshold: Int = 15,
    chapterCharSizeProvider: (chapterIndex: Int) -> Int = { 1 },
    modifier: Modifier = Modifier,
) {
    val theme = LocalReaderRenderTheme.current
    // ── 首帧锚定 ──
    //
    // 用 rememberLazyListState(initial...) 让 LazyColumn 首帧就在锚点处。
    // caller 必须保证：本 Composable 挂载时 paragraphs 已包含锚点章，否则
    // initialIdx/Offset 会指向错位置（caller 应用 key(paragraphsReady) 包裹）。
    //
    // 老路径用 LaunchedEffect + scrollToItem 是「事后纠偏」，肉眼可见从顶部到锚点的
    // 瞬移。新路径零瞬移，是 LazyColumn 的现代实践推荐方式
    // （见 androidx.compose.foundation.lazy.LazyListState 文档）。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIdx,
        initialFirstVisibleItemScrollOffset = initialOffsetPx,
    )

    // ── prepend 锚定补偿 ──
    //
    // caller 在窗口顶 prepend N 段后，传入 prependedCount=N + 新 prependToken。
    // 我们立即 scrollToItem(oldIdx + N, oldOffset) 抵消视觉上的"列表往上跳 N 段"。
    //
    // 用 prependToken 做幂等 key —— 同样的 prependedCount 数值（如连续两次都 prepend 30 段）
    // 用 token 区分两次操作。
    //
    // ── 首次挂载 skip ──
    //
    // 首次挂载时 listState 已用 [initialIdx]/[initialOffsetPx] 定位到正确锚点位置；
    // 但 caller 的 prepend 检测可能在 LazyScrollRenderer 挂载之前就触发了
    // （prev 章异步加载完毕、paragraphs 头部增加 N 段），给了非 0 的 [prependToken]。
    // LaunchedEffect(prependToken) 在挂载时立即 fire，又给 listState idx 加 N →
    // **双重补偿**，肉眼看到从锚点直接飞到末尾，撞 nearEnd 阈值死循环跳章。
    //
    // skip 规则：第一次进入 LaunchedEffect 不补偿，只把 firstPrependSkipped 设 true。
    // 之后用户实际滚到顶 caller 再 prepend prev 章时，LaunchedEffect 重新触发（token 变）
    // 走正常补偿路径。
    var firstPrependSkipped by remember { mutableStateOf(false) }
    LaunchedEffect(prependToken) {
        if (prependedCount <= 0 || prependToken == 0L) return@LaunchedEffect
        if (!firstPrependSkipped) {
            firstPrependSkipped = true
            AppLog.debug(
                "LazyScroll",
                "skip first-mount prepend (initial idx already correct): prepended=$prependedCount",
            )
            return@LaunchedEffect
        }
        val oldIdx = listState.firstVisibleItemIndex
        val oldOffset = listState.firstVisibleItemScrollOffset
        val newIdx = calcNewAnchorAfterPrepend(oldIdx, prependedCount)
        listState.scrollToItem(newIdx, oldOffset)
        AppLog.debug(
            "LazyScroll",
            "prepend anchor: oldIdx=$oldIdx → newIdx=$newIdx (prepended=$prependedCount, offset=${oldOffset}px)",
        )
    }

    // ── drop-from-front 锚定补偿 ──
    //
    // 与 prepend 对称：commitChapterShiftNext 让 prev 章从窗口头部被丢弃 N 段时，
    // LazyColumn 的 firstVisibleItemIndex 仍是旧值，但旧值在新列表坐标系里指向了
    // 「之后 N 段」的位置 → 用户视觉上看见列表「突然往下跳 N 段」（具体表现为：刚刚
    // 滚到的当前章被推走，呈现下一章内容；接着 LazyScroll derive 又把 chapter 反推
    // 回旧章——肉眼一闪）。
    //
    // 修正：scrollToItem(oldIdx - droppedFromFrontCount, oldOffset)。
    //
    // ── 不需要 first-mount skip ──
    //
    // 与 prepend 不同：drop-from-front 只来自 commitChapterShiftNext / Prev，这两条路径
    // 都需要用户主动操作（滑过章末或目录跳转）触发，必然发生在 LazyScrollRenderer 挂载
    // 之后；不存在「caller 提前 set token、LazyColumn 后挂载」的双重补偿场景。所以
    // dropToken 第一次非 0 触发就直接做补偿，不需要 firstDropSkipped 的安全阀。
    LaunchedEffect(dropToken) {
        if (droppedFromFrontCount <= 0 || dropToken == 0L) return@LaunchedEffect
        val oldIdx = listState.firstVisibleItemIndex
        val oldOffset = listState.firstVisibleItemScrollOffset
        val newIdx = (oldIdx - droppedFromFrontCount).coerceAtLeast(0)
        listState.scrollToItem(newIdx, oldOffset)
        AppLog.debug(
            "LazyScroll",
            "drop anchor: oldIdx=$oldIdx → newIdx=$newIdx (dropped=$droppedFromFrontCount, offset=${oldOffset}px)",
        )
    }

    // ── Phase 4：TTS 段落自动跟随 ──
    //
    // 朗读推进到下一段时，TtsEngineHost 把 chapterPosition 写进 TtsEventBus.playbackState；
    // ReaderScreen 透传到这里。我们用 chapterPositionToParagraphPos 解析为段编号，再
    // findItemIndex 找到 LazyColumn item idx → animateScrollToItem。
    //
    // 关键：只在目标段当前**不在可见范围**时才滚。这样：
    //   - 用户跟随 TTS 阅读时，TTS 推进 → 段落即将出屏 → 自动滚显
    //   - 用户回滚去看前文时，TTS 仍在推进，但目标段不在可见区——这种情况下我们**不**强行
    //     滚回去（不打断用户主动行为）；等用户自己滚回到 TTS 段附近时，下一次 TTS 推进
    //     会进入"目标段不在可见区"分支，重新开始跟随
    //
    // 与 [com.morealm.app.ui.reader.renderer.CanvasRenderer.LaunchedEffect(chapter, readAloudChapterPosition)]
    // 中的 AloudSpan 高亮**互补**：那条只更新页内字符级高亮，不改变滚动位置；本条只滚动，
    // 不动高亮。
    LaunchedEffect(ttsHighlightChapterIndex, ttsHighlightChapterPosition, paragraphs) {
        if (ttsHighlightChapterIndex < 0 || ttsHighlightChapterPosition < 0) return@LaunchedEffect
        val pos = chapterPositionToParagraphPos(
            paragraphs, ttsHighlightChapterIndex, ttsHighlightChapterPosition,
        ) ?: return@LaunchedEffect
        val targetIdx = pos.findItemIndex(paragraphs)
        if (targetIdx < 0) return@LaunchedEffect

        val firstVisible = listState.firstVisibleItemIndex
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
        if (targetIdx in firstVisible..lastVisible) return@LaunchedEffect  // 已在视野，不打扰

        AppLog.debug(
            "LazyScroll",
            "tts auto-follow: animateScrollToItem $targetIdx" +
                " (chPos=$ttsHighlightChapterPosition, chIdx=$ttsHighlightChapterIndex, visible=$firstVisible..$lastVisible)",
        )
        listState.animateScrollToItem(targetIdx)
    }

    // ── snapshotFlow #1：滚动中状态切换（idle/scrolling）──
    //
    // 用途：fling 时 caller 可降级（暂停 TTS 高亮刷新、推迟非紧要 UI 更新）。
    // distinctUntilChanged 防同 boolean 反复 emit。
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect(onScrollingChanged)
    }

    // ── snapshotFlow #2：可见首段变化 —— 顶栏标题/章号更新 ──
    //
    // 用 firstVisibleItemIndex + scrollOffset pair；段切换或精确像素都触发，但通过
    // distinctUntilChanged 自动过滤同段同偏移的重复 emit（fling 期间偏移连续变化，
    // 这里不能用 sample 因 caller 需要实时拿最新偏移做 bookmark 持久化）。
    LaunchedEffect(paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (idx, offset) ->
                paragraphs.getOrNull(idx)?.let { p ->
                    onVisibleParagraphChanged(p, offset)
                }
            }
    }

    // ── snapshotFlow #3：进度上报 —— sample(150ms) 防 fling 期间狂呼 ──
    //
    // 进度只需要"百分比变化时"通知 caller（caller 通常更新底栏 + 持久化），
    // sample(150L) 把 fling 期间一秒几十次的状态变化压成 ~6 次，再叠加
    // distinctUntilChanged 让最终上报 ~1 次/百分比阶。
    LaunchedEffect(paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .sample(150L)
            .map { (idx, offset) ->
                val p = paragraphs.getOrNull(idx)
                if (p == null) {
                    null
                } else {
                    val totalChars = chapterCharSizeProvider(p.chapterIndex).coerceAtLeast(1)
                    p.chapterIndex to calcChapterProgress(p, offset.toFloat(), totalChars)
                }
            }
            .filter { it != null }
            .map { it!! }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressLive(chIdx, prog) }
    }

    // ── snapshotFlow #3.5：持久化进度上报 —— debounce(800ms) 停手才写 ──
    //
    // 用途：DB / SP 写入。fling 期间用户持续滑动，没必要每秒写 6 次 —— debounce 让
    // 「停止滑动 800ms 后」才上报最终位置一次，IO 压力最小化。
    //
    // 与 #3 的区别：
    //   - #3 sample：固定时间窗取最新（持续输出）→ UI 跟随
    //   - #3.5 debounce：等待无新事件→单次输出（停下才出）→ 持久化
    // 两条链共用同一 snapshotFlow source，但 operator 不同，目标 sink 不同。
    LaunchedEffect(paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(800L)
            .map { (idx, offset) ->
                val p = paragraphs.getOrNull(idx)
                if (p == null) {
                    null
                } else {
                    val totalChars = chapterCharSizeProvider(p.chapterIndex).coerceAtLeast(1)
                    p.chapterIndex to calcChapterProgress(p, offset.toFloat(), totalChars)
                }
            }
            .filter { it != null }
            .map { it!! }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressPersist(chIdx, prog) }
    }

    // ── snapshotFlow #4：章末预加载触发 ──
    //
    // 阈值：visibleItemsInfo 中最后一个 idx >= paragraphs.size - nearEndThreshold 时为 true。
    // distinctUntilChanged + filter { it } 保证只在「false → true」边沿触发一次，
    // 避免快速滑动时 onNearBottom 一秒被调几十次（caller 会自己做 inFlight 去重，
    // 但发出的呼叫次数本身也是开销）。
    //
    // ── 首次挂载 skip ──
    //
    // 进入书时 paragraphs 可能只有 cur 章（prev/next 还在异步加载），visibleItems
    // 自然就在 paragraphs.size - nearEndThreshold 范围内（cur 章最末几段离 list 尾很近）→
    // **立即触发 onNearBottom → onNextChapter** → 跳到下一章 → 又触发 nearTop → 跳回 →
    // 视觉上看到「进入书时章节疯狂跳来跳去」。
    //
    // 解决：必须在用户**主动产生过滚动**后才允许触发预加载阈值。挂载时的初始 idx
    // 不算"用户滚动"，靠 [hasUserScrolled] 状态跟踪 [LazyListState.isScrollInProgress]
    // 第一次翻 true（手指 down + drag）来确认用户主动行为。
    var hasUserScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect {
                if (!hasUserScrolled) {
                    hasUserScrolled = true
                    AppLog.debug("LazyScroll", "user scroll detected; enabling near-edge preload triggers")
                }
            }
    }

    LaunchedEffect(paragraphs.size, nearEndThreshold) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= (paragraphs.size - nearEndThreshold).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .filter { it && hasUserScrolled }
            .collect { onNearBottom() }
    }

    // ── snapshotFlow #5：章首预加载触发 ──
    //
    // 同样需要 hasUserScrolled gate—— 进入书时 initialIdx 可能就 < nearStartThreshold（15）
    // （比如用户上次读到章首附近），不该立即触发 onPrevChapter。
    LaunchedEffect(paragraphs.size, nearStartThreshold) {
        snapshotFlow {
            paragraphs.isNotEmpty() && listState.firstVisibleItemIndex < nearStartThreshold
        }
            .distinctUntilChanged()
            .filter { it && hasUserScrolled }
            .collect { onNearTop() }
    }

    // ── derivedStateOf 派生：当前可见章 idx ──
    //
    // 仅在 chapterIndex 变化时触发依赖此 state 的子组件重组（这里没用，留给上层 topbar
    // 取用：caller 通过 onVisibleParagraphChanged 拿章 idx；如未来 LazyScrollRenderer
    // 内部也要用这个推导值，可直接读 currentChapterIdx.value）。
    val currentChapterIdx by remember(paragraphs) {
        derivedStateOf {
            paragraphs.getOrNull(listState.firstVisibleItemIndex)?.chapterIndex ?: -1
        }
    }
    // 把派生值喂回日志只为非空使用，避免被编译器优化掉（Compose 不读不算）。
    LaunchedEffect(currentChapterIdx) {
        if (currentChapterIdx >= 0) {
            AppLog.debug("LazyScroll", "currentChapter derived: $currentChapterIdx")
        }
    }

    // ── UI ──
    //
    // 结构：
    //   Box 顶层（接 tap 手势 + 充满）
    //   ├── 背景层（不滚动，viewport 静态：纯色 + 可选 bgBitmap）
    //   └── LazyColumn（文字层 + 顶/底羽化）
    //       └── items(paragraphs)：每段一个 Canvas
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(theme.bgArgb))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTapCenter() })
            },
    ) {
        // 1. 背景图层（如果用户配了背景图）—— 不在 LazyColumn 里，避免随段滚动
        theme.bgBitmap?.let { bmp ->
            Canvas(Modifier.fillMaxSize()) {
                if (!bmp.isRecycled) {
                    drawIntoCanvas { compose ->
                        drawBgBitmap(compose.nativeCanvas, bmp, size.width, size.height)
                    }
                }
            }
        }

        // 2. 文字层 + 羽化
        //
        // graphicsLayer(compositingStrategy = Offscreen) 把 LazyColumn 内容画到独立
        // RenderNode（Android 9+ 硬件加速），drawWithContent 阶段做 DstOut 渐变 ——
        // 等价于现有 ScrollRenderer 的 saveLayer + DstOut 方案，但 RenderNode 性能更好，
        // 且不需要手写 saveLayer/restore。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fadeHeight = size.height * 0.05f
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = fadeHeight,
                        ),
                        blendMode = BlendMode.DstOut,
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black,
                            startY = size.height - fadeHeight,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstOut,
                    )
                },
        ) {
            // contentType 是性能榨取关键：让相同 type 的 item 在滑动复用时直接走
            // view 结构层复用，跳过 Composition 评估。结构差异大的段（CHAPTER_TITLE
            // vs IMAGE）必须分类型，否则会触发 fallback 全量重组。
            //
            // key 全局唯一（"$chapterIndex-$paragraphNum"），跨章窗口拼接安全。
            items(
                items = paragraphs,
                key = { p -> p.key },
                contentType = { p -> p.contentType },
            ) { paragraph ->
                ScrollParagraphItem(
                    paragraph = paragraph,
                    onLongPress = { offsetInPara -> onLongPressParagraph(paragraph, offsetInPara) },
                )
            }
        }
    }
}

/**
 * 单段 LazyColumn item。
 *
 * **极简 Modifier 原则**：仅 fillMaxWidth + height(totalHeight)，无 drawBehind / clip /
 * shadow 等重量级 modifier。所有绘制集中在 Canvas 一次性完成，主线程瞬时压力恒定。
 *
 * LOADING 占位段：仅占高度，不画内容，让背景层透出来 —— 用户滚到章末发现"前方有
 * 空间但还没字"，不至于撞「空气墙」。
 *
 * ── Phase 4 长按选词 ──
 *
 * pointerInput(detectTapGestures(onLongPress)) 接住长按手势，把段内 y 坐标转换成段内字符
 * offset：先用 [paragraph.linePositions] 找到行 idx，再扫该行的 [TextLine.columns] 用
 * column.start..column.end 找到 x 命中的列，column.charData 在段内累计字符位置。
 *
 * **当前粒度仅到段** —— 段内字符 offset 通过回调上报，但 LazyScrollRenderer 不主动渲染
 * 选区高亮（drawScrollParagraphContent 没接 selection 参数）。caller 拿 offset 可以做的事：
 *
 *   - 复制整段（[onLongPress] 触发后调 onCopyText(paragraph.text)）
 *   - 朗读到此（onSpeakFromHere(firstChapterPosition + offset)）
 *   - 加书签（保存 ParagraphTextPos(chapterIdx, paragraphNum, offset)）
 *
 * 词级选区拖把手 + 跨段选区 + 高亮渲染：Phase 5 再补，需要把 selection state 引入 paragraph
 * 绘制路径并实现拖把手 hit-test。
 */
@Composable
private fun ScrollParagraphItem(
    paragraph: ScrollParagraph,
    onLongPress: (charOffsetInParagraph: Int) -> Unit = {},
) {
    val theme = LocalReaderRenderTheme.current
    val density = LocalDensity.current
    val heightDp = with(density) { paragraph.totalHeight.toDp() }

    if (paragraph.contentType == ScrollParagraphType.LOADING) {
        Box(Modifier.fillMaxWidth().height(heightDp))
        return
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            .pointerInput(paragraph.key) {
                detectTapGestures(
                    onLongPress = { tap ->
                        val offsetInPara = computeCharOffsetInParagraph(paragraph, tap.x, tap.y)
                        onLongPress(offsetInPara)
                    },
                )
            },
    ) {
        drawIntoCanvas { compose ->
            drawScrollParagraphContent(
                canvas = compose.nativeCanvas,
                paragraph = paragraph,
                titlePaint = theme.titlePaint,
                contentPaint = theme.contentPaint,
                chapterNumPaint = theme.chapterNumPaint,
            )
        }
    }
}

/**
 * 把段内 (x, y) 坐标转换为段内字符 offset。
 *
 * 算法：
 *   1. 用 [paragraph.linePositions] 找到 y 落在哪一行（最后一个 linePos <= y）
 *   2. 在该行的 columns 里用 [BaseColumn.isTouch] 找命中列，并累加前置列的 charSize
 *      得到行内字符 offset
 *   3. 段内字符 offset = (行首章内位置 + 行内字符 offset) - 段首章内位置
 *
 * 找不到精确命中时退化：
 *   - y 越过末行 → 段尾字符
 *   - x 越过行末 → 行末字符
 *   - 行内全是非文本列（图片等）→ 行首字符
 */
private fun computeCharOffsetInParagraph(paragraph: ScrollParagraph, x: Float, y: Float): Int {
    if (paragraph.lines.isEmpty() || paragraph.linePositions.isEmpty()) return 0
    // 找命中行：最后一个 linePosition <= y
    var lineIdx = paragraph.lines.lastIndex
    for (i in paragraph.linePositions.indices) {
        if (paragraph.linePositions[i] > y) {
            lineIdx = (i - 1).coerceAtLeast(0)
            break
        }
    }
    val line = paragraph.lines.getOrNull(lineIdx) ?: paragraph.lines[0]
    // 累加列字符数到命中列
    var charsBefore = 0
    var hit = false
    for (col in line.columns) {
        if (col.isTouch(x)) { hit = true; break }
        if (col is TextBaseColumn) charsBefore += col.charData.length
    }
    val lineCharOffset = if (hit) charsBefore else {
        // x 越过行末 → 行内总字符 - 1（如果有内容），否则 0
        val total = line.columns.sumOf { c -> if (c is TextBaseColumn) c.charData.length else 0 }
        (total - 1).coerceAtLeast(0)
    }
    val charIdxInChapter = line.chapterPosition + lineCharOffset
    return (charIdxInChapter - paragraph.firstChapterPosition).coerceAtLeast(0)
}
