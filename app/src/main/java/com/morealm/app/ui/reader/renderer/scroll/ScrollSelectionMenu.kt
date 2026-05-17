package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.render.scroll.ScrollChapterLayout
import com.morealm.app.domain.render.scroll.findColumnAt

/**
 * 选区菜单 8 项动作 —— 长按文字后选区菜单显示在选区下方。
 *
 * 8 项严格对齐旧 [com.morealm.app.ui.reader.renderer.LazyScrollRenderer] 路径下
 * SelectionMenu 的菜单项：
 *   1. 复制 / 2. 翻译 / 3. 词典 / 4. 朗读到末
 *   5. 高亮（KIND_BG）/ 6. 字体色（KIND_TEXT_COLOR）/ 7. 下划线（KIND_UNDERLINE）/ 8. 橡皮
 *
 * callback 通过 [ScrollSelectionAction] 枚举返回；调用方（ViewModel / Screen）按
 * action 执行：复制走 Clipboard；翻译走 openTranslate；高亮等走 Highlight DB 写。
 */
enum class ScrollSelectionAction {
    COPY,
    TRANSLATE,
    DICTIONARY,
    READ_ALOUD,
    HIGHLIGHT,
    TEXT_COLOR,
    UNDERLINE,
    ERASE,
}

/**
 * 显示选区菜单 —— 位置在选区底部下方 16px（避免遮挡选区文字）。
 *
 * 不显示 case：
 *   - selection inactive
 *   - selection.chapterIndex 与 layout 不匹配（跨章选区）
 *   - 反查 endCp 失败（极端 case）
 *
 * @param onAction 用户点击某菜单项时回调（action 枚举）
 */
@Composable
fun ScrollSelectionMenu(
    selection: ScrollSelectionState,
    layout: ScrollChapterLayout,
    pixelOffsetProvider: () -> Float,
    onAction: (ScrollSelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!selection.isActive) return
    if (selection.chapterIndex != layout.chapterIndex) return

    val cpRange = selection.cpRange
    val endHit = layout.findColumnAt(cpRange.last) ?: return

    // 计算菜单 view-local y：选区末 line lineBottom + 16px - pixelOffset
    val endLineBottomYInChapter = computeLineBottomYInChapterPublic(layout, cpRange.last)
    val menuYInView = (endLineBottomYInChapter - pixelOffsetProvider() + 16f).toInt()

    Box(
        modifier.offset { IntOffset(x = 16, y = menuYInView) },
    ) {
        Row(
            modifier = Modifier
                .shadow(elevation = 4.dp, shape = RoundedCornerShape(8.dp))
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            MenuItem("复制") { onAction(ScrollSelectionAction.COPY) }
            MenuItem("翻译") { onAction(ScrollSelectionAction.TRANSLATE) }
            MenuItem("词典") { onAction(ScrollSelectionAction.DICTIONARY) }
            MenuItem("朗读") { onAction(ScrollSelectionAction.READ_ALOUD) }
            MenuItem("高亮") { onAction(ScrollSelectionAction.HIGHLIGHT) }
            MenuItem("字色") { onAction(ScrollSelectionAction.TEXT_COLOR) }
            MenuItem("下划线") { onAction(ScrollSelectionAction.UNDERLINE) }
            MenuItem("橡皮") { onAction(ScrollSelectionAction.ERASE) }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 反查 cp 所在 line 的 lineBottom y（章内累计 y，含 pageOffsetY）。
 * public 版本供同包内 [ScrollSelectionMenu] 用；与 ScrollSelectionOverlay 内同名
 * private 函数算法等价。M5 阶段可统一抽到 layout extension。
 */
internal fun computeLineBottomYInChapterPublic(layout: ScrollChapterLayout, cp: Int): Float {
    var pageOffsetY = 0f
    for (page in layout.pages) {
        for (line in page.lines) {
            if (line.containsChapterPos(cp)) {
                return pageOffsetY + line.lineBottom
            }
        }
        pageOffsetY += page.height
    }
    return 0f
}
