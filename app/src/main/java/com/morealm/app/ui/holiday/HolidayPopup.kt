package com.morealm.app.ui.holiday

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.holiday.Holiday

/**
 * 节日彩蛋弹窗。
 *
 * 设计：
 *  - Material3 [AlertDialog] 一句话祝福 + 「读今日故事」/「知道了」两按钮
 *  - 圆角 28dp，配色随主题（不强行节日红绿，避免与 ReaderTheme 冲突）
 *  - 不放图片资源 — 走文字优先策略，未来再叠插图也好叠
 *  - 同时多个节日匹配时（如愚人节 ∩ 清明）只显示传入的那个；上层 [HolidayCatalog]
 *    返回 List 由 caller 选第一个
 *
 * ## DMRG 注水过渡
 * `messageText` 由 [com.morealm.app.presentation.holiday.HolidayPresenter] 提供，
 * 启动 0 ms 时可能是 [Holiday.message] 兜底，后台异步算出 DMRG 个性化句后
 * StateFlow 推新值进来。[AnimatedContent] 用 220ms 淡入 / 110ms 淡出过渡，
 * 用户看到的是「文字呼吸式」自然替换，而不是闪动。
 *
 * @param holiday 当天彩蛋节日
 * @param messageText 弹窗正文；为空时回退到 [Holiday.message]
 * @param onDismiss 用户点空白 / 「知道了」时调
 * @param onPrimaryAction 「读今日故事」按钮 — 暂时复用 onDismiss，未来可跳推荐书
 */
@Composable
fun HolidayPopup(
    holiday: Holiday,
    messageText: String? = null,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit = onDismiss,
) {
    val text = messageText?.takeIf { it.isNotBlank() } ?: holiday.message
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                holiday.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column {
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = 220,
                                easing = LinearOutSlowInEasing,
                            ),
                        ) togetherWith fadeOut(animationSpec = tween(durationMillis = 110))
                    },
                    label = "HolidayGreetingTransition",
                ) { current ->
                    Text(
                        current,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "—— MoRealm 与你共度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onPrimaryAction) {
                Text("继续阅读")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
    )
}
