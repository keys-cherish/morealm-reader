package com.morealm.app.presentation.holiday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.holiday.Holiday
import com.morealm.app.domain.holiday.HolidayCatalog
import com.morealm.app.domain.preference.AppPreferences
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
 *  3. 用户点掉后记录 today 到 [AppPreferences.setLastHolidayShownDate]，避免同日多弹
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
) : ViewModel() {

    /** 当前应该显示的节日；null 时不弹窗。点掉后回到 null。 */
    private val _activeHoliday = MutableStateFlow<Holiday?>(null)
    val activeHoliday: StateFlow<Holiday?> = _activeHoliday

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
            _activeHoliday.value = matches.first()
        }
    }

    /** 用户点空白 / 关闭按钮 / 主操作时调，写入今天，弹窗收起。 */
    fun dismiss() {
        _activeHoliday.value = null
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setLastHolidayShownDate(LocalDate.now().toString())
        }
    }
}
