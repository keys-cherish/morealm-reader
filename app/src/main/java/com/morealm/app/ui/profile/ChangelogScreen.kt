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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    // enterAlwaysScrollBehavior：往下滚顶栏隐藏，反向（往上滚）一指就立刻浮出。
    // 长内容页（更新日志条目多）拿到这个交互后竖屏空间能多还原一行。
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
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
            scrollBehavior = scrollBehavior,
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
        version = "v1.0",
        date = "2026-05-01",
        title = "正式版 · 阅读 / 听书 / 同步全栈完成",
        tag = ReleaseTag.LATEST,
        items = listOf(
            // ── Edge TTS 升级（远程音色 / 鉴权 / 缓存 / 引擎重构） ──
            ChangelogItem(ChangeType.NEW, "Edge TTS 接入远程音色：自动拉取 zh / en / ja / ... 共 600+ 语音，带 24h voices.json 缓存与失败回退到内置音色"),
            ChangelogItem(ChangeType.NEW, "Edge TTS Sec-MS-GEC token 鉴权 + 服务器时钟自校正：根治偶发 401 / 403、长句被切断、握手被拒"),
            ChangelogItem(ChangeType.NEW, "Edge TTS MP3 LRU 缓存（cacheDir/edge_tts/）：重听同段不再走网络"),
            ChangelogItem(ChangeType.NEW, "听书页「语音」区新增刷新按钮：主动失效音色缓存并重新拉取，新装 / 卸载 multiTTS 等引擎后即时生效"),
            ChangelogItem(ChangeType.IMPROVE, "Edge TTS 朗读引擎重构 800+ 行：WSS 通信 + MediaCodec 流式解码，配合本地 LRU 缓存大幅提升朗读响应速度"),

            // ── 阅读器交互（选区菜单 / 物理键 / 仿真翻页） ──
            ChangelogItem(ChangeType.NEW, "选区迷你菜单可自定义：按钮顺序、主行 cap、显示项任选；阅读设置内增编辑入口（SelectionMenuConfig，JSON 持久化）"),
            ChangelogItem(ChangeType.NEW, "物理按键翻页：音量键反向开关 + 长按跳章（off / page / chapter）+ 耳机 / 蓝牙翻页器按键支持（KEYCODE_MEDIA_NEXT/PREV、PAGE_UP/DOWN、DPAD、HEADSETHOOK）"),
            ChangelogItem(ChangeType.IMPROVE, "仿真翻页模式支持长按文字呼出选择菜单（之前几乎不可触发）"),
            ChangelogItem(ChangeType.IMPROVE, "文字选区手柄拖动调整范围时不再闪烁 / 跳跃，可平滑追随手指"),

            // ── 书源管理 ──
            ChangelogItem(ChangeType.NEW, "书源管理页新增分组 chip：不分组 / 按分组名 / 按域名 / 按类型，折叠状态在旋转 / 进程死亡后保留"),

            // ── 备份与同步（WebDav 全面对齐 Legado） ──
            ChangelogItem(ChangeType.NEW, "备份导出新增「选项页」：可勾选要导出的数据类别（书籍 / 书源 / 进度 / 主题 / 阅读样式…），并实时显示压缩后体积，避免因书源等占大头让用户误以为备份失败"),
            ChangelogItem(ChangeType.NEW, "备份导入 / 导出 / WebDav 操作完成时显示 Toast：成功 / 失败 / 失败原因；不再静默失败"),
            ChangelogItem(ChangeType.FIX, "WebDav 上传 zip 现在包含主题与阅读样式 — 修 generateBackupBytes 漏写两字段导致跨设备恢复主题 / 阅读样式静默丢失"),
            ChangelogItem(ChangeType.FIX, "备份 / 恢复 4 个入口加 Mutex 单飞 — 双击或备份恢复并发不再写脏数据库"),
            ChangelogItem(ChangeType.NEW, "「从云端恢复」加二次确认弹窗 — 列出会被覆盖的表，不再单击秒覆盖整库"),
            ChangelogItem(ChangeType.NEW, "WebDav lastModified 字段解析为 epoch 毫秒（RFC 1123），为「最新备份」「进度比对」奠基"),
            ChangelogItem(ChangeType.IMPROVE, "WebDav 屏幕显示备份 / 恢复实时状态（成功 / 失败 / 进行中三色），并去除冗余 saveWebDav；备份 / 恢复在配置未保存时禁用并红字提示"),
            ChangelogItem(ChangeType.FIX, "删除「自动同步」死 UI 项（占位 onClick 无逻辑），后续以真实开关回归"),
            ChangelogItem(ChangeType.NEW, "支持 davs:// / dav:// scheme — 自动重写为 https:// / http://，从其它阅读器导出的链接可直接粘贴"),
            ChangelogItem(ChangeType.IMPROVE, "WebDav 401 错误细化 — 检查 WWW-Authenticate 头，区分「密码错」与「服务器不支持 BasicAuth（仅 Digest / NTLM）」"),
            ChangelogItem(ChangeType.NEW, "WebDav 设备名输入 — 备份文件名追加 _<设备名> 后缀，多设备备份互不覆盖"),
            ChangelogItem(ChangeType.NEW, "「选择备份文件恢复」入口 — 列出云端 backup_*.zip 按时间倒序，可挑历史版本恢复"),
            ChangelogItem(ChangeType.NEW, "五个同步开关：自动备份 / 只保留最新 / 同步阅读进度 / 恢复时跳过本地书 / 恢复时保留本机阅读样式"),
            ChangelogItem(ChangeType.NEW, "BookProgress 进度同步 — 切章节后台 fire-and-forget 上传，App 启动批量拉取并按「时间戳更新 + 进度更前」合并"),
            ChangelogItem(ChangeType.IMPROVE, "进度同步按章节级节流 — 同章节内滚动不再刷网络"),
            ChangelogItem(ChangeType.NEW, "自动备份调度 — App 启动后检查上次备份是否超 24h，是则后台静默上传（开关可关）"),
            ChangelogItem(ChangeType.REFACTOR, "WebDavBackupRunner 单例统一手动按钮与自动调度的备份路径，杜绝代码漂移"),

            // ── 自动分组 / 多标签系统 ──
            ChangelogItem(ChangeType.FIX, "搜索结果 SearchResult 透传 kind / wordCount / latestChapter — 网络书加架瞬间不再丢失分类元数据"),
            ChangelogItem(ChangeType.NEW, "EPUB 导入解析 dc:subject 标签并写入 Book.kind — 本地书也能拿到题材信息"),
            ChangelogItem(ChangeType.NEW, "新增 book_tags 多对多表与 tag_definitions 词表，单分组升级为多标签系统（v17 schema）"),
            ChangelogItem(ChangeType.NEW, "Book 实体增加 tagsAssignedBy / groupLocked 字段，区分自动归类与用户手动锁定"),
            ChangelogItem(ChangeType.NEW, "BookGroup 增加 auto 字段，区分自动建组与用户手建组（v18 schema）"),
            ChangelogItem(ChangeType.IMPROVE, "DB v16→v17→v18 迁移：现有 BookGroup 自动迁移为 USER 标签，folderId 镜像为 MANUAL 标签 — 升级零数据丢失"),
            ChangelogItem(ChangeType.NEW, "首启自动播种 15 个内置题材标签（修真 / 玄幻 / 都市 / 言情 等），关键词支持用户编辑"),
            ChangelogItem(ChangeType.NEW, "TagResolver 5 层评分瀑布替代旧的 first-hit 分类器：用户关键词 → 元数据 → 简介 → 标题 → 来源/格式"),
            ChangelogItem(ChangeType.IMPROVE, "字段权重打分（标题 ×1.5 / kind ×1.3 / 简介 ×0.8）+ 中文词边界检测，避免「军事」误中「军事爱好者后传」"),
            ChangelogItem(ChangeType.IMPROVE, "AutoGroupClassifier 重写为「评分 → 写标签 → 自动建文件夹」三段流水线；每次分类同步写入 book_tags"),
            ChangelogItem(ChangeType.NEW, "自动归类同时写入来源标签（source:起点 / source:番茄 等），按来源聚合零摩擦"),
            ChangelogItem(ChangeType.NEW, "新增 AutoFolderManager：当某题材累计 ≥ 3 本书时自动建立同名文件夹（带 emoji），命中的 AUTO 书自动归入"),
            ChangelogItem(ChangeType.IMPROVE, "用户手建的文件夹（auto = false）永远不会被自动归类引擎改动；MANUAL 放置的书也不会被自动迁移"),
            ChangelogItem(ChangeType.NEW, "删除自动文件夹后记入忽略集（AppPreferences.autoFolderIgnored），下次匹配的题材不再重建该文件夹"),

            // ── 后台书架刷新 / 缓存导出 ──
            ChangelogItem(ChangeType.NEW, "书架顶部新增「刷新」按钮：后台并发拉取所有 WEB 书目录，发现新章节时显示「N 新」红色角标"),
            ChangelogItem(ChangeType.NEW, "数据库升级 v15→v16：Book 实体新增 lastCheckCount / lastCheckTime / canUpdate 字段（升级前自动备份）"),
            ChangelogItem(ChangeType.NEW, "ShelfRefreshController：固定线程池并发（默认 4），单源失败不影响其他源；状态合并避免双重刷新"),
            ChangelogItem(ChangeType.IMPROVE, "刷新进行中可点击按钮取消，已发起的 fetch 自然结束以避免半状态"),
            ChangelogItem(ChangeType.IMPROVE, "用户点击书本打开时自动清除「N 新」角标（与 Legado 一致）"),
            ChangelogItem(ChangeType.NEW, "缓存页支持「导出 TXT」：选择保存位置后自动导出全部已缓存章节，含书名、作者、简介与 ContentProcessor 处理后的正文"),
            ChangelogItem(ChangeType.NEW, "缓存项操作区改为自适应换行布局：全部缓存 / 从当前章 / 导出 TXT / 清除四按钮"),

            // ── 搜索 / 书源引擎 Legado 对齐 ──
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

            // ── 关于 / 更新日志页 ──
            ChangelogItem(ChangeType.NEW, "新增「更新日志」页面：按版本分组展示迭代记录，每项带类型标签（新增 / 修复 / 优化 / 重构）"),
            ChangelogItem(ChangeType.NEW, "关于页面增加「更新日志」入口卡片，可一键跳转查看历史版本变更"),
            ChangelogItem(ChangeType.NEW, "关于页面显示作者署名：光坂镇"),
            ChangelogItem(ChangeType.IMPROVE, "更新日志页支持「最新」与「里程碑」徽章，便于快速识别重要版本"),

            // ── 书源 JSON 导入 ──
            ChangelogItem(ChangeType.FIX, "JSON 粘贴 / 直接粘到导入框现可识别 — 之前 BookSourceImporter 把所有解析错误吞掉只剩一句「未识别到有效书源」无任何线索"),
            ChangelogItem(ChangeType.NEW, "导入器支持 Legado 类常见 JSON 包装：{\"sources\":[…]} / {\"bookSources\":[…]} / {\"data\":[…]} / {\"list\":[…]} / {\"items\":[…]} 自动解包"),
            ChangelogItem(ChangeType.IMPROVE, "导入失败时把首条解析异常透到 UI（如「期望 String 但收到 Number」），用户能立刻定位坏字段"),
            ChangelogItem(ChangeType.IMPROVE, "数组整体解码失败回退逐项解析 — 一个坏书源不再导致整批 0 导入"),
            ChangelogItem(ChangeType.FIX, "剪贴板粘贴导入按钮在 Android 12+ 偶尔为空 — 改用 Android 原生 ClipboardManager 替代 Compose LocalClipboardManager；并按内容前缀自动分流：[/{ 走 JSON、http(s):// 走 URL、其它给出明确「不识别」提示"),
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
