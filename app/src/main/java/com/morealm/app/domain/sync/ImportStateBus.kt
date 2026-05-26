package com.morealm.app.domain.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SAF 批量导入进度总线 —— [ImportService] 写，UI / ViewModel 订阅。
 *
 * ## Pattern 对齐
 *
 * 项目里已有 [BackupStatusBus] / [com.morealm.app.domain.sync.WebDavStatusBus] /
 * [com.morealm.app.service.TtsEventBus] / [com.morealm.app.domain.source.SourceLoginRefreshBus]
 * 都是 Kotlin `object` 单例。`object` 本身就是 process-wide singleton，**不需要**
 * Hilt `@Singleton class @Inject` 包装一层。Service / Engine / ViewModel 直接调
 * `ImportStateBus.update(...)` / `collect { ImportStateBus.state }` 即可。
 *
 * ## 与 [BackupStatusBus] 区别
 *
 * BackupStatusBus 是一次性事件 toast (`SharedFlow replay=0`)：用户切页面回来
 * **不应该**看到上次的旧 toast。
 *
 * ImportStateBus 是持续进度状态（`StateFlow`）：用户切走再回来，要立刻看到
 * 当前 `imported / total` 进度。两种总线语义不可互换。
 *
 * ## 取消信号
 *
 * UI 按"取消导入" → 调 [requestCancel]，独立 [cancelRequested] flag 不混进
 * [state] 流。[ImportEngine] 在 chunk 边界（每 50 本入库前后）调
 * [consumeCancelFlag]：返回 true 则停止后续 chunk 并 emit
 * `ImportState.Done(cancelled = true)`。
 *
 * 用独立 flag 而不是 state 内字段的理由：
 * - cancel 是**指令**不是**状态**。状态机的 Phase1 字段已含 `cancelled` 让 UI
 *   显示"正在取消…"，但**指令的接收**应该跟状态发布解耦。
 * - 避免 ABA 问题：state.update(...) 之间 cancel 可能丢失。volatile flag 是
 *   单次消费的 happens-before 保证。
 *
 * ## 多 session 限制
 *
 * 当前仅支持**最多 1 个 import session**同时跑。第二次 startForegroundService
 * 时 [ImportService] 检查 `state.value !is Idle && !is Done && !is Error` 应
 * 拒绝（Toast 提示用户"已有导入在跑"）。多 session 并发是 follow-up，需要
 * sessionId key。
 */
object ImportStateBus {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    /** Publisher（线程安全）。[ImportEngine] 在每个 chunk 边界 / 状态切换点调用。 */
    fun update(newState: ImportState) {
        _state.value = newState
    }

    /** UI 按"取消导入"调。设置 flag 后立即返回，不阻塞 caller。 */
    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * [ImportEngine] 在 chunk 边界调用，**消费**取消信号。
     *
     * 返回 true → 当前 chunk 应该是最后一个，跑完立即 emit Done(cancelled=true)。
     * 消费后 flag 重置为 false，避免下一次导入误触发。
     */
    fun consumeCancelFlag(): Boolean {
        val wasRequested = cancelRequested
        cancelRequested = false
        return wasRequested
    }

    /**
     * 重置回 [ImportState.Idle]。新一次 import 开始前由 [ImportService.onStartCommand]
     * 调用。也用于"完成 / 取消 / 错误"终态停留 N 秒后让 UI 隐藏 progress bar。
     */
    fun reset() {
        _state.value = ImportState.Idle
        cancelRequested = false
    }
}
