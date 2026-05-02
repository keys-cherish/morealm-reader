package com.morealm.app.domain.sync

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.preference.PreferenceSnapshot
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local + WebDav backup/restore — exports books, bookmarks, read progress,
 * sources, groups, replace rules, themes and reader styles as a ZIP file
 * containing one `backup.json` entry.
 *
 * Both the SAF (`exportBackup` / `importBackup`) and the WebDav
 * (`generateBackupBytes` / `importBackupFromBytes`) entry points share the
 * SAME [buildBackupData] / [applyBackup] core so the field set can no longer
 * drift between them — that drift was a P0 silent-data-loss bug where WebDav
 * uploads silently shipped without the user's themes and reader styles.
 *
 * All four entry points are guarded by a single object-level [mutex] so a
 * double-tap (or a backup colliding with a restore) cannot corrupt the local
 * database or upload a half-read snapshot to the cloud.
 */
object BackupManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Tolerate NaN / Infinity in Float fields (e.g. Book.lastReadOffset can become
        // NaN when a reader-progress row was saved before any layout completed). Without
        // this flag, kotlinx-serialization throws IllegalArgumentException("Unexpected
        // NaN value...") which used to silently abort the entire export through
        // `runCatching` — the user only saw a 0-byte file. The flag round-trips the
        // value as the literal string "NaN"/"Infinity" so restore is also lenient.
        allowSpecialFloatingPointValues = true
    }

    /**
     * Single-flight guard: serialises every backup / restore entry point so
     * concurrent invocations queue instead of racing the database.
     */
    private val mutex = Mutex()

    /**
     * Last error surfaced by any of the four entry points, set when an exception
     * was caught and used to be silently swallowed by `runCatching`. Read-and-
     * clear via [consumeLastErrorMessage] so the UI layer can show a meaningful
     * "导出失败：xxx" instead of a generic "导出失败".
     *
     * Single string is safe because [mutex] serialises every entry point — there
     * is at most one in-flight backup at a time.
     */
    @Volatile private var lastErrorMessage: String? = null

    /**
     * Read and clear the most recent failure message. The clear-on-read keeps
     * stale errors from leaking into a later (successful) operation's status.
     */
    fun consumeLastErrorMessage(): String? {
        val m = lastErrorMessage
        lastErrorMessage = null
        return m
    }

    /** Capture the diagnostic from a thrown exception in a UX-friendly way. */
    private fun recordError(prefix: String, e: Throwable) {
        lastErrorMessage = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
        AppLog.error("Backup", "$prefix: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    @Serializable
    data class BackupData(
        /**
         * 备份格式版本：
         * - v1：早期单表 JSON 直挂（已弃用，restore 不再识别）
         * - v2：8 类内嵌 JSON 字符串字段（books / bookmarks / ... / readerStyles）
         * - v3：在 v2 基础上追加 [preferences]（DataStore key→value JSON）和
         *       [bgImageManifest]（背景图清单），同时 zip 内多了 `bg/<file>` 子条目；
         *       v2 字段保持完全兼容 — v3 zip 可被旧 app 当 v2 读，多余的字段静默丢弃。
         *
         * **不要把 version 当成强校验**：`applyBackup` 走「字段非空就尝试解析、解析失败
         * 仅 log 不阻断」的容忍模型；version 主要是给将来「v3→v4 schema 不兼容」时
         * 留个分流锚点，不是当前的恢复条件。
         */
        val version: Int = 3,
        val timestamp: Long = System.currentTimeMillis(),
        val books: String = "",
        val bookmarks: String = "",
        val sources: String = "",
        val progress: String = "",
        val groups: String = "",
        val replaceRules: String = "",
        val themes: String = "",
        val readerStyles: String = "",
        val httpTts: String = "",
        /**
         * DataStore 偏好快照（v3+）。形如 `{"reader_font_size": 18.0, "page_anim": "cover", ...}`，
         * 由 [com.morealm.app.domain.preference.PreferenceSnapshot] 序列化。空字符串视为
         * 「该备份没带偏好」（v2 旧 zip 或用户在导出选项页取消勾选了「应用偏好」）。
         */
        val preferences: String = "",
        /**
         * 背景图清单（v3+）。形如 `[{"name":"day_bg.jpg","sizeBytes":120345,"sha256":"..."}]`。
         * 实际图片字节挂在 zip 的 `bg/<name>` 条目里 — manifest 仅用于恢复时校验文件是否
         * 完整、决定是否覆盖。空字符串/缺失 = 不备份背景图（v2 zip 或用户未勾选）。
         */
        val bgImageManifest: String = "",
    )

    /**
     * Per-category opt-in for [exportBackup]. Default = everything ON, matching
     * the legacy "export everything" behaviour. The "导出选项" page in
     * ProfileScreen flips individual flags off when the user unchecks a
     * category. Categories that are off are written as empty strings in the
     * BackupData json (the restore path already skips empty fields, so old
     * backups stay forward-compatible).
     */
    data class BackupOptions(
        val includeBooks: Boolean = true,
        val includeBookmarks: Boolean = true,
        val includeSources: Boolean = true,
        val includeProgress: Boolean = true,
        val includeGroups: Boolean = true,
        val includeReplaceRules: Boolean = true,
        val includeThemes: Boolean = true,
        val includeReaderStyles: Boolean = true,
        /** v3+：随包导出 DataStore 偏好（音量键 / 选区菜单 / 书源分组等 90+ 个 key）。 */
        val includePreferences: Boolean = true,
        /** v3+：随包导出 `filesDir/bg/` 下的日 / 夜阅读背景图（单图 >4 MB 自动跳过）。 */
        val includeBgImages: Boolean = true,
    ) {
        /** Quick "did the user disable anything?" probe used by the UI for the summary line. */
        fun isFullExport(): Boolean = includeBooks && includeBookmarks && includeSources &&
            includeProgress && includeGroups && includeReplaceRules &&
            includeThemes && includeReaderStyles &&
            includePreferences && includeBgImages
    }

    /**
     * Per-category opt-in for the restore path. Symmetric with [BackupOptions]
     * so the UI can use the same toggle key set. The restore path applies an
     * additional rule beyond "skip if false": even when a section is enabled,
     * `data.<field>.isBlank()` is treated as "section absent" and skipped
     * silently — that's how v2 backups (which lack `preferences` /
     * `bgImageManifest`) restore cleanly under v3 code.
     *
     * Section keys (UI side ↔ here):
     *  - books / bookmarks / sources / progress / groups / replaceRules
     *  - themes / readerStyles / preferences
     *  - bgImages 没有独立开关 — 走 `themes` 的开关，因为 ThemeEntity.bgImage 通过
     *    文件名引用 `filesDir/bg/<name>`，恢复主题但缺图等于显示半残主题，没意义。
     */
    data class RestoreOptions(
        val includeBooks: Boolean = true,
        val includeBookmarks: Boolean = true,
        val includeSources: Boolean = true,
        val includeProgress: Boolean = true,
        val includeGroups: Boolean = true,
        val includeReplaceRules: Boolean = true,
        val includeThemes: Boolean = true,
        val includeReaderStyles: Boolean = true,
        val includePreferences: Boolean = true,
    ) {
        companion object {
            /** Everything off — used by tests / unusual workflows; UI defaults are all on. */
            val NONE = RestoreOptions(
                includeBooks = false,
                includeBookmarks = false,
                includeSources = false,
                includeProgress = false,
                includeGroups = false,
                includeReplaceRules = false,
                includeThemes = false,
                includeReaderStyles = false,
                includePreferences = false,
            )
        }
    }

    /**
     * Per-category preview row shown on the export-options page so the user
     * can decide whether each section is worth exporting (e.g. "300 个书源
     * = 200 KB" might be worth dropping if the user just wants their books).
     *
     * @param key stable identifier matching [BackupOptions] field, also used
     *            as the toggle key in ProfileViewModel.backupSelections.
     * @param label Chinese, user-facing label.
     * @param itemCount how many rows the section currently has in the DB.
     * @param estimatedBytes JSON-serialized size of the section's full payload —
     *                       so the displayed size matches what actually goes
     *                       into the .zip if the user keeps the default selection.
     */
    data class BackupSectionInfo(
        val key: String,
        val label: String,
        val itemCount: Int,
        val estimatedBytes: Int,
    )

    /**
     * Per-category preview row shown on the **restore** options page so the user
     * can decide whether each section is worth restoring (e.g. the user might
     * want to skip "书源" because they've already curated a fresh source list
     * locally and don't want the backup to overwrite it).
     *
     * @param key stable identifier matching [RestoreOptions] field, used as
     *            the toggle key in ProfileViewModel.restoreSelections.
     * @param label Chinese, user-facing label.
     * @param itemCount how many rows the *backup* contains for this section.
     * @param conflictCount how many of those rows would overwrite an existing
     *            local row (matched by primary key). Drives the "将覆盖 N 条
     *            本地数据" hint in the confirmation dialog so the user is not
     *            surprised by silent overwrites.
     */
    data class RestoreSectionInfo(
        val key: String,
        val label: String,
        val itemCount: Int,
        val conflictCount: Int,
    )

    // ── Public entry points ───────────────────────────────────────────────

    /**
     * Export backup to a SAF Uri (local file).
     *
     * @param password optional AES-GCM password; non-empty wraps the zip
     *                 with [BackupCrypto.encrypt]. Empty = legacy plain
     *                 zip, which is forward-compatible with restore code
     *                 detecting the magic header.
     * @param prefs Optional [AppPreferences] handle used to dump DataStore
     *              preferences when [BackupOptions.includePreferences] is on.
     *              Omit (null) to keep the legacy DB-only export — tests
     *              and any caller that doesn't care about preferences can
     *              skip wiring AppPreferences in.
     */
    suspend fun exportBackup(
        context: Context,
        db: AppDatabase,
        outputUri: Uri,
        password: String = "",
        options: BackupOptions = BackupOptions(),
        prefs: AppPreferences? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plainZip = zipBackup(buildBackupData(db, options, prefs))
                    val finalBytes = if (password.isNotEmpty()) {
                        BackupCrypto.encrypt(plainZip, password)
                    } else plainZip
                    context.contentResolver.openOutputStream(outputUri)?.use { it.write(finalBytes) }
                        ?: error("openOutputStream returned null for $outputUri")
                    AppLog.info("Backup", "Export completed (${finalBytes.size} bytes, encrypted=${password.isNotEmpty()}, full=${options.isFullExport()})")
                    true
                }.getOrElse {
                    recordError("Export failed", it)
                    runCatching {
                        context.contentResolver.delete(outputUri, null, null)
                    }.onFailure { delErr ->
                        AppLog.warn("Backup", "Could not remove 0-byte placeholder: ${delErr.message}")
                    }
                    false
                }
            }
        }

    /**
     * Import backup from a SAF Uri.
     *
     * Auto-detects encryption: if the first bytes match `MoREncBk`, the
     * blob is decrypted with [password] before unzipping. A wrong (or
     * blank) password against an encrypted blob returns false with an
     * `AppLog.warn` rather than crashing.
     *
     * **[opts]** lets the caller restore a subset (e.g. only books). Default
     * = restore everything in the backup, matching the legacy "fully apply"
     * behaviour so existing call sites that don't pass `opts` keep working.
     *
     * **[prefs]** non-null + `opts.includePreferences` true triggers
     * DataStore restore from the zip's `preferences` field after the DB
     * write succeeds. Failures here are logged and **not** propagated —
     * a partial preference loss should not undo the (more important)
     * book / source restore that already landed.
     */
    suspend fun importBackup(
        context: Context,
        db: AppDatabase,
        inputUri: Uri,
        password: String = "",
        opts: RestoreOptions = RestoreOptions(),
        prefs: AppPreferences? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val raw = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                        ?: ByteArray(0)
                    val plainZip = unwrapMaybeEncrypted(raw, password) ?: return@runCatching false
                    val text = ByteArrayInputStream(plainZip).use { readBackupJson(it) }
                    if (text.isEmpty()) return@runCatching false
                    val data = json.decodeFromString<BackupData>(text)
                    applyBackup(db, data, opts)
                    applyPreferences(data, opts, prefs)
                    AppLog.info("Backup", "Import completed (opts=$opts)")
                    true
                }.getOrElse {
                    recordError("Import failed", it)
                    false
                }
            }
        }

    /** Generate backup as a ByteArray — used by WebDav upload. */
    suspend fun generateBackupBytes(
        @Suppress("UNUSED_PARAMETER") context: Context,
        db: AppDatabase,
        password: String = "",
        prefs: AppPreferences? = null,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plain = zipBackup(buildBackupData(db, prefs = prefs))
                    if (password.isNotEmpty()) BackupCrypto.encrypt(plain, password) else plain
                }.getOrElse {
                    recordError("Generate bytes failed", it)
                    null
                }
            }
        }

    /** Apply a backup ByteArray — used by WebDav restore. */
    suspend fun importBackupFromBytes(
        @Suppress("UNUSED_PARAMETER") context: Context,
        db: AppDatabase,
        data: ByteArray,
        password: String = "",
        opts: RestoreOptions = RestoreOptions(),
        prefs: AppPreferences? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plain = unwrapMaybeEncrypted(data, password) ?: return@runCatching false
                    val text = ByteArrayInputStream(plain).use { readBackupJson(it) }
                    if (text.isEmpty()) return@runCatching false
                    val parsed = json.decodeFromString<BackupData>(text)
                    applyBackup(db, parsed, opts)
                    applyPreferences(parsed, opts, prefs)
                    AppLog.info("Backup", "Import from bytes completed (opts=$opts)")
                    true
                }.getOrElse {
                    recordError("Import from bytes failed", it)
                    false
                }
            }
        }

    /**
     * Restore DataStore preferences from [data.preferences] when the user
     * opted in and a [prefs] handle was supplied. Failures here are logged
     * and **swallowed** — the DB write already succeeded above and we don't
     * want a corrupt prefs blob to undo that, so the user keeps their books
     * even if a few settings need to be re-applied.
     */
    private suspend fun applyPreferences(
        data: BackupData,
        opts: RestoreOptions,
        prefs: AppPreferences?,
    ) {
        if (!opts.includePreferences) return
        if (prefs == null) return
        if (data.preferences.isBlank()) return
        runCatching {
            val map = PreferenceSnapshot.load(data.preferences)
            if (map.isEmpty()) {
                AppLog.warn("Backup", "preferences blob present but produced empty map (decode skip?)")
                return@runCatching
            }
            val written = prefs.applySnapshotRaw(map, PreferenceSnapshot.IGNORED_KEYS)
            AppLog.info("Backup", "Restored $written preference entries (${map.size - written} ignored / failed)")
        }.onFailure {
            AppLog.warn("Backup", "preferences restore failed: ${it.message}")
        }
    }

    /**
     * If [raw] looks encrypted, decrypt with [password] (returns null if
     * decryption fails — caller treats as restore-failed). Otherwise
     * return [raw] unchanged so legacy plain-zip backups still work.
     */
    private fun unwrapMaybeEncrypted(raw: ByteArray, password: String): ByteArray? {
        if (!BackupCrypto.isEncrypted(raw)) return raw
        if (password.isEmpty()) {
            AppLog.warn("Backup", "Encrypted backup but no password provided")
            return null
        }
        val plain = BackupCrypto.decrypt(raw, password)
        if (plain == null) {
            AppLog.warn("Backup", "Decryption failed — wrong password or tampered blob")
        }
        return plain
    }

    // ── Shared core ───────────────────────────────────────────────────────

    /**
     * Build the [BackupData] payload from the database.
     *
     * Both export paths (SAF + WebDav) call this single function so the
     * persisted field set can never diverge between the two — the previous
     * `generateBackupBytes()` was missing themes / reader styles, causing
     * silent loss on multi-device WebDav restore.
     *
     * Categories disabled in [options] are written as empty strings; the
     * restore path's `if (foo.isNotBlank()) ...` guards already skip those.
     *
     * **[prefs]** non-null + `options.includePreferences` true triggers a
     * DataStore snapshot via [PreferenceSnapshot.dump]. The dump runs
     * inside [Dispatchers.IO] (caller already enforced) and reads the
     * current snapshot once with `first()`. The black-list filter
     * (`PreferenceSnapshot.IGNORED_KEYS`) is applied at *restore* time, not
     * here — backups remain "complete" so a future code change can choose
     * to honour previously-ignored keys without needing users to re-export.
     */
    private suspend fun buildBackupData(
        db: AppDatabase,
        options: BackupOptions = BackupOptions(),
        prefs: AppPreferences? = null,
    ): BackupData {
        val bookDao = db.bookDao()
        val bookmarkDao = db.bookmarkDao()
        val sourceDao = db.bookSourceDao()
        val progressDao = db.readProgressDao()
        val groupDao = db.bookGroupDao()
        val replaceRuleDao = db.replaceRuleDao()
        val themeDao = db.themeDao()
        val readerStyleDao = db.readerStyleDao()

        val preferencesJson = if (options.includePreferences && prefs != null) {
            runCatching { PreferenceSnapshot.dump(prefs.snapshotAllRaw()) }
                .onFailure { AppLog.warn("Backup", "preferences dump failed: ${it.message}") }
                .getOrDefault("")
        } else ""

        return BackupData(
            books = if (options.includeBooks) json.encodeToString(bookDao.getAllBooksSync()) else "",
            bookmarks = if (options.includeBookmarks) json.encodeToString(bookmarkDao.getAllSync()) else "",
            sources = if (options.includeSources) json.encodeToString(sourceDao.getEnabledSourcesList()) else "",
            progress = if (options.includeProgress) json.encodeToString(progressDao.getAllSync()) else "",
            groups = if (options.includeGroups) json.encodeToString(groupDao.getAllGroupsSync()) else "",
            replaceRules = if (options.includeReplaceRules) json.encodeToString(replaceRuleDao.getAllSync()) else "",
            themes = if (options.includeThemes) json.encodeToString(themeDao.getAllSync()) else "",
            readerStyles = if (options.includeReaderStyles) json.encodeToString(readerStyleDao.getAllSync()) else "",
            preferences = preferencesJson,
            // bgImageManifest 由 Stage 3 填充；当前留空让旧路径无副作用通过。
            // httpTts intentionally left empty here — restore handles missing
            // string transparently. P1 will widen the field set; this commit
            // only fixes the export-path divergence bug.
        )
    }

    /**
     * Compute per-category preview rows for the export-options page.
     *
     * `estimatedBytes` is the **ZIP-compressed** byte count (DEFLATE) of that
     * section's JSON payload, so the value displayed to the user closely
     * matches what actually lands inside the final .zip. Previously this
     * field reported raw JSON length, which inflated the displayed total
     * 5-10x compared to the real export — a 700 KB JSON body typically zips
     * down to ~120 KB. Wrapping overhead (single-entry zip header,
     * encryption header) is not included; the sum across selected sections
     * is therefore a slight upper bound vs. one combined zip (which can
     * share the deflate dictionary across sections), but it tracks the
     * actual file size much better than the old raw-JSON estimate.
     *
     * Reads each table once on Dispatchers.IO; all eight queries are sync
     * Room calls already used by [buildBackupData], so cost mirrors a
     * regular full export's read phase.
     */
    suspend fun previewBackupSections(
        db: AppDatabase,
        prefs: AppPreferences? = null,
    ): List<BackupSectionInfo> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val books = db.bookDao().getAllBooksSync()
                    val bookmarks = db.bookmarkDao().getAllSync()
                    val sources = db.bookSourceDao().getEnabledSourcesList()
                    val progress = db.readProgressDao().getAllSync()
                    val groups = db.bookGroupDao().getAllGroupsSync()
                    val replaceRules = db.replaceRuleDao().getAllSync()
                    val themes = db.themeDao().getAllSync()
                    val readerStyles = db.readerStyleDao().getAllSync()

                    val rows = mutableListOf(
                        BackupSectionInfo("books", "书籍", books.size, zippedSize(json.encodeToString(books))),
                        BackupSectionInfo("bookmarks", "书签", bookmarks.size, zippedSize(json.encodeToString(bookmarks))),
                        BackupSectionInfo("sources", "书源", sources.size, zippedSize(json.encodeToString(sources))),
                        BackupSectionInfo("progress", "阅读进度", progress.size, zippedSize(json.encodeToString(progress))),
                        BackupSectionInfo("groups", "分组", groups.size, zippedSize(json.encodeToString(groups))),
                        BackupSectionInfo("replaceRules", "替换规则", replaceRules.size, zippedSize(json.encodeToString(replaceRules))),
                        BackupSectionInfo("themes", "主题", themes.size, zippedSize(json.encodeToString(themes))),
                        BackupSectionInfo("readerStyles", "阅读样式", readerStyles.size, zippedSize(json.encodeToString(readerStyles))),
                    )

                    if (prefs != null) {
                        // 应用偏好预览：itemCount = 当前 DataStore 写过的 key 数；
                        // estimatedBytes 用 prefs JSON 的 zip 后体积，跟其它段口径一致。
                        runCatching {
                            val raw = prefs.snapshotAllRaw()
                            val prefsJson = PreferenceSnapshot.dump(raw)
                            rows += BackupSectionInfo(
                                key = "preferences",
                                label = "应用偏好",
                                itemCount = raw.size,
                                estimatedBytes = zippedSize(prefsJson),
                            )
                        }.onFailure {
                            AppLog.warn("Backup", "preferences preview failed: ${it.message}")
                        }
                    }

                    rows.toList()
                }.getOrElse {
                    recordError("Preview sections failed", it)
                    emptyList()
                }
            }
        }

    /**
     * Zip a single JSON string into a one-entry archive (DEFLATE, default
     * level) and return the resulting byte count. Used by
     * [previewBackupSections] so the preview UI reports compressed sizes
     * comparable to the real export. Cheap: pure in-memory, ~100 ms even
     * for a couple of MB of source text.
     */
    private fun zippedSize(jsonStr: String): Int {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("section.json"))
            zos.write(jsonStr.toByteArray())
            zos.closeEntry()
        }
        return baos.size()
    }

    /**
     * Read a backup zip (raw bytes, possibly encrypted) and report per-section
     * item count + how many of those rows would overwrite existing local data.
     *
     * Used by the **restore** options page so the user can decide whether each
     * category is worth restoring — the conflict count makes overwrites
     * explicit ("将覆盖 23 条本地书源") instead of silent.
     *
     * Failure modes:
     *  - encrypted blob + wrong password → empty list, lastErrorMessage set
     *  - malformed zip / non-backup zip → empty list, lastErrorMessage set
     *  - per-section decode failure → that one section reports `itemCount=0,
     *    conflictCount=0` (degraded but doesn't kill the rest)
     *
     * Reads the local DB once for each section to compute conflicts; cost is
     * comparable to a full export's read phase, which is acceptable for a
     * one-shot preview triggered by user navigation.
     */
    suspend fun previewRestoreSections(
        db: AppDatabase,
        rawZipBytes: ByteArray,
        password: String = "",
    ): List<RestoreSectionInfo> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plain = unwrapMaybeEncrypted(rawZipBytes, password)
                        ?: error("解密失败 — 密码错误或备份已损坏")
                    val text = ByteArrayInputStream(plain).use { readBackupJson(it) }
                    if (text.isEmpty()) error("备份文件不含 backup.json — 不是有效的 MoRealm 备份")
                    val data = json.decodeFromString<BackupData>(text)

                    val results = mutableListOf<RestoreSectionInfo>()

                    // ── 书籍 ──
                    sectionPreview(
                        key = "books", label = "书籍", payload = data.books,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.Book>>(it) },
                        keyOf = { it.id },
                        existingKeys = { db.bookDao().getAllBooksSync().map { b -> b.id }.toHashSet() },
                    )?.let(results::add)

                    // ── 书签 ──
                    sectionPreview(
                        key = "bookmarks", label = "书签", payload = data.bookmarks,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.Bookmark>>(it) },
                        keyOf = { it.id },
                        existingKeys = { db.bookmarkDao().getAllSync().map { b -> b.id }.toHashSet() },
                    )?.let(results::add)

                    // ── 书源 ──（主键 = bookSourceUrl）
                    sectionPreview(
                        key = "sources", label = "书源", payload = data.sources,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.BookSource>>(it) },
                        keyOf = { it.bookSourceUrl },
                        existingKeys = { db.bookSourceDao().getEnabledSourcesList().map { it.bookSourceUrl }.toHashSet() },
                    )?.let(results::add)

                    // ── 阅读进度 ──（主键 = bookId）
                    sectionPreview(
                        key = "progress", label = "阅读进度", payload = data.progress,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.ReadProgress>>(it) },
                        keyOf = { it.bookId },
                        existingKeys = { db.readProgressDao().getAllSync().map { it.bookId }.toHashSet() },
                    )?.let(results::add)

                    // ── 分组 ──
                    sectionPreview(
                        key = "groups", label = "分组", payload = data.groups,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.BookGroup>>(it) },
                        keyOf = { it.id },
                        existingKeys = { db.bookGroupDao().getAllGroupsSync().map { it.id }.toHashSet() },
                    )?.let(results::add)

                    // ── 替换规则 ──
                    sectionPreview(
                        key = "replaceRules", label = "替换规则", payload = data.replaceRules,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.ReplaceRule>>(it) },
                        keyOf = { it.id },
                        existingKeys = { db.replaceRuleDao().getAllSync().map { it.id }.toHashSet() },
                    )?.let(results::add)

                    // ── 主题 ──
                    sectionPreview(
                        key = "themes", label = "主题", payload = data.themes,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.ThemeEntity>>(it) },
                        keyOf = { it.id },
                        existingKeys = { db.themeDao().getAllSync().map { it.id }.toHashSet() },
                    )?.let(results::add)

                    // ── 阅读样式 ──
                    sectionPreview(
                        key = "readerStyles", label = "阅读样式", payload = data.readerStyles,
                        decode = { json.decodeFromString<List<com.morealm.app.domain.entity.ReaderStyle>>(it) },
                        keyOf = { it.id },
                        existingKeys = { db.readerStyleDao().getAllSync().map { it.id }.toHashSet() },
                    )?.let(results::add)

                    // ── 应用偏好 ──（key set 不算"覆盖"语义，conflictCount=0；
                    // itemCount 显示快照里有多少个 key —— 用户能直观看到"本机会有 N 个设置被改"）
                    if (data.preferences.isNotBlank()) {
                        runCatching {
                            val map = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(data.preferences)
                            results += RestoreSectionInfo(
                                key = "preferences",
                                label = "应用偏好",
                                itemCount = map.size,
                                conflictCount = 0,
                            )
                        }.onFailure {
                            AppLog.warn("Backup", "preferences preview decode failed: ${it.message}")
                        }
                    }

                    results
                }.getOrElse {
                    recordError("Preview restore failed", it)
                    emptyList()
                }
            }
        }

    /**
     * Helper: decode one [payload] field, compute conflicts, return null when
     * the field is blank (= section absent in this backup, don't show it).
     * Per-section failure is caught here so one bad section doesn't sink the
     * whole preview.
     */
    private suspend inline fun <reified T> sectionPreview(
        key: String,
        label: String,
        payload: String,
        decode: (String) -> List<T>,
        keyOf: (T) -> String,
        existingKeys: suspend () -> HashSet<String>,
    ): RestoreSectionInfo? {
        if (payload.isBlank()) return null
        return runCatching {
            val incoming = decode(payload)
            val local = existingKeys()
            val conflicts = incoming.count { keyOf(it) in local }
            RestoreSectionInfo(key, label, incoming.size, conflicts)
        }.getOrElse {
            AppLog.warn("Backup", "$key preview failed: ${it.message}")
            RestoreSectionInfo(key, label, 0, 0)
        }
    }

    /**
     * Apply a [BackupData] payload to the database.
     *
     * Both restore paths (SAF + WebDav) share this. Empty / blank string fields
     * are skipped so old backup zips that pre-date a new field still restore
     * cleanly; deserialisation errors per section are isolated so one bad
     * field cannot abort the whole restore.
     *
     * **[opts]** lets the UI restore a subset (e.g. "only books, leave my
     * sources alone"). When a flag is off, the section is treated identically
     * to "section absent in zip" — neither error nor partial write. The
     * default `RestoreOptions()` enables everything, which matches the
     * pre-Stage-1 behaviour so any caller that didn't pass opts still
     * restores the full payload.
     *
     * Books are special: when [opts.includeBooks] is true and the field is
     * non-blank, parse failure propagates so the user sees a real error
     * instead of "imported 0 books, all good". Other sections swallow per-
     * section failures with an AppLog.error to keep the rest of the restore
     * moving.
     */
    private suspend fun applyBackup(
        db: AppDatabase,
        backup: BackupData,
        opts: RestoreOptions = RestoreOptions(),
    ) {
        val bookDao = db.bookDao()
        val bookmarkDao = db.bookmarkDao()
        val sourceDao = db.bookSourceDao()
        val progressDao = db.readProgressDao()
        val groupDao = db.bookGroupDao()
        val replaceRuleDao = db.replaceRuleDao()

        var restoredBookCount = 0
        if (opts.includeBooks && backup.books.isNotBlank()) {
            // Books are mandatory when the user opted in — let parse failure
            // propagate so they get a real error instead of silent 0-count.
            val books = json.decodeFromString<List<com.morealm.app.domain.entity.Book>>(backup.books)
            bookDao.insertAll(books)
            restoredBookCount = books.size
        }

        if (opts.includeBookmarks && backup.bookmarks.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.Bookmark>>(backup.bookmarks)
                .forEach { bookmarkDao.insert(it) }
        }.onFailure { AppLog.error("Backup", "bookmarks decode failed: ${it.message}") }

        if (opts.includeSources && backup.sources.isNotBlank()) runCatching {
            val sources = json.decodeFromString<List<com.morealm.app.domain.entity.BookSource>>(backup.sources)
            sourceDao.insertAll(sources)
        }.onFailure { AppLog.error("Backup", "sources decode failed: ${it.message}") }

        if (opts.includeProgress && backup.progress.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.ReadProgress>>(backup.progress)
                .forEach { progressDao.save(it) }
        }.onFailure { AppLog.error("Backup", "progress decode failed: ${it.message}") }

        if (opts.includeGroups && backup.groups.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.BookGroup>>(backup.groups)
                .forEach { groupDao.insert(it) }
        }.onFailure { AppLog.error("Backup", "groups decode failed: ${it.message}") }

        if (opts.includeReplaceRules && backup.replaceRules.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.ReplaceRule>>(backup.replaceRules)
                .forEach { replaceRuleDao.insert(it) }
        }.onFailure { AppLog.error("Backup", "replaceRules decode failed: ${it.message}") }

        if (opts.includeThemes && backup.themes.isNotBlank()) runCatching {
            val themes = json.decodeFromString<List<com.morealm.app.domain.entity.ThemeEntity>>(backup.themes)
            db.themeDao().upsertAll(themes)
        }.onFailure { AppLog.error("Backup", "themes decode failed: ${it.message}") }

        if (opts.includeReaderStyles && backup.readerStyles.isNotBlank()) runCatching {
            val styles = json.decodeFromString<List<com.morealm.app.domain.entity.ReaderStyle>>(backup.readerStyles)
            db.readerStyleDao().upsertAll(styles)
        }.onFailure { AppLog.error("Backup", "readerStyles decode failed: ${it.message}") }

        // [opts.includePreferences] is honoured here; the actual DataStore
        // write happens in [importBackup]/[importBackupFromBytes] after this
        // function returns — those paths have access to AppPreferences.
        // Doing the prefs write *outside* applyBackup keeps this function
        // free of `Context`/`AppPreferences` dependencies (it only sees the
        // database, matching its current contract).

        AppLog.info("Backup", "Applied: $restoredBookCount books restored (opts=$opts)")
    }

    // ── Zip helpers ───────────────────────────────────────────────────────

    /** Encode [BackupData] as JSON and wrap it in a single-entry zip. */
    private fun zipBackup(data: BackupData): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("backup.json"))
            zos.write(json.encodeToString(data).toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Read the `backup.json` entry from a zip [InputStream]; returns "" if absent. */
    private fun readBackupJson(stream: InputStream): String {
        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") {
                    return zis.bufferedReader().readText()
                }
                entry = zis.nextEntry
            }
        }
        return ""
    }
}
