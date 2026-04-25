package com.morealm.app.domain.repository

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoGroupClassifier @Inject constructor(
    private val groupRepo: BookGroupRepository,
) {
    suspend fun classify(book: Book): String? {
        if (book.folderId != null) return book.folderId
        return matchGroup(book, groupRepo.getAllGroupsSync())?.id
    }

    fun matchGroup(book: Book, groups: List<BookGroup>): BookGroup? {
        val haystack = buildSearchText(book)
        if (haystack.isBlank()) return null
        return groups
            .filter { it.autoKeywords.isNotBlank() }
            .sortedWith(compareByDescending<BookGroup> { keywordList(it.autoKeywords).size }.thenBy { it.sortOrder })
            .firstOrNull { group ->
                keywordList(group.autoKeywords).any { keyword ->
                    haystack.contains(keyword.lowercase())
                }
            }
    }

    private fun buildSearchText(book: Book): String = listOfNotNull(
        book.title,
        book.author,
        book.description,
        book.category,
        book.kind,
        book.customTag,
        book.wordCount,
        book.localPath,
        book.originName,
    ).joinToString("\n") { it.lowercase() }

    private fun keywordList(value: String): List<String> = value
        .split(',', '，', ';', '；', '\n', '|', '/', '、')
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
}
