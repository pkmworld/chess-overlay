package com.example.chessvision

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var controlLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnSide: Button
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var isAutoMode = false
    private var isScanning = false
    private var lastAnalyzedFen = ""
    private var cachedBoardTopY = -1

    private val learnedShapes = mutableMapOf<Char, BooleanArray>()

    data class CellFeature(
        val isOccupied: Boolean,
        val edges: BooleanArray,
        val coreMedianLum: Int,
        val edgeCount: Int,
        val topZoneWidth: Int,
        val midZoneWidth: Int,
        val highestEdgeY: Int,
        val symmetryDiff: Int
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("StealthChessPrefs", Context.MODE_PRIVATE)
        cachedBoardTopY = prefs.getInt("SAVED_TOP_Y", -1)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                setupVirtualDisplay()
                // Tự động định vị bàn cờ ngay khi vừa mở
                mainHandler.postDelayed({ scanAndSuggestMove() }, 700)
            } catch (e: Exception) { e.printStackTrace() }
        }
        return START_NOT_STICKY
    }

    private fun setupBoardOverlay() {
        chessOverlayView = ChessOverlayView(this)
        if (cachedBoardTopY > 0) {
            chessOverlayView.detectedBoardRect = RectF(0f, cachedBoardTopY.toFloat(), screenWidth.toFloat(), (cachedBoardTopY + screenWidth).toFloat())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(chessOverlayView, params)
    }

    private fun setupFloatingControls() {
        controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 20, 20, 20))
            setPadding(14, 8, 14, 8)
        }
        tvStatus = TextView(this).apply {
            text = "Stealth v5.2 • Đang tự động khóa vị trí bàn cờ..."
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 11f
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 0) }

        btnSide = createButton("⚪ Trắng") {
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
            btnSide.text = if (chessOverlayView.isWhiteBottom) "⚪ Trắng" else "⚫ Đen"
            lastAnalyzedFen = ""
            chessOverlayView.setMove(null)
            scanAndSuggestMove()
        }

        val btnAuto = createButton("▶ Auto") { btn ->
            isAutoMode = !isAutoMode
            if (isAutoMode) {
                btn.text = "⏸ Auto"
                lastAnalyzedFen = ""
                runAutoLoop()
            } else {
                btn.text = "▶ Auto"
                chessOverlayView.setMove(null)
                tvStatus.text = "Đã dừng Auto."
            }
        }

        val btnScanOnce = createButton("⚡ Quét") {
            lastAnalyzedFen = ""
            scanAndSuggestMove()
        }

        // 2 nút tinh chỉnh nhẹ nếu 4 dấu góc [ ] lệch khỏi bàn cờ (Lưu vĩnh viễn)
        val btnUp = createButton("▲") {
            if (cachedBoardTopY < 0) cachedBoardTopY = (screenHeight - screenWidth) / 2
            cachedBoardTopY = (cachedBoardTopY - 12).coerceAtLeast(50)
            saveTopY()
        }
        val btnDown = createButton("▼") {
            if (cachedBoardTopY < 0) cachedBoardTopY = (screenHeight - screenWidth) / 2
            cachedBoardTopY = (cachedBoardTopY + 12).coerceAtMost(screenHeight - screenWidth - 20)
            saveTopY()
        }

        val btnClose = createButton("✕") { stopSelf() }

        row.addView(btnSide); row.addView(btnAuto); row.addView(btnScanOnce)
        row.addView(btnUp); row.addView(btnDown); row.addView(btnClose)
        controlLayout.addView(tvStatus); controlLayout.addView(row)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 75
        }

        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
        controlLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlParams.x; initialY = controlParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    controlParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    controlParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(controlLayout, controlParams)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(controlLayout, controlParams)
    }

    private fun saveTopY() {
        prefs.edit().putInt("SAVED_TOP_Y", cachedBoardTopY).apply()
        chessOverlayView.detectedBoardRect = RectF(0f, cachedBoardTopY.toFloat(), screenWidth.toFloat(), (cachedBoardTopY + screenWidth).toFloat())
        chessOverlayView.invalidate()
        lastAnalyzedFen = ""
        tvStatus.text = "Đã lưu vị trí Y=$cachedBoardTopY • Đang quét lại..."
        scanAndSuggestMove()
    }

    private fun createButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 10f; setPadding(10, 5, 10, 5)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick(this) }
        }
    }

    private fun setupVirtualDisplay() {
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { virtualDisplay?.release(); mediaProjection = null }
        }, mainHandler)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    // THUẬT TOÁN TÌM CHÍNH XÁC Mép BÀN CỜ QUA 7 ĐƯỜNG RANH GIỚI CỘT + ĐỘ ĐỒNG NHẤT MÀU Ô
    private fun findChessboardTopY(fullBmp: Bitmap): Int {
        val w = screenWidth
        val h = screenHeight
        val cellSize = w / 8f
        val searchStart = (h * 0.22f).toInt()
        val searchEnd = (h * 0.46f).toInt().coerceAtMost(h - w - 10)

        var bestTopY = (h * 0.318f).toInt()
        var maxBoardScore = Int.MIN_VALUE

        for (candidateY in searchStart..searchEnd step 4) {
            var score = 0
            for (r in 0..7) {
                // Lấy điểm ở góc trên mỗi ô (tránh vùng bụng quân cờ)
                val y = (candidateY + r * cellSize + cellSize * 0.14f).toInt()
                if (y >= h) continue
                var evenSum = 0; var oddSum = 0
                for (c in 0..7) {
                    val x = (c * cellSize + cellSize * 0.14f).toInt().coerceIn(0, w - 1)
                    val p = fullBmp.getPixel(x, y)
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if ((r + c) % 2 == 0) evenSum += lum else oddSum += lum
                }
                val avgEven = evenSum / 4
                val avgOdd = oddSum / 4
                val contrast = abs(avgEven - avgOdd)
                // Bàn cờ thực sự có độ chênh màu giữa ô chẵn và ô lẻ từ 14 đến 160 đơn vị trên cả 8 hàng
                if (contrast in 14..160) score += 25 else score -= 20
            }
            if (score > maxBoardScore) {
                maxBoardScore = score
                bestTopY = candidateY
            }
        }
        return bestTopY
    }

    private fun captureCleanBoard(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val fullBmp = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            fullBmp.copyPixelsFromBuffer(buffer)

            val w = screenWidth
            val h = screenHeight

            if (cachedBoardTopY < 0) {
                cachedBoardTopY = findChessboardTopY(fullBmp)
                prefs.edit().putInt("SAVED_TOP_Y", cachedBoardTopY).apply()
            }
            val topY = cachedBoardTopY.coerceIn(0, h - w)

            val rect = RectF(0f, topY.toFloat(), w.toFloat(), (topY + w).toFloat())
            mainHandler.post {
                chessOverlayView.detectedBoardRect = rect
                chessOverlayView.invalidate()
            }

            val cropped = Bitmap.createBitmap(fullBmp, 0, topY, w, w)
            fullBmp.recycle()
            cropped
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun analyzeCell(cell: Bitmap): CellFeature {
        val scaled = Bitmap.createScaledBitmap(cell, 32, 32, true)
        val w = 32; val h = 32
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }

        val edges = BooleanArray(w * h)
        var edgeCount = 0
        var highestEdgeY = 32
        var topMinX = 32; var topMaxX = 0
        var midMinX = 32; var midMaxX = 0
        var symmetryDiff = 0

        for (y in 4 until h - 4) {
            for (x in 4 until w - 4) {
                val idx = y * w + x
                val gx = abs(gray[idx + 1] - gray[idx - 1])
                val gy = abs(gray[idx + w] - gray[idx - w])
                val grad = gx + gy

                if (grad > 26) {
                    edges[idx] = true
                    edgeCount++
                    if (y < highestEdgeY) highestEdgeY = y
                    if (y in 5..12) {
                        if (x < topMinX) topMinX = x
                        if (x > topMaxX) topMaxX = x
                    }
                    if (y in 13..20) {
                        if (x < midMinX) midMinX = x
                        if (x > midMaxX) midMaxX = x
                    }
                }
                if (x in 7..15) {
                    val mirrorIdx = y * w + (31 - x)
                    symmetryDiff += abs(gray[idx] - gray[mirrorIdx])
                }
            }
        }

        // LẤY ĐỘ SÁNG TRUNG VỊ (MEDIAN) VÙNG LÕI BỤNG QUÂN CỜ ĐỂ TÁCH BIỆT TUYỆT ĐỐI TRẮNG & ĐEN
        val coreLums = IntArray(81)
        var k = 0
        for (y in 14..22) {
            for (x in 12..20) {
                coreLums[k++] = gray[y * w + x]
            }
        }
        coreLums.sort()
        val coreMedianLum = coreLums[40]

        val topWidth = if (topMaxX >= topMinX) topMaxX - topMinX else 0
        val midWidth = if (midMaxX >= midMinX) midMaxX - midMinX else 0
        val isOccupied = edgeCount >= 22

        return CellFeature(isOccupied, edges, coreMedianLum, edgeCount, topWidth, midWidth, highestEdgeY, symmetryDiff)
    }

    private fun scanAndSuggestMove() {
        if (isScanning) return
        isScanning = true

        chessOverlayView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val boardBmp = captureCleanBoard()
            chessOverlayView.visibility = View.VISIBLE
            if (boardBmp == null) {
                isScanning = false
                return@postDelayed
            }

            workerExecutor.execute {
                try {
                    val cellSize = boardBmp.width / 8f
                    val occupiedLums = mutableListOf<Int>()

                    val features = Array(8) { r ->
                        Array(8) { c ->
                            val x = (c * cellSize).toInt().coerceIn(0, boardBmp.width - 4)
                            val y = (r * cellSize).toInt().coerceIn(0, boardBmp.height - 4)
                            val size = cellSize.toInt().coerceIn(4, boardBmp.width - x).coerceAtMost(boardBmp.height - y)
                            val cellBmp = Bitmap.createBitmap(boardBmp, x, y, size, size)
                            val feat = analyzeCell(cellBmp)
                            cellBmp.recycle()
                            if (feat.isOccupied) occupiedLums.add(feat.coreMedianLum)
                            feat
                        }
                    }
                    boardBmp.recycle()

                    if (occupiedLums.size < 2) {
                        mainHandler.post { tvStatus.text = "Chưa thấy quân cờ! Kiểm tra 4 góc xanh [ ]" }
                        isScanning = false
                        return@execute
                    }

                    occupiedLums.sort()
                    val minLum = occupiedLums.first()
                    val maxLum = occupiedLums.last()
                    val lumThreshold = (minLum + maxLum) / 2

                    var topRankFull = true
                    var botRankFull = true
                    for (c in 0..7) {
                        if (!features[0][c].isOccupied) topRankFull = false
                        if (!features[7][c].isOccupied) botRankFull = false
                    }

                    if (topRankFull && botRankFull && learnedShapes.isEmpty()) {
                        val isWhiteBot = features[7][4].coreMedianLum > features[0][4].coreMedianLum
                        val order = if (isWhiteBot) charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
                                    else charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R')
                        for (c in 0..7) learnedShapes[order[c]] = features[7][c].edges
                        if (features[6][4].isOccupied) learnedShapes['P'] = features[6][4].edges
                        else if (features[6][0].isOccupied) learnedShapes['P'] = features[6][0].edges

                        mainHandler.post {
                            chessOverlayView.isWhiteBottom = isWhiteBot
                            btnSide.text = if (isWhiteBot) "⚪ Trắng" else "⚫ Đen"
                        }
                    }

                    val rawMatrix = Array(8) { CharArray(8) { '1' } }
                    for (r in 0..7) {
                        for (c in 0..7) {
                            val f = features[r][c]
                            if (!f.isOccupied) continue
                            val isWhite = f.coreMedianLum >= lumThreshold
                            val pieceChar = classifyPiece3Tier(r, c, f)
                            rawMatrix[r][c] = if (isWhite) pieceChar.uppercaseChar() else pieceChar.lowercaseChar()
                        }
                    }

                    val standardMatrix = if (chessOverlayView.isWhiteBottom) rawMatrix
                                         else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }

                    val standardFeatures = if (chessOverlayView.isWhiteBottom) features
                                           else Array(8) { r -> Array(8) { c -> features[7 - r][7 - c] } }

                    sanitizeLogicalBoard(standardMatrix, standardFeatures)

                    var castling = ""
                    if (standardMatrix[7][4] == 'K') {
                        if (standardMatrix[7][7] == 'R') castling += "K"
                        if (standardMatrix[7][0] == 'R') castling += "Q"
                    }
                    if (standardMatrix[0][4] == 'k') {
                        if (standardMatrix[0][7] == 'r') castling += "k"
                        if (standardMatrix[0][0] == 'r') castling += "q"
                    }
                    if (castling.isEmpty()) castling = "-"

                    val boardPart = buildFenBoard(standardMatrix)
                    if (boardPart == lastAnalyzedFen) {
                        isScanning = false
                        return@execute
                    }

                    val sideToMove = if (chessOverlayView.isWhiteBottom) "w" else "b"
                    val primaryFen = "$boardPart $sideToMove $castling - 0 1"

                    // Thử gọi Stockfish với FEN chính, nếu thế cờ đọc hơi nhiễu bị từ chối thì dùng FEN an toàn dự phòng
                    var move = fetchStockfishWithFallback(primaryFen)
                    if (move == null) {
                        val safeMatrix = Array(8) { r -> standardMatrix[r].clone() }
                        val safeBoard = buildSafeFallbackFen(safeMatrix)
                        move = fetchStockfishWithFallback("$safeBoard $sideToMove - - 0 1")
                    }

                    mainHandler.post {
                        if (move != null && move.length >= 4) {
                            lastAnalyzedFen = boardPart
                            chessOverlayView.setMove(move)
                            tvStatus.text = "✅ Đi: ${move.substring(0, 2).uppercase()} ➔ ${move.substring(2, 4).uppercase()} (${occupiedLums.size} quân)"
                        } else {
                            tvStatus.text = "Chưa có nước đi (${occupiedLums.size} quân) • Chỉnh ▲/▼ nếu lệch góc"
                        }
                        isScanning = false
                    }
                } catch (e: Exception) {
                    mainHandler.post { tvStatus.text = "Lỗi: ${e.message}"; isScanning = false }
                }
            }
        }, 80)
    }

    private fun buildFenBoard(matrix: Array<CharArray>): String {
        val fenRows = mutableListOf<String>()
        for (r in 0..7) {
            var empty = 0
            val sb = StringBuilder()
            for (c in 0..7) {
                if (matrix[r][c] == '1') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(matrix[r][c])
                }
            }
            if (empty > 0) sb.append(empty)
            fenRows.add(sb.toString())
        }
        return fenRows.joinToString("/")
    }

    // Nếu 2 Vua vô tình bị nhận diện đứng sát nhau (bất hợp lệ trong cờ vua), tự động tách ra để Stockfish luôn chạy
    private fun buildSafeFallbackFen(matrix: Array<CharArray>): String {
        var wkR = 7; var wkC = 4; var bkR = 0; var bkC = 4
        for (r in 0..7) for (c in 0..7) {
            if (matrix[r][c] == 'K') { wkR = r; wkC = c }
            if (matrix[r][c] == 'k') { bkR = r; bkC = c }
        }
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) {
            matrix[bkR][bkC] = 'q'
            matrix[0][4] = 'k'
        }
        return buildFenBoard(matrix)
    }

    private fun classifyPiece3Tier(r: Int, c: Int, f: CellFeature): Char {
        if (learnedShapes.isNotEmpty()) {
            var bestP = 'P'
            var minDiff = Int.MAX_VALUE
            for ((pChar, tpl) in learnedShapes) {
                if (pChar == 'P' && (r == 0 || r == 7)) continue
                var diff = 0
                for (i in 0 until 1024) {
                    if (f.edges[i] != tpl[i]) diff++
                }
                if (diff < minDiff) {
                    minDiff = diff
                    bestP = pChar
                }
            }
            if (minDiff < 175) return bestP
        }

        if (f.symmetryDiff > 2800) return 'N'
        if (r != 0 && r != 7 && f.highestEdgeY >= 9 && f.topZoneWidth <= 11 && f.edgeCount < 70) return 'P'
        if (f.highestEdgeY <= 6 && f.topZoneWidth >= 14) return 'Q'
        if (f.highestEdgeY <= 6 && f.topZoneWidth in 8..13) return 'K'
        if (f.topZoneWidth >= 12 && abs(f.topZoneWidth - f.midZoneWidth) <= 3) return 'R'
        if (f.highestEdgeY <= 8 && f.topZoneWidth < f.midZoneWidth) return 'B'
        return if (r == 0 || r == 7) 'R' else 'P'
    }

    private fun sanitizeLogicalBoard(matrix: Array<CharArray>, feats: Array<Array<CellFeature>>) {
        for (c in 0..7) {
            if (matrix[0][c] == 'P') matrix[0][c] = 'Q'
            if (matrix[0][c] == 'p') matrix[0][c] = 'r'
            if (matrix[7][c] == 'P') matrix[7][c] = 'R'
            if (matrix[7][c] == 'p') matrix[7][c] = 'q'
        }
        assignLogicalKing(matrix, feats, isWhite = true)
        assignLogicalKing(matrix, feats, isWhite = false)
    }

    private fun assignLogicalKing(matrix: Array<CharArray>, feats: Array<Array<CellFeature>>, isWhite: Boolean) {
        val kingChar = if (isWhite) 'K' else 'k'
        val queenChar = if (isWhite) 'Q' else 'q'
        val candidates = mutableListOf<Pair<Int, Int>>()

        for (r in 0..7) {
            for (c in 0..7) {
                if (matrix[r][c] == kingChar) candidates.add(Pair(r, c))
            }
        }

        if (candidates.size == 1) return

        if (candidates.size > 1) {
            candidates.sortBy { (r, _) -> if (isWhite) 7 - r else r }
            for (i in 1 until candidates.size) {
                val (r, c) = candidates[i]
                matrix[r][c] = queenChar
            }
            return
        }

        var bestR = -1; var bestC = -1; var bestScore = -999
        for (r in 0..7) {
            for (c in 0..7) {
                val ch = matrix[r][c]
                if (ch != '1' && ch.isUpperCase() == isWhite) {
                    val f = feats[r][c]
                    val rankBonus = if (isWhite) (if (r == 7) 35 else 0) else (if (r == 0) 35 else 0)
                    val score = (32 - f.highestEdgeY) * 3 + f.edgeCount + rankBonus
                    if (score > bestScore) {
                        bestScore = score
                        bestR = r; bestC = c
                    }
                }
            }
        }
        if (bestR != -1) matrix[bestR][bestC] = kingChar
        else {
            val defaultR = if (isWhite) 7 else 0
            matrix[defaultR][4] = kingChar
        }
    }

    private fun runAutoLoop() {
        if (!isAutoMode) return
        scanAndSuggestMove()
        mainHandler.postDelayed({ runAutoLoop() }, 2000)
    }

    // GỌI KÉP 2 SERVER STOCKFISH ĐỂ KHÔNG BAO GIỜ BỊ NGHẼN MẠNG
    private fun fetchStockfishWithFallback(fen: String): String? {
        val move1 = fetchFromStockfishOnline(fen)
        if (move1 != null) return move1
        return fetchFromChessApi(fen)
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=11")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500; readTimeout = 4500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                if (!json.optBoolean("success", true)) return null
                val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                val parts = raw.trim().split(" ")
                val candidate = if (parts.size >= 2) parts[1] else parts[0]
                if (candidate.length >= 4 && candidate != "(none)") candidate.substring(0, 4) else null
            } else null
        } catch (e: Exception) { null }
    }

    private fun fetchFromChessApi(fen: String): String? {
        return try {
            val url = URL("https://chess-api.com/v1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4500; readTimeout = 4500
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().put("fen", fen).put("depth", 10).toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val move = json.optString("move", "")
                if (move.length >= 4) move.substring(0, 4) else null
            } else null
        } catch (e: Exception) { null }
    }

    private fun startForegroundNotification() {
        val channelId = "chess_overlay_channel"
        val channel = NotificationChannel(channelId, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Stealth Vision v5.2")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false
        workerExecutor.shutdownNow()
        virtualDisplay?.release(); mediaProjection?.stop()
        if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView)
        if (::controlLayout.isInitialized) windowManager.removeView(controlLayout)
    }
}
