package com.morealm.app.domain.parser.pdf

import android.os.ParcelFileDescriptor
import com.morealm.app.core.log.AppLog

/**
 * 自写 PDF outline 解析器入口。
 *
 * 流水线：
 *  1. [ParcelFileDescriptor] → [PdfRandomReader]（FileChannel.read positional 读）
 *  2. [PdfXrefReader] 反扫 startxref → 读 xref（classical 或 stream + /Prev 链）→ 输出 [PdfXrefReader.XrefTable]
 *  3. [PdfObjectStore] 按 [PdfValue.Ref] 解对象（处理 type=1 + type=2 ObjStm + LRU 缓存）
 *  4. 从 trailer 拿 /Root → catalog → /Pages /Outlines
 *  5. [PdfPageTreeFlattener]：page objNum → 0-based pageIndex
 *  6. [PdfDestResolver]：每个 outline node 的 /Dest 或 /A → pageIndex
 *  7. [PdfOutlineWalker]：DFS pre-order 走 outline 树 → List<RawEntry>
 *  8. 失败率判断（resolved < total/2 → 返 null，让 caller 走 fallback）
 *
 * 任何阶段抛 [PdfParseException] / 其他 Throwable → 顶层 try/catch 吃掉，返回 null。
 * 调用方拿到 null 应回退到分页切片，永远不要把 outline 缺失暴露给用户当成错误。
 *
 * **不支持**（spec 子集裁剪，详见 KDoc）：
 *  - PDF 加密（trailer 含 /Encrypt 直接 null）
 *  - Linearized PDF 的 hint table（忽略，仍用文件末尾 xref）
 *  - 非 FlateDecode 的 Filter（LZW / ASCII85 / DCTDecode 等）
 *  - 非 /GoTo action（/URI、/Launch、/Named、/GoToR 等跳过 entry）
 *  - /PageLabels（用原始 0-based 页码，不显示 "i, ii, A-1"）
 *  - xref 损坏的 recovery 扫描
 *  - ObjStm 的 /Extends 链
 */
class PdfOutlineParser private constructor(
    private val reader: PdfRandomReader,
) : AutoCloseable {

    data class OutlineEntry(val title: String, val pageIndex: Int, val level: Int)

    /**
     * 解析 outline。返回 null = 该 PDF 没 outline / 加密 / 损坏 / 解析失败。
     * 非 null 一定非空（empty result 直接当 null 返）。
     */
    fun parse(): List<OutlineEntry>? {
        return try {
            val xref = PdfXrefReader.read(reader)
            val store = PdfObjectStore(reader, xref)

            val rootRef = xref.trailer["Root"] as? PdfValue.Ref
                ?: throw PdfParseException("trailer missing /Root ref")
            val catalog = store.resolve(rootRef.objNum).asDict()
                ?: throw PdfParseException("/Root is not a dict")

            // 无 /Outlines → 该 PDF 本来就没大纲，正常 fallback
            val outlinesRef = catalog["Outlines"] as? PdfValue.Ref ?: return null
            val outlinesRoot = store.resolve(outlinesRef.objNum).asDict() ?: return null

            val pageIndexMap = PdfPageTreeFlattener.flatten(store, catalog)
            if (pageIndexMap.isEmpty()) return null

            val destResolver = PdfDestResolver(store, pageIndexMap, catalog)
            val walker = PdfOutlineWalker(store, destResolver)
            val result = walker.walk(outlinesRoot)

            if (result.entries.isEmpty()) return null
            // 失败率超过一半 → 当作 outline 不可信
            if (result.totalNodes > 0 && result.resolvedNodes * 2 < result.totalNodes) {
                AppLog.warn(TAG, "outline failure rate too high: resolved=${result.resolvedNodes}/${result.totalNodes}")
                return null
            }

            result.entries.map { OutlineEntry(it.title, it.pageIndex, it.level) }
        } catch (t: Throwable) {
            AppLog.warn(TAG, "parse failed: ${t.message}")
            null
        }
    }

    override fun close() {
        try { reader.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "PdfOutline"

        /**
         * 从 [ParcelFileDescriptor] 建 parser。
         *
         * 注意：[pfd] 由 caller 管理（caller 关闭）；本类不负责关 pfd，只关内部 FileChannel。
         *
         * pipe/socket-backed content provider 的 fd 不可 seek（statSize < 0），
         * 此时返回 null —— caller 应直接走 fallback。
         */
        fun openOrNull(pfd: ParcelFileDescriptor): PdfOutlineParser? {
            if (pfd.statSize <= 0) return null
            return try {
                val reader = PdfRandomReader.open(pfd.fileDescriptor)
                PdfOutlineParser(reader)
            } catch (t: Throwable) {
                AppLog.warn(TAG, "open failed: ${t.message}")
                null
            }
        }

        /**
         * 测试入口：从 [PdfRandomReader] 直接建 parser。生产代码请走 [openOrNull]。
         */
        internal fun forReader(reader: PdfRandomReader): PdfOutlineParser = PdfOutlineParser(reader)
    }
}
