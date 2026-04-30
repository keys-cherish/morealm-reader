## Context

MoRealm's current page-turn architecture distributes state across multiple layers:
- **ViewModel** (`ReaderChapterController`): Manages chapter loading, caching, and TextChapter layout
- **UI Layer** (`CanvasRenderer`): Manages Pager state, page index, and rendering
- **Coordinator** (`PageTurnCoordinator`): Manages page-turn animations and settled page tracking

This distribution causes timing issues:
1. When user navigates backward, ViewModel rotates cache asynchronously
2. CanvasRenderer recomposes and initializes Pager with `initialPage = 0`
3. LaunchedEffect later calls `scrollToPage(lastPage)`, causing visible flicker
4. The root cause: Pager's `initialPage` must be determined at `remember` time, but the ViewModel hasn't communicated the target page yet

Legado avoids this through:
- Single state source (`ReadBook` global object)
- Synchronous cache rotation and content update
- Page content pre-rendered before animation starts

## Goals / Non-Goals

**Goals:**
- Eliminate page-turn flicker by ensuring Pager initializes with the correct page on first render
- Establish a single source of truth for page-turn state (chapter index, page index, cached chapters, navigation direction)
- Make cache rotation atomic: all related state updates happen together
- Pre-compute page counts during cache rotation so Pager knows the target page count before initialization
- Maintain backward compatibility with existing page-turn animations (SLIDE, COVER, SIMULATION, NONE, SCROLL)
- Provide a foundation for future page-turn features without introducing new timing issues

**Non-Goals:**
- Refactor the entire reader architecture (stay focused on page-turn stability)
- Change page animation implementations
- Modify TTS or search functionality
- Add new page-turn modes or animations

## Decisions

### Decision 1: Central State Object (`ReaderPageTurnState`)

**Choice**: Create a new `ReaderPageTurnState` data class in ViewModel that holds all page-turn related state.

**Rationale**:
- Legado's stability comes from having a single state source
- StateFlow<ReaderPageTurnState> ensures all related state updates are atomic
- CanvasRenderer can read the complete state in one place
- Easier to reason about state consistency

**Alternatives considered**:
- A: Keep state distributed, add synchronization logic → Complex, error-prone, doesn't solve root cause
- B: Use a single large StateFlow with all reader state → Too broad, mixes concerns
- C: Create ReaderPageTurnState → **Selected** - focused, atomic, clear responsibility

**Structure**:
```kotlin
data class ReaderPageTurnState(
    val currentChapterIndex: Int,
    val currentPageIndex: Int,
    val curTextChapter: TextChapter?,
    val prevTextChapter: TextChapter?,
    val nextTextChapter: TextChapter?,
    val navigationDirection: Int,  // -1 (prev), 0 (none), 1 (next)
    val renderPageCount: Int,  // Pre-computed for Pager initialization
)
```

### Decision 2: Atomic Cache Rotation in ViewModel

**Choice**: Modify `loadChapter()` to perform cache rotation, page index update, and navigation direction update in a single StateFlow emission.

**Rationale**:
- Ensures CanvasRenderer never sees partial state
- Eliminates race conditions where Pager sees old state
- Matches Legado's synchronous model

**Implementation**:
- Before launching the IO coroutine, compute:
  - Navigation direction (forward/backward/jump)
  - Rotated cache (prev/cur/next)
  - Pre-computed page count from prelayout cache or rotated cache
- Emit all updates together via `_pageTurnState.value = newState`
- CanvasRenderer recomposes once with complete state

### Decision 3: Pre-computed Page Counts

**Choice**: During cache rotation, compute the page count of the new chapter using prelayout cache or ViewModel cache.

**Rationale**:
- Pager needs `pageCount` to determine valid `initialPage` range
- Without pre-computation, Pager initializes with wrong `pageCount`, causing incorrect `initialPage`
- Legado pre-renders all pages before animation, we can at least pre-compute the count

**Implementation**:
- When rotating cache, check if rotated chapter is in prelayout cache
- If yes, use its `pageSize` as `renderPageCount`
- If no, use current `renderPageCount` as fallback (will be updated when layout completes)
- This ensures Pager can calculate correct `initialPage` on first render

### Decision 4: Pager Initialization Optimization

**Choice**: Calculate `initialPage` before Pager creation using `ReaderPageTurnState.navigationDirection` and `renderPageCount`.

**Rationale**:
- Current approach: `initialPage = 0`, then `scrollToPage(target)` in LaunchedEffect → causes flicker
- Better approach: `initialPage = target` on first render → no flicker
- Requires knowing the target page before `rememberPagerState()` is called

**Implementation**:
```kotlin
val targetInitialPage = remember(navigationDirection, renderPageCount) {
    when {
        navigationDirection < 0 -> (renderPageCount - 1).coerceAtLeast(0)  // Backward: last page
        navigationDirection > 0 -> 0  // Forward: first page
        else -> readerPageIndex  // Jump: restore saved index
    }
}
val pagerState = rememberPagerState(initialPage = targetInitialPage, pageCount = { renderPageCount })
```

### Decision 5: PageTurnCoordinator State Synchronization

**Choice**: Modify PageTurnCoordinator to read from `ReaderPageTurnState` instead of maintaining its own state.

**Rationale**:
- Eliminates duplicate state that can diverge
- Coordinator becomes a pure animation handler, not a state manager
- Simpler to reason about: one source of truth

**Implementation**:
- Remove `lastSettledDisplayPage`, `pendingSettledDirection`, etc. from Coordinator
- Pass `ReaderPageTurnState` to Coordinator methods
- Coordinator reads current state from parameter, not from its own fields

### Decision 6: Removal of Timing-Dependent LaunchedEffects

**Choice**: Remove or refactor LaunchedEffects that scroll Pager after initialization.

**Rationale**:
- These LaunchedEffects exist because Pager initializes incorrectly
- With correct `initialPage`, they're no longer needed
- Fewer LaunchedEffects = fewer timing issues

**Implementation**:
- Remove `LaunchedEffect(currentChapterKey, pageAnimType)` that calls `pagerState.scrollToPage(initialPage)`
- Keep `LaunchedEffect(renderPageCount, pageCount, ...)` for progress restoration (but it won't scroll, just validate)

## Risks / Trade-offs

**[Risk] Pre-computed page count might be inaccurate**
- Mitigation: Use prelayout cache which is guaranteed to be accurate. If not available, use current renderPageCount as fallback. When actual layout completes, update renderPageCount and re-validate Pager state.

**[Risk] Backward compatibility with existing page-turn state**
- Mitigation: ReaderPageTurnState is internal to ViewModel. Public APIs don't change. Existing callbacks (onNextChapter, onPrevChapter) still work the same way.

**[Risk] Quick successive page turns might cause state inconsistency**
- Mitigation: Each loadChapter() call increments a token. Only the latest token's state is applied. Older requests are discarded.

**[Risk] Coordinator needs to be refactored to read from parameter**
- Mitigation: This is a clean refactor, not a breaking change. Coordinator's public interface (turnPageByTap, turnPageByDrag, etc.) stays the same.

**[Trade-off] Slightly more complex ViewModel logic**
- Benefit: Eliminates flicker and provides stable foundation for future features. Worth the complexity.

## Migration Plan

1. **Phase 1**: Create `ReaderPageTurnState` and add it to ViewModel (non-breaking, parallel to existing state)
2. **Phase 2**: Modify `loadChapter()` to populate `ReaderPageTurnState` atomically
3. **Phase 3**: Update CanvasRenderer to use `ReaderPageTurnState` for Pager initialization
4. **Phase 4**: Refactor PageTurnCoordinator to read from `ReaderPageTurnState`
5. **Phase 5**: Remove old state management code and LaunchedEffects
6. **Phase 6**: Test and verify no flicker on all page-turn scenarios

**Rollback strategy**: Each phase is independently testable. If issues arise, revert to previous phase.

## Open Questions

1. Should `ReaderPageTurnState` be exposed as a public StateFlow or kept internal? → Keep internal for now, expose only if needed by other components
2. How to handle the case where prelayout cache doesn't have the rotated chapter? → Use current renderPageCount as fallback, update when layout completes
3. Should we add logging to track state transitions for debugging? → Yes, add debug logs in loadChapter() and Pager initialization
