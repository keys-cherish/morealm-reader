package com.morealm.app.domain.sync.core

import com.morealm.app.domain.sync.WebDavClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 同步传输抽象：合并内核（[mergeRecords]）只认字节，换服务端 = 换本接口实现。
 * 第一实现走既有 [WebDavClient]；未来自建服务端/局域网直连只增实现不改内核。
 */
interface SyncTransport {
    /** 拉取远端记录集；远端不存在时返回 null（首次同步）。 */
    suspend fun download(collection: String): SyncEnvelope?

    /** 全量覆盖上传记录集（LWW 合并后的 merged 全集）。 */
    suspend fun upload(collection: String, envelope: SyncEnvelope)
}

/** 远端存储的记录集信封（带 schema 版本，向后兼容留门）。 */
@Serializable
data class SyncEnvelope(
    val version: Int = 1,
    val records: List<SyncRecord> = emptyList(),
)

/**
 * WebDav 实现：每类实体一个 JSON 文件（`MoRealm/sync/<collection>.json`）。
 * 覆盖上传语义与 LWW 合并配套：上传的是合并后全集，慢端晚到也不会丢新记录
 * （它先 download 合并再 upload）。
 */
class WebDavSyncTransport(
    private val client: WebDavClient,
    private val basePath: String = "MoRealm/sync",
) : SyncTransport {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun download(collection: String): SyncEnvelope? {
        val path = "$basePath/$collection.json"
        if (!client.exists(path)) return null
        return runCatching {
            json.decodeFromString<SyncEnvelope>(client.download(path).decodeToString())
        }.getOrNull()
    }

    override suspend fun upload(collection: String, envelope: SyncEnvelope) {
        client.makeAsDir(basePath)
        client.upload(
            "$basePath/$collection.json",
            json.encodeToString(envelope).encodeToByteArray(),
            contentType = "application/json",
        )
    }
}
