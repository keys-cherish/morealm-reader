package com.morealm.app.domain.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreferenceSnapshot 单元测试 —— 纯 JSON 序列化 / 反序列化逻辑，不依赖 Android。
 *
 * 关键覆盖：
 *  1. **类型保真度**：Int / Long / Float / Double / Boolean / String / Set<String>
 *     都能准确 round-trip。这是引入 type tag 的核心动机 —— 没有 tag 时 JSON 数字
 *     `42` 会被解成 Int 而不是 Long，DataStore 用 longPreferencesKey 读不到。
 *  2. **黑名单常量**：[PreferenceSnapshot.IGNORED_KEYS] 包含敏感 / 单设备语义的 key。
 *  3. **健壮性**：未知 type tag、坏 JSON、空字符串都不抛异常。
 *  4. **空输入**：dump 空 map 给 "{}"；load("")/load("{}") 都给空 map。
 */
class PreferenceSnapshotTest {

    @Test
    fun `round-trip preserves Boolean`() {
        val src: Map<String, Any> = mapOf("auto_night_mode" to true, "fullscreen_tap" to false)
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals(true, out["auto_night_mode"])
        assertEquals(false, out["fullscreen_tap"])
        // 类型必须仍是 Boolean，不能是字符串 "true"
        assertTrue(out["auto_night_mode"] is Boolean)
    }

    @Test
    fun `round-trip preserves Int`() {
        val src: Map<String, Any> = mapOf("reader_margin" to 24, "screen_timeout" to -1)
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals(24, out["reader_margin"])
        assertEquals(-1, out["screen_timeout"])
        assertTrue(out["reader_margin"] is Int)
    }

    @Test
    fun `round-trip preserves Long`() {
        // 用一个超过 Int.MAX_VALUE 的值确保不被降级为 Int
        val src: Map<String, Any> = mapOf("last_auto_backup" to 9_999_999_999L)
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals(9_999_999_999L, out["last_auto_backup"])
        assertTrue("Long 必须保持 Long 类型", out["last_auto_backup"] is Long)
    }

    @Test
    fun `round-trip preserves Float`() {
        val src: Map<String, Any> = mapOf("reader_font_size" to 17.5f, "tts_speed" to 1.25f)
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals(17.5f, out["reader_font_size"])
        assertEquals(1.25f, out["tts_speed"])
        assertTrue(out["reader_font_size"] is Float)
    }

    @Test
    fun `round-trip preserves Double`() {
        val src: Map<String, Any> = mapOf("some_double" to 3.14159265358979)
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals(3.14159265358979, out["some_double"])
        assertTrue(out["some_double"] is Double)
    }

    @Test
    fun `round-trip preserves String`() {
        val src: Map<String, Any> = mapOf(
            "page_anim" to "cover",
            "tts_voice" to "zh-CN-XiaoxiaoNeural",
        )
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals("cover", out["page_anim"])
        assertEquals("zh-CN-XiaoxiaoNeural", out["tts_voice"])
    }

    @Test
    fun `round-trip preserves StringSet`() {
        val src: Map<String, Any> = mapOf(
            "auto_folder_ignored" to setOf("xuanhuan", "junshi", "yanqing"),
        )
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        @Suppress("UNCHECKED_CAST")
        val restored = out["auto_folder_ignored"] as Set<String>
        assertEquals(setOf("xuanhuan", "junshi", "yanqing"), restored)
    }

    @Test
    fun `mixed types in one snapshot all preserve`() {
        // 一次性写多种类型，模拟真实备份场景
        val src: Map<String, Any> = mapOf(
            "auto_night_mode" to true,
            "reader_margin" to 24,
            "last_auto_backup" to 12345L,
            "reader_font_size" to 17f,
            "page_anim" to "cover",
            "auto_folder_ignored" to setOf("a", "b"),
        )
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals(src.size, out.size)
        assertTrue(out["auto_night_mode"] is Boolean)
        assertTrue(out["reader_margin"] is Int)
        assertTrue(out["last_auto_backup"] is Long)
        assertTrue(out["reader_font_size"] is Float)
        assertTrue(out["page_anim"] is String)
        assertTrue(out["auto_folder_ignored"] is Set<*>)
    }

    @Test
    fun `IGNORED_KEYS contains expected sensitive keys`() {
        val ignored = PreferenceSnapshot.IGNORED_KEYS
        // 这些 key 跨设备恢复会出问题或扩大攻击面，必须在黑名单里
        assertTrue("webdav_pass 必须在黑名单（敏感）", "webdav_pass" in ignored)
        assertTrue("backup_password 必须在黑名单（敏感）", "backup_password" in ignored)
        assertTrue("last_auto_backup 必须在黑名单（恢复后调度会立即触发）", "last_auto_backup" in ignored)
        assertTrue("disclaimer_accepted 必须在黑名单（新设备应重看免责声明）", "disclaimer_accepted" in ignored)
    }

    @Test
    fun `IGNORED_KEYS does not contain general user prefs`() {
        // 反过来，常用用户偏好不应该被默认黑名单挡住，否则备份等于残废
        val ignored = PreferenceSnapshot.IGNORED_KEYS
        assertTrue("reader_font_size 不应在黑名单", "reader_font_size" !in ignored)
        assertTrue("page_anim 不应在黑名单", "page_anim" !in ignored)
        assertTrue("auto_night_mode 不应在黑名单", "auto_night_mode" !in ignored)
        assertTrue("tts_voice 不应在黑名单", "tts_voice" !in ignored)
    }

    @Test
    fun `dump empty map returns valid empty JSON object`() {
        val out = PreferenceSnapshot.dump(emptyMap())
        // 必须是合法 JSON object（"{}"）而不是 "" 或 "null"
        assertEquals("{}", out)
    }

    @Test
    fun `load empty string returns empty map`() {
        assertEquals(emptyMap<String, Any>(), PreferenceSnapshot.load(""))
        assertEquals(emptyMap<String, Any>(), PreferenceSnapshot.load("   "))
    }

    @Test
    fun `load empty JSON object returns empty map`() {
        assertEquals(emptyMap<String, Any>(), PreferenceSnapshot.load("{}"))
    }

    @Test
    fun `load malformed JSON returns empty map without throwing`() {
        // 损坏的 JSON 不应抛异常崩溃恢复流程，统一返回空 map 由调用方处理
        val out = PreferenceSnapshot.load("{not json at all")
        assertEquals(emptyMap<String, Any>(), out)
    }

    @Test
    fun `unknown type tag is silently skipped`() {
        // 未来版本可能引入新 type tag（如 "ll" = LongList）；当前版本读到时跳过
        // 而不是整个 load 失败 —— 让旧 app 能恢复新 zip 的"已知"部分
        val withUnknown = """{"foo":{"t":"zzz","v":42},"bar":{"t":"i","v":7}}"""
        val out = PreferenceSnapshot.load(withUnknown)
        assertNull("未识别 tag 应被跳过", out["foo"])
        assertEquals("已识别 tag 仍能解析", 7, out["bar"])
    }

    @Test
    fun `unsupported value type in dump is skipped`() {
        // DataStore 不支持的类型（比如 ByteArray）在 dump 时应被跳过，不让整个
        // dump 失败。这种情况理论上不会发生（AppPreferences 只用 6 种 raw 类型），
        // 但防御性编码值得 —— 用反射 / 第三方库往 DataStore 注入怪东西时不会炸。
        val src: Map<String, Any> = mapOf(
            "ok" to "kept",
            "weird" to byteArrayOf(1, 2, 3),
        )
        val out = PreferenceSnapshot.load(PreferenceSnapshot.dump(src))
        assertEquals("kept", out["ok"])
        assertNull("未知类型不应出现在 round-trip 结果里", out["weird"])
    }

    @Test
    fun `tagged JSON format follows documented shape`() {
        // 确保格式与文档描述一致：{ "key": { "t": "<tag>", "v": <value> } }
        // 这个测试是回归保护 —— 防止有人不小心改了 dump 输出格式后破坏旧 app 兼容。
        val src: Map<String, Any> = mapOf("page_anim" to "cover")
        val dumped = PreferenceSnapshot.dump(src)
        assertTrue("dumped JSON 必须含 t 标签", dumped.contains("\"t\":\"s\""))
        assertTrue("dumped JSON 必须含 v 字段", dumped.contains("\"v\":\"cover\""))
    }
}
