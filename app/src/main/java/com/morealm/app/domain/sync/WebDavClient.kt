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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * WebDAV client for cloud sync.
 * Supports PROPFIND (with XML body), GET, PUT, MKCOL, DELETE, file listing.
 * All IO runs on Dispatchers.IO (non-blocking).
 *
 * Scheme rewrites at construction:
 *  - `davs://` → `https://`
 *  - `dav://`  → `http://`
 * matching Legado / generic WebDAV client conventions so users can paste a
 * URL exported from another reader without manual editing.
 *
 * Error mapping centralised via [describeError]:
 *  - 401 inspects `WWW-Authenticate` to distinguish "wrong password" from
 *    "server rejects BasicAuth entirely" (Digest-only servers, etc.).
 *  - 404 mapped to a clear "resource not found" message.
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
) {
    /**
     * Normalised base URL: `davs://` / `dav://` rewritten to standard
     * `https://` / `http://`, trailing slash stripped so [resolveUrl] can
     * append paths predictably.
     */
    private val baseUrl: String = baseUrl
        .replaceFirst(Regex("^davs://", RegexOption.IGNORE_CASE), "https://")
        .replaceFirst(Regex("^dav://", RegexOption.IGNORE_CASE), "http://")
        .trimEnd('/')

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
        /**
         * Parsed RFC 1123 timestamp in epoch ms, or 0 when the server didn't
         * return getlastmodified or the value couldn't be parsed. Used by
         * "newest backup" / "fresher progress wins" comparisons — without
         * this, the [lastModified] string alone can't be ordered reliably.
         */
        val lastModifiedEpoch: Long = 0L,
    )

    suspend fun upload(remotePath: String, data: ByteArray, contentType: String = "application/octet-stream") =
        withContext(Dispatchers.IO) {
            val url = resolveUrl(remotePath)
            val response = client.newCall(Request.Builder().url(url)
                .put(data.toRequestBody(contentType.toMediaType())).build()).execute()
            response.use { if (!it.isSuccessful) throw WebDavException(describeError(it, "Upload")) }
        }

    suspend fun download(remotePath: String): ByteArray = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(resolveUrl(remotePath)).get().build()).execute()
        response.use {
            if (!it.isSuccessful) throw WebDavException(describeError(it, "Download"))
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
        response.use { if (!it.isSuccessful && it.code != 404) throw WebDavException(describeError(it, "Delete")) }
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

            files.add(DavFile(name, href, isDir, size, lastMod, parseLastModifiedEpoch(lastMod)))
        }
        return files.sortedWith(compareByDescending<DavFile> { it.isDirectory }.thenBy { it.name })
    }

    /**
     * Parse a `getlastmodified` value (RFC 1123, e.g. `Sun, 06 Nov 1994 08:49:37 GMT`)
     * into epoch milliseconds. Returns 0 on blank input or any parse failure so
     * the caller can treat 0 as "unknown timestamp" without crashing on a
     * server that returns an unexpected format.
     */
    private fun parseLastModifiedEpoch(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        return try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (e: Exception) {
            AppLog.warn("WebDAV", "Unparseable getlastmodified: $s -> ${e.message}")
            0L
        }
    }

    /**
     * Build a user-friendly error message for a non-2xx response. The 401
     * branch inspects `WWW-Authenticate` so we can surface "server rejects
     * BasicAuth entirely" (e.g. Digest-only or NTLM-only servers) versus
     * the more common "your password is wrong" — both produce a 401 but
     * have very different fixes.
     */
    private fun describeError(resp: Response, action: String): String {
        return when (resp.code) {
            401 -> {
                val wwwAuth = resp.headers("WWW-Authenticate")
                val supportsBasic = wwwAuth.any { it.startsWith("Basic", ignoreCase = true) }
                if (wwwAuth.isNotEmpty() && !supportsBasic) {
                    "$action: 服务器不支持 Basic 认证（仅支持 ${wwwAuth.joinToString()}），" +
                        "请改用支持 Basic 的 WebDAV 服务或联系服务器管理员"
                } else {
                    "$action: 认证失败 (401) — 用户名或密码错误"
                }
            }
            403 -> "$action: 服务器拒绝访问 (403) — 检查账号权限或目录写入权限"
            404 -> "$action: 资源不存在 (404)"
            else -> "$action failed: ${resp.code} ${resp.message}".trim()
        }
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
