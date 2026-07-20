package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.epub.css.EpubBackgroundGeometry
import com.morealm.epub.css.EpubBackgroundImage
import com.morealm.epub.css.EpubBackgroundLayer
import com.morealm.epub.css.EpubBackgroundOffset
import com.morealm.epub.css.EpubBackgroundPosition
import com.morealm.epub.css.EpubBackgroundAxisPosition
import com.morealm.epub.css.EpubBackgroundRepeat
import com.morealm.epub.css.EpubBackgroundRepeatMode
import com.morealm.epub.css.EpubBackgroundSize
import com.morealm.epub.css.EpubBackground
import com.morealm.epub.render.ScrollPageSectionRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EpubPageBackgroundDrawerTest {

    @Test
    fun `滚动长章的 auto 40 percent 始终按屏幕高度缩放`() {
        val layer = EpubBackgroundLayer(
            image = EpubBackgroundImage.Url("file:///sy.png"),
            repeat = EpubBackgroundRepeat(
                EpubBackgroundRepeatMode.NO_REPEAT,
                EpubBackgroundRepeatMode.NO_REPEAT,
            ),
            position = EpubBackgroundPosition(
                x = EpubBackgroundAxisPosition(EpubBackgroundOffset(percent = 50f)),
                y = EpubBackgroundAxisPosition(fromFarEdge = true),
            ),
            size = EpubBackgroundSize.Explicit(
                width = null,
                height = EpubBackgroundOffset(percent = 40f),
            ),
        )

        val resolved = resolveViewportBackgroundSize(
            layer = layer,
            viewportWidth = 920f,
            viewportHeight = 2048f,
            fontSizePx = 40f,
        )
        val plan = EpubBackgroundGeometry.plan(
            layer = resolved,
            areaWidth = 920f,
            areaHeight = 6000f,
            intrinsicWidth = 180f,
            intrinsicHeight = 205f,
        )
        assertNotNull(plan)
        plan!!

        assertEquals(2048f * 0.4f, plan.tileHeight, 0.01f)
        assertEquals(plan.tileHeight * 180f / 205f, plan.tileWidth, 0.01f)
        assertEquals((920f - plan.tileWidth) / 2f, plan.originX, 0.01f)
        assertEquals(6000f - plan.tileHeight, plan.originY, 0.01f)
        assertEquals(1L, plan.tileCount)
    }

    @Test
    fun `分页背景在每一页独立锚定底部且末页使用完整画布`() {
        val layer = EpubBackgroundLayer(
            image = EpubBackgroundImage.Url("file:///sy.png"),
            repeat = EpubBackgroundRepeat(
                EpubBackgroundRepeatMode.NO_REPEAT,
                EpubBackgroundRepeatMode.NO_REPEAT,
            ),
            position = EpubBackgroundPosition(
                x = EpubBackgroundAxisPosition(EpubBackgroundOffset(percent = 50f)),
                y = EpubBackgroundAxisPosition(fromFarEdge = true),
            ),
            size = EpubBackgroundSize.Explicit(
                width = null,
                height = EpubBackgroundOffset(percent = 40f),
            ),
        )
        val resolved = resolveViewportBackgroundSize(layer, 920f, 1_000f, 40f)
        val firstRegion = region(top = 0f, bottom = 900f, offset = 0f, sectionHeight = 1_200f)
        val lastRegion = region(top = 0f, bottom = 300f, offset = 900f, sectionHeight = 1_200f)

        val firstFrame = resolveEpubBackgroundRegionFrame(firstRegion, 1_000f, false, true)
        val lastFrame = resolveEpubBackgroundRegionFrame(lastRegion, 1_000f, false, true)
        val firstPlan = EpubBackgroundGeometry.plan(resolved, 920f, firstFrame.areaHeight, 180f, 205f)!!
        val lastPlan = EpubBackgroundGeometry.plan(resolved, 920f, lastFrame.areaHeight, 180f, 205f)!!

        assertEquals(1_000f, firstFrame.bottom, 0.01f)
        assertEquals(1_000f, lastFrame.bottom, 0.01f)
        assertEquals(600f, firstFrame.localY(firstPlan.originY), 0.01f)
        assertEquals(600f, lastFrame.localY(lastPlan.originY), 0.01f)
        assertEquals(1_000f, firstFrame.localY(firstPlan.originY + firstPlan.tileHeight), 0.01f)
        assertEquals(1_000f, lastFrame.localY(lastPlan.originY + lastPlan.tileHeight), 0.01f)
    }

    @Test
    fun `滚动背景继续使用章节累计偏移`() {
        val lastRegion = region(top = 0f, bottom = 300f, offset = 900f, sectionHeight = 1_200f)
        val frame = resolveEpubBackgroundRegionFrame(lastRegion, 1_000f, true, true)

        assertEquals(1_200f, frame.areaHeight, 0.01f)
        assertEquals(900f, frame.offsetY, 0.01f)
        assertEquals(-100f, frame.localY(800f), 0.01f)
    }

    private fun region(
        top: Float,
        bottom: Float,
        offset: Float,
        sectionHeight: Float,
    ) = ScrollPageSectionRegion(
        sectionIndex = 0,
        top = top,
        bottom = bottom,
        sectionOffsetY = offset,
        sectionHeight = sectionHeight,
        background = EpubBackground.EMPTY,
    )
}
