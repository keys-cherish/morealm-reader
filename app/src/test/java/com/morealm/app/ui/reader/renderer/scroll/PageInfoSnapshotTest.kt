package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageInfoSnapshotTest {

    @Test
    fun `snapshot keeps page owning chapter information`() {
        val config = infoConfig(
            headerLeft = "chapter",
            footerRight = "page",
        )
        val provider = PageInfoSnapshotProvider(
            infoVersion = 7L,
            density = 2f,
            fontScale = 1f,
        ) { page ->
            when (page.chapterIndex) {
                2 -> pageSpec(config, page, title = "前一章", pageCount = 4)
                3 -> pageSpec(config, page, title = "当前章", pageCount = 6)
                4 -> pageSpec(config, page, title = "后一章", pageCount = 8)
                else -> null
            }
        }

        val previous = provider.snapshotFor(ScrollPage(3, emptyList(), 100f, 2))
        val current = provider.snapshotFor(ScrollPage(1, emptyList(), 100f, 3))
        val next = provider.snapshotFor(ScrollPage(0, emptyList(), 100f, 4))

        assertEquals("前一章", (previous?.header?.left as PageInfoSlotValue.Text).value)
        assertEquals("4/4", (previous.footer?.right as PageInfoSlotValue.Text).value)
        assertEquals("当前章", (current?.header?.left as PageInfoSlotValue.Text).value)
        assertEquals("2/6", (current.footer?.right as PageInfoSlotValue.Text).value)
        assertEquals("后一章", (next?.header?.left as PageInfoSlotValue.Text).value)
        assertEquals("1/8", (next.footer?.right as PageInfoSlotValue.Text).value)
        assertNull(provider.snapshotFor(ScrollPage(0, emptyList(), 100f, 99)))
    }

    @Test
    fun `page info version changes only for visible dynamic slots`() {
        val pageOnly = infoConfig(footerRight = "page")
        val pageVersionA = version(pageOnly, time = "10:10", battery = 20)
        val pageVersionB = version(pageOnly, time = "10:11", battery = 80)
        assertEquals(pageVersionA, pageVersionB)

        val timeAndBattery = infoConfig(footerRight = "time_battery")
        assertNotEquals(
            version(timeAndBattery, time = "10:10", battery = 20),
            version(timeAndBattery, time = "10:11", battery = 20),
        )
        assertNotEquals(
            version(timeAndBattery, time = "10:10", battery = 20),
            version(timeAndBattery, time = "10:10", battery = 80),
        )
    }

    @Test
    fun `disabled time battery slots do not invalidate page bitmap`() {
        val disabled = infoConfig(
            showTimeBattery = false,
            footerRight = "time_battery",
        )

        assertEquals(
            version(disabled, time = "10:10", battery = 20),
            version(disabled, time = "10:11", battery = 80),
        )
    }

    private fun version(
        config: ScrollCanvasInfoBarConfig,
        time: String,
        battery: Int,
    ): Long = calculatePageInfoVersion(
        config = config,
        batteryLevel = battery,
        batteryCharging = false,
        currentTime = time,
        topInsetPx = 24,
        bottomInsetPx = 36,
        cornerInsetPx = 8,
        density = 2f,
        fontScale = 1f,
    )

    private fun pageSpec(
        config: ScrollCanvasInfoBarConfig,
        page: ScrollPage,
        title: String,
        pageCount: Int,
    ) = PageInfoBarSpec(
        config = config,
        chapterTitle = title,
        chapterIndex = page.chapterIndex,
        pageIndexInChapter = page.pageIndex,
        pageCountInChapter = pageCount,
        scrollPercent = (page.pageIndex + 1f) / pageCount * 100f,
        batteryLevel = 66,
        batteryCharging = false,
        currentTime = "10:10",
        topInsetDp = 12.dp,
        bottomInsetDp = 18.dp,
    )

    private fun infoConfig(
        showTimeBattery: Boolean = true,
        headerLeft: String = "none",
        footerRight: String = "none",
    ) = ScrollCanvasInfoBarConfig(
        chaptersSize = 10,
        textColor = Color.Black,
        backgroundColor = Color.White,
        hasBgImage = false,
        paddingHorizontal = 8,
        showChapterName = true,
        showTimeBattery = showTimeBattery,
        headerLeft = headerLeft,
        headerCenter = "none",
        headerRight = "none",
        footerLeft = "none",
        footerCenter = "none",
        footerRight = footerRight,
    )
}
