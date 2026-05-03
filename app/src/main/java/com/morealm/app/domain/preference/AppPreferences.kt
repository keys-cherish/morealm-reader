package com.morealm.app.domain.preference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        /**
         * 当 [TTS_ENGINE] = "system" 时绑定的具体 Android TTS 引擎包名（如
         * `com.google.android.tts`、`net.olekdia.multispeech`）。空字符串 = 跟随
         * 系统默认引擎（即不带 engineName 的 `TextToSpeech(context)` 行为）。
         *
         * 加这个 key 的原因：用户装了 MultiTTS 但 Android 默认 / 主进程
         * 没启动时，`TextToSpeech(context)` 不带 engineName 走系统默认引擎
         * 路径，OnInitListener 直接 status=ERROR，应用就报"未识别到 TTS 引擎"。
         * 让用户在「听书」页明确指定引擎包后，用 3 参构造 `TextToSpeech(context, listener, engineName)`
         * 直接绑那个包，就能避开默认引擎缺失的死路。
         */
        val TTS_SYSTEM_ENGINE_PACKAGE = stringPreferencesKey("tts_system_engine_package")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        /**
         * 是否在 TTS 播放期间持有 PARTIAL_WAKE_LOCK，强制让 CPU 不进 doze。
         *
         * 默认 **false**——和 Legado 默认对齐。Android 的 audio playback 本身在持有
         * 音频焦点时已经能避免 CPU 进 deep sleep；额外抢 wakelock 在大多数设备上
         * 是浪费电（夜读 8h 估计高 15-25%）。
         *
         * 个别国产 ROM（小米/华为某些版本）下锁屏后会偶发断声——遇到这种情况
         * 用户可以在「设置 → 朗读」里把这个开关打开，用更激进的保活策略换稳定性。
         */
        val TTS_KEEP_CPU_AWAKE = booleanPreferencesKey("tts_keep_cpu_awake")
        /**
         * 蓝牙耳机/有线耳机/Android Auto 上的「上一首/下一首」按键映射目标 ——
         * 默认 false：按一下走 **段级**（PrevParagraph/NextParagraph），符合 Legado 默认。
         *
         * 改为 true 时改成 **章级**：按一下直接切上/下章。适合用户场景：
         *  - 开车时单手控（章级跳转目的明确，段级误触多）
         *  - 长章节（一段段跳太慢）
         *  - 习惯 Legado 「按章」模式的老用户
         *
         * 锁屏通知按钮永远是章级，不受此偏好影响（按钮位置固定 + 标签写死「上一章/下一章」）。
         */
        val TTS_MEDIA_BUTTON_PER_CHAPTER = booleanPreferencesKey("tts_media_button_per_chapter")
        /**
         * 朗读时是否自动跟随翻页 —— 默认 true。
         *
         * 行为（由阅读器外层订阅 [com.morealm.app.service.TtsPlaybackState.paragraphRange] 实现）：
         *  - true：朗读越界当前页时，阅读器自动 gotoPage 到包含 chapterPosition 的页
         *  - false：用户必须点 "回到朗读位置" FAB 才回去
         *
         * 反向兜底：用户主动翻页后 `pauseAutoFollowUntil = now + 8s`，期间不自动跟随
         * （否则用户手指还在翻页就被自动拽回去）。这部分逻辑在 ReaderViewModel 而非偏好层。
         */
        val TTS_AUTO_FOLLOW_PAGE = booleanPreferencesKey("tts_auto_follow_page")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASS = stringPreferencesKey("webdav_pass")
        val SHELF_VIEW_MODE = stringPreferencesKey("shelf_view_mode")
        /**
         * 书源管理页的列表分组方式。取值见 ui 层的 SourceGroupMode enum 序列化字符串：
         *  - "none"       : 不分组，直接平铺（兼容旧行为，默认）
         *  - "group_name" : 按 BookSource.bookSourceGroup 分组（多分组场景把整个字段当一组）
         *  - "domain"     : 按 bookSourceUrl 提取的 host 分组
         *  - "type"       : 按 bookSourceType 分组（0=文本/1=音频/2=图片/3=文件/其他）
         *
         * 用 String 而非 Int：枚举值今后扩张（比如增加"按可用性分组"）时不需要 DB 迁移，
         * 异常值 fallback 到 "none"，比 enum ordinal 容错更好。
         */
        val SOURCE_GROUP_MODE = stringPreferencesKey("source_group_mode")
        val AUTO_NIGHT_MODE = booleanPreferencesKey("auto_night_mode")
        val SOURCE_FILTER_MIN_WORDS = intPreferencesKey("source_filter_min_words")
        val SOURCE_FILTER_MAX_WORDS = intPreferencesKey("source_filter_max_words")
        /**
         * 书源排序键。合法取值集中在 [com.morealm.app.presentation.source.SourceSortKey]
         * 的 `key` 字段；未知值 fallback 到 CUSTOM。用 String 让今后追加新维度（如「错误数」）
         * 时无需 DB 迁移，与 SOURCE_GROUP_MODE 同思路。
         */
        val SOURCE_SORT_BY = stringPreferencesKey("source_sort_by")
        /** 排序升降序。true = 升序（与 SourceSortKey.naturalAscending 配合得到默认方向）。 */
        val SOURCE_SORT_ASC = booleanPreferencesKey("source_sort_asc")
        val CUSTOM_FONT_URI = stringPreferencesKey("custom_font_uri")
        val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name")
        /**
         * 用户用 OpenDocumentTree 挂载的"外部字体文件夹"持久化 URI。
         * 与 [CUSTOM_FONT_URI]（当前选中那一只字体）正交：
         *   - 文件夹只是浏览源
         *   - 选中的字体路径仍写到 [CUSTOM_FONT_URI]，可能来自 App 字库（file://）或外部（content://）
         */
        val FONT_FOLDER_URI = stringPreferencesKey("font_folder_uri")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        // Reading settings
        val PAGE_ANIM = stringPreferencesKey("page_anim") // cover/simulation/slide/vertical/fade/none
        val TAP_LEFT_ACTION = stringPreferencesKey("tap_left_action") // next/prev
        val VOLUME_KEY_PAGE = booleanPreferencesKey("volume_key_page")
        val VOLUME_KEY_REVERSE = booleanPreferencesKey("volume_key_reverse")
        val HEADSET_BUTTON_PAGE = booleanPreferencesKey("headset_button_page")
        val VOLUME_KEY_LONG_PRESS = stringPreferencesKey("volume_key_long_press") // off|page|chapter
        val RESUME_LAST_READ = booleanPreferencesKey("resume_last_read")
        val LONG_PRESS_UNDERLINE = booleanPreferencesKey("long_press_underline")
        val SCREEN_TIMEOUT = intPreferencesKey("screen_timeout") // 0=system, -1=never, else seconds
        val SHOW_STATUS_BAR = booleanPreferencesKey("show_status_bar")
        val SHOW_CHAPTER_NAME = booleanPreferencesKey("show_chapter_name")
        val SHOW_TIME_BATTERY = booleanPreferencesKey("show_time_battery")
        /**
         * 章节标题对齐方式。0=左对齐 / 1=居中 / 2=右对齐。
         * 全局生效，不区分阅读样式预设；ReaderStyle.titleMode 仅保留隐藏 (==2) 语义。
         */
        val TITLE_ALIGN = intPreferencesKey("title_align")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val MARGIN_TOP = intPreferencesKey("margin_top")
        val MARGIN_BOTTOM = intPreferencesKey("margin_bottom")
        val CUSTOM_CSS = stringPreferencesKey("custom_css")
        val CUSTOM_BG_IMAGE = stringPreferencesKey("custom_bg_image")
        val READER_BG_IMAGE_DAY = stringPreferencesKey("reader_bg_image_day")
        val READER_BG_IMAGE_NIGHT = stringPreferencesKey("reader_bg_image_night")
        // ── 全局背景图（书架/发现/听书/我的 四 Tab 共用；阅读器自成一套不受此影响）──
        val GLOBAL_BG_IMAGE = stringPreferencesKey("global_bg_image")
        /** 0.3~1.0；默认 0.85。透传给各 Tab 内的 Card/Surface，实现"背景图+半透明卡片"叠层效果。 */
        val GLOBAL_BG_CARD_ALPHA = floatPreferencesKey("global_bg_card_alpha")
        /** 0~25dp；默认 0。Modifier.blur 需 API 31+，低版本忽略（UI 灰掉 Slider）。 */
        val GLOBAL_BG_CARD_BLUR = floatPreferencesKey("global_bg_card_blur")
        val CUSTOM_TXT_CHAPTER_REGEX = stringPreferencesKey("custom_txt_chapter_regex")
        val TTS_SKIP_PATTERN = stringPreferencesKey("tts_skip_pattern")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_SYSTEM_VOICE = stringPreferencesKey("tts_system_voice")
        val TTS_EDGE_VOICE = stringPreferencesKey("tts_edge_voice")
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
        /** Selection mini-menu customization (custom button list / order / main-row split).
         *  Stored as JSON of [SelectionMenuConfig] —— null/blank → DEFAULT. */
        val SELECTION_MENU_CONFIG = stringPreferencesKey("selection_menu_config")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val LAST_AUTO_BACKUP = longPreferencesKey("last_auto_backup")
        // ── WebDav P1 keys ─────────────────────────────────────────────
        /** Sub-folder appended to the user-supplied WebDav root (default: "MoRealm"). */
        val WEBDAV_DIR = stringPreferencesKey("webdav_dir")
        /** Optional device name appended to backup filename (e.g. "Pixel" → backup_20260501_Pixel.zip). */
        val WEBDAV_DEVICE_NAME = stringPreferencesKey("webdav_device_name")
        /** When true, reader chapter changes upload BookProgress JSON; startup fetches and merges. */
        val SYNC_BOOK_PROGRESS = booleanPreferencesKey("sync_book_progress")
        /** When true, only `backup_latest.zip` is kept on remote (overwritten); else timestamped + latest. */
        val ONLY_LATEST_BACKUP = booleanPreferencesKey("only_latest_backup")
        /** When true, restore skips local-file books to avoid invalid file-path entries from another device. */
        val IGNORE_LOCAL_BOOK = booleanPreferencesKey("ignore_local_book")
        /** When true, restore preserves the current device's reading-style / theme prefs instead of overwriting. */
        val IGNORE_READ_CONFIG = booleanPreferencesKey("ignore_read_config")
        /**
         * Backup encryption password. When non-empty, the backup zip is
         * wrapped in AES-GCM via [com.morealm.app.domain.sync.BackupCrypto]
         * before upload / SAF export. Empty = legacy plain zip behaviour.
         *
         * Stored in DataStore plain — same trust boundary as the WebDav
         * password. Users worried about device theft can set the OS-level
         * file encryption + screen lock; this field's purpose is to
         * protect the backup-at-rest on the cloud drive, not on disk.
         */
        val BACKUP_PASSWORD = stringPreferencesKey("backup_password")
        val READER_ENGINE = stringPreferencesKey("reader_engine") // "canvas" or "webview"
        val RECORD_LOG = booleanPreferencesKey("record_log") // detailed file logging
        // Auto-folder (since v18) — tag ids whose auto-created folder was deleted by user.
        // The classifier honours this set so a deleted "玄幻" folder doesn't reappear next
        // time a 3rd 玄幻 book is imported.
        val AUTO_FOLDER_IGNORED = stringSetPreferencesKey("auto_folder_ignored")
        /**
         * 章节内搜索的最近历史。换行分隔，最多 20 条；UI chip 展示。
         */
        val READER_SEARCH_HISTORY = stringPreferencesKey("reader_search_history")
        // Threshold (= number of books carrying the same genre tag) before the
        // classifier promotes that tag into a real folder. Lower = folders appear
        // sooner with less curation; higher = only "real" interests get a folder.
        val AUTO_FOLDER_THRESHOLD = intPreferencesKey("auto_folder_threshold")
        // 当一本 web 书没有任何 GENRE 标签命中时（典型：书源详情解析失败导致
        // kind/category/description 全空），是否允许 source 标签升级为兜底文件夹。
        // 默认 true —— 用户预期「立即整理」之后没有书停留在根目录看不到。
        val ALLOW_SOURCE_FALLBACK = booleanPreferencesKey("allow_source_fallback")
        // ── 搜索 / 换源 网络层调度参数 ────────────────────────────────────────
        //
        // 这两个值从 SearchViewModel / ChangeSourceController 之前的硬编码常量
        // (8 / 30s) 升级而来。提到 prefs 的动机：
        //  1. 用户网络/设备差异巨大 — 4G + 千元机用户跑 16 并发可能爆 socket，
        //     高速 Wi-Fi + 旗舰用户 32 并发也吃得消，硬编码服侍不了所有人。
        //  2. 部分书源对短时高频请求会临时拉黑 IP — 用户能调小并发来缓解。
        //  3. 单源超时调长，让弱网用户能拿到结果而不是一律「超时」。
        //
        // 默认值选择：
        //  - parallelism = 16 (旧默认 8 的两倍) — 与 legado-MD3 报告对齐；
        //    OkHttp 全局 dispatcher.maxRequests=64、per-host=5，16 并发不会撞上限。
        //  - timeoutSec = 30 — 维持旧行为，不主动让搜索体感变慢。
        //
        // 取值范围（在读侧 coerceIn 兜底）：
        //  - parallelism: 1..32  — 1 = 强行串行；32 = OkHttp 上限留余量
        //  - timeoutSec : 5..120 — 5s 是最低有效值；120s 是用户能接受的等待上限
        val SEARCH_PARALLELISM = intPreferencesKey("search_parallelism")
        val SOURCE_SEARCH_TIMEOUT_SEC = intPreferencesKey("source_search_timeout_sec")
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

    /** 系统 TTS 引擎包名；空 = 跟系统默认。详见 [Keys.TTS_SYSTEM_ENGINE_PACKAGE]。 */
    val ttsSystemEnginePackage: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_SYSTEM_ENGINE_PACKAGE] ?: "" }

    val ttsSpeed: Flow<Float> = context.dataStore.data
        .map { it[Keys.TTS_SPEED] ?: 1.0f }

    /** 见 [Keys.TTS_KEEP_CPU_AWAKE] —— 默认 false。 */
    val ttsKeepCpuAwake: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.TTS_KEEP_CPU_AWAKE] ?: false }

    /** 见 [Keys.TTS_MEDIA_BUTTON_PER_CHAPTER] —— 默认 false（段级）。 */
    val ttsMediaButtonPerChapter: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.TTS_MEDIA_BUTTON_PER_CHAPTER] ?: false }

    /** 见 [Keys.TTS_AUTO_FOLLOW_PAGE] —— 默认 true（朗读时自动跟随翻页）。 */
    val ttsAutoFollowPage: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.TTS_AUTO_FOLLOW_PAGE] ?: true }

    val shelfViewMode: Flow<String> = context.dataStore.data
        .map { it[Keys.SHELF_VIEW_MODE] ?: "grid" }

    /**
     * 书源管理页的分组模式 Flow。默认 "none"（不分组）—— 旧用户升级到这版本后
     * 行为完全不变，直到他主动点 chip 切换才会生效。
     */
    val sourceGroupMode: Flow<String> = context.dataStore.data
        .map { it[Keys.SOURCE_GROUP_MODE] ?: "none" }

    /**
     * 书源排序键 Flow。默认 "custom"（按 customOrder 排，与导入顺序一致）；旧用户
     * 没在 DataStore 里写过这两个键，所以升级后看到的列表顺序与升级前 100% 一致。
     */
    val sourceSortBy: Flow<String> = context.dataStore.data
        .map { it[Keys.SOURCE_SORT_BY] ?: "custom" }

    /**
     * 排序方向 Flow。默认 true（升序）。注意：BookSourceManageScreen 在做最终排序时
     * 会把这个 flag 与 [com.morealm.app.presentation.source.SourceSortKey.naturalAscending]
     * 异或，让"按响应时间"在升序时显示"快→慢"（直觉自然），而不是字面 ASC。
     */
    val sourceSortAscending: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SOURCE_SORT_ASC] ?: true }

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

    /** 外部字体文件夹（持久化 SAF Tree URI）。空 = 用户没挂外部目录，只用 App 字库。 */
    val fontFolderUri: Flow<String> = context.dataStore.data
        .map { it[Keys.FONT_FOLDER_URI] ?: "" }

    val disclaimerAccepted: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.DISCLAIMER_ACCEPTED] ?: false }

    val pageAnim: Flow<String> = context.dataStore.data
        .map { it[Keys.PAGE_ANIM] ?: "vertical" }

    val tapLeftAction: Flow<String> = context.dataStore.data
        .map { it[Keys.TAP_LEFT_ACTION] ?: "next" }

    val volumeKeyPage: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.VOLUME_KEY_PAGE] ?: true }

    /**
     * 音量键方向反转。默认 false：音量下=下一页、音量上=上一页（与系统/Legado 一致）。
     * 部分用户偏好"音量上=下一页"，开启该项即翻转。仅影响音量键，不影响 MEDIA_*。
     */
    val volumeKeyReverse: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.VOLUME_KEY_REVERSE] ?: false }

    /**
     * 耳机/蓝牙翻页器按键翻页。默认 false 避免误触。开启后阅读器响应：
     * - KEYCODE_MEDIA_NEXT / MEDIA_PREVIOUS （蓝牙翻页器常见映射）
     * - KEYCODE_PAGE_UP / PAGE_DOWN
     * - KEYCODE_DPAD_LEFT / DPAD_RIGHT
     * - KEYCODE_HEADSETHOOK（耳机线控单击：TTS 播放时切上下段，否则下一页）
     *
     * 注意：锁屏/后台的耳机线控走 MediaSession，不受此开关影响。
     */
    val headsetButtonPage: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.HEADSET_BUTTON_PAGE] ?: false }

    /**
     * 音量键长按行为：
     * - "off"     默认：长按只触发系统按键重复，等同多次单击翻页（OS 默认节奏）
     * - "page"    显式连续翻页（同 off，但语义上是"主动开启"）
     * - "chapter" 长按 ~1s 后跳到上/下一章
     */
    val volumeKeyLongPress: Flow<String> = context.dataStore.data
        .map { it[Keys.VOLUME_KEY_LONG_PRESS] ?: "off" }

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

    /**
     * 章节标题对齐：0=左 / 1=中 / 2=右。默认 0。
     * 排版引擎 [com.morealm.app.domain.render.ChapterProvider] 在画标题时按此值
     * 计算 startXOffset（左=0；中=(W-w)/2；右=W-w）。
     */
    val titleAlign: Flow<Int> = context.dataStore.data
        .map { it[Keys.TITLE_ALIGN] ?: 0 }

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

    val readerBgImageDay: Flow<String> = context.dataStore.data
        .map { it[Keys.READER_BG_IMAGE_DAY] ?: "" }

    val readerBgImageNight: Flow<String> = context.dataStore.data
        .map { it[Keys.READER_BG_IMAGE_NIGHT] ?: "" }

    // ── 全局背景图（四 Tab 共用）──
    val globalBgImage: Flow<String> = context.dataStore.data
        .map { it[Keys.GLOBAL_BG_IMAGE] ?: "" }
    val globalBgCardAlpha: Flow<Float> = context.dataStore.data
        .map { (it[Keys.GLOBAL_BG_CARD_ALPHA] ?: 0.85f).coerceIn(0.3f, 1.0f) }
    val globalBgCardBlur: Flow<Float> = context.dataStore.data
        .map { (it[Keys.GLOBAL_BG_CARD_BLUR] ?: 0f).coerceIn(0f, 25f) }

    val customTxtChapterRegex: Flow<String> = context.dataStore.data
        .map { it[Keys.CUSTOM_TXT_CHAPTER_REGEX] ?: "" }

    val ttsSkipPattern: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_SKIP_PATTERN] ?: "" }

    val ttsVoice: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_VOICE] ?: "" }

    val ttsSystemVoice: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_SYSTEM_VOICE] ?: "" }

    val ttsEdgeVoice: Flow<String> = context.dataStore.data
        .map { it[Keys.TTS_EDGE_VOICE] ?: "" }

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
    val headerLeft: Flow<String> = context.dataStore.data.map { it[Keys.HEADER_LEFT] ?: "chapter" }
    val headerCenter: Flow<String> = context.dataStore.data.map { it[Keys.HEADER_CENTER] ?: "none" }
    val headerRight: Flow<String> = context.dataStore.data.map { it[Keys.HEADER_RIGHT] ?: "none" }
    val footerLeft: Flow<String> = context.dataStore.data.map { it[Keys.FOOTER_LEFT] ?: "battery_time" }
    val footerCenter: Flow<String> = context.dataStore.data.map { it[Keys.FOOTER_CENTER] ?: "none" }
    val footerRight: Flow<String> = context.dataStore.data.map { it[Keys.FOOTER_RIGHT] ?: "page_progress" }
    suspend fun setHeaderFooter(key: Preferences.Key<String>, value: String) = update(key, value)

    /**
     * Selection-menu customization. Backed by a JSON string in DataStore so the
     * full config (which item / which position / what order) round-trips with
     * a single key. `decode` returns DEFAULT on parse failure or absence, so
     * fresh installs and old DataStores both behave the same.
     */
    val selectionMenuConfig: Flow<com.morealm.app.domain.entity.SelectionMenuConfig> =
        context.dataStore.data.map {
            com.morealm.app.domain.entity.SelectionMenuConfig.decode(it[Keys.SELECTION_MENU_CONFIG])
        }
    suspend fun setSelectionMenuConfig(config: com.morealm.app.domain.entity.SelectionMenuConfig) =
        update(Keys.SELECTION_MENU_CONFIG, com.morealm.app.domain.entity.SelectionMenuConfig.encode(config))

    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP] ?: false }
    suspend fun setAutoBackup(enabled: Boolean) = update(Keys.AUTO_BACKUP, enabled)
    val lastAutoBackup: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_AUTO_BACKUP] ?: 0L }
    suspend fun setLastAutoBackup(time: Long) = update(Keys.LAST_AUTO_BACKUP, time)

    // ── WebDav P1 settings ─────────────────────────────────────────────
    val webDavDir: Flow<String> = context.dataStore.data.map { it[Keys.WEBDAV_DIR] ?: "MoRealm" }
    suspend fun setWebDavDir(dir: String) = update(Keys.WEBDAV_DIR, dir.trim().trim('/'))

    val webDavDeviceName: Flow<String> = context.dataStore.data.map { it[Keys.WEBDAV_DEVICE_NAME] ?: "" }
    suspend fun setWebDavDeviceName(name: String) = update(Keys.WEBDAV_DEVICE_NAME, name.trim())

    val syncBookProgress: Flow<Boolean> = context.dataStore.data.map { it[Keys.SYNC_BOOK_PROGRESS] ?: false }
    suspend fun setSyncBookProgress(enabled: Boolean) = update(Keys.SYNC_BOOK_PROGRESS, enabled)

    val onlyLatestBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONLY_LATEST_BACKUP] ?: false }
    suspend fun setOnlyLatestBackup(enabled: Boolean) = update(Keys.ONLY_LATEST_BACKUP, enabled)

    val ignoreLocalBook: Flow<Boolean> = context.dataStore.data.map { it[Keys.IGNORE_LOCAL_BOOK] ?: true }
    suspend fun setIgnoreLocalBook(enabled: Boolean) = update(Keys.IGNORE_LOCAL_BOOK, enabled)

    val ignoreReadConfig: Flow<Boolean> = context.dataStore.data.map { it[Keys.IGNORE_READ_CONFIG] ?: false }
    suspend fun setIgnoreReadConfig(enabled: Boolean) = update(Keys.IGNORE_READ_CONFIG, enabled)

    val backupPassword: Flow<String> = context.dataStore.data.map { it[Keys.BACKUP_PASSWORD] ?: "" }
    suspend fun setBackupPassword(pw: String) = update(Keys.BACKUP_PASSWORD, pw)

    val readerEngine: Flow<String> = context.dataStore.data.map { it[Keys.READER_ENGINE] ?: "canvas" }
    suspend fun setReaderEngine(engine: String) = update(Keys.READER_ENGINE, engine)

    val recordLog: Flow<Boolean> = context.dataStore.data.map { it[Keys.RECORD_LOG] ?: false }
    suspend fun setRecordLog(enabled: Boolean) = update(Keys.RECORD_LOG, enabled)

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

    /** 设置系统 TTS 引擎包；传空字符串恢复"跟系统默认"。 */
    suspend fun setTtsSystemEnginePackage(pkg: String) = update(Keys.TTS_SYSTEM_ENGINE_PACKAGE, pkg)
    suspend fun setTtsSpeed(speed: Float) = update(Keys.TTS_SPEED, speed)

    suspend fun setTtsKeepCpuAwake(enabled: Boolean) = update(Keys.TTS_KEEP_CPU_AWAKE, enabled)
    suspend fun setTtsMediaButtonPerChapter(enabled: Boolean) =
        update(Keys.TTS_MEDIA_BUTTON_PER_CHAPTER, enabled)
    suspend fun setTtsAutoFollowPage(enabled: Boolean) = update(Keys.TTS_AUTO_FOLLOW_PAGE, enabled)
    suspend fun setShelfViewMode(mode: String) = update(Keys.SHELF_VIEW_MODE, mode)
    /** 写入新的书源分组模式；调用方负责传入 [Keys.SOURCE_GROUP_MODE] 注释里列出的字符串。 */
    suspend fun setSourceGroupMode(mode: String) = update(Keys.SOURCE_GROUP_MODE, mode)

    /** 写入书源排序键。值的合法性由调用方（SourceSortKey.fromKey）保证；不合法值在读侧 fallback。 */
    suspend fun setSourceSortBy(key: String) = update(Keys.SOURCE_SORT_BY, key)
    suspend fun setSourceSortAscending(asc: Boolean) = update(Keys.SOURCE_SORT_ASC, asc)
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

    /**
     * 持久化外部字体文件夹的 Tree URI。**必须** 在 SAF 回调（OpenDocumentTree）里立刻调，
     * 因为 [android.content.ContentResolver.takePersistableUriPermission] 只能在系统授权
     * 当下的进程上下文里成功调用。
     *
     * Legado FontSelectDialog 没做这步，重启 App 后偶发文件夹失效；这里补上。
     * 旧 URI 替换时会主动 release 防止权限表无限膨胀。
     */
    /**
     * 持久化外部字体文件夹的 Tree URI。**必须** 在 SAF 回调（OpenDocumentTree）里立刻调，
     * 因为 [android.content.ContentResolver.takePersistableUriPermission] 只能在系统授权
     * 当下的进程上下文里成功调用。
     *
     * Legado FontSelectDialog 没做这步，重启 App 后偶发文件夹失效；这里补上。
     * 旧 URI 替换时会主动 release 防止权限表无限膨胀。
     */
    suspend fun setFontFolderUri(uri: String) {
        val readFlag = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val rwFlag = readFlag or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // 1) 释放旧的（如果有）。同一个进程里只能保留一条字体文件夹授权——
        //    用户切换文件夹的语义就是"丢掉旧的、用新的"。
        //    分别 release READ + READ|WRITE：极少数 OEM SAF 实现会用 RW 持久化，
        //    只 release READ 会留下死记录在权限表里，下次启动 OS 仍试访问。
        runCatching {
            val old = context.dataStore.data.first()[Keys.FONT_FOLDER_URI].orEmpty()
            if (old.isNotBlank() && old != uri) {
                val oldUri = android.net.Uri.parse(old)
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(oldUri, readFlag)
                }.onFailure {
                    AppLog.warn(
                        "FontFolder",
                        "release(READ) old=$old failed: ${it.message}",
                    )
                }
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(oldUri, rwFlag)
                }
                AppLog.info("FontFolder", "released old folder uri=$old")
            }
        }
        // 2) 取新的。`takePersistableUriPermission` 只能在 SAF 授权当前活跃时
        //    调用成功（这就是为什么必须从 launcher 回调里同步 launch 进来）。
        if (uri.isNotBlank()) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(uri), readFlag)
                AppLog.info("FontFolder", "took persistable READ on new uri=$uri")
            }.onFailure {
                AppLog.warn(
                    "FontFolder",
                    "takePersistable failed for $uri: ${it.message} —— UI 仍记录该 uri，但可能下次启动失效",
                )
            }
        }
        update(Keys.FONT_FOLDER_URI, uri)
    }
    suspend fun setDisclaimerAccepted() = update(Keys.DISCLAIMER_ACCEPTED, true)
    suspend fun setPageAnim(anim: String) = update(Keys.PAGE_ANIM, anim)
    suspend fun setTapLeftAction(action: String) = update(Keys.TAP_LEFT_ACTION, action)
    suspend fun setVolumeKeyPage(enabled: Boolean) = update(Keys.VOLUME_KEY_PAGE, enabled)
    suspend fun setVolumeKeyReverse(enabled: Boolean) = update(Keys.VOLUME_KEY_REVERSE, enabled)
    suspend fun setHeadsetButtonPage(enabled: Boolean) = update(Keys.HEADSET_BUTTON_PAGE, enabled)
    suspend fun setVolumeKeyLongPress(mode: String) = update(Keys.VOLUME_KEY_LONG_PRESS, mode)
    suspend fun setResumeLastRead(enabled: Boolean) = update(Keys.RESUME_LAST_READ, enabled)
    suspend fun setLongPressUnderline(enabled: Boolean) = update(Keys.LONG_PRESS_UNDERLINE, enabled)
    suspend fun setScreenTimeout(seconds: Int) = update(Keys.SCREEN_TIMEOUT, seconds)
    suspend fun setShowStatusBar(show: Boolean) = update(Keys.SHOW_STATUS_BAR, show)
    suspend fun setShowChapterName(show: Boolean) = update(Keys.SHOW_CHAPTER_NAME, show)
    suspend fun setShowTimeBattery(show: Boolean) = update(Keys.SHOW_TIME_BATTERY, show)
    /** 章节标题对齐：0=左/1=中/2=右。其他值会被 coerce 到 0。 */
    suspend fun setTitleAlign(align: Int) =
        update(Keys.TITLE_ALIGN, align.coerceIn(0, 2))
    suspend fun setParagraphSpacing(spacing: Float) = update(Keys.PARAGRAPH_SPACING, spacing)
    suspend fun setMarginTop(margin: Int) = update(Keys.MARGIN_TOP, margin)
    suspend fun setMarginBottom(margin: Int) = update(Keys.MARGIN_BOTTOM, margin)
    suspend fun setCustomCss(css: String) = update(Keys.CUSTOM_CSS, css)
    suspend fun setCustomBgImage(uri: String) = update(Keys.CUSTOM_BG_IMAGE, uri)
    suspend fun setReaderBgImageDay(uri: String) = update(Keys.READER_BG_IMAGE_DAY, uri)
    suspend fun setReaderBgImageNight(uri: String) = update(Keys.READER_BG_IMAGE_NIGHT, uri)
    suspend fun setGlobalBgImage(uri: String) = update(Keys.GLOBAL_BG_IMAGE, uri)
    suspend fun setGlobalBgCardAlpha(alpha: Float) = update(Keys.GLOBAL_BG_CARD_ALPHA, alpha.coerceIn(0.3f, 1.0f))
    suspend fun setGlobalBgCardBlur(blur: Float) = update(Keys.GLOBAL_BG_CARD_BLUR, blur.coerceIn(0f, 25f))
    suspend fun setCustomTxtChapterRegex(regex: String) = update(Keys.CUSTOM_TXT_CHAPTER_REGEX, regex)
    suspend fun setTtsSkipPattern(pattern: String) = update(Keys.TTS_SKIP_PATTERN, pattern)
    suspend fun setTtsVoice(voice: String) = update(Keys.TTS_VOICE, voice)
    suspend fun setTtsSystemVoice(voice: String) = update(Keys.TTS_SYSTEM_VOICE, voice)
    suspend fun setTtsEdgeVoice(voice: String) = update(Keys.TTS_EDGE_VOICE, voice)

    // ── Auto-folder ignored set ────────────────────────────────────────────

    val autoFolderIgnored: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.AUTO_FOLDER_IGNORED] ?: emptySet() }

    /** Read the ignored set once (suspend) — used by the classifier on its hot path. */
    suspend fun getAutoFolderIgnored(): Set<String> = autoFolderIgnored.first()

    /** Mark a tag id as ignored — called when the user deletes its auto-folder. */
    suspend fun addAutoFolderIgnored(tagId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.AUTO_FOLDER_IGNORED] ?: emptySet()
            prefs[Keys.AUTO_FOLDER_IGNORED] = current + tagId
        }
    }

    /** Forget a previous ignore — exposed so a future "reset" UI can wire to it. */
    suspend fun removeAutoFolderIgnored(tagId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.AUTO_FOLDER_IGNORED] ?: emptySet()
            prefs[Keys.AUTO_FOLDER_IGNORED] = current - tagId
        }
    }

    val autoFolderThreshold: Flow<Int> = context.dataStore.data
        .map { it[Keys.AUTO_FOLDER_THRESHOLD] ?: 3 }

    suspend fun getAutoFolderThreshold(): Int = autoFolderThreshold.first()

    suspend fun setAutoFolderThreshold(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_FOLDER_THRESHOLD] = value.coerceIn(2, 10)
        }
    }

    val allowSourceFallback: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.ALLOW_SOURCE_FALLBACK] ?: true }

    suspend fun getAllowSourceFallback(): Boolean = allowSourceFallback.first()

    suspend fun setAllowSourceFallback(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ALLOW_SOURCE_FALLBACK] = value
        }
    }

    // ── 搜索 / 换源 调度参数 ─────────────────────────────────────────────
    //
    // 三种暴露方式各有用：
    //  - Flow         : SearchSettingsScreen 实时绑定 Slider 和文案
    //  - suspend get  : SearchViewModel.search() 入口一次性快照（搜索期间值不变）
    //  - setter       : 设置页落库；coerceIn 兜底防越界
    //
    // 不暴露同步阻塞读 — 搜索路径已经在 IO dispatcher 里 suspend，走 Flow.first()
    // 比额外维护一份 SharedPreferences 镜像更简单。

    /** 默认 16；合法范围 1..32（详见 [Keys.SEARCH_PARALLELISM] 注释）。 */
    val searchParallelism: Flow<Int> = context.dataStore.data
        .map { (it[Keys.SEARCH_PARALLELISM] ?: 16).coerceIn(1, 32) }

    /** 一次性取值，用于搜索入口构造 Semaphore — 搜索过程中不响应配置变更。 */
    suspend fun getSearchParallelism(): Int = searchParallelism.first()

    suspend fun setSearchParallelism(value: Int) {
        update(Keys.SEARCH_PARALLELISM, value.coerceIn(1, 32))
    }

    /** 默认 30；合法范围 5..120 秒（详见 [Keys.SOURCE_SEARCH_TIMEOUT_SEC] 注释）。 */
    val sourceSearchTimeoutSec: Flow<Int> = context.dataStore.data
        .map { (it[Keys.SOURCE_SEARCH_TIMEOUT_SEC] ?: 30).coerceIn(5, 120) }

    /** 一次性取值，单位 ms — withTimeout 直接消费。 */
    suspend fun getSourceSearchTimeoutMs(): Long = sourceSearchTimeoutSec.first() * 1000L

    suspend fun setSourceSearchTimeoutSec(value: Int) {
        update(Keys.SOURCE_SEARCH_TIMEOUT_SEC, value.coerceIn(5, 120))
    }

    // ── 备份 / 恢复用：DataStore 全量 raw 读写 ────────────────────────────
    //
    // 这两个方法故意是 *raw* 接口（基于 key name 而非 typed Key）。出口只给
    // [com.morealm.app.domain.preference.PreferenceSnapshot] 用 —— 它在恢复
    // 备份时把 zip 里 prefs.json 的全量条目灌回 DataStore，写入路径不应耦合
    // AppPreferences 的强类型 API（强类型 setter 太多、且要求按 key 分别调用，
    // 全量恢复会写一坨 if-else）。
    //
    // 不要把这两个方法当 public API 在 ViewModel 用：恢复以外的所有写入都
    // 应该走具体的 setXxx() 方法，那里有 coerceIn / 镜像到 themePrefs 等
    // 业务规则（比如 setAutoNightMode 同时刷 themePrefs 让启动闪屏判断生效）。
    // 走 raw 接口会绕过这些 — 仅在「整体快照恢复」这种单一场景安全。

    /**
     * 拍摄当前 DataStore 全量快照。返回 `key.name -> raw value`，类型是 DataStore 写入时的
     * 原生 Kotlin 类型（Boolean / Int / Long / Float / Double / String / Set<String>）。
     */
    suspend fun snapshotAllRaw(): Map<String, Any> {
        val prefs = context.dataStore.data.first()
        return prefs.asMap().mapKeys { it.key.name }
    }

    /**
     * 把 [updates] 全量灌回 DataStore；[ignored] 中的 key name 跳过（黑名单 — 通常
     * 用于排除 WEBDAV_PASS、LAST_*_TIME 之类不应跨设备恢复的项）。
     *
     * 类型分发依据 `value` 实际 Kotlin 类型，不依赖 PreferenceSnapshot 的 type tag —
     * 上层负责把 JSON 解回正确的类型。未识别类型的 entry 被静默跳过。
     *
     * @return 实际写入的条目数（不含 ignored 跳过的）。
     */
    suspend fun applySnapshotRaw(updates: Map<String, Any>, ignored: Set<String>): Int {
        var written = 0
        context.dataStore.edit { prefs ->
            for ((name, value) in updates) {
                if (name in ignored) continue
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is Int -> prefs[intPreferencesKey(name)] = value
                    is Long -> prefs[longPreferencesKey(name)] = value
                    is Float -> prefs[floatPreferencesKey(name)] = value
                    is Double -> prefs[doublePreferencesKey(name)] = value
                    is String -> prefs[stringPreferencesKey(name)] = value
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        prefs[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
                    }
                    else -> continue
                }
                written++
            }
        }
        return written
    }
}
