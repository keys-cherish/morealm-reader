package com.morealm.app.domain.reader.runtime

class ReaderSession(
    private val windowStore: ReaderWindowStore,
    private val firstAnchorFor: (ReadingUnitId) -> ContentAnchor,
    private val lastAnchorFor: (ReadingUnitId) -> ContentAnchor,
) {
    private var nextTransactionId = 1L
    private var latestTransactionId = 0L

    val window: ReaderWindow
        get() = windowStore.snapshot

    fun prepare(intent: ReaderIntent): NavigationPreparation {
        val target = resolveTarget(intent) ?: return NavigationPreparation.AtBoundary
        val from = currentAnchor()
        val transaction = NavigationTransaction(
            id = nextTransactionId++,
            source = intent.source,
            from = from,
            target = target,
            targetEntryRequestId = null,
        )
        latestTransactionId = transaction.id

        val targetEntry = windowEntry(target.unitId)
        return when (targetEntry) {
            is WindowEntry.Ready -> {
                NavigationPreparation.Ready(
                    TurnPlan(
                        transaction = transaction,
                        sourceArtifact = currentArtifact() ?: targetEntry.artifact,
                        targetArtifact = targetEntry.artifact,
                        direction = direction(from, target),
                        allowInteraction = true,
                    )
                )
            }

            is WindowEntry.Loading -> NavigationPreparation.Waiting(
                transaction = transaction.copy(targetEntryRequestId = targetEntry.requestId),
                showDelayedLoading = currentArtifact() != null,
            )

            is WindowEntry.Failed -> NavigationPreparation.Failed(
                transaction = transaction.copy(targetEntryRequestId = targetEntry.requestId),
                message = targetEntry.errorMessage,
            )

            WindowEntry.Empty -> when (val result = windowStore.ensure(target.unitId)) {
                is EnsureResult.Started -> NavigationPreparation.Waiting(
                    transaction = transaction.copy(targetEntryRequestId = result.requestId),
                    showDelayedLoading = currentArtifact() != null,
                )

                is EnsureResult.Loading -> NavigationPreparation.Waiting(
                    transaction = transaction.copy(targetEntryRequestId = result.requestId),
                    showDelayedLoading = currentArtifact() != null,
                )

                is EnsureResult.Ready -> error("Window store returned an inconsistent ready state")
                is EnsureResult.Failed -> NavigationPreparation.Failed(
                    transaction = transaction.copy(targetEntryRequestId = result.requestId),
                    message = result.message,
                )
            }
        }
    }

    fun commit(transactionId: Long): Boolean {
        if (transactionId != latestTransactionId) return false
        val target = pendingTarget ?: return false
        val committed = if (target.unitId == window.current) {
            true
        } else if (target.unitId == window.previous || target.unitId == window.next) {
            windowStore.commitAdjacent(target.unitId)
        } else {
            false
        }
        if (committed) pendingTarget = null
        return committed
    }

    fun cancel(transactionId: Long): Boolean {
        if (transactionId != latestTransactionId) return false
        pendingTarget = null
        return true
    }

    private var pendingTarget: ContentAnchor? = null

    private fun resolveTarget(intent: ReaderIntent): ContentAnchor? {
        val target = when (intent) {
            is ReaderIntent.Open -> intent.target
            is ReaderIntent.Move -> when (intent.direction) {
                NavigationDirection.PREVIOUS -> window.previous?.let {
                    if (intent.source == NavigationSource.BUTTON) firstAnchorFor(it) else lastAnchorFor(it)
                }
                NavigationDirection.NEXT -> window.next?.let(firstAnchorFor)
                NavigationDirection.NONE -> null
            }
        }
        pendingTarget = target
        return target
    }

    private fun currentAnchor(): ContentAnchor =
        currentArtifact()?.let { ContentAnchor(it.key.unitId, 0) }
            ?: ContentAnchor(window.current, 0)

    private fun currentArtifact(): LayoutArtifact? =
        (window.currentEntry as? WindowEntry.Ready)?.artifact

    private fun windowEntry(unitId: ReadingUnitId): WindowEntry = when (unitId) {
        window.previous -> window.previousEntry
        window.current -> window.currentEntry
        window.next -> window.nextEntry
        else -> WindowEntry.Empty
    }

    private fun direction(from: ContentAnchor, target: ContentAnchor): NavigationDirection = when {
        target.unitId == window.previous -> NavigationDirection.PREVIOUS
        target.unitId == window.next -> NavigationDirection.NEXT
        else -> NavigationDirection.NONE
    }
}
