package com.morealm.app.ui.widget

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.morealm.app.service.TtsEventBus
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Listens for [TtsEventBus.Event.Error] and renders a Material3 [SnackbarHost].
 *
 * Drop one into the root container of any screen that needs to surface TTS failures
 * (Reader, Listen settings, etc.). Multiple instances active at once is fine —
 * `SharedFlow` will deliver to all collectors, but each only renders into its own
 * scope, so the snackbar appears wherever the user currently is.
 *
 * When [TtsEventBus.Event.Error.canOpenSettings] is true, the snackbar offers a
 * "打开设置" action that launches the system TTS settings (with a fallback to the
 * voice input settings if the device has no dedicated TTS settings activity).
 */
@Composable
fun TtsErrorSnackbarHost(
    modifier: Modifier = Modifier,
    hostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current

    // Subscribe inside a LaunchedEffect keyed to the host state so re-composition
    // doesn't double-subscribe. SharedFlow has no replay, so we'll only see events
    // emitted while this composable is alive — which is exactly what we want
    // (don't show stale errors from a previous Reader session).
    LaunchedEffect(hostState) {
        TtsEventBus.events
            .filterIsInstance<TtsEventBus.Event.Error>()
            .collect { ev ->
                val result = hostState.showSnackbar(
                    message = ev.message,
                    actionLabel = if (ev.canOpenSettings) "打开设置" else null,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed && ev.canOpenSettings) {
                    openTtsSettings(context)
                }
            }
    }

    SnackbarHost(hostState = hostState, modifier = modifier)
}

/**
 * Best-effort launch of the system TTS settings page.
 * Tries the dedicated TTS settings activity first; falls back to the voice
 * input settings (which on most ROMs contains the same engine selector); finally
 * falls back to general settings so the user is never left without a destination.
 */
private fun openTtsSettings(context: Context) {
    val candidates = listOf(
        // AOSP / most stock Android: opens "Text-to-speech output" directly
        Intent("com.android.settings.TTS_SETTINGS"),
        // Older devices / some OEM ROMs route via voice input settings
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        // Last-resort: the top-level settings app
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Activity not found on this device; try the next fallback.
        }
    }
}
