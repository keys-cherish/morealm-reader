package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollChapterLayout

class ReaderRuntimeWindowSync(
    private val bookId: String,
    private val contentVersion: Long,
    private val store: ReaderWindowStore,
) {
    fun publish(
        current: ScrollChapterLayout,
        previous: ScrollChapterLayout? = null,
        next: ScrollChapterLayout? = null,
    ) {
        val currentId = ReaderRuntimeAdapter.unitIdForChapter(current.chapterIndex)
        val currentArtifact = ReaderRuntimeAdapter.artifactFrom(bookId, current, contentVersion)
        if (store.currentId != currentId) {
            store.replaceCurrent(currentId, WindowEntry.Ready(currentArtifact))
        } else {
            store.setCurrent(WindowEntry.Ready(currentArtifact))
        }

        store.setNeighbors(
            previous = previous?.let { ReaderRuntimeAdapter.unitIdForChapter(it.chapterIndex) },
            next = next?.let { ReaderRuntimeAdapter.unitIdForChapter(it.chapterIndex) },
        )
        previous?.let {
            val id = ReaderRuntimeAdapter.unitIdForChapter(it.chapterIndex)
            val request = store.ensure(id)
            if (request is EnsureResult.Started) {
                store.complete(id, request.requestId, ReaderRuntimeAdapter.artifactFrom(bookId, it, contentVersion))
            }
        }
        next?.let {
            val id = ReaderRuntimeAdapter.unitIdForChapter(it.chapterIndex)
            val request = store.ensure(id)
            if (request is EnsureResult.Started) {
                store.complete(id, request.requestId, ReaderRuntimeAdapter.artifactFrom(bookId, it, contentVersion))
            }
        }
    }
}
