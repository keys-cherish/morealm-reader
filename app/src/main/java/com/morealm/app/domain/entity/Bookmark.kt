package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")]
)
data class Bookmark(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String = "",
    val content: String = "",  // snippet of text near bookmark
    /**
     * 滚动模式下的回退定位：当前章节的 0-100 滚动百分比。
     * 非滚动模式（SLIDE / SIMULATION / COVER）只在没有 [chapterPos] 时退化使用。
     */
    val scrollProgress: Int = 0,
    /**
     * 章内字符偏移（对齐 Legado Bookmark.chapterPos）。
     *
     * - 0 表示章节首字符；用 `TextPage.chapterPosition + getPosByLineColumn(...)` 计算得到。
     * - 跳转书签时优先用此字段精确定位到具体页（仿真/滑动/覆盖翻页都生效）；
     * - 老书签由 v23→v24 迁移补 0，跳转时退化到章节首页（行为兼容）。
     */
    val chapterPos: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
