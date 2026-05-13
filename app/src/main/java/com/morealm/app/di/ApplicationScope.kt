package com.morealm.app.di

import javax.inject.Qualifier

/**
 * Hilt qualifier for the process-lifetime [kotlinx.coroutines.CoroutineScope].
 *
 * Inject this scope (instead of a ViewModel's `viewModelScope`) for background
 * jobs that **must complete** even after the launching screen / ViewModel
 * goes away — typical example: backup import / restore, where the user often
 * dismisses the page right after tapping "确认恢复" and we don't want the
 * coroutine cancelled mid-write, leaving the DB half-applied.
 *
 * The scope is provided by [AppModule] as a `@Singleton` with
 * `SupervisorJob() + Dispatchers.IO`, so:
 *   - one child failure does NOT cancel siblings,
 *   - IO is the right default dispatcher (file / DB / network),
 *   - the scope is never cancelled — it lives as long as the process.
 *
 * UI feedback for jobs launched on this scope flows back via the
 * application-wide [com.morealm.app.domain.sync.BackupStatusBus] /
 * [com.morealm.app.domain.sync.WebDavStatusBus] singletons, which any screen
 * can subscribe to without holding a reference to the originating ViewModel.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
