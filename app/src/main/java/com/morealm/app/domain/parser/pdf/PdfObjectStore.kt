package com.morealm.app.domain.parser.pdf

/**
 * 间接对象（[PdfValue.Ref]）→ 已 parsed [PdfValue] 的中央仓库。
 *
 * 用法：调用 [resolve] 拿对象；递归引用由 [deref] 帮忙跟到 max-depth 截止。
 *
 * 内部细节：
 *  - xref entry type=1（uncompressed）：random read 文件偏移 + recursive-descent parse
 *  - xref entry type=2（compressed in ObjStm）：先把对应 ObjStm load 进 [objStmCache]（也是用 resolve 递归拿），
 *    再从 ObjStm 内取对象字节切片 parse
 *  - LRU 限缓存大小（避免大书爆内存）
 */
internal class PdfObjectStore(
    private val reader: PdfRandomReader,
    private val xref: PdfXrefReader.XrefTable,
) {
    /** parsed object 的 LRU 缓存。key = objNum。 */
    private val objectCache: LinkedHashMap<Int, PdfValue> = object : LinkedHashMap<Int, PdfValue>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, PdfValue>?): Boolean = size > 512
    }

    /** ObjStm（按 obj 号）的 LRU 缓存。 */
    private val objStmCache: LinkedHashMap<Int, PdfObjectStreamReader.ObjStmIndex> =
        object : LinkedHashMap<Int, PdfObjectStreamReader.ObjStmIndex>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, PdfObjectStreamReader.ObjStmIndex>?): Boolean =
                size > 16
        }

    val trailer: PdfValue.Dict get() = xref.trailer

    /**
     * 解析一个间接引用。找不到 / 损坏抛 [PdfParseException]。
     *
     * @param resolveDepth 跨层间接保护：limit 已达后再返回原始 Ref（避免无限递归 ref → ref → ref）
     */
    fun resolve(objNum: Int): PdfValue {
        objectCache[objNum]?.let { return it }
        val entry = xref.entries[objNum] ?: throw PdfParseException("xref has no obj $objNum")
        val value = when (entry.type) {
            1 -> readUncompressed(objNum, entry.field2)
            2 -> readFromObjStm(objNum, entry.field2.toInt(), entry.field3)
            else -> throw PdfParseException("unknown xref entry type ${entry.type} for obj $objNum")
        }
        objectCache[objNum] = value
        return value
    }

    /**
     * 如果 [v] 是 [PdfValue.Ref] 就解引用，递归直到非 Ref 或达到深度上限。
     * 深度上限耗尽时抛 [PdfParseException]（实际意义上的环引用，无法恢复）。
     */
    fun deref(v: PdfValue?, maxDepth: Int = PdfLimits.MAX_INDIRECT_DEPTH): PdfValue? {
        if (v == null) return null
        var cur = v
        var depth = 0
        val visited = HashSet<Int>()
        while (cur is PdfValue.Ref) {
            if (!visited.add(cur.objNum)) throw PdfParseException("indirect ref cycle at obj ${cur.objNum}")
            if (depth >= maxDepth) throw PdfParseException("indirect ref depth > $maxDepth")
            cur = resolve(cur.objNum)
            depth++
        }
        return cur
    }

    private fun readUncompressed(objNum: Int, offset: Long): PdfValue {
        if (offset <= 0 || offset >= reader.size) throw PdfParseException("obj $objNum offset $offset out of range")
        // 读一大块，足够覆盖 indirect object body。多数对象 < 4KB；上限 [PdfLimits.MAX_OBJECT_PARSE_BYTES]。
        val len = minOf(PdfLimits.MAX_OBJECT_PARSE_BYTES.toLong(), reader.size - offset).toInt()
        val data = reader.read(offset, len)
        val r = PdfObjectReader(data)
        return r.readIndirectObject()
    }

    private fun readFromObjStm(objNum: Int, objStmNum: Int, indexInStm: Int): PdfValue {
        val stmIdx = objStmCache.getOrPut(objStmNum) {
            val stmVal = resolve(objStmNum) // ObjStm 自己也是间接对象
            val stm = (stmVal as? PdfValue.Stream)
                ?: throw PdfParseException("obj $objStmNum referenced as ObjStm is not a stream")
            PdfObjectStreamReader.parse(stm)
        }
        return stmIdx.parseObject(objNum)
            ?: throw PdfParseException("obj $objNum not in ObjStm $objStmNum (expected index $indexInStm)")
    }
}
