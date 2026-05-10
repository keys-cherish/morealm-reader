package com.morealm.app.ui.source

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.source.LoginUiState
import com.morealm.app.presentation.source.SourceLoginViewModel

/**
 * 书源登录流程通用 UI overlay。
 *
 * 为什么单独抽出：BookSourceManageScreen 和 ReaderScreen 都要承接 login 状态机
 *（前者是用户主动点"登录"chip，后者是章节加载失败后 Snackbar "去登录"），
 * 两处的 dialog/WebView 挂载代码 1:1 完全一样，内联两份会迅速产生分叉。
 *
 * [onNavigateToLog] 可选：失败 Dialog 上的"查看日志"按钮。在阅读器里传 null
 * 让它消失（阅读器内不方便做应用级导航），书源管理页会传实际 navigator。
 *
 * ## 行为注意
 * - 五个 LoginUiState 全覆盖：ShowDialog / ShowWebView / Loading / Success / Error / Idle
 * - Success 通过 Toast 轻提示 + 自动 dismiss；Error 通过 AlertDialog 强反馈
 * - Loading Dialog 不给任何按钮：登录异常由脚本层 catch，UI 不需人为介入；
 *   若脚本永挂，showBrowser 已有 5 分钟超时兜底
 */
@Composable
fun SourceLoginOverlay(
    loginViewModel: SourceLoginViewModel,
    onNavigateToLog: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val loginUiState by loginViewModel.uiState.collectAsStateWithLifecycle()
    when (val state = loginUiState) {
        is LoginUiState.ShowDialog -> {
            SourceLoginDialog(
                source = state.source,
                fields = state.rows,
                onDismiss = { loginViewModel.dismissDialog() },
                onLogin = { fieldValues -> loginViewModel.login(state.source, fieldValues) },
                onActionJs = { actionJs, currentValues ->
                    loginViewModel.runActionJs(state.source, actionJs, currentValues)
                },
                onNavigateToLog = onNavigateToLog,
                uiPatchFlow = loginViewModel.uiPatch,
                uiRebuildFlow = loginViewModel.uiRebuild,
            )
        }
        is LoginUiState.ShowWebView -> {
            WebViewLoginScreen(
                source = state.source,
                loginUrl = state.url,
                headerMap = state.headerMap,
                onDismiss = { loginViewModel.dismissDialog() },
                onLoginComplete = {
                    val name = state.source.bookSourceName
                    loginViewModel.dismissDialog()
                    Toast.makeText(context, "已登录到《$name》", Toast.LENGTH_SHORT).show()
                },
            )
        }
        is LoginUiState.Loading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("登录中") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(state.message)
                    }
                },
                confirmButton = {},
            )
        }
        is LoginUiState.Success -> {
            LaunchedEffect(state) {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                loginViewModel.dismissDialog()
            }
        }
        is LoginUiState.Error -> {
            AlertDialog(
                onDismissRequest = { loginViewModel.dismissDialog() },
                title = { Text("登录失败") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { loginViewModel.dismissDialog() }) { Text("知道了") }
                },
                dismissButton = if (onNavigateToLog != null) {
                    {
                        TextButton(onClick = {
                            loginViewModel.dismissDialog()
                            onNavigateToLog()
                        }) { Text("查看日志") }
                    }
                } else null,
            )
        }
        LoginUiState.Idle -> {}
    }
}
