package com.morealm.app.domain.http

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * HTTP 响应封装
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class StrResponse {
    var raw: Response
        private set
    var body: String? = null
        private set
    var errorBody: ResponseBody? = null
        private set

    constructor(rawResponse: Response, body: String?) {
        this.raw = rawResponse
        this.body = body
    }

    constructor(url: String, body: String?) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Request.Builder().url("http://localhost/").build()
        }
        raw = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .build()
        this.body = body
    }

    constructor(rawResponse: Response, errorBody: ResponseBody?) {
        this.raw = rawResponse
        this.errorBody = errorBody
    }

    val url: String
        get() {
            raw.networkResponse?.let {
                return it.request.url.toString()
            }
            return raw.request.url.toString()
        }

    fun body(): String? = body

    fun code(): Int = raw.code

    fun message(): String = raw.message

    fun header(name: String): String? = raw.header(name)

    fun headers(): Headers = raw.headers

    override fun toString(): String = body.orEmpty()
}
