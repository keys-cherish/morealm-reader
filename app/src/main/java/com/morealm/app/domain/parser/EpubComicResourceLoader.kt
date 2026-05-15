package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * EPUB 漫画的 [ComicResourceLoader] 实现。
 *
 * 与 [MobiComicResourceLoader] 并列：MOBI 走 PDB record offset 直读，EPUB 走
 * epublib lazy resources + spine 顺序遍历 img。两者对 [ComicReaderViewModel] /
 * [com.morealm.app.ui.reader.comic.ComicReaderScreen] 透明，都通过
 * [ComicResourceRegistry] 按 hash 反查。
 *
 * 状态：activate 后保存 hash → (uri, ordered hrefs) 映射，readBytes 时按 imageIndex
 * 反查 href 再调 [EpubParser.readResourceBytes]。activate 是幂等的，同一 uri 多次
 * 调用复用 hash + hrefs。
 */
object EpubComicResourceLoader : ComicResourceLoader {

    private const val TAG = "EpubComicLoader"

    private val hrefsByHash = ConcurrentHashMap<String, List<String>>()
    private val uriByHash = ConcurrentHashMap<String, Uri>()

    override fun activate(context: Context, uri: Uri): ComicResourceIndex? {
        val (hash, hrefs) = EpubParser.activateComicImages(context, uri) ?: return null
        hrefsByHash[hash] = hrefs
        uriByHash[hash] = uri
        ComicResourceRegistry.register(hash, this)
        AppLog.info(TAG, "activated hash=$hash images=${hrefs.size}")
        return ComicResourceIndex(hash = hash, totalImages = hrefs.size)
    }

    override fun readBytes(context: Context, hash: String, imageIndex: Int): ByteArray? {
        val hrefs = hrefsByHash[hash] ?: return null
        val uri = uriByHash[hash] ?: return null
        val zeroIdx = imageIndex - 1
        if (zeroIdx < 0 || zeroIdx >= hrefs.size) return null
        return EpubParser.readResourceBytes(context, uri, hrefs[zeroIdx])
    }
}
