package com.morealm.app.ui.reader.renderer

import android.text.TextPaint
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos

/**
 * Continuous vertical scroll renderer.
 * Renders all pages in a LazyColumn for seamless scrolling.
 */
@Composable
fun ScrollRenderer(
    pages: List<TextPage>,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selectionColor: Color = Color(0x4D2196F3),
    aloudLineIndex: Int = -1,
    aloudColor: Color = Color(0x3300C853),
    searchResultColor: Color = Color(0x40FFEB3B),
    onScrollProgress: (Int) -> Unit = {},
    onNearBottom: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // Report scroll progress
    LaunchedEffect(listState.firstVisibleItemIndex, pages.size) {
        if (pages.isNotEmpty()) {
            val progress = ((listState.firstVisibleItemIndex + 1) * 100) / pages.size
            onScrollProgress(progress.coerceIn(0, 100))
        }
        // Trigger near-bottom callback
        if (listState.firstVisibleItemIndex >= pages.size - 2) {
            onNearBottom()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        itemsIndexed(pages, key = { index, _ -> "scroll_page_$index" }) { index, page ->
            val pageHeightDp = with(density) { page.height.toDp() + page.paddingTop.toDp() + 16.dp }
            PageCanvas(
                page = page,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                selectionColor = selectionColor,
                aloudLineIndex = aloudLineIndex,
                aloudColor = aloudColor,
                searchResultColor = searchResultColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageHeightDp),
            )
        }
    }
}
