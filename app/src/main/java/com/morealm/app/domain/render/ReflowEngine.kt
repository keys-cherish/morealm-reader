package com.morealm.app.domain.render

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reader 排版状态机。
 *
 * # 为什么需要
 *
 * 旧实现把 [ChapterProvider.layoutChapterAsync] 直接挂在 CanvasRenderer 的
 * `LaunchedEffect(layoutInputs, ...)` 里，**scope 是 LaunchedEffect 自身的 CoroutineScope**。
 * 一旦 layoutInputs key 失效（字号 / 边距 / readerStyle 变化），LaunchedEffect 取消旧
 * scope → [AsyncLayoutHandle.job] 跟着取消 → `onCompleted` 永远跑不到 → textChapter
 * 无法 atomic swap。
 *
 * 用户感受到的就是「调间距无反应；要退出重进才生效」—— 单次 slider commit 已经触发
 * 了 layoutInputs 失效，但 onCompleted 在新 LaunchedEffect 启动前被 cancel 吞掉。
 *
 * 同章节快速重排时，UI 还会看到 textChapter 被重置成 pages=1 placeholder（旧补丁路径），
 * 导致 pagerState clamp → 页号丢失。
 *
 * # 设计
 *
 * - **scope 由外部传入并长存**：由 CanvasRenderer 用 `rememberCoroutineScope()` 创建，
 *   只在 composition 离开时 cancel。submit 时 engine 主动 cancel 旧 handle 起新 handle，
 *   旧 handle 不再被外层 LaunchedEffect cancel 间接打断。
 * - **显式 3 态状态机**取代 priorChapter / isRelayout / sameChapterRelayout 等推导补丁：
 *   - [State.Idle]：初始 / 上轮 chapter 不可复用。CanvasRenderer 走 placeholder 渲染。
 *   - [State.Reflowing]：同章重排（字号 / 边距）。**visible 始终是旧的完整 chapter**，
 *     UI 不被打扰；后台 handle 跑完后 atomic swap 到 Ready。
 *   - [State.Ready]：稳态。chapter.isCompleted 通常为 true（空内容占位也走 Ready）。
 * - **流式 partial** 通过 [submit] 的 `onPartial` 回调推回 caller，**不**进 state：
 *   partial 在 layoutInternal 内部对同一 TextChapter 对象累积 pages，state 用 partial
 *   做 equals 比较时同引用 = 相等，不会 emit。让 caller 自己每次回调时 pageCount += 1
 *   即可；engine 只关心「轨道转换」（Idle → Reflowing → Ready）。
 *
 * # 不变量
 *
 * - 一旦 [visibleChapter] 非 null，就**永不退回 null**（不再被 placeholder 重置）。
 * - Reflowing → Ready 的切换是 atomic 单次 state 写入，UI 看到一次性「旧 → 新」，无中间态。
 * - 旧 handle 在 submit 时被 [AsyncLayoutHandle.cancel] 显式取消，避免协程泄露。
 */
class ReflowEngine(
    private val scope: CoroutineScope,
    private val provider: () -> ChapterProvider,
) {
    sealed class State {
        /** 初始 / 上一章不可复用。CanvasRenderer 走 placeholder 渲染。 */
        object Idle : State()

        /**
         * 同章重排：UI 显示 [visible]（旧的完整 chapter），后台 [nextHandle] 跑新排版。
         * onCompleted 时 atomic 切到 Ready(nextHandle.textChapter)；UI 单次切换无中间态。
         */
        data class Reflowing(
            val visible: TextChapter,
            val nextHandle: AsyncLayoutHandle,
        ) : State()

        /** 稳态：chapter.isCompleted=true。 */
        data class Ready(val chapter: TextChapter) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前 UI 视角应当渲染的 chapter；任意状态下都给出最合适的可见值。 */
    val visibleChapter: TextChapter?
        get() = when (val s = _state.value) {
            is State.Reflowing -> s.visible
            is State.Ready -> s.chapter
            State.Idle -> null
        }

    private var currentHandle: AsyncLayoutHandle? = null
    private var lastChapterIndex: Int = Int.MIN_VALUE

    /**
     * 从 cache 命中等外部路径直接把一个完整 chapter 设为 Ready。会取消正在进行的 handle。
     * 用法：CanvasRenderer 的 prelayoutCache hit 路径调用此函数让 engine 知道当前章节态。
     */
    fun markReady(chapter: TextChapter) {
        currentHandle?.cancel()
        currentHandle = null
        lastChapterIndex = chapter.chapterIndex
        _state.value = State.Ready(chapter)
    }

    /**
     * 提交一次排版请求。submit 会：
     * 1. 取消上一个 handle（若有）—— 避免泄露；旧 onCompleted 不再触发；
     * 2. 判定是否「同章重排」：是 → 立刻进入 Reflowing(visible=旧 chapter)；否 → Idle；
     * 3. 启动新 layoutChapterAsync（用 engine.scope，**不**受外层 LaunchedEffect cancel 影响）；
     * 4. onPageReady 推 partial 给 caller（仅非 Reflowing 路径，让首屏 / 跨章早绘）；
     * 5. onCompleted 一次性切到 Ready(new chapter)。
     *
     * @param input 排版输入；其中 [ReflowInput.content] 为空时立刻发 Ready(placeholder)。
     * @param placeholderForEmpty 空内容时用的占位 chapter 工厂；由 caller 提供（避免本类
     *   依赖 UI 文案）。
     * @param onPartial 首次 / 跨章流式回调：layoutInternal 每排完一页就触发。caller
     *   据此即时更新 textChapter / pageCount，让用户尽早看到第一页（对齐参照实现）。
     *   同章重排（Reflowing）路径不触发 onPartial —— 此时 UI 显示 visible，partial 在
     *   后台累积，等 onCompleted 一次性切换。
     */
    fun submit(
        input: ReflowInput,
        placeholderForEmpty: () -> TextChapter,
        onPartial: (TextChapter) -> Unit,
    ) {
        currentHandle?.cancel()
        currentHandle = null

        val priorState = _state.value
        val sameChapter = input.chapterIndex == lastChapterIndex
        lastChapterIndex = input.chapterIndex

        if (input.content.isBlank()) {
            _state.value = State.Ready(placeholderForEmpty().apply { isCompleted = true })
            return
        }

        val visibleForReflow: TextChapter? = if (sameChapter) {
            when (priorState) {
                is State.Ready -> priorState.chapter.takeIf { it.pages.isNotEmpty() }
                is State.Reflowing -> priorState.visible
                State.Idle -> null
            }
        } else {
            null
        }

        val handle = provider().layoutChapterAsync(
            title = input.title,
            content = input.content,
            chapterIndex = input.chapterIndex,
            chaptersSize = input.chaptersSize,
            scope = scope,
            omitChapterTitleBlock = input.omitChapterTitleBlock,
            onPageReady = { _, _ ->
                if (visibleForReflow != null) return@layoutChapterAsync
                val ch = currentHandle?.textChapter ?: return@layoutChapterAsync
                onPartial(ch)
            },
            onCompleted = {
                val ch = currentHandle?.textChapter ?: return@layoutChapterAsync
                _state.value = State.Ready(ch)
            },
        )
        currentHandle = handle

        if (visibleForReflow != null) {
            _state.value = State.Reflowing(visible = visibleForReflow, nextHandle = handle)
        } else if (priorState !is State.Ready) {
            _state.value = State.Idle
        }
    }

    /** 显式取消并丢弃当前进行中的排版（不动 state.visible）。一般在 composition leave 时调用。 */
    fun cancel() {
        currentHandle?.cancel()
        currentHandle = null
    }
}

data class ReflowInput(
    val title: String,
    val content: String,
    val chapterIndex: Int,
    val chaptersSize: Int,
    val omitChapterTitleBlock: Boolean = false,
)
