package com.morealm.app.ui.reader.renderer

import com.morealm.app.domain.render.TextChapter
import com.morealm.app.domain.render.TextPage

/**
 * Compose/MVVM counterpart of Legado TextPageFactory.
 *
 * The public [pages] list intentionally contains only the committed current
 * chapter. Previous/next chapter pages are preview surfaces for delegates, not
 * real display pages. This mirrors Legado's ReadView model and keeps preview
 * pages from mutating saved progress or scroll state.
 */
internal class ReaderPageFactory(
    private val dataSource: ReaderDataSource,
) {
    private val currentChapter: TextChapter? = dataSource.currentChapter
    private val prevChapter: TextChapter? = dataSource.prevChapter
    private val nextChapter: TextChapter? = dataSource.nextChapter
    private val pageIndex: Int = dataSource.pageIndex
    private val currentPages: List<TextPage> = currentChapter?.snapshotPages().orEmpty()
    private val displayPages: List<TextPage> =
        currentPages.ifEmpty { listOf(formattedTitlePage(currentChapter?.title.orEmpty(), currentChapter)) }
    private val prevPages: List<TextPage> = prevChapter?.snapshotPages().orEmpty()
    private val nextPages: List<TextPage> = nextChapter?.snapshotPages().orEmpty()
    private val currentChapterCompleted: Boolean = currentChapter?.isCompleted == true
    private val prevChapterCompleted: Boolean = prevChapter?.isCompleted == true

    val currentPageIndex: Int = pageIndex.coerceIn(0, (displayPages.size - 1).coerceAtLeast(0))

    val pages: List<TextPage> = displayPages

    val pageCount: Int get() = pages.size.coerceAtLeast(1)
    val currentChapterPageCount: Int get() = displayPages.size.coerceAtLeast(1)
    private val currentLastDisplayIndex: Int get() = displayPages.lastIndex.coerceAtLeast(0)

    val hasPrev: Boolean get() = hasPrev(currentPageIndex)
    val hasNext: Boolean get() = hasNext(currentPageIndex)
    val hasNextPlus: Boolean get() = hasNextPlus(currentPageIndex)

    val curPage: TextPage
        get() = currentPageForLocalIndex(currentPageIndex)

    val nextPage: TextPage
        get() = nextPageForLocalIndex(currentPageIndex)

    val prevPage: TextPage
        get() = prevPageForLocalIndex(currentPageIndex)

    val nextPlusPage: TextPage
        get() = nextPlusPageForLocalIndex(currentPageIndex)

    fun moveToFirst(): Int = 0

    fun moveToLast(): Int = currentLastDisplayIndex

    fun hasPrev(displayIndex: Int): Boolean {
        return displayIndex > 0 || dataSource.hasPrevChapter()
    }

    fun hasNext(displayIndex: Int): Boolean {
        return displayIndex < currentLastDisplayIndex ||
            (currentChapterCompleted && dataSource.hasNextChapter())
    }

    fun hasNextPlus(displayIndex: Int): Boolean {
        return displayIndex < currentLastDisplayIndex - 1 ||
            (currentChapterCompleted && dataSource.hasNextChapter())
    }

    fun moveToPrev(displayIndex: Int): Int? {
        if (!hasPrev(displayIndex)) return null
        return if (displayIndex > 0) displayIndex - 1 else null
    }

    fun moveToNext(displayIndex: Int): Int? {
        if (!hasNext(displayIndex)) return null
        return if (displayIndex < currentLastDisplayIndex) displayIndex + 1 else null
    }

    fun isPrevChapterTurn(displayIndex: Int): Boolean {
        return displayIndex <= 0 && dataSource.hasPrevChapter()
    }

    fun isNextChapterTurn(displayIndex: Int): Boolean {
        return displayIndex >= currentLastDisplayIndex &&
            currentChapterCompleted &&
            dataSource.hasNextChapter()
    }

    /**
     * Last `displayIndex` of the *previous* chapter, or `null` if there is no
     * previous chapter / it has no pages snapshotted yet.
     *
     * Used by [PageTurnCoordinator.commitPageTurn] on the PREV boundary path
     * (cross-chapter flicker fix layer 3). When the user turns past the first
     * page of the current chapter, `commitPageTurn` writes this value back to
     * `lastSettledDisplayPage` so that — should the new coordinator be able to
     * read it before re-init clobbers it — the simulation view starts at the
     * incoming chapter's last page rather than its first page.
     *
     * Layer 2 (synchronous coordinator init in CanvasRenderer) is the one that
     * actually persists across coordinator rebuild; layer 3 is the in-memory
     * paper trail that lets layer 2 verify "yes, the user really did want the
     * last page of this chapter, not the first."
     */
    fun prevChapterLastDisplayIndex(): Int? {
        if (prevPages.isEmpty()) return null
        return prevPages.lastIndex
    }

    fun pageAt(displayIndex: Int): TextPage {
        return pages.getOrNull(displayIndex.coerceIn(0, pageCount - 1)) ?: curPage
    }

    fun prevPageForDisplay(displayIndex: Int): TextPage {
        val localIndex = currentLocalIndex(displayIndex)
        return when {
            localIndex != null -> prevPageForLocalIndex(localIndex)
            else -> prevPage
        }
    }

    fun nextPageForDisplay(displayIndex: Int): TextPage {
        val localIndex = currentLocalIndex(displayIndex)
        return when {
            localIndex != null -> nextPageForLocalIndex(localIndex)
            else -> nextPage
        }
    }

    fun nextPlusPageForDisplay(displayIndex: Int): TextPage {
        val localIndex = currentLocalIndex(displayIndex)
        return when {
            localIndex != null -> nextPlusPageForLocalIndex(localIndex)
            else -> nextPlusPage
        }
    }

    fun upContent(relativePosition: Int = 0, resetPageOffset: Boolean = true) {
        dataSource.upContent(relativePosition, resetPageOffset)
    }

    fun displayIndexForCurrentPage(localPageIndex: Int = currentPageIndex): Int {
        return localPageIndex.coerceIn(0, pageCount - 1)
    }

    fun currentLocalIndex(displayIndex: Int): Int? {
        return displayIndex.takeIf { it in displayPages.indices }
    }

    fun isCurrentChapterDisplay(displayIndex: Int): Boolean = currentLocalIndex(displayIndex) != null

    fun pageForTurn(displayIndex: Int, relativePos: Int): TextPage? {
        return when (relativePos) {
            -1 -> when {
                displayIndex > 0 -> pages.getOrNull(displayIndex - 1)
                prevChapterCompleted -> prevPages.lastOrNull()?.removePageAloudSpan()
                else -> null
            }
            0 -> pageAt(displayIndex)
            1 -> when {
                displayIndex < currentLastDisplayIndex -> pages.getOrNull(displayIndex + 1)
                currentChapterCompleted -> nextPages.firstOrNull()?.removePageAloudSpan()
                else -> null
            }
            2 -> when {
                displayIndex < currentLastDisplayIndex - 1 -> pages.getOrNull(displayIndex + 2)
                displayIndex < currentLastDisplayIndex && currentChapterCompleted ->
                    nextPages.firstOrNull()?.removePageAloudSpan()
                currentChapterCompleted -> nextPages.getOrNull(1)?.removePageAloudSpan()
                else -> null
            }
            else -> null
        }
    }

    private fun currentPageForLocalIndex(localIndex: Int): TextPage {
        return displayPages.getOrNull(localIndex)
            ?: formattedTitlePage(currentChapter?.title.orEmpty(), currentChapter)
    }

    private fun nextPageForLocalIndex(localIndex: Int): TextPage {
        currentChapter?.let { chapter ->
            if (localIndex < currentPages.size - 1) {
                return currentPages.getOrNull(localIndex + 1)?.removePageAloudSpan()
                    ?: formattedTitlePage(chapter.title, chapter)
            }
            if (!currentChapterCompleted) return formattedTitlePage(chapter.title, chapter)
        }
        nextChapter?.let { chapter ->
            return nextPages.firstOrNull()?.removePageAloudSpan()
                ?: formattedTitlePage(chapter.title, chapter)
        }
        return TextPage().format()
    }

    private fun prevPageForLocalIndex(localIndex: Int): TextPage {
        currentChapter?.let { chapter ->
            if (localIndex > 0) {
                return currentPages.getOrNull(localIndex - 1)?.removePageAloudSpan()
                    ?: formattedTitlePage(chapter.title, chapter)
            }
            if (!currentChapterCompleted) return formattedTitlePage(chapter.title, chapter)
        }
        prevChapter?.let { chapter ->
            return prevPages.lastOrNull()?.removePageAloudSpan()
                ?: formattedTitlePage(chapter.title, chapter)
        }
        return TextPage().format()
    }

    private fun nextPlusPageForLocalIndex(localIndex: Int): TextPage {
        currentChapter?.let { chapter ->
            if (localIndex < currentPages.size - 2) {
                return currentPages.getOrNull(localIndex + 2)?.removePageAloudSpan()
                    ?: formattedTitlePage(chapter.title, chapter)
            }
            if (!currentChapterCompleted) return formattedTitlePage(chapter.title, chapter)
            nextChapter?.let { next ->
                if (localIndex < currentPages.size - 1) {
                    return nextPages.firstOrNull()?.removePageAloudSpan()
                        ?: formattedTitlePage(next.title, next)
                }
                return nextPages.getOrNull(1)?.removePageAloudSpan()
                    ?: TextPage().apply { text = KEEP_SWIPE_TIP }.format()
            }
        }
        return TextPage().format()
    }

    private fun formattedTitlePage(title: String, chapter: TextChapter?): TextPage {
        return TextPage(title = title).apply { textChapter = chapter }.format()
    }

    private companion object {
        const val KEEP_SWIPE_TIP = "继续滑动以加载下一章..."
    }
}
