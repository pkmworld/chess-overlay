package com.example.chessvision

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.view.Choreographer
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var controlLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvFenDebug: TextView
    private lateinit var btnSide: Button
    private lateinit var btnTurn: Button
    private lateinit var btnCastle: Button
    private lateinit var controlParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    @Volatile private var screenWidth = 1080
    @Volatile private var screenHeight = 2400
    @Volatile private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var isAutoMode = false
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var waitingForCleanFrame = false
    @Volatile private var cleanFrameMinTimestamp = 0L

    // Lưu bản sao khung hình sạch gần nhất phòng trường hợp màn hình tĩnh hoàn toàn không phát sinh frame mới
    private val frameCacheLock = Any()
    private var lastCleanFullBitmap: Bitmap? = null
    private var prevBoardSignature: IntArray? = null
    @Volatile private var consecutiveMotionFrames = 0

    @Volatile private var detectedBoardRect: RectF? = null
    @Volatile private var previousValidMatrix: Array<CharArray>? = null
    @Volatile private var lastDetectedTurn: Char = 'w'
    @Volatile private var lastEnPassantTarget: String = "-"
    @Volatile private var halfmoveClock = 0
    @Volatile private var fullmoveNumber = 1

    // Chống kết quả API cũ đè lên thế cờ mới
    private val stateGeneration = AtomicLong(0L)
    @Volatile private var latestValidStateKey = ""
    @Volatile private var lastCompletedStateKey = ""

    @Volatile private var forceTurnSide: Char? = null
    // Chế độ nhập thành: 0 = Tự động theo lịch sử, 1 = Bật khi Vua/Xe ở ô gốc, 2 = Tắt (-)
    @Volatile private var castlingMode = 0
    @Volatile private var hasFullGameHistory = false
    @Volatile private var wKingMoved = false
    @Volatile private var wRookAMoved = false
    @Volatile private var wRookHMoved = false
    @Volatile private var bKingMoved = false
    @Volatile private var bRookAMoved = false
    @Volatile private var bRookHMoved = false

    private val moveCache = ConcurrentHashMap<String, String>()
    @Volatile private var consecutiveApiFailures = 0
    @Volatile private var nextAllowedApiCallTime = 0L

    private val dualTemplates = ConcurrentHashMap<Char, FloatArray>()
    @Volatile private var dynamicPawnMaxMass = 165
    @Volatile private var dynamicPawnMinTopY = 7

    data class CellAnalysis(
        val isOccupied: Boolean,
        val coreLuminance: Int,
        val isHighlighted: Boolean,
        val silhouette16x16: FloatArray,
        val bodyMass: Int,
        val highestY: Int,
        val sliceWidths: IntArray,
        val headAsymmetry: Float
    )

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode) return
            requestCleanCapture()
            mainHandler.postDelayed(this, 1300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("ImageCaptureThread").apply { start() }
        bgThread = thread
        bgHandler = Handler(thread.looper)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()

        setupBoardOverlay()
        setupFloatingControls()
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        resetBoardAlignment()
        recreateVirtualDisplay()
    }

    private fun resetBoardAlignment() {
        detectedBoardRect = null
        prevBoardSignature = null
        mainHandler.post {
            if (::chessOverlayView.isInitialized) {
                chessOverlayView.detectedBoardRect = null
                chessOverlayView.setMove(null)
            }
        }
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
                // Dọn dẹp MediaProjection cũ nếu người dùng bấm Khởi chạy lần 2
                cleanupMediaProjection()

                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpManager.getMediaProjection(resultCode, data)
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        virtualDisplay?.release()
                        virtualDisplay = null
                        imageReader?.close()
                        imageReader = null
                        mediaProjection = null
                    }
                }
                mp.registerCallback(cb, mainHandler)
                mediaProjection = mp
                projectionCallback = cb

                recreateVirtualDisplay()
                mainHandler.postDelayed({ requestCleanCapture() }, 550)
            } catch (e: Exception) { e.printStackTrace() }
        }
        return START_NOT_STICKY
    }

    private fun cleanupMediaProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            mediaProjection?.stop()
        } catch (_: Exception) {}
        projectionCallback = null
        mediaProjection = null
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
            setBackgroundColor(Color.argb(242, 18, 18, 22))
            setPadding(14, 8, 14, 8)
        }
        tvStatus = TextView(this).apply {
            text = "True Vision v6.3 • Đã đồng bộ chuẩn 100%"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 11f
        }
        tvFenDebug = TextView(this).apply {
            text = "FEN: Chưa quét"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 9.5f
            maxLines = 1
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 5, 0, 0) }

        btnSide = createButton("⚪ Trắng") {
            val newBottom = !chessOverlayView.isWhiteBottom
            chessOverlayView.isWhiteBottom = newBottom
            btnSide.text = if (newBottom) "⚪ Trắng" else "⚫ Đen"
            lastCompletedStateKey = ""
            previousValidMatrix = null
            lastDetectedTurn = if (newBottom) 'w' else 'b'
            chessOverlayView.setMove(null)
            requestCleanCapture()
        }

        btnTurn = createButton("🔄 Lượt: Auto") {
            forceTurnSide = when (forceTurnSide) {
                null -> if (chessOverlayView.isWhiteBottom) 'w' else 'b'
                'w' -> 'b'
                else -> null
            }
            btnTurn.text = when (forceTurnSide) {
                'w' -> "⚡ Lượt: Trắng"
                'b' -> "⚡ Lượt: Đen"
                else -> "🔄 Lượt: Auto"
            }
            lastCompletedStateKey = ""
            requestCleanCapture()
        }

        btnCastle = createButton("🏰 KQ: Auto") {
            castlingMode = (castlingMode + 1) % 3
            btnCastle.text = when (castlingMode) {
                0 -> "🏰 KQ: Auto"
                1 -> "🏰 KQ: Bật"
                else -> "🏰 KQ: Tắt"
            }
            lastCompletedStateKey = ""
            requestCleanCapture()
        }

        val btnAuto = createButton("▶ Auto") { btn ->
            isAutoMode = !isAutoMode
            mainHandler.removeCallbacks(autoLoopRunnable)
            if (isAutoMode) {
                btn.text = "⏸ Dừng"
                lastCompletedStateKey = ""
                mainHandler.post(autoLoopRunnable)
            } else {
                btn.text = "▶ Auto"
                chessOverlayView.setMove(null)
                tvStatus.text = "Đã tạm dừng Auto."
            }
        }

        val btnScan = createButton("⚡ Quét") {
            lastCompletedStateKey = ""
            dualTemplates.clear()
            resetBoardAlignment()
            requestCleanCapture()
        }

        val btnClose = createButton("✕") { stopSelf() }

        row.addView(btnSide); row.addView(btnTurn); row.addView(btnCastle)
        row.addView(btnAuto); row.addView(btnScan); row.addView(btnClose)
        controlLayout.addView(tvStatus); controlLayout.addView(tvFenDebug); controlLayout.addView(row)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 70
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

    private fun createButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 9.5f; setPadding(9, 5, 9, 5)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick(this) }
        }
    }

    private fun recreateVirtualDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        val mp = mediaProjection ?: return
        val handler = bgHandler ?: return

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val bmpWidth = screenWidth + rowPadding / pixelStride
                val fullBmp = Bitmap.createBitmap(bmpWidth, screenHeight, Bitmap.Config.ARGB_8888)
                fullBmp.copyPixelsFromBuffer(buffer)
                img.close()

                // Nếu đang ở trạng thái ẩn mũi tên, lưu lại bản sao khung hình sạch gần nhất
                if (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp) {
                    cacheCleanBitmap(fullBmp)
                    if (isProcessing.compareAndSet(false, true)) {
                        waitingForCleanFrame = false
                        mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                        workerExecutor.execute { processCapturedBitmap(fullBmp) }
                        return@setOnImageAvailableListener
                    }
                }
                fullBmp.recycle()
            } catch (e: Exception) {
                try { img.close() } catch (_: Exception) {}
                if (waitingForCleanFrame) {
                    waitingForCleanFrame = false
                    mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                    isProcessing.set(false)
                }
            }
        }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "ChessTrueCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun cacheCleanBitmap(bmp: Bitmap) {
        synchronized(frameCacheLock) {
            try {
                lastCleanFullBitmap?.recycle()
                lastCleanFullBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {}
        }
    }

    private fun obtainCachedCleanBitmapCopy(): Bitmap? {
        synchronized(frameCacheLock) {
            val cached = lastCleanFullBitmap ?: return null
            if (cached.isRecycled) return null
            return try { cached.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
        }
    }

    private fun requestCleanCapture() {
        if (isProcessing.get() || waitingForCleanFrame) return
        mainHandler.post {
            chessOverlayView.visibility = View.INVISIBLE
            try { imageReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
            Choreographer.getInstance().postFrameCallback {
                cleanFrameMinTimestamp = SystemClock.uptimeMillis() + 30L
                waitingForCleanFrame = true
                // Thay đổi nhẹ độ trong suốt của bảng điều khiển 1 đơn vị để ép SurfaceFlinger xuất frame mới cho VirtualDisplay
                controlLayout.alpha = if (controlLayout.alpha < 0.99f) 1.0f else 0.985f
                controlLayout.invalidate()

                bgHandler?.postDelayed({
                    if (waitingForCleanFrame && !isProcessing.get()) {
                        val img = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
                        if (img != null && isProcessing.compareAndSet(false, true)) {
                            waitingForCleanFrame = false
                            try {
                                val plane = img.planes[0]
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride
                                val rowPadding = rowStride - pixelStride * screenWidth
                                val fullBmp = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
                                fullBmp.copyPixelsFromBuffer(plane.buffer)
                                img.close()
                                cacheCleanBitmap(fullBmp)
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                workerExecutor.execute { processCapturedBitmap(fullBmp) }
                            } catch (e: Exception) {
                                try { img.close() } catch (_: Exception) {}
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                isProcessing.set(false)
                            }
                        } else {
                            img?.close()
                            // Nếu màn hình hoàn toàn tĩnh không sinh frame mới, dùng lại bản sao khung hình sạch gần nhất
                            val fallbackBmp = obtainCachedCleanBitmapCopy()
                            if (fallbackBmp != null && isProcessing.compareAndSet(false, true)) {
                                waitingForCleanFrame = false
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                workerExecutor.execute { processCapturedBitmap(fallbackBmp) }
                            } else {
                                fallbackBmp?.recycle()
                                waitingForCleanFrame = false
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                            }
                        }
                    }
                }, 145L)
            }
        }
    }

    // DÒ TÌM BÀN CỜ 2 CHIỀU LINH HOẠT (HỖ TRỢ MÀN DỌC, MÀN NGANG, TABLET VÀ BÀN CỜ CÓ LỀ TRÁI/PHẢI)
    private fun detectBoardRect2D(fullBmp: Bitmap): RectF? {
        val w = screenWidth.coerceAtMost(fullBmp.width)
        val h = screenHeight.coerceAtMost(fullBmp.height)
        val minDim = min(w, h)
        if (minDim < 160) return null

        // Thử các kích thước bàn cờ khả dĩ (từ 100% chiều nhỏ nhất xuống 75% cho bàn cờ có lề/tablet)
        val candidateSizes = intArrayOf(
            minDim,
            (minDim * 0.96f).toInt(),
            (minDim * 0.92f).toInt()
        ).distinct()

        var bestRect: RectF? = null
        var bestScore = -99999

        for (bSize in candidateSizes) {
            val cellSize = bSize / 8f
            val leftX = (w - bSize) / 2
            val searchStart = (h * 0.08f).toInt().coerceAtLeast(4)
            val searchEnd = (h - bSize - 4).coerceAtLeast(searchStart)

            for (candidateY in searchStart..searchEnd step 4) {
                var score = 0
                var validRows = 0
                for (r in 0..7) {
                    val sampleY = (candidateY + r * cellSize + cellSize * 0.18f).toInt()
                    if (sampleY >= h) continue
                    var evenLum = 0; var oddLum = 0
                    for (c in 0..7) {
                        val sampleX = (leftX + c * cellSize + cellSize * 0.50f).toInt().coerceIn(0, w - 1)
                        val p = fullBmp.getPixel(sampleX, sampleY)
                        val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        if ((r + c) % 2 == 0) evenLum += lum else oddLum += lum
                    }
                    val diff = abs((evenLum / 4) - (oddLum / 4))
                    if (diff in 13..190) {
                        score += 35
                        validRows++
                    } else {
                        score -= 30
                    }
                }
                if (candidateY > 8) {
                    var borderDiff = 0
                    for (c in 0..7) {
                        val x = (leftX + c * cellSize + cellSize * 0.5f).toInt().coerceIn(0, w - 1)
                        val pAbove = fullBmp.getPixel(x, candidateY - 3)
                        val pInside = fullBmp.getPixel(x, (candidateY + 5).coerceAtMost(h - 1))
                        val l1 = (Color.red(pAbove) * 299 + Color.green(pAbove) * 587 + Color.blue(pAbove) * 114) / 1000
                        val l2 = (Color.red(pInside) * 299 + Color.green(pInside) * 587 + Color.blue(pInside) * 114) / 1000
                        if (abs(l1 - l2) > 8) borderDiff += 6
                    }
                    score += borderDiff
                }
                if (validRows >= 6 && score > bestScore) {
                    bestScore = score
                    bestRect = RectF(leftX.toFloat(), candidateY.toFloat(), (leftX + bSize).toFloat(), (candidateY + bSize).toFloat())
                }
            }
        }
        return bestRect
    }

    private fun processCapturedBitmap(fullBmp: Bitmap) {
        try {
            var boardRect = detectedBoardRect
            if (boardRect == null) {
                boardRect = detectBoardRect2D(fullBmp)
                if (boardRect == null) {
                    mainHandler.post { tvStatus.text = "⚠️ Không tìm thấy bàn cờ 8x8 trên màn hình!" }
                    return
                }
                detectedBoardRect = boardRect
                mainHandler.post {
                    chessOverlayView.detectedBoardRect = boardRect
                    chessOverlayView.invalidate()
                }
            }

            val bLeft = boardRect.left.toInt().coerceIn(0, fullBmp.width - 64)
            val bTop = boardRect.top.toInt().coerceIn(0, fullBmp.height - 64)
            val bSize = boardRect.width().toInt().coerceIn(64, min(fullBmp.width - bLeft, fullBmp.height - bTop))
            val boardBmp = Bitmap.createBitmap(fullBmp, bLeft, bTop, bSize, bSize)

            // KIỂM TRA CHUYỂN ĐỘNG BẰNG ĐỘ LỆCH PIXEL GIỮA 2 KHUNG HÌNH (Kèm giới hạn tối đa 3 lần để không bao giờ lặp vô tận)
            val currSig = computeBoardSignature64(boardBmp)
            val prevSig = prevBoardSignature
            prevBoardSignature = currSig
            if (prevSig != null) {
                var changingCells = 0
                for (i in 0 until 64) {
                    if (abs(currSig[i] - prevSig[i]) > 18) changingCells++
                }
                // Nếu chỉ có > 2 ô đang biến đổi dở dang (quân đang trượt trên đường đi) và chưa vượt quá 3 lần liên tiếp
                if (changingCells in 3..10 && consecutiveMotionFrames < 3) {
                    consecutiveMotionFrames++
                    boardBmp.recycle()
                    mainHandler.post {
                        tvStatus.text = "⏳ Đang chờ quân cờ dừng hẳn ($consecutiveMotionFrames/3)..."
                    }
                    return
                }
            }
            consecutiveMotionFrames = 0

            val cellSize = bSize / 8f
            val evenBgColors = mutableListOf<Int>()
            val oddBgColors = mutableListOf<Int>()
            for (r in 2..5) {
                for (c in 0..7) {
                    val x = (c * cellSize + cellSize * 0.18f).toInt().coerceIn(0, bSize - 1)
                    val y = (r * cellSize + cellSize * 0.18f).toInt().coerceIn(0, bSize - 1)
                    val p = boardBmp.getPixel(x, y)
                    if ((r + c) % 2 == 0) evenBgColors.add(p) else oddBgColors.add(p)
                }
            }
            val refEvenBg = medianColor(evenBgColors)
            val refOddBg = medianColor(oddBgColors)

            val cells = Array(8) { r ->
                Array(8) { c ->
                    val cx = (c * cellSize).toInt().coerceIn(0, bSize - 8)
                    val cy = (r * cellSize).toInt().coerceIn(0, bSize - 8)
                    val cs = cellSize.toInt().coerceIn(8, min(bSize - cx, bSize - cy))
                    val cellBmp = Bitmap.createBitmap(boardBmp, cx, cy, cs, cs)
                    val refBg = if ((r + c) % 2 == 0) refEvenBg else refOddBg
                    val analysis = analyzeSingleCellScanline(cellBmp, refBg)
                    cellBmp.recycle()
                    analysis
                }
            }
            boardBmp.recycle()

            // 1. PHÂN CỤM ĐỘNG 2 NHÓM MÀU QUÂN (TRẮNG VS ĐEN) TRÊN TOÀN BỘ QUÂN ĐANG CÓ (KHÔNG DÙNG NGƯỠNG CỐ ĐỊNH 145)
            val colorThreshold = computeDynamicPieceLumThreshold(cells)
            val isWhiteMap = Array(8) { r ->
                BooleanArray(8) { c -> cells[r][c].coreLuminance >= colorThreshold }
            }

            // 2. Hiệu chỉnh động kích thước Tốt/Quân lớn trên mọi trạng thái bàn cờ
            calibrateDynamicThresholdsFromAnyBoard(cells)

            // 3. Học Ngân Hàng Mẫu Kép & Cập nhật đồng bộ hướng bàn cờ NGAY LẬP TỨC cho frame hiện tại
            learnDualBankFromOpening(cells, isWhiteMap)
            val whiteBottomNow = chessOverlayView.isWhiteBottom

            val rawMatrix = Array(8) { CharArray(8) { '1' } }
            for (r in 0..7) {
                for (c in 0..7) {
                    val cell = cells[r][c]
                    if (!cell.isOccupied) continue
                    val isWhite = isWhiteMap[r][c]
                    val pieceType = recognizePieceType(r, c, cell, isWhite)
                    rawMatrix[r][c] = if (isWhite) pieceType.uppercaseChar() else pieceType.lowercaseChar()
                }
            }

            val standardMatrix = if (whiteBottomNow) rawMatrix
                                 else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }

            // 4. Khóa số lượng quân hợp lý (Tránh nhận nhầm Tốt thành Xe/Tượng/Mã thứ 3)
            enforcePieceCountSanity(standardMatrix, cells, whiteBottomNow)

            val boardFen = buildFenBoardString(standardMatrix)
            mainHandler.post { tvFenDebug.text = "FEN: $boardFen" }

            // 5. KIỂM TRA NGHIÊM NGẶT LUẬT CỜ VUA (TUYỆT ĐỐI KHÔNG TỰ BỊA QUÂN CỜ)
            val validationError = validateChessPositionStrict(standardMatrix)
            if (validationError != null) {
                mainHandler.post {
                    tvStatus.text = "⚠️ Đọc chưa chắc ($validationError) • Chờ khung sau"
                }
                return
            }

            updateCastlingRights(standardMatrix)

            val myColorChar = if (whiteBottomNow) 'w' else 'b'
            val detectedTurn = determineSideToMoveAndUpdateHistory(standardMatrix, cells, whiteBottomNow, myColorChar)
            val activeTurn = forceTurnSide ?: detectedTurn

            // Kiểm tra hợp lệ chiếu tướng: Bên KHÔNG đến lượt đi không được phép đang bị chiếu!
            val nonMovingSide = if (activeTurn == 'w') 'b' else 'w'
            if (isKingInCheck(standardMatrix, nonMovingSide)) {
                mainHandler.post {
                    tvStatus.text = "⚠️ Thế cờ chưa đồng bộ lượt chiếu • Đang quét lại"
                }
                return
            }

            val castlingStr = getValidCastlingString(standardMatrix)
            val epStr = lastEnPassantTarget
            val stateKey = "$boardFen|$activeTurn|$castlingStr|$epStr"
            latestValidStateKey = stateKey
            val reqGen = stateGeneration.incrementAndGet()

            if (activeTurn != myColorChar) {
                lastCompletedStateKey = stateKey
                mainHandler.post {
                    chessOverlayView.setMove(null)
                    tvStatus.text = "⏳ Đang là lượt đối thủ suy nghĩ..."
                }
                return
            }

            if (stateKey == lastCompletedStateKey) return

            val fullFen = "$boardFen $activeTurn $castlingStr $epStr $halfmoveClock $fullmoveNumber"

            val cachedMove = moveCache[fullFen]
            if (cachedMove != null) {
                if (latestValidStateKey == stateKey) {
                    lastCompletedStateKey = stateKey
                    mainHandler.post {
                        chessOverlayView.setMove(cachedMove)
                        tvStatus.text = "✅ Đi ngay: ${formatMoveText(cachedMove)} (Cache)"
                    }
                }
                return
            }

            if (SystemClock.uptimeMillis() < nextAllowedApiCallTime) {
                mainHandler.post { tvStatus.text = "⏳ Đang chờ hồi API..." }
                return
            }

            val bestMove = queryStockfishOnlineWithBackoff(fullFen)
            // CHỈ CẬP NHẬT MŨI TÊN NẾU THẾ CỜ HIỆN TẠI VẪN KHỚP VỚI THẾ CỜ LÚC GỬI REQUEST
            if (latestValidStateKey != stateKey || stateGeneration.get() != reqGen) {
                return
            }

            mainHandler.post {
                if (bestMove != null && bestMove.length >= 4) {
                    moveCache[fullFen] = bestMove
                    lastCompletedStateKey = stateKey
                    chessOverlayView.setMove(bestMove)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(bestMove)}"
                } else {
                    tvStatus.text = "⚠️ Không có nước đi hợp lệ hoặc nghẽn mạng"
                }
            }
        } catch (e: Exception) {
            mainHandler.post { tvStatus.text = "Lỗi xử lý: ${e.message}" }
        } finally {
            fullBmp.recycle()
            isProcessing.set(false)
        }
    }

    private fun computeBoardSignature64(boardBmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(boardBmp, 16, 16, true)
        val sig = IntArray(64)
        for (r in 0..7) {
            for (c in 0..7) {
                val p = small.getPixel(c * 2 + 1, r * 2 + 1)
                sig[r * 8 + c] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
        }
        small.recycle()
        return sig
    }

    // PHÂN CỤM 2 NHÓM ĐỘ SÁNG ĐỘNG (2-MEANS CLUSTERING) TRÊN MỌI QUÂN CỜ HIỆN CÓ
    private fun computeDynamicPieceLumThreshold(cells: Array<Array<CellAnalysis>>): Int {
        val lums = mutableListOf<Int>()
        for (r in 0..7) for (c in 0..7) {
            if (cells[r][c].isOccupied) lums.add(cells[r][c].coreLuminance)
        }
        if (lums.size < 2) return 145
        lums.sort()
        var cDark = lums.first().toFloat()
        var cLight = lums.last().toFloat()
        if (abs(cLight - cDark) < 15f) return 145

        for (iter in 0..5) {
            var sumDark = 0f; var cntDark = 0
            var sumLight = 0f; var cntLight = 0
            val mid = (cDark + cLight) / 2f
            for (v in lums) {
                if (v <= mid) { sumDark += v; cntDark++ }
                else { sumLight += v; cntLight++ }
            }
            if (cntDark > 0) cDark = sumDark / cntDark
            if (cntLight > 0) cLight = sumLight / cntLight
        }
        return ((cDark + cLight) / 2f).toInt()
    }

    private fun formatMoveText(uci: String): String {
        val base = "${uci.substring(0, 2).uppercase()} ➔ ${uci.substring(2, 4).uppercase()}"
        return if (uci.length >= 5) "$base (Phong ${uci[4].uppercaseChar()})" else base
    }

    // QUÉT ĐIỀN ĐẦY SCANLINE + LOẠI TRỪ GÓC TỌA ĐỘ A-H/1-8 + LOẠI TRỪ CHẤM TRÒN GỢI Ý NƯỚC ĐI
    private fun analyzeSingleCellScanline(cellBmp: Bitmap, refBgColor: Int): CellAnalysis {
        val scaled = Bitmap.createScaledBitmap(cellBmp, 32, 32, true)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        scaled.recycle()

        // Lấy mẫu nền tránh hoàn toàn 4 góc (nơi chứa ký tự tọa độ a-h và 1-8)
        val localBgSamples = mutableListOf<Int>()
        for (x in 9..22 step 3) {
            localBgSamples.add(pixels[3 * 32 + x])
            localBgSamples.add(pixels[28 * 32 + x])
        }
        val localBg = medianColor(localBgSamples)
        val bgR = Color.red(localBg); val bgG = Color.green(localBg); val bgB = Color.blue(localBg)

        val isHighlighted = colorDist(localBg, refBgColor) > 28

        val boundaryMask = BooleanArray(32 * 32)
        for (y in 4..27) {
            for (x in 5..26) {
                // Loại trừ góc trên-trái (y<=9, x<=9) và góc dưới-phải (y>=23, x>=23) chứa chữ số 1-8, a-h
                if ((y <= 9 && x <= 9) || (y >= 23 && x >= 23)) continue

                val idx = y * 32 + x
                val p = pixels[idx]
                val distToBg = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)

                val pLeft = pixels[idx - 1]; val pRight = pixels[idx + 1]
                val pUp = pixels[idx - 32]; val pDown = pixels[idx + 32]
                val grad = abs(Color.green(pRight) - Color.green(pLeft)) +
                           abs(Color.green(pDown) - Color.green(pUp)) +
                           abs(Color.red(pRight) - Color.red(pLeft))

                if (distToBg > 38 || grad > 42) {
                    boundaryMask[idx] = true
                }
            }
        }

        val solidMask = BooleanArray(32 * 32)
        val silhouette = FloatArray(16 * 16)
        var bodyMass = 0
        var bottomMass = 0
        var highestY = 32
        var lowestY = 0
        var leftHalf = 0f; var rightHalf = 0f

        val sliceRanges = arrayOf(4..7, 8..11, 12..15, 16..19, 20..25)
        val sliceWidths = IntArray(5)

        for (y in 4..26) {
            var leftEdge = -1
            var rightEdge = -1
            for (x in 5..26) {
                if (boundaryMask[y * 32 + x]) {
                    val neighbors = (if (boundaryMask[y * 32 + x - 1]) 1 else 0) +
                                    (if (boundaryMask[y * 32 + x + 1]) 1 else 0) +
                                    (if (boundaryMask[(y - 1) * 32 + x]) 1 else 0) +
                                    (if (boundaryMask[(y + 1) * 32 + x]) 1 else 0)
                    if (neighbors >= 1) {
                        if (leftEdge == -1) leftEdge = x
                        rightEdge = x
                    }
                }
            }

            if (leftEdge != -1 && rightEdge >= leftEdge) {
                val span = rightEdge - leftEdge + 1
                if (span in 3..21) {
                    if (y < highestY) highestY = y
                    if (y > lowestY) lowestY = y
                    for (x in leftEdge..rightEdge) {
                        solidMask[y * 32 + x] = true
                        bodyMass++
                        if (y >= 20) bottomMass++
                        if (y in 7..17) {
                            if (x < 16) leftHalf += 1f else rightHalf += 1f
                        }
                        val sx = ((x - 6) * 16 / 20).coerceIn(0, 15)
                        val sy = ((y - 4) * 16 / 23).coerceIn(0, 15)
                        silhouette[sy * 16 + sx] += 1f
                    }
                }
            }
        }

        for (s in 0..4) {
            var sumW = 0
            var rows = 0
            for (y in sliceRanges[s]) {
                var minX = 32; var maxX = 0
                for (x in 5..26) {
                    if (solidMask[y * 32 + x]) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
                if (maxX >= minX) sumW += (maxX - minX + 1)
                rows++
            }
            sliceWidths[s] = sumW / rows
        }

        val verticalSpan = if (lowestY >= highestY) lowestY - highestY else 0
        // Chấm gợi ý nước đi chỉ nằm ở giữa ô (y:11..20), nên bắt buộc phải có đế ở đáy (lowestY >= 22 và bottomMass >= 14) mới là quân cờ thật!
        val isRealPiece = bodyMass >= 52 && bottomMass >= 14 && verticalSpan >= 11 && lowestY >= 22 && sliceWidths[4] >= 7

        normalizeTemplate(silhouette)

        val coreLums = IntArray(72)
        var k = 0
        for (y in 14..22) {
            for (x in 12..19) {
                val p = pixels[y * 32 + x]
                coreLums[k++] = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            }
        }
        coreLums.sort()
        val medianCoreLum = coreLums[36]
        val headAsymmetry = abs(leftHalf - rightHalf) / (leftHalf + rightHalf + 1f)

        return CellAnalysis(isRealPiece, medianCoreLum, isHighlighted, silhouette, bodyMass, highestY, sliceWidths, headAsymmetry)
    }

    private fun calibrateDynamicThresholdsFromAnyBoard(cells: Array<Array<CellAnalysis>>) {
        val midBoardMasses = mutableListOf<Int>()
        val midBoardTopYs = mutableListOf<Int>()
        for (r in 1..6) {
            for (c in 0..7) {
                val cell = cells[r][c]
                if (cell.isOccupied) {
                    midBoardMasses.add(cell.bodyMass)
                    midBoardTopYs.add(cell.highestY)
                }
            }
        }
        if (midBoardMasses.size >= 4) {
            midBoardMasses.sort()
            midBoardTopYs.sortDescending()
            val samplePawnMass = midBoardMasses[(midBoardMasses.size * 35) / 100]
            val samplePawnTopY = midBoardTopYs[(midBoardTopYs.size * 35) / 100]
            dynamicPawnMaxMass = (samplePawnMass * 1.18f).toInt().coerceIn(120, 195)
            dynamicPawnMinTopY = (samplePawnTopY - 1).coerceIn(6, 10)
        }
    }

    private fun learnDualBankFromOpening(cells: Array<Array<CellAnalysis>>, isWhiteMap: Array<BooleanArray>) {
        var backRankCount = 0
        var pawnRankCount = 0
        for (c in 0..7) {
            if (cells[0][c].isOccupied) backRankCount++
            if (cells[7][c].isOccupied) backRankCount++
            if (cells[1][c].isOccupied) pawnRankCount++
            if (cells[6][c].isOccupied) pawnRankCount++
        }

        if (backRankCount >= 12 && pawnRankCount >= 10 && dualTemplates.size < 10) {
            var whiteVotes = 0; var blackVotes = 0
            for (c in 0..7) {
                if (cells[7][c].isOccupied) {
                    if (isWhiteMap[7][c]) whiteVotes++ else blackVotes++
                }
            }
            val whiteAtBottom = whiteVotes >= blackVotes
            // CẬP NHẬT ĐỒNG BỘ NGAY TRÊN LUỒNG XỬ LÝ TRƯỚC KHI XOAY MA TRẬN
            chessOverlayView.isWhiteBottom = whiteAtBottom
            mainHandler.post {
                btnSide.text = if (whiteAtBottom) "⚪ Trắng" else "⚫ Đen"
            }

            for (c in 0..7) {
                if (cells[6][c].isOccupied && isWhiteMap[6][c] == whiteAtBottom) {
                    val key = if (whiteAtBottom) 'P' else 'p'
                    dualTemplates[key] = cells[6][c].silhouette16x16.clone()
                }
                if (cells[1][c].isOccupied && isWhiteMap[1][c] != whiteAtBottom) {
                    val key = if (whiteAtBottom) 'p' else 'P'
                    dualTemplates[key] = cells[1][c].silhouette16x16.clone()
                }
            }

            var botFull = true
            for (c in 0..7) if (!cells[7][c].isOccupied) botFull = false
            if (botFull) {
                val order = if (whiteAtBottom) charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
                            else charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
                for (c in 0..7) dualTemplates[order[c]] = cells[7][c].silhouette16x16.clone()
            }

            var topFull = true
            for (c in 0..7) if (!cells[0][c].isOccupied) topFull = false
            if (topFull) {
                val order = if (whiteAtBottom) charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
                            else charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R')
                for (c in 0..7) dualTemplates[order[c]] = cells[0][c].silhouette16x16.clone()
            }

            if (botFull && topFull && pawnRankCount == 16) {
                hasFullGameHistory = true
                wKingMoved = false; wRookAMoved = false; wRookHMoved = false
                bKingMoved = false; bRookAMoved = false; bRookHMoved = false
                halfmoveClock = 0
                fullmoveNumber = 1
            }
        }
    }

    private fun recognizePieceType(r: Int, c: Int, cell: CellAnalysis, isWhite: Boolean): Char {
        val s0 = cell.sliceWidths[0]
        val s1 = cell.sliceWidths[1]
        val s2 = cell.sliceWidths[2]

        if (r in 1..6) {
            val pawnKey = if (isWhite) 'P' else 'p'
            val pawnTpl = dualTemplates[pawnKey] ?: dualTemplates['P'] ?: dualTemplates['p']
            if (pawnTpl != null) {
                var corr = 0f
                for (i in 0 until 256) corr += cell.silhouette16x16[i] * pawnTpl[i]
                if (corr > 0.72f && cell.bodyMass <= dynamicPawnMaxMass) return 'P'
            }
            if (cell.highestY >= dynamicPawnMinTopY && s0 <= 4 && s1 <= 9 && cell.bodyMass <= dynamicPawnMaxMass && cell.headAsymmetry < 0.21f) {
                return 'P'
            }
        }

        val candidateChars = if (isWhite) charArrayOf('R', 'N', 'B', 'Q', 'K')
                             else charArrayOf('r', 'n', 'b', 'q', 'k')
        var bestMatch = 'R'
        var bestCorr = -999f
        for (ch in candidateChars) {
            val tpl = dualTemplates[ch] ?: dualTemplates[ch.uppercaseChar()] ?: continue
            var corr = 0f
            for (i in 0 until 256) corr += cell.silhouette16x16[i] * tpl[i]
            if (corr > bestCorr) {
                bestCorr = corr
                bestMatch = ch.uppercaseChar()
            }
        }
        if (bestCorr > 0.70f) return bestMatch

        if (cell.headAsymmetry >= 0.20f) return 'N'
        if (s0 <= 5 && s1 >= 9 && s1 >= s2) return 'R'
        if (cell.highestY <= 6 && s0 >= 6) {
            return if (s0 >= 9 || s1 >= 11) 'Q' else 'K'
        }
        if (s1 < s2 && s2 >= 8) return 'B'

        return if (r == 0 || r == 7) 'R' else 'P'
    }

    private fun enforcePieceCountSanity(standardMatrix: Array<CharArray>, rawCells: Array<Array<CellAnalysis>>, whiteBottom: Boolean) {
        sanitizeSideMajorCount(standardMatrix, rawCells, whiteBottom, isWhite = true, majorChar = 'R', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, whiteBottom, isWhite = true, majorChar = 'B', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, whiteBottom, isWhite = true, majorChar = 'N', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, whiteBottom, isWhite = false, majorChar = 'r', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, whiteBottom, isWhite = false, majorChar = 'b', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, whiteBottom, isWhite = false, majorChar = 'n', maxAllowed = 2)
    }

    private fun sanitizeSideMajorCount(
        standardMatrix: Array<CharArray>,
        rawCells: Array<Array<CellAnalysis>>,
        whiteBottom: Boolean,
        isWhite: Boolean,
        majorChar: Char,
        maxAllowed: Int
    ) {
        val list = mutableListOf<Triple<Int, Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                if (standardMatrix[r][c] == majorChar) {
                    val rawR = if (whiteBottom) r else 7 - r
                    val rawC = if (whiteBottom) c else 7 - c
                    list.add(Triple(r, c, rawCells[rawR][rawC].bodyMass))
                }
            }
        }
        if (list.size > maxAllowed) {
            list.sortBy { it.third }
            var excess = list.size - maxAllowed
            for (item in list) {
                if (excess <= 0) break
                val r = item.first; val c = item.second
                if (r in 1..6) {
                    standardMatrix[r][c] = if (isWhite) 'P' else 'p'
                    excess--
                }
            }
        }
    }

    // XÁC ĐỊNH LƯỢT ĐI CHUẨN XÁC + GIỮ NGUYÊN LƯỢT KHI THẾ CỜ KHÔNG ĐỔI + TÍNH EN PASSANT & BỘ ĐẾM NƯỚC
    private fun determineSideToMoveAndUpdateHistory(
        currentMatrix: Array<CharArray>,
        cells: Array<Array<CellAnalysis>>,
        whiteBottom: Boolean,
        myColor: Char
    ): Char {
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        if (buildFenBoardString(currentMatrix) == startFen) {
            previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
            lastDetectedTurn = 'w'
            lastEnPassantTarget = "-"
            return 'w'
        }

        // Quy tắc 1: Nếu một bên đang bị chiếu tướng, bắt buộc 100% là lượt của bên đó!
        val wInCheck = isKingInCheck(currentMatrix, 'w')
        val bInCheck = isKingInCheck(currentMatrix, 'b')
        if (wInCheck && !bInCheck) {
            lastDetectedTurn = 'w'
            previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
            return 'w'
        }
        if (bInCheck && !wInCheck) {
            lastDetectedTurn = 'b'
            previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
            return 'b'
        }

        val prev = previousValidMatrix
        if (prev != null) {
            var diffCount = 0
            var whiteArrived = 0
            var blackArrived = 0
            var pawnMovedOrCapture = false

            for (r in 0..7) {
                for (c in 0..7) {
                    val oldCh = prev[r][c]
                    val newCh = currentMatrix[r][c]
                    if (oldCh != newCh) {
                        diffCount++
                        if (newCh != '1') {
                            if (newCh.isUpperCase()) whiteArrived++ else blackArrived++
                            if (newCh.uppercaseChar() == 'P' || oldCh != '1') pawnMovedOrCapture = true
                        }
                    }
                }
            }

            // NẾU BÀN CỜ KHÔNG ĐỔI SO VỚI LẦN QUÉT TRƯỚC -> GIỮ NGUYÊN LƯỢT CŨ (KHÔNG BAO GIỜ LẬT SAI LƯỢT Ở LẦN QUÉT THỨ 2!)
            if (diffCount == 0) {
                return lastDetectedTurn
            }

            // Suy luận ô bắt Tốt qua đường (En Passant) từ bước nhảy 2 ô của Tốt
            lastEnPassantTarget = detectEnPassantSquare(prev, currentMatrix)

            if (whiteArrived > 0 && blackArrived == 0) {
                lastDetectedTurn = 'b'
                halfmoveClock = if (pawnMovedOrCapture) 0 else halfmoveClock + 1
                previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
                return 'b'
            }
            if (blackArrived > 0 && whiteArrived == 0) {
                lastDetectedTurn = 'w'
                halfmoveClock = if (pawnMovedOrCapture) 0 else halfmoveClock + 1
                fullmoveNumber++
                previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
                return 'w'
            }
        }

        // Quy tắc 3: Nếu mới bật app lần đầu giữa trận, kiểm tra ô tô sáng của nước vừa đi
        val highlightedPieces = mutableListOf<Char>()
        for (r in 0..7) {
            for (c in 0..7) {
                val orientR = if (whiteBottom) r else 7 - r
                val orientC = if (whiteBottom) c else 7 - c
                if (cells[orientR][orientC].isHighlighted && currentMatrix[r][c] != '1') {
                    highlightedPieces.add(currentMatrix[r][c])
                }
            }
        }
        val inferredTurn = if (highlightedPieces.size == 1) {
            if (highlightedPieces[0].isUpperCase()) 'b' else 'w'
        } else {
            myColor
        }

        lastDetectedTurn = inferredTurn
        previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
        return inferredTurn
    }

    private fun detectEnPassantSquare(prev: Array<CharArray>, curr: Array<CharArray>): String {
        for (c in 0..7) {
            // Tốt Trắng nhảy 2 ô từ hàng 6 lên hàng 4
            if (prev[6][c] == 'P' && curr[6][c] == '1' && curr[5][c] == '1' && curr[4][c] == 'P') {
                return "${'a' + c}3"
            }
            // Tốt Đen nhảy 2 ô từ hàng 1 xuống hàng 3
            if (prev[1][c] == 'p' && curr[1][c] == '1' && curr[2][c] == '1' && curr[3][c] == 'p') {
                return "${'a' + c}6"
            }
        }
        return "-"
    }

    // KIỂM TRA VUA CÓ ĐANG BỊ CHIẾU HAY KHÔNG (DÙNG CHO XÁC ĐỊNH LƯỢT & KIỂM TRA HỢP LỆ FEN)
    private fun isKingInCheck(matrix: Array<CharArray>, kingSide: Char): Boolean {
        val kingChar = if (kingSide == 'w') 'K' else 'k'
        val byWhite = (kingSide == 'b')
        var kr = -1; var kc = -1
        for (r in 0..7) for (c in 0..7) {
            if (matrix[r][c] == kingChar) { kr = r; kc = c; break }
        }
        if (kr == -1) return false

        // 1. Kiểm tra Tốt chiếu
        val pawnRow = if (byWhite) kr + 1 else kr - 1
        val enemyPawn = if (byWhite) 'P' else 'p'
        if (pawnRow in 0..7) {
            if (kc - 1 >= 0 && matrix[pawnRow][kc - 1] == enemyPawn) return true
            if (kc + 1 <= 7 && matrix[pawnRow][kc + 1] == enemyPawn) return true
        }

        // 2. Kiểm tra Mã chiếu
        val enemyKnight = if (byWhite) 'N' else 'n'
        val knightMoves = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        for ((dr, dc) in knightMoves) {
            val nr = kr + dr; val nc = kc + dc
            if (nr in 0..7 && nc in 0..7 && matrix[nr][nc] == enemyKnight) return true
        }

        // 3. Kiểm tra tia thẳng (Xe / Hậu)
        val enemyRook = if (byWhite) 'R' else 'r'
        val enemyQueen = if (byWhite) 'Q' else 'q'
        val orthoDirs = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in orthoDirs) {
            var r = kr + dr; var c = kc + dc
            while (r in 0..7 && c in 0..7) {
                val ch = matrix[r][c]
                if (ch != '1') {
                    if (ch == enemyRook || ch == enemyQueen) return true
                    break
                }
                r += dr; c += dc
            }
        }

        // 4. Kiểm tra tia chéo (Tượng / Hậu)
        val enemyBishop = if (byWhite) 'B' else 'b'
        val diagDirs = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        for ((dr, dc) in diagDirs) {
            var r = kr + dr; var c = kc + dc
            while (r in 0..7 && c in 0..7) {
                val ch = matrix[r][c]
                if (ch != '1') {
                    if (ch == enemyBishop || ch == enemyQueen) return true
                    break
                }
                r += dr; c += dc
            }
        }

        return false
    }

    // KIỂM TRA FEN NGHIÊM NGẶT (TUYỆT ĐỐI KHÔNG BỊA QUÂN CỜ)
    private fun validateChessPositionStrict(matrix: Array<CharArray>): String? {
        var wKings = 0; var bKings = 0
        var wPieces = 0; var bPieces = 0
        var wkR = -1; var wkC = -1; var bkR = -1; var bkC = -1

        for (r in 0..7) {
            for (c in 0..7) {
                val ch = matrix[r][c]
                if (ch == '1') continue
                if (ch.isUpperCase()) wPieces++ else bPieces++
                if ((r == 0 || r == 7) && (ch == 'P' || ch == 'p')) {
                    return "Có Tốt ở hàng cuối"
                }
                if (ch == 'K') { wKings++; wkR = r; wkC = c }
                if (ch == 'k') { bKings++; bkR = r; bkC = c }
            }
        }

        if (wKings != 1 || bKings != 1) {
            return "Đọc số Vua chưa chuẩn (K=$wKings, k=$bKings)"
        }
        if (wPieces > 16 || bPieces > 16) return "Quá 16 quân một phe"
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) return "2 Vua đứng sát nhau"
        if (isKingInCheck(matrix, 'w') && isKingInCheck(matrix, 'b')) return "Cả 2 Vua cùng bị chiếu"

        return null
    }

    private fun updateCastlingRights(matrix: Array<CharArray>) {
        if (matrix[7][4] != 'K') wKingMoved = true
        if (matrix[7][0] != 'R') wRookAMoved = true
        if (matrix[7][7] != 'R') wRookHMoved = true
        if (matrix[0][4] != 'k') bKingMoved = true
        if (matrix[0][0] != 'r') bRookAMoved = true
        if (matrix[0][7] != 'r') bRookHMoved = true
    }

    private fun getValidCastlingString(matrix: Array<CharArray>): String {
        if (castlingMode == 2) return "-"
        if (castlingMode == 0 && !hasFullGameHistory) return "-"

        val allowAllUnmovedOnHome = (castlingMode == 1)
        val sb = StringBuilder()
        if ((allowAllUnmovedOnHome || !wKingMoved) && matrix[7][4] == 'K') {
            if ((allowAllUnmovedOnHome || !wRookHMoved) && matrix[7][7] == 'R') sb.append('K')
            if ((allowAllUnmovedOnHome || !wRookAMoved) && matrix[7][0] == 'R') sb.append('Q')
        }
        if ((allowAllUnmovedOnHome || !bKingMoved) && matrix[0][4] == 'k') {
            if ((allowAllUnmovedOnHome || !bRookHMoved) && matrix[0][7] == 'r') sb.append('k')
            if ((allowAllUnmovedOnHome || !bRookAMoved) && matrix[0][0] == 'r') sb.append('q')
        }
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    private fun buildFenBoardString(matrix: Array<CharArray>): String {
        val rows = ArrayList<String>(8)
        for (r in 0..7) {
            var empty = 0
            val sb = StringBuilder()
            for (c in 0..7) {
                val ch = matrix[r][c]
                if (ch == '1') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            rows.add(sb.toString())
        }
        return rows.joinToString("/")
    }

    private fun queryStockfishOnlineWithBackoff(fen: String): String? {
        val move = fetchFromStockfishOnline(fen) ?: fetchFromChessApi(fen)
        if (move != null) {
            consecutiveApiFailures = 0
            return move
        }
        consecutiveApiFailures++
        val backoffMs = min(12000L, 1500L * (1 shl consecutiveApiFailures.coerceAtMost(3)))
        nextAllowedApiCallTime = SystemClock.uptimeMillis() + backoffMs
        return null
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=11")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000; readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                if (!json.optBoolean("success", true)) return null
                val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                val parts = raw.trim().split(" ")
                val candidate = if (parts.size >= 2) parts[1] else parts[0]
                if (candidate.length >= 4 && candidate != "(none)") candidate.substring(0, min(candidate.length, 5)) else null
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun fetchFromChessApi(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://chess-api.com/v1")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000; readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().put("fen", fen).put("depth", 10).toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val move = json.optString("move", "")
                if (move.length >= 4) move.substring(0, min(move.length, 5)) else null
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun normalizeTemplate(arr: FloatArray) {
        var sum = 0f
        for (v in arr) sum += v
        val mean = sum / arr.size
        var sqSum = 0f
        for (i in arr.indices) {
            arr[i] -= mean
            sqSum += arr[i] * arr[i]
        }
        val norm = sqrt(sqSum).coerceAtLeast(1e-5f)
        for (i in arr.indices) arr[i] /= norm
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.GRAY
        val rList = colors.map { Color.red(it) }.sorted()
        val gList = colors.map { Color.green(it) }.sorted()
        val bList = colors.map { Color.blue(it) }.sorted()
        val mid = colors.size / 2
        return Color.rgb(rList[mid], gList[mid], bList[mid])
    }

    private fun colorDist(c1: Int, c2: Int): Int {
        return (abs(Color.red(c1) - Color.red(c2)) +
                abs(Color.green(c1) - Color.green(c2)) +
                abs(Color.blue(c1) - Color.blue(c2))) / 3
    }

    private fun startForegroundNotification() {
        val channelId = "chess_overlay_channel"
        val channel = NotificationChannel(channelId, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("True Vision v6.3")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false
        mainHandler.removeCallbacksAndMessages(null)
        bgHandler?.removeCallbacksAndMessages(null)
        bgThread?.quitSafely()
        workerExecutor.shutdownNow()
        cleanupMediaProjection()
        synchronized(frameCacheLock) {
            lastCleanFullBitmap?.recycle()
            lastCleanFullBitmap = null
        }
        try {
            if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView)
        } catch (_: Exception) {}
        try {
            if (::controlLayout.isInitialized) windowManager.removeView(controlLayout)
        } catch (_: Exception) {}
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }
}
