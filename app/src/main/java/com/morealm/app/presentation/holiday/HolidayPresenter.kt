package com.morealm.app.presentation.holiday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.holiday.Holiday
import com.morealm.app.domain.holiday.HolidayCatalog
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 节日彩蛋调度器。负责：
 *
 *  1. 启动时（首次进入主页）查今天是不是节日 + 上次弹过的日期
 *  2. 决定是否显示彩蛋（today 是节日 && 不是 lastShownDate）
 *  3. 用户点掉后记录 today 到 [AppPreferences.setLastHolidayShownDate]，避免同日多弹
 *
 * 设计原则：当天逻辑只执行一次（[checkOnce] 守卫），切换 tab、配置改变都不重弹。
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

    fun checkOnce(today: LocalDate = LocalDate.now()) {
        if (checkedThisSession) return
        checkedThisSession = true
        viewModelScope.launch {
            val lastShown = prefs.lastHolidayShownDate.first()
            if (lastShown == today.toString()) return@launch
            val matches = HolidayCatalog.holidaysOn(today)
            if (matches.isEmpty()) return@launch
            _activeHoliday.value = matches.first()
        }
    }

    /** 用户点空白 / 关闭按钮 / 主操作时调，写入今天，弹窗收起。 */
    fun dismiss(today: LocalDate = LocalDate.now()) {
        _activeHoliday.value = null
        viewModelScope.launch {
            prefs.setLastHolidayShownDate(today.toString())
        }
    }
}
