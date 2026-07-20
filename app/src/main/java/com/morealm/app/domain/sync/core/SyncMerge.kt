package com.morealm.app.domain.sync.core

import kotlinx.serialization.Serializable

/**
 * 多端同步的最小记录模型（批次5 合并内核）。
 *
 * 设计原则：**同步的是记录，不是数据库**。每类可同步实体（进度/高亮/书签/书架元数据）
 * 序列化成 [SyncRecord] 集合参与合并；合并是纯函数（[mergeRecords]），无平台依赖，
 * 未来可原样下沉 KMP 供多端复用。
 *
 * @property syncId   稳定业务键（如 progress:<bookUrl>、highlight:<uuid>），跨设备一致
 * @property updatedAt 最后修改时间戳 ms（LWW 依据；发生写入时必须刷新）
 * @property deviceId  产生本次修改的设备标识（并列时间戳时的稳定 tiebreaker）
 * @property deleted   墓碑：true = 已删除但保留记录参与合并（物理删除会让删除无法传播）
 * @property payload   实体 JSON（kotlinx.serialization 编码；合并内核不解析，只透传）
 */
@Serializable
data class SyncRecord(
    val syncId: String,
    val updatedAt: Long,
    val deviceId: String,
    val deleted: Boolean = false,
    val payload: String = "",
)

/** 一次合并的产物：三个集合互不重叠，调用方各取所需。 */
data class MergeResult(
    /** 合并后的全集（本地 ∪ 远端，每 syncId 取胜者）。 */
    val merged: List<SyncRecord>,
    /** 需要写回本地的记录（远端胜出且与本地不同，或本地缺失）。 */
    val toApplyLocal: List<SyncRecord>,
    /** 需要上传远端的记录（本地胜出且与远端不同，或远端缺失）。 */
    val toUpload: List<SyncRecord>,
)

/**
 * LWW（last-write-wins）逐记录合并内核。
 *
 * 胜负规则：updatedAt 大者胜；相等时 deviceId 字典序大者胜（保证两端各自合并
 * 得到**相同结果**——确定性是收敛的前提，比"谁赢"本身重要）。
 * 墓碑记录同样参与 LWW：删除动作携带新 updatedAt 即可传播到所有端。
 */
fun mergeRecords(local: List<SyncRecord>, remote: List<SyncRecord>): MergeResult {
    val localById = local.associateBy { it.syncId }
    val remoteById = remote.associateBy { it.syncId }
    val merged = ArrayList<SyncRecord>(maxOf(localById.size, remoteById.size))
    val toApply = ArrayList<SyncRecord>()
    val toUpload = ArrayList<SyncRecord>()

    for (id in localById.keys + remoteById.keys) {
        val l = localById[id]
        val r = remoteById[id]
        val winner = when {
            l == null -> r!!.also { toApply.add(it) }
            r == null -> l.also { toUpload.add(it) }
            else -> {
                val w = pickWinner(l, r)
                if (w != l) toApply.add(w)
                if (w != r) toUpload.add(w)
                w
            }
        }
        merged.add(winner)
    }
    return MergeResult(merged, toApply, toUpload)
}

private fun pickWinner(a: SyncRecord, b: SyncRecord): SyncRecord = when {
    a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
    else -> if (a.deviceId >= b.deviceId) a else b
}
