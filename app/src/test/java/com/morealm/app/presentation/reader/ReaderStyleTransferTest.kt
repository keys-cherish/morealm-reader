package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.ReaderStyle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版预设导入/导出 round-trip 契约。
 *
 * 回归背景（2026-07-28）：导出端 Json 未开 encodeDefaults，`format` 判别符
 * 等于默认值被吞，导出文件只剩 `{"styles":[...]}`，导入侧三条识别规则全不
 * 命中 → 「不是有效的排版预设文件」。本测试锁两件事：
 *  1. 当前导出配置（encodeDefaults=true）必须能自我 round-trip 且带 format；
 *  2. 旧版无 format 导出文件必须仍可导入（兜底路径）。
 */
class ReaderStyleTransferTest {

    /** 与 ReaderSettingsController.styleJson 同配置。 */
    private val exportJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleStyle() = ReaderStyle(
        id = "custom_x",
        name = "测试预设",
        sortOrder = 9,
        textSize = 21,
        lineHeight = 1.6f,
        isBuiltin = false,
    )

    @Test
    fun `export then import round-trips`() {
        val bundle = MoRealmReaderStyleBundle(styles = listOf(sampleStyle().toExportData()))
        val jsonStr = exportJson.encodeToString(MoRealmReaderStyleBundle.serializer(), bundle)

        assertTrue("导出必须携带 format 判别符", jsonStr.contains(MoRealmReaderStyleBundle.FORMAT))
        val imported = parseReaderStyleBundle(exportJson, jsonStr)
        assertEquals(1, imported.size)
        assertEquals("测试预设", imported[0].name)
        assertEquals(21, imported[0].textSize)
        assertEquals(1.6f, imported[0].lineHeight)
    }

    @Test
    fun `legacy export without format field still imports`() {
        // encodeDefaults=false 年代的真实导出形态：无 format/version，仅 styles。
        val legacy = """{"styles":[{"name":"旧预设","textSize":19}]}"""
        val imported = parseReaderStyleBundle(exportJson, legacy)
        assertEquals(1, imported.size)
        assertEquals("旧预设", imported[0].name)
        assertEquals(19, imported[0].textSize)
    }

    @Test
    fun `garbage text yields empty list`() {
        assertTrue(parseReaderStyleBundle(exportJson, "not json at all").isEmpty())
        assertTrue(parseReaderStyleBundle(exportJson, """{"foo": 1}""").isEmpty())
    }
}
