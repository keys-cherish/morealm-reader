package com.morealm.app.ui.reader.renderer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.asPaddingValues
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.entity.ReaderStyle
import com.morealm.app.domain.entity.looksLikeAutoSplitTitle
import com.morealm.app.domain.render.*
import com.morealm.app.presentation.reader.ReaderSearchController
import com.morealm.app.ui.reader.page.animation.AnimatedPageReader
import com.morealm.app.ui.reader.page.animation.PageAnimType
import com.morealm.app.ui.reader.page.animation.SimulationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val VERTICAL_SWIPE_PAGE_THRESHOLD_RATIO = 0.08f
private const val PAGE_DELEGATE_DRAG_AXIS_RATIO = 1.2f

/**
 * restoreProgress 重试上限：getPageIndexByCharIndex 返回 -1（pageFactory
 * snapshot 还空）时，最多在同一 restoreToken 内静默重试这么多次再升级为
 * error + fallback page 0。8 次 ≈ 章节正常加载所需 2-3 帧的余量充足值，
 * 同时避免无限等待卡死 UI。
 */
private const val RESTORE_MAX_RETRIES = 8

/**
 * Full-featured Canvas-based text reader.
 *
 * Rendering pipeline:
 *   content → ChapterProvider (layout engine with justification + ZhLayout)
 *           → TextChapter (pre-computed pages with positioned columns)
 *           → PageCanvas (native Canvas drawing per page)
 *           → AnimatedPageReader / ScrollRenderer (page animation)
 *
 * Features:
 * - Full text justification with Chinese typography rules
 * - Text selection with cursor handles and floating toolbar
 * - TTS read-aloud line highlighting
 * - Search result highlighting
 * - Page animations: Slide, Cover, Simulation, None, Scroll
 * - Bookmark indicator
 * - Long-press context menu
 */
@Composable
fun CanvasRenderer(
    content: String,
    chapterTitle: String,
    chapterIndex: Int = 0,
    nextChapterTitle: String = "",
    nextChapterContent: String = "",
    prevChapterTitle: String = "",
    prevChapterContent: String = "",
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    isNight: Boolean = false,
    fontSize: Float = 18f,
    lineHeight: Float = 1.8f,
    typeface: Typeface = Typeface.SERIF,
    paddingHorizontal: Int = 24,
    paddingVertical: Int = 24,
    /**
     * 顶部边距（dp，独立于 [paddingVertical]）。null 时回落到 [paddingVertical]，向后兼容。
     * 由 ReaderScreen 维护的 preview 层驱动：拖动滑块期间只走 Compose state，不写 Room。
     */
    paddingTop: Int? = null,
    /**
     * 底部边距（dp，独立于 [paddingVertical]）。null 时回落到 [paddingVertical]，向后兼容。
     */
    paddingBottom: Int? = null,
    bgImageUri: String = "",
    startFromLastPage: Boolean = false,
    initialProgress: Int = 0,
    initialChapterPosition: Int = 0,
    /**
     * 每次 loadChapter 生成唯一 token（nanoTime）。restoreProgress LaunchedEffect
     * 以此为唯一 key：token 变 = 新恢复命令，不变 = 不恢复。
     * 对齐 Legado 精神：恢复是命令，不是状态订阅。
     */
    restoreToken: Long = 0L,
    onProgressRestored: () -> Unit = {},
    pageAnimType: PageAnimType = PageAnimType.SLIDE,
    onTapCenter: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onVisiblePageChanged: (chapterIndex: Int, title: String, readProgress: String, chapterPosition: Int) -> Unit = { _, _, _, _ -> },
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    onScrollNearBottom: () -> Unit = {},
    onScrollReachedBottom: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (Int) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onToggleTts: () -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onReadAloudParagraphPositions: (List<Int>) -> Unit = {},
    onVisibleReadAloudPosition: (chapterIndex: Int, chapterPosition: Int) -> Unit = { _, _ -> },
    pendingSearchSelection: ReaderSearchController.SearchSelection? = null,
    onSearchSelectionConsumed: () -> Unit = {},
    bookTitle: String = "",
    bookAuthor: String = "",
    // 9-zone tap actions (ported from Legado ReadView.click)
    // Values: "prev", "next", "menu", "prev_chapter", "next_chapter", "tts", "bookmark", "none"
    tapActionTopLeft: String = "prev",
    tapActionTopRight: String = "next",
    tapActionBottomLeft: String = "prev",
    tapActionBottomRight: String = "next",
    readerStyle: ReaderStyle? = null,
    chaptersSize: Int = 0,
    showChapterName: Boolean = true,
    showTimeBattery: Boolean = true,
    headerLeft: String = "chapter",
    headerCenter: String = "none",
    headerRight: String = "none",
    footerLeft: String = "battery_time",
    footerCenter: String = "none",
    footerRight: String = "page_progress",
    pageTurnCommand: ReaderPageDirection? = null,
    onPageTurnCommandConsumed: () -> Unit = {},
    autoPageSeconds: Int = 0,
    readAloudChapterPosition: Int = -1,
    /**
     * 当前章节所有用户高亮 — ReaderViewModel 按 chapterIndex 实时订阅 DB。
     * 渲染时每页自筛与本页 chapter-position 区间有交集的子集再画。
     */
    chapterHighlights: List<Highlight> = emptyList(),
    /**
     * 用户在 mini-menu 选某色后触发：保存一条新高亮。回调收到原文与颜色 ARGB；
     * 章节字符 offset 由 ReaderSelectionToolbar 自己根据 selectionState 计算。
     */
    onAddHighlight: (start: Int, end: Int, content: String, colorArgb: Int) -> Unit = { _, _, _, _ -> },
    /** 用户在「已存高亮」点击后选择删除。 */
    onDeleteHighlight: (id: String) -> Unit = {},
    /** 用户分享一条高亮内容（生成卡片图）。 */
    onShareHighlight: (Highlight) -> Unit = {},
    /**
     * 选区 mini-menu 的可见性 / 顺序 / 主行分配自定义配置；默认全部按预设。
     * 由 ReaderScreen 从 ReaderSettingsController 收到的 StateFlow 取值传入。
     */
    selectionMenuConfig: com.morealm.app.domain.entity.SelectionMenuConfig =
        com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT,
    /**
     * 章节标题对齐方式。0=左 / 1=中 / 2=右。来自 [com.morealm.app.domain.preference.AppPreferences.titleAlign]
     * 全局偏好，由 ReaderScreen 透传。变更会触发 LayoutInputs remember key 失效，
     * 重新排版整章。
     */
    titleAlign: Int = 0,
    /**
     * 用户在 mini-menu 选橡皮按钮 → 删除所有与当前选区有交集的高亮。
     * 回调收到本次选区的章节字符 offset 范围；持久化由调用方负责（一般转发到
     * [com.morealm.app.presentation.reader.ReaderHighlightController.eraseInRange]）。
     */
    onEraseHighlight: ((start: Int, end: Int) -> Unit)? = null,
    /**
     * 用户在选区菜单点 TEXT_COLOR 调色板上某色 → 保存"字体强调色"高亮（kind=1）。
     * 与 [onAddHighlight] 同入参，但持久化时 [com.morealm.app.domain.entity.Highlight.kind]
     * 落 1，渲染层据此替换该范围内字符的 paint.color 而不是画背景色块。
     */
    onAddTextColor: ((start: Int, end: Int, content: String, colorArgb: Int) -> Unit)? = null,
    /**
     * Layout-publish callbacks — Phase 2 MD3-aligned 同步腾挪基础。
     *
     * 对齐 Legado [io.legado.app.model.ReadBook.contentLoadFinish] 的精神：当排版完成
     * 后把 [com.morealm.app.domain.render.TextChapter] 推回 ViewModel 层（具体是
     * [com.morealm.app.presentation.reader.ReaderChapterController] 的三个 publish*
     * setter），让 ScrollRenderer 能从 _curTextChapter / _prev / _next 三个 StateFlow
     * 读到统一真值，并在 onChapterCommit 触发时同步腾挪。
     *
     * 调用时机：
     *   - [onCurTextChapterReady]：layoutChapterAsync 的 onCompleted 时触发（也在
     *     onPageReady index=0 时触发首次以最早建立 cur 引用，避免长章排版未完成时
     *     ScrollRenderer 看到 null cur）
     *   - [onPrevTextChapterReady] / [onNextTextChapterReady]：prelayoutCache 完成
     *     prev/next 章节预排版时触发
     *
     * 默认空函数 → 旧调用方零迁移成本；ReaderScreen 接通后 ScrollRenderer 才能走
     * 同步腾挪路径。idx 参数让 ChapterController 自己做"是否当前章"的校验
     * （防止快速跨章后旧章排版迟到回调污染）。
     */
    onCurTextChapterReady: (idx: Int, ch: com.morealm.app.domain.render.TextChapter) -> Unit = { _, _ -> },
    onPrevTextChapterReady: (idx: Int, ch: com.morealm.app.domain.render.TextChapter) -> Unit = { _, _ -> },
    onNextTextChapterReady: (idx: Int, ch: com.morealm.app.domain.render.TextChapter) -> Unit = { _, _ -> },
    /**
     * Phase 2 MD3 同步腾挪源 — 由 ReaderScreen 从
     * [com.morealm.app.presentation.reader.ReaderChapterController.prevTextChapter] /
     * [com.morealm.app.presentation.reader.ReaderChapterController.nextTextChapter]
     * collectAsState 派生传入。**非 null 时优先于 prelayoutCache 派生**，让
     * ScrollRenderer 的 prev/nextChapterPages 直接读 ChapterController 同步腾挪后的真值，
     * 绕开 prelayoutCache→cacheKey(title, content) 的异步派生窗口。
     *
     * 跨章 commit 时 [ReaderChapterController.commitChapterShiftNext] / Prev 在主线程
     * 当帧重写这两个 StateFlow，下一帧 Compose 重组立即生效——视觉无缝的物理基础。
     *
     * 默认 null 时回落到旧 prelayoutCache 派生路径，保证 ReaderScreen 接通前的兼容性。
     */
    syncPrevTextChapter: com.morealm.app.domain.render.TextChapter? = null,
    syncNextTextChapter: com.morealm.app.domain.render.TextChapter? = null,
    /**
     * Phase 2 MD3 同步腾挪 commit 入口 — 由 ScrollRenderer 在 onChapterCommit
     * 触发时调用。返回 true 表示同步腾挪成功（ChapterController 当帧已更新三个真值），
     * 此时 CanvasRenderer 跳过老 [onNextChapter] / [onPrevChapter] 回调（避免 loadChapter
     * 异步重排丢弃已就绪的 next）；返回 false 退回老路径。
     *
     * 默认返回 false → 老路径 = 异步 nextChapter()/prevChapter()，保证 ReaderScreen
     * 接通前的兼容。
     */
    onChapterCommitShift: (ReaderPageDirection) -> Boolean = { _ -> false },
    /**
     * 跳过章首标题块。本地 TXT 无目录自动分章场景必须设 true：
     * [com.morealm.app.domain.parser.LocalBookParser.parseWithoutToc] 把整本书按 10KB
     * 切成 N 段「第N节」伪章节，每段都画标题块会让用户翻页/滚动时反复看到同一书名。
     *
     * 该 flag 同时透传给 [com.morealm.app.domain.render.ChapterProvider.layoutChapterAsync]
     * （翻页路径首屏不画 isTitle 行）和 [LazyScrollSection]（滚动路径
     * `toScrollParagraphs(skipChapterTitleParagraph=true)` 把 CHAPTER_TITLE 段置空）。
     *
     * 由 [com.morealm.app.ui.reader.ReaderScreen] 根据 `book.format == TXT &&
     * book.localPath != null && chapters.all { it.isAutoSplitChapter() }` 计算后传入。
     */
    omitChapterTitleBlock: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun handleColumnClick(column: BaseColumn?): Boolean {
        return when (column) {
            is ImageColumn -> {
                onImageClick(column.src)
                true
            }
            is TextHtmlColumn -> {
                val link = column.linkUrl?.takeIf { it.isNotBlank() } ?: return false
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }.onFailure {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
                true
            }
            is ButtonColumn, is ReviewColumn -> {
                Toast.makeText(context, "暂不支持该内容操作", Toast.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx().toInt() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx().toInt() }

    // Display cutout insets (ported from Legado PageView.upPaddingDisplayCutouts)
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val cutoutLeft = with(density) { cutoutPadding.calculateLeftPadding(layoutDirection).toPx().toInt() }
    val cutoutRight = with(density) { cutoutPadding.calculateRightPadding(layoutDirection).toPx().toInt() }
    val cutoutTop = with(density) { cutoutPadding.calculateTopPadding().toPx().toInt() }
    val cutoutBottom = with(density) { cutoutPadding.calculateBottomPadding().toPx().toInt() }

    val padHPx = with(density) { paddingHorizontal.dp.toPx().toInt() }
    // 顶/底独立计算；caller 不传时回落到 paddingVertical（向后兼容老调用方）。
    val padTopPx = with(density) { (paddingTop ?: paddingVertical).dp.toPx().toInt() }
    val padBotPx = with(density) { (paddingBottom ?: paddingVertical).dp.toPx().toInt() }
    val infoBarHeightPx = with(density) { 64.dp.toPx().toInt() }
    // Ensure content padding is at least as large as the cutout insets
    val effectivePadLeft = maxOf(padHPx, cutoutLeft)
    val effectivePadRight = maxOf(padHPx, cutoutRight)
    val effectivePadTop = maxOf(padTopPx, cutoutTop) + infoBarHeightPx
    val effectivePadBottom = maxOf(padBotPx, cutoutBottom) + infoBarHeightPx
    val fontSizePx = with(density) { fontSize.sp.toPx() }

    // ── Battery level + charging (ported from Legado ReadBookActivity battery receiver) ──
    val batteryStatus by rememberBatteryStatus(context)
    val batteryLevel = batteryStatus.level
    val batteryCharging = batteryStatus.charging

    // ── Time (update every 30s) ──
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(30_000)
        }
    }

    // Create paints — apply letterSpacing and textBold from ReaderStyle
    val textArgb = textColor.toArgb()
    val chapterTitleColor = if (isNight) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
    val chapterAccentColor = if (isNight) 0xFFCFA875.toInt() else 0xFFBFA175.toInt()
    val styleLetterSpacing = readerStyle?.letterSpacing ?: 0f
    val styleTextBold = readerStyle?.textBold ?: 0

    val contentPaint = remember(fontSizePx, typeface, textArgb, lineHeight, styleLetterSpacing, styleTextBold) {
        TextPaint().apply {
            color = textArgb
            textSize = fontSizePx
            isAntiAlias = true
            this.typeface = when (styleTextBold) {
                1 -> Typeface.create(typeface, Typeface.BOLD)
                2 -> typeface // light — no fake bold
                else -> typeface
            }
            letterSpacing = styleLetterSpacing
        }
    }
    val titlePaint = remember(fontSizePx, typeface, chapterTitleColor, styleLetterSpacing) {
        TextPaint().apply {
            color = chapterTitleColor
            textSize = fontSizePx * 1.45f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = typeface
            letterSpacing = styleLetterSpacing + 0.01f
        }
    }
    // Chapter number paint: smaller, accent-colored, with letter-spacing
    val chapterNumPaint = remember(fontSizePx, typeface, chapterAccentColor, styleLetterSpacing) {
        TextPaint().apply {
            color = chapterAccentColor
            textSize = fontSizePx * 0.85f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            letterSpacing = styleLetterSpacing + 0.04f
        }
    }
    val textMeasure = remember(contentPaint) { TextMeasure(contentPaint) }

    data class LayoutInputs(
        val provider: ChapterProvider,
        val contentPaint: TextPaint,
        val titlePaint: TextPaint,
    )

    val layoutInputs = remember(screenWidthPx, screenHeightPx, fontSizePx, effectivePadLeft, effectivePadRight, effectivePadTop, effectivePadBottom, lineHeight, readerStyle, contentPaint, titlePaint, textMeasure, density, titleAlign) {
        val style = readerStyle
        val cssOverrides = CssParser.parse(style?.customCss ?: "")
        val effectiveContentPaint = if (cssOverrides.fontSize != null) {
            TextPaint(contentPaint).apply { textSize = cssOverrides.fontSize * density.density }
        } else TextPaint(contentPaint)
        val effectiveTitlePaint = if (cssOverrides.fontSize != null) {
            TextPaint(titlePaint).apply { textSize = (cssOverrides.fontSize + 4) * density.density }
        } else TextPaint(titlePaint)
        if (cssOverrides.letterSpacing != null) {
            effectiveContentPaint.letterSpacing = cssOverrides.letterSpacing
        }
        val effectiveMeasure = if (cssOverrides.fontSize != null || cssOverrides.letterSpacing != null) {
            TextMeasure(effectiveContentPaint)
        } else textMeasure
        LayoutInputs(
            provider = ChapterProvider(
                viewWidth = screenWidthPx,
                viewHeight = screenHeightPx,
                paddingLeft = cssOverrides.paddingLeft?.let { (it * density.density).toInt() } ?: effectivePadLeft,
                paddingRight = cssOverrides.paddingRight?.let { (it * density.density).toInt() } ?: effectivePadRight,
                paddingTop = cssOverrides.paddingTop?.let { (it * density.density).toInt() } ?: effectivePadTop,
                paddingBottom = cssOverrides.paddingBottom?.let { (it * density.density).toInt() } ?: effectivePadBottom,
                titlePaint = effectiveTitlePaint,
                contentPaint = effectiveContentPaint,
                textMeasure = effectiveMeasure,
                lineSpacingExtra = cssOverrides.lineSpacingExtra ?: lineHeight,
                paragraphIndent = cssOverrides.paragraphIndent ?: style?.paragraphIndent ?: "\u3000\u3000",
                textFullJustify = cssOverrides.textAlign?.let { it == "justify" } ?: (style?.isJustify() ?: true),
                titleMode = style?.titleMode ?: 0,
                isMiddleTitle = style?.titleMode == 1,
                titleAlign = titleAlign,
                paragraphSpacing = cssOverrides.paragraphSpacing ?: style?.paragraphSpacing ?: 8,
                titleTopSpacing = style?.titleTopSpacing ?: 0,
                titleBottomSpacing = style?.titleBottomSpacing ?: 0,
                chapterNumPaint = chapterNumPaint,
            ),
            contentPaint = effectiveContentPaint,
            titlePaint = effectiveTitlePaint,
        )
    }

    fun chapterCacheKey(index: Int, title: String, body: String): String = "$index|$title|${body.hashCode()}|$screenWidthPx|$screenHeightPx|$fontSizePx|$lineHeight|$effectivePadLeft|$effectivePadRight|$effectivePadTop|$effectivePadBottom|${readerStyle?.hashCode()}|$titleAlign|$omitChapterTitleBlock"

    val currentChapterKey: String = remember(
        chapterIndex,
        chapterTitle,
        content,
        screenWidthPx,
        screenHeightPx,
        fontSizePx,
        effectivePadLeft,
        effectivePadRight,
        effectivePadTop,
        effectivePadBottom,
        lineHeight,
        readerStyle,
        titleAlign,
        omitChapterTitleBlock,
    ) {
        chapterCacheKey(chapterIndex, chapterTitle, content)
    }

    fun placeholderChapterFor(
        index: Int,
        title: String,
        message: String = title.ifBlank { "加载中..." },
    ): TextChapter {
        val chapter = TextChapter(index, title, chaptersSize)
        val page = TextPage(
            index = 0,
            title = title,
            chapterIndex = index,
            chapterSize = chaptersSize,
            paddingTop = effectivePadTop,
        ).apply {
            text = message.ifBlank { "加载中..." }
            textChapter = chapter
        }.format()
        chapter.addPage(page)
        return chapter
    }

    fun placeholderChapter(message: String = chapterTitle.ifBlank { "加载中..." }): TextChapter =
        placeholderChapterFor(chapterIndex, chapterTitle, message)

    // Layout pages — 预排版 LRU 缓存（容量 8）。
    // 装：当前章 + 上下章 + ~5 个历史 padding/字号配置。
    //
    // ## Phase 2 后语义变化
    // 本 cache 在 Phase 2 MD3 同步腾挪接通前是 ScrollRenderer 跨章预览的**主源**——
    // prev/nextTextChapter 由它根据 (idx, title, content) cacheKey 派生。Phase 2e 之后
    // syncPrev/NextTextChapter 优先级更高（来自 ChapterController 同步腾挪后的真值流），
    // 本 cache 在滚动模式下退化为：
    //   1. **首次进入 / 切书时的兜底**：sync 流为 null 时回落到此 cache 派生
    //   2. **padding/字号配置 LRU 加速**：拖动设置滑块来回切回相同值时秒回（仍由本 cache 命中）
    //   3. **prelayoutPut 推回 onPrev/NextTextChapterReady**：让 ChapterController
    //      的 _prev/_nextTextChapter 与本 cache 同源
    // 即在滚动模式跨章无缝路径上，本 cache 不再是关键路径——但仍保留作兜底 +
    // padding LRU。SLIDE/SIMULATION 翻页路径仍依赖本 cache 的 cacheKey 派生
    // （它们没接同步腾挪），不动。
    // 与原版关键差异：
    //   1) 不再在 padding 变化时 clear() —— 旧条目自然 LRU 淘汰，反复在几个 padding 值
    //      之间切换时仍能命中（拖动结束、下次再调到相同值秒回）。
    //   2) textChapter / pageCount 不再 keyed 到 currentChapterKey —— 拖动 padding 时
    //      currentChapterKey 高频变化，老实现会把 textChapter 立刻重置为 placeholder
    //      导致每帧闪屏。新实现保留旧布局，等 layoutChapterAsync 出新结果后无缝切。
    val prelayoutCache = remember { mutableStateMapOf<String, TextChapter>() }
    val prelayoutOrder = remember { ArrayDeque<String>() }
    fun prelayoutPut(key: String, value: TextChapter) {
        if (prelayoutCache.containsKey(key)) prelayoutOrder.remove(key)
        prelayoutCache[key] = value
        prelayoutOrder.addLast(key)
        while (prelayoutOrder.size > 8) {
            val evict = prelayoutOrder.removeFirst()
            prelayoutCache.remove(evict)
        }
    }
    var textChapter by remember { mutableStateOf<TextChapter?>(null) }
    var pageCount by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(nextChapterContent, nextChapterTitle, prevChapterContent, prevChapterTitle, layoutInputs) {
        val targets = listOf(
            Triple(chapterIndex + 1, nextChapterTitle, nextChapterContent),
            Triple(chapterIndex - 1, prevChapterTitle, prevChapterContent),
        ).filter { (_, title, body) -> title.isNotBlank() && body.isNotBlank() }
        targets.forEach { (index, title, body) ->
            val key = chapterCacheKey(index, title, body)
            if (!prelayoutCache.containsKey(key)) {
                val chapter = withContext(Dispatchers.Default) {
                    val chapter = layoutInputs.provider.layoutChapter(
                        title = title,
                        content = body,
                        chapterIndex = index,
                        chaptersSize = chaptersSize,
                        omitChapterTitleBlock = omitChapterTitleBlock,
                    )
                    chapter
                }
                prelayoutPut(key, chapter)
                // Phase 2b: 推回 ChapterController 让 _prev/_nextTextChapter 与
                // CanvasRenderer 内部 prelayoutCache 同步真值。idx 校验由 publish 端做。
                if (index == chapterIndex + 1) {
                    onNextTextChapterReady(index, chapter)
                } else if (index == chapterIndex - 1) {
                    onPrevTextChapterReady(index, chapter)
                }
            }
        }
    }

    LaunchedEffect(currentChapterKey, layoutInputs) {
        // Cache hit: 立即显示完整布局（如来自 next/prev 预排版，或之前的 padding 配置）。
        val cachedChapter = prelayoutCache[currentChapterKey]
        if (cachedChapter != null) {
            textChapter = cachedChapter
            pageCount = cachedChapter.pageSize.coerceAtLeast(1)
            // Phase 2b: cache 命中也要推回 cur — 通常发生在 next/prev 转 cur 后，
            // 此时 ChapterController 的 _curTextChapter 应该指向这章。idx 校验在 publish 端。
            onCurTextChapterReady(chapterIndex, cachedChapter)
            return@LaunchedEffect
        }
        // Cache miss: 节流一帧（16ms）。拖动 padding 滑块时 key 高频变化，
        // LaunchedEffect 在新 key 到来时会取消旧 coroutine（含此 delay），
        // 保证只有"用户手指停下/即将停下"那一刻的 key 真正进入分页流水线。
        kotlinx.coroutines.delay(16L)
        // 仅在还没有任何可显示布局时才退到 placeholder（首次进入），
        // 否则保留旧 textChapter 直到 layoutChapterAsync 出第一页 —— 消除拖动闪屏。
        if (textChapter == null || textChapter?.pages.isNullOrEmpty()) {
            textChapter = placeholderChapter()
            pageCount = 1
        }
        if (content.isBlank()) {
            val chapter = placeholderChapter(chapterTitle.ifBlank { "当前章节暂无正文" }).apply {
                isCompleted = true
            }
            textChapter = chapter
            pageCount = 1
        } else {
            var handle: com.morealm.app.domain.render.AsyncLayoutHandle? = null
            handle = layoutInputs.provider.layoutChapterAsync(
                title = chapterTitle,
                content = content,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                scope = this,
                omitChapterTitleBlock = omitChapterTitleBlock,
                onPageReady = { index, _ ->
                    if (index == 0) {
                        textChapter = handle?.textChapter
                        // Phase 2b: 第一页就绪时立刻推回 cur，让 ScrollRenderer 尽早
                        // 拿到 cur reference（哪怕 isCompleted=false，pages 仍可访问已就绪部分）。
                        // 对齐 Legado 流式排版思路：边排边可见。
                        handle?.textChapter?.let { onCurTextChapterReady(chapterIndex, it) }
                    }
                    pageCount = handle?.textChapter?.pageSize?.coerceAtLeast(1) ?: 1
                },
                onCompleted = {
                    textChapter = handle?.textChapter
                    pageCount = handle?.textChapter?.pageSize?.coerceAtLeast(1) ?: 1
                    // 把当前章节最终布局也存入 LRU：来回拖到相同 padding 值时秒回。
                    handle?.textChapter?.let {
                        prelayoutPut(currentChapterKey, it)
                        // Phase 2b: 排版完成（isCompleted=true）后再推回一次，
                        // 让 _curTextChapter 指向 final 状态（含完整 pageSize / 末页等信息）。
                        onCurTextChapterReady(chapterIndex, it)
                    }
                },
            )
        }
    }

    val chapter = textChapter
    // 不再 keyed 到 currentChapterKey：拖动 padding 时旧 pages 作 fallback，
    // layoutChapterAsync 出新结果再无缝切；避免拖动期间渲染层瞬间丢页面。
    var lastRenderablePages by remember { mutableStateOf<List<TextPage>>(emptyList()) }
    if (!chapter?.pages.isNullOrEmpty()) {
        lastRenderablePages = chapter?.pages ?: emptyList()
    }
    val currentChapterPages = chapter?.pages?.takeIf { it.isNotEmpty() }
        ?: lastRenderablePages.ifEmpty { placeholderChapter().pages }
    // Phase 2e: prev/next TextChapter 派生 — 优先用 ChapterController 同步腾挪后的真值
    // [syncPrevTextChapter] / [syncNextTextChapter]，回落 prelayoutCache 派生。
    //
    // 跨章 commit 后的关键路径：
    //   - 同步路径生效（syncPrev/NextTextChapter 非 null 且 ReaderScreen 接通）：
    //     ChapterController.commitChapterShiftNext 当帧已经把 _prevTextChapter 设为
    //     旧 cur、_nextTextChapter 清空。Compose 下一帧重组时这里直接拿到新真值，
    //     ScrollRenderer 的 prev/nextChapterPages 当帧切换无窗口。
    //   - 兼容路径（sync 流为 null）：回落到 prelayoutCache 派生（旧逻辑），cacheKey
    //     依赖 prevChapterTitle/Content props 重组到位，存在异步窗口——这是 phase 2e
    //     接通前的临时退化路径，仅在 ReaderScreen 还没传 sync 流时启用。
    val prevTextChapter = syncPrevTextChapter
        ?: if (prevChapterTitle.isNotBlank() && prevChapterContent.isNotBlank()) {
            prelayoutCache[chapterCacheKey(chapterIndex - 1, prevChapterTitle, prevChapterContent)]
        } else null
    val nextTextChapter = syncNextTextChapter
        ?: if (nextChapterTitle.isNotBlank() && nextChapterContent.isNotBlank()) {
            prelayoutCache[chapterCacheKey(chapterIndex + 1, nextChapterTitle, nextChapterContent)]
        } else null
    // When navigating backward, initialize to last page from prelayout cache
    // to avoid flashing page 0 for one frame before LaunchedEffect corrects it.
    var readerPageIndex by remember(chapterIndex) {
        val initialPage = if (startFromLastPage) {
            val cached = prelayoutCache[currentChapterKey]
            (cached?.pageSize?.minus(1))?.coerceAtLeast(0) ?: 0
        } else 0
        mutableIntStateOf(initialPage)
    }
    LaunchedEffect(pageCount, chapter?.isCompleted) {
        if (chapter?.isCompleted == true && pageCount > 0 && readerPageIndex > pageCount - 1) {
            readerPageIndex = pageCount - 1
        }
    }
    val pageFactory = remember(
        chapter,
        prevTextChapter,
        nextTextChapter,
        currentChapterPages,
        pageCount,
        chapter?.isCompleted,
        prevTextChapter?.pageSize,
        prevTextChapter?.isCompleted,
        nextTextChapter?.pageSize,
        nextTextChapter?.isCompleted,
        pageAnimType,
        readerPageIndex,
    ) {
        ReaderPageFactory(
            dataSource = SnapshotReaderDataSource(
                pageIndex = readerPageIndex,
                currentChapter = chapter,
                prevChapter = prevTextChapter,
                nextChapter = nextTextChapter,
                isScroll = pageAnimType == PageAnimType.SCROLL,
                hasNextChapterValue = chapterIndex < chaptersSize - 1,
                hasPrevChapterValue = chapterIndex > 0,
                onUpContent = { _, _ -> },
            ),
        )
    }

    val pages = pageFactory.pages

    /**
     * Convert DB [Highlight] rows into renderer-friendly [HighlightSpan]s once
     * per change. Recomputes only when the chapter index or list identity
     * changes — Compose `remember(key)` keys it on the list reference, so
     * Flow.collectAsState producing a new list will trigger fresh mapping.
     */
    val highlightSpans = remember(chapterHighlights) {
        // 只取 KIND_BACKGROUND（kind==0）走传统的"画 bgFill 矩形"路径。
        chapterHighlights
            .filter { it.kind == Highlight.KIND_BACKGROUND }
            .map { h ->
                HighlightSpan(
                    id = h.id,
                    startChapterPos = h.startChapterPos,
                    endChapterPos = h.endChapterPos,
                    colorArgb = h.colorArgb,
                )
            }
    }
    /**
     * 字体色高亮（kind == [Highlight.KIND_TEXT_COLOR]）。
     *
     * 与 [highlightSpans] 同结构 [HighlightSpan]，但语义不同：渲染层不画 bg，
     * 而是在 [com.morealm.app.ui.reader.renderer.PageContentDrawer.PageCanvas]
     * 内、画字符时按 char 的 chapterPosition 命中范围替换 paint.color。
     */
    val textColorSpans = remember(chapterHighlights) {
        chapterHighlights
            .filter { it.kind == Highlight.KIND_TEXT_COLOR }
            .map { h ->
                HighlightSpan(
                    id = h.id,
                    startChapterPos = h.startChapterPos,
                    endChapterPos = h.endChapterPos,
                    colorArgb = h.colorArgb,
                )
            }
    }
    val renderPageCount = pageFactory.pageCount

    LaunchedEffect(chapter, readAloudChapterPosition) {
        chapter?.pages?.forEach { it.removePageAloudSpan() }
        if (chapter != null && readAloudChapterPosition >= 0) {
            val aloudPageIndex = chapter.getPageIndexByCharIndex(readAloudChapterPosition)
            val page = chapter.getPage(aloudPageIndex)
            val pageStart = chapter.getReadLength(aloudPageIndex)
            page?.upPageAloudSpan(readAloudChapterPosition - pageStart)
        }
    }

    LaunchedEffect(chapter) {
        val positions = chapter?.getParagraphs(pageSplit = false)
            ?.map { it.chapterPosition }
            .orEmpty()
        onReadAloudParagraphPositions(positions)
    }

    // Track whether we've already restored the saved position for this content.
    var progressRestored by remember { mutableStateOf(false) }
    /**
     * 当 `getPageIndexByCharIndex(initialChapterPosition)` 返回 -1（pageFactory
     * snapshot 还空）时累计的重试次数。restoreToken 一变就清零；同 token 内
     * 累到 [RESTORE_MAX_RETRIES] 仍解析不出来才升级为 error + fallback page 0，
     * 避免静默回退把用户的书签位置悄悄掉到章首。
     */
    var restoreAttempts by remember(restoreToken) { mutableIntStateOf(0) }
    // restoreToken 是唯一判据：只有 loadChapter 生成新 token 时才清 progressRestored。
    // 用户翻页 / renderPageCount 变化 / 前后台切换不会改 token → 不会触发幽灵恢复。
    //
    // 之前的 bug：key 里包含 initialChapterPosition / initialProgress，这些值
    // 来自 RenderedReaderChapter StateFlow，Compose 重组时一直存活 → 用户翻页后
    // 若其他 LaunchedEffect 重发射，把 progressRestored 清掉 → 幽灵恢复弹回去。
    LaunchedEffect(restoreToken) {
        if (restoreToken != 0L) {
            progressRestored = false
        }
    }

    // Selection state
    val selectionState = remember { SelectionState() }
    var selectedTextPage by remember(chapterIndex) { mutableStateOf<TextPage?>(null) }
    var scrollRelativePages by remember(chapterIndex) { mutableStateOf<Map<Int, TextPage>>(emptyMap()) }
    var toolbarOffset by remember { mutableStateOf(Offset.Zero) }
    /**
     * 当前正在弹「删除 / 分享」action menu 的高亮（用户点中已存高亮时填充）。
     * 与选区 toolbar 互斥：一旦设值，selection 自动清掉避免两个浮层同屏抢戏。
     */
    var highlightActionTarget by remember(chapterIndex) { mutableStateOf<Highlight?>(null) }
    var highlightActionOffset by remember { mutableStateOf(Offset.Zero) }
    /**
     * 滚动模式（[LazyScrollRenderer] / [ScrollRenderer]）当前是否处于
     * fling/drag。由下游 onScrollingChanged 回调写入。Popup 化后用来：
     * 用户开始滚动 → 立刻清掉 selection / highlightActionTarget，避免「僵尸菜单」
     * 卡在屏幕上跟着内容飘。pageAnimType 走 pagerState 的模式则直接观察
     * pagerState.isScrollInProgress，不依赖这个状态。
     */
    var scrollInProgress by remember { mutableStateOf(false) }
    /**
     * 跳转成功后的「整段褪色高亮」状态。书签 / TOC / 续读 / 跨章恢复成功后由
     * restoreProgress LaunchedEffect 设值并启动 alpha 动画；动画结束后置回 null。
     * 跨章节自动失效（key 含 chapterIndex），不会污染下一章。
     *
     * 渲染路径：作为一条临时 [HighlightSpan] 合并进 [highlightSpans]，复用已存
     * 高亮的 per-line bg rect 绘制管线，零绘制代码改动。详见 [RevealHighlight] 文档。
     */
    var revealHighlight by remember(chapterIndex) { mutableStateOf<RevealHighlight?>(null) }
    // Share quote dialog state
    var shareQuoteText by remember { mutableStateOf<String?>(null) }
    var scrollPageIndex by remember(chapterIndex) { mutableIntStateOf(0) }
    val autoPagerState = remember(chapterIndex, pageAnimType) { ReaderAutoPagerState() }
    var autoPageProgress by remember(chapterIndex, pageAnimType) { mutableIntStateOf(0) }
    var autoScrollDelta by remember(chapterIndex, pageAnimType) { mutableIntStateOf(0) }
    // Cross-chapter scroll state（已下线）：滚动模式现在走 [LazyScrollSection]，跨章
    // 衔接由 LazyColumn 的 prepend/append 视野补偿（见 LazyScrollSection 三道防线）
    // 接管，不再需要 ReaderScrollState 中转 displayOffset。

    // Pager state — always start at 0, then jump after layout completes
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { renderPageCount })

    // Page-turn coordinator — replaces local page-turn functions and state.
    //
    // 跨章闪烁 / 滚动→仿真跳首页防御第二层（参见 docs/issues/）：在 remember
    // 构造块里 *同步* 算出 initialPage 并写入新 coordinator，让它"出生即正确"。
    //
    // 没有这一步时，新 coordinator 默认 lastSettledDisplayPage=0，第一次重组的
    // AndroidView.update lambda 同步执行，用 displayPage=0 渲染章节首页位图
    // 装入 idleBitmap —— 屏幕闪一帧错的页。LaunchedEffect 修正是异步的，慢于
    // update lambda，所以纯靠它兜底兜不住。
    //
    // initialPage 的取值分四种场景：
    //   1. SCROLL 模式：0（滚动模式自己管位置，coordinator 的 lastSettled 不参与渲染）
    //   2. 同章节、仅模式切换 (chapterIndex 没变)：readerPageIndex —— 这是
    //      ScrollRenderer / 翻页模式都在维护的逻辑页索引，是当前阅读位置的
    //      "单一真值"。这条路径专门修「滚动→仿真跳首页」的 bug。
    //   3. PREV 跨章 (startFromLastPage=true)：上一章末页。优先读 prelayoutCache
    //      因为新章节的 renderPageCount 重组初期可能还没算好。
    //   4. 其它（首次进入 / NEXT 跨章）：0。
    val lastChapterIdxHolder = remember { intArrayOf(Int.MIN_VALUE) }
    val coordinator = remember(chapterIndex, pageAnimType) {
        val sameChapter = lastChapterIdxHolder[0] == chapterIndex
        lastChapterIdxHolder[0] = chapterIndex
        val initialPage = when {
            pageAnimType == PageAnimType.SCROLL -> 0
            sameChapter -> {
                // renderPageCount 在 coordinator 重建瞬间常常 reset 到 1（章节
                // 布局还没算好，prelayout 还在增量产出页面），coerceIn(0, 0)
                // 会把 readerPageIndex=N>0 强行夹成 0 —— 这是「滚动→仿真跳
                // 首页」第一次实测仍然复发的根因（见 18:15:42.948 日志）。
                // 优先用 prelayoutCache 的稳定 pageSize；缓存没命中再退回
                // renderPageCount，但只在 > 1 时使用；都不行就不设上限，
                // 让 LaunchedEffect 后续 scrollToPage 兜底处理越界。
                val cap = prelayoutCache[currentChapterKey]?.pageSize
                    ?: renderPageCount.takeIf { it > 1 }
                    ?: Int.MAX_VALUE
                readerPageIndex.coerceIn(0, (cap - 1).coerceAtLeast(0))
            }
            startFromLastPage -> {
                val cachedPageCount = prelayoutCache[currentChapterKey]?.pageSize ?: renderPageCount
                (cachedPageCount - 1).coerceAtLeast(0)
            }
            else -> 0
        }
        AppLog.debug(
            "PageTurnFlicker",
            "[2] coordinator REBUILD chapterIndex=$chapterIndex pageAnimType=$pageAnimType" +
                " sameChapter=$sameChapter readerPageIndex=$readerPageIndex" +
                " renderPageCount=$renderPageCount" +
                " cachedPageCount=${prelayoutCache[currentChapterKey]?.pageSize}" +
                " startFromLastPage=$startFromLastPage initialPage=$initialPage",
        )
        PageTurnCoordinator(
            scope, pageAnimType, onNextChapter, onPrevChapter, onProgress, onVisiblePageChanged
        ).apply {
            lastSettledDisplayPage = initialPage
            ignoredSettledDisplayPage = initialPage
        }
    }
    // Update deps on each recomposition so coordinator always sees latest values
    coordinator.updateDeps(pageFactory, pagerState, chapterIndex, pageCount, renderPageCount)

    /**
     * Stable upper bound for `coordinator.lastSettledDisplayPage.coerceIn(...)`.
     *
     * `renderPageCount` is sometimes 1 immediately after a coordinator rebuild
     * (the prelayout pipeline streams pages in incrementally — see the bursts
     * of `[3a] pageCount=1, 8, 29, 84, ...` in flicker logs). Using `renderPageCount - 1`
     * directly as the upper bound clamps a `lastSettledDisplayPage = 5` back to
     * `0` while pages 1..N are still being laid out — exactly the «显示首页一帧»
     * we just spent three layers fighting.
     *
     * Resolution order:
     *   1. `prelayoutCache.pageSize` — stable, computed once per chapter.
     *   2. `renderPageCount` if > 1 — covers the post-stable case after layout
     *      finished but no cache entry was written (rare).
     *   3. `Int.MAX_VALUE` — don't clamp at all when nothing else is known.
     *      Worst case the rendered TextPage is null/placeholder and the bitmap
     *      ends up blank; this is safer than clamping to 0 because LaunchedEffect
     *      will scrollToPage / re-render once a real bound is available.
     */
    val safeDisplayMax: Int = run {
        val cap = prelayoutCache[currentChapterKey]?.pageSize
            ?: renderPageCount.takeIf { it > 1 }
            ?: Int.MAX_VALUE
        (cap - 1).coerceAtLeast(0)
    }

    // 独立的 chapter tracker 给 LaunchedEffect 用 —— coordinator remember 块的
    // holder 在 composition 阶段就被覆盖成 chapterIndex 自己，effect 拿到的永远
    // 是 sameChapter=true，没法区分。这个 holder 只在 effect 真正执行时更新。
    val effectLastChapterHolder = remember { intArrayOf(Int.MIN_VALUE) }

    LaunchedEffect(currentChapterKey, pageAnimType) {
        if (pageAnimType == PageAnimType.SCROLL) {
            effectLastChapterHolder[0] = chapterIndex
            return@LaunchedEffect
        }
        val sameChapter = effectLastChapterHolder[0] == chapterIndex
        effectLastChapterHolder[0] = chapterIndex
        if (sameChapter) {
            // 仅模式切换（如 SCROLL→SIMULATION / SCROLL→SLIDE）：coordinator
            // remember 块里已经用 readerPageIndex 同步算出 initialPage 并写入
            // lastSettledDisplayPage，绝不能在这里写回 0 把它覆盖掉。
            //
            // ─── Layer 2: pagerState 同步 ─────────────────────────────────────
            // pagerState 在模式切换时不会被重建（rememberPagerState 在整段函数
            // 都是同一个），但 SCROLL 模式期间 HorizontalPager 没挂载、pagerState
            // 仍停留在上次离开 HorizontalPager 时的 currentPage 值——很可能是 0。
            // 切回 SLIDE/COVER/NONE 这些用 HorizontalPager 的模式时，新挂载的
            // pager 会以 pagerState.currentPage=0 立刻发出 onPageSettled(0)
            // phantom 信号。Layer 1 在 coordinator 端拒绝了这条 phantom，但
            // pagerState 自身仍然停在 0；后续如果再有任何写入路径触达，仍可
            // 能拖住进度。这里主动把 pagerState 拉到 coordinator 已经算好的
            // 真值（= readerPageIndex 解析出的进度页），让两边状态在切换瞬间
            // 一致，是「彻底不回归」的保险栓。
            //
            // 注意：必须用 lastSettledDisplayPage 而不是 readerPageIndex——前者
            // 在 SIMULATION 等用 displayIndex 寻址的模式里是经过 pageFactory
            // 转换的 display index，直接用 reader local index 会错位。
            //
            // ─── cap 必须用 safeDisplayMax 而非 (renderPageCount - 1) ───────
            // 模式切换瞬间 renderPageCount 常常 reset 到 1（pageFactory 重建、
            // pages 列表还在 layout streaming），用 (renderPageCount - 1) 当
            // 上限会把 lastSettled=N(N>0) 直接夹成 0，触发的 scrollToPage(0)
            // 会反过来把 pagerState 移到第 0 页 + 让 phantom settle(0) 命中
            // ignoredPage 被消费，最终把 lastSettled 改成 0——绕过 Layer 1
            // 防御。22:27 那次没踩雷只是因为 pagerState.currentPage 恰好也
            // 是 0（SCROLL session 没人动它）。若操作序列里 pagerState 已经
            // 被翻过页（如 SLIDE→SCROLL→SLIDE），就会暴雷。
            //
            // safeDisplayMax 的解析顺序 prelayoutCache.pageSize → renderPageCount(>1)
            // → Int.MAX_VALUE 在 layout streaming 早期也能给出稳定上限。
            val targetDisplay = coordinator.lastSettledDisplayPage
                .coerceIn(0, safeDisplayMax)
            if (pagerState.currentPage != targetDisplay) {
                // 对齐时把这一次 settle 标记成"忽略"，避免 scrollToPage 完成后
                // 紧跟着的 onPageSettled(targetDisplay) 仍触发 saveProgress 写回
                // (虽然写回的是同一个值不会损坏，但日志会更干净)。
                coordinator.ignoredSettledDisplayPage = targetDisplay
                pagerState.scrollToPage(targetDisplay)
            }
            return@LaunchedEffect
        }
        // 真章节切换：跟 remember 块的逻辑保持等价（startFromLastPage 才跳末页）。
        val initialPage = if (startFromLastPage) {
            val cachedPageCount = prelayoutCache[currentChapterKey]?.pageSize ?: renderPageCount
            (cachedPageCount - 1).coerceAtLeast(0)
        } else 0
        coordinator.ignoredSettledDisplayPage = initialPage
        coordinator.pendingSettledDirection = null
        coordinator.lastSettledDisplayPage = initialPage
        coordinator.lastReaderContent = null
        pagerState.scrollToPage(initialPage)
    }

    LaunchedEffect(chapter, pendingSearchSelection) {
        val selection = pendingSearchSelection ?: return@LaunchedEffect
        val textChapter = chapter ?: return@LaunchedEffect
        if (!textChapter.isCompleted || selection.chapterIndex != chapterIndex) return@LaunchedEffect
        val range = textChapter.searchSelectionRange(
            contentPosition = selection.queryIndexInChapter,
            queryLength = selection.queryLength,
        ) ?: return@LaunchedEffect
        selectedTextPage = textChapter.getPage(range.pageIndex)
        selectionState.setSelection(range.start, range.end)
        readerPageIndex = range.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (pageAnimType != PageAnimType.SCROLL) {
            pagerState.scrollToPage(pageFactory.displayIndexForCurrentPage(range.pageIndex).coerceIn(0, renderPageCount - 1))
        }
        onSearchSelectionConsumed()
    }

    fun stopAutoPager() {
        autoPagerState.stop()
        autoPageProgress = 0
    }

    LaunchedEffect(autoPageSeconds, pageAnimType, screenHeightPx, progressRestored) {
        if (!progressRestored || autoPageSeconds <= 0) {
            stopAutoPager()
            return@LaunchedEffect
        }
        autoPagerState.start()
        while (autoPagerState.isRunning && autoPageSeconds > 0) {
            withFrameNanos { }
            val offset = autoPagerState.computeOffset(
                readSpeedSeconds = autoPageSeconds,
                height = screenHeightPx,
                isScroll = pageAnimType == PageAnimType.SCROLL,
            )
            if (offset <= 0) continue
            if (pageAnimType == PageAnimType.SCROLL) {
                autoScrollDelta += offset
            } else {
                autoPageProgress = autoPagerState.progress.coerceIn(0, screenHeightPx)
                if (autoPagerState.progress >= screenHeightPx) {
                    val startDisplayPage = coordinator.lastSettledDisplayPage.coerceIn(0, renderPageCount - 1)
                    val canTurn = pageFactory.hasNext(startDisplayPage)
                    if (!canTurn) {
                        stopAutoPager()
                    } else {
                        coordinator.commitPageTurn(startDisplayPage, ReaderPageDirection.NEXT) { readerPageIndex = it }?.let { committed ->
                            pagerState.scrollToPage(committed.coerceIn(0, renderPageCount - 1))
                        } ?: stopAutoPager()
                        autoPagerState.reset()
                        autoPageProgress = 0
                    }
                }
            }
        }
    }

    LaunchedEffect(pageTurnCommand, progressRestored, pageAnimType, renderPageCount) {
        val direction = pageTurnCommand ?: return@LaunchedEffect
        if (!progressRestored) return@LaunchedEffect
        if (pageAnimType == PageAnimType.SCROLL) return@LaunchedEffect
        onPageTurnCommandConsumed()
        coordinator.turnPageByTap(direction) { readerPageIndex = it }
    }

    // Restore saved progress after layout is complete.
    //
    // 进度恢复 LaunchedEffect — restoreToken 作为唯一"命令 key"。
    // 其余 key（renderPageCount / pageCount / chapter.isCompleted）仅用于
    // "等渲染完成后执行"的守卫条件，不会独立触发新恢复。
    //
    // 与之前的区别：去掉了 initialChapterPosition / initialProgress / startFromLastPage
    // 作为 key。这些值由 restoreToken 代表（token 变 → 值必定已更新），不再独立触发。
    LaunchedEffect(
        restoreToken, renderPageCount, pageCount, chapter?.isCompleted, progressRestored, pageAnimType,
    ) {
        if (progressRestored) return@LaunchedEffect
        if (restoreToken == 0L) return@LaunchedEffect
        if (chapter?.isCompleted != true) return@LaunchedEffect
        // renderPageCount <= 1 且不需要跳特定位置 → 只有 1 页，直接标记完成。
        // 但如果 startFromLastPage / initialChapterPosition / initialProgress 有值，
        // 说明需要跳到非首页 → 必须等 renderPageCount 增长到真实值才能定位。
        val needsNonZeroPage = startFromLastPage || initialChapterPosition > 0 || initialProgress > 0
        if (renderPageCount <= 1 && !needsNonZeroPage) {
            progressRestored = true
            onProgressRestored()
            return@LaunchedEffect
        }
        if (renderPageCount <= 1) {
            // 需要非首页但布局还没好 → 等下一轮 renderPageCount 变化再重进
            return@LaunchedEffect
        }
        // 等 pageFactory 缓存追平 chapter.pageSize 才恢复。
        //
        // 背景：
        //   - `pageCount` 是 chapter.pageSize（layoutChapterAsync 流式 push，实时增长）
        //   - `renderPageCount` 是 pageFactory.pages.size（pageFactory 构造时 snapshotPages() 一次镜像）
        //   - layoutChapterAsync 一次性 push 多页时，pageCount 已经 = 15 但 pageFactory
        //     上一次重建时只 snapshot 到 10。下面 line 919 用 `renderPageCount - 1`
        //     做钳制 → target=13 被夹到 9，触发一次错的 JUMP；7 ms 后 pageFactory 再
        //     重建一次缓存到 15，第二次 LaunchedEffect 又跑一遍 JUMP 到 13。
        //     视觉表现：用户看到「page 9 → page 13」跳一下；副作用：page 9 的
        //     reportProgress 写脏一次 DB（position=2345 scroll=100%）。
        //
        //   修：要求 renderPageCount >= pageCount 才执行；否则直接 return 等下一轮
        //   （keys 里包含 renderPageCount 和 pageCount，下一帧 pageFactory 重建追上
        //   后会自动重进）。
        if (renderPageCount < pageCount) {
            AppLog.debug(
                "CanvasRenderer",
                "restoreProgress WAIT pageFactory snapshot stale | renderPC=$renderPageCount < pc=$pageCount token=$restoreToken",
            )
            return@LaunchedEffect
        }
        // BookmarkDebug: 诊断书签跳转后是否回到正确页。
        //
        // ── 静默回退 → 显式重试 ──
        //
        // 旧实现：`coerceIn(0, pageCount-1)` 直接把 -1 / 越界值夹到 0，下游
        // computedTarget 跟着掉到 page 0；用户书签明明在第 5 页却跳回章首。
        // 我们想要的语义：getPageIndexByCharIndex 返回 -1（pageFactory 还没
        // ready，snapshotPages 尚空）就**等下一轮**，不要假装"映射成功"。
        //
        // 重试机制：restoreToken 变 → 计数器清零；同 token 内最多 8 次早 return
        // （每次 LaunchedEffect 因 renderPageCount/pageCount/chapter.isCompleted
        // 变化重进），仍解析不出来再升级为 ERROR + fallback page 0，避免无限
        // 等待卡死 UI。8 次相对宽裕：实测一章正常加载 2-3 帧 pageFactory 追上，
        // 极端情况留余量。
        val rawPageFromCharIndex = if (initialChapterPosition > 0) {
            chapter.getPageIndexByCharIndex(initialChapterPosition)
        } else -1
        if (initialChapterPosition > 0 && rawPageFromCharIndex < 0) {
            if (restoreAttempts < RESTORE_MAX_RETRIES) {
                restoreAttempts += 1
                AppLog.debug(
                    "BookmarkDebug",
                    "restoreProgress retry char→page mapping ($restoreAttempts/$RESTORE_MAX_RETRIES)" +
                        " | chIdx=$chapterIndex initChapPos=$initialChapterPosition" +
                        " | pc=$pageCount renderPC=$renderPageCount token=$restoreToken",
                )
                return@LaunchedEffect
            }
            // 超限：升级为 error 严重度（旧实现仅 warn），并继续走 fallback
            AppLog.error(
                "BookmarkDebug",
                "restoreProgress UNRESOLVED after $restoreAttempts retries — falling back to page 0" +
                    " | chIdx=$chapterIndex initChapPos=$initialChapterPosition" +
                    " | pc=$pageCount renderPC=$renderPageCount token=$restoreToken",
            )
        }
        val pageFromCharIndex = if (initialChapterPosition > 0) {
            rawPageFromCharIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        } else -1
        val currentTargetPage = when {
            startFromLastPage -> pageCount - 1
            initialChapterPosition > 0 -> pageFromCharIndex
            initialProgress > 0 -> ((initialProgress / 100f) * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
            else -> 0
        }
        AppLog.info(
            "BookmarkDebug",
            "restoreProgress chIdx=${chapter.chaptersSize.let { chapterIndex }}" +
                " initChapPos=$initialChapterPosition initProg=$initialProgress" +
                " startFromLast=$startFromLastPage pageFromCharIdx=$pageFromCharIndex" +
                " computedTarget=$currentTargetPage pc=$pageCount renderPC=$renderPageCount" +
                " pageAnim=$pageAnimType restoreToken=$restoreToken attempts=$restoreAttempts",
        )
        AppLog.debug("CanvasRenderer", "restoreProgress: startFromLast=$startFromLastPage target=$currentTargetPage pc=$pageCount renderPC=$renderPageCount token=$restoreToken")
        val targetPage = pageFactory.displayIndexForCurrentPage(currentTargetPage).coerceIn(0, renderPageCount - 1)
        readerPageIndex = currentTargetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        AppLog.info(
            "BookmarkDebug",
            "restoreProgress JUMP targetDisplayPage=$targetPage pagerCurrent=${pagerState.currentPage}" +
                " readerPageIndex=$readerPageIndex pageAnim=$pageAnimType",
        )

        // ─── 关键：先写 coordinator 状态，再 scrollToPage ─────────────────
        // 仿真模式下 pagerState.currentPage 永远是 0（SimulationView 自管渲染，
        // pagerState 在 SimulationPager 里只是个摆设），真正决定显示的是
        // coordinator.lastSettledDisplayPage。如果先 scrollToPage 再写 lastSettled，
        // scrollToPage 触发的 onPageSettled 回调进 coordinator 时把 ignoredPage 吞掉
        // 但没更新 lastSettled —— 用户后续翻页基于 lastSettled 旧值（=0）继续算，
        // 视觉上就停在原地。
        //
        // 修复：先把 lastSettled / lastReaderContent 写好，再决定要不要 scrollToPage。
        // - 仿真模式：完全跳过 scrollToPage（pagerState 是摆设，scroll 反而触发幽灵 onPageSettled）
        // - **滚动模式（SCROLL）**：实际渲染走 [LazyScrollRenderer] / [ScrollRenderer]，
        //   pagerState 同样是摆设。多余的 scrollToPage 不仅无视觉效果，还因 LaunchedEffect
        //   在 scroll 真正 suspend 之前 key 已变被 cancel → 抛 LeftCompositionCancellationException。
        //   而且 onPageSettled 链会串扰 LazyScroll 的 firstVisibleItem 同步。
        //   书签精确跳转改由 [LazyScrollRenderer] 的 `jumpAnchor` + `jumpToken` 命令式 API
        //   接管（见本文件下方 LazyScrollRenderer 调用处）。
        // - 其他模式（SLIDE/COVER/NONE）：scrollToPage 仍然是必须的，pagerState 决定视觉
        coordinator.lastSettledDisplayPage = targetPage
        coordinator.lastReaderContent = coordinator.createPageState(targetPage).upContent()
        coordinator.reportProgress(coordinator.lastReaderContent)

        if (pageAnimType != PageAnimType.SIMULATION && pageAnimType != PageAnimType.SCROLL) {
            coordinator.ignoredSettledDisplayPage = targetPage
            val beforeScroll = pagerState.currentPage
            try {
                pagerState.scrollToPage(targetPage)
                val afterScroll = pagerState.currentPage
                AppLog.info(
                    "BookmarkDebug",
                    "restoreProgress JUMP DONE ($pageAnimType) before=$beforeScroll after=$afterScroll" +
                        " coord.lastSettled=${coordinator.lastSettledDisplayPage}" +
                        " scrollEffective=${afterScroll == targetPage}",
                )
            } catch (e: Throwable) {
                AppLog.error("BookmarkDebug", "scrollToPage threw: ${e.message}", e)
            }
        } else {
            AppLog.info(
                "BookmarkDebug",
                "restoreProgress JUMP DONE ($pageAnimType, skip scrollToPage)" +
                    " coord.lastSettled=${coordinator.lastSettledDisplayPage}",
            )
        }
        progressRestored = true
        onProgressRestored()

        // ── 跳转后整段褪色高亮 ──
        //
        // 书签 / TOC / 续读跳转成功后给用户一个清晰的"我跳到这儿了"视觉反馈：
        // 把目标 chapterPos 所在整段染上一层淡淡的 primary 色，1 秒内褪到透明。
        //
        // 触发条件：仅在用户主动跳转（initialChapterPosition > 0 或 startFromLastPage）
        // 时显示。普通"打开新书第一次进章"（initialChapterPosition == 0）不闪烁，
        // 避免每次进章都来一道光。
        //
        // chapterPosition 用来定位目标段：用 chapter.getParagraphs(pageSplit=false)
        // 取段列表，找首个 chapterIndices 包住目标的 paragraph。找不到 → 不触发，
        // 避免边界情况下画错位置（例如 layout 流式构造未完成）。
        if (initialChapterPosition > 0 && chapter != null) {
            val targetParagraph = chapter.getParagraphs(pageSplit = false).firstOrNull {
                initialChapterPosition in it.chapterIndices
            }
            if (targetParagraph != null && targetParagraph.length > 0) {
                val startCp = targetParagraph.chapterPosition
                val endCp = startCp + targetParagraph.length
                val baseArgb = accentColor.toArgb()
                val animatable = androidx.compose.animation.core.Animatable(1f)
                revealHighlight = RevealHighlight(
                    chapterIndex = chapterIndex,
                    startChapterPos = startCp,
                    endChapterPos = endCp,
                    baseColorArgb = baseArgb,
                    alpha = animatable,
                )
                scope.launch {
                    // hold 200ms 让用户先看清，然后 1000ms 渐隐到 0；总计 1.2s。
                    // 用 separate scope.launch 而不是占用 restoreProgress 的 LaunchedEffect
                    // 协程 —— 这条 LaunchedEffect 在 restoreToken 失效时会取消，太短不
                    // 够画完动画；scope（rememberCoroutineScope）只在 Composable 离开
                    // 组合时取消，更适合 UI 反馈类持续动效。
                    kotlinx.coroutines.delay(200)
                    animatable.animateTo(
                        targetValue = 0f,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 1000,
                        ),
                    )
                    // 动画完成后清空，避免下次同章相同位置 alpha=0 也保持引用
                    revealHighlight = null
                }
            }
        }
    }

    // Report progress —— 用 snapshotFlow + distinctUntilChanged 把「pagerState.currentPage
    // 在翻页动画过渡帧的高频变化」节流为「真正的页索引变化」。旧写法
    // `LaunchedEffect(pagerState.currentPage, renderPageCount, pageCount,
    //   chapter?.isCompleted, progressRestored, pageAnimType)` 6-key 复合键在 fling
    // 衰减期 + 章节切换瞬间会反复重启 effect，每次重新构造一个跑一帧就被取消的协程；
    // snapshotFlow 把状态变化外包给 Compose snapshot 系统，filterNotNull 跳过
    // 「未就绪态」（chapter 未完成 / progress 未恢复 / localPage 越界），
    // distinctUntilChanged 去掉同百分比连续重复回调。
    //
    // LaunchedEffect key 收缩到 (pageFactory, pagerState) —— 仅章节切换 / pager 重建
    // 时重启 Flow 链路；其余高频信号 (currentPage / pageCount / progressRestored /
    // chapter.isCompleted) 通过 snapshotFlow 自动追踪 Compose snapshot 读。
    LaunchedEffect(pageFactory, pagerState) {
        snapshotFlow {
            if (chapter?.isCompleted != true || !progressRestored) return@snapshotFlow null
            val localPage = pageFactory.currentLocalIndex(pagerState.currentPage)
                ?: return@snapshotFlow null
            if (pageCount > 1) (localPage * 100) / (pageCount - 1) else 100
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect(onProgress)
    }

    val bgArgb = backgroundColor.toArgb()

    // Load background image bitmap (decoded + cached by BgImageManager)
    val bgEntry = remember(bgImageUri, screenWidthPx, screenHeightPx) {
        if (bgImageUri.isNotEmpty() && screenWidthPx > 0 && screenHeightPx > 0) {
            BgImageManager.getBgBitmap(context, bgImageUri, screenWidthPx, screenHeightPx)
        } else null
    }
    val bgBitmap = bgEntry?.bitmap
    val bgMeanColor = bgEntry?.meanColor ?: bgArgb
    val pageInfoOverlaySpec = PageInfoOverlaySpec(
        chapterTitle = chapterTitle,
        pageCount = pageCount,
        chapterIndex = chapterIndex,
        chaptersSize = chaptersSize,
        batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
        currentTime = currentTime,
        textColorArgb = textColor.toArgb(),
        backgroundColorArgb = bgArgb,
        paddingHorizontalPx = with(density) { paddingHorizontal.dp.toPx() },
        barHeightPx = with(density) { 64.dp.toPx() },
        verticalPaddingPx = with(density) { 8.dp.toPx() },
        textSizePx = with(density) { 10.sp.toPx() },
        showChapterName = showChapterName,
        showTimeBattery = showTimeBattery,
        headerLeft = headerLeft,
        headerCenter = headerCenter,
        headerRight = headerRight,
        footerLeft = footerLeft,
        footerCenter = footerCenter,
        footerRight = footerRight,
        hasBgImage = bgBitmap != null,
    )

    // ── 渲染主题（5 件套 paint + 背景 + 4 高亮色）注入 ──
    //
    // 旧链路把 titlePaint/contentPaint/chapterNumPaint/bgArgb/bgBitmap 当作 5 个独立
    // 入参分别透传给 [rememberSimulationParams] / [LazyScrollRenderer] / [ScrollRenderer]，
    // caller 只要漏一个字段就静默渲染异常（譬如忘了传 bgBitmap → 自定义背景图丢失）。
    //
    // Phase 2 起统一打包成 [ReaderRenderTheme] 一次性注入 [LocalReaderRenderTheme]，
    // 让 SimulationDelegate / LazyScrollRenderer / PageCanvas / PageContentBox 等下游
    // 组件直接 .current 取，少传一组字段、少一份「漏字段」风险。
    //
    // Phase B（P1 #4/#5）扩展：4 个高亮色（selection / aloud / searchResult / bookmark）
    // 也并入 theme，从 [MaterialTheme.colorScheme] 派生。这样 PageCanvas / PageContentBox
    // 不再各自重复 `MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)` 表达式，
    // 主题切换 + 高亮色变化由 readerTheme 一处统一管理。
    //
    // remember key 用 5 件套 paint/bg + 2 件套 MaterialTheme 派生色：用户改字号 →
    // titlePaint 换引用 → readerTheme 重建；切换日/夜 / 主题 → mtPrimary/mtError 变 →
    // readerTheme 重建。下游 LazyScrollRenderer / PageCanvas 等通过 LocalReaderRenderTheme.current
    // 自动拿到新主题。
    val mtPrimary = MaterialTheme.colorScheme.primary
    val mtError = MaterialTheme.colorScheme.error
    val readerTheme = remember(
        titlePaint, contentPaint, chapterNumPaint, bgArgb, bgBitmap, mtPrimary, mtError,
    ) {
        ReaderRenderTheme(
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
            bgArgb = bgArgb,
            bgBitmap = bgBitmap,
            selectionColor = mtPrimary.copy(alpha = 0.30f),
            aloudColor = mtPrimary.copy(alpha = 0.20f),
            searchResultColor = mtPrimary.copy(alpha = 0.25f),
            bookmarkColor = mtError,
        )
    }

    // 把整段「params 构建 + UI 树」放在 LocalReaderRenderTheme 作用域内 —— 注意作用
    // 域跨度大（约 660 行，到 CanvasRenderer 主体结束），但 CompositionLocalProvider
    // 是纯值注入，不引入额外重组开销；为保留 git diff 的可读性，下方代码块缩进维持
    // 不变。
    CompositionLocalProvider(LocalReaderRenderTheme provides readerTheme) {

    // SimulationParams for bezier page curl —— Phase 2 后期重构：抽到独立模块。
    // 详见 [com.morealm.app.ui.reader.page.animation.rememberSimulationParams]。
    // 这里只透传 CanvasRenderer 内部状态，仿真细节（pageForTurn / canTurn /
    // onLongPress 等）已经搬到独立文件，CanvasRenderer 不再关心仿真路径的实现。
    //
    // 5 件套 paint 已迁到 LocalReaderRenderTheme，rememberSimulationParams 自取，
    // 这里不再重复传。
    val simulationParams = com.morealm.app.ui.reader.page.animation.rememberSimulationParams(
        pageAnimType = pageAnimType,
        pages = pages,
        bgMeanColor = bgMeanColor,
        pageInfoOverlaySpec = pageInfoOverlaySpec,
        pageFactory = pageFactory,
        chapterIndex = chapterIndex,
        pageCount = pageCount,
        renderPageCount = renderPageCount,
        coordinator = coordinator,
        selectionState = selectionState,
        chapterHighlights = chapterHighlights,
        // 仿真模式渲染 highlights / textColor 用：CanvasRenderer 已派生 highlightSpans
        // (kind=0) + textColorSpans (kind=1)，直接透传。SimulationDelegate 把它们填入
        // SimulationParams.chapter*Spans，SimulationPager 渲染 bitmap 时按页过滤后画。
        highlightSpans = highlightSpans,
        textColorSpans = textColorSpans,
        onProgress = onProgress,
        onTapCenter = onTapCenter,
        onImageClick = onImageClick,
        setHighlightActionTarget = { highlightActionTarget = it },
        setHighlightActionOffset = { highlightActionOffset = it },
        setSelectedTextPage = { selectedTextPage = it },
        setToolbarOffset = { toolbarOffset = it },
        setReaderPageIndex = { readerPageIndex = it },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .then(
                if (pageAnimType != PageAnimType.SCROLL && pageAnimType != PageAnimType.SIMULATION) {
                    Modifier.pointerInput(selectionState.isActive, pageAnimType, renderPageCount) {
                        var totalDragX = 0f
                        var totalDragY = 0f
                        var dragAxis = 0 // 0 unknown, 1 horizontal, 2 vertical
                        var turnRequested = false
                        val slop = viewConfiguration.touchSlop
                        detectDragGestures(
                            onDragStart = {
                                totalDragX = 0f
                                totalDragY = 0f
                                dragAxis = 0
                                turnRequested = false
                                if (!selectionState.isActive) {
                                    coordinator.pageDelegateState.onDown()
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (selectionState.isActive) return@detectDragGestures
                                totalDragX += dragAmount.x
                                totalDragY += dragAmount.y
                                if (dragAxis == 0) {
                                    val absX = abs(totalDragX)
                                    val absY = abs(totalDragY)
                                    if (absX > slop || absY > slop) {
                                        dragAxis = when {
                                            pageAnimType != PageAnimType.SLIDE_VERTICAL &&
                                                absX > absY * PAGE_DELEGATE_DRAG_AXIS_RATIO -> 1
                                            absY > absX * PAGE_DELEGATE_DRAG_AXIS_RATIO -> 2
                                            else -> 0
                                        }
                                    }
                                }
                                if (dragAxis != 0) {
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (!selectionState.isActive) {
                                    val horizontalThreshold = (size.width * VERTICAL_SWIPE_PAGE_THRESHOLD_RATIO).coerceAtLeast(48f)
                                    val verticalThreshold = (size.height * VERTICAL_SWIPE_PAGE_THRESHOLD_RATIO).coerceAtLeast(48f)
                                    when {
                                        dragAxis == 1 && abs(totalDragX) > horizontalThreshold -> {
                                            turnRequested = true
                                            coordinator.turnPageByDrag(if (totalDragX > 0f) ReaderPageDirection.PREV else ReaderPageDirection.NEXT) { readerPageIndex = it }
                                        }
                                        dragAxis == 2 && abs(totalDragY) > verticalThreshold -> {
                                            turnRequested = true
                                            coordinator.turnPageByDrag(if (totalDragY < 0f) ReaderPageDirection.NEXT else ReaderPageDirection.PREV) { readerPageIndex = it }
                                        }
                                    }
                                } else {
                                    // 翻页手势结束时如果选区还在，清掉——避免翻到新页后
                                    // 旧页的 mini menu 还悬浮在屏幕上。
                                    selectionState.clear()
                                    highlightActionTarget = null
                                }
                                totalDragX = 0f
                                totalDragY = 0f
                                dragAxis = 0
                                if (!turnRequested) {
                                    coordinator.pageDelegateState.stopScroll()
                                }
                            },
                            onDragCancel = {
                                totalDragX = 0f
                                totalDragY = 0f
                                dragAxis = 0
                                turnRequested = false
                                coordinator.pageDelegateState.abortAnim()
                            },
                        )
                    }
                } else Modifier
            )
            .then(
                if (pageAnimType != PageAnimType.SCROLL && pageAnimType != PageAnimType.SIMULATION) {
                    Modifier.pointerInput(selectionState.isActive) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (selectionState.isActive) {
                                    selectionState.clear()
                                    return@detectTapGestures
                                }
                                // Highlight hit-test takes priority — let
                                // taps on saved highlights bring up the
                                // delete/share menu before falling through
                                // to image / column / 9-zone tap handling.
                                if (chapterHighlights.isNotEmpty()) {
                                    val page = coordinator.getPageAt(pagerState.currentPage)
                                    val pos = chapterPositionAt(page, offset.x, offset.y)
                                    if (pos != null) {
                                        val hit = chapterHighlights.firstOrNull {
                                            pos in it.startChapterPos until it.endChapterPos
                                        }
                                        if (hit != null) {
                                            highlightActionTarget = hit
                                            highlightActionOffset = offset
                                            return@detectTapGestures
                                        }
                                    }
                                }
                                // Reset delegate state on tap — matches Legado's onDown()
                                // which always runs before any page-turn request. Without
                                // this, a stuck isRunning from a prior animation blocks
                                // all future tap-based page turns.
                                coordinator.pageDelegateState.onDown()
                                // 9-zone tap (ported from Legado ReadView.onSingleTapUp + setRect9x)
                                val w = size.width; val h = size.height
                                val tapColumn = when {
                                    offset.x < w * 0.33f -> 0  // left
                                    offset.x < w * 0.66f -> 1  // center
                                    else -> 2                   // right
                                }
                                val row = when {
                                    offset.y < h * 0.33f -> 0  // top
                                    offset.y < h * 0.66f -> 1  // middle
                                    else -> 2                   // bottom
                                }
                                // Determine action for this zone
                                val action = when (row to tapColumn) {
                                    0 to 0 -> tapActionTopLeft      // TL
                                    0 to 1 -> "prev"                // TC: prev page
                                    0 to 2 -> tapActionTopRight     // TR
                                    1 to 0 -> "prev"                // ML: prev page
                                    1 to 1 -> "menu"                // MC: show menu
                                    1 to 2 -> "next"                // MR: next page
                                    2 to 0 -> tapActionBottomLeft   // BL
                                    2 to 1 -> "next"                // BC: next page
                                    2 to 2 -> tapActionBottomRight  // BR
                                    else -> "menu"
                                }
                                if (action == "menu") {
                                    onTapCenter()
                                    return@detectTapGestures
                                }
                                // Execute page-zone actions before image hit testing. Legado's
                                // PageDelegate treats a moved/page-turn gesture separately from
                                // ContentTextView.click(), so cover images must not swallow left/right
                                // page turns or fast horizontal swipes.
                                when (action) {
                                    "prev" -> {
                                        if (pageAnimType != PageAnimType.SCROLL) {
                                            coordinator.turnPageByTap(ReaderPageDirection.PREV) { readerPageIndex = it }
                                        }
                                        return@detectTapGestures
                                    }
                                    "next" -> {
                                        if (pageAnimType != PageAnimType.SCROLL) {
                                            coordinator.turnPageByTap(ReaderPageDirection.NEXT) { readerPageIndex = it }
                                        }
                                        return@detectTapGestures
                                    }
                                    "prev_chapter" -> {
                                        onPrevChapter()
                                        return@detectTapGestures
                                    }
                                    "next_chapter" -> {
                                        onNextChapter()
                                        return@detectTapGestures
                                    }
                                    "tts" -> {
                                        onToggleTts()
                                        return@detectTapGestures
                                    }
                                    "bookmark" -> {
                                        onAddBookmark()
                                        return@detectTapGestures
                                    }
                                    "none" -> Unit
                                }
                                val page = coordinator.getPageAt(pagerState.currentPage)
                                val hitColumn = hitTestColumn(page, offset.x, offset.y)
                                handleColumnClick(hitColumn)
                            },
                            onLongPress = { offset ->
                                val page = coordinator.getPageAt(pagerState.currentPage)
                                // If long-press on image, show image viewer (ported from Legado)
                                val col = hitTestColumn(page, offset.x, offset.y)
                                if (col is ImageColumn) {
                                    onImageClick(col.src)
                                    return@detectTapGestures
                                }
                                val pos = hitTestPage(page, offset.x, offset.y)
                                if (pos != null) {
                                    // Word-level selection (ported from Legado ReadView.onLongPress)
                                    val wordRange = findWordRange(page, pos)
                                    selectedTextPage = page
                                    selectionState.setSelection(wordRange.first, wordRange.second)
                                    toolbarOffset = offset
                                    // Diagnostic: confirm the non-SCROLL long-press path
                                    // (SLIDE/COVER/NONE; SIMULATION uses pagerState.currentPage=0
                                    // here which may pick the wrong page — see earlier analysis).
                                    AppLog.info(
                                        "CursorHandleTrace",
                                        "NORMAL longPress setSelection" +
                                            " | pageAnim=$pageAnimType pagerCurrent=${pagerState.currentPage}" +
                                            " | tap=$pos -> word(${wordRange.first}..${wordRange.second})" +
                                            " | page.lines.size=${page.lines.size} chPos=${page.chapterPosition}",
                                    )
                                }
                            },
                        )
                    }
                } else Modifier
            )
    ) {
        if (pageAnimType == PageAnimType.SCROLL) {
            // ── SCROLL 模式：LazyColumn 段落级瀑布流 ──
            //
            // 数据流 + 三道防线全部委托给 [LazyScrollSection]（独立文件 ~280 行），
            // 这里只透传 chapter window + 跳转坐标 + 回调。详细机制见
            // [LazyScrollSection] 注释。
            //
            // 注：老 ScrollRenderer 路径已在删除 task #6 中下线，配套的
            // [PageReaderInfoOverlay]（页码/电池/时间状态栏）也一并移除——
            // 滚动模式天然无分页概念，状态栏由翻页模式独占即可。
            LazyScrollSection(
                chapter = chapter,
                prevTextChapter = prevTextChapter,
                nextTextChapter = nextTextChapter,
                chapterIndex = chapterIndex,
                initialChapterPosition = initialChapterPosition,
                restoreToken = restoreToken,
                readAloudChapterPosition = readAloudChapterPosition,
                chapterTitle = chapterTitle,
                prevChapterTitle = prevChapterTitle,
                nextChapterTitle = nextChapterTitle,
                backgroundColor = Color(readerTheme.bgArgb),
                textColor = Color(textColor.toArgb()),
                // 跨章后用 takeIf 过滤旧章 reveal 不画到当前 viewport
                revealHighlight = revealHighlight?.takeIf { it.chapterIndex == chapterIndex },
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onProgress = onProgress,
                onCopyText = onCopyText,
                // 段级 mini-menu 各回调透传（分享走本地 dialog；其余走 ReaderViewModel）
                onSpeakFromHere = onSpeakFromHere,
                onTranslateText = onTranslateText,
                onLookupWord = onLookupWord,
                onShareQuote = { text -> shareQuoteText = text },
                onAddHighlight = { start, end, text, argb ->
                    onAddHighlight(start, end, text, argb)
                },
                onEraseHighlight = onEraseHighlight,
                onAddTextColor = onAddTextColor,
                selectionMenuConfig = selectionMenuConfig,
                // 滚动模式接通用户高亮（kind=0 背景）/ 字体强调色（kind=1）渲染。
                // 这两个 List 已由 wrapper（CanvasRenderer 主体）从 chapterHighlights
                // 派生为 [HighlightSpan]，分别按 kind 分桶 —— 直接透传即可。
                chapterHighlights = highlightSpans,
                chapterTextColorSpans = textColorSpans,
                // 原始 Highlight 列表 + 删除/分享回调：滚动模式 tap 命中已存高亮 → 弹
                // action menu 路径用，与分页模式的 chapterHighlights / onDeleteHighlight /
                // onShareHighlight 一一对应。
                chapterHighlightsRaw = chapterHighlights,
                onDeleteHighlight = onDeleteHighlight,
                onShareHighlight = onShareHighlight,
                onTapCenter = onTapCenter,
                onVisiblePageChanged = onVisiblePageChanged,
                onScrollingChanged = { scrollInProgress = it },
                omitChapterTitleBlock = omitChapterTitleBlock,
                modifier = Modifier.fillMaxSize(),
            )
            // ── SCROLL 模式 info overlay（顶部章节标题 + 底部电池/时间/章节进度）──
            //
            // 分页模式由 [PageReaderInfoOverlay] 在每页 [PageContentBox] 内部叠加；
            // SCROLL 模式的 [LazyScrollSection] 内部不画状态栏，缺一组就好像在裸文字
            // 流上读，没有"我在哪一章"的视觉锚点。这里复用 [ReaderInfoBar]（同文件
            // private fun）画顶部 + 底部两条，对齐分页模式的体验。
            //
            // slot 映射：SCROLL 没有"当前页"概念，配置里 page / progress / page_progress
            // 三个 slot 自动 fallback 到 chapter_progress（X/Y 章），不至于显示
            // "1/0" 这种坏数据。其它 slot（chapter / time / battery / time_battery /
            // chapter_progress 等）保持原义。
            //
            // currentPage = null：InfoSlotContent 在 progress 分支会用 pageIndex/pageCount
            // 推算百分比，传 pageCount=0 + 上面 slot 映射后 progress 永远不会被命中 ——
            // 双重保险，不会进 NaN 路径。
            fun mapSlotForScroll(s: String): String = when (s) {
                "page", "progress", "page_progress" -> "chapter_progress"
                else -> s
            }
            val scrollHasBg = readerTheme.bgBitmap != null
            ReaderInfoBar(
                slotLeft = if (showTimeBattery) mapSlotForScroll(headerLeft) else "none",
                slotCenter = if (showChapterName) mapSlotForScroll(headerCenter) else "none",
                slotRight = if (showTimeBattery) mapSlotForScroll(headerRight) else "none",
                chapterTitle = chapterTitle,
                pageIndex = 0,
                pageCount = 0,
                currentPage = null,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                currentTime = currentTime,
                textColor = textColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(64.dp)
                    .then(
                        if (scrollHasBg) Modifier
                        else Modifier.background(
                            Brush.verticalGradient(
                                0f to backgroundColor,
                                0.72f to backgroundColor,
                                1f to backgroundColor.copy(alpha = 0f),
                            )
                        )
                    )
                    .padding(horizontal = paddingHorizontal.dp, vertical = 8.dp),
            )
            ReaderInfoBar(
                slotLeft = if (showChapterName) mapSlotForScroll(footerLeft) else "none",
                slotCenter = mapSlotForScroll(footerCenter),
                slotRight = mapSlotForScroll(footerRight),
                chapterTitle = chapterTitle,
                pageIndex = 0,
                pageCount = 0,
                currentPage = null,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                currentTime = currentTime,
                textColor = textColor,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(64.dp)
                    .then(
                        if (scrollHasBg) Modifier
                        else Modifier.background(
                            Brush.verticalGradient(
                                0f to backgroundColor.copy(alpha = 0f),
                                0.28f to backgroundColor,
                                1f to backgroundColor,
                            )
                        )
                    )
                    .padding(horizontal = paddingHorizontal.dp, vertical = 8.dp),
            )
        } else {
            // Diagnostic — pairs with [2] coordinator REBUILD and [3p]
            // SimulationPager COMPOSE so we can see the chain:
            //   coordinator.lastSettledDisplayPage → coerceIn(0, safeMax) →
            //   simulationDisplayPage → SimulationPager currentDisplayPage.
            // 用于诊断「切到仿真先闪首页」：如果 simulationDisplayPage 在
            // 第一次重组时是 0、第二次才变成 lastSettled——就解释了首页一帧。
            val computedSimDisplayPage =
                coordinator.lastSettledDisplayPage.coerceIn(0, safeDisplayMax)
            // 节流：分页流式产页时 safeDisplayMax 每追加几页就变一次，但
            // computedSimDisplayPage 通常恒定 0。同 (computedSimDisplayPage,
            // lastSettled) 在 1s 内只打第一行 + 累计被压制次数。
            // [0]=key, [1]=lastLogMs, [2]=suppressedCount
            val simDisplayPageLogState = remember { LongArray(3) }
            val nowMs = System.currentTimeMillis()
            val key = (computedSimDisplayPage.toLong() shl 32) or
                (coordinator.lastSettledDisplayPage.toLong() and 0xFFFFFFFFL)
            val sameKey = simDisplayPageLogState[0] == key
            val withinWindow = nowMs - simDisplayPageLogState[1] < 1_000L
            if (sameKey && withinWindow) {
                simDisplayPageLogState[2] += 1
            } else {
                val suppressed = if (sameKey) simDisplayPageLogState[2] else 0L
                AppLog.debug(
                    "PageTurnFlicker",
                    "[1] simulationDisplayPage=$computedSimDisplayPage" +
                        " (lastSettled=${coordinator.lastSettledDisplayPage}" +
                        " safeDisplayMax=$safeDisplayMax pageAnimType=$pageAnimType)" +
                        if (suppressed > 0) " (+ ${suppressed}x suppressed in last 1000ms)" else "",
                )
                simDisplayPageLogState[0] = key
                simDisplayPageLogState[1] = nowMs
                simDisplayPageLogState[2] = 0L
            }
            AnimatedPageReader(
                pagerState = pagerState,
                animType = pageAnimType,
                modifier = Modifier.fillMaxSize(),
                simulationParams = simulationParams,
                simulationDisplayPage = computedSimDisplayPage,
                onPageSettled = { settledPage ->
                    // Diagnostic [3o] — 验证假设：pagerState 在 SCROLL 时被
                    // 同步到 0，切到 SIMULATION 时若 HorizontalPager (在 SLIDE/
                    // COVER 等其它分支) 或 SimulationPager 内部某处把
                    // pagerState.currentPage=0 当成已 settled 上报，会写回
                    // readerPageIndex=0 → 下一帧 coordinator 重建 displayPage=0
                    // → 渲染章节首页那一帧。
                    AppLog.debug(
                        "PageTurnFlicker",
                        "[3o] onPageSettled RECV settledPage=$settledPage" +
                            " progressRestored=$progressRestored" +
                            " coordinatorLastSettled=${coordinator.lastSettledDisplayPage}" +
                            " readerPageIndex=$readerPageIndex" +
                            " pageAnimType=$pageAnimType",
                    )
                    if (!progressRestored) {
                        coordinator.pendingSettledDirection = null
                        return@AnimatedPageReader
                    }
                    coordinator.handlePagerSettled(settledPage) { readerPageIndex = it }
                    // 翻页完成后清掉残留的选区和高亮 action menu
                    if (selectionState.isActive) {
                        selectionState.clear()
                    }
                    highlightActionTarget = null
                },
            ) { pageIndex ->
                    PageContentBox(
                page = coordinator.getPageAt(pageIndex),
                    pageIndex = pageIndex,
                    currentPage = if (pageAnimType == PageAnimType.SIMULATION) {
                        coordinator.lastSettledDisplayPage.coerceIn(0, safeDisplayMax)
                    } else {
                        pagerState.currentPage
                    },
                    backgroundColor = backgroundColor,
                    selectionState = selectionState,
                    chapterTitle = chapterTitle,
                    pageCount = pageCount,
                    chapterIndex = chapterIndex,
                    chaptersSize = chaptersSize,
                    batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                    currentTime = currentTime,
                    textColor = textColor,
                    paddingHorizontal = paddingHorizontal,
                    headerLeft = headerLeft,
                    headerCenter = headerCenter,
                    headerRight = headerRight,
                    footerLeft = footerLeft,
                    footerCenter = footerCenter,
                    footerRight = footerRight,
                    showChapterName = showChapterName,
                    showTimeBattery = showTimeBattery,
                    autoPageOverlayProgress = autoPageProgress,
                    autoPageNextPage = if (autoPageProgress > 0) coordinator.getRelativePage(pageIndex, 1) else null,
                    autoPageAccentColor = accentColor,
                    readAloudChapterPosition = readAloudChapterPosition,
                    chapterHighlights = highlightSpans,
                    chapterTextColorSpans = textColorSpans,
                    // 跳转后整段褪色高亮：只在「当前章节」转发；alpha.value 的读取**故意不**
                    // 在这里展开 —— 读取被推迟到 [PageCanvas] 内的 Canvas DrawScope，
                    // 让动画每帧仅触发 Phase 3（绘制）重跑，不触发 Phase 1（重组）/
                    // Phase 2（布局）。这是 Compose 现代实践绕开"重组风暴"的关键
                    // —— 旧时代 ObjectAnimator + invalidate() 会把整棵子树重画。
                    revealHighlight = revealHighlight?.takeIf { it.chapterIndex == chapterIndex },
                    onCurrentPageChanged = {},
                    onSelectionStartMove = { textPos ->
                        selectedTextPage = coordinator.getPageAt(pageIndex)
                        selectionState.selectStartMove(textPos)
                    },
                    onSelectionEndMove = { textPos ->
                        selectedTextPage = coordinator.getPageAt(pageIndex)
                        selectionState.selectEndMove(textPos)
                    },
                )
            }
            // ── SIMULATION 模式选区/cursor overlay ──
            //
            // SimulationReadView 把整页烘成 bitmap 跑贝塞尔，PageContentBox 的实时
            // selection 渲染 + CursorHandle 在这条路径上不会被调用。这里在
            // AnimatedPageReader 之上叠一个 fillMaxSize 的 Compose overlay，专门负责：
            //   - 选区背景矩形（每行一段，颜色 = 主题 selectionColor）
            //   - 双 CursorHandle（拖动时调 selectionState.selectStartMove/EndMove）
            //
            // selectionState.startPos==null 或 endPos==null 时 overlay 自身 early-return
            // 不渲染任何东西，所以非 SIMULATION + 非选区状态下零开销。
            if (pageAnimType == PageAnimType.SIMULATION) {
                com.morealm.app.ui.reader.page.animation.SimulationSelectionOverlay(
                    selectionState = selectionState,
                    currentPage = coordinator.getPageAt(
                        coordinator.lastSettledDisplayPage.coerceIn(0, safeDisplayMax),
                    ),
                    selectionColor = readerTheme.selectionColor,
                    onSelectionStartMove = { textPos ->
                        selectedTextPage = coordinator.getPageAt(
                            coordinator.lastSettledDisplayPage.coerceIn(0, safeDisplayMax),
                        )
                        selectionState.selectStartMove(textPos)
                    },
                    onSelectionEndMove = { textPos ->
                        selectedTextPage = coordinator.getPageAt(
                            coordinator.lastSettledDisplayPage.coerceIn(0, safeDisplayMax),
                        )
                        selectionState.selectEndMove(textPos)
                    },
                )
            }
        }

        if (pages.isEmpty() && chapter?.isCompleted != true) {
            // Diagnostic [3f] — 这个 fallback 在 Box 内、if/else 分支之后，
            // 不论 pageAnimType 都会评估；激活时会在 SimulationReadView 之上
            // 层叠渲染 TextPage(title = chapterTitle) = 大字居中章节标题，
            // 这是「切换上下→仿真先闪 B 第一页」的最可能源头。
            // 切换瞬间 coordinator 重建 → pages 短暂为 empty → 本分支激活
            // 1 帧 → 下一帧 pages 填回 → 消失。日志里捕到这条就实锤。
            AppLog.debug(
                "PageTurnFlicker",
                "[3f] FALLBACK PageContentBox(title=\"$chapterTitle\")" +
                    " pages.isEmpty=true chapter.isCompleted=${chapter?.isCompleted}" +
                    " pageAnimType=$pageAnimType",
            )
            PageContentBox(
                page = TextPage(title = chapterTitle),
                pageIndex = 0,
                currentPage = 0,
                backgroundColor = backgroundColor,
                selectionState = selectionState,
                chapterTitle = chapterTitle,
                pageCount = 1,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                currentTime = currentTime,
                textColor = textColor,
                paddingHorizontal = paddingHorizontal,
                headerLeft = headerLeft,
                headerCenter = headerCenter,
                headerRight = headerRight,
                footerLeft = footerLeft,
                footerCenter = footerCenter,
                footerRight = footerRight,
                showChapterName = showChapterName,
                showTimeBattery = showTimeBattery,
                onCurrentPageChanged = { page -> onVisiblePageChanged(page.chapterIndex, page.title, page.readProgress, page.chapterPosition) },
                onSelectionStartMove = { textPos ->
                    selectedTextPage = TextPage(title = chapterTitle)
                    selectionState.selectStartMove(textPos)
                },
                onSelectionEndMove = { textPos ->
                    selectedTextPage = TextPage(title = chapterTitle)
                    selectionState.selectEndMove(textPos)
                },
            )
        }

        // Selection toolbar
        val currentDisplayForSelection = if (pageAnimType == PageAnimType.SIMULATION) {
            coordinator.lastSettledDisplayPage.coerceIn(0, safeDisplayMax)
        } else {
            pagerState.currentPage
        }
        // ── 滚动 / 翻页时自动收掉浮层 —— 杀「僵尸菜单」 ──
        //
        // 旧实现：toolbar 是手画 Box 嵌在 reader 主层里，用户在浮层显示中翻页 /
        // 滚动时 Column.offset 还按老 chapterPosition 算位置，菜单卡屏 + 跟着内容
        // 飘是肉眼可见的体验问题。改成 Popup 后浮层在自己的 window，但状态机
        // (selectionState / highlightActionTarget) 还活着，所以仍需明确触发清除。
        //
        // 同时清两个浮层：selectionState（编辑选区）和 highlightActionTarget（已存
        // 高亮的删除/分享 action menu），逻辑一致。
        val pagerScrolling = pagerState.isScrollInProgress
        LaunchedEffect(pagerScrolling, scrollInProgress) {
            if (pagerScrolling || scrollInProgress) {
                if (selectionState.isActive) selectionState.clear()
                if (highlightActionTarget != null) highlightActionTarget = null
            }
        }
        ReaderSelectionToolbar(
            selectionState = selectionState,
            toolbarOffset = toolbarOffset,
            page = selectedTextPage ?: coordinator.getPageAt(currentDisplayForSelection),
            relativePageProvider = { relativePos ->
                when (pageAnimType) {
                    PageAnimType.SCROLL -> scrollRelativePages[relativePos] ?: selectedTextPage?.takeIf {
                        relativePos == selectionState.startPos?.relativePagePos ||
                            relativePos == selectionState.endPos?.relativePagePos
                    }
                    else -> coordinator.getRelativePage(currentDisplayForSelection, relativePos)
                }
            },
            onCopyText = onCopyText,
            onSpeakFromHere = onSpeakFromHere,
            onTranslateText = onTranslateText,
            onLookupWord = onLookupWord,
            onShareQuote = { text -> shareQuoteText = text },
            onAddHighlight = { start, end, text, argb -> onAddHighlight(start, end, text, argb) },
            // 橡皮：仅当外层 CanvasRenderer 注入了 onEraseHighlight 才透传，
            // 否则保持 null —— SelectionToolbar 会自动隐藏橡皮按钮。
            onEraseHighlight = onEraseHighlight,
            // 字体色：和 onAddHighlight 同样的传递逻辑；wrapper 内部把 selection
            // 转成 chapter-pos 范围再回调。
            onAddTextColor = onAddTextColor,
            menuConfig = selectionMenuConfig,
        )

        // Quote share dialog
        if (shareQuoteText != null) {
            com.morealm.app.ui.reader.QuoteShareDialog(
                quoteText = shareQuoteText!!,
                bookTitle = bookTitle,
                author = bookAuthor,
                accentColor = accentColor,
                backgroundColor = backgroundColor,
                onDismiss = { shareQuoteText = null },
            )
        }

        // 已存高亮的「删除 / 分享」action menu — 与选区 toolbar 互斥（一旦点中
        // 已存高亮就清掉选区，避免两个浮层叠在一起）。点击 action 调用回调，
        // 调用方负责持久化操作（删除走 ReaderViewModel.highlight.delete，
        // 分享走 HighlightShareCard.shareAsImage）。
        highlightActionTarget?.let { target ->
            HighlightActionToolbar(
                offset = highlightActionOffset,
                colorArgb = target.colorArgb,
                onDelete = {
                    com.morealm.app.core.log.AppLog.info("Highlight",
                        "user delete via action menu id=${target.id} chIdx=${target.chapterIndex} " +
                            "range=${target.startChapterPos}..${target.endChapterPos} contentLen=${target.content.length}",
                    )
                    onDeleteHighlight(target.id)
                    highlightActionTarget = null
                },
                onShare = {
                    com.morealm.app.core.log.AppLog.info("Highlight",
                        "user share via action menu id=${target.id} chIdx=${target.chapterIndex} contentLen=${target.content.length}",
                    )
                    onShareHighlight(target)
                    highlightActionTarget = null
                },
                onDismiss = { highlightActionTarget = null },
            )
        }
    }
    } // ← 关闭 L~1136 处由 readerTheme 注入打开的 CompositionLocalProvider 作用域
}

// ══════════════════════════════════════════════════════════════
// ReaderInfoBar — configurable header/footer with 6 slots
// Ported from Legado PageView + ReadTipConfig + BatteryView
// ══════════════════════════════════════════════════════════════

/**
 * Slot content types (matching Legado ReadTipConfig):
 * "none", "chapter", "time", "battery", "battery_pct", "page",
 * "progress", "page_progress", "book_name", "time_battery", "battery_time", "time_battery_pct"
 */
@Composable
private fun ReaderInfoBar(
    slotLeft: String,
    slotCenter: String,
    slotRight: String,
    chapterTitle: String,
    pageIndex: Int,
    pageCount: Int,
    currentPage: TextPage? = null,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    /** 是否正在充电；为 true 时 [BatteryIcon] 上叠加一道闪电小象形。默认 false。 */
    batteryCharging: Boolean = false,
    currentTime: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    // If all slots are "none", don't render anything
    if (slotLeft == "none" && slotCenter == "none" && slotRight == "none") return

    val tipColor = textColor.copy(alpha = 0.4f)
    val tipStyle = TextStyle(color = tipColor, fontSize = 10.sp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left slot
        if (slotLeft != "none") {
            InfoSlotContent(
                slot = slotLeft,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
                currentPage = currentPage,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                currentTime = currentTime,
                tipColor = tipColor,
                tipStyle = tipStyle,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (slotLeft != "none" && (slotCenter != "none" || slotRight != "none")) {
            Spacer(Modifier.weight(1f))
        }
        // Center slot
        if (slotCenter != "none") {
            InfoSlotContent(
                slot = slotCenter,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
                currentPage = currentPage,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                currentTime = currentTime,
                tipColor = tipColor,
                tipStyle = tipStyle,
            )
        }
        if (slotRight != "none" && (slotLeft != "none" || slotCenter != "none")) {
            Spacer(Modifier.weight(1f))
        }
        // Right slot
        if (slotRight != "none") {
            InfoSlotContent(
                slot = slotRight,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
                currentPage = currentPage,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
                currentTime = currentTime,
                tipColor = tipColor,
                tipStyle = tipStyle,
            )
        }
    }
}

@Composable
private fun PageReaderInfoOverlay(
    pages: List<TextPage>,
    onCurrentPageChanged: (TextPage) -> Unit = {},
    pageIndex: Int,
    pageCount: Int,
    chapterTitle: String,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    /** 是否正在充电；为 true 时 [BatteryIcon] 上叠加一道闪电小象形。默认 false。 */
    batteryCharging: Boolean = false,
    currentTime: String,
    textColor: Color,
    backgroundColor: Color,
    paddingHorizontal: Int,
    showChapterName: Boolean,
    showTimeBattery: Boolean,
    headerLeft: String,
    headerCenter: String,
    headerRight: String,
    footerLeft: String,
    footerCenter: String,
    footerRight: String,
    hasBgImage: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val safePageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val currentPage = pages.getOrNull(safePageIndex) ?: pages.firstOrNull()
        val actualPageIndex = currentPage?.index ?: safePageIndex
        val actualPageCount = currentPage?.pageSize?.takeIf { it > 0 } ?: pageCount
        // 自动分节伪标题（"第N节" / "正文"）退回到外层 chapterTitle —— ReaderScreen 已用
        // [BookChapter.displayTitle] 把它换成书名。
        val actualChapterTitle = currentPage?.title
            ?.takeIf { it.isNotBlank() && !it.looksLikeAutoSplitTitle() }
            ?: chapterTitle
        val actualChapterIndex = currentPage?.chapterIndex ?: chapterIndex
        LaunchedEffect(currentPage) {
            currentPage?.let(onCurrentPageChanged)
        }
        ReaderInfoBar(
            slotLeft = if (showTimeBattery) headerLeft else "none",
            slotCenter = if (showChapterName) headerCenter else "none",
            slotRight = if (showTimeBattery) headerRight else "none",
            chapterTitle = actualChapterTitle,
            pageIndex = actualPageIndex,
            pageCount = actualPageCount,
            currentPage = currentPage,
            chapterIndex = actualChapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
            currentTime = currentTime,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(64.dp)
                .then(
                    if (hasBgImage) Modifier
                    else Modifier.background(
                        Brush.verticalGradient(
                            0f to backgroundColor,
                            0.72f to backgroundColor,
                            1f to backgroundColor.copy(alpha = 0f),
                        )
                    )
                )
                .padding(horizontal = paddingHorizontal.dp, vertical = 8.dp),
        )
        ReaderInfoBar(
            slotLeft = if (showChapterName) footerLeft else "none",
            slotCenter = footerCenter,
            slotRight = footerRight,
            chapterTitle = actualChapterTitle,
            pageIndex = actualPageIndex,
            pageCount = actualPageCount,
            currentPage = currentPage,
            chapterIndex = actualChapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
            currentTime = currentTime,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(64.dp)
                .then(
                    if (hasBgImage) Modifier
                    else Modifier.background(
                        Brush.verticalGradient(
                            0f to backgroundColor.copy(alpha = 0f),
                            0.28f to backgroundColor,
                            1f to backgroundColor,
                        )
                    )
                )
                .padding(horizontal = paddingHorizontal.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InfoSlotContent(
    slot: String,
    chapterTitle: String,
    pageIndex: Int,
    pageCount: Int,
    currentPage: TextPage? = null,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    /** 是否正在充电；为 true 时 [BatteryIcon] 上叠加一道闪电小象形。默认 false。 */
    batteryCharging: Boolean = false,
    currentTime: String,
    tipColor: Color,
    tipStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    when (slot) {
        "chapter" -> Text(
            chapterTitle,
            style = tipStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        "time" -> Text(currentTime, style = tipStyle, modifier = modifier)
        "battery" -> BatteryIcon(batteryLevel, tipColor, charging = batteryCharging, modifier = modifier)
        "battery_pct" -> Text("$batteryLevel%", style = tipStyle, modifier = modifier)
        "page" -> Text("${pageIndex + 1}/$pageCount", style = tipStyle, modifier = modifier)
        "progress" -> {
            val readProgress = currentPage?.readProgress?.takeIf { it.isNotBlank() }
            if (readProgress != null) {
                Text(readProgress, style = tipStyle, modifier = modifier)
                return
            }
            val pct = if (pageCount > 1) {
                (pageIndex.toFloat() / (pageCount - 1) * 100)
            } else 100f
            Text("%.1f%%".format(pct), style = tipStyle, modifier = modifier)
        }
        "page_progress" -> {
            val readProgress = currentPage?.readProgress?.takeIf { it.isNotBlank() } ?: "0.0%"
            val actualPageSize = currentPage?.pageSize?.takeIf { it > 0 } ?: pageCount
            Text("${pageIndex + 1}/$actualPageSize  $readProgress", style = tipStyle, modifier = modifier)
        }
        "book_name" -> Text("MoRealm", style = tipStyle, modifier = modifier)
        "time_battery" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            Text(currentTime, style = tipStyle)
            Spacer(Modifier.width(6.dp))
            BatteryIcon(batteryLevel, tipColor, charging = batteryCharging)
        }
        "battery_time" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            BatteryIcon(batteryLevel, tipColor, charging = batteryCharging)
            Spacer(Modifier.width(6.dp))
            Text(currentTime, style = tipStyle)
        }
        "time_battery_pct" -> Text(
            "$currentTime  $batteryLevel%",
            style = tipStyle,
            modifier = modifier,
        )
        "chapter_progress" -> {
            if (chaptersSize > 0) {
                Text("${chapterIndex + 1}/$chaptersSize", style = tipStyle, modifier = modifier)
            }
        }
    }
}

/**
 * Battery icon drawn with Compose Canvas (ported from Legado BatteryView).
 * Draws a small battery outline with fill level.
 *
 * 充电态 ([charging]==true) 时在电池本体上叠加一道小闪电（实心填充），
 * 用反差色 —— "电池本体外壳同色"在浅色 fill 上看不清，所以闪电的描边用
 * [color]，内填用半透明白色让黑色 fill 区域里也能看见拐角。
 *
 * 路径手画了一个标准闪电 7 顶点：左上 → 右上 → 中央右 → 右下 → 左下 → 中央左 → 闭合。
 * 比例参考材料 1.5 充电图标，按 bodyW × bodyH 的 60% 居中放置。
 */
@Composable
private fun BatteryIcon(
    level: Int,
    color: Color,
    charging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val w = 22.dp
    val h = 11.dp
    androidx.compose.foundation.Canvas(
        modifier = modifier.size(w, h),
    ) {
        val strokeW = 1.dp.toPx()
        val capW = 2.dp.toPx()
        val bodyW = size.width - capW - strokeW
        val bodyH = size.height
        val inset = strokeW / 2

        // Battery body outline
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(bodyW - strokeW, bodyH - strokeW),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
        )
        // Battery cap (positive terminal)
        val capH = bodyH * 0.4f
        val capY = (bodyH - capH) / 2
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(bodyW, capY),
            size = androidx.compose.ui.geometry.Size(capW, capH),
        )
        // Fill level
        val fillInset = strokeW + 1.dp.toPx()
        val fillW = (bodyW - fillInset * 2) * (level.coerceIn(0, 100) / 100f)
        if (fillW > 0) {
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(fillInset, fillInset),
                size = androidx.compose.ui.geometry.Size(fillW, bodyH - fillInset * 2),
            )
        }

        if (charging) {
            // 闪电小象形：放在电池本体居中位置；尺寸取本体的 0.6×0.7。
            val boltW = (bodyW - strokeW * 2) * 0.45f
            val boltH = (bodyH - strokeW * 2) * 0.85f
            val cx = (bodyW - strokeW) / 2f
            val cy = bodyH / 2f
            val left = cx - boltW / 2f
            val top = cy - boltH / 2f
            val path = androidx.compose.ui.graphics.Path().apply {
                // (x, y) using fractions of boltW / boltH
                moveTo(left + boltW * 0.55f, top)                          // 顶部尖
                lineTo(left + boltW * 0.00f, top + boltH * 0.55f)          // 左中下
                lineTo(left + boltW * 0.45f, top + boltH * 0.55f)          // 中央左
                lineTo(left + boltW * 0.30f, top + boltH * 1.00f)          // 底部尖
                lineTo(left + boltW * 1.00f, top + boltH * 0.40f)          // 右中
                lineTo(left + boltW * 0.55f, top + boltH * 0.40f)          // 中央右
                close()
            }
            // 反差色填充：用 surface（白/黑随主题），透明度 0.95；这样落在 fill 区
            // 里也清晰，落在空电区里也能看见。
            drawPath(
                path = path,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f),
            )
            // 描一道与电池本体同色的细边，帮在浅色背景下勾出形状。
            drawPath(
                path = path,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.6.dp.toPx()),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Extracted composables from CanvasRenderer
// ══════════════════════════════════════════════════════════════

/**
 * Reader 底部信息栏的电量状态快照。
 *
 * - [level] 0..100；从 [BatteryManager.EXTRA_LEVEL] / [BatteryManager.EXTRA_SCALE] 算出。
 * - [charging] 是否正在充电；用 [BatteryManager.EXTRA_STATUS] == CHARGING/FULL 判断
 *   （等价 EXTRA_PLUGGED!=0，但 STATUS 更精准：插上但未在充电的极少数情况会被
 *   过滤）。
 *
 * 在 [BatteryIcon] 上画的小闪电就由这个 charging 决定显示。
 */
private data class BatteryStatus(val level: Int, val charging: Boolean)

/**
 * Observes battery level + charging state via a sticky broadcast receiver.
 * Returns a [MutableState] that updates whenever the system reports a new level.
 */
@Composable
private fun rememberBatteryStatus(context: Context): MutableState<BatteryStatus> {
    val state = remember { mutableStateOf(BatteryStatus(level = 100, charging = false)) }
    DisposableEffect(context) {
        fun parse(intent: Intent): BatteryStatus? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0) return null
            val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusInt == BatteryManager.BATTERY_STATUS_FULL
            return BatteryStatus(level = (level * 100) / scale.coerceAtLeast(1), charging = charging)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                parse(intent)?.let { state.value = it }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter)
        // Read initial value from sticky intent
        if (sticky != null) parse(sticky)?.let { state.value = it }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/**
 * Observes battery level via a sticky broadcast receiver.
 * Returns a [MutableState] that updates whenever the system reports a new level.
 *
 * 兼容入口 — 老代码只关心数字百分比，新代码请用 [rememberBatteryStatus]。
 */
@Composable
private fun rememberBatteryLevel(context: Context): MutableState<Int> {
    val state = remember { mutableIntStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0) state.intValue = (level * 100) / scale
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter)
        // Read initial value from sticky intent
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0) state.intValue = (level * 100) / scale
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/**
 * A single page's content: the Canvas-drawn text plus header/footer info bars.
 *
 * # Phase B P1 #5 签名瘦身
 *
 * 旧签名 4 个 theme 入参（titlePaint / contentPaint / chapterNumPaint / bgBitmap）
 * 全部下沉到 [LocalReaderRenderTheme]，调用方不再写。本组件内部也不再透传给
 * [PageCanvas]（PageCanvas P1 #4 已自取 theme），仅在 info bar 处需要 `bgBitmap`
 * 判断是否走透明背景，故在函数体内一句 `theme.bgBitmap` 取出即可。
 *
 * 主体外层 [com.morealm.app.ui.reader.renderer.CanvasRenderer] 已用
 * `CompositionLocalProvider(LocalReaderRenderTheme provides readerTheme)` 包住整片
 * UI 子树，本函数所有调用点都在该作用域内，不需要每次再重新 provide。
 */
@Composable
private fun PageContentBox(
    page: TextPage,
    pageIndex: Int,
    currentPage: Int,
    backgroundColor: Color,
    selectionState: SelectionState,
    chapterTitle: String,
    pageCount: Int,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    /** 是否正在充电；为 true 时 [BatteryIcon] 上叠加一道闪电小象形。默认 false。 */
    batteryCharging: Boolean = false,
    currentTime: String,
    textColor: Color,
    paddingHorizontal: Int,
    headerLeft: String,
    headerCenter: String,
    headerRight: String,
    footerLeft: String,
    footerCenter: String,
    footerRight: String,
    showChapterName: Boolean,
    showTimeBattery: Boolean,
    autoPageOverlayProgress: Int = 0,
    autoPageNextPage: TextPage? = null,
    autoPageAccentColor: Color = Color.Transparent,
    /** TTS 当前段位置；透传给 PageCanvas 触发重组以刷新高亮。-1 = 非朗读态。 */
    readAloudChapterPosition: Int = -1,
    /**
     * 当前章节的所有用户高亮（按 chapter-position 区间存）。每个 PageContentBox
     * 在画自己这页时只取与本页 `chapterPosition .. chapterPosition + page.charSize`
     * 有交集的那批，避免每页都遍历整章。
     */
    chapterHighlights: List<HighlightSpan> = emptyList(),
    /**
     * 当前章节的字体强调色 spans（kind=1）。语义同 [chapterHighlights]，每页只取
     * 有交集的子集传给 [PageCanvas]。
     */
    chapterTextColorSpans: List<HighlightSpan> = emptyList(),
    /**
     * 跳转后整段褪色高亮的状态对象（已由上层做了"同章"过滤）。
     *
     * **故意不**在 PageContentBox 这层读 `alpha.value` —— 那样会让 alpha 动画
     * 每一帧把本 Composable 树重组一遍。read 操作下沉到 [PageCanvas] 内部
     * `Canvas { ... }` 的 DrawScope，让 Compose 把它登记为绘制阶段（Phase 3）依赖：
     * alpha 变化 → 仅触发本 Canvas 重画，不触发重组 / 重布局。
     */
    revealHighlight: RevealHighlight? = null,
    onCurrentPageChanged: (TextPage) -> Unit = {},
    onSelectionStartMove: (TextPos) -> Unit = {},
    onSelectionEndMove: (TextPos) -> Unit = {},
) {
    fun cursorOffsetFor(textPos: TextPos?, startHandle: Boolean): Offset? {
        val pos = textPos?.takeIf { it.relativePagePos == 0 } ?: return null
        val line = page.lines.getOrNull(pos.lineIndex) ?: return null
        if (line.columns.isEmpty()) return null
        val columnIndex = pos.columnIndex.coerceIn(0, line.columns.lastIndex)
        val column = line.columns[columnIndex]
        val x = when {
            startHandle && pos.columnIndex < line.columns.size -> column.start
            startHandle -> column.end
            pos.columnIndex >= 0 -> column.end
            else -> column.start
        }
        return Offset(x, line.lineBottom + page.paddingTop)
    }

    /**
     * Diagnostic-only sibling — surfaces which short-circuit branch hid the
     * cursor handle so CursorHandleTrace can report the failing step without
     * adding logging to the hot recompose path inside cursorOffsetFor itself.
     *
     * Possible values: null-input / filtered-relPos<N> / no-line-at-<i>(lines=N)
     * / empty-columns / ok
     */
    fun cursorReasonFor(textPos: TextPos?): String {
        if (textPos == null) return "null-input"
        if (textPos.relativePagePos != 0) return "filtered-relPos${textPos.relativePagePos}"
        val line = page.lines.getOrNull(textPos.lineIndex)
            ?: return "no-line-at-${textPos.lineIndex}(lines=${page.lines.size})"
        if (line.columns.isEmpty()) return "empty-columns"
        return "ok"
    }

    val isCurrentDisplayPage = pageIndex == currentPage
    // Phase B P1 #5：唯一一处仍需 theme 字段的地方是 info bar 的 hasBgImage 判断；
    // PageCanvas（已 P1 #4 瘦身）不再需要外部传 paint/bg，自取 LocalReaderRenderTheme。
    val bgBitmap = LocalReaderRenderTheme.current.bgBitmap
    // Filter chapter-wide highlight set down to those overlapping THIS page's
    // character range. Cheap (O(highlights × 1)); avoids re-scanning unrelated
    // ranges inside the per-line draw loop.
    val pageStart = page.chapterPosition
    val pageEnd = pageStart + page.lines.sumOf { it.charSize }
    val pageHighlights = if (chapterHighlights.isEmpty()) emptyList() else
        chapterHighlights.filter { it.startChapterPos < pageEnd && it.endChapterPos > pageStart }
    val pageTextColorSpans = if (chapterTextColorSpans.isEmpty()) emptyList() else
        chapterTextColorSpans.filter { it.startChapterPos < pageEnd && it.endChapterPos > pageStart }
    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        // Diagnostic: trace selection cursor handle visibility for non-SCROLL modes.
        // Logged once per state digest change (LaunchedEffect). Tells us:
        //   • whether a selection actually exists (selStart/selEnd present)
        //   • whether THIS PageContentBox is the displayed page (isCurrentDisplayPage)
        //   • why cursorOffsetFor would return null (cursorReasonFor result)
        //   • the resolved screen offsets of both handles (null = won't render)
        // Note: pageIndex/currentPage tells us if a wrong-page mismatch is in play
        // (the SIMULATION-mode `pagerState.currentPage=0` problem analyzed earlier).
        val pcStartReason = cursorReasonFor(selectionState.startPos)
        val pcEndReason = cursorReasonFor(selectionState.endPos)
        val pcStartOff = cursorOffsetFor(selectionState.startPos, startHandle = true)
        val pcEndOff = cursorOffsetFor(selectionState.endPos, startHandle = false)
        val pcDigest = "pageIdx=$pageIndex currentPage=$currentPage" +
            " isCurDisp=$isCurrentDisplayPage" +
            " selStart=${selectionState.startPos} selEnd=${selectionState.endPos}" +
            " startReason=$pcStartReason endReason=$pcEndReason" +
            " startOff=$pcStartOff endOff=$pcEndOff" +
            " page.lines.size=${page.lines.size} page.chPos=${page.chapterPosition}"
        LaunchedEffect(pcDigest) {
            if (selectionState.startPos != null || selectionState.endPos != null) {
                AppLog.debug("CursorHandleTrace", "PageContentBox cursor | $pcDigest")
            }
        }
        // Phase B P1 #4：PageCanvas 8 个 theme 字段（3 paint + bgBitmap + 4 高亮色）
        // 已迁到 LocalReaderRenderTheme，调用方不再写主题字段。本调用栈外层
        // CompositionLocalProvider(LocalReaderRenderTheme provides readerTheme)
        // 已在 CanvasRenderer 主体（L~1196）注入。
        PageCanvas(
            page = page,
            selectionStart = if (isCurrentDisplayPage) {
                selectionState.startPos?.takeIf { it.relativePagePos == 0 }
            } else null,
            selectionEnd = if (isCurrentDisplayPage) {
                selectionState.endPos?.takeIf { it.relativePagePos == 0 }
            } else null,
            readAloudChapterPosition = readAloudChapterPosition,
            highlights = pageHighlights,
            textColorSpans = pageTextColorSpans,
            // reveal 不在这层做 page-bounds 过滤；交给 PageCanvas 在 DrawScope 里
            // 一起判断 + 读 alpha.value，把"渲染决策"集中在绘制阶段。
            revealHighlight = revealHighlight,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrentDisplayPage) {
            val startOffset = cursorOffsetFor(selectionState.startPos, startHandle = true)
            val endOffset = cursorOffsetFor(selectionState.endPos, startHandle = false)
            val handlesTooClose = startOffset != null && endOffset != null &&
                abs(startOffset.x - endOffset.x) < 28f &&
                abs(startOffset.y - endOffset.y) < 28f
            startOffset?.let { offset ->
                val adjustedOffset = if (handlesTooClose) offset.copy(y = offset.y - 18f) else offset
                CursorHandle(position = adjustedOffset, onDrag = { dragOffset ->
                    hitTestPageRough(page, dragOffset.x, dragOffset.y)?.let(onSelectionStartMove)
                })
            }
            endOffset?.let { offset ->
                val adjustedOffset = if (handlesTooClose) offset.copy(y = offset.y + 18f) else offset
                CursorHandle(position = adjustedOffset, onDrag = { dragOffset ->
                    hitTestPageRough(page, dragOffset.x, dragOffset.y)?.let(onSelectionEndMove)
                })
            }
        }
        if (autoPageOverlayProgress > 0 && autoPageNextPage != null && isCurrentDisplayPage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { autoPageOverlayProgress.toDp() })
                    .align(Alignment.TopStart),
            ) {
                PageCanvas(
                    page = autoPageNextPage,
                    selectionStart = null,
                    selectionEnd = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopStart)
                    .offset(y = with(LocalDensity.current) { autoPageOverlayProgress.toDp() })
                .background(autoPageAccentColor),
            )
        }
        PageReaderInfoOverlay(
            pages = listOf(page),
            onCurrentPageChanged = if (isCurrentDisplayPage && page.chapterIndex == chapterIndex) onCurrentPageChanged else { _ -> },
            pageIndex = 0,
            pageCount = page.pageSize.takeIf { it > 0 } ?: pageCount,
            backgroundColor = backgroundColor,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
                batteryCharging = batteryCharging,
            currentTime = currentTime,
            textColor = textColor,
            paddingHorizontal = paddingHorizontal,
            showChapterName = showChapterName,
            showTimeBattery = showTimeBattery,
            headerLeft = headerLeft,
            headerCenter = headerCenter,
            headerRight = headerRight,
            footerLeft = footerLeft,
            footerCenter = footerCenter,
            footerRight = footerRight,
            hasBgImage = bgBitmap != null,
        )
    }
}

/**
 * Shows the floating selection toolbar (copy / speak / lookup) when text is selected.
 * Automatically dismisses selection on any action.
 */
@Composable
private fun ReaderSelectionToolbar(
    selectionState: SelectionState,
    toolbarOffset: Offset,
    page: TextPage?,
    relativePageProvider: (Int) -> TextPage? = { page },
    onCopyText: (String) -> Unit,
    onSpeakFromHere: (Int) -> Unit,
    onTranslateText: (String) -> Unit,
    onLookupWord: (String) -> Unit,
    onShareQuote: (String) -> Unit,
    /**
     * 选中后用户挑色面板里的色 → 保存高亮。回调收到本次选区的章节字符 offset
     * 范围与原文，由 ReaderViewModel 落到 DB；toolbar 调用后会自动 clear()。
     */
    onAddHighlight: ((start: Int, end: Int, content: String, colorArgb: Int) -> Unit)? = null,
    /**
     * 橡皮擦回调（可选）。非 null 时调色板左侧露出橡皮按钮；点击后本 wrapper
     * 把选区的 chapter-pos 范围抽出来转给上层（→ ReaderViewModel.highlight.eraseInRange）。
     * 选区在调用后会自动 clear。
     */
    onEraseHighlight: ((start: Int, end: Int) -> Unit)? = null,
    /**
     * 字体强调色回调，参考 [onAddHighlight]，wrapper 把 selectionState 转 chapter-pos
     * 范围转给上层（→ ReaderHighlightController.add(..., kind=1)）。选区在调用后
     * 自动 clear。
     */
    onAddTextColor: ((start: Int, end: Int, content: String, colorArgb: Int) -> Unit)? = null,
    /** 用户自定义按钮配置。透传给底层 [SelectionToolbar]。 */
    menuConfig: com.morealm.app.domain.entity.SelectionMenuConfig =
        com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT,
) {
    if (!selectionState.isActive) return

    fun selectedText(): String {
        val start = selectionState.startPos ?: return ""
        val end = selectionState.endPos ?: return ""
        val (actualStart, actualEnd) = if (start.compare(end) <= 0) start to end else end to start
        if (actualStart.relativePagePos == actualEnd.relativePagePos) {
            val textPage = relativePageProvider(actualStart.relativePagePos) ?: page ?: return ""
            return getSelectedText(textPage, actualStart, actualEnd)
        }
        val startPage = relativePageProvider(actualStart.relativePagePos) ?: return ""
        val endPage = relativePageProvider(actualEnd.relativePagePos) ?: return getSelectedText(
            startPage,
            actualStart,
            TextPos(actualStart.relativePagePos, startPage.lines.lastIndex, Int.MAX_VALUE),
        )
        val builder = StringBuilder()
        builder.append(
            getSelectedText(
                startPage,
                actualStart,
                TextPos(actualStart.relativePagePos, startPage.lines.lastIndex, Int.MAX_VALUE),
            ),
        )
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(getSelectedText(endPage, TextPos(actualEnd.relativePagePos, 0, -1), actualEnd))
        return builder.toString()
    }

    fun selectedStartChapterPosition(): Int {
        val textPage = page ?: return 0
        val start = selectionState.startPos ?: return textPage.chapterPosition
        val end = selectionState.endPos ?: start
        val actualStart = if (start.compare(end) <= 0) start else end
        val startPage = relativePageProvider(actualStart.relativePagePos) ?: textPage
        return startPage.chapterPosition + startPage.getPosByLineColumn(actualStart.lineIndex, actualStart.columnIndex)
    }

    /**
     * 选区结束位置在章节字符流里的 offset。和 [selectedStartChapterPosition]
     * 配对组成 Highlight 的 startChapterPos / endChapterPos。columnIndex 接受
     * Int.MAX_VALUE 表示行末（跨页选择时上半段就这么传）。
     *
     * **半开区间 [start, end) 语义**：endChapterPos 应当指向"最后一个被选中字符之后
     * 的位置"。[com.morealm.app.domain.render.TextPage.getPosByLineColumn] 对入参
     * `columnIndex` 的实现是"行内 0..columnIndex-1 的累计字符数"——所以传 `columnIndex`
     * 自身只到末选字符的"前缘"，丢了末字。修正：传 `columnIndex + 1` 让 end 跨过
     * 末选字符；这样一段选 N 个字得到的 endChapterPos - startChapterPos 正好等于 N。
     *
     * 影响面：highlight 背景渲染、字体强调色渲染（[PageContentDrawer.drawPageContent]
     * 用 `midPos < endChapterPos` 判定）、橡皮擦覆盖范围、以及上层
     * [com.morealm.app.presentation.reader.ReaderHighlightController.add] 落 DB 的
     * Highlight.endChapterPos。所有读这个值的地方都按半开区间约定来，统一修这一处。
     */
    fun selectedEndChapterPosition(): Int {
        val textPage = page ?: return 0
        val start = selectionState.startPos ?: return textPage.chapterPosition
        val end = selectionState.endPos ?: return textPage.chapterPosition
        val actualEnd = if (start.compare(end) <= 0) end else start
        val endPage = relativePageProvider(actualEnd.relativePagePos) ?: textPage
        // +1 让区间右端跨过末选字符（半开区间约定）。getPosByLineColumn 内部已对
        // columnIndex 做 coerceIn(0, columns.size) 兜底，传 lastIndex+1 = columns.size
        // 不会越界。
        return endPage.chapterPosition + endPage.getPosByLineColumn(actualEnd.lineIndex, actualEnd.columnIndex + 1)
    }

    SelectionToolbar(
        offset = toolbarOffset,
        onCopy = { onCopyText(selectedText()); selectionState.clear() },
        onSpeak = { onSpeakFromHere(selectedStartChapterPosition()); selectionState.clear() },
        onTranslate = { onTranslateText(selectedText()); selectionState.clear() },
        onShare = { onShareQuote(selectedText()); selectionState.clear() },
        onLookup = { onLookupWord(selectedText()); selectionState.clear() },
        onHighlight = onAddHighlight?.let { cb ->
            { argb ->
                val text = selectedText()
                val sStart = selectedStartChapterPosition()
                val sEnd = selectedEndChapterPosition()
                if (sEnd > sStart && text.isNotBlank()) {
                    cb(sStart, sEnd, text, argb)
                }
                selectionState.clear()
            }
        },
        // 橡皮：抽出当前选区的 chapter-pos 范围转给上层；范围非空才触发，
        // 选区在调用后立即 clear（和 onHighlight 行为一致）。
        onEraseHighlight = onEraseHighlight?.let { cb ->
            {
                val sStart = selectedStartChapterPosition()
                val sEnd = selectedEndChapterPosition()
                if (sEnd > sStart) {
                    cb(sStart, sEnd)
                }
                selectionState.clear()
            }
        },
        // 字体色：选完色后回调把 chapter-pos 范围 + 文本 + ARGB 转给上层落库
        // （kind=1）。选区也立即 clear，体验和点 highlight 调色板一致。
        onTextColor = onAddTextColor?.let { cb ->
            { argb ->
                val text = selectedText()
                val sStart = selectedStartChapterPosition()
                val sEnd = selectedEndChapterPosition()
                if (sEnd > sStart && text.isNotBlank()) {
                    cb(sStart, sEnd, text, argb)
                }
                selectionState.clear()
            }
        },
        onDismiss = { selectionState.clear() },
        config = menuConfig,
    )
}
