package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.entity.SelectionMenuConfig
import com.morealm.app.domain.render.ScrollAnchor
import com.morealm.app.domain.render.ScrollParagraph
import com.morealm.app.domain.render.TextBaseColumn
import com.morealm.app.domain.render.TextChapter
import com.morealm.app.domain.render.bookmarkToAnchor
import com.morealm.app.domain.render.chapterPositionToParagraphPos
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
    /**
     * 当前章节范围内的用户高亮（kind=0，画底色矩形）。透传给
     * [LazyScrollRenderer.chapterHighlights]，由各 [ScrollParagraphItem] 按段
     * chapter range 过滤后画。
     */
    chapterHighlights: List<HighlightSpan> = emptyList(),
    /**
     * 当前章节范围内的字体强调色 spans（kind=1，替换 paint.color）。
     */
    chapterTextColorSpans: List<HighlightSpan> = emptyList(),
    /**
     * 当前章节范围内的高亮原始对象（含 id / content / colorArgb / range）。
     *
     * 与 [chapterHighlights] 平行——后者是已派生为渲染元数据 [HighlightSpan] 的列表，
     * 用于画底色矩形；本列表保留原始 [Highlight]，给"tap 命中已存高亮 → 弹删除/分享菜单"
     * 路径用：tap 落点解析出 (chapterIndex, chapterPos) 后，需要凭 chapterPos 在
     * `[startChapterPos, endChapterPos)` 内匹配某条 Highlight，并把 `id` 给
     * [onDeleteHighlight]、把整条 [Highlight] 给 [onShareHighlight]（生成卡片图）。
     *
     * 缺省空 List —— caller 不传时 tap 永远 miss，行为退化到旧版"只切控制栏"。
     */
    chapterHighlightsRaw: List<Highlight> = emptyList(),
    /**
     * tap 命中已存高亮后，用户在 action menu 选删除时回调，传入 [Highlight.id]。
     * caller 一般转发到 [com.morealm.app.presentation.reader.ReaderHighlightController.delete]。
     */
    onDeleteHighlight: (id: String) -> Unit = {},
    /**
     * tap 命中已存高亮后，用户在 action menu 选分享时回调，传入整条 [Highlight]
     * （分享卡片需要 content / bookTitle / chapterTitle 等字段，单 id 不够）。
     */
    onShareHighlight: (Highlight) -> Unit = {},
    onTapCenter: () -> Unit,
    onVisiblePageChanged: (chapterIdx: Int, title: String, readProgress: String, charPos: Int) -> Unit,
    onScrollingChanged: (Boolean) -> Unit,
    /**
     * true 时章首 CHAPTER_TITLE 段（含 isTitle / isChapterNum 行）被 [toScrollParagraphs]
     * 置空，视觉上看不到章节标题分隔。给本地 TXT 无目录自动分章场景用。
     *
     * 之前实现走 `cur.title.looksLikeAutoSplitTitle()` 嗅探：但 [chapterTitle] 已经在
     * [com.morealm.app.ui.reader.ReaderScreen] 经 [com.morealm.app.domain.entity.displayTitle]
     * 替换成书名，正则 `^第\d+节$` 永远 miss → 标题段照画 → 用户每段都看到一次书名。
     * 改成由 caller 显式判定（book.format/localPath/chapters.all { isAutoSplitChapter() }）
     * 后传入。
     */
    omitChapterTitleBlock: Boolean = false,
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

    // ── effect 1: cur 章 reset / 流式 append ──
    //
    // 触发条件：
    //   (a) chapter.chapterIndex 变化 —— 用户跳章 / commitChapterShift / loadChapter
    //   (b) chapter.pages.size 变化 —— **流式 layout 中**逐步排版完成的页数增量
    //   (c) chapter.isCompleted 翻 true —— 全章 layout 完毕
    //
    // 之前实现强制 `if (!cur.isCompleted) return`，导致 EPUB 大章节流式 layout 期间
    // [paragraphsState] 一直为空，[paragraphsReady] 卡 false，用户被困在 ReaderLoadingCover
    // 看不到正文（实测一本 316 页 EPUB 章节流式时间 ≈ 4.7s，期间用户体感"一直在加载"）。
    //
    // 现在改成：流式中 pages 每增量都重 reset，让 [paragraphsState] 提前出现可渲染段；
    // 用户只要等首页 layout 完（毫秒级），就能进入正文。后续每次 pages 增加再 reset 时
    // 通过快照 anchorParaKey + requestScrollToItem 还原视野，避免抖动。
    //
    // [lastResetSnapshot] 用 (chapterIdx, pages.size, isCompleted) 三元组当幂等 key，
    // 重复触发同一组合直接跳过；流式期间每次 pages 增长都换出新 key，触发增量 reset。
    var lastResetSnapshot by remember { mutableStateOf(Triple(-1, -1, false)) }
    LaunchedEffect(chapter?.chapterIndex, chapter?.pageCount, chapter?.isCompleted) {
        val cur = chapter ?: return@LaunchedEffect
        val snapshot = Triple(cur.chapterIndex, cur.pageCount, cur.isCompleted)
        if (snapshot == lastResetSnapshot) return@LaunchedEffect
        // pages 还没出来（pageCount = 0）时 toScrollParagraphs 也会空 —— 先跳过，
        // 等下次回调 pageCount > 0 时再来。
        if (cur.pageCount == 0) return@LaunchedEffect

        // 流式 reset 时如果用户已滚到某段，先快照 anchor 用于 reset 后还原。
        // 首次进入（paragraphsState 为空）时 anchorParaKey null，跳过还原走自然 0 起点。
        val anchorParaIdx = listState.firstVisibleItemIndex
        val anchorOffsetPx = listState.firstVisibleItemScrollOffset
        val anchorParaKey = paragraphsState.getOrNull(anchorParaIdx)?.key

        val prev = prevTextChapter?.takeIf { it.isCompleted == true }
        val next = nextTextChapter?.takeIf { it.isCompleted == true }
        // 用 caller 显式传入的 omitChapterTitleBlock 决定 skipChapterTitleParagraph：
        // 之前依赖 cur.title.looksLikeAutoSplitTitle() 已不可靠（chapterTitle 在 ReaderScreen
        // 被 displayTitle 替换为书名，正则永远 miss）。本 flag 同时影响 prev/cur/next 三章，
        // 因为对一本本地 TXT 来说"是否无 TOC 自动分章"是整书属性，不会某章是某章不是。
        val prevParas = prev?.toScrollParagraphs(omitChapterTitleBlock).orEmpty()
        val curParas = cur.toScrollParagraphs(omitChapterTitleBlock)
        val nextParas = next?.toScrollParagraphs(omitChapterTitleBlock).orEmpty()

        paragraphsState.clear()
        paragraphsState.addAll(prevParas)
        paragraphsState.addAll(curParas)
        paragraphsState.addAll(nextParas)
        lastResetSnapshot = snapshot

        // anchor 还原：仅在用户曾滚动到非首段时才需要（首段重 reset 后自然在 0）。
        // 找不到对应 key 说明该段在 reset 后已不存在（罕见，发生在跨章正在切换的瞬间），
        // 此时不强行 scrollTo，保留 listState 当前位置。
        if (anchorParaKey != null && anchorParaIdx > 0) {
            val newAnchorParaIdx = paragraphsState.indexOfFirst { it.key == anchorParaKey }
            if (newAnchorParaIdx >= 0) {
                listState.requestScrollToItem(newAnchorParaIdx, anchorOffsetPx)
            }
        }
        AppLog.debug(
            "LazyScroll",
            "reset window chIdx=${cur.chapterIndex} pages=${cur.pageCount} completed=${cur.isCompleted}: " +
                "prev=${prevParas.size} cur=${curParas.size} next=${nextParas.size}" +
                if (anchorParaKey != null && anchorParaIdx > 0) " | restored anchor=$anchorParaKey" else "",
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

        val prevParas = prev.toScrollParagraphs(omitChapterTitleBlock)
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

        val nextParas = next.toScrollParagraphs(omitChapterTitleBlock)
        if (nextParas.isEmpty()) return@LaunchedEffect
        paragraphsState.addAll(nextParas)
        AppLog.debug("LazyScroll", "next append: added ${nextParas.size} paragraphs at tail")
    }

    // ── 锚点恢复（用户主动跳转 / 续读启动）──
    //
    // 之前条件：仅 [chapter.isCompleted=true] 才计算 anchor，导致 EPUB 大章节流式
    // layout 期间 anchor 一直 null，从而 [paragraphsReady] 卡 false 把用户困在 loading。
    //
    // 现在：只要 [paragraphsState] 非空就开始算 anchor —— effect 1 流式 reset 已经填充
    // paragraphsState，能算出哪个段对应 [initialChapterPosition]。短暂期内 anchor 可能
    // 落在 prev 章或 cur 章首段（pages 还没排到目标偏移那么远），用 atChapterStart 兜底；
    // 一旦后续 reset 把目标段补进来，[chapterPositionToParagraphPos] 路径会接管命令式跳转。
    val paragraphsReady = paragraphsState.isNotEmpty()
    val initialAnchor = remember(chapterIndex, initialChapterPosition, paragraphsReady, paragraphsState.size) {
        if (!paragraphsReady) null
        else bookmarkToAnchor(chapterIndex, initialChapterPosition, paragraphsState)
            ?: ScrollAnchor.atChapterStart(chapterIndex)
    }

    if (!paragraphsReady || initialAnchor == null) {
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
    // tap 命中已存高亮时的 action menu 状态：选中的 [Highlight] 与 tap 锚点（Box 局部坐标）。
    // 与 [scrollSelection] 互斥 —— 一旦命中已存高亮，先清编辑选区再弹动作菜单，避免
    // 双浮层叠加。
    var highlightActionTarget by remember(chapterIndex) { mutableStateOf<Highlight?>(null) }
    var highlightActionAnchor by remember(chapterIndex) { mutableStateOf(Offset.Zero) }
    // wrapper Box 在 root window 中的 LayoutCoordinates —— 用来把 long-press 上报的
    // anchorInWindow 转成 Box 内局部坐标（Popup parent 坐标系一致）。
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // 滚动 / fling 时立即清选区 + 已存高亮动作菜单：
    //  - 段已经划出 viewport，菜单浮在空气上没意义
    //  - 用户的预期是"我开始滚说明放弃了选这段 / 不想看那条高亮的菜单了"
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                scrollSelection = null
                highlightActionTarget = null
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> boxCoords = coords },
    ) {
        // 把字符级选区转成 [HighlightSpan] 注入 chapterHighlights 末尾，让
        // [drawScrollParagraphContent] 复用现有 paragraphHighlights 渲染路径
        // 画选区背景。颜色取主题 selectionColor —— 与分页 / SIMULATION 模式一致。
        // 选 30% alpha 是 PageContentDrawer 的默认值（[DEFAULT_SELECTION_COLOR]）。
        val theme = LocalReaderRenderTheme.current
        val effectiveChapterHighlights = remember(chapterHighlights, scrollSelection, theme) {
            val sel = scrollSelection
            if (sel == null || sel.endChapterPos <= sel.startChapterPos) chapterHighlights
            else {
                val selSpan = HighlightSpan(
                    id = "__scroll_selection__",
                    startChapterPos = sel.startChapterPos,
                    endChapterPos = sel.endChapterPos,
                    colorArgb = theme.selectionColor.toArgb(),
                )
                chapterHighlights + selSpan
            }
        }
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
            // 选区改成字符级后不再用整段染色作为视觉提示——selectionSpan 已经画在
            // 字符背景上，再加段级背景反而会盖住已存高亮 / 字色 spans。传 null
            // 让 [ScrollParagraphItem.isSelected] 不命中。
            selectedParagraphKey = null,
            chapterHighlights = effectiveChapterHighlights,
            chapterTextColorSpans = chapterTextColorSpans,
            // ── tap 段落 → 三步 dispatch ──
            //
            // 1. 编辑选区 / 已存高亮菜单弹着 → 任何 tap 优先把它们清掉（dismiss-on-tap），
            //    用户预期"在空白点一下 = 关菜单"。这一步阻止后续的 tapCenter 切控制栏，
            //    避免出现"点一下既关菜单又切控制栏"的双重反应。
            //
            // 2. 命中已存高亮 → tap 字符在 [startChapterPos, endChapterPos) 内某条
            //    [Highlight]，弹 [HighlightActionToolbar]（删除 / 分享）。anchor 用
            //    `paragraphPosInBox + tapInPara` 在 wrapper Box 内的局部坐标。
            //
            // 3. 都不是 → 走 [onTapCenter] 切阅读器控制栏。
            //
            // 与分页模式的 onSingleTap (CanvasRenderer L1432-1488) 行为对齐。
            onTapParagraph = { paragraph, charOffsetInParagraph, anchorInWindow ->
                // step 1: dismiss
                if (scrollSelection != null) {
                    scrollSelection = null
                    return@LazyScrollRenderer
                }
                if (highlightActionTarget != null) {
                    highlightActionTarget = null
                    return@LazyScrollRenderer
                }
                // step 2: highlight hit-test
                if (chapterHighlightsRaw.isNotEmpty()) {
                    val hitChapterPos = paragraph.firstChapterPosition + charOffsetInParagraph
                    val hit = chapterHighlightsRaw.firstOrNull { h ->
                        h.chapterIndex == paragraph.chapterIndex &&
                            hitChapterPos in h.startChapterPos until h.endChapterPos
                    }
                    if (hit != null) {
                        val coords = boxCoords
                        if (coords != null) {
                            highlightActionAnchor = coords.windowToLocal(anchorInWindow)
                            highlightActionTarget = hit
                            return@LazyScrollRenderer
                        }
                    }
                }
                // step 3: fall through
                onTapCenter()
            },
            // ── 长按段落 → 段级选区 ──
            //
            // 1. 抽出整段文本（buildString 拼 TextBaseColumn.charData）
            // 2. anchorInWindow 通过 boxCoords.windowToLocal 转到 wrapper 局部
            // 3. ScrollSelectionState 包含全部 mini-menu 动作所需的元信息
            //
            // Phase 5 升级：长按从"整段被选中"改成"长按选词"。用 BreakIterator 在段内
            // 找到 tap 字符所在的词 boundaries，selection.startChapterPos / endChapterPos
            // 落到字符级精度。后续用户可以拖动 CursorHandle 调整选区起止字符。
            onLongPressParagraph = { paragraph, charOffsetInParagraph, anchorInWindow ->
                val (wordStartInPara, wordEndInPara) = findWordRangeInParagraph(
                    paragraph, charOffsetInParagraph,
                )
                val paraText = paragraphText(paragraph)
                if (paraText.isBlank()) return@LazyScrollRenderer
                val safeStart = wordStartInPara.coerceIn(0, paraText.length)
                val safeEnd = wordEndInPara.coerceIn(safeStart, paraText.length)
                val selectedText = paraText.substring(safeStart, safeEnd)
                if (selectedText.isBlank()) return@LazyScrollRenderer
                val coords = boxCoords ?: return@LazyScrollRenderer
                // windowToLocal：把 root window 坐标 (anchorInWindow) 转成 Box 局部坐标
                val anchorInBox = coords.windowToLocal(anchorInWindow)
                scrollSelection = ScrollSelectionState(
                    paragraphKey = paragraph.key,
                    text = selectedText,
                    chapterIndex = paragraph.chapterIndex,
                    startChapterPos = paragraph.firstChapterPosition + safeStart,
                    endChapterPos = paragraph.firstChapterPosition + safeEnd,
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

            // ── Phase 5 双 CursorHandle 拖把手 ──
            //
            // 在 LazyColumn 之上（同 Box 内）叠两个圆点，分别锚定 selection 起点
            // 和终点。位置由 [cursorOffsetInScrollBox] 实时算 —— LazyColumn 滚动 /
            // 用户保存高亮 / 段窗口增量 mutate 都会让 visibleItemsInfo 变化，
            // 但这里不需要 LaunchedEffect：每次 recompose 重算 offset 即可，CursorHandle
            // 的 offset { ... } modifier 自己会读最新值；snapshot 依赖通过
            // listState.layoutInfo（自动追踪）建立。
            //
            // 拖动 → [hitTestScrollPos] 反向解析出 (chapterIndex, chapterPos)，
            // 同章内更新 selection 起 / 止字符位置；跨章拖动暂不允许（保持 sel 章节
            // 一致性，避免 SelectionToolbar 的 onAddHighlight 落 DB 时产生跨章
            // Highlight 这种数据库层不支持的组合）。
            val startCursorOffset = cursorOffsetInScrollBox(
                listState, paragraphsState,
                sel.chapterIndex, sel.startChapterPos, isStart = true,
            )
            val endCursorOffset = cursorOffsetInScrollBox(
                listState, paragraphsState,
                sel.chapterIndex, sel.endChapterPos, isStart = false,
            )
            startCursorOffset?.let { off ->
                CursorHandle(
                    position = off,
                    color = theme.selectionColor.copy(alpha = 0.85f),
                    onDrag = { dragOff ->
                        val hit = hitTestScrollPos(listState, paragraphsState, dragOff) ?: return@CursorHandle
                        val (chIdx, chPos) = hit
                        if (chIdx != sel.chapterIndex) return@CursorHandle
                        // 起点不能越过终点；越过时 swap 成 end 拖到了新位置。
                        val curEnd = scrollSelection?.endChapterPos ?: return@CursorHandle
                        if (chPos >= curEnd) return@CursorHandle
                        scrollSelection = sel.copy(
                            startChapterPos = chPos,
                            text = substringFromParagraphs(
                                paragraphsState, sel.chapterIndex, chPos, curEnd,
                            ),
                        )
                    },
                )
            }
            endCursorOffset?.let { off ->
                CursorHandle(
                    position = off,
                    color = theme.selectionColor.copy(alpha = 0.85f),
                    onDrag = { dragOff ->
                        val hit = hitTestScrollPos(listState, paragraphsState, dragOff) ?: return@CursorHandle
                        val (chIdx, chPos) = hit
                        if (chIdx != sel.chapterIndex) return@CursorHandle
                        val curStart = scrollSelection?.startChapterPos ?: return@CursorHandle
                        if (chPos <= curStart) return@CursorHandle
                        scrollSelection = sel.copy(
                            endChapterPos = chPos,
                            text = substringFromParagraphs(
                                paragraphsState, sel.chapterIndex, curStart, chPos,
                            ),
                        )
                    },
                )
            }
        }

        // ── 已存高亮的 action menu（删除 / 分享）──
        //
        // 与编辑选区的 SelectionToolbar 互斥：onTapParagraph 路径里二者只命中其一，
        // 同时切换到 SCROLL 模式启动时也用 chapterIndex 作为 remember key 让两者随
        // 跨章自动清理。anchor 直接用上面 hit-test 时存下的 highlightActionAnchor
        // （已经过 windowToLocal 转 Box 局部坐标），与分页模式的 highlightActionOffset
        // 字段语义对齐。
        highlightActionTarget?.let { target ->
            HighlightActionToolbar(
                offset = highlightActionAnchor,
                colorArgb = target.colorArgb,
                onDelete = {
                    AppLog.info(
                        "Highlight",
                        "user delete via scroll action menu id=${target.id} " +
                            "chIdx=${target.chapterIndex} " +
                            "range=${target.startChapterPos}..${target.endChapterPos} " +
                            "contentLen=${target.content.length}",
                    )
                    onDeleteHighlight(target.id)
                    highlightActionTarget = null
                },
                onShare = {
                    AppLog.info(
                        "Highlight",
                        "user share via scroll action menu id=${target.id} " +
                            "chIdx=${target.chapterIndex} contentLen=${target.content.length}",
                    )
                    onShareHighlight(target)
                    highlightActionTarget = null
                },
                onDismiss = { highlightActionTarget = null },
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

// ── Phase 5: 字符级选区 helper ────────────────────────────────────────────────

/**
 * 把 [ScrollParagraph] 内所有 [TextBaseColumn.charData] 顺序拼起来，得到段连续字符流。
 * 长按选词、selection 转 chapterPos 都用这个口径，与渲染层
 * [com.morealm.app.ui.reader.renderer.drawScrollParagraphContent] 用 charData.length
 * 累计 charPos 的逻辑保持一致 —— 任何"字符索引"在两边含义相同。
 */
private fun paragraphText(p: com.morealm.app.domain.render.ScrollParagraph): String =
    buildString {
        for (line in p.lines) {
            for (col in line.columns) {
                if (col is TextBaseColumn) append(col.charData)
            }
        }
    }

/**
 * 在段内字符流上做 BreakIterator 找词 boundary。
 *
 * @param charOffsetInPara tap 命中的段内字符索引（[paragraphText] 的索引口径）。
 * @return (wordStart, wordEnd) 段内字符索引半开区间。tap 不命中任何 word（空段 /
 *         偏移越界）时退化为 (offset, offset+1)。
 *
 * 与 [com.morealm.app.ui.reader.renderer.findWordRange] 一致使用 BreakIterator，
 * 但本函数不构建 charMap —— 段内字符在 [paragraphText] 已是 1:1 顺序。
 */
private fun findWordRangeInParagraph(
    paragraph: com.morealm.app.domain.render.ScrollParagraph,
    charOffsetInPara: Int,
): Pair<Int, Int> {
    val text = paragraphText(paragraph)
    if (text.isEmpty()) return 0 to 0
    val safeOffset = charOffsetInPara.coerceIn(0, text.length - 1)
    val boundary = java.text.BreakIterator.getWordInstance(java.util.Locale.getDefault())
    boundary.setText(text)
    var start = boundary.first()
    var end = boundary.next()
    while (end != java.text.BreakIterator.DONE) {
        if (safeOffset in start until end) {
            return start to end
        }
        start = end
        end = boundary.next()
    }
    return safeOffset to (safeOffset + 1).coerceAtMost(text.length)
}

/**
 * 把段内字符索引转换成 (lineIndex, columnIndex)。
 *
 * 用 [TextLine.charSize] 累加找行（线性扫描，对一段 10~30 行的小段成本可忽略），
 * 行内再用 [TextBaseColumn.charData.length] 累加找列。
 *
 * @return null 如果段为空 / charInPara 完全越界（含 paragraphEnd 多算的 +1 也兜得住）。
 */
private fun paraCharPosToLineCol(
    paragraph: com.morealm.app.domain.render.ScrollParagraph,
    charInPara: Int,
): Pair<Int, Int>? {
    val lines = paragraph.lines
    if (lines.isEmpty()) return null
    var acc = 0
    var lineIdx = -1
    val safeChar = charInPara.coerceAtLeast(0)
    for (i in lines.indices) {
        val cs = lines[i].charSize
        if (acc + cs > safeChar) {
            lineIdx = i
            break
        }
        acc += cs
    }
    if (lineIdx < 0) {
        // 超过段字符数：clamp 到末行末列
        val last = lines.last()
        return (lines.lastIndex) to (last.columns.lastIndex.coerceAtLeast(0))
    }
    val line = lines[lineIdx]
    val charInLine = (safeChar - acc).coerceAtLeast(0)
    var colCharAcc = 0
    var colIdx = 0
    var found = false
    for (i in line.columns.indices) {
        val col = line.columns[i]
        if (col is TextBaseColumn) {
            val len = col.charData.length
            if (colCharAcc + len > charInLine) {
                colIdx = i
                found = true
                break
            }
            colCharAcc += len
        }
    }
    if (!found) {
        colIdx = line.columns.lastIndex.coerceAtLeast(0)
    }
    return lineIdx to colIdx
}

/**
 * 算 cursor handle 在 [LazyScrollSection] 包裹 Box 内的屏幕坐标。
 *
 * @param listState LazyColumn 状态，需要 [androidx.compose.foundation.lazy.LazyListState.layoutInfo]
 *        来拿 visibleItemsInfo（每个段在 viewport 中的 offset）。
 * @param paragraphs 段窗口（与 LazyColumn item 索引一一对应）。
 * @param chapterIndex / chapterPos 选区端点的章节字符位置。
 * @param isStart true = 起点 cursor（取段内 column.start，左缘）；false = 终点 cursor
 *        （取 column.end，右缘）。
 * @return null 如果选区端点所在段不在当前 viewport（cursor 不应该悬浮在屏外）。
 *
 * 为什么 cursor 不在 viewport 时返回 null：用户滚到选区"看不见"的章节，再画 cursor
 * 在屏幕外或贴在屏幕边缘都没意义；让 overlay 自然消失，等用户滚回视野再出现。
 * SelectionToolbar 的 anchorInBox 同样会在选区滚出 viewport 时变得无意义，但
 * `listState.isScrollInProgress` 的 effect 会立即 clear selection，所以不会冲突。
 */
private fun cursorOffsetInScrollBox(
    listState: androidx.compose.foundation.lazy.LazyListState,
    paragraphs: List<com.morealm.app.domain.render.ScrollParagraph>,
    chapterIndex: Int,
    chapterPos: Int,
    isStart: Boolean,
): Offset? {
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    // 半开区间 [start, end) 的几何意义：
    //   - start cursor 画在 startChapterPos 对应字符的**左缘**（column.start）
    //   - end cursor 画在 (endChapterPos - 1) 对应字符的**右缘**（column.end）
    //
    // 之前实现用 endChapterPos 直接查列再取 column.end —— 相当于查到了"末选字符之后
    // 那个字符"的列，再取它的右缘，整体多 1 字宽。修正：end 时把 lookup 位置回退 1。
    // endChapterPos <= startChapterPos 的退化态用 startChapterPos 兜底，避免负值。
    val lookupPos = if (isStart) chapterPos
        else (chapterPos - 1).coerceAtLeast(0)
    val paraIdx = paragraphs.indexOfFirst {
        it.chapterIndex == chapterIndex &&
            it.firstChapterPosition <= lookupPos &&
            (it.firstChapterPosition + it.charSize) > lookupPos
    }
    if (paraIdx < 0) return null
    val item = visible.firstOrNull { it.index == paraIdx } ?: return null
    val para = paragraphs[paraIdx]
    val charInPara = (lookupPos - para.firstChapterPosition).coerceAtLeast(0)
    val (lineIdx, colIdx) = paraCharPosToLineCol(para, charInPara) ?: return null
    if (lineIdx !in para.lines.indices || lineIdx >= para.linePositions.size) return null
    val line = para.lines[lineIdx]
    if (line.columns.isEmpty()) return null
    val safeColIdx = colIdx.coerceIn(0, line.columns.lastIndex)
    val column = line.columns[safeColIdx]
    val x = if (isStart) column.start else column.end
    val yInPara = para.linePositions[lineIdx] + (line.lineBottom - line.lineTop)
    val yInBox = item.offset + yInPara
    return Offset(x, yInBox)
}

/**
 * 反向 hit-test：把 box 内拖动坐标 [dragOffset] 转换回 (chapterIndex, chapterPos)。
 *
 * 步骤：
 *   1. 在 visibleItemsInfo 里找 dragOffset.y 落在哪个 item
 *   2. local y = dragOffset.y - item.offset → 段内 y
 *   3. paragraph.linePositions 找行
 *   4. line.columns 用 [com.morealm.app.domain.render.BaseColumn.isTouch] 找列
 *   5. 累加 col.charData.length 得到行内字符 offset
 *   6. line.chapterPosition + offset = 章内字符位置；段 chapterIndex 直接拿
 *
 * 落到段外（y 越过末段）→ 取最后一个 visible item 的末段末字符。
 * x 越过行末 → 行末 column。
 */
private fun hitTestScrollPos(
    listState: androidx.compose.foundation.lazy.LazyListState,
    paragraphs: List<com.morealm.app.domain.render.ScrollParagraph>,
    dragOffset: Offset,
): Pair<Int, Int>? {
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    val y = dragOffset.y
    // 找命中的 item：item.offset <= y < item.offset + item.size
    val item = visible.firstOrNull {
        y >= it.offset && y < it.offset + it.size
    } ?: visible.lastOrNull { y >= it.offset } ?: visible.firstOrNull()
        ?: return null
    val paraIdx = item.index
    if (paraIdx !in paragraphs.indices) return null
    val para = paragraphs[paraIdx]
    if (para.lines.isEmpty()) return null
    val yInPara = (y - item.offset)
    // 找行：最后一个 linePosition <= yInPara
    var lineIdx = para.lines.lastIndex
    for (i in para.linePositions.indices) {
        if (para.linePositions[i] > yInPara) {
            lineIdx = (i - 1).coerceAtLeast(0)
            break
        }
    }
    val line = para.lines.getOrNull(lineIdx) ?: return null
    if (line.columns.isEmpty()) return null
    // 行内 hit：x 落在哪个 col 上
    val x = dragOffset.x
    var colHit = -1
    for (i in line.columns.indices) {
        if (line.columns[i].isTouch(x)) {
            colHit = i
            break
        }
    }
    if (colHit < 0) {
        // x 越过行末 / 落在行首左侧；clamp 到对应端
        colHit = if (line.columns.firstOrNull()?.start ?: 0f < x)
            line.columns.lastIndex
        else 0
    }
    // 累计 col.charData.length 算行内字符 offset
    var charsBefore = 0
    for (i in 0 until colHit) {
        val c = line.columns[i]
        if (c is TextBaseColumn) charsBefore += c.charData.length
    }
    val chapterPos = line.chapterPosition + charsBefore
    return para.chapterIndex to chapterPos
}

/**
 * 沿 [paragraphs] 在指定章节里抽出 [startChapterPos, endChapterPos) 区间的字符流。
 *
 * 用于 cursor 拖动后实时刷 [ScrollSelectionState.text]。算法：
 *   1. 顺序扫 paragraphs，跳过非目标 chapterIndex
 *   2. 段范围 [paraStart, paraEnd) 与 [start, end) 求交，把交集字符 append 进 sb
 *   3. 用 [TextLine.charSize] 累计行内位置，[TextBaseColumn.charData] 提供字符串
 *
 * 跨段时拼接段间换行符 `\n`，保持原文段落分隔（与 [paragraphText] 单段口径区分开
 * —— 段内不再注入额外字符）。
 */
private fun substringFromParagraphs(
    paragraphs: List<com.morealm.app.domain.render.ScrollParagraph>,
    chapterIndex: Int,
    startChapterPos: Int,
    endChapterPos: Int,
): String {
    if (endChapterPos <= startChapterPos) return ""
    val sb = StringBuilder()
    var firstParaWritten = false
    for (para in paragraphs) {
        if (para.chapterIndex != chapterIndex) continue
        val paraStart = para.firstChapterPosition
        val paraEnd = paraStart + para.charSize
        if (paraEnd <= startChapterPos) continue
        if (paraStart >= endChapterPos) break
        // 段内偏移：[localStart, localEnd) in paragraphText
        val localStart = (startChapterPos - paraStart).coerceAtLeast(0)
        val localEnd = (endChapterPos - paraStart).coerceAtMost(para.charSize)
        if (localEnd <= localStart) continue
        val text = paragraphText(para)
        val safeStart = localStart.coerceIn(0, text.length)
        val safeEnd = localEnd.coerceIn(safeStart, text.length)
        if (firstParaWritten) sb.append('\n')
        sb.append(text.substring(safeStart, safeEnd))
        firstParaWritten = true
    }
    return sb.toString()
}
