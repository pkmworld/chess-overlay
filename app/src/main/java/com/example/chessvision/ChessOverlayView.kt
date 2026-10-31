package com.example.chessvision

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ChessOverlayView(context: Context) : View(context) {

    @Volatile var detectedBoardRect: RectF? = null
    @Volatile var isWhiteBottom = true
    
    @Volatile var isCalibrationMode = false
    @Volatile var isEditorMode = false
    @Volatile var currentMatrix: Array<CharArray>? = null
    
    var onSquareClicked: ((Int, Int) -> Unit)? = null

    @Volatile var fromSquare: String? = null
        private set
    @Volatile var toSquare: String? = null
        private set
    private var promotionPiece: Char? = null

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 50, 50)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val highlightFromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 214, 0); style = Paint.Style.FILL
    }
    private val highlightToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 0, 230, 118); style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 0, 230, 118); style = Paint.Style.STROKE
        strokeWidth = 24f; strokeCap = Paint.Cap.ROUND
    }
    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 0, 230, 118); style = Paint.Style.FILL
    }

    private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val textBgPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 220, 220, 220); style = Paint.Style.FILL }
    private val textBgPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 40, 40, 40); style = Paint.Style.FILL }
    private val textPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val textPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val promoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 213, 0, 0); style = Paint.Style.FILL }
    private val promoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

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

    fun hasActiveArrow(): Boolean = fromSquare != null && toSquare != null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditorMode) return false
        val board = detectedBoardRect ?: return false
        
        if (event.action == MotionEvent.ACTION_DOWN) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val touchX = event.rawX - loc[0]
            val touchY = event.rawY - loc[1]

            if (board.contains(touchX, touchY)) {
                val cellSize = board.width() / 8f
                val c = ((touchX - board.left) / cellSize).toInt().coerceIn(0, 7)
                val r = ((touchY - board.top) / cellSize).toInt().coerceIn(0, 7)
                
                val actualR = if (isWhiteBottom) r else 7 - r
                val actualC = if (isWhiteBottom) c else 7 - c
                onSquareClicked?.invoke(actualR, actualC)
                return true
            }
        }
        return super.onTouchEvent(event)
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

        if (isCalibrationMode) {
            canvas.drawRect(0f, 0f, size, size, gridPaint)
            for (i in 1..7) {
                val pos = i * cellSize
                canvas.drawLine(pos, 0f, pos, size, gridPaint)
                canvas.drawLine(0f, pos, size, pos, gridPaint)
            }
            canvas.restore()
            return
        }

        val len = 32f
        canvas.drawLine(0f, 0f, len, 0f, cornerPaint); canvas.drawLine(0f, 0f, 0f, len, cornerPaint)
        canvas.drawLine(size - len, 0f, size, 0f, cornerPaint); canvas.drawLine(size, 0f, size, len, cornerPaint)
        canvas.drawLine(0f, size - len, 0f, size, cornerPaint); canvas.drawLine(0f, size, len, size, cornerPaint)
        canvas.drawLine(size - len, size, size, size, cornerPaint); canvas.drawLine(size, size - len, size, size, cornerPaint)

        if (isEditorMode) {
            canvas.drawRect(0f, 0f, size, size, dimPaint)
            val matrix = currentMatrix
            if (matrix != null) {
                for (r in 0..7) {
                    for (c in 0..7) {
                        val ch = matrix[r][c]
                        if (ch != '1') {
                            val dr = if (isWhiteBottom) r else 7 - r
                            val dc = if (isWhiteBottom) c else 7 - c
                            val cx = dc * cellSize + cellSize / 2f
                            val cy = dr * cellSize + cellSize / 2f
                            
                            val isW = ch.isUpperCase()
                            val name = when(ch.uppercaseChar()) {
                                'P' -> "TỐT"
                                'N' -> "MÃ"
                                'B' -> "TƯỢNG"
                                'R' -> "XE"
                                'Q' -> "HẬU"
                                'K' -> "VUA"
                                else -> "?"
                            }
                            val label = if (isW) "$name ⚪" else "$name ⚫"
                            val bgPaint = if (isW) textBgPaintWhite else textBgPaintBlack
                            val txtPaint = if (isW) textPaintWhite else textPaintBlack
                            
                            val textBounds = Rect()
                            txtPaint.getTextBounds(label, 0, label.length, textBounds)
                            val pad = 12f
                            val rectF = RectF(cx - textBounds.width()/2f - pad, cy - textBounds.height()/2f - pad, cx + textBounds.width()/2f + pad, cy + textBounds.height()/2f + pad)
                            canvas.drawRoundRect(rectF, 12f, 12f, bgPaint)
                            canvas.drawText(label, cx, cy + textBounds.height()/2f + 4f, txtPaint)
                        }
                    }
                }
            }
            canvas.restore()
            return
        }

        val fSq = fromSquare
        val tSq = toSquare
        if (fSq != null && tSq != null) {
            val startCenter = getSquareCenterLocal(fSq, cellSize)
            val endCenter = getSquareCenterLocal(tSq, cellSize)

            canvas.drawRoundRect(startCenter.x - cellSize/2 + 4f, startCenter.y - cellSize/2 + 4f, startCenter.x + cellSize/2 - 4f, startCenter.y + cellSize/2 - 4f, 12f, 12f, highlightFromPaint)
            canvas.drawRoundRect(endCenter.x - cellSize/2 + 4f, endCenter.y - cellSize/2 + 4f, endCenter.x + cellSize/2 - 4f, endCenter.y + cellSize/2 - 4f, 12f, 12f, highlightToPaint)
            drawArrowLocal(canvas, startCenter.x, startCenter.y, endCenter.x, endCenter.y)

            promotionPiece?.let { promo ->
                val label = when (promo) {
                    'Q' -> "PHONG HẬU (Q)"
                    'N' -> "PHONG MÃ (N)"
                    'R' -> "PHONG XE (R)"
                    'B' -> "PHONG TƯỢNG (B)"
                    else -> "PHONG $promo"
                }
                val badgeTop = (endCenter.y - cellSize / 2 - 46f).coerceAtLeast(4f)
                val badgeRect = RectF(endCenter.x - 115f, badgeTop, endCenter.x + 115f, badgeTop + 42f)
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
        val path = Path().apply { moveTo(endX, endY); lineTo(x1, y1); lineTo(x2, y2); close() }
        canvas.drawPath(path, arrowHeadPaint)
    }
}
