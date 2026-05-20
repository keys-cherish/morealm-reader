package com.morealm.app.domain.parser.epub.streaming

import com.morealm.epub.ir.TagId
import com.morealm.epub.xhtml.Attrs
import com.morealm.epub.xhtml.NodeVisitor

/**
 * Decorator that forwards only the half-open document range
 * `[startFragment .. endFragment)` to [delegate], replacing the old
 * jsoup-based `sliceBodyByFragments` clone-and-keep algorithm.
 *
 * Semantics matching the original implementation:
 *  - Range is matched by element `id` attribute, in document order.
 *  - The element bearing `startFragment` IS forwarded (its open / children /
 *    close are all delivered).
 *  - The element bearing `endFragment` is NOT forwarded — slicing stops as
 *    soon as that open event arrives.
 *  - Either side may be `null` / empty:
 *      • `startFragment == null/empty` → forward from document start
 *      • `endFragment == null/empty` → forward through document end
 *      • both null/empty → identical to a pass-through decorator
 *
 * When the end anchor lives deep inside still-open ancestors, the visitor
 * synthesizes close events for every tag it has forwarded an open for but
 * not yet seen the close of. This keeps downstream `BlockVisitor` paragraph
 * buffers properly flushed even though the original XHTML tree was cut
 * mid-traversal.
 */
class FragmentSliceVisitor(
    private val delegate: NodeVisitor,
    private val startFragment: String?,
    private val endFragment: String?,
) : NodeVisitor {

    private enum class State { AWAITING_START, FORWARDING, STOPPED }

    private val openStack = ArrayDeque<TagId>()
    private var state: State =
        if (startFragment.isNullOrEmpty()) State.FORWARDING else State.AWAITING_START

    override fun onOpen(tag: TagId, attrs: Attrs, selfClosing: Boolean) {
        when (state) {
            State.AWAITING_START -> {
                val id = attrs.id()
                if (id.isNotEmpty() && id == startFragment) {
                    state = State.FORWARDING
                    forwardOpen(tag, attrs, selfClosing)
                }
            }
            State.FORWARDING -> {
                val id = attrs.id()
                if (!endFragment.isNullOrEmpty() && id == endFragment) {
                    stopAndFlush()
                    return
                }
                forwardOpen(tag, attrs, selfClosing)
            }
            State.STOPPED -> { /* drop */ }
        }
    }

    override fun onClose(tag: TagId) {
        if (state != State.FORWARDING) return
        if (openStack.lastOrNull() == tag) openStack.removeLast()
        delegate.onClose(tag)
    }

    override fun onText(text: CharSequence) {
        if (state != State.FORWARDING) return
        delegate.onText(text)
    }

    override fun onCdata(text: CharSequence) {
        if (state != State.FORWARDING) return
        delegate.onCdata(text)
    }

    private fun forwardOpen(tag: TagId, attrs: Attrs, selfClosing: Boolean) {
        if (!selfClosing) openStack.addLast(tag)
        delegate.onOpen(tag, attrs, selfClosing)
    }

    private fun stopAndFlush() {
        while (openStack.isNotEmpty()) {
            delegate.onClose(openStack.removeLast())
        }
        state = State.STOPPED
    }
}
