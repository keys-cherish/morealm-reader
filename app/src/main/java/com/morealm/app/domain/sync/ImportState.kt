package com.morealm.app.domain.sync

/**
 * SAF 批量导入状态机 —— [ImportStateBus] 暴露的 StateFlow 元素。
 *
 * 状态流转：
 *   Idle → Scanning → Phase1(imported↑) → Phase2(enriched↑) → Done
 *                                                          ↘ Error
 *
 * 设计要点：
 * - 每个 chunk（50 本）入库后 emit 一次 [Phase1]，imported 字段实时增长。UI 拿
 *   `imported / total` 渲染 LinearProgressIndicator + 数字。
 * - [Phase2] 进度可选 emit（enrich 失败不影响 Phase 1 入库的书可见，所以 UI 上
 *   Phase 2 卡片可以做得更弱化）。
 * - [Done] / [Error] 是终态；[ImportService] 检测到后 stopForeground + stopSelf。
 * - [Idle] 是 reset 后的默认值；ImportService 启动时立刻 emit [Scanning]。
 */
sealed interface ImportState {

    /** 默认空闲态。新一次导入开始前由 [ImportStateBus.reset] 设置。 */
    data object Idle : ImportState

    /**
     * 扫描文件夹 / 准备 SAF children 阶段。total 未知（FastFileScanner 还没数完）。
     * UI 显示 indeterminate progress + folderName。
     */
    data class Scanning(val folderName: String) : ImportState

    /**
     * Phase 1：header 校验 + bulkInsert chunk 循环（原位引用后无文件复制）。
     *
     * @param imported 已批量入库的本数（chunk 边界 emit，实时增长）
     * @param total    扫描发现的可导入 file 总数（Scanning 阶段算出后 immutable）
     * @param cancelled true = 用户已请求取消，当前 chunk 跑完即停。UI 显示"正在取消…"
     */
    data class Phase1(
        val folderName: String,
        val imported: Int,
        val total: Int,
        val cancelled: Boolean = false,
    ) : ImportState

    /**
     * Phase 2：后台补 metadata / cover / isComic。
     *
     * Phase 1 全部入库后转 Phase 2。Phase 2 失败不回滚已入库的书（保留 placeholder
     * 字段，BookFormatProbeViewModel 兜底 detect）。UI 可弱化为"补元数据中…"。
     *
     * @param enriched 已完成 enrich 的本数
     * @param total    Phase 1 入库的本数（= Phase1.imported 终值）
     */
    data class Phase2(
        val folderName: String,
        val enriched: Int,
        val total: Int,
    ) : ImportState

    /**
     * 终态：成功 / 取消都走 Done，UI 据 cancelled 字段区分文案。
     *
     * @param imported    实际入库本数
     * @param durationMs  从 Scanning 到 Done 的总耗时
     * @param cancelled   true = 用户中途取消，已 imported 本仍在书架
     */
    data class Done(
        val folderName: String,
        val imported: Int,
        val durationMs: Long,
        val cancelled: Boolean = false,
    ) : ImportState

    /** 终态：扫描 / 入库不可恢复错误。已 imported 本仍在书架（不回滚）。 */
    data class Error(
        val folderName: String,
        val message: String,
    ) : ImportState
}
