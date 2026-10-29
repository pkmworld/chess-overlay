package com.example.chessmanual

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ChessOverlayView(context: Context) : View(context) {

    var boardTopY = 400f
    var boardSize = 1080f
    var isWhiteBottom = true

    private var fromSquare: String? = null
    private var toSquare: String? = null

    private val borderPaint = Paint().apply {
        color = Color.argb(160, 0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val highlightFromPaint = Paint().apply {
        color = Color.argb(120, 255, 214, 0)
        style = Paint.Style.FILL
    }

    private val highlightToPaint = Paint().apply {
        color = Color.argb(140, 0, 200, 83)
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 0, 230, 118)
        style = Paint.Style.FILL
    }

    fun setMove(uciMove: String?) {
        if (uciMove != null && uciMove.length >= 4) {
            fromSquare = uciMove.substring(0, 2)
            toSquare = uciMove.substring(2, 4)
        } else {
            fromSquare = null
            toSquare = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        boardSize = width.toFloat()

        canvas.drawRect(0f, boardTopY, boardSize, boardTopY + boardSize, borderPaint)

        val fSq = fromSquare ?: return
        val tSq = toSquare ?: return

        val startCenter = getSquareCenter(fSq)
        val endCenter = getSquareCenter(tSq)
        val cellSize = boardSize / 8f

        canvas.drawRect(
            startCenter.x - cellSize / 2, startCenter.y - cellSize / 2,
            startCenter.x + cellSize / 2, startCenter.y + cellSize / 2,
            highlightFromPaint
        )
        canvas.drawRect(
            endCenter.x - cellSize / 2, endCenter.y - cellSize / 2,
            endCenter.x + cellSize / 2, endCenter.y + cellSize / 2,
            highlightToPaint
        )

        drawArrow(canvas, startCenter.x, startCenter.y, endCenter.x, endCenter.y)
    }

    private fun getSquareCenter(square: String): PointF {
        val file = (square[0] - 'a').coerceIn(0, 7)
        val rank = (square[1] - '1').coerceIn(0, 7)

        val col = if (isWhiteBottom) file else 7 - file
        val row = if (isWhiteBottom) 7 - rank else rank

        val cellSize = boardSize / 8f
        val centerX = (col + 0.5f) * cellSize
        val centerY = boardTopY + (row + 0.5f) * cellSize
        return PointF(centerX, centerY)
    }

    private fun drawArrow(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float) {
        canvas.drawLine(startX, startY, endX, endY, arrowPaint)

        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val arrowLength = 50f
        val arrowAngle = Math.toRadians(32.0)

        val x1 = endX - arrowLength * cos(angle - arrowAngle).toFloat()
        val y1 = endY - arrowLength * sin(angle - arrowAngle).toFloat()
        val x2 = endX - arrowLength * cos(angle + arrowAngle).toFloat()
        val y2 = endY - arrowLength * sin(angle + arrowAngle).toFloat()

        val path = Path().apply {
            moveTo(endX, endY)
            lineTo(x1, y1)
            lineTo(x2, y2)
            close()
        }
        canvas.drawPath(path, arrowHeadPaint)
    }
}
