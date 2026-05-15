package com.morealm.app.domain.parser.pdf

/**
 * 从 `/Catalog/Outlines` 走 outline 树，把每个有效条目展平成 [RawEntry] 列表。
 *
 * Outline 是双向链表+树（spec 12.3.3）：
 *  - root dict 的 /First 指向第一个一级条目
 *  - 每个 outline node 通过 /Next 走兄弟、/First 进子
 *  - /Prev /Parent /Last /Count 我们不依赖
 *
 * 返回时**保持文档顺序**（DFS pre-order），所以章节列表里看到的顺序 = PDF reader 里看到的顺序。
 *
 * 单个 entry 失败（title 缺失 / dest 解不到 / 节点损坏）→ 跳过该 entry 继续走；
 * 整体失败率统计交给 [PdfOutlineParser] 做（< 50% 才信任 outline）。
 */
internal class PdfOutlineWalker(
    private val store: PdfObjectStore,
    private val destResolver: PdfDestResolver,
) {
    data class RawEntry(val title: String, val pageIndex: Int, val level: Int)

    data class WalkResult(val entries: List<RawEntry>, val totalNodes: Int, val resolvedNodes: Int)

    fun walk(outlinesRoot: PdfValue.Dict): WalkResult {
        val out = mutableListOf<RawEntry>()
        val visited = HashSet<Int>()
        val counters = IntArray(2) // [totalNodes, resolvedNodes]
        val firstRef = outlinesRoot["First"] as? PdfValue.Ref
            ?: return WalkResult(emptyList(), 0, 0)
        walkSiblings(firstRef, level = 0, out, visited, counters)
        return WalkResult(out, totalNodes = counters[0], resolvedNodes = counters[1])
    }

    /**
     * 从某节点开始沿 /Next 链遍历兄弟，并对每个节点递归进入 /First 子链。
     */
    private fun walkSiblings(
        startRef: PdfValue.Ref,
        level: Int,
        out: MutableList<RawEntry>,
        visited: HashSet<Int>,
        counters: IntArray,
    ) {
        if (level >= PdfLimits.MAX_OUTLINE_DEPTH) return
        var cur: PdfValue.Ref? = startRef
        while (cur != null) {
            if (out.size >= PdfLimits.MAX_OUTLINE_ENTRIES) return
            if (visited.size >= PdfLimits.MAX_OUTLINE_VISITED) return
            if (!visited.add(cur.objNum)) return // 环：兄弟链回到自己

            val node: PdfValue.Dict = try {
                store.deref(cur).asDict() ?: return
            } catch (_: PdfParseException) {
                return // 节点损坏，停掉这条兄弟链
            }
            counters[0]++

            // 解 title
            val titleBytes = (node["Title"] as? PdfValue.PString)?.bytes
            val title = if (titleBytes != null) PdfStringDecode.decode(titleBytes).trim() else ""

            // 解 dest → pageIndex
            val pageIdx: Int? = try {
                destResolver.resolveOutlineDest(node)
            } catch (_: PdfParseException) { null }

            if (title.isNotEmpty() && pageIdx != null) {
                out.add(RawEntry(title, pageIdx, level))
                counters[1]++
            }

            // 递归子链（即使本节点失败也尝试递归）
            val firstChild = node["First"] as? PdfValue.Ref
            if (firstChild != null) {
                walkSiblings(firstChild, level + 1, out, visited, counters)
            }

            // 下一兄弟
            cur = node["Next"] as? PdfValue.Ref
        }
    }
}
