package com.morealm.app.domain.render.color

/**
 * 正文规则上色的字符类别（参考 ColorTxt 的 Monarch token 分类，PRD §2.1）。
 *
 * QUOTE_INNER / BRACKET_INNER 是「子状态兜底色」——引号 / 括号内没匹配到更具体类别
 * （标点 / 数字 / 字母 / 特殊）的普通字符落这两类。NONE = 正文默认色（CJK 正文等），不上色。
 */
enum class RuleColorCategory {
    NONE,
    PUNCTUATION,
    NUMBER,
    LATIN,
    SPECIAL,
    QUOTE_INNER,
    BRACKET_INNER,
}
