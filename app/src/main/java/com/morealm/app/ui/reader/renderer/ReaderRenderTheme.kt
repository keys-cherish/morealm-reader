package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 阅读器渲染层主题 —— 给 [LazyScrollRenderer] 等新组件按需取用 paint / 颜色 / 背景图，
 * 替代 [ScrollRenderer] / [SimulationDelegate] 的「属性传递地狱」（手把手层层传
 * titlePaint/contentPaint/chapterNumPaint/bgArgb/bgBitmap 5 件套）。
 *
 * ── 现代实践对照 ──
 *
 * 1. **CompositionLocal**：本主题字段变化时，只有真正读取的子组件局部重组；
 *    背景图换张图片，文字段落不重组（文字段不读 [bgBitmap]）；切字号则文字段重组，
 *    但 LazyColumn 视口外的段不组合，开销可控。
 *
 * 2. **@Immutable**：Compose stability 推断标记 —— Compose 编译器据此跳过子组件
 *    不必要的等性判断，进一步降低重组评估开销。
 *
 * 3. **staticCompositionLocalOf**（而非 compositionLocalOf）：静态版本读取 0 开销
 *    （编译期内联），但任何字段变化全树重组。本主题适合 static —— 字段都是「整章
 *    一致、罕见变化」的配置；用户切字号触发的重组本来就是预期行为。
 *
 * ── 迁移现状 ──
 *
 * 仅服务于 [LazyScrollRenderer] 等新组件。现有 ScrollRenderer / SimulationDelegate
 * 仍走传参方式，不强迫一次性迁移（避免大爆破回归风险）。后续视稳定情况，把这套
 * CompositionLocal 推到全部渲染组件作为独立优化任务。
 *
 * @param titlePaint 标题行 paint（line.isTitle = true）
 * @param contentPaint 正文 paint（其他所有 line）
 * @param chapterNumPaint 章号大字体 paint（line.isChapterNum = true）。null 表示禁用大字章号
 * @param bgArgb 阅读区背景颜色（无背景图时填这个色，有背景图时盖在图上方）
 * @param bgBitmap 阅读区背景图。null 表示纯色背景
 * @param selectionColor 选区高亮色（半透明叠加色）
 * @param aloudColor TTS 朗读高亮色
 * @param searchResultColor 搜索结果高亮色
 * @param bookmarkColor 书签三角图标色
 */
@Immutable
data class ReaderRenderTheme(
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    val chapterNumPaint: TextPaint?,
    val bgArgb: Int,
    val bgBitmap: Bitmap?,
    val selectionColor: Color = DEFAULT_SELECTION_COLOR,
    val aloudColor: Color = DEFAULT_ALOUD_COLOR,
    val searchResultColor: Color = DEFAULT_SEARCH_RESULT_COLOR,
    val bookmarkColor: Color = DEFAULT_BOOKMARK_COLOR,
)

/**
 * 默认值用 `error { ... }` 而非提供 null safe 默认 —— 强制调用方必须 provide，
 * 漏配置时在第一次 [androidx.compose.runtime.CompositionLocal.current] 访问就 fail-fast，
 * 避免文字「莫名渲染不出」之类的悄悄错。
 */
val LocalReaderRenderTheme = staticCompositionLocalOf<ReaderRenderTheme> {
    error(
        "ReaderRenderTheme not provided. Wrap LazyScrollRenderer in " +
            "CompositionLocalProvider(LocalReaderRenderTheme provides theme) { ... }",
    )
}
