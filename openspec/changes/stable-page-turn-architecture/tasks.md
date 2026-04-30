## 1. Create ReaderPageTurnState

- [ ] 1.1 Create new file `presentation/reader/ReaderPageTurnState.kt`
- [ ] 1.2 Define `ReaderPageTurnState` data class with fields: currentChapterIndex, currentPageIndex, curTextChapter, prevTextChapter, nextTextChapter, navigationDirection, renderPageCount
- [ ] 1.3 Add companion object with factory methods for creating initial state and rotated state
- [ ] 1.4 Add validation logic to ensure state consistency (e.g., navigationDirection matches chapter index change)
- [ ] 1.5 Add debug logging helper to trace state transitions

## 2. Modify ReaderChapterController - Add Central State

- [ ] 2.1 Add `_pageTurnState: MutableStateFlow<ReaderPageTurnState>` to ReaderChapterController
- [ ] 2.2 Add `pageTurnState: StateFlow<ReaderPageTurnState>` public property
- [ ] 2.3 Initialize `_pageTurnState` with initial state in constructor
- [ ] 2.4 Create helper function `computeNavigationDirection(prevIndex, newIndex)` that returns -1/0/1
- [ ] 2.5 Create helper function `computeRenderPageCount(rotatedChapter, prelayoutCache)` that pre-computes page count

## 3. Modify ReaderChapterController - Atomic Cache Rotation

- [ ] 3.1 Refactor `loadChapter()` to compute cache rotation before launching IO coroutine
- [ ] 3.2 In cache rotation logic, compute navigationDirection using helper function
- [ ] 3.3 In cache rotation logic, validate rotated cache layout parameters (viewWidth, viewHeight)
- [ ] 3.4 In cache rotation logic, clear rotated cache if layout parameters don't match
- [ ] 3.5 In cache rotation logic, compute renderPageCount using helper function
- [ ] 3.6 Emit all updates atomically: `_pageTurnState.value = newState` (single emission)
- [ ] 3.7 Add debug log: "Cache rotation: direction={dir}, renderPageCount={count}, curChapter={index}"

## 4. Modify ReaderChapterController - Update Existing State Management

- [ ] 4.1 Update `_currentChapterIndex` update to also update `_pageTurnState`
- [ ] 4.2 Update `_chapterContent` update to also update `_pageTurnState`
- [ ] 4.3 Update `_curTextChapter` update to also update `_pageTurnState`
- [ ] 4.4 Update `_prevTextChapter` update to also update `_pageTurnState`
- [ ] 4.5 Update `_nextTextChapter` update to also update `_pageTurnState`
- [ ] 4.6 Ensure all updates maintain consistency with `_pageTurnState`

## 5. Modify ReaderChapterController - Error Handling

- [ ] 5.1 In `publishReaderError()`, clear `_pageTurnState` cache entries
- [ ] 5.2 In error path of `loadChapter()`, clear `_pageTurnState` cache entries
- [ ] 5.3 Add debug log when clearing state due to error

## 6. Modify ReaderScreen - Expose Page-Turn State

- [ ] 6.1 Collect `viewModel.pageTurnState` as state in ReaderScreen
- [ ] 6.2 Pass `pageTurnState` to CanvasRenderer as a new parameter
- [ ] 6.3 Verify parameter is correctly passed through composition

## 7. Modify CanvasRenderer - Use Central State for Pager Initialization

- [ ] 7.1 Add `pageTurnState: ReaderPageTurnState` parameter to CanvasRenderer
- [ ] 7.2 Extract `navigationDirection` and `renderPageCount` from `pageTurnState`
- [ ] 7.3 Create `remember` block that computes `targetInitialPage` based on navigationDirection and renderPageCount
- [ ] 7.4 Modify `rememberPagerState()` to use `targetInitialPage` instead of hardcoded 0
- [ ] 7.5 Add debug log: "Pager init: direction={dir}, renderPageCount={count}, initialPage={page}"
- [ ] 7.6 Remove or refactor `LaunchedEffect(currentChapterKey, pageAnimType)` that calls `pagerState.scrollToPage(initialPage)`

## 8. Modify CanvasRenderer - Update Pager State Tracking

- [ ] 8.1 Update `LaunchedEffect(pageCount, chapter?.isCompleted)` to use `pageTurnState.renderPageCount`
- [ ] 8.2 Update `LaunchedEffect(renderPageCount, pageCount, ...)` to validate Pager state consistency
- [ ] 8.3 Ensure `readerPageIndex` is kept in sync with Pager's current page

## 9. Modify PageTurnCoordinator - Read from Central State

- [ ] 9.1 Modify `updateDeps()` to accept `pageTurnState: ReaderPageTurnState` parameter
- [ ] 9.2 Remove fields: `lastSettledDisplayPage`, `pendingSettledDirection`, `pendingTurnStartDisplayPage`, `ignoredSettledDisplayPage`
- [ ] 9.3 Modify `turnPageByTap()` to read current page from `pageTurnState` instead of `lastSettledDisplayPage`
- [ ] 9.4 Modify `turnPageByDrag()` to read current page from `pageTurnState` instead of `lastSettledDisplayPage`
- [ ] 9.5 Modify `handlePagerSettled()` to read current page from `pageTurnState`
- [ ] 9.6 Modify `commitPageTurn()` to read current page from `pageTurnState`
- [ ] 9.7 Add debug log when reading state from parameter

## 10. Modify CanvasRenderer - Update Coordinator Integration

- [ ] 10.1 Update `coordinator.updateDeps()` call to pass `pageTurnState`
- [ ] 10.2 Update all `coordinator` method calls to pass `pageTurnState` as parameter
- [ ] 10.3 Update `LaunchedEffect` that initializes coordinator state to use `pageTurnState`
- [ ] 10.4 Remove coordinator state initialization code that's no longer needed

## 11. Compilation and Build Verification

- [ ] 11.1 Compile the project: `./gradlew assembleDebug`
- [ ] 11.2 Fix any compilation errors
- [ ] 11.3 Verify no new warnings introduced
- [ ] 11.4 Run unit tests: `./gradlew test`
- [ ] 11.5 Verify all tests pass

## 12. Manual Testing - Basic Page Turns

- [ ] 12.1 Test forward page turn (next page within chapter)
- [ ] 12.2 Test backward page turn (previous page within chapter)
- [ ] 12.3 Test forward chapter navigation (next chapter, should show first page)
- [ ] 12.4 Test backward chapter navigation (previous chapter, should show last page WITHOUT flicker)
- [ ] 12.5 Verify no visible flicker on any page turn

## 13. Manual Testing - Edge Cases

- [ ] 13.1 Test quick successive forward page turns
- [ ] 13.2 Test quick successive backward page turns
- [ ] 13.3 Test alternating forward/backward page turns
- [ ] 13.4 Test jump to chapter (non-adjacent)
- [ ] 13.5 Test page turn at chapter boundaries (first page, last page)
- [ ] 13.6 Verify no flicker in any edge case

## 14. Manual Testing - Different Page Animation Modes

- [ ] 14.1 Test with SLIDE animation mode
- [ ] 14.2 Test with COVER animation mode
- [ ] 14.3 Test with SIMULATION animation mode
- [ ] 14.4 Test with NONE animation mode
- [ ] 14.5 Test with SCROLL animation mode
- [ ] 14.6 Verify no flicker in any animation mode

## 15. Manual Testing - Screen Rotation and Layout Changes

- [ ] 15.1 Test page turn before screen rotation
- [ ] 15.2 Test page turn after screen rotation
- [ ] 15.3 Test font size change and page turn
- [ ] 15.4 Test margin change and page turn
- [ ] 15.5 Verify layout parameters are correctly validated

## 16. Debug Logging and Diagnostics

- [ ] 16.1 Enable debug logs for page-turn state transitions
- [ ] 16.2 Verify logs show atomic state updates (single emission)
- [ ] 16.3 Verify logs show correct navigationDirection
- [ ] 16.4 Verify logs show correct renderPageCount
- [ ] 16.5 Verify logs show correct Pager initialization page

## 17. Code Review and Cleanup

- [ ] 17.1 Review ReaderPageTurnState for clarity and completeness
- [ ] 17.2 Review ReaderChapterController changes for correctness
- [ ] 17.3 Review CanvasRenderer changes for correctness
- [ ] 17.4 Review PageTurnCoordinator changes for correctness
- [ ] 17.5 Remove any debug code or temporary logging
- [ ] 17.6 Ensure code follows project style guidelines

## 18. Final Verification and Commit

- [ ] 18.1 Run full test suite: `./gradlew test`
- [ ] 18.2 Run pre-commit hooks: `git add . && git commit`
- [ ] 18.3 Verify all hooks pass
- [ ] 18.4 Create git commit with message describing the change
- [ ] 18.5 Update CLAUDE.md with this change summary
- [ ] 18.6 Update memory with this architectural decision
