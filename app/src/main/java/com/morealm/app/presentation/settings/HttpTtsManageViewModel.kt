package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.HttpTtsDao
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.service.TtsEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * HttpTts 自定义朗读源管理 ViewModel。
 *
 * 操作集：列表订阅、新建/更新（upsert）、删除、启用切换、JSON 导入、试听。
 * 试听走 [TtsEventBus.Command.SpeakOneShot]，由 host 用当前选中的引擎发音；
 * 因此试听之前需要先 [setEngineForPreview] 把临时切到该源（保存原引擎，
 * 退出试听时再切回）。
 */
@HiltViewModel
class HttpTtsManageViewModel @Inject constructor(
    private val dao: HttpTtsDao,
    private val prefs: AppPreferences,
) : ViewModel() {

    val sources: StateFlow<List<HttpTts>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /** 试听前保存的引擎 id，结束后恢复。空串 = 没在试听。 */
    private var enginePriorToPreview: String = ""

    fun upsert(tts: HttpTts) {
        viewModelScope.launch {
            dao.upsert(tts.copy(lastUpdateTime = System.currentTimeMillis()))
            _toast.tryEmit("已保存：${tts.name.ifBlank { "未命名" }}")
        }
    }

    fun delete(tts: HttpTts) {
        viewModelScope.launch {
            dao.delete(tts)
            _toast.tryEmit("已删除：${tts.name.ifBlank { "未命名" }}")
            // 如果用户当前用的就是这个源，prefs 还指着它会让朗读 fallback；
            // host 的 setEngine 已经做了校验+回退，这里只发个事件让 UI 重置选择。
            val current = runCatching { prefs.ttsEngine.first() }.getOrDefault("")
            if (current == "http_${tts.id}") {
                prefs.setTtsEngine("system")
                TtsEventBus.sendCommand(TtsEventBus.Command.SetEngine("system"))
            }
        }
    }

    fun toggleEnabled(tts: HttpTts) {
        upsert(tts.copy(enabled = !tts.enabled))
    }

    /**
     * 试听：先临时把朗读引擎切到这个源，再发一段固定文本去合成。
     * 试听结束后由 [endPreview] 恢复原引擎。
     */
    fun preview(tts: HttpTts, sampleText: String = "这是一段朗读测试文本。") {
        viewModelScope.launch {
            enginePriorToPreview = runCatching { prefs.ttsEngine.first() }.getOrDefault("system")
            val targetEngine = "http_${tts.id}"
            prefs.setTtsEngine(targetEngine)
            TtsEventBus.sendCommand(TtsEventBus.Command.SetEngine(targetEngine))
            TtsEventBus.sendCommand(TtsEventBus.Command.SpeakOneShot(sampleText))
        }
    }

    fun endPreview() {
        if (enginePriorToPreview.isBlank()) return
        viewModelScope.launch {
            val restore = enginePriorToPreview
            enginePriorToPreview = ""
            prefs.setTtsEngine(restore)
            TtsEventBus.sendCommand(TtsEventBus.Command.SetEngine(restore))
        }
    }

    /**
     * 解析用户粘贴的 JSON 文本。兼容三种 Legado 常见格式：
     *   1. 单对象：`{"name":"...","url":"..."}`
     *   2. 多对象数组：`[{...},{...}]`
     *   3. 包了一层 `{"body":[...]}`（部分分享平台导出格式）
     *
     * 失败时通过 [toast] 提示用户具体原因。成功返回导入条数。
     */
    fun importFromJson(raw: String) {
        viewModelScope.launch {
            val text = raw.trim()
            if (text.isEmpty()) {
                _toast.tryEmit("请粘贴 JSON 内容")
                return@launch
            }
            val parsed = runCatching { jsonRelaxed.parseToJsonElement(text) }
                .getOrElse {
                    _toast.tryEmit("JSON 解析失败：${it.message?.take(80)}")
                    AppLog.warn("HttpTTSImport", "parse failed", it)
                    return@launch
                }
            val items: List<JsonObject> = when {
                parsed is JsonArray -> parsed.mapNotNull { it as? JsonObject }
                parsed is JsonObject && parsed["body"] is JsonArray ->
                    parsed["body"]!!.jsonArray.mapNotNull { it as? JsonObject }
                parsed is JsonObject -> listOf(parsed)
                else -> emptyList()
            }
            if (items.isEmpty()) {
                _toast.tryEmit("没有可导入的对象")
                return@launch
            }
            var ok = 0
            for (obj in items) {
                val tts = obj.toHttpTtsOrNull() ?: continue
                dao.upsert(tts)
                ok++
            }
            _toast.tryEmit(if (ok > 0) "已导入 $ok 条" else "导入失败：缺少 url 字段")
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }

    private fun JsonObject.toHttpTtsOrNull(): HttpTts? {
        val url = str("url") ?: return null
        val id = (this["id"]?.jsonPrimitive?.longOrNull)
            ?: System.currentTimeMillis()
        return HttpTts(
            id = id,
            name = str("name") ?: "未命名朗读源",
            url = url,
            contentType = str("contentType"),
            header = str("header"),
            enabled = (this["enabled"]?.jsonPrimitive?.contentOrNull)?.toBooleanStrictOrNull() ?: true,
            lastUpdateTime = System.currentTimeMillis(),
            loginUrl = str("loginUrl"),
            loginUi = str("loginUi"),
            loginCheckJs = str("loginCheckJs"),
            concurrentRate = str("concurrentRate"),
        )
    }

    companion object {
        private val jsonRelaxed = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
