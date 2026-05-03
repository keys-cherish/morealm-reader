package com.morealm.app.core.text

import java.util.regex.Pattern

/**
 * Pre-compiled regex patterns used across the app.
 * Ported from Legado's AppPattern — avoids per-call Regex() allocation.
 *
 * Rules:
 * - All patterns are top-level vals (compiled once at class load)
 * - Use Kotlin Regex for Kotlin code, java.util.regex.Pattern for Java interop
 * - Add new patterns here instead of inline Regex() in business code
 */
object AppPattern {

    // ── HTML tag stripping ──
    val htmlTagRegex = Regex("<[^>]+>")
    val htmlBrRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    val htmlPOpenRegex = Regex("<p[^>]*>", RegexOption.IGNORE_CASE)
    val htmlPCloseRegex = Regex("</p>", RegexOption.IGNORE_CASE)
    val htmlDivCloseRegex = Regex("</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE)
    val htmlImgRegex = Regex("<img[^>]*>", RegexOption.IGNORE_CASE)
    val htmlSvgRegex = Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE)

    // ── Markdown ──
    val markdownHeadingRegex = Regex("^#{1,6}\\s*")

    // ── Whitespace ──
    val whitespaceRegex = Regex("\\s+")

    // ── Image src extraction (used by ChapterProvider) ──
    val imgSrcPattern: Pattern = Pattern.compile(
        "<img[^>]+src=['\"]([^'\"]*)['\"][^>]*>", Pattern.CASE_INSENSITIVE
    )

    // ── Book file types ──
    val bookFileRegex = Regex(".*\\.(txt|epub|umd|pdf|mobi|azw3|cbz)", RegexOption.IGNORE_CASE)
    val archiveFileRegex = Regex(".*\\.(zip|rar|7z)$", RegexOption.IGNORE_CASE)

    // ── Number extraction (for natural sort) ──
    val numberRegex = Regex("(\\d+)")

    // ── TTS：纯无声段判定 ──
    //
    // 一段如果整段只由：空白(\s) / 控制符(\p{C}) / 标点(\p{P}) / 分隔符(\p{Z}) /
    // 符号(\p{S}) 组成，朗读出来不是"什么都不读"就是"读出 '破折号 破折号'" 这种
    // 让人困惑的字符名 —— 都不是用户想要的。段级跳转时遇到这种段直接跳过。
    //
    // 规则原样移植自 Legado AppPattern.notReadAloudRegex（同款 Unicode 类）。
    val notReadAloudRegex = Regex("^(\\s|\\p{C}|\\p{P}|\\p{Z}|\\p{S})+$")
}
