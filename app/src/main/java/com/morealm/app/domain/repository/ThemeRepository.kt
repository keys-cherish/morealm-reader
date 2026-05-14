package com.morealm.app.domain.repository

import android.content.Context
import android.content.res.Configuration
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ThemeDao
import com.morealm.app.domain.entity.LegadoReadConfig
import com.morealm.app.domain.entity.LegadoThemeConfig
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.entity.toThemeEntities
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

    /**
     * Legado 主题导入结果。两个分支语义：
     *  - [ThemeImported]：标准 Legado **主题配置（ThemeConfig）**，含 themeName/primaryColor 等
     *    app 主题色字段，导入后得到 1 个 [ThemeEntity]。
     *  - [ReadConfigImported]：Legado **阅读样式配置（ReadBookConfig.Config）**，含 bgStr/textColor/
     *    lineSpacingExtra 等字段（与主题色完全不同 schema）。当前只消费颜色 + 背景部分，得到「日 + 夜」
     *    两个 [ThemeEntity]；`inaccessibleBgPaths` 列出无法跨包访问的沙盒路径，UI 应给 toast 提醒。
     *  - [Failed]：JSON 既不像 ThemeConfig 也不像 ReadConfig，或反序列化抛错。
     *
     * 走 sealed 类而非异常是因为「ReadConfig 命中」不是错误而是合法分支，UI 要据此 toast 区别处理。
     */
    sealed class LegadoImportResult {
        data class ThemeImported(val theme: ThemeEntity) : LegadoImportResult()
        data class ReadConfigImported(
            val themes: List<ThemeEntity>,
            val inaccessibleBgPaths: List<String>,
        ) : LegadoImportResult()
        data class Failed(val message: String) : LegadoImportResult()
    }

    /**
     * 智能识别 Legado 主题 / 阅读样式 JSON。识别策略 —— 看 JSON 顶层 object 字段：
     *  - 含 `themeName` 或 `primaryColor` → ThemeConfig
     *  - 含 `bgStr` 或 `textColor`（同时大概率有 `lineSpacingExtra`）→ ReadBookConfig.Config
     *
     * 双 schema 都试解析；先匹配的赢，匹配失败兜底走 ThemeConfig（老行为）。
     */
    suspend fun importLegadoTheme(jsonString: String): LegadoImportResult {
        val trimmed = jsonString.trim()
        // 1. 快速识别 schema —— bgStr 是 ReadConfig 独有的强标识，主题色 ThemeConfig 没这字段
        val looksLikeReadConfig = trimmed.contains("\"bgStr\"") ||
            (trimmed.contains("\"textColor\"") && trimmed.contains("\"lineSpacingExtra\""))
        return runCatching {
            if (looksLikeReadConfig) {
                val readConfig = json.decodeFromString<LegadoReadConfig>(trimmed)
                val inaccessibleBg = mutableListOf<String>()
                val (dayTheme, nightTheme) = readConfig.toThemeEntities(inaccessibleBg)
                // 路径检查 + copy 到 internal（如果可读）
                val resolved = listOf(dayTheme, nightTheme).map { it.withResolvedBg(inaccessibleBg) }
                themeDao.upsertAll(resolved)
                activateTheme(resolved.first().id)  // 默认激活白天
                AppLog.info("Theme", "ReadConfig imported as 2 themes (day+night), inaccessible bg paths: ${inaccessibleBg.size}")
                LegadoImportResult.ReadConfigImported(resolved, inaccessibleBg)
            } else {
                val legadoConfig = json.decodeFromString<LegadoThemeConfig>(trimmed)
                val entity = legadoConfig.toThemeEntity().withResolvedBg(mutableListOf())
                saveAndActivate(entity)
                LegadoImportResult.ThemeImported(entity)
            }
        }.getOrElse { e ->
            AppLog.error("Theme", "Failed to import Legado theme JSON", e)
            LegadoImportResult.Failed(e.message ?: e::class.simpleName ?: "解析失败")
        }
    }

    suspend fun importLegadoThemes(jsonArray: String): List<ThemeEntity> {
        val configs = json.decodeFromString<List<LegadoThemeConfig>>(jsonArray)
        val entities = configs.map { it.toThemeEntity().withResolvedBg(mutableListOf()) }
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
     * 新增 file:// 绝对路径分支（Legado ReadConfig 导入用）：
     *   - 路径在 Legado 沙盒（/storage/emulated/0/Android/data/外部开源阅读器实现）→ Android 11+
     *     scoped storage 限制，跨包根本读不到 → 把路径加进 [inaccessibleBgPaths] 让 UI toast，
     *     uri 抹掉为 null（主题颜色照常生效，只是背景没图）
     *   - 路径在公共目录或我们能读到 → 试 copy 到 internal bgCacheDir 复用 http 分支同样的 file://
     *     重定向逻辑
     *
     * Failure modes — all non-fatal, theme still imports:
     *  - Network down / 404 / non-image response → log + null bg
     *  - Disk write fails → log + null bg
     *  - 沙盒路径 → 路径写 inaccessibleBgPaths 让 UI 提示用户手动指定 / 拷贝
     *  - Cache hit (file already exists) → reuse, no download
     */
    private suspend fun ThemeEntity.withResolvedBg(
        inaccessibleBgPaths: MutableList<String>,
    ): ThemeEntity {
        val url = backgroundImageUri ?: return this
        return when {
            url.startsWith("http", ignoreCase = true) -> resolveHttpBg(url)
            url.startsWith("file://") -> resolveFileBg(url.removePrefix("file://"), inaccessibleBgPaths)
            else -> this
        }
    }

    private suspend fun ThemeEntity.resolveHttpBg(url: String): ThemeEntity {
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

    /**
     * 处理 Legado ReadConfig 里的绝对路径背景图 —— 多数情况指向 Legado 自己的沙盒目录，
     * Android 11+ 跨包根本读不到。能读到时复制到 MoRealm 自己的 bgCacheDir 让后续主题
     * 切换/重启后仍可用；读不到时把路径加到 inaccessibleBgPaths 让 UI toast，uri 抹空。
     */
    private fun ThemeEntity.resolveFileBg(
        path: String,
        inaccessibleBgPaths: MutableList<String>,
    ): ThemeEntity {
        val src = File(path)
        // canRead() 在沙盒路径上会返回 false（即便文件存在），是最直接的可访问性检测
        if (!src.exists() || !src.canRead()) {
            inaccessibleBgPaths.add(path)
            AppLog.warn("Theme", "Legado bg path inaccessible (cross-package sandbox): $path")
            return copy(backgroundImageUri = null)
        }
        return runCatching {
            val ext = src.extension.ifBlank { "jpg" }
            val dst = File(bgCacheDir, md5(path) + ".$ext")
            if (!dst.exists() || dst.length() != src.length()) {
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            }
            copy(backgroundImageUri = "file://${dst.absolutePath}")
        }.getOrElse { e ->
            AppLog.error("Theme", "Background image copy failed: $path", e)
            inaccessibleBgPaths.add(path)
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
