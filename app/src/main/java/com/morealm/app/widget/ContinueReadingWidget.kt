package com.morealm.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.GlanceTheme
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.morealm.app.R
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「继续阅读」桌面小组件主体。
 *
 * 设计要点：
 *  - **数据获取**：Glance 的 [provideGlance] 是 suspend 函数，用 Hilt
 *    [EntryPointAccessors] 取 [BookRepository] / [ReadStatsRepository]，一次性
 *    拉到当前最新阅读的书 + 今日读书时长，无需常驻 Worker。
 *  - **多尺寸**：[SizeMode.Responsive] 配两个目标尺寸；小尺寸（紧凑条）只显示
 *    书名 + 进度，大尺寸再加章节信息和今日时长。两个布局都共享相同点击行为。
 *  - **点击行为**：[actionStartActivity] 复用 `MainActivity` 的
 *    `com.morealm.app.CONTINUE_READING` intent-filter，无需新增 Activity；和
 *    现有「继续阅读」长按快捷方式走完全相同的代码路径，避免逻辑分叉。
 *  - **空状态**：取不到上次阅读的书时，仍显示一个引导卡片，点击打开主入口；
 *    避免出现"小组件死掉"的观感。
 *  - **主题**：通过 [GlanceTheme] 取 Material3 token；浅/深色由系统自动驱动。
 *  - **刷新**：本 widget 故意不实现 onUpdate 轮询。刷新由
 *    [WidgetUpdater.refresh] 在 ReaderViewModel.onCleared 时主动 push。
 *    更新周期已在 widget_continue_reading_info.xml 中设为 0（禁用系统轮询），
 *    符合"零耗电"目标。
 */
class ContinueReadingWidget : GlanceAppWidget() {

    companion object {
        // 大尺寸 4×2：完整卡片（书名 + 章节 + 进度 + 今日时长）
        private val SIZE_LARGE = DpSize(250.dp, 110.dp)

        // 小尺寸 4×1：紧凑条（书名 + 进度，省去今日时长行）
        private val SIZE_SMALL = DpSize(180.dp, 60.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SIZE_SMALL, SIZE_LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 在 provideGlance 里做异步数据加载 — 它本身就是 suspend，Glance 会等
        // 我们把 provideContent 调到位再渲染，所以不用担心闪烁。
        val data = loadWidgetData(context)

        provideContent {
            GlanceTheme {
                WidgetContent(data)
            }
        }
    }

    private suspend fun loadWidgetData(context: Context): WidgetData {
        return runCatching {
            val entry = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
            val book = entry.bookRepository().getLastReadBook().first()
            // 今日时长：直接按今天 yyyy-MM-dd 查 ReadStats，命中就用，否则当 0。
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val todayMs = entry.readStatsRepository().getByDate(today)?.readDurationMs ?: 0L
            WidgetData(book = book, todayReadMs = todayMs)
        }.getOrElse {
            // Hilt / DB / 任何异常都不能让 widget 直接崩。降级为空状态。
            WidgetData(book = null, todayReadMs = 0L)
        }
    }
}

/** Hilt EntryPoint：让 Glance（非 Activity/ViewModel）也能拿到注入对象。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun bookRepository(): BookRepository
    fun readStatsRepository(): ReadStatsRepository
}

/** AppWidgetProvider 入口 — 系统通过这个 receiver 找到我们的 GlanceAppWidget。
 *  Receiver 类定义在独立文件 [ContinueReadingWidgetReceiver]，让 manifest-class-check
 *  hook 按"短名 → 文件路径"约定能找到。 */

/** 渲染数据快照。封装两个字段是为了让 [WidgetContent] 测试更直观。 */
internal data class WidgetData(
    val book: Book?,
    val todayReadMs: Long,
)

@Composable
private fun WidgetContent(data: WidgetData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val isCompact = size.height < 90.dp

    // 整卡点击 -> 复用 MainActivity 的 CONTINUE_READING intent-filter。
    // 显式设置 ComponentName 避免 Android 在多 task 场景下找不到 target。
    val openIntent = Intent("com.morealm.app.CONTINUE_READING").apply {
        component = ComponentName(context.packageName, "com.morealm.app.ui.navigation.MainActivity")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(openIntent)),
    ) {
        val book = data.book
        if (book == null) {
            EmptyState()
        } else if (isCompact) {
            CompactBookRow(book)
        } else {
            FullBookCard(book = book, todayReadMs = data.todayReadMs)
        }
    }
}

@Composable
private fun EmptyState() {
    Text(
        text = LocalContext.current.getString(R.string.widget_empty_title),
        style = TextStyle(
            color = GlanceTheme.colors.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(modifier = GlanceModifier.height(6.dp))
    Text(
        text = LocalContext.current.getString(R.string.widget_empty_subtitle),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 12.sp,
        ),
    )
}

@Composable
private fun CompactBookRow(book: Book) {
    Text(
        text = book.title,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(modifier = GlanceModifier.height(6.dp))
    ProgressBar(progress = book.progressFraction())
    Spacer(modifier = GlanceModifier.height(4.dp))
    Text(
        text = book.chapterLabel(),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun FullBookCard(book: Book, todayReadMs: Long) {
    Text(
        text = LocalContext.current.getString(R.string.widget_continue_reading_title),
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(modifier = GlanceModifier.height(6.dp))
    Text(
        text = book.title,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(modifier = GlanceModifier.height(2.dp))
    Text(
        text = book.chapterLabel(),
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
        ),
    )
    Spacer(modifier = GlanceModifier.height(8.dp))
    ProgressBar(progress = book.progressFraction())
    Spacer(modifier = GlanceModifier.height(6.dp))
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = LocalContext.current.getString(
                R.string.widget_today_read_format,
                formatDuration(todayReadMs),
            ),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(
            text = "${(book.progressFraction() * 100).toInt()}%",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    LinearProgressIndicator(
        progress = progress.coerceIn(0f, 1f),
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

private fun Book.progressFraction(): Float {
    // 优先用 Book.readProgress（已在 0..1 区间）；fallback 到 chapter ratio
    if (readProgress > 0f) return readProgress.coerceIn(0f, 1f)
    if (totalChapters > 0) {
        return (lastReadChapter.toFloat() / totalChapters).coerceIn(0f, 1f)
    }
    return 0f
}

private fun Book.chapterLabel(): String {
    return if (totalChapters > 0) {
        "第 ${lastReadChapter + 1} / $totalChapters 章"
    } else {
        "第 ${lastReadChapter + 1} 章"
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        minutes < 1 -> "0 分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}
