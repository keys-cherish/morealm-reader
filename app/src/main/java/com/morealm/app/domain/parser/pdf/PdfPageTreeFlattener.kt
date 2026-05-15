package com.morealm.app.domain.parser.pdf

/**
 * 把 PDF 的 `/Catalog/Pages` 树平铺成 `Page objNum → 0-based pageIndex` 映射。
 *
 * 树结构（spec 7.7.3）：内部节点 /Type=/Pages 有 /Kids（refs 数组），叶子 /Type=/Page。
 * 我们只关心 objNum 与 page 序号的对应，不关心 /MediaBox / /Resources 等渲染信息。
 */
internal object PdfPageTreeFlattener {

    fun flatten(store: PdfObjectStore, catalog: PdfValue.Dict): Map<Int, Int> {
        val pagesRef = catalog["Pages"] ?: throw PdfParseException("catalog missing /Pages")
        val map = HashMap<Int, Int>()
        val counter = intArrayOf(0)
        // /Pages 本身通常是一个 ref。先解
        val rootObjNum = (pagesRef as? PdfValue.Ref)?.objNum
        val root = store.deref(pagesRef).asDict() ?: throw PdfParseException("/Pages is not a dict")
        // 极端情况：单页 PDF 的 /Pages 直接 = /Page（罕见但 spec 允许）
        dfs(store, root, rootObjNum, map, counter, depth = 0)
        return map
    }

    private fun dfs(
        store: PdfObjectStore,
        node: PdfValue.Dict,
        nodeObjNum: Int?,
        out: HashMap<Int, Int>,
        counter: IntArray,
        depth: Int,
    ) {
        if (depth > PdfLimits.MAX_PAGE_TREE_DEPTH) throw PdfParseException("page tree depth > ${PdfLimits.MAX_PAGE_TREE_DEPTH}")
        val type = (node["Type"] as? PdfValue.Name)?.value
        if (type == "Page") {
            if (nodeObjNum != null) out[nodeObjNum] = counter[0]++
            return
        }
        val kids = node["Kids"] as? PdfValue.Array
            ?: throw PdfParseException("/Pages node has no /Kids (type=$type)")
        for (kid in kids.items) {
            val kidRef = kid as? PdfValue.Ref ?: continue
            val kidNode = store.deref(kid).asDict() ?: continue
            val kidType = (kidNode["Type"] as? PdfValue.Name)?.value
            if (kidType == "Page") {
                out[kidRef.objNum] = counter[0]++
                if (out.size > 1_000_000) throw PdfParseException("page count > 1M")
            } else {
                // /Type=/Pages 或缺失 (容错：缺 type 且有 /Kids 视为中间节点)
                dfs(store, kidNode, kidRef.objNum, out, counter, depth + 1)
            }
        }
    }
}
