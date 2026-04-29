# MoRealm Project Memory

## Key File Paths
- Database module: `di/AppModule.kt` (migrations + auto-backup)
- Database definition: `domain/db/AppDatabase.kt` (version 15)
- Book source rule engine: `domain/analyzeRule/AnalyzeRule.kt`
- Web book: `domain/webbook/WebBook.kt`, `BookList.kt`, `BookContent.kt`
- TTS: `service/TtsService.kt`, `TtsPlayer.kt`, `TtsEventBus.kt`
- TTS notification: `service/TtsNotificationProvider.kt` (custom MediaStyle notification)
- TTS control: `presentation/reader/ReaderTtsController.kt`
- Theme system: `ui/theme/MoRealmTheme.kt`, `domain/entity/BuiltinThemes.kt`
- Reader: `ui/reader/ReaderScreen.kt`, `ReaderComponents.kt`
- Reader rendering: `ui/reader/renderer/CanvasRenderer.kt`
- Scroll rendering: `ui/reader/renderer/ScrollRenderer.kt`
- Simulation page flip: `ui/reader/renderer/SimulationReadView.kt`, `SimulationDrawHelper.kt`
- Page content drawing: `ui/reader/renderer/PageContentDrawer.kt`
- Reading style: `domain/entity/ReaderStyle.kt` (5 presets)
- Reading settings: `ui/settings/ReadingSettingsScreen.kt`
- Reader settings control: `presentation/reader/ReaderSettingsController.kt`
- Preferences: `domain/preference/AppPreferences.kt`
- Navigation: `ui/navigation/AppNavHost.kt`, `PillNavigationBar.kt`
- Theme entity: `domain/entity/ThemeEntity.kt`
- Legado source: `D:/temp_build/sigma/legado/`

## Reader Chapter Caching (Three-Chapter Pre-layout)
- `presentation/reader/ReaderChapterController.kt`: _curTextChapter/_prevTextChapter/_nextTextChapter StateFlows, cache rotation, relayoutAllChapters()
- `ui/reader/renderer/CanvasRenderer.kt`: LaunchedEffect with VM cache priority, prev/next TextChapter resolution
- `domain/entity/LayoutParams.kt`: data class bridging UI layout config to ViewModel
- `domain/entity/PageLayout.kt`: TextChapter class (added viewWidth/viewHeight)
- `domain/webbook/ChapterProvider.kt`: layoutChapter/layoutChapterAsync (passes viewWidth/viewHeight)

## Architecture Notes
- fallbackToDestructiveMigrationFrom() cannot overlap with existing migration start versions (Room crashes)
- DB upgrade auto-backup in AppModule.provideDatabase()
- Unit tests: 82 passing (Robolectric)
- pre-push hook runs full unit tests, pre-commit runs quick tests only
- ScrollRenderer uses saveLayer + DstOut for text feathering; background drawn outside saveLayer
- Simulation page flip back color controlled by bgMeanColor (background image mean color or bgArgb)
- PageInfoOverlaySpec.hasBgImage controls whether info bar draws gradient background
- TTS notification uses custom TtsNotificationProvider (MediaNotification.Provider)
- TtsPlayer handles prev/next chapter via handleSeek

## Current State (2024-01 session)
- **Branch:** 1.0alpha2 (created from main)
- **Focus:** Legado-style three-chapter TextChapter pre-layout in ViewModel (ReaderChapterController)
- **New files:** LayoutParams.kt
- **Modified files:** ChapterProvider.kt, PageLayout.kt, ReaderChapterController.kt, CanvasRenderer.kt, ReaderScreen.kt
- **Architecture:** UI pushes LayoutParams to ViewModel -> ViewModel pre-layouts chapters -> CanvasRenderer uses VM cache with priority: VM cache -> local prelayout -> async layout
- **Cache rotation:** On chapter navigation (cur->prev, next->cur like Legado)

### Known Issue: Chapter Transition Flash/Flicker
- Still experiencing flash/flicker on chapter transitions, especially:
  - First page of new chapter when navigating forward
  - Last page when navigating backward (briefly shows first page of that chapter)
- The flash suggests CanvasRenderer's page rendering pipeline still has timing gaps where placeholder or wrong page is shown before the correct cached page is applied
- **Next step:** Investigate Legado/Sigma source code for their exact page flip + cache mechanism

## Legado Comparison
- Report: `temp/legado-comparison.md`
- MoRealm advantages: Compose+M3 UI, Canvas recording cache, Edge TTS, auto-grouping, regex safety
- Gaps to fill: auto-check new chapters (P1), search history (P2), search scope filter (P3), backup encryption (P4)

## Completed Work
- Theme switch animation fix (TopAppBar transparent)
- Pill nav bar bottom occlusion fix (ProfileScreen 96dp, ShelfScreen 88dp)
- README cleanup + docs cleanup + usage guide
- Book source engine error fixes (evalJS try-catch, safe type conversion, JS wrapper fallback)
- TTS notification upgrade v1 (book name+chapter+cover+4 buttons, Media3 default)
- TTS notification upgrade v2 (custom MediaNotification.Provider, Legado style)
- P0 database data loss fix (removed destructive downgrade, auto-backup+restore)
- 3 HTML promo pages
- Day/night reader background image settings (ReadingSettingsScreen + Coil thumbnails)
- Scroll mode alpha feathering + transparent info bar with background image
- ScrollRenderer background layer moved outside saveLayer (DstOut only erases text)
- Simulation page flip black frame fix (PageInfoOverlaySpec.hasBgImage skips gradient)
- Floating day/night toggle button in reader (bottom-right corner)
