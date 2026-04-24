package com.morealm.app.domain.preference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "morealm_settings")

@Singleton
class AppPreferences @Inject constructor(
    private val context: Context,
) {
    object Keys {
        val ACTIVE_THEME_ID = stringPreferencesKey("active_theme_id")
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size")
        val READER_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val READER_MARGIN = intPreferencesKey("reader_margin")
        val READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val READER_TITLE_FONT_FAMILY = stringPreferencesKey("reader_title_font_family")
        val READER_TITLE_FONT_WEIGHT = intPreferencesKey("reader_title_font_weight")
        val PAGE_TURN_MODE = stringPreferencesKey("page_turn_mode")
        val FULLSCREEN_TAP = booleanPreferencesKey("fullscreen_tap")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASS = stringPreferencesKey("webdav_pass")
        val SHELF_VIEW_MODE = stringPreferencesKey("shelf_view_mode")
        val AUTO_NIGHT_MODE = booleanPreferencesKey("auto_night_mode")
        val SOURCE_FILTER_MIN_WORDS = intPreferencesKey("source_filter_min_words")
        val SOURCE_FILTER_MAX_WORDS = intPreferencesKey("source_filter_max_words")
        val CUSTOM_FONT_URI = stringPreferencesKey("custom_font_uri")
        val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        // Reading settings
        val PAGE_ANIM = stringPreferencesKey("page_anim") // cover/simulation/slide/vertical/fade/none
        val TAP_LEFT_ACTION = stringPreferencesKey("tap_left_action") // next/prev
        val VOLUME_KEY_PAGE = booleanPreferencesKey("volume_key_page")
        val RESUME_LAST_READ = booleanPreferencesKey("resume_last_read")
        val LONG_PRESS_UNDERLINE = booleanPreferencesKey("long_press_underline")
        val SCREEN_TIMEOUT = intPreferencesKey("screen_timeout") // 0=system, -1=never, else seconds
        val SHOW_STATUS_BAR = booleanPreferencesKey("show_status_bar")
        val SHOW_CHAPTER_NAME = booleanPreferencesKey("show_chapter_name")
        val SHOW_TIME_BATTERY = booleanPreferencesKey("show_time_battery")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val MARGIN_TOP = intPreferencesKey("margin_top")
        val MARGIN_BOTTOM = intPreferencesKey("margin_bottom")
        val CUSTOM_CSS = stringPreferencesKey("custom_css")
        val CUSTOM_BG_IMAGE = stringPreferencesKey("custom_bg_image")
        val CUSTOM_TXT_CHAPTER_REGEX = stringPreferencesKey("custom_txt_chapter_regex")
        val TTS_SKIP_PATTERN = stringPreferencesKey("tts_skip_pattern")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val ACTIVE_READER_STYLE = stringPreferencesKey("active_reader_style")
        val SCREEN_ORIENTATION = intPreferencesKey("screen_orientation") // -1=auto, 0=portrait, 1=landscape
        val TEXT_SELECTABLE = booleanPreferencesKey("text_selectable")
        val CHINESE_CONVERT_MODE = intPreferencesKey("chinese_convert_mode") // 0=off, 1=s2t, 2=t2s
        // Tap zone actions: prev/next/menu/tts/bookmark/none
        val TAP_ACTION_TOP_LEFT = stringPreferencesKey("tap_action_top_left")
        val TAP_ACTION_TOP_RIGHT = stringPreferencesKey("tap_action_top_right")
        val TAP_ACTION_BOTTOM_LEFT = stringPreferencesKey("tap_action_bottom_left")
        val TAP_ACTION_BOTTOM_RIGHT = stringPreferencesKey("tap_action_bottom_right")
        // Header/footer content: time/battery/chapter/progress/page/none
        val HEADER_LEFT = stringPreferencesKey("header_left")
        val HEADER_CENTER = stringPreferencesKey("header_center")
        val HEADER_RIGHT = stringPreferencesKey("header_right")
        val FOOTER_LEFT = stringPreferencesKey("footer_left")
        val FOOTER_CENTER = stringPreferencesKey("footer_center")
        val FOOTER_RIGHT = stringPreferencesKey("footer_right")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val LAST_AUTO_BACKUP = longPreferencesKey("last_auto_backup")
        val READER_ENGINE = stringPreferencesKey("reader_engine") // "canvas" or "webview"
    }

    /**
     * Synchronous SharedPreferences for theme — avoids dark/light flash on startup.
     * Theme ID is read synchronously before first frame for instant theme application.
     */
    private val themePrefs = context.getSharedPreferences("morealm_theme", Context.MODE_PRIVATE)

    /** Read active theme ID synchronously (no suspend, no Flow). Safe to call in ViewModel init. */
    fun getActiveThemeIdSync(): String =
        themePrefs.getString("active_theme_id", "morealm_default") ?: "morealm_default"

    fun getAutoNightModeSync(): Boolean =
        themePrefs.getBoolean("auto_night_mode", true)

    fun getActiveThemeIsNightSync(): Boolean =
        themePrefs.getBoolean("active_theme_is_night", true)

    val activeThemeId: Flow<String> = context.dataStore.data
        .map { it[Keys.ACTIVE_THEME_ID] ?: "morealm_default" }

    val readerFontSize: Flow<Float> = context.dataStore.data
        .map { it[Keys.READER_FONT_SIZE] ?: 17f }

    val readerLineHeight: Flow<Float> = context.dataStore.data
        .map { it[Keys.READER_LINE_HEIGHT] ?: 2.0f }

    val readerMargin: Flow<Int> = context.dataStore.data
        .map { it[Keys.READER_MARGIN] ?: 24 }

    val readerFontFamily: Flow<String> = context.dataStore.data
        .map { it[Keys.READER_FONT_FAMILY] ?: "noto_serif_sc" }

    val readerTitleFontFamily: Flow<String> = context.dataStore.data
        .map { it[Keys.READER_TITLE_FONT_FAMILY] ?: "" }  // empty = same as body

    val readerTitleFontWeight: Flow<Int> = context.dataStore.data
        .map { it[Keys.READER_TITLE_FONT_WEIGHT] ?: 500 }  // 100-900, default 500 (medium, not bold)

    val sourceFilterMinWords: Flow<Int> = context.dataStore.data
        .map { it[Keys.SOURCE_FILTER_MIN_WORDS] ?: 2000 }

    val sourceFilterMaxWords: Flow<Int> = context.dataStore.data
        .map { it[Keys.SOURCE_FILTER_MAX_WORDS] ?: 0 }  // 0 = no max limit

    val pageTurnMode: Flow<String> = context.dataStore.data
        .map { it[Keys.PAGE_TURN_MODE] ?: "scroll" }

    val fullscreenTap: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.FULLSCREEN_TAP] ?: false }

    val ttsEngine: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_ENGINE] ?: "system" }

    val ttsSpeed: Flow<Float> = context.dataStore.data
        .map { it[Keys.TTS_SPEED] ?: 1.0f }

    val shelfViewMode: Flow<String> = context.dataStore.data
        .map { it[Keys.SHELF_VIEW_MODE] ?: "grid" }

    val autoNightMode: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AUTO_NIGHT_MODE] ?: true }

    val webDavUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.WEBDAV_URL] ?: "" }

    val webDavUser: Flow<String> = context.dataStore.data
        .map { it[Keys.WEBDAV_USER] ?: "" }

    val webDavPass: Flow<String> = context.dataStore.data
        .map { it[Keys.WEBDAV_PASS] ?: "" }

    val customFontUri: Flow<String> = context.dataStore.data
        .map { it[Keys.CUSTOM_FONT_URI] ?: "" }

    val customFontName: Flow<String> = context.dataStore.data
        .map { it[Keys.CUSTOM_FONT_NAME] ?: "" }

    val disclaimerAccepted: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.DISCLAIMER_ACCEPTED] ?: false }

    val pageAnim: Flow<String> = context.dataStore.data
        .map { it[Keys.PAGE_ANIM] ?: "vertical" }

    val tapLeftAction: Flow<String> = context.dataStore.data
        .map { it[Keys.TAP_LEFT_ACTION] ?: "next" }

    val volumeKeyPage: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.VOLUME_KEY_PAGE] ?: true }

    val resumeLastRead: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.RESUME_LAST_READ] ?: false }

    val longPressUnderline: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.LONG_PRESS_UNDERLINE] ?: true }

    val screenTimeout: Flow<Int> = context.dataStore.data
        .map { it[Keys.SCREEN_TIMEOUT] ?: -1 }

    val showStatusBar: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SHOW_STATUS_BAR] ?: false }

    val showChapterName: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SHOW_CHAPTER_NAME] ?: true }

    val showTimeBattery: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SHOW_TIME_BATTERY] ?: true }

    val paragraphSpacing: Flow<Float> = context.dataStore.data
        .map { it[Keys.PARAGRAPH_SPACING] ?: 1.4f }

    val marginTop: Flow<Int> = context.dataStore.data
        .map { it[Keys.MARGIN_TOP] ?: 24 }

    val marginBottom: Flow<Int> = context.dataStore.data
        .map { it[Keys.MARGIN_BOTTOM] ?: 24 }

    val customCss: Flow<String> = context.dataStore.data
        .map { it[Keys.CUSTOM_CSS] ?: "" }

    val customBgImage: Flow<String> = context.dataStore.data
        .map { it[Keys.CUSTOM_BG_IMAGE] ?: "" }

    val customTxtChapterRegex: Flow<String> = context.dataStore.data
        .map { it[Keys.CUSTOM_TXT_CHAPTER_REGEX] ?: "" }

    val ttsSkipPattern: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_SKIP_PATTERN] ?: "" }

    val ttsVoice: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_VOICE] ?: "" }

    val activeReaderStyle: Flow<String> = context.dataStore.data
        .map { it[Keys.ACTIVE_READER_STYLE] ?: "preset_paper" }

    suspend fun setActiveReaderStyle(id: String) = update(Keys.ACTIVE_READER_STYLE, id)

    val screenOrientation: Flow<Int> = context.dataStore.data
        .map { it[Keys.SCREEN_ORIENTATION] ?: -1 }
    suspend fun setScreenOrientation(value: Int) = update(Keys.SCREEN_ORIENTATION, value)

    val textSelectable: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.TEXT_SELECTABLE] ?: true }
    suspend fun setTextSelectable(enabled: Boolean) = update(Keys.TEXT_SELECTABLE, enabled)

    val chineseConvertMode: Flow<Int> = context.dataStore.data
        .map { it[Keys.CHINESE_CONVERT_MODE] ?: 0 }
    suspend fun setChineseConvertMode(mode: Int) = update(Keys.CHINESE_CONVERT_MODE, mode)

    // Tap zone actions
    val tapActionTopLeft: Flow<String> = context.dataStore.data.map { it[Keys.TAP_ACTION_TOP_LEFT] ?: "prev" }
    val tapActionTopRight: Flow<String> = context.dataStore.data.map { it[Keys.TAP_ACTION_TOP_RIGHT] ?: "next" }
    val tapActionBottomLeft: Flow<String> = context.dataStore.data.map { it[Keys.TAP_ACTION_BOTTOM_LEFT] ?: "prev" }
    val tapActionBottomRight: Flow<String> = context.dataStore.data.map { it[Keys.TAP_ACTION_BOTTOM_RIGHT] ?: "next" }
    suspend fun setTapAction(key: Preferences.Key<String>, action: String) = update(key, action)

    // Header/footer slots
    val headerLeft: Flow<String> = context.dataStore.data.map { it[Keys.HEADER_LEFT] ?: "time" }
    val headerCenter: Flow<String> = context.dataStore.data.map { it[Keys.HEADER_CENTER] ?: "none" }
    val headerRight: Flow<String> = context.dataStore.data.map { it[Keys.HEADER_RIGHT] ?: "battery" }
    val footerLeft: Flow<String> = context.dataStore.data.map { it[Keys.FOOTER_LEFT] ?: "chapter" }
    val footerCenter: Flow<String> = context.dataStore.data.map { it[Keys.FOOTER_CENTER] ?: "none" }
    val footerRight: Flow<String> = context.dataStore.data.map { it[Keys.FOOTER_RIGHT] ?: "progress" }
    suspend fun setHeaderFooter(key: Preferences.Key<String>, value: String) = update(key, value)

    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP] ?: false }
    suspend fun setAutoBackup(enabled: Boolean) = update(Keys.AUTO_BACKUP, enabled)
    val lastAutoBackup: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_AUTO_BACKUP] ?: 0L }
    suspend fun setLastAutoBackup(time: Long) = update(Keys.LAST_AUTO_BACKUP, time)

    val readerEngine: Flow<String> = context.dataStore.data.map { it[Keys.READER_ENGINE] ?: "canvas" }
    suspend fun setReaderEngine(engine: String) = update(Keys.READER_ENGINE, engine)

    suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setActiveTheme(id: String, isNight: Boolean = true) {
        // Write to sync SharedPreferences first (for instant startup read)
        themePrefs.edit()
            .putString("active_theme_id", id)
            .putBoolean("active_theme_is_night", isNight)
            .apply()
        update(Keys.ACTIVE_THEME_ID, id)
    }
    suspend fun setReaderFontSize(size: Float) = update(Keys.READER_FONT_SIZE, size)
    suspend fun setReaderLineHeight(height: Float) = update(Keys.READER_LINE_HEIGHT, height)
    suspend fun setReaderMargin(margin: Int) = update(Keys.READER_MARGIN, margin)
    suspend fun setReaderFontFamily(family: String) = update(Keys.READER_FONT_FAMILY, family)
    suspend fun setReaderTitleFontFamily(family: String) = update(Keys.READER_TITLE_FONT_FAMILY, family)
    suspend fun setReaderTitleFontWeight(weight: Int) = update(Keys.READER_TITLE_FONT_WEIGHT, weight)
    suspend fun setPageTurnMode(mode: String) = update(Keys.PAGE_TURN_MODE, mode)
    suspend fun setFullscreenTap(enabled: Boolean) = update(Keys.FULLSCREEN_TAP, enabled)
    suspend fun setTtsEngine(engine: String) = update(Keys.TTS_ENGINE, engine)
    suspend fun setTtsSpeed(speed: Float) = update(Keys.TTS_SPEED, speed)
    suspend fun setShelfViewMode(mode: String) = update(Keys.SHELF_VIEW_MODE, mode)
    suspend fun setAutoNightMode(enabled: Boolean) {
        themePrefs.edit().putBoolean("auto_night_mode", enabled).apply()
        update(Keys.AUTO_NIGHT_MODE, enabled)
    }
    suspend fun setSourceFilterMinWords(min: Int) = update(Keys.SOURCE_FILTER_MIN_WORDS, min)
    suspend fun setSourceFilterMaxWords(max: Int) = update(Keys.SOURCE_FILTER_MAX_WORDS, max)
    suspend fun setCustomFont(uri: String, name: String) {
        update(Keys.CUSTOM_FONT_URI, uri)
        update(Keys.CUSTOM_FONT_NAME, name)
    }
    suspend fun clearCustomFont() {
        update(Keys.CUSTOM_FONT_URI, "")
        update(Keys.CUSTOM_FONT_NAME, "")
    }
    suspend fun setDisclaimerAccepted() = update(Keys.DISCLAIMER_ACCEPTED, true)
    suspend fun setPageAnim(anim: String) = update(Keys.PAGE_ANIM, anim)
    suspend fun setTapLeftAction(action: String) = update(Keys.TAP_LEFT_ACTION, action)
    suspend fun setVolumeKeyPage(enabled: Boolean) = update(Keys.VOLUME_KEY_PAGE, enabled)
    suspend fun setResumeLastRead(enabled: Boolean) = update(Keys.RESUME_LAST_READ, enabled)
    suspend fun setLongPressUnderline(enabled: Boolean) = update(Keys.LONG_PRESS_UNDERLINE, enabled)
    suspend fun setScreenTimeout(seconds: Int) = update(Keys.SCREEN_TIMEOUT, seconds)
    suspend fun setShowStatusBar(show: Boolean) = update(Keys.SHOW_STATUS_BAR, show)
    suspend fun setShowChapterName(show: Boolean) = update(Keys.SHOW_CHAPTER_NAME, show)
    suspend fun setShowTimeBattery(show: Boolean) = update(Keys.SHOW_TIME_BATTERY, show)
    suspend fun setParagraphSpacing(spacing: Float) = update(Keys.PARAGRAPH_SPACING, spacing)
    suspend fun setMarginTop(margin: Int) = update(Keys.MARGIN_TOP, margin)
    suspend fun setMarginBottom(margin: Int) = update(Keys.MARGIN_BOTTOM, margin)
    suspend fun setCustomCss(css: String) = update(Keys.CUSTOM_CSS, css)
    suspend fun setCustomBgImage(uri: String) = update(Keys.CUSTOM_BG_IMAGE, uri)
    suspend fun setCustomTxtChapterRegex(regex: String) = update(Keys.CUSTOM_TXT_CHAPTER_REGEX, regex)
    suspend fun setTtsSkipPattern(pattern: String) = update(Keys.TTS_SKIP_PATTERN, pattern)
    suspend fun setTtsVoice(voice: String) = update(Keys.TTS_VOICE, voice)
}
