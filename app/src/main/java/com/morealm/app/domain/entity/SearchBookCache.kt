package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 换源候选缓存 — 保存某本书在各个书源中的搜索命中结果，下次打开换源对话框直接展示，
 * 后台再启动一轮异步刷新（Legado-parity，对应 SearchBook db 持久化语义）。
 *
 * 设计取舍：
 * - **不复用 [SearchBook] 数据类作为 @Entity**：SearchBook 携带 lazy 字段、Json 序列化逻辑、
 *   transient HTML 缓存与可变 originOrder/variable，强行加 @Entity 会污染搜索链路且 Room
 *   编译期对 lazy/Transient 不友好。这里独立一张表，存"足够换源 UI 渲染"的最小子集。
 * - **复合主键 (bookUrl, origin)**：同一书源下相同书的多次写入幂等 REPLACE。
 * - **(bookName, author) 索引**：换源列表查询路径，O(log N) 命中。
 *
 * 老化策略：由调用方在 onCreate 时按时间清理（保留 7 天），不靠 db 触发器以保留可观测性。
 */
@Entity(
    tableName = "search_book_cache",
    primaryKeys = ["bookUrl", "origin"],
    indices = [
        Index("bookName"),
        Index("author"),
        Index("time"),
    ],
)
data class SearchBookCache(
    /** 候选书在新源中的详情页 URL — 主键之一。 */
    val bookUrl: String,
    /** 源唯一 URL — 主键之一，对应 [BookSource.bookSourceUrl]。 */
    val origin: String,
    /** 源名称（冗余存一份，避免每次换源 join 一次 BookSource 表）。 */
    val originName: String,
    /** 用于按书检索的索引字段；一般 = 原书的 title。 */
    val bookName: String,
    /** 用于按书检索的索引字段；一般 = 原书的 author。 */
    val author: String,
    /** BookSource.bookSourceType（0=文本，1=音频…），换源 UI 只展示文本类。 */
    val type: Int = 0,
    val coverUrl: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    /** 新源给出的字数文案（"123万字"等），可空。 */
    val wordCount: String? = null,
    /** 新源「最新章节」标题，缺失时排序会把该候选下沉。 */
    val latestChapterTitle: String? = null,
    /** 新源目录页 URL；为空时用 bookUrl 当 toc anchor。 */
    val tocUrl: String = "",
    /** 来自 [BookSource.customOrder]，候选排序 secondary key（小者优先）。 */
    val originOrder: Int = 0,
    /** 搜索此候选时的耗时（毫秒），候选排序 tertiary key（小者优先）。 */
    val responseTime: Long = 0L,
    /** 写入时间，老化用。 */
    val time: Long = System.currentTimeMillis(),
)
