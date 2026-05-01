package com.morealm.app

import android.app.Application
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.CookieDao
import com.morealm.app.domain.db.TxtTocRuleDao
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.sync.WebDavBookProgressSync
import com.morealm.app.domain.webbook.CacheBook
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

    /**
     * App-scoped supervisor for fire-and-forget background work that
     * shouldn't be cancelled when an Activity goes away (e.g. WebDav
     * progress sync on cold start, future auto-backup scheduler).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        AppLog.info("App", "MoRealm started")
    }

    companion object {
        lateinit var instance: MoRealmApp
            private set
    }
}
