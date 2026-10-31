package com.example.chessvision

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var overlayParams: WindowManager.LayoutParams
    
    private lateinit var mainControlPanel: LinearLayout
    private lateinit var calibControlPanel: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var tvStatus: TextView
    private lateinit var tvFenDebug: TextView
    private lateinit var btnSide: Button
    private lateinit var btnTurn: Button
    
    private var pieceEditorPopup: LinearLayout? = null
    private lateinit var prefs: SharedPreferences

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
    private val apiRacePool = Executors.newFixedThreadPool(2)

    @Volatile private var isAutoMode = false
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var waitingForCleanFrame = false
    @Volatile private var cleanFrameMinTimestamp = 0L

    private val frameCacheLock = Any()
    private var lastCleanFullBitmap: Bitmap? = null

    @Volatile private var detectedBoardRect: RectF? = null
    @Volatile private var validMatrix: Array<CharArray>? = null
    @Volatile private var lastDetectedTurn: Char = 'w'
    @Volatile private var previousValidMatrix: Array<CharArray>? = null
    @Volatile private var lastEnPassantTarget: String = "-"
    @Volatile private var halfmoveClock = 0
    @Volatile private var fullmoveNumber = 1
    @Volatile private var hasFullGameHistory = false
    @Volatile private var wKingMoved = false
    @Volatile private var wRookAMoved = false
    @Volatile private var wRookHMoved = false
    @Volatile private var bKingMoved = false
    @Volatile private var bRookAMoved = false
    @Volatile private var bRookHMoved = false

    private val stateGeneration = AtomicLong(0L)
    @Volatile private var latestValidStateKey = ""
    @Volatile private var lastCompletedStateKey = ""
    @Volatile private var forceTurnSide: Char? = null

    private val moveCache = ConcurrentHashMap<String, String>()

    data class CellAnalysis(
        val isOccupied: Boolean,
        val avgPieceLum: Int,
        val isHighlighted: Boolean,
        val centerOfMassX: Float,
        val centerOfMassY: Float,
        val mass: Int,
        val highestY: Int,
        val sliceWidths: IntArray,
        val headAsymmetry: Float
    )

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode) return
            requestCleanCapture(manualTrigger = false)
            mainHandler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("VisionProPrefs", Context.MODE_PRIVATE)
        val thread = HandlerThread("ImageCaptureThread").apply { start() }
        bgThread = thread
        bgHandler = Handler(thread.looper)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()

        setupBoardOverlay()
        setupUI()
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
                cleanupMediaProjection()
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpManager.getMediaProjection(resultCode, data)
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        virtualDisplay?.release(); virtualDisplay = null
                        imageReader?.close(); imageReader = null
                        mediaProjection = null
                    }
                }
                mp.registerCallback(cb, mainHandler)
                mediaProjection = mp
                projectionCallback = cb

                recreateVirtualDisplay()
                mainHandler.postDelayed({ requestCleanCapture(manualTrigger = true) }, 450)
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
        val savedTop = prefs.getFloat("BOARD_TOP", -1f)
        val savedLeft = prefs.getFloat("BOARD_LEFT", 0f)
        val savedSize = prefs.getFloat("BOARD_SIZE", -1f)
        
        if (savedTop > 0 && savedSize > 0) {
            detectedBoardRect = RectF(savedLeft, savedTop, savedLeft + savedSize, savedTop + savedSize)
            chessOverlayView.detectedBoardRect = detectedBoardRect
        } else {
            val minDim = min(screenWidth, screenHeight).toFloat()
            val defTop = (screenHeight - minDim) / 2f
            detectedBoardRect = RectF(0f, defTop, minDim, defTop + minDim)
            chessOverlayView.detectedBoardRect = detectedBoardRect
        }
        
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(chessOverlayView, overlayParams)
        
        chessOverlayView.onSquareClicked = { r, c -> showPieceEditorPopup(r, c) }
    }
    
    private fun setOverlayTouchable(touchable: Boolean) {
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        overlayParams.flags = flags
        windowManager.updateViewLayout(chessOverlayView, overlayParams)
    }

    private fun getBtnBg(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun createButton(label: String, colorStr: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 12.5f; setTextColor(Color.WHITE); background = getBtnBg(colorStr)
            isAllCaps = false; setPadding(32, 22, 32, 22)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(10, 8, 10, 8); weight = 1f }
            setOnClickListener { onClick(this) }
        }
    }

    private fun setupUI() {
        mainControlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(245, 25, 25, 30)); setStroke(2, Color.parseColor("#444444")) }
            setPadding(24, 20, 24, 20)
        }

        tvStatus = TextView(this).apply {
            text = "Chess Pro v7.3.2 • Đã fix Editor Function"
            setTextColor(Color.parseColor("#00E676")); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
        }
        tvFenDebug = TextView(this).apply {
            text = "FEN: Chưa quét"
            setTextColor(Color.parseColor("#B0BEC5")); textSize = 9.5f; gravity = Gravity.CENTER; maxLines = 1; setPadding(0, 0, 0, 16)
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnSide = createButton("⚪ Phe: Trắng", "#2980b9") {
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
            btnSide.text = if (chessOverlayView.isWhiteBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
            resetState()
            requestCleanCapture(manualTrigger = true)
        }
        btnTurn = createButton("🔄 Lượt: Auto", "#2980b9") {
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
            resetState()
            requestCleanCapture(manualTrigger = true)
        }
        row1.addView(btnSide); row1.addView(btnTurn)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnCalib = createButton("🎯 Chỉnh Khung", "#8e44ad") {
            mainControlPanel.visibility = View.GONE
            calibControlPanel.visibility = View.VISIBLE
            chessOverlayView.isCalibrationMode = true
            chessOverlayView.invalidate()
        }
        val btnEdit = createButton("👁️ Sửa Quân", "#d35400") {
            if (validMatrix == null) {
                Toast.makeText(this, "Hãy Quét bàn cờ 1 lần trước khi sửa!", Toast.LENGTH_SHORT).show()
                return@createButton
            }
            isAutoMode = false
            chessOverlayView.isEditorMode = true
            chessOverlayView.currentMatrix = validMatrix
            chessOverlayView.invalidate()
            setOverlayTouchable(true)
            
            mainControlPanel.visibility = View.GONE
            tvStatus.text = "CHẾ ĐỘ SỬA QUÂN (Chạm vào ô cờ để sửa)"
            
            val exitEditBtn = createButton("✔️ LƯU & TÍNH NƯỚC", "#27ae60") { btn ->
                chessOverlayView.isEditorMode = false
                setOverlayTouchable(false)
                (btn.parent as LinearLayout).removeView(btn)
                mainControlPanel.visibility = View.VISIBLE
                forceEvaluateMatrix() // Hàm bị thiếu ở v7.3.1 đã được phục hồi!
            }
            (mainControlPanel.parent as LinearLayout).addView(exitEditBtn, 1)
        }
        row2.addView(btnCalib); row2.addView(btnEdit)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnAuto = createButton("▶ Auto", "#27ae60") { btn ->
            isAutoMode = !isAutoMode
            mainHandler.removeCallbacks(autoLoopRunnable)
            if (isAutoMode) {
                btn.text = "⏸ Dừng"
                resetState()
                mainHandler.post(autoLoopRunnable)
            } else {
                btn.text = "▶ Auto"
                tvStatus.text = "Đã tạm dừng."
            }
        }
        val btnScan = createButton("⚡ Quét", "#27ae60") { resetState(); requestCleanCapture(true) }
        val btnClose = createButton("✕", "#c0392b") { stopSelf() }
        btnClose.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(10,8,10,8) }
        row3.addView(btnAuto); row3.addView(btnScan); row3.addView(btnClose)

        mainControlPanel.addView(tvStatus); mainControlPanel.addView(tvFenDebug)
        mainControlPanel.addView(row1); mainControlPanel.addView(row2); mainControlPanel.addView(row3)

        calibControlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(250, 40, 20, 60)) }
            setPadding(24, 20, 24, 20)
            visibility = View.GONE
        }
        val tvCalib = TextView(this).apply { text = "Dùng các nút dưới để khớp Lưới Đỏ"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0,0,0,16) }
        val cRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cRow1.addView(createButton("▲ Lên", "#555555") { moveBoard(0f, -6f) })
        cRow1.addView(createButton("▼ Xuống", "#555555") { moveBoard(0f, 6f) })
        val cRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cRow2.addView(createButton("◀ Trái", "#555555") { moveBoard(-6f, 0f) })
        cRow2.addView(createButton("▶ Phải", "#555555") { moveBoard(6f, 0f) })
        val cRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cRow3.addView(createButton("➕ To ra", "#34495e") { scaleBoard(8f) })
        cRow3.addView(createButton("➖ Nhỏ lại", "#34495e") { scaleBoard(-8f) })
        val btnSaveCalib = createButton("✔️ LƯU KHUNG", "#27ae60") {
            calibControlPanel.visibility = View.GONE
            mainControlPanel.visibility = View.VISIBLE
            chessOverlayView.isCalibrationMode = false
            chessOverlayView.invalidate()
            detectedBoardRect?.let {
                prefs.edit().putFloat("BOARD_LEFT", it.left).putFloat("BOARD_TOP", it.top).putFloat("BOARD_SIZE", it.width()).apply()
            }
            resetState()
            requestCleanCapture(true)
        }
        calibControlPanel.addView(tvCalib); calibControlPanel.addView(cRow1); calibControlPanel.addView(cRow2); calibControlPanel.addView(cRow3); calibControlPanel.addView(btnSaveCalib)

        val rootContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(mainControlPanel); addView(calibControlPanel) }

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        rootContainer.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = controlParams.x; iy = controlParams.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    controlParams.x = ix + (e.rawX - tx).toInt()
                    controlParams.y = iy + (e.rawY - ty).toInt()
                    windowManager.updateViewLayout(rootContainer, controlParams)
                    true
                }
                else -> false
            }
        }
        windowManager.addView(rootContainer, controlParams)
    }
    
    private fun moveBoard(dx: Float, dy: Float) {
        val r = detectedBoardRect ?: return
        detectedBoardRect = RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
        chessOverlayView.detectedBoardRect = detectedBoardRect
        chessOverlayView.invalidate()
    }
    
    private fun scaleBoard(ds: Float) {
        val r = detectedBoardRect ?: return
        detectedBoardRect = RectF(r.left - ds/2, r.top - ds/2, r.right + ds/2, r.bottom + ds/2)
        chessOverlayView.detectedBoardRect = detectedBoardRect
        chessOverlayView.invalidate()
    }

    private fun resetState() {
        lastCompletedStateKey = ""
        chessOverlayView.setMove(null)
    }

    private fun showPieceEditorPopup(r: Int, c: Int) {
        if (pieceEditorPopup != null) {
            windowManager.removeView(pieceEditorPopup)
            pieceEditorPopup = null
        }

        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(245, 30, 30, 30)); setStroke(4, Color.parseColor("#f39c12")) }
            setPadding(32, 32, 32, 32)
        }

        val tvTitle = TextView(this).apply {
            val colName = (if (chessOverlayView.isWhiteBottom) c else 7 - c) + 'a'.code
            val rowName = if (chessOverlayView.isWhiteBottom) 8 - r else r + 1
            text = "Sửa ô ${colName.toChar()}$rowName"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        }
        popup.addView(tvTitle)

        fun createPieceBtn(label: String, ch: Char, isWhite: Boolean): Button {
            return Button(this).apply {
                text = label; textSize = 14f; isAllCaps = false
                setTextColor(if (isWhite) Color.BLACK else Color.WHITE)
                background = getBtnBg(if (isWhite) "#ecf0f1" else "#2c3e50")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f; setMargins(4,4,4,4) }
                setOnClickListener {
                    validMatrix?.get(r)?.set(c, ch)
                    chessOverlayView.currentMatrix = validMatrix
                    chessOverlayView.invalidate()
                    windowManager.removeView(popup)
                    pieceEditorPopup = null
                }
            }
        }

        val rowW1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowW1.addView(createPieceBtn("TỐT ⚪", 'P', true)); rowW1.addView(createPieceBtn("MÃ ⚪", 'N', true)); rowW1.addView(createPieceBtn("TƯỢNG ⚪", 'B', true))
        val rowW2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowW2.addView(createPieceBtn("XE ⚪", 'R', true)); rowW2.addView(createPieceBtn("HẬU ⚪", 'Q', true)); rowW2.addView(createPieceBtn("VUA ⚪", 'K', true))

        val rowB1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB1.addView(createPieceBtn("TỐT ⚫", 'p', false)); rowB1.addView(createPieceBtn("MÃ ⚫", 'n', false)); rowB1.addView(createPieceBtn("TƯỢNG ⚫", 'b', false))
        val rowB2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB2.addView(createPieceBtn("XE ⚫", 'r', false)); rowB2.addView(createPieceBtn("HẬU ⚫", 'q', false)); rowB2.addView(createPieceBtn("VUA ⚫", 'k', false))

        val btnClear = createButton("❌ XÓA TRỐNG", "#c0392b") {
            validMatrix?.get(r)?.set(c, '1')
            chessOverlayView.currentMatrix = validMatrix
            chessOverlayView.invalidate()
            windowManager.removeView(popup)
            pieceEditorPopup = null
        }
        btnClear.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }

        popup.addView(rowW1); popup.addView(rowW2)
        val space = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }
        popup.addView(space); popup.addView(rowB1); popup.addView(rowB2); popup.addView(btnClear)

        val popupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(popup, popupParams)
        pieceEditorPopup = popup
    }

    private fun extractExactScreenBitmap(img: android.media.Image): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * screenWidth
        val strideWidth = screenWidth + rowPadding / pixelStride
        val rawBmp = Bitmap.createBitmap(strideWidth, screenHeight, Bitmap.Config.ARGB_8888)
        rawBmp.copyPixelsFromBuffer(buffer)
        return if (strideWidth > screenWidth) {
            val cropped = Bitmap.createBitmap(rawBmp, 0, 0, screenWidth, screenHeight)
            rawBmp.recycle(); cropped
        } else rawBmp
    }

    private fun recreateVirtualDisplay() {
        try { virtualDisplay?.release(); imageReader?.close() } catch (_: Exception) {}
        val mp = mediaProjection ?: return
        val handler = bgHandler ?: return

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val exactBmp = extractExactScreenBitmap(img)
                img.close()
                if (!chessOverlayView.hasActiveArrow() || (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp)) {
                    cacheCleanBitmap(exactBmp)
                }
                if (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp) {
                    if (isProcessing.compareAndSet(false, true)) {
                        waitingForCleanFrame = false
                        mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                        workerExecutor.execute { processCapturedBitmap(exactBmp) }
                        return@setOnImageAvailableListener
                    }
                }
                exactBmp.recycle()
            } catch (e: Exception) {
                try { img.close() } catch (_: Exception) {}
                if (waitingForCleanFrame) {
                    waitingForCleanFrame = false
                    mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                    isProcessing.set(false)
                }
            }
        }, handler)
        virtualDisplay = mp.createVirtualDisplay("ChessProCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
    }

    private fun cacheCleanBitmap(bmp: Bitmap) {
        synchronized(frameCacheLock) {
            try { lastCleanFullBitmap?.recycle(); lastCleanFullBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) {}
        }
    }

    private fun obtainCachedCleanBitmapCopy(): Bitmap? {
        synchronized(frameCacheLock) {
            val cached = lastCleanFullBitmap ?: return null
            if (cached.isRecycled) return null
            return try { cached.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
        }
    }

    private fun requestCleanCapture(manualTrigger: Boolean) {
        if (isProcessing.get() || waitingForCleanFrame || chessOverlayView.isEditorMode || chessOverlayView.isCalibrationMode) return
        mainHandler.post {
            val needHideArrow = chessOverlayView.hasActiveArrow()
            if (!needHideArrow) {
                val img = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null && isProcessing.compareAndSet(false, true)) {
                    try {
                        val exactBmp = extractExactScreenBitmap(img)
                        img.close()
                        cacheCleanBitmap(exactBmp)
                        workerExecutor.execute { processCapturedBitmap(exactBmp) }
                        return@post
                    } catch (e: Exception) {
                        try { img.close() } catch (_: Exception) {}
                        isProcessing.set(false)
                    }
                } else img?.close()
            }

            chessOverlayView.visibility = View.INVISIBLE
            try { imageReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
            Choreographer.getInstance().postFrameCallback {
                cleanFrameMinTimestamp = SystemClock.uptimeMillis() + 20L
                waitingForCleanFrame = true
                mainControlPanel.alpha = if (mainControlPanel.alpha < 0.99f) 1.0f else 0.985f
                mainControlPanel.invalidate()

                bgHandler?.postDelayed({
                    if (waitingForCleanFrame && !isProcessing.get()) {
                        val img = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
                        if (img != null && isProcessing.compareAndSet(false, true)) {
                            waitingForCleanFrame = false
                            try {
                                val exactBmp = extractExactScreenBitmap(img)
                                img.close()
                                cacheCleanBitmap(exactBmp)
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                workerExecutor.execute { processCapturedBitmap(exactBmp) }
                            } catch (e: Exception) {
                                try { img.close() } catch (_: Exception) {}
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                isProcessing.set(false)
                            }
                        } else {
                            img?.close()
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
                }, 85L)
            }
        }
    }

    // HÀM ÉP TÍNH NƯỚC KHI BẤM NÚT "LƯU & TÍNH NƯỚC"
    private fun forceEvaluateMatrix() {
        val matrix = validMatrix ?: return
        val boardFen = buildFenBoardString(matrix)
        mainHandler.post { tvFenDebug.text = "FEN (Sửa tay): $boardFen" }

        val myColorChar = if (chessOverlayView.isWhiteBottom) 'w' else 'b'
        val activeTurn = forceTurnSide ?: lastDetectedTurn
        
        val stateKey = "$boardFen|$activeTurn|EDITED"
        latestValidStateKey = stateKey
        val reqGen = stateGeneration.incrementAndGet()
        
        val fullFen = "$boardFen $activeTurn - - 0 1"

        mainHandler.post { tvStatus.text = "⚡ Đang tính nước theo bàn cờ sửa tay..." }
        workerExecutor.execute {
            var bestMove = raceBothStockfishServers(fullFen)
            if (bestMove == null) {
                bestMove = raceBothStockfishServers("$boardFen $myColorChar - - 0 1")
            }
            if (latestValidStateKey != stateKey || stateGeneration.get() != reqGen) return@execute
            mainHandler.post {
                if (bestMove != null && bestMove.length >= 4) {
                    moveCache[fullFen] = bestMove
                    lastCompletedStateKey = stateKey
                    chessOverlayView.setMove(bestMove)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(bestMove)}"
                } else {
                    tvStatus.text = "⏳ Lỗi API, FEN sửa tay chưa hợp lệ..."
                }
            }
        }
    }

    private fun processCapturedBitmap(fullBmp: Bitmap) {
        try {
            var boardRect = detectedBoardRect
            if (boardRect == null) {
                mainHandler.post { tvStatus.text = "⚠️ Hãy chỉnh khung bàn cờ trước" }
                return
            }

            val bLeft = boardRect.left.toInt().coerceIn(0, fullBmp.width - 64)
            val bTop = boardRect.top.toInt().coerceIn(0, fullBmp.height - 64)
            val bSize = boardRect.width().toInt().coerceIn(64, min(fullBmp.width - bLeft, fullBmp.height - bTop))

            val croppedBoard = Bitmap.createBitmap(fullBmp, bLeft, bTop, bSize, bSize)
            val board256 = Bitmap.createScaledBitmap(croppedBoard, 256, 256, true)
            croppedBoard.recycle()

            val boardPixels = IntArray(256 * 256)
            board256.getPixels(boardPixels, 0, 256, 0, 0, 256, 256)
            board256.recycle()

            val cells = Array(8) { r -> Array(8) { c -> analyzeCellVariance(boardPixels, r, c) } }
            
            val lums = mutableListOf<Int>()
            for (r in 0..7) for (c in 0..7) if (cells[r][c].isOccupied) lums.add(cells[r][c].avgPieceLum)
            var threshold = 145
            if (lums.size >= 2) {
                lums.sort()
                threshold = (lums[lums.size/4] + lums[(lums.size*3)/4]) / 2
            }
            
            val rawMatrix = Array(8) { CharArray(8) { '1' } }
            for (r in 0..7) {
                for (c in 0..7) {
                    val cell = cells[r][c]
                    if (!cell.isOccupied) continue
                    val isWhite = cell.avgPieceLum >= threshold
                    val pieceType = classifyPiece(r, cell)
                    rawMatrix[r][c] = if (isWhite) pieceType.uppercaseChar() else pieceType.lowercaseChar()
                }
            }

            val standardMatrix = if (chessOverlayView.isWhiteBottom) rawMatrix else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }
            validMatrix = standardMatrix

            val validationError = validateChessPositionStrict(standardMatrix)
            if (validationError != null) {
                mainHandler.post { tvStatus.text = "⚠️ Rác ($validationError) • Đang chỉnh lại khung" }
                return
            }

            val boardFen = buildFenBoardString(standardMatrix)
            mainHandler.post { tvFenDebug.text = "FEN: $boardFen" }

            val myColorChar = if (chessOverlayView.isWhiteBottom) 'w' else 'b'
            val activeTurn = forceTurnSide ?: determineSideToMoveAndUpdateHistory(standardMatrix, myColorChar)

            val stateKey = "$boardFen|$activeTurn"
            latestValidStateKey = stateKey
            val reqGen = stateGeneration.incrementAndGet()

            if (stateKey == lastCompletedStateKey && chessOverlayView.hasActiveArrow()) return

            val fullFen = "$boardFen $activeTurn - - 0 1"
            val cachedMove = moveCache[fullFen]
            if (cachedMove != null) {
                lastCompletedStateKey = stateKey
                mainHandler.post { chessOverlayView.setMove(cachedMove); tvStatus.text = "⚡ Đi: ${formatMoveText(cachedMove)}" }
                return
            }

            mainHandler.post { tvStatus.text = "⚡ Đang tính..." }
            var bestMove = raceBothStockfishServers(fullFen)
            if (bestMove == null) {
                bestMove = raceBothStockfishServers("$boardFen $myColorChar - - 0 1")
            }

            if (latestValidStateKey != stateKey || stateGeneration.get() != reqGen) return
            mainHandler.post {
                if (bestMove != null && bestMove.length >= 4) {
                    moveCache[fullFen] = bestMove
                    lastCompletedStateKey = stateKey
                    chessOverlayView.setMove(bestMove)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(bestMove)}"
                } else {
                    tvStatus.text = "⏳ Lỗi API, thử lại..."
                }
            }
        } catch (e: Exception) {
            mainHandler.post { tvStatus.text = "Lỗi: ${e.message}" }
        } finally {
            fullBmp.recycle(); isProcessing.set(false)
        }
    }

    private fun analyzeCellVariance(boardPixels: IntArray, r: Int, c: Int): CellAnalysis {
        val baseX = c * 32; val baseY = r * 32
        var sumLum = 0L; var sumSqLum = 0L; var pxCount = 0

        for (y in 8..23) {
            val rowOffset = (baseY + y) * 256 + baseX
            for (x in 8..23) {
                val p = boardPixels[rowOffset + x]
                val lum = (Color.red(p)*299 + Color.green(p)*587 + Color.blue(p)*114)/1000
                sumLum += lum; sumSqLum += (lum * lum).toLong(); pxCount++
            }
        }
        val meanLum = (sumLum / pxCount).toInt()
        val meanDouble = sumLum.toDouble() / pxCount
        val variance = (sumSqLum.toDouble() / pxCount) - (meanDouble * meanDouble)
        val isOccupied = variance > 120.0

        if (!isOccupied) return CellAnalysis(false, meanLum, false, 16f, 0f, 0, 32, IntArray(5), 0f)

        val bgSamples = ArrayList<Int>(12)
        for (x in 6..26 step 4) {
            bgSamples.add(boardPixels[(baseY + 2) * 256 + baseX + x])
            bgSamples.add(boardPixels[(baseY + 29) * 256 + baseX + x])
        }
        val bgLum = if (bgSamples.isEmpty()) 128 else {
            bgSamples.map { color -> (Color.red(color)*299 + Color.green(color)*587 + Color.blue(color)*114)/1000 }.sorted()[bgSamples.size/2]
        }

        var sumX = 0f; var sumY = 0f; var mass = 0; var highestY = 32
        val solidMask = BooleanArray(32 * 32)
        for (y in 4..27) {
            val rowOffset = (baseY + y) * 256 + baseX
            for (x in 4..27) {
                val p = boardPixels[rowOffset + x]
                val lum = (Color.red(p)*299 + Color.green(p)*587 + Color.blue(p)*114)/1000
                if (abs(lum - bgLum) > 20) { 
                    sumX += x; sumY += y; mass++
                    if (y < highestY) highestY = y 
                    solidMask[y * 32 + x] = true
                }
            }
        }
        
        val sliceRanges = arrayOf(4..7, 8..11, 12..15, 16..19, 20..25)
        val sliceWidths = IntArray(5)
        for (s in 0..4) {
            var sumW = 0; var rows = 0
            for (y in sliceRanges[s]) {
                var minX = 32; var maxX = 0
                for (x in 4..27) {
                    if (solidMask[y * 32 + x]) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                    }
                }
                if (maxX >= minX) sumW += (maxX - minX + 1)
                rows++
            }
            sliceWidths[s] = sumW / rows
        }
        
        val cx = if (mass > 0) sumX / mass else 16f
        val asymX = abs(cx - 16f)
        
        var coreLumSum = 0; var corePx = 0
        for(y in 14..22) {
            val rowOffset = (baseY + y) * 256 + baseX
            for (x in 12..20) {
                if (solidMask[y * 32 + x]) {
                    val p = boardPixels[rowOffset + x]
                    coreLumSum += (Color.red(p)*299 + Color.green(p)*587 + Color.blue(p)*114)/1000
                    corePx++
                }
            }
        }
        val finalPieceLum = if (corePx > 0) coreLumSum / corePx else meanLum

        return CellAnalysis(true, finalPieceLum, false, cx, 0f, mass, highestY, sliceWidths, asymX)
    }

    private fun classifyPiece(r: Int, cell: CellAnalysis): Char {
        val s0 = cell.sliceWidths[0]
        val s1 = cell.sliceWidths[1]
        val s2 = cell.sliceWidths[2]
        
        if (cell.headAsymmetry > 1.8f) return 'N'
        if (r in 1..6 && cell.mass < 220 && cell.highestY >= 8 && s0 <= 4 && s1 <= 9) return 'P'
        if (s0 <= 5 && s1 >= 9 && s1 >= s2) return 'R'
        if (cell.highestY <= 6 && s0 >= 6) {
            return if (s0 >= 9 || s1 >= 11) 'Q' else 'K'
        }
        if (s1 < s2 && s2 >= 8) return 'B'
        
        return if (r == 0 || r == 7) 'R' else 'P'
    }

    private fun buildFenBoardString(matrix: Array<CharArray>): String {
        val rows = ArrayList<String>(8)
        for (r in 0..7) {
            var empty = 0; val sb = StringBuilder()
            for (c in 0..7) {
                val ch = matrix[r][c]
                if (ch == '1') empty++ else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(ch) }
            }
            if (empty > 0) sb.append(empty)
            rows.add(sb.toString())
        }
        return rows.joinToString("/")
    }

    private fun validateChessPositionStrict(matrix: Array<CharArray>): String? {
        var wKings = 0; var bKings = 0; var wPieces = 0; var bPieces = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val ch = matrix[r][c]
                if (ch == '1') continue
                if (ch.isUpperCase()) wPieces++ else bPieces++
                if ((r == 0 || r == 7) && (ch == 'P' || ch == 'p')) return "Tốt ở hàng cuối"
                if (ch == 'K') wKings++
                if (ch == 'k') bKings++
            }
        }
        if (wKings != 1 || bKings != 1) return "Vua ($wKings/$bKings)"
        if (wPieces > 16 || bPieces > 16) return "Quá 16 quân"
        return null
    }

    private fun determineSideToMoveAndUpdateHistory(currentMatrix: Array<CharArray>, myColor: Char): Char {
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        if (buildFenBoardString(currentMatrix) == startFen) {
            previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
            lastDetectedTurn = 'w'
            lastEnPassantTarget = "-"
            return 'w'
        }

        val prev = previousValidMatrix
        if (prev != null) {
            var diffCount = 0; var whiteArrived = 0; var blackArrived = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    val oldCh = prev[r][c]
                    val newCh = currentMatrix[r][c]
                    if (oldCh != newCh) {
                        diffCount++
                        if (newCh != '1') { if (newCh.isUpperCase()) whiteArrived++ else blackArrived++ }
                    }
                }
            }
            if (diffCount == 0) return lastDetectedTurn
            if (whiteArrived > 0 && blackArrived == 0) { lastDetectedTurn = 'b'; previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }; return 'b' }
            if (blackArrived > 0 && whiteArrived == 0) { lastDetectedTurn = 'w'; previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }; return 'w' }
        }
        lastDetectedTurn = myColor
        previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }
        return myColor
    }

    private fun raceBothStockfishServers(fen: String): String? {
        val tasks = listOf(
            Callable { fetchFromChessApi(fen) ?: throw RuntimeException("No move") },
            Callable { fetchFromStockfishOnline(fen) ?: throw RuntimeException("No move") }
        )
        return try { apiRacePool.invokeAny(tasks, 2200, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://stockfish.online/api/s/v2.php?fen=${URLEncoder.encode(fen, "UTF-8")}&depth=10")
            conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 1900; readTimeout = 1900; setRequestProperty("User-Agent", "Mozilla/5.0") }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                if (!json.optBoolean("success", true)) return null
                val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                val p = raw.trim().split(" "); val c = if (p.size >= 2) p[1] else p[0]
                if (c.length >= 4 && c != "(none)") c.substring(0, min(c.length, 5)) else null
            } else null
        } catch (e: Exception) { null } finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }

    private fun fetchFromChessApi(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://chess-api.com/v1").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 1900; readTimeout = 1900; setRequestProperty("Content-Type", "application/json"); doOutput = true }
            conn.outputStream.use { it.write(JSONObject().put("fen", fen).put("depth", 10).toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val move = JSONObject(text).optString("move", "")
                if (move.length >= 4) move.substring(0, min(move.length, 5)) else null
            } else null
        } catch (e: Exception) { null } finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }

    private fun formatMoveText(uci: String): String {
        val base = "${uci.substring(0, 2).uppercase()} ➔ ${uci.substring(2, 4).uppercase()}"
        return if (uci.length >= 5) "$base (Phong ${uci[4].uppercaseChar()})" else base
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel("chess_overlay_channel", "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = Notification.Builder(this, "chess_overlay_channel").setContentTitle("Chess Pro v7.3.2").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false
        mainHandler.removeCallbacksAndMessages(null)
        bgThread?.quitSafely()
        workerExecutor.shutdownNow(); apiRacePool.shutdownNow()
        cleanupMediaProjection()
        synchronized(frameCacheLock) { lastCleanFullBitmap?.recycle(); lastCleanFullBitmap = null }
        try { if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView) } catch (_: Exception) {}
        try { if (::mainControlPanel.isInitialized) windowManager.removeView(mainControlPanel.parent as View) } catch (_: Exception) {}
        try { pieceEditorPopup?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }
}
