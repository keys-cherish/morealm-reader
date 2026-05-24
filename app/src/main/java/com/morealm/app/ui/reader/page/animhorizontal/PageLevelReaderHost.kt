package com.morealm.app.ui.reader.page.animhorizontal

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.pageanim.rememberPageLevelCore
import com.morealm.app.domain.render.layout.ScrollLayoutEngine
import com.morealm.app.domain.render.layout.extractText
import com.morealm.app.domain.render.layout.findColumnAt
import com.morealm.app.domain.render.layout.findColumnByPixel
import com.morealm.app.ui.reader.page.animation.PageAnimType
import com.morealm.app.ui.reader.renderer.ReaderInfoBar
import com.morealm.app.ui.reader.renderer.SelectionToolbar
import com.morealm.app.ui.reader.renderer.rememberBatteryStatus
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasInfoBarConfig
import com.morealm.app.ui.reader.renderer.scroll.ScrollSelectionOverlay
import com.morealm.app.ui.reader.renderer.scroll.ScrollSelectionState
import com.morealm.app.ui.reader.renderer.scroll.handleCancelSelection
import com.morealm.app.ui.reader.renderer.scroll.handleLongPress
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transition 暴露给 Host 的「按当前动画 commit 翻页」句柄。
 *
 * SLIDE / COVER 各自的 [SlidePageTransition] / [CoverPageTransition] 在 DisposableEffect
 * 内把自身的 animateAndCommit 注入到 [animateToNext] / [animateToPrev]；
 * Host 的 zone tap（左/右 1/3 点击）走 controller，从而触发各自的平移/覆盖动画，
 * 而不是直接调 [com.morealm.app.domain.render.layout.ScrollPageFactory.moveToPrev]/[moveToNext] 瞬切。
 *
 * NONE Transition 不注册 controller，Host 自动 fallback 到 moveToPrev/Next 瞬切（NONE 语义本身就无动画）。
 */
class PageTurnAnimController {
    var animateToNext: (suspend () -> Unit)? = null
    var animateToPrev: (suspend () -> Unit)? = null
}

/**
 * page-level 翻页阅读器宿主 —— 服务 COVER / SLIDE / NONE 三种横向翻页动画。
 *
 * 设计参考 Legado `ReadView` (FrameLayout 单实例) + `PageDelegate` (各动画独立) 模型：
 *
 * - **共享** (本 Host)：page-level state/factory (走 [rememberPageLevelCore]) + engine
 *   + paint 派生 + 长按选区 + 手势接收 + InfoBar + 进度上报 + TTS 跟随
 * - **独立** (各 Transition)：动画绘制 + drag 偏移 + fling settle
 *
 * 与 [com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost] 的关系：
 * 后者专属 SCROLL（垂直滚动），本 Host 服务横向翻页。两个 Host **不互相调用**，
 * 共享只通过 [rememberPageLevelCore] helper。SCROLL 文件名实统一不被混用。
 *
 * P2 阶段（2026-05-20）：骨架版本，只接 NONE Transition；COVER / SLIDE 后续阶段补。
 * 选区 / TTS / InfoBar / 进度上报 / restoreToken JUMP P3 接入 ReaderScreen 时
 * 再依次补全（按"独立 Host 自管"原则）。
 */
@OptIn(FlowPreview::class)
@Composable
fun PageLevelReaderHost(
    currentChapterIndex: Int,
    chapterCount: Int,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    /** 翻页动画类型 —— NONE / COVER / SLIDE，决定内部 dispatch 到哪个 Renderer。 */
    animType: PageAnimType,
    viewWidth: Int,
    viewHeight: Int,
    paddingLeft: Int = 80,
    paddingRight: Int = 80,
    paddingTop: Int = 120,
    paddingBottom: Int = 120,
    fontSize: Int = 48,
    textColorArgb: Int? = null,
    typeface: Typeface? = null,
    isNight: Boolean = false,
    letterSpacing: Float = 0f,
    textBold: Int = 0,
    lineSpacingExtra: Float = 1.2f,
    paragraphSpacing: Int = 8,
    paragraphIndent: String = "",
    titleMode: Int = 0,
    titleAlign: Int = 0,
    textFullJustify: Boolean = true,
    bgColorArgb: Int = Color.WHITE,
    restoreToken: Long = 0L,
    /** 跳书签 / 续读 / 搜索定位的目标章内 chapterPosition（cp）。0 = 章首。 */
    initialChapterPosition: Int = 0,
    /** 跳 Slider 拖动 / 同章 in-place seek 的章内进度百分比（0..100）。initialChapterPosition > 0 优先。 */
    initialProgress: Int = 0,
    /** JUMP 完成后回调（caller 通常清除 navigateDirection）。 */
    onProgressRestored: () -> Unit = {},
    /** InfoBar 顶/底状态栏配置；null = 不画状态栏（纯净内容模式）。 */
    infoBar: ScrollCanvasInfoBarConfig? = null,
    /** 进度上报 live 回调（每章 0-100 整数）—— sample 150ms，UI 跟随用。 */
    onChapterProgressLive: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    /** 进度上报 persist 回调 —— debounce 800ms，停止翻页后才上报；caller 在此写 DB。 */
    onChapterProgressPersist: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    // ── 选区 / 长按 / 高亮（P4.4 接入）──
    /** 全书所有高亮（用于 tap-on-highlight 命中检测）。 */
    chapterHighlightsRaw: List<com.morealm.app.domain.entity.Highlight> = emptyList(),
    selectionMenuConfig: com.morealm.app.domain.entity.SelectionMenuConfig =
        com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT,
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (chapterPosition: Int) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onShareQuote: (String) -> Unit = {},
    onAddHighlight: ((startCp: Int, endCp: Int, content: String, colorArgb: Int) -> Unit)? = null,
    onEraseHighlight: ((startCp: Int, endCp: Int) -> Unit)? = null,
    onAddTextColor: ((startCp: Int, endCp: Int, content: String, colorArgb: Int) -> Unit)? = null,
    onAddUnderline: ((startCp: Int, endCp: Int, content: String, colorArgb: Int, style: Int) -> Unit)? = null,
    onDeleteHighlight: ((id: String) -> Unit)? = null,
    onShareHighlight: ((com.morealm.app.domain.entity.Highlight) -> Unit)? = null,
    // ── TTS 段跟随 / 搜索高亮 / 跳转呼吸高亮 / 书签三角（P4.5 接入）──
    /** TTS 朗读当前段所在的章 idx；< 0 = 未朗读。 */
    ttsChapterIndex: Int = -1,
    /** TTS 朗读当前段在章内 chapterPosition；< 0 = 未朗读。 */
    ttsChapterPosition: Int = -1,
    /** 搜索命中章 idx；-1 = 无。 */
    searchHighlightChapterIndex: Int = -1,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    /** 跳转后呼吸高亮；caller 已用 chapterIndex 过滤旧章。 */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    /** 全书所有书签 —— Host 内按 cur page 范围过滤画三角。 */
    bookmarks: List<com.morealm.app.domain.entity.Bookmark> = emptyList(),
    onChapterIndexChange: (Int) -> Unit = {},
    onTapCenter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ── paint 派生（与 ScrollCanvasReaderHost L225-260 同算法）──
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

    // safe area + InfoBar 占位（infoBar != null 时正文 padding 给 InfoBar 让位避免遮挡）
    val density = androidx.compose.ui.platform.LocalDensity.current
    // page-level 模式 InfoBar 让位高度：48dp 足够装一行 fontSize 10sp + battery icon
    // (Row centered)；之前 64dp 是 CanvasTransition 时代的设计，page-level 浪费 16dp。
    val infoBarHeightDp = 48.dp
    val infoBarHeightPx = with(density) { infoBarHeightDp.toPx() }.toInt()
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
    // page-level 模式 padding 仅"状态栏 + InfoBar"让位，不再叠加用户配置的 paddingTop/Bottom
    // —— 后者本意是"正文与边界留白"，但 InfoBar 让位已经远超它（infoBarHeightPx=192>paddingTop=120），
    // 叠加会浪费 ~120px 屏幕空间（截图章中 page 顶/底两块大空白）。
    val effectivePadTop = if (infoBar != null) topInsetPx + infoBarHeightPx else topInsetPx + paddingTop
    val effectivePadBottom = if (infoBar != null) bottomInsetPx + infoBarHeightPx else bottomInsetPx + paddingBottom

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
            pageLevelMode = true,
        )
    }

    val core = rememberPageLevelCore(
        currentChapterIndex = currentChapterIndex,
        chapterCount = chapterCount,
        restoreToken = restoreToken,
        onChapterIndexChange = onChapterIndexChange,
        loadChapterContent = loadChapterContent,
        engine = engine,
    )

    // ── 跳书签 / 续读 / 搜索定位 / Slider 拖动 in-place seek ──
    // 两阶段契约：caller 保证 restoreToken != 0L 时 currentChapter 已是目标章。
    // 横向 page-level JUMP 算法（与 SCROLL chapter-Y 算法不同）：
    //   - initialChapterPosition > 0 → findColumnAt(cp) 找目标 cp 所在 page → moveToPage
    //   - initialProgress > 0       → targetPageIdx = progress / 100 * pageCount
    // restoreToken 消费幂等：同一 token 只 JUMP 一次。
    var lastConsumedRestoreToken by remember { mutableLongStateOf(0L) }
    LaunchedEffect(restoreToken, core.state.currentChapter) {
        val curChIdx = core.state.currentChapterIndex
        val layoutChIdx = core.state.currentChapter?.chapterIndex
        val curPgIdx = core.pageFactory.pageIndex
        com.morealm.app.core.log.AppLog.info(
            "PageLevelReaderHost",
            "JUMP-ENTRY token=$restoreToken lastConsumed=$lastConsumedRestoreToken cp=$initialChapterPosition prog=$initialProgress curChIdx=$curChIdx layoutChIdx=$layoutChIdx curPgIdx=$curPgIdx",
        )
        if (restoreToken == 0L) {
            com.morealm.app.core.log.AppLog.info("PageLevelReaderHost", "JUMP-SKIP token=0L (no jump intent)")
            return@LaunchedEffect
        }
        if (restoreToken == lastConsumedRestoreToken) {
            com.morealm.app.core.log.AppLog.info("PageLevelReaderHost", "JUMP-SKIP token already consumed")
            return@LaunchedEffect
        }
        // 注：原 `if (initialChapterPosition <= 0 && initialProgress <= 0) return` 早退是 bug —
        // 用户拖 Slider 到 0% (P0 bug 2026-05-24)、跨章 loadChapter 默认章首 (cp=0,prog=0)
        // 都需要 moveToPage(0) reset pageIndex。restoreToken != 0L 已表达"有 JUMP 意图"，无需再卡 cp/prog。
        val layout = core.state.currentChapter
        if (layout == null) {
            com.morealm.app.core.log.AppLog.info("PageLevelReaderHost", "JUMP-WAIT layout=null")
            return@LaunchedEffect
        }
        if (layout.chapterIndex != core.state.currentChapterIndex) {
            com.morealm.app.core.log.AppLog.info(
                "PageLevelReaderHost",
                "JUMP-WAIT layout.chIdx=${layout.chapterIndex} != state.chIdx=${core.state.currentChapterIndex}",
            )
            return@LaunchedEffect
        }

        val total = layout.pages.size.coerceAtLeast(1)
        val targetPageIdx: Int = if (initialChapterPosition > 0) {
            val hit = layout.findColumnAt(initialChapterPosition)
            hit?.page?.pageIndex?.coerceIn(0, total - 1) ?: 0
        } else {
            ((initialProgress.toFloat() / 100f) * total).toInt().coerceIn(0, total - 1)
        }
        core.pageFactory.moveToPage(targetPageIdx)
        lastConsumedRestoreToken = restoreToken
        com.morealm.app.core.log.AppLog.info(
            "PageLevelReaderHost",
            "JUMP-DONE token=$restoreToken cp=$initialChapterPosition prog=$initialProgress → page=$targetPageIdx (total=$total) [moveToPage curPgIdx ${curPgIdx} → ${core.pageFactory.pageIndex}]",
        )
        onProgressRestored()
    }

    // ── 进度上报 live (sample 150ms) + persist (debounce 800ms) ──
    // page-level 横向语义：progress = (curPage idx + 1) / pageCount * 100。
    // 与 SCROLL 的 chapter-Y / scrollableRange 算法不同（横向无连续滚动概念）。
    LaunchedEffect(core.state.currentChapter) {
        val layout = core.state.currentChapter ?: return@LaunchedEffect
        snapshotFlow { core.pageFactory.pageIndex }
            .sample(150L)
            .map { pageIdx ->
                val total = layout.pages.size.coerceAtLeast(1)
                val progress = ((pageIdx + 1).toFloat() / total * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressLive(chIdx, prog) }
    }
    LaunchedEffect(core.state.currentChapter) {
        val layout = core.state.currentChapter ?: return@LaunchedEffect
        snapshotFlow { core.pageFactory.pageIndex }
            .debounce(800L)
            .map { pageIdx ->
                val total = layout.pages.size.coerceAtLeast(1)
                val progress = ((pageIdx + 1).toFloat() / total * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressPersist(chIdx, prog) }
    }

    // ── TTS 段自动跟随（P4.5）——找到目标段所在 page → moveToPage（横向 page-level
    //   不需 pageOffset 调整, 整页瞬切到目标 page）──
    val ttsTargetPageIdx by remember(core.state.currentChapter, ttsChapterIndex, ttsChapterPosition) {
        derivedStateOf {
            val layout = core.state.currentChapter ?: return@derivedStateOf -1
            if (ttsChapterIndex < 0 || ttsChapterIndex != layout.chapterIndex) return@derivedStateOf -1
            if (ttsChapterPosition < 0) return@derivedStateOf -1
            val hit = layout.findColumnAt(ttsChapterPosition) ?: return@derivedStateOf -1
            hit.page.pageIndex
        }
    }
    LaunchedEffect(ttsTargetPageIdx) {
        val target = ttsTargetPageIdx
        if (target < 0) return@LaunchedEffect
        // 目标 page 已是当前 page → 不动（不打扰用户）
        if (target == core.pageFactory.pageIndex) return@LaunchedEffect
        core.pageFactory.moveToPage(target)
        com.morealm.app.core.log.AppLog.debug(
            "PageLevelReaderHost",
            "TTS follow: moveToPage $target (cur was ${core.pageFactory.pageIndex})",
        )
    }

    // ── page-level 高亮 spec 投影（P4.5）──
    // 当前只投影 cur page（NONE 模式只显示 cur）。SLIDE/COVER 后续接入时再加 next/prev。
    val curPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.curPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        com.morealm.app.domain.render.layout.ScrollHighlightProjector.projectForPage(
            page,
            core.state.currentChapter?.viewWidth ?: 1080,
            chFiltered,
        )
    }
    // cur page 书签 cp 过滤
    val curPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.curPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks
            .filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }
    // SLIDE 用 nextPage / nextPlusPage；COVER 用 nextPage / prevPage
    val nextPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.nextPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        val viewW = if (page.chapterIndex == core.state.currentChapter?.chapterIndex) {
            core.state.currentChapter?.viewWidth
        } else core.state.nextChapter?.viewWidth
        com.morealm.app.domain.render.layout.ScrollHighlightProjector.projectForPage(
            page, viewW ?: 1080, chFiltered,
        )
    }
    val nextPlusPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.nextPlusPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        val viewW = if (page.chapterIndex == core.state.currentChapter?.chapterIndex) {
            core.state.currentChapter?.viewWidth
        } else core.state.nextChapter?.viewWidth
        com.morealm.app.domain.render.layout.ScrollHighlightProjector.projectForPage(
            page, viewW ?: 1080, chFiltered,
        )
    }
    val prevPageHighlightSpecs by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.prevPage
        if (page.chapterIndex < 0) return@derivedStateOf emptyList()
        val chFiltered = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
        val viewW = if (page.chapterIndex == core.state.currentChapter?.chapterIndex) {
            core.state.currentChapter?.viewWidth
        } else core.state.prevChapter?.viewWidth
        com.morealm.app.domain.render.layout.ScrollHighlightProjector.projectForPage(
            page, viewW ?: 1080, chFiltered,
        )
    }
    val nextPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.nextPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks.filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }
    val nextPlusPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.nextPlusPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks.filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }
    val prevPageBookmarkCps by androidx.compose.runtime.derivedStateOf {
        val page = core.pageFactory.prevPage
        if (page.chapterIndex < 0 || page.lines.isEmpty()) return@derivedStateOf emptyList<Int>()
        val firstCp = page.lines.first().firstChapterPos
        val lastCp = page.lines.last().lastChapterPos
        bookmarks.filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
            .map { it.chapterPos }
    }

    // ── zone tap → 各 Transition 自身动画 commit（Legado PageDelegate 模型）──
    // SLIDE/COVER Transition 通过 turnCtrl 注入 animateAndCommit lambda；NONE 不注册 → fallback 瞬切。
    // currentAnimJob 串行化连点：新 tap 启动前 cancelAndJoin 前一个 → 前一次 finally 立即
    // commit 翻页（pageFactory.moveToNext + reset offset），再 launch 新动画。视觉等价 Legado
    // abortAnim()+fillPage：连点 N 次翻 N 页不丢。
    val turnCtrl = remember { PageTurnAnimController() }
    val tapScope = rememberCoroutineScope()
    var currentAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ── 选区 / 长按 / tap-on-highlight 状态（P4.4 接入）──
    // 共享在 Host 层（Legado ReadView 模型）：长按检测 + 选区状态 + 高亮 action 弹窗
    // 都在 Host，所有 Transition (NONE/SLIDE/COVER) 共享。Transition 不再自己接 pointerInput。
    var selection by remember { mutableStateOf(ScrollSelectionState.Empty) }
    var highlightActionTarget by remember(core.state.currentChapterIndex) {
        mutableStateOf<com.morealm.app.domain.entity.Highlight?>(null)
    }
    var highlightActionAnchor by remember { mutableStateOf(Offset.Zero) }

    // 电池 / 时间维护（InfoBar 用）
    val context = LocalContext.current
    val batteryStatus by rememberBatteryStatus(context)
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(bgColorArgb))
            // Host 共享层 tap + 长按（NONE 模式 zone tap 也在这；SLIDE/COVER 后续接入时
            // drag 在 Transition 内自管，tap 仍由本层处理）。
            .pointerInput(animType, selection.isActive, highlightActionTarget != null, chapterHighlightsRaw) {
                detectTapGestures(
                    onTap = { offset ->
                        // 优先级 1: 选区 active → tap 取消选区
                        if (selection.isActive) {
                            selection = handleCancelSelection()
                            return@detectTapGestures
                        }
                        // 优先级 2: highlight action 弹出 → tap 关闭
                        if (highlightActionTarget != null) {
                            highlightActionTarget = null
                            return@detectTapGestures
                        }
                        // 优先级 3: tap-on-highlight 命中已存高亮 → 弹删除/分享菜单
                        val layout = core.state.currentChapter
                        if (layout != null && chapterHighlightsRaw.isNotEmpty()) {
                            val hit = layout.findColumnByPixel(
                                offset.x - layout.paddingLeft,
                                offset.y,
                            )
                            val cp = hit?.column?.chapterPosition ?: hit?.line?.firstChapterPos
                            if (cp != null) {
                                val highlight = chapterHighlightsRaw.firstOrNull { h ->
                                    h.chapterIndex == layout.chapterIndex &&
                                        cp >= h.startChapterPos && cp < h.endChapterPos
                                }
                                if (highlight != null) {
                                    highlightActionAnchor = offset
                                    highlightActionTarget = highlight
                                    return@detectTapGestures
                                }
                            }
                        }
                        // 优先级 4: zone tap 翻页（所有横向 page-level 模式）。
                        // NONE / COVER / SLIDE 都通过 Host 共享层 zone tap 翻页（瞬切语义）。
                        // drag 期间的动画由各 Transition 内部 own (settle-to-edge)。
                        val w = size.width.toFloat()
                        val zone = when {
                            offset.x < w * 0.33f -> "L"
                            offset.x > w * 0.67f -> "R"
                            else -> "M"
                        }
                        com.morealm.app.core.log.AppLog.info("PageLvlHost", "DIAG onTap zone=$zone animType=$animType x=${offset.x} w=$w")
                        when {
                            offset.x < w * 0.33f -> {
                                // 边界保护：全书首章首页 hasPrev=false 时不触发动画
                                // （否则 COVER 等动画白动一次还是停在原位，视觉"持续弹动画"困扰）
                                if (!core.pageFactory.hasPrev()) {
                                    com.morealm.app.core.log.AppLog.info(
                                        "PageLvlHost",
                                        "PREV tap at boundary (hasPrev=false), skip animation",
                                    )
                                    return@detectTapGestures
                                }
                                val animate = turnCtrl.animateToPrev
                                if (animate != null) {
                                    val prev = currentAnimJob
                                    currentAnimJob = tapScope.launch {
                                        prev?.cancelAndJoin()
                                        animate()
                                    }
                                } else core.pageFactory.moveToPrev()
                            }
                            offset.x > w * 0.67f -> {
                                // 边界保护：全书末章末页 hasNext=false 时不触发动画
                                if (!core.pageFactory.hasNext()) {
                                    com.morealm.app.core.log.AppLog.info(
                                        "PageLvlHost",
                                        "NEXT tap at boundary (hasNext=false), skip animation",
                                    )
                                    return@detectTapGestures
                                }
                                val animate = turnCtrl.animateToNext
                                if (animate != null) {
                                    val prev = currentAnimJob
                                    currentAnimJob = tapScope.launch {
                                        prev?.cancelAndJoin()
                                        animate()
                                    }
                                } else core.pageFactory.moveToNext()
                            }
                            else -> onTapCenter()
                        }
                    },
                    onLongPress = { offset ->
                        // 共享层长按 → 算选区。横向 page-level cur page 顶贴 view y=0，
                        // 所以 y 直接传 offset.y 当 chapter-Y 用（不需 pageOffset 累加）。
                        val layout = core.state.currentChapter ?: return@detectTapGestures
                        val sel = handleLongPress(
                            layout = layout,
                            chapterIndex = layout.chapterIndex,
                            x = offset.x - layout.paddingLeft,
                            yInChapter = offset.y,
                            anchorInBox = offset,
                        )
                        if (sel.isActive) selection = sel
                    },
                )
            }
    ) {
        val currentLayout = core.state.currentChapter
        if (currentLayout != null) {
            val selectionChapterIndex = if (selection.isActive) selection.chapterIndex else -1
            val selectionCpRange = if (selection.isActive) selection.cpRange else IntRange.EMPTY
            // dispatch 到具体 Transition（独立 own 自己的动画 + drag；不再接 pointerInput tap）
            when (animType) {
                PageAnimType.NONE -> {
                    NonePageTransition(
                        state = core.state,
                        pageFactory = core.pageFactory,
                        backgroundColor = androidx.compose.ui.graphics.Color(bgColorArgb),
                        contentPaint = contentPaint,
                        titlePaint = titlePaint,
                        chapterNumPaint = chapterNumPaint,
                        revealHighlight = revealHighlight,
                        searchHighlightChapterIndex = searchHighlightChapterIndex,
                        searchHighlightCpRange = searchHighlightCpRange,
                        searchHighlightArgb = searchHighlightArgb,
                        selectionChapterIndex = selectionChapterIndex,
                        selectionCpRange = selectionCpRange,
                        curPageHighlightSpecs = curPageHighlightSpecs,
                        curPageBookmarkCps = curPageBookmarkCps,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                PageAnimType.SLIDE -> {
                    SlidePageTransition(
                        state = core.state,
                        pageFactory = core.pageFactory,
                        backgroundColor = androidx.compose.ui.graphics.Color(bgColorArgb),
                        contentPaint = contentPaint,
                        titlePaint = titlePaint,
                        chapterNumPaint = chapterNumPaint,
                        revealHighlight = revealHighlight,
                        searchHighlightChapterIndex = searchHighlightChapterIndex,
                        searchHighlightCpRange = searchHighlightCpRange,
                        searchHighlightArgb = searchHighlightArgb,
                        selectionChapterIndex = selectionChapterIndex,
                        selectionCpRange = selectionCpRange,
                        curPageHighlightSpecs = curPageHighlightSpecs,
                        nextPageHighlightSpecs = nextPageHighlightSpecs,
                        nextPlusPageHighlightSpecs = nextPlusPageHighlightSpecs,
                        curPageBookmarkCps = curPageBookmarkCps,
                        nextPageBookmarkCps = nextPageBookmarkCps,
                        nextPlusPageBookmarkCps = nextPlusPageBookmarkCps,
                        turnCtrl = turnCtrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                PageAnimType.COVER -> {
                    CoverPageTransition(
                        state = core.state,
                        pageFactory = core.pageFactory,
                        backgroundColor = androidx.compose.ui.graphics.Color(bgColorArgb),
                        contentPaint = contentPaint,
                        titlePaint = titlePaint,
                        chapterNumPaint = chapterNumPaint,
                        revealHighlight = revealHighlight,
                        searchHighlightChapterIndex = searchHighlightChapterIndex,
                        searchHighlightCpRange = searchHighlightCpRange,
                        searchHighlightArgb = searchHighlightArgb,
                        selectionChapterIndex = selectionChapterIndex,
                        selectionCpRange = selectionCpRange,
                        curPageHighlightSpecs = curPageHighlightSpecs,
                        nextPageHighlightSpecs = nextPageHighlightSpecs,
                        prevPageHighlightSpecs = prevPageHighlightSpecs,
                        curPageBookmarkCps = curPageBookmarkCps,
                        nextPageBookmarkCps = nextPageBookmarkCps,
                        prevPageBookmarkCps = prevPageBookmarkCps,
                        turnCtrl = turnCtrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    NonePageTransition(
                        state = core.state,
                        pageFactory = core.pageFactory,
                        backgroundColor = androidx.compose.ui.graphics.Color(bgColorArgb),
                        contentPaint = contentPaint,
                        titlePaint = titlePaint,
                        chapterNumPaint = chapterNumPaint,
                        revealHighlight = revealHighlight,
                        searchHighlightChapterIndex = searchHighlightChapterIndex,
                        searchHighlightCpRange = searchHighlightCpRange,
                        searchHighlightArgb = searchHighlightArgb,
                        selectionChapterIndex = selectionChapterIndex,
                        selectionCpRange = selectionCpRange,
                        curPageHighlightSpecs = curPageHighlightSpecs,
                        curPageBookmarkCps = curPageBookmarkCps,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ── 选区 Overlay (handle drag) + SelectionToolbar (菜单) ──
            // 横向 page-level 模式 pixelOffsetProvider 返回 0（cur page 顶贴 view y=0 不偏移）
            ScrollSelectionOverlay(
                selection = selection,
                onSelectionChange = { selection = it },
                layout = currentLayout,
                pixelOffsetProvider = { 0f },
                scrollableState = null,
                viewportHeightProvider = { viewHeight },
            )
            if (selection.isActive && selection.chapterIndex == currentLayout.chapterIndex) {
                val cpRange = selection.cpRange
                val endHit = currentLayout.findColumnAt(cpRange.last)
                if (endHit != null) {
                    val selText = currentLayout.extractText(cpRange)
                    SelectionToolbar(
                        offset = selection.anchorInBox,
                        onCopy = { onCopyText(selText); selection = ScrollSelectionState.Empty },
                        onSpeak = { onSpeakFromHere(cpRange.first); selection = ScrollSelectionState.Empty },
                        onTranslate = { onTranslateText(selText); selection = ScrollSelectionState.Empty },
                        onShare = { onShareQuote(selText); selection = ScrollSelectionState.Empty },
                        onLookup = { onLookupWord(selText); selection = ScrollSelectionState.Empty },
                        onHighlight = onAddHighlight?.let { cb ->
                            { argb -> cb(cpRange.first, cpRange.last + 1, selText, argb); selection = ScrollSelectionState.Empty }
                        },
                        onEraseHighlight = onEraseHighlight?.let { cb ->
                            { cb(cpRange.first, cpRange.last + 1); selection = ScrollSelectionState.Empty }
                        },
                        onTextColor = onAddTextColor?.let { cb ->
                            { argb -> cb(cpRange.first, cpRange.last + 1, selText, argb); selection = ScrollSelectionState.Empty }
                        },
                        onUnderline = onAddUnderline?.let { cb ->
                            { argb, style -> cb(cpRange.first, cpRange.last, selText, argb, style); selection = ScrollSelectionState.Empty }
                        },
                        onDismiss = { selection = ScrollSelectionState.Empty },
                        config = selectionMenuConfig,
                    )
                }
            }

            // tap-on-highlight 弹删除/分享菜单
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

            // ── InfoBar 顶/底（P4.1 接入，与 SCROLL Host 同款 + 横向 page-level slot 语义）──
            if (infoBar != null) {
                // 横向 page-level 模式有"页"概念，slot "page"/"progress"/"page_progress" 保留原义
                // （不像 SCROLL 把 "page" 降级到 "chapter_progress"）。
                fun mapSlot(s: String): String = s

                // page-level 进度 = (curPage 在章内 1-based idx) / 章 page 总数 * 100
                val scrollPercent by remember(currentLayout) {
                    derivedStateOf {
                        val total = currentLayout.pages.size.coerceAtLeast(1)
                        val curIdx = core.pageFactory.pageIndex.coerceIn(0, total - 1)
                        ((curIdx + 1).toFloat() / total * 100f).coerceIn(0f, 100f)
                    }
                }

                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
                val cutoutBottom = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
                val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val topInsetDp = if (statusBarTop.value >= cutoutTop.value) statusBarTop else cutoutTop
                val bottomInsetDp = if (navBarBottom.value >= cutoutBottom.value) navBarBottom else cutoutBottom

                val chapterTitle = currentLayout.title

                ReaderInfoBar(
                    slotLeft = if (infoBar.showTimeBattery) mapSlot(infoBar.headerLeft) else "none",
                    slotCenter = if (infoBar.showChapterName) mapSlot(infoBar.headerCenter) else "none",
                    slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.headerRight) else "none",
                    chapterTitle = chapterTitle,
                    pageIndex = core.pageFactory.pageIndex,
                    pageCount = currentLayout.pages.size,
                    currentPage = null,
                    chapterIndex = core.state.currentChapterIndex,
                    chaptersSize = infoBar.chaptersSize,
                    batteryLevel = batteryStatus.level,
                    batteryCharging = batteryStatus.charging,
                    currentTime = currentTime,
                    textColor = infoBar.textColor,
                    scrollPercentOverride = scrollPercent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(infoBarHeightDp + topInsetDp)
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
                        .padding(top = topInsetDp, start = infoBar.paddingHorizontal.dp,
                            end = infoBar.paddingHorizontal.dp, bottom = 8.dp),
                )
                ReaderInfoBar(
                    slotLeft = if (infoBar.showChapterName) mapSlot(infoBar.footerLeft) else "none",
                    slotCenter = if (infoBar.showTimeBattery) mapSlot(infoBar.footerCenter) else "none",
                    slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.footerRight) else "none",
                    chapterTitle = chapterTitle,
                    pageIndex = core.pageFactory.pageIndex,
                    pageCount = currentLayout.pages.size,
                    currentPage = null,
                    chapterIndex = core.state.currentChapterIndex,
                    chaptersSize = infoBar.chaptersSize,
                    batteryLevel = batteryStatus.level,
                    batteryCharging = batteryStatus.charging,
                    currentTime = currentTime,
                    textColor = infoBar.textColor,
                    scrollPercentOverride = scrollPercent,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(infoBarHeightDp + bottomInsetDp)
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
                        .padding(top = 8.dp, start = infoBar.paddingHorizontal.dp,
                            end = infoBar.paddingHorizontal.dp, bottom = bottomInsetDp),
                )
            }
        }
    }
}
