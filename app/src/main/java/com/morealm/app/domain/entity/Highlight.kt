package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 用户在阅读器里产出的"段落高亮"。
 *
 * 设计要点
 * - 用「章节级字符 offset」(`startChapterPos` / `endChapterPos`) 而不是 `TextPos`
 *   作为定位主键。原因：`TextPos` 含 `lineIndex` / `columnIndex`，依赖排版结果 —
 *   字号/字体/页宽变化都会让同一段文字出现在不同 line/column，存出来下次打开就对不上
 *   位置。章节字符 offset 是字符流上的不变量，跟随章节文本本身，不被排版扰动。
 * - 命中检测时，渲染器拿到 `(startChapterPos, endChapterPos)`，
 *   按当前页 `TextLine.chapterPosition` + `charSize` 反向投影回 (line, col) 范围
 *   再画矩形。
 * - 颜色通过 `colorArgb` 直接存 ARGB，ColorPalette 提供推荐预设；用户不局限于预设，
 *   未来可加自定义。
 * - `bookTitle`/`chapterTitle` 冗余存盘是为了后续在「我的高亮」总览页 / 分享卡片
 *   不需要联表回查 Book + BookChapter，删除原书或换书源后高亮元数据仍可用。
 */
@Serializable
@Entity(
    tableName = "highlights",
    indices = [
        Index("bookId"),
        Index("bookId", "chapterIndex"),
        Index("createdAt"),
    ],
)
data class Highlight(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String = "",
    val bookTitle: String = "",
    val startChapterPos: Int,
    val endChapterPos: Int,
    val content: String,
    val colorArgb: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
