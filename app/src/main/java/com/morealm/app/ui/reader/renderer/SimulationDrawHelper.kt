package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Shadow gradient colors for the fold crease */
private val FOLDER_SHADOW_COLORS = intArrayOf(0x333333, -0x4fcccccd)
/** Shadow gradient colors for the back of the turning page */
private val BACK_SHADOW_COLORS = intArrayOf(-0xeeeeef, 0x111111)
/** Shadow gradient colors for the front of the current page */
private val FRONT_SHADOW_COLORS = intArrayOf(-0x7feeeeef, 0x111111)

/** Shadow spread width (px) for current-page edge shadow */
private const val SHADOW_WIDTH = 25
/** sqrt(2) approximation used for shadow diagonal offset */
private const val SQRT2 = 1.414f
/** Small epsilon to prevent division by zero in bezier calculations */
private const val BEZIER_EPSILON = 0.1f

/**
 * 仿真翻页绘制引擎 — 直接移植自 Legado SimulationPageDelegate。
 * 纯 Android Canvas 绘制，不依赖 View 系统，可在 Compose drawIntoCanvas 中使用。
 *
 * 使用方式：
 * 1. setViewSize() 设置尺寸
 * 2. setTouchPoint() / setCorner() 设置触摸点和角落
 * 3. onDraw() 绘制到 Canvas
 */
class SimulationDrawHelper {

    var viewWidth: Int = 0
        private set
    var viewHeight: Int = 0
        private set

    // 触摸点（不让为0，否则计算有问题）
    private var mTouchX = 0.1f
    private var mTouchY = 0.1f

    // 拖拽点对应的页脚
    private var mCornerX = 1
    private var mCornerY = 1
    private val mPath0: Path = Path()
    private val mPath1: Path = Path()

    // 贝塞尔曲线起始点
    private val mBezierStart1 = PointF()
    private val mBezierControl1 = PointF()
    private val mBezierVertex1 = PointF()
    private var mBezierEnd1 = PointF()

    // 另一条贝塞尔曲线
    private val mBezierStart2 = PointF()
    private val mBezierControl2 = PointF()
    private val mBezierVertex2 = PointF()
    private var mBezierEnd2 = PointF()

    private var mMiddleX = 0f
    private var mMiddleY = 0f
    private var mDegrees = 0f
    private var mTouchToCornerDis = 0f
    private val mColorMatrixFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    private val mMatrix: Matrix = Matrix()
    private val mMatrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)

    // 是否属于右上左下
    private var mIsRtOrLb = false
    private var mMaxLength = 0f

    // 阴影 GradientDrawable
    private var mBackShadowColors: IntArray
    private var mFrontShadowColors: IntArray
    private var mBackShadowDrawableLR: GradientDrawable
    private var mBackShadowDrawableRL: GradientDrawable
    private var mFolderShadowDrawableLR: GradientDrawable
    private var mFolderShadowDrawableRL: GradientDrawable
    private var mFrontShadowDrawableHBT: GradientDrawable
    private var mFrontShadowDrawableHTB: GradientDrawable
    private var mFrontShadowDrawableVLR: GradientDrawable
    private var mFrontShadowDrawableVRL: GradientDrawable

    private val mPaint: Paint = Paint().apply { style = Paint.Style.FILL }

    /** 背景平均色，用于绘制翻页背面 */
    var bgMeanColor: Int = 0xFFFFFFFF.toInt()

    init {
        mFolderShadowDrawableRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, FOLDER_SHADOW_COLORS)
        mFolderShadowDrawableRL.gradientType = GradientDrawable.LINEAR_GRADIENT

        mFolderShadowDrawableLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, FOLDER_SHADOW_COLORS)
        mFolderShadowDrawableLR.gradientType = GradientDrawable.LINEAR_GRADIENT

        mBackShadowColors = BACK_SHADOW_COLORS.copyOf()
        mBackShadowDrawableRL =
            GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, mBackShadowColors)
        mBackShadowDrawableRL.gradientType = GradientDrawable.LINEAR_GRADIENT

        mBackShadowDrawableLR =
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, mBackShadowColors)
        mBackShadowDrawableLR.gradientType = GradientDrawable.LINEAR_GRADIENT

        mFrontShadowColors = FRONT_SHADOW_COLORS.copyOf()
        mFrontShadowDrawableVLR =
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, mFrontShadowColors)
        mFrontShadowDrawableVLR.gradientType = GradientDrawable.LINEAR_GRADIENT

        mFrontShadowDrawableVRL =
            GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, mFrontShadowColors)
        mFrontShadowDrawableVRL.gradientType = GradientDrawable.LINEAR_GRADIENT

        mFrontShadowDrawableHTB =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, mFrontShadowColors)
        mFrontShadowDrawableHTB.gradientType = GradientDrawable.LINEAR_GRADIENT

        mFrontShadowDrawableHBT =
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, mFrontShadowColors)
        mFrontShadowDrawableHBT.gradientType = GradientDrawable.LINEAR_GRADIENT
    }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        mMaxLength = hypot(width.toDouble(), height.toDouble()).toFloat()
    }

    /**
     * 设置触摸点。由 Compose 的 pageOffset 驱动。
     */
    fun setTouchPoint(x: Float, y: Float) {
        mTouchX = x.coerceAtLeast(BEZIER_EPSILON)
        mTouchY = y.coerceAtLeast(BEZIER_EPSILON)
    }

    /**
     * 计算拖拽点对应的拖拽脚
     */
    fun calcCornerXY(x: Float, y: Float) {
        mCornerX = if (x <= viewWidth / 2) 0 else viewWidth
        mCornerY = if (y <= viewHeight / 2) 0 else viewHeight
        mIsRtOrLb = (mCornerX == 0 && mCornerY == viewHeight)
                || (mCornerY == 0 && mCornerX == viewWidth)
    }

    /**
     * 根据翻页方向设置触摸点和角落。
     * @param touchX 当前触摸 X（或动画驱动的 X）
     * @param touchY 当前触摸 Y
     * @param isNext true=翻到下一页（从右向左拖），false=翻到上一页（从左向右拖）
     */
    fun setDirectionAware(touchX: Float, touchY: Float, isNext: Boolean) {
        setTouchPoint(touchX, touchY)
        if (isNext) {
            // 翻到下一页：角落在右侧
            calcCornerXY(viewWidth.toFloat(), touchY)
        } else {
            // 翻到上一页：角落在左侧
            calcCornerXY(0f, touchY)
        }
    }

    /**
     * 绘制仿真翻页效果。
     * @param canvas 目标 Canvas
     * @param curBitmap 当前页位图
     * @param nextBitmap 下一页（或上一页）位图
     */
    fun onDraw(canvas: Canvas, curBitmap: Bitmap?, nextBitmap: Bitmap?) {
        calcPoints()
        drawCurrentPageArea(canvas, curBitmap)
        drawNextPageAreaAndShadow(canvas, nextBitmap)
        drawCurrentPageShadow(canvas)
        drawCurrentBackArea(canvas, curBitmap)
    }

    // ── 以下为 Legado SimulationPageDelegate 的绘制逻辑，原样移植 ──

    private fun drawCurrentBackArea(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        val i = ((mBezierStart1.x + mBezierControl1.x) / 2).toInt()
        val f1 = abs(i - mBezierControl1.x)
        val i1 = ((mBezierStart2.y + mBezierControl2.y) / 2).toInt()
        val f2 = abs(i1 - mBezierControl2.y)
        val f3 = min(f1, f2)
        mPath1.reset()
        mPath1.moveTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierEnd1.x, mBezierEnd1.y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath1.close()
        val mFolderShadowDrawable: GradientDrawable
        val left: Int
        val right: Int
        if (mIsRtOrLb) {
            left = (mBezierStart1.x - 1).toInt()
            right = (mBezierStart1.x + f3 + 1).toInt()
            mFolderShadowDrawable = mFolderShadowDrawableLR
        } else {
            left = (mBezierStart1.x - f3 - 1).toInt()
            right = (mBezierStart1.x + 1).toInt()
            mFolderShadowDrawable = mFolderShadowDrawableRL
        }
        canvas.save()
        canvas.clipPath(mPath0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(mPath1)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(mPath1, Region.Op.INTERSECT)
        }

        mPaint.colorFilter = mColorMatrixFilter
        val dis = hypot(
            mCornerX - mBezierControl1.x.toDouble(),
            mBezierControl2.y - mCornerY.toDouble()
        ).toFloat()
        val f8 = (mCornerX - mBezierControl1.x) / dis
        val f9 = (mBezierControl2.y - mCornerY) / dis
        mMatrixArray[0] = 1 - 2 * f9 * f9
        mMatrixArray[1] = 2 * f8 * f9
        mMatrixArray[3] = mMatrixArray[1]
        mMatrixArray[4] = 1 - 2 * f8 * f8
        mMatrix.reset()
        mMatrix.setValues(mMatrixArray)
        mMatrix.preTranslate(-mBezierControl1.x, -mBezierControl1.y)
        mMatrix.postTranslate(mBezierControl1.x, mBezierControl1.y)
        canvas.drawColor(bgMeanColor)
        canvas.drawBitmap(bitmap, mMatrix, mPaint)
        mPaint.colorFilter = null
        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y)
        mFolderShadowDrawable.setBounds(
            left, mBezierStart1.y.toInt(),
            right, (mBezierStart1.y + mMaxLength).toInt()
        )
        mFolderShadowDrawable.draw(canvas)
        canvas.restore()
    }

    private fun drawCurrentPageShadow(canvas: Canvas) {
        val degree: Double = if (mIsRtOrLb) {
            Math.PI / 4 - atan2(mBezierControl1.y - mTouchY, mTouchX - mBezierControl1.x)
        } else {
            Math.PI / 4 - atan2(mTouchY - mBezierControl1.y, mTouchX - mBezierControl1.x)
        }
        val d1 = SHADOW_WIDTH * SQRT2 * cos(degree)
        val d2 = SHADOW_WIDTH * SQRT2 * sin(degree)
        val x = (mTouchX + d1).toFloat()
        val y: Float = if (mIsRtOrLb) {
            (mTouchY + d2).toFloat()
        } else {
            (mTouchY - d2).toFloat()
        }
        mPath1.reset()
        mPath1.moveTo(x, y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierControl1.x, mBezierControl1.y)
        mPath1.lineTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.close()
        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(mPath0)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(mPath0, Region.Op.XOR)
        }
        @Suppress("DEPRECATION")
        canvas.clipPath(mPath1, Region.Op.INTERSECT)

        var leftX: Int
        var rightX: Int
        var mCurrentPageShadow: GradientDrawable
        if (mIsRtOrLb) {
            leftX = mBezierControl1.x.toInt()
            rightX = (mBezierControl1.x + SHADOW_WIDTH).toInt()
            mCurrentPageShadow = mFrontShadowDrawableVLR
        } else {
            leftX = (mBezierControl1.x - SHADOW_WIDTH).toInt()
            rightX = (mBezierControl1.x + 1).toInt()
            mCurrentPageShadow = mFrontShadowDrawableVRL
        }
        var rotateDegrees = Math.toDegrees(
            atan2(mTouchX - mBezierControl1.x, mBezierControl1.y - mTouchY).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, mBezierControl1.x, mBezierControl1.y)
        mCurrentPageShadow.setBounds(
            leftX, (mBezierControl1.y - mMaxLength).toInt(),
            rightX, mBezierControl1.y.toInt()
        )
        mCurrentPageShadow.draw(canvas)
        canvas.restore()

        mPath1.reset()
        mPath1.moveTo(x, y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierControl2.x, mBezierControl2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.close()
        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(mPath0)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(mPath0, Region.Op.XOR)
        }
        canvas.clipPath(mPath1)

        if (mIsRtOrLb) {
            leftX = mBezierControl2.y.toInt()
            rightX = (mBezierControl2.y + SHADOW_WIDTH).toInt()
            mCurrentPageShadow = mFrontShadowDrawableHTB
        } else {
            leftX = (mBezierControl2.y - SHADOW_WIDTH).toInt()
            rightX = (mBezierControl2.y + 1).toInt()
            mCurrentPageShadow = mFrontShadowDrawableHBT
        }
        rotateDegrees = Math.toDegrees(
            atan2(mBezierControl2.y - mTouchY, mBezierControl2.x - mTouchX).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, mBezierControl2.x, mBezierControl2.y)
        val temp =
            if (mBezierControl2.y < 0) (mBezierControl2.y - viewHeight).toDouble()
            else mBezierControl2.y.toDouble()
        val hmg = hypot(mBezierControl2.x.toDouble(), temp)
        if (hmg > mMaxLength)
            mCurrentPageShadow.setBounds(
                (mBezierControl2.x - SHADOW_WIDTH - hmg).toInt(), leftX,
                (mBezierControl2.x + mMaxLength - hmg).toInt(), rightX
            )
        else
            mCurrentPageShadow.setBounds(
                (mBezierControl2.x - mMaxLength).toInt(), leftX,
                mBezierControl2.x.toInt(), rightX
            )
        mCurrentPageShadow.draw(canvas)
        canvas.restore()
    }

    private fun drawNextPageAreaAndShadow(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        mPath1.reset()
        mPath1.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPath1.close()
        mDegrees = Math.toDegrees(
            atan2(
                (mBezierControl1.x - mCornerX).toDouble(),
                mBezierControl2.y - mCornerY.toDouble()
            )
        ).toFloat()
        val leftX: Int
        val rightX: Int
        val mBackShadowDrawable: GradientDrawable
        if (mIsRtOrLb) {
            leftX = mBezierStart1.x.toInt()
            rightX = (mBezierStart1.x + mTouchToCornerDis / 4).toInt()
            mBackShadowDrawable = mBackShadowDrawableLR
        } else {
            leftX = (mBezierStart1.x - mTouchToCornerDis / 4).toInt()
            rightX = mBezierStart1.x.toInt()
            mBackShadowDrawable = mBackShadowDrawableRL
        }
        canvas.save()
        canvas.clipPath(mPath0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(mPath1)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(mPath1, Region.Op.INTERSECT)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y)
        mBackShadowDrawable.setBounds(
            leftX, mBezierStart1.y.toInt(),
            rightX, (mMaxLength + mBezierStart1.y).toInt()
        )
        mBackShadowDrawable.draw(canvas)
        canvas.restore()
    }

    private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        mPath0.reset()
        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath0.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x, mBezierEnd1.y)
        mPath0.lineTo(mTouchX, mTouchY)
        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath0.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x, mBezierStart2.y)
        mPath0.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPath0.close()

        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(mPath0)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(mPath0, Region.Op.XOR)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()
    }

    private fun calcPoints() {
        mMiddleX = (mTouchX + mCornerX) / 2
        mMiddleY = (mTouchY + mCornerY) / 2
        mBezierControl1.x =
            mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / (mCornerX - mMiddleX)
        mBezierControl1.y = mCornerY.toFloat()
        mBezierControl2.x = mCornerX.toFloat()

        val f4 = mCornerY - mMiddleY
        if (f4 == 0f) {
            mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / BEZIER_EPSILON
        } else {
            mBezierControl2.y =
                mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / (mCornerY - mMiddleY)
        }
        mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2
        mBezierStart1.y = mCornerY.toFloat()

        // 固定左边上下两个点
        if (mTouchX > 0 && mTouchX < viewWidth) {
            if (mBezierStart1.x < 0 || mBezierStart1.x > viewWidth) {
                if (mBezierStart1.x < 0)
                    mBezierStart1.x = viewWidth - mBezierStart1.x

                val f1 = abs(mCornerX - mTouchX)
                val f2 = viewWidth * f1 / mBezierStart1.x
                mTouchX = abs(mCornerX - f2)

                val f3 = abs(mCornerX - mTouchX) * abs(mCornerY - mTouchY) / f1
                mTouchY = abs(mCornerY - f3)

                mMiddleX = (mTouchX + mCornerX) / 2
                mMiddleY = (mTouchY + mCornerY) / 2

                mBezierControl1.x =
                    mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / (mCornerX - mMiddleX)
                mBezierControl1.y = mCornerY.toFloat()

                mBezierControl2.x = mCornerX.toFloat()

                val f5 = mCornerY - mMiddleY
                if (f5 == 0f) {
                    mBezierControl2.y =
                        mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / 0.1f
                } else {
                    mBezierControl2.y =
                        mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / (mCornerY - mMiddleY)
                }

                mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2
            }
        }
        mBezierStart2.x = mCornerX.toFloat()
        mBezierStart2.y = mBezierControl2.y - (mCornerY - mBezierControl2.y) / 2

        mTouchToCornerDis = hypot(
            (mTouchX - mCornerX).toDouble(),
            (mTouchY - mCornerY).toDouble()
        ).toFloat()

        mBezierEnd1 = getCross(
            PointF(mTouchX, mTouchY), mBezierControl1, mBezierStart1,
            mBezierStart2
        )
        mBezierEnd2 = getCross(
            PointF(mTouchX, mTouchY), mBezierControl2, mBezierStart1,
            mBezierStart2
        )

        mBezierVertex1.x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4
        mBezierVertex1.y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4
        mBezierVertex2.x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4
        mBezierVertex2.y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4
    }

    /**
     * 求解直线P1P2和直线P3P4的交点坐标
     */
    private fun getCross(P1: PointF, P2: PointF, P3: PointF, P4: PointF): PointF {
        val crossP = PointF()
        val a1 = (P2.y - P1.y) / (P2.x - P1.x)
        val b1 = (P1.x * P2.y - P2.x * P1.y) / (P1.x - P2.x)
        val a2 = (P4.y - P3.y) / (P4.x - P3.x)
        val b2 = (P3.x * P4.y - P4.x * P3.y) / (P3.x - P4.x)
        crossP.x = (b2 - b1) / (a1 - a2)
        crossP.y = a1 * crossP.x + b1
        return crossP
    }
}
