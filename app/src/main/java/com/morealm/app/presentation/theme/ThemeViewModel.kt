package com.morealm.app.presentation.theme

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.ThemeRepository
import com.morealm.app.domain.entity.BuiltinThemes
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    val customCss: StateFlow<String> = prefs.customCss
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun setCustomCss(css: String) {
        viewModelScope.launch { prefs.setCustomCss(css) }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            themeRepo.ensureBuiltinThemes()
            applyAutoThemeIfNeeded()
        }
        // 跟随系统时间持续切换：之前 applyAutoThemeIfNeeded 只在启动时跑一次，
        // 用户在 App 里跨过 22:00 / 06:00 不会自动切换。这里加一个分钟级 polling，
        // auto 关闭时 applyAutoThemeIfNeeded 会立即 return，几乎零开销。
        // viewModelScope 在 ViewModel 销毁时自动取消，不需要手动管理生命周期。
        viewModelScope.launch(Dispatchers.IO) {
            // 等到下一个整分对齐再开始，让"22:00 准点切换"看起来更可靠。
            val now = Calendar.getInstance()
            val msToNextMinute = 60_000L - (now.get(Calendar.SECOND) * 1000L +
                now.get(Calendar.MILLISECOND))
            delay(msToNextMinute.coerceAtLeast(1_000L))
            while (isActive) {
                applyAutoThemeIfNeeded()
                delay(60_000L)
            }
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
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setAutoNightMode(false)
            val current = activeTheme.value
            val targetId = if (current?.isNightTheme == true) {
                BuiltinThemes.paper.id
            } else {
                BuiltinThemes.moRealm.id
            }
            themeRepo.activateTheme(targetId)
        }
    }

    fun switchTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setAutoNightMode(false)
            themeRepo.activateTheme(themeId)
        }
    }

    fun setAutoNightMode(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setAutoNightMode(enabled)
            if (enabled) applyAutoThemeIfNeeded()
        }
    }

    fun importLegadoTheme(jsonStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmed = jsonStr.trim()
                if (trimmed.startsWith("[")) {
                    // Array of Legado themes — import all, activate the first
                    val themes = themeRepo.importLegadoThemes(trimmed)
                    if (themes.isNotEmpty()) {
                        themeRepo.activateTheme(themes.first().id)
                    }
                } else {
                    themeRepo.importLegadoTheme(trimmed)
                }
            } catch (e: Exception) {
                AppLog.error("Theme", "Failed to import Legado theme", e)
            }
        }
    }

    fun importCustomTheme(theme: ThemeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                themeRepo.saveAndActivate(theme)
            } catch (e: Exception) {
                AppLog.error("Theme", "Failed to create custom theme", e)
            }
        }
    }

    fun deleteCustomTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            themeRepo.deleteCustomTheme(themeId)
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
                // 用 bundle 信封包一层并打上 format/version 标识。这样以后 import 端
                // 不必再用「字段名嗅探」（"themeName" → Legado / "name" → MoRealm）
                // 这种脆弱的方式做识别，新增字段也不会触发误判。旧版无 format 字段
                // 的导出仍然兼容（importThemeFromUri 走 fallback 分支）。
                val bundle = MoRealmThemeBundle(
                    themes = listOf(theme.toExportData())
                )
                val jsonStr = json.encodeToString(MoRealmThemeBundle.serializer(), bundle)
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(jsonStr.toByteArray(Charsets.UTF_8))
                }
                AppLog.info("Theme", "Exported theme: ${theme.name}")
            } catch (e: Exception) {
                AppLog.error("Theme", "Export failed", e)
            }
        }
    }

    /** Export every user-created / user-imported theme as a single bundle JSON.
     *  Excludes the 6 built-in themes (those ship with the app and re-importing
     *  them just creates noise). Bundle uses the same `morealm-theme` envelope
     *  as single-theme export so import doesn't need a separate code path. */
    fun exportAllCustomThemes(outputUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val themes = themeRepo.getCustomThemesSnapshot()
                if (themes.isEmpty()) {
                    AppLog.info("Theme", "No custom themes to export")
                    return@launch
                }
                val bundle = MoRealmThemeBundle(
                    themes = themes.map { it.toExportData() }
                )
                val jsonStr = json.encodeToString(MoRealmThemeBundle.serializer(), bundle)
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(jsonStr.toByteArray(Charsets.UTF_8))
                }
                AppLog.info("Theme", "Exported ${themes.size} custom theme(s)")
            } catch (e: Exception) {
                AppLog.error("Theme", "Export-all failed", e)
            }
        }
    }

    /** Import theme from a JSON URI.
     *
     *  Format detection priority:
     *    1. JsonObject with `format == "morealm-theme"` → new MoRealm bundle
     *    2. JsonObject with `themeName` field → Legado single
     *    3. JsonArray whose first element has `themeName` → Legado array
     *    4. Otherwise → legacy MoRealm single (raw `ThemeExportData`)
     *
     *  Step 1 is preferred because string-field sniffing (steps 2/3/4) breaks
     *  the moment either side adds a colliding field name. The version field
     *  is reserved for future schema migrations; v1 is the only version today. */
    fun importThemeFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonText = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: return@launch
                val trimmed = jsonText.trim()
                val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()

                // Path 1: new MoRealm bundle with explicit format marker.
                if (parsed is JsonObject &&
                    parsed["format"]?.jsonPrimitive?.contentOrNull == MoRealmThemeBundle.FORMAT
                ) {
                    val bundle = json.decodeFromString(
                        MoRealmThemeBundle.serializer(), trimmed
                    )
                    val saved = bundle.themes.map { data ->
                        val theme = data.toEntity()
                        themeRepo.saveAndActivate(theme)
                        theme
                    }
                    saved.firstOrNull()?.let { themeRepo.activateTheme(it.id) }
                    AppLog.info("Theme", "Imported MoRealm bundle (${saved.size} theme(s))")
                    return@launch
                }

                // Path 2/3: Legado format — array or single object with themeName.
                val isLegadoArray = parsed is JsonArray &&
                    (parsed.firstOrNull() as? JsonObject)?.containsKey("themeName") == true
                val isLegadoSingle = parsed is JsonObject && parsed.containsKey("themeName")
                if (isLegadoArray || isLegadoSingle) {
                    val themes = if (isLegadoArray) {
                        themeRepo.importLegadoThemes(trimmed)
                    } else {
                        listOf(themeRepo.importLegadoTheme(trimmed))
                    }
                    themes.firstOrNull()?.let { themeRepo.activateTheme(it.id) }
                    AppLog.info("Theme", "Imported Legado themes: ${themes.size}")
                    return@launch
                }

                // Path 4: legacy raw ThemeExportData (pre-bundle exports). No format
                // field, no themeName, just MoRealm fields directly. Kept for backward
                // compatibility with files exported before the bundle envelope landed.
                val data = json.decodeFromString(ThemeExportData.serializer(), trimmed)
                val theme = data.toEntity()
                themeRepo.saveAndActivate(theme)
                AppLog.info("Theme", "Imported legacy theme: ${data.name}")
            } catch (e: Exception) {
                AppLog.error("Theme", "Import failed", e)
            }
        }
    }
}

/** Stable id for re-imports — same source name produces the same id, so
 *  re-importing an updated copy *upserts* instead of stacking duplicates.
 *  Matches Legado's behavior (`addConfig` replaces by themeName). Earlier
 *  versions appended `System.currentTimeMillis()` here, which let stale
 *  copies pile up in the database — that's been removed. */
private fun ThemeExportData.toEntity(): ThemeEntity = ThemeEntity(
    id = "imported_${name.hashCode()}",
    name = name,
    author = author,
    isBuiltin = false,
    isNightTheme = isNightTheme,
    primaryColor = primaryColor,
    accentColor = accentColor,
    backgroundColor = backgroundColor,
    surfaceColor = surfaceColor,
    onBackgroundColor = onBackgroundColor,
    bottomBackground = bottomBackground,
    readerBackground = readerBackground,
    readerTextColor = readerTextColor,
    transparentBars = transparentBars,
    backgroundImageUri = backgroundImageUri,
    customCss = customCss,
)

private fun ThemeEntity.toExportData(): ThemeExportData = ThemeExportData(
    name = name,
    author = author,
    isNightTheme = isNightTheme,
    primaryColor = primaryColor,
    accentColor = accentColor,
    backgroundColor = backgroundColor,
    surfaceColor = surfaceColor,
    onBackgroundColor = onBackgroundColor,
    bottomBackground = bottomBackground,
    readerBackground = readerBackground,
    readerTextColor = readerTextColor,
    transparentBars = transparentBars,
    // Don't bake `texture:paper` and other built-in scheme URIs into exports —
    // they only mean something inside MoRealm. Skip non-file/non-http URIs.
    backgroundImageUri = backgroundImageUri?.takeIf {
        it.startsWith("file://") || it.startsWith("http", true)
    },
    customCss = customCss,
)

/** Wrapper format MoRealm exports ever since multi-theme bundle export shipped.
 *  `format` is the load-bearing discriminator for [importThemeFromUri]; do not
 *  change its value without a corresponding migration. `version` exists to
 *  signal future schema breaks; v1 is the only version. */
@kotlinx.serialization.Serializable
data class MoRealmThemeBundle(
    val format: String = FORMAT,
    val version: Int = 1,
    val themes: List<ThemeExportData> = emptyList(),
) {
    companion object { const val FORMAT = "morealm-theme" }
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
    val transparentBars: Boolean = false,
    val backgroundImageUri: String? = null,
    val customCss: String = "",
)
