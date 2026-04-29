package com.morealm.app.presentation.reader

import android.text.TextPaint
import com.morealm.app.domain.render.TextMeasure

/**
 * Layout parameters for chapter rendering.
 * Pushed from UI layer (CanvasRenderer) to ViewModel (ReaderChapterController).
 */
data class LayoutParams(
    val viewWidth: Int,
    val viewHeight: Int,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    val textMeasure: TextMeasure,
    val paragraphIndent: String,
    val textFullJustify: Boolean,
    val titleMode: Int,
    val isMiddleTitle: Boolean = false,
    val lineSpacingExtra: Float,
    val paragraphSpacing: Int,
    val titleTopSpacing: Int,
    val titleBottomSpacing: Int,
    val chapterNumPaint: TextPaint? = null,
)
