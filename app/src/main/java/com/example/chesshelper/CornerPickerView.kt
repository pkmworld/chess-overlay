package com.example.chesshelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Hiển thị ảnh và 4 chấm kéo được: trên-trái, trên-phải, dưới-phải, dưới-trái
 * của lưới 8x8 (theo đúng góc nhìn trong ảnh).
 */
class CornerPickerView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(v) {
            field = v
            resetCorners()
            invalidate()
        }

    /** Tọa độ 4 góc theo pixel của bitmap. */
    val corners = Array(4) { PointF() }

    private var drag = -1
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 82, 82)
        style = Paint.Style.FILL
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
        isFakeBoldText = true
    }

    private fun resetCorners() {
        val b = bitmap ?: return
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        corners[0].set(w * 0.12f, h * 0.2f)
        corners[1].set(w * 0.88f, h * 0.2f)
        corners[2].set(w * 0.88f, h * 0.8f)
        corners[3].set(w * 0.12f, h * 0.8f)
    }

    private fun layoutImage() {
        val b = bitmap ?: return
        scale = min(width / b.width.toFloat(), height / b.height.toFloat())
        offX = (width - b.width * scale) / 2f
        offY = (height - b.height * scale) / 2f
    }

    private fun vx(p: PointF) = offX + p.x * scale
    private fun vy(p: PointF) = offY + p.y * scale

    private fun gridPoint(u: Float, v: Float): PointF {
        val tx = corners[0].x + (corners[1].x - corners[0].x) * u
        val ty = corners[0].y + (corners[1].y - corners[0].y) * u
        val bx = corners[3].x + (corners[2].x - corners[3].x) * u
        val by = corners[3].y + (corners[2].y - corners[3].y) * u
        return PointF(tx + (bx - tx) * v, ty + (by - ty) * v)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#212121"))
        val b = bitmap
        if (b == null) {
            canvas.drawText("Chưa có ảnh", width / 2f, height / 2f, textPaint)
            return
        }
        layoutImage()
        canvas.drawBitmap(
            b, null,
            RectF(offX, offY, offX + b.width * scale, offY + b.height * scale), imgPaint
        )
        for (i in 0..8) {
            val t = i / 8f
            val a1 = gridPoint(0f, t)
            val a2 = gridPoint(1f, t)
            canvas.drawLine(vx(a1), vy(a1), vx(a2), vy(a2), gridPaint)
            val c1 = gridPoint(t, 0f)
            val c2 = gridPoint(t, 1f)
            canvas.drawLine(vx(c1), vy(c1), vx(c2), vy(c2), gridPaint)
        }
        for (i in 0..3) {
            val p = corners[i]
            val q = corners[(i + 1) % 4]
            canvas.drawLine(vx(p), vy(p), vx(q), vy(q), linePaint)
        }
        val r = 26f
        for (i in 0..3) {
            val p = corners[i]
            canvas.drawCircle(vx(p), vy(p), r, handlePaint)
            canvas.drawCircle(vx(p), vy(p), r, handleRing)
            canvas.drawText((i + 1).toString(), vx(p), vy(p) + 10f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bestD = 90f
                for (i in 0..3) {
                    val d = hypot(event.x - vx(corners[i]), event.y - vy(corners[i]))
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                drag = best
                parent?.requestDisallowInterceptTouchEvent(true)
                return best >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag >= 0) {
                    val x = ((event.x - offX) / scale).coerceIn(0f, b.width.toFloat() - 1)
                    val y = ((event.y - offY) / scale).coerceIn(0f, b.height.toFloat() - 1)
                    corners[drag].set(x, y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
