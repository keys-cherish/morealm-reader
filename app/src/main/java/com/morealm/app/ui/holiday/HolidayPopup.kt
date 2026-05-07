package com.morealm.app.ui.holiday

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * @param holiday 当天彩蛋节日
 * @param onDismiss 用户点空白 / 「知道了」时调
 * @param onPrimaryAction 「读今日故事」按钮 — 暂时复用 onDismiss，未来可跳推荐书
 */
@Composable
fun HolidayPopup(
    holiday: Holiday,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit = onDismiss,
) {
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
                Text(
                    holiday.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                // 装饰横线 — 用 primary 微弱透明，避免空白感
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
