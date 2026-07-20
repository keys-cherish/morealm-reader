package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reader 各控制器共享的可变状态（批次3 状态收口第一步）。
 *
 * 之前这四个 MutableStateFlow 分散在 Progress / Navigation 控制器内部，再由
 * ReaderViewModel 用 `internal lateinit var` 事后注入给 ChapterController——
 * 「谁在改这份状态」在类型上不可见，是隐秘 bug 的温床。现在收归一处、构造期
 * 注入，lateinit 全部消灭；写入方通过构造参数显式声明依赖。
 *
 * 下一步（Intent 化）会把写入进一步收敛到唯一 reduce 入口 + 事件日志。
 */
class ReaderSharedState {
    /** 当前章内滚动进度（0..100 千分比语义见 ProgressController）。 */
    val _scrollProgress = MutableStateFlow(0)

    /** 当前可见页快照（页码/标题/百分比文本）。 */
    val _visiblePage = MutableStateFlow(VisibleReaderPage())

    /** 最近一次章节切换方向：1=next / -1=prev / 0=无。 */
    val _navigateDirection = MutableStateFlow(0)

    /** 同文件夹联动书列表（读完自动跳下一本）。 */
    val _linkedBooks = MutableStateFlow<List<Book>>(emptyList())
}
