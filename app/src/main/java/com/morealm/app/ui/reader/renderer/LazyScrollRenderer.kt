package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import com.morealm.app.domain.render.chapterPositionToParagraphPos
import com.morealm.app.domain.render.findItemIndex
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

/**
 * LazyColumn 段落级瀑布流渲染器 —— 段落作为 LazyColumn item，由外部 caller 维护
 * paragraphs 窗口（[androidx.compose.runtime.snapshots.SnapshotStateList]）和
 * [LazyListState]，本组件只负责渲染 + 把滚动信号往上吐。
 *
 * ── 设计基调 ──
 *
 *   1. **粒度**：段落（[ScrollParagraph]，由 [com.morealm.app.domain.render.toScrollParagraphs]
 *      构建）。比页更细，比行更粗，瀑布流语义最自然
 *   2. **跨章窗口由 caller 管**：caller（[CanvasRenderer]）持有 paragraphs 与 listState，
 *      在 prev/cur/next 章异步加载完成时用 Key-Anchor 模式做窗口增量 mutation +
 *      [LazyListState.requestScrollToItem] 静默调整视野。本组件**不关心**窗口怎么变，
 *      不再监听 paragraphs.firstKey 来推断 prepend/drop（旧设计的"侧信道盲算"已废弃）。
 *   3. **滚到边界仍然由本组件触发** [onNearTop] / [onNearBottom]，由 caller 决定要不要拉
 *      邻章；本组件只负责"用户已经接近窗口边缘"这一信号。
 *   4. **现代实践**：CompositionLocal 取主题、snapshotFlow 处理高频滚动状态、
 *      derivedStateOf 派生低频状态、contentType 复用 item 视图结构
 *
 * ── 不在本组件里做的事（Phase 4 / Phase 5 补）──
 *
 *   - 选区拖把手 / 长按选词
 *   - TTS 朗读高亮 + 自动跟随（已有最简实现：仅在目标段不在视口时滚显）
 *   - 搜索结果高亮
 *   - 用户高亮 / 书签三角
 *   - 自动滚动（autoScroll）
 *   - 音量键翻页 / 键盘导航
 *
 * 上述功能依赖坐标系迁移（TextPos → ParagraphTextPos），独立 PR 处理。
 *
 * @param paragraphs 已扁平的段落窗口（含 prev+cur+next 三章）。caller 维护，
 *        通常是 [androidx.compose.runtime.snapshots.SnapshotStateList]。
 * @param listState 由 caller 持有的 [LazyListState]。caller 必须用同一个 listState
 *        实例做窗口 Key-Anchor 补偿（snapshot anchor → mutate paragraphs →
 *        [LazyListState.requestScrollToItem]），所以无法在本组件内 remember。
 * @param ttsHighlightChapterIndex 当前 TTS 朗读位置所在的章 idx；< 0 表示未在朗读。
 * @param ttsHighlightChapterPosition 当前 TTS 朗读段落的章内字符偏移；< 0 表示未在朗读。
 *        与 [ttsHighlightChapterIndex] 配套，用 [chapterPositionToParagraphPos] 解析为
 *        段编号，再 [findItemIndex] 拿到 LazyColumn item idx → animateScrollToItem。
 *        只在目标段当前**不在可见范围**时才滚，避免 TTS 跨段时反复扰动用户视野。
 * @param jumpChapterIndex 命令式跳转目标章 idx；-1 表示无跳转
 * @param jumpChapterPosition 跳转章内字符偏移
 * @param jumpToken 跳转幂等 key（每次跳转换 [System.nanoTime] 新值）；0L 表示无跳转。
 *        caller 必须保证两阶段契约：paragraphs 已含目标章/段时才出非 0 token。
 * @param onNearTop 用户接近窗口顶 [nearStartThreshold] 段时触发，caller 应异步加载 prev 章
 * @param onNearBottom 用户接近窗口底 [nearEndThreshold] 段时触发，caller 应异步加载 next 章
 * @param onChapterProgressLive **UI 实时**进度回调：sample(150ms) 节流，fling 期间也持续上报
 *        让底栏百分比 / 进度条跟随手指。**不要**在此回调里写 DB——会被打爆。
 * @param onChapterProgressPersist **持久化**进度回调：debounce(800ms) 节流，停止滑动后才上报
 *        一次。caller 在此回调里写 DB / SP，安全。
 * @param onVisibleParagraphChanged (paragraph, scrollOffsetInItem) —— 可见首段变化时通知 caller
 * @param onScrollingChanged (scrolling) —— fling/idle 状态切换
 * @param onTapCenter 点击空白区域 —— 切阅读器菜单
 * @param onLongPressParagraph 长按段落触发：caller 收到 `(paragraph, charOffset)`
 * @param nearStartThreshold 章首预加载阈值（距窗口顶 N 段触发）。默认 15
 * @param nearEndThreshold 章末预加载阈值（距窗口底 N 段触发）。默认 15
 * @param chapterCharSizeProvider 给定 chapterIdx 返回该章总字符数，进度计算用
 */
@OptIn(FlowPreview::class)
@Composable
fun LazyScrollRenderer(
    paragraphs: List<ScrollParagraph>,
    listState: LazyListState,
    ttsHighlightChapterIndex: Int = -1,
    ttsHighlightChapterPosition: Int = -1,
    /**
     * 命令式跳转目标 —— 同书内书签 / TOC / 续读跳转用，使用稳定的"章节-段落-字符"
     * 坐标，与书签 DB 存储格式 [com.morealm.app.domain.entity.Bookmark.chapterIndex] /
     * [com.morealm.app.domain.entity.Bookmark.chapterPos] 直接对齐 —— caller 透传，
     * 不需要再做 ScrollAnchor 转换。
     *
     * `rememberLazyListState(initialIdx, initialOffsetPx)` 的 initial 参数只在首次
     * compose 生效（rememberSaveable 行为）。同书跳转时 paragraphs 重建但 listState
     * 不重建（外层 caller 故意不 key 包裹以保留 prepend 锚定连续性），导致 initialIdx
     * 重算了**没人消费**，LazyColumn 还停在旧位置。
     *
     * 本字段提供命令式补救：[jumpToken] 变 → 内部用 [chapterPositionToParagraphPos]
     * 把 (chIdx, chPos) 解析为段编号 + 段内字符 offset，再用 [findItemIndex] 找 LazyColumn
     * item idx，[ScrollParagraph.lines] 扫到目标行算段内 y 像素，最后 `scrollToItem(idx, y)`。
     * 一次到位，精确到字符行级。
     *
     * ── 两阶段跳转契约（caller 保证）──
     *
     * caller（[CanvasRenderer]）只在 paragraphs 已含目标章/段时才出 token：
     *   - Phase 1：chapterPositionToParagraphPos 返回 null → caller 传 jumpToken=0L、
     *     jumpChapterIndex=-1
     *   - Phase 2：anchor 解析成功 → caller 用
     *     [com.morealm.app.presentation.reader.ReaderChapterController.loadChapter] 的
     *     restoreToken 触发，jumpChapterIndex >= 0
     *
     * 本组件只做单一职责：token 变 + 章/位 在 paragraphs 里能找到段就 scrollToItem。
     * 无 pending、无 retry、无 fallback —— 把"等 anchor 就绪"的复杂度上推到 caller。
     *
     * @see jumpChapterPosition 章内字符绝对偏移（与 [TextLine.chapterPosition] 同口径）
     * @see jumpToken 幂等 key，每次跳转用 `System.nanoTime()` 换新值；caller 在
     *      anchor 未解析期间传 0L 让 LaunchedEffect 跳过
     */
    jumpChapterIndex: Int = -1,
    jumpChapterPosition: Int = 0,
    jumpToken: Long = 0L,
    /**
     * 跳转后的目标段呼吸高亮（与翻页模式 [PageContentDrawer] 一致体验）。
     *
     * null 时不绘制；非 null 时段落落在 `[startChapterPos, endChapterPos)` 区间内
     * 的 item 在文字之下叠一层 [RevealHighlight.currentArgb] 半透明矩形。alpha
     * 由 [RevealHighlight.alpha] [androidx.compose.animation.core.Animatable] 驱动，
     * 1.2s 内褪色到透明。
     */
    revealHighlight: RevealHighlight? = null,
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
    // ── listState 由 caller 持有 ──
    //
    // 跨章窗口的 Key-Anchor 视野补偿（snapshot anchorParaKey → mutate paragraphs →
    // [LazyListState.requestScrollToItem]）必须由 caller 在 paragraphs mutation 同协程
    // 上下文执行。所以 listState 必须由外部传入；本组件不再 rememberLazyListState。

    // ── 命令式跳转（书签 / TOC / 续读）──
    //
    // 同书内跳转的难点：listState 的 initial 参数只在首次 compose 生效（rememberSaveable
    // 设计），caller 后续算出新位置不会改 listState。
    //
    // 解决：caller 用 jumpToken 做幂等 key，每次跳转用 [System.nanoTime] 换新值；本
    // LaunchedEffect 监听到 token 变化时 imperative scrollToItem。
    //
    // ── 两阶段跳转契约（caller 保证）──
    //
    // 上层 [CanvasRenderer] 已实现两阶段：
    //   Phase 1：paragraphs 不含目标段 → 传 jumpToken=0L、jumpChapterIndex=-1
    //   Phase 2：paragraphs 含目标段 → 传非 0 jumpToken + jumpChapterIndex >= 0
    // 所以本 effect 只需相信契约，token 非 0 时直接 scrollToItem。无 pending、无 retry、
    // 无 fallback —— 把"等 paragraphs 就绪"职责上推到 caller 层。
    //
    // ── 视野补偿不在本组件里做 ──
    //
    // prev 章 prepend / cur 章 drop 时保持视野不变的补偿：caller 在 mutate paragraphs
    // 的同一个 LaunchedEffect 内用 [LazyListState.requestScrollToItem] 直接处理，
    // 本组件**不再监听** paragraphs.firstKey 推断 prepend/drop——那种侧信道盲算与
    // first-mount 时机有时序漏洞，已废弃。

    LaunchedEffect(jumpToken) {
        if (jumpToken == 0L) return@LaunchedEffect
        if (jumpChapterIndex < 0) return@LaunchedEffect
        // ── 字符级精确跳转 ──
        //
        // 步骤 1：把 (chIdx, chPos) 解析为段编号 + 段内 charOffset
        val pos = chapterPositionToParagraphPos(paragraphs, jumpChapterIndex, jumpChapterPosition)
        if (pos == null) {
            // 两阶段契约破坏：caller 应已在 paragraphs 含目标段时才出 token
            AppLog.warn(
                "LazyScroll",
                "jump target not in paragraphs (caller two-phase contract violated):" +
                    " chIdx=$jumpChapterIndex chPos=$jumpChapterPosition" +
                    " paragraphs.size=${paragraphs.size} token=$jumpToken",
            )
            return@LaunchedEffect
        }
        // 步骤 2：段编号 → LazyColumn item idx
        val targetIdx = pos.findItemIndex(paragraphs)
        if (targetIdx < 0) return@LaunchedEffect
        val targetParagraph = paragraphs[targetIdx]
        // 步骤 3：段内字符 offset → 段内行 idx → 段内 y 像素
        // [TextLine.chapterPosition] 是该行首字符的章内位置，单调递增。扫到最后一个
        // chapterPosition <= jumpChapterPosition 的行 idx。
        var lineIdx = 0
        for (i in targetParagraph.lines.indices) {
            if (targetParagraph.lines[i].chapterPosition <= jumpChapterPosition) {
                lineIdx = i
            } else break
        }
        val offsetPx = targetParagraph.linePositions
            .getOrNull(lineIdx)
            ?.toInt()
            ?.coerceAtLeast(0)
            ?: 0
        listState.scrollToItem(targetIdx, offsetPx)
        AppLog.info(
            "BookmarkDebug",
            "LazyScroll JUMP scrollToItem(item=$targetIdx, offset=${offsetPx}px)" +
                " char-level chIdx=$jumpChapterIndex chPos=$jumpChapterPosition" +
                " paragraphNum=${pos.paragraphNum} charOffsetInPara=${pos.charOffset}" +
                " lineIdx=$lineIdx token=$jumpToken paragraphs.size=${paragraphs.size}",
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
    //
    // ── 跳转后强制重置（书签 / TOC 跳转 → 程序行为不算用户滚动）──
    //
    // 跳书签命中 paraIdx 落在 < nearStartThreshold 区域（如目标段是章首附近），
    // hasUserScrolled 旧值仍为 true 时会立即触发 onNearTop → 误推 prevChapter →
    // cur 章被切到上一章 → 视野错位。修：jumpToken 翻新值时 hasUserScrolled 强制
    // reset 为 false，需要用户再次手动滚动才重新启用预加载触发器。
    var hasUserScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(jumpToken) {
        if (jumpToken != 0L) {
            hasUserScrolled = false
        }
    }
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
                // ── reveal 命中判定 ──
                //
                // 段落字符区间 = [firstChapterPosition, firstChapterPosition + charSize)
                // reveal 区间 = [startChapterPos, endChapterPos)
                // 两者重叠即命中（用半开区间避免段尾恰好等于 reveal 起点时误命中）。
                //
                // 跨章后 caller 在 CanvasRenderer 的调用处用 takeIf { chapterIndex 匹配 }
                // 兜过滤；这里再加一层 chapterIndex 比对作为防御性编程。
                val isRevealTarget = revealHighlight?.let { rev ->
                    paragraph.chapterIndex == rev.chapterIndex &&
                        paragraph.firstChapterPosition < rev.endChapterPos &&
                        (paragraph.firstChapterPosition + paragraph.charSize) > rev.startChapterPos
                } ?: false
                ScrollParagraphItem(
                    paragraph = paragraph,
                    revealHighlight = if (isRevealTarget) revealHighlight else null,
                    onTap = onTapCenter,
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
    revealHighlight: RevealHighlight? = null,
    onTap: () -> Unit = {},
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
                // 内层 detectTapGestures 必须同时处理 onTap，否则会消费短点击事件、
                // 让外层 Box 的 onTapCenter（菜单切换）失效。
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { tap ->
                        val offsetInPara = computeCharOffsetInParagraph(paragraph, tap.x, tap.y)
                        onLongPress(offsetInPara)
                    },
                )
            },
    ) {
        // ── reveal 整段褪色高亮 ──
        //
        // 命中段 caller 已用 [RevealHighlight.startChapterPos]/[endChapterPos] 与
        // [paragraph.firstChapterPosition]/[charSize] 区间比对过滤过；这里只画。
        // 在 DrawScope 内读 [RevealHighlight.alpha].value（Animatable<Float>）让 Compose
        // 自动登记 snapshot 依赖，每帧 animateTo 推进时重画自然衰减。
        //
        // 文字层在 drawIntoCanvas 之后画 → reveal 在文字之**下**，alpha 衰减不影响可读性。
        // 上限 [RevealHighlight.currentArgb] 内已做 0.32 alpha 缩放；这里只检查 alpha>0
        // 跳过完全透明帧，避免 GPU 浪费。
        revealHighlight?.let { rev ->
            val argb = rev.currentArgb()
            if ((argb ushr 24) > 0) {
                drawRect(color = androidx.compose.ui.graphics.Color(argb))
            }
        }
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
