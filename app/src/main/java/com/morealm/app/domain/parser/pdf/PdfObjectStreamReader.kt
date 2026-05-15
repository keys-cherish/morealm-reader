package com.morealm.app.domain.parser.pdf

/**
 * Object Stream（ObjStm，spec 7.5.7）解压 + 索引。
 *
 * ObjStm 是 PDF 1.5+ 引入的"压缩对象容器"——把多个非 stream 间接对象塞进一个 FlateDecode 后的字节
 * 序列里，前置 N 对 (objNum, offsetInStream) 索引。xref entry type=2 指向 ObjStm 时通过
 * field2=ObjStmNum + field3=indexInStream 定位。
 *
 * 解析产出：[ObjStmIndex]，提供 `objNum → 字节切片` 查询；切片再交给 [PdfObjectReader] 解析。
 *
 * 限制：
 *  - 不支持 /Extends 链（极少见，多个 ObjStm 共享 first segment）
 *  - 不支持嵌套 ObjStm（spec 禁止：ObjStm 自己不能在 ObjStm 里）
 */
internal object PdfObjectStreamReader {

    class ObjStmIndex(private val data: ByteArray, private val offsets: Map<Int, IntRange>) {
        fun getObjectBytes(objNum: Int): ByteArray? {
            val range = offsets[objNum] ?: return null
            return data.copyOfRange(range.first, range.last + 1)
        }

        fun has(objNum: Int): Boolean = objNum in offsets

        fun parseObject(objNum: Int): PdfValue? {
            val bytes = getObjectBytes(objNum) ?: return null
            val r = PdfObjectReader(bytes)
            r.skipWhitespaceAndComments()
            return r.readValue()
        }
    }

    fun parse(stream: PdfValue.Stream): ObjStmIndex {
        val dict = stream.dict
        val type = (dict["Type"] as? PdfValue.Name)?.value
        if (type != "ObjStm") throw PdfParseException("not an ObjStm: type='$type'")
        val n = (dict["N"] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("ObjStm missing /N")
        val first = (dict["First"] as? PdfValue.Num)?.toInt() ?: throw PdfParseException("ObjStm missing /First")
        if (n < 0 || first < 0) throw PdfParseException("ObjStm bad N=$n First=$first")
        if (n > 10_000) throw PdfParseException("ObjStm /N=$n unreasonably large")

        // /Filter — 只接受 FlateDecode（其他全部当无 filter，但实际生产 ObjStm 必然 FlateDecode）
        val filter = dict["Filter"]
        val isFlate = when (filter) {
            null -> false
            is PdfValue.Name -> filter.value == "FlateDecode"
            is PdfValue.Array -> filter.items.size == 1 &&
                (filter.items[0] as? PdfValue.Name)?.value == "FlateDecode"
            else -> false
        }
        if (filter != null && !isFlate) throw PdfParseException("ObjStm filter '$filter' not supported")

        val raw = if (isFlate) PdfFlateDecode.inflate(stream.rawBytes) else stream.rawBytes
        // /DecodeParms 上 predictor 极罕见，但若有就应用
        val params = (dict["DecodeParms"] as? PdfValue.Dict) ?: (dict["DP"] as? PdfValue.Dict)
        val predictor = (params?.get("Predictor") as? PdfValue.Num)?.toInt() ?: 1
        val decoded = if (predictor == 1) raw else {
            val columns = (params?.get("Columns") as? PdfValue.Num)?.toInt()
                ?: throw PdfParseException("ObjStm predictor without /Columns")
            PdfFlateDecode.applyPredictor(raw, predictor, columns)
        }

        if (first > decoded.size) throw PdfParseException("ObjStm /First=$first beyond decoded size=${decoded.size}")

        // 头部表：N 对 (objNum offsetInsideStream) 用空白分隔
        val header = decoded.copyOfRange(0, first)
        val headerReader = PdfObjectReader(header)
        val pairs = IntArray(2 * n) // 一维存 [obj0, off0, obj1, off1, ...]
        for (i in 0 until n) {
            headerReader.skipWhitespaceAndComments()
            val objNumVal = headerReader.readValue()
            val objNum = (objNumVal as? PdfValue.Num)?.toInt()
                ?: throw PdfParseException("ObjStm header[$i] obj num not a number")
            headerReader.skipWhitespaceAndComments()
            val offsetVal = headerReader.readValue()
            val offsetInStm = (offsetVal as? PdfValue.Num)?.toInt()
                ?: throw PdfParseException("ObjStm header[$i] offset not a number")
            if (offsetInStm < 0) throw PdfParseException("ObjStm header[$i] offset < 0")
            pairs[2 * i] = objNum
            pairs[2 * i + 1] = offsetInStm
        }

        // 把相邻 offset 转成每个对象的字节区间
        val rangesByObj = HashMap<Int, IntRange>(n)
        val bodyData = decoded.copyOfRange(first, decoded.size)
        for (i in 0 until n) {
            val objNum = pairs[2 * i]
            val begin = pairs[2 * i + 1]
            val end = if (i + 1 < n) pairs[2 * (i + 1) + 1] - 1 else bodyData.size - 1
            if (begin < 0 || end >= bodyData.size || begin > end) {
                throw PdfParseException("ObjStm[$i] objNum=$objNum range [$begin..$end] invalid")
            }
            rangesByObj[objNum] = begin..end
        }

        return ObjStmIndex(bodyData, rangesByObj)
    }
}
