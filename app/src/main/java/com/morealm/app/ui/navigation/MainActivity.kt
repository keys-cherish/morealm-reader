package com.morealm.app.ui.navigation

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.theme.MoRealmTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let {
                window.attributes = window.attributes.also { a -> a.preferredDisplayModeId = it.modeId }
            }
        }

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val activeTheme by themeViewModel.activeTheme.collectAsState()
            val windowSizeClass = calculateWindowSizeClass(this)

            MoRealmTheme(theme = activeTheme) {
                val continueReading = intent?.action == "com.morealm.app.CONTINUE_READING"
                MoRealmNavHost(
                    windowSizeClass = windowSizeClass,
                    themeViewModel = themeViewModel,
                    continueReading = continueReading,
                )
            }
        }
    }
}
