package com.morealm.app.presentation.theme

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.ThemeRepository
import com.morealm.app.ui.theme.BuiltinThemes
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeRepo: ThemeRepository,
    private val prefs: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Synchronously pick the correct initial theme to avoid dark→light flash.
     *  Reads from SharedPreferences (instant) instead of guessing by time. */
    private val initialTheme: ThemeEntity = run {
        val savedId = prefs.getActiveThemeIdSync()
        val builtin = BuiltinThemes.all().find { it.id == savedId }
        if (builtin != null) {
            builtin
        } else {
            // Custom theme — use saved isNight flag to at least get light/dark correct
            val isNight = prefs.getActiveThemeIsNightSync()
            if (isNight) BuiltinThemes.moRealm else BuiltinThemes.paper
        }
    }

    val activeTheme: StateFlow<ThemeEntity?> = themeRepo.getActiveTheme()
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialTheme)

    val allThemes: StateFlow<List<ThemeEntity>> = themeRepo.getAllThemes()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val autoNightMode: StateFlow<Boolean> = prefs.autoNightMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.getAutoNightModeSync())

    init {
        viewModelScope.launch {
            themeRepo.ensureBuiltinThemes()
            applyAutoThemeIfNeeded()
        }
    }

    /** Apply time-based theme on startup if auto mode is enabled */
    private suspend fun applyAutoThemeIfNeeded() {
        val auto = autoNightMode.value
        if (!auto) return
        val shouldBeNight = isNightTime()
        val current = activeTheme.value
        val currentIsNight = current?.isNightTheme ?: true
        if (shouldBeNight != currentIsNight) {
            val targetId = if (shouldBeNight) BuiltinThemes.moRealm.id else BuiltinThemes.paper.id
            themeRepo.activateTheme(targetId)
            AppLog.info("Theme", "Auto day/night → $targetId (hour=${Calendar.getInstance().get(Calendar.HOUR_OF_DAY)})")
        }
    }

    /** Manual toggle — disables auto mode so user choice persists across restarts */
    fun toggleDayNight() {
        viewModelScope.launch {
            // Disable auto mode so this manual choice sticks
            prefs.setAutoNightMode(false)
            val current = activeTheme.value
            val targetId = if (current?.isNightTheme == true) {
                BuiltinThemes.paper.id
            } else {
                BuiltinThemes.moRealm.id
            }
            themeRepo.activateTheme(targetId)
            AppLog.info("Theme", "Manual day/night toggle → $targetId (auto mode disabled)")
        }
    }

    fun switchTheme(themeId: String) {
        viewModelScope.launch {
            prefs.setAutoNightMode(false)
            themeRepo.activateTheme(themeId)
            AppLog.info("Theme", "Switched to: $themeId (auto mode disabled)")
        }
    }

    fun setAutoNightMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoNightMode(enabled)
            if (enabled) applyAutoThemeIfNeeded()
            AppLog.info("Theme", "Auto night mode: $enabled")
        }
    }

    fun importLegadoTheme(json: String) {
        viewModelScope.launch {
            try {
                themeRepo.importLegadoTheme(json)
                AppLog.info("Theme", "Imported Legado theme")
            } catch (e: Exception) {
                AppLog.error("Theme", "Failed to import Legado theme", e)
            }
        }
    }

    fun importCustomTheme(theme: ThemeEntity) {
        viewModelScope.launch {
            try {
                themeRepo.saveAndActivate(theme)
                AppLog.info("Theme", "Created custom theme: ${theme.name}")
            } catch (e: Exception) {
                AppLog.error("Theme", "Failed to create custom theme", e)
            }
        }
    }

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 22
    }

    /** Warm color intensity based on time of day (0.0 = none, 1.0 = full warm) */
    fun getWarmColorIntensity(): Float {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 22..23 -> (hour - 21).toFloat() / 3f  // 22:00→0.33, 23:00→0.67
            hour in 0..5 -> 1.0f                            // Full warm at night
            hour in 6..7 -> (8 - hour).toFloat() / 3f      // Fade out in morning
            else -> 0f
        }
    }

    /** Export current theme as JSON to a URI */
    fun exportTheme(outputUri: Uri) {
        val theme = activeTheme.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = json.encodeToString(
                    ThemeExportData.serializer(),
                    ThemeExportData(
                        name = theme.name,
                        author = theme.author,
                        isNightTheme = theme.isNightTheme,
                        primaryColor = theme.primaryColor,
                        accentColor = theme.accentColor,
                        backgroundColor = theme.backgroundColor,
                        surfaceColor = theme.surfaceColor,
                        onBackgroundColor = theme.onBackgroundColor,
                        bottomBackground = theme.bottomBackground,
                        readerBackground = theme.readerBackground,
                        readerTextColor = theme.readerTextColor,
                    )
                )
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(jsonStr.toByteArray(Charsets.UTF_8))
                }
                AppLog.info("Theme", "Exported theme: ${theme.name}")
            } catch (e: Exception) {
                AppLog.error("Theme", "Export failed", e)
            }
        }
    }

    /** Import theme from a JSON URI */
    fun importThemeFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
                val data = json.decodeFromString(ThemeExportData.serializer(), jsonText)
                val theme = ThemeEntity(
                    id = "imported_${data.name.hashCode()}_${System.currentTimeMillis()}",
                    name = data.name,
                    author = data.author,
                    isBuiltin = false,
                    isNightTheme = data.isNightTheme,
                    primaryColor = data.primaryColor,
                    accentColor = data.accentColor,
                    backgroundColor = data.backgroundColor,
                    surfaceColor = data.surfaceColor,
                    onBackgroundColor = data.onBackgroundColor,
                    bottomBackground = data.bottomBackground,
                    readerBackground = data.readerBackground,
                    readerTextColor = data.readerTextColor,
                )
                themeRepo.saveAndActivate(theme)
                AppLog.info("Theme", "Imported theme: ${data.name}")
            } catch (e: Exception) {
                AppLog.error("Theme", "Import failed", e)
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class ThemeExportData(
    val name: String,
    val author: String = "MoRealm",
    val isNightTheme: Boolean = false,
    val primaryColor: String = "",
    val accentColor: String = "",
    val backgroundColor: String = "",
    val surfaceColor: String = "",
    val onBackgroundColor: String = "",
    val bottomBackground: String = "",
    val readerBackground: String = "",
    val readerTextColor: String = "",
)
