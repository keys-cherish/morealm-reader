package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollPage

@JvmInline
value class ReadingUnitId(val value: String) {
    init {
        require(value.isNotBlank()) { "Reading unit id must not be blank" }
    }

    companion object {
        fun fromChapterIndex(index: Int): ReadingUnitId {
            require(index >= 0) { "Chapter index must not be negative" }
            return ReadingUnitId(index.toString())
        }
    }
}

data class ContentAnchor(
    val unitId: ReadingUnitId,
    val characterOffset: Int,
) {
    init {
        require(characterOffset >= 0) { "Character offset must not be negative" }
    }
}

data class LayoutKey(
    val bookId: String,
    val unitId: ReadingUnitId,
    val contentVersion: Long,
    val styleSignature: Long,
) {
    init {
        require(bookId.isNotBlank()) { "Book id must not be blank" }
        require(contentVersion >= 0) { "Content version must not be negative" }
    }
}

data class AnchorIndex(
    val characterOffsets: List<Int>,
) {
    init {
        require(characterOffsets.zipWithNext().all { (left, right) -> left <= right }) {
            "Anchor offsets must be ordered"
        }
        require(characterOffsets.all { it >= 0 }) {
            "Anchor offsets must not be negative"
        }
    }

    fun nearestOffset(characterOffset: Int): Int =
        characterOffsets.minByOrNull { kotlin.math.abs(it - characterOffset) } ?: 0
}

data class LayoutArtifact(
    val key: LayoutKey,
    val title: String,
    val pages: List<ScrollPage>,
    val anchorIndex: AnchorIndex,
    /**
     * 生产路径必带的源排版（投影层消费 ScrollChapterLayout 而非 pages 列表）。
     * 纯 JVM 单测可不构造，故 nullable。
     */
    val sourceLayout: ScrollChapterLayout? = null,
) {
    init {
        require(title.isNotBlank()) { "Layout title must not be blank" }
        require(pages.isNotEmpty()) { "Layout artifact must contain a page" }
    }

    fun contains(anchor: ContentAnchor): Boolean = anchor.unitId == key.unitId
}

sealed interface WindowEntry {
    data object Empty : WindowEntry

    data class Loading(val requestId: Long) : WindowEntry {
        init {
            require(requestId > 0) { "Request id must be positive" }
        }
    }

    data class Ready(val artifact: LayoutArtifact) : WindowEntry

    data class Failed(
        val requestId: Long,
        val errorMessage: String,
    ) : WindowEntry {
        init {
            require(requestId > 0) { "Request id must be positive" }
            require(errorMessage.isNotBlank()) { "Error message must not be blank" }
        }
    }
}

data class ReaderWindow(
    val previous: ReadingUnitId?,
    val current: ReadingUnitId,
    val next: ReadingUnitId?,
    val previousEntry: WindowEntry,
    val currentEntry: WindowEntry,
    val nextEntry: WindowEntry,
)

enum class NavigationSource {
    BUTTON,
    GESTURE,
    DIRECTORY,
    SEARCH,
    BOOKMARK,
    VOLUME_KEY,
    RESTORE,
}

enum class NavigationDirection {
    PREVIOUS,
    NEXT,
    NONE,
}

sealed interface ReaderIntent {
    val source: NavigationSource

    data class Move(
        override val source: NavigationSource,
        val direction: NavigationDirection,
    ) : ReaderIntent

    data class Open(
        override val source: NavigationSource,
        val target: ContentAnchor,
    ) : ReaderIntent
}

data class NavigationTransaction(
    val id: Long,
    val source: NavigationSource,
    val from: ContentAnchor,
    val target: ContentAnchor,
    val targetEntryRequestId: Long?,
)

data class TurnPlan(
    val transaction: NavigationTransaction,
    val sourceArtifact: LayoutArtifact,
    val targetArtifact: LayoutArtifact,
    val direction: NavigationDirection,
    val allowInteraction: Boolean,
)

sealed interface NavigationPreparation {
    data class Ready(val plan: TurnPlan) : NavigationPreparation

    data class Waiting(
        val transaction: NavigationTransaction,
        val showDelayedLoading: Boolean,
    ) : NavigationPreparation

    data class Failed(
        val transaction: NavigationTransaction,
        val message: String,
    ) : NavigationPreparation

    data object AtBoundary : NavigationPreparation
}
