package com.morealm.app.ui.reader.renderer

import android.os.SystemClock

/** Full reader-side AutoPager state, ported from Legado AutoPager. */
internal class ReaderAutoPagerState {
    var progress: Int = 0
        private set
    var isRunning: Boolean = false
        private set
    var isPausing: Boolean = false
        private set
    var scrollOffsetRemain: Double = 0.0
        private set
    var scrollOffset: Int = 0
        private set
    var lastTimeMillis: Long = 0L
        private set

    fun start() {
        isRunning = true
        isPausing = false
        lastTimeMillis = SystemClock.uptimeMillis()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        isPausing = false
        reset()
    }

    fun pause() {
        if (!isRunning) return
        isPausing = true
    }

    fun resume() {
        if (!isRunning) return
        isPausing = false
        lastTimeMillis = SystemClock.uptimeMillis()
    }

    fun reset() {
        progress = 0
        scrollOffsetRemain = 0.0
        scrollOffset = 0
        lastTimeMillis = SystemClock.uptimeMillis()
    }

    fun computeOffset(readSpeedSeconds: Int, height: Int, isScroll: Boolean): Int {
        if (!isRunning || isPausing || readSpeedSeconds <= 0 || height <= 0) return 0
        val currentTime = SystemClock.uptimeMillis()
        val elapsedTime = currentTime - lastTimeMillis
        lastTimeMillis = currentTime
        val readTime = readSpeedSeconds * 1000.0
        scrollOffsetRemain += height / readTime * elapsedTime
        if (scrollOffsetRemain < 1.0) return 0
        scrollOffset = scrollOffsetRemain.toInt()
        scrollOffsetRemain -= scrollOffset
        if (!isScroll) {
            progress += scrollOffset
        }
        return scrollOffset
    }
}
