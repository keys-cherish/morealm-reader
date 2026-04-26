package com.morealm.app.ui.reader.renderer

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage

enum class ReaderPageDirection {
    NONE,
    PREV,
    NEXT,
}

internal data class ReaderPageContent(
    val pages: List<TextPage>,
    val currentDisplayIndex: Int,
    val currentPage: TextPage,
    val prevPage: TextPage,
    val nextPage: TextPage,
    val nextPlusPage: TextPage,
    val boundaryDirection: ReaderPageDirection? = null,
) {
    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            -1 -> prevPage
            0 -> currentPage
            1 -> nextPage
            2 -> nextPlusPage
            else -> currentPage
        }
    }

    fun pageForDisplay(displayIndex: Int, fallback: TextPage): TextPage {
        return when (displayIndex - currentDisplayIndex) {
            -1 -> prevPage
            0 -> currentPage
            1 -> nextPage
            2 -> nextPlusPage
            else -> fallback
        }
    }
}

/**
 * Legado ReadView-like page state for Compose.
 *
 * This owns the reading-page transition semantics:
 * delegate -> direction -> fillPage(direction) -> upContent().
 */
internal class ReaderPageState(
    private val pageFactory: ReaderPageFactory,
    currentDisplayIndex: Int,
    private val onBoundaryChapter: (ReaderPageDirection) -> Unit,
) {
    var currentDisplayIndex: Int = currentDisplayIndex.coerceIn(0, pageFactory.pageCount - 1)
        private set

    fun hasPrev(): Boolean = pageFactory.hasPrev(currentDisplayIndex)

    fun hasNext(): Boolean = pageFactory.hasNext(currentDisplayIndex)

    fun hasNextPlus(): Boolean = pageFactory.hasNextPlus(currentDisplayIndex)

    fun moveToFirst(): ReaderPageContent {
        currentDisplayIndex = pageFactory.moveToFirst()
        return upContent()
    }

    fun moveToLast(): ReaderPageContent {
        currentDisplayIndex = pageFactory.moveToLast()
        return upContent()
    }

    fun fillPage(direction: ReaderPageDirection): ReaderPageContent? {
        val relativePosition = when (direction) {
            ReaderPageDirection.PREV -> -1
            ReaderPageDirection.NEXT -> 1
            ReaderPageDirection.NONE -> 0
        }
        val target = when (direction) {
            ReaderPageDirection.PREV -> pageFactory.moveToPrev(currentDisplayIndex)
            ReaderPageDirection.NEXT -> pageFactory.moveToNext(currentDisplayIndex)
            ReaderPageDirection.NONE -> currentDisplayIndex
        }
        if (target == null) {
            when {
                direction == ReaderPageDirection.PREV && pageFactory.isPrevChapterTurn(currentDisplayIndex) -> {
                    pageFactory.upContent(relativePosition = relativePosition, resetPageOffset = false)
                    onBoundaryChapter(ReaderPageDirection.PREV)
                    return upContent().copy(boundaryDirection = ReaderPageDirection.PREV)
                }
                direction == ReaderPageDirection.NEXT && pageFactory.isNextChapterTurn(currentDisplayIndex) -> {
                    pageFactory.upContent(relativePosition = relativePosition, resetPageOffset = false)
                    onBoundaryChapter(ReaderPageDirection.NEXT)
                    return upContent().copy(boundaryDirection = ReaderPageDirection.NEXT)
                }
            }
            AppLog.debug("Reader", "fillPage($direction) rejected at display=$currentDisplayIndex")
            return null
        }
        currentDisplayIndex = target
        pageFactory.upContent(relativePosition = relativePosition, resetPageOffset = false)
        val content = upContent()
        return content
    }

    fun upContent(): ReaderPageContent {
        val currentPage = pageFactory.pageAt(currentDisplayIndex)
        return ReaderPageContent(
            pages = pageFactory.pages,
            currentDisplayIndex = currentDisplayIndex,
            currentPage = currentPage,
            prevPage = pageFactory.prevPageForDisplay(currentDisplayIndex),
            nextPage = pageFactory.nextPageForDisplay(currentDisplayIndex),
            nextPlusPage = pageFactory.nextPlusPageForDisplay(currentDisplayIndex),
        )
    }
}
