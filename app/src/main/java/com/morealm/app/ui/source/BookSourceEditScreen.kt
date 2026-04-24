package com.morealm.app.ui.source

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.*
import com.morealm.app.domain.webbook.SourceDebug
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * 书源编辑页面 - 编辑书源的所有字段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSourceEditScreen(
    source: BookSource,
    onBack: () -> Unit,
    onSave: (BookSource) -> Unit,
    debugSteps: List<SourceDebug.DebugStep> = emptyList(),
    isDebugging: Boolean = false,
    onDebug: (BookSource, String) -> Unit = { _, _ -> },
    onCancelDebug: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current

    // Basic fields
    var name by remember { mutableStateOf(source.bookSourceName) }
    var url by remember { mutableStateOf(source.bookSourceUrl) }
    var group by remember { mutableStateOf(source.bookSourceGroup ?: "") }
    var comment by remember { mutableStateOf(source.bookSourceComment ?: "") }
    var loginUrl by remember { mutableStateOf(source.loginUrl ?: "") }
    var loginCheckJs by remember { mutableStateOf(source.loginCheckJs ?: "") }
    var header by remember { mutableStateOf(source.header ?: "") }
    var concurrentRate by remember { mutableStateOf(source.concurrentRate ?: "") }
    var searchUrl by remember { mutableStateOf(source.searchUrl ?: "") }
    var exploreUrl by remember { mutableStateOf(source.exploreUrl ?: "") }
    var enabled by remember { mutableStateOf(source.enabled) }
    var enabledExplore by remember { mutableStateOf(source.enabledExplore) }
    var jsLib by remember { mutableStateOf(source.jsLib ?: "") }

    // Search rule
    val sr = source.ruleSearch ?: SearchRule()
    var srBookList by remember { mutableStateOf(sr.bookList ?: "") }
    var srName by remember { mutableStateOf(sr.name ?: "") }
    var srAuthor by remember { mutableStateOf(sr.author ?: "") }
    var srIntro by remember { mutableStateOf(sr.intro ?: "") }
    var srKind by remember { mutableStateOf(sr.kind ?: "") }
    var srBookUrl by remember { mutableStateOf(sr.bookUrl ?: "") }
    var srCoverUrl by remember { mutableStateOf(sr.coverUrl ?: "") }
    var srLastChapter by remember { mutableStateOf(sr.lastChapter ?: "") }
    var srWordCount by remember { mutableStateOf(sr.wordCount ?: "") }

    // BookInfo rule
    val bi = source.ruleBookInfo ?: BookInfoRule()
    var biInit by remember { mutableStateOf(bi.init ?: "") }
    var biName by remember { mutableStateOf(bi.name ?: "") }
    var biAuthor by remember { mutableStateOf(bi.author ?: "") }
    var biIntro by remember { mutableStateOf(bi.intro ?: "") }
    var biKind by remember { mutableStateOf(bi.kind ?: "") }
    var biCoverUrl by remember { mutableStateOf(bi.coverUrl ?: "") }
    var biTocUrl by remember { mutableStateOf(bi.tocUrl ?: "") }
    var biLastChapter by remember { mutableStateOf(bi.lastChapter ?: "") }
    var biWordCount by remember { mutableStateOf(bi.wordCount ?: "") }

    // Toc rule
    val tr = source.ruleToc ?: TocRule()
    var trChapterList by remember { mutableStateOf(tr.chapterList ?: "") }
    var trChapterName by remember { mutableStateOf(tr.chapterName ?: "") }
    var trChapterUrl by remember { mutableStateOf(tr.chapterUrl ?: "") }
    var trNextTocUrl by remember { mutableStateOf(tr.nextTocUrl ?: "") }
    var trIsVip by remember { mutableStateOf(tr.isVip ?: "") }
    var trPreUpdateJs by remember { mutableStateOf(tr.preUpdateJs ?: "") }

    // Content rule
    val cr = source.ruleContent ?: ContentRule()
    var crContent by remember { mutableStateOf(cr.content ?: "") }
    var crNextContentUrl by remember { mutableStateOf(cr.nextContentUrl ?: "") }
    var crWebJs by remember { mutableStateOf(cr.webJs ?: "") }
    var crSourceRegex by remember { mutableStateOf(cr.sourceRegex ?: "") }
    var crReplaceRegex by remember { mutableStateOf(cr.replaceRegex ?: "") }

    // Explore rule
    val er = source.ruleExplore ?: ExploreRule()
    var erBookList by remember { mutableStateOf(er.bookList ?: "") }
    var erName by remember { mutableStateOf(er.name ?: "") }
    var erAuthor by remember { mutableStateOf(er.author ?: "") }
    var erBookUrl by remember { mutableStateOf(er.bookUrl ?: "") }
    var erCoverUrl by remember { mutableStateOf(er.coverUrl ?: "") }
    var erIntro by remember { mutableStateOf(er.intro ?: "") }

    // Expandable sections
    var expandBasic by remember { mutableStateOf(true) }
    var expandSearch by remember { mutableStateOf(false) }
    var expandBookInfo by remember { mutableStateOf(false) }
    var expandToc by remember { mutableStateOf(false) }
    var expandContent by remember { mutableStateOf(false) }
    var expandExplore by remember { mutableStateOf(false) }
    var expandDebug by remember { mutableStateOf(false) }
    var debugKeyword by remember { mutableStateOf("我的") }

    fun buildSource(): BookSource = source.copy(
        bookSourceName = name,
        bookSourceUrl = url,
        bookSourceGroup = group.ifBlank { null },
        bookSourceComment = comment.ifBlank { null },
        loginUrl = loginUrl.ifBlank { null },
        loginCheckJs = loginCheckJs.ifBlank { null },
        header = header.ifBlank { null },
        concurrentRate = concurrentRate.ifBlank { null },
        searchUrl = searchUrl.ifBlank { null },
        exploreUrl = exploreUrl.ifBlank { null },
        enabled = enabled,
        enabledExplore = enabledExplore,
        jsLib = jsLib.ifBlank { null },
        ruleSearch = SearchRule(
            bookList = srBookList.ifBlank { null },
            name = srName.ifBlank { null },
            author = srAuthor.ifBlank { null },
            intro = srIntro.ifBlank { null },
            kind = srKind.ifBlank { null },
            bookUrl = srBookUrl.ifBlank { null },
            coverUrl = srCoverUrl.ifBlank { null },
            lastChapter = srLastChapter.ifBlank { null },
            wordCount = srWordCount.ifBlank { null },
        ),
        ruleBookInfo = BookInfoRule(
            init = biInit.ifBlank { null },
            name = biName.ifBlank { null },
            author = biAuthor.ifBlank { null },
            intro = biIntro.ifBlank { null },
            kind = biKind.ifBlank { null },
            coverUrl = biCoverUrl.ifBlank { null },
            tocUrl = biTocUrl.ifBlank { null },
            lastChapter = biLastChapter.ifBlank { null },
            wordCount = biWordCount.ifBlank { null },
        ),
        ruleToc = TocRule(
            preUpdateJs = trPreUpdateJs.ifBlank { null },
            chapterList = trChapterList.ifBlank { null },
            chapterName = trChapterName.ifBlank { null },
            chapterUrl = trChapterUrl.ifBlank { null },
            nextTocUrl = trNextTocUrl.ifBlank { null },
            isVip = trIsVip.ifBlank { null },
        ),
        ruleContent = ContentRule(
            content = crContent.ifBlank { null },
            nextContentUrl = crNextContentUrl.ifBlank { null },
            webJs = crWebJs.ifBlank { null },
            sourceRegex = crSourceRegex.ifBlank { null },
            replaceRegex = crReplaceRegex.ifBlank { null },
        ),
        ruleExplore = ExploreRule(
            bookList = erBookList.ifBlank { null },
            name = erName.ifBlank { null },
            author = erAuthor.ifBlank { null },
            bookUrl = erBookUrl.ifBlank { null },
            coverUrl = erCoverUrl.ifBlank { null },
            intro = erIntro.ifBlank { null },
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑书源", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        expandDebug = !expandDebug
                        if (expandDebug && debugSteps.isEmpty() && !isDebugging) {
                            onDebug(buildSource(), debugKeyword)
                        }
                    }) {
                        Icon(Icons.Default.BugReport, "调试",
                            tint = if (expandDebug) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    TextButton(onClick = {
                        if (url.isBlank()) {
                            Toast.makeText(context, "书源URL不能为空", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        onSave(buildSource())
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Basic info
            RuleSection("基本信息", expandBasic, { expandBasic = !expandBasic }) {
                RuleField("书源名称 *", name, { name = it })
                RuleField("书源URL *", url, { url = it })
                RuleField("分组", group, { group = it })
                RuleField("备注", comment, { comment = it })
                RuleField("请求头 (JSON)", header, { header = it }, mono = true)
                RuleField("搜索URL", searchUrl, { searchUrl = it }, mono = true)
                RuleField("发现URL", exploreUrl, { exploreUrl = it }, mono = true)
                RuleField("并发限制", concurrentRate, { concurrentRate = it }, placeholder = "如: 1000 或 3/5000")
                RuleField("登录URL", loginUrl, { loginUrl = it }, mono = true)
                RuleField("登录检测JS", loginCheckJs, { loginCheckJs = it }, mono = true)
                RuleField("jsLib", jsLib, { jsLib = it }, mono = true)
                Row {
                    Row(Modifier.weight(1f)) {
                        Checkbox(enabled, { enabled = it }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                        Text("启用", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                    }
                    Row(Modifier.weight(1f)) {
                        Checkbox(enabledExplore, { enabledExplore = it }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                        Text("启用发现", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }

            // Search rule
            RuleSection("搜索规则", expandSearch, { expandSearch = !expandSearch }) {
                RuleField("书籍列表", srBookList, { srBookList = it }, mono = true)
                RuleField("书名", srName, { srName = it }, mono = true)
                RuleField("作者", srAuthor, { srAuthor = it }, mono = true)
                RuleField("简介", srIntro, { srIntro = it }, mono = true)
                RuleField("分类", srKind, { srKind = it }, mono = true)
                RuleField("书籍URL", srBookUrl, { srBookUrl = it }, mono = true)
                RuleField("封面URL", srCoverUrl, { srCoverUrl = it }, mono = true)
                RuleField("最新章节", srLastChapter, { srLastChapter = it }, mono = true)
                RuleField("字数", srWordCount, { srWordCount = it }, mono = true)
            }

            // BookInfo rule
            RuleSection("详情规则", expandBookInfo, { expandBookInfo = !expandBookInfo }) {
                RuleField("预处理", biInit, { biInit = it }, mono = true)
                RuleField("书名", biName, { biName = it }, mono = true)
                RuleField("作者", biAuthor, { biAuthor = it }, mono = true)
                RuleField("简介", biIntro, { biIntro = it }, mono = true)
                RuleField("分类", biKind, { biKind = it }, mono = true)
                RuleField("封面URL", biCoverUrl, { biCoverUrl = it }, mono = true)
                RuleField("目录URL", biTocUrl, { biTocUrl = it }, mono = true)
                RuleField("最新章节", biLastChapter, { biLastChapter = it }, mono = true)
                RuleField("字数", biWordCount, { biWordCount = it }, mono = true)
            }

            // Toc rule
            RuleSection("目录规则", expandToc, { expandToc = !expandToc }) {
                RuleField("预处理JS", trPreUpdateJs, { trPreUpdateJs = it }, mono = true)
                RuleField("章节列表", trChapterList, { trChapterList = it }, mono = true)
                RuleField("章节名称", trChapterName, { trChapterName = it }, mono = true)
                RuleField("章节URL", trChapterUrl, { trChapterUrl = it }, mono = true)
                RuleField("下一页URL", trNextTocUrl, { trNextTocUrl = it }, mono = true)
                RuleField("VIP标识", trIsVip, { trIsVip = it }, mono = true)
            }

            // Content rule
            RuleSection("正文规则", expandContent, { expandContent = !expandContent }) {
                RuleField("正文", crContent, { crContent = it }, mono = true)
                RuleField("下一页URL", crNextContentUrl, { crNextContentUrl = it }, mono = true)
                RuleField("WebJS", crWebJs, { crWebJs = it }, mono = true)
                RuleField("资源正则", crSourceRegex, { crSourceRegex = it }, mono = true)
                RuleField("替换正则", crReplaceRegex, { crReplaceRegex = it }, mono = true)
            }

            // Explore rule
            RuleSection("发现规则", expandExplore, { expandExplore = !expandExplore }) {
                RuleField("书籍列表", erBookList, { erBookList = it }, mono = true)
                RuleField("书名", erName, { erName = it }, mono = true)
                RuleField("作者", erAuthor, { erAuthor = it }, mono = true)
                RuleField("书籍URL", erBookUrl, { erBookUrl = it }, mono = true)
                RuleField("封面URL", erCoverUrl, { erCoverUrl = it }, mono = true)
                RuleField("简介", erIntro, { erIntro = it }, mono = true)
            }

            // Debug section
            if (expandDebug) {
                RuleSection("调试结果", true, { expandDebug = false }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = debugKeyword,
                            onValueChange = { debugKeyword = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("搜索关键词") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (isDebugging) {
                            TextButton(onClick = onCancelDebug) {
                                Text("取消", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = { onDebug(buildSource(), debugKeyword) }) {
                                Text("调试", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (isDebugging) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    for (step in debugSteps) {
                        DebugStepCard(step)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RuleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            Surface(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun RuleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    mono: Boolean = false,
    placeholder: String = "",
) {
    val moColors = LocalMoRealmColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder, style = MaterialTheme.typography.bodySmall) }} else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.let {
            if (mono) it.copy(fontFamily = FontFamily.Monospace) else it
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun DebugStepCard(step: SourceDebug.DebugStep) {
    val moColors = LocalMoRealmColors.current
    val statusColor = if (step.success) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Spacer(Modifier.weight(1f))
                if (step.timeMs > 0) {
                    Text(
                        "${step.timeMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (step.success) "OK" else "FAIL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
            if (step.url.isNotBlank()) {
                Text(
                    step.url,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.error != null) {
                Text(
                    step.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.parsedResult.isNotBlank()) {
                Text(
                    step.parsedResult.take(300),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
