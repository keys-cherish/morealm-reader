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
        // Bug 4 收敛：当错误带 `canOpenSettings=true` 时，系统/引擎自己已经弹了一条
        // 「点击跳转 TTS 设置」的原生 Toast（更权威，可点击），我们再弹只会双 Toast 闪屏。
        // 让位给系统 Toast，UI 现场交给 Listen 设置页的持久化 banner（订阅同一 EventBus）。
        // 误判风险：少数引擎只设了 canOpenSettings 但没真正弹 Toast，此时用户可能没看到
        // 任何提示——但 Listen 页 banner 仍可见，整体损失小于"双 Toast"。
        if (event.canOpenSettings) {
            AppLog.info(
                "TtsErrorPresenter",
                "Suppress app Toast (canOpenSettings=true, system Toast takes over): " +
                    event.message.take(80),
            )
            return
        }

        // 抑制系统/multitts 等 TTS 引擎进程会自己弹原生 Toast 的失败场景：
        // 比如 "TextToSpeech is not initialized"、"missing data"、"engine not bound"
        // 这种关键词出现时，多半 Android Speech 框架已经替我们弹过一个 Toast，
        // 我们再弹就会出现两个一样的 Toast 重叠。命中关键词后只在 logcat 留痕，
        // 不再弹自家 Toast——把 UI 现场让给系统那条更权威的提示。
        //
        // 误判风险：少数自定义引擎会在错误文案里带这些英文词但不弹 Toast，
        // 此时用户会看不到任何 Toast。代价比"双 Toast 闪屏"小，且 Listen 页
        // 仍有同步的状态指示器（错误 banner 走 ListenViewModel.errorMessage 渠道）。
        if (likelySystemEngineSelfToast(event.message)) {
            AppLog.info(
                "TtsErrorPresenter",
                "Suppress duplicate Toast (engine likely shows its own): ${event.message.take(80)}",
            )
            return
        }

        val displayed = event.message
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

    /**
     * 启发式判断：错误是否来自"系统/第三方 TTS 引擎进程会自己弹 Toast"的场景。
     * 命中后我们就不再弹应用内 Toast，避免双 Toast。
     *
     * 关键词覆盖：Android TextToSpeech 框架的常见英文报错（即使我们的 Chinese
     * wrapping 是中文，原始 e.message 经常透出来），以及 multitts/讯飞/三星等
     * 第三方引擎绑定失败时框架抛的 message。
     */
    private fun likelySystemEngineSelfToast(message: String): Boolean {
        val lower = message.lowercase()
        return SYSTEM_ENGINE_TOAST_KEYWORDS.any { lower.contains(it) }
    }

    private fun shouldThrottle(message: String): Boolean {
        val now = System.currentTimeMillis()
        return message == lastMessage && (now - lastShownAtMs) < DEDUPE_WINDOW_MS
    }

    companion object {
        /** A speak loop with consecutiveErrors back-off retries every ~200ms can fire
         *  the same Failed reason 3+ times in <1s — drop duplicates within this window. */
        private const val DEDUPE_WINDOW_MS = 3_000L

        /**
         * 命中其中任何一个关键词，就认为系统/第三方 TTS 引擎自己已经弹了 Toast。
         * 这些关键词来自 Android TextToSpeech 框架源码 + multitts/讯飞/三星 TTS
         * 在崩溃/绑定失败/缺少语音数据时的实际原生提示。
         *
         * 全部小写匹配（[likelySystemEngineSelfToast] 已 lowercase）。
         */
        private val SYSTEM_ENGINE_TOAST_KEYWORDS = listOf(
            // 系统 / framework 路径的英文 message
            "texttospeech is not initialized",
            "tts engine",
            "engine not bound",
            "engine not installed",
            "missing data",
            "language is not supported",
            "language not supported",
            // multitts / 讯飞 / 三星 等第三方引擎的中文常见 Toast
            "缺失语音数据",
            "未识别到可用",
            "引擎未启动",
            "引擎不可用",
            // 占位：用户报"切到 multitts 后系统 toast"，但日志没拿到具体词。
            // 后续看到具体串再追加。
        )
    }
}
