package com.morealm.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Application-level subscriber that turns [TtsEventBus.Event.Error]s into user-visible toasts.
 *
 * Why Application-scope (and not per-screen Snackbar):
 *  - TTS init failures can happen at service start, in the voice picker, in the reader,
 *    or even while the user has navigated away — there's no single Compose screen that
 *    can be guaranteed to be in the foreground.
 *  - Toast renders regardless of which Activity/Screen is on top, including over the
 *    notification shade and the launcher.
 *
 * Throttling: identical messages emitted within [DEDUPE_WINDOW_MS] are dropped to avoid
 * Toast spam when the speak loop hits repeated init failures during back-off retries.
 */
class TtsErrorPresenter(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastMessage: String? = null
    @Volatile private var lastShownAtMs: Long = 0L

    /** Start collecting in [scope]; lifetime tied to the scope (Application's appScope). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            TtsEventBus.events
                .filterIsInstance<TtsEventBus.Event.Error>()
                .collect { handle(it) }
        }
        AppLog.info("TtsErrorPresenter", "Subscribed to TtsEventBus error stream")
    }

    private fun handle(event: TtsEventBus.Event.Error) {
        val displayed = if (event.canOpenSettings) {
            // Toast itself can't carry a clickable action button, so the hint is appended
            // inline. The dedicated button lives on the Listen settings screen.
            "${event.message}\n（可在「我的 → 听书」中前往系统 TTS 设置）"
        } else {
            event.message
        }
        if (shouldThrottle(displayed)) {
            AppLog.debug("TtsErrorPresenter", "Throttled duplicate Tts error: $displayed")
            return
        }
        lastMessage = displayed
        lastShownAtMs = System.currentTimeMillis()
        // Toast.makeText must be called on the main looper. Hop there explicitly so this
        // works regardless of which dispatcher the collector is running on.
        mainHandler.post {
            try {
                Toast.makeText(context, displayed, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // Defensive: a Toast crash should never bring down the app.
                AppLog.warn("TtsErrorPresenter", "Toast.show() failed", e)
            }
        }
    }

    private fun shouldThrottle(message: String): Boolean {
        val now = System.currentTimeMillis()
        return message == lastMessage && (now - lastShownAtMs) < DEDUPE_WINDOW_MS
    }

    companion object {
        /** A speak loop with consecutiveErrors back-off retries every ~200ms can fire
         *  the same Failed reason 3+ times in <1s — drop duplicates within this window. */
        private const val DEDUPE_WINDOW_MS = 3_000L
    }
}
