package com.morealm.app.domain.font

import android.graphics.Typeface
import com.morealm.app.core.log.AppLog
import com.morealm.epub.EpubBook
import com.morealm.epub.css.CssStylesheet
import java.net.URI

/**
 * **2026-05-25 EPUB 自带字体**：每本 EPUB 一个 registry，扫 manifest 所有 text/css → @font-face →
 * 把字体字节落盘 + 建 `family → Typeface` 映射。
 *
 * ## 何时构造
 * 跟 [EpubBook] 同生命周期：EpubCoreBridge 打开书后立即 [build]，挂到 cache Entry 上；
 * book close 时 registry 一并丢弃（落盘文件不删，靠 cache key 复用）。
 *
 * ## family lookup 规则
 * caller（renderer）拿到 `font-family: "kt"` → [resolve]`("kt")` → 命中 Typeface 或 null。
 * 大小写敏感对齐 CSS spec（多数 EPUB family 用 ASCII / 引号包裹中文，本表照原样存）。
 *
 * ## 跳过 woff/woff2
 * Android Typeface.Builder 不原生支持 woff/woff2 解码，本版本一律 ignore（log warn）。
 * caller 命中 family 但 typeface=null 时 fallback 系统字体，跟没声明 font-family 等价。
 *
 * ## 性能 / 内存
 * - 字体字节 ≈ 1-5 MB / 个，某 EPUB 4 个自定义 + 多个常规 = ~20 MB。落盘到 cacheDir/epub_fonts/
 *   (FontRepository.tryLoadFontBytes)，不常驻 RAM
 * - Typeface 自身是 native handle 不占多少 Java heap
 * - registry 实例本身只持 family→Typeface map (几十 KB)
 */
class EpubFontRegistry private constructor(
    private val families: Map<String, Typeface>,
) {

    /** family 名查 Typeface（精确匹配）；null = 没声明 / 加载失败 / woff2 不支持。 */
    fun resolve(family: String?): Typeface? {
        if (family.isNullOrEmpty()) return null
        return families[family]
    }

    /** 已注册的 family 数量（含成功落盘的）。 */
    val size: Int get() = families.size

    companion object {
        private const val TAG = "EpubFontRegistry"
        /** 空实例 —— 给没 CSS 字体或 build 失败的书复用，避免 null 检查噪声。 */
        val EMPTY: EpubFontRegistry = EpubFontRegistry(emptyMap())

        /**
         * 扫 [book] 的所有 text/css manifest item → 解析 @font-face → 落盘 + 建 family→Typeface
         * 映射。任何单一 css / src 失败仅跳过该项，不让 build 整体失败。
         *
         * @param bookKey 用作落盘文件名前缀，区分不同 EPUB 的同名 family（如两本书都有 "kt"）。
         *   建议传 EPUB 文件 path 的 hash 或 book id
         * @param fontRepo 用 [FontRepository.tryLoadFontBytes] 把字节落盘 + Typeface 化
         */
        fun build(book: EpubBook, bookKey: String, fontRepo: FontRepository): EpubFontRegistry {
            val cssItems = book.opfPackage.manifest.filter { it.mediaType == "text/css" }
            if (cssItems.isEmpty()) return EMPTY

            val map = HashMap<String, Typeface>(8)
            for (cssItem in cssItems) {
                val cssBytes = book.resource(cssItem.href) ?: continue
                val css = try {
                    CssStylesheet.parse(cssBytes.decodeToString())
                } catch (t: Throwable) {
                    AppLog.warn(TAG, "css parse 失败 href=${cssItem.href}: ${t.message}", t)
                    continue
                }
                if (css.fontFaces.isEmpty()) continue

                for (face in css.fontFaces) {
                    if (face.family.isEmpty() || face.sources.isEmpty()) continue
                    if (map.containsKey(face.family)) continue  // 多个 css 同 family 取首次声明

                    // sources 是 CSS src fallback list。本版本只用 url(...) 路径走 book.resource
                    // 取首个能加载成功的；local(...) 在 EPUB 上下文意义有限（书自带字体优先），
                    // 当前 CssFontFace 已只暴露 url tokens（详 CssFontFace.kt:21 注释）
                    val typeface = tryLoadFromSources(book, cssItem.href, face.sources, bookKey, face.family, fontRepo)
                    if (typeface != null) {
                        map[face.family] = typeface
                    }
                }
            }

            if (map.isEmpty()) return EMPTY
            AppLog.info(TAG, "EpubFontRegistry built for $bookKey: ${map.size} families = ${map.keys}")
            return EpubFontRegistry(map)
        }

        /**
         * 按 src 顺序尝试加载；跳过 woff/woff2；返回首个成功的 Typeface。
         *
         * @param cssHref OPF-relative css 路径（用于 url(...) 内相对路径解析的 base）
         */
        private fun tryLoadFromSources(
            book: EpubBook,
            cssHref: String,
            sources: List<String>,
            bookKey: String,
            family: String,
            fontRepo: FontRepository,
        ): Typeface? {
            for (src in sources) {
                val trimmed = src.trim()
                // 跳过 woff/woff2（Android Typeface 不支持），data:/ http:/ 协议无意义
                val lower = trimmed.lowercase()
                if (lower.endsWith(".woff") || lower.endsWith(".woff2")) {
                    AppLog.debug(TAG, "skip woff/woff2 src=$trimmed family=$family")
                    continue
                }
                if (lower.startsWith("data:") || lower.startsWith("http:") ||
                    lower.startsWith("https:") || lower.startsWith("res:") ||
                    lower.startsWith("file:")
                ) {
                    // res:/// 是 Kindle 设备本机字体路径，本机不存在；file:/// 是绝对路径（罕见且不安全）
                    continue
                }
                // 解析 url 相对路径：CSS url(../Fonts/x.ttf) 相对 cssHref（OPF-relative），
                // 拼出 OPF-relative font href 走 book.resource
                val fontHref = resolveRelative(cssHref, trimmed) ?: continue
                val bytes = book.resource(fontHref) ?: continue
                // bookKey + family 做 cache key，同书同 family 走同 cacheDir 文件
                val key = "${bookKey.hashCode().toUInt()}_${family.hashCode().toUInt()}"
                val typeface = fontRepo.tryLoadFontBytes(key, bytes)
                if (typeface != null) {
                    AppLog.debug(TAG, "loaded family=$family from $fontHref bytes=${bytes.size}")
                    return typeface
                }
            }
            AppLog.warn(TAG, "all sources failed for family=$family sources=$sources")
            return null
        }

        /**
         * Resolve [src] relative to [baseHref]; both are OPF-relative paths.
         * 与 ChapterReader.resolveRelative 同款语义：`../Fonts/x.ttf` 相对 `Styles/main.css`
         * → `Fonts/x.ttf`。Fail-soft 返回 null。
         */
        private fun resolveRelative(baseHref: String, src: String): String? {
            if (src.isEmpty()) return null
            return try {
                java.net.URLDecoder.decode(
                    URI(baseHref).resolve(src).toString(),
                    "UTF-8",
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * **2026-05-25**：当前 active book 的 registry。renderer 端通过 [resolveActive] 查 family
         * → Typeface，不用 prop drilling 把 registry 一路传到 PageContentDrawer / ChapterPaneCanvas /
         * PagePaneCanvas（影响 5+ 文件签名）。
         *
         * 同一时间用户只能阅读一本书，不存在并发：Reader 打开某书章节前 [setActive]，关书时
         * [clearActive]。多个 EpubReader 进程实例（罕见）共享同 active 也无害——renderer
         * 拿到的 family 一定来自 ScrollLine.blockStyle.fontFamily（已是当前书 cascade 产物），
         * 跟 active 同源。
         *
         * Atomic ref 保证读时 happens-before 关系；写不频繁（章节打开 / 关闭各 1 次）。
         */
        private val activeRegistry = java.util.concurrent.atomic.AtomicReference<EpubFontRegistry>(EMPTY)

        /** Reader 打开 EPUB 章节前调，注入当前书的 registry 让 renderer 端 [resolveActive] 拿到。 */
        fun setActive(registry: EpubFontRegistry) {
            activeRegistry.set(registry)
        }

        /** Reader 关闭 / 切书时调，避免 stale registry 污染下一本 / 非 EPUB 书（如 TXT）。 */
        fun clearActive() {
            activeRegistry.set(EMPTY)
        }

        /** renderer 端入口：拿 active book 的 family → Typeface 映射。 */
        fun resolveActive(family: String?): Typeface? = activeRegistry.get().resolve(family)
    }
}
