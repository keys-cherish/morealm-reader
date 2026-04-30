package com.morealm.app.domain.http

import com.morealm.app.core.log.AppLog
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * SSLHelper — Legado parity.
 *
 * Many small / self-hosted Chinese book-source sites ship self-signed or expired SSL
 * certificates that fail Android's default chain validation:
 *   - "Chain validation failed"
 *   - "Trust anchor for certification path not found"
 *
 * Rather than dropping those sources, we install a trust manager that accepts any
 * certificate. This matches Legado's default behavior and is what users expect from
 * a generic content reader: book sources are user-installed, sandboxed by the
 * source's domain, and don't carry credentials beyond per-domain cookies.
 *
 * Trade-off: an attacker on the network could MITM book-source traffic. We accept
 * this because:
 *   1. Book sources are public read-only HTML / JSON
 *   2. Sensitive data (login cookies) is per-domain — MITM affects only that source
 *   3. The alternative is dropping ~30% of the source ecosystem (parity with Legado)
 */
object SSLHelper {

    private const val TAG = "SSLHelper"

    /** Trust manager that accepts every certificate. */
    private val unsafeTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val unsafeSSLSocketFactory by lazy {
        try {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(unsafeTrustManager), SecureRandom())
            }.socketFactory
        } catch (e: Exception) {
            AppLog.warn(TAG, "build unsafe SSL context failed: ${e.message}")
            // Fallback to default — keeps app booting even if TLS init fails
            SSLContext.getDefault().socketFactory
        }
    }

    /** Hostname verifier that always returns true (paired with [unsafeSSLSocketFactory]) */
    val unsafeHostnameVerifier = HostnameVerifier { _, _ -> true }

    /** System default trust manager (used when caller wants strict validation) */
    val systemTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Apply unsafe SSL configuration to an OkHttpClient builder */
    fun OkHttpClient.Builder.trustAllSSL(): OkHttpClient.Builder {
        sslSocketFactory(unsafeSSLSocketFactory, unsafeTrustManager)
        hostnameVerifier(unsafeHostnameVerifier)
        return this
    }
}
