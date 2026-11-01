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
import android.util.Log
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OverlayService : Service() {

    companion object {
        private const val CELL = 32
        private const val BOARD_PX = 256
        private const val SIL = 12
        private const val SIL_N = SIL * SIL      // 144
        private const val BG_DIFF = 70
        private const val EDGE_GRAD = 36         // ngưỡng gradient biên cho quân trắng trên ô sáng
        private const val OCCUPIED_MIN_PX = 75
        private const val MIN_SPAN_Y = 12        // chiều cao tối thiểu để loại bỏ chấm gợi ý nước đi
        private const val MATCH_MIN_FULL = 0.55f // ngưỡng ZNCC khi đã đủ 6 loại quân
        private const val MATCH_MIN_PARTIAL = 0.75f // ngưỡng ZNCC khi học giữa ván (chưa đủ 6 loại)
        private const val MATCH_MARGIN = 0.01f
        private const val MAX_RETRIES = 6
        private const val NUDGE_COUNT = 8
        private const val START_BOARD = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        private const val PIECE_TYPES = "PNBRQK"
        private const val API_TIMEOUT_MS = 3200
        private const val API_TOTAL_MS = 3600L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private lateinit var mainControlPanel: LinearLayout
    private lateinit var calibControlPanel: LinearLayout
    private lateinit var manualEditorPanel: LinearLayout
    private lateinit var editorGridContainer: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private var rootContainer: LinearLayout? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvFenDebug: TextView
    private lateinit var btnSide: Button
    private lateinit var btnTurn: Button
    private lateinit var btnAuto: Button

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
    private val apiRacePool = Executors.newCachedThreadPool()

    @Volatile private var isAutoMode = false
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var waitingForCleanFrame = false
    @Volatile private var isLearningMode = false
    @Volatile private var cleanFrameMinTimestamp = 0L
    @Volatile private var captureToken = 0L

    @Volatile private var detectedBoardRect: RectF? = null
    @Volatile private var validMatrix: Array<CharArray>? = null
    @Volatile private var lastBoardPixels: IntArray? = null // lưu 256x256 gần nhất để học mẫu từ bàn cờ sửa tay
    private var editMatrix: Array<CharArray>? = null

    // Trạng thái theo dõi ván cờ (Lớp 3: Legal Move State Tracking)
    data class GameState(
        val board: Array<CharArray>,
        val turn: Char,
        val castles: Set<Char>,
        val ep: String
    )
    @Volatile private var trackedState: GameState? = null
    @Volatile private var lastDetectedTurn: Char = 'w'

    // Khử rung
    @Volatile private var pendingObsKey: String = ""
    @Volatile private var consecutiveMatchCount = 0
    @Volatile private var debounceRetries = 0
    @Volatile private var lastRetryReason = ""

    private val stateGeneration = AtomicLong(0L)
    @Volatile private var lastCompletedStateKey = ""
    @Volatile private var forceTurnSide: Char? = null

    private val moveCache = ConcurrentHashMap<String, String>()

    // Mẫu hình bóng 12x12 chuẩn hóa ZNCC (Lớp 1 & 2)
    private val shapeTemplates = ConcurrentHashMap<Char, CopyOnWriteArrayList<FloatArray>>()
    @Volatile private var lumWhite = -1
    @Volatile private var lumBlack = -1

    class CellProfile(
        val isOccupied: Boolean,
        val avgPieceLum: Int,
        val silhouette: FloatArray,
        val mass: Int,
        val highestY: Int,
        val centroidOffset: Float,
        val sliceWidths: IntArray
    )

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode) return
            requestCleanCapture(manualTrigger = false)
            mainHandler.postDelayed(this, 1400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Đổi sang VisionPro10_4Prefs để bỏ toàn bộ mẫu lỗi của phiên bản cũ
        prefs = getSharedPreferences("VisionPro10_4Prefs", Context.MODE_PRIVATE)
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
        try {
            val old = imageReader
            val fresh = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            fresh.setOnImageAvailableListener(imageAvailableListener, bgHandler)
            virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
            virtualDisplay?.surface = fresh.surface
            imageReader = fresh
            old?.close()
        } catch (e: Exception) {
            mainHandler.post { tvStatus.text = "Lỗi đổi góc xoay: ${e.message}" }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA")
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

                initVirtualDisplay()
                mainHandler.postDelayed({ requestCleanCapture(manualTrigger = true) }, 500)
            } catch (e: Exception) {
                mainHandler.post { tvStatus.text = "Lỗi khởi tạo Projection: ${e.message}" }
            }
        }
        return START_NOT_STICKY
    }

    private fun initVirtualDisplay() {
        val mp = mediaProjection ?: return
        val handler = bgHandler ?: return
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(imageAvailableListener, handler)
        virtualDisplay = mp.createVirtualDisplay("ChessProCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
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
            text = label; textSize = 10f; setTextColor(Color.WHITE); background = getBtnBg(colorStr); isAllCaps = false; setPadding(4, 16, 4, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(4, 4, 4, 4); weight = 1f }
            setOnClickListener { onClick(this) }
        }
    }

    private fun setupUI() {
        mainControlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(245, 25, 25, 30)); setStroke(2, Color.parseColor("#444444")) }
            setPadding(12, 16, 12, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvStatus = TextView(this).apply { text = "Chess Pro v10.4 • Sẵn sàng"; setTextColor(Color.parseColor("#00E676")); textSize = 11.5f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 8) }
        tvFenDebug = TextView(this).apply { text = "Chưa quét bàn cờ"; setTextColor(Color.parseColor("#f1c40f")); textSize = 9.5f; gravity = Gravity.CENTER; maxLines = 1; setPadding(0, 0, 0, 12) }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        btnSide = createButton("⚪ Phe: Trắng", "#2980b9") {
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
            btnSide.text = if (chessOverlayView.isWhiteBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
            trackedState = null
            resetState()
        }
        btnTurn = createButton("🔄 Lượt: Auto", "#2980b9") {
            forceTurnSide = when (forceTurnSide) { null -> if (chessOverlayView.isWhiteBottom) 'w' else 'b'; 'w' -> 'b'; else -> null }
            btnTurn.text = when (forceTurnSide) { 'w' -> "⚡ Ép 1L: Trắng"; 'b' -> "⚡ Ép 1L: Đen"; else -> "🔄 Lượt: Auto" }
            resetState()
        }
        val btnCalib = createButton("🎯 Khung", "#8e44ad") { mainControlPanel.visibility = View.GONE; calibControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = true; chessOverlayView.invalidate() }
        val btnLearn = createButton("📸 Học Đầu Ván", "#f39c12") {
            isAutoMode = false; mainHandler.removeCallbacks(autoLoopRunnable); btnAuto.text = "▶ Auto"
            isLearningMode = true; requestCleanCapture(manualTrigger = true)
        }
        row1.addView(btnSide); row1.addView(btnTurn); row1.addView(btnCalib); row1.addView(btnLearn)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val btnEdit = createButton("👁️ Sửa / Học", "#d35400") {
            val vm = validMatrix ?: trackedState?.board ?: Array(8) { CharArray(8) { '1' } }
            isAutoMode = false; mainHandler.removeCallbacks(autoLoopRunnable); btnAuto.text = "▶ Auto"
            editMatrix = Array(8) { r -> vm[r].clone() }
            mainControlPanel.visibility = View.GONE; manualEditorPanel.visibility = View.VISIBLE; buildEditorGridUI()
        }
        val btnScan = createButton("⚡ Quét", "#27ae60") { resetState(); requestCleanCapture(true) }
        btnAuto = createButton("▶ Auto", "#27ae60") { btn ->
            isAutoMode = !isAutoMode; mainHandler.removeCallbacks(autoLoopRunnable)
            if (isAutoMode) { btn.text = "⏸ Dừng"; resetState(); mainHandler.post(autoLoopRunnable) } else { btn.text = "▶ Auto"; tvStatus.text = "Đã tạm dừng." }
        }
        val btnClose = createButton("✕ Đóng", "#c0392b") { stopSelf() }
        row2.addView(btnEdit); row2.addView(btnScan); row2.addView(btnAuto); row2.addView(btnClose)

        mainControlPanel.addView(tvStatus); mainControlPanel.addView(tvFenDebug)
        mainControlPanel.addView(row1); mainControlPanel.addView(row2)

        // PANEL CHỈNH KHUNG
        calibControlPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(250, 40, 20, 60)) }; setPadding(24, 20, 24, 20); visibility = View.GONE }
        val tvCalib = TextView(this).apply { text = "Khớp Lưới Đỏ với ô viền bàn cờ"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0,0,0,16) }
        val cRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; cRow1.addView(createButton("▲ Lên", "#555555") { moveBoard(0f, -6f) }); cRow1.addView(createButton("▼ Xuống", "#555555") { moveBoard(0f, 6f) })
        val cRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; cRow2.addView(createButton("◀ Trái", "#555555") { moveBoard(-6f, 0f) }); cRow2.addView(createButton("▶ Phải", "#555555") { moveBoard(6f, 0f) })
        val cRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; cRow3.addView(createButton("➕ To", "#34495e") { scaleBoard(8f) }); cRow3.addView(createButton("➖ Nhỏ", "#34495e") { scaleBoard(-8f) })
        val cRow4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cRow4.addView(createButton("❌ HỦY", "#c0392b") { calibControlPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = false; chessOverlayView.invalidate() })
        cRow4.addView(createButton("✔️ LƯU", "#27ae60") {
            calibControlPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = false; chessOverlayView.invalidate()
            detectedBoardRect?.let { prefs.edit().putFloat("BOARD_LEFT", it.left).putFloat("BOARD_TOP", it.top).putFloat("BOARD_SIZE", it.width()).apply() }
        })
        calibControlPanel.addView(tvCalib); calibControlPanel.addView(cRow1); calibControlPanel.addView(cRow2); calibControlPanel.addView(cRow3); calibControlPanel.addView(cRow4)

        // BẢNG SỬA QUÂN (CÓ THÊM NÚT HỌC MẪU TỪ THẾ CỜ HIỆN TẠI - LỚP 1)
        manualEditorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(255, 35, 35, 45)); setStroke(4, Color.parseColor("#d35400")) }; setPadding(16, 24, 16, 24); visibility = View.GONE }
        val tvEditTitle = TextView(this).apply { text = "SỬA BÀN CỜ & HỌC MẪU"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0,0,0,12) }
        editorGridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,0,0,16) }
        val eRowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        eRowActions.addView(createButton("❌ HỦY", "#c0392b") {
            editMatrix = null
            manualEditorPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE
        })
        eRowActions.addView(createButton("🎓 LƯU & HỌC", "#f39c12") {
            val edited = editMatrix
            if (edited != null) {
                validMatrix = edited
                lastBoardPixels?.let { px -> learnFromPosition(px, edited, chessOverlayView.isWhiteBottom) }
            }
            editMatrix = null
            manualEditorPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; forceEvaluateMatrix()
        })
        eRowActions.addView(createButton("✔️ LƯU & TÍNH", "#27ae60") {
            editMatrix?.let { validMatrix = it }
            editMatrix = null
            manualEditorPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE; forceEvaluateMatrix()
        })
        manualEditorPanel.addView(tvEditTitle); manualEditorPanel.addView(editorGridContainer); manualEditorPanel.addView(eRowActions)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(mainControlPanel); addView(calibControlPanel); addView(manualEditorPanel) }
        rootContainer = root
        controlParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = controlParams.x; iy = controlParams.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { controlParams.x = ix + (e.rawX - tx).toInt(); controlParams.y = iy + (e.rawY - ty).toInt(); windowManager.updateViewLayout(root, controlParams); true }
                else -> false
            }
        }
        windowManager.addView(root, controlParams)
    }

    private fun buildEditorGridUI() {
        editorGridContainer.removeAllViews()
        val matrix = editMatrix ?: return
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
                        text = if (isW) "$n\n⚪" else "$n\n⚫"; setTextColor(if (isW) Color.BLACK else Color.WHITE); textSize = 8.5f
                    }
                    setOnClickListener { showPieceEditorPopup(actualR, actualC) }
                }
                row.addView(btn)
            }
            editorGridContainer.addView(row)
        }
    }

    private fun showPieceEditorPopup(r: Int, c: Int) {
        if (pieceEditorPopup != null) { try { windowManager.removeView(pieceEditorPopup) } catch (_: Exception) {}; pieceEditorPopup = null }
        val popup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(245, 30, 30, 30)); setStroke(4, Color.parseColor("#f39c12")) }; setPadding(32, 32, 32, 32) }
        val tvTitle = TextView(this).apply { val colName = c + 'a'.code; val rowName = 8 - r; text = "Sửa ô ${colName.toChar()}$rowName"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24) }
        popup.addView(tvTitle)

        fun closePopup() { try { windowManager.removeView(popup) } catch (_: Exception) {}; pieceEditorPopup = null }

        fun createPieceBtn(label: String, ch: Char, isWhite: Boolean): Button {
            return Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false; setTextColor(if (isWhite) Color.BLACK else Color.WHITE)
                background = getBtnBg(if (isWhite) "#ecf0f1" else "#2c3e50")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f; setMargins(4,4,4,4) }
                setOnClickListener { editMatrix?.get(r)?.set(c, ch); buildEditorGridUI(); closePopup() }
            }
        }

        val rowW1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowW1.addView(createPieceBtn("TỐT ⚪", 'P', true)); rowW1.addView(createPieceBtn("MÃ ⚪", 'N', true)); rowW1.addView(createPieceBtn("TƯỢNG ⚪", 'B', true))
        val rowW2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowW2.addView(createPieceBtn("XE ⚪", 'R', true)); rowW2.addView(createPieceBtn("HẬU ⚪", 'Q', true)); rowW2.addView(createPieceBtn("VUA ⚪", 'K', true))
        val rowB1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowB1.addView(createPieceBtn("TỐT ⚫", 'p', false)); rowB1.addView(createPieceBtn("MÃ ⚫", 'n', false)); rowB1.addView(createPieceBtn("TƯỢNG ⚫", 'b', false))
        val rowB2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rowB2.addView(createPieceBtn("XE ⚫", 'r', false)); rowB2.addView(createPieceBtn("HẬU ⚫", 'q', false)); rowB2.addView(createPieceBtn("VUA ⚫", 'k', false))

        val btnClear = createButton("❌ XÓA TRỐNG", "#c0392b") { editMatrix?.get(r)?.set(c, '1'); buildEditorGridUI(); closePopup() }
        btnClear.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }
        val btnCancel = createButton("HỦY", "#7f8c8d") { closePopup() }
        btnCancel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 0) }

        popup.addView(rowW1); popup.addView(rowW2); popup.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }); popup.addView(rowB1); popup.addView(rowB2); popup.addView(btnClear); popup.addView(btnCancel)
        val popupParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        windowManager.addView(popup, popupParams); pieceEditorPopup = popup
    }

    private fun moveBoard(dx: Float, dy: Float) { val r = detectedBoardRect ?: return; detectedBoardRect = RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy); chessOverlayView.detectedBoardRect = detectedBoardRect; chessOverlayView.invalidate() }
    private fun scaleBoard(ds: Float) { val r = detectedBoardRect ?: return; detectedBoardRect = RectF(r.left - ds/2, r.top - ds/2, r.right + ds/2, r.bottom + ds/2); chessOverlayView.detectedBoardRect = detectedBoardRect; chessOverlayView.invalidate() }

    private fun resetState() {
        lastCompletedStateKey = ""
        stateGeneration.incrementAndGet()
        debounceRetries = 0
        pendingObsKey = ""
        consecutiveMatchCount = 0
        chessOverlayView.setMove(null)
    }

    // =========================================================================
    // CHỤP KHUNG HÌNH (BỌC TRY-CATCH CHỐNG KẸT ISPROCESSING)
    // =========================================================================

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@OnImageAvailableListener
        try {
            if (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp && isProcessing.compareAndSet(false, true)) {
                waitingForCleanFrame = false
                val exactBmp = try {
                    extractExactScreenBitmap(img)
                } catch (e: Exception) {
                    isProcessing.set(false)
                    mainHandler.post {
                        chessOverlayView.visibility = View.VISIBLE
                        tvStatus.text = "Lỗi đọc khung hình: ${e.message}"
                    }
                    null
                }
                if (exactBmp != null) {
                    mainHandler.post { chessOverlayView.visibility = View.VISIBLE }
                    if (isLearningMode) workerExecutor.execute { processLearningCapture(exactBmp) }
                    else workerExecutor.execute { processCapturedBitmap(exactBmp) }
                }
            }
        } finally {
            img.close()
        }
    }

    private fun extractExactScreenBitmap(img: android.media.Image): Bitmap {
        val plane = img.planes[0]; val buffer = plane.buffer; val pixelStride = plane.pixelStride; val rowPadding = plane.rowStride - pixelStride * screenWidth
        val strideWidth = screenWidth + rowPadding / pixelStride
        val rawBmp = Bitmap.createBitmap(strideWidth, screenHeight, Bitmap.Config.ARGB_8888); rawBmp.copyPixelsFromBuffer(buffer)
        return if (strideWidth > screenWidth) { val cropped = Bitmap.createBitmap(rawBmp, 0, 0, screenWidth, screenHeight); rawBmp.recycle(); cropped } else rawBmp
    }

    private fun requestCleanCapture(manualTrigger: Boolean) {
        if (manualTrigger) debounceRetries = 0
        if (isProcessing.get() || waitingForCleanFrame || chessOverlayView.isCalibrationMode) return
        mainHandler.post {
            if (isProcessing.get() || waitingForCleanFrame) return@post
            val token = ++captureToken

            if (chessOverlayView.hasActiveArrow()) {
                chessOverlayView.visibility = View.INVISIBLE
                cleanFrameMinTimestamp = SystemClock.uptimeMillis() + 35L
            } else {
                cleanFrameMinTimestamp = SystemClock.uptimeMillis()
            }
            waitingForCleanFrame = true

            val nudge = object : Runnable {
                var count = 0
                override fun run() {
                    if (token == captureToken && waitingForCleanFrame && count < NUDGE_COUNT) {
                        rootContainer?.let { it.alpha = if (it.alpha < 0.99f) 1.0f else 0.985f }
                        count++
                        mainHandler.postDelayed(this, 55)
                    }
                }
            }
            mainHandler.postDelayed(nudge, 45)

            // Báo lỗi rõ ràng cho mọi chế độ nếu quá 500ms không có khung hình
            mainHandler.postDelayed({
                if (token == captureToken && waitingForCleanFrame && !isProcessing.get()) {
                    waitingForCleanFrame = false
                    chessOverlayView.visibility = View.VISIBLE
                    isLearningMode = false
                    tvStatus.text = "Không nhận được khung hình (hãy chạm nhẹ màn hình)"
                }
            }, 500L)
        }
    }

    private fun scheduleRetry(reason: String) {
        lastRetryReason = reason
        if (debounceRetries < MAX_RETRIES) {
            debounceRetries++
            mainHandler.postDelayed({ requestCleanCapture(false) }, 110L)
        } else {
            debounceRetries = 0
            pendingObsKey = ""
            consecutiveMatchCount = 0
            mainHandler.post { tvStatus.text = "Chưa ổn định: $lastRetryReason" }
        }
    }

    // =========================================================================
    // NHẬN DIỆN Ô CỜ: TRUNG VỊ VÙNG LÕI + ĐIỂM BIÊN + LẤP ĐẦY NGANG (SCANLINE FILL)
    // =========================================================================

    private fun lumOf(p: Int): Int = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    private fun analyzeCellProfile(px: IntArray, r: Int, c: Int): CellProfile {
        val baseX = c * CELL; val baseY = r * CELL

        // 1) Độ sáng quân = trung vị cả vùng lõi 9x9 (14..22 x 12..20), KHÔNG lọc theo solid
        val core = IntArray(81); var n = 0
        for (y in 14..22) {
            val ro = (baseY + y) * BOARD_PX + baseX
            for (x in 12..20) core[n++] = lumOf(px[ro + x])
        }
        core.sort()
        val pieceLum = core[n / 2]

        // Màu nền từ 8 điểm sát 4 góc
        val rs = IntArray(8); val gs = IntArray(8); val bs = IntArray(8)
        var k = 0
        fun take(xx: Int, yy: Int) {
            val p = px[(baseY + yy) * BOARD_PX + baseX + xx]
            rs[k] = Color.red(p); gs[k] = Color.green(p); bs[k] = Color.blue(p); k++
        }
        for (yy in intArrayOf(2, 29)) for (xx in intArrayOf(2, 29)) take(xx, yy)
        for (yy in intArrayOf(3, 28)) for (xx in intArrayOf(3, 28)) take(xx, yy)
        rs.sort(); gs.sort(); bs.sort()
        val bgR = rs[4]; val bgG = gs[4]; val bgB = bs[4]

        // 2) solid = khác nền HOẶC là điểm biên (giúp bắt trọn viền quân trắng trên ô sáng)
        val solid = BooleanArray(CELL * CELL)
        for (y in 4..27) {
            val ro = (baseY + y) * BOARD_PX + baseX
            for (x in 4..27) {
                val p = px[ro + x]
                var fg = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB) > BG_DIFF
                if (!fg && x < 27 && y < 27) {
                    val l = lumOf(p)
                    if (abs(l - lumOf(px[ro + x + 1])) + abs(l - lumOf(px[ro + BOARD_PX + x])) > EDGE_GRAD) fg = true
                }
                if (fg) solid[y * CELL + x] = true
            }
        }

        // 3) Lấp đầy giữa mép trái và mép phải của mỗi hàng (Scanline Fill)
        for (y in 4..27) {
            var mn = -1; var mx = -1
            for (x in 4..27) if (solid[y * CELL + x]) { if (mn < 0) mn = x; mx = x }
            if (mn >= 0 && mx - mn >= 3) {
                for (x in mn..mx) solid[y * CELL + x] = true
            }
        }

        // 4) Tính sil, mass, sumX, highestY, lowestY từ mảng solid đã lấp đầy
        val sil = FloatArray(SIL_N)
        var sumX = 0f; var mass = 0; var highestY = CELL; var lowestY = 0
        for (y in 4..27) {
            for (x in 4..27) {
                if (solid[y * CELL + x]) {
                    sumX += x; mass++
                    if (y < highestY) highestY = y
                    if (y > lowestY) lowestY = y
                    sil[((y - 4) / 2) * SIL + (x - 4) / 2] += 1f
                }
            }
        }

        // Loại bỏ ô trống và chấm tròn gợi ý nước đi (lowestY - highestY < 12)
        if (mass < OCCUPIED_MIN_PX || lowestY - highestY < MIN_SPAN_Y) {
            return CellProfile(false, pieceLum, FloatArray(SIL_N), 0, CELL, 0f, IntArray(5))
        }

        val sliceRanges = arrayOf(4..7, 8..11, 12..15, 16..19, 20..27)
        val sliceWidths = IntArray(5)
        for (s in 0..4) {
            var sumW = 0; var rows = 0
            for (y in sliceRanges[s]) {
                var minX = CELL; var maxX = 0
                for (x in 4..27) {
                    if (solid[y * CELL + x]) { if (x < minX) minX = x; if (x > maxX) maxX = x }
                }
                if (maxX >= minX) sumW += (maxX - minX + 1)
                rows++
            }
            sliceWidths[s] = if (rows > 0) sumW / rows else 0
        }
        val centroidOffset = abs(sumX / mass - 16f)

        normalizeTemplate(sil)
        return CellProfile(true, pieceLum, sil, mass, highestY, centroidOffset, sliceWidths)
    }

    private fun normalizeTemplate(arr: FloatArray) {
        var sum = 0f; for (v in arr) sum += v
        val mean = sum / arr.size
        var sqSum = 0f
        for (i in arr.indices) { arr[i] -= mean; sqSum += arr[i] * arr[i] }
        val norm = sqrt(sqSum).coerceAtLeast(1e-5f)
        for (i in arr.indices) arr[i] /= norm
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until SIL_N) s += a[i] * b[i]
        return s
    }

    private fun templatesReady(): Boolean = shapeTemplates.isNotEmpty()

    private fun hasAllSixPieceTemplates(): Boolean = shapeTemplates.size == PIECE_TYPES.length

    private fun addExemplar(t: Char, sil: FloatArray) {
        val list = shapeTemplates.getOrPut(t) { CopyOnWriteArrayList() }
        if (list.any { dot(it, sil) > 0.97f }) return
        if (list.size >= 8) list.removeAt(0)
        list.add(sil.clone())
    }

    // Lớp 1: Học mẫu từ thế cờ bất kỳ (gọi khi bấm "Lưu & Học" trong bảng Sửa hoặc tự học bồi đắp)
    private fun learnFromPosition(px: IntArray, matrix: Array<CharArray>, whiteBottom: Boolean) {
        val wLums = ArrayList<Int>(); val bLums = ArrayList<Int>()
        for (vr in 0..7) for (vc in 0..7) {
            val sr = if (whiteBottom) vr else 7 - vr
            val sc = if (whiteBottom) vc else 7 - vc
            val ch = matrix[sr][sc]
            if (ch == '1') continue
            val p = analyzeCellProfile(px, vr, vc)
            if (p.isOccupied) {
                addExemplar(ch.uppercaseChar(), p.silhouette)
                if (ch.isUpperCase()) wLums.add(p.avgPieceLum) else bLums.add(p.avgPieceLum)
            }
        }
        if (wLums.isNotEmpty() && bLums.isNotEmpty()) {
            val lw = wLums.average().toInt(); val lb = bLums.average().toInt()
            if (lw - lb >= 20) { lumWhite = lw; lumBlack = lb }
        }
        saveTemplatesToDisk()
    }

    private fun saveTemplatesToDisk() {
        val ed = prefs.edit()
        for (t in PIECE_TYPES) {
            val list = shapeTemplates[t]
            if (list == null) {
                ed.putInt("SHAPE12_N_$t", 0)
            } else {
                ed.putInt("SHAPE12_N_$t", list.size)
                list.forEachIndexed { i, a -> ed.putString("SHAPE12_${t}_$i", a.joinToString(",")) }
            }
        }
        ed.putInt("LUM_W", lumWhite).putInt("LUM_B", lumBlack)
        ed.apply()
    }

    private fun loadTemplatesFromDisk() {
        shapeTemplates.clear()
        for (t in PIECE_TYPES) {
            val n = prefs.getInt("SHAPE12_N_$t", 0)
            val list = CopyOnWriteArrayList<FloatArray>()
            for (i in 0 until n) {
                val s = prefs.getString("SHAPE12_${t}_$i", null) ?: continue
                try {
                    val arr = s.split(",").map { it.toFloat() }.toFloatArray()
                    if (arr.size == SIL_N) list.add(arr)
                } catch (_: Exception) {}
            }
            if (list.isNotEmpty()) shapeTemplates[t] = list
        }
        lumWhite = prefs.getInt("LUM_W", -1); lumBlack = prefs.getInt("LUM_B", -1)
        if (templatesReady()) mainHandler.post { tvFenDebug.text = "Đã nạp mẫu (${shapeTemplates.size}/6 loại quân)" }
        else mainHandler.post { tvFenDebug.text = "Chưa có mẫu: bấm Học Đầu Ván hoặc Sửa -> Lưu & Học" }
    }

    private fun cropBoardPixels(fullBmp: Bitmap): IntArray? {
        val rect = detectedBoardRect ?: return null
        val bLeft = rect.left.toInt().coerceIn(0, fullBmp.width - 64); val bTop = rect.top.toInt().coerceIn(0, fullBmp.height - 64)
        val bSize = rect.width().toInt().coerceIn(64, min(fullBmp.width - bLeft, fullBmp.height - bTop))
        val cropped = Bitmap.createBitmap(fullBmp, bLeft, bTop, bSize, bSize)
        val scaled = Bitmap.createScaledBitmap(cropped, BOARD_PX, BOARD_PX, true)
        if (scaled !== cropped) cropped.recycle()
        val px = IntArray(BOARD_PX * BOARD_PX)
        scaled.getPixels(px, 0, BOARD_PX, 0, 0, BOARD_PX, BOARD_PX)
        scaled.recycle()
        lastBoardPixels = px
        return px
    }

    private fun processLearningCapture(fullBmp: Bitmap) {
        try {
            val px = cropBoardPixels(fullBmp)
            if (px == null) { mainHandler.post { tvStatus.text = "Chưa có khung bàn cờ!" }; return }
            val prof = Array(8) { r -> Array(8) { c -> analyzeCellProfile(px, r, c) } }

            var bad = 0
            for (r in 0..7) for (c in 0..7) {
                val expect = (r <= 1 || r >= 6)
                if (prof[r][c].isOccupied != expect) bad++
            }
            if (bad > 0) {
                mainHandler.post {
                    tvStatus.text = "Chưa ở thế xuất phát ($bad ô lệch) -> Hãy vào Sửa và bấm Lưu & Học!"
                    Toast.makeText(this@OverlayService, "Giữa ván hãy bấm nút [Sửa / Học] -> chỉnh đúng -> [Lưu & Học]!", Toast.LENGTH_LONG).show()
                }
                return
            }

            // Tự động đoán hướng bàn cờ: hàng nào sáng hơn là quân Trắng
            var topLum = 0; var botLum = 0
            for (r in 0..1) for (c in 0..7) topLum += prof[r][c].avgPieceLum
            for (r in 6..7) for (c in 0..7) botLum += prof[r][c].avgPieceLum
            val whiteBottom = botLum >= topLum
            chessOverlayView.isWhiteBottom = whiteBottom
            mainHandler.post { btnSide.text = if (whiteBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen" }

            val lw = max(topLum, botLum) / 16
            val lb = min(topLum, botLum) / 16
            if (lw - lb < 20) {
                mainHandler.post { tvStatus.text = "LỖI: không phân biệt được màu quân (sáng $lw / tối $lb)" }
                return
            }

            val order = if (whiteBottom) "RNBQKBNR" else "RNBKQBNR"
            shapeTemplates.clear()
            for (c in 0..7) {
                for (r in intArrayOf(0, 7)) addExemplar(order[c], prof[r][c].silhouette)
                for (r in intArrayOf(1, 6)) addExemplar('P', prof[r][c].silhouette)
            }
            lumWhite = lw; lumBlack = lb
            saveTemplatesToDisk()

            // Khởi tạo luôn trạng thái theo dõi từ bàn cờ chuẩn
            val initBoard = arrayOf(
                "rnbqkbnr".toCharArray(),
                "pppppppp".toCharArray(),
                CharArray(8) { '1' }, CharArray(8) { '1' }, CharArray(8) { '1' }, CharArray(8) { '1' },
                "PPPPPPPP".toCharArray(),
                "RNBQKBNR".toCharArray()
            )
            trackedState = GameState(initBoard, 'w', setOf('K', 'Q', 'k', 'q'), "-")
            validMatrix = copyMatrix(initBoard)

            mainHandler.post {
                tvStatus.text = "✅ ĐÃ HỌC XONG MẪU (${if (whiteBottom) "Trắng dưới" else "Đen dưới"})!"
                tvFenDebug.text = "Mẫu 12x12 đã lưu (T=$lw / Đ=$lb)"
                Toast.makeText(this@OverlayService, "Đã lưu mẫu & đồng bộ bàn cờ!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            mainHandler.post { tvStatus.text = "Lỗi học mẫu: ${e.message}" }
        } finally {
            fullBmp.recycle(); isLearningMode = false; isProcessing.set(false)
        }
    }

    // =========================================================================
    // XỬ LÝ KHUNG HÌNH: KẾT HỢP THEO DÕI NƯỚC ĐI HỢP LỆ (LỚP 3) & NHẬN DIỆN HÌNH DÁNG
    // =========================================================================

    private fun processCapturedBitmap(fullBmp: Bitmap) {
        try {
            val px = cropBoardPixels(fullBmp)
            if (px == null) { mainHandler.post { tvStatus.text = "⚠️ Hãy chỉnh khung bàn cờ trước" }; return }
            val gen = stateGeneration.get()
            val whiteBottom = chessOverlayView.isWhiteBottom
            val myColor = if (whiteBottom) 'w' else 'b'

            val profiles = Array(8) { r -> Array(8) { c -> analyzeCellProfile(px, r, c) } }

            val lums = ArrayList<Int>()
            for (r in 0..7) for (c in 0..7) if (profiles[r][c].isOccupied) lums.add(profiles[r][c].avgPieceLum)
            val threshold = pieceLumThreshold(lums)
            if (threshold == null) {
                scheduleRetry("thiếu tương phản màu quân"); return
            }

            // Quan sát màu sắc và mật độ từng ô theo hệ tọa độ chuẩn (hàng 0 = rank 8, hàng 7 = rank 1)
            // obs[r][c]: '1' = trống, 'W' = quân trắng, 'B' = quân đen
            val obs = Array(8) { CharArray(8) { '1' } }
            val rawMatrix = Array(8) { CharArray(8) { '1' } }
            val highConfCells = ArrayList<Triple<Int, Int, Char>>()

            for (vr in 0..7) {
                for (vc in 0..7) {
                    val p = profiles[vr][vc]
                    if (!p.isOccupied) continue
                    val isW = p.avgPieceLum >= threshold
                    val sr = if (whiteBottom) vr else 7 - vr
                    val sc = if (whiteBottom) vc else 7 - vc
                    obs[sr][sc] = if (isW) 'W' else 'B'

                    val (type, score) = classifyPieceWithScore(vr, p)
                    val ch = if (isW) type.uppercaseChar() else type.lowercaseChar()
                    rawMatrix[sr][sc] = ch
                    if (score >= 0.82f) highConfCells.add(Triple(vr, vc, type))
                }
            }

            // Hiển thị ngay kết quả đọc thô lên tvFenDebug để dễ kiểm chứng
            val rawReadFen = buildFenBoardString(rawMatrix)
            mainHandler.post { tvFenDebug.text = "Đọc: $rawReadFen" }

            // Khử rung trên ma trận quan sát Trắng/Đen + đọc thô (2 khung liên tiếp khớp nhau)
            val obsKey = obs.joinToString("/") { String(it) }
            if (obsKey != pendingObsKey) {
                pendingObsKey = obsKey
                consecutiveMatchCount = 1
                scheduleRetry("đang lấy nét..."); return
            }
            consecutiveMatchCount++
            if (consecutiveMatchCount < 2) { scheduleRetry("đang xác nhận khung..."); return }

            // BƯỚC 1: THỬ KHỚP BẰNG THEO DÕI NƯỚC ĐI HỢP LỆ (LỚP 3 - CHÍNH XÁC TUYỆT ĐỐI)
            var resolvedState: GameState? = null
            val currentTracked = trackedState
            if (currentTracked != null && forceTurnSide == null) {
                resolvedState = matchByLegalMoves(currentTracked, obs, rawMatrix)
            }

            // BƯỚC 2: NẾU CHƯA CÓ TRACKED STATE HOẶC BỊ LỠ NHIỀU NƯỚC -> DÙNG NHẬN DIỆN ĐẦY ĐỦ
            if (resolvedState == null) {
                val validationError = validatePositionStrict(rawMatrix)
                if (validationError != null) {
                    scheduleRetry(validationError); return
                }
                val forced = forceTurnSide
                var turn = forced ?: if (rawReadFen == START_BOARD) 'w' else myColor
                if (isKingAttacked(rawMatrix, white = (turn == 'b'))) {
                    val other = if (turn == 'w') 'b' else 'w'
                    if (forced == null && !isKingAttacked(rawMatrix, white = (other == 'b'))) turn = other
                    else { scheduleRetry("vua bị chiếu sai lượt"); return }
                }
                if (forced != null) {
                    forceTurnSide = null
                    mainHandler.post { btnTurn.text = "🔄 Lượt: Auto" }
                }
                val castles = inferCastlingFromMatrix(rawMatrix, currentTracked?.castles)
                resolvedState = GameState(copyMatrix(rawMatrix), turn, castles, "-")

                // Lớp 2: Tự học bồi đắp các mẫu có độ tin cậy cao từ thế cờ hợp lệ
                for ((vr, vc, type) in highConfCells) {
                    addExemplar(type, profiles[vr][vc].silhouette)
                }
            }

            debounceRetries = 0
            trackedState = resolvedState
            validMatrix = copyMatrix(resolvedState.board)
            lastDetectedTurn = resolvedState.turn

            val boardFen = buildFenBoardString(resolvedState.board)
            val castling = "KQkq".filter { it in resolvedState.castles }.ifEmpty { "-" }
            val ep = resolvedState.ep
            val activeTurn = resolvedState.turn

            mainHandler.post { tvFenDebug.text = "FEN: $boardFen $activeTurn $castling $ep" }

            val stateKey = "$boardFen|$activeTurn|$castling|$ep"

            if (activeTurn != myColor) {
                mainHandler.post { chessOverlayView.setMove(null); tvStatus.text = "⏳ Đợi đối thủ đi..." }
                return
            }

            if (stateKey == lastCompletedStateKey && chessOverlayView.hasActiveArrow()) return

            val fullFen = "$boardFen $activeTurn $castling $ep 0 1"
            val cachedMove = moveCache[fullFen]
            if (cachedMove != null) {
                lastCompletedStateKey = stateKey
                mainHandler.post { chessOverlayView.setMove(cachedMove); tvStatus.text = "⚡ Đi: ${formatMoveText(cachedMove)}" }
                return
            }

            mainHandler.post { chessOverlayView.setMove(null); tvStatus.text = "⚡ Đang tính..." }
            val bestMove = raceBothStockfishServers(fullFen)

            val apiErr = if (bestMove == null) apiErrorSummary() else ""
            if (stateGeneration.get() != gen) return

            mainHandler.post {
                if (bestMove != null && bestMove.length >= 4) {
                    if (moveCache.size > 400) moveCache.clear()
                    moveCache[fullFen] = bestMove
                    lastCompletedStateKey = stateKey
                    chessOverlayView.setMove(bestMove)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(bestMove)}"
                } else {
                    tvStatus.text = "⏳ Lỗi API: $apiErr"
                    tvFenDebug.text = fullFen
                }
            }
        } catch (e: Exception) {
            mainHandler.post { tvStatus.text = "Lỗi: ${e.message}" }
        } finally {
            fullBmp.recycle(); isProcessing.set(false)
        }
    }

    private fun copyMatrix(m: Array<CharArray>): Array<CharArray> = Array(8) { r -> m[r].clone() }

    private fun pieceLumThreshold(lums: List<Int>): Int? {
        if (lumWhite >= 0 && lumBlack >= 0 && lumWhite - lumBlack >= 20) return (lumWhite + lumBlack) / 2
        if (lums.size < 2) return null
        val lo0 = lums.minOrNull() ?: return null
        val hi0 = lums.maxOrNull() ?: return null
        if (hi0 - lo0 < 20) return null
        var t = (lo0 + hi0) / 2f
        repeat(10) {
            val lo = lums.filter { it < t }
            val hi = lums.filter { it >= t }
            if (lo.isEmpty() || hi.isEmpty()) return t.toInt()
            t = (lo.average().toFloat() + hi.average().toFloat()) / 2f
        }
        return t.toInt()
    }

    private fun classifyPieceWithScore(r: Int, profile: CellProfile): Pair<Char, Float> {
        if (templatesReady()) {
            var best = 'P'; var bestS = -2f; var second = -2f
            for ((type, list) in shapeTemplates) {
                var s = -2f
                for (tpl in list) {
                    val d = dot(profile.silhouette, tpl)
                    if (d > s) s = d
                }
                if (s > bestS) { second = bestS; bestS = s; best = type } else if (s > second) second = s
            }
            val minReq = if (hasAllSixPieceTemplates()) MATCH_MIN_FULL else MATCH_MIN_PARTIAL
            if (bestS >= minReq && bestS - second >= MATCH_MARGIN) return best to bestS
        }
        return classifyByGeometry(r, profile) to 0.4f
    }

    private fun classifyByGeometry(r: Int, profile: CellProfile): Char {
        val s0 = profile.sliceWidths[0]; val s1 = profile.sliceWidths[1]; val s2 = profile.sliceWidths[2]
        if (profile.centroidOffset > 1.8f) return 'N'
        if (r in 1..6 && profile.mass < 210 && profile.highestY >= 8 && s0 <= 4 && s1 <= 9) return 'P'
        if (s0 <= 5 && s1 >= 9 && s1 >= s2) return 'R'
        if (profile.highestY <= 6 && s0 >= 6) return if (s0 >= 9 || s1 >= 11) 'Q' else 'K'
        if (s1 < s2 && s2 >= 8) return 'B'
        return if (r == 0 || r == 7) 'R' else 'P'
    }

    // =========================================================================
    // LỚP 3: BỘ MÁY THEO DÕI NƯỚC ĐI HỢP LỆ (LEGAL MOVE TRACKER)
    // =========================================================================

    private fun matchesOccupancy(board: Array<CharArray>, obs: Array<CharArray>): Boolean {
        for (r in 0..7) for (c in 0..7) {
            val ch = board[r][c]
            val exp = if (ch == '1') '1' else if (ch.isUpperCase()) 'W' else 'B'
            if (exp != obs[r][c]) return false
        }
        return true
    }

    private fun matchByLegalMoves(prev: GameState, obs: Array<CharArray>, rawRead: Array<CharArray>): GameState? {
        // 0-ply: Bàn cờ chưa hề thay đổi
        if (matchesOccupancy(prev.board, obs)) return prev

        // 1-ply: Tìm trong các nước đi hợp lệ của bên đang có lượt
        val onePly = generateLegalStates(prev).filter { matchesOccupancy(it.board, obs) }
        if (onePly.size == 1) return onePly[0]
        if (onePly.size > 1) return pickPromotionMatch(onePly, rawRead)

        // 2-plies: Mình vừa đi xong và Bot/Đối thủ phản hồi ngay lập tức
        val twoPly = ArrayList<GameState>()
        for (s1 in generateLegalStates(prev)) {
            for (s2 in generateLegalStates(s1)) {
                if (matchesOccupancy(s2.board, obs)) twoPly.add(s2)
            }
        }
        if (twoPly.size == 1) return twoPly[0]
        if (twoPly.size > 1) return pickPromotionMatch(twoPly, rawRead)

        return null
    }

    private fun pickPromotionMatch(candidates: List<GameState>, rawRead: Array<CharArray>): GameState {
        for (cand in candidates) {
            var match = true
            for (r in intArrayOf(0, 7)) for (c in 0..7) {
                if (cand.board[r][c] != rawRead[r][c]) match = false
            }
            if (match) return cand
        }
        return candidates[0] // Mặc định ưu tiên phong Hậu (được sinh đầu tiên)
    }

    private fun generateLegalStates(state: GameState): List<GameState> {
        val res = ArrayList<GameState>()
        val w = state.turn == 'w'
        val b = state.board
        val nextTurn = if (w) 'b' else 'w'

        fun tryAdd(nb: Array<CharArray>, newCastles: Set<Char>, newEp: String) {
            if (!isKingAttacked(nb, w)) {
                res.add(GameState(nb, nextTurn, newCastles, newEp))
            }
        }

        for (r in 0..7) for (c in 0..7) {
            val ch = b[r][c]
            if (ch == '1' || ch.isUpperCase() != w) continue
            val u = ch.uppercaseChar()

            if (u == 'P') {
                val dir = if (w) -1 else 1
                val startR = if (w) 6 else 1
                val promoR = if (w) 0 else 7
                val nr = r + dir
                if (nr in 0..7 && b[nr][c] == '1') {
                    if (nr == promoR) {
                        for (p in charArrayOf('Q', 'R', 'B', 'N')) {
                            val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][c] = if (w) p else p.lowercaseChar()
                            tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                        }
                    } else {
                        val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][c] = ch
                        tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                        val nr2 = r + dir * 2
                        if (r == startR && b[nr2][c] == '1') {
                            val nb2 = copyMatrix(b); nb2[r][c] = '1'; nb2[nr2][c] = ch
                            val epSq = "${'a' + c}${8 - nr}"
                            tryAdd(nb2, updateCastlesAfterMove(nb2, state.castles), epSq)
                        }
                    }
                }
                for (dc in intArrayOf(-1, 1)) {
                    val nc = c + dc
                    if (nr in 0..7 && nc in 0..7) {
                        val target = b[nr][nc]
                        val sqName = "${'a' + nc}${8 - nr}"
                        if (target != '1' && target.isUpperCase() != w) {
                            if (nr == promoR) {
                                for (p in charArrayOf('Q', 'R', 'B', 'N')) {
                                    val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = if (w) p else p.lowercaseChar()
                                    tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                                }
                            } else {
                                val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = ch
                                tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                            }
                        } else if (sqName == state.ep) {
                            val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = ch; nb[r][nc] = '1'
                            tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                        }
                    }
                }
            } else if (u == 'N') {
                val jumps = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
                for ((dr, dc) in jumps) {
                    val nr = r + dr; val nc = c + dc
                    if (nr in 0..7 && nc in 0..7 && (b[nr][nc] == '1' || b[nr][nc].isUpperCase() != w)) {
                        val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = ch
                        tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                    }
                }
            } else if (u == 'K') {
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr; val nc = c + dc
                    if (nr in 0..7 && nc in 0..7 && (b[nr][nc] == '1' || b[nr][nc].isUpperCase() != w)) {
                        val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = ch
                        tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                    }
                }
                // Nhập thành
                val kr = if (w) 7 else 0
                if (r == kr && c == 4 && !isKingAttacked(b, w)) {
                    val ks = if (w) 'K' else 'k'
                    val qs = if (w) 'Q' else 'q'
                    val rookCh = if (w) 'R' else 'r'
                    if (ks in state.castles && b[kr][5] == '1' && b[kr][6] == '1' && b[kr][7] == rookCh) {
                        val mid = copyMatrix(b); mid[kr][4] = '1'; mid[kr][5] = ch
                        if (!isKingAttacked(mid, w)) {
                            val nb = copyMatrix(b); nb[kr][4] = '1'; nb[kr][6] = ch; nb[kr][7] = '1'; nb[kr][5] = rookCh
                            tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                        }
                    }
                    if (qs in state.castles && b[kr][3] == '1' && b[kr][2] == '1' && b[kr][1] == '1' && b[kr][0] == rookCh) {
                        val mid = copyMatrix(b); mid[kr][4] = '1'; mid[kr][3] = ch
                        if (!isKingAttacked(mid, w)) {
                            val nb = copyMatrix(b); nb[kr][4] = '1'; nb[kr][2] = ch; nb[kr][0] = '1'; nb[kr][3] = rookCh
                            tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                        }
                    }
                }
            } else {
                val dirs = when (u) {
                    'B' -> arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
                    'R' -> arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
                    else -> arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
                }
                for ((dr, dc) in dirs) {
                    var nr = r + dr; var nc = c + dc
                    while (nr in 0..7 && nc in 0..7) {
                        val t = b[nr][nc]
                        if (t == '1') {
                            val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = ch
                            tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                        } else {
                            if (t.isUpperCase() != w) {
                                val nb = copyMatrix(b); nb[r][c] = '1'; nb[nr][nc] = ch
                                tryAdd(nb, updateCastlesAfterMove(nb, state.castles), "-")
                            }
                            break
                        }
                        nr += dr; nc += dc
                    }
                }
            }
        }
        return res
    }

    private fun updateCastlesAfterMove(m: Array<CharArray>, prev: Set<Char>): Set<Char> {
        val s = prev.toMutableSet()
        if (m[7][4] != 'K') { s.remove('K'); s.remove('Q') }
        if (m[7][7] != 'R') s.remove('K')
        if (m[7][0] != 'R') s.remove('Q')
        if (m[0][4] != 'k') { s.remove('k'); s.remove('q') }
        if (m[0][7] != 'r') s.remove('k')
        if (m[0][0] != 'r') s.remove('q')
        return s
    }

    private fun inferCastlingFromMatrix(m: Array<CharArray>, prev: Set<Char>?): Set<Char> {
        if (buildFenBoardString(m) == START_BOARD) return setOf('K', 'Q', 'k', 'q')
        val base = prev ?: setOf('K', 'Q', 'k', 'q')
        return updateCastlesAfterMove(m, base)
    }

    // =========================================================================
    // KIỂM TRA THẾ CỜ & SỬA TAY
    // =========================================================================

    private fun validatePositionStrict(m: Array<CharArray>): String? {
        val wCnt = IntArray(6); val bCnt = IntArray(6)
        var wkr = -1; var wkc = -1; var bkr = -1; var bkc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                val ch = m[r][c]
                if (ch == '1') continue
                val idx = PIECE_TYPES.indexOf(ch.uppercaseChar())
                if (idx < 0) return "Ký tự lạ"
                if (ch.isUpperCase()) wCnt[idx]++ else bCnt[idx]++
                if ((r == 0 || r == 7) && (ch == 'P' || ch == 'p')) return "Tốt ở hàng cuối"
                if (ch == 'K') { wkr = r; wkc = c }
                if (ch == 'k') { bkr = r; bkc = c }
            }
        }
        if (wCnt[5] != 1 || bCnt[5] != 1) return "Vua (${wCnt[5]}/${bCnt[5]})"
        if (max(abs(wkr - bkr), abs(wkc - bkc)) <= 1) return "Hai Vua sát nhau"
        for (cnt in listOf(wCnt, bCnt)) {
            val total = cnt.sum()
            if (total > 16) return "Quá 16 quân"
            if (cnt[0] > 8) return "Quá 8 Tốt"
            val extra = max(0, cnt[4] - 1) + max(0, cnt[3] - 2) + max(0, cnt[2] - 2) + max(0, cnt[1] - 2)
            if (extra > 8 - cnt[0]) return "Thừa quân so với số Tốt"
        }
        return null
    }

    private fun isKingAttacked(m: Array<CharArray>, white: Boolean): Boolean {
        val kch = if (white) 'K' else 'k'
        var kr = -1; var kc = -1
        for (r in 0..7) for (c in 0..7) if (m[r][c] == kch) { kr = r; kc = c }
        if (kr < 0) return false

        val ePawn = if (white) 'p' else 'P'; val eKnight = if (white) 'n' else 'N'
        val eBishop = if (white) 'b' else 'B'; val eRook = if (white) 'r' else 'R'
        val eQueen = if (white) 'q' else 'Q'; val eKing = if (white) 'k' else 'K'

        val pr = if (white) kr - 1 else kr + 1
        if (pr in 0..7) for (dc in intArrayOf(-1, 1)) { val c = kc + dc; if (c in 0..7 && m[pr][c] == ePawn) return true }

        val knight = arrayOf(intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1))
        for (d in knight) { val r = kr + d[0]; val c = kc + d[1]; if (r in 0..7 && c in 0..7 && m[r][c] == eKnight) return true }

        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val diag = dr != 0 && dc != 0
            var r = kr + dr; var c = kc + dc; var first = true
            while (r in 0..7 && c in 0..7) {
                val ch = m[r][c]
                if (ch != '1') {
                    if (ch == eQueen || (diag && ch == eBishop) || (!diag && ch == eRook)) return true
                    if (first && ch == eKing) return true
                    break
                }
                first = false; r += dr; c += dc
            }
        }
        return false
    }

    private fun forceEvaluateMatrix() {
        val src = validMatrix ?: return
        val m = copyMatrix(src)

        val err = validatePositionStrict(m)
        if (err != null) { tvStatus.text = "Bàn cờ sửa tay không hợp lệ: $err"; return }

        val myColor = if (chessOverlayView.isWhiteBottom) 'w' else 'b'
        val forced = forceTurnSide
        var turn = forced ?: myColor
        if (isKingAttacked(m, white = (turn == 'b'))) {
            val other = if (turn == 'w') 'b' else 'w'
            if (forced == null && !isKingAttacked(m, white = (other == 'b'))) turn = other
            else { tvStatus.text = "Bàn cờ sửa tay không hợp lệ (vua bị chiếu sai lượt)"; return }
        }

        forceTurnSide = null; btnTurn.text = "🔄 Lượt: Auto"
        lastDetectedTurn = turn
        val castles = inferCastlingFromMatrix(m, trackedState?.castles)
        trackedState = GameState(copyMatrix(m), turn, castles, "-")
        pendingObsKey = ""; consecutiveMatchCount = 0

        val boardFen = buildFenBoardString(m)
        val castlingStr = "KQkq".filter { it in castles }.ifEmpty { "-" }
        tvFenDebug.text = "FEN (Sửa tay): $boardFen $turn $castlingStr"
        val stateKey = "$boardFen|$turn|EDITED"
        val gen = stateGeneration.incrementAndGet()
        val fullFen = "$boardFen $turn $castlingStr - 0 1"
        tvStatus.text = "⚡ Đang tính nước theo bàn cờ sửa tay..."

        workerExecutor.execute {
            val move = moveCache[fullFen] ?: raceBothStockfishServers(fullFen)
            val apiErr = if (move == null) apiErrorSummary() else ""
            if (stateGeneration.get() != gen) return@execute
            mainHandler.post {
                if (move != null && move.length >= 4) {
                    if (moveCache.size > 400) moveCache.clear()
                    moveCache[fullFen] = move; lastCompletedStateKey = stateKey; chessOverlayView.setMove(move)
                    tvStatus.text = "✅ Đề xuất: ${formatMoveText(move)}"
                } else { tvStatus.text = "⏳ Lỗi API: $apiErr"; tvFenDebug.text = fullFen }
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

    // =========================================================================
    // API STOCKFISH
    // =========================================================================

    private val apiErrors = ConcurrentHashMap<String, String>()

    private fun noteApiError(name: String, msg: String) {
        apiErrors[name] = msg
        Log.w("ChessAPI", "$name: $msg")
    }

    private fun apiErrorSummary(): String {
        val parts = apiErrors.entries.filter { !(it.key == "lichess" && it.value == "HTTP 404") }
        return if (parts.isEmpty()) "không phản hồi (quá ${API_TOTAL_MS / 1000.0}s)" else parts.joinToString(" | ") { "${it.key}: ${it.value}" }
    }

    private fun raceBothStockfishServers(fen: String): String? {
        apiErrors.clear()
        val tasks = listOf(
            Callable { fetchFromChessApi(fen) ?: throw RuntimeException("No move") },
            Callable { fetchFromStockfishOnline(fen) ?: throw RuntimeException("No move") },
            Callable { fetchFromLichessCloud(fen) ?: throw RuntimeException("No move") }
        )
        return try { apiRacePool.invokeAny(tasks, API_TOTAL_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
    }

    private fun httpRequest(name: String, urlStr: String, method: String, body: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = API_TIMEOUT_MS; readTimeout = API_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)"); setRequestProperty("Accept", "application/json")
                if (body != null) { setRequestProperty("Content-Type", "application/json"); doOutput = true }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 200) conn.inputStream.bufferedReader().use { it.readText() }
            else { noteApiError(name, "HTTP $code"); null }
        } catch (e: Exception) {
            noteApiError(name, "${e.javaClass.simpleName}: ${e.message}".take(80)); null
        } finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }

    private fun fetchFromChessApi(fen: String): String? {
        val body = JSONObject().put("fen", fen).put("depth", 11).toString()
        val resp = httpRequest("chess-api", "https://chess-api.com/v1", "POST", body) ?: return null
        return try {
            val move = JSONObject(resp).optString("move", "")
            if (move.length >= 4) move.substring(0, min(move.length, 5))
            else { noteApiError("chess-api", "không có nước: ${resp.take(60)}"); null }
        } catch (_: Exception) { noteApiError("chess-api", "JSON lỗi: ${resp.take(60)}"); null }
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        val url = "https://stockfish.online/api/s/v2.php?fen=${URLEncoder.encode(fen, "UTF-8")}&depth=11"
        val resp = httpRequest("stockfish.online", url, "GET", null) ?: return null
        return try {
            val json = JSONObject(resp)
            if (!json.optBoolean("success", true)) { noteApiError("stockfish.online", "success=false: ${json.optString("data", resp).take(50)}"); return null }
            val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
            val p = raw.trim().split(" "); val c = if (p.size >= 2) p[1] else p[0]
            if (c.length >= 4 && c != "(none)") c.substring(0, min(c.length, 5))
            else { noteApiError("stockfish.online", "không có nước: ${resp.take(60)}"); null }
        } catch (_: Exception) { noteApiError("stockfish.online", "JSON lỗi: ${resp.take(60)}"); null }
    }

    private fun fetchFromLichessCloud(fen: String): String? {
        val url = "https://lichess.org/api/cloud-eval?fen=${URLEncoder.encode(fen, "UTF-8")}&multiPv=1"
        val resp = httpRequest("lichess", url, "GET", null) ?: return null
        return try {
            val pvs = JSONObject(resp).optJSONArray("pvs")
            val moves = pvs?.optJSONObject(0)?.optString("moves", "") ?: ""
            val first = moves.trim().split(" ").firstOrNull() ?: ""
            if (first.length >= 4) first.substring(0, min(first.length, 5)) else null
        } catch (_: Exception) { noteApiError("lichess", "JSON lỗi"); null }
    }

    private fun formatMoveText(uci: String): String {
        val base = "${uci.substring(0, 2).uppercase()} ➔ ${uci.substring(2, 4).uppercase()}"
        return if (uci.length >= 5) "$base (Phong ${uci[4].uppercaseChar()})" else base
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel("chess_overlay_channel", "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = Notification.Builder(this, "chess_overlay_channel").setContentTitle("Chess Pro v10.4").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false; mainHandler.removeCallbacksAndMessages(null); bgThread?.quitSafely()
        workerExecutor.shutdownNow(); apiRacePool.shutdownNow(); cleanupMediaProjection()
        try { if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView) } catch (_: Exception) {}
        try { rootContainer?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { pieceEditorPopup?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }
}
