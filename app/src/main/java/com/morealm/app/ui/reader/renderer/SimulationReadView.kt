package com.morealm.app.ui.reader.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.widget.Scroller
import com.morealm.app.core.log.AppLog
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 仿真翻页的原生 View — 移植自 Legado 的 HorizontalPageDelegate + SimulationPageDelegate。
 *
 * 使用原生 View 而非 Compose pointerInput 的原因：
 * - onTouchEvent 直接读取类字段，不存在闭包值捕获陈旧问题
 * - Scroller + computeScroll 每帧同步回调，动画完成后 stopScroll 必定执行
 * - 单一触摸入口，无手势冲突
 *
 * 详见 docs/page-turn-bug-analysis.md
 */
class SimulationReadView(context: Context) : android.view.View(context) {

    private val drawHelper = SimulationDrawHelper()
    private val scroller = Scroller(context, LinearInterpolator())

    // ── Page bitmaps ──
    private var curBitmap: Bitmap? = null
    private var prevBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null

    // ── Gesture state (class fields — Legado pattern, no closures!) ──
    private var startX = 0f
    private var startY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var isMoved = false
    private var isRunning = false
    private var isStarted = false
    private var isCancel = false
    private var isNext = true
    private var noNext = false
    private var directionSet = false

    // Slop for distinguishing tap from drag
    private val slopSquare: Int by lazy {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        slop * slop
    }

    // ── Callbacks (set by Compose wrapper) ──
    var onPageTurnCompleted: ((isNext: Boolean) -> Unit)? = null
    var onTapCenter: (() -> Unit)? = null
    var onTapPrev: (() -> Unit)? = null
    var onTapNext: (() -> Unit)? = null
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null
    var canTurnNext: (() -> Boolean)? = null
    var canTurnPrev: (() -> Boolean)? = null
    var bgMeanColor: Int = 0xFFFFFFFF.toInt()

    // Bitmap provider: (relativePos, viewWidth, viewHeight) -> Bitmap?
    // relativePos: -1=prev, 0=current, 1=next
    // Uses the View's own dimensions to ensure correct bitmap size
    var bitmapProvider: ((relativePos: Int, width: Int, height: Int) -> Bitmap?)? = null

    // ── Idle page bitmap (shown when not animating) ──
    private var idleBitmap: Bitmap? = null

    fun setIdleBitmap(bitmap: Bitmap?) {
        idleBitmap = bitmap
        if (!isMoved && !isRunning) {
            postInvalidate()
        }
    }

    // ── Long press detection ──
    private var longPressRunnable: Runnable? = null
    private val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawHelper.setViewSize(w, h)
        // Refresh idle bitmap now that we have real dimensions
        if (w > 0 && h > 0 && idleBitmap == null) {
            idleBitmap = bitmapProvider?.invoke(0, w, h)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Touch handling — ported from Legado HorizontalPageDelegate.onTouch()
    // Single entry point, direct field reads, no closures.
    // ══════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Legado: abortAnim() + onDown()
                abortAnim()
                onDown()
                startX = event.x
                startY = event.y
                touchX = event.x
                touchY = event.y

                // Start long press timer
                cancelLongPressTimer()
                longPressRunnable = Runnable {
                    onLongPress?.invoke(event.x, event.y)
                }.also { postDelayed(it, longPressTimeout) }
            }

            MotionEvent.ACTION_MOVE -> {
                cancelLongPressTimer()
                touchX = event.x
                touchY = event.y
                onScroll(event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPressTimer()
                touchX = event.x
                touchY = event.y
                if (!isMoved) {
                    // Tap — determine zone
                    handleTap(event.x, event.y)
                } else {
                    // Drag end — start commit/cancel animation
                    onAnimStart()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                abortAnim()
            }
        }
        return true
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    // ── Tap handling (3-column zones, like Legado ReadView.onSingleTapUp) ──
    private fun handleTap(x: Float, y: Float) {
        val third = width / 3f
        when {
            x < third -> {
                // Left zone → prev page
                if (canTurnPrev?.invoke() == true) {
                    keyTurnPage(isNext = false)
                } else {
                    onTapPrev?.invoke()
                }
            }
            x > third * 2 -> {
                // Right zone → next page
                if (canTurnNext?.invoke() == true) {
                    keyTurnPage(isNext = true)
                } else {
                    onTapNext?.invoke()
                }
            }
            else -> {
                // Center zone → menu
                onTapCenter?.invoke()
            }
        }
    }

    // ── Key/tap page turn (Legado PageDelegate.keyTurnPage) ──
    private fun keyTurnPage(isNext: Boolean) {
        if (isRunning) return
        this.isNext = isNext
        directionSet = true
        setBitmaps()

        val tapY = if (touchY > height / 2f) height.toFloat() * TAP_START_RATIO else height.toFloat() * TAP_START_RATIO_FAR
        if (isNext) {
            drawHelper.setDirectionAware(width.toFloat() * TAP_START_RATIO, tapY, true)
        } else {
            drawHelper.setDirectionAware(width.toFloat() * TAP_START_RATIO_FAR, tapY, false)
        }
        isMoved = true
        isCancel = false
        isRunning = true
        isStarted = true

        // Compute animation target (Legado SimulationPageDelegate.onAnimStart)
        val dx: Int
        val dy: Int
        if (isNext) {
            dx = -(width + drawHelper.mTouchX.toInt())
            dy = if (drawHelper.mCornerY > 0) {
                (height - drawHelper.mTouchY).toInt()
            } else {
                -drawHelper.mTouchY.toInt()
            }
        } else {
            dx = (width - drawHelper.mTouchX + width).toInt()
            dy = if (drawHelper.mCornerY > 0) {
                (height - drawHelper.mTouchY).toInt()
            } else {
                -drawHelper.mTouchY.toInt()
            }
        }
        val duration = (ANIM_SPEED * abs(dx).toFloat() / width).toInt().coerceAtLeast(1)
        scroller.startScroll(
            drawHelper.mTouchX.toInt(), drawHelper.mTouchY.toInt(),
            dx, dy, duration,
        )
        invalidate()
    }

    // ── Scroll (drag) handling — ported from Legado HorizontalPageDelegate.onScroll ──
    private fun onScroll(x: Float, y: Float) {
        if (!isMoved) {
            val deltaX = (x - startX).toInt()
            val deltaY = (y - startY).toInt()
            val distance = deltaX * deltaX + deltaY * deltaY
            if (distance > slopSquare) {
                // First move past slop — determine direction
                if (x - startX > 0) {
                    // Dragging right → prev page
                    if (canTurnPrev?.invoke() != true) {
                        noNext = true
                        return
                    }
                    isNext = false
                } else {
                    // Dragging left → next page
                    if (canTurnNext?.invoke() != true) {
                        noNext = true
                        return
                    }
                    isNext = true
                }
                directionSet = true
                setBitmaps()
                isMoved = true
            }
        }

        if (isMoved) {
            isRunning = true
            // Update cancel state (Legado: dragging back toward start = cancel)
            isCancel = if (isNext) {
                x - startX > 0
            } else {
                x - startX < 0
            }
            // Adjust touchY for mid-screen drags (Legado SimulationPageDelegate.onTouch ACTION_MOVE)
            val adjustedY = if (isNext) {
                if (drawHelper.mCornerY > 0) {
                    y.coerceAtMost(height.toFloat() - 1f)
                } else {
                    y.coerceAtLeast(1f)
                }
            } else y

            drawHelper.setDirectionAware(x, adjustedY, isNext)
            drawHelper.calcPoints()
            invalidate()
        }
    }

    // ── Animation start (drag end) — Legado SimulationPageDelegate.onAnimStart ──
    private fun onAnimStart() {
        if (!isMoved) return
        val dx: Int
        val dy: Int
        if (!isCancel) {
            // Complete the turn
            if (isNext) {
                dx = -(width + drawHelper.mTouchX.toInt())
            } else {
                dx = (width - drawHelper.mTouchX + width).toInt()
            }
            dy = if (drawHelper.mCornerY > 0) {
                (height - drawHelper.mTouchY).toInt()
            } else {
                -drawHelper.mTouchY.toInt()
            }
        } else {
            // Cancel — animate back
            if (isNext) {
                dx = (width - drawHelper.mTouchX + width).toInt()
            } else {
                dx = -(width + drawHelper.mTouchX.toInt())
            }
            dy = if (drawHelper.mCornerY > 0) {
                (height - drawHelper.mTouchY).toInt()
            } else {
                -drawHelper.mTouchY.toInt()
            }
        }
        val duration = (ANIM_SPEED * abs(dx).toFloat() / width).toInt().coerceAtLeast(1)
        scroller.startScroll(
            drawHelper.mTouchX.toInt(), drawHelper.mTouchY.toInt(),
            dx, dy, duration,
        )
        isRunning = true
        isStarted = true
        invalidate()
    }

    // ── Animation tick — Legado PageDelegate.computeScroll ──
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val x = scroller.currX.toFloat()
            val y = scroller.currY.toFloat()
            drawHelper.setTouchPoint(x, y)
            drawHelper.calcPoints()
            postInvalidate()
        } else if (isStarted) {
            onAnimStop()
            stopScroll()
        }
    }

    // ── Animation complete — Legado SimulationPageDelegate.onAnimStop ──
    private fun onAnimStop() {
        if (!isCancel) {
            AppLog.debug("Reader", "SimulationView onAnimStop commit isNext=$isNext")
            onPageTurnCompleted?.invoke(isNext)
        } else {
            AppLog.debug("Reader", "SimulationView onAnimStop cancelled")
        }
    }

    // ── Stop scroll — Legado PageDelegate.stopScroll ──
    private fun stopScroll() {
        isStarted = false
        post {
            isMoved = false
            isRunning = false
            invalidate()
        }
    }

    // ── Abort animation — Legado HorizontalPageDelegate.abortAnim ──
    private fun abortAnim() {
        isStarted = false
        isMoved = false
        isRunning = false
        if (!scroller.isFinished) {
            scroller.abortAnimation()
            if (!isCancel && directionSet) {
                AppLog.debug("Reader", "SimulationView abortAnim force-commit isNext=$isNext")
                onPageTurnCompleted?.invoke(isNext)
            }
        }
        directionSet = false
    }

    // ── Reset — Legado PageDelegate.onDown ──
    private fun onDown() {
        isMoved = false
        noNext = false
        isRunning = false
        isCancel = false
        directionSet = false
    }

    // ── Bitmap setup — Legado SimulationPageDelegate.setBitmap ──
    private fun setBitmaps() {
        val provider = bitmapProvider ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (isNext) {
            curBitmap = provider(0, w, h)
            nextBitmap = provider(1, w, h)
        } else {
            curBitmap = provider(0, w, h)
            prevBitmap = provider(-1, w, h)
        }
        drawHelper.bgMeanColor = bgMeanColor
    }

    // ── Draw — Legado SimulationPageDelegate.onDraw ──
    override fun onDraw(canvas: Canvas) {
        // Always fill with theme background first (prevents white flash on first frame)
        canvas.drawColor(bgMeanColor)
        if (isMoved || isRunning) {
            drawHelper.bgMeanColor = bgMeanColor
            if (isNext) {
                drawHelper.onDraw(canvas, curBitmap, nextBitmap)
            } else {
                drawHelper.onDraw(canvas, prevBitmap, curBitmap)
            }
        } else {
            // Idle — draw current page bitmap
            val bmp = idleBitmap
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }
    }

    companion object {
        /** Animation duration factor (px per ms). Matches Legado ReadView.defaultAnimationSpeed. */
        private const val ANIM_SPEED = 300
        /** Tap start position ratio from edge (0.0 = edge, 1.0 = center). */
        private const val TAP_START_RATIO = 0.9f
        /** Tap start position ratio for the opposite edge. */
        private const val TAP_START_RATIO_FAR = 0.1f
    }
}
