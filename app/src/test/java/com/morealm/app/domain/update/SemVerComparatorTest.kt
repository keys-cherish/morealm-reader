package com.morealm.app.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * compareSemVer 单元测试。
 *
 * 覆盖项目实际用法：
 *  - alpha → beta → 正式版的预发布排序
 *  - 当前 versionName "1.0.0-alpha1" 的形态
 *  - tag 前导 v 容错（GitHub tag 习惯写 v1.0.0）
 *  - 缺位补零、非法输入兜底
 */
class SemVerComparatorTest {

    @Test
    fun `正式版大于同主版本的预发布`() {
        assertTrue(compareSemVer("1.0.0", "1.0.0-alpha1") > 0)
        assertTrue(compareSemVer("1.0.0-alpha1", "1.0.0") < 0)
    }

    @Test
    fun `alpha 内部按数字递增`() {
        assertTrue(compareSemVer("1.0.0-alpha2", "1.0.0-alpha1") > 0)
        assertTrue(compareSemVer("1.0.0-alpha1", "1.0.0-alpha2") < 0)
    }

    @Test
    fun `beta 大于 alpha`() {
        // 字典序保证：'b' > 'a'
        assertTrue(compareSemVer("1.0.0-beta1", "1.0.0-alpha2") > 0)
        assertTrue(compareSemVer("1.0.0-rc1", "1.0.0-beta9") > 0)
    }

    @Test
    fun `主版本号优先于预发布标识`() {
        assertTrue(compareSemVer("1.0.1-alpha1", "1.0.0") > 0)
        assertTrue(compareSemVer("1.1.0-alpha1", "1.0.99") > 0)
        assertTrue(compareSemVer("2.0.0-alpha1", "1.99.99") > 0)
    }

    @Test
    fun `前导 v 容错`() {
        assertEquals(0, compareSemVer("v1.0.0", "1.0.0"))
        assertEquals(0, compareSemVer("V1.0.0-alpha1", "1.0.0-alpha1"))
        assertTrue(compareSemVer("v1.0.1", "1.0.0") > 0)
    }

    @Test
    fun `缺位补零`() {
        assertEquals(0, compareSemVer("1.0", "1.0.0"))
        assertEquals(0, compareSemVer("1", "1.0.0"))
        assertTrue(compareSemVer("1.1", "1.0.99") > 0)
    }

    @Test
    fun `相等的版本返回 0`() {
        assertEquals(0, compareSemVer("1.0.0-alpha1", "1.0.0-alpha1"))
        assertEquals(0, compareSemVer("1.0.0", "1.0.0"))
    }

    @Test
    fun `非法输入不抛异常返回 0 或合理值`() {
        // 全非法 → 都被当作 0.0.0 + 空 preRelease
        assertEquals(0, compareSemVer("garbage", "garbage"))
        assertEquals(0, compareSemVer("", ""))
        // 一边正常一边非法：非法被当 0.0.0
        assertTrue(compareSemVer("1.0.0", "garbage") > 0)
    }

    @Test
    fun `项目实际场景 alpha1 升 alpha2 应识别为有更新`() {
        // 模拟 ProfileScreen 场景：当前 1.0.0-alpha1，远端 latest tag 1.0.0-alpha2
        val current = "1.0.0-alpha1"
        val remote = "1.0.0-alpha2"
        assertTrue("应识别为有更新", compareSemVer(current, remote) < 0)
    }

    @Test
    fun `项目实际场景 alpha 升正式版应识别为有更新`() {
        val current = "1.0.0-alpha1"
        val remote = "1.0.0"
        assertTrue("alpha → 正式版应升级", compareSemVer(current, remote) < 0)
    }
}
