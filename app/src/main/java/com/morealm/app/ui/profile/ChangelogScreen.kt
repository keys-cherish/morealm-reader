package com.morealm.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Changelog page — shows the project's release history grouped by version.
 * Entries are kept in code (not parsed from git) so we can curate user-facing
 * descriptions instead of leaking internal commit phrasing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("更新日志", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(CHANGELOG, key = { it.version }) { entry ->
                ChangelogCard(entry)
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ChangelogCard(entry: ChangelogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Version badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        entry.version,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    entry.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                if (entry.tag != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(entry.tag.color.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            entry.tag.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = entry.tag.color,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            if (entry.title.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            entry.items.forEach { item ->
                ChangelogItemRow(item)
            }
        }
    }
}

@Composable
private fun ChangelogItemRow(item: ChangelogItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Type chip
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(item.type.color.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                item.type.label,
                style = MaterialTheme.typography.labelSmall,
                color = item.type.color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Data models ──────────────────────────────────────────────────────────

private enum class ChangeType(val label: String, val color: Color) {
    NEW("新增", Color(0xFF2E7D32)),
    FIX("修复", Color(0xFFD84315)),
    IMPROVE("优化", Color(0xFF1565C0)),
    REFACTOR("重构", Color(0xFF6A1B9A)),
}

private data class ReleaseTag(val label: String, val color: Color) {
    companion object {
        val LATEST = ReleaseTag("最新", Color(0xFFE65100))
        val MILESTONE = ReleaseTag("里程碑", Color(0xFF00695C))
    }
}

private data class ChangelogItem(val type: ChangeType, val text: String)

private data class ChangelogEntry(
    val version: String,
    val date: String,
    val title: String = "",
    val tag: ReleaseTag? = null,
    val items: List<ChangelogItem>,
)

// ── Changelog content ────────────────────────────────────────────────────

private val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        version = "v1.0.0",
        date = "2026-04-30",
        title = "首个正式版本",
        tag = ReleaseTag.LATEST,
        items = listOf(
            ChangelogItem(ChangeType.NEW, "搜索结果卡片增加最新章节、字数、类型标签展示"),
            ChangelogItem(ChangeType.IMPROVE, "Legado 书源兼容性大幅增强：errResponse、loginCheckJs、JSONPath jsoup-Element 等多项对齐"),
            ChangelogItem(ChangeType.FIX, "搜索结果空内容、重定向、JSONPath 解析等场景的稳定性修复"),
            ChangelogItem(ChangeType.NEW, "正文处理器：标题缩进修复 + 二级标题去重"),
            ChangelogItem(ChangeType.NEW, "网络书优先从详情页路由 + AnalyzeUrl 的 cookie / errResponse 行为对齐"),
        ),
    ),
    ChangelogEntry(
        version = "v0.9.5",
        date = "2026-04-22",
        title = "书源引擎稳定性",
        items = listOf(
            ChangelogItem(ChangeType.NEW, "完整实现真正的换源功能"),
            ChangelogItem(ChangeType.FIX, "JS / SSL / 分组等多个长期遗留问题"),
            ChangelogItem(ChangeType.IMPROVE, "BookChapterList / BookContent / BookInfo 在解析失败时不再崩溃"),
            ChangelogItem(ChangeType.NEW, "移植 Legado CookieManager：通过 OkHttp 拦截器自动持久化登录会话"),
            ChangelogItem(ChangeType.IMPROVE, "搜索流程对 JSONPath / JS 规则失败的容错"),
        ),
    ),
    ChangelogEntry(
        version = "v0.9.0",
        date = "2026-04-12",
        title = "TTS 与导入体验",
        items = listOf(
            ChangelogItem(ChangeType.NEW, "TTS 通知栏增加「+10 分钟」睡眠按钮和倒计时显示"),
            ChangelogItem(ChangeType.NEW, "WebView 登录支持纯 URL 类型书源"),
            ChangelogItem(ChangeType.NEW, "未配置 loginUi 时提供默认登录表单"),
            ChangelogItem(ChangeType.NEW, "支持本地文件批量导入书源"),
            ChangelogItem(ChangeType.FIX, "文件选择器 MIME 过滤匹配 JSON 导入"),
        ),
    ),
    ChangelogEntry(
        version = "v0.8.5",
        date = "2026-04-04",
        title = "阅读器体验优化",
        items = listOf(
            ChangelogItem(ChangeType.NEW, "书源登录功能（兼容 Legado）"),
            ChangelogItem(ChangeType.NEW, "阅读器右下角增加日 / 夜模式悬浮切换按钮"),
            ChangelogItem(ChangeType.FIX, "章节切换闪烁问题：通过 prelayout 缓存校验解决"),
            ChangelogItem(ChangeType.FIX, "仿真翻页向前翻页：当前页正确卷起以露出上一页"),
            ChangelogItem(ChangeType.FIX, "前一章节导航从最后一页开始而非第一页"),
        ),
    ),
    ChangelogEntry(
        version = "v0.8.0",
        date = "2026-03-25",
        title = "TTS 通知栏与背景图",
        tag = ReleaseTag.MILESTONE,
        items = listOf(
            ChangelogItem(ChangeType.NEW, "升级 TTS 通知栏：自定义 MediaStyle，含书名、章节、封面与四按钮控制"),
            ChangelogItem(ChangeType.NEW, "支持日 / 夜模式下分别设置阅读器背景图（带缩略图预览）"),
            ChangelogItem(ChangeType.NEW, "滚动模式新增上下边缘 alpha 羽化"),
            ChangelogItem(ChangeType.FIX, "仿真翻页带背景图时不再出现深色横条"),
            ChangelogItem(ChangeType.FIX, "滚动模式背景层独立绘制，DstOut 只擦文字"),
        ),
    ),
    ChangelogEntry(
        version = "v0.7.0",
        date = "2026-03-15",
        title = "数据安全与基础架构",
        items = listOf(
            ChangelogItem(ChangeType.FIX, "P0 级数据库数据丢失问题：移除破坏性降级，引入升级前自动备份与恢复"),
            ChangelogItem(ChangeType.IMPROVE, "书源引擎错误防护：try-catch 包裹 JS 求值、安全类型转换"),
            ChangelogItem(ChangeType.NEW, "三个 HTML 宣传页"),
            ChangelogItem(ChangeType.IMPROVE, "用户指南 + 文档清理 + README 精简"),
        ),
    ),
    ChangelogEntry(
        version = "v0.6.0",
        date = "2026-03-05",
        title = "导航与主题",
        items = listOf(
            ChangelogItem(ChangeType.IMPROVE, "重新设计导航栏：浮动药丸样式 + 视觉打磨"),
            ChangelogItem(ChangeType.FIX, "Profile 页 TopAppBar 主题切换不再突兀"),
            ChangelogItem(ChangeType.FIX, "药丸导航栏底部不再遮挡内容"),
            ChangelogItem(ChangeType.NEW, "切换为 GPL-3.0 + 商业双许可证"),
        ),
    ),
    ChangelogEntry(
        version = "v0.5.0",
        date = "2026-02-20",
        title = "架构重构",
        tag = ReleaseTag.MILESTONE,
        items = listOf(
            ChangelogItem(ChangeType.REFACTOR, "ReaderViewModel 拆分为 6 个 Controller（1349 → 330 行）"),
            ChangelogItem(ChangeType.NEW, "PageTurnCoordinator 接入 CanvasRenderer"),
            ChangelogItem(ChangeType.IMPROVE, "语义化重命名 + 关键诊断日志 + OOM 防护"),
            ChangelogItem(ChangeType.FIX, "AppLogScreen 中 LazyColumn 重复 key 崩溃"),
            ChangelogItem(ChangeType.NEW, "LogRecord 自增 id 与 hashCode key pre-commit 检查"),
        ),
    ),
)
