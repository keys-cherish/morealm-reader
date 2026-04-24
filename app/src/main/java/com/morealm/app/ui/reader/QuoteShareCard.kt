package com.morealm.app.ui.reader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File

/* ── Quote Share Card ── */

/**
 * Quote share card dialog — ported from MoRealm HTML prototype's quote modal.
 * Renders a gradient card tinted with the current theme accent color,
 * captures it as a bitmap, and shares via Android share sheet.
 */
@Composable
fun QuoteShareDialog(
    quoteText: String,
    bookTitle: String,
    author: String,
    accentColor: Color,
    backgroundColor: Color,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The card itself — we capture this for sharing
            var cardBitmap by remember { mutableStateOf<Bitmap?>(null) }
            val cardModifier = Modifier
                .widthIn(max = 320.dp)
                .drawWithContent {
                    drawContent()
                    // Capture on first composition
                    if (cardBitmap == null && size.width > 0 && size.height > 0) {
                        // We'll capture on button press instead
                    }
                }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Card
                QuoteCard(
                    quoteText = quoteText,
                    bookTitle = bookTitle,
                    author = author,
                    accentColor = accentColor,
                    backgroundColor = backgroundColor,
                    modifier = cardModifier,
                )

                Spacer(Modifier.height(20.dp))

                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White.copy(alpha = 0.8f),
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            shareQuoteAsImage(context, quoteText, bookTitle, author, accentColor, backgroundColor)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    ) {
                        Text("保存并分享", fontSize = 13.sp)
                    }
                }
            }

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(36.dp),
            ) {
                Icon(
                    Icons.Default.Close, "关闭",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The visual quote card — gradient background tinted with accent color,
 * decorative quote mark, text, attribution, and branding.
 */
@Composable
private fun QuoteCard(
    quoteText: String,
    bookTitle: String,
    author: String,
    accentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.15f).compositeOver(backgroundColor),
            backgroundColor,
        ),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
    val textColor = if (backgroundColor.luminance() > 0.4f) Color.Black else Color.White
    val subtleColor = textColor.copy(alpha = 0.5f)
    val accentLight = accentColor.copy(alpha = 0.3f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        shadowElevation = 16.dp,
    ) {
        Box(
            modifier = Modifier
                .background(gradientBrush, RoundedCornerShape(24.dp))
                .padding(32.dp, 36.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Decorative opening quote mark
                Text(
                    "\u201C",
                    color = accentLight,
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp,
                )

                Spacer(Modifier.height(4.dp))

                // Quote text
                Text(
                    quoteText,
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                Spacer(Modifier.height(20.dp))

                // Attribution line
                if (bookTitle.isNotBlank() || author.isNotBlank()) {
                    val attribution = buildString {
                        append("——")
                        if (bookTitle.isNotBlank()) append("《$bookTitle》")
                        if (author.isNotBlank()) append(" $author")
                    }
                    Text(
                        attribution,
                        color = subtleColor,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Branding
                Text(
                    "墨境 MoRealm",
                    color = textColor.copy(alpha = 0.25f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

/**
 * Render the quote card to a bitmap and share via Android share sheet.
 */
private fun shareQuoteAsImage(
    context: Context,
    quoteText: String,
    bookTitle: String,
    author: String,
    accentColor: Color,
    backgroundColor: Color,
) {
    val width = 720
    val padding = 80f
    val bgArgb = backgroundColor.toArgb()
    val textArgb = if (backgroundColor.luminance() > 0.4f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    val accentArgb = accentColor.toArgb()
    val subtleArgb = android.graphics.Color.argb(128, android.graphics.Color.red(textArgb), android.graphics.Color.green(textArgb), android.graphics.Color.blue(textArgb))

    // Measure text height
    val textPaint = android.text.TextPaint().apply {
        color = textArgb; textSize = 42f; isAntiAlias = true
        typeface = android.graphics.Typeface.SERIF
    }
    val textLayout = android.text.StaticLayout.Builder
        .obtain(quoteText, 0, quoteText.length, textPaint, (width - padding * 2).toInt())
        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
        .setLineSpacing(18f, 1f)
        .build()

    val attrText = buildString {
        append("——")
        if (bookTitle.isNotBlank()) append("《$bookTitle》")
        if (author.isNotBlank()) append(" $author")
    }
    val attrPaint = android.text.TextPaint().apply {
        color = subtleArgb; textSize = 28f; isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
    }

    val brandPaint = android.text.TextPaint().apply {
        color = android.graphics.Color.argb(64, android.graphics.Color.red(textArgb), android.graphics.Color.green(textArgb), android.graphics.Color.blue(textArgb))
        textSize = 24f; isAntiAlias = true; letterSpacing = 0.15f
    }

    val totalHeight = (padding + 80 + textLayout.height + 40 + 30 + 40 + 24 + padding).toInt()
    val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    // Background gradient
    canvas.drawColor(bgArgb)
    val gradPaint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), totalHeight.toFloat(),
            android.graphics.Color.argb(38, android.graphics.Color.red(accentArgb), android.graphics.Color.green(accentArgb), android.graphics.Color.blue(accentArgb)),
            bgArgb,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), totalHeight.toFloat(), gradPaint)

    // Round corners via clip
    val path = android.graphics.Path().apply {
        addRoundRect(0f, 0f, width.toFloat(), totalHeight.toFloat(), 56f, 56f, android.graphics.Path.Direction.CW)
    }
    // (Bitmap already has the content, corners will be handled by the share image viewer)

    var y = padding

    // Quote mark
    val quotePaint = android.text.TextPaint().apply {
        color = android.graphics.Color.argb(76, android.graphics.Color.red(accentArgb), android.graphics.Color.green(accentArgb), android.graphics.Color.blue(accentArgb))
        textSize = 100f; isAntiAlias = true
        typeface = android.graphics.Typeface.SERIF
    }
    val qw = quotePaint.measureText("\u201C")
    canvas.drawText("\u201C", (width - qw) / 2, y + 70, quotePaint)
    y += 80f

    // Quote text
    canvas.save()
    canvas.translate(padding, y)
    textLayout.draw(canvas)
    canvas.restore()
    y += textLayout.height + 40

    // Attribution
    if (attrText.length > 2) {
        val aw = attrPaint.measureText(attrText)
        canvas.drawText(attrText, (width - aw) / 2, y, attrPaint)
    }
    y += 40f

    // Brand
    val brandText = "墨境 MoRealm"
    val bw = brandPaint.measureText(brandText)
    canvas.drawText(brandText, (width - bw) / 2, y + 24, brandPaint)

    // Save and share
    val uri = saveBitmapToCache(context, bitmap)
    if (uri != null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享引用卡片"))
    }
    bitmap.recycle()
}

private fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
    return try {
        val file = File(context.cacheDir, "morealm_quote_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        null
    }
}
