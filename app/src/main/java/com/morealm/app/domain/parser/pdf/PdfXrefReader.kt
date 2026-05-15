package com.morealm.app.domain.parser.pdf

/**
 * Cross-reference (xref) 读取：classical table（PDF 1.4-）+ xref stream（PDF 1.5+）+ /Prev 链。
 *
 * 输出：[XrefTable] 包含 objNum → [XrefEntry] 的全量映射 + trailer dict（含 /Root /Encrypt 等元信息）。
 *
 * 入口：[read]，传入 [PdfRandomReader]。失败抛 [PdfParseException]。
 */
internal object PdfXrefReader {

    /**
     * 单条 xref entry。
     *
     * @property type 0=free / 1=uncompressed / 2=compressed
     * @property field2 type=1 时是文件偏移；type=2 时是 ObjStm 的 obj 号
     * @property field3 type=1 时是 generation；type=2 时是 ObjStm 内的对象索引
     */
    data class XrefEntry(val type: Int, val field2: Long, val field3: Int)

    data class XrefTable(val entries: Map<Int, XrefEntry>, val trailer: PdfValue.Dict, val size: Int)

    /**
     * 入口：找 startxref，解 xref chain（带 /Prev 跳转），返回汇总后的 xref + trailer。
     */
    fun read(reader: PdfRandomReader): XrefTable {
        val startXref = locateStartXref(reader)
        return readChain(reader, startXref)
    }

    /**
     * 反扫文件末尾，找最后一个 `startxref\n<num>\n%%EOF`。
     */
    private fun locateStartXref(reader: PdfRandomReader): Long {
        val tail = reader.readTail(PdfLimits.EOF_SCAN_WINDOW)
        val eofIdx = reader.lastIndexOfAscii(tail, "%%EOF")
        if (eofIdx < 0) throw PdfParseException("missing %%EOF marker in last ${tail.size} bytes")
        val sxIdx = reader.lastIndexOfAscii(tail.copyOf(eofIdx), "startxref")
        if (sxIdx < 0) throw PdfParseException("missing startxref marker before %%EOF")
        // 跳过 "startxref" 9 字节，再跳空白，读数字
        var p = sxIdx + 9
        while (p < tail.size && tail[p].toInt() and 0xFF in setOf(0x20, 0x09, 0x0A, 0x0C, 0x0D)) p++
        val numStart = p
        while (p < tail.size && (tail[p].toInt() and 0xFF) in '0'.code..'9'.code) p++
        if (p == numStart) throw PdfParseException("startxref not followed by a number")
        val numStr = String(tail, numStart, p - numStart, Charsets.US_ASCII)
        val offset = numStr.toLongOrNull() ?: throw PdfParseException("bad startxref number '$numStr'")
        if (offset < 0 || offset >= reader.size) throw PdfParseException("startxref offset $offset out of range")
        return offset
    }

    /**
     * 从 startxref 开始，读 xref + trailer，并跟 /Prev 一直走。
     *
     * 合并规则：早被加入的 entry 优先（newer xref overrides，先读 newer → 先添加，后续 /Prev 中重复 objNum 不覆盖）。
     */
    private fun readChain(reader: PdfRandomReader, firstOffset: Long): XrefTable {
        val entries = HashMap<Int, XrefEntry>()
        var trailer: PdfValue.Dict? = null
        var encryptDetected = false
        var size = 0

        var nextOffset: Long = firstOffset
        var hops = 0
        val visited = HashSet<Long>()

        while (hops < PdfLimits.MAX_XREF_PREV_CHAIN) {
            if (!visited.add(nextOffset)) throw PdfParseException("xref /Prev cycle at $nextOffset")
            val (xref, tr) = readSingle(reader, nextOffset)
            for ((k, v) in xref) {
                if (k !in entries) entries[k] = v
                if (entries.size > PdfLimits.MAX_XREF_ENTRIES) {
                    throw PdfParseException("xref entries exceed ${PdfLimits.MAX_XREF_ENTRIES}")
                }
            }
            if (trailer == null) trailer = tr // 用最新 trailer
            if (tr["Encrypt"] != null) encryptDetected = true
            (tr["Size"] as? PdfValue.Num)?.toInt()?.let { if (it > size) size = it }
            val prev = (tr["Prev"] as? PdfValue.Num)?.toLong() ?: break
            if (prev <= 0 || prev >= reader.size) break
            nextOffset = prev
            hops++
        }

        if (encryptDetected) throw PdfParseException("encrypted PDF (/Encrypt present) — not supported")
        val t = trailer ?: throw PdfParseException("xref chain produced no trailer")
        return XrefTable(entries, t, size)
    }

    /**
     * 读一个 xref 段（classical table 或 xref stream），返回该段的 entries + 其 trailer dict。
     */
    private fun readSingle(reader: PdfRandomReader, offset: Long): Pair<Map<Int, XrefEntry>, PdfValue.Dict> {
        // 读一小段判断是 classical xref 还是 xref stream
        val probeLen = minOf(64L, reader.size - offset).toInt()
        val probe = reader.read(offset, probeLen)
        // 跳空白
        var p = 0
        while (p < probe.size && (probe[p].toInt() and 0xFF) in setOf(0x20, 0x09, 0x0A, 0x0C, 0x0D)) p++
        val startsWithXrefKw = p + 4 <= probe.size &&
            probe[p] == 'x'.code.toByte() && probe[p + 1] == 'r'.code.toByte() &&
            probe[p + 2] == 'e'.code.toByte() && probe[p + 3] == 'f'.code.toByte() &&
            (p + 4 == probe.size ||
                (probe[p + 4].toInt() and 0xFF) in setOf(0x20, 0x09, 0x0A, 0x0C, 0x0D))

        return if (startsWithXrefKw) {
            readClassicalTable(reader, offset)
        } else {
            readXrefStream(reader, offset)
        }
    }

    // ── Classical xref table ──

    private fun readClassicalTable(
        reader: PdfRandomReader,
        offset: Long,
    ): Pair<Map<Int, XrefEntry>, PdfValue.Dict> {
        // 一次性读较大块，足够覆盖 xref + trailer。Pdf 1.4 文档常见 xref 表 < 50KB；保守按 256KB 上限读
        val maxRead = minOf(256 * 1024L, reader.size - offset).toInt()
        val data = reader.read(offset, maxRead)
        val ascii = String(data, Charsets.ISO_8859_1) // 字节级，安全
        var p = 0
        // 跳前导空白
        while (p < ascii.length && ascii[p].code in setOf(0x20, 0x09, 0x0A, 0x0C, 0x0D)) p++
        if (!ascii.startsWith("xref", p)) throw PdfParseException("not an xref table at $offset")
        p += 4
        // 跳 EOL
        while (p < ascii.length && ascii[p].code in setOf(0x20, 0x09, 0x0A, 0x0C, 0x0D)) p++

        val entries = HashMap<Int, XrefEntry>()
        // 多个 subsection: 每个 subsection 头是 "<firstObj> <count>\n"
        while (p < ascii.length) {
            // 看是不是 trailer
            if (ascii.startsWith("trailer", p)) {
                p += 7
                break
            }
            // subsection 头
            val lineEnd = ascii.indexOfFirst(p) { it == '\n' || it == '\r' }
            if (lineEnd < 0) throw PdfParseException("xref subsection header not terminated")
            val header = ascii.substring(p, lineEnd).trim()
            val tokens = header.split(Regex("\\s+"))
            if (tokens.size != 2) throw PdfParseException("bad xref subsection header '$header'")
            val first = tokens[0].toIntOrNull() ?: throw PdfParseException("bad first obj '$header'")
            val count = tokens[1].toIntOrNull() ?: throw PdfParseException("bad count '$header'")
            if (count < 0 || count > PdfLimits.MAX_XREF_ENTRIES) {
                throw PdfParseException("xref subsection count $count out of bounds")
            }
            p = lineEnd
            while (p < ascii.length && (ascii[p] == '\n' || ascii[p] == '\r')) p++
            // 每条 entry 固定 20 字节: "OOOOOOOOOO GGGGG X EOL"
            for (i in 0 until count) {
                if (p + 20 > ascii.length) throw PdfParseException("xref entry truncated at obj ${first + i}")
                val line = ascii.substring(p, p + 20)
                val offsetStr = line.substring(0, 10)
                val genStr = line.substring(11, 16)
                val type = line[17]
                val objNum = first + i
                if (objNum != 0 || type != 'f') { // obj 0 通常是 free，但任意 free 都不进 entries
                    val o = offsetStr.toLongOrNull() ?: throw PdfParseException("bad offset '$offsetStr'")
                    val g = genStr.toIntOrNull() ?: throw PdfParseException("bad gen '$genStr'")
                    if (type == 'n') {
                        entries.putIfAbsent(objNum, XrefEntry(type = 1, field2 = o, field3 = g))
                    }
                    // 'f' free → 不加入；overrides 已存在的也不做（caller 已经按 newer-first 顺序合并）
                }
                p += 20
            }
        }

        // 读 trailer dict
        while (p < ascii.length && ascii[p].code in setOf(0x20, 0x09, 0x0A, 0x0C, 0x0D)) p++
        if (p >= ascii.length || ascii[p] != '<') throw PdfParseException("trailer dict missing at $p")
        val reader2 = PdfObjectReader(data)
        reader2.seek(p)
        val trailerVal = reader2.readValue()
        val trailer = (trailerVal as? PdfValue.Dict) ?: throw PdfParseException("trailer is not a dict")
        return entries to trailer
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }

    // ── Xref stream (PDF 1.5+) ──

    private fun readXrefStream(
        reader: PdfRandomReader,
        offset: Long,
    ): Pair<Map<Int, XrefEntry>, PdfValue.Dict> {
        // xref stream 是一个 indirect object，body 是 dict + stream
        // 先读一大块拿到 dict 和 stream raw bytes
        val maxRead = minOf(8 * 1024 * 1024L, reader.size - offset).toInt()
        val data = reader.read(offset, maxRead)
        val r = PdfObjectReader(data)
        val obj = r.readIndirectObject()
        val stream = (obj as? PdfValue.Stream) ?: throw PdfParseException("xref stream object is not a stream")
        val dict = stream.dict

        val type = (dict["Type"] as? PdfValue.Name)?.value
        if (type != "XRef") throw PdfParseException("xref stream /Type is '$type', expected XRef")

        val sizeNum = (dict["Size"] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("xref stream missing /Size")
        val wArr = (dict["W"] as? PdfValue.Array) ?: throw PdfParseException("xref stream missing /W")
        if (wArr.items.size < 3) throw PdfParseException("xref /W must have 3 fields")
        val w1 = (wArr.items[0] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("xref /W[0] not number")
        val w2 = (wArr.items[1] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("xref /W[1] not number")
        val w3 = (wArr.items[2] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("xref /W[2] not number")
        if (w1 < 0 || w2 <= 0 || w3 < 0) throw PdfParseException("xref /W has invalid widths: $w1 $w2 $w3")
        val entryLen = w1 + w2 + w3

        // /Filter — 只接受 FlateDecode 或缺失
        val filter = dict["Filter"]
        val isFlate = when (filter) {
            null -> false
            is PdfValue.Name -> filter.value == "FlateDecode"
            is PdfValue.Array -> filter.items.size == 1 &&
                (filter.items[0] as? PdfValue.Name)?.value == "FlateDecode"
            else -> false
        }
        if (filter != null && !isFlate) throw PdfParseException("xref stream filter '$filter' not supported")

        // 解压 + 反 predictor
        val raw = if (isFlate) PdfFlateDecode.inflate(stream.rawBytes) else stream.rawBytes
        val decoded = run {
            val params = (dict["DecodeParms"] as? PdfValue.Dict)
                ?: (dict["DP"] as? PdfValue.Dict)
            val predictor = (params?.get("Predictor") as? PdfValue.Num)?.toInt() ?: 1
            val columns = (params?.get("Columns") as? PdfValue.Num)?.toInt() ?: entryLen
            if (predictor == 1) raw else PdfFlateDecode.applyPredictor(raw, predictor, columns)
        }

        // /Index [ first count first count ... ]，缺省 [0 size]
        val indexPairs: List<Pair<Int, Int>> = run {
            val idx = dict["Index"] as? PdfValue.Array
            if (idx == null) {
                listOf(0 to sizeNum)
            } else {
                val pairs = mutableListOf<Pair<Int, Int>>()
                var i = 0
                while (i + 1 < idx.items.size) {
                    val f = (idx.items[i] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("/Index[$i] not num")
                    val c = (idx.items[i + 1] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("/Index[${i+1}] not num")
                    pairs += f to c
                    i += 2
                }
                pairs
            }
        }

        val entries = HashMap<Int, XrefEntry>()
        var cursor = 0
        for ((first, count) in indexPairs) {
            if (count < 0) throw PdfParseException("xref /Index count $count < 0")
            for (i in 0 until count) {
                if (cursor + entryLen > decoded.size) throw PdfParseException("xref stream entries truncated")
                val t = if (w1 == 0) 1L else readField(decoded, cursor, w1) // 默认 type=1
                val f2 = readField(decoded, cursor + w1, w2)
                val f3 = readField(decoded, cursor + w1 + w2, w3)
                cursor += entryLen
                val objNum = first + i
                if (t == 1L || t == 2L) {
                    entries.putIfAbsent(objNum, XrefEntry(type = t.toInt(), field2 = f2, field3 = f3.toInt()))
                }
                if (entries.size > PdfLimits.MAX_XREF_ENTRIES) {
                    throw PdfParseException("xref entries exceed ${PdfLimits.MAX_XREF_ENTRIES}")
                }
                // type=0 (free) 或 t >= 3 (vendor extension) 跳过
            }
        }

        return entries to dict
    }

    private fun readField(data: ByteArray, offset: Int, width: Int): Long {
        if (width == 0) return 0L
        var v = 0L
        for (i in 0 until width) {
            v = (v shl 8) or ((data[offset + i].toInt() and 0xFF).toLong())
        }
        return v
    }
}
