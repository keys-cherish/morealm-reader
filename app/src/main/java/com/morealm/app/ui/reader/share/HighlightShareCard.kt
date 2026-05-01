package com.morealm.app.ui.reader.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import java.io.File
import java.io.FileOutputStream

/**
 * 渲染「高亮分享卡片」位图 + 触发系统分享。
 *
 * 选型说明
 * - 用 native Android Canvas + Paint 直接绘 Bitmap，而不是 Compose-to-Bitmap。
 *   原因：Compose 渲染到 Bitmap 需要 ComposeView/PictureRecorder，离屏复杂且对
 *   Compose 内部状态有依赖；纯 native 绘图独立、便于做模板调整、文件大小可控。
 * - 用 [StaticLayout] 让长文本自动换行（中文 / 英文都能正确算行），避免手撸折行
 *   逻辑漏掉 emoji / CJK 标点。
 * - 输出 PNG（无损，避免 JPEG 让纯色块出现压缩瑕疵），写到 cacheDir/share/，由
 *   `${applicationId}.fileprovider` 发出 content:// URI。res/xml/file_paths.xml
 *   已声明 cache-path "."，可直接覆盖。
 * - 底色取主题色配合 highlight.colorArgb 作色条，让收件人一眼就能看出来源 App
 *   与高亮归属。
 *
 * 卡片版式（自上而下）
 *   ┌──────────────────────────────────────┐
 *   │  ▌  《书名》                          │  ← 12 px 色条 + bookTitle
 *   │  ▌  · 章节名                          │  ← chapterTitle (淡色)
 *   │  ▌                                    │
 *   │  ▌  「正文……                          │  ← highlight.content (大字号)
 *   │  ▌    ……」                            │
 *   │  ▌                                    │
 *   │  ▌                            墨境  │  ← App brand 角注
 *   └──────────────────────────────────────┘
 */
object HighlightShareCard {

    private const val CARD_WIDTH = 1080
    private const val PADDING = 60
    private const val STRIPE_WIDTH = 12f
    private const val TEXT_LEFT_GAP = 32f  // 色条到正文左缘的水平间距
    private const val MAX_HEIGHT = 4096    // 防御：极长高亮被截断时不渲染过大位图

    /**
     * 渲染并触发分享，返回 true = 成功唤起 chooser，false = 渲染 / IO 失败。
     *
     * @param highlight 待分享高亮
     * @param appName 角标 App 名（默认「墨境」）
     */
    fun shareAsImage(
        context: Context,
        highlight: Highlight,
        appName: String = "墨境",
    ): Boolean = runCatching {
        val bitmap = render(highlight, appName)
        val uri = saveToCache(context, bitmap)
        // 释放位图，FileProvider 已通过文件句柄持有
        bitmap.recycle()
        launchShareSheet(context, uri, highlight)
        true
    }.getOrElse { err ->
        AppLog.error("HighlightShare", "shareAsImage failed", err)
        false
    }

    /**
     * 公开渲染入口，便于做预览 / 单元测试。调用方负责 [Bitmap.recycle] —
     * 内部 [shareAsImage] 走完后会自己回收。
     */
    fun render(highlight: Highlight, appName: String = "墨境"): Bitmap {
        val maxTextWidth = (CARD_WIDTH - PADDING * 2 - STRIPE_WIDTH - TEXT_LEFT_GAP).toInt()

        val bookTitleText = highlight.bookTitle.ifBlank { "未命名书籍" }
        val chapterText = "· ${highlight.chapterTitle.ifBlank { "未命名章节" }}"
        val contentText = "「${highlight.content.trim()}」"

        // ── Paints ───────────────────────────────────────────────────────
        val bookPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 56f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = 0xFF222222.toInt()
        }
        val chapterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            typeface = Typeface.SERIF
            color = 0xFF666666.toInt()
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 46f
            typeface = Typeface.SERIF
            color = 0xFF1A1A1A.toInt()
        }
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = 0xFFB0B0B0.toInt()
        }

        // ── StaticLayouts (do all measuring up front so we can size the bitmap) ──
        val bookLayout = makeLayout(bookTitleText, bookPaint, maxTextWidth)
        val chapterLayout = makeLayout(chapterText, chapterPaint, maxTextWidth)
        val bodyLayout = makeLayout(contentText, bodyPaint, maxTextWidth, lineSpacing = 1.4f)
        val brandLayout = makeLayout(appName, brandPaint, maxTextWidth)

        // ── Vertical layout ──────────────────────────────────────────────
        val gapAfterBook = 14
        val gapAfterChapter = 60
        val gapAfterBody = 60

        val totalHeight = (PADDING +
            bookLayout.height + gapAfterBook +
            chapterLayout.height + gapAfterChapter +
            bodyLayout.height + gapAfterBody +
            brandLayout.height +
            PADDING).coerceAtMost(MAX_HEIGHT)

        val bmp = Bitmap.createBitmap(CARD_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ── Background — 米白色淡渐变，避免纯白显得太"屏幕截图" ──────────
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, totalHeight.toFloat(),
                0xFFFAF8F2.toInt(), 0xFFF1ECDF.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), totalHeight.toFloat(), bgPaint)

        // ── Color stripe (highlight 颜色，去 alpha 让色条在卡片上更鲜明) ──
        val stripePaint = Paint().apply {
            // 把 highlight 的 0.4 透明度版本提到 0.85，色条才看得清楚
            color = (highlight.colorArgb and 0x00FFFFFF) or (0xD9 shl 24)
            isAntiAlias = true
        }
        val stripeLeft = PADDING.toFloat()
        canvas.drawRoundRect(
            RectF(stripeLeft, PADDING.toFloat(), stripeLeft + STRIPE_WIDTH, (totalHeight - PADDING).toFloat()),
            STRIPE_WIDTH / 2, STRIPE_WIDTH / 2, stripePaint,
        )

        // ── Text column ─────────────────────────────────────────────────
        val textLeft = stripeLeft + STRIPE_WIDTH + TEXT_LEFT_GAP
        var y = PADDING.toFloat()

        canvas.save(); canvas.translate(textLeft, y); bookLayout.draw(canvas); canvas.restore()
        y += bookLayout.height + gapAfterBook

        canvas.save(); canvas.translate(textLeft, y); chapterLayout.draw(canvas); canvas.restore()
        y += chapterLayout.height + gapAfterChapter

        // 正文带轻微底色 — 一次性绘制 highlight color 半透明矩形作衬底，让收件人一
        // 看就联想到「这是阅读高亮」。
        val bodyBgPaint = Paint().apply {
            color = (highlight.colorArgb and 0x00FFFFFF) or (0x40 shl 24)
            isAntiAlias = true
        }
        val bodyBgRect = RectF(
            textLeft - 12, y - 8,
            CARD_WIDTH - PADDING.toFloat() + 4,
            y + bodyLayout.height + 8,
        )
        canvas.drawRoundRect(bodyBgRect, 12f, 12f, bodyBgPaint)
        canvas.save(); canvas.translate(textLeft, y); bodyLayout.draw(canvas); canvas.restore()
        y += bodyLayout.height + gapAfterBody

        // 角注右下
        val brandX = CARD_WIDTH - PADDING.toFloat() - brandLayout.width
        canvas.save(); canvas.translate(brandX, y); brandLayout.draw(canvas); canvas.restore()

        return bmp
    }

    /**
     * StaticLayout 生成器；不同 API level 接口签名差异在这里集中处理。
     * 23+ 用 Builder（M+ 公共 API），更老的设备不在我们的 minSdk 范围内。
     */
    private fun makeLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        lineSpacing: Float = 1.2f,
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacing)
            .setIncludePad(false)
            .build()
    }

    private fun saveToCache(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // 文件名带时间戳避免分享 chooser 缓存旧图缩略图
        val file = File(dir, "highlight_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { os ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        }
        AppLog.info("HighlightShare", "card saved bytes=${file.length()} path=${file.absolutePath}")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun launchShareSheet(context: Context, uri: Uri, highlight: Highlight) {
        // EXTRA_TEXT 兜底 — 接收方不支持图片（少数 IM）时仍能拿到文字。
        val fallbackText = buildString {
            append(highlight.content.trim())
            append("\n\n— 《")
            append(highlight.bookTitle.ifBlank { "未命名书籍" })
            append("》· ")
            append(highlight.chapterTitle.ifBlank { "未命名章节" })
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, fallbackText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "分享高亮")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
