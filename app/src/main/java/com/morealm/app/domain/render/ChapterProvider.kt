package com.morealm.app.domain.render

import android.graphics.BitmapFactory
import android.graphics.Paint.FontMetrics
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.text.AppPattern
import com.morealm.epub.compat.BlockStyle
import com.morealm.epub.compat.StructuredChapterContent
import com.morealm.epub.layout.ZhLayout
import kotlin.math.roundToInt

/**
 * Layout engine: splits chapter text into pages with full text justification,
 * Chinese typography rules (ZhLayout), image layout, and paragraph indentation.
 *
 * Chapter typesetting engine, adapted for MoRealm's MVVM architecture.
 * This is a pure domain-layer class — no Compose/UI imports.
 */
class ChapterProvider(
    val viewWidth: Int,
    val viewHeight: Int,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    val textMeasure: TextMeasure,
    val paragraphIndent: String = "\u3000\u3000",
    val textFullJustify: Boolean = true,
    val titleMode: Int = 0,       // 0=left, 1=center, 2=hidden
    val isMiddleTitle: Boolean = false,
    /**
     * 章节标题对齐方式。0=左 / 1=中 / 2=右。
     *
     * 与 [titleMode] 解耦：titleMode 仅决定「是否隐藏标题」(== 2)，对齐由本字段控制。
     * 在 [setTypeText] 计算最后一行 / 中间行的 `startXOffset` 时用：
     *   - 0  → 0f
     *   - 1  → max(0, (visibleWidth - desiredWidth) / 2)
     *   - 2  → max(0, visibleWidth - desiredWidth)
     *
     * 仅在 isTitle==true 且 isChapterNum==false 时生效；isVolumeTitle / 整页空内容
     * 仍走旧的居中分支（避免视觉破坏）。
     */
    val titleAlign: Int = 0,
    val useZhLayout: Boolean = true,
    val lineSpacingExtra: Float = 1.2f,
    val paragraphSpacing: Int = 8,
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,
    val doublePage: Boolean = false,
    /** Paint for the chapter-number sub-line (smaller, accent-colored). Null = use titlePaint. */
    val chapterNumPaint: TextPaint? = null,
) {

    val visibleWidth: Int = viewWidth - paddingLeft - paddingRight
    val visibleHeight: Int = viewHeight - paddingTop - paddingBottom

    private val titlePaintTextHeight: Float = titlePaint.textHeight
    private val titlePaintFontMetrics: FontMetrics = titlePaint.fontMetrics
    private val chapterNumPaintTextHeight: Float = chapterNumPaint?.textHeight ?: titlePaintTextHeight
    private val chapterNumPaintFontMetrics: FontMetrics = chapterNumPaint?.fontMetrics ?: titlePaintFontMetrics
    private val contentPaintTextHeight: Float = contentPaint.textHeight
    private val contentPaintFontMetrics: FontMetrics = contentPaint.fontMetrics
    private val indentCharWidth: Float = contentPaint.measureText(INDENT_CHAR)
    private val drawPaddingTop: Int = if (paddingTop > 0) maxOf(0, paddingTop - titlePaintTextHeight.roundToInt() / 2) else 0

    private val imgPattern = Regex(IMG_PATTERN.pattern(), RegexOption.IGNORE_CASE)

    /**
     * Layout a chapter into pages (synchronous).
     * @return Fully laid-out TextChapter with all pages
     */
    fun layoutChapter(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int = 0,
        /**
         * 跳过章首标题块（chapter-num 小字 + 大标题 + 装饰横条）。
         *
         * 给本地 TXT 无目录自动分章场景用——[com.morealm.app.domain.parser.LocalBookParser.parseWithoutToc]
         * 把整本书按 10KB 切成 N 段「第N节」，每段被当独立章排版。如果每段都画标题块，
         * 用户翻页/滚动时会看到 N 次相同的「《书名》」（[com.morealm.app.domain.entity.displayTitle]
         * 已把伪章名替换成书名）—— 完全没区分度，且占据每页头部空间。
         *
         * 设为 true 时不画 isTitle/isChapterNum 行，全章直接从正文开始；info 状态栏
         * 仍由 [com.morealm.app.ui.reader.renderer.PageContentDrawer] 用 spec.chapterTitle
         * 单独绘制，所以书名一次性显示在状态栏不会丢失。
         */
        omitChapterTitleBlock: Boolean = false,
        /**
         * 排版方向。HORIZONTAL（默认）走原 layoutInternal；VERTICAL_RL 走
         * [layoutInternalVertical]——列从右到左、字从上到下，适合日文 / 古典中文。
         */
        readingDirection: ReadingDirection = ReadingDirection.HORIZONTAL,
    ): TextChapter {
        val textChapter = TextChapter(chapterIndex, title, chaptersSize, readingDirection)
        val textPages = arrayListOf<TextPage>()
        if (readingDirection.isVertical) {
            layoutInternalVertical(
                title, content, chapterIndex, chaptersSize, textChapter, textPages,
                omitChapterTitleBlock = omitChapterTitleBlock,
                direction = readingDirection,
            )
        } else {
            layoutInternal(
                title, content, chapterIndex, chaptersSize, textChapter, textPages,
                omitChapterTitleBlock = omitChapterTitleBlock,
            )
        }
        return textChapter
    }

    /**
     * 异步流式排版 — 参考 Legado TextChapterLayout。
     * 排完一页立即通过 Channel 发送，UI 可以立即显示第一页，无需等待整章排完。
     *
     * @param scope 协程作用域，用于启动排版协程
     * @param onPageReady 每排完一页的回调（在排版线程调用）
     * @param onCompleted 全部排完的回调
     * @param onError 排版异常回调
     * @return AsyncLayoutHandle，可用于取消排版和读取 Channel
     */
    fun layoutChapterAsync(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int = 0,
        scope: CoroutineScope,
        onPageReady: ((Int, TextPage) -> Unit)? = null,
        onCompleted: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
        /** 见 [layoutChapter] 同名参数。 */
        omitChapterTitleBlock: Boolean = false,
        /** 见 [layoutChapter] 同名参数。 */
        readingDirection: ReadingDirection = ReadingDirection.HORIZONTAL,
    ): AsyncLayoutHandle {
        val textChapter = TextChapter(chapterIndex, title, chaptersSize, readingDirection)
        val channel = Channel<TextPage>(Channel.UNLIMITED)

        val job = scope.launch(Dispatchers.Default) {
            // 关键：捕获本协程的 CoroutineContext 给 layoutInternal 用作取消检查。
            // layoutInternal 是非 suspend 函数（被 sync 路径 [layoutChapter] 共用），
            // 不能直接 currentCoroutineContext().ensureActive()。改成传 lambda 让
            // 内部循环周期检查；外部 cancel 时下一个检查点立刻抛 CancellationException
            // → 中断进一步 onPageReady 回调 → 阻断"已 cancel 的协程仍在向 Compose state
            // 写 placeholder 页"的回退-重发死循环（用户拖动滑块时 currentChapterKey
            // 高频变化，老实现下多个被 cancel 的 layoutInternal 仍旧把 pages=1
            // incomplete 倒灌，触发 publishCurTextChapter 在 pages=N completed=true 与
            // pages=1 incomplete 之间反弹）。
            val ctx = coroutineContext
            try {
                val textPages = arrayListOf<TextPage>()
                if (readingDirection.isVertical) {
                    layoutInternalVertical(
                        title, content, chapterIndex, chaptersSize,
                        textChapter, textPages, channel, onPageReady,
                        omitChapterTitleBlock = omitChapterTitleBlock,
                        cancelCheck = { ctx.ensureActive() },
                        direction = readingDirection,
                    )
                } else {
                    layoutInternal(
                        title, content, chapterIndex, chaptersSize,
                        textChapter, textPages, channel, onPageReady,
                        omitChapterTitleBlock = omitChapterTitleBlock,
                        cancelCheck = { ctx.ensureActive() },
                    )
                }
                channel.close()
                onCompleted?.invoke()
            } catch (e: Exception) {
                channel.close(e)
                if (e !is kotlinx.coroutines.CancellationException) {
                    onError?.invoke(e)
                }
            }
        }

        return AsyncLayoutHandle(textChapter, channel, job)
    }

    /**
     * 内部排版逻辑，同步和异步共用。
     * 当 channel 不为 null 时，每排完一页就发送到 channel。
     */
    private fun layoutInternal(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int,
        textChapter: TextChapter,
        textPages: ArrayList<TextPage>,
        channel: Channel<TextPage>? = null,
        onPageReady: ((Int, TextPage) -> Unit)? = null,
        omitChapterTitleBlock: Boolean = false,
        /**
         * 取消检查回调（async 路径必传，sync 路径不传 = no-op）。
         *
         * **为什么需要**：[layoutChapterAsync] 走 `scope.launch(Dispatchers.Default)`，
         * 当 caller 的 [LaunchedEffect] 因 key 变化（如 currentChapterKey 因滑块拖动重算）
         * 重启时，原协程被 cancel，但 [layoutInternal] 是非 suspend 同步代码，**不会自动
         * 检查取消**。结果是 cancelled 协程继续把 placeholder 页推给 [onPageReady] / channel
         * → CanvasRenderer 把 `pages=1, completed=false` 写回 Compose state → ProgressTrace
         * 0%↔100% 反弹 → reader UI 永不稳态。
         *
         * **调用点**：放在外层段落循环顶端 + [finalizePage] 顶端。前者保证还没排到的段落
         * 立即停手，后者保证不向 UI 倒灌 placeholder 页。`ensureActive()` 抛
         * [kotlinx.coroutines.CancellationException]，被外层 try/catch 静默吞掉
         * （`if (e !is CancellationException) onError`）。
         */
        cancelCheck: (() -> Unit)? = null,
    ) {
        val stringBuilder = StringBuilder()
        var absStartX = paddingLeft
        var durY = 0f
        textPages.add(TextPage())
        val floatArray = FloatArray(256)

        // 内部辅助：完成一页时的处理
        fun finalizePage(page: TextPage) {
            // 取消检查：如果协程已 cancel，立刻抛 CancellationException，不再触发
            // onPageReady / channel.trySend → 阻断「cancelled 协程仍在向 Compose
            // state 写 placeholder」的回退-重发死循环（详见 [cancelCheck] KDoc）。
            cancelCheck?.invoke()
            page.index = textPages.indexOf(page)
            page.chapterIndex = chapterIndex
            page.chapterSize = chaptersSize
            page.title = title
            page.doublePage = doublePage
            page.paddingTop = drawPaddingTop
            page.isCompleted = true
            page.textChapter = textChapter
            page.upLinesPosition()
            textChapter.addPage(page)
            channel?.trySend(page)
            onPageReady?.invoke(page.index, page)
        }

        // Parse content into paragraphs
        // P3-5b Phase 3 修复：content 含 BLOCK_STYLE_MARKER 时强制走 parseHtmlParagraphs
        // 让 marker 被解码。原 isHtml 仅检测 `<`/`<p>`/`<div>`/`<img>` 在 EPUB Heading 首块
        // 时 content 首字符是中文（如"简介"）→ isHtml=false → 走 else 分支跳过 marker decode。
        val isHtml = content.trimStart().let {
            it.startsWith("<") && (it.contains("<p") || it.contains("<div") || it.contains("<img"))
        } || content.contains(StructuredChapterContent.BLOCK_STYLE_MARKER)
        val rawParagraphs = if (isHtml) parseHtmlParagraphs(content) else {
            parsePlainTextParagraphs(content)
        }
        // EPUB 章首常有正文级 h1-h6 章节标题段（典型形如
        // `<h2 class="head1">第一章</h2><h2 class="head">小节名</h2>`）。ChapterProvider
        // 下面会用 chapter.title 自画一份「chapter num 小字 + 主标题大字 + 装饰横线」
        // 的好看标题块——这是我们刻意保留的视觉亮点；如果正文 h 段再渲染一次就是双份。
        //
        // 处理：把正文 paragraphs 里所有 isChapterTitle 段先剥掉，让正文只剩 image / 正文段。
        // 副作用：极少数书在正文中部用 h3 表达子标题也会被吃掉——可接受的折中。
        // stripDuplicateTitleSegments：再剥掉「正文开头被拆成多段普通 <p> 的 title 文本」
        // （如 EPUB 把「第五章」+「梁王的珠宝」分两段写）—— 自画章标块照画，避免重复。
        val paragraphs = stripDuplicateTitleSegments(
            rawParagraphs.filterNot { it.isChapterTitle },
            title,
        )
        // contentProvidesChapterTitle 仍走 firstOrNull 老语义：仅当正文**第一段**就是
        // 与 chapter.title 同名的纯文本（且未匹配上 h 段）时跳过自画——这是兼容那些
        // 直接把章名写在第一段 <p> 里、不用 h 标签的 EPUB 流派。
        val contentProvidesChapterTitle = paragraphs.firstOrNull()?.let { first ->
            first.isChapterTitle || isSameChapterTitle(first.text, title)
        } == true

        val pageCountBefore = textPages.size

        // Layout title — split into chapter-num line + title line (4.htm style)
        // omitChapterTitleBlock 为 true 时整段跳过：自动分章 TXT 不再画伪章名标题。
        // 等价于 titleMode==2 的"无标题"模式，但作用域只限本次调用，不污染全局偏好。
        if (titleMode != 2 && !contentProvidesChapterTitle && !omitChapterTitleBlock) {
            val titleParts = splitChapterNumAndTitle(title)
            val chapterNumText = titleParts.first   // e.g. "第一章" or null
            val titleText = titleParts.second        // e.g. "山边小村" or full title

            // 1) Chapter number sub-line (small accent text)
            if (chapterNumText != null && chapterNumPaint != null) {
                val result = setTypeText(
                    absStartX, durY, chapterNumText, textPages, stringBuilder,
                    chapterNumPaint, chapterNumPaintTextHeight, chapterNumPaintFontMetrics,
                    floatArray, isTitle = true, isChapterNum = true, forceLeftTitle = true,
                    emptyContent = paragraphs.isEmpty(),
                )
                absStartX = result.first
                durY = result.second
                if (textPages.last().lines.isNotEmpty()) {
                    textPages.last().lines.last().isParagraphEnd = true
                }
                stringBuilder.append("\n")
                // 4.htm: chapter-num margin-bottom: 0.5rem; use the smaller
                // chapter-number line height so the gap does not balloon with body text.
                durY += chapterNumPaintTextHeight * 0.20f
            }

            // 2) Title text (main color, medium weight)
            val titleLines = titleText.split("\n").filter { it.isNotBlank() }
            titleLines.forEach { text ->
                val result = setTypeText(
                    absStartX, durY, text, textPages, stringBuilder,
                    titlePaint, titlePaintTextHeight, titlePaintFontMetrics,
                    floatArray, isTitle = true, forceLeftTitle = true,
                    emptyContent = paragraphs.isEmpty(),
                )
                absStartX = result.first
                durY = result.second
            }
            // Mark the last title line for the decorative accent bar
            if (textPages.last().lines.isNotEmpty()) {
                textPages.last().lines.last().isTitleEnd = true
                textPages.last().lines.last().isParagraphEnd = true
            }
            stringBuilder.append("\n")
            // Reserve room for the decorative line and keep the title block compact.
            durY += (contentPaintTextHeight * 0.75f).coerceAtLeast(titleBottomSpacing.toFloat() * 0.5f)
        }

        // 检查排版过程中是否产生了新页（分页），如果有就 finalize 已完成的页
        fun flushCompletedPages() {
            // 除了最后一页（正在排版中），之前的页都已完成
            while (textChapter.pageSize < textPages.size - 1) {
                val idx = textChapter.pageSize
                finalizePage(textPages[idx])
            }
        }

        // Layout content paragraphs
        for (paragraph in paragraphs) {
            // 段落级取消检查：长章节一次有数百段，每段开始前查一次 — cancel 后
            // 不再继续排版下一段。配合 finalizePage 的检查双层防御。
            cancelCheck?.invoke()
            val para = paragraph.text
            if (paragraph.isBlankSeparator) {
                // 空行分隔段：占一行正文高的留白，不产字、不占 cp。仅页中生效——
                // 本页还没有任何行（页首）时吞掉，翻页后顶部悬空行没有排版意义
                // （传统排版规则同款）；落在页尾溢出时由下一段的翻页逻辑自然吃掉。
                if (textPages.last().lines.isNotEmpty()) {
                    durY += contentPaintTextHeight
                }
                continue
            }
            if (paragraph.isChapterTitle) {
                if (textPages.last().lines.isNotEmpty()) {
                    durY += titleTopSpacing.coerceAtLeast(paragraphSpacing).toFloat()
                }
                val paint = if (paragraph.isChapterNum && chapterNumPaint != null) chapterNumPaint else titlePaint
                val paintHeight = if (paragraph.isChapterNum && chapterNumPaint != null) chapterNumPaintTextHeight else titlePaintTextHeight
                val paintFontMetrics = if (paragraph.isChapterNum && chapterNumPaint != null) chapterNumPaintFontMetrics else titlePaintFontMetrics
                val r = setTypeText(
                    absStartX, durY, para, textPages, stringBuilder,
                    paint, paintHeight, paintFontMetrics,
                    floatArray,
                    isTitle = true,
                    isChapterNum = paragraph.isChapterNum,
                    forceLeftTitle = true,
                    blockStyle = paragraph.blockStyle,
                )
                absStartX = r.first
                durY = r.second
                if (textPages.last().lines.isNotEmpty()) {
                    if (paragraph.isChapterSubTitle) {
                        textPages.last().lines.last().isTitleEnd = true
                    }
                    textPages.last().lines.last().isParagraphEnd = true
                }
                stringBuilder.append("\n")
                durY += if (paragraph.isChapterNum) {
                    chapterNumPaintTextHeight * 0.20f
                } else {
                    (contentPaintTextHeight * 0.75f).coerceAtLeast(titleBottomSpacing.toFloat() * 0.5f)
                }
                flushCompletedPages()
                continue
            }

            val imgMatcher = imgPattern.find(para)
            if (imgMatcher != null) {
                var start = 0
                var matchResult = imgMatcher
                while (matchResult != null) {
                    val textBefore = para.substring(start, matchResult.range.first)
                    if (textBefore.isNotBlank()) {
                        val r = setTypeText(
                            absStartX, durY, textBefore, textPages, stringBuilder,
                            contentPaint, contentPaintTextHeight, contentPaintFontMetrics,
                            floatArray, isFirstLine = start == 0,
                            blockStyle = paragraph.blockStyle,
                        )
                        absStartX = r.first; durY = r.second
                    }
                    val src = matchResult.groupValues[1]
                    val r = setTypeImage(
                        src, absStartX, durY, textPages, contentPaintTextHeight, stringBuilder,
                    )
                    absStartX = r.first; durY = r.second
                    start = matchResult.range.last + 1
                    matchResult = imgPattern.find(para, start)
                }
                if (start < para.length) {
                    val remaining = para.substring(start)
                    if (remaining.isNotBlank()) {
                        val r = setTypeText(
                            absStartX, durY, remaining, textPages, stringBuilder,
                            contentPaint, contentPaintTextHeight, contentPaintFontMetrics,
                            floatArray, isFirstLine = start == 0,
                            blockStyle = paragraph.blockStyle,
                        )
                        absStartX = r.first; durY = r.second
                    }
                }
            } else {
                val r = setTypeText(
                    absStartX, durY, para, textPages, stringBuilder,
                    contentPaint, contentPaintTextHeight, contentPaintFontMetrics,
                    floatArray,
                    blockStyle = paragraph.blockStyle,
                )
                absStartX = r.first; durY = r.second
            }
            if (textPages.last().lines.isNotEmpty()) {
                textPages.last().lines.last().isParagraphEnd = true
            }
            stringBuilder.append("\n")

            // 流式：每处理完一个段落，flush 已完成的页
            flushCompletedPages()
        }

        // Finalize last page
        val lastPage = textPages.last()
        val endPadding = 20
        val durYPadding = durY + endPadding
        if (lastPage.height < durYPadding) {
            lastPage.height = durYPadding
        } else {
            lastPage.height += endPadding
        }
        lastPage.text = stringBuilder.toString()
        finalizePage(lastPage)

        textChapter.isCompleted = true
    }

    /**
     * 竖排版（VERTICAL_RL）排版逻辑——列从右到左、列内字从上到下。
     *
     * Phase 2 简化策略（与 [layoutInternal] 的横排版相比）：
     *   - **不**做全宽对齐 / 标点挤压 / 末行特殊化（横排的 ZhLayout 这些精排手法暂不适配）
     *   - **不**走 setTypeImage 路径（图片栏 Phase 3 再说，先把文字打通）
     *   - **不**做 tate-chu-yoko 长串数字字母旋转——所有字符 upright（Task #5 接入）
     *   - **不**做卧排标点 / 句号顿号偏移——所有标点同 upright（Task #5 接入）
     *   - **不**画装饰横条（横排 isTitleEnd 的 accent bar），竖排标题用 isTitle=true
     *     交给 drawer 用 titlePaint 上色，不做几何装饰
     *   - 标题用 contentPaint 的字号（不放大）——避免列宽不一致导致排版崩
     *
     * 输出兼容：仍写 TextChapter / TextPage / TextLine / TextColumn，字段语义按
     * [ReadingDirection.VERTICAL_RL] 文档解读：
     *   - TextLine = 一列（右起为 lines[0]）
     *   - TextLine.columnLeftX / columnRightX = 列在页面的 X 范围
     *   - TextLine.lineTop / lineBottom = 列的 Y 范围（= padding 区间，整列共用）
     *   - TextColumn.start / end = 字符在列内的 Y 上下边界
     *   - TextColumn.charData = 单字
     *
     * chapterPosition / page.charSize / paragraphEnd 语义与横排一致——
     * getPageIndexByCharIndex / getPosByLineColumn / TextChapter.getReadLength 等
     * 通用 API 不需要为竖排额外修改。
     */
    private fun layoutInternalVertical(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int,
        textChapter: TextChapter,
        textPages: ArrayList<TextPage>,
        channel: Channel<TextPage>? = null,
        onPageReady: ((Int, TextPage) -> Unit)? = null,
        omitChapterTitleBlock: Boolean = false,
        cancelCheck: (() -> Unit)? = null,
        /**
         * 竖排列方向。VERTICAL_RL = 第 0 列贴右边（日式 / 古典），章节标题在屏幕右侧；
         * VERTICAL_LR = 第 0 列贴左边，章节标题在屏幕左侧（用户选项）。其他逻辑同。
         */
        direction: ReadingDirection = ReadingDirection.VERTICAL_RL,
    ) {
        // ── 几何 ───────────────────────────────────────────────────────────
        // charYStep：列内每字 Y 步进 = 字号 × 1.5。
        //   原值是 contentPaintTextHeight (≈textSize*1.2) 偏紧，字与字之间几乎贴着，
        //   对照传统日文 / 古典中文竖排（明朝体活字印刷间距 ~50% 字高）调到 *1.5。
        //
        // columnXStep：列与列的 X 步进 = 字号 × 1.7。
        //   原值 *1.1 太紧 —— 列宽刚好放下一个字符宽，相邻列字符紧贴。
        //   *1.7 给出约 70% 列间距 + 字符自身 100% 宽 = 接近商业阅读器的宽松感。
        //
        // 调大间距会让一页字数下降（charsPerColumn / columnsPerPage 都减小），
        // 章节总页数同比上升。但「读起来不挤」远比「页数少」对竖排体验更重要。
        // 用户如果觉得太松，可以反过来调小这两个常数；目前没暴露设置项。
        // 竖排沿用阅读设置的语义：letterSpacing 控制列内字距，lineHeight 控制列间距。
        // 两者都按字号换算，字号变化后几何同比例重排，不再依赖固定 1.5/1.7 常量。
        val charYStep: Float = contentPaint.textSize *
            (1f + contentPaint.letterSpacing).coerceIn(0.8f, 2f)
        val columnXStep: Float = contentPaint.textSize * lineSpacingExtra.coerceIn(1f, 3f)
        val charsPerColumn: Int = (visibleHeight / charYStep).toInt().coerceAtLeast(1)
        val columnsPerPage: Int = (visibleWidth / columnXStep).toInt().coerceAtLeast(1)
        val pageWidth: Float = viewWidth.toFloat()
        val stringBuilder = StringBuilder()

        // 内部辅助：完成一页时的处理（与横排 finalizePage 等价）
        fun finalizePage(page: TextPage) {
            cancelCheck?.invoke()
            page.index = textPages.indexOf(page)
            page.chapterIndex = chapterIndex
            page.chapterSize = chaptersSize
            page.title = title
            page.doublePage = doublePage
            page.paddingTop = drawPaddingTop
            page.isCompleted = true
            page.textChapter = textChapter
            // 竖排不需要 upLinesPosition（那是横排的"剩余空间均分到 line gap"对齐手段，
            // 竖排里"剩余 X 空间"应该让最左列右移居中，但 Phase 2 简化为留白即可——
            // 用户视觉上是"右起一栏栏排满，剩下左侧留白"，不影响阅读）。
            textChapter.addPage(page)
            channel?.trySend(page)
            onPageReady?.invoke(page.index, page)
        }

        // ── 解析段落 ──（与横排共享 parseHtmlParagraphs / normalizeParagraph） ──
        // P3-5b Phase 3 修复同步：BLOCK_STYLE_MARKER 存在时强制走 parseHtmlParagraphs（详 L249 注释）
        val isHtml = content.trimStart().let {
            it.startsWith("<") && (it.contains("<p") || it.contains("<div") || it.contains("<img"))
        } || content.contains(StructuredChapterContent.BLOCK_STYLE_MARKER)
        val rawParagraphs = if (isHtml) parseHtmlParagraphs(content) else {
            parsePlainTextParagraphs(content)
        }
        // 与横排 layoutInternal 同款：剥掉所有正文 h 标题段 + 剥掉拆段普通 <p> 形式的
        // 重复 title 文本。详细思路见横排同步注释。
        val paragraphs = stripDuplicateTitleSegments(
            rawParagraphs.filterNot { it.isChapterTitle },
            title,
        )
        val contentProvidesChapterTitle = paragraphs.firstOrNull()?.let { first ->
            first.isChapterTitle || isSameChapterTitle(first.text, title)
        } == true

        // ── 状态机 ───
        var currentPage = TextPage()
        textPages.add(currentPage)
        var columnIdxFromRight = 0
        var paragraphNum = 0
        var chapterPosition = 0

        fun startNewColumn(isTitle: Boolean): TextLine {
            val col = TextLine(isTitle = isTitle, isLeftLine = true)
            col.paragraphNum = paragraphNum
            col.chapterPosition = chapterPosition
            // 几何：
            //   VERTICAL_RL — 第 0 列贴右边界 paddingRight，往左推进（日式 / 古典）
            //   VERTICAL_LR — 第 0 列贴左边界 paddingLeft，往右推进（章节标题在左 / 正文在右）
            val columnLeftX = if (direction == ReadingDirection.VERTICAL_LR) {
                paddingLeft + columnIdxFromRight * columnXStep
            } else {
                pageWidth - paddingRight - (columnIdxFromRight + 1) * columnXStep
            }
            col.columnLeftX = columnLeftX
            col.columnRightX = columnLeftX + columnXStep
            // lineTop / lineBottom 仍是 Y——竖排里整列共享同一个 Y 范围（= 正文区上下边界）。
            // lineBase 在竖排没有"基线"概念，给个合理近似值供 isTouchY 等横排兼容代码使用。
            col.lineTop = paddingTop.toFloat()
            col.lineBase = paddingTop + charYStep
            col.lineBottom = (paddingTop + visibleHeight).toFloat()
            return col
        }

        // 把一段文字按列切片填入页面。columnsPerPage 用完则起新页继续填这段的剩余。
        fun layoutParagraphText(text: String, isTitle: Boolean) {
            if (text.isEmpty()) return
            cancelCheck?.invoke()
            var idx = 0
            while (idx < text.length) {
                val col = startNewColumn(isTitle)
                var charsInColumn = 0
                var y = paddingTop.toFloat()
                while (idx < text.length && charsInColumn < charsPerColumn) {
                    val ch = text[idx]
                    if (isZeroWidthChar(ch)) {
                        idx++
                        chapterPosition++
                        continue
                    }
                    col.addColumn(
                        TextColumn(charData = ch.toString(), start = y, end = y + charYStep),
                    )
                    col.text += ch
                    stringBuilder.append(ch)
                    idx++
                    chapterPosition++
                    charsInColumn++
                    y += charYStep
                }
                if (col.columns.isNotEmpty()) {
                    currentPage.addLine(col)
                    columnIdxFromRight++
                    val isParaEnd = idx >= text.length
                    if (isParaEnd) {
                        col.isParagraphEnd = true
                        chapterPosition++  // implicit '\n'
                        stringBuilder.append('\n')
                    }
                    if (columnIdxFromRight >= columnsPerPage) {
                        currentPage.text = stringBuilder.toString()
                        finalizePage(currentPage)
                        stringBuilder.setLength(0)
                        currentPage = TextPage()
                        textPages.add(currentPage)
                        columnIdxFromRight = 0
                    }
                } else {
                    // col 完全空（text 全是 zero-width chars）——退出避免死循环
                    break
                }
            }
        }

        // ── 标题块 ──
        // titleMode==2 / contentProvidesChapterTitle / omitChapterTitleBlock 跟横排同语义：
        // 不画标题块，直接进正文。
        if (titleMode != 2 && !contentProvidesChapterTitle && !omitChapterTitleBlock && title.isNotBlank()) {
            paragraphNum++
            layoutParagraphText(title, isTitle = true)
        }

        // ── 正文段落 ──
        for (paragraph in paragraphs) {
            cancelCheck?.invoke()
            // 空行分隔段：竖排暂不表达（空列的 x 定位与 page.addLine 语义耦合，
            // 风险大于收益），维持跳过 = 旧行为。横排已渲染成一行高留白。
            if (paragraph.isBlankSeparator) continue
            paragraphNum++
            // 段首加 paragraphIndent（"　　"两个全角空格）——竖排里就是
            // 列顶部留两格空白，跟横排"行首缩进"等价的视觉暗示。chapterTitle 段不加。
            val paraText = if (paragraph.isChapterTitle) paragraph.text
                           else paragraphIndent + paragraph.text
            layoutParagraphText(paraText, isTitle = paragraph.isChapterTitle)
        }

        // ── 收尾 ──
        if (currentPage.lines.isNotEmpty()) {
            currentPage.text = stringBuilder.toString()
            finalizePage(currentPage)
        } else if (textPages.size > 1) {
            // 末页空（最后一段恰好填满前一页）——移除空白尾页
            textPages.removeAt(textPages.lastIndex)
        } else {
            // 整章空——给个占位页避免 pageSize=0 把 pageFactory 卡住
            currentPage.format()
            finalizePage(currentPage)
        }

        textChapter.isCompleted = true
    }

    // ── Image layout ──

    private fun setTypeImage(
        src: String,
        x: Int,
        y: Float,
        textPages: ArrayList<TextPage>,
        textHeight: Float,
        stringBuilder: StringBuilder,
    ): Pair<Int, Float> {
        var absStartX = x
        var durY = y
        // Read actual image dimensions for correct aspect ratio
        val intrinsicW: Int
        val intrinsicH: Int
        if (src.startsWith("mobi-img://")) {
            // mobi-img:// 协议：通过 ImageCache 按需解码拿 Bitmap dims
            val bmp = ImageCache.get(src, visibleWidth)
            intrinsicW = bmp?.width ?: 0
            intrinsicH = bmp?.height ?: 0
        } else {
            val path = src.removePrefix("file://")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            intrinsicW = opts.outWidth
            intrinsicH = opts.outHeight
        }
        var imgWidth: Int
        var imgHeight: Int
        if (intrinsicW > 0 && intrinsicH > 0) {
            imgWidth = visibleWidth
            imgHeight = (intrinsicH.toFloat() * visibleWidth / intrinsicW).roundToInt()
            if (imgHeight > visibleHeight) {
                imgWidth = (imgWidth.toFloat() * visibleHeight / imgHeight).roundToInt()
                imgHeight = visibleHeight
            }
        } else {
            // Fallback: 4:3 if dimensions unreadable
            imgWidth = visibleWidth
            imgHeight = (visibleWidth * 0.75f).toInt().coerceAtMost(visibleHeight)
        }

        if (durY + imgHeight > visibleHeight) {
            val textPage = textPages.last()
            if (doublePage && absStartX < viewWidth / 2) {
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                if (textPage.leftLineSize == 0) textPage.leftLineSize = textPage.lineSize
                textPage.text = stringBuilder.toString()
                stringBuilder.clear()
                textPages.add(TextPage())
                absStartX = paddingLeft
            }
            if (textPage.height < durY) textPage.height = durY
            durY = 0f
        }

        val textLine = TextLine(isImage = true)
        textLine.text = " "
        textLine.lineTop = durY + paddingTop
        durY += imgHeight
        textLine.lineBottom = durY + paddingTop
        val startOffset = if (visibleWidth > imgWidth) (visibleWidth - imgWidth) / 2f else 0f
        textLine.addColumn(
            ImageColumn(
                start = absStartX + startOffset,
                end = absStartX + startOffset + imgWidth,
                src = src,
            )
        )
        calcTextLinePosition(textPages, textLine, stringBuilder.length)
        stringBuilder.append(" ")
        textPages.last().addLine(textLine)
        return absStartX to durY + textHeight * paragraphSpacing / 10f
    }

    // ── Text layout with full justification ──

    /**
     * 计算章节标题行的水平起始偏移。
     *
     * 规则（优先级从高到低）：
     * 1. 不是标题（[isTitle]==false）  → 0f；
     * 2. 标题但 chapter-number 小行（[isChapterNum]）  → 0f（数字行始终左贴边，
     *    避免和正文标题对齐方式不一致看起来散）；
     * 3. forceLeftTitle==true 且不是 volume/empty 特殊页 → 走 [titleAlign]
     *    （0=左/1=中/2=右）。这是用户全局偏好生效的主入口；
     * 4. forceLeftTitle==false（volume / 整页空内容）→ 维持老的居中逻辑，避免
     *    破坏现有视觉（比如卷标题、空章节占位页）。
     */
    private fun computeTitleStartXOffset(
        isTitle: Boolean,
        forceLeftTitle: Boolean,
        isVolumeTitle: Boolean,
        emptyContent: Boolean,
        isChapterNum: Boolean,
        desiredWidth: Float,
    ): Float {
        if (!isTitle) return 0f
        if (isChapterNum) return 0f
        if (forceLeftTitle && !isVolumeTitle && !emptyContent) {
            return when (titleAlign) {
                1 -> ((visibleWidth - desiredWidth) / 2).coerceAtLeast(0f)
                2 -> (visibleWidth - desiredWidth).coerceAtLeast(0f)
                else -> 0f
            }
        }
        return 0f
    }

    @Suppress("DEPRECATION")
    private fun setTypeText(
        x: Int,
        y: Float,
        text: String,
        textPages: ArrayList<TextPage>,
        stringBuilder: StringBuilder,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: FontMetrics,
        floatArray: FloatArray,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        isChapterNum: Boolean = false,
        forceLeftTitle: Boolean = false,
        /** P3-5b Phase 3：paragraph 携带的 CSS box 装饰，复制到本次产出的每个 TextLine */
        blockStyle: BlockStyle = BlockStyle.EMPTY,
    ): Pair<Int, Float> {
        var absStartX = x
        val widthsArray = allocateFloatArray(text.length, floatArray)
        textPaint.getTextWidths(text, widthsArray)
        // ZhLayout 纯化后不再是 android.text.Layout 子类，不能与 StaticLayout 共用多态 layout
        // 变量（两分支 LUB 退化成 Any）。改为从各分支抽出 lineCount + 取行 lambda，下方按它们迭代。
        val lineCount: Int
        val lineStartOf: (Int) -> Int
        val lineEndOf: (Int) -> Int
        if (useZhLayout) {
            val (words, widths) = measureTextSplit(text, widthsArray)
            // indentSize 语义 = words 前 N 个字符是缩进空格（正文 text 自带「　　」前缀，
            // addCharsToLineFirst 会剥掉重画）。标题 text 无前缀，谎报会让 ZhLayout 压缩
            // 回退的搜索下界错位 —— 标题一律 0。
            val indentSize = if (isFirstLine && !isTitle) paragraphIndent.length else 0
            // 标题行走 Natural 直排，没有正文 justify 均摊那层「压回行宽」的兜底；末字
            // 字形视觉边界超出 advance 时（大字号下常见），断行预算若只看 advance，字会
            // 被 Canvas clip 裁掉右半。把正向溢出计入 ZhLayout 候选行末（与滚动引擎
            // emitTitleParagraph 同款语义）。只测标题——字数少，逐字 getTextBounds 成本
            // 可忽略；正文行由 justify 压回行宽，维持原状不测。
            val titleOverhangs: List<Float> = if (isTitle) {
                val bounds = Rect()
                List(words.size) { i ->
                    val w = words[i]
                    if (w.isEmpty()) 0f else {
                        textPaint.getTextBounds(w, 0, w.length, bounds)
                        (bounds.right - widths[i]).coerceAtLeast(0f)
                    }
                }
            } else {
                emptyList()
            }
            val zh = ZhLayout(
                visibleWidth, words, widths, indentSize, cjkFullCharWidth(textPaint),
                rightOverhangs = titleOverhangs,
            )
            lineCount = zh.lineCount
            lineStartOf = zh::getLineStart
            lineEndOf = zh::getLineEnd
            if (isTitle) {
                // 标题截字排查日志：行数 / 每行宽 vs 可用宽 / paint 字号。每章仅标题块
                // 两三条，常驻成本可忽略；真机复现「末字截半」时以此定位断行预算。
                val lineWidthsDump = (0 until lineCount).joinToString { zh.getLineWidth(it).toInt().toString() }
                AppLog.info(
                    "TitleLayout",
                    "title='${text.take(24)}' lines=$lineCount lineW=[$lineWidthsDump] " +
                        "visW=$visibleWidth textSize=${textPaint.textSize} " +
                        "overhangMax=${titleOverhangs.maxOrNull() ?: 0f}",
                )
            }
        } else {
            val sl = StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
            lineCount = sl.lineCount
            lineStartOf = sl::getLineStart
            lineEndOf = sl::getLineEnd
        }
        var durY = when {
            emptyContent && textPages.size == 1 -> {
                val textPage = textPages.last()
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = lineCount * textHeight
                    val firstLine = textPage.getLine(0)
                    if (firstLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = firstLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    y - textLayoutHeight
                }
            }
            isTitle && textPages.size == 1 && textPages.last().lines.isEmpty() ->
                y + titleTopSpacing
            else -> y
        }

        for (lineIndex in 0 until lineCount) {
            val textLine = TextLine(isTitle = isTitle, isChapterNum = isChapterNum, blockStyle = blockStyle)
            if (durY + textHeight > visibleHeight) {
                val textPage = textPages.last()
                if (doublePage && absStartX < viewWidth / 2) {
                    textPage.leftLineSize = textPage.lineSize
                    absStartX = viewWidth / 2 + paddingLeft
                } else {
                    if (textPage.leftLineSize == 0) textPage.leftLineSize = textPage.lineSize
                    textPage.text = stringBuilder.toString()
                    textPages.add(TextPage())
                    stringBuilder.clear()
                    absStartX = paddingLeft
                }
                if (textPage.height < durY) textPage.height = durY
                durY = 0f
            }
            val lineStart = lineStartOf(lineIndex)
            val lineEnd = lineEndOf(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, widthsArray, lineStart)
            val desiredWidth = widths.sum()
            when {
                lineIndex == 0 && lineCount > 1 && !isTitle && isFirstLine -> {
                    textLine.text = lineText
                    addCharsToLineFirst(
                        absStartX, textLine, words, desiredWidth, widths,
                    )
                }
                lineIndex == lineCount - 1 -> {
                    textLine.text = lineText
                    val startXOffset = computeTitleStartXOffset(
                        isTitle, forceLeftTitle, isVolumeTitle, emptyContent, isChapterNum,
                        desiredWidth,
                    )
                    addCharsToLineNatural(
                        absStartX, textLine, words, startXOffset,
                        !isTitle && lineIndex == 0, widths,
                    )
                }
                else -> {
                    val titleStartXOffset = computeTitleStartXOffset(
                        isTitle, forceLeftTitle, isVolumeTitle, emptyContent, isChapterNum,
                        desiredWidth,
                    )
                    if (isTitle && titleStartXOffset > 0f) {
                        addCharsToLineNatural(
                            absStartX, textLine, words, titleStartXOffset, false, widths,
                        )
                    } else if (isTitle && !forceLeftTitle && (isMiddleTitle || emptyContent || isVolumeTitle)) {
                        // 兼容老逻辑：volume / empty 两类原本就居中
                        val startXOffset = ((visibleWidth - desiredWidth) / 2).coerceAtLeast(0f)
                        addCharsToLineNatural(
                            absStartX, textLine, words, startXOffset, false, widths,
                        )
                    } else {
                        textLine.text = lineText
                        addCharsToLineMiddle(
                            absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths,
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = textPages.last()
            textPage.addLine(textLine)
            durY += textHeight * lineSpacingExtra
            if (textPage.height < durY) textPage.height = durY
        }
        durY += textHeight * paragraphSpacing / 10f
        return Pair(absStartX, durY)
    }

    // ── Line position tracking ──

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int,
    ) {
        val lastLine = textPages.last().lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        textLine.chapterPosition =
            (textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    // ── First line with indent + justify ──

    private fun addCharsToLineFirst(
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        desiredWidth: Float,
        textWidths: List<Float>,
    ) {
        var x = 0f
        if (!textFullJustify) {
            addCharsToLineNatural(absStartX, textLine, words, x, true, textWidths)
            return
        }
        val bodyIndent = paragraphIndent
        repeat(bodyIndent.length) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(charData = INDENT_CHAR, start = absStartX + x, end = absStartX + x1)
            )
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = bodyIndent.length
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            addCharsToLineMiddle(
                absStartX, textLine, text1, contentPaint,
                desiredWidth, x, textWidths1,
            )
        }
    }

    // ── Middle line: no indent, full justify ──

    private fun addCharsToLineMiddle(
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        startX: Float,
        textWidths: List<Float>,
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(absStartX, textLine, words, startX, false, textWidths)
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        if (words.size <= 1 || residualWidth <= 0f || desiredWidth < visibleWidth * 0.65f) {
            addCharsToLineNatural(absStartX, textLine, words, startX, false, textWidths)
            return
        }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            if (residualWidth > visibleWidth * 0.25f) {
                addCharsToLineNatural(absStartX, textLine, words, startX, false, textWidths)
                return
            }
            val d = residualWidth / spaceSize
            textLine.wordSpacing = d
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else (x + cw)
                textLine.addColumn(
                    TextColumn(charData = char, start = absStartX + x, end = absStartX + x1)
                )
                x = x1
            }
        } else {
            val gapCount = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                textLine.addColumn(
                    TextColumn(charData = char, start = absStartX + x, end = absStartX + x1)
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    // ── Natural (left-aligned) layout ──

    private fun addCharsToLineNatural(
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
    ) {
        val indentLength = paragraphIndent.length
        var x = startX
        textLine.startX = absStartX + startX
        for (index in words.indices) {
            val char = words[index]
            val cw = textWidths[index]
            val x1 = x + cw
            textLine.addColumn(
                TextColumn(charData = char, start = absStartX + x, end = absStartX + x1)
            )
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    // ── Boundary overflow correction ──

    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--; offset++
            columns[columns.lastIndex - 1]
        } else columns.last()
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    // ── Text measurement ──

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0,
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

    private fun allocateFloatArray(size: Int, existing: FloatArray): FloatArray {
        return if (size > existing.size) FloatArray(size) else existing
    }

    // ── HTML parsing ──

    private fun parseHtmlParagraphs(html: String): List<LayoutParagraph> {
        val markedHtml = html
            .replace(chapterNumOpenRegex, "\n$chapterTitleMarker$chapterNumMarker")
            .replace(chapterSubOpenRegex, "\n$chapterTitleMarker$chapterSubMarker")
            // 兼容 EPUB 用 h1-h6 表达章节大小标题：典型如
            // `<h2 class="head1">第一章</h2><h2 class="head">小节名</h2>` 走这条。
            // 不细分 head1/head（class 名因 EPUB 作者而异），统一标 chapterTitle —
            // 真章节标题与 chapter.title 通常匹配，contentProvidesChapterTitle 会
            // 检测并跳过 ChapterProvider 自画的标题块。
            .replace(hTagOpenRegex, "\n$chapterTitleMarker")
            .replace(hTagCloseRegex, "\n")
        val text = markedHtml
            .replace(AppPattern.htmlDivCloseRegex, "\n")
            .replace(AppPattern.htmlBrRegex, "\n")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"")
        val cleaned = text.replace(nonImgTagRegex, "")
        return cleaned.lines().mapNotNull { line ->
            val trimmed = line.trim { it.code <= 0x20 || it == '\u3000' }
            when {
                trimmed.startsWith(chapterTitleMarker) -> {
                    val markedTitle = trimmed.removePrefix(chapterTitleMarker).trim()
                    val isChapterNum = markedTitle.startsWith(chapterNumMarker)
                    val isChapterSubTitle = markedTitle.startsWith(chapterSubMarker)
                    val title = markedTitle
                        .removePrefix(chapterNumMarker)
                        .removePrefix(chapterSubMarker)
                        .trim()
                    if (title.isEmpty()) null else LayoutParagraph(
                        title,
                        isChapterTitle = true,
                        isChapterNum = isChapterNum,
                        isChapterSubTitle = isChapterSubTitle,
                    )
                }
                trimmed.startsWith(StructuredChapterContent.BLOCK_STYLE_MARKER) -> {
                    // P3-5b Phase 3b\uff1aBlockStyle inline marker
                    // \u683c\u5f0f\uff1a__MOREALM_BLOCK_STYLE__<payload>__/MOREALM_BLOCK_STYLE__<text>
                    val endIdx = trimmed.indexOf(StructuredChapterContent.BLOCK_STYLE_END)
                    if (endIdx < 0) {
                        // Malformed \u2014\u2014 \u5f53\u4f5c\u7eaf\u6587\u672c\u515c\u5e95\uff08\u53bb\u6389\u9996\u90e8 marker \u6b8b\u7559\uff09
                        normalizeParagraph(
                            trimmed.removePrefix(StructuredChapterContent.BLOCK_STYLE_MARKER),
                        )?.let(::LayoutParagraph)
                    } else {
                        val payload = trimmed.substring(
                            StructuredChapterContent.BLOCK_STYLE_MARKER.length, endIdx,
                        )
                        val bodyText = trimmed.substring(
                            endIdx + StructuredChapterContent.BLOCK_STYLE_END.length,
                        )
                        val blockStyle = StructuredChapterContent.decodeBlockStyle(payload)
                        normalizeParagraph(bodyText)?.let { txt ->
                            LayoutParagraph(txt, blockStyle = blockStyle)
                        }
                    }
                }
                else -> normalizeParagraph(line)?.let(::LayoutParagraph)
            }
        }
    }

    /**
     * 纯文本（TXT / 无 HTML 标记内容）→ 段落列表。
     *
     * 历史 bug（用户 2026-07-07 报告）：空行经 [normalizeParagraph] 返回 null 被
     * mapNotNull 静默丢弃 → `1\n\n2` 渲染成两段无缝衔接，[collapseBlankLines]
     * "压成单个空行保留段落分隔"的设计在渲染端断链。现在「连续 N 个空行」折叠成
     * 1 个 [LayoutParagraph.isBlankSeparator] 段；首部空行仍丢（页首留白无语义），
     * 尾部空行天然丢（pendingBlank 不落地）。
     */
    private fun parsePlainTextParagraphs(content: String): List<LayoutParagraph> = buildList {
        var pendingBlank = false
        for (line in content.lines()) {
            val norm = normalizeParagraph(line)
            if (norm == null) {
                pendingBlank = isNotEmpty()
            } else {
                if (pendingBlank) {
                    add(LayoutParagraph("", isBlankSeparator = true))
                    pendingBlank = false
                }
                add(LayoutParagraph(norm))
            }
        }
    }

    private fun normalizeParagraph(paragraph: String): String? {
        // trim \u8c13\u8bcd\u5fc5\u987b\u8986\u76d6**\u6240\u6709** Unicode \u7a7a\u767d\u5b57\u7b26\u2014\u2014\u5426\u5219\u53ea\u542b NBSP (U+00A0) / FIGURE SPACE
        // (U+2007) / NARROW NBSP (U+202F) \u7b49"\u770b\u4f3c\u7a7a\u767d\u4f46\u975e ASCII"\u5b57\u7b26\u7684\u6bb5\u843d\u4f1a\u88ab\u5f53\u6210\u6709\u5185\u5bb9
        // \u6bb5\u843d\u4fdd\u7559\u4e0b\u6765\uff0c\u6e32\u67d3\u6210\u5927\u6bb5\u7a7a\u767d\u884c\uff08\u5178\u578b\u4f8b\u5b50\uff1aEPUB \u91cc <p>&#160;</p> \u5047\u7a7a\u6bb5\u843d\uff09\u3002
        //
        // \u6ce8\u610f\uff1aJava `Character.isWhitespace` **\u4e0d**\u8ba4 NBSP\uff08\u7279\u6b8a\u5904\u7406\uff09\uff0c\u6240\u4ee5\u5355\u7528\u5b83\u4e0d\u591f\uff1b
        // \u8fd9\u91cc\u7ec4\u5408 isWhitespace + isSpaceChar \u624d\u80fd\u8986\u76d6\u5230 Unicode `Zs` \u7c7b\u522b\u7684\u5168\u90e8\u7a7a\u767d\u3002
        val trimmed = paragraph.trim { it.isWhitespace() || Character.isSpaceChar(it) }
        return if (trimmed.isEmpty()) null else paragraphIndent + trimmed
    }

    private fun isSameChapterTitle(paragraph: String, title: String): Boolean {
        val normalizedParagraph = normalizeTitleForCompare(paragraph)
        val normalizedTitle = normalizeTitleForCompare(title)
        if (normalizedParagraph.isEmpty() || normalizedTitle.isEmpty()) return false
        return normalizedParagraph == normalizedTitle ||
            normalizedParagraph.endsWith(normalizedTitle) ||
            normalizedTitle.endsWith(normalizedParagraph)
    }

    /**
     * 剥掉正文开头跟 chapter title 重复的 N 段文本（N=1..3）。
     *
     * 场景：EPUB 把章名拆成多段普通 `<p>` 写在正文开头（如「第五章」+「梁王的珠宝」），
     * 既不是 `<h1-6>` 也不是单段完整 title —— `filterNot { isChapterTitle }` 漏掉它们，
     * `isSameChapterTitle` 只看首段也匹不上。结果：ChapterProvider 自画的样式化章标
     * 块（橙色章序号 + 大字主标题 + 装饰线）和正文重复 title 段同框，用户看到两份。
     *
     * 算法：N=3 → 2 → 1 由大到小尝试，前 N 段 normalized 文本拼接是否完全等于
     * normalized title。命中即 drop 那 N 段。优先大 N 避免 N=1 部分匹配遗漏后续段。
     *
     * 不和 [isSameChapterTitle] 合并：后者决定「正文已含 title 就跳过自画」（保留正文），
     * 本函数语义相反「自画照画，剥掉正文里多余的 title 文本」。两者互补，前者覆盖单段
     * 完整 title 场景，本函数覆盖多段拆分场景。
     *
     * 不放进 [parseHtmlParagraphs]：parse 阶段没有 chapter.title 上下文，提到调用点更清晰。
     */
    private fun stripDuplicateTitleSegments(
        paragraphs: List<LayoutParagraph>,
        title: String,
    ): List<LayoutParagraph> {
        if (title.isBlank() || paragraphs.isEmpty()) return paragraphs
        val normalizedTitle = normalizeTitleForCompare(title)
        if (normalizedTitle.isEmpty()) return paragraphs
        val maxN = minOf(3, paragraphs.size)
        for (n in maxN downTo 1) {
            val joined = paragraphs.take(n)
                .joinToString("") { normalizeTitleForCompare(it.text) }
            if (joined == normalizedTitle) return paragraphs.drop(n)
        }
        return paragraphs
    }

    private fun normalizeTitleForCompare(value: String): String = value
        .replace(chapterTitleMarker, "")
        .replace(INDENT_CHAR, "")
        .replace(AppPattern.whitespaceRegex, "")
        .trim()

    private data class LayoutParagraph(
        val text: String,
        val isChapterTitle: Boolean = false,
        val isChapterNum: Boolean = false,
        val isChapterSubTitle: Boolean = false,
        /**
         * 空行分隔段：TXT 正文里作者用空行表达的场景转换 / 诗节分隔。
         * [parsePlainTextParagraphs] 把「连续 N 个空行」折叠成 1 个此类段；横排布局
         * 渲染成一行高留白（页首自动吞掉）、不产字不占 cp。HTML/EPUB 分支永不产生
         * （<p>&#160;</p> 假空段仍按原样丢弃，见 [normalizeParagraph] 注释）。
         */
        val isBlankSeparator: Boolean = false,
        /**
         * **P3-5b Phase 3**：CSS box 装饰（圆角背景 / 边框）。由 [parseHtmlParagraphs]
         * 处理 `__MOREALM_BLOCK_STYLE__` inline marker 后填，[setTypeText] 创建 TextLine
         * 时复制到 [TextLine.blockStyle]。
         */
        val blockStyle: BlockStyle = BlockStyle.EMPTY,
    )

    /**
     * Split a chapter title like "第一章 山边小村" into ("第一章", "山边小村").
     * If no recognizable chapter-number prefix, returns (null, fullTitle).
     */
    private fun splitChapterNumAndTitle(title: String): Pair<String?, String> {
        val trimmed = title.trim()
        // Match common chapter number patterns: 第X章, 第X节, Chapter X, etc.
        val match = chapterNumSplitRegex.find(trimmed)
        if (match != null) {
            val num = match.value.trim()
            val rest = trimmed.substring(match.range.last + 1).trim()
            return if (rest.isNotEmpty()) num to rest else null to trimmed
        }
        return null to trimmed
    }

    companion object {
        const val INDENT_CHAR = "\u3000"
        val IMG_PATTERN = AppPattern.imgSrcPattern
        private val nonImgTagRegex = Regex("<(?!img)[^>]+>", RegexOption.IGNORE_CASE)
        private const val chapterTitleMarker = "__MOREALM_CHAPTER_TITLE__"
        private const val chapterNumMarker = "__MOREALM_CHAPTER_NUM__"
        private const val chapterSubMarker = "__MOREALM_CHAPTER_SUB__"
        private val chapterNumOpenRegex = Regex("<div\\s+class=[\"']chapter-num[\"']\\s*>", RegexOption.IGNORE_CASE)
        private val chapterSubOpenRegex = Regex("<div\\s+class=[\"']chapter-sub[\"']\\s*>", RegexOption.IGNORE_CASE)
        // h1-h6 通用章标题识别：覆盖 EPUB 用 `<h2 class="head1">第一章</h2><h2 class="head">小节名</h2>`
        // 这种结构作章节大小标题的写法（部分 EPUB），避免 ChapterProvider 自画一遍标题块
        // 后正文里 h2 文本再渲染一遍 → 双份标题。
        private val hTagOpenRegex = Regex("<h[1-6](?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        private val hTagCloseRegex = Regex("</h[1-6]>", RegexOption.IGNORE_CASE)
        private val chapterNumSplitRegex = Regex(
            """^(第[零一二三四五六七八九十百千万亿\d]+[章节卷集部篇回话幕折场]|[Cc]hapter\s+\d+|[Vv]ol(?:ume)?\s*\.?\s*\d+|序[章言]|终章|尾声|楔子|番外|引[子章]|\d+[.、]\s*)""",
        )
    }
}

/**
 * 异步排版句柄。
 * - textChapter: 排版结果（页面会逐步添加）
 * - channel: 流式接收已排完的页面
 * - job: 排版协程，可用于取消
 */
class AsyncLayoutHandle(
    val textChapter: TextChapter,
    val channel: Channel<TextPage>,
    val job: Job,
) {
    fun cancel() {
        job.cancel()
        channel.close()
    }
}

// ── Extension: TextPaint.textHeight ──

val TextPaint.textHeight: Float
    get() = fontMetrics.let { it.descent - it.ascent }
