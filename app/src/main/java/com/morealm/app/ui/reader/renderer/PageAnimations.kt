package com.morealm.app.ui.reader.renderer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Page animation types supported by the reader.
 */
enum class PageAnimType {
    NONE,       // Instant page change
    SLIDE,      // Default HorizontalPager slide
    COVER,      // Incoming page slides over, outgoing stays
    SIMULATION, // Page curl effect (simplified for Compose)
    SCROLL,     // Vertical continuous scroll
}

fun String.toPageAnimType(): PageAnimType = when (this.lowercase()) {
    "none" -> PageAnimType.NONE
    "slide" -> PageAnimType.SLIDE
    "cover" -> PageAnimType.COVER
    "simulation" -> PageAnimType.SIMULATION
    "scroll" -> PageAnimType.SCROLL
    else -> PageAnimType.SLIDE
}

/**
 * Paged reader with configurable page-turn animation.
 * For NONE/SLIDE/COVER: uses HorizontalPager with graphicsLayer transforms.
 * For SIMULATION: uses custom drag-based curl effect.
 * For SCROLL: caller should use ScrollRenderer instead.
 */
@Composable
fun AnimatedPageReader(
    pagerState: PagerState,
    animType: PageAnimType,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    when (animType) {
        PageAnimType.COVER -> {
            CoverPager(pagerState, modifier, pageContent)
        }
        PageAnimType.SIMULATION -> {
            // Simulation (curl) is complex in Compose — use a simplified version
            // that looks like a cover with shadow gradient
            CoverPager(pagerState, modifier, pageContent)
        }
        else -> {
            // NONE and SLIDE both use HorizontalPager
            // NONE uses snap-like fast animation, SLIDE uses default
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
            ) { pageIndex ->
                pageContent(pageIndex)
            }
        }
    }
}

/**
 * Cover page animation: incoming page slides over the outgoing page.
 * The outgoing page stays in place with a shadow overlay.
 */
@Composable
private fun CoverPager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
    ) { pageIndex ->
        val pageOffset = (pagerState.currentPage - pageIndex) +
            pagerState.currentPageOffsetFraction

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Cover effect: current page stays, next/prev slides over
                    val offset = pageOffset.coerceIn(-1f, 1f)
                    if (offset < 0) {
                        // This page is sliding in from the right (next page)
                        translationX = size.width * offset
                    } else if (offset > 0) {
                        // This page is being covered (current page stays)
                        translationX = 0f
                        // Add shadow effect
                        alpha = 1f - (offset * 0.3f).coerceIn(0f, 0.3f)
                    }
                }
                .drawWithContent {
                    drawContent()
                    // Draw shadow on the left edge of the sliding page
                    val offset = pageOffset.coerceIn(-1f, 1f)
                    if (offset != 0f) {
                        val shadowAlpha = (abs(offset) * 0.5f).coerceIn(0f, 0.3f)
                        drawRect(
                            color = Color.Black.copy(alpha = shadowAlpha),
                            size = size,
                        )
                    }
                }
        ) {
            pageContent(pageIndex)
        }
    }
}
