package com.morealm.app.domain.reader.runtime

class ReaderWindowStore(
    initialCurrent: ReadingUnitId,
) {
    private var previousId: ReadingUnitId? = null
    private var nextId: ReadingUnitId? = null
    private var previousEntry: WindowEntry = WindowEntry.Empty
    private var currentEntry: WindowEntry = WindowEntry.Empty
    private var nextEntry: WindowEntry = WindowEntry.Empty
    private val entries = LinkedHashMap<ReadingUnitId, WindowEntry>()
    private var nextRequestId = 1L

    var currentId: ReadingUnitId = initialCurrent
        private set

    val snapshot: ReaderWindow
        get() = ReaderWindow(
            previous = previousId,
            current = currentId,
            next = nextId,
            previousEntry = previousEntry,
            currentEntry = currentEntry,
            nextEntry = nextEntry,
        )

    fun setCurrent(entry: WindowEntry.Ready) {
        require(entry.artifact.key.unitId == currentId) {
            "Current artifact does not match current unit"
        }
        currentEntry = entry
        entries[currentId] = entry
    }

    fun moveTo(unitId: ReadingUnitId) {
        currentId = unitId
        currentEntry = entries[unitId] ?: WindowEntry.Empty
        previousId = null
        previousEntry = WindowEntry.Empty
        nextId = null
        nextEntry = WindowEntry.Empty
        retainWindowEntries()
    }

    fun setNeighbors(previous: ReadingUnitId?, next: ReadingUnitId?) {
        previousId = previous
        nextId = next
        previousEntry = entryFor(previous)
        nextEntry = entryFor(next)
        retainWindowEntries()
    }

    fun ensure(unitId: ReadingUnitId): EnsureResult {
        return when (val entry = entryFor(unitId)) {
            is WindowEntry.Ready -> EnsureResult.Ready(entry.artifact)
            is WindowEntry.Loading -> EnsureResult.Loading(entry.requestId)
            // 失败不缓存：再次 ensure 即重试（旧加载链路失败后由下次 effect 重启重试，语义对齐）
            is WindowEntry.Failed,
            WindowEntry.Empty,
            -> {
                val requestId = nextRequestId++
                updateEntry(unitId, WindowEntry.Loading(requestId))
                EnsureResult.Started(requestId)
            }
        }
    }

    fun invalidateAll() {
        entries.clear()
        previousEntry = WindowEntry.Empty
        currentEntry = WindowEntry.Empty
        nextEntry = WindowEntry.Empty
    }

    fun complete(
        unitId: ReadingUnitId,
        requestId: Long,
        artifact: LayoutArtifact,
    ): Boolean {
        if (artifact.key.unitId != unitId) return false
        if (entryFor(unitId) != WindowEntry.Loading(requestId)) return false
        updateEntry(unitId, WindowEntry.Ready(artifact))
        return true
    }

    fun fail(unitId: ReadingUnitId, requestId: Long, message: String): Boolean {
        if (entryFor(unitId) != WindowEntry.Loading(requestId)) return false
        updateEntry(unitId, WindowEntry.Failed(requestId, message))
        return true
    }

    fun commitAdjacent(unitId: ReadingUnitId): Boolean {
        require(unitId == previousId || unitId == nextId) {
            "Only an adjacent unit can be committed"
        }
        val target = entryFor(unitId) as? WindowEntry.Ready ?: return false
        if (unitId == nextId) {
            previousId = currentId
            previousEntry = currentEntry
            currentId = unitId
            currentEntry = target
            nextId = null
            nextEntry = WindowEntry.Empty
        } else {
            nextId = currentId
            nextEntry = currentEntry
            currentId = unitId
            currentEntry = target
            previousId = null
            previousEntry = WindowEntry.Empty
        }
        return true
    }

    private fun entryFor(unitId: ReadingUnitId?): WindowEntry {
        if (unitId == null) return WindowEntry.Empty
        return entries[unitId] ?: when (unitId) {
            previousId -> previousEntry
            currentId -> currentEntry
            nextId -> nextEntry
            else -> WindowEntry.Empty
        }
    }

    private fun updateEntry(unitId: ReadingUnitId, entry: WindowEntry) {
        entries[unitId] = entry
        when (unitId) {
            previousId -> previousEntry = entry
            currentId -> currentEntry = entry
            nextId -> nextEntry = entry
            else -> error("Unit is outside the current window")
        }
    }

    private fun retainWindowEntries() {
        val retained = setOfNotNull(previousId, currentId, nextId)
        entries.keys.retainAll(retained)
    }
}

sealed interface EnsureResult {
    data class Started(val requestId: Long) : EnsureResult
    data class Loading(val requestId: Long) : EnsureResult
    data class Ready(val artifact: LayoutArtifact) : EnsureResult
}
