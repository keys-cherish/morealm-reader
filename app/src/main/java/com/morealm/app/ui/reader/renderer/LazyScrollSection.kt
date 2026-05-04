package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.SelectionMenuConfig
import com.morealm.app.domain.entity.looksLikeAutoSplitTitle
import com.morealm.app.domain.render.ScrollAnchor
import com.morealm.app.domain.render.ScrollParagraph
import com.morealm.app.domain.render.TextBaseColumn
import com.morealm.app.domain.render.TextChapter
import com.morealm.app.domain.render.bookmarkToAnchor
import com.morealm.app.domain.render.chapterPositionToParagraphPos
import com.morealm.app.domain.render.findAnchorIndex
import com.morealm.app.domain.render.toScrollParagraphs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * SCROLL 模式的"段落级 LazyColumn 瀑布流"实现 —— 从 [CanvasRenderer] 拆出。
 *
 * 之所以独立成文件：CanvasRenderer 主体职责已经横跨翻页 / 滚动 / 仿真三种模式 + 选区
 * + TTS + 手势 + 信息栏，> 3000 行难维护。本组件聚焦"滚动模式数据流 + 三道防线"，
 * 接口面收敛到必要的 chapter window + 跳转坐标 + 进度回调，外层 [CanvasRenderer]
 * 只负责把当前章节窗口和回调透传进来。
 *
 * ── 三道防线一览 ──
 *
 *   **第一道**：[chapterPositionToParagraphPos] 两阶段屏障——paragraphs 含目标段才出
 *   非 0 jumpToken，否则 jumpReady=false，[LazyScrollRenderer] 内部 LaunchedEffect 跳过。
 *
 *   **第二道**：Key-Anchor 视野补偿（替代旧 prependedCount/Token 侧信道盲算）
 *     - paragraphsState 用 [SnapshotStateList]
 *     - 三个独立 [LaunchedEffect]：cur 章 reset / prev 章 prepend / next 章 append
 *     - prev prepend 时 snapshot anchorParaKey → addAll(0,...) → indexOfFirst →
 *       [LazyListState.requestScrollToItem]，**fling 不打断、视野零位移**
 *
 *   **第三道**：进度上报闸门
 *     - 跳转静默期 [jumpSilenceUntilMs] = jumpToken 翻新时 +500ms
 *     - 跳转重置 [hasUserScrolled]（在 [LazyScrollRenderer] 内）
 *     - dirty check：[onChapterProgressLive] / [onChapterProgressPersist] 内 chIdx != cur 章
 *       的瞬时态全部丢弃，绝不写脏数据进 DB
 *
 * @param chapter 当前章节（cur）。null 或未 isCompleted 时显示 [ReaderLoadingCover]。
 * @param prevTextChapter 上一章（prev）。null 或未 isCompleted 时不参与窗口
 * @param nextTextChapter 下一章（next）。同上
 * @param chapterIndex 当前章节 idx（与 [chapter].chapterIndex 通常一致；用户跳章时 caller
 *        会先更新此值，再异步加载新 chapter）
 * @param initialChapterPosition 续读 / 书签 / TOC 跳转的目标章内字符偏移（0 = 章首）
 * @param restoreToken 跳转幂等 key（每次 chapter loadChapter 用 [System.nanoTime] 换新值）
 * @param readAloudChapterPosition TTS 朗读位置；< 0 表示未在朗读
 * @param chapterTitle / prevChapterTitle / nextChapterTitle 三章标题，给可见段落变化时回调用
 * @param backgroundColor 阅读器背景色（[ReaderLoadingCover] 用，与 LazyColumn 同色）
 * @param textColor 阅读器文字色
 * @param revealHighlight 跳转后整段褪色高亮；caller 已用 chapterIndex 过滤旧章
 * @param onPrevChapter / onNextChapter 用户滚到窗口边缘时由 [LazyScrollRenderer] 触发
 * @param onProgress 进度上报（已经过本组件的第三道防线 dirty check + jumpSilence 过滤）
 * @param onCopyText 段级 mini-menu 复制按钮 → 把整段文字传给 caller（通常 caller 走系统
 *        ClipboardManager 或自有 toast）。Phase 5 字符级落地后会被 selection.text 取代，
 *        但段级 MVP 阶段保持现签名。
 * @param onSpeakFromHere 段级 mini-menu 朗读到此 → caller 用 chapterPosition 起播 TTS
 * @param onTranslateText 段级 mini-menu 翻译 → caller 调翻译能力
 * @param onLookupWord 段级 mini-menu 查词 → caller 弹查词面板
 * @param onShareQuote 段级 mini-menu 分享 → caller 弹分享对话框
 * @param onAddHighlight 段级 mini-menu 高亮调色板挑色 → caller 落 DB
 *        参数：(start, end, content, colorArgb)；start/end 是 chapter-pos 字符范围
 * @param onEraseHighlight 段级 mini-menu 橡皮 → caller 删除选区与已有高亮的交集
 * @param onAddTextColor 段级 mini-menu 字体强调色 → caller 落 DB（kind = TEXT_COLOR）
 * @param selectionMenuConfig 用户在阅读设置里配置的"主行/扩展行/隐藏"按钮分配
 * @param onTapCenter 点击空白切阅读器菜单
 * @param onVisiblePageChanged (chIdx, title, readProgressTag, charPos) —— 顶栏标题更新
 * @param onScrollingChanged fling/idle 切换
 */
@Composable
fun LazyScrollSection(
    chapter: TextChapter?,
    prevTextChapter: TextChapter?,
    nextTextChapter: TextChapter?,
    chapterIndex: Int,
    initialChapterPosition: Int,
    restoreToken: Long,
    readAloudChapterPosition: Int,
    chapterTitle: String,
    prevChapterTitle: String,
    nextChapterTitle: String,
    backgroundColor: Color,
    textColor: Color,
    revealHighlight: RevealHighlight?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onProgress: (Int) -> Unit,
    onCopyText: (String) -> Unit,
    onSpeakFromHere: (Int) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onShareQuote: (String) -> Unit = {},
    onAddHighlight: ((start: Int, end: Int, content: String, colorArgb: Int) -> Unit)? = null,
    onEraseHighlight: ((start: Int, end: Int) -> Unit)? = null,
    onAddTextColor: ((start: Int, end: Int, content: String, colorArgb: Int) -> Unit)? = null,
    selectionMenuConfig: SelectionMenuConfig = SelectionMenuConfig.DEFAULT,
    onTapCenter: () -> Unit,
    onVisiblePageChanged: (chapterIdx: Int, title: String, readProgress: String, charPos: Int) -> Unit,
    onScrollingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ── 数据流：[paragraphsState] + [listState] 由本层持有 ──
    //
    // [listState] 必须由本层创建：effect 2/3 要在 paragraphs mutation 同协程上下文里
    // snapshot anchorParaKey + 调 [LazyListState.requestScrollToItem]，listState 实例
    // 不能在 [LazyScrollRenderer] 内部 remember（那样外层 effect 拿不到）。
    val paragraphsState = remember { mutableStateListOf<ScrollParagraph>() }
    val listState = rememberLazyListState()

    // 进度计算需要每章字符总数 —— 现场建小 map 缓存（窗口仅 3 章）
    val chapterCharSizes = remember(prevTextChapter, chapter, nextTextChapter) {
        buildMap {
            prevTextChapter?.let { put(it.chapterIndex, it.getContent().length.coerceAtLeast(1)) }
            chapter?.let { put(it.chapterIndex, it.getContent().length.coerceAtLeast(1)) }
            nextTextChapter?.let { put(it.chapterIndex, it.getContent().length.coerceAtLeast(1)) }
        }
    }

    // ── effect 1: cur 章变化 → 重置整个 list ──
    //
    // 触发条件：chapter.chapterIndex 变化（用户跳章 / commitChapterShift / loadChapter）
    // 或同章 isCompleted 翻 true（流式 layout 完成）。
    //
    // 跨章后跳转锚点由 jumpToken 路径独立驱动；本 effect 不动 listState。
    var lastResetCurChapterIdx by remember { mutableIntStateOf(-1) }
    LaunchedEffect(chapter?.chapterIndex, chapter?.isCompleted) {
        val cur = chapter ?: return@LaunchedEffect
        if (!cur.isCompleted) return@LaunchedEffect
        if (cur.chapterIndex == lastResetCurChapterIdx) return@LaunchedEffect

        val prev = prevTextChapter?.takeIf { it.isCompleted == true }
        val next = nextTextChapter?.takeIf { it.isCompleted == true }
        val prevParas = prev?.toScrollParagraphs(prev.title.looksLikeAutoSplitTitle()).orEmpty()
        val curParas = cur.toScrollParagraphs(cur.title.looksLikeAutoSplitTitle())
        val nextParas = next?.toScrollParagraphs(next.title.looksLikeAutoSplitTitle()).orEmpty()

        paragraphsState.clear()
        paragraphsState.addAll(prevParas)
        paragraphsState.addAll(curParas)
        paragraphsState.addAll(nextParas)
        lastResetCurChapterIdx = cur.chapterIndex
        AppLog.debug(
            "LazyScroll",
            "reset window for chapterIdx=${cur.chapterIndex}: prev=${prevParas.size} cur=${curParas.size} next=${nextParas.size}",
        )
    }

    // ── effect 2: prev 章 prepend（Key-Anchor 视野补偿） ──
    //
    // 用户向上滚到 prev 章窗口、ChapterController 异步加载 prev → prevTextChapter
    // StateFlow 翻 ready → 头部 prepend prev 段。
    //
    // **Key-Anchor 流程**（同协程上下文原子执行）：
    //   1. snapshot anchorParaKey + anchorOffsetPx
    //   2. paragraphsState.addAll(0, prevParas)
    //   3. indexOfFirst { it.key == anchorParaKey } → newAnchorParaIdx
    //   4. listState.requestScrollToItem(newAnchorParaIdx, anchorOffsetPx)
    //
    // [LazyListState.requestScrollToItem]（Foundation 1.7+）的关键属性：**不取消
    // 进行中的 fling**——layout 用新 anchor 静默调整，物理动画继续推。
    LaunchedEffect(prevTextChapter?.chapterIndex, prevTextChapter?.isCompleted) {
        val prev = prevTextChapter?.takeIf { it.isCompleted == true } ?: return@LaunchedEffect
        val cur = chapter?.takeIf { it.isCompleted == true } ?: return@LaunchedEffect
        if (paragraphsState.isEmpty()) return@LaunchedEffect
        // 已含 prev 章（effect 1 reset 过的同窗口）跳过
        if (paragraphsState.firstOrNull()?.chapterIndex == prev.chapterIndex) return@LaunchedEffect
        // 防 stale prev（StateFlow 短暂错位时）
        if (prev.chapterIndex >= cur.chapterIndex) return@LaunchedEffect

        val anchorParaIdx = listState.firstVisibleItemIndex
        val anchorOffsetPx = listState.firstVisibleItemScrollOffset
        val anchorParaKey = paragraphsState.getOrNull(anchorParaIdx)?.key ?: return@LaunchedEffect

        val prevParas = prev.toScrollParagraphs(prev.title.looksLikeAutoSplitTitle())
        if (prevParas.isEmpty()) return@LaunchedEffect

        paragraphsState.addAll(0, prevParas)

        val newAnchorParaIdx = paragraphsState.indexOfFirst { it.key == anchorParaKey }
        if (newAnchorParaIdx >= 0) {
            listState.requestScrollToItem(newAnchorParaIdx, anchorOffsetPx)
            AppLog.debug(
                "LazyScroll",
                "prev prepend: anchor key=$anchorParaKey paraIdx $anchorParaIdx → $newAnchorParaIdx (added ${prevParas.size}, offset=${anchorOffsetPx}px)",
            )
        }
    }

    // ── effect 3: next 章 append（无需视野补偿） ──
    LaunchedEffect(nextTextChapter?.chapterIndex, nextTextChapter?.isCompleted) {
        val next = nextTextChapter?.takeIf { it.isCompleted == true } ?: return@LaunchedEffect
        val cur = chapter?.takeIf { it.isCompleted == true } ?: return@LaunchedEffect
        if (paragraphsState.isEmpty()) return@LaunchedEffect
        if (paragraphsState.lastOrNull()?.chapterIndex == next.chapterIndex) return@LaunchedEffect
        if (next.chapterIndex <= cur.chapterIndex) return@LaunchedEffect

        val nextParas = next.toScrollParagraphs(next.title.looksLikeAutoSplitTitle())
        if (nextParas.isEmpty()) return@LaunchedEffect
        paragraphsState.addAll(nextParas)
        AppLog.debug("LazyScroll", "next append: added ${nextParas.size} paragraphs at tail")
    }

    // ── 锚点恢复（用户主动跳转 / 续读启动）──
    val curReady = chapter?.isCompleted == true
    val initialAnchor = remember(chapterIndex, initialChapterPosition, curReady) {
        if (!curReady) null
        else bookmarkToAnchor(chapterIndex, initialChapterPosition, paragraphsState)
            ?: ScrollAnchor.atChapterStart(chapterIndex)
    }

    val paragraphsReady = curReady &&
        initialAnchor != null &&
        paragraphsState.isNotEmpty() &&
        findAnchorIndex(paragraphsState, initialAnchor) >= 0

    if (!paragraphsReady) {
        ReaderLoadingCover(
            bgColor = backgroundColor,
            textColor = textColor,
            chapterTitle = chapterTitle.ifBlank { "正在加载…" },
            chapterSubtitle = if (chapterIndex >= 0) "第 ${chapterIndex + 1} 章" else null,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    // ── 命令式跳转两阶段屏障（第一道防线）──
    val jumpReady = remember(chapterIndex, initialChapterPosition, paragraphsState.size) {
        if (initialChapterPosition <= 0) false
        else chapterPositionToParagraphPos(
            paragraphsState, chapterIndex, initialChapterPosition,
        ) != null
    }
    val effectiveJumpChapterIdx = if (jumpReady) chapterIndex else -1
    val effectiveJumpChapterPos = if (jumpReady) initialChapterPosition else 0
    val effectiveJumpToken = if (jumpReady) restoreToken else 0L

    // ── 跳转静默期（第三道防线之一）──
    //
    // jumpToken 翻新值 → 进入 500ms 静默期，期内拒收 onChapterProgressLive/Persist。
    // 让 LazyScrollRenderer 内部 scrollToItem + LazyColumn measure/layout/settle 完成、
    // firstVisibleItem 派生稳定到目标段后再放行进度上报。
    var jumpSilenceUntilMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(effectiveJumpToken) {
        if (effectiveJumpToken != 0L) {
            jumpSilenceUntilMs = System.currentTimeMillis() + 500L
        }
    }

    // ── 段级选区状态（Phase 5 之前的简版） ──
    //
    // - paragraphKey：被选中段的 [ScrollParagraph.key]（"$chapterIndex-$paragraphNum"），
    //   传给 LazyScrollRenderer.selectedParagraphKey 让该段画选中前景。
    // - text：整段连续字符（拆 [TextBaseColumn.charData] 拼起来），mini-menu 各动作的载荷。
    // - chapterIndex / startChapterPos / endChapterPos：用于 onAddHighlight 等回调的 DB 持久化范围。
    // - anchorInBox：长按 tap 点在 [boxCoords] 局部坐标系下的位置；SelectionToolbar 的 Popup
    //   用 ReaderToolbarPositionProvider 以这个 Offset 当 anchor 决定 above/below。
    //
    // 字符级 Phase 5 落地后此 data class 会扩成 (startTextPos, endTextPos)，但当前段级
    // MVP 不需要词级粒度。
    var scrollSelection by remember { mutableStateOf<ScrollSelectionState?>(null) }
    // wrapper Box 在 root window 中的 LayoutCoordinates —— 用来把 long-press 上报的
    // anchorInWindow 转成 Box 内局部坐标（Popup parent 坐标系一致）。
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // 滚动 / fling 时立即清选区：
    //  - 段已经划出 viewport，菜单浮在空气上没意义
    //  - 用户的预期是"我开始滚说明放弃了选这段"
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { it }
            .collect { scrollSelection = null }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> boxCoords = coords },
    ) {
        LazyScrollRenderer(
            paragraphs = paragraphsState,
            listState = listState,
            ttsHighlightChapterIndex = if (readAloudChapterPosition >= 0) chapterIndex else -1,
            ttsHighlightChapterPosition = readAloudChapterPosition,
            jumpChapterIndex = effectiveJumpChapterIdx,
            jumpChapterPosition = effectiveJumpChapterPos,
            jumpToken = effectiveJumpToken,
            revealHighlight = revealHighlight,
            onNearTop = onPrevChapter,
            onNearBottom = onNextChapter,
            selectedParagraphKey = scrollSelection?.paragraphKey,
            // ── 长按段落 → 段级选区 ──
            //
            // 1. 抽出整段文本（buildString 拼 TextBaseColumn.charData）
            // 2. anchorInWindow 通过 boxCoords.windowToLocal 转到 wrapper 局部
            // 3. ScrollSelectionState 包含全部 mini-menu 动作所需的元信息
            //
            // charOffsetInParagraph 暂时不用（接口为 Phase 5 字符级保留）—— 段级 MVP
            // 直接以"整段"为选区，endChapterPos = firstChapterPosition + 段总字符数。
            onLongPressParagraph = { paragraph, _, anchorInWindow ->
                val text = buildString {
                    for (line in paragraph.lines) {
                        for (col in line.columns) {
                            if (col is TextBaseColumn) append(col.charData)
                        }
                    }
                }
                if (text.isBlank()) return@LazyScrollRenderer
                val coords = boxCoords ?: return@LazyScrollRenderer
                // windowToLocal：把 root window 坐标 (anchorInWindow) 转成 Box 局部坐标
                val anchorInBox = coords.windowToLocal(anchorInWindow)
                scrollSelection = ScrollSelectionState(
                    paragraphKey = paragraph.key,
                    text = text,
                    chapterIndex = paragraph.chapterIndex,
                    startChapterPos = paragraph.firstChapterPosition,
                    endChapterPos = paragraph.firstChapterPosition + text.length,
                    anchorInBox = anchorInBox,
                )
            },
            // ── 进度上报闸门（第三道防线之二）──
            //
            // 三层 guard：
            //   1. cur 章未完成 → drop（避免 placeholderChapter 期间脏写）
            //   2. reportChapterIdx != cur 章 → drop（窗口边缘瞬时态：用户视野落 prev 章末
            //      或 next 章首，不该按 cur 章 idx 写 DB）
            //   3. 跳转静默期内 → drop（scrollToItem settle 中间态）
            onChapterProgressLive = { reportChapterIdx, prog ->
                val nowMs = System.currentTimeMillis()
                val curChapterIdx = chapter?.chapterIndex ?: -1
                when {
                    chapter?.isCompleted != true -> Unit
                    reportChapterIdx != curChapterIdx -> {
                        AppLog.debug(
                            "Progress",
                            "drop live: chIdx=$reportChapterIdx != cur=$curChapterIdx (cross-chapter transient)",
                        )
                    }
                    nowMs < jumpSilenceUntilMs -> {
                        AppLog.debug(
                            "Progress",
                            "drop live: jump silence ${jumpSilenceUntilMs - nowMs}ms remaining",
                        )
                    }
                    else -> onProgress(prog)
                }
            },
            onChapterProgressPersist = { reportChapterIdx, prog ->
                val nowMs = System.currentTimeMillis()
                val curChapterIdx = chapter?.chapterIndex ?: -1
                when {
                    chapter?.isCompleted != true -> Unit
                    reportChapterIdx != curChapterIdx -> {
                        AppLog.debug(
                            "Progress",
                            "drop persist: chIdx=$reportChapterIdx != cur=$curChapterIdx",
                        )
                    }
                    nowMs < jumpSilenceUntilMs -> {
                        AppLog.debug(
                            "Progress",
                            "drop persist: jump silence ${jumpSilenceUntilMs - nowMs}ms remaining",
                        )
                    }
                    else -> onProgress(prog)
                }
            },
            onVisibleParagraphChanged = { p, _ ->
                val titleForChapter = when (p.chapterIndex) {
                    chapter?.chapterIndex -> chapterTitle
                    prevTextChapter?.chapterIndex -> prevChapterTitle
                    nextTextChapter?.chapterIndex -> nextChapterTitle
                    else -> ""
                }
                onVisiblePageChanged(p.chapterIndex, titleForChapter, "", p.firstChapterPosition)
            },
            onScrollingChanged = onScrollingChanged,
            onTapCenter = onTapCenter,
            chapterCharSizeProvider = { paraChapterIdx -> chapterCharSizes[paraChapterIdx] ?: 1 },
            modifier = Modifier.fillMaxSize(),
        )

        // ── 段级 mini-menu ──
        //
        // selectionState != null 时叠在 LazyScrollRenderer 上层。各 callback 调用后
        // 立即清 selection，让段背景褪去 + Popup 关闭，统一手感。
        //
        // 与分页模式的 ReaderSelectionToolbar 一致：用 SelectionToolbar 渲染、
        // ReaderToolbarPositionProvider 决定 above/below。anchor 是 long-press tap
        // 在本 Box 内的局部坐标（已经过 windowToLocal 转换）。
        val sel = scrollSelection
        if (sel != null) {
            SelectionToolbar(
                offset = sel.anchorInBox,
                onCopy = {
                    onCopyText(sel.text)
                    scrollSelection = null
                },
                onSpeak = {
                    onSpeakFromHere(sel.startChapterPos)
                    scrollSelection = null
                },
                onTranslate = {
                    onTranslateText(sel.text)
                    scrollSelection = null
                },
                onShare = {
                    onShareQuote(sel.text)
                    scrollSelection = null
                },
                onLookup = {
                    onLookupWord(sel.text)
                    scrollSelection = null
                },
                onHighlight = onAddHighlight?.let { cb ->
                    { argb ->
                        cb(sel.startChapterPos, sel.endChapterPos, sel.text, argb)
                        scrollSelection = null
                    }
                },
                onEraseHighlight = onEraseHighlight?.let { cb ->
                    {
                        cb(sel.startChapterPos, sel.endChapterPos)
                        scrollSelection = null
                    }
                },
                onTextColor = onAddTextColor?.let { cb ->
                    { argb ->
                        cb(sel.startChapterPos, sel.endChapterPos, sel.text, argb)
                        scrollSelection = null
                    }
                },
                onDismiss = { scrollSelection = null },
                config = selectionMenuConfig,
            )
        }
    }
}

/**
 * 段级 mini-menu 选区状态。Phase 5 字符级选区落地后会扩成 (startTextPos, endTextPos)
 * 双锚点版本，但段级 MVP 只需要"哪个段被选 + 章内字符范围 + Popup 锚点"。
 *
 * @property paragraphKey 被选中段的 [ScrollParagraph.key]，给 [LazyScrollRenderer] 决定
 *           哪个段画选中前景
 * @property text 段落连续字符（按 [TextBaseColumn.charData] 顺序拼成），mini-menu 各动作的载荷
 * @property chapterIndex 段所在的章索引（onAddHighlight 落 DB 用）
 * @property startChapterPos / [endChapterPos] 章内字符 [start, end) 区间，
 *           Highlight 的 startChapterPos / endChapterPos 直接用
 * @property anchorInBox 长按 tap 点在 [LazyScrollSection] 包裹 Box 局部坐标系下的位置，
 *           SelectionToolbar 的 ReaderToolbarPositionProvider 用它决定 above/below
 */
private data class ScrollSelectionState(
    val paragraphKey: String,
    val text: String,
    val chapterIndex: Int,
    val startChapterPos: Int,
    val endChapterPos: Int,
    val anchorInBox: Offset,
)
