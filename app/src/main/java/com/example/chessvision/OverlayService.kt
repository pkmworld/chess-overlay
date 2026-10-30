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
            text = "Kéo bảng này đi nếu che -> Bấm [1. Bắt Đầu]"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 12f
        }
        
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
        
        val btnLearn = createButton("1. Bắt Đầu") { triggerInitBoard() }
        val btnAuto = createButton("2. Auto") { btn ->
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

    private fun triggerInitBoard() {
        chessOverlayView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val boardBmp = locateAndCaptureBoard()
            chessOverlayView.visibility = View.VISIBLE
            if (boardBmp != null) {
                boardBmp.recycle()
                mainHandler.post { tvStatus.text = "✅ Đã định vị bàn cờ! Bật Auto." }
            } else {
                mainHandler.post { tvStatus.text = "❌ Không tìm thấy bàn cờ." }
            }
        }, 150)
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
                    val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
                    val move = fetchStockfishOnline(fen)
                    mainHandler.post {
                        if (move != null && move.length >= 4) {
                            chessOverlayView.setMove(move)
                            tvStatus.text = "Gợi ý: ${move.substring(0, 2)} ➔ ${move.substring(2, 4)}"
                        } else {
                            tvStatus.text = "Đang quét nước đi..."
                        }
                        isScanning = false
                    }
                } catch (e: Exception) {
                    mainHandler.post { tvStatus.text = "Lỗi quét: ${e.message}"; isScanning = false }
                }
            }
        }, 150)
    }

    private fun runAutoLoop() {
        if (!isAutoMode) return
        scanAndSuggestMove()
        mainHandler.postDelayed({ runAutoLoop() }, 3000)
    }

    private fun fetchStockfishOnline(fen: String): String? {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=10")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000; readTimeout = 5000
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
        val notif = Notification.Builder(this, channelId).setContentTitle("Auto Vision v4.1.8").setSmallIcon(android.R.drawable.ic_menu_compass).build()
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
