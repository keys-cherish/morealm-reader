package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages reader settings/preferences delegation for ReaderViewModel.
 */
class ReaderSettingsController(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val context: Context,
    private val readerStyleDao: com.morealm.app.domain.db.ReaderStyleDao,
) {
    // ── Preference flows ──
    val pageTurnMode: StateFlow<PageTurnMode> = prefs.pageTurnMode
        .map { key -> PageTurnMode.entries.find { it.key == key } ?: PageTurnMode.SCROLL }
        .stateIn(scope, SharingStarted.Eagerly, PageTurnMode.SCROLL)

    val fontFamily: StateFlow<String> = prefs.readerFontFamily
        .stateIn(scope, SharingStarted.Eagerly, "noto_serif_sc")

    val fontSize: StateFlow<Float> = prefs.readerFontSize
        .stateIn(scope, SharingStarted.Eagerly, 17f)

    val lineHeight: StateFlow<Float> = prefs.readerLineHeight
        .stateIn(scope, SharingStarted.Eagerly, 2.0f)

    val customFontUri: StateFlow<String> = prefs.customFontUri
        .stateIn(scope, SharingStarted.Eagerly, "")

    val customFontName: StateFlow<String> = prefs.customFontName
        .stateIn(scope, SharingStarted.Eagerly, "")

    val volumeKeyPage: StateFlow<Boolean> = prefs.volumeKeyPage
        .stateIn(scope, SharingStarted.Eagerly, true)

    val screenTimeout: StateFlow<Int> = prefs.screenTimeout
        .stateIn(scope, SharingStarted.Eagerly, -1)

    val showChapterName: StateFlow<Boolean> = prefs.showChapterName
        .stateIn(scope, SharingStarted.Eagerly, true)

    val showTimeBattery: StateFlow<Boolean> = prefs.showTimeBattery
        .stateIn(scope, SharingStarted.Eagerly, true)

    val showStatusBar: StateFlow<Boolean> = prefs.showStatusBar
        .stateIn(scope, SharingStarted.Eagerly, false)

    val tapLeftAction: StateFlow<String> = prefs.tapLeftAction
        .stateIn(scope, SharingStarted.Eagerly, "prev")

    val pageAnim: StateFlow<String> = prefs.pageAnim
        .stateIn(scope, SharingStarted.Eagerly, "none")

    val screenOrientation: StateFlow<Int> = prefs.screenOrientation
        .stateIn(scope, SharingStarted.Eagerly, -1)

    val textSelectable: StateFlow<Boolean> = prefs.textSelectable
        .stateIn(scope, SharingStarted.Eagerly, true)

    val chineseConvertMode: StateFlow<Int> = prefs.chineseConvertMode
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val readerEngine: StateFlow<String> = prefs.readerEngine
        .stateIn(scope, SharingStarted.Eagerly, "canvas")

    val paragraphSpacing: StateFlow<Float> = prefs.paragraphSpacing
        .stateIn(scope, SharingStarted.Eagerly, 1.4f)

    val marginHorizontal: StateFlow<Int> = prefs.readerMargin
        .stateIn(scope, SharingStarted.Eagerly, 24)

    val marginTop: StateFlow<Int> = prefs.marginTop
        .stateIn(scope, SharingStarted.Eagerly, 24)

    val marginBottom: StateFlow<Int> = prefs.marginBottom
        .stateIn(scope, SharingStarted.Eagerly, 24)

    val customCss: StateFlow<String> = prefs.customCss
        .stateIn(scope, SharingStarted.Eagerly, "")

    val customBgImage: StateFlow<String> = prefs.customBgImage
        .stateIn(scope, SharingStarted.Eagerly, "")

    // ── Tap zone customization ──
    val tapActionTopLeft: StateFlow<String> = prefs.tapActionTopLeft.stateIn(scope, SharingStarted.Eagerly, "prev")
    val tapActionTopRight: StateFlow<String> = prefs.tapActionTopRight.stateIn(scope, SharingStarted.Eagerly, "next")
    val tapActionBottomLeft: StateFlow<String> = prefs.tapActionBottomLeft.stateIn(scope, SharingStarted.Eagerly, "prev")
    val tapActionBottomRight: StateFlow<String> = prefs.tapActionBottomRight.stateIn(scope, SharingStarted.Eagerly, "next")

    // ── Header/footer customization ──
    val headerLeft: StateFlow<String> = prefs.headerLeft.stateIn(scope, SharingStarted.Eagerly, "time")
    val headerCenter: StateFlow<String> = prefs.headerCenter.stateIn(scope, SharingStarted.Eagerly, "none")
    val headerRight: StateFlow<String> = prefs.headerRight.stateIn(scope, SharingStarted.Eagerly, "battery")
    val footerLeft: StateFlow<String> = prefs.footerLeft.stateIn(scope, SharingStarted.Eagerly, "chapter")
    val footerCenter: StateFlow<String> = prefs.footerCenter.stateIn(scope, SharingStarted.Eagerly, "none")
    val footerRight: StateFlow<String> = prefs.footerRight.stateIn(scope, SharingStarted.Eagerly, "progress")

    // ── Reader Style Presets ──
    val allStyles: StateFlow<List<com.morealm.app.domain.entity.ReaderStyle>> =
        readerStyleDao.getAll()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _activeStyleId = MutableStateFlow("preset_paper")
    val activeStyleId: StateFlow<String> = _activeStyleId.asStateFlow()

    val activeStyle: StateFlow<com.morealm.app.domain.entity.ReaderStyle?> =
        combine(allStyles, _activeStyleId) { styles, id ->
            styles.find { it.id == id } ?: styles.firstOrNull()
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun initialize() {
        scope.launch(Dispatchers.IO) {
            if (readerStyleDao.count() == 0) {
                readerStyleDao.upsertAll(com.morealm.app.domain.entity.ReaderStyle.defaults())
            }
        }
        scope.launch {
            _activeStyleId.value = prefs.activeReaderStyle.first()
        }
    }

    // ── Setters ──

    fun setPageTurnMode(mode: PageTurnMode) {
        scope.launch {
            prefs.setPageTurnMode(mode.key)
            AppLog.info("Reader", "Page turn mode: ${mode.label}")
        }
    }

    fun setFontFamily(family: String) {
        scope.launch {
            prefs.setReaderFontFamily(family)
            AppLog.info("Reader", "Font family: $family")
        }
    }

    fun setFontSize(size: Float) {
        scope.launch {
            prefs.setReaderFontSize(size)
            AppLog.info("Reader", "Font size: $size")
        }
    }

    fun setLineHeight(height: Float) {
        scope.launch {
            prefs.setReaderLineHeight(height)
            AppLog.info("Reader", "Line height: $height")
        }
    }

    fun importCustomFont(uri: Uri, name: String) {
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.setCustomFont(uri.toString(), name)
                prefs.setReaderFontFamily("custom")
                AppLog.info("Reader", "Imported custom font: $name")
            } catch (e: Exception) {
                AppLog.error("Reader", "Failed to import font", e)
            }
        }
    }

    fun clearCustomFont() {
        scope.launch {
            prefs.clearCustomFont()
            prefs.setReaderFontFamily("noto_serif_sc")
        }
    }

    fun setScreenOrientation(value: Int) {
        scope.launch { prefs.setScreenOrientation(value) }
    }

    fun setTextSelectable(enabled: Boolean) {
        scope.launch { prefs.setTextSelectable(enabled) }
    }

    fun setReaderEngine(engine: String) {
        scope.launch { prefs.setReaderEngine(engine) }
    }

    fun setChineseConvertMode(mode: Int) {
        scope.launch { prefs.setChineseConvertMode(mode) }
    }

    fun setTapAction(zone: String, action: String) {
        val key = when (zone) {
            "topLeft" -> AppPreferences.Keys.TAP_ACTION_TOP_LEFT
            "topRight" -> AppPreferences.Keys.TAP_ACTION_TOP_RIGHT
            "bottomLeft" -> AppPreferences.Keys.TAP_ACTION_BOTTOM_LEFT
            "bottomRight" -> AppPreferences.Keys.TAP_ACTION_BOTTOM_RIGHT
            else -> return
        }
        scope.launch { prefs.setTapAction(key, action) }
    }

    fun setHeaderFooter(slot: String, value: String) {
        val key = when (slot) {
            "headerLeft" -> AppPreferences.Keys.HEADER_LEFT
            "headerCenter" -> AppPreferences.Keys.HEADER_CENTER
            "headerRight" -> AppPreferences.Keys.HEADER_RIGHT
            "footerLeft" -> AppPreferences.Keys.FOOTER_LEFT
            "footerCenter" -> AppPreferences.Keys.FOOTER_CENTER
            "footerRight" -> AppPreferences.Keys.FOOTER_RIGHT
            else -> return
        }
        scope.launch { prefs.setHeaderFooter(key, value) }
    }

    fun switchStyle(styleId: String) {
        _activeStyleId.value = styleId
        scope.launch { prefs.setActiveReaderStyle(styleId) }
    }

    fun saveCurrentStyle(style: com.morealm.app.domain.entity.ReaderStyle) {
        scope.launch(Dispatchers.IO) { readerStyleDao.upsert(style) }
    }

    fun deleteStyle(styleId: String) {
        if (styleId.startsWith("preset_")) return
        scope.launch(Dispatchers.IO) {
            readerStyleDao.deleteById(styleId)
            if (_activeStyleId.value == styleId) {
                _activeStyleId.value = "preset_paper"
                prefs.setActiveReaderStyle("preset_paper")
            }
        }
    }

    fun setParagraphSpacing(value: Float) {
        scope.launch { prefs.setParagraphSpacing(value) }
    }

    fun setMarginHorizontal(value: Int) {
        scope.launch { prefs.setReaderMargin(value) }
    }

    fun setMarginTop(value: Int) {
        scope.launch { prefs.setMarginTop(value) }
    }

    fun setMarginBottom(value: Int) {
        scope.launch { prefs.setMarginBottom(value) }
    }

    fun setCustomCss(css: String) {
        scope.launch { prefs.setCustomCss(css) }
    }

    fun setCustomBgImage(uri: String) {
        scope.launch { prefs.setCustomBgImage(uri) }
    }
}
