package com.morealm.app

import android.app.Application
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MoRealmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.info("App", "MoRealm started")
    }
}
