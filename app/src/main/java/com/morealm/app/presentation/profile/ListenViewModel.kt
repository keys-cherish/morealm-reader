package com.morealm.app.presentation.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.HttpTtsDao
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.tts.EdgeTtsEngine
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsPlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListenViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val httpTtsDao: HttpTtsDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Live TTS playback state from ReaderTtsController via TtsEventBus */
    val playbackState: StateFlow<TtsPlaybackState> = TtsEventBus.playbackState

    private val systemTtsEngine = SystemTtsEngine(context)

    /**
     * Edge 引擎实例，仅用于在「听书」页拉取远程音色列表 + 失效缓存。
     * 不参与实际朗读 —— 朗读由 [com.morealm.app.service.TtsEngineHost] 自己持有的引擎执行。
     * 两个实例共享同一个 `cacheDir/edge_tts/` 目录，所以 [EdgeTtsEngine.invalidateRemoteVoicesCache]
     * 在这边删了 voices.json，host 那边下次重拉也会走网络。
     */
    private val edgeTtsEngine by lazy { EdgeTtsEngine(context) }

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    /** 远程音色加载状态，UI 用来显示 spinner 和禁用刷新按钮。 */
    private val _voicesRefreshing = MutableStateFlow(false)
    val voicesRefreshing: StateFlow<Boolean> = _voicesRefreshing.asStateFlow()

    val selectedEngine: StateFlow<String> = prefs.ttsEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    /**
     * 已启用的 HttpTts 自定义朗读源列表。引擎选择 chip 行需要把这些 chip 接在
     * "system / edge" 后面。源被禁用 (enabled=false) 的不显示——避免误选到一个
     * 用户特意关掉的源。
     */
    val httpTtsList: StateFlow<List<HttpTts>> = httpTtsDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前用户绑定的系统 TTS 引擎包名。空字符串 = 跟随系统默认。
     * 仅在 [selectedEngine] = "system" 时 UI 才显示对应 picker；切到 edge / 其他
     * 引擎时这个值仍保留，便于切回 system 时恢复用户偏好。
     */
    val selectedSystemEnginePackage: StateFlow<String> = prefs.ttsSystemEnginePackage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * 系统已安装的所有 Android TTS 引擎清单（懒加载，UI 打开 picker 时调
     * [refreshSystemEngineList] 主动拉一次；不放进 init 因为 systemTtsEngine
     * 还没 init 完时清单可能为空）。
     */
    private val _systemEngines = MutableStateFlow<List<SystemTtsEngine.EngineInfo>>(emptyList())
    val systemEngines: StateFlow<List<SystemTtsEngine.EngineInfo>> = _systemEngines.asStateFlow()

    val selectedSpeed: StateFlow<Float> = prefs.ttsSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val selectedVoice: StateFlow<String> = combine(
        selectedEngine,
        prefs.ttsSystemVoice,
        prefs.ttsEdgeVoice,
        prefs.ttsVoice,
    ) { engine, systemVoice, edgeVoice, legacyVoice ->
        when (engine) {
            "edge" -> edgeVoice.ifBlank { legacyVoice }
            "system" -> systemVoice.ifBlank { legacyVoice }
            else -> ""
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Bug 4 收敛：TtsErrorPresenter 在 `canOpenSettings=true` 时不再弹 app Toast，
     * 改由 Listen 页持久化 banner 展示——红字提示 + 「前往系统 TTS 设置」按钮，比
     * 1.5s 一闪而过的 Toast 体验好。null = 没有当前错误，banner 不渲染。
     *
     * 写入触发：[TtsEventBus.Event.Error] 且 `canOpenSettings=true`。
     * 清除路径：用户点 [dismissTtsErrorBanner]，或下一次 LoadAndPlay 成功时（host 端不主动清，
     *   暂留给用户手动关——避免 banner 闪现没看清）。
     */
    private val _ttsErrorBanner = MutableStateFlow<String?>(null)
    val ttsErrorBanner: StateFlow<String?> = _ttsErrorBanner.asStateFlow()

    init {
        systemTtsEngine.initialize()
        viewModelScope.launch {
            selectedEngine.collectLatest { engine ->
                refreshVoices(engine)
            }
        }
        // 订阅 EventBus 上的 TTS 错误事件，落到 banner state。只看 canOpenSettings=true
        // 的事件——这些是「需要用户去系统设置修」的硬错误（缺引擎、缺语音数据等）；
        // 临时性错误（单段 speak 失败）走 TtsErrorPresenter 的 Toast 路径，不挡用户视野。
        viewModelScope.launch {
            TtsEventBus.events.collect { event ->
                if (event is TtsEventBus.Event.Error && event.canOpenSettings) {
                    _ttsErrorBanner.value = event.message
                }
            }
        }
    }

    /** 用户点 banner 上的关闭按钮 / 完成跳转设置后调；清空 banner。 */
    fun dismissTtsErrorBanner() {
        _ttsErrorBanner.value = null
    }

    /**
     * 拉一次 `TextToSpeech.getEngines()` 列表，UI 打开"系统 TTS 引擎包"
     * picker 时调一次，结果写到 [systemEngines]。
     */
    fun refreshSystemEngineList() {
        viewModelScope.launch {
            _systemEngines.value = systemTtsEngine.getInstalledEngines()
        }
    }

    /**
     * 设置系统 TTS 引擎包名。传空字符串 = 恢复 "跟系统默认"。
     *
     * 之前依赖"重启阅读器后生效"的 lazy init 路径，体验差：用户切了 multitts
     * 还得退出重进。改用 [TtsEventBus.Command.RebindSystemEngine] 让 host 即时
     * 销毁旧引擎 + 用新包名重 init，正在播放的话还会自动续播。
     */
    fun selectSystemEnginePackage(pkg: String) {
        viewModelScope.launch {
            prefs.setTtsSystemEnginePackage(pkg)
            com.morealm.app.core.log.AppLog.info(
                "TTS",
                "user picked system TTS package: ${pkg.ifBlank { "<system-default>" }}",
            )
            // 关键：发 RebindSystemEngine，host 收到会即时切换 systemTtsEngine 实例
            TtsEventBus.sendCommand(TtsEventBus.Command.RebindSystemEngine(pkg))
        }
    }

    fun selectEngine(engineId: String) {
        viewModelScope.launch { prefs.setTtsEngine(engineId) }
        // host 启动后只在 start() 里读了一次 prefs，之后只看 Command。
        // 不发 Command 的话听书 Tab 的引擎切换形同虚设——音频继续走旧引擎。
        TtsEventBus.sendCommand(TtsEventBus.Command.SetEngine(engineId))
    }

    fun selectSpeed(speed: Float) {
        viewModelScope.launch { prefs.setTtsSpeed(speed) }
        // 同上：缺 SetSpeed 时 host 的 speed 字段不会更新，下一段仍按旧速朗读。
        TtsEventBus.sendCommand(TtsEventBus.Command.SetSpeed(speed))
    }

    fun selectVoice(voiceId: String) {
        val engineId = voiceEngineId(selectedEngine.value) ?: return
        val resolvedVoice = validVoiceOrDefault(voiceId, _voices.value)
        viewModelScope.launch {
            if (engineId == "edge") {
                prefs.setTtsEdgeVoice(resolvedVoice)
            } else {
                prefs.setTtsSystemVoice(resolvedVoice)
            }
            prefs.setTtsVoice(resolvedVoice)
        }
        // 同上：只写 prefs 不发 Command 时，host 不会调用引擎的 setVoice，
        // 即使下拉选了不同语音，朗读仍是旧的。
        TtsEventBus.sendCommand(TtsEventBus.Command.SetVoice(resolvedVoice))
    }

    fun sendPlayPause() {
        // 直接 sendCommand 而不是 sendEvent(PlayPause)：
        // Event 是 Service→ViewModel 方向，依赖 ReaderViewModel 在线监听并转 Command。
        // 听书 Tab 单独使用（reader 已退出）时 Event 无人响应，按钮失效。
        if (TtsEventBus.playbackState.value.isPlaying) {
            TtsEventBus.sendCommand(TtsEventBus.Command.Pause)
        } else {
            TtsEventBus.sendCommand(TtsEventBus.Command.Play)
        }
    }

    fun sendPrevChapter() {
        // 新 Command 包装：host 收到会转发 Event.PrevChapter 给 reader VM 处理。
        TtsEventBus.sendCommand(TtsEventBus.Command.PrevChapter)
    }

    fun sendNextChapter() {
        TtsEventBus.sendCommand(TtsEventBus.Command.NextChapter)
    }

    fun sendPrevParagraph() {
        TtsEventBus.sendCommand(TtsEventBus.Command.PrevParagraph)
    }

    fun sendNextParagraph() {
        TtsEventBus.sendCommand(TtsEventBus.Command.NextParagraph)
    }

    private fun voiceEngineId(engine: String): String? =
        when {
            engine == "edge" || engine == "system" -> engine
            // HttpTts 引擎也参与 voice 维护流程（虽然就一个虚拟"声音"），让
            // refreshVoices 给 _voices 写入单元素列表，UI 选择器能正常显示
            engine.startsWith("http_") -> engine
            else -> null
        }

    private fun validVoiceOrDefault(voiceId: String, voices: List<TtsVoice>): String {
        if (voiceId.isBlank()) return ""
        return voiceId.takeIf { selected -> voices.any { it.id == selected } } ?: ""
    }

    private suspend fun refreshVoices(engine: String) {
        val engineId = voiceEngineId(engine) ?: run {
            _voices.value = emptyList()
            return
        }
        _voicesRefreshing.value = true
        try {
            _voices.value = when {
                engineId == "edge" -> {
                    // 远程拉 600+ 条 zh/en/ja/... 音色（带 24h voices.json 缓存 + 失败回退到硬编码）
                    runCatching { edgeTtsEngine.fetchRemoteVoices() }
                        .getOrNull()?.takeIf { it.isNotEmpty() }
                        ?: EdgeTtsEngine.VOICES
                }
                engineId.startsWith("http_") -> {
                    // HttpTts 没有多音色概念。给 UI 一个单元素，name 用源名
                    val id = engineId.removePrefix("http_").toLongOrNull()
                    val cfg = id?.let { httpTtsDao.getById(it) }
                    if (cfg != null) {
                        listOf(TtsVoice(id = cfg.name, name = cfg.name, language = "custom", engine = "http"))
                    } else emptyList()
                }
                else -> {
                    // Mirror the host-side fix: bound the wait so a missing/broken system
                    // TTS engine doesn't leave the picker spinning. Failed init surfaces
                    // as a TtsEventBus.Error so the settings UI can react via snackbar.
                    when (val initRes = systemTtsEngine.awaitReadyResult()) {
                        is SystemTtsEngine.InitResult.Failed -> {
                            TtsEventBus.sendEvent(
                                TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                            )
                            emptyList()
                        }
                        SystemTtsEngine.InitResult.Success -> systemTtsEngine.getChineseVoices()
                    }
                }
            }
            val savedVoice = if (engineId == "edge") {
                prefs.ttsEdgeVoice.first()
            } else {
                prefs.ttsSystemVoice.first()
            }.ifBlank { prefs.ttsVoice.first() }
            if (savedVoice.isNotBlank() && validVoiceOrDefault(savedVoice, _voices.value).isBlank()) {
                selectVoice("")
            }
        } finally {
            _voicesRefreshing.value = false
        }
    }

    /**
     * 用户主动「刷新音色列表」入口（"语音"区刷新按钮点击）。
     * - Edge：先 [EdgeTtsEngine.invalidateRemoteVoicesCache] 删 voices.json，然后重新拉远程
     * - System：重新初始化 + 重读 [TextToSpeech.getVoices][android.speech.tts.TextToSpeech.getVoices]
     *   （比如用户刚装/卸载了 multiTTS / 讯飞 / 三星 TTS 后过来刷一下）
     */
    fun refreshVoiceListNow() {
        viewModelScope.launch {
            val engine = selectedEngine.value
            if (engine == "edge") {
                edgeTtsEngine.invalidateRemoteVoicesCache()
            }
            refreshVoices(engine)
        }
    }

    override fun onCleared() {
        super.onCleared()
        systemTtsEngine.shutdown()
    }
}
