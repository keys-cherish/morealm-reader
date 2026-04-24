package com.morealm.app

import android.app.Application
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.CookieDao
import com.morealm.app.domain.db.TxtTocRuleDao
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.webbook.CacheBook
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MoRealmApp : Application() {

    @Inject lateinit var cacheDao: CacheDao
    @Inject lateinit var cookieDao: CookieDao
    @Inject lateinit var txtTocRuleDao: TxtTocRuleDao

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        CacheManager.init(cacheDao)
        CookieStore.init(cookieDao)
        CacheBook.init(cacheDao)
        LocalBookParser.txtTocRuleDao = txtTocRuleDao
        AppLog.info("App", "MoRealm started")
    }

    companion object {
        lateinit var instance: MoRealmApp
            private set
    }
}
