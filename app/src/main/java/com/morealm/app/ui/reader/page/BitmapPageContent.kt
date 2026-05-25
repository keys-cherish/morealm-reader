package com.morealm.app.ui.reader.page

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **P3-3b**：[PageBitmapProvider] 的 Compose 适配组件 —— 把「拿到第 N 页 Bitmap」
 * 的契约落到 UI 层渲染。
 *
 * 异步从 [provider] 拉取第 [pageIndex] 页 Bitmap 并渲染。bitmap recycle 由本组件
 * 兜底（pageIndex / 尺寸 / provider 任一变化 → 旧 bitmap 在 effect cancel 时
 * recycle，避免 ARGB_8888 大图堆积触发 GC 抖动）。
 *
 * ── 职责边界（**严格**） ──
 *  - 只负责「拿 Bitmap → 显示 → recycle」三步
 *  - 不懂 chapter / 翻页 / 选区 / 高亮 / pager state —— 那是 4 Pager +
 *    [com.morealm.app.ui.reader.page.animation.AnimatedPageReader] 的职责
 *  - 越界 / 加载中 / 失败 → [PageBitmapProvider.bitmapAt] 返回 null → 本组件画
 *    透明占位（什么都不画），由外层 Pager 自己叠 loading / cached last frame
 *
 * ── recycle 正确性论证 ──
 *
 * [produceState] 的 keys = (provider, pageIndex, w, h)。任一变更：
 *   1. 旧协程 cancel → awaitDispose 的 onDispose lambda 触发
 *   2. lambda 闭包里的 `loaded` 是**本次** coroutine 的局部变量，唯一引用本次
 *      load 出来的 bitmap
 *   3. → recycle 的一定是当前帧，不会误伤后续 produceState 协程新 load 的 bitmap
 *
 * 这是用 `LaunchedEffect + DisposableEffect` 两个独立 effect 难做对的地方
 * （两个 effect 的 onDispose 触发顺序不保证，state 跨 effect 共享会撞 race）。
 *
 * ── 当前阶段（P3-3b）：仅声明组件，4 Pager 都不调用 ──
 *
 * P3-3c 会让 4 Pager 在 `bitmapProvider != null` 时改走 BitmapPageContent，
 * 否则保持现 `pageContent: @Composable (Int) -> Unit` 兜底。这样新老路径共存，
 * 出问题立刻 flag 切回老路径。
 */
@Composable
fun BitmapPageContent(
    provider: PageBitmapProvider,
    pageIndex: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // 0 尺寸 / Constraints.Infinity 都不分配 bitmap（HorizontalPager 给的 slot
        // 一定有限，理论上不进这条路径；保险起见早退避免 OOM）
        if (w <= 0 || h <= 0) return@BoxWithConstraints

        val bitmap by produceState<Bitmap?>(null, provider, pageIndex, w, h) {
            val loaded = withContext(Dispatchers.IO) {
                provider.bitmapAt(pageIndex, w, h)
            }
            value = loaded
            awaitDispose {
                loaded?.takeUnless { it.isRecycled }?.recycle()
            }
        }

        bitmap?.takeUnless { it.isRecycled }?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // provider 已按 w×h 精确像素 render，None 不缩放避免双重采样模糊
                contentScale = ContentScale.None,
            )
        }
    }
}
