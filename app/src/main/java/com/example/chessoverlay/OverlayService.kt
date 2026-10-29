package com.example.chessoverlay

import android.app.*
import android.content.Context
import android.content.Intent
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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var isAutoMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
            chessOverlayView = ChessOverlayView(this).apply {
                // Tọa độ mặc định tối ưu cho hầu hết màn hình 20:9 phổ biến
                boardTopY = (screenHeight - screenWidth) * 0.52f
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
                setBackgroundColor(Color.argb(240, 18, 18, 18))
                setPadding(18, 12, 18, 12)
            }

            tvStatus = TextView(this).apply {
                text = "Sẵn sàng! Bấm [⚡ Gợi ý] để phân tích"
                setTextColor(Color.parseColor("#69F0AE"))
                textSize = 12f
            }

            val rowButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnUp = createSmallButton("▲") {
                chessOverlayView.boardTopY -= 12f
                chessOverlayView.invalidate()
            }
            val btnDown = createSmallButton("▼") {
                chessOverlayView.boardTopY += 12f
                chessOverlayView.invalidate()
            }
            val btnAnalyze = createSmallButton("⚡ Gợi ý") {
                scanAndSuggestMove()
            }
            val btnAuto = createSmallButton("🔄 Auto: TẮT") { btn ->
                isAutoMode = !isAutoMode
                btn.text = if (isAutoMode) "🔄 Auto: BẬT" else "🔄 Auto: TẮT"
                if (isAutoMode) runAutoLoop()
            }
            val btnClose = createSmallButton("✕") {
                isAutoMode = false
                stopSelf()
            }

            rowButtons.addView(btnUp)
            rowButtons.addView(btnDown)
            rowButtons.addView(btnAnalyze)
            rowButtons.addView(btnAuto)
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
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(16, 6, 16, 6)
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

    private fun captureBoardBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val fullBitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        fullBitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val maxTop = (screenHeight - screenWidth).coerceAtLeast(0)
        val boardY = chessOverlayView.boardTopY.toInt().coerceIn(0, maxTop)
        return Bitmap.createBitmap(fullBitmap, 0, boardY, screenWidth, screenWidth)
    }

    private fun scanAndSuggestMove() {
        chessOverlayView.setMove(null)
        tvStatus.text = "Đang quét bàn cờ..."

        mainHandler.postDelayed({
            val boardBmp = captureBoardBitmap()
            if (boardBmp == null) {
                tvStatus.text = "Chưa chụp được màn hình, thử lại!"
                return@postDelayed
            }

            workerExecutor.execute {
                // TỰ ĐỘNG PHÁT HIỆN PHE: Đo độ sáng quân cờ ở hàng đáy vs hàng đỉnh
                val isWhiteBottom = detectBoardOrientation(boardBmp)
                mainHandler.post {
                    chessOverlayView.isWhiteBottom = isWhiteBottom
                }

                val currentFen = analyzeBoardToFen(boardBmp, isWhiteBottom)
                val perspectiveText = if (isWhiteBottom) "Phe Trắng" else "Phe Đen"

                mainHandler.post {
                    tvStatus.text = "[$perspectiveText] Đang tính toán FEN..."
                }

                val result = fetchBestMoveFromStockfish(currentFen)
                mainHandler.post {
                    if (result.first != null) {
                        val bestMove = result.first!!
                        chessOverlayView.setMove(bestMove)
                        tvStatus.text = "[$perspectiveText] Nên đi: ${bestMove.substring(0, 2)} ➔ ${bestMove.substring(2, 4)}"
                    } else {
                        tvStatus.text = "Lỗi: ${result.second}"
                    }
                }
            }
        }, 80)
    }

    private fun runAutoLoop() {
        if (!isAutoMode) return
        scanAndSuggestMove()
        mainHandler.postDelayed({ runAutoLoop() }, 3200)
    }

    // Tự động nhận diện hướng bàn cờ dựa vào độ sáng quân cờ
    private fun detectBoardOrientation(boardBmp: Bitmap): Boolean {
        val cellSize = boardBmp.width / 8f
        var bottomLumSum = 0L
        var bottomPixelCount = 0
        var topLumSum = 0L
        var topPixelCount = 0

        // Đo quân cờ ở 2 hàng dưới (r = 6, 7)
        for (r in 6..7) {
            for (c in 0..7) {
                val lum = sampleCellPieceLuminance(boardBmp, c * cellSize, r * cellSize, cellSize)
                if (lum >= 0) {
                    bottomLumSum += lum
                    bottomPixelCount++
                }
            }
        }

        // Đo quân cờ ở 2 hàng trên (r = 0, 1)
        for (r in 0..1) {
            for (c in 0..7) {
                val lum = sampleCellPieceLuminance(boardBmp, c * cellSize, r * cellSize, cellSize)
                if (lum >= 0) {
                    topLumSum += lum
                    topPixelCount++
                }
            }
        }

        val avgBottom = if (bottomPixelCount > 0) bottomLumSum / bottomPixelCount else 128
        val avgTop = if (topPixelCount > 0) topLumSum / topPixelCount else 128

        // Quân Trắng luôn sáng màu hơn quân Đen
        return avgBottom >= avgTop
    }

    private fun sampleCellPieceLuminance(board: Bitmap, startX: Float, startY: Float, size: Float): Int {
        val cellBmp = Bitmap.createBitmap(board, startX.toInt(), startY.toInt(), size.toInt(), size.toInt())
        val bgPixel = cellBmp.getPixel((size * 0.15f).toInt(), (size * 0.85f).toInt())
        val bgR = Color.red(bgPixel)
        val bgG = Color.green(bgPixel)
        val bgB = Color.blue(bgPixel)

        var lumSum = 0
        var count = 0

        for (y in (size * 0.25f).toInt()..(size * 0.75f).toInt() step 3) {
            for (x in (size * 0.25f).toInt()..(size * 0.75f).toInt() step 3) {
                val p = cellBmp.getPixel(x, y)
                val diff = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)
                if (diff > 45) {
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    lumSum += lum
                    count++
                }
            }
        }
        return if (count > 25) lumSum / count else -1
    }

    // Nhận diện quân cờ trực tiếp qua tỷ lệ hình học và quang sai
    private fun analyzeBoardToFen(boardBmp: Bitmap, isWhiteBottom: Boolean): String {
        val cellSize = boardBmp.width / 8f
        val matrix = Array(8) { CharArray(8) { '1' } }

        for (row in 0..7) {
            for (col in 0..7) {
                val cellBmp = Bitmap.createBitmap(
                    boardBmp,
                    (col * cellSize).toInt(),
                    (row * cellSize).toInt(),
                    cellSize.toInt(),
                    cellSize.toInt()
                )
                matrix[row][col] = classifyPiece(cellBmp, row, col, isWhiteBottom)
            }
        }

        // Đảo bàn cờ nếu cầm quân Đen
        val finalMatrix = if (isWhiteBottom) {
            matrix
        } else {
            Array(8) { r -> CharArray(8) { c -> matrix[7 - r][7 - c] } }
        }

        val fenRows = mutableListOf<String>()
        for (r in 0..7) {
            val sb = StringBuilder()
            var emptyCount = 0
            for (c in 0..7) {
                val ch = finalMatrix[r][c]
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

        val side = if (isWhiteBottom) "w" else "b"
        return "${fenRows.joinToString("/")} $side - - 0 1"
    }

    private fun classifyPiece(cell: Bitmap, row: Int, col: Int, isWhiteBottom: Boolean): Char {
        val w = cell.width
        val h = cell.height
        val bgPixel = cell.getPixel((w * 0.12f).toInt(), (h * 0.88f).toInt())
        val bgR = Color.red(bgPixel)
        val bgG = Color.green(bgPixel)
        val bgB = Color.blue(bgPixel)

        var fgPixels = 0
        var totalLum = 0
        var topThirdFg = 0
        var bottomThirdFg = 0

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val p = cell.getPixel(x, y)
                val diff = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)
                if (diff > 42) {
                    fgPixels++
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    totalLum += lum
                    if (y < h * 0.35f) topThirdFg++
                    if (y > h * 0.65f) bottomThirdFg++
                }
            }
        }

        // Nếu số điểm ảnh khác nền quá ít -> Ô trống
        if (fgPixels < (w * h) / 36) return '1'

        val avgLum = totalLum / fgPixels
        // Xác định quân Trắng hay Đen dựa vào ngưỡng sáng
        val isWhitePiece = avgLum > 115

        // Nhận diện loại quân dựa trên tỷ trọng đỉnh/đáy và vị trí
        val pieceChar: Char
        val topRatio = topThirdFg.toFloat() / (fgPixels.coerceAtLeast(1))

        if (fgPixels < (w * h) / 18) {
            pieceChar = 'P' // Tốt (thể tích nhỏ nhất)
        } else if (topRatio > 0.28f) {
            // Phần đầu to: Xe (Rook) hoặc Hậu (Queen)
            pieceChar = if (fgPixels > (w * h) / 10) 'Q' else 'R'
        } else if (topRatio < 0.16f) {
            // Phần đầu nhọn, hẹp: Tượng (Bishop) hoặc Vua (King)
            pieceChar = if (fgPixels > (w * h) / 10) 'K' else 'B'
        } else {
            pieceChar = 'N' // Mã (Knight)
        }

        return if (isWhitePiece) pieceChar.uppercaseChar() else pieceChar.lowercaseChar()
    }

    private fun fetchBestMoveFromStockfish(fen: String): Pair<String?, String> {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=12")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                if (json.optBoolean("success", false)) {
                    val bestMoveRaw = json.optString("bestmove", "")
                    val parts = bestMoveRaw.split(" ")
                    val move = if (parts.size >= 2) parts[1] else null
                    Pair(move, "")
                } else {
                    Pair(null, "FEN không hợp lệ")
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
            .setContentTitle("Chess Coach v2.0 đang hoạt động")
            .setContentText("Tự động nhận diện bàn cờ và gợi ý nước đi")
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
