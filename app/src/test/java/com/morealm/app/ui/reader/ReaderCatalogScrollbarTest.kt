package com.morealm.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderCatalogScrollbarTest {
    @Test
    fun `long catalog exposes proportional thumb`() {
        val metrics = calculateCatalogScrollbarMetrics(
            totalItemsCount = 100,
            visibleItemsCount = 10f,
            firstVisibleItemIndex = 45,
        )

        assertNotNull(metrics)
        assertEquals(0.10f, metrics!!.thumbSizeFraction, 0.001f)
        assertEquals(0.50f, metrics.thumbOffsetFraction, 0.001f)
        assertEquals(90, metrics.maxFirstVisibleItemIndex)
    }

    @Test
    fun `partial first item moves thumb smoothly`() {
        val metrics = calculateCatalogScrollbarMetrics(
            totalItemsCount = 100,
            visibleItemsCount = 10f,
            firstVisibleItemIndex = 44,
            firstVisibleItemScrollFraction = 1f,
        )

        assertEquals(0.50f, metrics!!.thumbOffsetFraction, 0.001f)
    }

    @Test
    fun `short catalog hides scrollbar`() {
        assertNull(
            calculateCatalogScrollbarMetrics(
                totalItemsCount = 1,
                visibleItemsCount = 1f,
                firstVisibleItemIndex = 0,
            ),
        )
        assertNull(
            calculateCatalogScrollbarMetrics(
                totalItemsCount = 5,
                visibleItemsCount = 8f,
                firstVisibleItemIndex = 0,
            ),
        )
    }

    @Test
    fun `invalid and out of range positions are clamped`() {
        val beforeStart = calculateCatalogScrollbarMetrics(100, 10f, -8, -1f)
        val afterEnd = calculateCatalogScrollbarMetrics(100, 10f, 999, 3f)

        assertEquals(0f, beforeStart!!.thumbOffsetFraction, 0f)
        assertEquals(1f, afterEnd!!.thumbOffsetFraction, 0f)
        assertNull(calculateCatalogScrollbarMetrics(0, 0f, 0))
    }
}
