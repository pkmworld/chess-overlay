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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    private val pieceTemplates = mutableMapOf<Char, IntArray>()
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
        val data: Intent? = intent?.getParcelableExtra("DATA")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }
        return START_NOT_STICKY
    }

    private fun setupBoardOverlay() {
        chessOverlayView = ChessOverlayView(this).apply {
            boardTopY = (screenHeight - screenWidth) / 2f
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
    }

    private fun setupFloatingControls() {
        controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 25, 25, 25))
            setPadding(16, 12, 16, 12)
        }

        tvStatus = TextView(this).apply {
            text = "B1: Chỉnh khung khớp bàn cờ -> Bấm [1. Học mẫu]"
            setTextColor(Color.YELLOW)
            textSize = 11f
        }

        val rowButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnUp = createSmallButton("▲") {
            chessOverlayView.boardTopY -= 15f
            chessOverlayView.invalidate()
        }
        val btnDown = createSmallButton("▼") {
            chessOverlayView.boardTopY += 15f
            chessOverlayView.invalidate()
        }
        val btnCalibrate = createSmallButton("1. Học mẫu") {
            calibrateFromStartingBoard()
        }
        val btnAnalyze = createSmallButton("2. Gợi ý") {
            scanAndSuggestMove()
        }
        val btnAuto = createSmallButton("Auto: TẮT") { btn ->
            isAutoMode = !isAutoMode
            btn.text = if (isAutoMode) "Auto: BẬT" else "Auto: TẮT"
            if (isAutoMode) runAutoLoop()
        }
        val btnFlip = createSmallButton("Phe: Trắng") { btn ->
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
            btn.text = if (chessOverlayView.isWhiteBottom) "Phe: Trắng" else "Phe: Đen"
            chessOverlayView.invalidate()
        }
        val btnClose = createSmallButton("X") {
            isAutoMode = false
            stopSelf()
        }

        rowButtons.addView(btnUp)
        rowButtons.addView(btnDown)
        rowButtons.addView(btnCalibrate)
        rowButtons.addView(btnAnalyze)
        rowButtons.addView(btnAuto)
        rowButtons.addView(btnFlip)
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
            y = 80
        }

        windowManager.addView(controlLayout, controlParams)
    }

    private fun createSmallButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            setPadding(16, 8, 16, 8)
            setOnClickListener { onClick(this) }
        }
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
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

    private fun calibrateFromStartingBoard() {
        chessOverlayView.setMove(null)
        mainHandler.postDelayed({
            val boardBmp = captureBoardBitmap()
            if (boardBmp == null) {
                tvStatus.text = "Chưa chụp được màn hình, hãy bấm lại!"
                return@postDelayed
            }

            val cellSize = boardBmp.width / 8f
            val startRows = mapOf(
                0 to charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
                1 to charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
                6 to charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
                7 to charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
            )

            pieceTemplates.clear()
            for ((row, chars) in startRows) {
                for (col in 0..7) {
                    val pieceChar = chars[col]
                    if (!pieceTemplates.containsKey(pieceChar)) {
                        val cellBmp = Bitmap.createBitmap(
                            boardBmp,
                            (col * cellSize).toInt(),
                            (row * cellSize).toInt(),
                            cellSize.toInt(),
                            cellSize.toInt()
                        )
                        pieceTemplates[pieceChar] = extractCellSignature(cellBmp)
                    }
                }
            }
            tvStatus.text = "Đã học xong 12 quân cờ! Bấm [2. Gợi ý] hoặc bật [Auto]"
            Toast.makeText(this, "Học mẫu thành công!", Toast.LENGTH_SHORT).show()
        }, 150)
    }

    private fun scanAndSuggestMove() {
        if (pieceTemplates.isEmpty()) {
            tvStatus.text = "Hãy bấm [1. Học mẫu] ở bàn cờ đầu trận trước!"
            return
        }

        chessOverlayView.setMove(null)
        mainHandler.postDelayed({
            val boardBmp = captureBoardBitmap() ?: return@postDelayed

            workerExecutor.execute {
                val currentFen = recognizeBoardToFen(boardBmp, chessOverlayView.isWhiteBottom)
                mainHandler.post { tvStatus.text = "Đang tính: $currentFen" }

                val bestMove = fetchBestMoveFromStockfish(currentFen)
                mainHandler.post {
                    if (bestMove != null) {
                        chessOverlayView.setMove(bestMove)
                        tvStatus.text = "Nên đi: ${bestMove.substring(0, 2)} -> ${bestMove.substring(2, 4)}"
                    } else {
                        tvStatus.text = "Lỗi lấy nước đi (Kiểm tra mạng hoặc khung bàn cờ)"
                    }
                }
            }
        }, 120)
    }

    private fun runAutoLoop() {
        if (!isAutoMode) return
        scanAndSuggestMove()
        mainHandler.postDelayed({ runAutoLoop() }, 3000)
    }

    private fun extractCellSignature(cell: Bitmap): IntArray {
        val w = cell.width
        val h = cell.height
        val bgPixel = cell.getPixel((w * 0.12f).toInt(), (h * 0.88f).toInt())
        val bgR = Color.red(bgPixel)
        val bgG = Color.green(bgPixel)
        val bgB = Color.blue(bgPixel)

        val signature = IntArray(256)
        var idx = 0
        for (iy in 0 until 16) {
            val py = (h * (0.20f + 0.60f * iy / 15f)).toInt()
            for (ix in 0 until 16) {
                val px = (w * (0.20f + 0.60f * ix / 15f)).toInt()
                val p = cell.getPixel(px, py)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                val diffFromBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                signature[idx++] = if (diffFromBg < 45) -1 else (r * 30 + g * 59 + b * 11) / 100
            }
        }
        return signature
    }

    private fun recognizeBoardToFen(boardBmp: Bitmap, isWhiteBottom: Boolean): String {
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
                val sig = extractCellSignature(cellBmp)
                val fgCount = sig.count { it != -1 }

                if (fgCount < 22) {
                    matrix[row][col] = '1'
                } else {
                    var bestChar = 'P'
                    var minScore = Int.MAX_VALUE
                    for ((pieceChar, templateSig) in pieceTemplates) {
                        var score = 0
                        for (i in 0 until 256) {
                            val a = if (sig[i] == -1) 128 else sig[i]
                            val b = if (templateSig[i] == -1) 128 else templateSig[i]
                            score += abs(a - b)
                        }
                        if (score < minScore) {
                            minScore = score
                            bestChar = pieceChar
                        }
                    }
                    matrix[row][col] = bestChar
                }
            }
        }

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

        val sideToMove = if (isWhiteBottom) "w" else "b"
        return "${fenRows.joinToString("/")} $sideToMove KQkq - 0 1"
    }

    private fun fetchBestMoveFromStockfish(fen: String): String? {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=12")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                if (json.optBoolean("success", false)) {
                    val bestMoveRaw = json.optString("bestmove", "")
                    val parts = bestMoveRaw.split(" ")
                    if (parts.size >= 2) parts[1] else null
                } else null
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startForegroundNotification() {
        val channelId = "chess_overlay_channel"
        val channel = NotificationChannel(channelId, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Chess Coach đang chạy")
            .setContentText("Sử dụng thanh điều khiển nổi trên màn hình")
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
