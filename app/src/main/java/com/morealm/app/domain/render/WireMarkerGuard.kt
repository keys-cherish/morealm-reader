package com.morealm.app.domain.render

import com.morealm.epub.compat.StructuredChapterContent

/**
 * 判断一段章节内容是否是 **flatten wire 串**（带排版协议 marker），用于把用户替换 /
 * 净化规则挡在协议之外。
 *
 * 背景（2026-07-26 好讀繁体 EPUB 事故）：EPUB flatten 出的缓存串把 BlockStyle / 表格 /
 * 容器 / heading / span 颜色等排版信息编码成 marker。用户的替换规则是**全文 regex**，
 * 一条「行尾汉字合并断行」这类常见净化规则就会把行首 marker 并进行中，翻页排版层只剥
 * 行首 marker → marker 字面量直接画进正文。
 *
 * 这不是某一条规则的问题：任意用户 regex 都可能删下划线、改大小写、跨行合并，协议随时
 * 可能被打碎。所以门控放在「内容是否含 marker」这一层，而不是逐条规则做兼容。
 *
 * 影响面：只有 EPUB 精排路径的内容含 marker。TXT / 网络书 / 纯文本 EPUB 章节不含 marker，
 * 替换净化功能完全不受影响。
 */
object WireMarkerGuard {

    /**
     * 文本形 marker 的公共命名空间前缀，从两个实际 marker 常量求公共前缀得到
     * （`BLOCK_STYLE_MARKER` 与 `TABLE_START` 的公共头）。
     *
     * 刻意不写死字面量：marker 常量本身在 epub-compat 侧是运行时解码的，写死会在协议
     * 改名时静默失配，也会把命名空间明文钉进 app 二进制。
     */
    private val textMarkerPrefix: String by lazy {
        StructuredChapterContent.BLOCK_STYLE_MARKER
            .commonPrefixWith(StructuredChapterContent.TABLE_START)
    }

    /**
     * 控制字符形 marker 的起始符（heading / span 颜色 / inline 图 / inline 背景盒 /
     * 字号 / 字体 / 链接 / 着重号）。
     *
     * 2026-08-13 补 SIZE / TEXT_STYLE / LINK / EMPH 四类：此前只列了首批 marker，
     * 「段落只带链接或着重号、无其他 marker」时替换规则仍可能打碎协议。
     */
    private val controlMarkers: List<String> by lazy {
        listOf(
            StructuredChapterContent.HEADING_LEVEL_START,
            StructuredChapterContent.SPAN_COLOR_START,
            StructuredChapterContent.INLINE_IMG_START,
            StructuredChapterContent.SPAN_BG_START,
            StructuredChapterContent.HR_MARKER,
            StructuredChapterContent.SIZE_START,
            StructuredChapterContent.TEXT_STYLE_START,
            StructuredChapterContent.LINK_START,
            StructuredChapterContent.EMPH_START,
        )
    }

    /** 内容是否含任一排版协议 marker。含 → 调用方必须跳过全文 regex 改写。 */
    fun containsWireMarkers(content: String): Boolean {
        if (content.isEmpty()) return false
        if (textMarkerPrefix.isNotEmpty() && content.contains(textMarkerPrefix)) return true
        return controlMarkers.any { it.isNotEmpty() && content.contains(it) }
    }
}
