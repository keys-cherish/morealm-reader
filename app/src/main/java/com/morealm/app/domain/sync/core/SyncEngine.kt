package com.morealm.app.domain.sync.core

import com.morealm.app.domain.entity.ReadProgress
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 同步编排器：download → [mergeRecords] → 回写本地 → 上传合并全集。
 *
 * 与实体解耦：调用方提供本地记录集与「远端胜出回写」回调，引擎不认识具体实体。
 * 两端各自跑 [sync] 后收敛到同一集合（合并内核的确定性保证，见 SyncMergeTest）。
 */
class SyncEngine(private val transport: SyncTransport) {

    /**
     * 同步一个 collection。
     * @param applyLocal 远端胜出的记录（含墓碑）回写本地；空集不回调
     * @return 合并结果（调用方可据此打日志/统计）
     */
    suspend fun sync(
        collection: String,
        localRecords: List<SyncRecord>,
        applyLocal: suspend (List<SyncRecord>) -> Unit,
    ): MergeResult {
        val remote = transport.download(collection)?.records ?: emptyList()
        val result = mergeRecords(localRecords, remote)
        if (result.toApplyLocal.isNotEmpty()) applyLocal(result.toApplyLocal)
        if (result.toUpload.isNotEmpty()) {
            transport.upload(collection, SyncEnvelope(records = result.merged))
        }
        return result
    }
}

/**
 * 阅读进度 ↔ SyncRecord 编解码。
 *
 * syncId 用 `progress:<bookId>`：bookId 在 WebDav 备份恢复 / 远程书导入路径下跨设备
 * 一致（同一本书同 id）。手动分别导入同一文件产生不同 id 的场景不在 v1 收敛范围，
 * 届时在 Book.extras 里放内容指纹升级 syncId 即可（记录模型不用动）。
 */
object ProgressSyncCodec {
    const val COLLECTION = "progress"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(progress: ReadProgress, deviceId: String): SyncRecord = SyncRecord(
        syncId = "progress:${progress.bookId}",
        updatedAt = progress.updatedAt,
        deviceId = deviceId,
        deleted = false,
        payload = json.encodeToString(progress),
    )

    /** 解码失败（未来版本字段不兼容等）返回 null，调用方跳过该记录不中断同步。 */
    fun decode(record: SyncRecord): ReadProgress? =
        runCatching { json.decodeFromString<ReadProgress>(record.payload) }.getOrNull()
}
