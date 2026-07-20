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
}
