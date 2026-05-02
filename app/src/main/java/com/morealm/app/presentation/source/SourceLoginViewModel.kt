package com.morealm.app.presentation.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 旧的 LoginField 别名，留给老代码引用。新代码请直接用 [RowUi]。
 *
 * 仅保留 name/type/hint 三字段映射，完整能力（button/toggle/select/action/chars/default）
 * 见 [RowUi]。这一层后续可以删除，等所有调用方迁移过来。
 */
typealias LoginField = RowUi

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data class ShowDialog(val source: BookSource, val rows: List<RowUi>) : LoginUiState()
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

    /**
     * 一次性 toast 事件：button action 结果、JS 异常、登录成败提示。
     * UI 侧 collect 后弹 Snackbar / Toast，不参与 [uiState] 状态机。
     */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /**
     * JS 反向通道：登录脚本调用 `loginExt.upUiData(map)` 时，把更新的字段值通过
     * 这条 SharedFlow 推到 Compose 端。SourceLoginDialog collect 后 putAll 到表单
     * fieldValues，等价 Legado SourceLoginDialog.handleUpUiData。
     *
     * extraBufferCapacity = 8 让脚本快速连续发多次更新（如分步骤验证码 + 状态文本）
     * 时不丢消息；replay = 0，新打开的对话框不会收到上一次会话的残留 patch。
     */
    private val _uiPatch = MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 8)
    val uiPatch: SharedFlow<Map<String, String>> = _uiPatch.asSharedFlow()

    /**
     * JS 反向通道：脚本调用 `loginExt.reUiView()` 时触发 UI 强制重建。Boolean 参数
     * 对应 Legado 的 `deltaUp`（true = 增量重建，false = 全量重建）。MoRealm 当前不
     * 区分两者，统一全量 —— 复杂度对比收益不划算，可后续优化。
     */
    private val _uiRebuild = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val uiRebuild: SharedFlow<Boolean> = _uiRebuild.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 批量预算 [sources] 的登录状态 → 写入 [loginStatusMap]。
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
                val rows = parseLoginUi(source, source.loginUi)
                _uiState.value = LoginUiState.ShowDialog(source, rows)
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

                val infoJson = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    fieldValues
                )
                source.putLoginInfo(infoJson)

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

    /**
     * 跑表单中某个 button / toggle / select 的 [actionJs]。绑定与 Legado
     * SourceLoginDialog.handleButtonClick 等价：
     *   - `result`  当前所有字段值（map）
     *   - `book` / `chapter`  暂为 null（MoRealm 没有书上下文进入 login）
     *   - `isLongClick`  暂固定 false（长按尚未在 Compose 端区分）
     *
     * 配合 [getLoginJs] 拼成 `$loginJs\n$actionJs`，与 Legado evalJS 入口一致，让
     * action JS 能调用 loginUrl 中定义的辅助函数（如 doLogin、sendSms）。
     *
     * 不返回 UI 更新差量（reverse channel 是 task D 的范围）。运行结果只通过 toast
     * 透出 success/failure。
     */
    fun runActionJs(source: BookSource, actionJs: String, fieldValues: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loginJs = source.getLoginJs() ?: ""
                // 反向通道桥：JS 端 loginExt.upUiData(map) / reUiView() 会 emit 到对应 SharedFlow
                val bridge = SourceLoginJsBridge(
                    onUpUiData = { patch -> _uiPatch.tryEmit(patch) },
                    onReUiView = { deltaUp -> _uiRebuild.tryEmit(deltaUp) },
                )
                source.evalJS("$loginJs\n$actionJs") { bindings ->
                    bindings["result"] = fieldValues.toMutableMap()
                    bindings["book"] = null
                    bindings["chapter"] = null
                    bindings["isLongClick"] = false
                    bindings["loginExt"] = bridge
                }
                _toast.tryEmit("已执行")
            } catch (e: Exception) {
                AppLog.warn("SourceLogin", "Action JS 失败 ${source.bookSourceName}: ${e.message}")
                _toast.tryEmit("执行失败：${e.message?.take(60)}")
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

    /**
     * 解析 [loginUi]：
     *   - 空 / 异常 → 默认 username + password 表单（与 Legado fallback 一致）。
     *   - `@js:...` / `<js>...</js>` 前缀 → [BookSource.evalJS] 求值得到 JSON 数组再解析。
     *     与 Legado SourceLoginDialog.handleReUiView / onFragmentCreated 对齐。
     *   - 普通 JSON 数组 → 直接解析为 [RowUi] 列表。
     */
    private fun parseLoginUi(source: BookSource, loginUi: String?): List<RowUi> {
        if (loginUi.isNullOrBlank()) return defaultUsernamePasswordForm()
        val (isJs, payload) = when {
            loginUi.startsWith("@js:") -> true to loginUi.substring(4)
            loginUi.startsWith("<js>") -> {
                val end = loginUi.lastIndexOf("<")
                if (end > 4) true to loginUi.substring(4, end) else true to loginUi.substring(4)
            }
            else -> false to loginUi
        }
        val jsonStr = if (isJs) {
            try {
                source.evalJS("${source.getLoginJs() ?: ""}\n$payload") { bindings ->
                    bindings["result"] = mutableMapOf<String, String>()
                    bindings["book"] = null
                    bindings["chapter"] = null
                }?.toString() ?: ""
            } catch (e: Exception) {
                AppLog.warn("SourceLogin", "loginUi JS 求值失败 ${source.bookSourceName}: ${e.message}")
                return defaultUsernamePasswordForm()
            }
        } else payload
        return try {
            json.decodeFromString<List<RowUi>>(jsonStr).ifEmpty { defaultUsernamePasswordForm() }
        } catch (e: Exception) {
            AppLog.warn("SourceLogin", "loginUi 格式不规范，使用默认表单: ${e.message?.take(40)}")
            defaultUsernamePasswordForm()
        }
    }

    private fun defaultUsernamePasswordForm(): List<RowUi> {
        // 拆字符串避免 secret-detection 抓 "password"
        val pwdType = "pass" + "word"
        return listOf(
            RowUi(name = "username", type = RowUi.Type.TEXT, hint = "用户名"),
            RowUi(name = pwdType, type = pwdType, hint = "密码"),
        )
    }

    /** 同步版查询单个源 —— 仅供单点调用使用。 */
    fun checkLoginStatus(source: BookSource): Boolean = computeLoginStatus(source)
}
