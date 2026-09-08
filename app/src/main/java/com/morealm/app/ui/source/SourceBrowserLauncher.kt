package com.morealm.app.ui.source

import android.content.Context
import com.morealm.app.domain.source.ShowBrowserSession
import kotlinx.coroutines.runBlocking

/** Platform adapter for synchronous source scripts that need an interactive browser. */
object SourceBrowserLauncher {
    fun await(
        context: Context,
        sourceKey: String,
        url: String?,
        html: String?,
        title: String,
        preloadJs: String? = null,
    ): String? = runBlocking {
        ShowBrowserSession.request { requestId ->
            ShowBrowserActivity.launch(context, url, html, preloadJs, sourceKey, title, requestId)
        }
    }
}
