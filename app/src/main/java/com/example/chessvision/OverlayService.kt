package com.example.chessvision

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
import android.widget.Toast
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
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCleanBoardBmp: Bitmap? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var isAutoMode = false
    private var isScanning = false
    private var sideMode = 0
    private var lastAnalyzedFen = ""

    private val learnedEdges = mutableMapOf<Char, BooleanArray>()

    data class CellFeature(
        val isOccupied: Boolean,
        val edges: BooleanArray,
        val coreLum: Int
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ChessVisionPrefs", Context.MODE_PRIVATE)
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
            } catch (e: Exception) { e.printStackTrace() }
        }
        return START_NOT_STICKY
    }

    private fun setupBoardOverlay() {
        val savedTopY = prefs.getFloat("BOARD_TOP_Y", 400f)
        val savedLeftX = prefs.getFloat("BOARD_LEFT_X", 0f)
        val savedSize = prefs.getFloat("BOARD_SIZE", screenWidth.toFloat())

        chessOverlayView = ChessOverlayView(this).apply {
            boardTopY = savedTopY
            boardLeftX = savedLeftX
            boardSize = savedSize
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
            setBackgroundColor(Color.argb(245, 20, 20, 20))
            setPadding(12, 8, 12, 8)
        }
        tvStatus = TextView(this).apply {
            text = "Căn kích thước + vị trí lưới -> [1. Học Cờ] -> [2. Auto]"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 11f
        }
        
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        
        // Nút điều chỉnh vị trí và kích thước khung
        val btnUp = createSmallButton("▲") {
            chessOverlayView.boardTopY -= 15f
            savePrefs()
            chessOverlayView.invalidate()
        }
        val btnDown = createSmallButton("▼") {
            chessOverlayView.boardTopY += 15f
            savePrefs()
            chessOverlayView.invalidate()
        }
        val btnZoomIn = createSmallButton("➕ To") {
            chessOverlayView.boardSize += 30f
            savePrefs()
            chessOverlayView.invalidate()
        }
        val btnZoomOut = createSmallButton("➖ Nhỏ") {
            chessOverlayView.boardSize -= 30f
            savePrefs()
            chessOverlayView.invalidate()
        }
        val btnLearn = createSmallButton("1. Học") { triggerAutoLearn() }
        val btnAuto = createSmallButton("2. Auto") { btn ->
            if (learnedEdges.isEmpty()) {
                Toast.makeText(this, "Phải bấm [1. Học] trước!", Toast.LENGTH_SHORT).show()
                return@createSmallButton
            }
            isAutoMode = !isAutoMode
            btn.text = if (isAutoMode) "2. Đang Auto" else "2. Auto"
            if (isAutoMode) runAutoLoop()
        }
        val btnClose = createSmallButton("✕") { stopSelf() }

        row1.addView(btnUp); row1.addView(btnDown); row1.addView(btnZoomIn)
        row1.addView(btnZoomOut); row1.addView(btnLearn); row1.addView(btnAuto); row1.addView(btnClose)

        controlLayout.addView(tvStatus)
        controlLayout.addView(row1)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 30 }
        windowManager.addView(controlLayout, params)
    }

    private fun savePrefs() {
        prefs.edit()
            .putFloat("BOARD_TOP_Y", chessOverlayView.boardTopY)
            .putFloat("BOARD_LEFT_X", chessOverlayView.boardLeftX)
            .putFloat("BOARD_SIZE", chessOverlayView.boardSize)
            .apply()
    }

    private fun createSmallButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 10f; setPadding(8, 4, 8, 4)
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

    private fun captureBoardBitmap(): Bitmap? {
        val reader = imageReader ?: return lastCleanBoardBmp
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return lastCleanBoardBmp
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val fullBmp = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            fullBmp.copyPixelsFromBuffer(buffer)
            
            val cropX = chessOverlayView.boardLeftX.toInt().coerceIn(0, screenWidth - 100)
            val cropY = chessOverlayView.boardTopY.toInt().coerceIn(0, screenHeight - 100)
            val cropSize = chessOverlayView.boardSize.toInt().coerceIn(100, screenWidth * 2)

            val safeX = cropX.coerceIn(0, fullBmp.width - 2)
            val safeY = cropY.coerceIn(0, fullBmp.height - 2)
            val safeW = cropSize.coerceAtMost(fullBmp.width - safeX)
            val safeH = cropSize.coerceAtMost(fullBmp.height - safeY)

            val cropped = Bitmap.createBitmap(fullBmp, safeX, safeY, safeW, safeH)
            fullBmp.recycle()
            lastCleanBoardBmp = cropped
            cropped
        } catch (e: Exception) { lastCleanBoardBmp } finally { image.close() }
    }

    private fun extractEdgeFeature(cell: Bitmap): CellFeature {
        val scaled = Bitmap.createScaledBitmap(cell, 32, 32, true)
        val w = 32; val h = 32
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }

        val edges = BooleanArray(w * h)
        var edgeCount = 0; var coreLumSum = 0; var corePixels = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val p00 = gray[(y - 1) * w + (x - 1)]
                val p01 = gray[(y - 1) * w + x]
                val p02 = gray[(y - 1) * w + (x + 1)]
                val p10 = gray[y * w + (x - 1)]
                val p12 = gray[y * w + (x + 1)]
                val p20 = gray[(y + 1) * w + (x - 1)]
                val p21 = gray[(y + 1) * w + x]
                val p22 = gray[(y + 1) * w + (x + 1)]

                val gx = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20)
                val gy = (p20 + 2 * p21 + p22) - (p00 + 2 * p01 + p02)
                val mag = abs(gx) + abs(gy)

                if (mag > 100) {
                    edges[y * w + x] = true
                    edgeCount++
                }
                if (x in 10..22 && y in 10..22) {
                    coreLumSum += gray[y * w + x]
                    corePixels++
                }
            }
        }
        scaled.recycle()
        return CellFeature(edgeCount > 25, edges, if (corePixels > 0) coreLumSum / corePixels else 128)
    }

    private fun triggerAutoLearn() {
        chessOverlayView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val boardBmp = captureBoardBitmap()
            chessOverlayView.visibility = View.VISIBLE
            if (boardBmp == null) { tvStatus.text = "Lỗi chụp, bấm lại!"; return@postDelayed }
            
            workerExecutor.execute {
                val cellSize = boardBmp.width / 8f
                val features = Array(8) { r ->
                    Array(8) { c ->
                        val cellBmp = Bitmap.createBitmap(boardBmp, (c * cellSize).toInt().coerceIn(0, boardBmp.width-2), (r * cellSize).toInt().coerceIn(0, boardBmp.height-2), cellSize.toInt().coerceAtLeast(2), cellSize.toInt().coerceAtLeast(2))
                        val feat = extractEdgeFeature(cellBmp)
                        cellBmp.recycle()
                        feat
                    }
                }
                
                val isWhiteBottom = features[7][4].coreLum > features[0][4].coreLum
                val backRankOrder = if (isWhiteBottom) charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R') 
                                    else charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R')

                learnedEdges.clear()
                for (c in 0..7) learnedEdges[backRankOrder[c]] = features[7][c].edges
                for (c in 0..7) {
                    if (features[6][c].isOccupied) {
                        learnedEdges['P'] = features[6][c].edges
                        break
                    }
                }
                mainHandler.post {
                    if (learnedEdges.size >= 6) {
                        tvStatus.text = "✅ Đã học cờ xong! Bật nút [2. Auto]."
                        chessOverlayView.isWhiteBottom = isWhiteBottom
                    } else {
                        tvStatus.text = "❌ Hãy đưa bàn cờ về đầu trận rồi bấm Học."
                    }
                }
            }
        }, 120)
    }

    private fun scanAndSuggestMove() {
        if (isScanning || learnedEdges.size < 6) return
        isScanning = true
        chessOverlayView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val boardBmp = captureBoardBitmap()
            chessOverlayView.visibility = View.VISIBLE
            if (boardBmp == null) { isScanning = false; return@postDelayed }

            workerExecutor.execute {
                try {
                    val cellSize = boardBmp.width / 8f
                    val rawMatrix = Array(8) { CharArray(8) { '1' } }
                    val lums = mutableListOf<Int>()

                    val features = Array(8) { r ->
                        Array(8) { c ->
                            val cellBmp = Bitmap.createBitmap(boardBmp, (c * cellSize).toInt().coerceIn(0, boardBmp.width-2), (r * cellSize).toInt().coerceIn(0, boardBmp.height-2), cellSize.toInt().coerceAtLeast(2), cellSize.toInt().coerceAtLeast(2))
                            val feat = extractEdgeFeature(cellBmp)
                            cellBmp.recycle()
                            if (feat.isOccupied) lums.add(feat.coreLum)
                            feat
                        }
                    }

                    lums.sort()
                    val threshold = if (lums.size >= 4) (lums[lums.size / 4] + lums[lums.size - 1 - lums.size / 4]) / 2 else 128

                    for (r in 0..7) {
                        for (c in 0..7) {
                            val f = features[r][c]
                            if (!f.isOccupied) continue

                            val isWhite = f.coreLum >= threshold
                            var bestPiece = 'P'
                            var minDiff = Int.MAX_VALUE

                            for ((pChar, template) in learnedEdges) {
                                if (pChar == 'P' && (r == 0 || r == 7)) continue
                                var diff = 0
                                for (i in 0 until 1024) {
                                    if (f.edges[i] != template[i]) diff++
                                }
                                if (diff < minDiff) { minDiff = diff; bestPiece = pChar }
                            }
                            rawMatrix[r][c] = if (isWhite) bestPiece.uppercaseChar() else bestPiece.lowercaseChar()
                        }
                    }

                    val standardMatrix = if (chessOverlayView.isWhiteBottom) rawMatrix 
                                         else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }

                    sanitizeMatrixForStockfish(standardMatrix)

                    val fenRows = mutableListOf<String>()
                    for (r in 0..7) {
                        var empty = 0
                        val sb = StringBuilder()
                        for (c in 0..7) {
                            if (standardMatrix[r][c] == '1') empty++
                            else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(standardMatrix[r][c]) }
                        }
                        if (empty > 0) sb.append(empty)
                        fenRows.add(sb.toString())
                    }
                    val sideToMove = if (chessOverlayView.isWhiteBottom) "w" else "b"
                    val fen = fenRows.joinToString("/") + " $sideToMove - - 0 1"

                    val boardPart = fen.substringBefore(" ")
                    if (boardPart == lastAnalyzedFen) { isScanning = false; return@execute }

                    val move = fetchStockfishOnline(fen)
                    mainHandler.post {
                        if (move != null && move.length >= 4) {
                            lastAnalyzedFen = boardPart
                            chessOverlayView.setMove(move)
                            tvStatus.text = "Gợi ý: ${move.substring(0, 2)} ➔ ${move.substring(2, 4)}"
                        } else {
                            tvStatus.text = "Đang quét bàn cờ..."
                        }
                        isScanning = false
                    }
                } catch (e: Exception) {
                    mainHandler.post { tvStatus.text = "Lỗi: ${e.message}"; isScanning = false }
                }
            }
        }, 120)
    }

    private fun sanitizeMatrixForStockfish(matrix: Array<CharArray>) {
        for (c in 0..7) {
            if (matrix[0][c] == 'P') matrix[0][c] = 'Q'
            if (matrix[0][c] == 'p') matrix[0][c] = 'r'
            if (matrix[7][c] == 'P') matrix[7][c] = 'R'
            if (matrix[7][c] == 'p') matrix[7][c] = 'q'
        }
        enforceSingleKing(matrix, 'K', 'Q', 7, 4)
        enforceSingleKing(matrix, 'k', 'q', 0, 4)
    }
    
    private fun enforceSingleKing(matrix: Array<CharArray>, kingChar: Char, fallback: Char, dr: Int, dc: Int) {
        val pos = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) for (c in 0..7) if (matrix[r][c] == kingChar) pos.add(Pair(r, c))
        if (pos.isEmpty()) { matrix[dr][dc] = kingChar } 
        else if (pos.size > 1) { for (i in 1 until pos.size) { matrix[pos[i].first][pos[i].second] = fallback } }
    }

    private fun runAutoLoop() {
        if (!isAutoMode) return
        scanAndSuggestMove()
        mainHandler.postDelayed({ runAutoLoop() }, 3000)
    }

    private fun fetchStockfishOnline(fen: String): String? {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=12")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000; readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                val parts = raw.trim().split(" ")
                val candidate = if (parts.size >= 2) parts[1] else parts[0]
                if (candidate.length >= 4 && candidate != "(none)") candidate.substring(0, 4) else null
            } else null
        } catch (e: Exception) { null }
    }

    private fun startForegroundNotification() {
        val channelId = "chess_overlay_channel"
        val channel = NotificationChannel(channelId, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId).setContentTitle("Auto Vision v4.1").setSmallIcon(android.R.drawable.ic_menu_compass).build()
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
