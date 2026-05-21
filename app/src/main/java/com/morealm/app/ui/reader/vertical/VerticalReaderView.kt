package com.morealm.app.ui.reader.vertical

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.ChapterProvider
import com.morealm.app.domain.render.ReadingDirection
import com.morealm.app.domain.render.TextChapter
import com.morealm.app.domain.render.TextMeasure
import com.morealm.app.ui.reader.page.animation.CoverPager
import com.morealm.app.ui.reader.page.animation.PageAnimType
import com.morealm.app.ui.reader.page.animation.SlidePager
import com.morealm.app.ui.reader.renderer.drawPageContentVertical
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * 竖排版（VERTICAL_RL）阅读器视图——**完全独立**于横排 [CanvasRenderer] 与
 * SLIDE / COVER / NONE / SLIDE_VERTICAL / SIMULATION / SCROLL 这 6 个翻页动画路径。
 *
 * # 为什么要独立
 *
 * 横排版 [CanvasRenderer] 是一棵巨大的状态树（PagerState + chapterWindow +
 * SimulationDelegate + LazyScroll + ...），任何「在原路径加 readingDirection 分支」
 * 的尝试都会让 6 个翻页动画与竖排版渲染深度耦合——后期任意一边动改另一边都
 * 可能连锁回归（实测：改 SCROLL 的 chapterWindow 会触发 LaunchedEffect 重启
 * 影响 SIMULATION 的 coordinator）。所以本组件独立成包，**绝不**引用
 * CanvasRenderer / PageTurnCoordinator / SimulationDelegate / ChapterWindowSource
 * 等横排专属符号。
 *
 * # Phase 2 范围（本文件）
 *
 *   - 仅支持「翻页」这一种交互（HorizontalPager + reverseLayout = true，视觉上
 *     页面从右向左流出，符合 vertical_rl 阅读顺序）
 *   - 单章节范围内的左右滑翻页 + 三段式 tap zone (left / center / right)
 *   - 跨章节通过 [onPrevChapter] / [onNextChapter] 回调由外层 ReaderScreen 处理
 *     （**不**做 cross-chapter pager，避免引入 CanvasRenderer 同样级别的状态复杂度）
 *   - 排版调 [ChapterProvider.layoutChapter] (readingDirection = VERTICAL_RL)
 *   - 渲染调 [drawPageContentVertical]（OpenType `vert` 字形 + rotate90 fallback）
 *   - 不支持：选区 / 高亮 / TTS aloud / 翻页动画特效 / 自动翻页 / 仿真翻页 / 滚动模式
 *     —— 这些都是 Phase 3 / Task #5 的范围。竖排默认走最朴素的「换页」语义即可。
 *
 * # 状态隔离
 *
 *   - PagerState 在本组件 remember，不与外层共享
 *   - 章节 layout 通过 produceState 在 Default 调度器跑，输入变化时丢弃旧 chapter
 *   - 不读 / 不写 [com.morealm.app.presentation.reader.ReaderViewModel] 的 chapterWindow /
 *     PreloadedReaderChapter 等横排专属字段；只与 ReaderScreen 通过 props/callbacks 通信
 *
 * @param chapterTitle 当前章节标题（用于排版的标题块 + 进度栏显示）
 * @param chapterIndex 当前章节索引（0-based）
 * @param chaptersSize 总章节数
 * @param content 章节正文（已做 replace rules / 繁简转换的最终文本）
 * @param backgroundColor 阅读区底色
 * @param textColor 正文颜色
 * @param accentColor 标题强调色
 * @param fontSize 字号 (sp)
 * @param lineHeight 行高额外间距 (px)
 * @param typeface 字体
 * @param initialChapterPosition 章内字符位置——用于跨会话恢复阅读位置；本组件
 *        把它换算为初始 page index 通过 [TextChapter.getPageIndexByCharIndex]
 * @param restoreToken 恢复 token，每次外层主动恢复时换值——本组件用 LaunchedEffect
 *        监听变化重置 PagerState 到初始页
 * @param onProgressRestored 恢复完成回调——通知外层「你给的 initialPosition 已应用」
 * @param onTapCenter 中央点击回调（外层用来 toggle 控件栏）
 * @param onProgress 当前 page 翻页变化时回报 0-100 进度（用于进度条）
 * @param onVisiblePageChanged 当前 page 翻页变化时报章节 + 标题 + 「N/M」 + chapterPosition
 * @param onNextChapter 在最后一页向左滑（vertical_rl 下=继续读）时回调
 * @param onPrevChapter 在第一页向右滑时回调
 */
@Composable
fun VerticalReaderView(
    chapterTitle: String,
    chapterIndex: Int,
    chaptersSize: Int,
    content: String,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    /** Font size in sp (matches [com.morealm.app.ui.reader.renderer.CanvasRenderer.fontSize]). */
    fontSize: Float,
    /** Extra line spacing (px) appended to natural ascent+descent gap. */
    lineHeight: Float,
    typeface: Typeface,
    /** Padding values in DP (interpreted via [androidx.compose.ui.unit.Density]); matches CanvasRenderer convention. */
    paddingLeft: Int,
    paddingRight: Int,
    paddingTop: Int,
    paddingBottom: Int,
    initialChapterPosition: Int,
    restoreToken: Long,
    /**
     * 翻页动画类型（来自用户偏好 [com.morealm.app.domain.preference.AppPreferences.pageAnim]）。
     *
     * 竖排支持的映射：
     *   - SLIDE / SLIDE_VERTICAL → 走 [SlidePager]（HorizontalPager 默认平移；
     *     SLIDE_VERTICAL 在竖排里没有合理映射 —— 列从右向左走的视觉里再加上下
     *     翻页等于双轴干扰，体验差，统一 fallback 到 SLIDE）
     *   - COVER → [CoverPager]（incoming 页从视觉左侧滑入覆盖；阴影方向因
     *     reverseLayout 而镜像，Phase 2 不修，视觉上仍能识别）
     *   - NONE → 自带 HorizontalPager + 程序触发 scrollToPage（瞬切无动画）
     *   - SIMULATION / SCROLL → fallback 到 SLIDE（仿真翻页 / 上下滚动这两种与
     *     竖排版的几何深度耦合，独立实现成本高 → Phase 3 / Task #5 范围）
     *
     * 用户在阅读设置里选了 SLIDE_VERTICAL/SIMULATION/SCROLL 时不会看到 toast 提示
     * "已 fallback"，仅视觉上等价于 SLIDE。如果需要明确 UI 提示可后续加。
     */
    pageAnimType: PageAnimType,
    onProgressRestored: () -> Unit,
    onTapCenter: () -> Unit,
    onProgress: (Int) -> Unit,
    onVisiblePageChanged: (chapterIndex: Int, title: String, readProgress: String, chapterPosition: Int) -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    /**
     * 列方向：[ReadingDirection.VERTICAL_RL] 章标在屏幕右侧 / 翻页 RTL；
     * [ReadingDirection.VERTICAL_LR] 章标在左 / 翻页 LTR。其他枚举值视为 RL（兜底）。
     */
    direction: ReadingDirection = ReadingDirection.VERTICAL_RL,
    /**
     * 阅读区背景图 uri；空 = 纯色背景。来自 ReaderScreen 的 readerBgImage。
     * 修复 2026-05-21：之前竖排路径完全没接收此字段 → 用户设的 bgImage 在竖排模式下不生效。
     */
    bgImageUri: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.sp.toPx() }
    val padLeftPx = with(density) { paddingLeft.dp.toPx().toInt() }
    val padRightPx = with(density) { paddingRight.dp.toPx().toInt() }
    val padTopPx = with(density) { paddingTop.dp.toPx().toInt() }
    val padBotPx = with(density) { paddingBottom.dp.toPx().toInt() }

    // ── Paint 配置 ──
    // 独立维护、不复用 CanvasRenderer 的 paint cache。竖排版的字号/字重需求
    // 与横排可能不一致（比如未来要给标题用更小字号），但 Phase 2 复用同一组合理默认。
    val contentPaint = remember(fontSizePx, typeface, textColor) {
        TextPaint().apply {
            color = textColor.toArgb()
            textSize = fontSizePx
            isAntiAlias = true
            this.typeface = typeface
            // Phase 2 不设 letterSpacing —— 竖排里它是字符之间的"列内 Y 间距"，
            // 0 已经够用；将来如果排版稀疏可按比例调。
        }
    }
    val titlePaint = remember(fontSizePx, typeface, accentColor) {
        TextPaint().apply {
            color = accentColor.toArgb()
            textSize = fontSizePx * 1.15f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = typeface
        }
    }
    val textMeasure = remember(contentPaint) { TextMeasure(contentPaint) }

    // ── 排版 ──
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithSizePx { widthPx, heightPx ->
            if (widthPx == 0 || heightPx == 0) return@BoxWithSizePx

            // 背景图（与横排 ScrollCanvasReaderHost.bgImageUri 同款）：BgImageManager LRU
            // 取 bitmap 然后 Compose Image fillBounds 铺底。空字符串 / null = 纯色背景兜底。
            val bgEntry = remember(bgImageUri, widthPx, heightPx) {
                if (!bgImageUri.isNullOrEmpty() && widthPx > 0 && heightPx > 0) {
                    com.morealm.app.domain.render.BgImageManager.getBgBitmap(
                        context, bgImageUri, widthPx, heightPx,
                    )
                } else null
            }
            val bgBitmap = bgEntry?.bitmap
            if (bgBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bgBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }

            // ── ChapterProvider ──
            // 每次 size / paint / content / direction 变化时重建。竖排版 layout 算法
            // 只看 viewWidth / viewHeight / paddings / contentPaint / titlePaint，
            // 其余高级配置（justify / paragraphSpacing 等）在 layoutInternalVertical
            // 里都按简化策略处理，不需要传。
            val provider = remember(widthPx, heightPx, padLeftPx, padRightPx, padTopPx, padBotPx, contentPaint, titlePaint, textMeasure, lineHeight) {
                ChapterProvider(
                    viewWidth = widthPx,
                    viewHeight = heightPx,
                    paddingLeft = padLeftPx,
                    paddingRight = padRightPx,
                    paddingTop = padTopPx,
                    paddingBottom = padBotPx,
                    titlePaint = titlePaint,
                    contentPaint = contentPaint,
                    textMeasure = textMeasure,
                    lineSpacingExtra = lineHeight,
                )
            }

            val chapterState = produceState<TextChapter?>(
                initialValue = null,
                chapterIndex,
                chapterTitle,
                content,
                provider,
            ) {
                value = null
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        provider.layoutChapter(
                            title = chapterTitle,
                            content = content,
                            chapterIndex = chapterIndex,
                            chaptersSize = chaptersSize,
                            readingDirection = direction,
                        )
                    }.onFailure {
                        AppLog.warn("VerticalReaderView", "layout failed for ch=$chapterIndex: ${it.message}")
                    }.getOrNull()
                }
            }
            val chapter = chapterState.value

            if (chapter == null) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = textColor)
                return@BoxWithSizePx
            }

            val pageCount = chapter.pageSize.coerceAtLeast(1)
            // 计算初始 page —— restoreToken 变时重新求值（恢复阅读位置）。
            // initialChapterPosition 在 produceState 内换章后重置走外层 onProgressRestored
            // 一致流程：拿到 chapter 后立即 jump，jump 完 emit onProgressRestored。
            val initialPage = remember(chapter, restoreToken) {
                if (initialChapterPosition <= 0) 0
                else chapter.getPageIndexByCharIndex(initialChapterPosition).coerceIn(0, pageCount - 1)
            }
            val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }

            // restoreToken 变 → 重置 pager 到 initialPage（外层主动跳章 / 跨会话恢复用）
            LaunchedEffect(restoreToken, pageCount) {
                if (pagerState.currentPage != initialPage && initialPage in 0 until pageCount) {
                    pagerState.scrollToPage(initialPage)
                }
                onProgressRestored()
            }

            // 翻页变化通知
            val pageInfoFlow = remember(pagerState, chapter) {
                snapshotFlow { pagerState.currentPage }.distinctUntilChanged()
            }
            LaunchedEffect(pageInfoFlow) {
                pageInfoFlow.collect { idx ->
                    val pct = if (pageCount <= 1) 100 else (idx * 100 / (pageCount - 1)).coerceIn(0, 100)
                    onProgress(pct)
                    val page = chapter.getPage(idx)
                    val charPos = page?.lines?.firstOrNull()?.chapterPosition ?: 0
                    onVisiblePageChanged(
                        chapter.chapterIndex,
                        chapter.title,
                        "${idx + 1}/$pageCount",
                        charPos,
                    )
                }
            }

            // ── Pager + tap zones ──
            Box(modifier = Modifier.fillMaxSize()) {
                // 把单页内容渲染抽出来共享给 SLIDE/COVER/NONE 三条路径——
                // 它们都接受同样的 (pageIdx) -> Composable 签名。
                val pageContent: @Composable (Int) -> Unit = { pageIdx ->
                    val page = chapter.getPage(pageIdx)
                    if (page != null) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(),
                        ) {
                            drawIntoCanvas { canvas ->
                                drawPageContentVertical(
                                    canvas = canvas.nativeCanvas,
                                    page = page,
                                    titlePaint = titlePaint,
                                    contentPaint = contentPaint,
                                    chapterNumPaint = null,
                                    hasBookmark = false,
                                    bmColorArgb = accentColor.toArgb(),
                                    canvasWidth = size.width,
                                )
                            }
                        }
                    }
                }

                // 根据 pageAnimType 选 pager primitive。reverseLayout 随 direction：
                // VERTICAL_RL = true（视觉上 next 页在左，匹配日式阅读）；
                // VERTICAL_LR = false（next 在右，章标在左、正文向右铺开时翻页同向）。
                val rtlPager = direction == ReadingDirection.VERTICAL_RL
                when (pageAnimType) {
                    PageAnimType.COVER -> CoverPager(
                        pagerState = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = rtlPager,
                        userScrollEnabled = true,
                        pageContent = pageContent,
                    )
                    PageAnimType.NONE -> HorizontalPager(
                        state = pagerState,
                        pageSize = PageSize.Fill,
                        reverseLayout = rtlPager,
                        userScrollEnabled = true,
                        modifier = Modifier.fillMaxSize(),
                    ) { idx -> pageContent(idx) }
                    else -> SlidePager(
                        pagerState = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = rtlPager,
                        userScrollEnabled = true,
                        pageContent = pageContent,
                    )
                }

                // Tap zones —— 用 pointerInput 识别 tap 在 [0, 1/3) [1/3, 2/3) [2/3, 1] 三段。
                // 中央 tap → onTapCenter（控件栏切换）；左/右 tap → 翻页。
                // 注意 vertical_rl 下：右 tap = 视觉上"下一页"过来的方向 = pagerState.currentPage++
                // = 阅读顺序的 next page；左 tap = 视觉上"已读完"方向 = currentPage-- = 退一页。
                //
                // tap 翻页用 animateScrollToPage 让 SLIDE/COVER 看到动画；NONE 用
                // scrollToPage 瞬切。这样 tap 与 swipe 的视觉一致。
                val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                val isInstant = pageAnimType == PageAnimType.NONE
                val tapModifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pagerState, pageCount, isInstant) {
                        detectTapGestures { offset: Offset ->
                            val zone = offset.x / size.width
                            when {
                                zone < 1f / 3f -> {
                                    val cur = pagerState.currentPage
                                    if (cur > 0) {
                                        coroutineScope.launch {
                                            runCatching {
                                                if (isInstant) pagerState.scrollToPage(cur - 1)
                                                else pagerState.animateScrollToPage(cur - 1)
                                            }
                                        }
                                    } else {
                                        onPrevChapter()
                                    }
                                }
                                zone > 2f / 3f -> {
                                    val cur = pagerState.currentPage
                                    if (cur < pageCount - 1) {
                                        coroutineScope.launch {
                                            runCatching {
                                                if (isInstant) pagerState.scrollToPage(cur + 1)
                                                else pagerState.animateScrollToPage(cur + 1)
                                            }
                                        }
                                    } else {
                                        onNextChapter()
                                    }
                                }
                                else -> onTapCenter()
                            }
                        }
                    }
                Box(modifier = tapModifier)

                // 右上角占位：显示「进度」"N/M"。Phase 2 不画完整 info bar —
                // 进度由控件栏菜单显示，这里仅在调试/视觉边缘处画一行标识。
                // 如果不喜欢可以删，但目前留着便于一眼定位是否是竖排路径。
                Text(
                    text = "竖排 ${pagerState.currentPage + 1}/$pageCount",
                    color = textColor.copy(alpha = 0.4f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color.Transparent)
                        .padding(start = 8.dp, top = 4.dp),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * 取 Compose container 像素尺寸的小 helper —— BoxWithConstraints 的 maxWidth/maxHeight
 * 是 Dp 形态，要换 px 才能喂给 ChapterProvider。
 */
@Composable
private fun BoxWithSizePx(content: @Composable (widthPx: Int, heightPx: Int) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx().toInt() }
        val h = with(density) { maxHeight.toPx().toInt() }
        content(w, h)
    }
}
