package com.morealm.app.domain.storage

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A tree is a permission grant. The document ID identifies the selected descendant. */
object LocalDocumentIdentity {
    fun documentId(uri: Uri): String {
        val segments = uri.pathSegments
        val isDocument = segments.firstOrNull() == "document" ||
            (segments.firstOrNull() == "tree" && segments.getOrNull(2) == "document")
        return if (isDocument) DocumentsContract.getDocumentId(uri)
        else DocumentsContract.getTreeDocumentId(uri)
    }

    fun key(uri: Uri): String {
        if (uri.scheme == "file" || uri.scheme == null) {
            val raw = uri.path ?: uri.toString()
            // External-storage identities are Android paths, even in a desktop JVM test host.
            val path = if (raw.startsWith("/storage/") || raw.startsWith("/sdcard/")) {
                java.net.URI(null, null, raw, null).normalize().path
                    .replaceFirst(Regex("^/sdcard/"), "/storage/emulated/0/")
            } else File(raw).canonicalPath
            val relative = path.removePrefix("/storage/emulated/0/")
            if (relative != path) return "external:primary:$relative"
            if (path.startsWith("/storage/")) {
                val parts = path.removePrefix("/storage/").split('/', limit = 2)
                if (parts.size == 2) return "external:${parts[0]}:${parts[1]}"
            }
            return "file:$path"
        }
        if (uri.scheme == "content") {
            val id = runCatching { documentId(uri) }.getOrNull()
            if (id != null) {
                return if (uri.authority == "com.android.externalstorage.documents") {
                    "external:$id"
                } else "document:${uri.authority}:$id"
            }
        }
        return uri.toString()
    }
}

data class LocalBookReference(val id: String, val localPath: String)
