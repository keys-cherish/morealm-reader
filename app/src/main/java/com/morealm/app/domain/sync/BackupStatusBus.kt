package com.morealm.app.domain.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一次性事件总线 — 本地导入/导出备份的最终结果。
 *
 * 为什么不用 [com.morealm.app.presentation.profile.ProfileViewModel] 上的
 * `backupStatus` StateFlow + LaunchedEffect 监听：
 *
 * - StateFlow 持有最后一个值；当 ProfileScreen 因为导航到 BackupExportScreen
 *   而退出 composition、再返回时，`LaunchedEffect(backupStatus)` 会用「当前
 *   值」作为 key 重新启动一次，**重放**老 toast。例如先导入成功 (status =
 *   "导入成功") → 用户进入导出选项页做导出 → 回到 ProfileScreen 时 LaunchedEffect
 *   重启，弹出旧的 "导入成功"。即使中间导出已经把 status 改成 "导出成功"，
 *   recompose 时机决定了用户看到的是哪个值。
 * - 单个 Toast 应该是事件，不是状态。一次性事件流 (replay=0) 天然没有重放问题。
 *
 * 收听点：在 [com.morealm.app.ui.navigation.MoRealmNavHost] 顶层订阅 — 它在
 * App 整个生命周期都 alive，永不重启，所以 emit 之后不论用户在哪个页面都能
 * 一次性收到。
 *
 * 发射点：[com.morealm.app.presentation.profile.ProfileViewModel] 的
 * `exportBackup` / `importBackup` 在落地最终态字符串后调用 [emit]。
 *
 * 模式参考自同目录下的 [WebDavStatusBus]，但 replay 改为 0：备份/导入是用户
 * 主动触发的瞬时操作，不需要"打开页面后看见上次结果"的语义；相反，重放会
 * 导致误显示。
 */
object BackupStatusBus {
    private val _events = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Non-suspending publisher; safe to call from any coroutine context. */
    fun emit(message: String) {
        _events.tryEmit(message)
    }
}
