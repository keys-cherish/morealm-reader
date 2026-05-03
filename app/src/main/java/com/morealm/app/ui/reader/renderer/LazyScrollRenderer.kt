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
import com.morealm.app.domain.render.ScrollAnchor
import com.morealm.app.domain.render.ScrollParagraph
import com.morealm.app.domain.render.ScrollParagraphType
import com.morealm.app.domain.render.calcAnchorScrollOffsetPx
import com.morealm.app.domain.render.calcChapterProgress
import com.morealm.app.domain.render.calcNewAnchorAfterPrepend
import com.morealm.app.domain.render.findAnchorIndex
import kotlinx.coroutines.FlowPreview
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
 * @param initialAnchor 启动 / 章节切换时的恢复锚点。null 表示从段首开始
 * @param prependedCount 上一次 prepend 操作向窗口顶部加入的段数（用于锚定补偿）
 * @param prependToken 每次 prepend 完成换新值（System.nanoTime() 即可），即使 prependedCount
 *        数值与上次相同也能触发补偿。null/0 表示无 prepend
 * @param onNearTop 用户接近窗口顶 [nearStartThreshold] 段时触发，caller 应异步加载 prev 章并 prepend
 * @param onNearBottom 用户接近窗口底 [nearEndThreshold] 段时触发，caller 应异步加载 next 章并 append
 * @param onChapterProgress (chapterIndex, progress 0..100) —— 章内进度变化时通知 caller
 * @param onVisibleParagraphChanged (paragraph, scrollOffsetInItem) —— 可见首段变化时通知 caller，
 *        用于顶栏标题/章号更新、bookmark 持久化等
 * @param onScrollingChanged (scrolling) —— fling/idle 状态切换。caller 可在 idle 时启用
 *        TTS 高亮刷新等高开销 UI，scrolling 时降级
 * @param onTapCenter 点击空白区域 —— 切阅读器菜单
 * @param nearStartThreshold 章首预加载阈值（距窗口顶 N 段触发）。默认 15 —— 按用户建议
 *        预留 1/3 章空间，避免快速滑动时撞「空气墙」
 * @param nearEndThreshold 章末预加载阈值（距窗口底 N 段触发）。默认 15
 * @param chapterCharSizeProvider 给定 chapterIndex 返回该章总字符数，进度计算用
 */
@OptIn(FlowPreview::class)
@Composable
fun LazyScrollRenderer(
    paragraphs: List<ScrollParagraph>,
    initialAnchor: ScrollAnchor?,
    prependedCount: Int = 0,
    prependToken: Long = 0L,
    onNearTop: () -> Unit = {},
    onNearBottom: () -> Unit = {},
    onChapterProgress: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    onVisibleParagraphChanged: (paragraph: ScrollParagraph, scrollOffsetInItem: Int) -> Unit = { _, _ -> },
    onScrollingChanged: (scrolling: Boolean) -> Unit = {},
    onTapCenter: () -> Unit = {},
    nearStartThreshold: Int = 15,
    nearEndThreshold: Int = 15,
    chapterCharSizeProvider: (chapterIndex: Int) -> Int = { 1 },
    modifier: Modifier = Modifier,
) {
    val theme = LocalReaderRenderTheme.current
    val listState = rememberLazyListState()

    // ── 锚点恢复 ──
    //
    // 触发条件：initialAnchor 或 paragraphs 边界变化时（章节切换 / prepend / append）。
    // 用 anchor + paragraphs 首末 key 三元组做幂等 key，避免同窗口内用户滚动时反复
    // 重启 effect。
    //
    // 注意：这里不能 keyed listState.firstVisibleItemIndex —— 那样用户每滚动一段
    // 就会 reset 锚点，撤销用户操作。
    var lastRestoredSig by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        initialAnchor,
        paragraphs.firstOrNull()?.key,
        paragraphs.lastOrNull()?.key,
    ) {
        val anchor = initialAnchor ?: return@LaunchedEffect
        if (paragraphs.isEmpty()) return@LaunchedEffect
        val sig = "${anchor.chapterIndex}-${anchor.paragraphNum}-${anchor.lineIdxInParagraph}-${paragraphs.size}"
        if (lastRestoredSig == sig) return@LaunchedEffect
        val idx = findAnchorIndex(paragraphs, anchor)
        if (idx < 0) {
            // 锚点章不在窗口内 —— caller 还没准备好，等下一次 paragraphs 更新再试
            return@LaunchedEffect
        }
        val offset = calcAnchorScrollOffsetPx(paragraphs[idx], anchor)
        listState.scrollToItem(idx, offset)
        lastRestoredSig = sig
        AppLog.debug(
            "LazyScroll",
            "anchor restore: idx=$idx offset=${offset}px anchor=$anchor paragraphs=${paragraphs.size}",
        )
    }

    // ── prepend 锚定补偿 ──
    //
    // caller 在窗口顶 prepend N 段后，传入 prependedCount=N + 新 prependToken。
    // 我们立即 scrollToItem(oldIdx + N, oldOffset) 抵消视觉上的"列表往上跳 N 段"。
    //
    // 用 prependToken 做幂等 key —— 同样的 prependedCount 数值（如连续两次都 prepend 30 段）
    // 用 token 区分两次操作。
    LaunchedEffect(prependToken) {
        if (prependedCount <= 0 || prependToken == 0L) return@LaunchedEffect
        val oldIdx = listState.firstVisibleItemIndex
        val oldOffset = listState.firstVisibleItemScrollOffset
        val newIdx = calcNewAnchorAfterPrepend(oldIdx, prependedCount)
        listState.scrollToItem(newIdx, oldOffset)
        AppLog.debug(
            "LazyScroll",
            "prepend anchor: oldIdx=$oldIdx → newIdx=$newIdx (prepended=$prependedCount, offset=${oldOffset}px)",
        )
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
            .collect { (chIdx, prog) -> onChapterProgress(chIdx, prog) }
    }

    // ── snapshotFlow #4：章末预加载触发 ──
    //
    // 阈值：visibleItemsInfo 中最后一个 idx >= paragraphs.size - nearEndThreshold 时为 true。
    // distinctUntilChanged + filter { it } 保证只在「false → true」边沿触发一次，
    // 避免快速滑动时 onNearBottom 一秒被调几十次（caller 会自己做 inFlight 去重，
    // 但发出的呼叫次数本身也是开销）。
    LaunchedEffect(paragraphs.size, nearEndThreshold) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= (paragraphs.size - nearEndThreshold).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onNearBottom() }
    }

    // ── snapshotFlow #5：章首预加载触发 ──
    LaunchedEffect(paragraphs.size, nearStartThreshold) {
        snapshotFlow {
            paragraphs.isNotEmpty() && listState.firstVisibleItemIndex < nearStartThreshold
        }
            .distinctUntilChanged()
            .filter { it }
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
                ScrollParagraphItem(paragraph)
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
 */
@Composable
private fun ScrollParagraphItem(paragraph: ScrollParagraph) {
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
            .height(heightDp),
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
