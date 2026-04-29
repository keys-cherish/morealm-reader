package com.morealm.app.presentation.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class LoginField(
    val name: String,
    val type: String = "text", // text, password, number
    val hint: String = "",
)

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data class ShowDialog(val source: BookSource, val fields: List<LoginField>) : LoginUiState()
    data class Loading(val message: String) : LoginUiState()
    data class Success(val message: String) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

@HiltViewModel
class SourceLoginViewModel @Inject constructor(
    private val sourceRepo: com.morealm.app.domain.repository.SourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun showLoginDialog(source: BookSource) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fields = parseLoginUi(source.loginUi)
                if (fields.isEmpty()) {
                    _uiState.value = LoginUiState.Error("书源未配置登录界面")
                    return@launch
                }
                _uiState.value = LoginUiState.ShowDialog(source, fields)
            } catch (e: Exception) {
                AppLog.error("SourceLogin", "解析登录UI失败", e)
                _uiState.value = LoginUiState.Error("登录配置解析失败: ${e.message}")
            }
        }
    }

    fun login(source: BookSource, fieldValues: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = LoginUiState.Loading("登录中...")

                // Save login info
                val infoJson = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    fieldValues
                )
                source.putLoginInfo(infoJson)

                // Execute login script
                source.login()

                withContext(Dispatchers.Main) {
                    _uiState.value = LoginUiState.Success("登录成功")
                }
            } catch (e: Exception) {
                AppLog.error("SourceLogin", "登录失败", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = LoginUiState.Error("登录失败: ${e.message}")
                }
            }
        }
    }

    fun logout(source: BookSource) {
        viewModelScope.launch(Dispatchers.IO) {
            source.removeLoginInfo()
            source.removeLoginHeader()
            _uiState.value = LoginUiState.Success("已退出登录")
        }
    }

    fun dismissDialog() {
        _uiState.value = LoginUiState.Idle
    }

    private fun parseLoginUi(loginUi: String?): List<LoginField> {
        if (loginUi.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<LoginField>>(loginUi)
        } catch (e: Exception) {
            AppLog.warn("SourceLogin", "loginUi 格式不规范，尝试简化解析")
            // Fallback: simple format [{"name":"username"},{"name":"password","type":"password"}]
            emptyList()
        }
    }

    fun checkLoginStatus(source: BookSource): Boolean {
        val loginCheckJs = source.loginCheckJs
        if (loginCheckJs.isNullOrBlank()) {
            // No check script — assume logged in if loginInfo exists
            return source.getLoginInfo() != null
        }
        return try {
            val result = source.evalJS(loginCheckJs)
            result == true || result == "true"
        } catch (e: Exception) {
            AppLog.warn("SourceLogin", "登录检查失败: ${e.message}")
            false
        }
    }
}
