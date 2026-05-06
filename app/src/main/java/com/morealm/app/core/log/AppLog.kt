package com.morealm.app.core.log

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

// ── Log Level ────────────────────────────────────────

enum class LogLevel(val priority: Int, val label: String) {
    VERBOSE(0, "V"),
    DEBUG(1, "D"),
    INFO(2, "I"),
    WARN(3, "W"),
    ERROR(4, "E"),
    FATAL(5, "F");
}

// ── Log Record ───────────────────────────────────────

data class LogRecord(
    val id: Long,
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,
    val threadName: String = Thread.currentThread().name,
) {
    fun format(): String {
        val ts = TIME_FORMAT.get()!!.format(Date(time))
        val t = if (throwable != null) "\n$throwable" else ""
        return "[$ts] ${level.label}/$tag [$threadName]: $message$t"
    }

    companion object {
        private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

// ── Log Sink Interface (extensible appender) ─────────

interface LogSink {
    val name: String
    var minLevel: LogLevel
    fun write(record: LogRecord)
    fun flush() {}
    fun close() {}
}

// ── Built-in Sinks ───────────────────────────────────

/** Logcat output — synchronous, always available */
class LogcatSink(override var minLevel: LogLevel = LogLevel.VERBOSE) : LogSink {
    override val name = "logcat"
    override fun write(record: LogRecord) {
        val msg = record.message
        val t = record.throwable
        when (record.level) {
            LogLevel.VERBOSE -> Log.v(record.tag, msg)
            LogLevel.DEBUG -> Log.d(record.tag, msg)
            LogLevel.INFO -> Log.i(record.tag, msg)
            LogLevel.WARN -> if (t != null) Log.w(record.tag, "$msg\n$t") else Log.w(record.tag, msg)
            LogLevel.ERROR -> if (t != null) Log.e(record.tag, "$msg\n$t") else Log.e(record.tag, msg)
            LogLevel.FATAL -> Log.wtf(record.tag, if (t != null) "$msg\n$t" else msg)
        }
    }
}

/** In-memory ring buffer — synchronous, for UI log viewer.
 *
 *  [maxEntries] is `@Volatile var` so the user-facing settings panel can shrink
 *  the cap at runtime; the next [write] / [writeForce] / [resizeIfNeeded] call
 *  trims the buffer down to the new size. Growing the cap is a no-op until
 *  more records arrive — we don't synthesize entries.
 */
class MemorySink(
    override var minLevel: LogLevel = LogLevel.DEBUG,
    initialMaxEntries: Int = 1000,
) : LogSink {
    override val name = "memory"
    @Volatile var maxEntries: Int = initialMaxEntries
        set(value) {
            field = value.coerceAtLeast(50)
            // Eagerly trim so a downsize is visible in the UI immediately,
            // not only after the next log line arrives.
            trimToCap()
            _flow.value = buffer.toList()
        }
    private val buffer = ConcurrentLinkedDeque<LogRecord>()
    private val _flow = MutableStateFlow<List<LogRecord>>(emptyList())
    val records: StateFlow<List<LogRecord>> = _flow.asStateFlow()

    private fun trimToCap() {
        while (buffer.size > maxEntries) buffer.pollFirst()
    }

    override fun write(record: LogRecord) {
        buffer.addLast(record)
        trimToCap()
        _flow.value = buffer.toList()
    }

    /** Force-write bypassing minLevel check (for crash/ANR records) */
    fun writeForce(record: LogRecord) {
        buffer.addLast(record)
        trimToCap()
        _flow.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _flow.value = emptyList()
    }

    fun loadFromFile(file: File, dateBase: String) {
        if (!file.exists()) return
        val lineRegex = Regex("""\[(\d{2}:\d{2}:\d{2}\.\d{3})] ([VDIWEF])/(\S+) \[.*?]: (.+)""")
        val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        file.readLines().takeLast(maxEntries).forEach { line ->
            val m = lineRegex.find(line) ?: return@forEach
            val (ts, lv, tag, msg) = m.destructured
            val level = LogLevel.entries.find { it.label == lv } ?: LogLevel.INFO
            val time = try { fullFmt.parse("$dateBase $ts")?.time ?: 0L } catch (_: Exception) { 0L }
            if (time > 0) buffer.addLast(LogRecord(id = AppLog.nextId(), time, level, tag, msg))
        }
        while (buffer.size > maxEntries) buffer.pollFirst()
        _flow.value = buffer.toList()
    }

    /** Load crash file summaries into memory so they appear in the UI log viewer */
    fun loadCrashFiles(logDir: File) {
        val crashFiles = logDir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedBy { it.lastModified() } ?: return
        for (file in crashFiles.takeLast(10)) {
            val lines = file.readLines()
            val timeLine = lines.find { it.startsWith("Time:") }
            val threadLine = lines.find { it.startsWith("Thread:") }
            val exceptionStart = lines.indexOf("--- Exception ---")
            val exceptionMsg = if (exceptionStart >= 0 && exceptionStart + 1 < lines.size) {
                lines.drop(exceptionStart + 1).take(3).joinToString("\n")
            } else file.name
            val time = try {
                timeLine?.substringAfter("Time:")?.trim()?.let {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(it)?.time
                } ?: file.lastModified()
            } catch (_: Exception) { file.lastModified() }
            val thread = threadLine?.substringAfter("Thread:")?.trim() ?: "?"
            buffer.addLast(LogRecord(id = AppLog.nextId(),
                time = time,
                level = LogLevel.FATAL,
                tag = "CRASH",
                message = "[历史崩溃] $thread: ${exceptionMsg.lines().firstOrNull() ?: file.name}",
                throwable = exceptionMsg,
                threadName = thread,
            ))
        }
        while (buffer.size > maxEntries) buffer.pollFirst()
        _flow.value = buffer.toList()
    }
}

/**
 * Rolling file sink — async by default, thread-safe.
 * Writes are queued to a background thread to avoid blocking callers.
 * Supports rolling by size and by date.
 *
 * All limit fields ([maxFileSize], [maxFiles], [maxAgeDays], [maxLogFiles],
 * [maxCrashFiles], [maxTotalBytes]) are `@Volatile var` so the user-facing
 * settings panel can adjust them at runtime. [maxFileSize] / [maxFiles]
 * affect the next rotation; the rest affect the next [enforceLimits] pass.
 *
 * Rate-limited self-enforce: every [enforceEveryWrites] writes OR after
 * [enforceEveryMs] ms have elapsed, [writeSync] runs an [enforceLimits]
 * pass on the writer thread. This piggybacks on the existing async writer
 * so we never spawn a second thread or stat the directory on the caller's
 * thread. Idle apps pay nothing; chatty apps pay proportionally.
 */
class RollingFileSink(
    private val logDir: File,
    override var minLevel: LogLevel = LogLevel.INFO,
    initialMaxFileSize: Long = 2 * 1024 * 1024L,
    initialMaxFiles: Int = 10,
    private val async: Boolean = true,
) : LogSink {

    override val name = "file"
    @Volatile var maxFileSize: Long = initialMaxFileSize
    @Volatile var maxFiles: Int = initialMaxFiles
    /** Whole-directory limits — used by the rate-limited enforcer. Set by
     *  AppLog via [updateLimits] from persisted user prefs. Defaults match
     *  the previous hard-coded constants (7 days / 40 log_* / 20 crash_* /
     *  unlimited size — 0 means "no total-size cap"). */
    @Volatile var maxAgeDays: Int = 7
    @Volatile var maxLogFiles: Int = 40
    @Volatile var maxCrashFiles: Int = 20
    @Volatile var maxTotalBytes: Long = 0L  // 0 = no cap

    /** Rate-limit knobs — every Nth write OR every Mth ms, run enforce. */
    @Volatile var enforceEveryWrites: Int = 200
    @Volatile var enforceEveryMs: Long = 30 * 60_000L  // 30 min

    private val writeSinceEnforce = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastEnforceMs: Long = System.currentTimeMillis()

    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val queue = LinkedBlockingQueue<LogRecord>(4096)
    private val running = AtomicBoolean(true)
    @Volatile private var currentFile: File? = null
    @Volatile private var currentDate: String = ""

    private val writerThread = Thread({
        while (running.get() || queue.isNotEmpty()) {
            try {
                val record = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                writeSync(record)
                // Rate-limited enforce runs ONLY on the async writer path —
                // never on the crash/ANR sync path (writeImmediate). Doing
                // a full directory scan + cascading deletes mid-crash would
                // delay the OS's "App stopped" dialog and risk losing the
                // crash record itself if a kill arrives while we're in
                // listFiles. Cleanup can wait until next launch.
                maybeEnforce()
            } catch (_: InterruptedException) { break }
        }
    }, "MoRealm-LogWriter").apply { isDaemon = true; start() }

    override fun write(record: LogRecord) {
        if (async) {
            if (!queue.offer(record)) {
                // Queue full — drop oldest and retry
                queue.poll()
                queue.offer(record)
            }
        } else {
            writeSync(record)
            maybeEnforce()  // sync mode is dev-only / tests; piggyback fine
        }
    }

    @Synchronized
    private fun writeSync(record: LogRecord) {
        try {
            val date = dateFmt.get()!!.format(Date(record.time))
            if (date != currentDate || currentFile == null) {
                currentDate = date
                currentFile = File(logDir, "log_$date.txt")
            }
            val file = currentFile!!
            if (file.exists() && file.length() > maxFileSize) {
                rotate(file, date)
            }
            file.appendText(record.format() + "\n")
        } catch (_: Exception) {}
    }

    /** Trigger an [enforceLimits] pass if the rate-limit gate has opened.
     *  Always runs on the writer thread (called from [writeSync] or
     *  [writeImmediate]). The expensive `listFiles` + per-file `length` /
     *  `lastModified` syscalls happen at most once per [enforceEveryWrites]
     *  records or per [enforceEveryMs] ms, whichever fires first. */
    private fun maybeEnforce() {
        val count = writeSinceEnforce.incrementAndGet()
        val now = System.currentTimeMillis()
        if (count >= enforceEveryWrites || now - lastEnforceMs >= enforceEveryMs) {
            writeSinceEnforce.set(0)
            lastEnforceMs = now
            try {
                enforceLimits(maxAgeDays, maxLogFiles, maxCrashFiles, maxTotalBytes)
            } catch (_: Throwable) {
                // Cleanup must never crash the writer thread. If a delete
                // fails we'll just retry on the next gate opening.
            }
        }
    }

    private fun rotate(file: File, date: String) {
        for (i in maxFiles - 1 downTo 1) {
            val src = File(logDir, "log_${date}_$i.txt")
            val dst = File(logDir, "log_${date}_${i + 1}.txt")
            if (src.exists()) { if (i + 1 > maxFiles) src.delete() else src.renameTo(dst) }
        }
        file.renameTo(File(logDir, "log_${date}_1.txt"))
    }

    /** Force-write a record synchronously (for crash handler) */
    fun writeImmediate(record: LogRecord) = writeSync(record)

    override fun flush() { while (queue.isNotEmpty()) Thread.sleep(10) }

    override fun close() {
        running.set(false)
        writerThread.interrupt()
        writerThread.join(2000)
    }

    fun todayFile(): File = File(logDir, "log_${dateFmt.get()!!.format(Date())}.txt")

    /**
     * Enforce age + count + total-size limits on every file in [logDir].
     * Called both on app start (from `AppLog.init`) and periodically by
     * the rate-limited self-enforcer in [maybeEnforce].
     *
     * Order matters:
     *   1. Age cull — anything older than [maxAgeDays] days is deleted regardless
     *      of category. This is the cheap pass; most older files have been gone
     *      for a while and never get re-checked.
     *   2. Count cull — survivors are split by filename prefix and the
     *      newest-first window of [maxLogFiles] / [maxCrashFiles] is kept;
     *      the rest are deleted. Files that don't match either prefix
     *      (e.g. cached export zips) are left alone.
     *   3. Total-size cull — if [maxTotalBytes] > 0 and the sum of
     *      `log_*` + `crash_*` sizes still exceeds the cap, delete oldest
     *      `log_*` first (we keep crash records longer because they're
     *      irreplaceable diagnostic state) until under the cap. The
     *      currently-open day file is excluded so we don't kick the foot
     *      out from under [writeSync].
     *
     * Idempotent and cheap (single `listFiles` + small in-memory sort).
     * Silently no-ops if [logDir] doesn't exist.
     *
     * Returns counts so callers (the user-facing "立即清理" button) can show
     * a confirmation toast.
     */
    fun enforceLimits(
        maxAgeDays: Int,
        maxLogFiles: Int,
        maxCrashFiles: Int,
        maxTotalBytes: Long = 0L,
    ): CleanupReport {
        val files = logDir.listFiles() ?: return CleanupReport()
        val ageCutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        var deletedLogs = 0
        var deletedCrashes = 0
        var freedBytes = 0L

        // Pass 1 — age cull
        val survivors = files.filter { f ->
            if (f.lastModified() < ageCutoff) {
                val sz = f.length()
                if (f.delete()) {
                    freedBytes += sz
                    when {
                        f.name.startsWith("log_") -> deletedLogs++
                        f.name.startsWith("crash_") -> deletedCrashes++
                    }
                    false
                } else true  // keep if delete failed (read-only FS, race, etc.)
            } else true
        }

        fun cullByCount(items: List<File>, keep: Int, isLogs: Boolean) {
            if (items.size <= keep) return
            items.sortedByDescending { it.lastModified() }
                .drop(keep)
                .forEach { f ->
                    val sz = f.length()
                    if (f.delete()) {
                        freedBytes += sz
                        if (isLogs) deletedLogs++ else deletedCrashes++
                    }
                }
        }

        // Pass 2 — count cull
        cullByCount(survivors.filter { it.name.startsWith("log_") }, maxLogFiles, isLogs = true)
        cullByCount(survivors.filter { it.name.startsWith("crash_") }, maxCrashFiles, isLogs = false)

        // Pass 3 — total-size cull (if a cap is set). Re-scan because
        // pass 1/2 may have deleted some of the survivors. Excluding the
        // currently-open day file is critical: rotating it out from under
        // an in-flight appendText would corrupt the log.
        if (maxTotalBytes > 0L) {
            val openName = currentFile?.name
            val remaining = (logDir.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("log_") || it.name.startsWith("crash_") }
            var total = remaining.sumOf { it.length() }
            if (total > maxTotalBytes) {
                // Delete oldest log_* first (preserve crash_* — they're
                // higher signal-to-noise per byte and the user rarely
                // needs week-old INFO logs after the fact).
                val candidates = remaining
                    .filter { it.name.startsWith("log_") && it.name != openName }
                    .sortedBy { it.lastModified() }
                for (f in candidates) {
                    if (total <= maxTotalBytes) break
                    val sz = f.length()
                    if (f.delete()) {
                        total -= sz
                        freedBytes += sz
                        deletedLogs++
                    }
                }
                // Still over after eating all log_*? Start eating oldest crash_*
                // too — the user explicitly asked for a hard cap.
                if (total > maxTotalBytes) {
                    val crashCandidates = remaining
                        .filter { it.name.startsWith("crash_") }
                        .sortedBy { it.lastModified() }
                    for (f in crashCandidates) {
                        if (total <= maxTotalBytes) break
                        val sz = f.length()
                        if (f.delete()) {
                            total -= sz
                            freedBytes += sz
                            deletedCrashes++
                        }
                    }
                }
            }
        }

        return CleanupReport(deletedLogs, deletedCrashes, freedBytes)
    }
}

/** Result of a cleanup pass. Surfaced to the UI as
 *  「已删 N 个文件，回收 X.X MB」. Zero values are perfectly normal
 *  (limits already satisfied). */
data class CleanupReport(
    val deletedLogFiles: Int = 0,
    val deletedCrashFiles: Int = 0,
    val freedBytes: Long = 0L,
) {
    val totalDeleted: Int get() = deletedLogFiles + deletedCrashFiles
    val freedMb: Double get() = freedBytes / 1024.0 / 1024.0
}

// ── AppLog Facade ────────────────────────────────────

/**
 * MoRealm logging facade.
 *
 * Architecture:
 *   AppLog.info("tag", "msg")
 *       ↓ dispatch to all registered sinks
 *   ┌─ LogcatSink   (sync, stdout)
 *   ├─ MemorySink    (sync, ring buffer → StateFlow for UI)
 *   └─ RollingFileSink (async writer thread, daily rolling, size rotation)
 *
 * Extensible: call addSink() to add custom sinks (network, database, etc.)
 * Thread-safe: all sinks handle concurrent writes.
 * Crash-safe: crash handler uses synchronous file write.
 */
object AppLog {

    private const val TAG = "MoRealm"

    // ── Default limits (used as fallback when no persisted prefs exist) ──
    // Were `const val` constants pre-cleanup-feature; now they're just
    // defaults — actual live values live in [currentLimits] and persist
    // via [logPrefs]. Hard floors / ceilings on each setter clamp user
    // input so a slip can't render the app silently log-less.
    private const val DEFAULT_MAX_AGE_DAYS = 7
    /** Hard cap on `log_*.txt` rolling files. Default keeps the natural
     *  ceiling (4 MB × 5 slices × 7 days ≈ 35) with breathing room. */
    private const val DEFAULT_MAX_LOG_FILES = 40
    /** Hard cap on `crash_*.txt`. UI shows 10 — keep more on disk so
     *  crash bursts don't drop history below what the UI hints at. */
    private const val DEFAULT_MAX_CRASH_FILES = 20
    private const val DEFAULT_MEM_ENTRIES = 1000
    private const val DEFAULT_FILE_SIZE_BYTES = 4L * 1024 * 1024
    /** 0 = no total-directory-size cap (the legacy behavior — only
     *  count + age limits applied). User can set it explicitly via
     *  the cleanup panel; we default to off so existing installs see
     *  no behavioral change after upgrade. */
    private const val DEFAULT_TOTAL_DIR_BYTES = 0L

    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
    fun nextId(): Long = idCounter.incrementAndGet()

    private val sinks = mutableListOf<LogSink>()
    private var memorySink: MemorySink? = null
    private var fileSink: RollingFileSink? = null
    private var deviceInfo: String = ""
    private var initialized = false

    /** SharedPreferences for [setRecordLog] persistence. Kept tiny + separate
     *  from the user-facing app prefs (`morealm_settings`) so the DataStore
     *  there isn't hit on every log toggle. Now also stores [LogLimits]. */
    private var logPrefs: android.content.SharedPreferences? = null
    private const val LOG_PREFS_NAME = "morealm_log_prefs"
    private const val KEY_RECORD_LOG = "record_log_enabled"
    private const val KEY_MEM_ENTRIES = "limit_mem_entries"
    private const val KEY_FILE_SIZE = "limit_file_size_bytes"
    private const val KEY_TOTAL_DIR = "limit_total_dir_bytes"
    private const val KEY_MAX_DAYS = "limit_max_days"

    /** Reactive log records for Compose UI */
    val logs: StateFlow<List<LogRecord>>
        get() = memorySink?.records ?: MutableStateFlow(emptyList())

    // ── Initialization ──

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val logDir = (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "logs").apply { mkdirs() } }

        // Pre-load persisted limits so the sinks are constructed with the
        // user's saved values, not the factory defaults. Falling back to
        // defaults on first launch (or after a "reset" that clears prefs).
        logPrefs = context.getSharedPreferences(LOG_PREFS_NAME, Context.MODE_PRIVATE)
        val initialMemEntries = logPrefs?.getInt(KEY_MEM_ENTRIES, DEFAULT_MEM_ENTRIES) ?: DEFAULT_MEM_ENTRIES
        val initialFileSize = logPrefs?.getLong(KEY_FILE_SIZE, DEFAULT_FILE_SIZE_BYTES) ?: DEFAULT_FILE_SIZE_BYTES
        val initialTotalDir = logPrefs?.getLong(KEY_TOTAL_DIR, DEFAULT_TOTAL_DIR_BYTES) ?: DEFAULT_TOTAL_DIR_BYTES
        val initialMaxDays = logPrefs?.getInt(KEY_MAX_DAYS, DEFAULT_MAX_AGE_DAYS) ?: DEFAULT_MAX_AGE_DAYS

        // Register built-in sinks — all use DEBUG as minimum to keep app UI and file in sync
        val logcat = LogcatSink(LogLevel.DEBUG)
        val memory = MemorySink(LogLevel.DEBUG, initialMemEntries)
        val file = RollingFileSink(
            logDir = logDir,
            minLevel = LogLevel.WARN,
            initialMaxFileSize = initialFileSize,
            initialMaxFiles = 5,
        ).apply {
            // Wire whole-directory limits read from prefs (sink's defaults
            // would otherwise be 7/40/20/0 — we overwrite explicitly so a
            // manual prefs edit can lower e.g. maxLogFiles below the default).
            maxAgeDays = initialMaxDays
            maxLogFiles = DEFAULT_MAX_LOG_FILES
            maxCrashFiles = DEFAULT_MAX_CRASH_FILES
            maxTotalBytes = initialTotalDir
        }

        memorySink = memory
        fileSink = file
        sinks += listOf(logcat, memory, file)

        deviceInfo = collectDeviceInfo(context)
        // Initial enforce on app start using the now-live limits. This
        // is the only synchronous full scan we do per process; subsequent
        // passes piggyback on the writer thread via [maybeEnforce].
        file.enforceLimits(
            maxAgeDays = file.maxAgeDays,
            maxLogFiles = file.maxLogFiles,
            maxCrashFiles = file.maxCrashFiles,
            maxTotalBytes = file.maxTotalBytes,
        )

        // Persisted "详细日志记录" toggle. Read once on init so a user who
        // turned it on yesterday still has DEBUG file logging today after a
        // process kill — without this the toggle was UI-only and reset every
        // launch, which is exactly what bit us when chasing the page-turn
        // flicker (DEBUG logs in memory + nothing on disk).
        val savedRecordLog = logPrefs?.getBoolean(KEY_RECORD_LOG, false) ?: false
        if (savedRecordLog) file.minLevel = LogLevel.DEBUG
        // Load previous crash files first so they appear in UI
        memory.loadCrashFiles(logDir)
        memory.loadFromFile(file.todayFile(), SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

        installCrashHandler(logDir)
        installAnrWatchdog()
        if (context is Application) installLifecycleMonitor(context)
    }

    // ── Public API ──

    fun verbose(tag: String, msg: String) = dispatch(LogLevel.VERBOSE, tag, msg)
    fun debug(tag: String, msg: String) = dispatch(LogLevel.DEBUG, tag, msg)
    fun info(tag: String, msg: String) = dispatch(LogLevel.INFO, tag, msg)
    fun warn(tag: String, msg: String, t: Throwable? = null) = dispatch(LogLevel.WARN, tag, msg, t)
    fun error(tag: String, msg: String, t: Throwable? = null) = dispatch(LogLevel.ERROR, tag, msg, t)
    fun fatal(tag: String, msg: String, t: Throwable? = null) = dispatch(LogLevel.FATAL, tag, msg, t)

    fun verbose(msg: String) = verbose(TAG, msg)
    fun debug(msg: String) = debug(TAG, msg)
    fun info(msg: String) = info(TAG, msg)
    fun warn(msg: String, t: Throwable? = null) = warn(TAG, msg, t)
    fun error(msg: String, t: Throwable? = null) = error(TAG, msg, t)
    fun fatal(msg: String, t: Throwable? = null) = fatal(TAG, msg, t)

    /** Clear in-memory logs */
    fun clear() { memorySink?.clear() }

    /** Add a custom sink at runtime */
    fun addSink(sink: LogSink) { sinks += sink }

    /** Remove a sink by name */
    fun removeSink(name: String) { sinks.removeAll { it.name == name } }

    /** Flush all async sinks */
    fun flush() { sinks.forEach { it.flush() } }

    /** Shutdown all sinks (call on app exit) */
    fun shutdown() { sinks.forEach { it.close() } }

    // ── Query API ──

    fun getRecentLogs(count: Int = 100): List<LogRecord> =
        memorySink?.records?.value?.takeLast(count) ?: emptyList()

    fun getLogText(): String =
        memorySink?.records?.value?.joinToString("\n") { it.format() } ?: ""

    fun getCrashFiles(): List<File> =
        fileSink?.todayFile()?.parentFile
            ?.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun getAllLogFiles(): List<File> =
        fileSink?.todayFile()?.parentFile
            ?.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun getLogDirPath(): String =
        fileSink?.todayFile()?.parentFile?.absolutePath ?: "N/A"

    fun getLogDir(): File? = fileSink?.todayFile()?.parentFile

    /** Check if there are crash files from previous sessions */
    fun hasPendingCrash(): Boolean = getCrashFiles().isNotEmpty()

    /** Enable/disable detailed file logging (recordLog toggle). Persists to
     *  [LOG_PREFS_NAME] so the choice survives process kill — the toggle is
     *  read back in [init]. UI passes through here on every state change. */
    fun setRecordLog(enabled: Boolean) {
        fileSink?.minLevel = if (enabled) LogLevel.DEBUG else LogLevel.WARN
        logPrefs?.edit()?.putBoolean(KEY_RECORD_LOG, enabled)?.apply()
    }

    /** Current persisted state of [setRecordLog] — UI uses this to seed the
     *  switch's initial state so it doesn't visually drift from the actual
     *  fileSink minLevel after a process restart. */
    fun isRecordLogEnabled(): Boolean =
        logPrefs?.getBoolean(KEY_RECORD_LOG, false) ?: false

    // ── Log size / retention limits (user-configurable) ──

    /** Snapshot of the user-tunable retention knobs. The 4 fields below are
     *  what the cleanup panel exposes; harder caps ([maxLogFiles] /
     *  [maxCrashFiles]) live as private constants because they're tied to
     *  the rotation arithmetic and shouldn't be lowered without thinking. */
    data class LogLimits(
        /** In-memory ring buffer cap. Affects the log viewer scroll length. */
        val memoryEntries: Int,
        /** Single rolling-file size before [RollingFileSink.rotate] kicks in. */
        val maxFileSizeBytes: Long,
        /** Whole-directory soft cap. 0 = no cap (legacy behavior). When the
         *  rate-limited enforcer or [cleanupNow] sees the directory exceed
         *  this, it deletes oldest `log_*` first, then `crash_*`. */
        val maxTotalDirBytes: Long,
        /** Files older than this are deleted regardless of category. */
        val maxAgeDays: Int,
    ) {
        companion object {
            /** Defaults — used when prefs are missing or after a reset. */
            val DEFAULT = LogLimits(
                memoryEntries = 1000,
                maxFileSizeBytes = 4L * 1024 * 1024,
                maxTotalDirBytes = 0L,  // off by default — opt-in
                maxAgeDays = 7,
            )
        }
    }

    /** Read currently active limits. Not the persisted-but-unapplied values
     *  (those are identical — we apply on every set). */
    fun getLogLimits(): LogLimits {
        val mem = memorySink?.maxEntries ?: LogLimits.DEFAULT.memoryEntries
        val file = fileSink
        return LogLimits(
            memoryEntries = mem,
            maxFileSizeBytes = file?.maxFileSize ?: LogLimits.DEFAULT.maxFileSizeBytes,
            maxTotalDirBytes = file?.maxTotalBytes ?: LogLimits.DEFAULT.maxTotalDirBytes,
            maxAgeDays = file?.maxAgeDays ?: LogLimits.DEFAULT.maxAgeDays,
        )
    }

    /** Apply + persist new limits. Each value is clamped to a sane floor /
     *  ceiling: the user can't accidentally set memoryEntries=0 (silent
     *  log loss) or maxFileSizeBytes=1B (rotate-storm). Live sinks pick up
     *  the new values immediately; the rate-limited enforcer will use them
     *  on its next gate opening, and a synchronous full enforce can be
     *  triggered by [cleanupNow] if the user wants instant effect. */
    fun setLogLimits(limits: LogLimits) {
        val clamped = LogLimits(
            memoryEntries = limits.memoryEntries.coerceIn(50, 5000),
            maxFileSizeBytes = limits.maxFileSizeBytes.coerceIn(256L * 1024, 32L * 1024 * 1024),
            maxTotalDirBytes = limits.maxTotalDirBytes.coerceAtLeast(0L)
                .let { if (it == 0L) 0L else it.coerceIn(5L * 1024 * 1024, 2L * 1024 * 1024 * 1024) },
            maxAgeDays = limits.maxAgeDays.coerceIn(1, 365),
        )
        memorySink?.maxEntries = clamped.memoryEntries
        fileSink?.let { f ->
            f.maxFileSize = clamped.maxFileSizeBytes
            f.maxTotalBytes = clamped.maxTotalDirBytes
            f.maxAgeDays = clamped.maxAgeDays
        }
        logPrefs?.edit()?.apply {
            putInt(KEY_MEM_ENTRIES, clamped.memoryEntries)
            putLong(KEY_FILE_SIZE, clamped.maxFileSizeBytes)
            putLong(KEY_TOTAL_DIR, clamped.maxTotalDirBytes)
            putInt(KEY_MAX_DAYS, clamped.maxAgeDays)
        }?.apply()
    }

    /** Run an immediate cleanup pass against the current live limits. The
     *  rate-limited periodic enforcer would do the same thing eventually
     *  (within 30 min or 200 writes); this is the user-facing "立即清理"
     *  button that wants instant feedback. Safe to call on the main thread —
     *  the underlying ops are listFiles + a handful of File.delete syscalls
     *  on a directory with at most a few dozen files. Returns a report
     *  the UI can SnackBar back to the user. */
    fun cleanupNow(): CleanupReport {
        val sink = fileSink ?: return CleanupReport()
        return sink.enforceLimits(
            maxAgeDays = sink.maxAgeDays,
            maxLogFiles = sink.maxLogFiles,
            maxCrashFiles = sink.maxCrashFiles,
            maxTotalBytes = sink.maxTotalBytes,
        )
    }

    /** Wipe all log files + crash files + in-memory buffer. Aggressive —
     *  surface only behind a confirm dialog. Used by "全删（含崩溃文件）".
     *  Returns the same shape as [cleanupNow] for UI symmetry. */
    fun clearAll(): CleanupReport {
        var deletedLogs = 0
        var deletedCrashes = 0
        var freed = 0L
        val openName = fileSink?.todayFile()?.name
        getLogDir()?.listFiles()?.forEach { f ->
            // Skip the currently-open day file — clearing it from under
            // the writer would corrupt an in-flight appendText.
            if (f.name == openName) return@forEach
            val sz = f.length()
            if (f.delete()) {
                freed += sz
                when {
                    f.name.startsWith("log_") -> deletedLogs++
                    f.name.startsWith("crash_") -> deletedCrashes++
                }
            }
        }
        memorySink?.clear()
        return CleanupReport(deletedLogs, deletedCrashes, freed)
    }

    fun coroutineExceptionHandler(tag: String = "Coroutine"): CoroutineExceptionHandler =
        CoroutineExceptionHandler { context, throwable ->
            error(tag, "Unhandled coroutine exception in $context", throwable)
        }

    fun logThreadException(tag: String, thread: Thread, throwable: Throwable) {
        error(tag, "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
    }

    // ── Core dispatch ──

    private fun dispatch(level: LogLevel, tag: String, msg: String, t: Throwable? = null) {
        val record = LogRecord(id = nextId(),
            time = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg,
            throwable = t?.let { throwableToString(it) },
        )
        for (sink in sinks) {
            if (level.priority >= sink.minLevel.priority) {
                try {
                    sink.write(record)
                } catch (sinkError: Throwable) {
                    Log.e(TAG, "Log sink '${sink.name}' failed", sinkError)
                }
            }
        }
    }
    // ── Crash Handler ──

    private fun installCrashHandler(logDir: File) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crash = buildString {
                    appendLine("=== MoRealm Crash Report ===")
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    appendLine("Time: ${fmt.format(Date())}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine("--- Device Info ---")
                    append(deviceInfo)
                    appendLine()
                    appendLine("--- Exception ---")
                    appendLine(throwableToString(throwable))
                    var cause = throwable.cause
                    while (cause != null) {
                        appendLine("--- Caused by ---")
                        appendLine(throwableToString(cause))
                        cause = cause.cause
                    }
                    appendLine()
                    appendLine("--- Recent Logs (last 30) ---")
                    getRecentLogs(30).forEach { appendLine(it.format()) }
                }
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                File(logDir, "crash_$ts.txt").writeText(crash)
                // Synchronous write to daily log + memory (so UI shows it if app survives)
                val record = LogRecord(id = nextId(),
                    System.currentTimeMillis(), LogLevel.FATAL, "CRASH",
                    "Uncaught exception on ${thread.name}: ${throwable.message}",
                    throwableToString(throwable),
                )
                memorySink?.writeForce(record)
                fileSink?.writeImmediate(record)
            } catch (_: Exception) {}
            finally { default?.uncaughtException(thread, throwable) }
        }
    }

    // ── ANR Watchdog ──
    //
    // 同一份 ANR 在持续阻塞期间通常会被反复检测到（监控线程每 5s 唤醒一次）。
    // 朴素实现下每次都把整份 stack 打到日志里，logcat / 文件 sink 会被同样的
    // 几十行重复堆栈淹没——历史 err.txt 里 9 条堆栈完全一致就是这个症状。
    //
    // 去重策略：用 stack 文本 hash 作为指纹；指纹相同且距上次记录不到
    // [ANR_DEDUP_WINDOW_MS] 时只递增计数器，不写完整堆栈。窗口结束 / 指纹
    // 变化时把累计的抑制次数补一行简短摘要再开始下一段。

    /** 距上次相同 stack 的 ANR 在此时间内仅累计计数，不重复落盘。 */
    private const val ANR_DEDUP_WINDOW_MS = 30_000L

    private fun installAnrWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            var tickDone = true
            val tick = Runnable { tickDone = true }

            // 去重状态——只在监控线程内访问，无需同步。
            var lastStackHash = 0
            var lastReportAtMs = 0L
            var dedupCount = 0

            while (true) {
                try {
                    tickDone = false
                    mainHandler.post(tick)
                    Thread.sleep(5000)
                    if (!tickDone) {
                        Thread.sleep(3000)
                        if (!tickDone) {
                            val stack = Looper.getMainLooper().thread.stackTrace
                            val idle = stack.any { it.methodName == "nativePollOnce" || it.methodName == "parkNanos" }
                            if (!idle) {
                                val trace = stack.joinToString("\n") { "  at $it" }
                                val now = System.currentTimeMillis()
                                val hash = trace.hashCode()
                                val withinWindow = (now - lastReportAtMs) < ANR_DEDUP_WINDOW_MS
                                val isDup = hash == lastStackHash && withinWindow
                                if (isDup) {
                                    dedupCount++
                                } else {
                                    // 切换到新 stack 或窗口已过 —— 先把之前累计的抑制
                                    // 计数补一条短摘要（如有），再写新堆栈。
                                    if (dedupCount > 0) {
                                        val summary = LogRecord(
                                            id = nextId(),
                                            now, LogLevel.WARN, "ANR",
                                            "[suppressed $dedupCount duplicate ANR(s) with same stack]",
                                        )
                                        memorySink?.writeForce(summary)
                                        fileSink?.writeImmediate(summary)
                                        for (sink in sinks) {
                                            if (sink !== memorySink && sink !== fileSink &&
                                                LogLevel.WARN.priority >= sink.minLevel.priority) {
                                                sink.write(summary)
                                            }
                                        }
                                        dedupCount = 0
                                    }
                                    val record = LogRecord(
                                        id = nextId(),
                                        now, LogLevel.ERROR, "ANR",
                                        "Main thread blocked >8s\n$trace",
                                    )
                                    // Write to memory directly (ANR means main thread is stuck, dispatch may not work)
                                    memorySink?.writeForce(record)
                                    fileSink?.writeImmediate(record)
                                    for (sink in sinks) {
                                        if (sink !== memorySink && sink !== fileSink && LogLevel.ERROR.priority >= sink.minLevel.priority) {
                                            sink.write(record)
                                        }
                                    }
                                    lastStackHash = hash
                                    lastReportAtMs = now
                                }
                            }
                        }
                    }
                } catch (_: InterruptedException) { break }
            }
        }, "MoRealm-ANR-Watchdog").apply { isDaemon = true; start() }
    }

    // ── Lifecycle Monitor ──

    private fun installLifecycleMonitor(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private val starts = HashMap<String, Long>()
            override fun onActivityCreated(a: Activity, s: Bundle?) { starts[a.javaClass.simpleName] = System.currentTimeMillis() }
            override fun onActivityResumed(a: Activity) {
                val name = a.javaClass.simpleName
                val t = starts.remove(name) ?: return
                val ms = System.currentTimeMillis() - t
                if (ms > 2000) dispatch(LogLevel.WARN, "Lifecycle", "$name onCreate→onResume: ${ms}ms")
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { starts.remove(a.javaClass.simpleName) }
        })
    }

    // ── Util ──

    /**
     * 设备 / 系统 / 应用 / 显示 等元信息，单行 key:value 平铺，不带分节标题——
     * 调用方（崩溃报告 / 日志 TXT 导出）会按需自己加 "--- Device ---" 之类
     * 的二级标题，避免内嵌一层造成嵌套混乱。
     *
     * 含分辨率 / 密度：用户报 bug 时分辨率 + 密度直接关系到 reader 排版、
     * 翻页阴影尺寸、TopAppBar 偏移等问题，必须带上。
     */
    private fun collectDeviceInfo(context: Context): String = buildString {
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Hardware: ${Build.HARDWARE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Locale: ${Locale.getDefault()}")
        try {
            val dm = context.resources.displayMetrics
            appendLine("Resolution: ${dm.widthPixels}x${dm.heightPixels} px")
            appendLine("Density: ${dm.density} (${dm.densityDpi} dpi)")
            appendLine("ScaledDensity: ${dm.scaledDensity}")
        } catch (_: Exception) {}
        try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("App: ${p.versionName} (${p.longVersionCode})")
            appendLine("Package: ${context.packageName}")
        } catch (_: Exception) {}
        appendLine("Heap: ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")
        appendLine("Cores: ${Runtime.getRuntime().availableProcessors()}")
    }

    /**
     * 暴露 [deviceInfo] 给 UI 层（日志 TXT 导出会写到文件头），不希望调用方
     * 直接访问私有字段；这里给个稳定的只读入口。在 [init] 之前调用会拿到空
     * 串——理论上不可能发生，因为 UI 路径上 init 必然先于 Composable 渲染。
     */
    fun getDeviceInfo(): String = deviceInfo

    private fun throwableToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
