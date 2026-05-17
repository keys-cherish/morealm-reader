package com.morealm.app.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测覆盖 [EpubParser.isComicByMediaBytes] —— OPF 结构指纹判定 (Level 2)。
 *
 * 算法：N_img / N_html / Size_html_total 三维统计 + 三道指纹（不依赖任何关键词）：
 * - 指纹 1：N_img ≥ 10 && N_html/N_img ∈ [0.8, 1.2] → 一页一档漫画
 * - 指纹 2：N_html < N_img && 每图 html < 500B → Webtoon 长图滚动漫画
 * - 否则 → Novel（含 N_img < 10 样本量保护）
 */
class EpubParserTest {

    // ── 样本量保护：N_img < 10 一律 Novel ──

    @Test
    fun `pure text EPUB returns false`() {
        val items = listOf(
            "application/xhtml+xml" to 30_000L,
            "application/xhtml+xml" to 28_000L,
            "application/xhtml+xml" to 32_000L,
            "text/css" to 1_000L,
        )
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `novel with cover image and many chapters returns false`() {
        // 1 张封面 + 200 章 xhtml → nImg=1 < 10 → Novel
        val items = mutableListOf<Pair<String, Long>>("image/jpeg" to 50_000L)
        repeat(200) { items.add("application/xhtml+xml" to 30_000L) }
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `light novel with few illustrations (5-9 images) returns false`() {
        // 9 张插图 + 100 xhtml → nImg=9 < 10 → Novel（样本量保护）
        val items = (1..9).map { "image/jpeg" to 800_000L } +
            (1..100).map { "application/xhtml+xml" to 50_000L }
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    // ── 指纹 1：一页一档（N_html ≈ N_img） ──

    @Test
    fun `manga 100 images 100 xhtml wrappers returns true`() {
        // 漫画 EPUB 典型结构：每章 1 xhtml 包 1 img
        // nImg=100, nHtml=100, ratio=1.0 → fp-1 命中
        val items = mutableListOf<Pair<String, Long>>()
        repeat(100) {
            items.add("application/xhtml+xml" to 5_000L)
            items.add("image/jpeg" to 500_000L)
        }
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `exactly at fp1 ratio 0_8 returns true`() {
        // nImg=30, nHtml=24 → ratio=0.80 = 边界下限 → Comic
        val items = (1..30).map { "image/jpeg" to 100_000L } +
            (1..24).map { "application/xhtml+xml" to 5_000L }
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `exactly at fp1 ratio 1_2 returns true`() {
        // nImg=30, nHtml=36 → ratio=1.20 = 边界上限 → Comic
        val items = (1..30).map { "image/jpeg" to 100_000L } +
            (1..36).map { "application/xhtml+xml" to 5_000L }
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `fp1 ratio just below 0_8 falls through to novel`() {
        // nImg=30, nHtml=23 → ratio=0.766 < 0.80
        // fp-2: nHtml < nImg ✓, 但 avgHtml/img = 23*50K/30 ≈ 38KB >> 500B → 不命中
        // → Novel
        val items = (1..30).map { "image/jpeg" to 100_000L } +
            (1..23).map { "application/xhtml+xml" to 50_000L }  // 50KB/章 = 文字章
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `fp1 ratio above 1_2 falls through to novel`() {
        // nImg=15, nHtml=200 → ratio=13.3 远大于 1.2（轻小说典型）
        // fp-2: nHtml > nImg → 不命中
        // → Novel
        val items = (1..15).map { "image/jpeg" to 800_000L } +
            (1..200).map { "application/xhtml+xml" to 30_000L }
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    // ── 指纹 2：Webtoon 长图滚动（N_html << N_img + 每图 html 极小） ──

    @Test
    fun `webtoon many images few wrappers returns true`() {
        // Webtoon：1 个 html 包 100 张图，html 全是 img 标签
        // nHtml=1, nImg=100, htmlTotalBytes ≈ 10KB（100 个 img 标签）
        // avgHtmlPerImg = 100B < 500B → fp-2 命中
        val items = mutableListOf<Pair<String, Long>>("application/xhtml+xml" to 10_000L)
        repeat(100) { items.add("image/jpeg" to 500_000L) }
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `webtoon 10 chapters 200 images returns true`() {
        // 10 话每话 1 个 html 含 20 张图：nHtml=10, nImg=200
        // 每个 html ~ 4KB（20 个 img 标签）→ htmlTotalBytes 40KB
        // avgHtmlPerImg = 40000/200 = 200B < 500B → fp-2 命中
        val items = (1..10).map { "application/xhtml+xml" to 4_000L } +
            (1..200).map { "image/jpeg" to 300_000L }
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `dense text in few wrappers with many imgs falls through to novel`() {
        // 边界 case：nHtml=5, nImg=50, 但每 html 1MB（全是文字）
        // avgHtmlPerImg = 5*1MB/50 = 100KB >> 500B → fp-2 不命中
        // fp-1: ratio=0.1 不在 [0.8,1.2] → 不命中
        // → Novel
        val items = (1..5).map { "application/xhtml+xml" to 1_000_000L } +
            (1..50).map { "image/jpeg" to 100_000L }
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    // ── 纯图 / 边界 ──

    @Test
    fun `pure image EPUB no html wrapper returns true`() {
        // 30 张图，没 html 资源 → fp-2: nHtml=0 < 30 ✓, avgHtmlPerImg=0 < 500 ✓
        val items = (1..30).map { "image/jpeg" to 500_000L }
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `empty list returns false`() {
        assertFalse(EpubParser.isComicByMediaBytes(emptyList()))
    }

    @Test
    fun `only non-doc non-image media returns false`() {
        val items = listOf(
            "text/css" to 5_000L,
            "application/javascript" to 10_000L,
            "application/font-woff" to 80_000L,
        )
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    // ── mediaType 识别覆盖 ──

    @Test
    fun `webp images are counted as images`() {
        val items = (1..30).map { "image/webp" to 800_000L } +
            (1..30).map { "application/xhtml+xml" to 5_000L }
        // ratio 1.0 → fp-1 → Comic
        assertTrue(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `text html alongside few images returns false`() {
        // 老 EPUB 用 text/html 而非 xhtml；只 1 img → 样本量保护
        val items = listOf(
            "text/html" to 100_000L,
            "image/jpeg" to 10_000L,
        )
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `legacy OEB1 documents counted as text returns false on light novel`() {
        // 老 OEB1 mediaType + 15 张插图（轻小说）
        // 之前的 bug：text/x-oeb1-document 被忽略 → textBytes=0 → 误判 Comic
        // 新算法：nImg=15, nHtml=2 → ratio=0.13 不命中 fp-1，fp-2 avgHtml 极大 → Novel
        val items = listOf(
            "text/x-oeb1-document" to 100_000L,
            "application/oeb1+xml" to 80_000L,
        ) + (1..15).map { "image/jpeg" to 800_000L }
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `dtbook documents are counted as text not images`() {
        val items = listOf(
            "application/x-dtbook+xml" to 200_000L,
            "image/jpeg" to 30_000L,
        )
        // 1 img < 10 → 样本量保护 → Novel
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }

    @Test
    fun `negative or zero sizes are clamped to zero`() {
        // 2 imgs（负 size 当 0）+ 1 doc → nImg=2 < 10 → Novel
        val items = listOf(
            "image/jpeg" to -1L,
            "image/jpeg" to 0L,
            "application/xhtml+xml" to 100_000L,
        )
        assertFalse(EpubParser.isComicByMediaBytes(items))
    }
}
