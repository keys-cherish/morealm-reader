package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.runtime.mutableIntStateOf
import com.morealm.epub.render.LinkRange
import java.util.concurrent.ConcurrentHashMap

/**
 * 当前书「书内链接目标可解析」的章级 cp 区间注册表 —— 链接虚线提示的数据源。
 *
 * 渲染端（[PagePaneCanvas]）只给**确认可解析**的链接画虚线下划线；目标不存在的
 * 死链接不画任何提示（也不着链接色，视觉上等同普通文字）。可解析性由
 * ReaderScreen 端经 [com.morealm.app.presentation.reader.ReaderChapterController]
 * `resolveEpubLinkRanges`（跳章 / 脚注文本 / 整文件兜底，与点击处理同一套语义）
 * 异步批量判定后写入本注册表。
 *
 * 与 [com.morealm.app.domain.font.EpubFontRegistry] 的 active-book 单例同一模式：
 * 同一时间只读一本书，避免把数据 prop drilling 穿过 PagePaneCanvas 的 7 个调用点
 * （3 个翻页 transition + 滚动 renderer + 仿真截帧）。
 *
 * **生命周期跟书严格绑定**：[activateBook] / [deactivateBook] 由 ReaderScreen 的
 * DisposableEffect(book.id) 驱动；[setChapter] 写入时二次校验 bookKey —— 异步判定
 * 尚在飞行时用户快速退出/切书，旧书的章号区间不会写进新书（章号在两本书里语义无关）。
 *
 * Compose 失效通路：[version] 是 snapshot state，draw lambda 内经 [resolvableRangesFor]
 * 读到它 → 异步判定完成后 [setChapter] bump → 相关 Canvas 自动重绘。
 */
object LinkTargetResolvability {

    /** snapshot 订阅锚点 —— 值本身无意义，bump 即让读过它的 draw/composition 失效。 */
    private val version = mutableIntStateOf(0)

    @Volatile
    private var activeBookKey: String? = null

    private val rangesByChapter = ConcurrentHashMap<Int, List<LinkRange>>()

    /** 渲染端入口：该章已确认可解析的链接 cp 区间（未判定完成 = 空 = 不画）。 */
    fun resolvableRangesFor(chapterIndex: Int): List<LinkRange> {
        version.intValue
        return rangesByChapter[chapterIndex] ?: emptyList()
    }

    /** ReaderScreen 打开某书时调；清掉上一本的区间。null key（book 未就绪）= 仅清空。 */
    fun activateBook(bookKey: String?) {
        activeBookKey = bookKey
        if (rangesByChapter.isNotEmpty()) {
            rangesByChapter.clear()
            version.intValue++
        }
    }

    /** ReaderScreen 离开某书时调；key 不匹配（新书已 activate）时不动新书数据。 */
    fun deactivateBook(bookKey: String?) {
        if (activeBookKey != bookKey) return
        activeBookKey = null
        if (rangesByChapter.isNotEmpty()) {
            rangesByChapter.clear()
            version.intValue++
        }
    }

    /** 判定完一章后写入（覆盖旧值），触发重绘。bookKey 与当前 active 不符 = 丢弃（stale 异步）。 */
    fun setChapter(bookKey: String, chapterIndex: Int, resolvable: List<LinkRange>) {
        if (bookKey != activeBookKey) return
        val old = rangesByChapter.put(chapterIndex, resolvable)
        if (old != resolvable) version.intValue++
    }
}
