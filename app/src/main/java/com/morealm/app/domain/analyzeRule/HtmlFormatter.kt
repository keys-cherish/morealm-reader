package com.morealm.app.domain.analyzeRule

import java.net.URL
import java.util.regex.Pattern

/**
 * HTML 净化与格式化（Legado parity）。
 *
 * 用于把书源解析得到的正文 / 简介 HTML 片段转成阅读器可显示的纯文本：
 * - `<p>/<br>/<div>/<hr>/<h\d>/<article>/<dd>/<dl>` → `\n`
 * - `&nbsp;` / `&ensp;` / `&emsp;` → 单空格；零宽字符直接删除
 * - HTML 注释、其他标签全部剥离（[formatKeepImg] 例外保留 `<img>` 并把 src 写成绝对 URL）
 * - 段首加全角空格 `　　`（中文小说排版习惯）
 *
 * 实现参考成熟开源阅读器的 HTML 格式化工具。
 * 唯一的 MoRealm 适配点：URL 重写不再依赖 `NetworkUtils.getAbsoluteURL`（MoRealm 没有
 * 此方法），改用同一包下的 [AnalyzeRule.getAbsoluteURL] 静态方法 —— 语义一致。
 */
@Suppress("RegExpRedundantEscape")
object HtmlFormatter {
    private val nbspRegex = "(&nbsp;)+".toRegex()
    private val espRegex = "(&ensp;|&emsp;)".toRegex()
    private val noPrintRegex = "(&thinsp;|&zwnj;|&zwj;| |‌|‍)".toRegex()
    private val wrapHtmlRegex = "</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>".toRegex()
    private val commentRegex = "<!--[^>]*-->".toRegex()
    private val notImgHtmlRegex = "</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    private val otherHtmlRegex = "</?[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    private val formatImagePattern: Pattern = Pattern.compile(
        "<img[^>]*\\ssrc\\s*=\\s*['\"]([^'\"{>]*\\{(?:[^{}]|\\{[^}>]+\\})+\\})['\"][^>]*>" +
            "|<img[^>]*\\sdata-(?:src|original|srcset)\\s*=\\s*['\"]([^'\">]+)['\"][^>]*>" +
            "|<img[^>]*\\ssrc\\s*=\\s*\"([^\">]+)\"[^>]*>" +
            "|<img[^>]*\\s(?:data-[^=>]*|src)=\\s*['\"]([^'\">]*)['\"][^>]*>",
        Pattern.CASE_INSENSITIVE
    )
    private val indent1Regex = "\\s*\\n+\\s*".toRegex()
    private val indent2Regex = "^[\\n\\s]+".toRegex()
    private val lastRegex = "[\\n\\s]+$".toRegex()

    /**
     * 净化 HTML 为纯文本（不保留 `<img>`）。
     *
     * @param otherRegex 默认 [otherHtmlRegex]（删除一切标签）；[formatKeepImg] 内部传
     *   [notImgHtmlRegex] 实现「保留 img 删其他」。
     */
    fun format(html: String?, otherRegex: Regex = otherHtmlRegex): String {
        html ?: return ""
        return html
            .replace(nbspRegex, " ")
            .replace(espRegex, " ")
            .replace(noPrintRegex, "")
            .replace(wrapHtmlRegex, "\n")
            .replace(commentRegex, "")
            .replace(otherRegex, "")
            .replace(indent1Regex, "\n　　")
            .replace(indent2Regex, "　　")
            .replace(lastRegex, "")
    }

    /**
     * 净化 HTML，但保留 `<img>` 标签并把相对 src 重写为绝对 URL。
     *
     * 支持四种 src 写法（按优先级）：
     * 1. `src="..../{...}"` 含尾部参数（[AnalyzeUrl.paramPattern] 切分）
     * 2. `data-src` / `data-original` / `data-srcset`（懒加载常用）
     * 3. 普通双引号 `src="..."`
     * 4. 兜底 `data-*` 或 `src=...` 任意带引号
     */
    fun formatKeepImg(html: String?, redirectUrl: URL? = null): String {
        html ?: return ""
        val keepImgHtml = format(html, notImgHtmlRegex)
        val matcher = formatImagePattern.matcher(keepImgHtml)
        var appendPos = 0
        val sb = StringBuilder()
        while (matcher.find()) {
            var param = ""
            val rawSrc = matcher.group(1)?.let { srcWithParams ->
                val urlMatcher = AnalyzeUrl.paramPattern.matcher(srcWithParams)
                if (urlMatcher.find()) {
                    param = "," + srcWithParams.substring(urlMatcher.end())
                    srcWithParams.substring(0, urlMatcher.start())
                } else {
                    srcWithParams
                }
            } ?: matcher.group(2) ?: matcher.group(3) ?: matcher.group(4) ?: ""
            val absSrc = AnalyzeRule.getAbsoluteURL(redirectUrl, rawSrc)
            sb.append(keepImgHtml, appendPos, matcher.start())
            sb.append("<img src=\"").append(absSrc).append(param).append("\">")
            appendPos = matcher.end()
        }
        if (appendPos < keepImgHtml.length) {
            sb.append(keepImgHtml, appendPos, keepImgHtml.length)
        }
        return sb.toString()
    }
}
