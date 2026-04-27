package com.morealm.app.ui.reader.renderer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.core.log.AppLog

/**
 * Manages cross-chapter scroll state for continuous scroll mode.
 *
 * This class centralizes the state that must survive chapter transitions
 * so that the ScrollRenderer can provide seamless cross-chapter scrolling.
 * Having this as a separate, testable class makes it easier to diagnose
 * scroll-related bugs — all state changes are in one place.
 */
@Stable
class ReaderScrollState {
    /** Pixel offset to restore after a chapter shift for visual continuity. */
    var pendingScrollOffset by mutableFloatStateOf(0f)
        private set

    /** The chapter index that the scroll mode considers "committed". */
    var committedChapterIndex by mutableIntStateOf(0)
        private set

    /**
     * Record a chapter boundary commit.
     * Called by the scroll renderer when the viewport majority shows adjacent chapter content.
     */
    fun commitChapterShift(direction: ReaderPageDirection, scrollIntoOffset: Float) {
        pendingScrollOffset = scrollIntoOffset
        val oldIndex = committedChapterIndex
        when (direction) {
            ReaderPageDirection.NEXT -> committedChapterIndex++
            ReaderPageDirection.PREV -> committedChapterIndex--
            ReaderPageDirection.NONE -> return
        }
        AppLog.debug(
            "Reader",
            "ReaderScrollState.commitChapterShift" +
                " | direction=$direction" +
                " | chapter=$oldIndex→$committedChapterIndex" +
                " | pendingOffset=$scrollIntoOffset",
        )
    }

    /**
     * Consume the pending scroll offset (returns it and resets to 0).
     * Called by ScrollRenderer during position restoration after chapter shift.
     */
    fun consumeScrollOffset(): Float {
        val offset = pendingScrollOffset
        pendingScrollOffset = 0f
        return offset
    }

    /** Reset when a new book is opened. */
    fun reset(chapterIndex: Int) {
        committedChapterIndex = chapterIndex
        pendingScrollOffset = 0f
    }
}
