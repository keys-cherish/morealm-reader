package com.morealm.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * Floating pill-shaped bottom navigation bar.
 *
 * Design spec: docs/ui-design-spec.md（视觉升级版，2026-05-10）
 *
 * # 视觉
 *   - **半透明胶囊**：surface alpha = 0.62 + 顶部细高光 + 软阴影，模拟玻璃浮起
 *     （Compose 原生没有 backdrop-blur API，用 alpha + 高光 + shadow 三件套近似
 *     glassmorphism；TODO: API 31+ 可考虑 RenderEffect.createBlurEffect 真模糊）
 *   - **选中 icon glow**：[Modifier.drawBehind] 画一个软色 radial brush 圆，给紫色
 *     选中态在深色模式下加微微"发光"层次感
 *   - **滑动指示器**：单个 4dp 圆点在 tabs 之间平滑过渡（spring 动画），不是
 *     旧版每个 tab 自带一个 dot 闪现/消失——视觉焦点连贯
 *
 * # 交互
 *   - **点击弹性回弹**：interactionSource 监听 isPressed → spring scale 0.92 ↔ 1.0，
 *     给"按下去手感"，松手回弹无 overshoot 但带轻微弹性 (DampingRatioMediumBouncy)
 *   - **保留长按**：[onTabLongClick] 行为不变，combinedClickable 同时承接单击/长按
 *
 * # 与原版的兼容
 *   - 公开签名 100% 不动（tabs/selectedIndex/onTabClick/onTabLongClick/tabExtras）
 *   - 内部用 BoxWithConstraints 拿胶囊宽度算 tab center —— 调用方无感
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillNavigationBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    onTabLongClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    tabExtras: @Composable BoxScope.(tabIndex: Int) -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val accent = moColors.accent
    val unselected = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val pillShape = RoundedCornerShape(100.dp)
    // 半透明背景 —— 提到 0.88 alpha：足够"看到内容隐约透过"形成浮起感，但底层
    // 文字不会清晰可读（避免被用户报"看到背景文字"，见 2026-05-10 反馈）。
    // 真正的 backdrop-blur (Compose 没原生 API) 留 TODO；当前用高 alpha + 边缘
    // 高光 + 软阴影三件套近似玻璃感。
    val navBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    // 顶部细高光 —— 模拟玻璃边缘反射，深色模式尤为关键 (没高光的纯黑胶囊很死板)
    val highlightBorder = Color.White.copy(alpha = 0.07f)
    // ── 性能优化：预缓存 brush 颜色列表 ──
    // 之前 drawBehind 内 inline 构造 Color.copy + listOf + Brush.radialGradient
    // 每帧都重新分配 → animateDpAsState 期间 60fps 触发 360+ 临时对象/秒，
    // GC 抖动是用户报"加了 glow 后明显卡顿"的根因。
    // 改用 remember 把颜色列表缓存到外层 Composable scope，配合下面 drawWithCache
    // 也缓存 Brush 实例，drawScope 只剩纯绘制操作。
    val dotGlowColors = remember(accent) {
        listOf(accent.copy(alpha = 0.5f), accent.copy(alpha = 0.0f))
    }
    val iconGlowColors = remember(accent) {
        listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.0f))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val pillWidth = maxWidth
            val tabWidth = pillWidth / tabs.size.coerceAtLeast(1)

            // ── 胶囊背景层 ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // shadow 必须 BEFORE clip+background，否则会被裁出黑色矩形 corner 残影
                    .shadow(elevation = 18.dp, shape = pillShape, clip = false)
                    .background(navBg, pillShape)
                    .border(1.dp, highlightBorder, pillShape),
            )

            // ── 滑动指示器 dot ──
            // 单个 dot 在 tabs 之间 spring 过渡。容器 14dp 比真 dot (4dp) 大让 glow
            // 有 5dp 扩散空间——之前 4dp 容器 + glow radius=7dp 时 glow 被裁掉看不见。
            //
            // # 性能关键：state 读取必须下沉到 placement/render 阶段，而不是 composition
            //
            // 错误写法：
            //   val animatedDotX by animateDpAsState(...)  // 顶层 by 委托
            //   Modifier.offset(x = animatedDotX, y = ...)  // 顶层读 state
            // 后者每帧读 state → Compose 重新执行整个 PillNavigationBar 的 composition，
            // 60fps 期间触发 360+ 次重组（动画 600ms × 60fps）→ 卡顿。
            //
            // 正确写法（本次采用）：
            //   val animatedDotX = animateDpAsState(...)  // State<Dp>，不用 by
            //   Modifier.offset { IntOffset(animatedDotX.value.toPx().toInt(), ...) }
            // Modifier.offset { lambda } 接收 Density，state 读发生在 lambda 内 →
            // 只触发 placement 阶段（不是 measure/compose），无 GC 开销。
            // 见 https://developer.android.com/jetpack/compose/performance#defer-reads
            val dotSize = 4.dp
            val dotContainer = 14.dp
            val dotContainerYDp = ((64 - dotContainer.value).toInt() - 2).dp  // 贴近胶囊底部 2dp 留白
            val targetDotX = tabWidth * selectedIndex + tabWidth / 2 - dotContainer / 2
            val animatedDotX = animateDpAsState(
                targetValue = targetDotX,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "nav_dot_x",
            )
            // drawWithCache: brush 只在 size 变化时构造一次，offset 平移期间不重建。
            // 一次绘制画两层：底层 radial glow + 顶层实心 dot。
            Box(
                modifier = Modifier
                    .offset {
                        // 读 animatedDotX.value 在 lambda 内 → 只 invalidate placement，
                        // 不触发 PillNavigationBar 重组。Animatable 60fps 期间 0 次 GC。
                        IntOffset(
                            x = animatedDotX.value.toPx().toInt(),
                            y = dotContainerYDp.toPx().toInt(),
                        )
                    }
                    .size(dotContainer)
                    .drawWithCache {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val glowR = size.minDimension / 2f
                        val dotR = dotSize.toPx() / 2f
                        val brush = Brush.radialGradient(
                            colors = dotGlowColors,
                            center = Offset(cx, cy),
                            radius = glowR,
                        )
                        val center = Offset(cx, cy)
                        onDrawBehind {
                            drawCircle(brush = brush, radius = glowR, center = center)
                            drawCircle(color = accent, radius = dotR, center = center)
                        }
                    },
            )

            // ── Tabs Row ──
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val color by animateColorAsState(
                        targetValue = if (selected) accent else unselected,
                        animationSpec = tween(180),
                        label = "nav_color_$index",
                    )

                    // 弹性按压：每个 tab 独立 InteractionSource，监听 isPressed
                    // → 0.92 / 1.0 在 spring 间过渡。dampingRatio MediumBouncy 给一点点
                    // overshoot，松手时手指还能感知到"q 弹"反馈。
                    //
                    // # 性能：scale state 不用 `by` 委托
                    // 同 dot offset 的注释——`val scale by animateFloatAsState(...)` 后
                    // `graphicsLayer { scaleX = scale }` 看似是 graphicsLayer block 内读，
                    // 实际 `scale` 已经被 `by` 在 composition scope 解出值再传进去 → 每帧
                    // 触发 Column 重组。改成 `val scale = animateFloatAsState(...)` 保留
                    // State<Float> 形态，graphicsLayer 块内直接读 `.value` → 只 invalidate
                    // 渲染层，跳过 composition + measure + layout。
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale = animateFloatAsState(
                        targetValue = if (isPressed) 0.92f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "nav_scale_$index",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val s = scale.value
                                    scaleX = s
                                    scaleY = s
                                }
                                .combinedClickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { onTabClick(index) },
                                    onLongClick = { onTabLongClick(index) },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // 选中 icon glow —— drawWithCache 把 brush 缓存到 size 变化前。
                            // size 是固定 20dp 永不变 → brush 只构造一次，后续 invalidation
                            // (color animation / parent recompose) 不会重建 brush。
                            // 颜色列表 [iconGlowColors] 提到 Composable 顶层，跨 tab 共用。
                            val iconModifier = if (selected) {
                                Modifier
                                    .size(20.dp)
                                    .drawWithCache {
                                        val r = size.minDimension * 0.95f
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val brush = Brush.radialGradient(
                                            colors = iconGlowColors,
                                            center = center,
                                            radius = r,
                                        )
                                        onDrawBehind {
                                            drawCircle(brush = brush, radius = r, center = center)
                                        }
                                    }
                            } else Modifier.size(20.dp)
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = color,
                                modifier = iconModifier,
                            )
                            Text(
                                text = tab.label,
                                color = color,
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                lineHeight = 12.sp,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            // 旧版每个 tab 自带 dot 占位 —— 现在 dot 是顶层共享的滑动 dot，
                            // 这里留 6dp Spacer 维持 vertical layout 节奏（让 icon+label 不
                            // 紧贴胶囊底，给共享 dot 留位置）。
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.size(6.dp),
                            )
                        }
                        // 让调用方在这个 tab 的 Box 里塞 DropdownMenu / Tooltip 等。
                        tabExtras(index)
                    }
                }
            }
        }
    }
}
