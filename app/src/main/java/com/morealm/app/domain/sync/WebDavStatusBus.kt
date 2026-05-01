package com.morealm.app.domain.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for WebDav backup status messages.
 *
 * The auto-backup scheduler in `MoRealmApp.appScope` runs entirely outside
 * the ViewModel scope, so its `Result.message` had nowhere to go before
 * this — the user couldn't tell whether their nightly auto-backup
 * succeeded, failed, or never ran.
 *
 * `runIfDue` and `runOnce` both publish through [emit]; any UI that wants
 * the feedback (currently only [com.morealm.app.presentation.profile.ProfileViewModel])
 * subscribes to [statuses]. Replay 1 means a screen opened after the
 * background backup finished still shows the result.
 */
object WebDavStatusBus {
    private val _statuses = MutableSharedFlow<Status>(replay = 1, extraBufferCapacity = 8)
    val statuses: SharedFlow<Status> = _statuses.asSharedFlow()

    /**
     * Source of the status update — lets subscribers prefix the UI with
     * "自动" / "手动" so the user can tell which path produced the line.
     */
    enum class Source { MANUAL, AUTO, PROGRESS }

    data class Status(
        val source: Source,
        val message: String,
        val success: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /** Non-suspending publisher; safe to call from any coroutine context. */
    fun emit(status: Status) {
        _statuses.tryEmit(status)
    }
}
