package com.morealm.app.ui.reader

/**
 * 阅读器点击九宫格 —— 分区索引、动作 token、配置解析的唯一权威。
 *
 * 此前四处各自硬编码同一张映射表（renderer.Reader / PageLevelReaderHost /
 * SimulationReadView 各一份 when，TC/BC/MC 写死、仅四角可配）；本对象把「像素 →
 * 分区 → 动作」收敛成单实现，并引入 [AppPreferences.Keys.READER_TAP_GRID] 全网格
 * 配置（9 token 逗号连接，行主序）。未配置时由四角设置合成现状矩阵——老用户
 * 零行为变化。
 *
 * 分区索引（行主序）：
 * ```
 * 0 TL | 1 TC | 2 TR
 * 3 ML | 4 MC | 5 MR
 * 6 BL | 7 BC | 8 BR
 * ```
 */
object ReaderTapZones {

    const val ACTION_PREV = "prev"
    const val ACTION_NEXT = "next"
    const val ACTION_MENU = "menu"
    const val ACTION_PREV_CHAPTER = "prev_chapter"
    const val ACTION_NEXT_CHAPTER = "next_chapter"
    const val ACTION_TTS = "tts"
    const val ACTION_BOOKMARK = "bookmark"
    const val ACTION_NONE = "none"

    /** 合法动作 token 全集（网格编辑器选项顺序即此）。 */
    val ACTIONS: List<String> = listOf(
        ACTION_PREV, ACTION_NEXT, ACTION_MENU, ACTION_PREV_CHAPTER,
        ACTION_NEXT_CHAPTER, ACTION_TTS, ACTION_BOOKMARK, ACTION_NONE,
    )

    val ACTION_LABELS: Map<String, String> = mapOf(
        ACTION_PREV to "上一页",
        ACTION_NEXT to "下一页",
        ACTION_MENU to "呼出菜单",
        ACTION_PREV_CHAPTER to "上一章",
        ACTION_NEXT_CHAPTER to "下一章",
        ACTION_TTS to "朗读",
        ACTION_BOOKMARK to "添加书签",
        ACTION_NONE to "无操作",
    )

    fun labelOf(action: String): String = ACTION_LABELS[action] ?: action

    /** 出厂矩阵（四角默认 prev/next + 固定中列），与历史硬编码逐格一致。 */
    val DEFAULT_GRID: List<String> = listOf(
        ACTION_PREV, ACTION_PREV, ACTION_NEXT,
        ACTION_PREV, ACTION_MENU, ACTION_NEXT,
        ACTION_PREV, ACTION_NEXT, ACTION_NEXT,
    )

    /** 像素 → 分区索引（0..8）。0.33/0.66 分界与历史实现一致。 */
    fun zoneIndex(x: Float, y: Float, width: Float, height: Float): Int {
        val col = when {
            x < width * 0.33f -> 0
            x < width * 0.66f -> 1
            else -> 2
        }
        val row = when {
            y < height * 0.33f -> 0
            y < height * 0.66f -> 1
            else -> 2
        }
        return row * 3 + col
    }

    /** 像素 → 动作。grid 必须是 9 元素（[effectiveGrid] 产物）。 */
    fun actionAt(grid: List<String>, x: Float, y: Float, width: Float, height: Float): String =
        grid.getOrNull(zoneIndex(x, y, width, height)) ?: ACTION_MENU

    /** 解析用户网格配置；格式不合法（≠9 token / 含未知 token）返回 null 走四角回退。 */
    fun parseGrid(pref: String?): List<String>? {
        if (pref.isNullOrBlank()) return null
        val tokens = pref.split(',').map { it.trim() }
        if (tokens.size != 9 || tokens.any { it !in ACTIONS }) return null
        return tokens
    }

    fun encodeGrid(grid: List<String>): String = grid.joinToString(",")

    /**
     * 生效网格：配置了合法 READER_TAP_GRID 用之；否则由四角设置合成现状矩阵
     * （ML/MR/BL/BR 跟随左右角、TC=上一页、BC=下一页、MC=菜单，与旧硬编码逐格一致，
     * 含 TAP_LEFT_ACTION 老迁移链——四角 flow 本身已带该回退）。
     */
    fun effectiveGrid(
        pref: String?,
        topLeft: String,
        topRight: String,
        bottomLeft: String,
        bottomRight: String,
    ): List<String> = parseGrid(pref) ?: listOf(
        topLeft, ACTION_PREV, topRight,
        bottomLeft, ACTION_MENU, bottomRight,
        bottomLeft, ACTION_NEXT, bottomRight,
    )
}
