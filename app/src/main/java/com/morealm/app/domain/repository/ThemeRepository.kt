package com.morealm.app.domain.repository

import android.content.Context
import android.content.res.Configuration
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ThemeDao
import com.morealm.app.domain.entity.LegadoThemeConfig
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.http.okHttpClient
import com.morealm.app.domain.http.newCallByteArrayResponse
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.entity.BuiltinThemes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeRepository @Inject constructor(
    private val themeDao: ThemeDao,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Cache directory for theme background images downloaded from http(s) URLs.
     *  Stored under internal `filesDir` so backups can include them later if we
     *  decide to (current BackupManager skips this dir). Files are content-addressed
     *  by URL hash so re-importing the same theme is a no-op. */
    private val bgCacheDir: File by lazy {
        File(context.filesDir, "theme_bg").apply { mkdirs() }
    }

    fun getAllThemes(): Flow<List<ThemeEntity>> = themeDao.getAllThemes()

    fun getActiveTheme(): Flow<ThemeEntity?> = themeDao.getActiveTheme()

    /** Snapshot of user-imported / user-created themes (excludes the 6 built-ins).
     *  Used by `ThemeViewModel.exportAllCustomThemes` for the bundle export path. */
    suspend fun getCustomThemesSnapshot(): List<ThemeEntity> =
        themeDao.getAllSync().filter { !it.isBuiltin }

    /** 直接按 ID 取主题。供 ViewModel 在「跟随系统」场景下校验用户配的日 / 夜默认
     *  主题是否仍然存在（自定义主题可能被用户删除），不存在时由调用方 fallback。 */
    suspend fun getThemeById(id: String): ThemeEntity? = themeDao.getById(id)

    suspend fun activateTheme(themeId: String) {
        themeDao.deactivateAll()
        themeDao.activate(themeId)
        // Determine isNight for sync prefs (used on next startup to avoid flash)
        val theme = themeDao.getById(themeId)
        preferences.setActiveTheme(themeId, theme?.isNightTheme ?: true)
    }

    suspend fun saveAndActivate(theme: ThemeEntity) {
        val existingId = if (!theme.isBuiltin && themeDao.getById(theme.id) == null) {
            themeDao.getAllSync()
                .firstOrNull { !it.isBuiltin && it.name == theme.name }
                ?.id
        } else {
            null
        }
        val savedTheme = theme.copy(id = existingId ?: theme.id, isActive = false)
        themeDao.insert(savedTheme)
        activateTheme(savedTheme.id)
    }

    suspend fun importLegadoTheme(jsonString: String): ThemeEntity {
        val legadoConfig = json.decodeFromString<LegadoThemeConfig>(jsonString)
        val entity = legadoConfig.toThemeEntity().withResolvedBg()
        saveAndActivate(entity)
        return entity
    }

    suspend fun importLegadoThemes(jsonArray: String): List<ThemeEntity> {
        val configs = json.decodeFromString<List<LegadoThemeConfig>>(jsonArray)
        val entities = configs.map { it.toThemeEntity().withResolvedBg() }
        themeDao.upsertAll(entities)
        return entities
    }

    /**
     * If [ThemeEntity.backgroundImageUri] points at an http(s) URL, download it
     * once into [bgCacheDir] and rewrite the URI to `file://...` so the image
     * stays available offline and renders without re-fetching every theme switch.
     * Mirrors Legado's `ThemeConfig.applyConfig` http-bg branch (line 256-273 of
     * `legado/help/config/ThemeConfig.kt`) but stores in internal storage to
     * avoid the runtime storage-permission dance.
     *
     * Failure modes — all non-fatal, theme still imports:
     *  - Network down / 404 / non-image response → log + null bg
     *  - Disk write fails → log + null bg
     *  - Cache hit (file already exists) → reuse, no download
     */
    private suspend fun ThemeEntity.withResolvedBg(): ThemeEntity {
        val url = backgroundImageUri ?: return this
        if (!url.startsWith("http", ignoreCase = true)) return this
        return runCatching {
            val ext = when {
                url.contains(".png", true) -> ".png"
                url.contains(".webp", true) -> ".webp"
                url.contains(".gif", true) -> ".gif"
                url.contains(".jpeg", true) -> ".jpg"
                else -> ".jpg"
            }
            val file = File(bgCacheDir, md5(url) + ext)
            if (!file.exists() || file.length() == 0L) {
                val bytes = okHttpClient.newCallByteArrayResponse { url(url) }
                if (bytes.isEmpty()) error("empty body for $url")
                file.writeBytes(bytes)
            }
            copy(backgroundImageUri = "file://${file.absolutePath}")
        }.getOrElse { e ->
            AppLog.error("Theme", "Background image download failed: $url", e)
            copy(backgroundImageUri = null)
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    suspend fun deleteCustomTheme(themeId: String) {
        val theme = themeDao.getById(themeId) ?: return
        if (theme.isBuiltin) return
        // If deleting the active theme, switch to default first
        if (theme.isActive) {
            activateTheme(BuiltinThemes.moRealm.id)
        }
        themeDao.deleteCustomTheme(themeId)
    }

    /**
     * 撤销删除：把 [theme] 整条原样写回。配合 UI Snackbar 撤销用 — UI 删除前先拿到
     * 完整 entity，撤销时调本方法。不强制 activate，调用方按需自己 [activateTheme]：
     * 例如撤销时如果之前 active 已切到默认，是否要切回看产品偏好。
     *
     * 内置主题不允许通过这条路径恢复（理论上也不会被删，[deleteCustomTheme] 已挡）。
     */
    suspend fun restoreCustomTheme(theme: ThemeEntity) {
        if (theme.isBuiltin) return
        themeDao.insert(theme.copy(isActive = false))
    }

    suspend fun ensureBuiltinThemes() {
        val builtins = BuiltinThemes.all()
        val activeId = themeDao.getAllSync().firstOrNull { it.isActive }?.id
        themeDao.upsertAll(builtins.map { theme ->
            theme.copy(isActive = theme.id == activeId)
        })
        // Only set default active if no theme is currently active (fresh install)
        if (themeDao.countActiveThemes() == 0) {
            // 真·首次安装：getFollowSystemThemeSync() 在 AppPreferences 里默认 true，
            // 这里就直接按当前系统暗色态选 paper / moRealm；否则 ThemeViewModel 拿到
            // initialTheme=paper（系统白天）但 DB activate 写的是 moRealm，进入主屏后
            // activeTheme StateFlow 收到 DB 真值会从白闪到夜（再被 applySystemDarkMode
            // IfFollowing 异步切回白），用户能看到一次明显的颜色跳变。
            val followSystem = preferences.getFollowSystemThemeSync()
            val firstThemeId = if (followSystem) {
                val sysIsNight = isSystemInNightMode()
                if (sysIsNight) BuiltinThemes.moRealm.id else BuiltinThemes.paper.id
            } else {
                builtins.first().id
            }
            themeDao.activate(firstThemeId)
            // 同步到 themePrefs，让冷启动 getActiveThemeIdSync 命中正确主题；
            // 不然下次启动 sync 路径还是默认 morealm_default 又闪一次。
            val targetTheme = builtins.first { it.id == firstThemeId }
            preferences.setActiveTheme(firstThemeId, targetTheme.isNightTheme)
        }
    }

    private fun isSystemInNightMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }
}
