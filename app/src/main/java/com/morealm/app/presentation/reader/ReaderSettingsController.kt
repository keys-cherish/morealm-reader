package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.ReaderStyle
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.ReaderStyleRepository
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages reader settings/preferences delegation for ReaderViewModel.
 *
 * Visual/layout settings live in [ReaderStyle] (Room) — single source of truth.
 * Behavioral/system settings stay in [AppPreferences] (DataStore).
 */
class ReaderSettingsController(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val context: Context,
    private val styleRepo: ReaderStyleRepository,
) {
    // ══════════════════════════════════════════════════════════════
    // Behavioral settings (DataStore) — NOT part of visual style
    // ══════════════════════════════════════════════════════════════

    val pageTurnMode: StateFlow<PageTurnMode> = prefs.pageTurnMode
        .map { key -> PageTurnMode.entries.find { it.key == key } ?: PageTurnMode.SCROLL }
        .stateIn(scope, SharingStarted.Eagerly, PageTurnMode.SCROLL)

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

    val screenOrientation: StateFlow<Int> = prefs.screenOrientation
        .stateIn(scope, SharingStarted.Eagerly, -1)

    val textSelectable: StateFlow<Boolean> = prefs.textSelectable
        .stateIn(scope, SharingStarted.Eagerly, true)

    val chineseConvertMode: StateFlow<Int> = prefs.chineseConvertMode
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // Tap zone customization
    val tapActionTopLeft: StateFlow<String> = prefs.tapActionTopLeft.stateIn(scope, SharingStarted.Eagerly, "prev")
    val tapActionTopRight: StateFlow<String> = prefs.tapActionTopRight.stateIn(scope, SharingStarted.Eagerly, "next")
    val tapActionBottomLeft: StateFlow<String> = prefs.tapActionBottomLeft.stateIn(scope, SharingStarted.Eagerly, "prev")
    val tapActionBottomRight: StateFlow<String> = prefs.tapActionBottomRight.stateIn(scope, SharingStarted.Eagerly, "next")

    // Header/footer customization
    val headerLeft: StateFlow<String> = prefs.headerLeft.stateIn(scope, SharingStarted.Eagerly, "chapter")
    val headerCenter: StateFlow<String> = prefs.headerCenter.stateIn(scope, SharingStarted.Eagerly, "none")
    val headerRight: StateFlow<String> = prefs.headerRight.stateIn(scope, SharingStarted.Eagerly, "none")
    val footerLeft: StateFlow<String> = prefs.footerLeft.stateIn(scope, SharingStarted.Eagerly, "battery_time")
    val footerCenter: StateFlow<String> = prefs.footerCenter.stateIn(scope, SharingStarted.Eagerly, "none")
    val footerRight: StateFlow<String> = prefs.footerRight.stateIn(scope, SharingStarted.Eagerly, "page_progress")

    // ══════════════════════════════════════════════════════════════
    // Visual style (Room) — single source of truth: ReaderStyle
    // ══════════════════════════════════════════════════════════════

    val allStyles: StateFlow<List<ReaderStyle>> =
        styleRepo.getAll()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _activeStyleId = MutableStateFlow("preset_paper")
    val activeStyleId: StateFlow<String> = _activeStyleId.asStateFlow()

    val activeStyle: StateFlow<ReaderStyle?> =
        combine(allStyles, _activeStyleId) { styles, id ->
            styles.find { it.id == id } ?: styles.firstOrNull()
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // Derived convenience flows from activeStyle (for UI bindings that need individual values)
    val fontSize: StateFlow<Float> = activeStyle
        .map { it?.textSize?.toFloat() ?: 18f }
        .stateIn(scope, SharingStarted.Eagerly, 18f)

    val lineHeight: StateFlow<Float> = activeStyle
        .map { it?.lineHeight ?: 2.0f }
        .stateIn(scope, SharingStarted.Eagerly, 2.0f)

    val fontFamily: StateFlow<String> = activeStyle
        .map { it?.fontFamily ?: "noto_serif_sc" }
        .stateIn(scope, SharingStarted.Eagerly, "noto_serif_sc")

    val paragraphSpacing: StateFlow<Float> = activeStyle
        .map { it?.paragraphSpacing?.toFloat() ?: 8f }
        .stateIn(scope, SharingStarted.Eagerly, 8f)

    val marginHorizontal: StateFlow<Int> = activeStyle
        .map { it?.paddingLeft ?: 24 }
        .stateIn(scope, SharingStarted.Eagerly, 24)

    val marginTop: StateFlow<Int> = activeStyle
        .map { it?.paddingTop ?: 24 }
        .stateIn(scope, SharingStarted.Eagerly, 24)

    val marginBottom: StateFlow<Int> = activeStyle
        .map { it?.paddingBottom ?: 24 }
        .stateIn(scope, SharingStarted.Eagerly, 24)

    val customCss: StateFlow<String> = activeStyle
        .map { it?.customCss ?: "" }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val customBgImage: StateFlow<String> = activeStyle
        .map { it?.customBgImage ?: "" }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val readerBgImageDay: StateFlow<String> = prefs.readerBgImageDay
        .stateIn(scope, SharingStarted.Eagerly, "")

    val readerBgImageNight: StateFlow<String> = prefs.readerBgImageNight
        .stateIn(scope, SharingStarted.Eagerly, "")

    val pageAnim: StateFlow<String> = activeStyle
        .map { it?.pageAnim ?: "slide" }
        .stateIn(scope, SharingStarted.Eagerly, "slide")

    // ══════════════════════════════════════════════════════════════
    // Initialization
    // ══════════════════════════════════════════════════════════════

    fun initialize() {
        scope.launch(Dispatchers.IO) {
            styleRepo.ensureDefaults()
        }
        scope.launch {
            _activeStyleId.value = prefs.activeReaderStyle.first()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Setters — behavioral (DataStore)
    // ══════════════════════════════════════════════════════════════

    fun setPageTurnMode(mode: PageTurnMode) {
        scope.launch { prefs.setPageTurnMode(mode.key) }
    }

    fun importCustomFont(uri: Uri, name: String) {
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.setCustomFont(uri.toString(), name)
                prefs.setReaderFontFamily("custom")
                AppLog.info("Settings", "Imported custom font: $name")
            } catch (e: Exception) {
                AppLog.error("Settings", "Failed to import font", e)
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

    // ══════════════════════════════════════════════════════════════
    // Setters — visual style (write to Room via activeStyle)
    // ══════════════════════════════════════════════════════════════

    private fun updateStyle(transform: (ReaderStyle) -> ReaderStyle) {
        scope.launch(Dispatchers.IO) {
            val current = activeStyle.value ?: return@launch
            styleRepo.upsert(transform(current))
        }
    }

    fun setFontFamily(family: String) = updateStyle { it.copy(fontFamily = family) }

    fun setFontSize(size: Float) = updateStyle { it.copy(textSize = size.toInt()) }

    fun setLineHeight(height: Float) = updateStyle { it.copy(lineHeight = height) }

    fun setParagraphSpacing(value: Float) = updateStyle { it.copy(paragraphSpacing = value.toInt()) }

    fun setMarginHorizontal(value: Int) = updateStyle { it.copy(paddingLeft = value, paddingRight = value) }

    fun setMarginTop(value: Int) = updateStyle { it.copy(paddingTop = value) }

    fun setMarginBottom(value: Int) = updateStyle { it.copy(paddingBottom = value) }

    fun setCustomCss(css: String) = updateStyle { it.copy(customCss = css) }

    fun setCustomBgImage(uri: String) = updateStyle { it.copy(customBgImage = uri) }

    fun setPageAnim(anim: String) = updateStyle { it.copy(pageAnim = anim) }

    // ── Style preset management ──

    fun switchStyle(styleId: String) {
        _activeStyleId.value = styleId
        scope.launch { prefs.setActiveReaderStyle(styleId) }
    }

    fun saveCurrentStyle(style: ReaderStyle) {
        scope.launch(Dispatchers.IO) { styleRepo.upsert(style) }
    }

    fun deleteStyle(styleId: String) {
        if (styleId.startsWith("preset_")) return
        scope.launch(Dispatchers.IO) {
            styleRepo.deleteById(styleId)
            if (_activeStyleId.value == styleId) {
                _activeStyleId.value = "preset_paper"
                prefs.setActiveReaderStyle("preset_paper")
            }
        }
    }
}
