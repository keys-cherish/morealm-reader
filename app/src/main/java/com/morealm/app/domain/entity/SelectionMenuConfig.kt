package com.morealm.app.domain.entity

import com.morealm.app.core.log.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 阅读器选区 mini menu 的可自定义动作。每个 enum 值对应工具栏上一个按钮。
 *
 * 顺序无语义 —— 真正的"在工具栏里的位置和顺序"由 [SelectionMenuConfig.items]
 * 列表的 (item, position) 对决定。`HIGHLIGHT` 落在主行 / 展开行时表现为一个
 * 「✏️」入口按钮，点开后才弹 5 色调色板；落在 HIDDEN 时调色板根本不渲染。
 */
@Serializable
enum class SelectionMenuItem(val displayName: String) {
    COPY("复制"),
    SPEAK("朗读"),
    TRANSLATE("翻译"),
    SHARE("分享"),
    LOOKUP("查词"),
    HIGHLIGHT("高亮"),
    ;

    companion object {
        val ALL: List<SelectionMenuItem> = entries.toList()
    }
}

/**
 * 选区 mini menu 上单个按钮的位置：
 *   - [MAIN]：始终可见的主行（最多 3 个，超过部分会被强制降级到 EXPANDED）
 *   - [EXPANDED]：点击「更多」按钮后才显示的扩展行
 *   - [HIDDEN]：完全不渲染
 *
 * 「最多 3 个 MAIN」由 UI 层强制（[com.morealm.app.ui.settings.ReadingSettingsScreen]
 * 在用户尝试设置第 4 个 MAIN 时弹 Toast 阻止），存储层不验证 —— 万一持久化的
 * 数据违规（迁移 / 手改），渲染层会自动取前 3 个 MAIN，剩下视作 EXPANDED。
 */
@Serializable
enum class SelectionMenuPosition { MAIN, EXPANDED, HIDDEN }

/**
 * 用户对选区 mini menu 的完整自定义配置。
 *
 * 列表语义：[items] 同时承载「位置分配」(每项的 `position`) 和「同位置内的
 * 顺序」(在列表中的相对顺序)。渲染时按列表遍历，按 position 分桶后保持桶内
 * 相对顺序 —— 这样用户在设置页通过上下移动调整顺序时，逻辑模型就是单一的
 * 列表重排，不需要分桶后单独维护每段的 order 字段。
 *
 * 不变量（由 UI 强制 + [normalize] 在加载时兜底修复）：
 *   - 每个 [SelectionMenuItem] 在 [items] 里恰好出现一次
 *   - MAIN 位置的项 ≤ 3
 */
@Serializable
data class SelectionMenuConfig(
    val items: List<SelectionMenuEntry>,
) {
    @Serializable
    data class SelectionMenuEntry(
        val item: SelectionMenuItem,
        val position: SelectionMenuPosition,
    )

    /**
     * 修复列表里的不变量违例。在反序列化后立即调用 —— 处理：
     *   1. 历史版本里某个 item 缺失 → 追加到末尾，position = HIDDEN
     *   2. MAIN 超过 3 个 → 保留前 3 个 MAIN，多余的降级为 EXPANDED
     *   3. 重复 item → 仅保留首次出现（理论上不会发生，防御性兜底）
     */
    fun normalize(): SelectionMenuConfig {
        val seen = mutableSetOf<SelectionMenuItem>()
        val deduped = items.filter { seen.add(it.item) }

        // 补齐缺失项
        val missing = SelectionMenuItem.ALL - seen
        val withMissing = deduped + missing.map { SelectionMenuEntry(it, SelectionMenuPosition.HIDDEN) }

        // 限制 MAIN 数量为 3
        var mainCount = 0
        val capped = withMissing.map { entry ->
            if (entry.position == SelectionMenuPosition.MAIN) {
                if (mainCount >= 3) entry.copy(position = SelectionMenuPosition.EXPANDED)
                else { mainCount++; entry }
            } else entry
        }
        return SelectionMenuConfig(capped)
    }

    fun position(item: SelectionMenuItem): SelectionMenuPosition =
        items.firstOrNull { it.item == item }?.position ?: SelectionMenuPosition.HIDDEN

    /** 当前 MAIN 位置的项数。UI 用它判断是否还能再放主行。 */
    fun mainCount(): Int = items.count { it.position == SelectionMenuPosition.MAIN }

    /** 按位置分组返回，桶内保持原列表顺序。 */
    fun groupedByPosition(): Map<SelectionMenuPosition, List<SelectionMenuItem>> =
        items.groupBy({ it.position }, { it.item })

    /**
     * 「主/折/隐」三段计数概览，写日志 / 显示标签时通用，避免每个调用点
     * 自己 `count{...}` 三次拼字符串。
     */
    fun summary(): String {
        val main = items.count { it.position == SelectionMenuPosition.MAIN }
        val ext = items.count { it.position == SelectionMenuPosition.EXPANDED }
        val hidden = items.count { it.position == SelectionMenuPosition.HIDDEN }
        return "main=$main expanded=$ext hidden=$hidden"
    }

    companion object {
        /**
         * 默认配置 —— 复制 / 朗读 / 高亮上主行（用频率最高的 3 个），翻译 / 分享 /
         * 查词进展开行。HIGHLIGHT 在主行更顺手，因为高亮调色板是阅读时高频操作。
         */
        val DEFAULT: SelectionMenuConfig = SelectionMenuConfig(
            listOf(
                SelectionMenuEntry(SelectionMenuItem.COPY, SelectionMenuPosition.MAIN),
                SelectionMenuEntry(SelectionMenuItem.SPEAK, SelectionMenuPosition.MAIN),
                SelectionMenuEntry(SelectionMenuItem.HIGHLIGHT, SelectionMenuPosition.MAIN),
                SelectionMenuEntry(SelectionMenuItem.TRANSLATE, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.SHARE, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.LOOKUP, SelectionMenuPosition.EXPANDED),
            )
        )

        /** 日志 tag —— 与 LogTagCatalog 注册名一致，便于按 tag 过滤。 */
        private const val LOG_TAG = "SelectionMenu"

        private val json = Json { ignoreUnknownKeys = true }

        /** JSON 序列化 —— 写入 AppPreferences DataStore 用。 */
        fun encode(config: SelectionMenuConfig): String =
            json.encodeToString(serializer(), config)

        /**
         * JSON 反序列化；解析失败 / 空字符串 → 返回 [DEFAULT]。
         *
         * 解析失败会打 WARN 日志（带前 80 字符 raw 文本片段方便诊断），但绝不抛
         * 异常 —— 配置丢失比阅读器崩溃好得多。
         */
        fun decode(text: String?): SelectionMenuConfig {
            if (text.isNullOrBlank()) return DEFAULT
            return runCatching {
                json.decodeFromString(serializer(), text).normalize()
            }.getOrElse { e ->
                // 截断防日志爆炸；附 message 与异常类型用于诊断格式 / 兼容性问题
                AppLog.warn(
                    LOG_TAG,
                    "decode fallback to DEFAULT: ${e::class.simpleName}: ${e.message}; " +
                        "raw=\"${text.take(80)}${if (text.length > 80) "..." else ""}\"",
                    e,
                )
                DEFAULT
            }
        }
    }
}
