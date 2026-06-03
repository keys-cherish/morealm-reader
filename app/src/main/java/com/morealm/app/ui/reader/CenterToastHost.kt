package com.morealm.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 阅读器统一瞬时反馈（居中、短时、自动消失）的状态持有器。
 *
 * 为什么不用系统 [android.widget.Toast]：Android 11+ 对文本 Toast 的 `setGravity` 已失效，
 * 无法居中；且 Reader 内浮层多，需要一个与现有浮层零撞位、随手可调的统一入口。
 *
 * 用 [token] 而非仅靠 [message] 驱动计时：连续触发同一/不同消息时，token 自增让宿主的
 * `LaunchedEffect` 重启，**重置消失计时**（最新消息为准，不堆叠、不提前消失）。
 */
@Stable
class CenterToastState {
    internal var message by mutableStateOf<String?>(null)
        private set
    internal var token by mutableStateOf(0)
        private set

    /** 触发一次居中反馈。连续调用以最新消息为准并重置消失计时；空串忽略。 */
    fun show(text: String) {
        if (text.isBlank()) return
        message = text
        token++
    }

    internal fun clear() { message = null }
}

@Composable
fun rememberCenterToastState(): CenterToastState = remember { CenterToastState() }

/**
 * 居中反馈浮层。挂在阅读器根 [androidx.compose.foundation.layout.Box]（fillMaxSize）顶层。
 *
 * - **只在屏幕正中放一小块**，不铺全屏透明层 → 显示时也不拦翻页 / 选区 / tap（Surface 无
 *   onClick，不消费 pointer，事件透传到下层）。
 * - [displayText] 锁住最后一次的文本：消失时把 [CenterToastState.message] 置空触发退场动画，
 *   但 content 仍读 [displayText]，避免淡出过程中文字突然变空。
 */
@Composable
fun BoxScope.CenterToastHost(
    state: CenterToastState,
    durationMillis: Long = 1500L,
) {
    var displayText by remember { mutableStateOf("") }
    LaunchedEffect(state.token) {
        val msg = state.message
        if (state.token > 0 && msg != null) {
            displayText = msg
            delay(durationMillis)
            state.clear()
        }
    }
    AnimatedVisibility(
        visible = state.message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Surface(
            color = Color(0xD9000000),  // 黑底 ~85%，日 / 夜阅读主题下均清晰，独立于 readerTheme
            contentColor = Color.White,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = displayText,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}
