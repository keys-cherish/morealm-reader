package com.morealm.app.presentation.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.morealm.app.domain.entity.ReaderStyle
import com.morealm.app.domain.font.FontRepository
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
    private val fontRepo: FontRepository,
) {
    // ══════════════════════════════════════════════════════════════
    // Behavioral settings (DataStore) — NOT part of visual style
    // ══════════════════════════════════════════════════════════════

    val pageTurnMode: StateFlow<PageTurnMode> = prefs.pageTurnMode
        .map { key -> PageTurnMode.entries.find { it.key == key } ?: PageTurnMode.SCROLL }
        .stateIn(scope, SharingStarted.Eagerly, PageTurnMode.SCROLL)

    /** 阅读方向：horizontal / vertical_rl。Phase 1 仅持久化，layout 暂未响应（Phase 2 落地）。 */
    val readingDirection: StateFlow<String> = prefs.readingDirection
        .stateIn(scope, SharingStarted.Eagerly, "horizontal")

    val customFontUri: StateFlow<String> = prefs.customFontUri
        .stateIn(scope, SharingStarted.Eagerly, "")

    val customFontName: StateFlow<String> = prefs.customFontName
        .stateIn(scope, SharingStarted.Eagerly, "")

    val volumeKeyPage: StateFlow<Boolean> = prefs.volumeKeyPage
        .stateIn(scope, SharingStarted.Eagerly, true)

    val volumeKeyReverse: StateFlow<Boolean> = prefs.volumeKeyReverse
        .stateIn(scope, SharingStarted.Eagerly, false)

    val headsetButtonPage: StateFlow<Boolean> = prefs.headsetButtonPage
        .stateIn(scope, SharingStarted.Eagerly, false)

    val volumeKeyLongPress: StateFlow<String> = prefs.volumeKeyLongPress
        .stateIn(scope, SharingStarted.Eagerly, "off")

    val screenTimeout: StateFlow<Int> = prefs.screenTimeout
        .stateIn(scope, SharingStarted.Eagerly, -1)

    val isChapterNameVisible: StateFlow<Boolean> = prefs.isChapterNameVisible
        .stateIn(scope, SharingStarted.Eagerly, true)

    val isTimeBatteryVisible: StateFlow<Boolean> = prefs.isTimeBatteryVisible
        .stateIn(scope, SharingStarted.Eagerly, true)

    val isStatusBarVisible: StateFlow<Boolean> = prefs.isStatusBarVisible
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

    /** 选区 mini-menu 显示 / 顺序 / 主行分配的用户自定义。reader 端只读，订阅 prefs 即时生效。 */
    val selectionMenuConfig: StateFlow<com.morealm.app.domain.entity.SelectionMenuConfig> =
        prefs.selectionMenuConfig
            .stateIn(scope, SharingStarted.Eagerly, com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT)

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
        .map { it?.lineHeight ?: 1.4f }
        .stateIn(scope, SharingStarted.Eagerly, 1.4f)

    val fontFamily: StateFlow<String> = activeStyle
        .map { it?.fontFamily ?: "noto_serif_sc" }
        .stateIn(scope, SharingStarted.Eagerly, "noto_serif_sc")

    /**
     * 阅读器当前应用的 [Typeface]。组合 [fontFamily]（内置字体键）和 [customFontUri]
     * （用户自定义字体路径，优先级更高）实时计算。
     *
     * 加载逻辑全权委托 [FontRepository.resolveTypeface]，与字体管理页 / 其它调用点一致。
     * 自定义路径加载失败时由 repo 内部 fallback 到系统字体；这里**不**自动清空 prefs，
     * 避免在用户瞬时拔 SD 卡 / 切换 SAF 权限时把"已配置"状态吞掉。需要清理由 UI 主动触发。
     */
    val currentTypeface: StateFlow<Typeface> =
        combine(fontFamily, customFontUri) { fam, uri -> fam to uri }
            .map { (fam, uri) -> fontRepo.resolveTypeface(fam, uri) }
            .stateIn(scope, SharingStarted.Eagerly, Typeface.DEFAULT)

    val paragraphSpacing: StateFlow<Int> = activeStyle
        .map { it?.paragraphSpacing ?: 8 }
        .stateIn(scope, SharingStarted.Eagerly, 8)

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

    // pageAnim 是全局行为设置，不跟随样式预设切换。
    // 修复：从 DataStore 读取，避免切样式时动画被重置。
    val pageAnim: StateFlow<String> = prefs.pageAnim
        .stateIn(scope, SharingStarted.Eagerly, "slide")

    /**
     * 章节标题对齐：0=左 / 1=中 / 2=右。
     * 来自 [com.morealm.app.domain.preference.AppPreferences.titleAlign] 全局偏好；
     * UI 层在 ReadingSettingsScreen 修改，阅读器实时透传到排版引擎。
     */
    val titleAlign: StateFlow<Int> = prefs.titleAlign
        .stateIn(scope, SharingStarted.Eagerly, 0)

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

    /** 切换阅读方向。Phase 1 只更新偏好，UI 暂不响应。 */
    fun setReadingDirection(value: String) {
        scope.launch { prefs.setReadingDirection(value) }
    }

    /**
     * 旧入口：直接复制 SAF URI 作为自定义字体（保留单文件兼容路径，但**不再首选**）。
     * 新流程是用 FontManagerScreen + FontRepository.importFromUri 复制到 App 字库。
     * 这里仅在外部模块直接复用 ReaderSettingsController 时兜底使用。
     */
    fun importCustomFont(uri: Uri, name: String) {
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.setCustomFont(uri.toString(), name)
                AppLog.info("Settings", "Imported custom font (legacy SAF path): $name")
            } catch (e: Exception) {
                AppLog.error("Settings", "Failed to import font", e)
            }
        }
    }

    fun clearCustomFont() {
        scope.launch {
            prefs.clearCustomFont()
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

    fun setParagraphSpacing(value: Int) = updateStyle { it.copy(paragraphSpacing = value) }

    fun setMarginHorizontal(value: Int) = updateStyle { it.copy(paddingLeft = value, paddingRight = value) }

    fun setMarginTop(value: Int) = updateStyle { it.copy(paddingTop = value) }

    fun setMarginBottom(value: Int) = updateStyle { it.copy(paddingBottom = value) }

    fun setCustomCss(css: String) = updateStyle { it.copy(customCss = css) }

    fun setCustomBgImage(uri: String) = updateStyle { it.copy(customBgImage = uri) }

    fun setPageAnim(anim: String) {
        scope.launch { prefs.setPageAnim(anim) }
    }

    /**
     * #1 「恢复出厂」一键回退所有阅读相关设置到内置默认值。
     *
     * 范围（**用户期望「一切皆还原为起点」，不再是历史的「轻还原」**）：
     *  - **ReaderStyle**：当前 active style 全字段重置为 [ReaderStyle] 默认值（含
     *    bg/text 颜色、字体、字号、行距、段距、对齐、indent、padding、自定义 css/bg
     *    图等）。同时把 5 个 builtin preset 重置回 [ReaderStyle.defaults]，防御
     *    用户曾经改过 preset 内部参数。
     *  - **active style id** 切回 `preset_paper`（首次启动时的默认）。
     *  - **DataStore prefs**（阅读器范围）：pageTurnMode / pageAnim / 屏幕方向 /
     *    划词可选 / 繁简模式 / 状态栏开关 / 章节名 & 时间电量 / 屏幕亮度永亮 /
     *    音量键 & 耳机键 & 长按 / 4 个 tap zone / 6 个 header/footer slot /
     *    标题对齐 / day & night 阅读区背景图 / 选区菜单顺序。
     *
     * **不动**的范围：
     *  - TTS 引擎 / 速度 / voice 等（属于"读"功能，与排版无关）
     *  - WebDav / 备份 / 全局背景图 / shelf 排序等（属于全局应用设置）
     *  - 用户自定义的非 builtin styles（保留在 styleRepo 里，只是 active 切回 preset_paper）
     *
     * 注意：updateStyle 走 IO 协程异步写 Room；`prefs.set*` 走自己的 IO 协程。
     * 调用方点完按钮后 ~50ms 内 StateFlow 全部 emit，CanvasRenderer 自然重排版。
     */
    fun resetAllToFactoryDefaults() {
        scope.launch(Dispatchers.IO) {
            val current = activeStyle.value
            // 当前 active style 字段全重置（保留 id / sortOrder / isBuiltin 等身份字段，
            // 用 ReaderStyle(id = ...) 拿默认值再 copy 身份字段）。
            if (current != null) {
                val d = ReaderStyle(id = current.id)
                styleRepo.upsert(
                    d.copy(
                        name = current.name,
                        sortOrder = current.sortOrder,
                        isBuiltin = current.isBuiltin,
                    ),
                )
            }
            // 5 个 builtin preset 强制刷回 defaults——防御用户曾经在某个 preset 上
            // 自己改过 paragraphSpacing/textSize 等：用户期望的"出厂"不只是当前 active，
            // 也包含切到其它 preset 时仍是出厂值。
            styleRepo.upsertAll(ReaderStyle.defaults())

            // active id 回到 preset_paper（与冷启动首次默认一致）。
            _activeStyleId.value = "preset_paper"
            prefs.setActiveReaderStyle("preset_paper")

            // ── 阅读相关 prefs 全面 reset 到默认值 ─────────────────────────
            prefs.setPageTurnMode(PageTurnMode.SCROLL.key)
            prefs.setPageAnim("slide")
            prefs.setReadingDirection("horizontal")
            prefs.setScreenOrientation(-1)
            prefs.setTextSelectable(true)
            prefs.setChineseConvertMode(0)
            prefs.setShowChapterName(true)
            prefs.setShowTimeBattery(true)
            prefs.setShowStatusBar(false)
            prefs.setScreenTimeout(-1)
            prefs.setVolumeKeyPage(true)
            prefs.setVolumeKeyReverse(false)
            prefs.setHeadsetButtonPage(false)
            prefs.setVolumeKeyLongPress("off")
            prefs.setTitleAlign(0)
            prefs.setReaderBgImageDay("")
            prefs.setReaderBgImageNight("")

            // 4 个 tap zone：默认值与 ReaderSettingsController 各 StateFlow 的初值对齐
            // (topLeft="prev" / topRight="next" / bottomLeft="prev" / bottomRight="next")。
            prefs.setTapAction(AppPreferences.Keys.TAP_ACTION_TOP_LEFT, "prev")
            prefs.setTapAction(AppPreferences.Keys.TAP_ACTION_TOP_RIGHT, "next")
            prefs.setTapAction(AppPreferences.Keys.TAP_ACTION_BOTTOM_LEFT, "prev")
            prefs.setTapAction(AppPreferences.Keys.TAP_ACTION_BOTTOM_RIGHT, "next")

            // 6 个 header/footer slot：默认值同 controller 各 StateFlow 初值。
            prefs.setHeaderFooter(AppPreferences.Keys.HEADER_LEFT, "chapter")
            prefs.setHeaderFooter(AppPreferences.Keys.HEADER_CENTER, "none")
            prefs.setHeaderFooter(AppPreferences.Keys.HEADER_RIGHT, "none")
            prefs.setHeaderFooter(AppPreferences.Keys.FOOTER_LEFT, "battery_time")
            prefs.setHeaderFooter(AppPreferences.Keys.FOOTER_CENTER, "none")
            prefs.setHeaderFooter(AppPreferences.Keys.FOOTER_RIGHT, "page_progress")

            // 选区菜单：恢复 DEFAULT 顺序与可见性。
            prefs.setSelectionMenuConfig(com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT)

            AppLog.info("Settings", "resetAllToFactoryDefaults: completed")
        }
    }

    // ── Style preset management ──

    fun switchStyle(styleId: String) {
        val before = _activeStyleId.value
        // 用户报"切样式出现翻页动画"——根因待定，先打点观察上下游：
        // ① 切换瞬间 _activeStyleId 翻新；
        // ② activeStyle 衍生流（fontSize / lineHeight / typeface / paddings / customCss）会陆续 emit；
        // ③ CanvasRenderer 的 layoutInputs 重排版 → pageCount 突变 → 看 HorizontalPager 是否 fling。
        AppLog.debug("StyleSwitch", "switchStyle from=$before to=$styleId")
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
