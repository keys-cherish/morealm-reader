package com.morealm.app.domain.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏布局的持久化容错。
 *
 * 核心关切是**升级**：新版本给 [ReaderTool] 加了枚举项（本次是「换源」），而用户
 * DataStore 里存的还是旧版本 JSON。此时既不能丢用户自定义的顺序/分区，也不能让
 * 新工具凭空消失 —— [ReaderToolLayout.fromJson] 的 missing 合并逻辑负责这件事。
 */
class ReaderToolLayoutTest {

    /** 新增 ChangeSource 之前的版本会写出的 JSON：只有 5 个旧工具，且用户改过顺序与分区。 */
    private val legacyJson = """
        {"order":["settings","catalog","audio","search","auto_page"],
        "zones":{"settings":"Bottom","catalog":"Bottom","audio":"Hidden",
        "search":"Bottom","auto_page":"Hidden"}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `升级后保留用户自定义顺序与分区`() {
        val layout = ReaderToolLayout.fromJson(legacyJson)

        // 旧工具的相对顺序原样保留（用户把设置拖到了第一位）
        val legacyOrder = layout.order.filter { it != ReaderTool.ChangeSource }
        assertEquals(
            listOf(
                ReaderTool.Settings,
                ReaderTool.Catalog,
                ReaderTool.Audio,
                ReaderTool.Search,
                ReaderTool.AutoPage,
            ),
            legacyOrder,
        )

        // 用户隐藏过的工具仍然是隐藏的 —— 升级不该把它们弹回底栏
        assertEquals(ReaderToolZone.Hidden, layout.zones[ReaderTool.Audio])
        assertEquals(ReaderToolZone.Hidden, layout.zones[ReaderTool.AutoPage])
        assertEquals(ReaderToolZone.Bottom, layout.zones[ReaderTool.Settings])
    }

    @Test
    fun `升级后新增工具追加在末尾并落默认分区`() {
        val layout = ReaderToolLayout.fromJson(legacyJson)

        assertEquals(ReaderTool.ChangeSource, layout.order.last())
        assertEquals(
            ReaderTool.ChangeSource.defaultZone,
            layout.zones[ReaderTool.ChangeSource],
        )
        // 全量枚举都在 order 里，渲染时不会漏掉任何工具
        assertEquals(ReaderTool.entries.toSet(), layout.order.toSet())
    }

    @Test
    fun `未知工具 id 被跳过而不是整份重置`() {
        // 降级场景：更高版本写入了当前枚举不认识的 id
        val futureJson = """
            {"order":["catalog","time_machine","settings"],
            "zones":{"catalog":"Bottom","time_machine":"Bottom","settings":"Hidden"}}
        """.trimIndent().replace("\n", "")

        val layout = ReaderToolLayout.fromJson(futureJson)

        assertTrue(layout.order.contains(ReaderTool.Catalog))
        // 用户对已知工具的自定义仍然生效，没有因为一个脏 id 回退到 Default
        assertEquals(ReaderToolZone.Hidden, layout.zones[ReaderTool.Settings])
        assertEquals(ReaderTool.entries.toSet(), layout.order.toSet())
    }

    @Test
    fun `空输入回落默认布局`() {
        assertEquals(ReaderToolLayout.Default.order, ReaderToolLayout.fromJson(null).order)
        assertEquals(ReaderToolLayout.Default.order, ReaderToolLayout.fromJson("").order)
        // 彻底解析不了也不该抛，直接给默认
        assertEquals(ReaderToolLayout.Default.order, ReaderToolLayout.fromJson("{{{").order)
    }

    @Test
    fun `序列化后再解析保持等价`() {
        val original = ReaderToolLayout.fromJson(legacyJson)
        val roundTripped = ReaderToolLayout.fromJson(original.toJson())

        assertEquals(original.order, roundTripped.order)
        assertEquals(original.zones, roundTripped.zones)
    }

    @Test
    fun `换源工具默认可隐藏且落底栏`() {
        // 本地书场景靠 UI 层门控隐藏（见 ReaderControlBar.changeSourceAvailable），
        // 这里只固定枚举自身的契约：可被用户拖走、默认在底栏。
        assertTrue(ReaderTool.ChangeSource.removable)
        assertEquals(ReaderToolZone.Bottom, ReaderTool.ChangeSource.defaultZone)
        // id 是持久化契约，改动会让老用户布局里的该项失配后被当作未知 id 丢弃
        assertEquals("change_source", ReaderTool.ChangeSource.id)
    }
}
