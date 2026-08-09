package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollChapterLayout

object ReaderRuntimeAdapter {
    fun unitIdForChapter(index: Int): ReadingUnitId =
        ReadingUnitId.fromChapterIndex(index)

    fun layoutKey(
        bookId: String,
        chapterIndex: Int,
        contentVersion: Long,
        styleSignature: String,
    ): LayoutKey = LayoutKey(
        bookId = bookId,
        unitId = unitIdForChapter(chapterIndex),
        contentVersion = contentVersion,
        styleSignature = styleSignature.hashCode().toLong(),
    )

    fun artifactFrom(
        bookId: String,
        layout: ScrollChapterLayout,
        contentVersion: Long,
    ): LayoutArtifact {
        val unitId = unitIdForChapter(layout.chapterIndex)
        return LayoutArtifact(
            key = LayoutKey(
                bookId = bookId,
                unitId = unitId,
                contentVersion = contentVersion,
                styleSignature = layout.styleSignature.hashCode().toLong(),
            ),
            title = layout.title,
            pages = layout.pages.toList(),
            anchorIndex = AnchorIndex(
                layout.pages
                    .asSequence()
                    .flatMap { page -> page.lines.asSequence() }
                    .map { it.firstChapterPos }
                    .distinct()
                    .sorted()
                    .toList(),
            ),
            sourceLayout = layout,
        )
    }
}
