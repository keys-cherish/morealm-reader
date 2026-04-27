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

/** In-memory ring buffer — synchronous, for UI log viewer */
class MemorySink(
    override var minLevel: LogLevel = LogLevel.DEBUG,
    private val maxEntries: Int = 300,
) : LogSink {
    override val name = "memory"
    private val buffer = ConcurrentLinkedDeque<LogRecord>()
    private val _flow = MutableStateFlow<List<LogRecord>>(emptyList())
    val records: StateFlow<List<LogRecord>> = _flow.asStateFlow()

    override fun write(record: LogRecord) {
        buffer.addLast(record)
        while (buffer.size > maxEntries) buffer.pollFirst()
        _flow.value = buffer.toList()
    }

    /** Force-write bypassing minLevel check (for crash/ANR records) */
    fun writeForce(record: LogRecord) {
        buffer.addLast(record)
        while (buffer.size > maxEntries) buffer.pollFirst()
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
 */
class RollingFileSink(
    private val logDir: File,
    override var minLevel: LogLevel = LogLevel.INFO,
    private val maxFileSize: Long = 2 * 1024 * 1024L,
    private val maxFiles: Int = 10,
    private val async: Boolean = true,
) : LogSink {

    override val name = "file"
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

    fun cleanOld(maxDays: Int) {
        val cutoff = System.currentTimeMillis() - maxDays * 86_400_000L
        logDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
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
    private const val MAX_LOG_DAYS = 7
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
    fun nextId(): Long = idCounter.incrementAndGet()

    private val sinks = mutableListOf<LogSink>()
    private var memorySink: MemorySink? = null
    private var fileSink: RollingFileSink? = null
    private var deviceInfo: String = ""
    private var initialized = false

    /** Reactive log records for Compose UI */
    val logs: StateFlow<List<LogRecord>>
        get() = memorySink?.records ?: MutableStateFlow(emptyList())

    // ── Initialization ──

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val logDir = (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "logs").apply { mkdirs() } }

        // Register built-in sinks — all use DEBUG as minimum to keep app UI and file in sync
        val logcat = LogcatSink(LogLevel.DEBUG)
        val memory = MemorySink(LogLevel.DEBUG, 500)
        val file = RollingFileSink(logDir, LogLevel.WARN, maxFileSize = 4 * 1024 * 1024L, maxFiles = 5)

        memorySink = memory
        fileSink = file
        sinks += listOf(logcat, memory, file)

        deviceInfo = collectDeviceInfo(context)
        file.cleanOld(MAX_LOG_DAYS)
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

    /** Enable/disable detailed file logging (recordLog toggle) */
    fun setRecordLog(enabled: Boolean) {
        fileSink?.minLevel = if (enabled) LogLevel.DEBUG else LogLevel.WARN
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

    private fun installAnrWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            var tickDone = true
            val tick = Runnable { tickDone = true }
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
                                val record = LogRecord(id = nextId(),
                                    System.currentTimeMillis(), LogLevel.ERROR, "ANR",
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

    private fun collectDeviceInfo(context: Context): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("App: ${p.versionName} (${p.longVersionCode})")
        } catch (_: Exception) {}
        appendLine("Heap: ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")
    }

    private fun throwableToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
