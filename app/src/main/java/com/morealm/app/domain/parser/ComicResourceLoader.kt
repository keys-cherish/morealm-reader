package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 漫画图片资源加载器统一接口 —— MOBI/AZW3 与 EPUB 共用。
 *
 * 设计动机：原 [ComicReaderViewModel] 直接调用 [MobiResourceLoader.activate]，把
 * 漫画阅读管线绑死在 MOBI 实现上。引入 EPUB 漫画检测后，EPUB 进 ComicReaderScreen
 * 后必崩（黑屏 / "未在该文件中解析到图片"）。
 *
 * 抽象后两者并存：VM 按 [com.morealm.app.domain.entity.BookFormat] 派发到对应 loader，
 * Screen 通过 [ComicResourceRegistry] 按 hash 反查 loader 读图字节，对调用方透明。
 */
interface ComicResourceLoader {
    /**
     * 加载资源索引；同时把自己注册到 [ComicResourceRegistry] 以便后续 [readBytes]
     * 通过 hash 路由回来。失败返回 null。
     */
    fun activate(context: Context, uri: Uri): ComicResourceIndex?

    /**
     * 读第 [imageIndex] 张图的字节（**1-based**，与 [ComicResourceIndex.totalImages] 对齐）。
     * 失败返回 null —— 调用方负责显示占位。
     */
    fun readBytes(context: Context, hash: String, imageIndex: Int): ByteArray?
}

/**
 * 索引快照 —— hash 是后续 [ComicResourceLoader.readBytes] 的路由 key。
 * MOBI 用 uri.hashCode()，EPUB 用相同策略；只要同一 loader 内 hash 唯一即可。
 */
data class ComicResourceIndex(
    val hash: String,
    val totalImages: Int,
)

/**
 * Loader 注册表 —— 解决 [com.morealm.app.ui.reader.comic.ComicReaderScreen.ComicPage]
 * 只持有 hash + imageIndex 但需要按 hash 反查 loader 的问题。
 *
 * 生命周期：loader.activate 时 register，loader 自行管理 unregister（一般不主动卸载，
 * 因为同一进程内同一文件 hash 复用就是 cache hit）。
 */
object ComicResourceRegistry {
    private val loaders = ConcurrentHashMap<String, ComicResourceLoader>()

    fun register(hash: String, loader: ComicResourceLoader) {
        loaders[hash] = loader
    }

    fun unregister(hash: String) {
        loaders.remove(hash)
    }

    /** 反查 + 读字节。loader 未注册返回 null。 */
    fun readBytes(context: Context, hash: String, imageIndex: Int): ByteArray? =
        loaders[hash]?.readBytes(context, hash, imageIndex)
}

/**
 * [MobiResourceLoader] 的 [ComicResourceLoader] 适配器。
 *
 * 不直接让 MobiResourceLoader 实现接口的原因：它的 [MobiResourceLoader.activate]
 * 已经被 [MobiParser] 与 [com.morealm.app.domain.render.ImageCache] 用 —— 返回详细的
 * [MobiResourceIndex]（含 PDB record offsets），改签名会牵连多处。adapter 更稳。
 */
object MobiComicResourceLoader : ComicResourceLoader {
    override fun activate(context: Context, uri: Uri): ComicResourceIndex? {
        val mobiIndex = MobiResourceLoader.activate(context, uri) ?: return null
        if (mobiIndex.images.isEmpty()) return null
        ComicResourceRegistry.register(mobiIndex.hash, this)
        return ComicResourceIndex(mobiIndex.hash, mobiIndex.images.size)
    }

    override fun readBytes(context: Context, hash: String, imageIndex: Int): ByteArray? =
        MobiResourceLoader.readBytes(context, hash, imageIndex)
}
