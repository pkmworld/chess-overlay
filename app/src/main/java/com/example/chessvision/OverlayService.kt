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
    @Volatile private var lastAnalyzedBoardFen = ""
    @Volatile private var forceTurnSide: Char? = null // null = Auto detect turn, 'w' or 'b'

    // Theo dõi lịch sử di chuyển Vua/Xe để không bao giờ gợi ý nhập thành sai luật
    @Volatile private var hasFullGameHistory = false
    @Volatile private var wKingMoved = false
    @Volatile private var wRookAMoved = false
    @Volatile private var wRookHMoved = false
    @Volatile private var bKingMoved = false
    @Volatile private var bRookAMoved = false
    @Volatile private var bRookHMoved = false

    // Cache kết quả Stockfish & Backoff chống bị khóa mạng
    private val moveCache = ConcurrentHashMap<String, String>()
    @Volatile private var consecutiveApiFailures = 0
    @Volatile private var nextAllowedApiCallTime = 0L

    // Bộ lưu trữ Template 16x16 chuẩn hóa theo từng loại quân
    private val pieceTemplates = ConcurrentHashMap<Char, FloatArray>()

    data class CellAnalysis(
        val isOccupied: Boolean,
        val isWhitePiece: Boolean,
        val isHighlighted: Boolean,
        val silhouette16x16: FloatArray,
        val heightRatio: Float,
        val topWidthRatio: Float,
        val midWidthRatio: Float,
        val headAsymmetry: Float
    )

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode) return
            requestCleanCapture()
            mainHandler.postDelayed(this, 1400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bgThread = HandlerThread("ImageCaptureThread").apply { start() }
        bgHandler = Handler(bgThread.looper)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
        initDefaultSyntheticTemplates()

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
            text = "True Vision v6.0 • Sẵn sàng phân tích"
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
            lastAnalyzedBoardFen = ""
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
            lastAnalyzedBoardFen = ""
            requestCleanCapture()
        }

        val btnAuto = createButton("▶ Auto") { btn ->
            isAutoMode = !isAutoMode
            mainHandler.removeCallbacks(autoLoopRunnable)
            if (isAutoMode) {
                btn.text = "⏸ Dừng"
                lastAnalyzedBoardFen = ""
                mainHandler.post(autoLoopRunnable)
            } else {
                btn.text = "▶ Auto"
                chessOverlayView.setMove(null)
                tvStatus.text = "Đã tạm dừng Auto."
            }
        }

        val btnScan = createButton("⚡ Quét") {
            lastAnalyzedBoardFen = ""
            detectedBoardRect = null
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

    // ĐỒNG BỘ CHỤP ẢNH SẠCH 100%: ẨN OVERLAY -> ĐỢI VSYNC RENDER XONG -> LẤY KHUNG MỚI TỪ LISTENER
    private fun requestCleanCapture() {
        if (isProcessing.get() || waitingForCleanFrame) return
        mainHandler.post {
            chessOverlayView.visibility = View.INVISIBLE
            // Xả bỏ khung hình cũ còn lưu mũi tên trong buffer
            try { imageReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
            Choreographer.getInstance().postFrameCallback {
                cleanFrameMinTimestamp = SystemClock.uptimeMillis() + 35L
                waitingForCleanFrame = true
                // Kích hoạt nhẹ cập nhật màn hình để VirtualDisplay đẩy frame mới sang ImageReader ngay lập tức
                controlLayout.invalidate()
                // Fallback nếu màn hình tĩnh hoàn toàn không phát sinh frame mới sau 140ms
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

    // TỰ ĐỘNG DÒ TÌM BÀN CỜ (CẢ X, Y VÀ WIDTH) BẰNG MẪU TƯƠNG QUAN RANH GIỚI 8x8 Ô
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
            // Thưởng điểm khi mép trên cùng của hàng 0 có đường biên rõ nét so với vùng ngoài bàn cờ
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

            // 1. Tính màu nền chuẩn của Ô Sáng và Ô Tối để phát hiện Ô Tô Sáng (Last Move Highlight) & Ô Trống
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
                    val analysis = analyzeSingleCell(cellBmp, refBg)
                    cellBmp.recycle()
                    analysis
                }
            }
            boardBmp.recycle()

            // 2. Nếu phát hiện ván cờ mới ở trạng thái khai cuộc chuẩn (32 quân ở hàng 0,1,6,7), tự động học Template chuẩn 100%
            checkAndLearnInitialTemplates(cells)

            // 3. Xây dựng ma trận bàn cờ từ phân tích hình học & Template Matching
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

            val boardFen = buildFenBoardString(standardMatrix)
            mainHandler.post { tvFenDebug.text = "FEN: $boardFen" }

            // 4. KIỂM TRA TÍNH HỢP LỆ CỦA BÀN CỜ (TUYỆT ĐỐI KHÔNG BỊA QUÂN HOẶC GỬI FEN RÁC)
            val validationError = validateChessPosition(standardMatrix)
            if (validationError != null) {
                mainHandler.post {
                    tvStatus.text = "⚠️ Đọc chưa chắc chắn ($validationError) • Chờ khung ổn định"
                }
                return
            }

            // 5. Cập nhật lịch sử di chuyển Vua/Xe để tính quyền nhập thành chuẩn xác
            updateCastlingRights(standardMatrix)

            // 6. XÁC ĐỊNH LƯỢT ĐI THÔNG MINH (TỰ ĐỘNG BIẾT ĐẾN LƯỢT AI)
            val myColorChar = if (chessOverlayView.isWhiteBottom) 'w' else 'b'
            val detectedTurn = determineSideToMove(standardMatrix, cells, myColorChar)
            val activeTurn = forceTurnSide ?: detectedTurn

            previousValidMatrix = Array(8) { r -> standardMatrix[r].clone() }

            if (activeTurn != myColorChar) {
                lastAnalyzedBoardFen = boardFen
                mainHandler.post {
                    chessOverlayView.setMove(null)
                    tvStatus.text = "⏳ Đang là lượt đối thủ suy nghĩ..."
                }
                return
            }

            val fullFenKey = "$boardFen $activeTurn"
            if (fullFenKey == lastAnalyzedBoardFen) return

            val castlingStr = getValidCastlingString(standardMatrix)
            val fullFen = "$boardFen $activeTurn $castlingStr - 0 1"

            // Kiểm tra Cache trước khi gọi mạng
            val cachedMove = moveCache[fullFen]
            if (cachedMove != null) {
                lastAnalyzedBoardFen = fullFenKey
                mainHandler.post {
                    chessOverlayView.setMove(cachedMove)
                    tvStatus.text = "✅ Đi ngay: ${formatMoveText(cachedMove)} (Cache)"
                }
                return
            }

            // Kiểm tra Backoff mạng
            if (SystemClock.uptimeMillis() < nextAllowedApiCallTime) {
                mainHandler.post { tvStatus.text = "⏳ Đang chờ hồi API..." }
                return
            }

            val bestMove = queryStockfishOnlineWithBackoff(fullFen)
            mainHandler.post {
                if (bestMove != null && bestMove.length >= 4) {
                    moveCache[fullFen] = bestMove
                    lastAnalyzedBoardFen = fullFenKey
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

    // PHÂN TÍCH Ô CỜ BẰNG TRỪ NỀN (BACKGROUND SUBTRACTION) - LOẠI BỎ HOÀN TOÀN NHIỄU CHẤM GỢI Ý & CHỮ A-H/1-8
    private fun analyzeSingleCell(cellBmp: Bitmap, refBgColor: Int): CellAnalysis {
        val scaled = Bitmap.createScaledBitmap(cellBmp, 32, 32, true)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        scaled.recycle()

        // Lấy màu nền thực tế của chính ô này ở viền trong (x: 4..27, y: 4..6) để tránh chữ tọa độ ở sát mép (0..3)
        val localBgSamples = mutableListOf<Int>()
        for (x in 5..26 step 3) {
            localBgSamples.add(pixels[4 * 32 + x])
            localBgSamples.add(pixels[27 * 32 + x])
        }
        val localBg = medianColor(localBgSamples)
        val bgR = Color.red(localBg); val bgG = Color.green(localBg); val bgB = Color.blue(localBg)
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        // Kiểm tra xem ô này có đang được tô sáng (Highlight nước vừa đi) so với màu bàn cờ gốc không
        val refDiff = colorDist(localBg, refBgColor)
        val isHighlighted = refDiff > 28

        // Quét vùng trung tâm quân cờ (x: 6..25, y: 5..27), bỏ qua mép 0..4 (chứa chữ a-h, 1-8)
        val silhouette = FloatArray(16 * 16)
        var fgCount = 0
        var topFgCount = 0
        var bottomFgCount = 0
        var highestY = 32
        var lowestY = 0
        var topMinX = 32; var topMaxX = 0
        var midMinX = 32; var midMaxX = 0
        var leftMass = 0f; var rightMass = 0f

        val fgLums = mutableListOf<Int>()

        for (y in 5..27) {
            for (x in 6..25) {
                val p = pixels[y * 32 + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                val distToBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                val pLeft = pixels[y * 32 + x - 1]
                val pRight = pixels[y * 32 + x + 1]
                val pUp = pixels[(y - 1) * 32 + x]
                val pDown = pixels[(y + 1) * 32 + x]
                val grad = abs(Color.green(pRight) - Color.green(pLeft)) + abs(Color.green(pDown) - Color.green(pUp))

                if (distToBg > 42 || grad > 34) {
                    fgCount++
                    if (y < 14) topFgCount++
                    if (y > 19) bottomFgCount++
                    if (y < highestY) highestY = y
                    if (y > lowestY) lowestY = y

                    if (y in 6..12) {
                        if (x < topMinX) topMinX = x
                        if (x > topMaxX) topMaxX = x
                    }
                    if (y in 13..19) {
                        if (x < midMinX) midMinX = x
                        if (x > midMaxX) midMaxX = x
                    }
                    if (y in 7..17) {
                        if (x < 16) leftMass += 1f else rightMass += 1f
                    }
                    if (x in 11..20 && y in 12..23) {
                        fgLums.add(lum)
                    }
                    val sx = ((x - 6) * 16 / 20).coerceIn(0, 15)
                    val sy = ((y - 5) * 16 / 23).coerceIn(0, 15)
                    silhouette[sy * 16 + sx] += 1f
                }
            }
        }

        // LOẠI BỎ CHẤM TRÒN GỢI Ý NƯỚC ĐI (Chấm gợi ý chỉ nằm lơ lửng ở giữa ô, không có đế ở đáy y > 20 và không có đầu ở y < 12)
        val verticalSpan = if (lowestY >= highestY) lowestY - highestY else 0
        val isRealPiece = fgCount >= 52 && bottomFgCount >= 14 && verticalSpan >= 11

        // Chuẩn hóa vector silhouette 16x16 (Zero-Mean Unit-Variance) để khớp mẫu ZNCC chính xác
        normalizeTemplate(silhouette)

        // Phân định Quân Trắng hay Quân Đen dựa trên độ sáng trung vị vùng lõi bụng quân cờ
        fgLums.sort()
        val medianPieceLum = if (fgLums.isNotEmpty()) fgLums[fgLums.size / 2] else bgLum
        val isWhite = medianPieceLum >= 152

        val heightRatio = verticalSpan / 23f
        val topWidthRatio = if (topMaxX >= topMinX) (topMaxX - topMinX + 1) / 20f else 0f
        val midWidthRatio = if (midMaxX >= midMinX) (midMaxX - midMinX + 1) / 20f else 0f
        val headAsymmetry = abs(leftMass - rightMass) / (leftMass + rightMass + 1f)

        return CellAnalysis(isRealPiece, isWhite, isHighlighted, silhouette, heightRatio, topWidthRatio, midWidthRatio, headAsymmetry)
    }

    private fun checkAndLearnInitialTemplates(cells: Array<Array<CellAnalysis>>) {
        var isInitialBoard = true
        for (c in 0..7) {
            if (!cells[0][c].isOccupied || !cells[1][c].isOccupied || !cells[6][c].isOccupied || !cells[7][c].isOccupied) {
                isInitialBoard = false; break
            }
            for (r in 2..5) if (cells[r][c].isOccupied) { isInitialBoard = false; break }
        }
        if (!isInitialBoard) return

        val whiteAtBottom = cells[7][4].isWhitePiece
        mainHandler.post {
            chessOverlayView.isWhiteBottom = whiteAtBottom
            btnSide.text = if (whiteAtBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
        }

        val order = if (whiteAtBottom) charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
                    else charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R')
        for (c in 0..7) {
            pieceTemplates[order[c]] = cells[7][c].silhouette16x16.clone()
        }
        pieceTemplates['P'] = cells[6][4].silhouette16x16.clone()

        // Reset lịch sử nhập thành vì đây là đầu ván mới 100%
        hasFullGameHistory = true
        wKingMoved = false; wRookAMoved = false; wRookHMoved = false
        bKingMoved = false; bRookAMoved = false; bRookHMoved = false
        previousValidMatrix = null
    }

    private fun recognizePieceType(r: Int, c: Int, cell: CellAnalysis): Char {
        // 1. So khớp tương quan chéo ZNCC với Template 16x16
        var bestType = 'P'
        var bestCorr = -999f
        for ((type, tpl) in pieceTemplates) {
            if (type == 'P' && (r == 0 || r == 7)) continue
            var corr = 0f
            for (i in 0 until 256) corr += cell.silhouette16x16[i] * tpl[i]
            if (corr > bestCorr) {
                bestCorr = corr
                bestType = type
            }
        }
        if (bestCorr > 0.62f) return bestType

        // 2. Phân loại hình thái học chuẩn xác khi chưa có Template đầu ván
        if (cell.headAsymmetry > 0.22f && cell.heightRatio > 0.62f) return 'N' // Quân Mã lệch trục đầu
        if (r != 0 && r != 7 && cell.heightRatio < 0.68f && cell.topWidthRatio < 0.45f) return 'P'
        if (cell.heightRatio >= 0.78f && cell.topWidthRatio >= 0.65f) return 'Q'
        if (cell.heightRatio >= 0.80f && cell.topWidthRatio in 0.40f..0.64f) return 'K'
        if (cell.topWidthRatio >= 0.55f && abs(cell.topWidthRatio - cell.midWidthRatio) < 0.14f) return 'R'
        if (cell.heightRatio >= 0.72f && cell.topWidthRatio < cell.midWidthRatio) return 'B'
        return if (r == 0 || r == 7) 'R' else 'P'
    }

    // XÁC ĐỊNH LƯỢT ĐI THÔNG MINH QUA Ô TÔ SÁNG (HIGHLIGHT) & BIẾN THIÊN MA TRẬN
    private fun determineSideToMove(currentMatrix: Array<CharArray>, cells: Array<Array<CellAnalysis>>, myColor: Char): Char {
        val oppColor = if (myColor == 'w') 'b' else 'w'

        // Kiểm tra xem có phải bàn cờ khai cuộc ban đầu không (Chưa ai đi nước nào -> Luôn là lượt Trắng 'w')
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        if (buildFenBoardString(currentMatrix) == startFen) return 'w'

        // Kiểm tra các ô đang được tô sáng (Last Move Highlight trên Chess.com / Lichess)
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
            val movedPiece = highlightedPieces[0]
            val movedSide = if (movedPiece.isUpperCase()) 'w' else 'b'
            // Nếu quân nằm trên ô tô sáng là quân Trắng -> Trắng vừa đi xong -> Bây giờ là lượt Đen ('b')
            return if (movedSide == 'w') 'b' else 'w'
        }

        // So sánh với ma trận hợp lệ ngay trước đó
        val prev = previousValidMatrix
        if (prev != null) {
            var whiteChanged = 0
            var blackChanged = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    if (prev[r][c] != currentMatrix[r][c]) {
                        if (currentMatrix[r][c] != '1') {
                            if (currentMatrix[r][c].isUpperCase()) whiteChanged++ else blackChanged++
                        }
                    }
                }
            }
            if (whiteChanged > 0 && blackChanged == 0) return 'b'
            if (blackChanged > 0 && whiteChanged == 0) return 'w'
        }

        return myColor
    }

    // KIỂM TRA NGHIÊM NGẶT LUẬT CỜ VUA (KHÔNG BAO GIỜ BẺ DỮ LIỆU SAI SỰ THẬT)
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
            // Tự động tinh chỉnh duy nhất trường hợp Vua bị nhận nhầm thành Hậu cùng màu trên bàn cờ
            if (tryResolveKingQueenAmbiguity(matrix)) {
                return validateChessPositionAfterResolve(matrix)
            }
            return "Số Vua không hợp lệ (K=$wKings, k=$bKings)"
        }
        if (wPieces > 16 || bPieces > 16) return "Quá 16 quân một phe"
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) return "2 Vua đứng sát nhau"

        return null
    }

    private fun tryResolveKingQueenAmbiguity(matrix: Array<CharArray>): Boolean {
        fixSingleSideKingQueen(matrix, isWhite = true)
        fixSingleSideKingQueen(matrix, isWhite = false)
        return true
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
            // Giữ lại quân ở đúng vị trí Vua hoặc gần hàng cuối nhất làm Vua, còn lại là Hậu
            kings.sortBy { (r, c) -> abs(r - homeR) * 10 + abs(c - 4) }
            for (i in 1 until kings.size) {
                matrix[kings[i].first][kings[i].second] = qChar
            }
        } else if (kings.isEmpty() && queens.isNotEmpty()) {
            // Nếu Vua bị nhìn nhầm thành Hậu, chọn quân gần vị trí Vua nhất trong các quân Hậu hiện có (Không bịa vào ô trống!)
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
        // Nếu bật app ở giữa ván cờ (không theo dõi từ đầu ván), trả về '-' để không bao giờ gợi ý nhập thành phạm luật
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

    private fun initDefaultSyntheticTemplates() {
        // Khởi tạo khung mẫu hình học cơ sở phòng trường hợp bật app giữa ván
        val types = charArrayOf('P', 'R', 'N', 'B', 'Q', 'K')
        for (t in types) {
            val tpl = FloatArray(256)
            for (y in 0..15) {
                for (x in 0..15) {
                    val insideBase = y in 12..15 && x in 3..12
                    val insideBody = when (t) {
                        'P' -> y in 5..11 && x in 5..10
                        'R' -> y in 3..11 && x in 4..11
                        'N' -> y in 3..11 && x in (if (y < 8) 3..11 else 5..11)
                        'B' -> y in 3..11 && x in (6 - y / 4)..(9 + y / 4)
                        'Q' -> y in 2..11 && x in 3..12
                        'K' -> y in 1..11 && x in 4..11
                        else -> false
                    }
                    if (insideBase || insideBody) tpl[y * 16 + x] = 1f
                }
            }
            normalizeTemplate(tpl)
            pieceTemplates[t] = tpl
        }
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
            .setContentTitle("True Vision v6.0")
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
