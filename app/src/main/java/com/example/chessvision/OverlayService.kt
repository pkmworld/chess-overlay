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
        private const val CELL = 32              // mỗi ô = 32px trên ảnh bàn cờ chuẩn hóa 256x256
        private const val BOARD_PX = 256
        private const val SIL = 12               // lưới hình bóng 12x12, mỗi bin đúng 2x2 pixel (vùng 4..27)
        private const val SIL_N = SIL * SIL      // 144
        private const val BG_DIFF = 70           // ngưỡng khác nền: |dR|+|dG|+|dB|
        private const val OCCUPIED_MIN_PX = 70   // số pixel "khác nền" tối thiểu để coi ô có quân
        private const val MATCH_MIN = 0.60f      // điểm ZNCC tối thiểu
        private const val MATCH_MARGIN = 0.015f  // chênh lệch tối thiểu giữa loại quân tốt nhất và nhì
        private const val MAX_RETRIES = 8        // số lần quét lại tối đa khi chưa ổn định (~800ms)
        private const val NUDGE_COUNT = 8
        private const val START_BOARD = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        private const val PIECE_TYPES = "PNBRQK"
        private const val API_TIMEOUT_MS = 7000
        private const val API_TOTAL_MS = 8000L
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
    @Volatile private var captureToken = 0L          // chỉ tăng trên luồng chính, chống nudge/timeout của lần chụp cũ

    @Volatile private var detectedBoardRect: RectF? = null
    @Volatile private var validMatrix: Array<CharArray>? = null
    private var editMatrix: Array<CharArray>? = null // bản sao dùng cho bảng Sửa (chỉ luồng chính đụng vào)

    // Trạng thái theo dõi ván cờ
    @Volatile private var lastDetectedTurn: Char = 'w'
    @Volatile private var previousValidMatrix: Array<CharArray>? = null
    @Volatile private var lastEnPassant: String = "-"
    @Volatile private var castleRights: Set<Char> = setOf('K', 'Q', 'k', 'q')

    // Khử rung
    @Volatile private var pendingStableMatrix: Array<CharArray>? = null
    @Volatile private var consecutiveMatchCount = 0
    @Volatile private var debounceRetries = 0

    private val stateGeneration = AtomicLong(0L)     // tăng khi người dùng đổi cấu hình, kết quả API cũ sẽ bị bỏ
    @Volatile private var lastCompletedStateKey = ""
    @Volatile private var forceTurnSide: Char? = null // dùng 1 lần rồi tự về null

    private val moveCache = ConcurrentHashMap<String, String>()

    // Mẫu hình bóng 12x12 đã chuẩn hóa ZNCC, gộp Trắng/Đen cùng loại (khóa = chữ HOA)
    private val shapeTemplates = ConcurrentHashMap<Char, List<FloatArray>>()
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
            mainHandler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("VisionPro10_1Prefs", Context.MODE_PRIVATE)
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
            // Tạo reader mới và gắn surface TRƯỚC, rồi mới đóng reader cũ
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

                // Android 14 (target 34): bắt buộc đăng ký callback trước khi tạo VirtualDisplay
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

        tvStatus = TextView(this).apply { text = "Chess Pro v10.2 • Sẵn sàng"; setTextColor(Color.parseColor("#00E676")); textSize = 11.5f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 8) }
        tvFenDebug = TextView(this).apply { text = "Chưa quét bàn cờ"; setTextColor(Color.parseColor("#f1c40f")); textSize = 9.5f; gravity = Gravity.CENTER; maxLines = 1; setPadding(0, 0, 0, 12) }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        btnSide = createButton("⚪ Phe: Trắng", "#2980b9") {
            chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
            btnSide.text = if (chessOverlayView.isWhiteBottom) "⚪ Phe: Trắng" else "⚫ Phe: Đen"
            resetState()
        }
        btnTurn = createButton("🔄 Lượt", "#2980b9") {
            forceTurnSide = when (forceTurnSide) { null -> if (chessOverlayView.isWhiteBottom) 'w' else 'b'; 'w' -> 'b'; else -> null }
            btnTurn.text = when (forceTurnSide) { 'w' -> "⚡ Trắng"; 'b' -> "⚡ Đen"; else -> "🔄 Lượt" }
            resetState()
        }
        val btnCalib = createButton("🎯 Khung", "#8e44ad") { mainControlPanel.visibility = View.GONE; calibControlPanel.visibility = View.VISIBLE; chessOverlayView.isCalibrationMode = true; chessOverlayView.invalidate() }
        val btnLearn = createButton("📸 Học Mẫu", "#f39c12") {
            isAutoMode = false; mainHandler.removeCallbacks(autoLoopRunnable); btnAuto.text = "▶ Auto"
            isLearningMode = true; requestCleanCapture(manualTrigger = true)
        }
        row1.addView(btnSide); row1.addView(btnTurn); row1.addView(btnCalib); row1.addView(btnLearn)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val btnEdit = createButton("👁️ Sửa", "#d35400") {
            val vm = validMatrix
            if (vm == null) {
                Toast.makeText(this@OverlayService, "Hãy quét 1 lần trước khi sửa!", Toast.LENGTH_SHORT).show()
            } else {
                isAutoMode = false; mainHandler.removeCallbacks(autoLoopRunnable); btnAuto.text = "▶ Auto"
                editMatrix = Array(8) { r -> vm[r].clone() }
                mainControlPanel.visibility = View.GONE; manualEditorPanel.visibility = View.VISIBLE; buildEditorGridUI()
            }
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

        // BẢNG SỬA QUÂN
        manualEditorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(255, 35, 35, 45)); setStroke(4, Color.parseColor("#d35400")) }; setPadding(16, 24, 16, 24); visibility = View.GONE }
        val tvEditTitle = TextView(this).apply { text = "SỬA BÀN CỜ"; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0,0,0,16) }
        editorGridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,0,0,24) }
        val eRowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        eRowActions.addView(createButton("❌ HỦY", "#c0392b") {
            editMatrix = null
            manualEditorPanel.visibility = View.GONE; mainControlPanel.visibility = View.VISIBLE
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
        pendingStableMatrix = null
        consecutiveMatchCount = 0
        chessOverlayView.setMove(null)
    }

    // =========================================================================
    // CHỤP KHUNG HÌNH
    // =========================================================================

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@OnImageAvailableListener
        try {
            if (waitingForCleanFrame && SystemClock.uptimeMillis() >= cleanFrameMinTimestamp) {
                if (isProcessing.compareAndSet(false, true)) {
                    waitingForCleanFrame = false
                    val exactBmp = extractExactScreenBitmap(img)
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

    // Quy trình: ẩn mũi tên -> đợi khung hình đến SAU mốc +50ms -> bơm khung hình (màn hình tĩnh sẽ không phát frame)
    private fun requestCleanCapture(manualTrigger: Boolean) {
        if (manualTrigger) debounceRetries = 0
        if (isProcessing.get() || waitingForCleanFrame || chessOverlayView.isCalibrationMode) return
        mainHandler.post {
            if (isProcessing.get() || waitingForCleanFrame) return@post
            val token = ++captureToken

            if (chessOverlayView.hasActiveArrow()) {
                chessOverlayView.visibility = View.INVISIBLE
                cleanFrameMinTimestamp = SystemClock.uptimeMillis() + 50L
            } else {
                cleanFrameMinTimestamp = SystemClock.uptimeMillis()
            }
            waitingForCleanFrame = true

            // Nudge trên luồng UI (view chỉ được đụng từ luồng chính)
            val nudge = object : Runnable {
                var count = 0
                override fun run() {
                    if (token == captureToken && waitingForCleanFrame && count < NUDGE_COUNT) {
                        rootContainer?.let { it.alpha = if (it.alpha < 0.99f) 1.0f else 0.985f }
                        count++
                        mainHandler.postDelayed(this, 60)
                    }
                }
            }
            mainHandler.postDelayed(nudge, 60)

            // Timeout 500ms: nudge đủ thời gian chạy
            mainHandler.postDelayed({
                if (token == captureToken && waitingForCleanFrame && !isProcessing.get()) {
                    waitingForCleanFrame = false
                    chessOverlayView.visibility = View.VISIBLE
                    if (isLearningMode) {
                        tvStatus.text = "Không nhận được khung hình, vui lòng thử lại"
                        isLearningMode = false
                    }
                }
            }, 500L)
        }
    }

    // Quét lại sau 100ms, tối đa MAX_RETRIES lần
    private fun scheduleRetry() {
        if (debounceRetries < MAX_RETRIES) {
            debounceRetries++
            mainHandler.postDelayed({ requestCleanCapture(false) }, 100L)
        } else {
            debounceRetries = 0
            pendingStableMatrix = null
            consecutiveMatchCount = 0
            mainHandler.post { tvStatus.text = "Không ổn định, hãy quét lại" }
        }
    }

    // =========================================================================
    // NHẬN DIỆN Ô CỜ
    // =========================================================================

    private fun lumOf(p: Int): Int = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    private fun analyzeCellProfile(px: IntArray, r: Int, c: Int): CellProfile {
        val baseX = c * CELL; val baseY = r * CELL

        // Màu nền ô: trung vị RGB của 8 điểm sát 4 góc (chịu được 1-2 điểm dính chữ tọa độ)
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

        val solid = BooleanArray(CELL * CELL)
        val sil = FloatArray(SIL_N)
        var sumX = 0f; var mass = 0; var highestY = CELL

        for (y in 4..27) {
            val rowOffset = (baseY + y) * BOARD_PX + baseX
            for (x in 4..27) {
                val p = px[rowOffset + x]
                val d = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)
                if (d > BG_DIFF) {
                    solid[y * CELL + x] = true
                    sumX += x; mass++
                    if (y < highestY) highestY = y
                    // mỗi bin đúng 2x2 pixel: (x-4)/2 và (y-4)/2 nằm trong 0..11
                    sil[((y - 4) / 2) * SIL + (x - 4) / 2] += 1f
                }
            }
        }

        // Có quân khi đủ số pixel khác nền (chấm gợi ý nhỏ, vân gỗ, highlight đồng màu sẽ không đạt)
        if (mass < OCCUPIED_MIN_PX) return CellProfile(false, 0, FloatArray(SIL_N), 0, CELL, 0f, IntArray(5))

        // Lát cắt chiều ngang (dùng cho heuristic dự phòng khi chưa học mẫu)
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

        // Độ sáng thân quân: vùng lõi, loại trừ nền
        var lumSum = 0; var lumCnt = 0
        for (y in 14..22) for (x in 12..20) {
            if (solid[y * CELL + x]) { lumSum += lumOf(px[(baseY + y) * BOARD_PX + baseX + x]); lumCnt++ }
        }
        if (lumCnt < 6) {
            lumSum = 0; lumCnt = 0
            for (y in 4..27) for (x in 4..27) {
                if (solid[y * CELL + x]) { lumSum += lumOf(px[(baseY + y) * BOARD_PX + baseX + x]); lumCnt++ }
            }
        }
        val pieceLum = if (lumCnt > 0) lumSum / lumCnt else 128

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

    private fun templatesReady(): Boolean = shapeTemplates.size == PIECE_TYPES.length

    private fun saveTemplatesToDisk() {
        val ed = prefs.edit()
        for ((t, list) in shapeTemplates) {
            ed.putInt("SHAPE12_N_$t", list.size)
            list.forEachIndexed { i, a -> ed.putString("SHAPE12_${t}_$i", a.joinToString(",")) }
        }
        ed.putInt("LUM_W", lumWhite).putInt("LUM_B", lumBlack)
        ed.apply()
    }

    // Mẫu cũ (256 phần tử, khóa SHAPE_x) bị bỏ qua tự động vì khóa mới là SHAPE12_*
    private fun loadTemplatesFromDisk() {
        shapeTemplates.clear()
        for (t in PIECE_TYPES) {
            val n = prefs.getInt("SHAPE12_N_$t", 0)
            val list = ArrayList<FloatArray>()
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
        if (templatesReady()) mainHandler.post { tvFenDebug.text = "Đã nạp mẫu hình học từ bộ nhớ" }
        else mainHandler.post { tvFenDebug.text = "Chưa có mẫu: hãy bấm Học Mẫu khi vừa tạo ván mới" }
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
        return px
    }

    private fun processLearningCapture(fullBmp: Bitmap) {
        try {
            val px = cropBoardPixels(fullBmp)
            if (px == null) { mainHandler.post { tvStatus.text = "Chưa có khung bàn cờ!" }; return }
            val prof = Array(8) { r -> Array(8) { c -> analyzeCellProfile(px, r, c) } }

            // Thế xuất phát CHÍNH XÁC: hàng 0,1,6,7 phải đủ quân; hàng 2..5 phải trống
            var bad = 0
            for (r in 0..7) for (c in 0..7) {
                val expect = (r <= 1 || r >= 6)
                if (prof[r][c].isOccupied != expect) bad++
            }
            if (bad > 0) {
                mainHandler.post {
                    tvStatus.text = "LỖI: chưa đúng thế xuất phát ($bad ô sai)"
                    Toast.makeText(this@OverlayService, "Chỉ bấm Học Mẫu khi vừa tạo ván mới, chưa đi nước nào, tắt gợi ý/highlight!", Toast.LENGTH_LONG).show()
                }
                return
            }

            val whiteBottom = chessOverlayView.isWhiteBottom
            // Thứ tự quân hàng cuối theo cột hiển thị (cả hàng trên lẫn hàng dưới)
            val order = if (whiteBottom) "RNBQKBNR" else "RNBKQBNR"

            val fresh = HashMap<Char, MutableList<FloatArray>>()
            for (t in PIECE_TYPES) fresh[t] = ArrayList()
            for (c in 0..7) {
                for (r in intArrayOf(0, 7)) fresh[order[c]]!!.add(prof[r][c].silhouette.clone())
                for (r in intArrayOf(1, 6)) fresh['P']!!.add(prof[r][c].silhouette.clone())
            }

            // Độ sáng trung bình mỗi phe -> ngưỡng Trắng/Đen = trung điểm
            val whiteRows = if (whiteBottom) intArrayOf(6, 7) else intArrayOf(0, 1)
            val blackRows = if (whiteBottom) intArrayOf(0, 1) else intArrayOf(6, 7)
            fun avgLum(rows: IntArray): Int {
                var s = 0; var n = 0
                for (r in rows) for (c in 0..7) { s += prof[r][c].avgPieceLum; n++ }
                return s / n
            }
            val lw = avgLum(whiteRows); val lb = avgLum(blackRows)
            if (lw - lb < 40) {
                mainHandler.post { tvStatus.text = "LỖI: không phân biệt được màu quân (sáng $lw / tối $lb)" }
                return
            }

            shapeTemplates.clear()
            for ((t, l) in fresh) shapeTemplates[t] = l
            lumWhite = lw; lumBlack = lb
            saveTemplatesToDisk()
            mainHandler.post {
                tvStatus.text = "✅ ĐÃ HỌC XONG MẪU BỘ CỜ!"
                tvFenDebug.text = "Mẫu 12x12 đã lưu (sáng T=$lw / Đ=$lb)"
                Toast.makeText(this@OverlayService, "Đã lưu mẫu hình cờ vào bộ nhớ!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            mainHandler.post { tvStatus.text = "Lỗi học mẫu: ${e.message}" }
        } finally {
            fullBmp.recycle(); isLearningMode = false; isProcessing.set(false)
        }
    }

    // =========================================================================
    // XỬ LÝ MỘT KHUNG HÌNH
    // =========================================================================

    private fun processCapturedBitmap(fullBmp: Bitmap) {
        try {
            val px = cropBoardPixels(fullBmp)
            if (px == null) { mainHandler.post { tvStatus.text = "⚠️ Hãy chỉnh khung bàn cờ trước" }; return }
            val gen = stateGeneration.get()

            val profiles = Array(8) { r -> Array(8) { c -> analyzeCellProfile(px, r, c) } }

            val lums = ArrayList<Int>()
            for (r in 0..7) for (c in 0..7) if (profiles[r][c].isOccupied) lums.add(profiles[r][c].avgPieceLum)
            val threshold = pieceLumThreshold(lums)
            if (threshold == null) {
                mainHandler.post { tvStatus.text = "Chờ nét (thiếu tương phản)..." }
                scheduleRetry(); return
            }

            val rawMatrix = Array(8) { CharArray(8) { '1' } }
            var unknown = 0
            for (vr in 0..7) {
                for (vc in 0..7) {
                    val p = profiles[vr][vc]
                    if (!p.isOccupied) continue
                    val type = classifyPieceType(vr, p)
                    if (type == null) { unknown++; continue }
                    rawMatrix[vr][vc] = if (p.avgPieceLum >= threshold) type.uppercaseChar() else type.lowercaseChar()
                }
            }
            if (unknown > 0) {
                mainHandler.post { tvStatus.text = "Chờ nét ($unknown ô chưa nhận ra)..." }
                scheduleRetry(); return
            }

            val standardMatrix = if (chessOverlayView.isWhiteBottom) rawMatrix else Array(8) { r -> CharArray(8) { c -> rawMatrix[7 - r][7 - c] } }

            val validationError = validatePositionStrict(standardMatrix)
            if (validationError != null) {
                mainHandler.post { tvStatus.text = "Chờ nét ($validationError)..." }
                scheduleRetry(); return
            }

            // KHỬ RUNG: cần 2 lần quét liên tiếp giống nhau
            val pending = pendingStableMatrix
            if (pending == null || !areMatricesEqual(pending, standardMatrix)) {
                pendingStableMatrix = copyMatrix(standardMatrix)
                consecutiveMatchCount = 1
                scheduleRetry(); return
            }
            consecutiveMatchCount++
            if (consecutiveMatchCount < 2) { scheduleRetry(); return }
            debounceRetries = 0

            validMatrix = standardMatrix
            val boardFen = buildFenBoardString(standardMatrix)
            mainHandler.post { tvFenDebug.text = "FEN: $boardFen" }

            val myColor = if (chessOverlayView.isWhiteBottom) 'w' else 'b'

            // Lượt đi: cờ ép chỉ dùng 1 lần và đóng đinh baseline
            val forced = forceTurnSide
            var activeTurn: Char
            if (forced != null) {
                activeTurn = forced
                previousValidMatrix = copyMatrix(standardMatrix)
                lastDetectedTurn = forced
                lastEnPassant = "-"
                forceTurnSide = null
                mainHandler.post { btnTurn.text = "🔄 Lượt" }
            } else {
                activeTurn = determineSideToMove(standardMatrix, myColor)
            }

            // Bên KHÔNG có lượt không được đang bị chiếu; nếu sai thì thử lượt còn lại
            if (isKingAttacked(standardMatrix, white = (activeTurn == 'b'))) {
                val other = if (activeTurn == 'w') 'b' else 'w'
                if (forced == null && !isKingAttacked(standardMatrix, white = (other == 'b'))) {
                    activeTurn = other; lastDetectedTurn = other
                } else {
                    mainHandler.post { tvStatus.text = "Thế cờ không hợp lệ (vua bị chiếu sai lượt)" }
                    return
                }
            }

            val castling = updateCastling(standardMatrix)
            val ep = if ((lastEnPassant.endsWith("3") && activeTurn == 'b') || (lastEnPassant.endsWith("6") && activeTurn == 'w')) lastEnPassant else "-"

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
            // Người dùng đã đổi phe/lượt/quét lại trong lúc chờ API -> bỏ kết quả cũ
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

    private fun areMatricesEqual(m1: Array<CharArray>, m2: Array<CharArray>): Boolean {
        for (r in 0..7) for (c in 0..7) if (m1[r][c] != m2[r][c]) return false
        return true
    }

    // Ngưỡng Trắng/Đen: ưu tiên trung điểm đã học; không có thì 2-means 1D, kèm kiểm tra dải sáng
    private fun pieceLumThreshold(lums: List<Int>): Int? {
        if (lumWhite >= 0 && lumBlack >= 0 && lumWhite - lumBlack >= 40) return (lumWhite + lumBlack) / 2
        if (lums.size < 2) return null
        val lo0 = lums.minOrNull() ?: return null
        val hi0 = lums.maxOrNull() ?: return null
        if (hi0 - lo0 < 40) return null
        var t = (lo0 + hi0) / 2f
        repeat(10) {
            val lo = lums.filter { it < t }
            val hi = lums.filter { it >= t }
            if (lo.isEmpty() || hi.isEmpty()) return t.toInt()
            t = (lo.average().toFloat() + hi.average().toFloat()) / 2f
        }
        return t.toInt()
    }

    // Trả về loại quân (chữ HOA) hoặc null nếu không đủ tin cậy
    private fun classifyPieceType(r: Int, profile: CellProfile): Char? {
        if (templatesReady()) {
            var best = '?'; var bestS = -2f; var second = -2f
            for ((type, list) in shapeTemplates) {
                var s = -2f
                for (tpl in list) {
                    var dot = 0f
                    for (i in 0 until SIL_N) dot += profile.silhouette[i] * tpl[i]
                    if (dot > s) s = dot
                }
                if (s > bestS) { second = bestS; bestS = s; best = type } else if (s > second) second = s
            }
            return if (bestS >= MATCH_MIN && bestS - second >= MATCH_MARGIN) best else null
        }
        return classifyByGeometry(r, profile)   // tạm thời cho tới khi người dùng Học Mẫu
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
    // LUẬT CỜ: KIỂM TRA THẾ CỜ, LƯỢT ĐI, NHẬP THÀNH, EN PASSANT
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
            // thứ tự: P N B R Q K
            val total = cnt.sum()
            if (total > 16) return "Quá 16 quân"
            if (cnt[0] > 8) return "Quá 8 Tốt"
            val extra = max(0, cnt[4] - 1) + max(0, cnt[3] - 2) + max(0, cnt[2] - 2) + max(0, cnt[1] - 2)
            if (extra > 8 - cnt[0]) return "Thừa quân so với số Tốt"
        }
        return null
    }

    // Vua của phe `white` có đang bị chiếu không (ma trận chuẩn: hàng 0 = hàng 8)
    private fun isKingAttacked(m: Array<CharArray>, white: Boolean): Boolean {
        val kch = if (white) 'K' else 'k'
        var kr = -1; var kc = -1
        for (r in 0..7) for (c in 0..7) if (m[r][c] == kch) { kr = r; kc = c }
        if (kr < 0) return false

        val ePawn = if (white) 'p' else 'P'; val eKnight = if (white) 'n' else 'N'
        val eBishop = if (white) 'b' else 'B'; val eRook = if (white) 'r' else 'R'
        val eQueen = if (white) 'q' else 'Q'; val eKing = if (white) 'k' else 'K'

        // Tốt đen tấn công xuống (hàng tăng), tốt trắng tấn công lên
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

    // Lượt đi suy ra từ MÀU quân mới xuất hiện ở ô; luôn cập nhật baseline sau mỗi lần gọi
    private fun determineSideToMove(cur: Array<CharArray>, myColor: Char): Char {
        val snap = copyMatrix(cur)
        if (buildFenBoardString(cur) == START_BOARD) {
            previousValidMatrix = snap; lastDetectedTurn = 'w'; lastEnPassant = "-"
            return 'w'
        }
        val prev = previousValidMatrix
        if (prev == null) {
            previousValidMatrix = snap; lastDetectedTurn = myColor; lastEnPassant = "-"
            return myColor
        }

        var wArr = 0; var bArr = 0
        for (r in 0..7) for (c in 0..7) {
            val o = prev[r][c]; val n = cur[r][c]
            if (n == '1') continue
            val oc = if (o == '1') '1' else if (o.isUpperCase()) 'W' else 'B'
            val nc = if (n.isUpperCase()) 'W' else 'B'
            if (oc != nc) { if (nc == 'W') wArr++ else bArr++ }
        }

        if (wArr + bArr > 0) {
            lastEnPassant = detectEnPassant(prev, cur, wArr, bArr)
            lastDetectedTurn = when {
                wArr > 0 && bArr == 0 -> 'b'
                bArr > 0 && wArr == 0 -> 'w'
                else -> myColor          // cả hai bên cùng đổi (mất frame/bot đi ngay): coi như tới lượt mình
            }
        }
        previousValidMatrix = snap
        return lastDetectedTurn
    }

    // En passant chỉ có khi vừa có đúng 1 nước Tốt đi 2 ô và có Tốt địch ngay bên cạnh
    private fun detectEnPassant(prev: Array<CharArray>, cur: Array<CharArray>, wArr: Int, bArr: Int): String {
        if (wArr == 1 && bArr == 0) {
            for (c in 0..7) {
                if (prev[6][c] == 'P' && prev[5][c] == '1' && prev[4][c] == '1' && cur[6][c] == '1' && cur[5][c] == '1' && cur[4][c] == 'P') {
                    val adj = (c > 0 && cur[4][c - 1] == 'p') || (c < 7 && cur[4][c + 1] == 'p')
                    if (adj) return "${'a' + c}3"
                }
            }
        } else if (bArr == 1 && wArr == 0) {
            for (c in 0..7) {
                if (prev[1][c] == 'p' && prev[2][c] == '1' && prev[3][c] == '1' && cur[1][c] == '1' && cur[2][c] == '1' && cur[3][c] == 'p') {
                    val adj = (c > 0 && cur[3][c - 1] == 'P') || (c < 7 && cur[3][c + 1] == 'P')
                    if (adj) return "${'a' + c}6"
                }
            }
        }
        return "-"
    }

    // Quyền nhập thành chỉ mất đi, không tự sinh lại (trừ khi gặp thế xuất phát)
    private fun updateCastling(m: Array<CharArray>): String {
        if (buildFenBoardString(m) == START_BOARD) castleRights = setOf('K', 'Q', 'k', 'q')
        val s = castleRights.toMutableSet()
        if (m[7][4] != 'K') { s.remove('K'); s.remove('Q') }
        if (m[7][7] != 'R') s.remove('K')
        if (m[7][0] != 'R') s.remove('Q')
        if (m[0][4] != 'k') { s.remove('k'); s.remove('q') }
        if (m[0][7] != 'r') s.remove('k')
        if (m[0][0] != 'r') s.remove('q')
        castleRights = s
        return "KQkq".filter { it in s }.ifEmpty { "-" }
    }

    // Tính nước cho bàn cờ vừa sửa tay (gọi từ luồng chính)
    private fun forceEvaluateMatrix() {
        val src = validMatrix ?: return
        val m = copyMatrix(src)

        val err = validatePositionStrict(m)
        if (err != null) { tvStatus.text = "Bàn cờ sửa tay không hợp lệ: $err"; return }

        val forced = forceTurnSide
        var turn = forced ?: lastDetectedTurn
        if (isKingAttacked(m, white = (turn == 'b'))) {
            val other = if (turn == 'w') 'b' else 'w'
            if (forced == null && !isKingAttacked(m, white = (other == 'b'))) turn = other
            else { tvStatus.text = "Bàn cờ sửa tay không hợp lệ (vua bị chiếu sai lượt)"; return }
        }

        forceTurnSide = null; btnTurn.text = "🔄 Lượt"
        lastDetectedTurn = turn
        previousValidMatrix = copyMatrix(m)
        pendingStableMatrix = null; consecutiveMatchCount = 0
        lastEnPassant = "-"

        val boardFen = buildFenBoardString(m)
        tvFenDebug.text = "FEN (Sửa tay): $boardFen"
        val castling = updateCastling(m)
        val stateKey = "$boardFen|$turn|EDITED"
        val gen = stateGeneration.incrementAndGet()
        val fullFen = "$boardFen $turn $castling - 0 1"
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

    // Tóm tắt lý do lỗi của từng nguồn để hiện lên panel (lichess 404 chỉ là chưa có trong cache nên bỏ qua)
    private fun apiErrorSummary(): String {
        val parts = apiErrors.entries.filter { !(it.key == "lichess" && it.value == "HTTP 404") }
        return if (parts.isEmpty()) "không phản hồi (hết ${API_TOTAL_MS / 1000}s)" else parts.joinToString(" | ") { "${it.key}: ${it.value}" }
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

    // Gọi HTTP, ghi lại lý do nếu lỗi (mã HTTP / loại exception)
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
        val body = JSONObject().put("fen", fen).put("depth", 12).put("maxThinkingTime", 100).toString()
        val resp = httpRequest("chess-api", "https://chess-api.com/v1", "POST", body) ?: return null
        return try {
            val move = JSONObject(resp).optString("move", "")
            if (move.length >= 4) move.substring(0, min(move.length, 5))
            else { noteApiError("chess-api", "không có nước: ${resp.take(60)}"); null }
        } catch (_: Exception) { noteApiError("chess-api", "JSON lỗi: ${resp.take(60)}"); null }
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        val url = "https://stockfish.online/api/s/v2.php?fen=${URLEncoder.encode(fen, "UTF-8")}&depth=12"
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

    // Nguồn dự phòng: cloud-eval của Lichess (chỉ có cho thế cờ đã được cache, nên 404 là bình thường)
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
        val notif = Notification.Builder(this, "chess_overlay_channel").setContentTitle("Chess Pro v10.2").setSmallIcon(android.R.drawable.ic_menu_compass).build()
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
