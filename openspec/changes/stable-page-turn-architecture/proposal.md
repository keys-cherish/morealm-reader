## Why

MoRealm's current page-turn implementation uses an asynchronous model where Pager state, ViewModel state, and Coordinator state are managed independently. This causes a race condition: when navigating backward to the previous chapter, the Pager initializes to page 0 before the ViewModel can communicate the target page (last page), resulting in visible flicker (last page → page 1 → last page). This is not a minor UI glitch but a fundamental architectural issue that will cause recurring problems as features are added. Legado's View-based architecture avoids this through synchronous cache rotation and atomic state updates. We need to redesign MoRealm's page-turn mechanism to achieve Legado-level stability.

## What Changes

- **New central state object** (`ReaderPageTurnState`) that serves as the single source of truth for all page-turn related state (chapter index, page index, cached chapters, navigation direction)
- **Atomic cache rotation** in ViewModel: when navigating chapters, cache rotation, page index update, and navigation direction are updated together in a single StateFlow emission
- **Pre-computed page counts** during cache rotation so that CanvasRenderer knows the target page count before Pager initialization
- **Optimized Pager initialization** that uses the correct `initialPage` on first render instead of initializing to 0 and then scrolling
- **Synchronized PageTurnCoordinator** that reads from the central state instead of maintaining its own state
- **Removal of timing-dependent LaunchedEffects** that cause the flicker by scrolling after Pager is already rendered

## Capabilities

### New Capabilities
- `stable-page-turn`: Core page-turn mechanism with single state source, atomic updates, and pre-rendered content. Eliminates flicker on chapter transitions and provides foundation for reliable page navigation.

### Modified Capabilities
- `reader-chapter-navigation`: Current chapter loading and caching. Modified to support atomic state updates and pre-computed page counts for Pager initialization.

## Impact

- **Modified files**:
  - `presentation/reader/ReaderChapterController.kt` (add ReaderPageTurnState, modify loadChapter)
  - `ui/reader/renderer/CanvasRenderer.kt` (use central state for Pager initialization)
  - `ui/reader/renderer/PageTurnCoordinator.kt` (read from central state)
  - `ui/reader/ReaderScreen.kt` (pass central state to CanvasRenderer)

- **New files**:
  - `presentation/reader/ReaderPageTurnState.kt` (central state object)

- **Breaking changes**: None for public APIs, but internal state management changes significantly

- **Dependencies**: No new external dependencies

- **Testing**: Requires manual testing of page transitions (forward, backward, jump) and automated tests for state consistency
