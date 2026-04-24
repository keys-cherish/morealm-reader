# Jetpack Compose Audit Report

Target: D:/temp_build/MoRealm
Date: 2026-04-22
Scope: `app` module — all Compose UI under `app/src/main/java/com/morealm/app/ui/`
Excluded from scoring: No test/sample/preview-only paths found
Confidence: High
Overall Score: 62/100

## Scorecard

| Category | Score | Weight | Status | Notes |
|----------|-------|--------|--------|-------|
| Performance | 6/10 | 35% | needs work | SSM on, 98.8% named skippable, but `collectAsState` everywhere + missing `contentType` |
| State management | 6/10 | 25% | needs work | ViewModel pattern solid, but `collectAsState` instead of lifecycle-aware, sparse `rememberSaveable` |
| Side effects | 7/10 | 20% | solid | Good `DisposableEffect` cleanup, proper `rememberUpdatedState`, minor issues |
| Composable API quality | 6/10 | 20% | needs work | Modifier conventions mostly correct, but no `@Preview`, hardcoded strings, giant screens |

## Critical Findings

1. **State: `collectAsState()` used everywhere instead of `collectAsStateWithLifecycle()`**
   - Why it matters: Flows keep collecting when the app is backgrounded, wasting resources and potentially causing crashes on stale UI updates. This is the single most impactful pattern to fix.
   - Evidence: `ReaderScreen.kt:61-104` (44 `collectAsState` calls), `ShelfScreen.kt:51-63`, `SearchScreen.kt:40-45`, `BookSourceManageScreen.kt:41-47`, `ProfileScreen.kt:52-57`, `BookDetailScreen.kt:39-42`
   - Fix direction: Replace all `collectAsState()` with `collectAsStateWithLifecycle()` from `lifecycle-runtime-compose` (already in dependencies as `libs.lifecycle.runtime`).
   - References: <https://developer.android.com/develop/ui/compose/state>

2. **Performance: No `contentType` on heterogeneous lazy lists**
   - Why it matters: Without `contentType`, Compose cannot reuse compositions between items of different types (folders vs books), causing unnecessary recomposition and layout work during scrolling.
   - Evidence: `ShelfScreen.kt:342-376` (grid mixes `FolderCard` + `BookGridItem` without `contentType`), `ShelfScreen.kt:317-339` (list mixes `FolderListItem` + `BookListItem`)
   - Fix direction: Add `contentType = { if (it is folder) "folder" else "book" }` or use separate `items` blocks with explicit `contentType`.
   - References: <https://developer.android.com/develop/ui/compose/lists>

3. **Performance: `ReaderScreen` collects 44+ StateFlows in a single composable body**
   - Why it matters: Every flow emission triggers recomposition of the entire 600-line `ReaderScreen` composable. Even with Strong Skipping, the sheer number of state reads at the top level means the composition body runs frequently.
   - Evidence: `ReaderScreen.kt:61-104` — 44 individual `collectAsState()` calls at the top of one function
   - Fix direction: Group related state into sealed/data classes (e.g. `ReaderUiState`, `TtsState`, `StyleState`) exposed as fewer flows, or split `ReaderScreen` into smaller composables that each observe only the state they need.
   - References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>

## Category Details

### Performance — 6/10 (needs work)

**Compiler report summary (Strong Skipping ON, Kotlin 2.1.0):**

- Module-wide: 412/658 skippable = 62.6%
- Named composables only: 84/85 skippable = 98.8%
- 1 non-skippable named composable: `rememberBatteryLevel` (non-restartable utility, expected)
- 137 unstable classes (mostly domain-layer: `AnalyzeRule`, `BookSource`, `AppLog`, etc.)
- Unstable params reaching composables: `List<BookChapter>`, `List<Bookmark>`, `List<Book>`, `List<SearchResult>`, `Typeface`, ViewModels (expected with Hilt)

**Performance ceiling check:**
```
Strong Skipping: ON (Kotlin 2.1.0, Compose Compiler plugin) → applying SSM-on table
named-only skippable% = 98.8% → no cap from skippable%
Binding constraint: instance-recreation churn + broad state reads
  - ReaderScreen.kt:61-104 reads 44 StateFlows in one body → frequent full-body recomposition
  - ShelfScreen.kt:342-376 missing contentType on heterogeneous lazy grid
  - listOf(...) used in composition bodies (ReaderComponents.kt, ProfileScreen.kt) for static option lists — low churn risk
qualitative score: 6
ceiling from SSM-on table: 7 (churn present but not pathological)
applied score: 6 (qualitative below ceiling, no adjustment needed)
```

**Positive evidence:**
- All `items()` / `itemsIndexed()` calls use `key = { ... }` — `ShelfScreen.kt:317`, `ShelfScreen.kt:342`, `ScrollRenderer.kt:38`, `BookSourceManageScreen.kt:235`
- `graphicsLayer { }` lambda modifier used for deferred state reads in animations — `PageAnimations.kt:150`
- `Animatable` correctly held in `remember` — `PageAnimations.kt:225`
- `remember(sources, searchQuery)` for filtered list caching — `BookSourceManageScreen.kt:62`
- `derivedStateOf` for tab selection — `AppNavHost.kt:61`

**Deductions:**
- (-2) `collectAsState()` everywhere instead of `collectAsStateWithLifecycle()` — all 6 screens. Under SSM-on this doesn't affect skippability but wastes resources when backgrounded and risks stale updates. References: <https://developer.android.com/develop/ui/compose/state>
- (-1) No `contentType` on heterogeneous lazy lists — `ShelfScreen.kt:317-376`. References: <https://developer.android.com/develop/ui/compose/lists>
- (-1) 44 state reads at top of `ReaderScreen` — broad recomposition scope. References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>

### State Management — 6/10 (needs work)

**Positive evidence:**
- ViewModel + StateFlow 模式统一且一致 — 所有 screen 都通过 `hiltViewModel()` 注入，状态通过 `StateFlow` 暴露
- `rememberSaveable` 用于需要跨配置变更保留的状态 — `ShelfScreen.kt:51` (`isListView`), `ShelfScreen.kt:52` (`currentFolderId`)
- `remember(key)` 正确使用依赖键 — `BookSourceManageScreen.kt:62`, `CanvasRenderer.kt:140,153`
- `SelectionState` 使用 `mutableStateOf` 实现响应式选择状态 — `TextSelection.kt`
- `MoRealmColors` 标注 `@Stable` — `MoRealmTheme.kt`

**Deductions:**
- (-2) 全局使用 `collectAsState()` 而非 `collectAsStateWithLifecycle()` — 6 个 screen 共约 70+ 处。Flow 在 app 进入后台时仍持续收集，浪费资源且可能导致崩溃。References: <https://developer.android.com/develop/ui/compose/state>
- (-1) `rememberSaveable` 使用稀少 — 仅 `ShelfScreen` 使用了 2 处，其他 screen 的 UI 状态（如 `searchQuery`、`showImportDialog`）在配置变更时丢失。`BookSourceManageScreen.kt:49-52` 的 `showImportDialog`、`importUrl`、`searchQuery` 都用 `remember` 而非 `rememberSaveable`。References: <https://developer.android.com/develop/ui/compose/state#restore>
- (-1) `ReaderScreen` 的 44 个独立 StateFlow 缺乏结构化分组 — 应合并为 `ReaderUiState`、`TtsState`、`StyleState` 等数据类。References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>

### Side Effects — 7/10 (solid)

**Positive evidence:**
- `DisposableEffect` 清理正确 — `CanvasRenderer.kt` 中 `rememberBatteryLevel` 注册/注销 BroadcastReceiver，`PageAnimations.kt:399` 清理 bitmap 资源
- `rememberUpdatedState` 用于稳定回调引用 — `ReaderComponents.kt:96-104` 对 9 个回调使用 `rememberUpdatedState`，避免 WebView 中的过时闭包捕获
- `LaunchedEffect` 键依赖正确 — `CanvasRenderer.kt:170` 使用多个键触发异步布局，`ReaderScreen.kt` 中 orientation/navigation 回调使用正确的键
- `DisposableEffect` 管理屏幕亮度和超时 — `ReaderScreen.kt` 中正确恢复系统设置
- `LaunchedEffect(importResult)` 正确响应一次性事件 — `BookSourceManageScreen.kt:55`

**Deductions:**
- (-1) `ReaderScreen` 中部分 `LaunchedEffect` 捕获了可能变化的值但未使用 `rememberUpdatedState` — 例如 TTS 相关的回调。风险较低因为 ViewModel 引用稳定，但模式不一致。References: <https://developer.android.com/develop/ui/compose/side-effects>
- (-1) Toast 显示使用 `LaunchedEffect(importResult)` + `Toast.makeText` — 可行但不够健壮，`importResult` 为 null 时的竞态条件需要注意。更好的方式是使用 `SnackbarHostState` 或 channel-based 事件。References: <https://developer.android.com/develop/ui/compose/side-effects>
- (-1) 缺少 `produceState` 的使用场景 — 部分可以用 `produceState` 简化的模式（如 `rememberBatteryLevel`）选择了手动 `DisposableEffect` + `mutableStateOf`，功能正确但略显冗长。References: <https://developer.android.com/develop/ui/compose/side-effects>

### Composable API Quality — 6/10 (needs work)

**Positive evidence:**
- 所有可复用组件都接受 `modifier: Modifier = Modifier` 参数且位置正确 — `ShelfComponents.kt` 中 `BookGridItem`、`BookListItem`、`FolderCard`、`FolderListItem`、`ContinueReadingCard`
- 组件职责清晰，分层合理 — `ShelfComponents.kt` 提供列表/网格项，`ShelfScreen.kt` 组合使用
- `animateColorAsState` 使用有意义的 `label` — `MoRealmTheme.kt`, `BookSourceManageScreen.kt:313`
- `SourceItem` 等组件参数设计合理，使用回调而非直接依赖 ViewModel

**Deductions:**
- (-2) 完全没有 `@Preview` 注解 — 85 个命名 composable 中 0 个有 Preview。这严重影响开发效率和组件可测试性。References: <https://developer.android.com/develop/ui/compose/tooling/previews>
- (-1) 硬编码中文字符串 — 所有 UI 文本直接写在代码中（如 `"书源管理"`、`"搜索书源"`、`"暂无书源"`），未使用 `stringResource()`。影响国际化和可维护性。References: <https://developer.android.com/develop/ui/compose/resources>
- (-1) 巨型 screen composable — `ReaderScreen.kt` 600+ 行、`ShelfScreen.kt` 750+ 行，单个 composable 函数过长，应拆分为更小的子组件。References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>

## Prioritized Fixes

按影响排序，前 3 项修复可将总分提升约 10-15 分：

### Fix 1: 将 `collectAsState()` 替换为 `collectAsStateWithLifecycle()`

- **文件:** `ReaderScreen.kt:61-104`, `ShelfScreen.kt:51-63`, `SearchScreen.kt:40-45`, `BookSourceManageScreen.kt:42-48`, `ProfileScreen.kt:52-57`, `BookDetailScreen.kt:39-42`
- **操作:** 全局替换 `.collectAsState()` → `.collectAsStateWithLifecycle()`，添加 `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
- **影响:** Performance +1, State +2。消除后台资源浪费，防止 stale UI 更新
- **参考:** <https://developer.android.com/develop/ui/compose/state>

### Fix 2: 为异构 LazyList/LazyGrid 添加 `contentType`

- **文件:** `ShelfScreen.kt:317-339` (LazyColumn), `ShelfScreen.kt:342-376` (LazyVerticalGrid)
- **操作:** 在 `items()` 调用中添加 `contentType = { item -> if (item is BookGroup) "folder" else "book" }`
- **影响:** Performance +0.5。改善滚动时的 composition 复用效率
- **参考:** <https://developer.android.com/develop/ui/compose/lists>

### Fix 3: 拆分 `ReaderScreen` 状态分组 + 组件拆分

- **文件:** `ReaderScreen.kt:61-104` (44 个 StateFlow), `ReaderViewModel.kt`
- **操作:** 在 ViewModel 中将相关状态合并为 `data class ReaderUiState(...)`, `data class TtsState(...)`, `data class StyleState(...)`，暴露 3-5 个 StateFlow 而非 44 个。将 `ReaderScreen` 拆分为 `ReaderContent`、`ReaderOverlay`、`ReaderSettings` 等子组件，每个只订阅所需状态
- **影响:** Performance +1, State +1, API Quality +0.5。减少 recomposition 范围，提升可维护性
- **参考:** <https://developer.android.com/develop/ui/compose/performance/bestpractices>

## Notes And Limits

- **Compiler diagnostics used:** Yes — via `compose-reports.init.gradle` init script, clean release build
- **Strong Skipping Mode:** ON (Kotlin 2.1.0, Compose Compiler plugin, confirmed in `app_release-module.json`)
- **Named-only skippable%:** 98.8% (84/85) — excellent
- **Module-wide skippable%:** 62.6% (412/658) — gap driven by zero-argument lambdas, not a real concern
- **Unstable classes:** 137 total, but overwhelmingly domain/infrastructure layer (`AnalyzeRule`, `BookSource`, `OkHttpUtils` etc.), not UI-layer composable params
- **Subagent unavailable:** `claude-haiku-4-5-20251001` returned 503; all exploration done manually
- **Single module:** Only `app` module present, no multi-module complexity

## Suggested Follow-Up

- 考虑运行 `material-3` audit — 项目使用自定义 `MoRealmColors` 主题系统，可能存在 Material 3 token 覆盖不完整的问题
- 添加 `@Preview` 注解后，可运行 Compose UI 测试审计
- 当项目规模增长时，考虑将 domain 层实体标注 `@Immutable` 或使用 stability configuration file 来改善编译器推断
