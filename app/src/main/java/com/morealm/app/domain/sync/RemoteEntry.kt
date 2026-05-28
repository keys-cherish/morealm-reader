package com.morealm.app.domain.sync

import com.morealm.app.domain.entity.BookFormat

/**
 * 单条远程目录条目 — 文件夹或可识别书形文件。
 *
 * 替代 v1 的「平铺 List<RemoteBookFile>」：v2 远程书架按层级 lazy load 浏览，
 * 同一层既可能是子目录又可能是书文件，UI 需要区分图标 / 点击行为，所以用 sealed
 * class 强类型分发，避免 if/else 嗅探扩展名 / 标志字段。
 *
 * 与 [RemoteBookFile] 的关系：[File] 几乎等价但多了 [File.format]（避免 UI 再
 * 算一次扩展名 → BookFormat），且参与 sealed class 让 when 详尽。BFS 实际下载
 * 仍走 [RemoteBookFile]，因为下载只关心文件不关心目录，[RemoteBookManager.bfsCollectFiles]
 * 返回的是 [RemoteBookFile] 列表。
 */
sealed class RemoteEntry {
    abstract val name: String
    abstract val remotePath: String

    data class Folder(
        override val name: String,
        override val remotePath: String,
        val lastModifiedEpoch: Long,
    ) : RemoteEntry()

    data class File(
        override val name: String,
        override val remotePath: String,
        val size: Long,
        val lastModifiedEpoch: Long,
        val format: BookFormat,
    ) : RemoteEntry()
}

/**
 * 单条导入失败记录 —— Phase 2 UI banner 与"失败详情"对话框消费。
 *
 * 故意做成纯 data class 不挂任何 Throwable：远程导入失败原因往往是网络层
 * （404 / 401 / timeout）或本地 I/O（磁盘满），UI 只需要人话错误消息。
 * 上层 catch 后 `e.message?.take(120)` 写进 [reason]，UI 直接展示。
 *
 * @param remotePath 失败的远程路径，作为重试时的 key
 * @param fileName 显示用文件名（避免 UI 再解析 path）
 * @param reason 人话错误消息（"网络中断" / "401 鉴权失败" / "磁盘空间不足" 等），可空
 */
data class FailedImport(
    val remotePath: String,
    val fileName: String,
    val reason: String?,
)
