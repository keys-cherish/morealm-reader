package com.morealm.app.domain.render.color

/**
 * 类别 → 前景色 ARGB 调色板，明 / 暗各一套。默认值抄 ColorTxt（PRD §2.6）。
 *
 * MVP 用内置默认（随日 / 夜主题切 [default]）；Phase 3 支持用户覆盖单个类别色。
 */
class RuleColorPalette(
    private val colors: Map<RuleColorCategory, Int>,
) {
    /** 取类别色；[RuleColorCategory.NONE] 或未配置返回 0（= 不上色，走正文默认色）。 */
    fun colorOf(category: RuleColorCategory): Int = colors[category] ?: 0

    companion object {
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

        fun default(isNight: Boolean): RuleColorPalette =
            RuleColorPalette(if (isNight) DARK else LIGHT)
    }
}
