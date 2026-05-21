package com.morealm.app.ui.reader.page

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test —— 验证 androidTest 基础设施跑得通 + [FakePageBitmapProvider]
 * 自身契约正确。
 *
 * 跑法：emulator / 真机 + `./gradlew :app:connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class FakePageBitmapProviderSmokeTest {

    @Test
    fun androidTest_infrastructure_smoke() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("instrumentation should provide context", ctx)
    }

    @Test
    fun bitmapAt_returns_non_null_for_valid_index() {
        val provider = FakePageBitmapProvider(pageCount = 5)
        val bmp = provider.bitmapAt(0, 100, 200)
        assertNotNull(bmp)
        assertEquals(100, bmp!!.width)
        assertEquals(200, bmp.height)
        bmp.recycle()
    }

    @Test
    fun bitmapAt_returns_null_for_out_of_bounds() {
        val provider = FakePageBitmapProvider(pageCount = 5)
        assertNull("negative index → null", provider.bitmapAt(-1, 100, 100))
        assertNull("index >= pageCount → null", provider.bitmapAt(5, 100, 100))
        assertNull("index way over → null", provider.bitmapAt(999, 100, 100))
    }

    @Test
    fun bitmapAt_returns_null_for_invalid_size() {
        val provider = FakePageBitmapProvider(pageCount = 3)
        assertNull("w=0 → null", provider.bitmapAt(0, 0, 100))
        assertNull("h=0 → null", provider.bitmapAt(0, 100, 0))
        assertNull("w<0 → null", provider.bitmapAt(0, -1, 100))
    }

    @Test
    fun pageCount_matches_constructor_arg() {
        assertEquals(14, FakePageBitmapProvider(pageCount = 14).pageCount)
        assertEquals(1, FakePageBitmapProvider(pageCount = 1).pageCount)
        assertEquals(100, FakePageBitmapProvider(pageCount = 100).pageCount)
    }
}
