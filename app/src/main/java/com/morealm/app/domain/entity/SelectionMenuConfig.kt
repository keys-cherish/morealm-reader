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
    /**
     * 字体强调色 —— 与 HIGHLIGHT 同形 UI（点击展开调色板），但回调走另一条线，
     * 落库时 [com.morealm.app.domain.entity.Highlight.kind] = 1，渲染层不画
     * 背景而是替换前景字色。默认在 EXPANDED 桶里（不强占主行）。
     */
    TEXT_COLOR("字体色"),
    /**
     * 下划线 —— 点击展开"线型 + 颜色"面板（4 种线型 × 5 色），落库时
     * [com.morealm.app.domain.entity.Highlight.kind] = 2，
     * [com.morealm.app.domain.entity.Highlight.underlineStyle] 记线型。
     * 渲染层在基线下方画线，按 style 切换 PathEffect。默认在 EXPANDED 桶。
     */
    UNDERLINE("下划线"),
    /**
     * 替换 —— 把选中的文字换成别的（或删掉）。点击弹独立对话框填「替换为」，
     * 确认后落一条 [com.morealm.app.domain.entity.ReplaceRule]（限当前书，可再限
     * 当前章），当前章立刻重载生效。
     *
     * 与 HIGHLIGHT / UNDERLINE 那种「点开内嵌面板」不同：替换要输入文本，内嵌到
     * 选区条里会把条撑得很高，所以走独立弹窗。
     */
    REPLACE("替换"),
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

        // **2026-05-25 fix**：缺失项按 DEFAULT 位置补，而不是 HIDDEN。
        // 老用户 DataStore 里早期版本保存的 config 不含后加的 UNDERLINE / TEXT_COLOR
        // 等 enum 值；若补成 HIDDEN，popup 主行 / 展开行永远看不到，用户反馈
        // 「点更多也看不到下划线」根因即此。改走 DEFAULT.position 让新加的项
        // 默认按 DEFAULT 设计的位置（如 UNDERLINE → MAIN）显示。
        val missing = SelectionMenuItem.ALL - seen
        val withMissing = deduped + missing.map { item ->
            SelectionMenuEntry(item, DEFAULT.position(item).let {
                // 兜底：DEFAULT 也没收录的项（理论不存在，防御性）退化为 HIDDEN
                if (it == SelectionMenuPosition.HIDDEN) SelectionMenuPosition.EXPANDED else it
            })
        }

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
         * 默认配置 —— 复制 / 高亮 / 下划线 上主行（用频率最高的 3 个），其余进展开行。
         *
         * **2026-05-25 调整**：UNDERLINE 从 EXPANDED 升到 MAIN，LOOKUP 从 MAIN 降到
         * EXPANDED。原因：用户反馈「长按 popup 没有下划线选择」—— 实际是 UNDERLINE
         * 默认在 EXPANDED 行需要点展开按钮才看到，主行不可见容易被误以为缺失。
         * 高亮+下划线是阅读时最高频的"留痕"操作，主行直显更顺手。LOOKUP（查词）相对
         * 低频，进展开行；用户仍可在阅读设置 → 选区菜单按钮自定义重排。
         *
         * **2026-08-02 调整**：选区条改成一行平铺（不再有「更多」折叠），MAIN /
         * EXPANDED 退化成排序权重——排在前面的先显示，HIDDEN 仍不渲染。同时新增
         * [SelectionMenuItem.REPLACE] 并排在 COPY 之后，与参照阅读器的「复制、替换…」
         * 顺序一致。UNDERLINE 因此顺位到 EXPANDED，但一行平铺下照样直显。
         */
        val DEFAULT: SelectionMenuConfig = SelectionMenuConfig(
            listOf(
                SelectionMenuEntry(SelectionMenuItem.COPY, SelectionMenuPosition.MAIN),
                SelectionMenuEntry(SelectionMenuItem.REPLACE, SelectionMenuPosition.MAIN),
                SelectionMenuEntry(SelectionMenuItem.HIGHLIGHT, SelectionMenuPosition.MAIN),
                SelectionMenuEntry(SelectionMenuItem.UNDERLINE, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.LOOKUP, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.SPEAK, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.TRANSLATE, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.SHARE, SelectionMenuPosition.EXPANDED),
                SelectionMenuEntry(SelectionMenuItem.TEXT_COLOR, SelectionMenuPosition.EXPANDED),
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
