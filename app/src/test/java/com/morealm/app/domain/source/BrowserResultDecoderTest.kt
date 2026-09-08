package com.morealm.app.domain.source

import org.junit.Assert.*
import org.junit.Test

class BrowserResultDecoderTest {
    @Test fun `WebView Unicode and quote escapes produce actual HTML`() {
        assertEquals("<input value=\"中文\">\n", decodeBrowserHtml("\"\\u003Cinput value=\\\"\\u4e2d\\u6587\\\">\\n\""))
    }
    @Test fun `cancelled or malformed WebView results do not invent content`() {
        assertNull(decodeBrowserHtml("null"))
        assertNull(decodeBrowserHtml("{bad}"))
    }
}
