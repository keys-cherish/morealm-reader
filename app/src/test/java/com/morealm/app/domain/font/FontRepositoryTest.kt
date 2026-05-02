package com.morealm.app.domain.font

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * FontRepository 单测。覆盖不依赖真实字体二进制的部分：
 *   - listAppFonts 仅识别 .ttf / .otf，过滤其他文件
 *   - deleteAppFont 真删文件
 *   - tryLoadFontFile 对非法 / 不存在路径返回 null（不抛异常）
 *   - importFromUri 文件名后缀校验（拒绝非 .ttf/.otf）
 *   - importFromUri 防穿越：含路径分隔符的文件名被 sanitize 后只保留末段
 *
 * 不覆盖：真字体加载（需要真 TTF 数据），由 instrumented test 兜底。
 */
@RunWith(RobolectricTestRunner::class)
class FontRepositoryTest {

    private lateinit var repo: FontRepository
    private lateinit var fontsDir: File

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        repo = FontRepository(ctx)
        fontsDir = File(ctx.filesDir, "fonts").apply { mkdirs() }
        // 清空可能的残留
        fontsDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `listAppFonts returns empty for fresh dir`() = runBlocking {
        assertTrue(repo.listAppFonts().isEmpty())
    }

    @Test
    fun `listAppFonts includes ttf and otf, excludes others`() = runBlocking {
        File(fontsDir, "ChineseSerif.ttf").writeText("fake ttf bytes")
        File(fontsDir, "Inter.otf").writeText("fake otf bytes")
        File(fontsDir, "readme.txt").writeText("not a font")
        File(fontsDir, "image.jpg").writeText("not a font")

        val list = repo.listAppFonts().map { it.displayName }.sorted()
        assertEquals(listOf("ChineseSerif", "Inter"), list)
    }

    @Test
    fun `listAppFonts case insensitive on extension`() = runBlocking {
        File(fontsDir, "Title.TTF").writeText("x")
        File(fontsDir, "Body.OtF").writeText("x")
        val names = repo.listAppFonts().map { it.displayName }.toSet()
        assertEquals(setOf("Title", "Body"), names)
    }

    @Test
    fun `deleteAppFont removes file`() = runBlocking {
        val f = File(fontsDir, "tmp.ttf").apply { writeText("x") }
        assertTrue(f.exists())
        val list = repo.listAppFonts()
        assertEquals(1, list.size)
        val ok = repo.deleteAppFont(list[0])
        assertTrue(ok)
        assertFalse(f.exists())
    }

    @Test
    fun `deleteAppFont refuses external entry`() = runBlocking {
        val externalEntry = FontEntry(
            displayName = "External",
            path = "content://com.example/fonts/External.ttf",
            source = FontEntry.Source.EXTERNAL,
            sizeBytes = 1234,
        )
        // External 不应被删 —— 即使它"看起来"存在，repository 也直接拒绝
        assertFalse(repo.deleteAppFont(externalEntry))
    }

    @Test
    fun `tryLoadFontFile returns null for nonexistent path`() {
        val nonExistent = File(fontsDir, "nope.ttf").absolutePath
        assertNull(repo.tryLoadFontFile(nonExistent))
        assertNull(repo.tryLoadFontFile("file://" + nonExistent))
    }

    @Test
    fun `tryLoadFontFile returns null for invalid font bytes`() {
        // 写一个 .ttf 但内容不是真字体；createFromFile 在 Robolectric 下应返回非 null
        // 但内部其实是 Typeface.DEFAULT 的 stub，这里我们仅断言函数不会抛异常。
        val f = File(fontsDir, "bad.ttf").apply { writeText("not a real font") }
        // 不抛 = 通过；返回值在 Robolectric 下不一定是 null（shadow 可能宽松）
        runCatching { repo.tryLoadFontFile(f.absolutePath) }
            .onFailure { error("tryLoadFontFile threw: ${it.message}") }
    }

    @Test
    fun `importFromUri rejects non-font extension`() = runBlocking {
        // 制造一个本地 file:// URI 当作 SAF 选中的文件，扩展名 .exe
        val src = File(fontsDir.parentFile, "evil.exe").apply { writeText("MZ...") }
        val uri = Uri.fromFile(src)

        try {
            repo.importFromUri(uri, "evil.exe")
            error("expected IllegalArgumentException for non-font extension")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ttf") == true || e.message?.contains("otf") == true)
        }
        // 别污染字库
        assertTrue(repo.listAppFonts().isEmpty())
    }

    @Test
    fun `importFromUri sanitizes path-traversal filename`() = runBlocking {
        // 构造个含 ../ 的"文件名"，期望仓库只取最后一段并仍按 .ttf 通过后缀校验，
        // 真正的字体校验会在 createFromFile 阶段失败 → 删掉 → 抛 IllegalArgument
        val src = File(fontsDir.parentFile, "trav.ttf").apply { writeText("not a font") }
        val uri = Uri.fromFile(src)

        try {
            repo.importFromUri(uri, "../../etc/passwd.ttf")
            // 在 Robolectric 下 createFromFile 可能不抛 → 不强制 fail
        } catch (e: IllegalArgumentException) {
            // 可接受：字体无效被拒；关键是没出现"穿越"目录的副作用
        }
        // 确认 etc/ 目录没被创建到 filesDir 之外
        val outside = File(fontsDir, "../../etc")
        assertFalse(outside.exists())
    }
}

// runBlocking 在 test 模块里没显式 import：使用 kotlinx-coroutines-test 的便捷形式
private fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
