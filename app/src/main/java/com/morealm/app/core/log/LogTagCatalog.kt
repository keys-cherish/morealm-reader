package com.morealm.app.core.log

/**
 * 日志 tag 语义字典。
 *
 * 现状：代码里的 AppLog tag 散落在 100+ 处，命名风格不统一（`TtsHost` vs
 * `TTS` vs `HttpTTS`），出问题时不知道该看哪个 tag。这里**不重命名调用点**，
 * 只把现有 tag 映射到「模块/子项 — 中文用途说明 — 出什么问题看它」。
 *
 * 规范化后的命名空间用 `模块/子项` 斜杠风格，便于 logcat 过滤（`grep TTS/`
 * 一次性拉出所有朗读相关）。后续逐步迁移调用点时按此命名约定写入。
 *
 * 用法：
 * - `LogTagCatalog.describe(tag)` 拿到一行简介（chip / 详情副文使用）。
 * - `LogTagCatalog.module(tag)` 拿到模块前缀（如 "TTS"），用于按模块批量过滤。
 * - `LogTagCatalog.allEntries` 给「日志 tag 速查」对话框列举。
 */
object LogTagCatalog {

    /**
     * 单条 tag 的语义描述。
     *
     * @property canonical 推荐写法（模块/子项），将来迁移调用点时的目标。
     * @property purpose 中文用途说明（一行）。
     * @property whenToCheck 出现什么症状时该看这个 tag（一行）。
     */
    data class Entry(
        val canonical: String,
        val purpose: String,
        val whenToCheck: String,
    ) {
        /** chip / 详情副文显示用 */
        fun shortLine(): String = "$canonical — $purpose"
    }

    /** 现有 tag → 语义说明。新增调用点请优先使用 [Entry.canonical] 形式的写法。 */
    private val ENTRIES: Map<String, Entry> = mapOf(
        // ── TTS 朗读模块 ─────────────────────────────────────────────
        "TTS" to Entry("TTS/Common", "朗读链路通用埋点（控制器/事件总线/UI 入口）", "朗读启动/切段/事件丢失"),
        "TtsHost" to Entry("TTS/Host", "TTS 引擎宿主：批量入队、句中续读、speakLoop", "段落进度卡住、引擎异常、断点续读异常"),
        "TtsService" to Entry("TTS/Service", "Foreground Service 生命周期、音频焦点、电话监听", "服务被杀、焦点丢失、来电不暂停"),
        "TtsPlayer" to Entry("TTS/Player", "Media3 SimpleBasePlayer 包装，封面加载", "通知封面不显示、媒体按钮失灵"),
        "TtsErrorPresenter" to Entry("TTS/ErrorPresenter", "朗读错误 Toast 去重展示", "Toast 刷屏 / 关键错误没看到"),
        "TtsSettings" to Entry("TTS/Settings", "朗读设置页（速度/语音/引擎）", "设置不生效 / 配置丢失"),
        "TtsNotif" to Entry("TTS/Notification", "通知栏 MediaNotificationProvider", "通知栏永远是占位文案、按钮不响应"),
        "HttpTTS" to Entry("TTS/Http", "Http 流式 TTS 引擎（Edge / 自定义 API）", "Edge 没声音、URL 拼接错、SSML 异常"),
        "EdgeTTS" to Entry("TTS/Edge", "Edge TTS WSS 通信 + MediaCodec 流式解码 + 远程音色", "Edge 播放卡 / 首字慢 / 401 403 / 音色列表为空"),
        "EdgeTtsAuth" to Entry("TTS/EdgeAuth", "Sec-MS-GEC token 生成与时钟偏移自校正", "Edge 偶发 401/403、长句被切断、握手被拒"),
        "EdgeTtsCache" to Entry("TTS/EdgeCache", "Edge TTS MP3 LRU 缓存（cacheDir/edge_tts/）", "重听仍走网络、缓存写入失败、占用过大"),

        // ── 阅读器 ──────────────────────────────────────────────────
        "CanvasRenderer" to Entry("Reader/Render", "翻页模式 Canvas 渲染主流程", "翻页渲染异常 / 高亮不显示"),
        "Simulation" to Entry("Reader/Simulation", "仿真翻页 SimulationReadView", "仿真翻页黑框/闪烁"),
        "PageTurn" to Entry("Reader/PageTurn", "翻页协调器 PageTurnCoordinator", "翻页方向错乱、页索引漂移"),
        "Chapter" to Entry("Reader/Chapter", "章节加载与解析（ReaderChapterController）", "章节读不出、内容空、切章卡住"),
        "Progress" to Entry("Reader/Progress", "阅读进度持久化与恢复", "进度不保存、回到旧位置"),
        "Edit" to Entry("Reader/Edit", "章节正文编辑", "编辑保存失败、内容覆盖"),
        "Search" to Entry("Reader/Search", "全文搜索 / 站内搜索", "搜索无结果、跳转错位"),
        "Highlight" to Entry("Reader/Highlight", "标注/高亮持久化（HighlightRepository）", "标注丢失、跨章异常"),
        "ReaderKey" to Entry("Reader/Key", "物理按键翻页（音量键 / 耳机 / 蓝牙翻页器）", "音量键不响应、长按无翻章、方向反向"),
        "SelectionMenu" to Entry("Reader/SelectionMenu", "选区 mini-menu 自定义（显示 / 顺序 / 主行分配）", "按钮顺序不对、主行 cap 误判、解析回退默认"),

        // ── 解析器 ──────────────────────────────────────────────────
        "EpubParser" to Entry("Parser/Epub", "EPUB 解压 + 章节抽取", "EPUB 打不开 / 章节缺失"),
        "PdfParser" to Entry("Parser/Pdf", "PDF 文本提取", "PDF 解析失败 / 文字乱码"),
        "MobiParser" to Entry("Parser/Mobi", "MOBI 解析", "MOBI 打不开"),
        "UmdParser" to Entry("Parser/Umd", "UMD 解析", "UMD 打不开"),
        "EpubWriter" to Entry("Parser/EpubWriter", "EPUB 导出", "导出 EPUB 文件失败 / 体积异常"),

        // ── 备份 / 同步 ────────────────────────────────────────────
        "Backup" to Entry("Backup/Manager", "数据备份与恢复主流程", "导出 0 字节、恢复失败、占位文件"),
        "BackupCrypto" to Entry("Backup/Crypto", "备份文件加解密", "解密失败、密码错误"),
        "WebDAV" to Entry("Backup/WebDav", "WebDav 远端同步", "WebDav 401/连不上/上传失败"),

        // ── 数据库 ──────────────────────────────────────────────────
        "DB" to Entry("DB/Common", "Room 数据库操作（Migration / 写入 / 自动备份）", "DB 升级 crash、写入失败、迁移异常"),

        // ── 网络 ────────────────────────────────────────────────────
        "OkHttp" to Entry("Net/OkHttp", "OkHttp 拦截器、Dispatcher 异常", "请求 silently 失败、Dispatcher 卡死"),

        // ── 书架 / 书源 ────────────────────────────────────────────
        "Shelf" to Entry("Shelf/Common", "书架 + 后台刷新（ShelfRefreshController）", "书架不刷新、新章数错乱"),
        "AutoFolder" to Entry("Shelf/AutoFolder", "自动分组规则", "自动分组没生效、规则匹配错"),
        "AutoGroupRules" to Entry("Shelf/AutoFolder", "自动分组规则（同 AutoFolder）", "同上"),
        "BookList" to Entry("Source/BookList", "书源 BookList 解析", "搜索/书架页解析空 / 字段错位"),
        "ChangeSource" to Entry("Shelf/ChangeSource", "换源逻辑", "换源后章节对不齐、内容差异"),
        "RemoteBook" to Entry("Shelf/Remote", "远程书（云端云盘）加载", "远程书打不开"),

        "SourceImport" to Entry("Source/Import", "书源导入 + 净化", "导入失败、字段丢失、JS 注入"),
        "SourceManage" to Entry("Source/Manage", "书源管理页", "源列表显示异常"),
        "SourceLogin" to Entry("Source/Login", "书源登录态", "登录态失效、Cookie 丢失"),
        "CheckSource" to Entry("Source/Check", "CheckSource 一键检测", "检测卡死、误判超时"),
        "AnalyzeRule" to Entry("Source/AnalyzeRule", "解析规则引擎（XPath/JSONPath/JS）", "规则解析空、JS 报错"),
        "ReplaceRule" to Entry("Source/Replace", "正文替换规则", "替换没生效、误替换"),
        "JsExtensions" to Entry("Source/JsExt", "书源 JS 扩展（Rhino）", "JS 函数找不到、运行时错"),

        // ── 主题 / UI ───────────────────────────────────────────────
        "Theme" to Entry("Theme", "主题导入导出 / 切换", "主题字段丢失、导入失败"),
        "Settings" to Entry("UI/Settings", "设置页", "设置写入异常"),
        "Profile" to Entry("UI/Profile", "我的/捐赠页", "捐赠跳转、ProfileViewModel"),
        "Detail" to Entry("UI/Detail", "书籍详情页", "详情加载失败"),
        "Nav" to Entry("UI/Nav", "导航事件", "页面跳转异常 / deep link"),

        // ── 缓存 ────────────────────────────────────────────────────
        "CacheBook" to Entry("Cache/Book", "缓存整本书逻辑（CacheBookService）", "缓存中断、章节缺失"),
        "CacheService" to Entry("Cache/Service", "缓存服务进程", "服务自动停止 / 空闲过早退出"),
        "CacheCleanup" to Entry("Cache/Cleanup", "缓存清理", "清理误删 / 空间未释放"),

        // ── 系统 ────────────────────────────────────────────────────
        "App" to Entry("System/App", "Application 生命周期", "进程冷启动、未捕获异常"),
        "Seeder" to Entry("System/Seeder", "首次启动数据 seed", "默认书源/主题没注入"),
        "Import" to Entry("System/Import", "通用导入入口（书架/主题/书源选择）", "选文件导入失败"),
        "Export" to Entry("System/Export", "通用导出", "导出弹不出选择器"),
        "LogExport" to Entry("System/LogExport", "日志导出 zip", "日志导出失败"),
        "PageTurnFlicker" to Entry("Reader/Diag", "翻页闪烁专项诊断埋点（临时）", "翻页闪烁现场复现"),

        // 历史遗留 / 待修复
        "tag" to Entry("(invalid)", "占位/调用错误", "看到这个 tag 就是有 bug，应改 tag 调用点"),
    )

    /** 一行简介，用于 chip 提示 / 单条详情副文。 */
    fun describe(tag: String): String? = ENTRIES[tag]?.shortLine()

    /** 详细条目（含规范名 + 用途 + 出问题看它），用于「速查 dialog」。 */
    fun entry(tag: String): Entry? = ENTRIES[tag]

    /** 该 tag 的模块前缀（如 "TTS"）。用于按模块批量过滤。 */
    fun module(tag: String): String? =
        ENTRIES[tag]?.canonical?.substringBefore('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }

    /**
     * 按模块分组列出所有已知 tag。
     * 返回 `Map<模块, List<tag → Entry>>`，按模块名排序，模块内按 tag 字母序。
     * 用于「速查 dialog」按模块分节展示。
     */
    val allEntriesByModule: Map<String, List<Pair<String, Entry>>> by lazy {
        ENTRIES.entries
            .groupBy { module(it.key) ?: "其他" }
            .toSortedMap()
            .mapValues { (_, items) ->
                items.sortedBy { it.key }.map { it.key to it.value }
            }
    }
}
