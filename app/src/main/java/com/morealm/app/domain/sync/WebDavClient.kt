package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.http.addExceptionLoggingInterceptor
import com.morealm.app.domain.http.installDispatcherExceptionLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * WebDAV client for cloud sync.
 * Supports PROPFIND (with XML body), GET, PUT, MKCOL, DELETE, file listing.
 * All IO runs on Dispatchers.IO (non-blocking).
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val credential = Credentials.basic(username, password)
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request().newBuilder()
                    .header("Authorization", credential).build())
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Throwable) {
                AppLog.error("WebDAV", "Unexpected WebDAV interceptor error", e)
                throw java.io.IOException(e)
            }
        }
        .addExceptionLoggingInterceptor("WebDAV")
        .build()
        .apply { installDispatcherExceptionLogger("WebDAV") }

    data class DavFile(
        val name: String, val href: String, val isDirectory: Boolean,
        val size: Long = 0, val lastModified: String = "",
    )

    suspend fun upload(remotePath: String, data: ByteArray, contentType: String = "application/octet-stream") =
        withContext(Dispatchers.IO) {
            val url = resolveUrl(remotePath)
            val response = client.newCall(Request.Builder().url(url)
                .put(data.toRequestBody(contentType.toMediaType())).build()).execute()
            response.use { if (!it.isSuccessful) throw WebDavException("Upload failed: ${it.code}") }
        }

    suspend fun download(remotePath: String): ByteArray = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(resolveUrl(remotePath)).get().build()).execute()
        response.use {
            if (!it.isSuccessful) throw WebDavException("Download failed: ${it.code}")
            it.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun mkdir(remotePath: String) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(resolveUrl(remotePath))
            .method("MKCOL", null).build()).execute().close()
    }

    suspend fun delete(remotePath: String) = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(resolveUrl(remotePath))
            .delete().build()).execute()
        response.use { if (!it.isSuccessful && it.code != 404) throw WebDavException("Delete failed: ${it.code}") }
    }

    suspend fun exists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""
        val response = client.newCall(Request.Builder().url(resolveUrl(remotePath))
            .method("PROPFIND", xml.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0").build()).execute()
        response.use { it.isSuccessful || it.code == 207 }
    }

    suspend fun listFiles(remotePath: String): List<DavFile> = withContext(Dispatchers.IO) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop>
                <D:displayname/><D:resourcetype/><D:getcontentlength/><D:getlastmodified/>
            </D:prop></D:propfind>"""
        val url = resolveUrl(remotePath)
        val response = client.newCall(Request.Builder().url(url)
            .method("PROPFIND", xml.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1").build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 207) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            parsePropfindResponse(body, url)
        }
    }

    private fun parsePropfindResponse(xml: String, requestUrl: String): List<DavFile> {
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        val files = mutableListOf<DavFile>()
        val requestPath = java.net.URI(requestUrl).path?.trimEnd('/') ?: ""

        doc.select("response, D\\:response").forEach { response ->
            val href = response.select("href, D\\:href").text().trim()
            val hrefPath = try { java.net.URI(href).path?.trimEnd('/') } catch (_: Exception) { href.trimEnd('/') }
            if (hrefPath == requestPath) return@forEach // Skip self

            val name = response.select("displayname, D\\:displayname").text().takeIf { it.isNotBlank() }
                ?: href.trimEnd('/').substringAfterLast('/')
            val isDir = response.select("resourcetype collection, D\\:resourcetype D\\:collection").isNotEmpty()
            val size = response.select("getcontentlength, D\\:getcontentlength").text().toLongOrNull() ?: 0
            val lastMod = response.select("getlastmodified, D\\:getlastmodified").text()

            files.add(DavFile(name, href, isDir, size, lastMod))
        }
        return files.sortedWith(compareByDescending<DavFile> { it.isDirectory }.thenBy { it.name })
    }

    private fun resolveUrl(path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = path.trimStart('/')
        return if (p.isEmpty()) base else "$base/$p"
    }
}

class WebDavException(message: String) : Exception(message)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
)
