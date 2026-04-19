package com.morealm.app.presentation.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.BookSourceDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.parser.EpubMetadataWriter
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.core.log.AppLog
import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepo: BookRepository,
    private val sourceDao: BookSourceDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _availableSources = MutableStateFlow<List<BookSource>>(emptyList())
    val availableSources: StateFlow<List<BookSource>> = _availableSources.asStateFlow()

    private val _showSourcePicker = MutableStateFlow(false)
    val showSourcePicker: StateFlow<Boolean> = _showSourcePicker.asStateFlow()

    init {
        viewModelScope.launch {
            _book.value = bookRepo.getById(bookId)
            _availableSources.value = sourceDao.getEnabledSourcesList()
        }
    }

    fun showSourcePicker() { _showSourcePicker.value = true }
    fun hideSourcePicker() { _showSourcePicker.value = false }

    fun switchSource(source: BookSource) {
        viewModelScope.launch {
            val current = _book.value ?: return@launch
            val updated = current.copy(
                sourceId = source.id,
                originName = source.name,
            )
            bookRepo.update(updated)
            _book.value = updated
            _showSourcePicker.value = false
            AppLog.info("Detail", "Switched source to: ${source.name}")
        }
    }

    fun deleteBook() {
        viewModelScope.launch {
            bookRepo.deleteById(bookId)
            AppLog.info("Detail", "Deleted book: $bookId")
        }
    }

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Update book metadata. For EPUB books, also writes changes back to the EPUB file
     * so that re-imports will reflect the edits.
     */
    fun updateMetadata(
        title: String,
        author: String,
        description: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            val current = _book.value ?: return@launch
            val updated = current.copy(
                title = title.ifBlank { current.title },
                author = author,
                description = description.ifBlank { null },
            )
            bookRepo.update(updated)
            _book.value = updated

            // Write back to EPUB file for persistence across re-imports
            if (current.format == BookFormat.EPUB && current.localPath != null) {
                try {
                    val uri = Uri.parse(current.localPath)
                    val epubUpdate = EpubMetadataWriter.MetadataUpdate(
                        title = title.ifBlank { null },
                        author = author.ifBlank { null },
                        description = description.ifBlank { null },
                    )
                    val success = EpubMetadataWriter.updateMetadata(context, uri, epubUpdate)
                    if (success) {
                        AppLog.info("Detail", "EPUB metadata written back to file")
                    }
                } catch (e: Exception) {
                    AppLog.error("Detail", "Failed to write EPUB metadata: ${e.message}")
                }
            }
            _saving.value = false
        }
    }
}
