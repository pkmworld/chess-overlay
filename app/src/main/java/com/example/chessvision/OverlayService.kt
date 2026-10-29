package com.example.chessvision

import android.app.*
import android.content.Context
import android.content.Intent
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
    private lateinit var controlParams: WindowManager.LayoutParams

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

    private val learnedTemplates = mutableMapOf<Char, BooleanArray>()
    data class CellFeature(val isOccupied: Boolean, val edges: BooleanArray, val coreLum: Int, val density: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        chessOverlayView = ChessOverlayView(this)
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
            setBackgroundColor(Color.argb(240, 20, 20, 20))
            setPadding(14, 10, 14, 10)
        }
        tvStatus = TextView(this).apply {
            text = "Kéo bảng này đi nếu bị che -> [1. Học Cờ Chuẩn]"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 12f
        }
        
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
        
        val btnLearn = createButton("1. Học Mọi Lúc") { triggerFlexibleLearn() }
        val btnAuto = createButton("2. Auto") { btn ->
            if (learnedTemplates.isEmpty()) {
                Toast.makeText(this, "Hãy bấm [1. Học Mọi Lúc] trước!", Toast.LENGTH_SHORT).show()
                return@createButton
            }
            isAutoMode = !isAutoMode
            btn.text = if (isAutoMode) "2. Đang Auto" else "2. Auto"
            if (isAutoMode) runAutoLoop()
        }
        val btnClose = createButton("✕ Đóng") { stopSelf() }

        row.addView(btnLearn); row.addView(btnAuto); row.addView(btnClose)
        controlLayout.addView(tvStatus); controlLayout.addView(row)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { 
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100 
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        controlLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlParams.x
                    initialY = controlParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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

    private fun createButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 11f; setPadding(14, 6, 14, 6)
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

    private fun locateAndCaptureBoard(): Bitmap? {
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
            val boardSize = (w * 0.98f).toInt()
            var bestTop = (h - boardSize) / 2
            
            val searchStart = (h * 0.05f).toInt()
            val searchEnd = (h * 0.75f).toInt()
            var maxScore = -1

            for (topY in searchStart..searchEnd step 25) {
                if (topY + boardSize > h) break
                var score = 0
                val testCell = boardSize / 8
                for (r in 0..7 step 2) {
                    val p1 = fullBmp.getPixel(w / 4, topY + r * testCell + testCell/2)
                    val p2 = fullBmp.getPixel(w * 3 / 4, topY + r * testCell + testCell/2)
                    if (p1 != p2) score++
                }
                if (score > maxScore) {
                    maxScore = score
                    bestTop = topY
                }
            }

            if (bestTop < 50) bestTop = (h - w) / 2

            val finalBoardSize = w.toFloat()
            val finalTop = bestTop.toFloat()
            
            val rect = RectF(0f, finalTop, finalBoardSize, finalTop + finalBoardSize)
            mainHandler.post { chessOverlayView.detectedBoardRect = rect }

            val cropped = Bitmap.createBitmap(fullBmp, 0, finalTop.toInt().coerceIn(0, h - w), w, w)
            fullBmp.recycle()
            cropped
        } catch (e: Exception) { 
            null 
        } finally { 
            image.close() 
        }
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

                if (mag > 90) {
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
        return CellFeature(edgeCount > 18, edges, if (corePixels > 0) coreLumSum / corePixels else 128, edgeCount)
    }

    private fun triggerFlexibleLearn() {
        chessOverlayView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val boardBmp = locateAndCaptureBoard()
            chessOverlayView.visibility = View.VISIBLE
            if (boardBmp == null) { tvStatus.text = "Không tìm thấy bàn cờ!"; return@postDelayed }
            
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
                
                learnedTemplates.clear()
                val isWhiteBottom = features[7][4].coreLum >= features[0][4].coreLum

                // Tự động suy luận vị trí các quân cờ tiêu chuẩn dựa trên hàng khai cuộc nếu đang ở đầu ván, 
                // hoặc ánh xạ khuôn mẫu theo mật độ nét viền nếu đang ở giữa trận.
                for (r in 0..7) {
                    for (c in 0..7) {
                        val f = features[r][c]
                        if (f.isOccupied) {
                            val isWhite = f.coreLum >= 120
                            val pChar = guessPieceType(r, c, f.density, isWhite, isWhiteBottom)
                            learnedTemplates[pChar] = f.edges
                        }
                    }
                }

                mainHandler.post {
                    tvStatus.text = "✅ Đã học xong mẫu cờ! Bật Auto."
                    chessOverlayView.isWhiteBottom = isWhiteBottom
                }
            }
        }, 150)
    }

    private fun guessPieceType(r: Int, c: Int, density: Int, isWhite: Boolean, isWhiteBottom: Boolean): Char {
        val actualRank = if (isWhiteBottom) 7 - r else r
        // Đoán quân dựa vào hàng khởi điểm chuẩn của cờ vua
        if (actualRank == 0) {
            return when(c) {
                0, 7 -> if (isWhite) 'R' else 'r'
                1, 6 -> if (isWhite) 'N' else 'n'
                2, 5 -> if (isWhite) 'B' else 'b'
                3 -> if (isWhite) 'Q' else 'q'
                4 -> if (isWhite) 'K' else 'k'
                else -> if (isWhite) 'P' else 'p'
            }
        } else if (actualRank == 1) {
            return if (isWhite) 'P' else 'p'
        }
        // Giữa trận phân biệt dựa vào độ phức tạp nét viền (density)
        return when {
            density > 120 -> if (isWhite) 'Q' else 'q'
            density > 90 -> if (isWhite) 'R' else 'r'
            density > 70 -> if (isWhite) 'B' else 'b'
            density > 50 -> if (isWhite) 'N' else 'n'
            else -> if (isWhite) 'P' else 'p'
        }
    }

    private fun scanAndSuggestMove() {
        if (isScanning) return
        isScanning = true
        chessOverlayView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val boardBmp = locateAndCaptureBoard()
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
                            // Phân biệt đầy đủ các quân cờ thay vì gom hết thành Tốt
                            rawMatrix[r][c] = guessPieceType(r, c, f.density, isWhite, chessOverlayView.isWhiteBottom)
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
                            tvStatus.text = "Đang theo dõi nước đi..."
                        }
                        isScanning = false
                    }
                } catch (e: Exception) {
                    mainHandler.post { tvStatus.text = "Lỗi quét: ${e.message}"; isScanning = false }
                }
            }
        }, 150)
    }

    private fun sanitizeMatrixForStockfish(matrix: Array<CharArray>) {
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
        val notif = Notification.Builder(this, channelId).setContentTitle("Auto Vision v4.1.6").setSmallIcon(android.R.drawable.ic_menu_compass).build()
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
