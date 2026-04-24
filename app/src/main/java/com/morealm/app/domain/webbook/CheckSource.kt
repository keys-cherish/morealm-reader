package com.morealm.app.domain.webbook

import com.morealm.app.domain.entity.BookSource
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * 书源校验 — 批量检测书源是否可用
 *
 * 校验流程（同Legado CheckSource逻辑）：
 * 1. 用固定关键词搜索，检查是否返回结果
 * 2. 取第一个结果获取详情页
 * 3. 获取目录列表
 * 4. 获取第一章正文
 * 每步失败即标记该步骤错误，全部通过则标记为可用
 */
object CheckSource {

    private const val TEST_KEYWORD = "我的"
    private const val TIMEOUT_MS = 30000L

    data class CheckResult(
        val sourceUrl: String,
        val sourceName: String,
        val searchOk: Boolean = false,
        val bookInfoOk: Boolean = false,
        val tocOk: Boolean = false,
        val contentOk: Boolean = false,
        val error: String? = null,
    ) {
        val isValid: Boolean get() = searchOk
        val score: Int get() = listOf(searchOk, bookInfoOk, tocOk, contentOk).count { it }
    }

    /**
     * 校验单个书源
     */
    suspend fun check(source: BookSource): CheckResult = withContext(Dispatchers.IO) {
        val result = CheckResult(source.bookSourceUrl, source.bookSourceName)
        try {
            // Step 1: 搜索
            coroutineContext.ensureActive()
            val searchBooks = withTimeout(TIMEOUT_MS) {
                WebBook.searchBookAwait(source, TEST_KEYWORD)
            }
            if (searchBooks.isEmpty()) {
                return@withContext result.copy(error = "搜索无结果")
            }
            val step1 = result.copy(searchOk = true)

            // Step 2: 详情
            coroutineContext.ensureActive()
            val book = searchBooks.first()
            try {
                withTimeout(TIMEOUT_MS) {
                    WebBook.getBookInfoAwait(source, book)
                }
            } catch (e: Exception) {
                return@withContext step1.copy(error = "详情失败: ${e.message?.take(50)}")
            }
            val step2 = step1.copy(bookInfoOk = true)

            // Step 3: 目录
            coroutineContext.ensureActive()
            val tocUrl = book.tocUrl.ifEmpty { book.bookUrl }
            val chapters = try {
                withTimeout(TIMEOUT_MS) {
                    WebBook.getChapterListAwait(source, book.bookUrl, tocUrl)
                }
            } catch (e: Exception) {
                return@withContext step2.copy(error = "目录失败: ${e.message?.take(50)}")
            }
            if (chapters.isEmpty()) {
                return@withContext step2.copy(error = "目录为空")
            }
            val step3 = step2.copy(tocOk = true)

            // Step 4: 正文
            coroutineContext.ensureActive()
            val content = try {
                withTimeout(TIMEOUT_MS) {
                    WebBook.getContentAwait(
                        source,
                        chapters.first().url,
                        chapters.getOrNull(1)?.url,
                    )
                }
            } catch (e: Exception) {
                return@withContext step3.copy(error = "正文失败: ${e.message?.take(50)}")
            }
            if (content.isBlank()) {
                return@withContext step3.copy(error = "正文为空")
            }

            step3.copy(contentOk = true)
        } catch (e: Exception) {
            result.copy(error = e.message?.take(80) ?: "未知错误")
        }
    }

    /**
     * 批量校验书源
     * @param concurrency 并发数
     * @param onResult 每完成一个书源的回调
     */
    suspend fun checkAll(
        sources: List<BookSource>,
        concurrency: Int = 4,
        onResult: (index: Int, result: CheckResult) -> Unit = { _, _ -> },
    ): List<CheckResult> = withContext(Dispatchers.IO) {
        val results = Array<CheckResult?>(sources.size) { null }
        val semaphore = Semaphore(concurrency)

        val jobs = sources.mapIndexed { index, source ->
            launch {
                semaphore.acquire()
                try {
                    coroutineContext.ensureActive()
                    val result = check(source)
                    results[index] = result
                    onResult(index, result)
                    AppLog.debug("CheckSource", "${source.bookSourceName}: score=${result.score}/4 ${result.error ?: "OK"}")
                } finally {
                    semaphore.release()
                }
            }
        }
        jobs.forEach { it.join() }

        results.mapIndexed { i, r -> r ?: CheckResult(sources[i].bookSourceUrl, sources[i].bookSourceName, error = "未执行") }
    }
}
