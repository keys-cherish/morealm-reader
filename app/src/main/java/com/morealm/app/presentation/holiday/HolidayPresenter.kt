package com.morealm.app.presentation.holiday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.holiday.Holiday
import com.morealm.app.domain.holiday.HolidayCatalog
import com.morealm.app.domain.holiday.greeting.GreetingContext
import com.morealm.app.domain.holiday.greeting.GreetingEngine
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 节日彩蛋调度器。负责：
 *
 *  1. 启动时（首次进入主页）查今天是不是节日 + 上次弹过的日期
 *  2. 决定是否显示彩蛋（today 是节日 && 不是 lastShownDate）
 *  3. 用 DMRG 引擎生成"个性化句"（错峰预计算 + 渐进式注水）
 *  4. 用户点掉后记录 today 到 [AppPreferences.setLastHolidayShownDate]，避免同日多弹
 *
 * ## 渐进式注水流程（首屏零阻塞）
 *
 *  - **0 ms（合成阶段）**：`activeHoliday` 置为 Holiday，`greetingText` 取 DataStore
 *    `cachedHolidayGreeting`。若缓存 key 不指向今天/当前节日则用 `Holiday.message`
 *    兜底文案 —— UI 第 0 帧永远有句子。
 *  - **后台 50-200 ms**：协程在 IO 上跑 [GreetingContext.build] + [GreetingEngine.generate]，
 *    重算的新句和 cached 不同时把 `greetingText` 替换 + 写回 DataStore。
 *  - **UI 端**：[com.morealm.app.ui.holiday.HolidayPopup] 用 `AnimatedContent` 把
 *    fallback→精细句的切换做成 200 ms 淡入，用户视觉上"句子在呼吸生成"。
 *
 * 设计原则：当天逻辑只执行一次（[checkOnce] 守卫），切换 tab、配置改变都不重弹。
 *
 * ## 关于 `LocalDate.now()` 不暴露为默认参数
 * Android 8 以下走 core-library-desugar 的 `j$.time.*`，首次 `LocalDate.now()`
 * 要触发 `ZoneRulesProvider.getRules` 冷启动解 tzdb —— 实测在老设备上可阻塞 8s+
 * 直接 ANR（stack: `DesugarTimeZone.toZoneId`）。因此 today 一律在 IO 协程里取，
 * 绝不要以默认参数形式让它在调用方（Compose / click handler）线程求值。
 */
@HiltViewModel
class HolidayPresenter @Inject constructor(
    private val prefs: AppPreferences,
    private val bookRepo: BookRepository,
    private val statsRepo: ReadStatsRepository,
) : ViewModel() {

    /** 当前应该显示的节日；null 时不弹窗。点掉后回到 null。 */
    private val _activeHoliday = MutableStateFlow<Holiday?>(null)
    val activeHoliday: StateFlow<Holiday?> = _activeHoliday

    /**
     * 当前弹窗显示的句子。
     *  - null：弹窗未激活
     *  - 非 null 但等于 Holiday.message：注水尚未完成的过渡态
     *  - 非 null 且来自 [GreetingEngine]：DMRG 个性化句
     */
    private val _greetingText = MutableStateFlow<String?>(null)
    val greetingText: StateFlow<String?> = _greetingText

    /** 防多次：本进程内只查一次。configChange / 进出 NavHost 都不重弹。 */
    @Volatile private var checkedThisSession = false

    fun checkOnce() {
        if (checkedThisSession) return
        checkedThisSession = true
        viewModelScope.launch {
            val today = withContext(Dispatchers.IO) { LocalDate.now() }
            val lastShown = prefs.lastHolidayShownDate.first()
            if (lastShown == today.toString()) return@launch
            val matches = withContext(Dispatchers.IO) { HolidayCatalog.holidaysOn(today) }
            if (matches.isEmpty()) return@launch
            val h = matches.first()

            // 第一道防线：DataStore 同步发兜底 / 上次缓存句 —— Compose 第 0 帧即可渲染
            val cachedText = prefs.cachedHolidayGreeting.first()
            val cachedKey = prefs.cachedHolidayGreetingKey.first()
            val cachedIsForToday = cachedKey.startsWith("${today}|${h.id}|")
            _greetingText.value = if (cachedIsForToday && cachedText.isNotEmpty()) {
                cachedText
            } else {
                h.message
            }
            _activeHoliday.value = h

            // 第二道防线：IO 协程异步算 DMRG 句 + 注水。和 UI 渲染完全并行，零阻塞。
            launch(Dispatchers.IO) {
                val ctx = GreetingContext.build(h, today, bookRepo, statsRepo)
                val freshKey = composeCacheKey(ctx)
                if (freshKey == cachedKey && cachedText.isNotEmpty()) return@launch
                val fresh = GreetingEngine.generate(ctx)
                prefs.setCachedHolidayGreeting(fresh, freshKey)
                _greetingText.value = fresh
            }
        }
    }

    /** 用户点空白 / 关闭按钮 / 主操作时调，写入今天，弹窗收起。 */
    fun dismiss() {
        _activeHoliday.value = null
        _greetingText.value = null
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setLastHolidayShownDate(LocalDate.now().toString())
        }
    }

    /**
     * 缓存键 — 同样的 (date, holidayId, bookTitle, 阅读分钟桶, hourOfDay) 视为同句。
     * hourOfDay 必须入键：DMRG seed 不含它但 B 桶选择含 —— 跨小时桶切换时句子会变。
     */
    private fun composeCacheKey(ctx: GreetingContext): String = buildString {
        append(ctx.date.toString()).append('|')
        append(ctx.holidayId).append('|')
        append(ctx.bookTitle?.hashCode()?.toString(16) ?: "_").append('|')
        append(ctx.todayMinutes / 10).append('|')
        append(ctx.hourOfDay)
    }
}
