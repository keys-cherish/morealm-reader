package com.morealm.app.domain.repository

import android.content.Context
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手写 EPUB3 zip 输出器（参考 Legado 的目录结构与 HTML/CSS 模板，但不依赖 epublib）。
 *
 * 输出布局（与 Legado 一致，便于用户在两个 app 之间互通）：
 *
 * ```
 * mimetype                  ← 必须第一条目，STORED（不压缩），CRC=2007599984（"application/epub+zip"）
 * META-INF/container.xml    ← 指向 OEBPS/content.opf
 * OEBPS/
 *   content.opf             ← package：metadata + manifest + spine
 *   nav.xhtml               ← EPUB3 nav doc
 *   toc.ncx                 ← EPUB2 NCX（向后兼容 ADE / Sony）
 *   Text/
 *     cover.xhtml           ← 仅当有 [coverBytes] 时生成
 *     intro.xhtml           ← 内容简介
 *     chapter_001.xhtml ... ← 每章一个 XHTML 文件
 *   Styles/
 *     main.css              ← 拷自 assets/epub/main.css
 *     fonts.css             ← 拷自 assets/epub/fonts.css
 *   Images/
 *     cover.jpg             ← 仅当有 [coverBytes] 时生成
 * ```
 *
 * **未实现**（相对 Legado 的简化）：
 *  - 章节正文图片内联（Legado 的 fixPic 把 `<img>` 抓为 Images/<md5>.<ext>）；
 *    本实现把 `<img>` 标签原样保留，外部 URL 在阅读器看不到图但不影响文字。
 *  - 多卷分册（Legado 的 CustomExporter.size）；交给 Stage C。
 *  - 卷标作为父级 TOC（Legado 走 `addSection(parent, ...)`）；本实现把所有
 *    章节平铺到一级 TOC，足够大多数网文使用。
 *
 * 性能：单本 1000 章 ≈ 5MB EPUB；流式写出（章节内容串行 read → write），峰值
 * 内存 = 当前章节文本（< 200 KB）+ ZipOutputStream 默认 deflate 缓冲。
 */
class EpubExporter(
    /** 书籍元数据（书名、作者、简介）。 */
    private val book: Book,
    /** 章节列表 — 顺序即 spine 顺序；调用方负责过滤掉 isVolume 的卷标。 */
    private val chapters: List<BookChapter>,
    /**
     * 章节正文供给器。给定 0-based 局部索引（在 [chapters] 内），返回该章正文文本。
     * 返回 `null` 表示该章未缓存 — 输出时跳过该章；仍占 spine 与 TOC 一席（便于读者
     * 后续补缓存重导）。空字符串视为有内容但为空。
     */
    private val contentProvider: suspend (chapterLocalIndex: Int) -> String?,
    /** 封面图字节（JPEG）。null = 不生成 cover.xhtml + Images/cover.jpg。 */
    private val coverBytes: ByteArray? = null,
    /** EPUB 范围标识，写入元数据 description 末尾，方便用户辨识。 */
    private val rangeNote: String = "",
    /**
     * 进度回调。`current` 已写章节数（含跳过的未缓存章），`total` = chapters.size。
     */
    private val onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
) {

    /**
     * 把整本 EPUB 字节流写到 [out]。**调用方负责 close** [out]（典型用 SAF
     * `openOutputStream(uri)?.use { writeTo(it) }`）。
     *
     * 返回：实际写入的章节数（contentProvider 返回非 null 的章数）。
     */
    suspend fun writeTo(context: Context, out: OutputStream): Int {
        var written = 0
        val opfBuilder = OpfBuilder(book, chapters.size, hasCover = coverBytes != null, rangeNote = rangeNote)
        val ncxBuilder = NcxBuilder(book.title, hasCover = coverBytes != null)
        val navBuilder = NavBuilder(hasCover = coverBytes != null)

        ZipOutputStream(out).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)

            // 1) mimetype — 必须第一条且 STORED（无压缩、无 extra），EPUB 规范硬要求。
            writeStoredEntry(zos, "mimetype", MIMETYPE_BYTES)

            // 2) META-INF/container.xml
            writeDeflatedEntry(zos, "META-INF/container.xml", CONTAINER_XML.toByteArray())

            // 3) Styles
            writeAssetEntry(zos, context, "OEBPS/Styles/main.css", "epub/main.css")
            writeAssetEntry(zos, context, "OEBPS/Styles/fonts.css", "epub/fonts.css")

            // 4) Cover (optional)
            if (coverBytes != null) {
                writeDeflatedEntry(zos, "OEBPS/Images/cover.jpg", coverBytes)
                val coverHtml = readAsset(context, "epub/cover.html")
                    .replace("{name}", xmlEscape(book.title))
                    .replace("{author}", xmlEscape(book.author.ifBlank { "佚名" }))
                writeDeflatedEntry(zos, "OEBPS/Text/cover.xhtml", coverHtml.toByteArray())
            }

            // 5) Intro
            val introHtml = readAsset(context, "epub/intro.html")
                .replace("{intro}", buildIntroBody(book, rangeNote))
            writeDeflatedEntry(zos, "OEBPS/Text/intro.xhtml", introHtml.toByteArray())

            // 6) Chapters
            val chapterTpl = readAsset(context, "epub/chapter.html")
            for ((i, ch) in chapters.withIndex()) {
                val raw = contentProvider(i)
                if (raw == null) {
                    // 未缓存 — 仍占 spine 但放一行提示，避免 nav 链接到 404。
                    val placeholder = chapterTpl
                        .replace("{title}", xmlEscape(ch.title))
                        .replace("{content}", "<p>（此章未缓存，可在缓存页补完后重新导出）</p>")
                    writeDeflatedEntry(zos, chapterPath(i + 1), placeholder.toByteArray())
                } else {
                    val xhtml = chapterTpl
                        .replace("{title}", xmlEscape(ch.title))
                        .replace("{content}", textToXhtml(raw))
                    writeDeflatedEntry(zos, chapterPath(i + 1), xhtml.toByteArray())
                    written++
                }
                opfBuilder.addChapter(i + 1)
                ncxBuilder.addChapter(i + 1, ch.title)
                navBuilder.addChapter(i + 1, ch.title)
                onProgress(i + 1, chapters.size)
            }

            // 7) content.opf / toc.ncx / nav.xhtml — 必须在所有 manifest 项收齐后写
            writeDeflatedEntry(zos, "OEBPS/content.opf", opfBuilder.build().toByteArray())
            writeDeflatedEntry(zos, "OEBPS/toc.ncx", ncxBuilder.build().toByteArray())
            writeDeflatedEntry(zos, "OEBPS/nav.xhtml", navBuilder.build().toByteArray())
        }
        AppLog.info("EpubExport", "EPUB 写入完成：$written/${chapters.size} 章 — ${book.title}")
        return written
    }

    /** 章节文件名（1-based）。`chapter_001.xhtml` 这种零填充让 ADE 之类按文件名排序时不会乱。 */
    private fun chapterPath(oneBasedIndex: Int): String =
        "OEBPS/Text/chapter_${oneBasedIndex.toString().padStart(3, '0')}.xhtml"

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32().apply { update(data) }
        entry.crc = crc.value
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun writeDeflatedEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun writeAssetEntry(
        zos: ZipOutputStream,
        context: Context,
        zipName: String,
        assetName: String,
    ) {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        writeDeflatedEntry(zos, zipName, bytes)
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).use { it.bufferedReader().readText() }

    /**
     * 把缓存里的纯文本正文（行分隔，含可能的 `<img>`）转成 XHTML：
     *  - 每行包成 `<p>...</p>`，空行跳过
     *  - HTML/XML 特殊字符转义（&、<、>），保留 `<img>` 这种用户已有的 HTML（如果是
     *    干净的标签，本函数不做语法校验 — 责任在 ContentProcessor 上游）
     *
     * Legado 用 `contentProcessor.getContent(...).toString()` 已经是分行文本；MoRealm
     * 这里直接读 cache 里的字符串（同样分行），所以行内策略对齐。
     */
    private fun textToXhtml(raw: String): String {
        val sb = StringBuilder(raw.length + 64)
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            sb.append("<p>").append(xmlEscape(trimmed)).append("</p>\n")
        }
        return sb.toString()
    }

    /**
     * 对 attribute / 文本做 XML 转义。注意：[textToXhtml] 内的转义会把用户已经写好的
     * `<img>` 也变成 `&lt;img...&gt;` —— 当前实现就是这个保守策略，因为缓存正文里偶尔
     * 出现的 HTML 多半是脏数据；想要真正支持图片需要单独的 fixPic 流程（Stage C 之后）。
     */
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun buildIntroBody(book: Book, rangeNote: String): String {
        val sb = StringBuilder("<p>作者：${xmlEscape(book.author.ifBlank { "佚名" })}</p>\n")
        if (!book.description.isNullOrBlank()) {
            for (line in book.description.split('\n')) {
                val t = line.trim()
                if (t.isNotEmpty()) sb.append("<p>").append(xmlEscape(t)).append("</p>\n")
            }
        }
        if (rangeNote.isNotBlank()) {
            sb.append("<p style=\"color:#888;font-size:0.85em;text-align:right;margin-top:2em;\">")
                .append(xmlEscape(rangeNote)).append("</p>\n")
        }
        return sb.toString()
    }

    companion object {
        /** EPUB 规范要求 mimetype 文件第一字节就是这串。 */
        private val MIMETYPE_BYTES = "application/epub+zip".toByteArray(Charsets.US_ASCII)

        private val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>"""
    }
}

/**
 * 增量构建 content.opf —— 所有章节加完后调用 [build] 一次性吐出 XML。
 *
 * 把 manifest / spine 的 chapter 项放在两个 builder 里同步追加，确保 id 在
 * 两处保持一致（spine `<itemref idref="ch001">` 必须命中 manifest `<item id="ch001">`）。
 */
private class OpfBuilder(
    private val book: com.morealm.app.domain.entity.Book,
    @Suppress("unused") private val totalChapters: Int,
    private val hasCover: Boolean,
    private val rangeNote: String,
) {
    private val manifestSb = StringBuilder()
    private val spineSb = StringBuilder()

    fun addChapter(oneBasedIndex: Int) {
        val id = "ch${oneBasedIndex.toString().padStart(3, '0')}"
        val href = "Text/chapter_${oneBasedIndex.toString().padStart(3, '0')}.xhtml"
        manifestSb.append("        <item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>\n")
        spineSb.append("        <itemref idref=\"$id\"/>\n")
    }

    fun build(): String {
        val title = xmlEscape(book.title)
        val author = xmlEscape(book.author.ifBlank { "佚名" })
        val descBase = book.description?.trim().orEmpty()
        val desc = xmlEscape(if (rangeNote.isNotBlank()) "$descBase\n\n$rangeNote".trim() else descBase)
        val uuid = "morealm-${book.id}-${System.currentTimeMillis()}"
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        val coverManifest = if (hasCover) {
            "        <item id=\"cover-image\" href=\"Images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n" +
                "        <item id=\"cover\" href=\"Text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
        } else ""
        val coverSpine = if (hasCover) "        <itemref idref=\"cover\" linear=\"yes\"/>\n" else ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:identifier id="bookid">$uuid</dc:identifier>
        <dc:title>$title</dc:title>
        <dc:creator>$author</dc:creator>
        <dc:language>zh-CN</dc:language>
        <dc:publisher>MoRealm</dc:publisher>
        <dc:description>$desc</dc:description>
        <meta property="dcterms:modified">$now</meta>
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        <item id="css-main" href="Styles/main.css" media-type="text/css"/>
        <item id="css-fonts" href="Styles/fonts.css" media-type="text/css"/>
$coverManifest        <item id="intro" href="Text/intro.xhtml" media-type="application/xhtml+xml"/>
$manifestSb    </manifest>
    <spine toc="ncx">
$coverSpine        <itemref idref="intro" linear="yes"/>
$spineSb    </spine>
</package>
"""
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}

/** EPUB2 NCX — 老阅读器（ADE / Sony）必备；EPUB3 已用 nav 替代但保留向后兼容。 */
private class NcxBuilder(private val title: String, private val hasCover: Boolean) {
    private val pointsSb = StringBuilder()
    private var playOrder = if (hasCover) 3 else 2  // cover=1, intro=2 起步

    fun addChapter(oneBasedIndex: Int, chTitle: String) {
        val id = "ch${oneBasedIndex.toString().padStart(3, '0')}"
        val href = "Text/chapter_${oneBasedIndex.toString().padStart(3, '0')}.xhtml"
        pointsSb.append("    <navPoint id=\"$id\" playOrder=\"$playOrder\">\n")
        pointsSb.append("        <navLabel><text>${xmlEscape(chTitle)}</text></navLabel>\n")
        pointsSb.append("        <content src=\"$href\"/>\n")
        pointsSb.append("    </navPoint>\n")
        playOrder++
    }

    fun build(): String {
        val coverNav = if (hasCover) {
            """    <navPoint id="cover" playOrder="1">
        <navLabel><text>封面</text></navLabel>
        <content src="Text/cover.xhtml"/>
    </navPoint>
"""
        } else ""
        val introOrder = if (hasCover) 2 else 1
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head>
    <meta name="dtb:uid" content="morealm-${System.currentTimeMillis()}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
</head>
<docTitle><text>${xmlEscape(title)}</text></docTitle>
<navMap>
$coverNav    <navPoint id="intro" playOrder="$introOrder">
        <navLabel><text>简介</text></navLabel>
        <content src="Text/intro.xhtml"/>
    </navPoint>
$pointsSb</navMap>
</ncx>
"""
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** EPUB3 nav.xhtml — 必需文档，新阅读器读这个。 */
private class NavBuilder(private val hasCover: Boolean) {
    private val itemsSb = StringBuilder()

    fun addChapter(oneBasedIndex: Int, chTitle: String) {
        val href = "Text/chapter_${oneBasedIndex.toString().padStart(3, '0')}.xhtml"
        itemsSb.append("        <li><a href=\"$href\">${xmlEscape(chTitle)}</a></li>\n")
    }

    fun build(): String {
        val coverItem = if (hasCover) "        <li><a href=\"Text/cover.xhtml\">封面</a></li>\n" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
    <title>目录</title>
    <link rel="stylesheet" type="text/css" href="Styles/main.css"/>
</head>
<body>
<nav epub:type="toc" id="toc">
    <h1>目录</h1>
    <ol>
$coverItem        <li><a href="Text/intro.xhtml">简介</a></li>
$itemsSb    </ol>
</nav>
</body>
</html>
"""
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
