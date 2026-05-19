package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.scroll.ScrollLayoutEngine
import com.morealm.app.domain.render.scroll.extractText
import com.morealm.app.domain.render.scroll.findColumnAt
import com.morealm.app.domain.render.scroll.findColumnByPixel
import com.morealm.app.ui.reader.renderer.ReaderInfoBar
import com.morealm.app.ui.reader.renderer.SelectionToolbar
import com.morealm.app.ui.reader.renderer.drawBgBitmap
import com.morealm.app.ui.reader.renderer.rememberBatteryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 滚动 Canvas V2 阅读器宿主 —— ReaderScreen 直接调用，封装：
 * 1. ScrollLayoutEngine 实例化（按外部传入的 paint / 视图参数）
 * 2. 3 章异步加载 + 排版 → [ScrollCanvasReaderState]
 * 3. ScrollCanvasRenderer（三块面板 + 滚动 + 羽化 + 视口剔除 + tap-toggle）
 * 4. ReaderInfoBar 顶 / 底两条状态栏（与旧 SCROLL CanvasRenderer 完全同款 ReaderInfoBar 调用）
 * 5. ScrollSelectionOverlay（M6.4 暂禁，M4-revive 用 awaitEachGesture 修拦截后恢复）
 * 6. ScrollSelectionMenu（同上）
 *
 * Host 不持业务依赖（不引 Hilt / ViewModel），只接：
 *   - 章节状态：currentChapterIndex / chapterCount / loadChapterContent suspend lambda
 *   - 排版参数：viewWidth / viewHeight / paddingLeft / paddingRight / paddingTop / paddingBottom / fontSize
 *   - 回调：onChapterIndexChange / onTapCenter / onSelectionAction
 *   - InfoBar 配置：[ScrollCanvasInfoBarConfig]（null 时不画状态栏，纯净内容模式）
 */
@OptIn(FlowPreview::class)
@Composable
fun ScrollCanvasReaderHost(
    currentChapterIndex: Int,
    chapterCount: Int,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    viewWidth: Int,
    viewHeight: Int,
    paddingLeft: Int = 80,
    paddingRight: Int = 80,
    paddingTop: Int = 120,
    paddingBottom: Int = 120,
    fontSize: Int = 48,
    /**
     * 正文字色（android argb）—— 与 [com.morealm.app.ui.reader.renderer.CanvasRenderer]
     * 同源（来自 ReaderTheme.readerText.toArgb()）。null 走默认黑色，但 ReaderScreen
     * 必传，避免夜间模式黑底黑字。
     */
    textColorArgb: Int? = null,
    /**
     * 用户字体（[android.graphics.Typeface]）。来自 ReaderScreen 的 readerTypeface。
     * null 走默认；与 V1 CanvasRenderer 同源。
     */
    typeface: android.graphics.Typeface? = null,
    /** 是否夜间模式 —— 决定 title / chapterNum 的颜色派生。 */
    isNight: Boolean = false,
    /** letterSpacing（ReaderStyle.letterSpacing）—— 字符间距乘 fontSize。 */
    letterSpacing: Float = 0f,
    /** 字重：0=normal / 1=bold / 2=light（ReaderStyle.textBold）。 */
    textBold: Int = 0,
    /**
     * 行距倍率（ReaderStyle.lineHeight），1.0 = 行高刚好，1.5 = 1.5 倍。
     * 引擎用 lineSpacingExtra = 此值；默认 1.2 与旧硬编码一致。
     */
    lineSpacingExtra: Float = 1.2f,
    /**
     * 段间距（ReaderStyle.paragraphSpacing，整数刻度，引擎内 px = textHeight * /10）。
     * 默认 8 与旧硬编码一致。
     */
    paragraphSpacing: Int = 8,
    /**
     * 段首缩进字符（ReaderStyle.paragraphIndent）。注意：ContentProcessor 已给每段加
     * "　　"；本字段一般传 ""，仅当 reader style 用特殊缩进（如"文章"preset 用 4 空格）
     * 时由 caller 自己 strip ContentProcessor 加的 "　　" 后传新 indent。默认 ""。
     */
    paragraphIndent: String = "",
    /**
     * 章首块 title 显示模式（ReaderStyle.titleMode）：
     *   0 = 左对齐 / 1 = 居中 / 2 = 隐藏（不画自画 title 块）。
     */
    titleMode: Int = 0,
    /**
     * 章首块 title 文字对齐（ReaderStyle.titleAlign）：0=left / 1=center / 2=right。
     */
    titleAlign: Int = 0,
    /**
     * 正文是否两端对齐（ReaderStyle.textAlign == "justify"）。
     * 默认 true 启用末行外的整段两端对齐 + 字符间隙均摊。
     */
    textFullJustify: Boolean = true,
    /** 阅读区背景图 uri；空串 = 纯色背景。来自 ReaderScreen 的 readerBgImage。 */
    bgImageUri: String = "",
    /** 阅读区纯色背景（android argb）—— 无背景图 / 背景图加载失败时使用。 */
    bgColorArgb: Int = android.graphics.Color.WHITE,
    // ── TTS 自动跟随 ──
    /** TTS 朗读当前段所在的章 idx；< 0 = 未朗读，本组件 noop。 */
    ttsChapterIndex: Int = -1,
    /** TTS 朗读当前段在章内的 chapterPosition；< 0 = 未朗读。 */
    ttsChapterPosition: Int = -1,
    // ── 搜索高亮 ──
    /** 搜索命中段在哪一章。-1 = 无搜索高亮。 */
    searchHighlightChapterIndex: Int = -1,
    /** 搜索命中段在章内的 [startCp, endCp) 范围 */
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    /** 搜索高亮 argb 颜色 */
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    // ── RevealHighlight 跳转后呼吸高亮 ──
    /** 跳转目标段呼吸高亮；null 时不画。 */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    // ── 进度上报 ──
    /** 进度 live 回调（每章 0-100 整数）—— sample 150ms，fling 期间持续上报。 */
    onChapterProgressLive: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    /** 进度 persist 回调 —— debounce 800ms，停手才上报；caller 在此写 DB。 */
    onChapterProgressPersist: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    /**
     * 全书所有高亮（含 KIND_BACKGROUND / KIND_TEXT_COLOR / KIND_UNDERLINE）。
     * Host 内按 prev/cur/next 章过滤 + 投影为 spec → 透传给 ChapterPaneCanvas 绘制。
     */
    chapterHighlightsRaw: List<com.morealm.app.domain.entity.Highlight> = emptyList(),
    /**
     * tap 命中已存高亮后用户选删除时的回调（V1 LazyScrollSection 等价）。
     * caller 通常转发到 ReaderHighlightController.delete。null 时 tap 高亮不弹菜单。
     */
    onDeleteHighlight: ((id: String) -> Unit)? = null,
    /**
     * tap 命中已存高亮后用户选分享时的回调，传入整条 Highlight（分享卡片需要 content 等字段）。
     */
    onShareHighlight: ((com.morealm.app.domain.entity.Highlight) -> Unit)? = null,
    /**
     * 全书所有书签 —— Host 内按 prev/cur/next 章过滤，在书签对应行画橙色三角标记。
     * 与 V1 PageContentDrawer.hasBookmark 三角等价。
     */
    bookmarks: List<com.morealm.app.domain.entity.Bookmark> = emptyList(),
    /**
     * 跳书签 / 续读 / 搜索定位的目标章内 chapterPosition（cp）。
     * 与 [restoreToken] 配套：token 变 + currentChapter 就绪后，Host 滚到该 cp。
     * 0 表示章首；用户不主动指定的场景应传 0（默认）。
     */
    initialChapterPosition: Int = 0,
    /**
     * 跳 Slider 拖动 / 同章 in-place seek 的章内进度百分比（0..100）。
     * 与 [restoreToken] 配套；[initialChapterPosition] > 0 优先（书签 cp 字符级定位）。
     * 仅当 cp == 0 且 initialProgress > 0 时按 `pixelOffset = scrollableRange * progress / 100`
     * 跳转 —— 「拖动 Slider 所见所得」路径。
     */
    initialProgress: Int = 0,
    /**
     * 跳转幂等 key（每次跳转 caller 用 System.nanoTime() 换新值）；0L 表示无跳转。
     * Host 监听此值变化触发 imperative scroll；不变则正常滚动状态不被打扰。
     */
    restoreToken: Long = 0L,
    /**
     * 跳转完成回调（caller 用来清 jumpToken / 标记 navigateDirection 已消费等状态）。
     */
    onProgressRestored: () -> Unit = {},
    onChapterIndexChange: (Int) -> Unit = {},
    onTapCenter: () -> Unit = {},
    // ── M4-revive 选区菜单 callbacks（直接复用 SelectionToolbar）──
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (chapterPosition: Int) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onShareQuote: (String) -> Unit = {},
    /** 选区 → 高亮（KIND_BG）。null 时菜单按钮自动摘掉。 */
    onAddHighlight: ((startCp: Int, endCp: Int, content: String, argb: Int) -> Unit)? = null,
    /** 选区 → 字体强调色（KIND_TEXT_COLOR）。null 时按钮自动摘掉。 */
    onAddTextColor: ((startCp: Int, endCp: Int, content: String, argb: Int) -> Unit)? = null,
    /** 选区 → 下划线（KIND_UNDERLINE，含 style 0..3）。null 时按钮自动摘掉。 */
    onAddUnderline: ((startCp: Int, endCp: Int, content: String, argb: Int, style: Int) -> Unit)? = null,
    /** 选区交集橡皮删除。null 时按钮自动摘掉。 */
    onEraseHighlight: ((startCp: Int, endCp: Int) -> Unit)? = null,
    selectionMenuConfig: com.morealm.app.domain.entity.SelectionMenuConfig =
        com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT,
    infoBar: ScrollCanvasInfoBarConfig? = null,
    modifier: Modifier = Modifier,
) {
    // ── M6.5 paint 派生（与 V1 CanvasRenderer line 393-433 同源算法）──
    // 用 textColorArgb（fresh）+ fontSize + typeface + letterSpacing + bold 派生：
    //   - contentPaint：正文，color = textColorArgb
    //   - titlePaint：章首块大字标题，color = chapterTitleColor（夜模式浅色 / 日模式深色）
    //   - chapterNumPaint：橙色"第 N 章" 章号小字，color = chapterAccentColor
    // 不再 hardcode Color.BLACK → 夜模式自动适配。
    val resolvedTextColor = textColorArgb ?: Color.BLACK
    val chapterTitleColor = if (isNight) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
    val chapterAccentColor = if (isNight) 0xFFCFA875.toInt() else 0xFFBFA175.toInt()

    val contentPaint = remember(fontSize, typeface, resolvedTextColor, letterSpacing, textBold) {
        TextPaint().apply {
            color = resolvedTextColor
            textSize = fontSize.toFloat()
            isAntiAlias = true
            this.typeface = when (textBold) {
                1 -> Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
                else -> typeface ?: Typeface.DEFAULT
            }
            this.letterSpacing = letterSpacing
        }
    }
    val titlePaint = remember(fontSize, typeface, chapterTitleColor, letterSpacing) {
        TextPaint().apply {
            color = chapterTitleColor
            textSize = fontSize * 1.45f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = typeface ?: Typeface.DEFAULT_BOLD
            this.letterSpacing = letterSpacing + 0.01f
        }
    }
    val chapterNumPaint = remember(fontSize, typeface, chapterAccentColor, letterSpacing) {
        TextPaint().apply {
            color = chapterAccentColor
            textSize = fontSize * 0.85f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            this.letterSpacing = letterSpacing + 0.04f
        }
    }

    // 给 engine paddingTop / paddingBottom 加上 InfoBar + status bar / nav bar inset 高度，
    // 让正文（含章首大字 title 块）的起始 y 在 InfoBar 之下，避免被 InfoBar 遮挡。
    // 与 V1 CanvasRenderer effectivePadTop = maxOf(padTopPx, cutoutTop) + topInfoBarPx 等价。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val infoBarHeightPx = with(density) { 64.dp.toPx() }.toInt()
    val statusBarPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }.toInt()
    val navBarPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
    }.toInt()
    val cutoutTopPx = with(density) {
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding().toPx()
    }.toInt()
    val cutoutBottomPx = with(density) {
        WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding().toPx()
    }.toInt()
    val topInsetPx = maxOf(statusBarPx, cutoutTopPx)
    val bottomInsetPx = maxOf(navBarPx, cutoutBottomPx)
    val effectivePadTop = if (infoBar != null) paddingTop + topInsetPx + infoBarHeightPx else paddingTop + topInsetPx
    val effectivePadBottom = if (infoBar != null) paddingBottom + bottomInsetPx + infoBarHeightPx else paddingBottom + bottomInsetPx

    val engine = remember(
        viewWidth, viewHeight, paddingLeft, paddingRight, effectivePadTop, effectivePadBottom,
        contentPaint, titlePaint, chapterNumPaint, lineSpacingExtra, paragraphSpacing,
        paragraphIndent, titleMode, titleAlign, textFullJustify,
    ) {
        ScrollLayoutEngine(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight,
            paddingTop = effectivePadTop,
            paddingBottom = effectivePadBottom,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
            lineSpacingExtra = lineSpacingExtra,
            paragraphSpacing = paragraphSpacing,
            paragraphIndent = paragraphIndent,
            titleMode = titleMode,
            titleAlign = titleAlign,
            textFullJustify = textFullJustify,
        )
    }

    val state = remember { ScrollCanvasReaderState(initialChapterIndex = currentChapterIndex) }

    // ── Phase 4 page-level：注入 page 序列管理器 ──
    // chapterShiftCallback 在 factory 跨章 swap 时调用，同步切 state.prev/cur/next 引用。
    // 解耦：Factory 不直接写 state 字段，state 暴露 swapToNext/Prev 方法。
    val pageFactory = remember(state) {
        com.morealm.app.domain.render.scroll.ScrollPageFactory(
            dataSource = state,
            chapterShiftCallback = { delta ->
                if (delta == +1) state.swapToNext() else state.swapToPrev()
            },
        )
    }

    LaunchedEffect(currentChapterIndex, chapterCount) {
        state.chapterCount = chapterCount
        // Phase 4b/5 简化：外部 prop currentChapterIndex 变化（用户从外部跳转）时调
        // setExternalChapterIndex 整体重设 state；同章 idx 不变则 no-op。
        // 与重构前 RESET 路径相比：单向数据流模型，不再因 prop ↔ state race 误触 RESET。
        // currentChapterIndex 字段 Phase 5 收紧为 private set，外部仅此路径可改。
        if (state.currentChapterIndex != currentChapterIndex) {
            AppLog.info(
                "ScrollCanvasV2",
                "[host-effect] external chapter jump: ${state.currentChapterIndex} → $currentChapterIndex",
            )
            state.setExternalChapterIndex(currentChapterIndex)
        }
    }

    // ── Phase 5：onChapterIndexChange 节流通知 VM ──
    // factory 跨章 swap 同步切 state.currentChapterIndex（即时，无延迟）。但通知 VM
    // 持久化 / 元数据更新走 debounce(150ms)：fling 期快速跨多章时只触发最后一次，
    // 避免 VM 高频写 IO。snapshotFlow 自动监听 state.currentChapterIndex 变化。
    val onChapterIndexChangeUpdated by rememberUpdatedState(onChapterIndexChange)
    LaunchedEffect(state) {
        snapshotFlow { state.currentChapterIndex }
            .distinctUntilChanged()
            .debounce(150L)
            .collect { onChapterIndexChangeUpdated(it) }
    }

    // ── 跳书签 / 续读 / 搜索定位 / Slider 拖动 in-place seek ──
    // 两阶段契约：caller 保证 restoreToken != 0L 时 currentChapter 已是目标章。
    // Host 监听 restoreToken + state.currentChapter 双 key：token 变 + cur layout ready 即滚。
    //
    // 分支优先级：
    //   1. initialChapterPosition > 0 → 按 cp 字符级定位（书签 / 搜索 / TTS 跟随）
    //   2. initialChapterPosition == 0 && initialProgress > 0 → 按章内 progress (0-100)
    //      算 pixelOffset = scrollableRange * progress / 100（拖动 Slider in-place seek
    //      或 loadChapter restoreProgress）
    //   3. 都是 0 → V2 swap 路径（applyScrollDelta 已正确设 pixelOffset，无需干预）
    //
    // **关键守门（修跳章 bug）**：仅当 cp > 0 或 progress > 0 时才动 pixelOffset。
    // V2 内部 applyScrollDelta swap → onChapterShift → viewModel.loadChapter(newIdx, pos=0)
    // 会触发新 restoreToken。如果 JUMP 在 cp=0 && progress=0 也跑，pixelOffset 被强拉到 0
    // → 用户视野从 swap 后的位置（章末 / 章首附近）跳到章顶 → 继续 fling 再 swap →
    // 反复跳章。cp=0 && progress=0 时 V2 swap 已正确设置 pixelOffset，无需 JUMP 干预。
    LaunchedEffect(restoreToken, state.currentChapter) {
        if (restoreToken == 0L) return@LaunchedEffect
        if (initialChapterPosition <= 0 && initialProgress <= 0) return@LaunchedEffect
        val layout = state.currentChapter ?: return@LaunchedEffect
        if (layout.chapterIndex != state.currentChapterIndex) return@LaunchedEffect

        val viewportH = viewHeight.coerceAtLeast(1)
        // Phase 6 page-level：定位到 (targetPageIdx, pageOffsetInPage)，调 factory.moveToPage
        // + 设 state.pageOffset。两种分支共用相同的"算出目标 chapter-relative Y → 反算 page 索引"算法。
        val targetChapterY: Float = if (initialChapterPosition > 0) {
            // 分支 1：cp 字符级 —— 找 cp 所在 page + 行；视口上 1/3 显示
            val hit = layout.findColumnAt(initialChapterPosition) ?: return@LaunchedEffect
            var y = 0f
            for (i in 0 until hit.page.pageIndex) y += layout.pages[i].height
            y += hit.line.lineTop
            (y - viewportH / 3f).coerceAtLeast(0f)
        } else {
            // 分支 2：章内 progress 百分比 —— 按 totalHeight 反算到 chapter-relative Y
            // 与 page-level 进度上报算法对偶
            val scrollableRange = (layout.totalHeight - viewportH).coerceAtLeast(1f)
            (scrollableRange * initialProgress / 100f).coerceIn(0f, scrollableRange)
        }
        // 把 chapter-relative Y 反算成 (targetPageIdx, pageOffsetInPage)
        var accY = 0f
        var targetPageIdx = 0
        var pageOffsetInPage = 0f
        for ((i, page) in layout.pages.withIndex()) {
            if (targetChapterY < accY + page.height) {
                targetPageIdx = i
                pageOffsetInPage = (targetChapterY - accY).coerceIn(0f, page.height)
                break
            }
            accY += page.height
            // 兜底：targetChapterY 超过 totalHeight 时停在末页末
            if (i == layout.pages.lastIndex) {
                targetPageIdx = i
                pageOffsetInPage = page.height
            }
        }
        pageFactory.moveToPage(targetPageIdx)
        state.pageOffset = pageOffsetInPage
        AppLog.info(
            "ScrollCanvasV2",
            "JUMP restoreToken=$restoreToken cp=$initialChapterPosition prog=$initialProgress" +
                " → page=$targetPageIdx pageOffset=$pageOffsetInPage (chapterY=$targetChapterY viewportH=$viewportH)",
        )
        onProgressRestored()
    }

    // engine 引用变化（fontSize / typeface / padding / 任一影响排版的参数变）→
    // 现有所有 layout 失效（用旧 paint 排的，行宽/行高都错），必须强制重排所有 3 章。
    // 用 styleSignature 对比：layout 的 signature 与当前 engine 的 signature 不一致 = 失效。
    LaunchedEffect(engine) {
        val sig = engine.computeStyleSignature()
        if (state.currentChapter?.styleSignature != sig) state.currentChapter = null
        if (state.prevChapter?.styleSignature != sig) state.prevChapter = null
        if (state.nextChapter?.styleSignature != sig) state.nextChapter = null
    }

    LaunchedEffect(state.currentChapterIndex, engine) {
        val curIdx = state.currentChapterIndex
        val needLoadCur = state.currentChapter?.chapterIndex != curIdx
        val needLoadPrev = curIdx > 0 && state.prevChapter?.chapterIndex != curIdx - 1
        val needLoadNext = curIdx < chapterCount - 1 && state.nextChapter?.chapterIndex != curIdx + 1
        AppLog.info(
            "ScrollCanvasV2",
            "HOST sync cur=$curIdx loadCur=$needLoadCur loadPrev=$needLoadPrev loadNext=$needLoadNext " +
                "(chapterCount=$chapterCount viewWidth=$viewWidth viewHeight=$viewHeight)",
        )

        suspend fun loadAndLayout(idx: Int): com.morealm.app.domain.render.scroll.ScrollChapterLayout? {
            return try {
                val content = withContext(Dispatchers.IO) { loadChapterContent(idx) } ?: return null
                AppLog.info("ScrollCanvasV2", "  loaded idx=$idx contentLen=${content.content.length}")
                withContext(Dispatchers.Default) {
                    engine.layoutChapter(content.chapterIndex, content.title, content.content)
                }
            } catch (e: Throwable) {
                AppLog.warn("ScrollCanvasV2", "loadAndLayout FAILED idx=$idx: ${e.message}", e)
                null
            }
        }

        if (needLoadCur) {
            val curLayout = loadAndLayout(curIdx)
            if (curLayout != null) {
                state.currentChapter = curLayout
                AppLog.info("ScrollCanvasV2", "  cur layout READY pages=${curLayout.pages.size} totalH=${curLayout.totalHeight}")
            } else {
                state.currentChapter = null
                AppLog.warn("ScrollCanvasV2", "cur NULL idx=$curIdx — 无内容可滑")
            }
        }
        if (needLoadPrev) {
            val prevLayout = loadAndLayout(curIdx - 1)
            if (state.currentChapterIndex == curIdx) state.prevChapter = prevLayout
        }
        if (needLoadNext) {
            val nextLayout = loadAndLayout(curIdx + 1)
            if (state.currentChapterIndex == curIdx) state.nextChapter = nextLayout
        }
    }

    // 选区状态（M4-revive 已修：SelectionOverlay fullscreen pointerInput 已删除，长按检测
    // 改由 ScrollCanvasRenderer 内 detectTapGestures.onLongPress 触发；handle drag 仍在
    // SelectionOverlay 内部 HandleDot 子 Composable 24dp 触发区内处理，不影响整屏 scroll）。
    var selection by remember { mutableStateOf(ScrollSelectionState.Empty) }

    // ── tap-on-highlight 弹删除/分享菜单（V1 LazyScrollSection L545-575 + L859-885 等价）──
    // tap 命中已存高亮 cp 范围时不切控制栏，弹 HighlightActionToolbar；再 tap 关闭。
    var highlightActionTarget by remember(state.currentChapterIndex) {
        mutableStateOf<com.morealm.app.domain.entity.Highlight?>(null)
    }
    var highlightActionAnchor by remember { mutableStateOf(Offset.Zero) }

    // ── page-level 高亮 spec 投影（Phase 4 新增）──
    // 重构前：按 prev/cur/next 整章投影 → ChapterPaneCanvas 内部 viewport 剔除
    // 重构后：按 curPage/nextPage/nextPlusPage 单 page 投影 → PagePaneCanvas 直接消费
    // factory 的 4 个 page getter 变化时重投影；rawHighlight 变化时也重投影。
    val curPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = pageFactory.curPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        com.morealm.app.domain.render.scroll.ScrollHighlightProjector.projectForPage(
            page, state.currentChapter?.viewWidth ?: 1080, chFiltered,
        )
    }
    val nextPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = pageFactory.nextPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        val viewW = if (page.chapterIndex == state.currentChapter?.chapterIndex) {
            state.currentChapter?.viewWidth
        } else state.nextChapter?.viewWidth
        com.morealm.app.domain.render.scroll.ScrollHighlightProjector.projectForPage(
            page, viewW ?: 1080, chFiltered,
        )
    }
    val nextPlusPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = pageFactory.nextPlusPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        val viewW = if (page.chapterIndex == state.currentChapter?.chapterIndex) {
            state.currentChapter?.viewWidth
        } else state.nextChapter?.viewWidth
        com.morealm.app.domain.render.scroll.ScrollHighlightProjector.projectForPage(
            page, viewW ?: 1080, chFiltered,
        )
    }

    // ── page-level 书签 cp 过滤 ──
    // 按 page chapterIndex + page cp 范围双重过滤
    val curPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = pageFactory.curPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks.filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }
    val nextPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = pageFactory.nextPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks.filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }
    val nextPlusPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = pageFactory.nextPlusPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks.filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }

    // ── 电池 / 时间维护（与 CanvasRenderer line 380-389 同款）──
    val context = LocalContext.current

    // ── 背景图加载（V1 CanvasRenderer line 1525-1532 同款逻辑）──
    // BgImageManager LRU 缓存（最多 3 张：day/night/blur），同 uri 同尺寸命中。
    val bgEntry = remember(bgImageUri, viewWidth, viewHeight) {
        if (bgImageUri.isNotEmpty() && viewWidth > 0 && viewHeight > 0) {
            com.morealm.app.domain.render.BgImageManager.getBgBitmap(
                context, bgImageUri, viewWidth, viewHeight,
            )
        } else null
    }
    val bgBitmap = bgEntry?.bitmap

    // ── TTS 段自动跟随（V1 LazyScrollRenderer line 348-382 同款）──
    // tts 推进到下一段 → 计算目标 cp 在 currentChapter 的 y 坐标 → 用
    // scrollableState.animateScrollBy 平滑滚到视口中心。目标段已在视口 / 不在当前章
    // 时不滚（不打扰用户主动操作）。
    // 这里用 LaunchedEffect 监听 ttsChapterIndex / ttsChapterPosition 变化即可，
    // scrollableState 由 ScrollCanvasRenderer 内部持有，无法直接动它 —— 改写 pixelOffset
    // 即可达到同等效果（pixelOffset 是 mutableFloatStateOf，写入立即触发 placement-only
    // 重组，下一帧视口就更新）。
    val ttsTargetInfo by remember(state.currentChapter, ttsChapterIndex, ttsChapterPosition) {
        derivedStateOf {
            val layout = state.currentChapter ?: return@derivedStateOf null
            if (ttsChapterIndex < 0 || ttsChapterIndex != layout.chapterIndex) return@derivedStateOf null
            if (ttsChapterPosition < 0) return@derivedStateOf null
            val hit = layout.findColumnAt(ttsChapterPosition) ?: return@derivedStateOf null
            // 返回 (chapter-relative Y, target page idx, line lineTop in page)
            var y = 0f
            for (i in 0 until hit.page.pageIndex) y += layout.pages[i].height
            Triple(y + hit.line.lineTop, hit.page.pageIndex, hit.line.lineTop)
        }
    }
    LaunchedEffect(ttsTargetInfo) {
        val info = ttsTargetInfo ?: return@LaunchedEffect
        val viewportH = viewHeight
        if (viewportH <= 0) return@LaunchedEffect
        val (targetChapterY, targetPageIdx, lineTopInPage) = info
        // 当前 chapter-relative Y = pageStartY(curPageIdx) + state.pageOffset
        val layout = state.currentChapter ?: return@LaunchedEffect
        var curChapterY = 0f
        for (i in 0 until pageFactory.pageIndex.coerceAtMost(layout.pages.lastIndex)) {
            curChapterY += layout.pages[i].height
        }
        curChapterY += state.pageOffset
        // 目标段已在视口内 → 不滚（不打扰用户）
        if (targetChapterY in curChapterY..(curChapterY + viewportH - 200f)) return@LaunchedEffect
        // 跳到目标 page，pageOffset 设为目标行视口上 1/3
        pageFactory.moveToPage(targetPageIdx)
        val targetPage = pageFactory.curPage
        val desiredPageOffset = (lineTopInPage - viewportH / 3f).coerceAtLeast(0f)
            .coerceAtMost(targetPage.height)
        state.pageOffset = desiredPageOffset
        AppLog.debug(
            "ScrollCanvasV2",
            "TTS follow: chapterY=$targetChapterY → page=$targetPageIdx pageOffset=$desiredPageOffset",
        )
    }

    // ── 进度上报：live (sample 150ms) + persist (debounce 800ms) ──
    // V1 LazyScrollRenderer line 501-550 同款思路：UI 跟随用 live、DB 写入用 persist。
    // 进度计算 = pixelOffset / (totalHeight - viewportH) 比 / totalHeight 准确（避免章末
    // 进度永远停在 90% 多）。
    // Phase 6 page-level 进度上报算法：
    //   全章 chapter-relative Y = sum(pages[0..pageIdx-1].height) + state.pageOffset
    //   全章可滚动范围 = totalHeight - viewportH
    //   progress = curChapterY / scrollableRange * 100
    LaunchedEffect(state.currentChapter, viewHeight) {
        val layout = state.currentChapter ?: return@LaunchedEffect
        if (layout.totalHeight <= 0f) return@LaunchedEffect
        snapshotFlow { pageFactory.pageIndex to state.pageOffset }
            .sample(150L)
            .map { (pageIdx, pgOffset) ->
                var y = 0f
                for (i in 0 until pageIdx.coerceAtMost(layout.pages.lastIndex)) {
                    y += layout.pages[i].height
                }
                y += pgOffset
                val scrollableRange = (layout.totalHeight - viewHeight).coerceAtLeast(1f)
                val progress = (y / scrollableRange * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressLive(chIdx, prog) }
    }
    LaunchedEffect(state.currentChapter, viewHeight) {
        val layout = state.currentChapter ?: return@LaunchedEffect
        if (layout.totalHeight <= 0f) return@LaunchedEffect
        snapshotFlow { pageFactory.pageIndex to state.pageOffset }
            .debounce(800L)
            .map { (pageIdx, pgOffset) ->
                var y = 0f
                for (i in 0 until pageIdx.coerceAtMost(layout.pages.lastIndex)) {
                    y += layout.pages[i].height
                }
                y += pgOffset
                val scrollableRange = (layout.totalHeight - viewHeight).coerceAtLeast(1f)
                val progress = (y / scrollableRange * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressPersist(chIdx, prog) }
    }

    val batteryStatus by rememberBatteryStatus(context)
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }

    Box(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(bgColorArgb))) {
        // 1. 背景图层（固定不滚动，与 LazyScrollRenderer line 742-751 同款）：
        //    在 Box 内但在 ScrollCanvasRenderer 之前先画 → z-order 在文字下方。
        //    bgBitmap 已经按 viewWidth × viewHeight 缓存，center-crop 填满。
        bgBitmap?.let { bmp ->
            Canvas(Modifier.fillMaxSize()) {
                if (!bmp.isRecycled) {
                    drawIntoCanvas { compose ->
                        drawBgBitmap(compose.nativeCanvas, bmp, size.width, size.height)
                    }
                }
            }
        }

        val currentLayout = state.currentChapter
        if (currentLayout != null) {
            ScrollCanvasRenderer(
                state = state,
                pageFactory = pageFactory,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                revealHighlight = revealHighlight,
                searchHighlightChapterIndex = searchHighlightChapterIndex,
                searchHighlightCpRange = searchHighlightCpRange,
                searchHighlightArgb = searchHighlightArgb,
                selectionChapterIndex = if (selection.isActive) selection.chapterIndex else -1,
                selectionCpRange = if (selection.isActive) selection.cpRange else IntRange.EMPTY,
                curPageHighlightSpecs = curPageHighlightSpecs,
                nextPageHighlightSpecs = nextPageHighlightSpecs,
                nextPlusPageHighlightSpecs = nextPlusPageHighlightSpecs,
                curPageBookmarkCps = curPageBookmarkCps,
                nextPageBookmarkCps = nextPageBookmarkCps,
                nextPlusPageBookmarkCps = nextPlusPageBookmarkCps,
                onChapterShift = { _ ->
                    // Phase 5：通知 VM 的路径已迁移到上方 snapshotFlow + debounce 自动节流。
                    // 此回调保留空实现（factory swap 时仍触发），未来 Phase 6 可能用作即时
                    // prefetch trigger（如果 LaunchedEffect 监听 currentChapterIndex 的 prefetch
                    // 路径 latency 不够）。
                },
                onTapCenter = {
                    // 选区 active 时 tap 取消选区，否则 toggle controls
                    if (selection.isActive) {
                        selection = handleCancelSelection()
                    } else if (highlightActionTarget != null) {
                        highlightActionTarget = null
                    } else {
                        onTapCenter()
                    }
                },
                onTap = { offset ->
                    // tap-on-highlight 命中：Phase 6 page-level 坐标转换：
                    //   view-local y → chapter-local y = pageStartY(curPageIdx) + state.pageOffset + view-y
                    //   （curPage 顶在 view 中 y = -state.pageOffset，所以 view-y 对应 page-y = view-y + pageOffset）
                    if (chapterHighlightsRaw.isEmpty()) return@ScrollCanvasRenderer false
                    var pageStartY = 0f
                    val pageIdx = pageFactory.pageIndex.coerceAtMost(currentLayout.pages.lastIndex)
                    for (i in 0 until pageIdx) pageStartY += currentLayout.pages[i].height
                    val yInChapter = pageStartY + state.pageOffset + offset.y
                    val hit = currentLayout.findColumnByPixel(offset.x - currentLayout.paddingLeft, yInChapter)
                        ?: return@ScrollCanvasRenderer false
                    val cp = hit.column?.chapterPosition ?: hit.line.firstChapterPos
                    val highlight = chapterHighlightsRaw.firstOrNull { h ->
                        h.chapterIndex == currentLayout.chapterIndex &&
                            cp >= h.startChapterPos && cp < h.endChapterPos
                    } ?: return@ScrollCanvasRenderer false
                    highlightActionAnchor = offset
                    highlightActionTarget = highlight
                    true
                },
                onLongPress = { offset ->
                    // longPress 触发选区命中。Phase 6 page-level 坐标转换（同 onTap）：
                    //   x = view-x - paddingLeft（不变）
                    //   y = pageStartY + state.pageOffset + view-y
                    val xInChapter = offset.x - currentLayout.paddingLeft
                    var pageStartY = 0f
                    val pageIdx = pageFactory.pageIndex.coerceAtMost(currentLayout.pages.lastIndex)
                    for (i in 0 until pageIdx) pageStartY += currentLayout.pages[i].height
                    val yInChapter = pageStartY + state.pageOffset + offset.y
                    val sel = handleLongPress(
                        layout = currentLayout,
                        chapterIndex = currentLayout.chapterIndex,
                        x = xInChapter,
                        yInChapter = yInChapter,
                        anchorInBox = offset,
                    )
                    if (sel.isActive) selection = sel
                },
            )
            // 选区 overlay（画 handle + handle drag）
            // Phase 6 page-level：pixelOffsetProvider 仍返回 chapter-relative Y（保留 Overlay 接口），
            // 但内部计算从 pageFactory.pageIndex + state.pageOffset 累加。
            ScrollSelectionOverlay(
                selection = selection,
                onSelectionChange = { selection = it },
                layout = currentLayout,
                pixelOffsetProvider = {
                    var y = 0f
                    val pageIdx = pageFactory.pageIndex.coerceAtMost(currentLayout.pages.lastIndex)
                    for (i in 0 until pageIdx) y += currentLayout.pages[i].height
                    y + state.pageOffset
                },
                scrollableState = null,  // 自动滚动联动 M6.x 完善
                viewportHeightProvider = { viewHeight },
            )

            // 选区菜单 —— 直接复用旧 LazyScrollSection 同款 SelectionToolbar，
            // 完整功能（8 项按钮 + 调色板 + 线型面板 + above/below 自适应定位）。
            if (selection.isActive && selection.chapterIndex == currentLayout.chapterIndex) {
                val cpRange = selection.cpRange
                val endHit = currentLayout.findColumnAt(cpRange.last)
                if (endHit != null) {
                    // popup anchor = 长按 tap 点（view-local，与 V1 LazyScrollSection
                    // anchorInBox 等价）。这样 popup / 箭头始终指向用户最初按下位置，
                    // 不会随 handle drag 而飘到选区末。
                    val selText = currentLayout.extractText(cpRange)
                    androidx.compose.runtime.LaunchedEffect(cpRange) {
                        AppLog.info(
                            "ScrollSelection",
                            "SelectionToolbar render anchorInBox=${selection.anchorInBox} " +
                                "cpRange=$cpRange selText='${selText.take(20)}' " +
                                "endCol=${endHit.column?.let { "x=${it.start}..${it.end} char='${it.charData}'" }}",
                        )
                    }
                    SelectionToolbar(
                        offset = selection.anchorInBox,
                        onCopy = {
                            onCopyText(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onSpeak = {
                            onSpeakFromHere(cpRange.first)
                            selection = ScrollSelectionState.Empty
                        },
                        onTranslate = {
                            onTranslateText(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onShare = {
                            onShareQuote(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onLookup = {
                            onLookupWord(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onHighlight = onAddHighlight?.let { cb ->
                            { argb ->
                                cb(cpRange.first, cpRange.last + 1, selText, argb)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onEraseHighlight = onEraseHighlight?.let { cb ->
                            {
                                cb(cpRange.first, cpRange.last + 1)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onTextColor = onAddTextColor?.let { cb ->
                            { argb ->
                                cb(cpRange.first, cpRange.last + 1, selText, argb)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onUnderline = onAddUnderline?.let { cb ->
                            { argb, style ->
                                cb(cpRange.first, cpRange.last, selText, argb, style)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onDismiss = { selection = ScrollSelectionState.Empty },
                        config = selectionMenuConfig,
                    )
                }
            }

            // tap-on-highlight 弹删除/分享菜单（V1 LazyScrollSection L859-885 等价）
            highlightActionTarget?.let { target ->
                com.morealm.app.ui.reader.renderer.HighlightActionToolbar(
                    offset = highlightActionAnchor,
                    colorArgb = target.colorArgb,
                    onDelete = {
                        onDeleteHighlight?.invoke(target.id)
                        highlightActionTarget = null
                    },
                    onShare = {
                        onShareHighlight?.invoke(target)
                        highlightActionTarget = null
                    },
                    onDismiss = { highlightActionTarget = null },
                )
            }
        }

        // ── InfoBar 顶 / 底两条（与 CanvasRenderer line 2078-2147 同款 ReaderInfoBar 调用）──
        if (infoBar != null && currentLayout != null) {
            val chapterTitle = currentLayout.title

            // V2 没"页"概念（像素滚动），slot 映射规则：
            //   - "page"            → "chapter_progress"（章 i/n，最接近"页 i/n"语义）
            //   - "progress"        → 保留，由 scrollPercentOverride 喂入像素级 Float 百分比
            //   - "page_progress"   → 保留，组合"章 i/n  XX.X%"
            // 用户反馈"滚动条实时不精确到具体页，而是章"——之前把 progress 也降级到
            // chapter_progress 就是把像素级实时进度退化成章序号离散显示。
            fun mapSlot(s: String): String = when (s) {
                "page" -> "chapter_progress"
                else -> s
            }
            // Phase 6 page-level：从 pageFactory.pageIndex + state.pageOffset 累加 chapter-relative Y
            // 算 scrollPercent。与 onChapterProgressLive 算法一致，保留 Float 精度不 toInt。
            val scrollPercent by remember(currentLayout, viewHeight) {
                derivedStateOf {
                    val totalH = currentLayout.totalHeight
                    val scrollableRange = (totalH - viewHeight).coerceAtLeast(1f)
                    var y = 0f
                    val pageIdx = pageFactory.pageIndex.coerceAtMost(currentLayout.pages.lastIndex)
                    for (i in 0 until pageIdx) y += currentLayout.pages[i].height
                    y += state.pageOffset
                    (y / scrollableRange * 100f).coerceIn(0f, 100f)
                }
            }

            // 修复用户反馈"顶部被挡"：V2 InfoBar 之前直接贴 Box 顶 / 底，没考虑 status bar /
            // navigation bar / display cutout（刘海屏 / 居中挖孔）。与 V1 CanvasRenderer 同源：
            // 给 top InfoBar 加 statusBars + displayCutout 顶部 padding，bottom InfoBar 加
            // navigationBars + displayCutout 底部 padding。
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
            val cutoutBottom = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val topInsetDp = if (statusBarTop.value >= cutoutTop.value) statusBarTop else cutoutTop
            val bottomInsetDp = if (navBarBottom.value >= cutoutBottom.value) navBarBottom else cutoutBottom

            ReaderInfoBar(
                slotLeft = if (infoBar.showTimeBattery) mapSlot(infoBar.headerLeft) else "none",
                slotCenter = if (infoBar.showChapterName) mapSlot(infoBar.headerCenter) else "none",
                slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.headerRight) else "none",
                chapterTitle = chapterTitle,
                pageIndex = 0,
                pageCount = 0,
                currentPage = null,
                chapterIndex = state.currentChapterIndex,
                chaptersSize = infoBar.chaptersSize,
                batteryLevel = batteryStatus.level,
                batteryCharging = batteryStatus.charging,
                currentTime = currentTime,
                textColor = infoBar.textColor,
                scrollPercentOverride = scrollPercent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // 渐变背景必须覆盖 status bar 区（用户反馈：之前 padding(top=topInsetDp)
                    // 把整条 bar 推到 status bar 下方，导致 status bar 后透出章首块文字）。
                    // 现在 height 包含 status bar 高度，背景画整个区域。
                    .height(64.dp + topInsetDp)
                    .then(
                        if (infoBar.hasBgImage) Modifier
                        else Modifier.background(
                            Brush.verticalGradient(
                                0f to infoBar.backgroundColor,
                                0.72f to infoBar.backgroundColor,
                                1f to infoBar.backgroundColor.copy(alpha = 0f),
                            )
                        )
                    )
                    // 内部 padding：把 ReaderInfoBar 实际文字推到 status bar 下方
                    .padding(top = topInsetDp, start = infoBar.paddingHorizontal.dp,
                        end = infoBar.paddingHorizontal.dp, bottom = 8.dp),
            )
            ReaderInfoBar(
                slotLeft = if (infoBar.showChapterName) mapSlot(infoBar.footerLeft) else "none",
                slotCenter = if (infoBar.showTimeBattery) mapSlot(infoBar.footerCenter) else "none",
                slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.footerRight) else "none",
                chapterTitle = chapterTitle,
                pageIndex = 0,
                pageCount = 0,
                currentPage = null,
                chapterIndex = state.currentChapterIndex,
                chaptersSize = infoBar.chaptersSize,
                batteryLevel = batteryStatus.level,
                batteryCharging = batteryStatus.charging,
                currentTime = currentTime,
                textColor = infoBar.textColor,
                scrollPercentOverride = scrollPercent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(64.dp + bottomInsetDp)
                    .then(
                        if (infoBar.hasBgImage) Modifier
                        else Modifier.background(
                            Brush.verticalGradient(
                                0f to infoBar.backgroundColor.copy(alpha = 0f),
                                0.28f to infoBar.backgroundColor,
                                1f to infoBar.backgroundColor,
                            )
                        )
                    )
                    .padding(bottom = bottomInsetDp, start = infoBar.paddingHorizontal.dp,
                        end = infoBar.paddingHorizontal.dp, top = 8.dp),
            )
        }
    }
}
