package com.morealm.app.domain.source

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 登录脚本反向刷新通道。对齐 Legado `SourceLoginJsExtensions.refresh*`：
 * 登录完成后脚本调 `java.refreshBookInfo()` 让 UI 立刻反映新 cookie / token，而不是
 * 等用户下次主动下拉。
 *
 * MoRealm 没有通用 EventBus，按 [com.morealm.app.domain.sync.BackupStatusBus] 的模式
 * 起一个针对登录场景的轻量事件总线。UI 侧在合适的 ViewModel 里 collect 对应 Flow。
 *
 * 事件是**无参一次性信号**：订阅方自己知道要刷的是当前展示的书 / 章节 / 发现页，不需
 * 要从事件载荷里取任何身份信息。这样同一条 refresh 事件可以同时通知所有打开中的相
 * 关页（书架、详情、目录、阅读器）。
 *
 * 发射点：[SourceLoginScriptApi.refreshBookInfo] / `refreshBookToc` / `refreshContent` /
 * `refreshExplore`。
 *
 * 订阅点：
 *  - `refreshBookInfo` → 书籍详情 ViewModel（如果当前栈里有打开书籍）
 *  - `refreshBookToc`  → 目录 ViewModel / 阅读器 ViewModel（目录数据源）
 *  - `refreshContent`  → 阅读器 ViewModel（当前章节正文）
 *  - `refreshExplore`  → 发现页 ViewModel
 */
object SourceLoginRefreshBus {
    private val _bookInfo = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    private val _bookToc = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    private val _content = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    private val _explore = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)

    val bookInfo: SharedFlow<Unit> = _bookInfo.asSharedFlow()
    val bookToc: SharedFlow<Unit> = _bookToc.asSharedFlow()
    val content: SharedFlow<Unit> = _content.asSharedFlow()
    val explore: SharedFlow<Unit> = _explore.asSharedFlow()

    fun emitBookInfo() { _bookInfo.tryEmit(Unit) }
    fun emitBookToc() { _bookToc.tryEmit(Unit) }
    fun emitContent() { _content.tryEmit(Unit) }
    fun emitExplore() { _explore.tryEmit(Unit) }
}
