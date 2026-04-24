package com.filmlut.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val drawMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var minScale = 1f
    private var maxScale = 5f
    private var hasViewport = false
    private var preserveViewportForNextDrawable = false

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable == null) {
            hasViewport = false
            drawMatrix.reset()
            imageMatrix = drawMatrix
            return
        }

        val shouldPreserve = preserveViewportForNextDrawable && hasViewport
        preserveViewportForNextDrawable = false
        post {
            if (shouldPreserve) {
                fixTranslation()
                imageMatrix = drawMatrix
            } else {
                resetZoom()
            }
        }
    }

    fun setImageBitmapPreservingViewport(bitmap: Bitmap) {
        preserveViewportForNextDrawable = true
        setImageBitmap(bitmap)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!isDragging) {
                        isDragging = dx * dx + dy * dy > 16f
                    }
                    if (isDragging) {
                        drawMatrix.postTranslate(dx, dy)
                        fixTranslation()
                        imageMatrix = drawMatrix
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                isDragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    fun resetZoom() {
        val d = drawable ?: return
        val viewWidth = width.toFloat().takeIf { it > 0 } ?: return
        val viewHeight = height.toFloat().takeIf { it > 0 } ?: return
        val drawableWidth = d.intrinsicWidth.toFloat().takeIf { it > 0 } ?: return
        val drawableHeight = d.intrinsicHeight.toFloat().takeIf { it > 0 } ?: return

        drawMatrix.reset()
        minScale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * minScale) / 2f
        val dy = (viewHeight - drawableHeight * minScale) / 2f
        drawMatrix.postScale(minScale, minScale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        hasViewport = true
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        drawMatrix.getValues(matrixValues)
        val scale = currentScale()
        val contentWidth = d.intrinsicWidth * scale
        val contentHeight = d.intrinsicHeight * scale
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        var transX = matrixValues[Matrix.MTRANS_X]
        var transY = matrixValues[Matrix.MTRANS_Y]

        val minX = if (contentWidth <= viewWidth) (viewWidth - contentWidth) / 2f else viewWidth - contentWidth
        val maxX = if (contentWidth <= viewWidth) minX else 0f
        val minY = if (contentHeight <= viewHeight) (viewHeight - contentHeight) / 2f else viewHeight - contentHeight
        val maxY = if (contentHeight <= viewHeight) minY else 0f

        transX = transX.coerceIn(minX, maxX)
        transY = transY.coerceIn(minY, maxY)
        matrixValues[Matrix.MTRANS_X] = transX
        matrixValues[Matrix.MTRANS_Y] = transY
        drawMatrix.setValues(matrixValues)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = currentScale()
            val target = (current * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = target / current
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = drawMatrix
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val current = currentScale()
            val target = if (current < minScale * 1.8f) min(current * 2f, maxScale) else minScale
            val factor = target / current
            drawMatrix.postScale(factor, factor, e.x, e.y)
            fixTranslation()
            imageMatrix = drawMatrix
            return true
        }
    }
}
