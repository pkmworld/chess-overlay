package com.example.chessvision

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ChessOverlayView(context: Context) : View(context) {

    @Volatile var detectedBoardRect: RectF? = null
    @Volatile var isWhiteBottom = true

    private var fromSquare: String? = null
    private var toSquare: String? = null
    private var promotionPiece: Char? = null

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private val highlightFromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 214, 0)
        style = Paint.Style.FILL
    }
    private val highlightToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 0, 230, 118)
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 0, 230, 118)
        style = Paint.Style.FILL
    }
    private val promoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 213, 0, 0)
        style = Paint.Style.FILL
    }
    private val promoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun setMove(uciMove: String?) {
        if (uciMove != null && uciMove.length >= 4) {
            fromSquare = uciMove.substring(0, 2)
            toSquare = uciMove.substring(2, 4)
            promotionPiece = if (uciMove.length >= 5) uciMove[4].uppercaseChar() else null
        } else {
            fromSquare = null
            toSquare = null
            promotionPiece = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val board = detectedBoardRect ?: return

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val offsetX = loc[0].toFloat()
        val offsetY = loc[1].toFloat()

        canvas.save()
        canvas.translate(board.left - offsetX, board.top - offsetY)
        val size = board.width()
        val cellSize = size / 8f

        val len = 28f
        canvas.drawLine(0f, 0f, len, 0f, cornerPaint); canvas.drawLine(0f, 0f, 0f, len, cornerPaint)
        canvas.drawLine(size - len, 0f, size, 0f, cornerPaint); canvas.drawLine(size, 0f, size, len, cornerPaint)
        canvas.drawLine(0f, size - len, 0f, size, cornerPaint); canvas.drawLine(0f, size, len, size, cornerPaint)
        canvas.drawLine(size - len, size, size, size, cornerPaint); canvas.drawLine(size, size - len, size, size, cornerPaint)

        val fSq = fromSquare
        val tSq = toSquare
        if (fSq != null && tSq != null) {
            val startCenter = getSquareCenterLocal(fSq, cellSize)
            val endCenter = getSquareCenterLocal(tSq, cellSize)

            canvas.drawRoundRect(
                startCenter.x - cellSize / 2 + 4f, startCenter.y - cellSize / 2 + 4f,
                startCenter.x + cellSize / 2 - 4f, startCenter.y + cellSize / 2 - 4f,
                12f, 12f, highlightFromPaint
            )
            canvas.drawRoundRect(
                endCenter.x - cellSize / 2 + 4f, endCenter.y - cellSize / 2 + 4f,
                endCenter.x + cellSize / 2 - 4f, endCenter.y + cellSize / 2 - 4f,
                12f, 12f, highlightToPaint
            )
            drawArrowLocal(canvas, startCenter.x, startCenter.y, endCenter.x, endCenter.y)

            promotionPiece?.let { promo ->
                val label = when (promo) {
                    'Q' -> "PHONG HẬU (Q)"
                    'N' -> "PHONG MÃ (N)"
                    'R' -> "PHONG XE (R)"
                    'B' -> "PHONG TƯỢNG (B)"
                    else -> "PHONG $promo"
                }
                val badgeRect = RectF(endCenter.x - 120f, endCenter.y - cellSize / 2 - 46f, endCenter.x + 120f, endCenter.y - cellSize / 2 - 4f)
                canvas.drawRoundRect(badgeRect, 12f, 12f, promoBgPaint)
                canvas.drawText(label, endCenter.x, badgeRect.centerY() + 11f, promoTextPaint)
            }
        }
        canvas.restore()
    }

    private fun getSquareCenterLocal(square: String, cellSize: Float): PointF {
        val file = (square[0] - 'a').coerceIn(0, 7)
        val rank = (square[1] - '1').coerceIn(0, 7)
        val col = if (isWhiteBottom) file else 7 - file
        val row = if (isWhiteBottom) 7 - rank else rank
        return PointF((col + 0.5f) * cellSize, (row + 0.5f) * cellSize)
    }

    private fun drawArrowLocal(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float) {
        canvas.drawLine(startX, startY, endX, endY, arrowPaint)
        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val arrowLength = 46f
        val arrowAngle = Math.toRadians(32.0)
        val x1 = endX - arrowLength * cos(angle - arrowAngle).toFloat()
        val y1 = endY - arrowLength * sin(angle - arrowAngle).toFloat()
        val x2 = endX - arrowLength * cos(angle + arrowAngle).toFloat()
        val y2 = endY - arrowLength * sin(angle + arrowAngle).toFloat()
        val path = Path().apply {
            moveTo(endX, endY); lineTo(x1, y1); lineTo(x2, y2); close()
        }
        canvas.drawPath(path, arrowHeadPaint)
    }
}
