package com.morealm.app.domain.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式 v2 → v3 升级的兼容性回归测试。
 *
 * 历史：v2 zip 内含 8 类内嵌 JSON 字符串字段。v3 增加 `preferences` 与
 * `bgImageManifest` 两个字段，并约定 zip 内可挂 `bg/<file>` 子条目。本套测试保护：
 *
 *  1. **旧 v2 zip 依然能被 v3 代码读出**：解码后多余的 v2 字段不掉，缺的 v3 字段
 *     默认空字符串；不需要任何 schema 迁移。
 *  2. **新 v3 zip 在解码时携带新字段**：preferences 不会神秘消失。
 *  3. **RestoreOptions 默认全开 / NONE 全关 / data class equality** 行为正确，
 *     UI 拼字段时不容易把开关方向搞反。
 *  4. **isFullExport / 默认值** 一致性 —— 防止以后改 BackupOptions 默认值时出现
 *     "默认不是全选" 的回归。
 */
class BackupDataCompatibilityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    @Test
    fun `v2 backup JSON decodes into v3 BackupData with empty new fields`() {
        // 这是 v2 时代写入磁盘的实际形态 —— preferences / bgImageManifest 不存在
        val v2Json = """
            {
              "version": 2,
              "timestamp": 1234567890,
              "books": "[]",
              "bookmarks": "",
              "sources": "[]",
              "progress": "",
              "groups": "",
              "replaceRules": "",
              "themes": "",
              "readerStyles": "",
              "httpTts": ""
            }
        """.trimIndent()

        val data = json.decodeFromString<BackupManager.BackupData>(v2Json)
        assertEquals(2, data.version)
        assertEquals(1234567890L, data.timestamp)
        assertEquals("[]", data.books)
        assertEquals("[]", data.sources)
        // v3 新增字段：v2 zip 没写时应解为空字符串（默认值），applyBackup 的 isBlank 守卫
        // 会把它们当"该 section 缺失"对待
        assertEquals("", data.preferences)
        assertEquals("", data.bgImageManifest)
    }

    @Test
    fun `v3 backup JSON round-trips preferences and bgImageManifest fields`() {
        val v3Source = BackupManager.BackupData(
            version = 3,
            timestamp = 9000L,
            books = "[]",
            preferences = """{"page_anim":{"t":"s","v":"cover"}}""",
            bgImageManifest = """[{"name":"day.jpg","sizeBytes":1024}]""",
        )
        val encoded = json.encodeToString(BackupManager.BackupData.serializer(), v3Source)
        val decoded = json.decodeFromString<BackupManager.BackupData>(encoded)
        assertEquals(v3Source.version, decoded.version)
        assertEquals(v3Source.timestamp, decoded.timestamp)
        assertEquals(v3Source.preferences, decoded.preferences)
        assertEquals(v3Source.bgImageManifest, decoded.bgImageManifest)
    }

    @Test
    fun `decoding json with unknown future field does not throw`() {
        // 万一 v4 增加了字段（比如 "subscriptions"），v3 代码必须能忽略这个新字段而非崩
        // —— 这正是 BackupManager.json 配 ignoreUnknownKeys = true 的目的。
        val v4Json = """
            {
              "version": 4,
              "books": "[]",
              "subscriptions": "[\"feed1\"]"
            }
        """.trimIndent()
        val data = json.decodeFromString<BackupManager.BackupData>(v4Json)
        assertEquals(4, data.version)
        assertEquals("[]", data.books)
    }

    @Test
    fun `RestoreOptions default has every section enabled`() {
        // 默认行为应该等价于 "传统全量恢复" —— 否则旧调用方升到带 opts 的签名后
        // 会突然只恢复部分数据，产生静默回归
        val defaults = BackupManager.RestoreOptions()
        assertTrue(defaults.includeBooks)
        assertTrue(defaults.includeBookmarks)
        assertTrue(defaults.includeSources)
        assertTrue(defaults.includeProgress)
        assertTrue(defaults.includeGroups)
        assertTrue(defaults.includeReplaceRules)
        assertTrue(defaults.includeThemes)
        assertTrue(defaults.includeReaderStyles)
        assertTrue(defaults.includePreferences)
    }

    @Test
    fun `RestoreOptions NONE has every section disabled`() {
        val none = BackupManager.RestoreOptions.NONE
        assertFalse(none.includeBooks)
        assertFalse(none.includeBookmarks)
        assertFalse(none.includeSources)
        assertFalse(none.includeProgress)
        assertFalse(none.includeGroups)
        assertFalse(none.includeReplaceRules)
        assertFalse(none.includeThemes)
        assertFalse(none.includeReaderStyles)
        assertFalse(none.includePreferences)
    }

    @Test
    fun `BackupOptions default is full export`() {
        val defaults = BackupManager.BackupOptions()
        assertTrue("默认应该 isFullExport, 不能默认裁剪", defaults.isFullExport())
        assertTrue(defaults.includePreferences)
        assertTrue(defaults.includeBgImages)
    }

    @Test
    fun `BackupOptions disabling any single section makes isFullExport false`() {
        // 任意一项 off → isFullExport 必须 false。这个性质 UI 用来显示 "已裁剪 N 项"
        // 概要文案，回归会让用户看到错误的 "完整导出" 标签
        assertFalse(BackupManager.BackupOptions(includeBooks = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeBookmarks = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeSources = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeProgress = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeGroups = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeReplaceRules = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeThemes = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeReaderStyles = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includePreferences = false).isFullExport())
        assertFalse(BackupManager.BackupOptions(includeBgImages = false).isFullExport())
    }

    @Test
    fun `RestoreSectionInfo data class equality respects all fields`() {
        // RestoreSectionInfo 在 ViewModel/UI 之间作为列表元素流转 —— equality 不能马虎，
        // 否则 LazyColumn key 表行为会出问题
        val a = BackupManager.RestoreSectionInfo("books", "书籍", 10, 3)
        val b = BackupManager.RestoreSectionInfo("books", "书籍", 10, 3)
        val c = BackupManager.RestoreSectionInfo("books", "书籍", 10, 5) // conflictCount 不同
        assertEquals(a, b)
        assertNotNull(a.hashCode()) // smoke
        assertFalse(a == c)
    }
}
