package com.morealm.app.domain.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字体仓库 —— 双轨：
 *   1) APP_LIBRARY：[appFontsDir] = filesDir/fonts/，由 [importFromUri] 复制进来，可删
 *   2) EXTERNAL   ：用户用 OpenDocumentTree 选的文件夹，URI 存在 AppPreferences.fontFolderUri
 *      只读扫描（.ttf / .otf），不复制
 *
 * Typeface 加载逻辑 [loadTypeface] 参考成熟开源阅读器的
 * 章节排版器字体加载实现：
 *   - content URI + Android O+：openFileDescriptor + Typeface.Builder(fd)
 *   - file URI / 绝对路径   ：Typeface.createFromFile
 *   - 加载失败兜底返回 [Typeface.SANS_SERIF]，由调用方决定是否清空 path
 */
@Singleton
class FontRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** App 私有字库目录，懒创建。filesDir 不会被系统清理，区别于 cacheDir。 */
    private val appFontsDir: File by lazy {
        File(context.filesDir, "fonts").also { if (!it.exists()) it.mkdirs() }
    }

    /** 仅匹配 .ttf / .otf，与 Legado FontSelectDialog.fontRegex 对齐（不含 .ttc 集合字体）。 */
    private val fontRegex = Regex("(?i).*\\.[ot]tf")

    /**
     * 内置 family key → assets/fonts/ 下的资源文件名映射。
     *
     * 拉丁字体（CrimsonPro / Inter / CormorantGaramond）已直接打包到 assets。
     * 中文字体（思源宋体 / 思源黑体 / 楷体 / 仿宋）由于完整 ttf 体积巨大（单文件 60MB+），
     * 不直接打包；需通过 `scripts/build-font-subsets.sh` 用 pyftsubset 生成常用汉字
     * 子集后放入 `app/src/main/assets/fonts/`。文件缺失时由 [resolveTypeface]
     * 走系统 fallback（保持衬线/无衬线的视觉差异，让用户切换肉眼可见）。
     */
    private val assetFontFiles: Map<String, String> = mapOf(
        "noto_serif_sc" to "NotoSerifSC.ttf",
        "noto_sans_sc"  to "NotoSansSC.ttf",
        "kaiti"         to "Kaiti.ttf",
        "fangsong"      to "FangSong.ttf",
        "crimson_pro"   to "CrimsonPro.ttf",
        "inter"         to "Inter.ttf",
        "cormorant"     to "CormorantGaramond.ttf",
    )

    /**
     * assets/fonts/ 加载结果缓存。Map 整体替换实现"读不加锁"。
     * - 命中：缓存 [Typeface]，避免每次切字体都重新 IO + 解码 ttf
     * - 未命中：缓存 null sentinel，避免反复抛 RuntimeException
     */
    @Volatile
    private var assetTypefaceCache: Map<String, Typeface?> = emptyMap()

    // ─── 列表 ──────────────────────────────────────────────────────────────

    /** App 字库：扫 filesDir/fonts/。返回按文件名排序。IO 操作，请在 Dispatchers.IO 调度。 */
    suspend fun listAppFonts(): List<FontEntry> = withContext(Dispatchers.IO) {
        appFontsDir.listFiles()
            ?.filter { it.isFile && it.name.matches(fontRegex) }
            ?.map {
                FontEntry(
                    displayName = it.nameWithoutExtension,
                    path = Uri.fromFile(it).toString(),
                    source = FontEntry.Source.APP_LIBRARY,
                    sizeBytes = it.length(),
                )
            }
            ?.sortedBy { it.displayName }
            ?: emptyList()
    }

    /** 外部文件夹：用持久化的 Tree URI 扫描；URI 失效（用户撤销权限/换 SD 卡）时返回空表。 */
    suspend fun listExternalFonts(folderUri: String): List<FontEntry> = withContext(Dispatchers.IO) {
        if (folderUri.isBlank()) return@withContext emptyList()
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            if (tree == null || !tree.canRead()) return@runCatching emptyList<FontEntry>()
            tree.listFiles()
                .filter { it.isFile && (it.name?.matches(fontRegex) == true) }
                .map {
                    FontEntry(
                        displayName = it.name?.substringBeforeLast('.') ?: "字体",
                        path = it.uri.toString(),
                        source = FontEntry.Source.EXTERNAL,
                        sizeBytes = it.length(),
                    )
                }
                .sortedBy { it.displayName }
        }.getOrElse {
            AppLog.warn("FontRepository", "扫描外部字体文件夹失败: ${it.localizedMessage}", it)
            emptyList()
        }
    }

    // ─── 导入 / 删除 ───────────────────────────────────────────────────────

    /**
     * 把用户用 OpenDocument 选中的单个 .ttf/.otf 复制到 [appFontsDir]。
     *
     * 校验流程：
     *   1) 文件名后缀过滤（防 `.exe` 重命名为字体）
     *   2) 复制完成后用 [Typeface.createFromFile] 验证，加载失败立刻删
     * 重名冲突：同名直接覆盖（用户体验上对应"重新导入更新"）。
     *
     * @return 导入成功的 [FontEntry]；失败抛 [IllegalArgumentException] / [java.io.IOException]
     */
    suspend fun importFromUri(uri: Uri, suggestedName: String? = null): FontEntry =
        withContext(Dispatchers.IO) {
            val rawName = suggestedName ?: DocumentFile.fromSingleUri(context, uri)?.name
                ?: "imported_font.ttf"
            val safeName = sanitizeFileName(rawName)
            require(safeName.matches(fontRegex)) {
                "仅支持 .ttf / .otf 字体文件（当前: $safeName）"
            }
            val target = File(appFontsDir, safeName)
            // 复制
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开字体文件流" }
                target.outputStream().use { input.copyTo(it) }
            }
            // 校验：加载失败说明文件损坏 / 不是真字体，删掉别留垃圾
            val ok = runCatching { Typeface.createFromFile(target) }
                .map { it != null && it !== Typeface.DEFAULT }
                .getOrDefault(false)
            if (!ok) {
                target.delete()
                throw IllegalArgumentException("字体文件无效或已损坏")
            }
            FontEntry(
                displayName = target.nameWithoutExtension,
                path = Uri.fromFile(target).toString(),
                source = FontEntry.Source.APP_LIBRARY,
                sizeBytes = target.length(),
            )
        }

    /** 删除 App 字库里的字体文件。返回是否成功。外部字体不在此处理。 */
    suspend fun deleteAppFont(entry: FontEntry): Boolean = withContext(Dispatchers.IO) {
        if (entry.source != FontEntry.Source.APP_LIBRARY) return@withContext false
        val uri = runCatching { Uri.parse(entry.path) }.getOrNull() ?: return@withContext false
        val file = uri.path?.let { File(it) } ?: return@withContext false
        file.exists() && file.delete()
    }

    // ─── Typeface 加载 ─────────────────────────────────────────────────────

    /**
     * 解析当前阅读器应使用的 [Typeface]。优先级：
     *   1) [customFontPath] 非空 → 试加载，失败兜底 SANS_SERIF
     *   2) [fontFamily] 命中已知键 → 返回系统字体（中文 4 内置目前都映射到系统 fallback，
     *      因为 res/font 没打包；预留切换点：以后挂 [R.font.xxx] 后改这里即可）
     *   3) 默认 [Typeface.DEFAULT]
     *
     * 注意：会抛吞掉 IO 异常，**不会** suspend / 触发 DataStore 写入；调用方需要在
     * 加载失败后自己决定是否清空 customFontPath（参考 Legado runCatching+save 模式）。
     * 是否成功用返回 != Typeface.SANS_SERIF（且 customFontPath 非空时）来判断不太准确，
     * 调用方应该自己用 [tryLoadFontFile] 试探。
     */
    fun resolveTypeface(fontFamily: String, customFontPath: String?): Typeface {
        if (!customFontPath.isNullOrBlank()) {
            tryLoadFontFile(customFontPath)?.let { return it }
        }
        // 优先：assets/fonts 里打包的真子集字体（拉丁已有，中文需用户跑子集化脚本生成）
        loadAssetTypeface(fontFamily)?.let { return it }
        // 系统 fallback：保留默认 noto_serif_sc → SERIF（用户习惯的阅读体验）；
        // noto_sans_sc / inter 走 SANS_SERIF 让"宋体↔黑体"切换肉眼可见有差异。
        //
        // 楷体 / 仿宋的 ttf 未打包到 assets（完整体积巨大，需用户手动跑
        // scripts/build-font-subsets.sh 生成子集）。如果都 fallback 到 Typeface.SERIF，
        // 用户切换肉眼无变化——这就是群里反馈的"楷体和仿宋显示一样"的根因。
        // 临时区分方案：楷体用 SERIF italic（接近楷书的流畅倾斜气质），仿宋保 SERIF normal。
        // 一旦用户从「设置→字体→导入」装了真正的 ttf，前面 customFontPath / loadAssetTypeface
        // 路径会优先命中，本 fallback 自动让位。
        return when (fontFamily) {
            "noto_sans_sc", "inter" -> Typeface.SANS_SERIF
            "kaiti" -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            "noto_serif_sc", "fangsong",
            "crimson_pro", "cormorant" -> Typeface.SERIF
            "system", "" -> Typeface.DEFAULT
            else -> Typeface.DEFAULT
        }
    }

    /**
     * 是否处于"asset ttf 缺失，走系统 fallback"状态。
     * 让 UI 层（[com.morealm.app.ui.settings.ReadingSettingsScreen]）能在字体选项
     * 上方挂一行提示：「未安装字体文件，当前为近似 fallback；如需精准字体，请到
     * 『设置 → 字体 → 导入』装一份 .ttf」。
     *
     * 判定：family 在 [assetFontFiles] 映射里 + 真正加载结果为 null。命中本判定的
     * 当前仅楷体 / 仿宋两个（中文真子集体积巨大，未打包；拉丁字体已直接打包到 assets）。
     */
    fun isAssetMissing(fontFamily: String): Boolean {
        if (fontFamily !in assetFontFiles) return false
        // loadAssetTypeface 已带缓存，多次调用零成本
        return loadAssetTypeface(fontFamily) == null
    }

    /**
     * 尝试从 `assets/fonts/${file}` 加载 Typeface。命中 / 失败结果都进 [assetTypefaceCache]，
     * 同一 family 后续调用走内存（O(1)），避免每次切字体都解码一次 ttf。
     *
     * `Typeface.createFromAsset` 在资源缺失时——
     *   - Android Pie+：抛 RuntimeException（被 runCatching 吞）
     *   - Pre-Pie：返回 [Typeface.DEFAULT]（被 takeIf 过滤）
     * 两种情况都视为"未命中"返回 null。
     */
    private fun loadAssetTypeface(family: String): Typeface? {
        val cached = assetTypefaceCache
        if (family in cached) return cached[family]

        val fileName = assetFontFiles[family] ?: return null

        val typeface = runCatching {
            Typeface.createFromAsset(context.assets, "fonts/$fileName")
        }.getOrNull()
            ?.takeIf { it !== Typeface.DEFAULT }

        if (typeface == null) {
            AppLog.debug(
                "FontRepository",
                "asset 'fonts/$fileName' 缺失或加载失败，family=$family 走系统 fallback",
            )
        } else {
            AppLog.debug("FontRepository", "asset 字体加载成功 family=$family file=$fileName")
        }
        assetTypefaceCache = cached + (family to typeface)
        return typeface
    }

    /**
     * 把 EPUB `@font-face` 的 `local("…")` 名映射到打包 asset / 系统 Typeface。
     *
     * 覆盖 EPUB 常引用的中文系统字体名（宋体/楷体/黑体/仿宋/准圆 及别名 / 短码 / 罗马名）。命中
     * 返回对应 Typeface（asset 缺失时回落 Android 内建 SERIF/SANS）；无系统等价的特殊装饰字体
     * 返回 null，由 caller 继续 fallback。修「EPUB `src: local(系统字体), url(未打包)` 链全废」。
     */
    fun resolveSystemFontByName(localName: String): Typeface? {
        val raw = localName.trim()
        if (raw.isEmpty()) return null
        val n = raw.lowercase()
        fun has(vararg keys: String): Boolean = keys.any { raw.contains(it) || n.contains(it) }
        return when {
            has("楷") || n.contains("kai") || n == "kt" ->
                loadAssetTypeface("kaiti") ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            has("仿宋") || n.contains("fangsong") || n == "fs" ->
                loadAssetTypeface("fangsong") ?: Typeface.SERIF
            has("黑", "雅黑") || n.contains("hei") || n.contains("gothic") || n == "ht" ->
                loadAssetTypeface("noto_sans_sc") ?: Typeface.SANS_SERIF
            has("圆") || n.contains("yuan") || n == "yt" ->
                Typeface.SANS_SERIF
            has("宋", "明") || n.contains("song") || n.contains("mincho") || n.contains("ming") || n == "st" ->
                loadAssetTypeface("noto_serif_sc") ?: Typeface.SERIF
            else -> null
        }
    }

    /**
     * 加载一个字体路径为 [Typeface]，加载失败返回 null（**不** 兜底，让调用方决策）。
     * 参考 Legado ChapterProvider.getTypeface()。
     */
    fun tryLoadFontFile(path: String): Typeface? = runCatching {
        when {
            path.startsWith("content://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use {
                    Typeface.Builder(it.fileDescriptor).build()
                }
            }
            path.startsWith("content://") -> null  // 旧版本 content URI 不再支持，提示用户改用 App 字库
            path.startsWith("file://") -> {
                val file = Uri.parse(path).path?.let(::File)
                if (file != null && file.canRead()) Typeface.createFromFile(file) else null
            }
            else -> {
                val file = File(path)
                if (file.canRead()) Typeface.createFromFile(file) else null
            }
        }
    }.onFailure {
        AppLog.warn("FontRepository", "加载字体 $path 失败: ${it.localizedMessage}", it)
    }.getOrNull()

    /**
     * **2026-05-25 EPUB 自带字体**：把字节流落到 cacheDir 临时文件再 [Typeface.createFromFile]。
     *
     * Android Typeface 没原生接口直接接 byte[]（API 26+ 有 [Typeface.Builder.setSourceFromMemory]
     * 但需要 AssetFileDescriptor），最简单稳健做法是落盘走 createFromFile。
     *
     * @param cacheKey 落盘文件名前缀（通常 = book id + font family hash），同 key 复用同文件
     *   避免重复落盘。caller 自己保证 key 唯一
     * @param bytes 字体字节流（ttf/otf real bytes）。woff/woff2 caller 应先过滤
     * @return Typeface 或 null（落盘失败 / Typeface.createFromFile 抛异常）
     */
    fun tryLoadFontBytes(cacheKey: String, bytes: ByteArray): Typeface? {
        if (bytes.isEmpty()) return null
        val dir = File(context.cacheDir, "epub_fonts").apply { if (!exists()) mkdirs() }
        val sanitizedKey = sanitizeFileName(cacheKey)
        val file = File(dir, "$sanitizedKey.ttf")
        // Typeface.createFromFile 在单本 EPUB 多个嵌入字体连续加载 / 低端设备压力下会偶发失败
        // （抛异常，或返回 Typeface.DEFAULT 这种"看似成功实为 fallback"的结果），表现为
        // "部分字体没生效、整页字体割裂、重启时好时坏"。重试 + 显式判 DEFAULT（对齐 importFromUri
        // 校验）让瞬时失败 / 残缺缓存文件可在同次构建内恢复。
        repeat(3) { attempt ->
            try {
                // 同 key 已落盘且大小一致则复用，避免重复 I/O（同一本书同字体多章共享）
                if (!file.exists() || file.length() != bytes.size.toLong()) {
                    file.outputStream().use { it.write(bytes) }
                }
                val tf = Typeface.createFromFile(file)
                if (tf != null && tf !== Typeface.DEFAULT) return tf
            } catch (t: Throwable) {
                AppLog.warn("FontRepository", "tryLoadFontBytes key=$cacheKey attempt=$attempt 失败: ${t.localizedMessage}", t)
            }
            // 本轮失败（异常 / null / DEFAULT）→ 删可能残缺的缓存文件，下一轮重写重试
            runCatching { file.delete() }
        }
        return null
    }

    // ─── 工具 ──────────────────────────────────────────────────────────────

    /** 防穿越：去掉路径分隔符 + 危险字符；仅保留文件名部分。 */
    private fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("""[<>:"|?*\\]"""), "_").take(128)
    }
}
