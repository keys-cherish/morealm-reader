package com.morealm.app.ui.navigation

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.theme.MoRealmTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val continueReadingRequest = mutableIntStateOf(0)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在任何 Hilt 注入触发 AppDatabase 之前，先检测是否需要进入恢复流程。
        // - 如果 SQLite user_version > 当前 schema → Room 一旦打开就 throw，必须先跳走
        // - 如果存在 recovery_pending marker → 上轮恢复未完成，直接进 RecoveryActivity 接力
        // 注意：必须在 super.onCreate / hiltViewModel() / setContent 之前 ——
        // 后者会触发 ViewModel 创建链 → DAO 注入 → DB 打开。
        com.morealm.app.domain.db.recovery.RecoveryGuard.shouldEnterRecovery(this)?.let { reason ->
            startActivity(
                com.morealm.app.ui.recovery.RecoveryActivity.newIntent(this, reason)
            )
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        updateContinueReadingRequest(intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let {
                window.attributes = window.attributes.also { a -> a.preferredDisplayModeId = it.modeId }
            }
        }

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val activeTheme by themeViewModel.activeTheme.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)

            MoRealmTheme(theme = activeTheme) {
                MoRealmNavHost(
                    windowSizeClass = windowSizeClass,
                    themeViewModel = themeViewModel,
                    continueReadingRequest = continueReadingRequest.intValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateContinueReadingRequest(intent)
    }

    private fun updateContinueReadingRequest(intent: Intent?) {
        if (intent?.action == "com.morealm.app.CONTINUE_READING") {
            continueReadingRequest.intValue += 1
        }
    }
}
