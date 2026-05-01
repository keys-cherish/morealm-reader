package com.morealm.app.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.morealm.app.core.log.AppLog

/**
 * Helpers for routing the user to the system's TTS configuration screen.
 *
 * The "official" intent action `com.android.settings.TTS_SETTINGS` works on stock Android,
 * but some OEM ROMs (MIUI / ColorOS / OneUI) rename or hide it. We fall back through:
 *  1. com.android.settings.TTS_SETTINGS                — direct, when supported
 *  2. Settings.ACTION_VOICE_INPUT_SETTINGS             — adjacent, almost always present
 *  3. Settings.ACTION_SETTINGS                         — last resort, drops user at the root
 *
 * All branches set [Intent.FLAG_ACTIVITY_NEW_TASK] so the call is safe from an
 * Application context (used by [TtsErrorPresenter]).
 */
object TtsSystemSettings {

    private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

    /**
     * Try to open the TTS settings page.
     * @return true if any of the fallbacks succeeded.
     */
    fun open(context: Context): Boolean {
        val candidates = listOf(
            Intent(ACTION_TTS_SETTINGS),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                AppLog.info("TtsSettings", "Opened settings via ${intent.action}")
                return true
            } catch (_: ActivityNotFoundException) {
                // Try next fallback
            } catch (e: Exception) {
                AppLog.warn("TtsSettings", "startActivity for ${intent.action} threw", e)
            }
        }
        AppLog.error("TtsSettings", "No TTS settings activity resolvable on this device")
        return false
    }
}
