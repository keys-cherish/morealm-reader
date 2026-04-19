package com.morealm.app.domain.repository

import com.morealm.app.domain.db.ThemeDao
import com.morealm.app.domain.entity.LegadoThemeConfig
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.ui.theme.BuiltinThemes
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeRepository @Inject constructor(
    private val themeDao: ThemeDao,
    private val preferences: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllThemes(): Flow<List<ThemeEntity>> = themeDao.getAllThemes()

    fun getActiveTheme(): Flow<ThemeEntity?> = themeDao.getActiveTheme()

    suspend fun activateTheme(themeId: String) {
        themeDao.deactivateAll()
        themeDao.activate(themeId)
        // Determine isNight for sync prefs (used on next startup to avoid flash)
        val theme = themeDao.getById(themeId)
        preferences.setActiveTheme(themeId, theme?.isNightTheme ?: true)
    }

    suspend fun saveAndActivate(theme: ThemeEntity) {
        themeDao.insert(theme.copy(isActive = false))
        activateTheme(theme.id)
    }

    suspend fun importLegadoTheme(jsonString: String): ThemeEntity {
        val legadoConfig = json.decodeFromString<LegadoThemeConfig>(jsonString)
        val entity = legadoConfig.toThemeEntity()
        themeDao.insert(entity)
        return entity
    }

    suspend fun importLegadoThemes(jsonArray: String): List<ThemeEntity> {
        val configs = json.decodeFromString<List<LegadoThemeConfig>>(jsonArray)
        val entities = configs.map { it.toThemeEntity() }
        themeDao.upsertAll(entities)
        return entities
    }

    suspend fun ensureBuiltinThemes() {
        val builtins = BuiltinThemes.all()
        // IGNORE strategy — only inserts if theme doesn't exist yet, preserves user's active state
        themeDao.insertAll(builtins)
        // Only set default active if no theme is currently active (fresh install)
        if (themeDao.countActiveThemes() == 0) {
            themeDao.activate(builtins.first().id)
        }
    }
}
