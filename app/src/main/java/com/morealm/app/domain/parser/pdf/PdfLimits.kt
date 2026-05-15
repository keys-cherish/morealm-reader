package com.morealm.app.domain.parser.pdf

/**
 * 所有 PDF outline 解析过程中的硬上限集中在这里。
 *
 * 目的：把对抗性输入（巨大 PDF / 损坏 PDF / zip bomb / 环引用）的边界统一可见，方便审计与调参。
 * 任何越界都不抛崩，由调用层包成"该 entry 跳过"或"全文档回退"。
 */
internal object PdfLimits {
    /** outline 条目总数硬上限。典型学术教材 200–800。 */
    const val MAX_OUTLINE_ENTRIES = 5_000

    /** outline 嵌套深度。健康 PDF ≤ 6；超过视为环或恶意。 */
    const val MAX_OUTLINE_DEPTH = 16

    /** outline 遍历访问过的节点 obj 数上限，配合 visited set 断环。 */
    const val MAX_OUTLINE_VISITED = 20_000

    /** 单个 ObjStm 解压后最大字节，防 zip bomb。 */
    const val MAX_OBJSTM_DECOMPRESSED = 16 * 1024 * 1024

    /** /Title 字符串最大长度（解码后字符数）。 */
    const val MAX_STRING_LEN = 4_096

    /** xref 条目总数（含历次 /Prev 累加）。 */
    const val MAX_XREF_ENTRIES = 1_000_000

    /** /Prev 链跳转次数上限。 */
    const val MAX_XREF_PREV_CHAIN = 16

    /** 单个间接对象 body 解析时读入的最大字节。 */
    const val MAX_OBJECT_PARSE_BYTES = 4 * 1024 * 1024

    /** 解 /Dest /A → /D 时跨层间接引用的最大深度。 */
    const val MAX_INDIRECT_DEPTH = 8

    /** /Pages 树递归深度上限（健康 PDF ≤ 10）。 */
    const val MAX_PAGE_TREE_DEPTH = 32

    /** name tree (`/Names/Dests`) 节点 /Kids 嵌套深度。 */
    const val MAX_NAME_TREE_DEPTH = 16

    /** 末端反扫 `startxref` 在文件尾的字节窗口。Spec 要求在最后 1024 内可寻址，留 8KB 容错。 */
    const val EOF_SCAN_WINDOW = 8 * 1024
}

/**
 * 内部解析层的"放弃"异常。由顶层 [PdfOutlineParser.parse] 统一 catch 后回 null。
 *
 * 不继承 [Exception] 的细分（如 IOException）—— 我们不区分"IO 失败 vs 数据损坏"，
 * 对调用方来说都是"outline 拿不到 → 回退分页切片"。
 */
internal class PdfParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
