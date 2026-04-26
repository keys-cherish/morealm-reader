package com.morealm.app.ui.reader.renderer

/**
 * Compose/MVVM counterpart of Legado's PageDelegate base state.
 *
 * It owns only direction/running/cancel/moved flags. Rendering remains in
 * Compose animation implementations, but all page-turn entries must pass this
 * gate before starting an animation.
 */
internal class ReaderPageDelegateState {
    var isMoved: Boolean = false
        private set
    var noNext: Boolean = false
        private set
    var direction: ReaderPageDirection = ReaderPageDirection.NONE
        private set
    var isCancel: Boolean = false
        private set
    var isRunning: Boolean = false
        private set
    var isStarted: Boolean = false
        private set
    var isAbortAnim: Boolean = false
        private set

    fun onDown() {
        isMoved = false
        noNext = false
        isRunning = false
        isCancel = false
        setDirection(ReaderPageDirection.NONE)
    }

    fun setDirection(direction: ReaderPageDirection) {
        this.direction = direction
    }

    fun markMoved(cancel: Boolean = false) {
        isMoved = true
        isCancel = cancel
        isRunning = true
    }

    fun startAnim(direction: ReaderPageDirection): Boolean {
        if (isRunning) return false
        if (direction == ReaderPageDirection.NONE) return false
        setDirection(direction)
        isCancel = false
        isRunning = true
        isStarted = true
        return true
    }

    fun keyTurnPage(direction: ReaderPageDirection): Boolean {
        return startAnim(direction)
    }

    fun cancelAnim() {
        isCancel = true
    }

    fun abortAnim() {
        isAbortAnim = isStarted || isRunning
        isStarted = false
        isMoved = false
        isRunning = false
    }

    fun consumeAbortAnim(): Boolean {
        val aborted = isAbortAnim
        isAbortAnim = false
        return aborted
    }

    fun stopScroll() {
        isStarted = false
        isMoved = false
        isRunning = false
        setDirection(ReaderPageDirection.NONE)
    }
}
