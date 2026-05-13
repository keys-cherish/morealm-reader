package com.morealm.app.domain.entity

/**
 * 阅读器工具栏中可参与编辑排序的工具枚举。
 *
 * # 设计约束
 * - 与**阅读器核心行为**（翻页动画 / SimulationReadView / ReflowEngine / 手势区 / overlay 显隐）
 *   完全解耦 —— 这里只定义工具的身份标识，不持有任何行为逻辑。
 * - `removable = false` 的工具在编辑态不能被标记隐藏 / 拖到 Hidden 区，确保用户不会
 *   把自己锁出基本功能（目录 / 设置 至少保留）。
 * - **进度条 / 上一章 / 下一章** 等章节导航控件**不在此枚举中**，它们通过独立 slot
 *   渲染，不参与工具栏编辑流程。
 *
 * # 关于图标
 * 图标 [ImageVector] 不在 entity 层持有 —— Compose 依赖不该污染 domain 层。
 * UI 层通过 `ReaderTool.id` 做 switch 映射到实际 [androidx.compose.material.icons]
 * 矢量图标（见 `ReaderToolIcons.kt`）。
 *
 * # 持久化
 * 由 [com.morealm.app.domain.preference.AppPreferences.readerToolbarLayout] 以 JSON
 * 字符串形式存在 `morealm_settings` DataStore 中；键 `reader_toolbar_layout`，格式
 * 见 [ReaderToolLayout.toJson] / [ReaderToolLayout.fromJson]。
 */
enum class ReaderTool(
    val id: String,
    val label: String,
    val removable: Boolean,
    val defaultZone: ReaderToolZone,
) {
    Catalog(id = "catalog", label = "目录", removable = true, defaultZone = ReaderToolZone.Bottom),
    Search(id = "search", label = "搜索", removable = true, defaultZone = ReaderToolZone.Bottom),
    Audio(id = "audio", label = "听书", removable = true, defaultZone = ReaderToolZone.Bottom),
    AutoPage(id = "auto_page", label = "自动翻页", removable = true, defaultZone = ReaderToolZone.Bottom),
    Settings(id = "settings", label = "设置", removable = true, defaultZone = ReaderToolZone.Bottom);

    companion object {
        /** byId 查询；未知 id 返回 null（反序列化时用来跳过旧版删除的工具）。 */
        fun fromId(id: String): ReaderTool? = entries.firstOrNull { it.id == id }
    }
}

/** 工具归属的区域。 */
enum class ReaderToolZone { Top, Bottom, Hidden }

/**
 * 完整工具栏布局快照 —— 记录每个 [ReaderTool] 的归属区域 + 同区域内的排序。
 *
 * 由 [order] 维护显示顺序；zone 从 [zones] 查 —— 两个字段互补：
 * - [order]：全量枚举（所有工具），按用户拖拽后的顺序
 * - [zones]：tool → zone 映射，决定该工具落在哪一行
 *
 * 渲染时按 `order` 遍历，zone=Top 的进顶栏、Bottom 进底栏、Hidden 不显示（仅编辑态在
 * Hidden 区显示）。这样一次遍历就能同时决定"哪些显示"+"什么顺序"。
 */
data class ReaderToolLayout(
    val order: List<ReaderTool>,
    val zones: Map<ReaderTool, ReaderToolZone>,
) {
    /** 取指定 zone 下的工具，按 [order] 排序。 */
    fun toolsIn(zone: ReaderToolZone): List<ReaderTool> =
        order.filter { zones[it] == zone }

    /**
     * 序列化为稳定的单行 JSON 字符串，写入 DataStore。
     *
     * 手写 JSON 而不引 kotlinx.serialization：一来 entity 层想保持零三方依赖，二来
     * 结构足够简单（两字段 + 枚举值）。形如：
     * ```
     * {"order":["catalog","progress",...],"zones":{"catalog":"Bottom",...}}
     * ```
     * 键、值都走白名单枚举，对 JSON 特殊字符无转义需求。
     */
    fun toJson(): String {
        val orderArr = order.joinToString(prefix = "[", postfix = "]") { "\"${it.id}\"" }
        val zonesObj = zones.entries.joinToString(prefix = "{", postfix = "}") { (tool, zone) ->
            "\"${tool.id}\":\"${zone.name}\""
        }
        return """{"order":$orderArr,"zones":$zonesObj}"""
    }

    companion object {
        /**
         * 默认布局：按 [ReaderTool.defaultZone] 展开。`order` 按枚举声明顺序。
         *
         * 作为**首次安装**、用户"恢复默认"、以及 [fromJson] 解析失败时的 fallback。
         */
        val Default: ReaderToolLayout by lazy {
            val order = ReaderTool.entries.toList()
            val zones = order.associateWith { it.defaultZone }
            ReaderToolLayout(order = order, zones = zones)
        }

        /**
         * 从持久化 JSON 恢复布局。解析失败 / 字段缺失 / 包含新版本不认识的工具 id
         * 时，**合并默认布局做容错**：
         * - 新版本新增的工具（JSON 里没有）→ 按默认 zone 追加到 order 末尾
         * - JSON 里有但当前枚举已删除的工具（返回 null）→ 直接跳过
         * - 彻底解析失败 → 返回 [Default]
         *
         * 这样升级 / 降级都不会丢用户自定义的布局，也不会因为一个脏字段整份重置。
         */
        fun fromJson(json: String?): ReaderToolLayout {
            if (json.isNullOrBlank()) return Default
            return runCatching {
                val orderIds = extractStringArray(json, "order")
                val zoneMap = extractStringMap(json, "zones")
                val parsedOrder = orderIds.mapNotNull { ReaderTool.fromId(it) }
                val parsedZones = zoneMap.mapNotNull { (k, v) ->
                    val tool = ReaderTool.fromId(k) ?: return@mapNotNull null
                    val zone = runCatching { ReaderToolZone.valueOf(v) }.getOrNull() ?: return@mapNotNull null
                    tool to zone
                }.toMap()

                // 合并：新版本新增的工具 → 追加到末尾 + 默认 zone
                val missing = ReaderTool.entries.filterNot { it in parsedOrder }
                val fullOrder = parsedOrder + missing
                val fullZones = parsedZones.toMutableMap().apply {
                    missing.forEach { putIfAbsent(it, it.defaultZone) }
                }
                ReaderToolLayout(order = fullOrder, zones = fullZones)
            }.getOrDefault(Default)
        }

        /** 极小手写 JSON array 解析：只认扁平 string 数组。 */
        private fun extractStringArray(json: String, key: String): List<String> {
            val marker = "\"$key\":["
            val start = json.indexOf(marker).takeIf { it >= 0 } ?: return emptyList()
            val from = start + marker.length
            val end = json.indexOf(']', from).takeIf { it >= 0 } ?: return emptyList()
            val body = json.substring(from, end)
            return body.split(',')
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
        }

        /** 极小手写 JSON object 解析：只认扁平 string→string。 */
        private fun extractStringMap(json: String, key: String): Map<String, String> {
            val marker = "\"$key\":{"
            val start = json.indexOf(marker).takeIf { it >= 0 } ?: return emptyMap()
            val from = start + marker.length
            val end = json.indexOf('}', from).takeIf { it >= 0 } ?: return emptyMap()
            val body = json.substring(from, end)
            if (body.isBlank()) return emptyMap()
            return body.split(',').mapNotNull { entry ->
                val kv = entry.split(':')
                if (kv.size != 2) return@mapNotNull null
                val k = kv[0].trim().trim('"')
                val v = kv[1].trim().trim('"')
                if (k.isBlank() || v.isBlank()) null else k to v
            }.toMap()
        }
    }
}
