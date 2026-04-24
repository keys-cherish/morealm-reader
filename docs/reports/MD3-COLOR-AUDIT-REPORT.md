# MD3 Compliance Audit Report — Color Focus

Target: D:/temp_build/MoRealm
Date: 2026-04-23
Overall Score: 38/100

## Scores by Category

| Category | Score | Status |
|----------|-------|--------|
| Color tokens | 3/10 | fail |
| Typography | 7/10 | pass |
| Shape | 6/10 | warn |
| Elevation | 5/10 | warn |
| Components | 6/10 | warn |
| Layout | 7/10 | pass |
| Navigation | 7/10 | pass |
| Motion | 5/10 | warn |
| Accessibility | 2/10 | fail |
| Theming | 3/10 | fail |

## Critical Issues

### 1. 双色系统冲突：`MoRealmColors` 与 `MaterialTheme.colorScheme` 并行使用 (Color tokens: 3/10)

项目同时维护两套颜色系统：
- `MoRealmColors`（自定义 CompositionLocal）：`accent`, `readerBackground`, `readerText`, `bottomBar`, `surfaceGlass`
- `MaterialTheme.colorScheme`（MD3 标准）：`primary`, `onSurface`, `background`, `error` 等

**问题：** UI 代码随意混用两套系统，没有一致的规则决定何时用哪套。

证据：
- `ShelfComponents.kt` 中 `moColors.accent` 用于进度条、图标、选中态，而 `MaterialTheme.colorScheme.onSurface` 用于文字 — 同一组件内混用
- `BookSourceManageScreen.kt:324` 用 `moColors.surfaceGlass` 做启用态背景，`:325` 用 `MaterialTheme.colorScheme.surfaceVariant` 做禁用态背景 — 两个不同系统的 surface 角色混搭
- `ReaderComponents.kt:788,854,993` 用 `moColors.bottomBar` 做 Surface 颜色，但其上的文字/图标用 `MaterialTheme.colorScheme.onSurface` — 这对 `on*` 颜色不保证与 `moColors.bottomBar` 有足够对比度

**修复方向：** 选择一套系统作为 single source of truth。如果保留 `MoRealmColors`，则不应再直接引用 `MaterialTheme.colorScheme` 的颜色角色；或者将 `MoRealmColors` 的角色全部映射到 `MaterialTheme.colorScheme` 的对应 slot 中。

### 2. 硬编码颜色绕过主题系统 (Color tokens: 3/10)

24 处 `Color(0x...)` 硬编码散布在 UI 层，完全绕过了主题系统：

| 文件 | 行号 | 硬编码值 | 问题 |
|------|------|----------|------|
| `BookSourceManageScreen.kt` | 355 | `Color(0xFF4CAF50)` | 绿色评分 — 应使用语义色 |
| `BookSourceManageScreen.kt` | 356 | `Color(0xFFFF9800)` | 橙色评分 — 应使用语义色 |
| `BookSourceEditScreen.kt` | 418 | `Color(0xFF4CAF50)` | 同上，重复硬编码 |
| `AppLogScreen.kt` | 67-69 | `Color(0xFFD32F2F)`, `Color(0xFFEF5350)`, `Color(0xFFFFA726)` | 日志级别颜色 — 应使用 `error`/`errorContainer` |
| `AppLogScreen.kt` | 104 | `Color(0xFFEF5350)` | 重复硬编码红色 |
| `ScrollRenderer.kt` | 28-31 | 4 个硬编码颜色 | 选区/朗读/搜索高亮色 |
| `PageCanvas.kt` | 26-29 | 4 个硬编码颜色 | 同上，重复定义 |
| `TextSelection.kt` | 143 | `Color(0xF0212121)` | 工具栏背景 — 应使用 `inverseSurface` |
| `TextSelection.kt` | 181 | `Color(0xFF2196F3)` | 光标颜色 — 应使用 `primary` |

**修复方向：** 将语义颜色提取到 `MoRealmColors` 或 `MaterialTheme.colorScheme` 的扩展中。日志级别颜色可以用 `error`/`tertiary` 等 MD3 角色替代。

### 3. `onPrimary` 硬编码导致浅色主题对比度不足 (Accessibility: 2/10)

`MoRealmTheme.kt:86`:
```kotlin
val onPrimary = if (t.isNightTheme) Color(0xFF1A1A2E) else Color.White
```

这个 `onPrimary` 对所有浅色主题都是白色，但：
- **纸上主题** `primaryColor = "#FF92400E"`（深棕色）+ `onPrimary = White` → 对比度 OK
- **墨水屏主题** `primaryColor = "#FF333333"`（深灰）+ `onPrimary = White` → 对比度 OK
- 但如果用户导入一个浅色 primary 的主题，`onPrimary = White` 就会失败

同样，`onSecondary = onPrimary` 和 `onTertiary = onPrimary` 也是硬编码的，没有根据实际 primary/secondary/tertiary 的亮度动态计算。

### 4. `outline` 和 `outlineVariant` 通过 alpha 衍生，对比度不可控 (Accessibility: 2/10)

`MoRealmTheme.kt:91-92`:
```kotlin
val outline = onBg.copy(alpha = 0.3f)
val outlineVariant = onBg.copy(alpha = 0.15f)
```

MD3 规范要求 `outline` 用于重要边界（如 TextField 边框），需要 3:1 对比度。`onBg.copy(alpha = 0.3f)` 在深色主题下（`onBg ≈ #EDEDEF`）实际渲染为约 `rgba(237,237,239,0.3)` — 在 `#0A0A0F` 背景上的有效对比度约 2.8:1，低于 3:1 要求。

`outlineVariant` 的 0.15 alpha 更低，在某些主题下几乎不可见。

### 5. `MoRealmColors.accent` 承担了过多 MD3 角色 (Color tokens: 3/10)

`moColors.accent` 被用于：
- 导航栏选中态（应为 `primary`）— `AppNavHost.kt:83-85`
- 进度条颜色（应为 `primary`）— `ShelfComponents.kt:132-133`
- 输入框焦点边框（应为 `primary`）— `ShelfScreen.kt:528`, `SearchScreen.kt:113`
- Switch 选中轨道（应为 `primary`）— `ReadingSettingsScreen.kt:267`
- FilterChip 选中态（应为 `secondaryContainer`）— `ReaderComponents.kt:1055`
- 文字强调色（应为 `primary` 或 `secondary`）— 多处

一个 `accent` 颜色替代了 MD3 的 `primary`、`secondary`、`primaryContainer`、`secondaryContainer` 等多个角色，导致：
- 无法区分不同层级的强调
- 所有交互元素视觉权重相同
- 切换主题时无法独立调整不同角色

### 6. 缺失大量 MD3 颜色角色 (Theming: 3/10)

`MoRealmTheme.kt` 的 `darkColorScheme()` / `lightColorScheme()` 调用中缺失以下角色：

| 缺失角色 | 影响 |
|----------|------|
| `tertiary` | 没有第三强调色，所有强调都是同一个 accent |
| `primaryContainer` / `onPrimaryContainer` | FAB、卡片等容器组件使用默认紫色 |
| `secondaryContainer` / `onSecondaryContainer` | FilterChip、Tonal Button 使用默认色 |
| `tertiaryContainer` / `onTertiaryContainer` | 完全缺失 |
| `surfaceContainerLow` / `surfaceContainerLowest` | 缺少低层级 surface |
| `surfaceDim` / `surfaceBright` | 缺失 |
| `scrim` | 遮罩层用硬编码 `Color.Black.copy(alpha = 0.4f)` 代替 |
| `error` / `onError` / `errorContainer` / `onErrorContainer` | 未覆盖，使用 MD3 默认值 |

这意味着任何使用这些角色的 MD3 组件（如 `AlertDialog`、`FloatingActionButton`、`FilterChip`）都会显示 MD3 默认的紫色基线色，与自定义主题不协调。

## Warnings

### 7. `Color.White` / `Color.Black` 直接使用 (Shape: 6/10)

- `TextSelection.kt:170-171` — 工具栏图标/文字用 `Color.White`，不适应浅色主题
- `ReaderComponents.kt:1287` — 图片查看器背景用 `Color.Black`，合理但应考虑用 `scrim`
- `ProfileScreen.kt:651` — 主题预览文字用 `Color.Black/White` 判断，逻辑正确但脆弱

### 8. `surfaceGlass` 的 alpha 值过低 (Elevation: 5/10)

`MoRealmTheme.kt:81`:
```kotlin
if (t.isNightTheme) Color(0x0FFFFFFF) else Color(0x0F000000)
```

`0x0F` = alpha 约 6%。这个值作为卡片/容器背景几乎不可见，在深色主题下 `#0A0A0F` 上叠加 6% 白色 ≈ `#111118`。虽然视觉上可能是有意为之（毛玻璃效果），但：
- 没有使用 MD3 的 `surfaceContainer` 层级系统
- 在浅色主题下 6% 黑色几乎看不出来
- 不同主题下的可见度差异很大

### 9. 日志颜色与 MD3 error 系统不一致 (Components: 6/10)

`AppLogScreen.kt:67-70`:
```kotlin
LogLevel.FATAL -> Color(0xFFD32F2F)  // Material Red 700
LogLevel.ERROR -> Color(0xFFEF5350)  // Material Red 400
LogLevel.WARN  -> Color(0xFFFFA726)  // Material Orange 400
LogLevel.INFO  -> moColors.accent
```

FATAL/ERROR/WARN 使用 Material Design 2 的调色板颜色，而 INFO 使用自定义 accent。应统一使用 MD3 的 `error`/`errorContainer`/`tertiary` 角色。

### 10. 分隔线使用 `onSurface.copy(alpha = 0.06f)` 而非 `outlineVariant` (Components: 6/10)

多处分隔线：
- `ReadingSettingsScreen.kt:348,385` — `HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))`
- `ShelfScreen.kt:586,693,707` — 同上

MD3 规范要求分隔线使用 `outlineVariant`，而非手动降低 `onSurface` 的 alpha。虽然视觉效果可能相似，但绕过了主题系统。

## Passing

### Typography — 7/10 (pass)
正确使用 `MaterialTheme.typography` 的 type scale：`titleSmall`、`bodyMedium`、`bodySmall`、`labelSmall`、`labelMedium` 等。没有发现硬编码字体大小（除 `AppLogScreen` 的 `10.sp` 和 `12.sp` 用于等宽日志显示，合理）。

### Layout — 7/10 (pass)
使用 `WindowSizeClass` 响应式调整列数（`AppNavHost.kt:99-103`：Expanded=5列, Medium=4列, Compact=3列）。`Scaffold` + `NavHost` 结构正确。

### Navigation — 7/10 (pass)
`NavigationBar` + `HorizontalPager` 实现底部导航，`NavigationBarItem` 使用正确。全屏页面（reader、settings）正确隐藏底部栏。`derivedStateOf` 同步 pager 与 tab 选中态。

## Recommended Fixes (Priority Order)

### 1. 统一颜色系统：将 `MoRealmColors` 角色映射到 `MaterialTheme.colorScheme`

在 `MoRealmTheme.kt` 中补全所有 MD3 角色：

```kotlin
// 从 accent 派生 primary 系列
val primaryContainer = accent.mix(surface, 0.85f)
val onPrimaryContainer = accent.mix(onBg, 0.3f)

// 补全 tertiary（可从 accent 色相偏移 60° 生成，或使用 material-color-utilities）
val tertiary = /* 色相偏移后的颜色 */

// 补全 error 系列（覆盖默认值以匹配主题色调）
val errorColor = Color(0xFFFF5449) // 或从主题配置读取
```

然后在 UI 代码中统一使用 `MaterialTheme.colorScheme.*`，将 `moColors.accent` 的使用逐步替换为 `MaterialTheme.colorScheme.primary`。

**影响：** Color tokens +4, Theming +4, Accessibility +2

### 2. 消除硬编码颜色

将 24 处 `Color(0x...)` 替换为语义角色：

```kotlin
// Before
Color(0xFF4CAF50) // 绿色评分
Color(0xFFFF9800) // 橙色评分

// After — 定义语义扩展
val ColorScheme.scoreGood get() = tertiary        // 或自定义 CompositionLocal
val ColorScheme.scoreWarn get() = error.copy(alpha = 0.7f)
```

对于 `TextSelection.kt` 的工具栏：
```kotlin
// Before
color = Color(0xF0212121)
// After
color = MaterialTheme.colorScheme.inverseSurface
```

**影响：** Color tokens +2, Accessibility +1

### 3. 动态计算 `onPrimary` 对比度

```kotlin
// Before
val onPrimary = if (t.isNightTheme) Color(0xFF1A1A2E) else Color.White

// After — 根据 primary 亮度动态选择
val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
```

同样处理 `onSecondary`、`onTertiary`。

**影响：** Accessibility +3

### 4. 修复 `outline` 对比度

```kotlin
// Before
val outline = onBg.copy(alpha = 0.3f)

// After — 确保至少 3:1 对比度
val outline = if (t.isNightTheme) onBg.copy(alpha = 0.45f) else onBg.copy(alpha = 0.38f)
val outlineVariant = if (t.isNightTheme) onBg.copy(alpha = 0.2f) else onBg.copy(alpha = 0.15f)
```

或者更好的方式：使用 `material-color-utilities` 库从 seed color 生成完整的 tonal palette，自动保证对比度。

**影响：** Accessibility +2

### 5. 分隔线使用 `outlineVariant`

全局替换：
```kotlin
// Before
HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

// After
HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
```

**影响：** Components +1
