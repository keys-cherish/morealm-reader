package com.morealm.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavHostGuardTest {
    @Test
    fun `激活 Tab 在主路由上可以导航`() {
        assertTrue(canNavigateFromMainTab(selectedTab = 3, sourceTab = 3, currentRoute = "main_tabs"))
    }

    @Test
    fun `隐藏 Tab 的导航请求被拒绝`() {
        assertFalse(canNavigateFromMainTab(selectedTab = 0, sourceTab = 3, currentRoute = "main_tabs"))
    }

    @Test
    fun `离开主路由后的滞后回调被拒绝`() {
        assertFalse(canNavigateFromMainTab(selectedTab = 3, sourceTab = 3, currentRoute = "reading_settings"))
    }
}
