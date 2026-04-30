## ADDED Requirements

### Requirement: Single source of truth for page-turn state
The system SHALL maintain all page-turn related state (current chapter index, current page index, cached chapters, navigation direction, render page count) in a single `ReaderPageTurnState` object exposed as a StateFlow. All components that need page-turn state SHALL read from this single source.

#### Scenario: ViewModel exposes page-turn state
- **WHEN** CanvasRenderer collects the page-turn state StateFlow
- **THEN** it receives a complete `ReaderPageTurnState` object with all necessary information

#### Scenario: State consistency across components
- **WHEN** multiple components read from the page-turn state StateFlow
- **THEN** they all see the same state at the same time (no divergence)

### Requirement: Atomic cache rotation
The system SHALL perform cache rotation, page index update, and navigation direction update as a single atomic operation. When navigating to a new chapter, all related state changes SHALL be emitted together in one StateFlow update.

#### Scenario: Forward navigation cache rotation
- **WHEN** user navigates forward to the next chapter
- **THEN** the system atomically updates: prevTextChapter ← curTextChapter, curTextChapter ← nextTextChapter, navigationDirection = 1, and emits all changes together

#### Scenario: Backward navigation cache rotation
- **WHEN** user navigates backward to the previous chapter
- **THEN** the system atomically updates: nextTextChapter ← curTextChapter, curTextChapter ← prevTextChapter, navigationDirection = -1, and emits all changes together

#### Scenario: Jump navigation clears cache
- **WHEN** user jumps to a non-adjacent chapter
- **THEN** the system atomically clears all cached chapters (prevTextChapter = null, curTextChapter = null, nextTextChapter = null), sets navigationDirection = 0, and emits all changes together

### Requirement: Pre-computed page counts during cache rotation
The system SHALL compute and include the page count of the new chapter in the `ReaderPageTurnState` during cache rotation. This page count SHALL be derived from the prelayout cache if available, or from the rotated chapter's cached layout if available.

#### Scenario: Page count from prelayout cache
- **WHEN** cache rotation occurs and the new chapter is in the prelayout cache
- **THEN** the system uses the prelayout cache's page count as `renderPageCount` in the new state

#### Scenario: Page count from rotated chapter
- **WHEN** cache rotation occurs and the rotated chapter has a cached layout
- **THEN** the system uses the rotated chapter's page size as `renderPageCount` in the new state

#### Scenario: Page count fallback
- **WHEN** cache rotation occurs and neither prelayout cache nor rotated chapter has page count information
- **THEN** the system uses the current `renderPageCount` as fallback and updates it when layout completes

### Requirement: Correct Pager initialization on first render
The system SHALL initialize the Pager with the correct `initialPage` on the first render, based on the navigation direction and pre-computed page count. The Pager SHALL NOT initialize to page 0 and then scroll to the target page.

#### Scenario: Backward navigation initializes to last page
- **WHEN** user navigates backward and CanvasRenderer recomposes
- **THEN** the Pager initializes with `initialPage = renderPageCount - 1` (last page) on first render

#### Scenario: Forward navigation initializes to first page
- **WHEN** user navigates forward and CanvasRenderer recomposes
- **THEN** the Pager initializes with `initialPage = 0` (first page) on first render

#### Scenario: Jump navigation restores saved page index
- **WHEN** user jumps to a chapter and CanvasRenderer recomposes
- **THEN** the Pager initializes with `initialPage = savedPageIndex` on first render

### Requirement: No visible flicker on page transitions
The system SHALL NOT display visible flicker when transitioning between chapters. Specifically, the system SHALL NOT show the first page of a chapter when navigating backward to that chapter's last page.

#### Scenario: Backward navigation shows last page immediately
- **WHEN** user navigates backward to the previous chapter
- **THEN** the last page of the previous chapter is displayed immediately without showing the first page

#### Scenario: Forward navigation shows first page immediately
- **WHEN** user navigates forward to the next chapter
- **THEN** the first page of the next chapter is displayed immediately without showing the last page

### Requirement: PageTurnCoordinator reads from central state
The system SHALL modify PageTurnCoordinator to read page-turn state from the `ReaderPageTurnState` parameter instead of maintaining its own state. PageTurnCoordinator SHALL NOT have fields like `lastSettledDisplayPage`, `pendingSettledDirection`, etc.

#### Scenario: Coordinator receives state as parameter
- **WHEN** PageTurnCoordinator methods are called (turnPageByTap, turnPageByDrag, etc.)
- **THEN** they receive the current `ReaderPageTurnState` as a parameter

#### Scenario: Coordinator uses parameter state
- **WHEN** PageTurnCoordinator needs to know the current page index
- **THEN** it reads from the `ReaderPageTurnState` parameter, not from its own fields

### Requirement: Layout parameter validation during cache rotation
The system SHALL validate that rotated cached chapters have matching layout parameters (viewWidth, viewHeight) with the current layout. If layout parameters don't match, the rotated cache SHALL be cleared to force re-layout.

#### Scenario: Rotated cache has matching layout params
- **WHEN** cache rotation occurs and the rotated chapter has matching layout parameters
- **THEN** the rotated chapter is used as-is

#### Scenario: Rotated cache has mismatched layout params
- **WHEN** cache rotation occurs and the rotated chapter has different layout parameters
- **THEN** the rotated chapter is cleared and re-layout is triggered

## MODIFIED Requirements

### Requirement: Chapter loading and caching
The system SHALL load chapters and maintain a three-chapter cache (previous, current, next). When loading a new chapter, the system SHALL rotate the cache atomically and pre-compute the page count for Pager initialization.

#### Scenario: Load chapter with cache rotation
- **WHEN** loadChapter(index) is called
- **THEN** the system rotates the cache, computes the page count, and emits all changes atomically via ReaderPageTurnState

#### Scenario: Preload adjacent chapters
- **WHEN** a chapter is loaded
- **THEN** the system preloads the next and previous chapters asynchronously and updates the cache when ready

#### Scenario: Handle preload completion
- **WHEN** a preloaded chapter's layout completes
- **THEN** the system updates the corresponding cache entry (prevTextChapter, nextTextChapter) and updates renderPageCount if needed
