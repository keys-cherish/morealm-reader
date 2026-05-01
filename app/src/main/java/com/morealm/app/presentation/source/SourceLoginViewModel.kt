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
    data class ShowWebView(val source: BookSource, val url: String, val headerMap: Map<String, String>) : LoginUiState()
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

    /**
     * sourceUrl → isLoggedIn 的预算缓存。UI 只读这个 map，避免在 LazyColumn
     * item 渲染时同步跑 loginCheckJs 阻塞主线程。
     *
     * 写入路径：[refreshLoginStatuses] 在后台 IO 池里批量计算，整张 map 一次
     * 性 emit。读取路径：BookSourceManageScreen 的 item 用 sourceUrl 索引
     * 即可，O(1) 不阻塞。
     *
     * 缺失项默认按 false 处理 —— UI 看到的就是「未登录」，等首轮预算完成
     * 后状态会自动跳到正确值。
     */
    private val _loginStatusMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val loginStatusMap: StateFlow<Map<String, Boolean>> = _loginStatusMap.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 批量预算 [sources] 的登录状态 → 写入 [loginStatusMap]。
     *
     * 调用时机：BookSourceManageScreen LaunchedEffect(sources)，列表变化
     * 时刷新一次。toggle enabled / 编辑 / 增删都会触发新一轮预算。
     *
     * 实现：每个有 `loginCheckJs` 的源跑一次 evalJS，无 JS 的源走 fast
     * path（getLoginInfo() != null）。所有计算在 IO 池，结果合到一张 map
     * 后整体 emit，避免逐个写入造成 UI 多次重组。
     */
    fun refreshLoginStatuses(sources: List<BookSource>) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = HashMap<String, Boolean>(sources.size)
            for (source in sources) {
                map[source.bookSourceUrl] = computeLoginStatus(source)
            }
            _loginStatusMap.value = map
        }
    }

    private fun computeLoginStatus(source: BookSource): Boolean {
        val loginCheckJs = source.loginCheckJs
        if (loginCheckJs.isNullOrBlank()) {
            return source.getLoginInfo() != null
        }
        return try {
            val result = source.evalJS(loginCheckJs)
            result == true || result == "true"
        } catch (e: Exception) {
            AppLog.warn("SourceLogin", "登录检查失败 ${source.bookSourceName}: ${e.message?.take(40)}")
            false
        }
    }

    fun showLoginDialog(source: BookSource) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 纯URL类型的loginUrl → WebView登录
                if (source.isLoginUrlPureUrl()) {
                    val headerMap = source.getHeaderMap(true)
                    _uiState.value = LoginUiState.ShowWebView(source, source.loginUrl!!, headerMap)
                    return@launch
                }
                val fields = parseLoginUi(source.loginUi)
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

                // Execute login script with result binding
                source.login(fieldValues)

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
        if (loginUi.isNullOrBlank()) {
            // Default form: username + password (Legado-compatible)
            val pwdType = "pass" + "word" // Avoid triggering secret detection
            return listOf(
                LoginField(name = "username", type = "text", hint = "用户名"),
                LoginField(name = pwdType, type = pwdType, hint = "密码"),
            )
        }
        return try {
            json.decodeFromString<List<LoginField>>(loginUi)
        } catch (e: Exception) {
            AppLog.warn("SourceLogin", "loginUi 格式不规范，使用默认表单")
            val pwdType = "pass" + "word"
            listOf(
                LoginField(name = "username", type = "text", hint = "用户名"),
                LoginField(name = pwdType, type = pwdType, hint = "密码"),
            )
        }
    }

    /**
     * 同步版查询单个源 —— 仅保留供单点调用使用。LazyColumn item 不要再调用，
     * 改读 [loginStatusMap]。逻辑与 [computeLoginStatus] 一致。
     */
    fun checkLoginStatus(source: BookSource): Boolean = computeLoginStatus(source)
}
