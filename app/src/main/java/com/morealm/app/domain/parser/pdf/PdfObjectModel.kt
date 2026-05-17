package com.morealm.app.domain.parser.pdf

/**
 * PDF 对象模型 + tokenizer + recursive-descent object reader 合一文件。
 *
 * 三件事强耦合（tokenizer 直接产 [PdfValue]，object reader 通过 tokenizer 推进），
 * 拆三个文件会有大量 friend-class-style 互访，合并更易读。
 *
 * 解析的 PDF 对象类型（spec 7.3）：
 *  - Boolean / Number / Name / String (literal + hex) / Array / Dict / Null / Reference / Stream
 *
 * 不实现：comment 作为 token（直接跳过），inline image，content stream operators。
 * 这是 outline 解析子集——我们只读 trailer/catalog/outline/page-tree 类的 metadata 对象。
 */

// ── PdfValue sealed class ─────────────────────────────

internal sealed class PdfValue {
    object Null : PdfValue()
    data class Bool(val value: Boolean) : PdfValue()
    /** 统一用 Double 承载 int + real，调用方按需取 toInt()/toLong()。PDF 数字理论上无限精度，实践 Double 够用。 */
    data class Num(val value: Double) : PdfValue() {
        fun toInt(): Int = value.toInt()
        fun toLong(): Long = value.toLong()
    }
    data class Name(val value: String) : PdfValue()
    /** PDF 字符串的原始字节（literal 已 unescape / hex 已解十六进制）。再走 [PdfStringDecode] 转 Unicode。 */
    data class PString(val bytes: ByteArray) : PdfValue() {
        override fun equals(other: Any?): Boolean = other is PString && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data class Array(val items: List<PdfValue>) : PdfValue()
    data class Dict(val entries: Map<String, PdfValue>) : PdfValue() {
        operator fun get(key: String): PdfValue? = entries[key]
    }
    /** 间接引用 `N G R`。G 通常 0；我们忽略 generation 在 outline 场景的语义。 */
    data class Ref(val objNum: Int, val gen: Int) : PdfValue()
    /**
     * 流对象 = dict + raw 字节。raw 是原始（已应用 /Filter 但未解 predictor 之前？不——
     * 我们这里只保留**未解压**的原始字节，让调用方决定怎么解）。
     */
    data class Stream(val dict: Dict, val rawBytes: ByteArray) : PdfValue() {
        override fun equals(other: Any?): Boolean = other is Stream && dict == other.dict && rawBytes.contentEquals(other.rawBytes)
        override fun hashCode(): Int = 31 * dict.hashCode() + rawBytes.contentHashCode()
    }
}

// ── Helper extensions for safe casting in callers ─────

internal fun PdfValue?.asDict(): PdfValue.Dict? = this as? PdfValue.Dict
internal fun PdfValue?.asArray(): PdfValue.Array? = this as? PdfValue.Array
internal fun PdfValue?.asRef(): PdfValue.Ref? = this as? PdfValue.Ref
internal fun PdfValue?.asNum(): PdfValue.Num? = this as? PdfValue.Num
internal fun PdfValue?.asName(): PdfValue.Name? = this as? PdfValue.Name
internal fun PdfValue?.asPString(): PdfValue.PString? = this as? PdfValue.PString
internal fun PdfValue?.asStream(): PdfValue.Stream? = this as? PdfValue.Stream

// ── Tokenizer / Parser ────────────────────────────────

/**
 * 在一段 PDF 字节缓冲（任意位置开始）上做递归下降解析。
 *
 * 不直接 random-access 文件——random read 由上层 [PdfObjectStore] 做，把读到的字节传给 [PdfObjectReader]。
 * 这样 reader 是纯函数 over byte buffer，单元测试只需构造字节字面量。
 */
internal class PdfObjectReader(private val data: ByteArray, private val base: Int = 0) {

    private var pos: Int = 0

    val position: Int get() = pos

    fun seek(p: Int) {
        if (p < 0 || p > data.size - base) throw PdfParseException("seek out of range: $p")
        pos = p
    }

    fun hasMore(): Boolean = pos < data.size - base

    // ── Byte access ──

    private fun byteAt(p: Int): Int = data[base + p].toInt() and 0xFF
    private fun curByte(): Int = byteAt(pos)
    private fun advance() { pos++ }

    /**
     * 跳过空白 + 注释。spec 7.2.3：whitespace 是 NUL/HT/LF/FF/CR/SP；注释是 `%` 到行尾。
     */
    fun skipWhitespaceAndComments() {
        while (pos < data.size - base) {
            val b = curByte()
            when {
                isWhitespace(b) -> advance()
                b == '%'.code -> {
                    // 跳到行尾（CR 或 LF）
                    while (pos < data.size - base) {
                        val c = curByte()
                        advance()
                        if (c == 0x0A || c == 0x0D) break
                    }
                }
                else -> return
            }
        }
    }

    /** Spec 7.2.3 whitespace set. */
    private fun isWhitespace(b: Int): Boolean =
        b == 0x00 || b == 0x09 || b == 0x0A || b == 0x0C || b == 0x0D || b == 0x20

    /** Spec 7.2.3 delimiter set. */
    private fun isDelimiter(b: Int): Boolean =
        b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code ||
        b == '['.code || b == ']'.code || b == '{'.code || b == '}'.code ||
        b == '/'.code || b == '%'.code

    private fun isRegular(b: Int): Boolean = !isWhitespace(b) && !isDelimiter(b)

    // ── Top-level: parse one PdfValue ──

    /**
     * 解一个对象。可能是任意 [PdfValue] 子类（除 Stream —— stream 必须由 [readIndirectObject] 走）。
     *
     * 引用 `N G R` 是一个 token 序列，需要 look-ahead；这里在拿到一个 Num 后会试探后续。
     */
    fun readValue(): PdfValue {
        skipWhitespaceAndComments()
        if (!hasMore()) throw PdfParseException("eof while reading value at $pos")
        val b = curByte()
        return when {
            b == '<'.code && peekAhead(1) == '<'.code -> readDict()
            b == '<'.code -> readHexString()
            b == '('.code -> readLiteralString()
            b == '['.code -> readArray()
            b == '/'.code -> readName()
            b == '+'.code || b == '-'.code || b == '.'.code || (b in '0'.code..'9'.code) -> readNumberOrRef()
            isRegular(b) -> readKeywordOrBool()
            else -> throw PdfParseException("unexpected byte 0x${b.toString(16)} at $pos")
        }
    }

    private fun peekAhead(offset: Int): Int =
        if (pos + offset < data.size - base) byteAt(pos + offset) else -1

    // ── Name `/...` ──

    private fun readName(): PdfValue.Name {
        if (curByte() != '/'.code) throw PdfParseException("name must start with /, at $pos")
        advance()
        val sb = StringBuilder()
        while (pos < data.size - base) {
            val b = curByte()
            if (!isRegular(b)) break
            if (b == '#'.code) {
                // 反转义 #XX 两位十六进制
                advance()
                if (pos + 1 >= data.size - base) throw PdfParseException("truncated #escape in name at $pos")
                val h1 = hexDigit(byteAt(pos))
                val h2 = hexDigit(byteAt(pos + 1))
                if (h1 < 0 || h2 < 0) throw PdfParseException("bad #escape in name at $pos")
                sb.append(((h1 shl 4) or h2).toChar())
                pos += 2
            } else {
                sb.append(b.toChar())
                advance()
            }
        }
        return PdfValue.Name(sb.toString())
    }

    private fun hexDigit(b: Int): Int = when (b) {
        in '0'.code..'9'.code -> b - '0'.code
        in 'a'.code..'f'.code -> b - 'a'.code + 10
        in 'A'.code..'F'.code -> b - 'A'.code + 10
        else -> -1
    }

    // ── Literal string `(...)` ──

    private fun readLiteralString(): PdfValue.PString {
        if (curByte() != '('.code) throw PdfParseException("literal string must start with (")
        advance()
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (pos < data.size - base) {
            val b = curByte()
            when {
                b == '('.code -> { depth++; out.write(b); advance() }
                b == ')'.code -> {
                    depth--
                    if (depth == 0) { advance(); return PdfValue.PString(out.toByteArray()) }
                    out.write(b); advance()
                }
                b == '\\'.code -> {
                    advance()
                    if (!hasMore()) throw PdfParseException("trailing \\ in literal string")
                    val c = curByte()
                    when (c) {
                        'n'.code -> { out.write(0x0A); advance() }
                        'r'.code -> { out.write(0x0D); advance() }
                        't'.code -> { out.write(0x09); advance() }
                        'b'.code -> { out.write(0x08); advance() }
                        'f'.code -> { out.write(0x0C); advance() }
                        '('.code, ')'.code, '\\'.code -> { out.write(c); advance() }
                        0x0A -> advance() // \LF → 续行
                        0x0D -> {
                            advance()
                            if (hasMore() && curByte() == 0x0A) advance() // \CRLF → 续行
                        }
                        in '0'.code..'7'.code -> {
                            // 八进制最多 3 位
                            var v = 0
                            var n = 0
                            while (n < 3 && hasMore() && curByte() in '0'.code..'7'.code) {
                                v = (v shl 3) or (curByte() - '0'.code)
                                advance(); n++
                            }
                            out.write(v and 0xFF)
                        }
                        else -> { /* spec: 未识别 escape 视为字面 '\' 后跟该字符 — 这里跳过 '\'，写下一个字符 */
                            out.write(c); advance()
                        }
                    }
                }
                b == 0x0D -> {
                    // CR / CRLF → 规范化为 LF
                    out.write(0x0A); advance()
                    if (hasMore() && curByte() == 0x0A) advance()
                }
                else -> { out.write(b); advance() }
            }
        }
        throw PdfParseException("unterminated literal string")
    }

    // ── Hex string `<...>` ──

    private fun readHexString(): PdfValue.PString {
        if (curByte() != '<'.code) throw PdfParseException("hex string must start with <")
        advance()
        val out = java.io.ByteArrayOutputStream()
        var nibble = -1
        while (pos < data.size - base) {
            val b = curByte()
            if (b == '>'.code) {
                advance()
                if (nibble != -1) out.write(nibble shl 4) // 奇数位 → 末位补 0
                return PdfValue.PString(out.toByteArray())
            }
            if (isWhitespace(b)) { advance(); continue }
            val d = hexDigit(b)
            if (d < 0) throw PdfParseException("bad hex digit in string at $pos: 0x${b.toString(16)}")
            if (nibble == -1) nibble = d else { out.write((nibble shl 4) or d); nibble = -1 }
            advance()
        }
        throw PdfParseException("unterminated hex string")
    }

    // ── Array `[ ... ]` ──

    private fun readArray(): PdfValue.Array {
        if (curByte() != '['.code) throw PdfParseException("array must start with [")
        advance()
        val items = mutableListOf<PdfValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (!hasMore()) throw PdfParseException("unterminated array")
            if (curByte() == ']'.code) { advance(); return PdfValue.Array(items) }
            items.add(readValue())
            if (items.size > 100_000) throw PdfParseException("array too large")
        }
    }

    // ── Dict `<< ... >>` ──

    private fun readDict(): PdfValue.Dict {
        if (curByte() != '<'.code || peekAhead(1) != '<'.code) throw PdfParseException("dict must start with <<")
        pos += 2
        val entries = LinkedHashMap<String, PdfValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (!hasMore()) throw PdfParseException("unterminated dict")
            if (curByte() == '>'.code && peekAhead(1) == '>'.code) {
                pos += 2
                return PdfValue.Dict(entries)
            }
            // key 必须是 name
            if (curByte() != '/'.code) throw PdfParseException("dict key not /name at $pos: 0x${curByte().toString(16)}")
            val key = readName().value
            skipWhitespaceAndComments()
            if (!hasMore()) throw PdfParseException("dict missing value for /$key")
            val value = readValue()
            entries[key] = value
            if (entries.size > 10_000) throw PdfParseException("dict too large")
        }
    }

    // ── Number / Reference ──

    /**
     * 数字一定可以解出来；后续两个 token 如果是另一个非负整数 + 单字母 `R`，则三者合成 [PdfValue.Ref]。
     */
    private fun readNumberOrRef(): PdfValue {
        val first = readRawNumber()
        // 看后面有没有 ` G R` 模式
        val savedPos = pos
        skipWhitespaceAndComments()
        if (hasMore() && (curByte() in '0'.code..'9'.code || curByte() == '+'.code || curByte() == '-'.code)) {
            val saved2 = pos
            val maybeGen = try { readRawNumber() } catch (_: PdfParseException) { pos = savedPos; return PdfValue.Num(first) }
            skipWhitespaceAndComments()
            if (hasMore() && curByte() == 'R'.code) {
                // 确认 'R' 后是 delimiter / whitespace / EOF
                if (pos + 1 >= data.size - base || !isRegular(byteAt(pos + 1))) {
                    advance()
                    return PdfValue.Ref(first.toInt(), maybeGen.toInt())
                }
            }
            // 不是 ref，回退
            pos = savedPos
        } else {
            pos = savedPos
        }
        return PdfValue.Num(first)
    }

    private fun readRawNumber(): Double {
        val start = pos
        if (hasMore() && (curByte() == '+'.code || curByte() == '-'.code)) advance()
        var hasDigit = false
        while (hasMore() && curByte() in '0'.code..'9'.code) { hasDigit = true; advance() }
        if (hasMore() && curByte() == '.'.code) {
            advance()
            while (hasMore() && curByte() in '0'.code..'9'.code) { hasDigit = true; advance() }
        }
        if (!hasDigit) throw PdfParseException("not a number at $start")
        val str = String(data, base + start, pos - start, Charsets.US_ASCII)
        return str.toDoubleOrNull() ?: throw PdfParseException("bad number '$str' at $start")
    }

    // ── Keyword (true/false/null) ──

    private fun readKeywordOrBool(): PdfValue {
        val start = pos
        while (hasMore() && isRegular(curByte())) advance()
        val kw = String(data, base + start, pos - start, Charsets.US_ASCII)
        return when (kw) {
            "true" -> PdfValue.Bool(true)
            "false" -> PdfValue.Bool(false)
            "null" -> PdfValue.Null
            else -> throw PdfParseException("unknown keyword '$kw' at $start")
        }
    }

    /**
     * 解一个完整的 indirect object：`N G obj <body> endobj`。
     *
     * 若 body 后跟 `stream\n...\nendstream`，则返回 [PdfValue.Stream]（dict + raw bytes，未解压）。
     *
     * 调用方：tokenizer 当前 position 应该在 `N` 之前（即 `<num> <gen> obj ...`）。
     */
    fun readIndirectObject(): PdfValue {
        skipWhitespaceAndComments()
        readRawNumber() // objNum，忽略（caller 已知道）
        skipWhitespaceAndComments()
        readRawNumber() // gen，忽略
        skipWhitespaceAndComments()
        // 读 'obj' 关键字
        val obj = readKeywordRaw()
        if (obj != "obj") throw PdfParseException("expected 'obj', got '$obj'")
        skipWhitespaceAndComments()
        val body = readValue()
        skipWhitespaceAndComments()
        if (!hasMore()) return body
        // 看是不是 stream
        val mark = pos
        val maybeStream = try { readKeywordRaw() } catch (_: PdfParseException) { pos = mark; return body }
        if (maybeStream == "stream") {
            // 流体起始：spec 要求 stream 关键字后必须有 EOL（CR LF 或 LF；不允许只有 CR）
            if (hasMore() && curByte() == 0x0D) {
                advance()
                if (hasMore() && curByte() == 0x0A) advance()
            } else if (hasMore() && curByte() == 0x0A) {
                advance()
            }
            val dict = body as? PdfValue.Dict ?: throw PdfParseException("stream body not a dict")
            val length = (dict["Length"] as? PdfValue.Num)?.toInt()
                ?: throw PdfParseException("stream missing /Length (or it's indirect — caller must resolve first)")
            if (length < 0 || length > PdfLimits.MAX_OBJECT_PARSE_BYTES) {
                throw PdfParseException("stream length out of range: $length")
            }
            if (pos + length > data.size - base) {
                throw PdfParseException("stream extends past buffer: pos=$pos len=$length size=${data.size - base}")
            }
            val raw = data.copyOfRange(base + pos, base + pos + length)
            pos += length
            skipWhitespaceAndComments()
            val endTok = try { readKeywordRaw() } catch (e: PdfParseException) {
                throw PdfParseException("missing endstream", e)
            }
            if (endTok != "endstream") throw PdfParseException("expected endstream, got '$endTok'")
            return PdfValue.Stream(dict, raw)
        }
        // 不是 stream，回退（body 已读完）
        pos = mark
        return body
    }

    private fun readKeywordRaw(): String {
        val start = pos
        while (hasMore() && isRegular(curByte())) advance()
        if (pos == start) throw PdfParseException("expected keyword at $start, got delimiter")
        return String(data, base + start, pos - start, Charsets.US_ASCII)
    }
}
