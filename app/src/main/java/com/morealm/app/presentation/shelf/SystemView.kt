package com.morealm.app.presentation.shelf

import com.morealm.app.domain.entity.BookFormat

/**
 * The seven smart shelves that show up by default — these are not stored,
 * they're computed views over the book table.
 *
 * Why baked-in instead of user-configurable? Because they're **defaults**
 * — what every user gets on first launch with zero configuration. The point
 * is "装上即用" (works out of the box). Power users can still build their
 * own filters via the chip row + custom tags in P4.
 *
 * Each view carries the SQL fragment in spirit only — actual queries live in
 * [com.morealm.app.domain.db.BookDao] so Room can validate them at compile
 * time. The mapping is intentional: change a view's semantic here, you'll
 * also touch the DAO.
 */
enum class SystemView(
    val displayName: String,
    val emoji: String,
    val description: String,
) {
    CONTINUE_READING("继续阅读", "📖", "最近 7 天读过的"),
    FOLLOWING_UPDATES("追更中", "🔥", "持续更新的网络书"),
    NEW_THIS_WEEK("本周新加", "✨", "刚加入书架的书"),
    STALE("搁置已久", "😴", "30 天没翻过且未读完"),
    FINISHED("已读完", "✅", "进度过 95% 的书"),
    LOCAL_FILES("离线书", "📁", "TXT / EPUB / PDF / MOBI"),
    BY_SOURCE("按来源", "🌐", "按书源聚合的网络书"),
    ;

    companion object {
        // Time windows used by the queries; centralised so tests can stub them.
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val WEEK_MS = 7 * DAY_MS
        const val FOLLOWING_WINDOW_MS = 14 * DAY_MS
        const val STALE_WINDOW_MS = 30 * DAY_MS
        const val FINISHED_THRESHOLD = 0.95f

        val LOCAL_FORMATS = listOf(
            BookFormat.TXT, BookFormat.EPUB, BookFormat.PDF,
            BookFormat.MOBI, BookFormat.AZW3, BookFormat.CBZ, BookFormat.UMD,
        ).map { it.name }
    }
}

/** Pairs a [SystemView] with the count of matching books — for badges in the UI. */
data class SystemViewCount(val view: SystemView, val count: Int)
