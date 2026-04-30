package com.morealm.app.presentation.shelf

import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.entity.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the seven smart-shelf views described in [SystemView].
 *
 * Why a dedicated controller and not more methods on [ShelfViewModel]?
 * - The shelf VM is already 600+ lines doing folder import, group editing,
 *   book CRUD, and cover refresh. Adding seven more flows would push it past
 *   the size where reviewers can keep state in their heads.
 * - System views are pure read-projections of the book table and cleanly fit
 *   the "controller per concern" pattern already used for the reader
 *   (`ReaderTtsController`, `ReaderSearchController`, etc.).
 * - Tests can drive this controller alone with a fake [BookDao] without
 *   needing to set up the full shelf graph.
 *
 * The controller is `@Singleton` because the smart-view selection is global
 * UI state — switching tabs in the shelf shouldn't reset the view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ShelfSystemViewController @Inject constructor(
    private val bookDao: BookDao,
) {

    /** Currently active smart view, or null if the user is browsing the legacy folder list. */
    private val _selectedView = MutableStateFlow<SystemView?>(SystemView.CONTINUE_READING)
    val selectedView: StateFlow<SystemView?> = _selectedView.asStateFlow()

    fun selectView(view: SystemView?) {
        _selectedView.value = view
    }

    /**
     * Per-view book counts, one entry per [SystemView] in declaration order.
     * Updates reactively as books are added / read / archived.
     *
     * Note we don't include BY_SOURCE here — that one is rendered as a list of
     * sub-groups via [observeSourceGroups], not a single count.
     */
    fun observeCounts(scope: CoroutineScope, now: () -> Long = System::currentTimeMillis): StateFlow<List<SystemViewCount>> {
        val recent = now() - SystemView.WEEK_MS
        val followingSince = now() - SystemView.FOLLOWING_WINDOW_MS
        val staleBefore = now() - SystemView.STALE_WINDOW_MS

        // We snapshot `now()` once when the flow is built; for a long-running app
        // this is fine — the deltas across views are at most a few hours, which
        // doesn't change which books are "recent" vs "stale" enough to be wrong.
        // If accuracy matters we can later tick this every minute.
        val flows: List<Flow<Int>> = listOf(
            bookDao.countContinueReading(recent),
            bookDao.countFollowingUpdates(followingSince),
            bookDao.countNewThisWeek(recent),
            bookDao.countStale(staleBefore, SystemView.FINISHED_THRESHOLD),
            bookDao.countFinished(SystemView.FINISHED_THRESHOLD),
            bookDao.countLocalFiles(SystemView.LOCAL_FORMATS),
            // BY_SOURCE has no single count — surfaced as 0 here, sub-groups elsewhere.
            flowOf(0),
        )
        return combine(flows) { counts ->
            SystemView.values().mapIndexed { idx, view ->
                SystemViewCount(view, counts[idx])
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    }

    /**
     * Books in the currently-selected view. Switches reactively when
     * [selectView] is called — Room emits a new query result without us
     * having to re-bind the UI.
     */
    fun observeBooksInSelectedView(now: () -> Long = System::currentTimeMillis): Flow<List<Book>> {
        return _selectedView.flatMapLatest { view ->
            if (view == null) flowOf(emptyList()) else booksFor(view, now())
        }
    }

    private fun booksFor(view: SystemView, t: Long): Flow<List<Book>> = when (view) {
        SystemView.CONTINUE_READING -> bookDao.observeContinueReading(t - SystemView.WEEK_MS)
        SystemView.FOLLOWING_UPDATES -> bookDao.observeFollowingUpdates(t - SystemView.FOLLOWING_WINDOW_MS)
        SystemView.NEW_THIS_WEEK -> bookDao.observeNewThisWeek(t - SystemView.WEEK_MS)
        SystemView.STALE -> bookDao.observeStale(t - SystemView.STALE_WINDOW_MS, SystemView.FINISHED_THRESHOLD)
        SystemView.FINISHED -> bookDao.observeFinished(SystemView.FINISHED_THRESHOLD)
        SystemView.LOCAL_FILES -> bookDao.observeLocalFiles(SystemView.LOCAL_FORMATS)
        SystemView.BY_SOURCE -> flowOf(emptyList()) // Rendered specially; uses observeSourceGroups
    }

    fun observeSourceGroups() = bookDao.observeSourceGroups()
}
