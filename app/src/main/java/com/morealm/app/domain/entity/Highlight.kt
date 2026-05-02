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
    /**
     * 高亮种类 —— 决定渲染层怎么用 [colorArgb]：
     *   - [KIND_BACKGROUND] (0)：传统背景高亮 —— 在选区文字下面铺一层 bgFill；
     *   - [KIND_TEXT_COLOR] (1)：字体强调色 —— 把选区文字的前景色（drawText 的
     *     paint.color）替换为 colorArgb，背景不动；适合"段落重点字句换色"。
     *
     * 数据层不区分两种 kind 的查询路径（同 bookId+chapterIndex 一起返回）；
     * 渲染层在 [com.morealm.app.ui.reader.renderer.PageContentDrawer.PageCanvas]
     * 内按 kind 分桶，分别画。
     *
     * 默认 0 —— v19→v20 迁移给历史行加列时填 0，行为等价于"全部都是背景高亮"，
     * 不破坏老数据。
     */
    val kind: Int = KIND_BACKGROUND,
) {
    companion object {
        /** 背景高亮（默认） —— 在文字下铺一层透明色块。 */
        const val KIND_BACKGROUND = 0
        /** 字体强调色 —— 替换文字前景色，不画背景。 */
        const val KIND_TEXT_COLOR = 1
    }
}
