package com.example.chessvision

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var overlayParams: WindowManager.LayoutParams
    
    private lateinit var mainControlPanel: LinearLayout
    private lateinit var calibControlPanel: LinearLayout
    private lateinit var manualEditorPanel: LinearLayout
    private lateinit var editorGridContainer: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    
    private lateinit var tvStatus: TextView
    private lateinit var tvFenDebug: TextView
    private lateinit var btnSide: Button
    private lateinit var btnTurn: Button
    
    private var pieceEditorPopup: LinearLayout? = null
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
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
    @Volatile private var isLearningMode = false
    @Volatile private var cleanFrameMinTimestamp = 0L

    private val frameCacheLock = Any()
    private var lastCleanFullBitmap: Bitmap? = null

    @Volatile private var detectedBoardRect: RectF? = null
    @Volatile private var validMatrix: Array<CharArray>? = null
    @Volatile private var lastDetectedTurn: Char = 'w'
    @Volatile private var previousValidMatrix: Array<CharArray>? = null

    private val stateGeneration = AtomicLong(0L)
    @Volatile private var latestValidStateKey = ""
    @Volatile private var lastCompletedStateKey = ""
    @Volatile private var forceTurnSide: Char? = null

    private val moveCache = ConcurrentHashMap<String, String>()
    private val imageTemplates = ConcurrentHashMap<Char, IntArray>()

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
        prefs = getSharedPreferences("VisionPro9_4Prefs", Context.MODE_PRIVATE)
        val thread = HandlerThread("ImageCaptureThread").apply { start() }
        bgThread = thread; bgHandler = Handler(thread.looper)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
        setupBoardOverlay()
        setupUI()
        
        loadTemplatesFromDisk()
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width(); screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
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
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent?.getParcelableExtra("DATA", Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA")
        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                cleanupMediaProjection()
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                recreateVirtualDisplay()
                mainHandler.postDelayed({ requestCleanCapture(manualTrigger = true) }, 450)
            } catch (e: Exception) { e.printStackTrace() }
        }
        return START_NOT_STICKY
    }

    private fun cleanupMediaProjection() {
        try { virtualDisplay?.release(); imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun setupBoardOverlay() {
        chessOverlayView = ChessOverlayView(this)
        val savedTop = prefs.getFloat("BOARD_TOP", -1f); val savedLeft = prefs.getFloat("BOARD_LEFT", 0f); val savedSize = prefs.getFloat("BOARD_SIZE", -1f)
        if (savedTop > 0 && savedSize > 0) {
            detectedBoardRect = RectF(savedLeft, savedTop, savedLeft + savedSize, savedTop + savedSize); chessOverlayView.detectedBoardRect = detectedBoardRect
        } else {
            val minDim = min(screenWidth, screenHeight).toFloat(); val defTop = (screenHeight - minDim) / 2f
            detectedBoardRect = RectF(0f, defTop, minDim, defTop + minDim); chessOverlayView.detectedBoardRect = detectedBoardRect
        }
        overlayParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        windowManager.addView(chessOverlayView, overlayParams)
    }

    private fun getBtnBg(colorHex: String): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.parseColor(colorHex)) }

    private fun createButton(label: String, colorStr: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 11.5f; setTextColor(Color.WHITE); background = getBtnBg(colorStr); isAllCaps = false; setPadding(16, 20, 16, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(6, 6, 6, 6); weight = 1f }
            setOnClickListener { onClick(this) }
        }
    }

    private fun setupUI() {
        mainControlPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(245, 25, 25, 30)); setStroke(2, Color.parseColor("#444444")) }; setPadding(20, 20, 20, 20) }
        tvStatus = TextView(this).apply { text = "Chess Pro v9.4 • Đã Sửa Lỗi Biên Dịch"; setTextColor(Color.parseColor("#00E676")); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 12) }
        tvFenDebug = TextView(this).apply { text = "Vui lòng mở Trận Máy & HỌC BỘ CỜ"; setTextColor(Color.parseColor("#f1c40f")); textSize = 10f; gravity = Gravity.CENTER; maxLines = 1; setPadding(0, 0, 0, 12) }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnSide = createButton("⚪ Phe: Trắng", "#2980b9") {
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom; btnSide.text = if (chessOverlayView.isWhiteBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
            resetState()
        }
        btnTurn = createButton("🔄 Lượt: Auto", "#2980b9") {
            forceTurnSide = when (forceTurnSide) { null -> if (chessOverlayView.isWhiteBottom) 'w' else 'b'; 'w' -> 'b'; else -> null }
            btnTurn.text = when (forceTurnSide) { 'w' -> "⚡ Lượt: Trắng"; 'b' -> "⚡ Lượt: Đen"; else -> "🔄 Lượt: Auto" }
            resetState()
        }
        row1.addView(btnSide); row1.addView(btnTurn)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(createButton("🎯 Chỉnh Khung", "#8e44ad") { mainControlPanel.visibility = View.GONE; calibControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = true; chessOverlayView.invalidate() })
        row2.addView(createButton("📸 HỌC BỘ CỜ", "#f39c12") {
            isAutoMode = false; mainHandler.removeCallbacks(autoLoopRunnable)
            isLearningMode = true; requestCleanCapture(manualTrigger = true)
            Toast.makeText(this@OverlayService, "Đang chụp ảnh bộ cờ...", Toast.LENGTH_SHORT).show()
        })
        
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(createButton("👁️ Sửa Bảng", "#d35400") {
            if (validMatrix == null) { 
                Toast.makeText(this@OverlayService, "Hãy Quét 1 lần trước!", Toast.LENGTH_SHORT).show()
            } else {
                isAutoMode = false; mainControlPanel.visibility = View.GONE; manualEditorPanel.visibility = View.VISIBLE
                buildEditorGridUI()
            }
        })
        row3.addView(createButton("⚡ Quét", "#27ae60") {
            if (imageTemplates.isEmpty()) { 
                Toast.makeText(this@OverlayService, "Chưa nạp ảnh cờ!", Toast.LENGTH_LONG).show()
            } else {
                resetState(); requestCleanCapture(true)
            }
        })

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnAuto = createButton("▶ Auto", "#27ae60") { btn ->
            if (imageTemplates.isEmpty()) { 
                Toast.makeText(this@OverlayService, "Chưa nạp ảnh cờ!", Toast.LENGTH_LONG).show()
            } else {
                isAutoMode = !isAutoMode; mainHandler.removeCallbacks(autoLoopRunnable)
                if (isAutoMode) { btn.text = "⏸ Dừng"; resetState(); mainHandler.post(autoLoopRunnable) } else { btn.text = "▶ Auto"; tvStatus.text = "Đã tạm dừng." }
            }
        }
        val btnClose = createButton("✕ Đóng App", "#c0392b") { stopSelf() }
        row4.addView(btnAuto); row4.addView(btnClose)

        mainControlPanel.addView(tvStatus); mainControlPanel.addView(tvFenDebug)
        mainControlPanel.addView(row1); mainControlPanel.addView(row2); mainControlPanel.addView(row3); mainControlPanel.addView(row4)

        // CHỈNH KHUNG PANEL
        calibControlPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(250, 40, 20, 60)) }; setPadding(24, 20, 24, 20); visibility = View.GONE }
        val tvCalib = TextView(this).apply { text = "Dùng các nút dưới để khớp Lưới Đỏ"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0,0,0,16) }
        val cRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; cRow1.addView(createButton("▲ Lên", "#555555") { moveBoard(0f, -6f) }); cRow1.addView(createButton("▼ Xuống", "#555555") { moveBoard(0f, 6f) })
        val cRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; cRow2.addView(createButton("◀ Trái", "#555555") { moveBoard(-6f, 0f) }); cRow2.addView(createButton("▶ Phải", "#555555") { moveBoard(6f, 0f) })
        val cRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; cRow3.addView(createButton("➕ To ra", "#34495e") { scaleBoard(8f) }); cRow3.addView(createButton("➖ Nhỏ lại", "#34495e") { scaleBoard(-8f) })
        val cRow4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cRow4.addView(createButton("❌ HỦY", "#c0392b") { calibControlPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = false; chessOverlayView.invalidate() })
        cRow4.addView(createButton("✔️ LƯU", "#27ae60") {
            calibControlPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = false; chessOverlayView.invalidate()
            detectedBoardRect?.let { prefs.edit().putFloat("BOARD_LEFT", it.left).putFloat("BOARD_TOP", it.top).putFloat("BOARD_SIZE", it.width()).apply() }
        })
        calibControlPanel.addView(tvCalib); calibControlPanel.addView(cRow1); calibControlPanel.addView(cRow2); calibControlPanel.addView(cRow3); calibControlPanel.addView(cRow4)

        // BẢNG SỬA QUÂN (SOLID GRID EDITOR)
        manualEditorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(255, 35, 35, 45)); setStroke(4, Color.parseColor("#d35400")) }; setPadding(16, 24, 16, 24); visibility = View.GONE }
        val tvEditTitle = TextView(this).apply { text = "SỬA BÀN CỜ THỦ CÔNG"; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0,0,0,16) }
        editorGridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,0,0,24) }
        val eRowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        eRowActions.addView(createButton("❌ HỦY", "#c0392b") { manualEditorPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE })
        eRowActions.addView(createButton("✔️ LƯU & TÍNH NƯỚC", "#27ae60") { manualEditorPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; forceEvaluateMatrix() })
        manualEditorPanel.addView(tvEditTitle); manualEditorPanel.addView(editorGridContainer); manualEditorPanel.addView(eRowActions)

        val rootContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(mainControlPanel); addView(calibControlPanel); addView(manualEditorPanel) }
        controlParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        rootContainer.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = controlParams.x; iy = controlParams.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { controlParams.x = ix + (e.rawX - tx).toInt(); controlParams.y = iy + (e.rawY - ty).toInt(); windowManager.updateViewLayout(rootContainer, controlParams); true }
                else -> false
            }
        }
        windowManager.addView(rootContainer, controlParams)
    }

    private fun buildEditorGridUI() {
        editorGridContainer.removeAllViews()
        val matrix = validMatrix ?: return
        val w = screenWidth - 100; val btnSize = w / 8
        for (r in 0..7) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..7) {
                val btn = Button(this).apply {
                    val actualR = if (chessOverlayView.isWhiteBottom) r else 7 - r; val actualC = if (chessOverlayView.isWhiteBottom) c else 7 - c
                    val ch = matrix[actualR][actualC]
                    val isWhiteBg = (r + c) % 2 == 0
                    background = GradientDrawable().apply { setColor(if (isWhiteBg) Color.parseColor("#ecf0f1") else Color.parseColor("#95a5a6")) }
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(1,1,1,1) }; setPadding(0,0,0,0)
                    if (ch != '1') {
                        val isW = ch.isUpperCase()
                        val n = when(ch.uppercaseChar()) { 'P'->"TỐT"; 'N'->"MÃ"; 'B'->"TƯỢNG"; 'R'->"XE"; 'Q'->"HẬU"; 'K'->"VUA"; else->"" }
                        text = if (isW) "$n\n⚪" else "$n\n⚫"; setTextColor(if (isW) Color.BLACK else Color.WHITE); textSize = 9f
                    }
                    setOnClickListener { showPieceEditorPopup(actualR, actualC) }
                }
                row.addView(btn)
            }
            editorGridContainer.addView(row)
        }
    }

    private fun showPieceEditorPopup(r: Int, c: Int) {
        if (pieceEditorPopup != null) { windowManager.removeView(pieceEditorPopup); pieceEditorPopup = null }
        val popup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(245, 30, 30, 30)); setStroke(4, Color.parseColor("#f39c12")) }; setPadding(32, 32, 32, 32) }
        val tvTitle = TextView(this).apply { val colName = c + 'a'.code; val rowName = 8 - r; text = "Sửa ô ${colName.toChar()}$rowName"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24) }
        popup.addView(tvTitle)

        fun createPieceBtn(label: String, ch: Char, isWhite: Boolean): Button {
            return Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false; setTextColor(if (isWhite) Color.BLACK else Color.WHITE)
                background = getBtnBg(if (isWhite) "#ecf0f1" else "#2c3e50")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f; setMargins(4,4,4,4) }
                setOnClickListener { validMatrix?.get(r)?.set(c, ch); buildEditorGridUI(); windowManager.removeView(popup); pieceEditorPopup = null }
            }
        }

        val rowW1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowW1.addView(createPieceBtn("TỐT ⚪", 'P', true)); rowW1.addView(createPieceBtn("MÃ ⚪", 'N', true)); rowW1.addView(createPieceBtn("TƯỢNG ⚪", 'B', true))
        val rowW2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowW2.addView(createPieceBtn("XE ⚪", 'R', true)); rowW2.addView(createPieceBtn("HẬU ⚪", 'Q', true)); rowW2.addView(createPieceBtn("VUA ⚪", 'K', true))
        val rowB1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowB1.addView(createPieceBtn("TỐT ⚫", 'p', false)); rowB1.addView(createPieceBtn("MÃ ⚫", 'n', false)); rowB1.addView(createPieceBtn("TƯỢNG ⚫", 'b', false))
        val rowB2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowB2.addView(createPieceBtn("XE ⚫", 'r', false)); rowB2.addView(createPieceBtn("HẬU ⚫", 'q', false)); rowB2.addView(createPieceBtn("VUA ⚫", 'k', false))
        
        val btnClear = createButton("❌ XÓA TRỐNG", "#c0392b") { validMatrix?.get(r)?.set(c, '1'); buildEditorGridUI(); windowManager.removeView(popup); pieceEditorPopup = null }
        btnClear.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }
        val btnCancel = createButton("HỦY ĐÓNG", "#7f8c8d") { windowManager.removeView(popup); pieceEditorPopup = null }
        btnCancel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 0) }

        popup.addView(rowW1); popup.addView(rowW2); popup.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }); popup.addView(rowB1); popup.addView(rowB2); popup.addView(btnClear); popup.addView(btnCancel)
        val popupParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        windowManager.addView(popup, popupParams); pieceEditorPopup = popup
    }
    
    private fun moveBoard(dx: Float, dy: Float) { val r = detectedBoardRect ?: return; detectedBoardRect = RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy); chessOverlayView.detectedBoardRect = detectedBoardRect; chessOverlayView.invalidate() }
    private fun scaleBoard(ds: Float) { val r = detectedBoardRect ?: return; detectedBoardRect = RectF(r.left - ds/2, r.top - ds/2, r.right + ds/2, r.bottom + ds/2); chessOverlayView.detectedBoardRect = detectedBoardRect; chessOverlayView.invalidate() }
    private fun resetState() { lastCompletedStateKey = ""; chessOverlayView.setMove(null) }

    private fun extractExactScreenBitmap(img: android.media.Image): Bitmap {
        val plane = img.planes[0]; val buffer = plane.buffer; val pixelStride = plane.pixelStride; val rowPadding = plane.rowStride - pixelStride * screenWidth
        val strideWidth = screenWidth + rowPadding / pixelStride
        val rawBmp = Bitmap.createBitmap(strideWidth, screenHeight, Bitmap.Config.ARGB_8888); rawBmp.copyPixelsFromBuffer(buffer)
        return if (strideWidth > screenWidth) { val cropped = Bitmap.createBitmap(rawBmp, 0, 0, screenWidth, screenHeight); rawBmp.recycle(); cropped } else rawBmp
    }

    private fun recreateVirtualDisplay() {
        try { virtualDisplay?.release(); imageReader?.close() } catch (_: Exception) {}
        val mp = mediaProjection ?: return
        val handler = bgHandler ?: return
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val exactBmp = extractExactScreenBitmap(img); img.close()
                if (!chessOverlayView.hasActiveArrow() || (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp)) {
                    synchronized(frameCacheLock) { lastCleanFullBitmap?.recycle(); lastCleanFullBitmap = exactBmp.copy(Bitmap.Config.ARGB_8888, false) }
                }
                if (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp) {
                    if (isProcessing.compareAndSet(false, true)) {
                        waitingForCleanFrame = false; mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                        if (isLearningMode) workerExecutor.execute { processLearningCapture(exactBmp) }
                        else workerExecutor.execute { processCapturedBitmap(exactBmp) }
                        return@setOnImageAvailableListener
                    }
                }
                exactBmp.recycle()
            } catch (e: Exception) {
                try { img.close() } catch (_: Exception) {}
                if (waitingForCleanFrame) { waitingForCleanFrame = false; mainHandler.post { chessOverlayView.visibility = View.VISIBLE }; isProcessing.set(false) }
            }
        }, handler)
        virtualDisplay = mp.createVirtualDisplay("ChessProCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
    }

    private fun requestCleanCapture(manualTrigger: Boolean) {
        if (isProcessing.get() || waitingForCleanFrame || chessOverlayView.isCalibrationMode) return
        mainHandler.post {
            val needHideArrow = chessOverlayView.hasActiveArrow()
            if (!needHideArrow) {
                val img = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null && isProcessing.compareAndSet(false, true)) {
                    try {
                        val exactBmp = extractExactScreenBitmap(img); img.close()
                        synchronized(frameCacheLock) { lastCleanFullBitmap?.recycle(); lastCleanFullBitmap = exactBmp.copy(Bitmap.Config.ARGB_8888, false) }
                        if (isLearningMode) workerExecutor.execute { processLearningCapture(exactBmp) }
                        else workerExecutor.execute { processCapturedBitmap(exactBmp) }
                        return@post
                    } catch (e: Exception) { try { img.close() } catch (_: Exception) {}; isProcessing.set(false) }
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
                                val exactBmp = extractExactScreenBitmap(img); img.close()
                                synchronized(frameCacheLock) { lastCleanFullBitmap?.recycle(); lastCleanFullBitmap = exactBmp.copy(Bitmap.Config.ARGB_8888, false) }
                                mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                if (isLearningMode) workerExecutor.execute { processLearningCapture(exactBmp) }
                                else workerExecutor.execute { processCapturedBitmap(exactBmp) }
                            } catch (e: Exception) { try { img.close() } catch (_: Exception) {}; mainHandler.post { chessOverlayView.visibility = View.VISIBLE }; isProcessing.set(false) }
                        } else {
                            img?.close()
                            val fb = synchronized(frameCacheLock) { lastCleanFullBitmap?.let { try { it.copy(Bitmap.Config.ARGB_8888, false) } catch (_:Exception) {null} } }
                            if (fb != null && isProcessing.compareAndSet(false, true)) {
                                waitingForCleanFrame = false; mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                                if (isLearningMode) workerExecutor.execute { processLearningCapture(fb) }
                                else workerExecutor.execute { processCapturedBitmap(fb) }
                            } else { fb?.recycle(); waitingForCleanFrame = false; mainHandler.post { chessOverlayView.visibility = View.VISIBLE } }
                        }
                    }
                }, 85L)
            }
        }
    }

    private fun checkOccupiedByVariance(boardPixels: IntArray, r: Int, c: Int): Boolean {
        val baseX = c * 32; val baseY = r * 32
        var sumLum = 0L; var sumSqLum = 0L; var pxCount = 0
        for (y in 4..27) {
            val rowOffset = (baseY + y) * 256 + baseX
            for (x in 4..27) {
                val p = boardPixels[rowOffset + x]
                val lum = (Color.red(p)*299 + Color.green(p)*587 + Color.blue(p)*114)/1000
                sumLum += lum; sumSqLum += (lum * lum).toLong(); pxCount++
            }
        }
        val meanLum = (sumLum / pxCount).toInt(); val meanDouble = sumLum.toDouble() / pxCount
        val variance = (sumSqLum.toDouble() / pxCount) - (meanDouble * meanDouble)
        return variance > 40.0 
    }

    private fun extractCellCoreColors(boardPixels: IntArray, visualRow: Int, visualCol: Int): IntArray {
        val core = IntArray(64) 
        val baseX = visualCol * 32; val baseY = visualRow * 32; var k = 0
        for (y in 14..21) {
            val rowOffset = (baseY + y) * 256 + baseX
            for (x in 12..19) { core[k++] = boardPixels[rowOffset + x] }
        }
        return core
    }

    private fun saveTemplatesToDisk() {
        val editor = prefs.edit()
        for ((char, array) in imageTemplates) { editor.putString("TPL_$char", array.joinToString(",")) }
        editor.apply()
    }

    private fun loadTemplatesFromDisk() {
        imageTemplates.clear()
        for (c in charArrayOf('P','N','B','R','Q','K','p','n','b','r','q','k')) {
            prefs.getString("TPL_$c", null)?.let { str ->
                try { imageTemplates[c] = str.split(",").map { it.toInt() }.toIntArray() } catch(e: Exception) {}
            }
        }
        if (imageTemplates.size == 12) mainHandler.post { tvFenDebug.text = "✅ Đã nạp thành công bộ cờ từ bộ nhớ!" }
    }

    private fun processLearningCapture(fullBmp: Bitmap) {
        try {
            val boardRect = detectedBoardRect
            if (boardRect == null) { mainHandler.post { Toast.makeText(this@OverlayService, "Chưa có khung bàn cờ!", Toast.LENGTH_SHORT).show() }; return }

            val bLeft = boardRect.left.toInt().coerceIn(0, fullBmp.width - 64); val bTop = boardRect.top.toInt().coerceIn(0, fullBmp.height - 64)
            val bSize = boardRect.width().toInt().coerceIn(64, min(fullBmp.width - bLeft, fullBmp.height - bTop))
            val croppedBoard = Bitmap.createBitmap(fullBmp, bLeft, bTop, bSize, bSize)
            val board256 = Bitmap.createScaledBitmap(croppedBoard, 256, 256, true); croppedBoard.recycle()
            val boardPixels = IntArray(256 * 256); board256.getPixels(boardPixels, 0, 256, 0, 0, 256, 256); board256.recycle()

            imageTemplates.clear()
            val wRow = if (chessOverlayView.isWhiteBottom) 7 else 0; val wpRow = if (chessOverlayView.isWhiteBottom) 6 else 1
            val bRow = if (chessOverlayView.isWhiteBottom) 0 else 7; val bpRow = if (chessOverlayView.isWhiteBottom) 1 else 6
            
            val qCol = if (chessOverlayView.isWhiteBottom) 3 else 4
            val kCol = if (chessOverlayView.isWhiteBottom) 4 else 3

            imageTemplates['R'] = extractCellCoreColors(boardPixels, wRow, 7)
            imageTemplates['N'] = extractCellCoreColors(boardPixels, wRow, 6)
            imageTemplates['B'] = extractCellCoreColors(boardPixels, wRow, 5)
            imageTemplates['Q'] = extractCellCoreColors(boardPixels, wRow, qCol)
            imageTemplates['K'] = extractCellCoreColors(boardPixels, wRow, kCol)
            imageTemplates['P'] = extractCellCoreColors(boardPixels, wpRow, 4)
            
            imageTemplates['r'] = extractCellCoreColors(boardPixels, bRow, 7)
            imageTemplates['n'] = extractCellCoreColors(boardPixels, bRow, 6)
            imageTemplates['b'] = extractCellCoreColors(boardPixels, bRow, 5)
            imageTemplates['q'] = extractCellCoreColors(boardPixels, bRow, qCol)
            imageTemplates['k'] = extractCellCoreColors(boardPixels, bRow, kCol)
            imageTemplates['p'] = extractCellCoreColors(boardPixels, bpRow, 4)
            
            saveTemplatesToDisk()
            mainHandler.post { tvStatus.text = "✅ ĐÃ HỌC XONG BỘ CỜ MỚI!"; tvFenDebug.text = "Dữ liệu ảnh mẫu đã được lưu vào máy"; Toast.makeText(this@OverlayService, "Đã lưu 12 ảnh mẫu vào bộ nhớ!", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { mainHandler.post { Toast.makeText(this@OverlayService, "Lỗi học ảnh: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally { fullBmp.recycle(); isLearningMode = false; isProcessing.set(false) }
    }

    private fun processCapturedBitmap(fullBmp: Bitmap) {
        try {
            val boardRect = detectedBoardRect
            if (boardRect == null) { mainHandler.post { tvStatus.text = "⚠️ Hãy chỉnh khung bàn cờ trước" }; return }
            if (imageTemplates.size < 12) { mainHandler.post { tvStatus.text = "⚠️ BẠN CHƯA BẤM NÚT [HỌC BỘ CỜ]" }; return }

            val bLeft = boardRect.left.toInt().coerceIn(0, fullBmp.width - 64); val bTop = boardRect.top.toInt().coerceIn(0, fullBmp.height - 64)
            val bSize = boardRect.width().toInt().coerceIn(64, min(fullBmp.width - bLeft, fullBmp.height - bTop))
            val croppedBoard = Bitmap.createBitmap(fullBmp, bLeft, bTop, bSize, bSize)
            val board256 = Bitmap.createScaledBitmap(croppedBoard, 256, 256, true); croppedBoard.recycle()
            val boardPixels = IntArray(256 * 256); board256.getPixels(boardPixels, 0, 256, 0, 0, 256, 256); board256.recycle()

            val rawMatrix = Array(8) { CharArray(8) { '1' } }
            for (visualR in 0..7) {
                for (visualC in 0..7) {
                    if (checkOccupiedByVariance(boardPixels, visualR, visualC)) {
                        val currentCore = extractCellCoreColors(boardPixels, visualR, visualC)
                        rawMatrix[visualR][visualC] = classifyByTemplateMatching(currentCore)
                    }
                }
            }

            val standardMatrix = if (chessOverlayView.isWhiteBottom) rawMatrix else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }
            validMatrix = standardMatrix
            val boardFen = buildFenBoardString(standardMatrix)
            mainHandler.post { tvFenDebug.text = "FEN: $boardFen" }

            val myColorChar = if (chessOverlayView.isWhiteBottom) 'w' else 'b'
            val activeTurn = forceTurnSide ?: determineSideToMoveAndUpdateHistory(standardMatrix, myColorChar)

            val stateKey = "$boardFen|$activeTurn"
            latestValidStateKey = stateKey
            val reqGen = stateGeneration.incrementAndGet()

            if (activeTurn != myColorChar) { mainHandler.post { chessOverlayView.setMove(null); tvStatus.text = "⏳ Đợi đối thủ đi..." }; return }
            if (stateKey == lastCompletedStateKey && chessOverlayView.hasActiveArrow()) return

            val fullFen = "$boardFen $activeTurn - - 0 1"
            val cachedMove = moveCache[fullFen]
            if (cachedMove != null) { lastCompletedStateKey = stateKey; mainHandler.post { chessOverlayView.setMove(cachedMove); tvStatus.text = "⚡ Đi: ${formatMoveText(cachedMove)}" }; return }

            mainHandler.post { tvStatus.text = "⚡ Đang tính (Template Match)..." }
            var bestMove = raceBothStockfishServers(fullFen)
            if (bestMove == null) bestMove = raceBothStockfishServers("$boardFen $myColorChar - - 0 1")

            val finalMove = bestMove
            if (latestValidStateKey != stateKey || stateGeneration.get() != reqGen) return
            mainHandler.post {
                if (finalMove != null && finalMove.length >= 4) {
                    moveCache[fullFen] = finalMove; lastCompletedStateKey = stateKey; chessOverlayView.setMove(finalMove)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(finalMove)}"
                } else tvStatus.text = "⏳ Lỗi API, thử lại..."
            }
        } catch (e: Exception) { mainHandler.post { tvStatus.text = "Lỗi: ${e.message}" } } finally { fullBmp.recycle(); isProcessing.set(false) }
    }

    private fun classifyByTemplateMatching(cellCore: IntArray): Char {
        var bestChar = 'P'; var minDiff = Long.MAX_VALUE
        for ((pieceChar, templatePixels) in imageTemplates) {
            var diff = 0L
            for (i in 0 until 64) {
                val p1 = cellCore[i]; val p2 = templatePixels[i]
                diff += abs(Color.red(p1) - Color.red(p2)) + abs(Color.green(p1) - Color.green(p2)) + abs(Color.blue(p1) - Color.blue(p2))
            }
            if (diff < minDiff) { minDiff = diff; bestChar = pieceChar }
        }
        return bestChar
    }

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
            if (bestMove == null) bestMove = raceBothStockfishServers("$boardFen $myColorChar - - 0 1")
            
            val finalMove = bestMove
            if (latestValidStateKey != stateKey || stateGeneration.get() != reqGen) return@execute
            mainHandler.post {
                if (finalMove != null && finalMove.length >= 4) {
                    moveCache[fullFen] = finalMove; lastCompletedStateKey = stateKey; chessOverlayView.setMove(finalMove)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(finalMove)}"
                } else tvStatus.text = "⏳ Lỗi API, FEN sửa tay chưa hợp lệ..."
            }
        }
    }

    private fun buildFenBoardString(matrix: Array<CharArray>): String {
        val rows = ArrayList<String>(8)
        for (r in 0..7) {
            var empty = 0; val sb = StringBuilder()
            for (c in 0..7) {
                val ch = matrix[r][c]; if (ch == '1') empty++ else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(ch) }
            }
            if (empty > 0) sb.append(empty); rows.add(sb.toString())
        }
        return rows.joinToString("/")
    }

    private fun determineSideToMoveAndUpdateHistory(currentMatrix: Array<CharArray>, myColor: Char): Char {
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        if (buildFenBoardString(currentMatrix) == startFen) {
            previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }; lastDetectedTurn = 'w'; return 'w'
        }
        val prev = previousValidMatrix
        if (prev != null) {
            var diffCount = 0; var whiteArrived = 0; var blackArrived = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    val oldCh = prev[r][c]; val newCh = currentMatrix[r][c]
                    if (oldCh != newCh) { diffCount++; if (newCh != '1') { if (newCh.isUpperCase()) whiteArrived++ else blackArrived++ } }
                }
            }
            if (diffCount == 0) return lastDetectedTurn
            if (whiteArrived > 0 && blackArrived == 0) { lastDetectedTurn = 'b'; previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }; return 'b' }
            if (blackArrived > 0 && whiteArrived == 0) { lastDetectedTurn = 'w'; previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }; return 'w' }
        }
        lastDetectedTurn = myColor; previousValidMatrix = Array(8) { r -> currentMatrix[r].clone() }; return myColor
    }

    private fun raceBothStockfishServers(fen: String): String? {
        val tasks = listOf(Callable { fetchFromChessApi(fen) ?: throw RuntimeException("No move") }, Callable { fetchFromStockfishOnline(fen) ?: throw RuntimeException("No move") })
        return try { apiRacePool.invokeAny(tasks, 2200, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://stockfish.online/api/s/v2.php?fen=${URLEncoder.encode(fen, "UTF-8")}&depth=10")
            conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 1900; readTimeout = 1900; setRequestProperty("User-Agent", "Mozilla/5.0") }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
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
                val move = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("move", "")
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
        val notif = Notification.Builder(this, "chess_overlay_channel").setContentTitle("Chess Pro v9.4").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false; mainHandler.removeCallbacksAndMessages(null); bgThread?.quitSafely()
        workerExecutor.shutdownNow(); apiRacePool.shutdownNow(); cleanupMediaProjection()
        synchronized(frameCacheLock) { lastCleanFullBitmap?.recycle(); lastCleanFullBitmap = null }
        try { if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView) } catch (_: Exception) {}
        try { if (::mainControlPanel.isInitialized) windowManager.removeView(mainControlPanel.parent as View) } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }
}
