package com.morealm.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.text.AppPattern
import com.morealm.app.domain.db.HttpTtsDao
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.tts.EdgeTtsEngine
import com.morealm.app.domain.tts.HttpTtsEngine
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.domain.tts.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lives inside [TtsService] and owns the entire TTS reading state:
 * engine instances, the speak loop ([speakJob]), paragraph data, and the sleep timer.
 *
 * This replaces the previous `ReaderTtsController` ownership model where the loop ran on
 * `viewModelScope` and was cancelled the moment the user left the reader. By moving the loop
 * here, playback survives ViewModel destruction — only stopping on explicit user action,
 * sleep timer expiration, or unrecoverable audio focus loss.
 *
 * State is published via [TtsEventBus.playbackState]; commands enter via [handleCommand].
 * Chapter boundaries are signalled out via [TtsEventBus.Event.ChapterFinished].
 */
class TtsEngineHost(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val httpTtsDao: HttpTtsDao,
    /**
     * 用于 host 自动续章兜底——[Event.ChapterFinished] 后超时 [WAIT_NEXT_CHAPTER_MS]
     * 未收到 ViewModel 推送的新 LoadAndPlay 时，host 自己用这个 loader 直接拉下一章
     * 续播。这是 Phase D 的核心：在 ReaderViewModel 销毁后 TTS 不断声。
     */
    private val chapterContentLoader: com.morealm.app.domain.reader.ChapterContentLoader,
) {
    // ── Engines ──────────────────────────────────────────────────────────────
    /**
     * 系统 TTS 引擎实例。
     *
     * 不再是 `by lazy` —— 之前 lazy 一次性 init 后没法换包，用户切 multitts/讯飞
     * 必须重启阅读器才生效。改成可变字段后，[handleCommand] 处理
     * [TtsEventBus.Command.RebindSystemEngine] 时调用 [rebindSystemEngine] 重建。
     *
     * 第一次访问 [currentEngine] 时如果是 null 才走首启路径（读 prefs 拿包名）。
     */
    @Volatile private var systemTtsEngine: SystemTtsEngine? = null

    /**
     * 同步获取或懒初始化 systemTtsEngine。第一次调用读 prefs 拿包名；后续调用
     * 直接返回已绑定实例。`runBlocking` 风险可控——只在 host scope 内（service 进
     * 程主线程）调用，且 prefs.first() 是同进程 DataStore 读取，毫秒级。
     */
    private fun ensureSystemTtsEngine(): SystemTtsEngine {
        // 任何会用到 engine 的入口都视为"用户回来了"，取消空闲释放计时。
        // 放在最顶端而非"engine == null 才取消"——这样 60s 内 pause→resume→pause
        // 的来回会刷新计时器，避免边界情况下被错误释放。
        cancelIdleRelease()
        systemTtsEngine?.let { return it }
        val pkg = runCatching {
            kotlinx.coroutines.runBlocking { prefs.ttsSystemEnginePackage.first() }
        }.getOrDefault("")
        AppLog.info("TtsHost", "ensureSystemTtsEngine: first init pkg='${pkg.ifBlank { "<system-default>" }}'")
        return SystemTtsEngine(context).also {
            it.initialize(enginePackage = pkg)
            systemTtsEngine = it
        }
    }

    private val edgeTtsEngine by lazy { EdgeTtsEngine(context) }

    /**
     * HttpTts 引擎缓存：key = HttpTts.id（不带 "http_" 前缀）。
     * 每个用户配置的源懒构造一份；切换或删除时由 [evictHttpTtsEngine] 释放。
     *
     * 不在 lazy 初始化时一次性预创建——TtsEngineHost.start 阶段还没有 dao 数据，
     * 也没必要把所有源都拉起 ExoPlayer；按需即可。
     */
    private val httpTtsEngineCache = mutableMapOf<Long, HttpTtsEngine>()

    /**
     * 解析 `engineId = "http_<long>"` → HttpTts.id。非法格式返回 null（host 会
     * 报 Error 并 fallback system）。
     */
    private fun parseHttpEngineId(engineId: String): Long? =
        engineId.removePrefix("http_").toLongOrNull()

    /**
     * 同步获取或懒构造对应 id 的 HttpTtsEngine。DAO 查不到（被用户删了但 prefs
     * 还指着）时返回 null，调用方统一回退 system。
     */
    private fun ensureHttpTtsEngine(engineId: String): HttpTtsEngine? {
        val id = parseHttpEngineId(engineId) ?: return null
        httpTtsEngineCache[id]?.let { return it }
        val cfg = runCatching {
            kotlinx.coroutines.runBlocking { httpTtsDao.getById(id) }
        }.getOrNull() ?: return null
        AppLog.info("TtsHost", "ensureHttpTtsEngine: id=$id name='${cfg.name}'")
        val engine = HttpTtsEngine(context, cfg)
        httpTtsEngineCache[id] = engine
        return engine
    }

    /** 释放并从缓存移除一个 HttpTtsEngine（更换/删除源时调用）。 */
    private fun evictHttpTtsEngine(id: Long) {
        httpTtsEngineCache.remove(id)?.release()
    }

    // ── Mutable state (host is single source of truth) ───────────────────────
    private var paragraphs: List<String> = emptyList()
    private var paragraphPositions: List<Int> = emptyList()
    private var paragraphIndex: Int = 0

    /**
     * 当前段内字符偏移，用于句中续读（仿 Legado paragraphStartPos）。
     * - SystemTtsEngine 批量路径下：onRangeStart 实时回写，pause 不清零；
     *   resume 时第一段切片 substring(paragraphStartPos) 后再入队。
     * - 段切换（onDone 推进 paragraphIndex）时复位为 0。
     * - 用户手动切上/下段（prevParagraph/nextParagraph）也复位为 0。
     * - 这是 Legado 没做、MoRealm 新增的「真断点续读」能力。
     */
    private var paragraphStartPos: Int = 0

    private var bookTitle: String = ""
    private var chapterTitle: String = ""
    private var coverUrl: String? = null

    private var speed: Float = 1.0f
    private var engineId: String = "system"
    /**
     * 当前激活的 TTS 引擎 id 的只读视图，供 [TtsService] 决策资源申请
     * （例如仅在线引擎才抢 WiFiLock）。
     *
     * 这里不暴露可变引用——`engineId` 的写入仍只在 host 内部协程里串行化。
     */
    val currentEngineId: String get() = engineId
    private var voiceName: String = ""
    private var skipRegex: Regex? = null

    private var sleepRemainingMinutes: Int = 0

    /** True between "last paragraph finished" and "next chapter loaded" — keeps service alive briefly. */
    private var waitingForNextChapter: Boolean = false

    // ── 自动续章兜底（Phase D）──────────────────────────────────────────────
    //
    // [LoadAndPlay] 时 reader 透传过来的 bookId / chapterIndex / totalChapters。
    // host 缓存这些信息后，[Event.ChapterFinished] 等待 [WAIT_NEXT_CHAPTER_MS]
    // 未收到 reader 推送的下一章 LoadAndPlay 时，[tryAutoAdvanceChapter] 用
    // [chapterContentLoader] 直接加载下一章并自发 LoadAndPlay 续播。
    //
    // 没有 bookId（旧调用方 / oneShot / Listen 页等）时自动续章不启用，行为
    // 退回旧逻辑（超时即停止）——保持向后兼容。
    @Volatile private var currentBookId: String? = null
    @Volatile private var currentChapterIndex: Int = -1
    @Volatile private var totalChapters: Int = 0

    // ── Jobs ─────────────────────────────────────────────────────────────────
    private var speakJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var oneShotJob: Job? = null
    /** [setSpeed] 的去抖重启 job，每次速度变化重设；[release] 时取消。 */
    private var pendingSpeedRestartJob: Job? = null
    /** 心跳 watchdog job——isPlaying=true 期间每 [HEARTBEAT_INTERVAL_MS] 打一行摘要日志。 */
    private var heartbeatJob: Job? = null
    /**
     * 空闲释放 job。pause / stopPlayback 后开始计时 [IDLE_RELEASE_MS]，到点
     * 主动 shutdown 系统 TTS 引擎释放 binder、置 [systemTtsEngine] = null。
     *
     * 动机：Android TextToSpeech 持有跨进程 binder（绑定到具体 TTS 服务进程，
     * 如 Google TTS / MultiTTS），即便 host 暂停播放，binder + 引擎进程仍持续
     * 占用资源。低端机 / 低内存设备上 OS 会优先 LMK 这种"空闲但持引用"的服务，
     * 表现就是用户暂停 TTS 几分钟后回来发现通知栏服务被杀。
     *
     * 设计取舍：60s 是经验阈值——足够覆盖"用户读到一半接电话/暂时切走"的场景，
     * 又足够短让长时间空闲的引擎及时归还内存。重新播放时 [ensureSystemTtsEngine]
     * 会自动按需重建（按需 init 路径已经存在），用户感知到的代价是首段多 ~500ms
     * 的 TextToSpeech 初始化。
     *
     * 仅释放 system 引擎；edge / http 引擎的资源占用由各自实例管理（OkHttp 自带
     * 空闲连接回收、ExoPlayer pause 状态资源占用本就远低于 binder）。
     */
    private var idleReleaseJob: Job? = null
    /**
     * HttpTts 章节预下载 job。在 [runHttpChapterPlayback] 进入章末时启动，把下一章
     * 前 N 段提前拉到磁盘缓存，让翻章时段间停顿尽量短。每章只跑一次；切章/切引擎/
     * stop 时由 [release] 或 setEngine 显式 cancel。
     */
    private var preloadNextChapterJob: Job? = null

    // ── Heartbeat watchdog ──────────────────────────────────────────────────
    //
    // 解决"没声音 + 没日志"的诊断盲区。以前引擎回调走丢时 logcat 一片空白，
    // 和"程序没运行"看起来一样——无法区分是 host 没启动还是引擎吞了回调。
    //
    // 现在只要 isPlaying=true，每 5s 强制打一行心跳，包含完整的 host 内部状态
    // 快照。下次用户复现"没声音"后截 logcat，看到这行就能立刻判断：
    //   - 有心跳但 lastCbAge 持续增大 → 引擎吞回调（batch watchdog 会兜底）
    //   - 有心跳但 speakJobActive=false → speakJob 被意外 cancel，loop 退出
    //   - 没心跳 → host 根本没进入 playing 状态（问题在 loadAndPlay/service 启动）
    //   - 心跳里 enginePkg 是空 → systemTtsEngine 没绑到任何引擎

    /** 最后一次收到引擎回调（onStart/onDone/onRangeStart/chunk）的时间戳。 */
    @Volatile private var lastCallbackAtMs: Long = 0L
    /** 最后一次收到的回调类型，用于心跳日志。 */
    @Volatile private var lastCallbackType: String = "none"

    /** 由引擎回调路径调用：记录"最后一次引擎有动静的时刻"。 */
    private fun markCallback(type: String) {
        lastCallbackAtMs = System.currentTimeMillis()
        lastCallbackType = type
    }

    // ── 引擎 fatal 短路 ─────────────────────────────────────────────────────
    //
    // 历史 ANR 现场：[runBatchPlayback] 第 0 条 enqueue 就 ERROR（引擎已经异常），
    // host 报 Error 后 publishState(false) 退出；但 ChapterFinished 监听 / 用户
    // 重试 / 自动续章流程很快又触发新的 [loadAndPlay] / [resume]，再次进入
    // [runBatchPlayback] → 再次调 [applyVoiceToEngine] → `getVoices` 同步 binder
    // 在已经无响应的 TTS 进程上一路阻塞 8s+ → ANR Watchdog 报警一次。每 8s 一次，
    // 就是 err.txt 里 9 条堆栈完全相同的 ANR 复读。
    //
    // 防御策略：第一次确认引擎 fatal（i=0 enqueue ERROR / runBatchPlayback 抛
    // 出非 cancellation 异常）后通过 [engineFatalCooldown] 进入冷却；后续
    // [ENGINE_FATAL_COOLDOWN_MS] 内任何 [loadAndPlay] / [resume] / [speakOneShot]
    // 入口直接发 Error 事件并 return，不再进入播放循环，给引擎进程留出恢复
    // 空间或让用户切引擎。
    //
    // [reInit] 成功后或用户主动切引擎 / 切包名时通过 [EngineFatalCooldown.clear]
    // 主动解除冷却。
    private val engineFatalCooldown = EngineFatalCooldown(ENGINE_FATAL_COOLDOWN_MS)

    /** 标记引擎 fatal，进入 [ENGINE_FATAL_COOLDOWN_MS] 冷却期。 */
    private fun markEngineFatal(reason: String) {
        engineFatalCooldown.markFatal()
        AppLog.warn(
            "TtsHost",
            "markEngineFatal: $reason — entering ${ENGINE_FATAL_COOLDOWN_MS / 1000}s cooldown",
        )
    }

    /** 解除 fatal 标记（reInit 成功 / 切引擎 / 切包名后调用）。 */
    private fun clearEngineFatal() {
        if (engineFatalCooldown.isInCooldown()) {
            AppLog.info("TtsHost", "clearEngineFatal: cooldown cleared")
        }
        engineFatalCooldown.clear()
    }

    /**
     * 在 [loadAndPlay] / [resume] / [speakOneShot] 入口调用：若处于冷却期则
     * 发送 Error 事件并 return，避免反复触发同样的 ANR 路径。
     *
     * @return true 表示已被冷却拦截（调用方应 return），false 表示可继续。
     */
    private fun shortCircuitIfEngineFatal(): Boolean {
        if (!engineFatalCooldown.isInCooldown()) return false
        val remaining = engineFatalCooldown.remainingSeconds()
        AppLog.warn("TtsHost", "shortCircuitIfEngineFatal: still in cooldown, ${remaining}s left")
        TtsEventBus.sendEvent(
            TtsEventBus.Event.Error(
                "TTS 引擎刚刚出错（${remaining}s 内重试将再次失败），请稍后或更换引擎",
                canOpenSettings = true,
            )
        )
        publishState(playing = false)
        return true
    }

    private fun startHeartbeat() {
        // 已有活动心跳 job 时不重启；调用方应在 publishState(playing=true) 转换到
        // 播放态时调一次（参见 [publishState]）。
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val state = TtsEventBus.playbackState.value
                if (!state.isPlaying) {
                    // 不在播放就退出 job，节省 wakeup（之前是 continue 不退出，
                    // 即使 isPlaying=false 也每隔间隔醒来一次）。下次进入播放
                    // 态时由 [publishState] 重新启动。
                    AppLog.debug("TtsHB", "heartbeat exit: not playing")
                    break
                }
                val cbAge = if (lastCallbackAtMs > 0) {
                    "${(System.currentTimeMillis() - lastCallbackAtMs) / 1000}s"
                } else "never"
                val sysPkg = runCatching {
                    systemTtsEngine?.let { e ->
                        // TextToSpeech.defaultEngine 返回当前绑定的包名
                        val field = android.speech.tts.TextToSpeech::class.java
                            .getDeclaredField("mCurrentEngine")
                        field.isAccessible = true
                        field.get(
                            // systemTtsEngine 包裹了 TextToSpeech 实例；需要反射拿
                            e.javaClass.getDeclaredField("tts").apply { isAccessible = true }.get(e)
                        ) as? String
                    }
                }.getOrNull() ?: "?"
                AppLog.info(
                    "TtsHB",
                    "para=${state.paragraphIndex}/${state.totalParagraphs} " +
                        "startPos=$paragraphStartPos " +
                        "lastCb=$lastCallbackType+$cbAge " +
                        "speakJob=${speakJob?.isActive == true} " +
                        "engine=$engineId pkg=$sysPkg " +
                        "voice='$voiceName' speed=$speed " +
                        "sleepMin=${state.sleepMinutes}",
                )
            }
        }
    }

    // Serialize all (cancel + start) operations so rapid commands don't race.
    private val controlMutex = Mutex()

    /** Initialize host: load saved prefs, push initial playback state. */
    fun start() {
        // 心跳不再在 start() 时启动——它只在播放态下有意义。改由 [publishState]
        // 在 playing=true 转换处按需启动；播放结束时心跳自然退出循环。
        scope.launch {
            speed = prefs.ttsSpeed.first()
            skipRegex = prefs.ttsSkipPattern.first().let { p ->
                if (p.isNotBlank()) runCatching { Regex(p) }.getOrNull() else null
            }
            engineId = prefs.ttsEngine.first()
            voiceName = savedVoiceForEngine(engineId)
            applyVoiceToEngine()
            val voices = loadVoicesForEngine(engineId)
            TtsEventBus.updatePlayback {
                copy(
                    speed = speed,
                    engine = engineId,
                    voiceName = voiceName,
                    voices = voices,
                )
            }
        }
    }

    fun release() {
        speakJob?.cancel()
        sleepTimerJob?.cancel()
        oneShotJob?.cancel()
        preloadNextChapterJob?.cancel()
        pendingSpeedRestartJob?.cancel()
        heartbeatJob?.cancel()
        idleReleaseJob?.cancel()
        runCatching { systemTtsEngine?.stop(); systemTtsEngine?.shutdown() }
        runCatching { edgeTtsEngine.stop() }
        // HttpTts 引擎缓存：每个 entry 持有 ExoPlayer，必须显式 release 防泄漏
        httpTtsEngineCache.values.forEach { runCatching { it.release() } }
        httpTtsEngineCache.clear()
    }

    // ── Idle release helpers ─────────────────────────────────────────────────
    //
    // 这两个方法是任务"TTS 60s 空闲释放"的核心。pause/stopPlayback 调
    // [scheduleIdleRelease] 启动 60s 单发计时；任何重新使用 engine 的入口走
    // [ensureSystemTtsEngine] 时调 [cancelIdleRelease]。

    /**
     * 启动 60s 空闲计时。已有 pending job 时 cancel 重置（"再次暂停"应当
     * 重新开始计时，而不是延续上次）。
     */
    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            // 双重检查：60s 内用户重新播放时 cancelIdleRelease 应已取消该 job；
            // 但保险起见再校验一次播放状态——竞态下被恢复为 isPlaying=true 时
            // 不应该误杀引擎。
            if (TtsEventBus.playbackState.value.isPlaying) {
                AppLog.debug("TtsHost", "idleRelease: state.isPlaying flipped, abort")
                return@launch
            }
            if (engineId != "system") {
                // 当前用的是 edge / http_*：systemTtsEngine 此时即便存在也是
                // 上次切引擎前残留，可以放心释放。但更稳健的做法是 noop——
                // 等 release() 接手。这里直接 noop 与 idleRelease 的语义对齐：
                // "释放的是 system 引擎用户没在用 system 时不需触发"。
                return@launch
            }
            val engine = systemTtsEngine ?: return@launch
            AppLog.info(
                "TtsHost",
                "idleRelease: ${IDLE_RELEASE_MS / 1000}s idle, releasing systemTtsEngine to free binder",
            )
            // 清 batchCallback 防止 listener 持有已 shutdown 的 engine 引用。
            // 不主动 setBatchCallback(null) 也无害——重新 init 时旧 listener 不会再
            // 被触发——但显式清掉读起来更明确。
            runCatching { engine.setBatchCallback(null) }
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
            systemTtsEngine = null
        }
    }

    /** 取消空闲计时。任何重新使用 engine 的入口都会调它。 */
    private fun cancelIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
    }

    /** Entry point for all [TtsEventBus.Command]s; called by [TtsService.listenForCommands]. */
    fun handleCommand(cmd: TtsEventBus.Command) {
        when (cmd) {
            is TtsEventBus.Command.UpdateMeta -> {
                bookTitle = cmd.book
                chapterTitle = cmd.chapter
                cmd.coverUrl?.let { coverUrl = it }
                TtsEventBus.updatePlayback {
                    copy(
                        bookTitle = cmd.book.ifBlank { this.bookTitle },
                        chapterTitle = cmd.chapter.ifBlank { this.chapterTitle },
                        coverUrl = cmd.coverUrl ?: this.coverUrl,
                    )
                }
            }

            is TtsEventBus.Command.LoadAndPlay -> loadAndPlay(cmd)
            is TtsEventBus.Command.Play -> resume()
            is TtsEventBus.Command.Pause -> pause()
            is TtsEventBus.Command.PrevParagraph -> prevParagraph()
            is TtsEventBus.Command.NextParagraph -> nextParagraph()
            // host 不持有章节列表 → 只是把 Command 透传成对应 Event，
            // 让 ReaderViewModel 的 Event.PrevChapter/NextChapter 处理分支接管真正的章节切换。
            is TtsEventBus.Command.PrevChapter ->
                TtsEventBus.sendEvent(TtsEventBus.Event.PrevChapter)
            is TtsEventBus.Command.NextChapter ->
                TtsEventBus.sendEvent(TtsEventBus.Event.NextChapter)
            is TtsEventBus.Command.SetSpeed -> setSpeed(cmd.speed)
            is TtsEventBus.Command.SetEngine -> setEngine(cmd.engine)
            is TtsEventBus.Command.SetVoice -> setVoice(cmd.voiceName)
            is TtsEventBus.Command.SetSkipPattern -> setSkipPattern(cmd.pattern)
            is TtsEventBus.Command.SetSleepMinutes -> setSleepMinutes(cmd.minutes)
            is TtsEventBus.Command.SpeakOneShot -> speakOneShot(cmd.text)
            is TtsEventBus.Command.RebindSystemEngine -> rebindSystemEngine(cmd.pkg)
            is TtsEventBus.Command.StopService -> {
                // Service.onDestroy will call release(); just stop playback now.
                stopPlayback()
            }
        }
    }

    // ── Audio focus hooks (called by TtsService) ─────────────────────────────

    fun onAudioFocusLoss(resumeOnGain: Boolean) {
        if (TtsEventBus.playbackState.value.isPlaying) {
            pendingResumeOnFocusGain = resumeOnGain
            pause()
        } else {
            pendingResumeOnFocusGain = false
        }
    }

    fun onAudioFocusGain() {
        if (pendingResumeOnFocusGain) {
            pendingResumeOnFocusGain = false
            resume()
        }
    }

    private var pendingResumeOnFocusGain: Boolean = false

    // ── Core playback control ────────────────────────────────────────────────

    private fun loadAndPlay(cmd: TtsEventBus.Command.LoadAndPlay) {
        if (shortCircuitIfEngineFatal()) return
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                bookTitle = cmd.bookTitle.ifBlank { bookTitle }
                chapterTitle = cmd.chapterTitle
                coverUrl = cmd.coverUrl ?: coverUrl

                // Legado-style single-source-of-truth: when the ViewModel hands
                // us authoritative paragraph offsets (computed from
                // TextChapter.getParagraphs — the same data the renderer uses
                // for `upPageAloudSpan`), slice [content] at those offsets
                // instead of independently re-parsing. That guarantees
                // `paragraphs.size == paragraphPositions.size` so the
                // [paragraphIndex → chapterPosition] lookup never falls back to
                // 0, which previously caused the highlight to freeze on the
                // first paragraph whenever the two splitters disagreed.
                val pos = cmd.paragraphPositions
                val sliceMode: String
                if (pos != null && pos.isNotEmpty()) {
                    paragraphs = paragraphsFromPositions(cmd.content, pos)
                    paragraphPositions = pos
                    sliceMode = "fromPositions(${pos.size})"
                } else {
                    paragraphs = parseParagraphs(cmd.content)
                    paragraphPositions = buildSequentialPositions(paragraphs)
                    sliceMode = "parseParagraphs"
                }
                paragraphIndex = paragraphIndexForPosition(cmd.startChapterPosition)
                paragraphStartPos = 0 // 新章节加载，句中位置归零
                waitingForNextChapter = false
                // Phase D：缓存 bookId / chapterIndex 以便章末自动续章。null 时
                // 退回旧行为（不自动续章，依赖 reader 推章），与 oneShot / Listen
                // 页这类没有书上下文的调用方兼容。
                cmd.bookId?.let { currentBookId = it }
                cmd.chapterIndex?.let { currentChapterIndex = it }
                if (cmd.bookId != null && cmd.chapterIndex != null) {
                    // totalChapters 仅在调用方未提供时延迟到首次自动续章再读 DB；
                    // 见 [tryAutoAdvanceChapter]。
                    totalChapters = 0
                }

                // TTS-DIAG #5 — what the host actually loaded. Sample the first
                // 60 chars of the first non-blank paragraph so we can spot
                // "content cleaned to nothing" failures on real chapters.
                val firstNonBlank = paragraphs.firstOrNull { it.isNotBlank() }
                AppLog.info(
                    "TTS",
                    "Host.loadAndPlay: contentLen=${cmd.content.length}, " +
                        "mode=$sliceMode, paragraphs=${paragraphs.size}, " +
                        "positions=${paragraphPositions.size}, startIdx=$paragraphIndex, " +
                        "engine=$engineId, voice='$voiceName', speed=$speed, " +
                        "firstPara='${firstNonBlank?.take(60) ?: "<all blank>"}'",
                )

                publishState(playing = paragraphs.isNotEmpty())
                if (paragraphs.isEmpty()) {
                    AppLog.warn("TtsHost", "LoadAndPlay with empty paragraphs")
                    TtsEventBus.sendEvent(
                        TtsEventBus.Event.Error("无法朗读：本章节无可读文本", canOpenSettings = false)
                    )
                    return@withLock
                }
                speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
            }
        }
    }

    private fun resume() {
        AppLog.info(
            "TtsHost",
            "resume() called: paragraphIndex=$paragraphIndex paragraphStartPos=$paragraphStartPos " +
                "isPlaying=${TtsEventBus.playbackState.value.isPlaying} jobActive=${speakJob?.isActive == true}",
        )
        if (shortCircuitIfEngineFatal()) return
        if (paragraphs.isEmpty()) {
            AppLog.warn("TtsHost", "resume: paragraphs empty, ignored")
            return
        }
        if (TtsEventBus.playbackState.value.isPlaying && speakJob?.isActive == true) {
            AppLog.debug("TtsHost", "resume: already playing+active, no-op")
            return
        }
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                publishState(playing = true)
                speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
            }
        }
    }

    private fun pause() {
        AppLog.info(
            "TtsHost",
            "pause() called: paragraphIndex=$paragraphIndex paragraphStartPos=$paragraphStartPos " +
                "(startPos preserved for sentence-level resume)",
        )
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                runCatching { currentEngine().stop() }
                publishState(playing = false)
            }
            // 暂停后启动空闲计时——60s 内若没人 resume / 切引擎 / 调 voice，
            // 主动释放 systemTtsEngine 让 binder 归还。controlMutex 外调度，
            // 避免与 pause 自身的 cancelAndJoin 串行化。
            scheduleIdleRelease()
        }
    }

    private fun stopPlayback() {
        AppLog.info(
            "TtsHost",
            "stopPlayback() called: resetting paragraphIndex/StartPos to 0",
        )
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                preloadNextChapterJob?.cancel()
                runCatching { currentEngine().stop() }
                paragraphIndex = 0
                paragraphStartPos = 0
                waitingForNextChapter = false
                // 用户主动停止，清自动续章上下文——避免下次再播时延续旧 bookId
                currentBookId = null
                currentChapterIndex = -1
                totalChapters = 0
                TtsEventBus.updatePlayback {
                    copy(
                        isPlaying = false,
                        paragraphIndex = 0,
                        chapterPosition = -1,
                        scrollProgress = -1f,
                    )
                }
            }
            // 与 pause() 同理：stopPlayback 通常紧跟 StopService Cmd，service
            // 销毁会触发 release() 接手；但若 service 因外部原因延迟销毁
            // (比如另一个 client 还粘着 binding)，60s 计时器会兜底释放引擎。
            scheduleIdleRelease()
        }
    }

    private fun prevParagraph() {
        if (paragraphs.isEmpty()) {
            AppLog.warn("TtsHost", "prevParagraph: paragraphs empty, ignored")
            return
        }
        val newIdx = (paragraphIndex - 1).coerceAtLeast(0)
        AppLog.info(
            "TtsHost",
            "prevParagraph: $paragraphIndex → $newIdx (startPos $paragraphStartPos → 0)",
        )
        if (newIdx == paragraphIndex && !TtsEventBus.playbackState.value.isPlaying) return
        paragraphIndex = newIdx
        paragraphStartPos = 0 // 用户主动切段，断点信息作废
        if (TtsEventBus.playbackState.value.isPlaying) {
            // restart loop at new index
            scope.launch {
                controlMutex.withLock {
                    speakJob?.cancelAndJoin()
                    publishState(playing = true)
                    speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
                }
            }
        } else {
            publishState(playing = false)
        }
    }

    private fun nextParagraph() {
        if (paragraphs.isEmpty()) {
            AppLog.warn("TtsHost", "nextParagraph: paragraphs empty, ignored")
            return
        }
        val newIdx = (paragraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        AppLog.info(
            "TtsHost",
            "nextParagraph: $paragraphIndex → $newIdx (startPos $paragraphStartPos → 0)",
        )
        if (newIdx == paragraphIndex && !TtsEventBus.playbackState.value.isPlaying) return
        paragraphIndex = newIdx
        paragraphStartPos = 0
        if (TtsEventBus.playbackState.value.isPlaying) {
            scope.launch {
                controlMutex.withLock {
                    speakJob?.cancelAndJoin()
                    publishState(playing = true)
                    speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
                }
            }
        } else {
            publishState(playing = false)
        }
    }

    // ── The speak loop ───────────────────────────────────────────────────────

    /**
     * 入口：按引擎类型分流。
     * - [SystemTtsEngine]：走 [runBatchPlayback]（仿 Legado，一次性入队整章）。
     * - [EdgeTtsEngine] / 其他：走 [runStreamingPlayback]（callbackFlow 串行）。
     *
     * 之前的统一串行实现遇到「listener 被替换 / onDone 不触发」时整个 host 卡死
     * 在第一段 collect 上（症状：进度永远 1/N，但用户能听到第 12 段——其实是
     * 引擎回调走丢，host 状态没推进）。批量路径用全局常驻 listener + utteranceId
     * 解析回调来源，根除这个 race。
     */
    private suspend fun speakLoop() {
        val engine = currentEngine()
        AppLog.info(
            "TTS",
            "Host.speakLoop: ENTER engine=${engine.javaClass.simpleName}, " +
                "paragraphs=${paragraphs.size}, startIdx=$paragraphIndex, " +
                "paragraphStartPos=$paragraphStartPos, " +
                "thread=${Thread.currentThread().name}",
        )
        when (engine) {
            is SystemTtsEngine -> runBatchPlayback(engine)
            is HttpTtsEngine -> runHttpChapterPlayback(engine)
            else -> runStreamingPlayback(engine)
        }
    }

    /**
     * 仿 Legado [TTSReadAloudService.play] 的批量入队播放。
     *
     * 流程：
     * 1. 把当前段及之后所有段全部规划成 utterance 列表（长段二次切句）。
     * 2. 注册批量回调：onUtteranceStart→更新 paragraphIndex+publishState；
     *    onUtteranceDone→若是整章最后一个 utt 则 `finished.complete()`；
     *    onRangeStart→刷新 paragraphStartPos（句中位置）。
     * 3. 一次性 enqueue（首条 QUEUE_FLUSH，后续 QUEUE_ADD）。
     * 4. await finished；任何中断（pause/stop/切段）由外层 cancel speakJob → 协程
     *    在 await 处抛 CancellationException。
     *
     * 第一段如有 [paragraphStartPos] > 0（pause 时记录的句中位置），切片后再入队，
     * 实现「真断点续读」——这是 Legado 没做但合理的扩展。
     */
    private suspend fun runBatchPlayback(engine: SystemTtsEngine) {
        // 等引擎就绪
        val initRes = engine.awaitReadyResult()
        if (initRes is SystemTtsEngine.InitResult.Failed) {
            AppLog.error("TtsHost", "System TTS init failed: ${initRes.reason}")
            TtsEventBus.sendEvent(
                TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
            )
            publishState(playing = false)
            return
        }

        engine.setSpeechRate(speed)
        applyVoiceToEngine() // 确保语音配置应用到当前 engine 实例

        // 规划 utterance 列表
        data class Utt(
            val paraIdx: Int,
            val subIdx: Int,
            val text: String,
            val isLastSubOfPara: Boolean,
        )
        val plan = ArrayList<Utt>()
        for (idx in paragraphIndex until paragraphs.size) {
            val rawText = paragraphs[idx]
            if (rawText.isBlank() || skipRegex?.containsMatchIn(rawText) == true) continue
            // 第一段从断点切片
            val text = if (idx == paragraphIndex && paragraphStartPos in 1 until rawText.length) {
                rawText.substring(paragraphStartPos)
            } else {
                rawText
            }
            val subs = splitIntoSubSentences(text)
            for ((subIdx, sub) in subs.withIndex()) {
                if (sub.isBlank()) continue
                plan.add(Utt(idx, subIdx, sub, subIdx == subs.lastIndex))
            }
        }

        AppLog.info(
            "TTS",
            "Host.runBatchPlayback: plan size=${plan.size}, startPara=$paragraphIndex, " +
                "startPos=$paragraphStartPos",
        )

        if (plan.isEmpty()) {
            AppLog.warn("TtsHost", "runBatchPlayback: nothing to read in this chapter")
            // 整章空（全是空段/标点段）→ 等同于读完，触发翻章。统一走自动续章
            // 兜底——避免直接 publishState(false) 导致 UI 闪一帧停止。
            waitForNextChapterOrAutoAdvance()
            return
        }

        val finished = CompletableDeferred<Unit>()
        val lastUtt = plan.last()

        val cb = object : SystemTtsEngine.BatchCallback {
            override fun onUtteranceStart(utteranceId: String) {
                markCallback("onStart")
                val parsed = parseUtteranceId(utteranceId)
                if (parsed == null) {
                    AppLog.warn("TtsHost", "cb.onStart: parse failed id=$utteranceId")
                    return
                }
                val (paraIdx, subIdx) = parsed
                val oldIdx = paragraphIndex
                if (paraIdx != oldIdx) {
                    // 段切换：info 级别——这是 logcat 里追"进度推进"最重要的信号。
                    // 用户反馈"进度卡 1/N"时，就看这条 advance 行；如果整段朗读
                    // 期间这条没出现，就说明段没切换或回调没分发到 host。
                    AppLog.info(
                        "TtsHost",
                        "para advance: $oldIdx → $paraIdx (sub=$subIdx, id=$utteranceId)",
                    )
                    paragraphIndex = paraIdx
                    paragraphStartPos = 0 // 段切换重置句中位置
                } else {
                    // 同段内子句切换：debug 级别。subIdx > 0 才打，避免重复（首子句
                    // 已被 advance 那条覆盖）。
                    if (subIdx > 0) {
                        AppLog.debug(
                            "TtsHost",
                            "cb.onStart sub-only id=$utteranceId paraIdx=$paraIdx subIdx=$subIdx",
                        )
                    }
                }
                publishState(playing = true)
            }

            override fun onUtteranceDone(utteranceId: String) {
                markCallback("onDone")
                val parsed = parseUtteranceId(utteranceId)
                if (parsed == null) {
                    AppLog.warn("TtsHost", "cb.onDone: parse failed id=$utteranceId")
                    return
                }
                val (paraIdx, subIdx) = parsed
                val isLast = paraIdx == lastUtt.paraIdx && subIdx == lastUtt.subIdx
                AppLog.debug(
                    "TtsHost",
                    "cb.onDone id=$utteranceId paraIdx=$paraIdx subIdx=$subIdx isLast=$isLast",
                )
                if (isLast && !finished.isCompleted) {
                    AppLog.info("TtsHost", "cb.onDone: chapter all utterances finished")
                    finished.complete(Unit)
                }
            }

            override fun onUtteranceError(utteranceId: String, errorCode: Int) {
                markCallback("onError")
                AppLog.warn("TtsHost", "batch utterance error id=$utteranceId code=$errorCode")
                // 单段失败：把它当作完成跳过；如果是最后一段也要 fulfill finished
                val parsed = parseUtteranceId(utteranceId)
                if (parsed != null) {
                    val (paraIdx, subIdx) = parsed
                    if (paraIdx == lastUtt.paraIdx && subIdx == lastUtt.subIdx) {
                        if (!finished.isCompleted) {
                            AppLog.info(
                                "TtsHost",
                                "cb.onError on last utt → completing finished anyway",
                            )
                            finished.complete(Unit)
                        }
                    }
                }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
                markCallback("onRange")
                val parsed = parseUtteranceId(utteranceId) ?: return
                val (paraIdx, subIdx) = parsed
                if (paraIdx == paragraphIndex) {
                    // 当 utterance 是从段断点切片来的，要把切片偏移加回原段坐标
                    val sliceOffset = if (subIdx == 0 && paraIdx == paragraphIndex) {
                        paragraphStartPosBaseForFirstSub
                    } else 0
                    val oldStartPos = paragraphStartPos
                    paragraphStartPos = (sliceOffset + start).coerceAtLeast(0)
                    // onRangeStart 在长段里会触发很多次（每个字 / 每个词），全打
                    // 会刷屏；只在「跳了 >= 5 字」时记一次，足够追踪进度同时不淹没
                    // 其他日志。第一次进入新段（oldStartPos == 0 且 paragraphStartPos > 0）
                    // 也强制打一条做信号。
                    if (oldStartPos == 0 || (paragraphStartPos - oldStartPos) >= 5) {
                        AppLog.debug(
                            "TtsHost",
                            "cb.onRangeStart id=$utteranceId range=[$start,$end) " +
                                "base=$sliceOffset → paragraphStartPos $oldStartPos→$paragraphStartPos",
                        )
                    }
                }
            }
        }

        // 记下"第一段第一子句"的切片基准（用于 onRangeStart 把局部偏移还原成段内坐标）
        // 注意：plan 入队后 paragraphStartPos 会被回调改写，所以这里用一个独立字段保存初始值
        paragraphStartPosBaseForFirstSub = if (paragraphStartPos in 1 until (paragraphs.getOrNull(paragraphIndex)?.length ?: 0)) {
            paragraphStartPos
        } else 0

        engine.setBatchCallback(cb)

        // 入队：逐条 debug 记录 (id, queueMode, len, result)。这是诊断「multitts
        // 不发声」最直接的证据——若所有 enqueue 都返回 SUCCESS 但听不到，说明
        // 引擎收下了但合成阶段静默；若全部 ERROR，说明根本没入队成功。
        AppLog.info("TtsHost", "enqueue starting: ${plan.size} utterances")
        for ((i, utt) in plan.withIndex()) {
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "morealm_${utt.paraIdx}_${utt.subIdx}"
            val result = engine.enqueue(utt.text, id, mode)
            AppLog.debug(
                "TtsHost",
                "enqueue[$i] id=$id mode=${if (mode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"} " +
                    "len=${utt.text.length} result=$result",
            )
            if (result == TextToSpeech.ERROR) {
                AppLog.error("TtsHost", "batch enqueue ERROR at i=$i id=$id, aborting")
                engine.setBatchCallback(null)
                // 第 0 条 enqueue 就 ERROR —— 引擎进程基本已经异常（参见
                // markEngineFatal 头注释）。记 fatal，下个 30s 内的 loadAndPlay /
                // resume / speakOneShot 都会被短路，避免反复触发同样的 ANR。
                if (i == 0) markEngineFatal("first enqueue returned ERROR (id=$id)")
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error("TTS 入队失败，请重试", canOpenSettings = true)
                )
                publishState(playing = false)
                return
            }
        }
        AppLog.info("TtsHost", "enqueue done: all ${plan.size} utterances accepted by engine")

        // ── 批量播放 watchdog ──
        //
        // 之前 finished.await() 没有超时：引擎吞掉所有回调时 host 永久挂起，
        // isPlaying=true 但完全静默，logcat 空白——"没声音没日志"的元凶。
        //
        // 现在记一个时间戳基准（enqueue 刚完成 = "引擎应该开始读了"），然后
        // 用 withTimeoutOrNull 包裹 finished.await()，每 [BATCH_WATCHDOG_TIMEOUT_MS]
        // 醒来检查最后一次回调的时间。如果自从 enqueue 完成后一直没收到任何回调，
        // 则认定引擎静默——尝试 reInit 并重新 enqueue 整章。
        //
        // 不用担心 withTimeoutOrNull 超时时 finished 已经 complete 的 race：
        // CompletableDeferred.await() 在 complete 后立刻返回，不会被超时中断。
        val enqueueDoneAtMs = System.currentTimeMillis()
        markCallback("enqueued") // 基准：enqueue 完成时刻

        try {
            var watchdogTriggered = false
            val result = withTimeoutOrNull(BATCH_WATCHDOG_TIMEOUT_MS) { finished.await() }
            if (result == null && !finished.isCompleted) {
                // 超时 + finished 没 complete → 检查回调是否有动静
                val silentMs = System.currentTimeMillis() - lastCallbackAtMs
                if (silentMs >= BATCH_WATCHDOG_TIMEOUT_MS) {
                    watchdogTriggered = true
                    AppLog.error(
                        "TtsHost",
                        "WATCHDOG: no engine callbacks for ${silentMs / 1000}s after enqueue! " +
                            "engine=$engineId, plan=${plan.size} utts, " +
                            "lastCb=$lastCallbackType — attempting reInit + replay",
                    )
                    engine.setBatchCallback(null)

                    // 尝试 reInit 恢复
                    try {
                        engine.reInit()
                        val reInitResult = engine.awaitReadyResult()
                        if (reInitResult is SystemTtsEngine.InitResult.Failed) {
                            AppLog.error("TtsHost", "WATCHDOG reInit failed: ${reInitResult.reason}")
                            TtsEventBus.sendEvent(
                                TtsEventBus.Event.Error(
                                    "TTS 引擎无响应且重启失败：${reInitResult.reason}",
                                    canOpenSettings = true,
                                )
                            )
                            publishState(playing = false)
                            return
                        }
                        AppLog.info("TtsHost", "WATCHDOG: reInit OK, replaying from para=$paragraphIndex")
                        // reInit 成功 —— 引擎已经恢复，解除冷却让续播重新可用。
                        clearEngineFatal()
                        engine.setSpeechRate(speed)
                        applyVoiceToEngine()
                        // 重新进入 runBatchPlayback —— 这次 paragraphIndex 保持不变，
                        // 从卡住的那一段重新规划 utterance 列表
                        runBatchPlayback(engine)
                        return
                    } catch (e: Exception) {
                        AppLog.error("TtsHost", "WATCHDOG reInit threw", e)
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(
                                "TTS 引擎无响应且重启异常",
                                canOpenSettings = true,
                            )
                        )
                        publishState(playing = false)
                        return
                    }
                } else {
                    // 超时但有回调（只是读得慢 / 章节很长），继续等
                    AppLog.debug(
                        "TtsHost",
                        "batch watchdog timeout but callbacks alive (age=${silentMs}ms), keep waiting",
                    )
                    finished.await() // 无超时兜底——回调在活着就正常等
                }
            }
            // 到这里 finished 已 complete（正常读完）
        } catch (_: CancellationException) {
            // pause/stop/切段被外层 cancel：此时 engine.stop() 已被调用（pause/stop/setEngine 都会调）
            AppLog.debug("TtsHost", "runBatchPlayback: cancelled mid-flight")
            engine.setBatchCallback(null)
            throw kotlin.coroutines.cancellation.CancellationException("batch cancelled")
        }
        engine.setBatchCallback(null)

        // 章节读完
        AppLog.info("TtsHost", "runBatchPlayback: chapter finished")
        waitForNextChapterOrAutoAdvance()
    }

    /** 仅供 [onRangeStart] 把切片局部偏移还原成段内坐标用。 */
    @Volatile private var paragraphStartPosBaseForFirstSub: Int = 0

    /** 解析 utteranceId "morealm_{paraIdx}_{subIdx}" → (paraIdx, subIdx)。 */
    private fun parseUtteranceId(id: String): Pair<Int, Int>? {
        if (!id.startsWith("morealm_")) return null
        val parts = id.removePrefix("morealm_").split("_")
        if (parts.size != 2) return null
        val p = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return p to s
    }

    // ── 章末等待 + 自动续章兜底 ──────────────────────────────────────────────
    //
    // 把 6 个 speakLoop 分支末尾的"sendEvent(ChapterFinished) → 等 ViewModel 推章
    // → 超时停止"重复块统一到 [waitForNextChapterOrAutoAdvance]。
    //
    // 新行为相比之前多一步：超时后若 host 缓存了 bookId/chapterIndex（reader 在
    // LoadAndPlay 时透传），用 [chapterContentLoader] 直接加载下一章并自发
    // LoadAndPlay 续播——这是 Phase D 的"用户离开 Reader 后续章不断声"。

    /**
     * 章节读完后调一次：先发 ChapterFinished 事件给 reader 一个推章窗口，超时后
     * 自动续章兜底；都失败时 publishState(false) 停止。
     */
    private suspend fun waitForNextChapterOrAutoAdvance() {
        waitingForNextChapter = true
        TtsEventBus.sendEvent(TtsEventBus.Event.ChapterFinished)
        val gotNext = withTimeoutOrNull(WAIT_NEXT_CHAPTER_MS) {
            while (waitingForNextChapter) delay(50)
            true
        }
        if (gotNext == true) {
            // ViewModel 在窗口内推送了新 LoadAndPlay（旧路径），续章成功
            return
        }
        AppLog.info(
            "TtsHost",
            "ChapterFinished: viewModel didn't push within ${WAIT_NEXT_CHAPTER_MS}ms, " +
                "trying host autoAdvance",
        )
        if (tryAutoAdvanceChapter()) return
        AppLog.info("TtsHost", "ChapterFinished: autoAdvance unavailable, stopping playback")
        publishState(playing = false)
    }

    /**
     * 尝试用 [chapterContentLoader] 加载下一章并自发 LoadAndPlay 续播。
     *
     * @return true 表示已成功提交下一章 LoadAndPlay 命令；false 表示无 bookId 上下文
     *         / 已是最后一章 / 加载失败 / 内容为空，调用方应停止播放。
     */
    private suspend fun tryAutoAdvanceChapter(): Boolean {
        val bookId = currentBookId ?: return false
        val curIdx = currentChapterIndex
        if (curIdx < 0) return false
        val nextIdx = curIdx + 1
        AppLog.info("TtsHost", "autoAdvance: bookId=$bookId nextIdx=$nextIdx (host 接管续章)")
        val loaded = chapterContentLoader.loadForTts(bookId, nextIdx)
        if (loaded == null) {
            AppLog.warn(
                "TtsHost",
                "autoAdvance: loader returned null (book/章节不存在或已到末章)",
            )
            return false
        }
        if (loaded.content.isBlank()) {
            AppLog.warn("TtsHost", "autoAdvance: content blank, treating as end-of-book")
            return false
        }
        // 通过 EventBus 把命令送回自己——走和 reader 完全相同的入口，保持单一路径
        TtsEventBus.sendCommand(
            TtsEventBus.Command.LoadAndPlay(
                bookTitle = loaded.bookTitle,
                chapterTitle = loaded.chapterTitle,
                coverUrl = loaded.coverUrl,
                content = loaded.content,
                paragraphPositions = null,
                startChapterPosition = 0,
                bookId = bookId,
                chapterIndex = nextIdx,
            )
        )
        return true
    }

    /**
     * 长段二次切句。Legado 没做这步——它的 contentList 来自排版后的 page.text，自带换行。
     * MoRealm 是按章节段 offset 切片，长段（200+ 字）整体扔给引擎会断句不自然。
     *
     * 切分点：。！？；和换行。保留分隔符在前一子句末尾。短于 [LONG_PARA_THRESHOLD]
     * 的段不切，避免把短句切碎让引擎反复重启 prosody。
     */
    private fun splitIntoSubSentences(text: String): List<String> {
        if (text.length <= LONG_PARA_THRESHOLD) return listOf(text)
        val result = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in SENTENCE_SEPARATORS) {
                if (sb.isNotBlank()) result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return if (result.isEmpty()) listOf(text) else result
    }

    /**
     * 流式引擎的入口：
     *
     * - **Edge 引擎**：走 [runEdgeChapterPlayback]——把当前段及之后所有非空段拼成
     *   一份大 SSML（段间用 `<break time="600ms"/>`）一次性 WSS 合成，超长自动分批。
     *   这是 plan C：解决"段间没停顿、段中乱停顿"的同时，重听整章可以缓存命中。
     *   段进度通过时长估算驱动 `onParagraphStart` 回调（避开 Edge `audio.metadata`
     *   的 JSON 解析）。
     * - **其他流式引擎**（HttpTts 等）：保留旧的逐段 [engine.speak] callbackFlow
     *   串行路径——这些引擎一段一次 HTTP/合成，没有"整章 SSML"概念。
     */
    private suspend fun runStreamingPlayback(engine: TtsEngine) {
        if (engine is EdgeTtsEngine) {
            runEdgeChapterPlayback(engine)
            return
        }
        var consecutiveErrors = 0
        try {
            for (idx in paragraphIndex until paragraphs.size) {
                if (!TtsEventBus.playbackState.value.isPlaying) {
                    AppLog.info("TTS", "Host.runStreamingPlayback: isPlaying=false, exit at idx=$idx")
                    return
                }
                paragraphIndex = idx
                paragraphStartPos = 0
                publishState(playing = true)

                val paragraphText = paragraphs[idx]
                if (paragraphText.isBlank() ||
                    skipRegex?.containsMatchIn(paragraphText) == true) {
                    continue
                }

                try {
                    AppLog.info(
                        "TTS",
                        "Host.runStreamingPlayback: speak idx=$idx/${paragraphs.size} " +
                            "len=${paragraphText.length}",
                    )
                    engine.speak(paragraphText, speed).collect { /* drain */ }
                    consecutiveErrors = 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLog.warn("TtsHost", "stream speak error para $idx (consec=$consecutiveErrors)", e)
                    if (consecutiveErrors == 1) {
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(
                                e.message ?: "TTS 朗读失败",
                                canOpenSettings = engineId == "system",
                            )
                        )
                    }
                    if (consecutiveErrors >= 3) {
                        if (engineId != "system") {
                            AppLog.info("TtsHost", "Stream engine fallback → system after repeated errors")
                            TtsEventBus.sendEvent(
                                TtsEventBus.Event.Error(
                                    "${engine.javaClass.simpleName} 多次失败，已自动切换为系统朗读",
                                    canOpenSettings = false,
                                )
                            )
                            withContext(NonCancellable) { prefs.setTtsEngine("system") }
                            engineId = "system"
                            voiceName = savedVoiceForEngine("system")
                            applyVoiceToEngine()
                            val voices = loadVoicesForEngine("system")
                            TtsEventBus.updatePlayback {
                                copy(engine = "system", voiceName = voiceName, voices = voices)
                            }
                            consecutiveErrors = 0
                            speakLoop() // 切到 system → 入口会进入 batch 路径
                            return
                        }
                        AppLog.error("TtsHost", "Stream engine repeatedly failing, stopping", e)
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(
                                "TTS 连续 3 次朗读失败，已停止：${e.message ?: "未知错误"}",
                                canOpenSettings = true,
                            )
                        )
                        publishState(playing = false)
                        return
                    }
                    delay(200)
                }
            }
            // chapter end
            waitForNextChapterOrAutoAdvance()
        } catch (_: CancellationException) {
            // normal cancel
        } catch (e: Exception) {
            AppLog.error("TtsHost", "runStreamingPlayback crashed", e)
            publishState(playing = false)
        }
    }

    /**
     * Plan C — Edge 整章合成路径。
     *
     * 把 [paragraphIndex, paragraphs.size) 中所有非空、非 skipRegex 的段拼成一份
     * SSML（段间 `<break time="${EDGE_CHAPTER_BREAK_MS}ms"/>`），调
     * [EdgeTtsEngine.speakChapter] 整章一次性合成（超长由 engine 内部分批 WSS）。
     *
     * 段进度跟随：engine 通过 [onParagraphStart] 回调按时长估算驱动，参数是
     * "本次合成内非空段的相对索引"——映射回 host 真实 paragraphIndex 用 [origIndices]。
     *
     * 失败时（连错 ≥ 2）回退到老的逐段 streaming 路径。Edge 故障多见于鉴权时钟漂移
     * 已被 EdgeTtsEngine 内部处理；这里只兜底彻底崩溃的情况。
     */
    private suspend fun runEdgeChapterPlayback(engine: EdgeTtsEngine) {
        try {
            // 收集要朗读的段：保留 (originalIdx, text)，blank 和 skipRegex 直接跳过。
            // 与 paragraphsFromPositions "保留空段以推进高亮" 的策略不冲突：
            // 这里只是不送给引擎合成，paragraphIndex 仍然能从 onParagraphStart 推进
            // 到下一个非空段，过程中跳过的空段会被 publishState 一次性带过。
            data class ToRead(val origIdx: Int, val text: String)
            val toRead = ArrayList<ToRead>()
            for (i in paragraphIndex until paragraphs.size) {
                val p = paragraphs[i]
                if (p.isBlank() || skipRegex?.containsMatchIn(p) == true) continue
                toRead.add(ToRead(i, p))
            }
            if (toRead.isEmpty()) {
                AppLog.warn("TtsHost", "runEdgeChapterPlayback: nothing to read")
                // 整章空 → 等同于读完，走自动续章兜底
                waitForNextChapterOrAutoAdvance()
                return
            }

            AppLog.info(
                "TTS",
                "Host.runEdgeChapterPlayback: nonEmpty=${toRead.size}, " +
                    "startPara=$paragraphIndex, totalChars=${toRead.sumOf { it.text.length }}",
            )

            val texts = toRead.map { it.text }
            val origIndices = toRead.map { it.origIdx }

            try {
                engine.speakChapter(
                    paragraphs = texts,
                    speed = speed,
                    breakMs = EDGE_CHAPTER_BREAK_MS,
                    onParagraphStart = { localIdx ->
                        // localIdx 是非空段相对索引（0..toRead.size-1）
                        if (!TtsEventBus.playbackState.value.isPlaying) return@speakChapter
                        markCallback("edgePara")
                        val origIdx = origIndices.getOrNull(localIdx) ?: return@speakChapter
                        if (paragraphIndex != origIdx) {
                            AppLog.debug(
                                "TtsHost",
                                "edge para advance: $paragraphIndex → $origIdx (local=$localIdx)",
                            )
                            paragraphIndex = origIdx
                            paragraphStartPos = 0
                            publishState(playing = true)
                        }
                    },
                ).collect {
                    markCallback("edgeChunk")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.warn("TtsHost", "Edge speakChapter failed, falling back to per-para streaming", e)
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error(
                        "Edge 整章合成失败，已切回逐段模式：${e.message ?: "未知错误"}",
                        canOpenSettings = false,
                    )
                )
                // 直接复用旧的逐段路径——把 engine 当通用 TtsEngine 走 streaming
                runPerParagraphStreaming(engine)
                return
            }

            // chapter end
            waitForNextChapterOrAutoAdvance()
        } catch (_: CancellationException) {
            // normal cancel
        } catch (e: Exception) {
            AppLog.error("TtsHost", "runEdgeChapterPlayback crashed", e)
            publishState(playing = false)
        }
    }

    /**
     * Plan C-equivalent for HttpTts —— 整章批量朗读路径，对齐 Legado
     * [io.legado.app.service.HttpReadAloudService.downloadAndPlayAudios]。
     *
     * 流程：
     * 1. 收集 [paragraphIndex, paragraphs.size) 中所有非空、非 skipRegex 的段。
     * 2. 调 [HttpTtsEngine.speakChapter]：第一段同步下载入队 ExoPlayer，后续段
     *    边下边入队；onParagraphStart(localIdx) 把段进度回调上来 → 转换为
     *    paragraphIndex 真实坐标 → publishState 让阅读器高亮跟随。
     * 3. 全章一旦进入"正在播最后一段"阶段，启动 [preloadNextChapterJob] 后台
     *    把下一章前 [HTTP_PRELOAD_NEXT_PARAGRAPHS] 段拉到磁盘缓存（与 Legado
     *    HttpReadAloudService.kt:193-216 的 preDownloadAudios 思路一致）。
     * 4. 完成后等 [WAIT_NEXT_CHAPTER_MS] 让 ReaderViewModel 推进章节。
     *
     * 失败处理：HttpTtsEngine 已经做了"连续 5 次失败抛 IOException"——这里只兜底
     * 把错误提示给用户并停止播放，不再做引擎级回退（用户答确认）。
     */
    private suspend fun runHttpChapterPlayback(engine: HttpTtsEngine) {
        try {
            data class ToRead(val origIdx: Int, val text: String)
            val toRead = ArrayList<ToRead>()
            for (i in paragraphIndex until paragraphs.size) {
                val p = paragraphs[i]
                if (p.isBlank() || skipRegex?.containsMatchIn(p) == true) continue
                // 第一段从断点切片（与 Edge 路径不同：HttpTts 单段一文件，切片只影响第一段缓存命名）
                val text = if (i == paragraphIndex && paragraphStartPos in 1 until p.length) {
                    p.substring(paragraphStartPos)
                } else p
                toRead.add(ToRead(i, text))
            }
            if (toRead.isEmpty()) {
                AppLog.warn("TtsHost", "runHttpChapterPlayback: nothing to read")
                // 整章空 → 等同于读完，走自动续章兜底
                waitForNextChapterOrAutoAdvance()
                return
            }
            AppLog.info(
                "TTS",
                "Host.runHttpChapterPlayback: nonEmpty=${toRead.size} startPara=$paragraphIndex " +
                    "totalChars=${toRead.sumOf { it.text.length }} chapter='$chapterTitle'",
            )
            val texts = toRead.map { it.text }
            val origIndices = toRead.map { it.origIdx }

            // 启动下一章预下载（独立 job，章节失败不会影响当前播放）
            startHttpPreloadNextChapter(engine)

            try {
                engine.speakChapter(
                    paragraphs = texts,
                    speed = speed,
                    chapterTitleHint = chapterTitle,
                    onParagraphStart = { localIdx ->
                        if (!TtsEventBus.playbackState.value.isPlaying) return@speakChapter
                        markCallback("httpPara")
                        val origIdx = origIndices.getOrNull(localIdx) ?: return@speakChapter
                        if (paragraphIndex != origIdx) {
                            AppLog.debug(
                                "TtsHost",
                                "http para advance: $paragraphIndex → $origIdx (local=$localIdx)",
                            )
                            paragraphIndex = origIdx
                            paragraphStartPos = 0
                            publishState(playing = true)
                        }
                    },
                ).collect {
                    markCallback("httpChunk")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.error("TtsHost", "HttpTtsEngine.speakChapter failed", e)
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error(
                        e.message ?: "HTTP 朗读失败",
                        canOpenSettings = false,
                    )
                )
                publishState(playing = false)
                return
            }

            // chapter end
            waitForNextChapterOrAutoAdvance()
        } catch (_: CancellationException) {
            // normal cancel
        } catch (e: Exception) {
            AppLog.error("TtsHost", "runHttpChapterPlayback crashed", e)
            publishState(playing = false)
        }
    }

    /**
     * 章节级预下载——后台拉下一章前 [HTTP_PRELOAD_NEXT_PARAGRAPHS] 段到磁盘缓存。
     *
     * 关键点：host 自己**没有**章节列表（ViewModel 那边持有 BookChapter[]），所以
     * 这里只能通过 [TtsEventBus.Event.RequestNextChapterPreload] 让 ViewModel 把
     * 下一章正文回传，然后 host 调 engine 拉缓存。如果 ViewModel 不响应（比如
     * 阅读器已退出），preloadNextChapterJob 自然超时退出，不影响功能。
     *
     * 当前**简化实现**：直接结束（占位）；ViewModel 桥接留作后续 PR。Legado 的
     * preDownloadAudios 在同 service 内能直接拿 ReadBook.nextTextChapter，MoRealm
     * 的解耦架构需要新增一条事件桥，避免本次 PR 跨过 host/VM 边界改动太大。
     */
    private fun startHttpPreloadNextChapter(@Suppress("UNUSED_PARAMETER") engine: HttpTtsEngine) {
        preloadNextChapterJob?.cancel()
        preloadNextChapterJob = scope.launch {
            // TODO(http-tts): 让 ReaderViewModel 通过新增事件回传下一章正文，
            //  这里再调 engine.downloadOrFromCache(text, speed, nextChapterTitle)
            //  把前 N 段提前拉到磁盘。先空实现，留 hook 点。
            AppLog.debug("TtsHost", "preloadNextChapter: stub (waiting for VM bridge)")
        }
    }

    /**
     * 旧的逐段流式路径，从 [runStreamingPlayback] 抽出来供 [runEdgeChapterPlayback]
     * 失败时回退使用。**HttpTts 已改走 [runHttpChapterPlayback]**——这里只剩潜在的
     * "其他自定义流式引擎"备用。
     */
    private suspend fun runPerParagraphStreaming(engine: TtsEngine) {
        var consecutiveErrors = 0
        try {
            for (idx in paragraphIndex until paragraphs.size) {
                if (!TtsEventBus.playbackState.value.isPlaying) return
                paragraphIndex = idx
                paragraphStartPos = 0
                publishState(playing = true)

                val paragraphText = paragraphs[idx]
                if (paragraphText.isBlank() ||
                    skipRegex?.containsMatchIn(paragraphText) == true) continue

                try {
                    engine.speak(paragraphText, speed).collect { /* drain */ }
                    consecutiveErrors = 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLog.warn("TtsHost", "per-para fallback error idx=$idx (consec=$consecutiveErrors)", e)
                    if (consecutiveErrors >= 3) {
                        publishState(playing = false)
                        return
                    }
                    delay(200)
                }
            }
            waitForNextChapterOrAutoAdvance()
        } catch (_: CancellationException) {
            // normal cancel
        }
    }

    // ── Configuration setters ────────────────────────────────────────────────

    private fun setSpeed(newSpeed: Float) {
        val coerced = newSpeed.coerceIn(0.3f, 4.0f)
        if (coerced == speed) return
        speed = coerced
        scope.launch { prefs.setTtsSpeed(speed) }
        TtsEventBus.updatePlayback { copy(speed = speed) }
        // 必须重启播放循环才能让新速度生效：
        // - Edge plan C：rate 写死在整章 SSML 的 <prosody> 里，已发出去的合成请求
        //   不会改速度，必须取消重发当前位置往后的内容。
        // - System TTS 批量：tts.setSpeechRate 只影响**未来 enqueue**，已入队的
        //   utterance 仍按旧速读完。
        // - Edge 老逐段：要等到下一段才生效，体感上"按了没用"。
        //
        // Debounce 250ms 防 Slider 拖动时狂打断（TtsPanel 用 Slider，每个 tick 都
        // 会调 setSpeed；ListenScreen 用 5 档按钮也复用这条路径无碍）。最后一次
        // tick 后 250ms 才真正重启，整个滑动过程不会反复打断音频。
        if (TtsEventBus.playbackState.value.isPlaying && paragraphs.isNotEmpty()) {
            pendingSpeedRestartJob?.cancel()
            pendingSpeedRestartJob = scope.launch {
                delay(SPEED_RESTART_DEBOUNCE_MS)
                controlMutex.withLock {
                    // 二次确认：debounce 等待期间用户可能按了暂停 / 停止
                    if (!TtsEventBus.playbackState.value.isPlaying) return@withLock
                    speakJob?.cancelAndJoin()
                    runCatching { currentEngine().stop() }
                    publishState(playing = true)
                    speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
                }
            }
        }
    }

    private fun setEngine(newEngine: String) {
        if (newEngine == engineId) return
        scope.launch {
            controlMutex.withLock {
                val wasPlaying = TtsEventBus.playbackState.value.isPlaying
                speakJob?.cancelAndJoin()
                preloadNextChapterJob?.cancel()
                runCatching { currentEngine().stop() }
                // 切到 http_* 时校验源仍存在；源被删则 fallback system 并报错
                val resolvedEngine: String = if (newEngine.startsWith("http_")) {
                    val id = parseHttpEngineId(newEngine)
                    val cfg = id?.let { httpTtsDao.getById(it) }
                    if (cfg == null) {
                        AppLog.warn("TtsHost", "setEngine: $newEngine missing in DAO, fallback system")
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(
                                "选择的 HTTP 朗读源不存在，已切回系统朗读",
                                canOpenSettings = false,
                            )
                        )
                        "system"
                    } else newEngine
                } else newEngine
                engineId = resolvedEngine
                clearEngineFatal() // 用户主动切引擎，等同重置——之前 fatal 与新引擎无关
                prefs.setTtsEngine(resolvedEngine)
                voiceName = savedVoiceForEngine(resolvedEngine)
                applyVoiceToEngine()
                val voices = loadVoicesForEngine(resolvedEngine)
                TtsEventBus.updatePlayback {
                    copy(engine = resolvedEngine, voiceName = voiceName, voices = voices)
                }
                if (wasPlaying && paragraphs.isNotEmpty()) {
                    publishState(playing = true)
                    speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
                }
            }
        }
    }

    private fun setVoice(newVoice: String) {
        val resolved = resolveVoiceOrEmpty(newVoice, TtsEventBus.playbackState.value.voices)
        voiceName = resolved
        // applyVoiceToEngine 是 suspend（内部 IO 包裹同步 binder 调用），
        // 这里 setVoice 本身是非 suspend 入口，所以丢到 scope.launch 异步跑。
        // 不阻塞 handleCommand 路径，也不阻塞调用者（UI/通知）。
        scope.launch { applyVoiceToEngine() }
        scope.launch { saveVoiceForEngine(engineId, resolved) }
        TtsEventBus.updatePlayback { copy(voiceName = resolved) }
    }

    private fun setSkipPattern(pattern: String) {
        skipRegex = if (pattern.isNotBlank()) {
            runCatching { Regex(pattern) }.getOrNull()
        } else null
        scope.launch { prefs.setTtsSkipPattern(pattern) }
    }

    private fun setSleepMinutes(minutes: Int) {
        sleepRemainingMinutes = minutes
        sleepTimerJob?.cancel()
        TtsEventBus.updatePlayback { copy(sleepMinutes = minutes) }
        if (minutes > 0) {
            sleepTimerJob = scope.launch {
                var remaining = minutes
                while (remaining > 0) {
                    delay(60_000L)
                    remaining -= 1
                    sleepRemainingMinutes = remaining
                    TtsEventBus.updatePlayback { copy(sleepMinutes = remaining) }
                }
                AppLog.info("TtsHost", "Sleep timer expired")
                stopPlayback()
                TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
            }
        }
    }

    private fun speakOneShot(text: String) {
        if (text.isBlank()) return
        if (shortCircuitIfEngineFatal()) return
        oneShotJob?.cancel()
        oneShotJob = scope.launch {
            try {
                val engine = currentEngine()
                if (engine is SystemTtsEngine) {
                    val initRes = engine.awaitReadyResult()
                    if (initRes is SystemTtsEngine.InitResult.Failed) {
                        AppLog.error("TtsHost", "speakOneShot: TTS init failed: ${initRes.reason}")
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                        )
                        return@launch
                    }
                }
                engine.speak(text, speed).collect { /* drain */ }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLog.warn("TtsHost", "speakOneShot failed", e)
                // One-shot failures (試聽片段) — opening system settings rarely helps,
                // so leave canOpenSettings = false. The exception's localized message
                // (set by SystemTtsEngine.speak's IllegalStateException path) carries
                // the actionable reason already.
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error(
                        e.message ?: "TTS 朗读失败",
                        canOpenSettings = false,
                    )
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * 上一次推送给 [TtsEventBus.playbackState] 的关键三元组：(idx, total, isPlaying)。
     * 仅用于 [publishState] 内部的日志去重——只有这三项变化时才打印一行 debug，
     * 避免 onRangeStart 里频繁调用 publishState 时刷屏。
     */
    private var lastPublishedTriple: Triple<Int, Int, Boolean>? = null

    private fun publishState(playing: Boolean) {
        val total = paragraphs.size
        val pos = paragraphPositions.getOrNull(paragraphIndex) ?: 0
        val progress = if (total > 1) paragraphIndex.toFloat() / (total - 1) else if (total == 1) 1f else -1f
        // Bug 2/3 数据通路：把当前段落的章内字符区间 [start, end) 算出来发到 EventBus，
        // 阅读器渲染层用它给当前段画高亮，外层用它判断 "回到朗读 FAB" / 自动跟随翻页。
        // 非播放态置 null —— 不让停止后的高亮残留在阅读器上。
        val paragraphLen = paragraphs.getOrNull(paragraphIndex)?.length ?: 0
        val range: IntRange? = if (playing && paragraphLen > 0)
            pos until (pos + paragraphLen) else null
        TtsEventBus.updatePlayback {
            copy(
                bookTitle = this@TtsEngineHost.bookTitle.ifBlank { this.bookTitle },
                chapterTitle = this@TtsEngineHost.chapterTitle.ifBlank { this.chapterTitle },
                coverUrl = this@TtsEngineHost.coverUrl ?: this.coverUrl,
                isPlaying = playing,
                paragraphIndex = paragraphIndex,
                totalParagraphs = total,
                chapterPosition = if (playing) pos else -1,
                paragraphRange = range,
                scrollProgress = if (playing) progress else -1f,
                speed = speed,
                engine = engineId,
                voiceName = voiceName,
            )
        }
        // 进入播放态时按需启动心跳（idempotent — 已在跑就 noop）；播放态结束
        // 时心跳 job 自己会从 while 里 break 退出，无需主动 cancel。
        if (playing) startHeartbeat()
        // 只在 (idx, total, playing) 变化时记日志：onRangeStart 高频触发时
        // 不会刷屏，但段切换、章切换、暂停/恢复都会留下记录。
        val triple = Triple(paragraphIndex, total, playing)
        if (triple != lastPublishedTriple) {
            lastPublishedTriple = triple
            AppLog.debug(
                "TtsHost",
                "publishState: idx=$paragraphIndex/$total chapterPos=$pos " +
                    "playing=$playing engine=$engineId",
            )
        }
    }

    private fun currentEngine(): TtsEngine {
        return when {
            engineId == "edge" -> edgeTtsEngine
            engineId.startsWith("http_") -> ensureHttpTtsEngine(engineId)
                ?: ensureSystemTtsEngine().also {
                    // 切到不存在的 http_<id>（用户删了源但 prefs 还指着）→ 回退 system，
                    // 同时报一条 Error 让 UI 弹 snackbar，引导用户重选引擎。
                    AppLog.warn(
                        "TtsHost",
                        "currentEngine: http engine $engineId not found in DAO, fallback to system",
                    )
                    TtsEventBus.sendEvent(
                        TtsEventBus.Event.Error(
                            "已选的 HTTP 朗读源已被删除，已临时切回系统朗读",
                            canOpenSettings = false,
                        )
                    )
                }
            else -> ensureSystemTtsEngine()
        }
    }

    /**
     * 把 [voiceName] 写到当前激活的引擎上。
     *
     * suspend + `withContext(Dispatchers.IO)`：内部
     * - [SystemTtsEngine.setVoice] 现已只读 cachedVoices，常态下不再 IPC，
     *   但缓存 miss 兜底（极少数情况）仍可能跑同步 binder；
     * - [EdgeTtsEngine.setVoice] 仅写本地字段，本身不阻塞，但放在 IO 也无害。
     *
     * 切到 IO 是 ANR 防御的关键一环：业务路径不会再因 TextToSpeech 同步方法
     * 卡住协程所在的线程；最坏情况是 IO 线程池里某个 worker 暂时被占用。
     */
    private suspend fun applyVoiceToEngine() = withContext(Dispatchers.IO) {
        when {
            engineId == "edge" -> edgeTtsEngine.setVoice(voiceName)
            engineId.startsWith("http_") -> { /* HttpTts 不暴露多音色，noop */ }
            else -> ensureSystemTtsEngine().setVoice(voiceName)
        }
    }

    /**
     * 切换 system TTS 引擎包名 —— 不需要重启阅读器。
     *
     * 流程（持 controlMutex 串行化）：
     * 1. 记录"切换前是否在朗读"。
     * 2. cancelAndJoin speakJob → 旧 engine.stop() + shutdown()。
     * 3. 新建 SystemTtsEngine 用新包名 initialize；voice/rate 也重新应用。
     * 4. 之前在朗读则继续 speakLoop（从当前 paragraphIndex/StartPos 续读）。
     *
     * 仅当 [engineId] 当前是 "system" 时才有意义。"edge" 时该命令仅更新 prefs，
     * 待用户切回 system 时新包名生效。
     */
    private fun rebindSystemEngine(newPkg: String) {
        scope.launch {
            controlMutex.withLock {
                AppLog.info(
                    "TtsHost",
                    "rebindSystemEngine: pkg='${newPkg.ifBlank { "<system-default>" }}', engineId=$engineId",
                )
                prefs.setTtsSystemEnginePackage(newPkg)
                if (engineId != "system") {
                    AppLog.info("TtsHost", "rebindSystemEngine: engineId=$engineId, pkg saved but not active")
                    return@withLock
                }
                val wasPlaying = TtsEventBus.playbackState.value.isPlaying
                speakJob?.cancelAndJoin()
                runCatching { systemTtsEngine?.stop() }
                runCatching { systemTtsEngine?.shutdown() }
                systemTtsEngine = null
                // 直接调 ensureSystemTtsEngine 会再读一次 prefs；这里直接传新包名避免竞态
                val fresh = SystemTtsEngine(context).also { it.initialize(enginePackage = newPkg.ifBlank { null }) }
                systemTtsEngine = fresh
                clearEngineFatal() // 重新绑定了引擎实例，旧 fatal 不再适用
                fresh.setSpeechRate(speed)
                applyVoiceToEngine()
                if (wasPlaying && paragraphs.isNotEmpty()) {
                    publishState(playing = true)
                    speakJob = scope.launch(Dispatchers.IO) { speakLoop() }
                }
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error(
                        "已切换 TTS 引擎${if (newPkg.isBlank()) "（跟随系统默认）" else "：$newPkg"}",
                        canOpenSettings = false,
                    )
                )
            }
        }
    }

    private suspend fun loadVoicesForEngine(engine: String): List<TtsVoice> {
        return when {
            engine == "edge" -> {
                // 优先远程拉 600+ 多语种音色（带 24h 文件缓存 + 失败回退到硬编码）。
                // EdgeTtsEngine.fetchRemoteVoices 内部已 try/catch，不会抛出，但稳妥
                // 起见再加一层 runCatching —— 任何意外都退到硬编码列表，保证语音设置
                // 至少有 21 个 zh-* 可选。
                runCatching { edgeTtsEngine.fetchRemoteVoices() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: EdgeTtsEngine.VOICES
            }
            engine.startsWith("http_") -> {
                // HttpTts 没有多音色概念——每个源就是一个声音；这里返回单元素，
                // 让 UI 的语音选择器直接显示源名当作唯一可选项。
                val httpEngine = ensureHttpTtsEngine(engine)
                httpEngine?.getVoices() ?: emptyList()
            }
            else -> {
                // Same timeout-bounded init resolution used by speakLoop —
                // prevents the voice picker from hanging forever when the device
                // has no TTS engine bound.
                val sys = ensureSystemTtsEngine()
                when (val initRes = sys.awaitReadyResult()) {
                    is SystemTtsEngine.InitResult.Failed -> {
                        AppLog.warn("TtsHost", "loadVoicesForEngine: ${initRes.reason}")
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                        )
                        emptyList()
                    }
                    SystemTtsEngine.InitResult.Success -> sys.getChineseVoices()
                }
            }
        }
    }

    private suspend fun savedVoiceForEngine(engine: String): String {
        val saved = if (engine == "edge") prefs.ttsEdgeVoice.first() else prefs.ttsSystemVoice.first()
        return saved.ifBlank { prefs.ttsVoice.first() }
    }

    private suspend fun saveVoiceForEngine(engine: String, voice: String) {
        if (engine == "edge") prefs.setTtsEdgeVoice(voice) else prefs.setTtsSystemVoice(voice)
        prefs.setTtsVoice(voice)
    }

    private fun resolveVoiceOrEmpty(voice: String, voices: List<TtsVoice>): String {
        if (voice.isBlank()) return ""
        return voice.takeIf { selected -> voices.any { it.id == selected } } ?: ""
    }

    private fun parseParagraphs(content: String): List<String> {
        val text = content
            .replace(AppPattern.htmlImgRegex, "")
            .replace(AppPattern.htmlSvgRegex, "")
            .replace(AppPattern.htmlDivCloseRegex, "\n")
            .replace(AppPattern.htmlBrRegex, "\n")
            .replace(AppPattern.htmlTagRegex, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        val paras = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
        val skip = skipRegex
        return if (skip != null) paras.filter { !skip.containsMatchIn(it) } else paras
    }

    /**
     * Slice [content] at the supplied paragraph offsets to produce a paragraph
     * list whose size and ordering exactly matches [positions]. Each slice is
     * lightly cleaned (HTML tag strip + entity decode + trim) so the engine
     * speaks readable text, but blank slices are KEPT in place — the speak
     * loop skips them at iteration time so the highlight still advances
     * across silent gaps.
     *
     * Why this exists: when the renderer hands us authoritative offsets via
     * `Command.LoadAndPlay.paragraphPositions`, re-parsing with
     * [parseParagraphs] would drop empty / single-char paragraphs and put the
     * lists out of step with the renderer — making the highlight stick at
     * paragraph 0 because `paragraphPositions.getOrNull(idx)` returns null
     * partway through. Slicing keeps the 1:1 invariant.
     */
    private fun paragraphsFromPositions(content: String, positions: List<Int>): List<String> {
        if (positions.isEmpty()) return emptyList()
        val out = ArrayList<String>(positions.size)
        var orphanFragments = 0
        for (i in positions.indices) {
            val rawStart = positions[i]
            val rawEnd = positions.getOrNull(i + 1) ?: content.length
            val start = rawStart.coerceIn(0, content.length)
            val end = rawEnd.coerceIn(start, content.length)
            var cleaned = content.substring(start, end)
                .replace(AppPattern.htmlImgRegex, "")
                .replace(AppPattern.htmlSvgRegex, "")
                .replace(AppPattern.htmlDivCloseRegex, "\n")
                .replace(AppPattern.htmlBrRegex, "\n")
                .replace(AppPattern.htmlTagRegex, "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
            // 防御性兜底：positions 来自渲染层 stringBuilder（裸 `<img>` 在那边
            // 占 5 字节文本），但 content 是渲染前的原始 HTML —— 两个坐标系
            // 微小漂移会让切片落在 `<img...>` 标签中间，留下 `<img` 这种没有
            // 闭合 `>` 的碎片，所有 htmlImgRegex / htmlTagRegex 都无法匹配，
            // 最后被当成纯文本读出来（即"img src..."这种引擎合成结果）。
            // 见：ReaderViewModel.cleanContentForTts 也做了一道。
            // 检测到 `<` 残留就把它到 slice 末尾全部裁掉 —— 宁可少读半句，
            // 不要让用户听见标签字面读音。
            val orphan = cleaned.indexOf('<')
            if (orphan >= 0) {
                orphanFragments++
                cleaned = cleaned.substring(0, orphan).trimEnd()
            }
            out.add(cleaned)
        }
        if (orphanFragments > 0) {
            AppLog.debug(
                "TtsHost",
                "paragraphsFromPositions: stripped $orphanFragments orphan-tag fragment(s) " +
                    "(positions/content coordinate-space drift)",
            )
        }
        return out
    }

    private fun buildSequentialPositions(paras: List<String>): List<Int> {
        val out = ArrayList<Int>(paras.size)
        var pos = 0
        for (p in paras) { out.add(pos); pos += p.length + 1 }
        return out
    }

    private fun paragraphIndexForPosition(position: Int): Int {
        if (paragraphs.isEmpty() || position <= 0) return 0
        for (i in paragraphPositions.indices) {
            val start = paragraphPositions[i]
            val next = paragraphPositions.getOrNull(i + 1) ?: Int.MAX_VALUE
            if (position in start until next) return i
        }
        return paragraphs.lastIndex.coerceAtLeast(0)
    }

    private suspend fun Job.cancelAndJoin() {
        cancel()
        try { join() } catch (_: CancellationException) {}
    }

    companion object {
        /**
         * ChapterFinished 事件后等待 reader 推送新 LoadAndPlay 的窗口。
         *
         * Phase D 把这个值从 5s 缩到 2s——基于以下事实：
         *  - reader 在前台时 ViewModel collect Event.ChapterFinished → 翻页 →
         *    chapter.chapterContent.collect → tts.ttsPlay 的链路通常 <500ms 完成；
         *  - 用户离开 reader 后 ViewModel 已销毁，等待越久断声越久；
         *  - 超时后由 [tryAutoAdvanceChapter] 用 [chapterContentLoader] 自加载下一章
         *    续播，所以"超时停止"不再是终态——2s 静默是可接受的过渡。
         */
        private const val WAIT_NEXT_CHAPTER_MS = 2_000L

        /** HttpTts 章节级预下载的段数上限（与 Legado preDownloadAudios 的 10 段对齐）。 */
        @Suppress("unused") // 在 [startHttpPreloadNextChapter] VM 桥接打通后会用到
        private const val HTTP_PRELOAD_NEXT_PARAGRAPHS = 10

        /** 长段二次切句阈值（字符数）。<= 80 不切，避免短段被切碎。 */
        private const val LONG_PARA_THRESHOLD = 80

        /**
         * Plan C：Edge 整章合成时段间 `<break>` 标签的毫秒数。
         * 600ms 是经验值：明显长于 Edge 服务端句末 prosody（~250-350ms），低于让人
         * 错觉"已经停下来了"的 800ms。
         */
        private const val EDGE_CHAPTER_BREAK_MS = 600L

        /**
         * [setSpeed] 去抖窗口。Slider 拖动时每个 tick 触发，250ms 内只算最后一次值。
         */
        private const val SPEED_RESTART_DEBOUNCE_MS = 250L

        /** 切句分隔符：中文标点 + 换行（结合 splitIntoSubSentences 使用）。 */
        private val SENTENCE_SEPARATORS = setOf('。', '！', '？', '；', '\n')

        /** 心跳日志间隔。播放态期间每隔这么久打一行 TtsHB；非播放态自动退出循环。 */
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        /**
         * 空闲释放阈值——pause / stopPlayback 后多久 shutdown 系统 TTS 引擎。
         *
         * 60s 是 trade-off：
         *  - 太短（10-30s）：用户接电话 / 临时切走又快速回来时，第一段会看到
         *    init 重建的延迟，破坏连贯性；
         *  - 太长（5min+）：低端机 LMK 会优先杀这种"空闲但持 binder"的进程，
         *    用户回来发现通知栏 TTS 不见了（前台服务被回收）。
         *
         * 60s 覆盖了"接电话"这种典型场景（响铃 + 接听通常在 30-45s 内），
         * 又能在用户真的不打算继续读时及时归还 binder。详见
         * [scheduleIdleRelease] 注释。
         */
        private const val IDLE_RELEASE_MS = 60_000L

        /**
         * 批量播放 watchdog：enqueue 后如果 [BATCH_WATCHDOG_TIMEOUT_MS] 内没有收到
         * 任何 onUtteranceStart，认为引擎回调走丢，触发 reInit + 重播。
         * 值取 10s：正常引擎从 enqueue 到 onStart 通常 ≤500ms，10s 足够宽容。
         */
        private const val BATCH_WATCHDOG_TIMEOUT_MS = 10_000L

        /**
         * 引擎 fatal 冷却期。第一次 [markEngineFatal] 后，[ENGINE_FATAL_COOLDOWN_MS]
         * 内任何播放入口都会被 [shortCircuitIfEngineFatal] 拦下，避免反复触发同样的
         * ANR 路径。30s 是经验值：足够引擎进程被系统重启或用户察觉并切换引擎，
         * 又不会让用户长时间无法使用 TTS。
         */
        private const val ENGINE_FATAL_COOLDOWN_MS = 30_000L
    }
}
