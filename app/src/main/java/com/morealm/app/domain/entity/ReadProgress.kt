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
    val updatedAt: Long = System.currentTimeMillis(),
)
