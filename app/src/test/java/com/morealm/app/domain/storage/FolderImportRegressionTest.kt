package com.morealm.app.domain.storage

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
class FolderImportRegressionTest {
    @Test fun `Android 10 without legacy storage uses its granted document provider`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(StorageAccessHelper.hasAllFilesAccess(context))
        val provider = DirectoryProvider()
        ShadowContentResolver.registerProviderInternal("test.documents", provider)
        val root = DocumentsContract.buildTreeDocumentUri("test.documents", "root")
        val folders = FastFileScanner.listImmediateChildren(context, root)
        val files = folders.flatMap { FastFileScanner.scanFolder(context, it.uri, { name -> name.endsWith(".epub") }) }
        assertEquals(listOf("a.epub", "b.epub"), files.map { it.name })
        assertEquals(listOf("root", "a", "b"), provider.queries)
    }

    @Test fun `different grants for the same document have one import identity`() {
        val root = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Books")
        val child = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Books/A")
        val document = "primary:Books/A/story.epub"
        val first = DocumentsContract.buildDocumentUriUsingTree(root, document)
        val second = DocumentsContract.buildDocumentUriUsingTree(child, document)
        assertEquals(LocalDocumentIdentity.key(first), LocalDocumentIdentity.key(second))
        assertEquals(LocalDocumentIdentity.key(first), LocalDocumentIdentity.key(Uri.parse("file:///storage/emulated/0/Books/A/story.epub")))
    }

    @Test fun `subfolder document id wins over the permission root`() {
        val tree = DocumentsContract.buildTreeDocumentUri("test.documents", "root")
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, "root/sub")
        assertEquals("root/sub", LocalDocumentIdentity.documentId(folder))
        assertEquals("root", LocalDocumentIdentity.documentId(tree))
    }

    @Test fun `opaque tree IDs may themselves be named document`() {
        val tree = DocumentsContract.buildTreeDocumentUri("test.documents", "document")
        assertEquals("document", LocalDocumentIdentity.documentId(tree))
    }

    private class DirectoryProvider : ContentProvider() {
        val queries = mutableListOf<String>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            queries += id
            val rows = if (id == "root") listOf("a" to "A", "b" to "B") else listOf("$id/book" to "$id.epub")
            return MatrixCursor(projection!!).apply {
                rows.forEach { (docId, name) ->
                    val values = mapOf<String, Any>(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID to docId,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME to name,
                        DocumentsContract.Document.COLUMN_MIME_TYPE to if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/epub+zip",
                        DocumentsContract.Document.COLUMN_SIZE to 123L,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1L,
                    )
                    addRow(projection.map { values[it] }.toTypedArray())
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
