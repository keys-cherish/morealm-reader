package com.morealm.app.presentation.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.domain.sync.LegadoImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Legado 一键搬家页的 ViewModel —— 封装"选 zip → 解析 → 预览 → 导入"四步流程。
 *
 * 故意**不**复用 [ProfileViewModel] 的备份/恢复管线：
 *  - 加密、分类勾选、SAF 多 mime、状态总线（BackupStatusBus）等都是 MoRealm 自家备份特有的，
 *    Legado 流程要简单得多（不加密、固定全量、入口独立），叠加进去会让 ProfileViewModel
 *    更臃肿
 *  - 错误隔离：Legado 解析失败不应该污染 MoRealm 自家备份页的状态
 *
 * 状态机：
 *  - **idle**（pendingUri = null, preview = null）：等用户点「选择 Legado 备份」
 *  - **previewing**（loading = true）：解 zip 中
 *  - **previewed**（preview 非 null）：显示统计，等用户点「开始导入」
 *  - **importing**（importing = true）：写库中
 *  - **done**（result 非 null）：展示导入结果，可继续选下一个 zip
 *  - **error**（errorMessage 非 null）：解析失败 / 导入抛错；用户重选清错
 */
@HiltViewModel
class LegadoImportViewModel @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _pendingUri = MutableStateFlow<Uri?>(null)
    val pendingUri: StateFlow<Uri?> = _pendingUri.asStateFlow()

    /** zip 解析后的预览（数量 + 冲突）；null = 还没选 / 选了正在解。 */
    private val _preview = MutableStateFlow<LegadoImporter.Preview?>(null)
    val preview: StateFlow<LegadoImporter.Preview?> = _preview.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 写完后显示的最终结果；null = 未导入 / 已 dismiss。 */
    private val _result = MutableStateFlow<LegadoImporter.ImportResult?>(null)
    val result: StateFlow<LegadoImporter.ImportResult?> = _result.asStateFlow()

    /** 解析或导入失败的错误信息；null = 没错。 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 冲突策略 —— UI 用 Switch 切。默认 SKIP：保守不动用户已有数据。
     * 改成 OVERWRITE 等价于"我就要 Legado 这边全套"，慎用。
     */
    private val _conflictStrategy = MutableStateFlow(LegadoImporter.ConflictStrategy.SKIP)
    val conflictStrategy: StateFlow<LegadoImporter.ConflictStrategy> = _conflictStrategy.asStateFlow()

    /** 缓存 zip bytes，免得 import 时再读一次 SAF（用户中途撤销权限会失败）。 */
    private var cachedZipBytes: ByteArray? = null

    fun setConflictStrategy(strategy: LegadoImporter.ConflictStrategy) {
        _conflictStrategy.value = strategy
    }

    /**
     * 用户在 SAF 选完 zip 后调。读字节 + 立刻解析预览。
     * 失败（不是有效 zip / 不是 Legado 备份）会写到 errorMessage，UI 弹错卡片。
     */
    fun onZipPicked(uri: Uri) {
        _pendingUri.value = uri
        _preview.value = null
        _result.value = null
        _errorMessage.value = null
        _loading.value = true

        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件（权限可能已撤销）")
                }
                cachedZipBytes = bytes
                val pv = LegadoImporter.previewZip(bytes, db)
                if (pv.bookCount + pv.bookSourceCount + pv.bookmarkCount + pv.bookGroupCount +
                    pv.replaceRuleCount + pv.httpTtsCount == 0
                ) {
                    _errorMessage.value = "未在 zip 中识别到任何 Legado 数据，请确认这是 Legado 备份文件"
                    cachedZipBytes = null
                } else {
                    _preview.value = pv
                }
            } catch (e: Exception) {
                AppLog.error("LegadoImport", "preview failed", e)
                _errorMessage.value = "解析失败：${e.message ?: e.javaClass.simpleName}"
                cachedZipBytes = null
            } finally {
                _loading.value = false
            }
        }
    }

    /** 用户点「开始导入」。读取已缓存的 zip bytes，写库。 */
    fun runImport() {
        val bytes = cachedZipBytes
        if (bytes == null) {
            _errorMessage.value = "状态错误：请重新选择 Legado 备份文件"
            return
        }
        _importing.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                val r = LegadoImporter.import(
                    zipBytes = bytes,
                    db = db,
                    opts = LegadoImporter.ImportOptions(
                        conflictStrategy = _conflictStrategy.value,
                    ),
                )
                _result.value = r
                AppLog.info("LegadoImport", "Done: ${r.summarize()}")
            } catch (e: Exception) {
                AppLog.error("LegadoImport", "import failed", e)
                _errorMessage.value = "导入失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _importing.value = false
            }
        }
    }

    /** 用户点「再来一个」/「关闭」时清状态，回到 idle。 */
    fun reset() {
        _pendingUri.value = null
        _preview.value = null
        _result.value = null
        _errorMessage.value = null
        cachedZipBytes = null
    }
}
