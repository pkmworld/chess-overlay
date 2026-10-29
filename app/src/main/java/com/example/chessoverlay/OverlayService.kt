package com.example.chessoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var controlLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var lastCleanBoardBmp: Bitmap? = null
    private var lastAnalyzedBoardPart: String = ""
    private var lastSuggestedMove: String? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var isAutoMode = false
    private var isScanning = false

    private var sideMode = 0
    private val learnedSilhouettes = mutableMapOf<Char, BooleanArray>()

    private data class CellFeatures(
        val isOccupied: Boolean,
        val coreLum: Int,
        val fillRatio: Float,
        val asym: Float,
        val topRatio: Float,
        val solidMask: BooleanArray
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ChessCoachPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        setupBoardOverlay()
        setupFloatingControls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                setupVirtualDisplay()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupBoardOverlay() {
        try {
            val defaultTopY = (screenHeight - screenWidth) * 0.62f
            val savedTopY = prefs.getFloat("BOARD_TOP_Y", defaultTopY)

            chessOverlayView = ChessOverlayView(this).apply {
                boardTopY = savedTopY
            }

            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(chessOverlayView, overlayParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupFloatingControls() {
        try {
            controlLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(242, 18, 18, 18))
                setPadding(16, 10, 16, 10)
            }

            tvStatus = TextView(this).apply {
                text = "Stockfish 16 v3.3 | Bấm [⚡ Gợi ý]"
                setTextColor(Color.parseColor("#69F0AE"))
                textSize = 11.5f
            }

            val rowButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnUp = createSmallButton("▲") {
                chessOverlayView.boardTopY -= 8f
                prefs.edit().putFloat("BOARD_TOP_Y", chessOverlayView.boardTopY).apply()
                learnedSilhouettes.clear()
                lastAnalyzedBoardPart = ""
                chessOverlayView.setMove(null)
            }
            val btnDown = createSmallButton("▼") {
                chessOverlayView.boardTopY += 8f
                prefs.edit().putFloat("BOARD_TOP_Y", chessOverlayView.boardTopY).apply()
                learnedSilhouettes.clear()
                lastAnalyzedBoardPart = ""
                chessOverlayView.setMove(null)
            }
            val btnAnalyze = createSmallButton("⚡ Gợi ý") {
                scanAndSuggestMove(forceRefresh = true)
            }
            val btnAuto = createSmallButton("Auto: TẮT") { btn ->
                isAutoMode = !isAutoMode
                btn.text = if (isAutoMode) "Auto: BẬT" else "Auto: TẮT"
                if (isAutoMode) runAutoLoop()
            }
            val btnSide = createSmallButton("Phe: Tự động") { btn ->
                sideMode = (sideMode + 1) % 3
                btn.text = when (sideMode) {
                    1 -> "Phe: Trắng"
                    2 -> "Phe: Đen"
                    else -> "Phe: Tự động"
                }
                learnedSilhouettes.clear()
                lastAnalyzedBoardPart = ""
                scanAndSuggestMove(forceRefresh = true)
            }
            val btnClose = createSmallButton("✕") {
                isAutoMode = false
                stopSelf()
            }

            rowButtons.addView(btnUp)
            rowButtons.addView(btnDown)
            rowButtons.addView(btnAnalyze)
            rowButtons.addView(btnAuto)
            rowButtons.addView(btnSide)
            rowButtons.addView(btnClose)

            controlLayout.addView(tvStatus)
            controlLayout.addView(rowButtons)

            val controlParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 60
            }

            windowManager.addView(controlLayout, controlParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createSmallButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10.5f
            minWidth = 0
            minimumWidth = 0
            setPadding(14, 6, 14, 6)
            setOnClickListener { onClick(this) }
        }
    }

    private fun setupVirtualDisplay() {
        try {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    virtualDisplay?.release()
                    mediaProjection = null
                }
            }, mainHandler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ChessScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun safeCrop(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val safeX = x.coerceIn(0, src.width - 1)
        val safeY = y.coerceIn(0, src.height - 1)
        val safeW = w.coerceAtMost(src.width - safeX)
        val safeH = h.coerceAtMost(src.height - safeY)
        if (safeW <= 2 || safeH <= 2) return null
        return Bitmap.createBitmap(src, safeX, safeY, safeW, safeH)
    }

    private fun captureCleanBoardBitmap(): Bitmap? {
        val reader = imageReader ?: return lastCleanBoardBmp
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return lastCleanBoardBmp

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)

            val maxTop = (screenHeight - screenWidth).coerceAtLeast(0)
            val boardY = chessOverlayView.boardTopY.toInt().coerceIn(0, maxTop)
            val cropped = safeCrop(fullBmp, 0, boardY, screenWidth, screenWidth)
            
            // Giải phóng rác bộ nhớ toàn màn hình tránh tràn RAM
            fullBmp.recycle()

            if (cropped != null) {
                lastCleanBoardBmp = cropped
            }
            cropped ?: lastCleanBoardBmp
        } catch (e: Exception) {
            e.printStackTrace()
            lastCleanBoardBmp
        } finally {
            image.close()
        }
    }

    private fun scanAndSuggestMove(forceRefresh: Boolean) {
        if (isScanning) return
        isScanning = true

        if (forceRefresh) {
            chessOverlayView.setMove(null)
            tvStatus.text = "Đang quét bàn cờ..."
        }

        chessOverlayView.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            val boardBmp = captureCleanBoardBitmap()
            chessOverlayView.visibility = View.VISIBLE

            if (boardBmp == null) {
                tvStatus.text = "Chờ hình ảnh, bấm lại!"
                isScanning = false
                return@postDelayed
            }

            workerExecutor.execute {
                try {
                    val analysis = analyzeBoardTwoPass(boardBmp)
                    val currentFen = analysis.first
                    val isWhiteBottom = analysis.second
                    val boardPart = currentFen.substringBefore(" ")
                    val sideLabel = if (isWhiteBottom) "Trắng" else "Đen"
                    val modeTag = if (learnedSilhouettes.size >= 6) "Chuẩn" else "Tự động"

                    if (!forceRefresh && boardPart == lastAnalyzedBoardPart && lastSuggestedMove != null) {
                        mainHandler.post {
                            chessOverlayView.isWhiteBottom = isWhiteBottom
                            chessOverlayView.setMove(lastSuggestedMove)
                            isScanning = false
                        }
                        return@execute
                    }

                    mainHandler.post {
                        chessOverlayView.isWhiteBottom = isWhiteBottom
                        tvStatus.text = "[$sideLabel - $modeTag] Stockfish đang tính..."
                    }

                    val result = fetchBestMoveDualEngine(currentFen)
                    mainHandler.post {
                        if (result.first != null) {
                            val bestMove = result.first!!
                            lastAnalyzedBoardPart = boardPart
                            lastSuggestedMove = bestMove
                            chessOverlayView.setMove(bestMove)
                            tvStatus.text = "[$sideLabel] Đi: ${bestMove.substring(0, 2)} ➔ ${bestMove.substring(2, 4)}"
                        } else {
                            tvStatus.text = "Lỗi: ${result.second}"
                        }
                        isScanning = false
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        tvStatus.text = "Lỗi xử lý: ${e.message}"
                        isScanning = false
                    }
                }
            }
        }, 120)
    }

    private fun runAutoLoop() {
        if (!isAutoMode) return
        scanAndSuggestMove(forceRefresh = false)
        mainHandler.postDelayed({ runAutoLoop() }, 3000)
    }

    private fun analyzeBoardTwoPass(boardBmp: Bitmap): Pair<String, Boolean> {
        val cellSize = boardBmp.width / 8f
        val features = Array(8) { r ->
            Array(8) { c ->
                val cellBmp = safeCrop(
                    boardBmp,
                    (c * cellSize).toInt(),
                    (r * cellSize).toInt(),
                    cellSize.toInt(),
                    cellSize.toInt()
                )
                val res = if (cellBmp != null) extractCellFeaturesSpanFill(cellBmp)
                else CellFeatures(false, 128, 0f, 0f, 0f, BooleanArray(256))
                cellBmp?.recycle()
                res
            }
        }

        val occupiedCoreLums = mutableListOf<Int>()
        for (r in 0..7) {
            for (c in 0..7) {
                if (features[r][c].isOccupied) {
                    occupiedCoreLums.add(features[r][c].coreLum)
                }
            }
        }
        occupiedCoreLums.sort()
        val lumThreshold = if (occupiedCoreLums.size >= 4) {
            val minLum = occupiedCoreLums[occupiedCoreLums.size / 5]
            val maxLum = occupiedCoreLums[occupiedCoreLums.size - 1 - occupiedCoreLums.size / 5]
            (minLum + maxLum) / 2
        } else 130

        val isWhiteBottom = when (sideMode) {
            1 -> true
            2 -> false
            else -> {
                var bottomWhiteScore = 0
                var topWhiteScore = 0
                for (r in 5..7) {
                    for (c in 0..7) {
                        if (features[r][c].isOccupied) {
                            if (features[r][c].coreLum >= lumThreshold) bottomWhiteScore += 2 else bottomWhiteScore -= 2
                        }
                    }
                }
                for (r in 0..2) {
                    for (c in 0..7) {
                        if (features[r][c].isOccupied) {
                            if (features[r][c].coreLum >= lumThreshold) topWhiteScore += 2 else topWhiteScore -= 2
                        }
                    }
                }
                bottomWhiteScore >= topWhiteScore
            }
        }

        tryAutoLearnTemplates(features, isWhiteBottom)

        val rawMatrix = Array(8) { CharArray(8) { '1' } }
        for (r in 0..7) {
            for (c in 0..7) {
                val f = features[r][c]
                if (!f.isOccupied) {
                    rawMatrix[r][c] = '1'
                } else {
                    val isWhitePiece = f.coreLum >= lumThreshold
                    val pieceType = identifyPieceType(f, r, c)
                    rawMatrix[r][c] = if (isWhitePiece) pieceType.uppercaseChar() else pieceType.lowercaseChar()
                }
            }
        }

        val standardMatrix = if (isWhiteBottom) {
            rawMatrix
        } else {
            Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }
        }

        val sideToMove = if (isWhiteBottom) 'w' else 'b'
        sanitizeMatrixForStockfish(standardMatrix, sideToMove)

        val fenRows = mutableListOf<String>()
        for (r in 0..7) {
            val sb = StringBuilder()
            var emptyCount = 0
            for (c in 0..7) {
                val ch = standardMatrix[r][c]
                if (ch == '1') {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(ch)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            fenRows.add(sb.toString())
        }

        val fen = "${fenRows.joinToString("/")} $sideToMove - - 0 1"
        return Pair(fen, isWhiteBottom)
    }

    private fun tryAutoLearnTemplates(features: Array<Array<CellFeatures>>, isWhiteBottom: Boolean) {
        val backRankOrder = if (isWhiteBottom) {
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        } else {
            charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R')
        }

        val row0Full = (0..7).all { features[0][it].isOccupied }
        val row7Full = (0..7).all { features[7][it].isOccupied }
        val pawnsCount = (0..7).count { features[1][it].isOccupied } + (0..7).count { features[6][it].isOccupied }

        if ((row0Full || row7Full) && pawnsCount >= 12) {
            val sourceRow = if (row7Full) 7 else 0
            learnedSilhouettes.clear()
            for (c in 0..7) {
                val pChar = backRankOrder[c]
                if (!learnedSilhouettes.containsKey(pChar)) {
                    learnedSilhouettes[pChar] = features[sourceRow][c].solidMask
                }
            }
            for (c in 0..7) {
                if (features[6][c].isOccupied) {
                    learnedSilhouettes['P'] = features[6][c].solidMask
                    break
                } else if (features[1][c].isOccupied) {
                    learnedSilhouettes['P'] = features[1][c].solidMask
                    break
                }
            }
        }
    }

    private fun extractCellFeaturesSpanFill(cell: Bitmap): CellFeatures {
        val w = cell.width
        val h = cell.height

        val sampleCoords = arrayOf(
            Pair((w * 0.14f).toInt(), (h * 0.20f).toInt()),
            Pair((w * 0.86f).toInt(), (h * 0.20f).toInt()),
            Pair((w * 0.14f).toInt(), (h * 0.80f).toInt()),
            Pair((w * 0.86f).toInt(), (h * 0.80f).toInt())
        )
        var bgR = 0
        var bgG = 0
        var bgB = 0
        for ((sx, sy) in sampleCoords) {
            val p = cell.getPixel(sx.coerceIn(0, w - 1), sy.coerceIn(0, h - 1))
            bgR += Color.red(p)
            bgG += Color.green(p)
            bgB += Color.blue(p)
        }
        bgR /= 4
        bgG /= 4
        bgB /= 4

        val solidMask = BooleanArray(256)
        var filledPixels = 0
        val interiorLums = mutableListOf<Int>()
        val allFgLums = mutableListOf<Int>()
        var leftFg = 0
        var rightFg = 0
        var topFg = 0

        for (iy in 0 until 16) {
            val py = (h * (0.14f + 0.72f * iy / 15f)).toInt().coerceIn(0, h - 1)
            var firstX = -1
            var lastX = -1
            val rowLums = IntArray(16)

            for (ix in 0 until 16) {
                val px = (w * (0.14f + 0.72f * ix / 15f)).toInt().coerceIn(0, w - 1)
                val p = cell.getPixel(px, py)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                rowLums[ix] = (r * 299 + g * 587 + b * 114) / 1000
                val diff = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                if (diff > 38) {
                    if (firstX == -1) firstX = ix
                    lastX = ix
                }
            }

            if (firstX != -1 && lastX >= firstX) {
                for (ix in firstX..lastX) {
                    solidMask[iy * 16 + ix] = true
                    filledPixels++
                    allFgLums.add(rowLums[ix])
                    if (ix < 8) leftFg++ else rightFg++
                    if (iy < 5) topFg++
                }
                if (iy in 3..13 && lastX - firstX >= 2) {
                    for (ix in (firstX + 1) until lastX) {
                        interiorLums.add(rowLums[ix])
                    }
                }
            }
        }

        val fillRatio = filledPixels / 256f
        val isOccupied = fillRatio >= 0.14f

        val targetLums = if (interiorLums.size >= 6) interiorLums else allFgLums
        val coreLum = if (targetLums.isNotEmpty()) {
            targetLums.sort()
            val startIdx = targetLums.size / 2
            var sum = 0
            var cnt = 0
            for (i in startIdx until targetLums.size) {
                sum += targetLums[i]
                cnt++
            }
            if (cnt > 0) sum / cnt else 128
        } else 128

        val asym = if (filledPixels > 0) abs(leftFg - rightFg).toFloat() / filledPixels else 0f
        val topRatio = if (filledPixels > 0) topFg.toFloat() / filledPixels else 0f

        return CellFeatures(isOccupied, coreLum, fillRatio, asym, topRatio, solidMask)
    }

    private fun identifyPieceType(f: CellFeatures, row: Int, col: Int): Char {
        if (learnedSilhouettes.size >= 6) {
            var bestPiece = 'P'
            var minDiff = Int.MAX_VALUE
            for ((pieceChar, templateMask) in learnedSilhouettes) {
                if (pieceChar == 'P' && (row == 0 || row == 7)) continue
                var diffCount = 0
                for (i in 0 until 256) {
                    if (f.solidMask[i] != templateMask[i]) diffCount++
                }
                if (diffCount < minDiff) {
                    minDiff = diffCount
                    bestPiece = pieceChar
                }
            }
            return bestPiece
        }

        if (row in 1..6 && f.fillRatio < 0.34f) return 'P'
        if (f.asym > 0.16f) return 'N'
        if (f.topRatio > 0.28f) return 'R'
        if (f.fillRatio > 0.48f) return if (col == 4) 'K' else 'Q'
        return 'B'
    }

    private fun sanitizeMatrixForStockfish(matrix: Array<CharArray>, sideToMove: Char) {
        for (c in 0..7) {
            if (matrix[0][c] == 'P') matrix[0][c] = 'Q'
            if (matrix[0][c] == 'p') matrix[0][c] = 'r'
            if (matrix[7][c] == 'P') matrix[7][c] = 'R'
            if (matrix[7][c] == 'p') matrix[7][c] = 'q'
        }

        val wkPos = enforceSingleKing(matrix, 'K', 'Q', 7, 4)
        var bkPos = enforceSingleKing(matrix, 'k', 'q', 0, 4)

        if (max(abs(wkPos.first - bkPos.first), abs(wkPos.second - bkPos.second)) <= 1) {
            matrix[bkPos.first][bkPos.second] = 'q'
            val safeRow = if (wkPos.first >= 4) 0 else 7
            matrix[safeRow][4] = 'k'
            bkPos = Pair(safeRow, 4)
        }

        val oppKingPos = if (sideToMove == 'w') bkPos else wkPos
        val kr = oppKingPos.first
        val kc = oppKingPos.second

        val dirs = arrayOf(
            Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1),
            Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1)
        )
        for ((dr, dc) in dirs) {
            var r = kr + dr
            var c = kc + dc
            while (r in 0..7 && c in 0..7) {
                val ch = matrix[r][c]
                if (ch != '1') {
                    val isAttacker = if (sideToMove == 'w') ch.isUpperCase() else ch.isLowerCase()
                    if (isAttacker) {
                        val up = ch.uppercaseChar()
                        val isStraight = (dr == 0 || dc == 0)
                        if ((isStraight && (up == 'R' || up == 'Q')) || (!isStraight && (up == 'B' || up == 'Q'))) {
                            matrix[r][c] = if (sideToMove == 'w') 'N' else 'n'
                        }
                    }
                    break
                }
                r += dr
                c += dc
            }
        }

        val knightMoves = arrayOf(
            Pair(-2, -1), Pair(-2, 1), Pair(-1, -2), Pair(-1, 2),
            Pair(1, -2), Pair(1, 2), Pair(2, -1), Pair(2, 1)
        )
        val attackerKnight = if (sideToMove == 'w') 'N' else 'n'
        val replacementBishop = if (sideToMove == 'w') 'B' else 'b'
        for ((dr, dc) in knightMoves) {
            val r = kr + dr
            val c = kc + dc
            if (r in 0..7 && c in 0..7 && matrix[r][c] == attackerKnight) {
                matrix[r][c] = replacementBishop
            }
        }

        val pawnAttackRow = if (sideToMove == 'w') kr + 1 else kr - 1
        val attackerPawn = if (sideToMove == 'w') 'P' else 'p'
        val replacementKnight = if (sideToMove == 'w') 'N' else 'n'
        if (pawnAttackRow in 0..7) {
            for (dc in intArrayOf(-1, 1)) {
                val c = kc + dc
                if (c in 0..7 && matrix[pawnAttackRow][c] == attackerPawn) {
                    matrix[pawnAttackRow][c] = replacementKnight
                }
            }
        }
    }

    private fun enforceSingleKing(
        matrix: Array<CharArray>,
        kingChar: Char,
        fallbackQueen: Char,
        defaultRow: Int,
        defaultCol: Int
    ): Pair<Int, Int> {
        val positions = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                if (matrix[r][c] == kingChar) positions.add(Pair(r, c))
            }
        }

        if (positions.isEmpty()) {
            for (c in intArrayOf(4, 3, 6, 2, 5, 1, 7, 0)) {
                if (matrix[defaultRow][c] != '1' && matrix[defaultRow][c].uppercaseChar() != 'K') {
                    matrix[defaultRow][c] = kingChar
                    return Pair(defaultRow, c)
                }
            }
            matrix[defaultRow][defaultCol] = kingChar
            return Pair(defaultRow, defaultCol)
        } else {
            positions.sortBy { abs(it.first - defaultRow) * 10 + abs(it.second - defaultCol) }
            for (i in 1 until positions.size) {
                val (r, c) = positions[i]
                matrix[r][c] = fallbackQueen
            }
            return positions[0]
        }
    }

    private fun fetchBestMoveDualEngine(fen: String): Pair<String?, String> {
        val res1 = fetchFromChessApi(fen)
        if (res1.first != null) return res1

        val res2 = fetchFromStockfishOnline(fen)
        if (res2.first != null) return res2

        return Pair(null, res1.second.ifEmpty { res2.second })
    }

    private fun fetchFromChessApi(fen: String): Pair<String?, String> {
        return try {
            val url = URL("https://chess-api.com/v1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                doOutput = true
            }
            val jsonBody = JSONObject().apply {
                put("fen", fen)
                put("depth", 11)
            }
            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                val from = json.optString("from", "")
                val to = json.optString("to", "")
                val moveField = json.optString("move", "")
                val move = when {
                    from.length == 2 && to.length == 2 -> "$from$to"
                    moveField.length >= 4 -> moveField.substring(0, 4)
                    else -> null
                }
                if (move != null) Pair(move, "") else Pair(null, json.optString("text", "FEN lỗi"))
            } else {
                Pair(null, "HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "Lỗi mạng")
        }
    }

    private fun fetchFromStockfishOnline(fen: String): Pair<String?, String> {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=11")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                if (json.optBoolean("success", false)) {
                    val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                    val parts = raw.trim().split(" ")
                    val candidate = if (parts.size >= 2) parts[1] else parts[0]
                    val move = if (candidate.length >= 4) candidate.substring(0, 4) else null
                    Pair(move, "")
                } else {
                    Pair(null, json.optString("data", "FEN không hợp lệ"))
                }
            } else {
                Pair(null, "HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "Lỗi kết nối")
        }
    }

    private fun startForegroundNotification() {
        val channelId = "chess_overlay_channel"
        val channel = NotificationChannel(channelId, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Chess Coach v3.3 (Stockfish 16)")
            .setContentText("Phân tích cờ tự động đang chạy")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false
        workerExecutor.shutdownNow()
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView)
        if (::controlLayout.isInitialized) windowManager.removeView(controlLayout)
    }
}
