package com.morealm.app.ui.reader.renderer

import com.morealm.app.domain.render.TextChapter

/**
 * Compose/MVVM counterpart of Legado's page.api.DataSource.
 *
 * ViewModel still owns book/chapter loading, while the renderer reads all paging
 * inputs through this interface so PageFactory is the single page-state entry.
 */
internal interface ReaderDataSource {
    val pageIndex: Int
    val currentChapter: TextChapter?
    val nextChapter: TextChapter?
    val prevChapter: TextChapter?
    val isScroll: Boolean

    fun hasNextChapter(): Boolean
    fun hasPrevChapter(): Boolean
    fun upContent(relativePosition: Int = 0, resetPageOffset: Boolean = true)
}

internal class SnapshotReaderDataSource(
    override val pageIndex: Int,
    override val currentChapter: TextChapter?,
    override val nextChapter: TextChapter?,
    override val prevChapter: TextChapter?,
    override val isScroll: Boolean,
    private val hasNextChapterValue: Boolean = nextChapter != null,
    private val hasPrevChapterValue: Boolean = prevChapter != null,
    private val onUpContent: (relativePosition: Int, resetPageOffset: Boolean) -> Unit = { _, _ -> },
) : ReaderDataSource {
    override fun hasNextChapter(): Boolean = hasNextChapterValue

    override fun hasPrevChapter(): Boolean = hasPrevChapterValue

    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        onUpContent(relativePosition, resetPageOffset)
    }
}
