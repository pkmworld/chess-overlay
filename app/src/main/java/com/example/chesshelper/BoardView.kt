package com.example.chesshelper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Ký tự cờ vua Unicode (dạng đặc) cho từng loại quân. */
fun glyph(type: Int): String {
    val g = when (type) {
        Pc.KING -> "♚"
        Pc.QUEEN -> "♛"
        Pc.ROOK -> "♜"
        Pc.BISHOP -> "♝"
        Pc.KNIGHT -> "♞"
        Pc.PAWN -> "♟"
        else -> "?"
    }
    return g + "︎"
}

class BoardView(context: Context) : View(context) {

    var pos: Position = Position.start()
    var flipped = false
    var hint: Move? = null
    var selected = -1
    var targets: List<Int> = emptyList()
    var onSquareTap: ((Int) -> Unit)? = null

    private var blink = 0f

    private val lightSq = Color.parseColor("#F0D9B5")
    private val darkSq = Color.parseColor("#B58863")
    private val whiteFill = Color.WHITE
    private val blackFill = Color.parseColor("#2B2B2B")
    private val blinkColor = Color.parseColor("#FF6D00")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val pieceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        color = Color.BLACK
        typeface = Typeface.DEFAULT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 650
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            blink = it.animatedValue as Float
            if (hint != null) invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private fun colOf(sq: Int) = if (flipped) 7 - (sq and 7) else (sq and 7)
    private fun rowOf(sq: Int) = if (flipped) (sq shr 3) else 7 - (sq shr 3)

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        val cell = width / 8f
        val h = hint

        // 1. Ô cờ + tô sáng
        for (r in 0..7) {
            for (f in 0..7) {
                val sq = r * 8 + f
                val x = colOf(sq) * cell
                val y = rowOf(sq) * cell
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = if ((f + r) % 2 == 0) darkSq else lightSq
                canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
                if (h != null && sq == h.from) {
                    fillPaint.color = Color.argb((90 + 150 * blink).toInt(), 255, 196, 0)
                    canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
                }
                if (h != null && sq == h.to) {
                    fillPaint.color = Color.argb(150, 0, 200, 83)
                    canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
                }
                if (sq == selected) {
                    fillPaint.color = Color.argb(140, 255, 235, 59)
                    canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
                }
            }
        }

        // 2. Nhãn tọa độ
        labelPaint.textSize = cell * 0.17f
        val bottomRank = if (flipped) 7 else 0
        val leftFile = if (flipped) 7 else 0
        for (i in 0..7) {
            val fileIdx = if (flipped) 7 - i else i
            val rankIdx = if (flipped) i else 7 - i
            // ô tối -> nhãn sáng, ô sáng -> nhãn tối
            labelPaint.color = if ((fileIdx + bottomRank) % 2 == 0) lightSq else darkSq
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                ('a' + fileIdx).toString(),
                (i + 1) * cell - cell * 0.05f, 8 * cell - cell * 0.05f, labelPaint
            )
            labelPaint.color = if ((leftFile + rankIdx) % 2 == 0) lightSq else darkSq
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                ('1' + rankIdx).toString(),
                cell * 0.05f, i * cell + cell * 0.2f, labelPaint
            )
        }

        // 3. Quân cờ
        pieceFill.textSize = cell * 0.82f
        pieceStroke.textSize = cell * 0.82f
        pieceStroke.strokeWidth = cell * 0.035f
        val fm = pieceFill.fontMetrics
        for (sq in 0..63) {
            val v = pos.b[sq]
            if (v == 0) continue
            val cx = colOf(sq) * cell + cell / 2f
            val cy = rowOf(sq) * cell + cell / 2f
            val baseY = cy - (fm.ascent + fm.descent) / 2f
            val base = if (v > 0) whiteFill else blackFill
            val fill = if (h != null && sq == h.from) mix(base, blinkColor, blink) else base
            val g = glyph(abs(v))
            if (abs(v) == Pc.UNKNOWN) {
                pieceFill.color = if (v > 0) Color.parseColor("#E0E0E0") else Color.parseColor("#616161")
                canvas.drawText("?", cx, baseY, pieceStroke)
                canvas.drawText("?", cx, baseY, pieceFill)
            } else {
                pieceFill.color = fill
                canvas.drawText(g, cx, baseY, pieceStroke)
                canvas.drawText(g, cx, baseY, pieceFill)
            }
        }

        // 4. Nước đi hợp lệ của quân đang chọn
        fillPaint.color = Color.argb(150, 40, 40, 40)
        for (t in targets) {
            val cx = colOf(t) * cell + cell / 2f
            val cy = rowOf(t) * cell + cell / 2f
            if (pos.b[t] == 0) {
                fillPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, cell * 0.14f, fillPaint)
            } else {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = cell * 0.07f
                canvas.drawCircle(cx, cy, cell * 0.44f, fillPaint)
                fillPaint.style = Paint.Style.FILL
            }
        }

        // 5. Mũi tên gợi ý
        if (h != null) drawArrow(canvas, h.from, h.to, cell)
    }

    private fun drawArrow(canvas: Canvas, from: Int, to: Int, cell: Float) {
        val x1 = colOf(from) * cell + cell / 2f
        val y1 = rowOf(from) * cell + cell / 2f
        val x2 = colOf(to) * cell + cell / 2f
        val y2 = rowOf(to) * cell + cell / 2f
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val headLen = cell * 0.42f
        val headW = cell * 0.30f
        val tipX = x2 - ux * cell * 0.08f
        val tipY = y2 - uy * cell * 0.08f
        val baseX = tipX - ux * headLen
        val baseY = tipY - uy * headLen
        val startX = x1 + ux * cell * 0.22f
        val startY = y1 + uy * cell * 0.22f

        arrowPaint.color = Color.argb(215, 0, 150, 60)
        arrowPaint.style = Paint.Style.STROKE
        arrowPaint.strokeWidth = cell * 0.14f
        arrowPaint.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(startX, startY, baseX + ux * 2f, baseY + uy * 2f, arrowPaint)

        arrowPaint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX + nx * headW, baseY + ny * headW)
        path.lineTo(baseX - nx * headW, baseY - ny * headW)
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val cell = width / 8f
                val col = (event.x / cell).toInt()
                val row = (event.y / cell).toInt()
                if (col in 0..7 && row in 0..7) {
                    val f = if (flipped) 7 - col else col
                    val r = if (flipped) row else 7 - row
                    onSquareTap?.invoke(r * 8 + f)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
