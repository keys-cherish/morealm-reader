package com.morealm.app.ui.reader.renderer

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Pure-function unit tests for [ReaderPageFactory] cross-chapter unified
 * pageCount mathematics (SLIDE / COVER paging path, 2026-05-18).
 *
 * 不构造 ReaderPageFactory instance —— TextChapter / TextPage 依赖复杂，
 * 测 companion object 纯函数足以覆盖三向映射 + 边界 case，是
 * [ReaderPageFactory.localFromUnifiedIndex] 与 [ReaderPageFactory.remapUnifiedAfterChapterShift]
 * 的语义契约定义。
 *
 * 关键 invariant：
 *   - 联合页号 [0, prevCount + curCount + nextCount) 三段映射零重叠零空隙
 *   - chapter NEXT shift 后 unified currentPage = newPrevCount + 0 (新 cur 首页)
 *   - chapter PREV shift 后 unified currentPage = newPrevCount + (newCurCount - 1) (新 cur 末页)
 */
class ReaderPageFactoryUnifiedTest {

    // ─── localFromUnifiedIndex 三向映射纯函数 ──────────────────────────

    @Test
    fun localFromUnified_inPrevRange_returnsPrevAndLocal() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 0, prevCount = 3, curCount = 5, nextCount = 4,
        )
        assertEquals("chapterRelative=-1 (prev)", -1, rel)
        assertEquals("local=0", 0, local)
    }

    @Test
    fun localFromUnified_lastPrevPage_returnsPrevAndLastLocal() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 2, prevCount = 3, curCount = 5, nextCount = 4,
        )
        assertEquals(-1, rel)
        assertEquals(2, local)
    }

    @Test
    fun localFromUnified_firstCurPage_returnsCurAndZero() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 3, prevCount = 3, curCount = 5, nextCount = 4,
        )
        assertEquals("chapterRelative=0 (cur)", 0, rel)
        assertEquals(0, local)
    }

    @Test
    fun localFromUnified_lastCurPage_returnsCurAndLastLocal() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 7, prevCount = 3, curCount = 5, nextCount = 4,
        )
        assertEquals(0, rel)
        assertEquals(4, local)
    }

    @Test
    fun localFromUnified_firstNextPage_returnsNextAndZero() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 8, prevCount = 3, curCount = 5, nextCount = 4,
        )
        assertEquals("chapterRelative=+1 (next)", 1, rel)
        assertEquals(0, local)
    }

    @Test
    fun localFromUnified_lastNextPage_returnsNextAndLastLocal() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 11, prevCount = 3, curCount = 5, nextCount = 4,
        )
        assertEquals(1, rel)
        assertEquals(3, local)
    }

    // ─── 边界 case：prev / next 缺失 ────────────────────────────────────

    @Test
    fun localFromUnified_noPrevChapter_curStartsAtZero() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 0, prevCount = 0, curCount = 5, nextCount = 4,
        )
        assertEquals("首章场景：cur 从 unified=0 开始", 0, rel)
        assertEquals(0, local)
    }

    @Test
    fun localFromUnified_noNextChapter_lastCurPage_correct() {
        // prev=3, cur=5, next=0 → prev[0..2] = unified[0..2]; cur[0..4] = unified[3..7]
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 7, prevCount = 3, curCount = 5, nextCount = 0,
        )
        assertEquals("cur 末页（无 next）", 0, rel)
        assertEquals(4, local)
    }

    @Test
    fun localFromUnified_negativeIndex_clampedToCurStart() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = -1, prevCount = 3, curCount = 5, nextCount = 4,
        )
        // 越界负值 fallback 到 cur 首页
        assertEquals(0, rel)
        assertEquals(0, local)
    }

    @Test
    fun localFromUnified_indexBeyondTotal_clampedToCurLast() {
        val (rel, local) = ReaderPageFactory.localFromUnifiedIndex(
            unifiedIndex = 100, prevCount = 3, curCount = 5, nextCount = 4,
        )
        // 越界超大值 fallback 到 cur 末页
        assertEquals(0, rel)
        assertEquals(4, local)
    }

    // ─── remapUnifiedAfterChapterShift ──────────────────────────────────

    @Test
    fun remapAfterNextShift_targetsNewCurFirstPage() {
        // 章节 NEXT shift：旧 prev=3/cur=5/next=4 → 新 prev=5(旧 cur)/cur=4(旧 next)/next=?
        // 用户视觉跨过 cur 边界落在 unified=8（旧 next 首页）→ shift 后旧 next 成新 cur
        // 期望新 unified currentPage = newPrev(5) + 0 = 5
        val newUnified = ReaderPageFactory.remapUnifiedAfterChapterShift(
            oldUnified = 8,
            isNextShift = true,
            isPrevShift = false,
            newPrevCount = 5,
            newCurCount = 4,
        )
        assertEquals("NEXT shift → 新 cur 首页 = newPrevCount", 5, newUnified)
    }

    @Test
    fun remapAfterPrevShift_targetsNewCurLastPage() {
        // PREV shift：旧 prev=3/cur=5/next=4 → 新 prev=?/cur=3(旧 prev)/next=5(旧 cur)
        // 用户视觉跨过 cur 顶部落在 unified=2（旧 prev 末页）→ shift 后旧 prev 成新 cur
        // 期望新 unified currentPage = newPrev + (newCur-1) = 0 + 2 = 2（新 cur 末页）
        val newUnified = ReaderPageFactory.remapUnifiedAfterChapterShift(
            oldUnified = 2,
            isNextShift = false,
            isPrevShift = true,
            newPrevCount = 0,
            newCurCount = 3,
        )
        assertEquals("PREV shift → 新 cur 末页", 2, newUnified)
    }

    @Test
    fun remapAfterNextShift_newPrevCountZero_targetsZero() {
        // 极端 case：shift 后 prev 未加载（newPrevCount=0）→ 新 cur 首页 = unified 0
        val newUnified = ReaderPageFactory.remapUnifiedAfterChapterShift(
            oldUnified = 5,
            isNextShift = true,
            isPrevShift = false,
            newPrevCount = 0,
            newCurCount = 4,
        )
        assertEquals(0, newUnified)
    }

    @Test
    fun remapAfterPrevShift_newCurCountOne_targetsNewPrevCount() {
        // 极端 case：shift 后 cur 只 1 页 → 末页 = 首页 = newPrev + 0
        val newUnified = ReaderPageFactory.remapUnifiedAfterChapterShift(
            oldUnified = 0,
            isNextShift = false,
            isPrevShift = true,
            newPrevCount = 7,
            newCurCount = 1,
        )
        assertEquals(7, newUnified)
    }

    @Test
    fun remapNoShift_returnsOriginalUnified() {
        // 章内翻页 no shift → 返回原 unified
        val newUnified = ReaderPageFactory.remapUnifiedAfterChapterShift(
            oldUnified = 5,
            isNextShift = false,
            isPrevShift = false,
            newPrevCount = 3,
            newCurCount = 5,
        )
        assertEquals(5, newUnified)
    }
}
