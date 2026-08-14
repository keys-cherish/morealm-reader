package com.morealm.app.presentation.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLocationResolverTest {
    private val externalStorageAuthority = "com.android.externalstorage.documents"

    @Test
    fun `external storage document id resolves nested and volume root parents`() {
        assertEquals(
            "primary:Books/A",
            importParentDocumentId(externalStorageAuthority, "primary:Books/A/book.epub"),
        )
        assertEquals(
            "primary:",
            importParentDocumentId(externalStorageAuthority, "primary:book.epub"),
        )
    }

    @Test
    fun `opaque providers and malformed ids stay unresolved`() {
        assertNull(importParentDocumentId("com.example.cloud.documents", "primary:Books/book.epub"))
        assertNull(importParentDocumentId(externalStorageAuthority, "primary:"))
        assertNull(importParentDocumentId(externalStorageAuthority, "malformed"))
    }
}
