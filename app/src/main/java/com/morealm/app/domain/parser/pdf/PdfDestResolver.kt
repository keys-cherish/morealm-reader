package com.morealm.app.domain.parser.pdf

/**
 * 把 outline node 的 /Dest 或 /A → /D 解析到 0-based 页码。
 *
 * Dest 三种形式（spec 12.3.2.2）：
 *  1. 显式数组 `[<pageRef> /XYZ x y zoom]`（或 /Fit /FitH y 等，我们只用 first element 当 page ref）
 *  2. PDF 1.1 命名 dest：name string，去 `/Catalog/Dests` dict 查
 *  3. PDF 1.2+ 命名 dest：name string，去 `/Catalog/Names/Dests` name tree 查
 *
 * /A action：`<< /Type /Action /S /GoTo /D ... >>`，/S 必须是 /GoTo，/D 视作 dest。
 */
internal class PdfDestResolver(
    private val store: PdfObjectStore,
    private val pageIndexMap: Map<Int, Int>,
    catalog: PdfValue.Dict,
) {
    // 缓存惰性加载 named dest 容器
    private val namedDestsDict: PdfValue.Dict? by lazy {
        store.deref(catalog["Dests"]) as? PdfValue.Dict
    }
    private val nameTreeRoot: PdfValue.Dict? by lazy {
        val names = store.deref(catalog["Names"]) as? PdfValue.Dict ?: return@lazy null
        store.deref(names["Dests"]) as? PdfValue.Dict
    }

    /**
     * 给一个 outline 节点，返回它的目标页 0-based index；解不出返回 null。
     */
    fun resolveOutlineDest(outlineNode: PdfValue.Dict): Int? {
        outlineNode["Dest"]?.let { dest ->
            resolveDest(dest)?.let { return it }
        }
        val action = store.deref(outlineNode["A"]).asDict()
        if (action != null) {
            val s = (action["S"] as? PdfValue.Name)?.value
            if (s == "GoTo") {
                action["D"]?.let { d -> return resolveDest(d) }
            }
            // 非 GoTo action（URI / Launch / Named 等）→ 跳过
        }
        return null
    }

    private fun resolveDest(dest: PdfValue): Int? {
        val v = store.deref(dest) ?: return null
        return when (v) {
            is PdfValue.Array -> resolveArrayDest(v)
            is PdfValue.PString -> resolveNamedDest(PdfStringDecode.decode(v.bytes))
            is PdfValue.Name -> resolveNamedDest(v.value)
            else -> null
        }
    }

    private fun resolveArrayDest(arr: PdfValue.Array): Int? {
        val first = arr.items.firstOrNull() ?: return null
        return when (first) {
            is PdfValue.Ref -> pageIndexMap[first.objNum]
            is PdfValue.Num -> {
                // 一些工具直接用页号（0-based）；做范围检查
                val idx = first.toInt()
                if (idx in 0 until pageIndexMap.size) idx else null
            }
            else -> null
        }
    }

    private fun resolveNamedDest(name: String): Int? {
        // 1. /Catalog/Dests dict 形式
        namedDestsDict?.let { dict ->
            // PDF 1.1 dict 的 key 是 name；这里的 dict.entries 已经按 name 字符串存
            val v = dict[name]
            if (v != null) return unwrapNamedDest(v)
        }
        // 2. /Catalog/Names/Dests name tree
        nameTreeRoot?.let { root ->
            val v = findInNameTree(root, name, depth = 0)
            if (v != null) return unwrapNamedDest(v)
        }
        return null
    }

    /**
     * Named dest 的值可能是：
     *  - 直接的 explicit dest array
     *  - 一个 dict `<< /D [...] >>`（PDF 1.2+ 包了一层）
     *  - ref → 上述两者之一
     */
    private fun unwrapNamedDest(v: PdfValue): Int? {
        val dv = store.deref(v) ?: return null
        val arr = when (dv) {
            is PdfValue.Array -> dv
            is PdfValue.Dict -> store.deref(dv["D"]) as? PdfValue.Array
            else -> null
        } ?: return null
        return resolveArrayDest(arr)
    }

    /**
     * Name tree 查找。叶子（有 /Names）做二分；中间节点（有 /Kids）按 /Limits 缩小范围。
     */
    private fun findInNameTree(node: PdfValue.Dict, name: String, depth: Int): PdfValue? {
        if (depth > PdfLimits.MAX_NAME_TREE_DEPTH) return null

        val names = node["Names"] as? PdfValue.Array
        if (names != null) {
            // 叶子：键值对线性扫描（name tree 规模通常小，二分收益有限）
            var i = 0
            while (i + 1 < names.items.size) {
                val keyStr = (names.items[i] as? PdfValue.PString)?.let { PdfStringDecode.decode(it.bytes) }
                if (keyStr == name) return names.items[i + 1]
                i += 2
            }
            return null
        }

        val kids = node["Kids"] as? PdfValue.Array ?: return null
        for (kid in kids.items) {
            val kidDict = store.deref(kid).asDict() ?: continue
            // /Limits 缩小搜索区间
            val limits = kidDict["Limits"] as? PdfValue.Array
            if (limits != null && limits.items.size >= 2) {
                val low = (limits.items[0] as? PdfValue.PString)?.let { PdfStringDecode.decode(it.bytes) }
                val high = (limits.items[1] as? PdfValue.PString)?.let { PdfStringDecode.decode(it.bytes) }
                if (low != null && high != null && (name < low || name > high)) continue
            }
            val found = findInNameTree(kidDict, name, depth + 1)
            if (found != null) return found
        }
        return null
    }
}
