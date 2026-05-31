package com.morealm.app.domain.render.color

/**
 * 类别 → 前景色 ARGB 调色板，明 / 暗各一套。默认值抄 ColorTxt（PRD §2.6）。
 *
 * 用户可在「文字上色」设置里覆盖单个类别的颜色（明 / 暗各存一组），[resolve] 把内置默认
 * 与用户覆盖 merge。MVP 默认无覆盖时 [resolve] == [default]，零开销。
 */
class RuleColorPalette(
    private val colors: Map<RuleColorCategory, Int>,
) {
    /** 取类别色；[RuleColorCategory.NONE] 或未配置返回 0（= 不上色，走正文默认色）。 */
    fun colorOf(category: RuleColorCategory): Int = colors[category] ?: 0

    companion object {
        /** 可调色的 6 个类别（[RuleColorCategory.NONE] 不上色、不可调）。UI 按此顺序展示。 */
        val EDITABLE_CATEGORIES: List<RuleColorCategory> = listOf(
            RuleColorCategory.PUNCTUATION,
            RuleColorCategory.NUMBER,
            RuleColorCategory.LATIN,
            RuleColorCategory.SPECIAL,
            RuleColorCategory.QUOTE_INNER,
            RuleColorCategory.BRACKET_INNER,
        )

        // PRD §2.6 默认 hex（明）
        private val LIGHT: Map<RuleColorCategory, Int> = mapOf(
            RuleColorCategory.QUOTE_INNER to 0xFFA31515.toInt(),
            RuleColorCategory.BRACKET_INNER to 0xFF001080.toInt(),
            RuleColorCategory.PUNCTUATION to 0xFF267F99.toInt(),
            RuleColorCategory.SPECIAL to 0xFFF56C6C.toInt(),
            RuleColorCategory.NUMBER to 0xFF795E26.toInt(),
            RuleColorCategory.LATIN to 0xFFAF00DB.toInt(),
        )

        // PRD §2.6 默认 hex（暗）
        private val DARK: Map<RuleColorCategory, Int> = mapOf(
            RuleColorCategory.QUOTE_INNER to 0xFFCE9178.toInt(),
            RuleColorCategory.BRACKET_INNER to 0xFF9CDCFE.toInt(),
            RuleColorCategory.PUNCTUATION to 0xFF4EC9B0.toInt(),
            RuleColorCategory.SPECIAL to 0xFFF56C6C.toInt(),
            RuleColorCategory.NUMBER to 0xFFDCDCAA.toInt(),
            RuleColorCategory.LATIN to 0xFFC586C0.toInt(),
        )

        /** 类别在指定主题下的内置默认色（UI 显示默认参考 + 「恢复默认」用）。 */
        fun defaultColor(category: RuleColorCategory, isNight: Boolean): Int =
            (if (isNight) DARK else LIGHT)[category] ?: 0

        fun default(isNight: Boolean): RuleColorPalette =
            RuleColorPalette(if (isNight) DARK else LIGHT)

        // 高亮词色组（PRD §2.6）：明 / 暗各 10 色。colorIndex % size 取色。
        private val HL_LIGHT: List<Int> = listOf(
            0xFFCC0000, 0xFFCC6600, 0xFF996600, 0xFF669900, 0xFF009933,
            0xFF009999, 0xFF0033CC, 0xFF6633CC, 0xFF990099, 0xFFCC3399,
        ).map { it.toInt() }
        private val HL_DARK: List<Int> = listOf(
            0xFFFF6666, 0xFFFFCC66, 0xFFFFFF66, 0xFF66FF66, 0xFF66FFFF,
            0xFF66CCFF, 0xFF9999FF, 0xFFCC66FF, 0xFFFF99CC, 0xFFFFCCCC,
        ).map { it.toInt() }

        /** 高亮词色组（按主题）；调色板高亮色号 colorIndex 取此组（越界 % size 兜底）。 */
        fun highlightColors(isNight: Boolean): List<Int> = if (isNight) HL_DARK else HL_LIGHT

        /** 内置默认 + 用户对当前主题的覆盖（[overrides] 仅含被改过的类别）。空覆盖回退 [default]。 */
        fun resolve(isNight: Boolean, overrides: Map<RuleColorCategory, Int>): RuleColorPalette {
            if (overrides.isEmpty()) return default(isNight)
            return RuleColorPalette((if (isNight) DARK else LIGHT) + overrides)
        }

        // ── 用户覆盖色编码（存 AppPreferences；明 / 暗各一组，只存改过的类别）──
        // 格式："L|PUNCTUATION=ffaa00bb,NUMBER=ff112233;D|LATIN=ff445566"（仿 SelectionMenuConfig 自编码，
        // 不引 JSON 依赖）。
        fun encodeOverrides(
            light: Map<RuleColorCategory, Int>,
            dark: Map<RuleColorCategory, Int>,
        ): String {
            fun enc(m: Map<RuleColorCategory, Int>): String =
                m.entries.joinToString(",") { "${it.key.name}=${"%08x".format(it.value)}" }
            return "L|${enc(light)};D|${enc(dark)}"
        }

        /** 解析 [encodeOverrides] 的串 → (明覆盖, 暗覆盖)；非法 / 未知项跳过（容错）。 */
        fun decodeOverrides(
            s: String?,
        ): Pair<Map<RuleColorCategory, Int>, Map<RuleColorCategory, Int>> {
            val light = LinkedHashMap<RuleColorCategory, Int>()
            val dark = LinkedHashMap<RuleColorCategory, Int>()
            if (s.isNullOrBlank()) return light to dark
            for (seg in s.split(";")) {
                val bar = seg.indexOf('|')
                if (bar <= 0) continue
                val target = if (seg.substring(0, bar) == "D") dark else light
                val body = seg.substring(bar + 1)
                if (body.isBlank()) continue
                for (kv in body.split(",")) {
                    val eq = kv.indexOf('=')
                    if (eq <= 0) continue
                    val cat = runCatching {
                        RuleColorCategory.valueOf(kv.substring(0, eq))
                    }.getOrNull() ?: continue
                    val argb = kv.substring(eq + 1).toLongOrNull(16)?.toInt() ?: continue
                    target[cat] = argb
                }
            }
            return light to dark
        }
    }
}
