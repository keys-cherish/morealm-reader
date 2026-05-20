package com.morealm.app.domain.parser.epub.streaming

import com.morealm.epub.ir.TagId
import com.morealm.epub.xhtml.Attrs
import com.morealm.epub.xhtml.NodeVisitor

/**
 * Decorator NodeVisitor that rewrites every `<img>` `src` through [lookup] before
 * forwarding to [delegate].
 *
 * Behavior on `<img>` open events:
 *  - `src` empty → drop the `<img>` entirely (no open/close forwarded).
 *  - [lookup] returns a non-empty file URL → forward `<img>` with **only**
 *    `src` (plus original `alt` when present); strip the raw `style/width/height`
 *    EPUB-author attributes so the downstream BlockVisitor renders with the
 *    reader's own layout rules.
 *  - [lookup] returns `null` → drop the `<img>` (downstream Image block on an
 *    unresolvable src would render a broken placeholder).
 *
 * `<img>` is a void element so XhtmlReader emits `onOpen(IMG, _, true)`
 * immediately followed by `onClose(IMG)`. This visitor pairs them by
 * remembering whether the most recent open was suppressed, then skipping the
 * matching close. Nested or mismatched IMG events do not occur in valid
 * XHTML so the single boolean is sufficient.
 *
 * **Out of scope**: cover-page sentinel handling. The StreamingChapterReader
 * short-circuits cover pages before they reach this visitor; this visitor
 * only sees the regular per-chapter `<img>` stream.
 */
class ImgRewriteVisitor(
    private val delegate: NodeVisitor,
    private val lookup: (src: String) -> String?,
) : NodeVisitor {

    private var suppressNextImgClose = false

    override fun onOpen(tag: TagId, attrs: Attrs, selfClosing: Boolean) {
        if (tag != TagId.IMG) {
            delegate.onOpen(tag, attrs, selfClosing)
            return
        }
        val src = attrs.src()
        if (src.isEmpty()) {
            suppressNextImgClose = true
            return
        }
        val resolved = lookup(src)
        if (resolved.isNullOrEmpty()) {
            suppressNextImgClose = true
            return
        }
        val rewritten = LinkedHashMap<String, String>(2)
        rewritten["src"] = resolved
        attrs.alt().takeIf { it.isNotEmpty() }?.let { rewritten["alt"] = it }
        delegate.onOpen(TagId.IMG, Attrs.of(rewritten), true)
        suppressNextImgClose = false
    }

    override fun onClose(tag: TagId) {
        if (tag != TagId.IMG) {
            delegate.onClose(tag)
            return
        }
        if (suppressNextImgClose) {
            suppressNextImgClose = false
            return
        }
        delegate.onClose(TagId.IMG)
    }

    override fun onText(text: CharSequence) {
        delegate.onText(text)
    }

    override fun onCdata(text: CharSequence) {
        delegate.onCdata(text)
    }
}
