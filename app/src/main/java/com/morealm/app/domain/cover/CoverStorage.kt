package com.morealm.app.domain.cover

import android.net.Uri
import java.io.File

/**
 * 用户自定义封面存储抽象。
 *
 * 职责：
 *  - 把用户选择的原图（通常 2-5MB）**低 IO**地转成 600px 长边 + WebP 80% （30-80KB）
 *    并写入 App 私有目录 `filesDir/covers/{kind}/{ownerId}.webp`
 *  - 返回一个 `file://` URI，业务侧写入 DB 对应字段，Coil 无缝接管缓存
 *  - 删除 / 查询存在性
 *
 * 性能保证：
 *  - 所有 IO 都在 `Dispatchers.IO`
 *  - 写入时用 inJustDecodeBounds + inSampleSize 降采样，绝不把 2-5MB 原图整张 decode 进内存
 *  - WebP 有损 80% 相比 JPEG 同画质小 ~30%，相比 PNG 小 ~80%
 *
 * 扩展性（"好维护"体现在这）：
 *  - 加新 [CoverKind] 只需在枚举里加常量，存储逻辑完全复用
 *  - 业务层只和接口打交道，底层可换 encoding / 换云存储，调用方不动
 */
interface CoverStorage {
    /**
     * 把 [sourceUri] 指向的图片（相册选的 content:// 或 file://）处理后存储。
     *
     * @return 成功：存储后的 `file://` URI 字符串（写入 DB 的 customCoverUrl 字段）；
     *         失败：null（调用方应保持 DB 不变，显示原 coverUrl）
     */
    suspend fun saveCover(sourceUri: Uri, kind: CoverKind, ownerId: String): String?

    /**
     * 删除指定 owner 的自定义封面文件。DB 侧字段由调用方 set null。
     * 即使文件不存在也不抛异常，保证幂等。
     */
    suspend fun deleteCover(kind: CoverKind, ownerId: String)

    /**
     * 同步查询封面文件是否存在（给 DB 迁移 / 孤儿清理用）。
     * 主线程可调用（只做 File.exists()）。
     */
    fun getCoverFile(kind: CoverKind, ownerId: String): File?

    /**
     * 封面目录根路径（用于备份/恢复系统整体归档）。
     * 备份时 ZIP 进 `covers/` 条目，恢复时整目录解压即可。
     */
    fun getCoversRoot(): File
}
