package com.morealm.app

import android.app.Application
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.CookieDao
import com.morealm.app.domain.db.TxtTocRuleDao
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.sync.WebDavBackupRunner
import com.morealm.app.domain.sync.WebDavBookProgressSync
import com.morealm.app.domain.webbook.CacheBook
import com.morealm.app.service.TtsErrorPresenter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MoRealmApp : Application() {

    @Inject lateinit var cacheDao: CacheDao
    @Inject lateinit var cookieDao: CookieDao
    @Inject lateinit var txtTocRuleDao: TxtTocRuleDao
    @Inject lateinit var progressSync: WebDavBookProgressSync
    @Inject lateinit var backupRunner: WebDavBackupRunner

    /**
     * App-scoped supervisor for fire-and-forget background work that
     * shouldn't be cancelled when an Activity goes away (e.g. WebDav
     * progress sync on cold start, future auto-backup scheduler).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Surfaces TTS init / playback failures as Toasts. Subscribes for the lifetime
     * of [appScope] so errors raised while the user is on a non-Reader screen
     * (e.g. settings, launcher) still reach the user.
     */
    private val ttsErrorPresenter by lazy { TtsErrorPresenter(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        CacheManager.init(cacheDao)
        CookieStore.init(cookieDao)
        CacheBook.init(cacheDao)
        LocalBookParser.txtTocRuleDao = txtTocRuleDao

        // P1-B: pull every other-device book progress once at app start.
        // The sync class itself short-circuits to a no-op when WebDav is
        // unconfigured or syncBookProgress is disabled, so this is cheap
        // for users who haven't opted in.
        appScope.launch {
            runCatching { progressSync.downloadAll() }
                .onFailure { AppLog.warn("App", "Initial progress sync failed: ${it.message}") }
        }

        // P1-E: opportunistic auto-backup. The runner enforces the 24h
        // window itself; we just ask once per cold start. No-op when the
        // user hasn't enabled `autoBackup`.
        appScope.launch {
            runCatching { backupRunner.runIfDue() }
                .onFailure { AppLog.warn("App", "Auto-backup check failed: ${it.message}") }
        }

        AppLog.info("App", "MoRealm started")

        // Wire up TTS error → Toast bridge. Must happen after appScope is constructed
        // and before any TTS-using surface (Reader / ListenScreen) is opened, so errors
        // raised on first launch (e.g. no engine bound) are not missed.
        ttsErrorPresenter.start(appScope)
    }

    companion object {
        lateinit var instance: MoRealmApp
            private set
    }
}
