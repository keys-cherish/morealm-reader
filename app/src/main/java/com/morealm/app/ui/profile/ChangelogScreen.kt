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
        version = "v1.0-alpha3",
        date = "2026-05-01",
        title = "自动分组重构 · 多标签 · 自动建文件夹 · Legado 全链路对齐",
        tag = ReleaseTag.LATEST,
        items = listOf(
            // ── 自动分组重构（本次重点） ──
            // P0 数据透传修复
            ChangelogItem(ChangeType.FIX, "搜索结果 SearchResult 透传 kind / wordCount / latestChapter — 网络书加架瞬间不再丢失分类元数据"),
            ChangelogItem(ChangeType.NEW, "EPUB 导入解析 dc:subject 标签并写入 Book.kind — 本地书也能拿到题材信息"),
            // P1 数据结构升级 v17/v18
            ChangelogItem(ChangeType.NEW, "新增 book_tags 多对多表与 tag_definitions 词表，单分组升级为多标签系统（v17 schema）"),
            ChangelogItem(ChangeType.NEW, "Book 实体增加 tagsAssignedBy / groupLocked 字段，区分自动归类与用户手动锁定"),
            ChangelogItem(ChangeType.NEW, "BookGroup 增加 auto 字段，区分自动建组与用户手建组（v18 schema）"),
            ChangelogItem(ChangeType.IMPROVE, "DB v16→v17→v18 迁移：现有 BookGroup 自动迁移为 USER 标签，folderId 镜像为 MANUAL 标签 — 升级零数据丢失"),
            ChangelogItem(ChangeType.NEW, "首启自动播种 15 个内置题材标签（修真 / 玄幻 / 都市 / 言情 等），关键词支持用户编辑"),
            // P2 分类引擎重写
            ChangelogItem(ChangeType.NEW, "TagResolver 5 层评分瀑布替代旧的 first-hit 分类器：用户关键词 → 元数据 → 简介 → 标题 → 来源/格式"),
            ChangelogItem(ChangeType.IMPROVE, "字段权重打分（标题 ×1.5 / kind ×1.3 / 简介 ×0.8）+ 中文词边界检测，避免「军事」误中「军事爱好者后传」"),
            ChangelogItem(ChangeType.IMPROVE, "AutoGroupClassifier 重写为「评分 → 写标签 → 自动建文件夹」三段流水线；每次分类同步写入 book_tags"),
            ChangelogItem(ChangeType.NEW, "自动归类同时写入来源标签（source:起点 / source:番茄 等），按来源聚合零摩擦"),
            // P3 自动建文件夹（取代之前的智能视图）
            ChangelogItem(ChangeType.NEW, "新增 AutoFolderManager：当某题材累计 ≥ 3 本书时自动建立同名文件夹（带 emoji），命中的 AUTO 书自动归入"),
            ChangelogItem(ChangeType.IMPROVE, "用户手建的文件夹（auto = false）永远不会被自动归类引擎改动；MANUAL 放置的书也不会被自动迁移"),
            ChangelogItem(ChangeType.NEW, "删除自动文件夹后记入忽略集（AppPreferences.autoFolderIgnored），下次匹配的题材不再重建该文件夹"),

            // ── 缓存与搜索体验 ──
            // 后台书架刷新（任务 1，Legado MainViewModel.upToc 移植）
            ChangelogItem(ChangeType.NEW, "书架顶部新增「刷新」按钮：后台并发拉取所有 WEB 书目录，发现新章节时显示「N 新」红色角标"),
            ChangelogItem(ChangeType.NEW, "数据库升级 v15→v16：Book 实体新增 lastCheckCount / lastCheckTime / canUpdate 字段（升级前自动备份）"),
            ChangelogItem(ChangeType.NEW, "ShelfRefreshController：固定线程池并发（默认 4），单源失败不影响其他源；状态合并避免双重刷新"),
            ChangelogItem(ChangeType.IMPROVE, "刷新进行中可点击按钮取消，已发起的 fetch 自然结束以避免半状态"),
            ChangelogItem(ChangeType.IMPROVE, "用户点击书本打开时自动清除「N 新」角标（与 Legado 一致）"),

            ChangelogItem(ChangeType.NEW, "缓存页支持「导出 TXT」：选择保存位置后自动导出全部已缓存章节，含书名、作者、简介与 ContentProcessor 处理后的正文"),
            ChangelogItem(ChangeType.NEW, "缓存项操作区改为自适应换行布局：全部缓存 / 从当前章 / 导出 TXT / 清除四按钮"),
            ChangelogItem(ChangeType.NEW, "搜索结果卡片显示「最新章节 / 字数 / 分类」元信息（与 Legado 对齐）"),
            ChangelogItem(ChangeType.IMPROVE, "AnalyzeUrl：搜索 / 详情 / 目录 / 正文 五处统一走 errResponse + loginCheckJs 错误恢复（Legado-parity）"),
            ChangelogItem(ChangeType.IMPROVE, "AnalyzeUrl：cookie 始终注入 — 关闭 enabledCookieJar 不再误丢已登录会话"),
            ChangelogItem(ChangeType.NEW, "AnalyzeUrl：补齐 getErrResponse / getErrStrResponse — 网络异常时 JS 仍能检查响应"),
            ChangelogItem(ChangeType.FIX, "搜索 HTTP 重定向到详情页时 bookUrl 错误：现在使用最终 URL 并复用 body（修单结果重定向源大量打不开）"),
            ChangelogItem(ChangeType.FIX, "晋江文学搜索崩溃：JSONPath 收到 jsoup Element 时 fallback 到空文档，不再 cast 异常"),
            ChangelogItem(ChangeType.FIX, "JSONPath「No results for path」噪音从 warn 降为 debug — 日志量降低约 10×"),
            ChangelogItem(ChangeType.FIX, "正文为空时显示友好占位文本 + 自动展开控制栏，引导用户换源 / 重试"),
            ChangelogItem(ChangeType.FIX, "ContentProcessor：标题首行不再被错误段首缩进；标题去重新增二次匹配（替换后标题）"),
            ChangelogItem(ChangeType.IMPROVE, "WEB 书短按打开优先进入详情页（Legado-parity），本地书保持直进阅读器"),
            ChangelogItem(ChangeType.IMPROVE, "OkHttp 全局信任自签 / 过期证书（SSLHelper），修「Trust anchor not found」一类源失效"),
            ChangelogItem(ChangeType.IMPROVE, "搜索详情页二次请求复用 infoHtml — 减少 ~10% 跨阶段失败率"),

            // ── 关于 / 更新日志页面 ──
            ChangelogItem(ChangeType.NEW, "新增「更新日志」页面：按版本分组展示迭代记录，每项带类型标签（新增 / 修复 / 优化 / 重构）"),
            ChangelogItem(ChangeType.NEW, "关于页面增加「更新日志」入口卡片，可一键跳转查看历史版本变更"),
            ChangelogItem(ChangeType.NEW, "关于页面显示作者署名：光坂镇"),
            ChangelogItem(ChangeType.IMPROVE, "更新日志页支持「最新」与「里程碑」徽章，便于快速识别重要版本"),
        ),
    ),
    ChangelogEntry(
        version = "v1.0-alpha2",
        date = "2026-04-30",
        title = "主题与书源登录",
        items = listOf(
            ChangelogItem(ChangeType.FIX, "修复主题切换动画不一致问题"),
            ChangelogItem(ChangeType.NEW, "加入书源登录（测试功能）"),
            ChangelogItem(ChangeType.FIX, "修复主题超时导致批量超时问题"),
            ChangelogItem(ChangeType.FIX, "修复阅读器内夜间 / 白天按钮主题切换闪烁问题"),
            ChangelogItem(ChangeType.NEW, "支持本地 JSON 导入书源功能"),
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
