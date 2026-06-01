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
        version = "v1.6",
        date = "2026-06-01",
        title = "EPUB 富排版增强 + 远程书架层级浏览",
        tag = ReleaseTag.LATEST,
        items = listOf(
            ChangelogItem(ChangeType.NEW, "搜索改为「先看后加」—— 点搜索结果先进入书籍详情页（可直接开始阅读试看），不再一点就自动进书架；想收藏点「加入书架」才进，看过不要的不留痕迹"),
            ChangelogItem(ChangeType.NEW, "远程书架（WebDAV）层级浏览 —— 远程书架可以按文件夹一层层点进去看，勾选要的书再导入，不用一次性拉全部"),
            ChangelogItem(ChangeType.NEW, "EPUB 字符级背景色 —— 「内容简介」这种每个字带独立彩色块的艺术标题现在能正确显示（彩色方块 + 白字）"),
            ChangelogItem(ChangeType.FIX, "正文偶发显示成书内特殊字体修复 —— 部分 EPUB 正文偶尔被画成书里自带的艺术 / 子集字体（字忽大忽小、跨章变化），现在正文稳定使用阅读字体"),
            ChangelogItem(ChangeType.FIX, "部分电子书系统字体声明不生效修复 —— 一些书用「宋体 / 楷体 / 黑体 / 仿宋」等系统字体声明的文字之前没生效退成默认，现在正确回退到对应系统字体"),
            ChangelogItem(ChangeType.FIX, "嵌入字体偶发加载失败修复 —— 书内嵌字体偶尔只加载一半 / 失败，现在自动重试 + 兜底"),
            ChangelogItem(ChangeType.FIX, "夜间模式部分电子书正文不变白修复 —— 之前正文颜色被写死成黑色、夜间贴深色背景几乎看不清，现在跟随夜间主题自动变浅"),
            ChangelogItem(ChangeType.FIX, "特定节日打开偶发闪退修复 —— 个别节日打开 App 时节日问候卡片可能闪退，已修复"),
            ChangelogItem(ChangeType.FIX, "信息栏关闭「时间 / 电量」时误藏阅读进度修复 —— 关掉信息栏的时间 / 电量显示后阅读进度被一起藏了，现在两者独立"),
            ChangelogItem(ChangeType.FIX, "EPUB 封面对话气泡 + 作者名显示 —— 之前部分精排 EPUB 封面上的对话气泡、竖排作者名整块不显示，现在正常显示"),
            ChangelogItem(ChangeType.FIX, "EPUB 简介页标题不再重复 —— 之前简介页顶部多出一个大标题（和页面自带的艺术标题撞了），现在去掉"),
            ChangelogItem(ChangeType.FIX, "EPUB 封面显示真封面 —— 之前部分书（svg 包裹封面）书架封面显示成封底或纯色块，现在正确显示真封面"),
            ChangelogItem(ChangeType.FIX, "EPUB 页面背景图竖条修复 —— 之前个别书的页面背景图被切成一条条竖条，现在正常铺满"),
            ChangelogItem(ChangeType.FIX, "EPUB 自带字体排版修正 —— 自带字体的字符间距 / 测量偏差修正"),
            ChangelogItem(ChangeType.FIX, "音量键翻页失效修复 —— 部分场景下按音量键无法翻页的问题修复"),
            ChangelogItem(ChangeType.FIX, "个别书本跨页显示错位修复 —— 个别书本在翻页时跨页错位的问题修复"),
            ChangelogItem(ChangeType.FIX, "超多书本导入卡死修复 —— 一次性导入大量书籍时阅读器卡死的问题修复"),
            ChangelogItem(ChangeType.FIX, "EPUB 简介页标题横线 + 副标题位置修正 —— 部分精排 EPUB 简介页，标题下的横线之前离标题偏远、紧跟的副标题还会跟标题艺术字重叠；现在横线贴着标题、副标题规整落在横线下方"),
            ChangelogItem(ChangeType.FIX, "EPUB 人物简介/对话气泡页乱码修正 —— 部分精排 EPUB 的人物简介页（头像 + 对话气泡那种）之前会冒出一串内部标记字符当正文显示；现在正常显示对话文字"),
            ChangelogItem(ChangeType.IMPROVE, "EPUB 精排样式进一步完善 —— 更多精排样式（彩色盒子、单边边框、不等圆角、元素背景图等）正确显示"),
        ),
    ),
    ChangelogEntry(
        version = "v1.5",
        date = "2026-05-25",
        title = "EPUB 精品排版引擎 + 翻页体验全面重做",
        items = listOf(
            ChangelogItem(ChangeType.IMPROVE, "仿真翻页（贝塞尔卷边）暂不支持 EPUB —— 阅读 EPUB 时请把翻页模式改为 平移 / 覆盖 / 滚动 / 无动画 任一项；TXT 仍可正常使用仿真翻页"),
            ChangelogItem(ChangeType.NEW, "【重大更新】EPUB 精品排版引擎 —— 精品 EPUB 的样式现在能正确渲染：圆角彩色文字框、段落背景色、边框、对话气泡描边光晕、行内小图、表格排版、标题大字号 + 间距、段落缩进对齐"),
            ChangelogItem(ChangeType.NEW, "EPUB 自带字体 —— 书里内嵌的字体现在能自动识别并应用到对应段落，不再全书统一用系统字体"),
            ChangelogItem(ChangeType.NEW, "EPUB 整屏封面 —— 包成整屏的封面写法现在能正确识别并撑满整屏；普通封面仍按原比例居中"),
            ChangelogItem(ChangeType.NEW, "阅读器搜索方向过滤 —— 阅读设置新增「搜索方向」三档：全文 / 前向（只看当前页之前）/ 后向（只看之后），想跳回前面读过的某段时不用从未来章节的命中里翻找"),
            ChangelogItem(ChangeType.NEW, "「我的」页插画卡 —— 阅读统计卡换成日夜各一张氛围插画背景（米黄日落 / 月夜书桌），统计数字叠在插画上"),
            ChangelogItem(ChangeType.NEW, "搜索页胶囊式输入框 —— 放大镜 + 输入框 + 搜索按钮收进一个圆角胶囊，视觉更聚焦"),
            ChangelogItem(ChangeType.IMPROVE, "翻页引擎重做 —— 覆盖 / 平移 / 无动画三种翻页模式底层重写，根治了滚动模式下跨章闪烁、空气墙、InfoBar 闪烁三大顽疾"),
            ChangelogItem(ChangeType.FIX, "滚动阅读真无缝 —— 滚动模式下跨章自然切换，正文不再闪烁、不再跳一下"),
            ChangelogItem(ChangeType.FIX, "拖动进度条所见所得 —— 滚动模式下拖动底部进度条时阅读区实时跟随到对应位置；之前只显示章节开头、松手后才跳"),
            ChangelogItem(ChangeType.FIX, "平移翻页向左滑跟手 —— 之前左滑回前一页时左侧露黑底要等完全滑完上一页才出现，现在拖动过程中前一页就跟手露出"),
            ChangelogItem(ChangeType.FIX, "EPUB 封面按图片原比例显示 —— 之前部分 EPUB 封面被压扁只占屏幕约 1/3，现在按真实长宽比撑到阅读宽度"),
            ChangelogItem(ChangeType.FIX, "横向翻页模式 EPUB 章节背景图显示 —— 之前覆盖 / 平移 / 无动画下看不到章节级背景图，现在跟滚动模式表现一致"),
            ChangelogItem(ChangeType.FIX, "WebDAV 书架支持嵌套文件夹 —— 之前子文件夹里的书看不见，现在递归扫描最多 6 层"),
            ChangelogItem(ChangeType.FIX, "竖排版可设阅读背景图 —— 之前选了背景图切到竖排版不生效，现在正常显示"),
            ChangelogItem(ChangeType.FIX, "下划线全线型加粗 + 贴近文字 —— 线粗跟字号成比例、Y 锚到字符底沿不再受行距影响；4 种线型在所有翻页模式下表现一致"),
            ChangelogItem(ChangeType.FIX, "老用户升级后新增的菜单项能看见 —— 老版本保存过菜单配置的用户，升级后新增项不再被补成「隐藏」"),
            ChangelogItem(ChangeType.FIX, "搜索历史 chip 的删除按钮位置修正 —— chip 高度自适应，文字垂直居中"),
            ChangelogItem(ChangeType.FIX, "书源规则编辑支持多行 —— 所有规则字段允许多行输入和粘贴"),
            ChangelogItem(ChangeType.FIX, "书源 JSON 导入支持原始换行 —— Legado 等格式书源 JSON 里带原始换行的规则不再报语法错误"),
        ),
    ),
    ChangelogEntry(
        version = "v1.4",
        date = "2026-05-17",
        title = "滚动阅读新引擎（实验性）+ Kindle 完整支持 + 大文件夹导入加速",
        items = listOf(
            ChangelogItem(ChangeType.NEW, "滚动阅读模式新引擎（默认启用）—— 固定 prev/cur/next 三章常驻 + 像素级滚动，从根上消除老滚动引擎的跨章跳章 bug（向上翻页连续跳几章 / 章序错乱）。功能与老引擎对齐：长按选词 + handle 拖动 / 8 项选区菜单 / 高亮 + 字色 + 下划线 / 字号 + 行距 + 段距 + 字体 + 字重实时生效 / 顶底状态栏 / 背景图 / TTS 段跟随 / 续读 + 书签跳转 / 章顶三角标记 / tap 已存高亮弹删除分享菜单。如遇到滚动模式有问题可在 设置 → 阅读 → 实验性功能 关掉退回老引擎"),
            ChangelogItem(ChangeType.IMPROVE, "关于 Edge TTS（微软语音）—— Edge TTS 走微软官方接口，可能因网络环境 / 微软侧风控波动 / 频率限制等原因偶尔不可用。遇到 TTS 报错或无声音：稍候重试 / 换网络（4G ↔ WiFi）/ 或在阅读器 TTS 面板切到系统 TTS 引擎"),
            ChangelogItem(ChangeType.NEW, "azw3 / Kindle 电子书完整支持 —— 个别现代 azw3 现在能正常打开，章节目录、正文、封面都对了"),
            ChangelogItem(ChangeType.NEW, "竖排版多了「章标在左」选项 —— 设置 → 阅读 → 竖排版，可在「横排」「竖排（章标在右）」「竖排（章标在左）」三种间切换"),
            ChangelogItem(ChangeType.NEW, "空章节给清晰提示 —— 遇到分隔页、版权页等没有正文的章节，显示「本章暂无内容」，不再大片空白"),
            ChangelogItem(ChangeType.NEW, "打开坏文件直接给错误页 —— 0 字节占位文件 / DRM 加密的 Kindle 书会显示「文件损坏 / 不支持加密」，不再让 app 崩"),
            ChangelogItem(ChangeType.NEW, "导入时拦坏文件 —— .epub 后缀但内容是 0 字节 / 不是 ZIP 的文件在入库前被拒绝，避免书架占位卡死"),
            ChangelogItem(ChangeType.IMPROVE, "大文件夹导入显著加速 —— 上千本书的导入从「数分钟」缩到「半分钟以内」，先秒进书架占位，后台慢慢补封面 / 元数据"),
            ChangelogItem(ChangeType.IMPROVE, "漫画 / 小说自动识别更准 —— 带几张插图的个别轻小说不再被误判为漫画"),
            ChangelogItem(ChangeType.IMPROVE, "快速连点同一本书不再叠开多个 —— 连点 5 次只进一次阅读器，按一次返回就回主页"),
            ChangelogItem(ChangeType.FIX, "跨章节后音量键 / 顶栏按钮翻页失灵 —— 章节切换后短时间内按音量键、按顶栏「上一页 / 下一页」按钮没反应，必须等几秒或退出重进。现在跨章瞬间就能按"),
            ChangelogItem(ChangeType.FIX, "仿真翻页（贝塞尔卷边）模式音量键能用了 —— 之前在仿真翻页下按音量键完全没反应（只能手指点屏幕翻），现在音量键也能触发卷边翻页动画"),
            ChangelogItem(ChangeType.FIX, "仿真翻页狂按不再错位 —— 之前在仿真翻页下快速连按音量键，动画期间多按的几次会偷偷改了内部页码导致下一次按键「按了没反应」，现在动画期间多按的指令直接忽略，等动画结束再按就正常"),
            ChangelogItem(ChangeType.FIX, "EPUB 封面经常取错 —— 个别精品书之前拿到版权页扫描 / 制作水印页当封面，现在按 EPUB3 标准识别真封面"),
            ChangelogItem(ChangeType.FIX, "滑动卡顿 + 莫名崩溃 —— 内存吃紧时主动收缩图片缓存，配合更大的内存配额，漫画 / 大文件 EPUB 翻页明显流畅"),
            ChangelogItem(ChangeType.FIX, "关于页面格式描述清理"),
        ),
    ),
    ChangelogEntry(
        version = "v1.3.1",
        date = "2026-05-14",
        title = "EPUB 精品书籍兼容性 + 群里反馈痛点修复",
        items = listOf(
            ChangelogItem(ChangeType.FIX, "EPUB 翻页黑屏修复（部分多级目录精品书籍）：跨章到「制作说明 / 内容介绍 / 人物志」等空内容占位章时，之前 ReaderScreen 用「内容非空」作为渲染门槛 → 整个阅读区跳过组合 → 黑屏 + 点击没反应。改用「ChapterController 已 publish 过有效章节」作门槛，空章走 ReflowEngine placeholder 一页占位，可继续翻"),
            ChangelogItem(ChangeType.FIX, "EPUB 段落空行假阳性：normalizeParagraph trim 改用 isWhitespace + isSpaceChar 覆盖 NBSP / FIGURE SPACE 等 Unicode Zs 空白，避免 <p>&#160;</p> 假空段被当有效段落渲染成大段空行"),
            ChangelogItem(ChangeType.FIX, "大型精品 EPUB 解析（部分 69MB / 几十张高清插图量级）：多级目录的父子层级保留，「人物志」类章节下的子节点按缩进显示；高清大图自动压缩入缓存，避免几十张同时解码引发的卡顿；单张坏图不再把整章拖崩"),
            ChangelogItem(ChangeType.FIX, "PDF 自带 outline 识别为目录：之前一律按「每 N 页一节」切，多级精品 PDF 失去章节结构。现在优先 DFS 解析 outline tree + 层级缩进展示；outline 缺失才退回按页切"),
            ChangelogItem(ChangeType.FIX, "阅读器亮度设置丢失：手动调亮度后退出重进会变回「跟随系统」 —— 之前 setReaderBrightness 只改内存 StateFlow 没持久化。新加 DataStore key + ViewModel init 从 prefs 注入历史值"),
            ChangelogItem(ChangeType.FIX, "「楷体和仿宋显示效果一样」：完整中文 ttf 未打包，之前两者都 fallback 到 Typeface.SERIF。改成楷体走 SERIF italic（流畅倾斜近似楷书）、仿宋保 SERIF normal，肉眼可见差异；阅读器字体面板加 fallback 提示，引导到「字体管理」导入真 ttf"),
        ),
    ),
    ChangelogEntry(
        version = "v1.3",
        date = "2026-05-13",
        title = "阅读体验 + 书源稳定性修复",
        items = listOf(
            ChangelogItem(ChangeType.NEW, "阅读器底部工具栏可自定义：长按进入编辑模式，拖动改位置，加号添加 / 减号隐藏"),
            ChangelogItem(ChangeType.NEW, "主题编辑器加「首页背景图」字段，每个自定义主题可以绑一张"),
            ChangelogItem(ChangeType.NEW, "轻量漫画模式：MOBI / AZW3 等图片书自动识别并切到漫画阅读器"),
            ChangelogItem(ChangeType.NEW, "划词下划线高亮：4 种线型（直线 / 虚线 / 点划线 / 波浪线）+ 5 色"),
            ChangelogItem(ChangeType.FIX, "MOBI 格式解析问题"),
            ChangelogItem(ChangeType.FIX, "滚动模式上下滚动自动跳章"),
            ChangelogItem(ChangeType.FIX, "换源大多数返回空目录"),
            ChangelogItem(ChangeType.FIX, "校验书源过程中删源会闪退"),
            ChangelogItem(ChangeType.FIX, "校验完成可以勾选删除无效书源"),
            ChangelogItem(ChangeType.FIX, "Legado 主题导入颜色异常（洋红 / 透明）"),
            ChangelogItem(ChangeType.FIX, "Legado「阅读样式配置」也能导入了：自动识别 + 拆为日 / 夜两个主题 + 导入提示"),
            ChangelogItem(ChangeType.FIX, "跟随系统主题打开开关后不立即生效"),
            ChangelogItem(ChangeType.FIX, "阅读设置「左侧轻按」改完不生效"),
            ChangelogItem(ChangeType.FIX, "阅读器工具栏加号被遮挡 + 颜色容易被误以为不可点击"),
        ),
    ),
    ChangelogEntry(
        version = "v1.2",
        date = "2026-05-10",
        title = "视觉重设计 · UI 全面焕新",
        items = listOf(
            // ── 新增 ──
            ChangelogItem(ChangeType.NEW, "首页书架「我的书架」标题行：在「继续阅读」下方左标题右双按钮（排序 + 切换视图），与顶栏 greeting 形成清晰视觉分隔"),
            ChangelogItem(ChangeType.NEW, "顶栏副文本显示今日阅读时长：「今日已阅读 X 小时 Y 分钟」替代原「享受阅读时光」，数据来自 ReadStatsRepository"),
            ChangelogItem(ChangeType.NEW, "搜索结果来源标签 chip 化：文本源 primary 容器、非文本源 error 容器，与 SourceTag 视觉系统统一"),
            ChangelogItem(ChangeType.NEW, "竖排版 Phase 1：排版引擎层加入 Axis 抽象 + vertical 渲染目录骨架；EpubParser 保留 <rt> 振假名 + readingDirection 偏好（UI 入口后续版本接通）"),

            // ── 视觉重设计 ──
            ChangelogItem(ChangeType.IMPROVE, "进度条全新设计：底部进度条填充段从「嵌入式」（左圆右直）改为「独立胶囊」（两端圆角）+ 横向渐变（亮紫 → primary），凸出立体光感"),
            ChangelogItem(ChangeType.IMPROVE, "Tab 栏（PillNavigationBar）全新设计：半透明胶囊 + 顶部细高光 + 软阴影 + spring 滑动 dot 指示器；选中 icon glow + 弹性按压（scale 0.92 ↔ 1.0）"),
            ChangelogItem(ChangeType.IMPROVE, "日志屏 UI 全新设计：每条第一行重排为 Level chip + Tag + Time（Mono 右对齐）；WARN 及以上 message 染 level 色；删除无用「详细日志记录」开关"),
            ChangelogItem(ChangeType.IMPROVE, "阅读控制栏重排：进度条上、按钮下；按钮容器 32dp → 44dp（接近 Material 3 标准触摸目标 48dp）；章节标题与进度去除分隔符「·」"),
            ChangelogItem(ChangeType.IMPROVE, "顶栏 / overflow 按钮整理：排序按钮从顶栏移到「我的书架」标题行；overflow 内的「切换视图」也移到标题行；同一动作单一入口"),

            // ── 性能 ──
            ChangelogItem(ChangeType.IMPROVE, "PillNavigationBar 动画状态读取从顶层 by 委托改为 lambda 内读 State —— 每次动画 invalidation 只触发 placement / render，跳过 composition + measure + layout"),
            ChangelogItem(ChangeType.IMPROVE, "PillNavigationBar dot glow / icon glow 的 Brush 用 drawWithCache 缓存到 size 变化前，60fps 期间 0 次 GC"),

            // ── 修复 ──
            ChangelogItem(ChangeType.FIX, "滚动模式当前页号实时更新：底部 InfoBar 之前硬编码 pageIndex=0 / pageCount=0 全 fallback 到 chapter_progress；现在从 LazyScrollSection 首段 charPos 反查 TextChapter.getPageIndexByCharIndex 60fps 实时更新"),
            ChangelogItem(ChangeType.FIX, "音量键翻页未生效修复"),
            ChangelogItem(ChangeType.FIX, "仿真翻页快翻「走三步退一步」修复（仿真快翻 base race）"),
            ChangelogItem(ChangeType.FIX, "个别书源闪退修复（P0 书源补齐 + AnalyzeRule WebJs 模式接通 BackstageWebView）"),
            ChangelogItem(ChangeType.FIX, "首装时主题跟随系统暗色态"),
            ChangelogItem(ChangeType.FIX, "拖动滑块时菜单栏自己消失修复（onSeekFullBook 内补 hideControls）"),
            ChangelogItem(ChangeType.FIX, "拖动滑块改 conflate + 单 worker 串行，避免章预览风暴"),
            ChangelogItem(ChangeType.FIX, "注 / 批 SVG 超大字 + 拖动期间 sliderValue / thumb 分裂修复"),
        ),
    ),
    ChangelogEntry(
        version = "v1.1",
        date = "2026-05-07",
        title = "稳态优化 · 进度条 / 边距 / 繁简 / 搜索",
        items = listOf(
            // ── 阅读器·进度与滑块 ──
            ChangelogItem(ChangeType.FIX, "拖底部进度条松手后 thumb 不再先回弹再恢复（seek preview 延迟到 ViewModel 真值流到再清，覆盖本地 + 网络章节加载窗口）"),
            ChangelogItem(ChangeType.FIX, "上下 / 左右 / 段距 slider 松手「弹回再回来」消失（preview 改为等 StateFlow emit 到目标值再清空）"),

            // ── 阅读器·SCROLL 模式（边距实时 + 反 reset 风暴）──
            ChangelogItem(ChangeType.FIX, "SCROLL 模式拖底部进度条到当前章不同位置不再清窗口重 fetch（同章 reload 走 requestJumpWithinWindow 轻路径，杜绝 230 段反复 clear 的体感「拖动没反应」）"),
            ChangelogItem(ChangeType.FIX, "上下 / 左右 / 段距 slider 实时反映到正文 — 修复滚动模式下间距失效（ChapterWindowSource.relayoutAll 用最新 layoutInputs 重排已加载章节）"),
            ChangelogItem(ChangeType.FIX, "阅读器顶栏 top bar 间距过大问题修复"),

            // ── 阅读器·繁简切换 ──
            ChangelogItem(ChangeType.FIX, "连点繁→简→繁 时章内位置不再累计回退（anchor 锁，停止操作 2 秒后清；连点期间共用首次快照）"),
            ChangelogItem(ChangeType.FIX, "并发繁简切换造成「切完又变回去」修复（Mutex 串行化，按到达顺序排队，最后一次胜出）"),
            ChangelogItem(ChangeType.FIX, "繁简转换异常不再静默吞错（quick-transfer s2t / t2s 失败时打 warn 日志保留排查线索）"),

            // ── 阅读器·搜索 / 跳转 ──
            ChangelogItem(ChangeType.FIX, "修复全文搜索闪退"),
            ChangelogItem(ChangeType.FIX, "修复书内搜索"),
            ChangelogItem(ChangeType.NEW, "正文支持目录链接跳转 — 章节内引用其它章节的链接可直接点开"),

            // ── 书源 / 数据导入 ──
            ChangelogItem(ChangeType.FIX, "修复部分书源乱码问题"),
            ChangelogItem(ChangeType.FIX, "修复从 Legado 移植数据时闪退"),

            // ── 设置·恢复出厂 ──
            ChangelogItem(ChangeType.IMPROVE, "「一键还原」从「轻还原」扩展为完整出厂：颜色 / 字体 / 字号 / 行距 / 段距 / 边距 / 翻页方式 / 翻页动画 / 屏幕方向 / 划词可选 / 繁简模式 / 状态栏 / 章节名 / 时间电量 / 屏幕亮度 / 音量键 / 耳机键 / 4 个 tap zone / 6 个 header & footer slot / 标题对齐 / 阅读区背景图 / 选区菜单顺序"),
            ChangelogItem(ChangeType.IMPROVE, "5 个内置预设（preset_paper 等）执行恢复出厂时一并刷回出厂参数，防止用户曾改过预设后 active 切换仍带着旧值"),

            // ── 书架 ──
            ChangelogItem(ChangeType.NEW, "书架列表 / 网格视图切换持久化到 DataStore — 重启 App 保留上次选择"),
            ChangelogItem(ChangeType.IMPROVE, "书名自然排序（按数字顺序）— 「第2章 / 第10章 / 第11章」不再被字典序排成「第10章 / 第11章 / 第2章」"),

            // ── UI / 文案 ──
            ChangelogItem(ChangeType.IMPROVE, "「收纳」菜单语义调整，更贴近用户对该入口功能的直觉"),

            // ── 关于页 ──
            ChangelogItem(ChangeType.NEW, "新增「贡献者」入口（关于页内）— 基于 assets/contributors.json 列出社区贡献成员，含维度标签 / 加入时间 / 链接，配合根目录 CONTRIBUTING.md 入榜规则"),
        ),
    ),
    ChangelogEntry(
        version = "v1.0",
        date = "2026-05-01",
        title = "正式版 · 阅读 / 听书 / 同步全栈完成",
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
