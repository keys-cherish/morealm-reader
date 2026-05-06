package com.morealm.app.presentation.shelf

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.WebBook
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ShelfOrganizeController(
    private val bookRepo: BookRepository,
    private val groupRepo: BookGroupRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
    private val sourceRepo: SourceRepository,
    private val scope: CoroutineScope,
) {
    private val _isOrganizing = MutableStateFlow(false)
    val isOrganizing: StateFlow<Boolean> = _isOrganizing.asStateFlow()

    private val _organizeReport = MutableStateFlow<String?>(null)
    val organizeReport: StateFlow<String?> = _organizeReport.asStateFlow()
    fun consumeOrganizeReport() { _organizeReport.value = null }

    fun reclassifyUngroupedBooks() {
        scope.launch(Dispatchers.IO) {
            val groups = groupRepo.getAllGroupsSync()
            var moved = 0
            bookRepo.getAllBooksSync().filter { it.folderId == null }.forEach { book ->
                val target = autoGroupClassifier.matchGroup(book, groups)?.id ?: return@forEach
                bookRepo.update(book.copy(folderId = target))
                moved++
            }
            AppLog.info("Shelf", "Auto grouped $moved books")
        }
    }

    fun organizeShelf() {
        if (_isOrganizing.value) return
        scope.launch(Dispatchers.IO) {
            _isOrganizing.value = true
            try {
                val before = groupRepo.getAllGroupsSync().count { it.auto }

                // Stage A: metadata prefetch
                val sparse = bookRepo.getAllBooksSync().filter { b ->
                    b.format == BookFormat.WEB &&
                        !b.groupLocked &&
                        b.kind.isNullOrBlank() &&
                        b.category.isNullOrBlank() &&
                        b.description.isNullOrBlank() &&
                        !b.sourceUrl.isNullOrBlank()
                }
                val refreshed = if (sparse.isNotEmpty()) prefetchWebMetadata(sparse) else 0

                // Stage B: classify + auto-create groups
                var touched = 0
                for (book in bookRepo.getAllBooksSync()) {
                    if (book.groupLocked) continue
                    if (book.folderId != null && book.tagsAssignedBy == "MANUAL") continue
                    val newId = autoGroupClassifier.classify(book)
                    if (newId != book.folderId) {
                        bookRepo.update(book.copy(folderId = newId))
                        touched++
                    }
                }

                // Stage C: orphan rescue
                val orphans = bookRepo.getAllBooksSync().filter { b ->
                    b.format == BookFormat.WEB &&
                        b.folderId == null &&
                        !b.groupLocked &&
                        b.tagsAssignedBy != "MANUAL"
                }
                var rescued = 0
                if (orphans.isNotEmpty()) {
                    val unId = "auto:unrecognized"
                    if (groupRepo.getById(unId) == null) {
                        val nextOrder = (groupRepo.getAllGroupsSync().maxOfOrNull { it.sortOrder } ?: 0) + 1
                        groupRepo.insert(
                            BookGroup(
                                id = unId,
                                name = "无法识别",
                                emoji = "❓",
                                sortOrder = nextOrder,
                                auto = true,
                            )
                        )
                    }
                    orphans.forEach {
                        bookRepo.update(it.copy(folderId = unId))
                        rescued++
                    }
                }

                val after = groupRepo.getAllGroupsSync().count { it.auto }
                val newFolders = (after - before).coerceAtLeast(0)
                val parts = buildList {
                    if (refreshed > 0) add("补全 $refreshed 本")
                    if (touched > 0) add("移动 $touched 本")
                    if (rescued > 0) add("收纳 $rescued 本到「无法识别」")
                    if (newFolders > 0) add("新建 $newFolders 个文件夹")
                }
                _organizeReport.value = if (parts.isEmpty()) "书架已是最新状态"
                else "整理完成：" + parts.joinToString("，")
                AppLog.info(
                    "Shelf",
                    "Organize: refreshed=$refreshed touched=$touched rescued=$rescued newFolders=$newFolders",
                )
            } catch (e: Exception) {
                AppLog.error("Shelf", "Organize failed: ${e.message}", e)
                _organizeReport.value = "整理失败：${e.message?.take(60)}"
            } finally {
                _isOrganizing.value = false
            }
        }
    }

    private suspend fun prefetchWebMetadata(books: List<Book>): Int {
        var refreshed = 0
        coroutineScope {
            val semaphore = Semaphore(4)
            books.map { book ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val srcUrl = book.sourceUrl ?: return@withPermit false
                            val source = sourceRepo.getByUrl(srcUrl) ?: return@withPermit false
                            val sb = SearchBook(
                                bookUrl = book.bookUrl,
                                origin = book.origin,
                                originName = book.originName,
                                name = book.title,
                                author = book.author,
                                kind = book.kind,
                                coverUrl = book.coverUrl,
                                intro = book.description,
                                tocUrl = book.tocUrl.orEmpty(),
                            )
                            val updated = WebBook.getBookInfoAwait(source, sb)
                            val merged = book.copy(
                                kind = updated.kind?.takeIf { it.isNotBlank() } ?: book.kind,
                                description = updated.intro?.takeIf { it.isNotBlank() } ?: book.description,
                                coverUrl = updated.coverUrl?.takeIf { it.isNotBlank() } ?: book.coverUrl,
                                wordCount = updated.wordCount?.takeIf { it.isNotBlank() } ?: book.wordCount,
                            )
                            if (merged != book) {
                                bookRepo.update(merged)
                                true
                            } else false
                        } catch (e: Exception) {
                            AppLog.warn(
                                "Shelf",
                                "Metadata prefetch 失败 ${book.title}: ${e.message?.take(40)}",
                            )
                            false
                        }
                    }
                }
            }.awaitAll().forEach { if (it) refreshed++ }
        }
        return refreshed
    }
}
