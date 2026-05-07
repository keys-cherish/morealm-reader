package com.morealm.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.CookieDao
import com.morealm.app.domain.db.TxtTocRuleDao
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.sync.WebDavBackupRunner
import com.morealm.app.domain.sync.WebDavBookProgressSync
import com.morealm.app.domain.webbook.CacheBook
import com.morealm.app.domain.webbook.ChapterMemoryCache
import com.morealm.app.service.TtsErrorPresenter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MoRealmApp : Application(), ImageLoaderFactory {

    @Inject lateinit var cacheDao: CacheDao
    @Inject lateinit var cookieDao: CookieDao
    @Inject lateinit var txtTocRuleDao: TxtTocRuleDao
    @Inject lateinit var progressSync: WebDavBookProgressSync
    @Inject lateinit var backupRunner: WebDavBackupRunner
    @Inject lateinit var database: com.morealm.app.domain.db.AppDatabase
    @Inject lateinit var snapshotManager: com.morealm.app.domain.db.snapshot.SnapshotManager

    /**
     * App-scoped supervisor for fire-and-forget background work that
     * shouldn't be cancelled when an Activity goes away (e.g. WebDav
     * progress sync on cold start, future auto-backup scheduler).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Surfaces TTS init / playback failures as Toasts. Subscribes for the lifetime
     * of [appScope] so errors raised while the user is on a non-Reader screen
     * (e.g. settings, launcher) still reach the user.
     */
    private val ttsErrorPresenter by lazy { TtsErrorPresenter(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        CacheManager.init(cacheDao)
        CookieStore.init(cookieDao)
        CacheBook.init(cacheDao)
        // 章节正文 L1 内存缓存（50 MB LRU）注册 onTrimMemory hook，
        // 让低端机在系统内存吃紧时主动归还。CacheBook 读写时透传到本类。
        ChapterMemoryCache.register(this)
        LocalBookParser.txtTocRuleDao = txtTocRuleDao

        // P1-B: pull every other-device book progress once at app start.
        // The sync class itself short-circuits to a no-op when WebDav is
        // unconfigured or syncBookProgress is disabled, so this is cheap
        // for users who haven't opted in.
        appScope.launch {
            runCatching { progressSync.downloadAll() }
                .onFailure { AppLog.warn("App", "Initial progress sync failed: ${it.message}") }
        }

        // P1-E: opportunistic auto-backup. The runner enforces the 24h
        // window itself; we just ask once per cold start. No-op when the
        // user hasn't enabled `autoBackup`.
        appScope.launch {
            runCatching { backupRunner.runIfDue() }
                .onFailure { AppLog.warn("App", "Auto-backup check failed: ${it.message}") }
        }

        // 每日首次启动跑一次全量 JSON 快照（filesDir/db_snapshot/snapshot.json）。
        // SnapshotManager 自己用文件 mtime 判断是否到期；同一天内多次冷启动只跑一次。
        // 与 backupRunner（WebDav 24h auto-backup）独立——后者是用户可选的远端备份，
        // snapshot 是本地强制双保险，无 opt-in。
        //
        // 这里 fire-and-forget；database.openHelper.writableDatabase 第一次访问会触发
        // Room 真正打开 + migration —— 此调用前的 backup/preserve 已经跑完了。
        appScope.launch {
            runCatching {
                snapshotManager.runDailySnapshotIfDue(database.openHelper.writableDatabase)
            }.onFailure { AppLog.warn("App", "Daily snapshot failed: ${it.message}") }
        }

        AppLog.info("App", "MoRealm started")

        // Wire up TTS error → Toast bridge. Must happen after appScope is constructed
        // and before any TTS-using surface (Reader / ListenScreen) is opened, so errors
        // raised on first launch (e.g. no engine bound) are not missed.
        ttsErrorPresenter.start(appScope)
    }

    companion object {
        lateinit var instance: MoRealmApp
            private set
    }

    /**
     * 全局 Coil [ImageLoader] 配置。Coil 在首次使用时通过 [ImageLoaderFactory]
     * 拿到该实例并缓存为单例（`Coil.imageLoader(context)`）。
     *
     * 之前项目里没有自定义实现 —— 所有 `coil.ImageLoader(ctx)` 调用走 Coil 默认值，
     * 每个使用点 new 一份意味着：
     *   1. 内存缓存各自占（虽然 Coil2 默认值是 `maxMemory * 0.25` 即百兆级，但
     *      多个独立 ImageLoader 实例不会共享，浪费）；
     *   2. 磁盘缓存默认禁用（`Builder.diskCache` 不调时 = no disk cache），导致
     *      封面冷启动每次走网络重下，弱网用户首屏白图；
     *   3. crossfade 等体验配置需要在每个使用点重写，散乱。
     *
     * 配置选择：
     *   - memoryCache 25% maxMemory：与 Coil2 默认对齐，但保证只有一个实例占用
     *     这块内存（与 [com.morealm.app.domain.render.ImageCache] 分工：本类管
     *     coil compose 体系的封面/缩略图，ImageCache 管 Canvas 直接绘制路径上的
     *     Bitmap，两层互不干扰）
     *   - diskCache 200 MB on `cacheDir/image_cache`：足够覆盖几千封面 +
     *     阅读器嵌入图。cacheDir 子目录约定与 EdgeTtsCache / 章节缓存一致，
     *     系统存储紧张时由 OS 自动回收。
     *   - crossfade(true)：所有 AsyncImage / load() 调用统一淡入。
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
