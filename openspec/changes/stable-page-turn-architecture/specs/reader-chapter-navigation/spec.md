## MODIFIED Requirements

### Requirement: Chapter loading and caching
The system SHALL load chapters and maintain a three-chapter cache (previous, current, next). When loading a new chapter, the system SHALL rotate the cache atomically, validate layout parameters, pre-compute the page count, and set the navigation direction. All these updates SHALL be emitted together in a single ReaderPageTurnState update.

#### Scenario: Load chapter with atomic cache rotation
- **WHEN** loadChapter(index) is called
- **THEN** the system atomically: rotates the cache, validates layout parameters, computes the page count, sets navigationDirection, and emits all changes via ReaderPageTurnState in a single update

#### Scenario: Validate rotated cache layout parameters
- **WHEN** cache rotation occurs
- **THEN** the system checks if the rotated chapter's viewWidth and viewHeight match the current layout parameters. If not, the rotated chapter is cleared to force re-layout.

#### Scenario: Pre-compute page count for Pager initialization
- **WHEN** cache rotation occurs
- **THEN** the system computes the page count of the new chapter using prelayout cache or rotated chapter's cached layout, and includes it in ReaderPageTurnState

#### Scenario: Preload adjacent chapters
- **WHEN** a chapter is loaded
- **THEN** the system preloads the next and previous chapters asynchronously and updates the cache when ready

#### Scenario: Handle preload completion
- **WHEN** a preloaded chapter's layout completes
- **THEN** the system updates the corresponding cache entry (prevTextChapter, nextTextChapter) and updates renderPageCount if needed

### Requirement: Navigation direction tracking
The system SHALL track the direction of chapter navigation (forward, backward, or jump) and include it in the page-turn state. This direction SHALL be used by CanvasRenderer to determine the correct Pager initialization page.

#### Scenario: Forward navigation sets direction
- **WHEN** user navigates to the next chapter (index == prevIndex + 1)
- **THEN** navigationDirection is set to 1 (forward)

#### Scenario: Backward navigation sets direction
- **WHEN** user navigates to the previous chapter (index == prevIndex - 1)
- **THEN** navigationDirection is set to -1 (backward)

#### Scenario: Jump navigation sets direction
- **WHEN** user navigates to a non-adjacent chapter
- **THEN** navigationDirection is set to 0 (none/jump)
