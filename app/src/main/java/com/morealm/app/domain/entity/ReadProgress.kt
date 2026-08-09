package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "read_progress")
data class ReadProgress(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int = 0,
    /**
     * 章内字符偏移（章首 = 0）。所有翻页 / 滚动模式都用这个字段定位续读位置——
     * 它是排版无关的，跨设备 / 字号变化也精准。
     *
     * 历史背景：v27 之前还有一个 `scrollProgress: Int (0..100)` 百分比字段，
     * 滚动模式下作为兜底位置；但该百分比依赖章节字符总数 + 当前字号 + 行距等
     * 易变量，跨设备恢复时漂移明显。v27→v28 迁移把它彻底删了，所有模式统一
     * 用 [chapterPosition]，滚动模式靠 ScrollAnchor / bookmarkToAnchor 精准定位。
     */
    val chapterPosition: Int = 0,
    val chapterOffset: Float = 0f,
    val totalProgress: Float = 0f,
    /**
     * 章稳定 id（= BookChapter.url；EPUB 为 spine href，网络书为章 url）。
     * 空 = 旧数据没存过。恢复时若它与 chapterIndex 处的章对不上，按 id 重映射
     * 章号 —— 目录刷新 / 换源 / 书文件更新导致的章序漂移在这里被吸收。
     */
    @androidx.room.ColumnInfo(defaultValue = "") val chapterId: String = "",
    /**
     * 锚点处正文快照（[com.morealm.app.domain.render.layout.ANCHOR_SNIPPET_CP_SPAN]
     * 个 cp 跨度内的可见字符）。恢复时用它对 [chapterPosition] 做内容自校验：
     * 对得上直用，对不上就近搜索重定位（详见 AnchorTextIndex）。空 = 旧数据。
     */
    @androidx.room.ColumnInfo(defaultValue = "") val anchorSnippet: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
