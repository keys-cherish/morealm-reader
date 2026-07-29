package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 书架顶部分组 tab 的自定义分组。与 [BookGroup]（文件夹体系，`Book.folderId`
 * 单值成员制）是并存的两套组织维度：本表走 [ShelfGroupBook] 多对多成员制，
 * 一本书可同时属于多个分组。预置智能分组（在读/想读/已读，按阅读进度实时
 * 归类）不落库，「全部」恒在不可删——本表只存用户自建分组。
 */
@Serializable
@Entity(tableName = "shelf_groups")
data class ShelfGroup(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** [ShelfGroup] 的多对多成员关联：一行 = 一本书在一个分组里。 */
@Serializable
@Entity(
    tableName = "shelf_group_books",
    primaryKeys = ["groupId", "bookId"],
    indices = [Index("groupId"), Index("bookId")],
)
data class ShelfGroupBook(
    val groupId: String,
    val bookId: String,
    val addedAt: Long = System.currentTimeMillis(),
)
