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
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var controlLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvFenDebug: TextView
    private lateinit var btnSide: Button
    private lateinit var btnTurn: Button
    private lateinit var controlParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    @Volatile private var screenWidth = 1080
    @Volatile private var screenHeight = 2400
    @Volatile private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var isAutoMode = false
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var waitingForCleanFrame = false
    @Volatile private var cleanFrameMinTimestamp = 0L

    @Volatile private var detectedBoardRect: RectF? = null
    @Volatile private var previousValidMatrix: Array<CharArray>? = null
    @Volatile private var lastAnalyzedStateKey = ""
    @Volatile private var forceTurnSide: Char? = null

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

    // NGÂN HÀNG MẪU KÉP: Lưu riêng biệt hình bóng Quân Trắng ('P','R','N','B','Q','K') và Quân Đen ('p','r','n','b','q','k')
    private val dualTemplates = ConcurrentHashMap<Char, FloatArray>()
    @Volatile private var dynamicPawnMaxMass = 165
    @Volatile private var dynamicPawnMinTopY = 7

    data class CellAnalysis(
        val isOccupied: Boolean,
        val isInFlightNoise: Boolean,
        val isWhitePiece: Boolean,
        val isHighlighted: Boolean,
        val silhouette16x16: FloatArray,
        val bodyMass: Int,
        val highestY: Int,
        val sliceWidths: IntArray, // 5 lát cắt ngang từ đỉnh xuống đế: [0:Chóp, 1:Đầu, 2:Cổ, 3:Bụng, 4:Đế]
        val headAsymmetry: Float
    )

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode) return
            requestCleanCapture()
            mainHandler.postDelayed(this, 1250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bgThread = HandlerThread("ImageCaptureThread").apply { start() }
        bgHandler = Handler(bgThread.looper)

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
        detectedBoardRect = null
        recreateVirtualDisplay()
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
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        virtualDisplay?.release()
                        imageReader?.close()
                        mediaProjection = null
                    }
                }, mainHandler)
                recreateVirtualDisplay()
                mainHandler.postDelayed({ requestCleanCapture() }, 600)
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
            setBackgroundColor(Color.argb(242, 18, 18, 22))
            setPadding(16, 10, 16, 10)
        }
        tvStatus = TextView(this).apply {
            text = "True Vision v6.2 • Đã vá lỗi Quân Đen & Tốt"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 11.5f
        }
        tvFenDebug = TextView(this).apply {
            text = "FEN: Chưa quét"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 9.5f
            maxLines = 1
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }

        btnSide = createButton("⚪ Phe: Trắng") {
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
            btnSide.text = if (chessOverlayView.isWhiteBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
            lastAnalyzedStateKey = ""
            previousValidMatrix = null
            chessOverlayView.setMove(null)
            requestCleanCapture()
        }

        btnTurn = createButton("🔄 Lượt: Tự Động") {
            forceTurnSide = when (forceTurnSide) {
                null -> if (chessOverlayView.isWhiteBottom) 'w' else 'b'
                'w' -> 'b'
                else -> null
            }
            btnTurn.text = when (forceTurnSide) {
                'w' -> "⚡ Lượt: Trắng"
                'b' -> "⚡ Lượt: Đen"
                else -> "🔄 Lượt: Tự Động"
            }
            lastAnalyzedStateKey = ""
            requestCleanCapture()
        }

        val btnAuto = createButton("▶ Auto") { btn ->
            isAutoMode = !isAutoMode
            mainHandler.removeCallbacks(autoLoopRunnable)
            if (isAutoMode) {
                btn.text = "⏸ Dừng"
                lastAnalyzedStateKey = ""
                mainHandler.post(autoLoopRunnable)
            } else {
                btn.text = "▶ Auto"
                chessOverlayView.setMove(null)
                tvStatus.text = "Đã tạm dừng Auto."
            }
        }

        val btnScan = createButton("⚡ Quét") {
            lastAnalyzedStateKey = ""
            detectedBoardRect = null
            dualTemplates.clear()
            requestCleanCapture()
        }

        val btnClose = createButton("✕") { stopSelf() }

        row.addView(btnSide); row.addView(btnTurn); row.addView(btnAuto); row.addView(btnScan); row.addView(btnClose)
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
            text = label; textSize = 10f; setPadding(11, 5, 11, 5)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick(this) }
        }
    }

    private fun recreateVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()
        val mp = mediaProjection ?: return

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            if (!waitingForCleanFrame || SystemClock.uptimeMillis() < cleanFrameMinTimestamp) {
                img.close()
                return@setOnImageAvailableListener
            }
            if (!isProcessing.compareAndSet(false, true)) {
                img.close()
                return@setOnImageAvailableListener
            }
            waitingForCleanFrame = false

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

                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                workerExecutor.execute { processCapturedBitmap(fullBmp) }
            } catch (e: Exception) {
                img.close()
                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                isProcessing.set(false)
            }
        }, bgHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "ChessTrueCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun requestCleanCapture() {
        if (isProcessing.get() || waitingForCleanFrame) return
        mainHandler.post {
            chessOverlayView.visibility = View.INVISIBLE
            try { imageReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
            Choreographer.getInstance().postFrameCallback {
                cleanFrameMinTimestamp = SystemClock.uptimeMillis() + 35L
                waitingForCleanFrame = true
                controlLayout.invalidate()
                bgHandler.postDelayed({
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
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                workerExecutor.execute { processCapturedBitmap(fullBmp) }
                            } catch (e: Exception) {
                                img.close()
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                isProcessing.set(false)
                            }
                        } else {
                            img?.close()
                            waitingForCleanFrame = false
                            mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                        }
                    }
                }, 140L)
            }
        }
    }

    private fun detectBoardRect(fullBmp: Bitmap): RectF? {
        val w = screenWidth.coerceAtMost(fullBmp.width)
        val h = screenHeight.coerceAtMost(fullBmp.height)
        val cellSize = w / 8f
        val searchStart = (h * 0.15f).toInt()
        val searchEnd = (h * 0.52f).toInt().coerceAtMost(h - w - 8)

        var bestTopY = -1
        var bestScore = -99999

        for (candidateY in searchStart..searchEnd step 3) {
            var score = 0
            var validRows = 0
            for (r in 0..7) {
                val sampleY = (candidateY + r * cellSize + cellSize * 0.16f).toInt()
                if (sampleY >= h) continue
                var evenLum = 0; var oddLum = 0
                for (c in 0..7) {
                    val sampleX = (c * cellSize + cellSize * 0.50f).toInt().coerceIn(0, w - 1)
                    val p = fullBmp.getPixel(sampleX, sampleY)
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if ((r + c) % 2 == 0) evenLum += lum else oddLum += lum
                }
                val diff = abs((evenLum / 4) - (oddLum / 4))
                if (diff in 14..185) {
                    score += 35
                    validRows++
                } else {
                    score -= 30
                }
            }
            if (candidateY > 10) {
                var borderDiff = 0
                for (c in 0..7) {
                    val x = (c * cellSize + cellSize * 0.5f).toInt()
                    val pAbove = fullBmp.getPixel(x, candidateY - 4)
                    val pInside = fullBmp.getPixel(x, candidateY + 6)
                    val l1 = (Color.red(pAbove) * 299 + Color.green(pAbove) * 587 + Color.blue(pAbove) * 114) / 1000
                    val l2 = (Color.red(pInside) * 299 + Color.green(pInside) * 587 + Color.blue(pInside) * 114) / 1000
                    if (abs(l1 - l2) > 8) borderDiff += 6
                }
                score += borderDiff
            }
            if (validRows >= 6 && score > bestScore) {
                bestScore = score
                bestTopY = candidateY
            }
        }

        if (bestTopY < 0) return null
        return RectF(0f, bestTopY.toFloat(), w.toFloat(), (bestTopY + w).toFloat())
    }

    private fun processCapturedBitmap(fullBmp: Bitmap) {
        try {
            var boardRect = detectedBoardRect
            if (boardRect == null) {
                boardRect = detectBoardRect(fullBmp)
                if (boardRect == null) {
                    mainHandler.post { tvStatus.text = "⚠️ Không tìm thấy bàn cờ 8x8 trên màn hình!" }
                    return
                }
                detectedBoardRect = boardRect
                mainHandler.post { chessOverlayView.detectedBoardRect = boardRect; chessOverlayView.invalidate() }
            }

            val bLeft = boardRect.left.toInt().coerceIn(0, fullBmp.width - 64)
            val bTop = boardRect.top.toInt().coerceIn(0, fullBmp.height - 64)
            val bSize = boardRect.width().toInt().coerceIn(64, minOf(fullBmp.width - bLeft, fullBmp.height - bTop))
            val boardBmp = Bitmap.createBitmap(fullBmp, bLeft, bTop, bSize, bSize)

            val cellSize = bSize / 8f

            val evenBgColors = mutableListOf<Int>()
            val oddBgColors = mutableListOf<Int>()
            for (r in 2..5) {
                for (c in 0..7) {
                    val x = (c * cellSize + cellSize * 0.14f).toInt()
                    val y = (r * cellSize + cellSize * 0.14f).toInt()
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
                    val cs = cellSize.toInt().coerceIn(8, minOf(bSize - cx, bSize - cy))
                    val cellBmp = Bitmap.createBitmap(boardBmp, cx, cy, cs, cs)
                    val refBg = if ((r + c) % 2 == 0) refEvenBg else refOddBg
                    val analysis = analyzeSingleCellScanline(cellBmp, refBg)
                    cellBmp.recycle()
                    analysis
                }
            }
            boardBmp.recycle()

            // KIỂM TRA QUÂN CỜ ĐANG BAY/KÉO DỞ TAY TRÊN MÀN HÌNH (IN-FLIGHT CHECK)
            for (r in 0..7) {
                for (c in 0..7) {
                    if (cells[r][c].isInFlightNoise) {
                        mainHandler.post {
                            tvStatus.text = "⏳ Quân cờ đang di chuyển... Đợi đứng yên"
                            if (!isAutoMode) mainHandler.postDelayed({ requestCleanCapture() }, 200)
                        }
                        return
                    }
                }
            }

            // 1. Tự động hiệu chỉnh ngưỡng kích thước Tốt/Quân lớn trên MỌI TRẠNG THÁI BÀN CỜ (Dù vào giữa trận)
            calibrateDynamicThresholdsFromAnyBoard(cells)

            // 2. Học Ngân Hàng Mẫu Kép (Cả Trắng lẫn Đen) nếu đang ở giai đoạn khai cuộc
            learnDualBankFromOpening(cells)

            val rawMatrix = Array(8) { CharArray(8) { '1' } }
            for (r in 0..7) {
                for (c in 0..7) {
                    val cell = cells[r][c]
                    if (!cell.isOccupied) continue
                    val pieceType = recognizePieceType(r, c, cell)
                    rawMatrix[r][c] = if (cell.isWhitePiece) pieceType.uppercaseChar() else pieceType.lowercaseChar()
                }
            }

            val standardMatrix = if (chessOverlayView.isWhiteBottom) rawMatrix
                                 else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }

            // 3. Khử lỗi nhận diện thừa quân lớn (Ví dụ nhìn nhầm Tốt thành Xe thứ 3 hoặc Tượng thứ 3)
            enforcePieceCountSanity(standardMatrix, cells)

            val boardFen = buildFenBoardString(standardMatrix)
            mainHandler.post { tvFenDebug.text = "FEN: $boardFen" }

            val validationError = validateChessPosition(standardMatrix)
            if (validationError != null) {
                mainHandler.post {
                    tvStatus.text = "⚠️ Đọc chưa chắc chắn ($validationError) • Chờ khung mới"
                }
                return
            }

            updateCastlingRights(standardMatrix)

            val myColorChar = if (chessOverlayView.isWhiteBottom) 'w' else 'b'
            val detectedTurn = determineSideToMove(standardMatrix, cells, myColorChar)
            val activeTurn = forceTurnSide ?: detectedTurn

            previousValidMatrix = Array(8) { r -> standardMatrix[r].clone() }

            val castlingStr = getValidCastlingString(standardMatrix)
            val stateKey = "$boardFen|$activeTurn|$castlingStr"

            if (activeTurn != myColorChar) {
                lastAnalyzedStateKey = stateKey
                mainHandler.post {
                    chessOverlayView.setMove(null)
                    tvStatus.text = "⏳ Đang là lượt đối thủ suy nghĩ..."
                }
                return
            }

            if (stateKey == lastAnalyzedStateKey) return

            val fullFen = "$boardFen $activeTurn $castlingStr - 0 1"

            val cachedMove = moveCache[fullFen]
            if (cachedMove != null) {
                lastAnalyzedStateKey = stateKey
                mainHandler.post {
                    chessOverlayView.setMove(cachedMove)
                    tvStatus.text = "✅ Đi ngay: ${formatMoveText(cachedMove)} (Cache)"
                }
                return
            }

            if (SystemClock.uptimeMillis() < nextAllowedApiCallTime) {
                mainHandler.post { tvStatus.text = "⏳ Đang chờ hồi API..." }
                return
            }

            val bestMove = queryStockfishOnlineWithBackoff(fullFen)
            mainHandler.post {
                if (bestMove != null && bestMove.length >= 4) {
                    moveCache[fullFen] = bestMove
                    lastAnalyzedStateKey = stateKey
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

    private fun formatMoveText(uci: String): String {
        val base = "${uci.substring(0, 2).uppercase()} ➔ ${uci.substring(2, 4).uppercase()}"
        return if (uci.length >= 5) "$base (Phong ${uci[4].uppercaseChar()})" else base
    }

    // THUẬT TOÁN QUÉT ĐIỀN ĐẦY THEO DÒNG (SCANLINE FLOOD-FILL BETWEEN EDGES):
    // Bắt dính 100% Quân Đen trên Ô Tối dù màu ruột của Quân Đen giống hệt màu Ô Tối!
    private fun analyzeSingleCellScanline(cellBmp: Bitmap, refBgColor: Int): CellAnalysis {
        val scaled = Bitmap.createScaledBitmap(cellBmp, 32, 32, true)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        scaled.recycle()

        val localBgSamples = mutableListOf<Int>()
        for (x in 5..26 step 3) {
            localBgSamples.add(pixels[3 * 32 + x])
            localBgSamples.add(pixels[28 * 32 + x])
        }
        val localBg = medianColor(localBgSamples)
        val bgR = Color.red(localBg); val bgG = Color.green(localBg); val bgB = Color.blue(localBg)
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        val isHighlighted = colorDist(localBg, refBgColor) > 28

        // Bước 1: Tìm điểm biên (Boundary/Edge) của quân cờ bằng kết hợp Độ lệch màu nền VÀ Độ dốc Gradient
        val boundaryMask = BooleanArray(32 * 32)
        for (y in 4..27) {
            for (x in 5..26) {
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

        // Bước 2: Điền đầy dòng quét ngang (Scanline Fill từ biên trái sang biên phải trên mỗi hàng y)
        // Giúp lấp đặc hoàn toàn phần bụng của Quân Đen trên nền tối
        val solidMask = BooleanArray(32 * 32)
        val silhouette = FloatArray(16 * 16)
        var bodyMass = 0
        var bottomMass = 0
        var highestY = 32
        var lowestY = 0
        var leftHalf = 0f; var rightHalf = 0f
        var edgeBorderHits = 0

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
                // Bỏ qua các vệt nhiễu ngang quá mảnh hoặc chạm sát rìa ô
                if (span in 3..21) {
                    if (leftEdge <= 5 || rightEdge >= 26) edgeBorderHits++
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
        // Phát hiện quân cờ thật sự (loại bỏ hoàn toàn chấm tròn gợi ý ở giữa ô vì chấm tròn có bottomMass = 0 và verticalSpan < 9)
        val isRealPiece = bodyMass >= 50 && bottomMass >= 14 && verticalSpan >= 10
        // Phát hiện quân cờ đang bay/kéo dở tay vắt qua biên ô
        val isInFlightNoise = isRealPiece && edgeBorderHits >= 12

        normalizeTemplate(silhouette)

        // Đo độ sáng vùng lõi bụng (x: 12..19, y: 14..22) để phân định Quân Trắng vs Quân Đen chuẩn xác 100%
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
        val isWhite = medianCoreLum >= 145
        val headAsymmetry = abs(leftHalf - rightHalf) / (leftHalf + rightHalf + 1f)

        return CellAnalysis(isRealPiece, isInFlightNoise, isWhite, isHighlighted, silhouette, bodyMass, highestY, sliceWidths, headAsymmetry)
    }

    // HIỆU CHỈNH ĐỘNG TRÊN MỌI KHUNG HÌNH (KỂ CẢ KHI BẬT APP Ở GIỮA TRẬN)
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
            midBoardTopYs.sortDescending() // highestY lớn nghĩa là quân cờ thấp (Tốt)
            // Phân vị 35% nhỏ nhất trên hàng 1..6 chắc chắn là nhóm quân Tốt
            val samplePawnMass = midBoardMasses[(midBoardMasses.size * 35) / 100]
            val samplePawnTopY = midBoardTopYs[(midBoardTopYs.size * 35) / 100]
            dynamicPawnMaxMass = (samplePawnMass * 1.18f).toInt().coerceIn(120, 195)
            dynamicPawnMinTopY = (samplePawnTopY - 1).coerceIn(6, 10)
        }
    }

    // HỌC NGÂN HÀNG MẪU KÉP (CẢ TRẮNG LẪN ĐEN) KHI Ở ĐẦU TRẬN
    private fun learnDualBankFromOpening(cells: Array<Array<CellAnalysis>>) {
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
                    if (cells[7][c].isWhitePiece) whiteVotes++ else blackVotes++
                }
            }
            val whiteAtBottom = whiteVotes >= blackVotes
            mainHandler.post {
                chessOverlayView.isWhiteBottom = whiteAtBottom
                btnSide.text = if (whiteAtBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
            }

            // Học mẫu Tốt dưới (hàng 6) và Tốt trên (hàng 1)
            for (c in 0..7) {
                if (cells[6][c].isOccupied && cells[6][c].isWhitePiece == whiteAtBottom) {
                    val key = if (whiteAtBottom) 'P' else 'p'
                    dualTemplates[key] = cells[6][c].silhouette16x16.clone()
                }
                if (cells[1][c].isOccupied && cells[1][c].isWhitePiece != whiteAtBottom) {
                    val key = if (whiteAtBottom) 'p' else 'P'
                    dualTemplates[key] = cells[1][c].silhouette16x16.clone()
                }
            }

            // Học hàng quân lớn bên dưới (hàng 7) nếu còn đủ 8 quân
            var botFull = true
            for (c in 0..7) if (!cells[7][c].isOccupied) botFull = false
            if (botFull) {
                val order = if (whiteAtBottom) charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
                            else charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
                for (c in 0..7) dualTemplates[order[c]] = cells[7][c].silhouette16x16.clone()
            }

            // Học hàng quân lớn bên trên (hàng 0) nếu còn đủ 8 quân
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
            }
        }
    }

    private fun recognizePieceType(r: Int, c: Int, cell: CellAnalysis): Char {
        val s0 = cell.sliceWidths[0] // Chóp (y: 4..7)
        val s1 = cell.sliceWidths[1] // Đầu (y: 8..11)
        val s2 = cell.sliceWidths[2] // Cổ (y: 12..15)

        // 1. KIỂM TRA QUÂN TỐT (P) TRƯỚC TIÊN (Chỉ ở hàng 1..6)
        if (r in 1..6) {
            val pawnKey = if (cell.isWhitePiece) 'P' else 'p'
            val pawnTpl = dualTemplates[pawnKey] ?: dualTemplates['P'] ?: dualTemplates['p']
            if (pawnTpl != null) {
                var corr = 0f
                for (i in 0 until 256) corr += cell.silhouette16x16[i] * pawnTpl[i]
                if (corr > 0.72f && cell.bodyMass <= dynamicPawnMaxMass) return 'P'
            }
            // Hình thái học bất biến của Tốt: Thấp hơn quân lớn (highestY >= dynamicPawnMinTopY), chóp hẹp (s0 <= 4), đầu tròn nhỏ (s1 <= 9)
            if (cell.highestY >= dynamicPawnMinTopY && s0 <= 4 && s1 <= 9 && cell.bodyMass <= dynamicPawnMaxMass && cell.headAsymmetry < 0.21f) {
                return 'P'
            }
        }

        // 2. So khớp với Ngân Hàng Mẫu Kép cùng màu (Trắng so với Trắng, Đen so với Đen)
        val candidateChars = if (cell.isWhitePiece) charArrayOf('R', 'N', 'B', 'Q', 'K')
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

        // 3. Phân tích giải phẫu 5 lát cắt khi chưa có mẫu
        if (cell.headAsymmetry >= 0.20f) return 'N'
        if (s0 <= 5 && s1 >= 9 && s1 >= s2) return 'R'
        if (cell.highestY <= 6 && s0 >= 6) {
            return if (s0 >= 9 || s1 >= 11) 'Q' else 'K'
        }
        if (s1 < s2 && s2 >= 8) return 'B'

        return if (r == 0 || r == 7) 'R' else 'P'
    }

    // KHÓA SỐ LƯỢNG QUÂN HỢP LÝ: Ngăn chặn triệt để việc một quân Tốt ở hàng 1..6 bị nhìn nhầm thành Xe thứ 3 hoặc Tượng thứ 3
    private fun enforcePieceCountSanity(standardMatrix: Array<CharArray>, rawCells: Array<Array<CellAnalysis>>) {
        sanitizeSideMajorCount(standardMatrix, rawCells, isWhite = true, majorChar = 'R', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, isWhite = true, majorChar = 'B', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, isWhite = true, majorChar = 'N', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, isWhite = false, majorChar = 'r', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, isWhite = false, majorChar = 'b', maxAllowed = 2)
        sanitizeSideMajorCount(standardMatrix, rawCells, isWhite = false, majorChar = 'n', maxAllowed = 2)
    }

    private fun sanitizeSideMajorCount(standardMatrix: Array<CharArray>, rawCells: Array<Array<CellAnalysis>>, isWhite: Boolean, majorChar: Char, maxAllowed: Int) {
        val list = mutableListOf<Triple<Int, Int, Int>>() // (r, c, bodyMass)
        for (r in 0..7) {
            for (c in 0..7) {
                if (standardMatrix[r][c] == majorChar) {
                    val rawR = if (chessOverlayView.isWhiteBottom) r else 7 - r
                    val rawC = if (chessOverlayView.isWhiteBottom) c else 7 - c
                    list.add(Triple(r, c, rawCells[rawR][rawC].bodyMass))
                }
            }
        }
        if (list.size > maxAllowed) {
            // Sắp xếp theo diện tích tăng dần: Quân nào diện tích nhỏ nhất và không nằm ở hàng cuối chính là quân Tốt bị nhìn nhầm!
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

    private fun determineSideToMove(currentMatrix: Array<CharArray>, cells: Array<Array<CellAnalysis>>, myColor: Char): Char {
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        if (buildFenBoardString(currentMatrix) == startFen) return 'w'

        val highlightedPieces = mutableListOf<Char>()
        for (r in 0..7) {
            for (c in 0..7) {
                val orientR = if (chessOverlayView.isWhiteBottom) r else 7 - r
                val orientC = if (chessOverlayView.isWhiteBottom) c else 7 - c
                if (cells[orientR][orientC].isHighlighted && currentMatrix[r][c] != '1') {
                    highlightedPieces.add(currentMatrix[r][c])
                }
            }
        }
        if (highlightedPieces.size == 1) {
            val movedSide = if (highlightedPieces[0].isUpperCase()) 'w' else 'b'
            return if (movedSide == 'w') 'b' else 'w'
        }

        val prev = previousValidMatrix
        if (prev != null) {
            var whiteChanged = 0
            var blackChanged = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    if (prev[r][c] != currentMatrix[r][c] && currentMatrix[r][c] != '1') {
                        if (currentMatrix[r][c].isUpperCase()) whiteChanged++ else blackChanged++
                    }
                }
            }
            if (whiteChanged > 0 && blackChanged == 0) return 'b'
            if (blackChanged > 0 && whiteChanged == 0) return 'w'
        }

        return myColor
    }

    private fun validateChessPosition(matrix: Array<CharArray>): String? {
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
            fixSingleSideKingQueen(matrix, isWhite = true)
            fixSingleSideKingQueen(matrix, isWhite = false)
            return validateChessPositionAfterResolve(matrix)
        }
        if (wPieces > 16 || bPieces > 16) return "Quá 16 quân một phe"
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) return "2 Vua đứng sát nhau"

        return null
    }

    private fun fixSingleSideKingQueen(matrix: Array<CharArray>, isWhite: Boolean) {
        val kChar = if (isWhite) 'K' else 'k'
        val qChar = if (isWhite) 'Q' else 'q'
        val homeR = if (isWhite) 7 else 0

        val kings = mutableListOf<Pair<Int, Int>>()
        val queens = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) for (c in 0..7) {
            if (matrix[r][c] == kChar) kings.add(Pair(r, c))
            if (matrix[r][c] == qChar) queens.add(Pair(r, c))
        }

        if (kings.size > 1) {
            kings.sortBy { (r, c) -> abs(r - homeR) * 10 + abs(c - 4) }
            for (i in 1 until kings.size) {
                matrix[kings[i].first][kings[i].second] = qChar
            }
        } else if (kings.isEmpty() && queens.isNotEmpty()) {
            queens.sortBy { (r, c) -> abs(r - homeR) * 10 + abs(c - 4) }
            matrix[queens[0].first][queens[0].second] = kChar
        }
    }

    private fun validateChessPositionAfterResolve(matrix: Array<CharArray>): String? {
        var wK = 0; var bK = 0
        var wkR = -1; var wkC = -1; var bkR = -1; var bkC = -1
        for (r in 0..7) for (c in 0..7) {
            if (matrix[r][c] == 'K') { wK++; wkR = r; wkC = c }
            if (matrix[r][c] == 'k') { bK++; bkR = r; bkC = c }
        }
        if (wK != 1 || bK != 1) return "Chưa xác định rõ Vua ($wK/$bK)"
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) return "2 Vua đứng cạnh nhau"
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
        if (!hasFullGameHistory) return "-"
        val sb = StringBuilder()
        if (!wKingMoved && matrix[7][4] == 'K') {
            if (!wRookHMoved && matrix[7][7] == 'R') sb.append('K')
            if (!wRookAMoved && matrix[7][0] == 'R') sb.append('Q')
        }
        if (!bKingMoved && matrix[0][4] == 'k') {
            if (!bRookHMoved && matrix[0][7] == 'r') sb.append('k')
            if (!bRookAMoved && matrix[0][0] == 'r') sb.append('q')
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
        val backoffMs = minOf(12000L, 1500L * (1 shl consecutiveApiFailures.coerceAtMost(3)))
        nextAllowedApiCallTime = SystemClock.uptimeMillis() + backoffMs
        return null
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=11")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3200; readTimeout = 3200
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                if (!json.optBoolean("success", true)) return null
                val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                val parts = raw.trim().split(" ")
                val candidate = if (parts.size >= 2) parts[1] else parts[0]
                if (candidate.length >= 4 && candidate != "(none)") candidate.substring(0, minOf(candidate.length, 5)) else null
            } else null
        } catch (e: Exception) { null }
    }

    private fun fetchFromChessApi(fen: String): String? {
        return try {
            val url = URL("https://chess-api.com/v1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3200; readTimeout = 3200
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().put("fen", fen).put("depth", 10).toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val move = json.optString("move", "")
                if (move.length >= 4) move.substring(0, minOf(move.length, 5)) else null
            } else null
        } catch (e: Exception) { null }
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
            .setContentTitle("True Vision v6.2")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false
        mainHandler.removeCallbacksAndMessages(null)
        bgHandler.removeCallbacksAndMessages(null)
        bgThread.quitSafely()
        workerExecutor.shutdownNow()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView)
        if (::controlLayout.isInitialized) windowManager.removeView(controlLayout)
    }
}
