package com.morealm.app.domain.render

/**
 * Parses a subset of CSS properties from a custom CSS string and maps them
 * to ChapterProvider layout parameters.
 *
 * Supported CSS properties:
 * - text-indent: <N>em | <N>px | 0   → paragraphIndent
 * - line-height: <N> | <N>em          → lineSpacingExtra
 * - letter-spacing: <N>px | <N>em     → (applied via TextPaint)
 * - text-align: left | center | justify → textFullJustify
 * - margin-top: <N>dp | <N>px         → paddingTop override
 * - margin-bottom: <N>dp | <N>px      → paddingBottom override
 * - margin-left: <N>dp | <N>px        → paddingLeft override
 * - margin-right: <N>dp | <N>px       → paddingRight override
 * - font-size: <N>sp | <N>px          → fontSize override
 * - paragraph-spacing: <N>            → paragraphSpacing
 */
data class CssOverrides(
    val paragraphIndent: String? = null,
    val lineSpacingExtra: Float? = null,
    val letterSpacing: Float? = null,
    val textAlign: String? = null,
    val paddingTop: Int? = null,
    val paddingBottom: Int? = null,
    val paddingLeft: Int? = null,
    val paddingRight: Int? = null,
    val fontSize: Float? = null,
    val paragraphSpacing: Int? = null,
) {
    val isEmpty get() = this == EMPTY

    companion object {
        val EMPTY = CssOverrides()
    }
}

object CssParser {

    private val propertyRegex = Regex(
        """([\w-]+)\s*:\s*([^;{}]+)""",
        RegexOption.IGNORE_CASE,
    )

    // Pre-compiled regexes — avoid re-creation per call
    private val emValueRegex = Regex("""([\d.]+)\s*em""")
    private val pxValueRegex = Regex("""(-?[\d.]+)\s*px""")
    private val numUnitRegex = Regex("""([\d.]+)\s*(px|dp|sp|pt)?""")

    fun parse(css: String): CssOverrides {
        if (css.isBlank()) return CssOverrides.EMPTY

        var indent: String? = null
        var lineHeight: Float? = null
        var letterSpacing: Float? = null
        var textAlign: String? = null
        var padTop: Int? = null
        var padBottom: Int? = null
        var padLeft: Int? = null
        var padRight: Int? = null
        var fontSize: Float? = null
        var paraSpacing: Int? = null

        for (match in propertyRegex.findAll(css)) {
            val prop = match.groupValues[1].trim().lowercase()
            val value = match.groupValues[2].trim().lowercase()

            when (prop) {
                "text-indent" -> indent = parseIndent(value)
                "line-height" -> lineHeight = parseLineHeight(value)
                "letter-spacing" -> letterSpacing = parsePxOrEm(value)
                "text-align" -> textAlign = value.takeIf { it in setOf("left", "center", "justify", "right") }
                "margin-top", "padding-top" -> padTop = parsePxValue(value)
                "margin-bottom", "padding-bottom" -> padBottom = parsePxValue(value)
                "margin-left", "padding-left" -> padLeft = parsePxValue(value)
                "margin-right", "padding-right" -> padRight = parsePxValue(value)
                "font-size" -> fontSize = parseFontSize(value)
                "paragraph-spacing" -> paraSpacing = value.toFloatOrNull()?.toInt()
            }
        }

        return CssOverrides(
            paragraphIndent = indent,
            lineSpacingExtra = lineHeight,
            letterSpacing = letterSpacing,
            textAlign = textAlign,
            paddingTop = padTop,
            paddingBottom = padBottom,
            paddingLeft = padLeft,
            paddingRight = padRight,
            fontSize = fontSize,
            paragraphSpacing = paraSpacing,
        )
    }

    /** text-indent: 2em → "　　", 0 → "", 1em → "　" */
    private fun parseIndent(value: String): String {
        if (value == "0" || value == "0px" || value == "0em") return ""
        val emMatch = emValueRegex.find(value)
        if (emMatch != null) {
            val count = emMatch.groupValues[1].toFloatOrNull()?.toInt() ?: 2
            return "\u3000".repeat(count.coerceIn(0, 8))
        }
        val pxMatch = pxValueRegex.find(value)
        if (pxMatch != null) {
            val px = pxMatch.groupValues[1].toFloatOrNull() ?: return "\u3000\u3000"
            val emCount = (px / 16f).toInt().coerceIn(0, 8)
            return "\u3000".repeat(emCount)
        }
        return "\u3000\u3000"
    }

    /** line-height: 1.8 or 1.8em → 1.8f */
    private fun parseLineHeight(value: String): Float? {
        val num = value.replace("em", "").trim().toFloatOrNull()
        return num?.coerceIn(1.0f, 4.0f)
    }

    /** Parse px or em value to float (em units) */
    private fun parsePxOrEm(value: String): Float? {
        val emMatch = emValueRegex.find(value)
        if (emMatch != null) return emMatch.groupValues[1].toFloatOrNull()
        val pxMatch = pxValueRegex.find(value)
        if (pxMatch != null) return pxMatch.groupValues[1].toFloatOrNull()?.let { it / 16f }
        return value.toFloatOrNull()
    }

    /** Parse px/dp value to Int */
    private fun parsePxValue(value: String): Int? {
        val match = numUnitRegex.find(value)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
    }

    /** Parse font-size: 18sp, 18px, etc. */
    private fun parseFontSize(value: String): Float? {
        val match = numUnitRegex.find(value)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(8f, 72f)
    }
}
