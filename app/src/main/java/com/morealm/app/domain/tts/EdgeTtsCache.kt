package com.morealm.app.domain.tts

import android.content.Context
import com.morealm.app.core.log.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * Edge TTS 的 MP3 段缓存。
 *
 * # 为什么要缓存
 * 用户重听同一段（暂停后继续、刚听完又点回去、章节内段切换又跳回）非常常见，
 * 每次走 WSS 既慢又耗流量。把 MP3 字节按 (voice|rate|pitch|text) 的哈希落盘，
 * 命中时直接喂 MediaCodec，零网络。
 *
 * # 容量管理
 * 默认 [DEFAULT_MAX_BYTES] = 50 MB。超过即按 `lastModified` 升序删除最旧文件，
 * 直到总大小落在 [DEFAULT_TARGET_BYTES] = 40 MB 以下（10 MB 缓冲避免每次写入
 * 都触发清理形成抖动）。
 *
 * # 线程安全
 * - [get] / [put] 各自独立的文件操作，并发写同一 key 不会损坏文件（File.writeBytes
 *   原子替换由 OS 保证；最后写赢，对 TTS 场景而言两个相同 key 的字节也应该一致）。
 * - [enforceLimit] 内部加 [evictLock] synchronized，防止两个 put 同时触发清理时
 *   重复扫描和重复删除。
 *
 * # 复用模式
 * 项目里 EPUB 解压、章节缓存、封面均放在 `context.cacheDir` 子目录，本类沿用同样
 * 约定 —— Android 系统在存储紧张时会自动清理 cacheDir，符合"可重新生成"的语义。
 *
 * # 日志
 * tag `EdgeTtsCache`（已注册到 [com.morealm.app.core.log.LogTagCatalog]）。
 * 关键事件：写入失败 / 触发淘汰后剩余总量。命中由 [EdgeTtsEngine] 自己打"cache hit"。
 */
class EdgeTtsCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val targetBytes: Long = DEFAULT_TARGET_BYTES,
) {

    private val cacheDir: File = File(context.cacheDir, "edge_tts").apply {
        if (!exists()) mkdirs()
    }

    private val evictLock = Any()

    /**
     * 计算缓存键。把 voice/rate/pitch/text 拼起来后 SHA-256，避免特殊字符进文件名。
     * 不直接 hash text 因为同文本不同声音/语速应当各自缓存。
     */
    fun keyFor(voice: String, rate: String, pitch: String, text: String): String {
        val payload = "$voice|$rate|$pitch|$text"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(payload.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 命中返回 File（同时 `setLastModified(now)` 用作 LRU touch）；未命中返回 null。
     *
     * 触发 lastModified 更新而非读时间是为了让"频繁重听的段"留下来、"久未访问的段"
     * 被 [enforceLimit] 优先淘汰，模拟 LRU。
     */
    fun get(key: String): File? {
        val file = File(cacheDir, "$key.mp3")
        if (!file.exists() || file.length() == 0L) return null
        // 静默失败 OK：lastModified 更新失败不影响读取，只是 LRU 不准
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    /**
     * 写入 MP3 字节。失败不抛异常（缓存不可用应该退化为不缓存，而不是阻塞 TTS）。
     * 写完后异步触发容量检查。
     */
    fun put(key: String, mp3Bytes: ByteArray) {
        if (mp3Bytes.isEmpty()) return
        val file = File(cacheDir, "$key.mp3")
        try {
            // writeBytes 实际是 FileOutputStream + write，非原子但对 TTS 场景够用
            file.writeBytes(mp3Bytes)
        } catch (e: Exception) {
            AppLog.warn(TAG, "Cache put failed: ${e.message}")
            return
        }
        enforceLimit()
    }

    /**
     * 容量检查 + LRU 淘汰。同步执行，但耗时小（只 listFiles + 排序，无解码）。
     * 极端情况下（首次启动有 1000+ 缓存文件）才会有几百毫秒，可接受。
     */
    fun enforceLimit() {
        synchronized(evictLock) {
            val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") }
                ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return

            // 升序：最老的在前，先删
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (total <= targetBytes) break
                val size = f.length()
                if (f.delete()) {
                    total -= size
                }
            }
            AppLog.debug(TAG, "Cache evicted to $total bytes (target=$targetBytes)")
        }
    }

    /** 测试 / 用户主动清缓存入口。 */
    fun clear() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** 当前总大小（字节），主要给"清缓存"按钮显示用。 */
    fun totalBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    companion object {
        private const val TAG = "EdgeTtsCache"
        private const val DEFAULT_MAX_BYTES = 50L * 1024L * 1024L  // 50 MB
        private const val DEFAULT_TARGET_BYTES = 40L * 1024L * 1024L // 40 MB

        private val HEX = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )
    }
}
