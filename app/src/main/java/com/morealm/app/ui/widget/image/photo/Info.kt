package com.morealm.app.ui.widget.image.photo

import android.graphics.PointF
import android.graphics.RectF
import android.widget.ImageView

class Info(
    rect: RectF,
    img: RectF,
    widget: RectF,
    base: RectF,
    screenCenter: PointF,
    scale: Float,
    degrees: Float,
    scaleType: ImageView.ScaleType?
) {
    var mRect = RectF()
    var mImgRect = RectF()
    var mWidgetRect = RectF()
    var mBaseRect = RectF()
    var mScreenCenter = PointF()
    var mScale = 0f
    var mDegrees = 0f
    var mScaleType: ImageView.ScaleType? = null

    init {
        mRect.set(rect)
        mImgRect.set(img)
        mWidgetRect.set(widget)
        mScale = scale
        mScaleType = scaleType
        mDegrees = degrees
        mBaseRect.set(base)
        mScreenCenter.set(screenCenter)
    }
}
