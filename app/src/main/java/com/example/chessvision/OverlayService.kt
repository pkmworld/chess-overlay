package com.example.chessvision

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
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
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Đọc pixel RGBA trực tiếp từ buffer của khung hình (không tạo Bitmap toàn màn hình). */
class PixelSource(private val buf: ByteBuffer, private val rowStride: Int, private val pixelStride: Int) {
    fun get(x: Int, y: Int): Int {
        val o = y * rowStride + x * pixelStride
        return Color.rgb(buf.get(o).toInt() and 0xFF, buf.get(o + 1).toInt() and 0xFF, buf.get(o + 2).toInt() and 0xFF)
    }
}

/** Đặc trưng của một ô cờ (ảnh 32x32). */
class CellFeature(
    val occupied: Boolean,
    val edges: BooleanArray,
    val coreMedianLum: Int,
    val edgeCount: Int,
    val topZoneWidth: Int,
    val midZoneWidth: Int,
    val highestEdgeY: Int,
    val symmetryDiff: Int
)

class OverlayService : Service() {

    companion object {
        private const val TAG = "ChessVision"
        private const val CW = 32                    // ô được thu về 32x32
        private const val EDGE_GRAD = 26             // ngưỡng gradient để coi là cạnh
        private const val OCC_EDGE_MIN = 22          // số điểm cạnh tối thiểu để coi ô có quân
        private const val TEMPLATE_MAX_DIFF = 175    // sai khác tối đa (trên 1024) khi so khớp mẫu đã học
        private const val ENGINE_TIME_MS = 2500L
        private const val AUTO_INTERVAL_MS = 1000L
        private const val PREF_TOP_Y = "SAVED_TOP_Y"
        private val START_ROWS = arrayOf(
            "rnbqkbnr", "pppppppp", "11111111", "11111111",
            "11111111", "11111111", "PPPPPPPP", "RNBQKBNR"
        )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var chessOverlayView: ChessOverlayView
    private lateinit var controlLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnSide: Button
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var frameThread: HandlerThread
    private lateinit var frameHandler: Handler
    private val imageLock = Any()
    private var heldImage: Image? = null
    private val frameSeq = AtomicLong(0)

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private val pendingManual = AtomicBoolean(false)

    @Volatile private var destroyed = false
    @Volatile private var isAutoMode = false
    @Volatile private var cachedBoardTopY = -1
    @Volatile private var whiteBottom = true   // phe của bạn = phe đang ở phía dưới bàn cờ
    @Volatile private var lumThr = 128

    // ---- Chỉ đọc/ghi trên worker thread ----
    private val learnedShapes = HashMap<Char, BooleanArray>()  // tự học khi tình cờ thấy thế khởi đầu (không bắt buộc)
    private var castleMem = "KQkq"
    private var epSquare = "-"
    private var prevStd: Array<CharArray>? = null
    private var pendingKey = ""
    private var confirmedKey = ""
    private var lastSuggestedFen = ""
    private val fenCache = HashMap<String, String>()
    private var failCount = 0
    private var nextApiAt = 0L

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode || destroyed) return
            scanAndSuggest(false)
            mainHandler.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("StealthChessPrefs", Context.MODE_PRIVATE)
        cachedBoardTopY = prefs.getInt(PREF_TOP_Y, -1)
        whiteBottom = prefs.getBoolean("WHITE_BOTTOM", true)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = readScreenSize()
        screenWidth = size.first
        screenHeight = size.second
        screenDensity = resources.displayMetrics.densityDpi

        frameThread = HandlerThread("ChessFrames").apply { start() }
        frameHandler = Handler(frameThread.looper)

        setupBoardOverlay()
        setupFloatingControls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (mediaProjection != null) return START_NOT_STICKY // đã chạy, bỏ qua lệnh trùng

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
                mainHandler.postDelayed({ scanAndSuggest(true) }, 800)
            } catch (e: Exception) {
                Log.e(TAG, "start projection", e)
                stopSelf()
            }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ UI

    private fun readScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    private fun setupBoardOverlay() {
        chessOverlayView = ChessOverlayView(this)
        chessOverlayView.isWhiteBottom = whiteBottom
        if (cachedBoardTopY > 0) {
            chessOverlayView.detectedBoardRect =
                RectF(0f, cachedBoardTopY.toFloat(), screenWidth.toFloat(), (cachedBoardTopY + screenWidth).toFloat())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(chessOverlayView, params)
    }

    private fun setupFloatingControls() {
        controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 20, 20, 20))
            setPadding(14, 8, 14, 8)
        }
        tvStatus = TextView(this).apply {
            text = "Chess Vision v6.1 • chọn phe rồi bấm ⚡ hoặc ▶ Auto"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 11f
            maxWidth = (screenWidth * 0.9f).toInt()
            maxLines = 3
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 0) }

        btnSide = createButton(if (whiteBottom) "⚪ Trắng" else "⚫ Đen") {
            whiteBottom = !whiteBottom
            prefs.edit().putBoolean("WHITE_BOTTOM", whiteBottom).apply()
            chessOverlayView.isWhiteBottom = whiteBottom
            btnSide.text = if (whiteBottom) "⚪ Trắng" else "⚫ Đen"
            chessOverlayView.setMove(null)
            runOnWorker { resetGameState() }
            scanAndSuggest(true)
        }

        val btnAuto = createButton("▶ Auto") { btn ->
            isAutoMode = !isAutoMode
            mainHandler.removeCallbacks(autoRunnable)
            if (isAutoMode) {
                btn.text = "⏸ Auto"
                mainHandler.post(autoRunnable)
            } else {
                btn.text = "▶ Auto"
                tvStatus.text = "Đã dừng Auto."
            }
        }

        val btnScan = createButton("⚡ Quét") { scanAndSuggest(true) }

        // Reset trí nhớ ván (quyền nhập thành, mẫu quân đã học) — dùng khi bắt đầu ván mới / đổi bộ quân
        val btnRelearn = createButton("♻") {
            runOnWorker { learnedShapes.clear(); resetGameState() }
            tvStatus.text = "Đã reset trí nhớ ván • quét lại..."
            scanAndSuggest(true)
        }

        val btnUp = createButton("▲") { moveBoard(-12) }
        val btnDown = createButton("▼") { moveBoard(12) }

        val btnReset = createButton("⟲") {
            cachedBoardTopY = -1
            prefs.edit().remove(PREF_TOP_Y).apply()
            tvStatus.text = "Đang dò lại vị trí bàn cờ..."
            scanAndSuggest(true)
        }

        val btnClose = createButton("✕") { stopSelf() }

        row.addView(btnSide); row.addView(btnAuto); row.addView(btnScan); row.addView(btnRelearn)
        row.addView(btnUp); row.addView(btnDown); row.addView(btnReset); row.addView(btnClose)
        controlLayout.addView(tvStatus); controlLayout.addView(row)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 75
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
            text = label; textSize = 10f; setPadding(10, 5, 10, 5)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick(this) }
        }
    }

    private fun moveBoard(delta: Int) {
        val maxY = (screenHeight - screenWidth - 4).coerceAtLeast(0)
        if (cachedBoardTopY < 0) cachedBoardTopY = (screenHeight - screenWidth) / 2
        cachedBoardTopY = (cachedBoardTopY + delta).coerceIn(0, maxY)
        prefs.edit().putInt(PREF_TOP_Y, cachedBoardTopY).apply()
        chessOverlayView.detectedBoardRect =
            RectF(0f, cachedBoardTopY.toFloat(), screenWidth.toFloat(), (cachedBoardTopY + screenWidth).toFloat())
        chessOverlayView.invalidate()
        tvStatus.text = "Đã lưu Y=$cachedBoardTopY • đang quét lại..."
        scanAndSuggest(true)
    }

    private fun setStatus(s: String) {
        mainHandler.post { if (!destroyed) tvStatus.text = s }
    }

    private fun runOnWorker(block: () -> Unit) {
        if (destroyed) return
        try { workerExecutor.execute { block() } } catch (e: Exception) { /* service đang tắt */ }
    }

    // ------------------------------------------------------------ Chụp màn hình

    private fun setupVirtualDisplay() {
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!destroyed) mainHandler.post { stopSelf() }
            }
        }, mainHandler)

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        // Luôn rút khung mới nhất để hàng đợi buffer không bị tắc; giữ lại 1 khung để đọc khi cần.
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                synchronized(imageLock) {
                    heldImage?.close()
                    heldImage = img
                }
                frameSeq.incrementAndGet()
            }
        }, frameHandler)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )
    }

    private fun lum(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    /** Ẩn lớp phủ (mũi tên, góc xanh) trước khi chụp. Bảng điều khiển chỉ ẩn nếu chồng lên bàn cờ. */
    private fun setOverlaysVisible(visible: Boolean) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                if (!destroyed) {
                    chessOverlayView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                    if (visible) controlLayout.visibility = View.VISIBLE
                    else if (controlOverlapsBoard()) controlLayout.visibility = View.INVISIBLE
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    private fun controlOverlapsBoard(): Boolean {
        val top = cachedBoardTopY
        if (top < 0) return true
        val loc = IntArray(2)
        controlLayout.getLocationOnScreen(loc)
        val cTop = loc[1]
        val cBottom = cTop + controlLayout.height
        return cBottom > top && cTop < top + screenWidth
    }

    /** Chấm điểm mẫu ô sáng/tối trên cả 64 ô; lấy giữa vùng điểm cao nhất. Trả -1 nếu không chắc. */
    private fun findChessboardTopY(src: PixelSource): Int {
        val w = screenWidth; val h = screenHeight
        val cs = w / 8f
        val offs = IntArray(8) { (it * cs + cs * 0.12f).toInt().coerceIn(0, w - 1) }
        val l = IntArray(64)
        var bestScore = -1
        val bestYs = ArrayList<Int>()
        for (top in 0..(h - w) step 2) {
            for (r in 0..7) {
                val y = (top + r * cs + cs * 0.12f).toInt().coerceIn(0, h - 1)
                for (c in 0..7) l[r * 8 + c] = lum(src.get(offs[c], y))
            }
            var se = 0; var so = 0
            for (i in 0 until 64) if (((i / 8) + (i % 8)) % 2 == 0) se += l[i] else so += l[i]
            val me = se / 32; val mo = so / 32
            if (abs(me - mo) < 12) continue
            val mid = (me + mo) / 2
            val evenBright = me > mo
            var ok = 0
            for (i in 0 until 64) {
                val even = ((i / 8) + (i % 8)) % 2 == 0
                val bright = l[i] > mid
                if (bright == (even == evenBright)) ok++
            }
            if (ok > bestScore) { bestScore = ok; bestYs.clear(); bestYs.add(top) }
            else if (ok == bestScore) bestYs.add(top)
        }
        return if (bestScore >= 56 && bestYs.isNotEmpty()) bestYs[bestYs.size / 2] else -1
    }

    /** Chụp vùng bàn cờ thành mảng pixel vuông (cạnh = screenWidth). Gọi trên worker thread. */
    private fun captureBoard(): IntArray? {
        setOverlaysVisible(false)
        try {
            // Chờ khung hình MỚI (sau khi ẩn lớp phủ) để không đọc nhầm khung cũ còn mũi tên.
            val seq0 = frameSeq.get()
            Thread.sleep(70)
            if (frameSeq.get() == seq0) {
                val end = SystemClock.elapsedRealtime() + 300
                while (frameSeq.get() == seq0 && SystemClock.elapsedRealtime() < end) Thread.sleep(15)
            }
            synchronized(imageLock) {
                val img = heldImage
                if (img == null) { setStatus("Chưa nhận được khung hình, thử lại"); return null }
                if (img.width != screenWidth || img.height != screenHeight) {
                    setStatus("Kích thước màn hình đã đổi • đóng và mở lại app"); return null
                }
                val plane = img.planes[0]
                val src = PixelSource(plane.buffer, plane.rowStride, plane.pixelStride)

                if (cachedBoardTopY < 0) {
                    val found = findChessboardTopY(src)
                    cachedBoardTopY = if (found >= 0) found else (screenHeight - screenWidth) / 2
                    prefs.edit().putInt(PREF_TOP_Y, cachedBoardTopY).apply()
                    if (found < 0) setStatus("Không tự dò được bàn cờ • chỉnh ▲/▼ cho khớp 4 góc xanh")
                }
                val topY = cachedBoardTopY.coerceIn(0, screenHeight - screenWidth)
                val rect = RectF(0f, topY.toFloat(), screenWidth.toFloat(), (topY + screenWidth).toFloat())
                mainHandler.post { chessOverlayView.detectedBoardRect = rect; chessOverlayView.invalidate() }

                val n = screenWidth
                val px = IntArray(n * n)
                for (y in 0 until n) {
                    val base = y * n
                    for (x in 0 until n) px[base + x] = src.get(x, topY + y)
                }
                return px
            }
        } finally {
            setOverlaysVisible(true)
        }
    }

    // ------------------------------------------------------- Đọc bàn cờ từ ảnh

    /** Phân tích 1 ô: thu về 32x32 xám, tìm cạnh, đo hình dạng (như code cũ). */
    private fun analyzeCell(px: IntArray, n: Int, x0: Float, y0: Float, cs: Float): CellFeature {
        val w = CW; val h = CW
        val gray = IntArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            var s = 0
            for (dj in 0..1) for (di in 0..1) {
                val x = (x0 + (i + 0.25f + 0.5f * di) * cs / w).toInt().coerceIn(0, n - 1)
                val y = (y0 + (j + 0.25f + 0.5f * dj) * cs / h).toInt().coerceIn(0, n - 1)
                s += lum(px[y * n + x])
            }
            gray[j * w + i] = s / 4
        }

        val edges = BooleanArray(w * h)
        var edgeCount = 0
        var highestEdgeY = 32
        var topMinX = 32; var topMaxX = 0
        var midMinX = 32; var midMaxX = 0
        var symmetryDiff = 0

        for (y in 4 until h - 4) {
            for (x in 4 until w - 4) {
                val idx = y * w + x
                val gx = abs(gray[idx + 1] - gray[idx - 1])
                val gy = abs(gray[idx + w] - gray[idx - w])
                if (gx + gy > EDGE_GRAD) {
                    edges[idx] = true
                    edgeCount++
                    if (y < highestEdgeY) highestEdgeY = y
                    if (y in 5..12) { if (x < topMinX) topMinX = x; if (x > topMaxX) topMaxX = x }
                    if (y in 13..20) { if (x < midMinX) midMinX = x; if (x > midMaxX) midMaxX = x }
                }
                if (x in 7..15) symmetryDiff += abs(gray[idx] - gray[y * w + (31 - x)])
            }
        }

        // Độ sáng trung vị vùng lõi để tách quân Trắng / Đen
        val core = IntArray(81)
        var k = 0
        for (y in 14..22) for (x in 12..20) core[k++] = gray[y * w + x]
        core.sort()

        val topWidth = if (topMaxX >= topMinX) topMaxX - topMinX else 0
        val midWidth = if (midMaxX >= midMinX) midMaxX - midMinX else 0
        return CellFeature(edgeCount >= OCC_EDGE_MIN, edges, core[40], edgeCount, topWidth, midWidth, highestEdgeY, symmetryDiff)
    }

    /** Ngưỡng tách Trắng/Đen: k-means 2 cụm; nếu chỉ có 1 màu quân thì dùng ngưỡng đã nhớ. */
    private fun lumThreshold(lums: List<Int>): Int {
        var lo = lums.min(); var hi = lums.max()
        if (hi - lo < 40) return lumThr
        var thr = (lo + hi) / 2
        repeat(8) {
            val a = lums.filter { it <= thr }; val b = lums.filter { it > thr }
            if (a.isNotEmpty() && b.isNotEmpty()) {
                lo = a.average().toInt(); hi = b.average().toInt(); thr = (lo + hi) / 2
            }
        }
        return thr
    }

    /** Tình cờ thấy thế khởi đầu thì học mẫu hình dạng quân để nhận diện chuẩn hơn (không bắt buộc). */
    private fun tryLearn(f: Array<Array<CellFeature>>) {
        if (learnedShapes.isNotEmpty()) return
        for (r in 0..7) for (c in 0..7) {
            if (f[r][c].occupied != (r <= 1 || r >= 6)) return
        }
        val topLum = (0..1).flatMap { r -> (0..7).map { f[r][it].coreMedianLum } }.average()
        val botLum = (6..7).flatMap { r -> (0..7).map { f[r][it].coreMedianLum } }.average()
        if (abs(botLum - topLum) < 40) return
        val wb = botLum > topLum
        lumThr = ((botLum + topLum) / 2).toInt()
        val order = if (wb) charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
                    else charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R')
        for (c in 0..7) learnedShapes[order[c]] = f[7][c].edges
        learnedShapes['P'] = f[6][3].edges
        castleMem = "KQkq"
        if (wb != whiteBottom) {
            whiteBottom = wb
            prefs.edit().putBoolean("WHITE_BOTTOM", wb).apply()
            mainHandler.post {
                chessOverlayView.isWhiteBottom = wb
                btnSide.text = if (wb) "⚪ Trắng" else "⚫ Đen"
            }
        }
    }

    private fun classifyPiece(r: Int, f: CellFeature): Char {
        if (learnedShapes.isNotEmpty()) {
            var bestP = 'P'
            var minDiff = Int.MAX_VALUE
            for ((pChar, tpl) in learnedShapes) {
                if (pChar == 'P' && (r == 0 || r == 7)) continue
                var diff = 0
                for (i in 0 until CW * CW) if (f.edges[i] != tpl[i]) diff++
                if (diff < minDiff) { minDiff = diff; bestP = pChar }
            }
            if (minDiff < TEMPLATE_MAX_DIFF) return bestP
        }
        if (f.symmetryDiff > 2800) return 'N'
        if (r != 0 && r != 7 && f.highestEdgeY >= 9 && f.topZoneWidth <= 11 && f.edgeCount < 70) return 'P'
        if (f.highestEdgeY <= 6 && f.topZoneWidth >= 14) return 'Q'
        if (f.highestEdgeY <= 6 && f.topZoneWidth in 8..13) return 'K'
        if (f.topZoneWidth >= 12 && abs(f.topZoneWidth - f.midZoneWidth) <= 3) return 'R'
        if (f.highestEdgeY <= 8 && f.topZoneWidth < f.midZoneWidth) return 'B'
        return if (r == 0 || r == 7) 'R' else 'P'
    }

    // ------------------------------------------------- Sửa thế cờ đọc nhiễu (như code cũ)

    private fun sanitize(m: Array<CharArray>, feats: Array<Array<CellFeature>>) {
        for (c in 0..7) {
            if (m[0][c] == 'P') m[0][c] = 'Q'
            if (m[0][c] == 'p') m[0][c] = 'r'
            if (m[7][c] == 'P') m[7][c] = 'R'
            if (m[7][c] == 'p') m[7][c] = 'q'
        }
        assignKing(m, feats, true)
        assignKing(m, feats, false)
        // Hai Vua sát nhau là bất hợp lệ -> tách ra
        var wkR = 7; var wkC = 4; var bkR = 0; var bkC = 4
        for (r in 0..7) for (c in 0..7) {
            if (m[r][c] == 'K') { wkR = r; wkC = c }
            if (m[r][c] == 'k') { bkR = r; bkC = c }
        }
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) {
            m[bkR][bkC] = 'q'
            m[0][4] = 'k'
        }
    }

    private fun assignKing(m: Array<CharArray>, feats: Array<Array<CellFeature>>, isWhite: Boolean) {
        val kingChar = if (isWhite) 'K' else 'k'
        val queenChar = if (isWhite) 'Q' else 'q'
        val cand = ArrayList<IntArray>()
        for (r in 0..7) for (c in 0..7) if (m[r][c] == kingChar) cand.add(intArrayOf(r, c))
        if (cand.size == 1) return
        if (cand.size > 1) {
            // giữ Vua ở gần hàng nhà nhất, các Vua còn lại thành Hậu
            cand.sortBy { if (isWhite) 7 - it[0] else it[0] }
            for (i in 1 until cand.size) m[cand[i][0]][cand[i][1]] = queenChar
            return
        }
        var bestR = -1; var bestC = -1; var bestScore = -999
        for (r in 0..7) for (c in 0..7) {
            val ch = m[r][c]
            if (ch != '1' && ch.isUpperCase() == isWhite) {
                val f = feats[r][c]
                val rankBonus = if (isWhite) (if (r == 7) 35 else 0) else (if (r == 0) 35 else 0)
                val score = (32 - f.highestEdgeY) * 3 + f.edgeCount + rankBonus
                if (score > bestScore) { bestScore = score; bestR = r; bestC = c }
            }
        }
        if (bestR != -1) m[bestR][bestC] = kingChar
        else m[if (isWhite) 7 else 0][4] = kingChar
    }

    // ---------------------------------------------------------------- Quét chính

    private fun resetGameState() {
        castleMem = "KQkq"; epSquare = "-"; prevStd = null
        pendingKey = ""; confirmedKey = ""; lastSuggestedFen = ""
    }

    private fun scanAndSuggest(manual: Boolean) {
        if (destroyed) return
        if (!scanning.compareAndSet(false, true)) {
            if (manual) pendingManual.set(true) // đang quét dở: xếp hàng quét lại ngay khi xong
            return
        }
        try {
            workerExecutor.execute {
                try {
                    doScan(manual)
                } catch (e: Exception) {
                    Log.e(TAG, "scan", e)
                    setStatus("Lỗi: ${e.message}")
                } finally {
                    scanning.set(false)
                    if (pendingManual.getAndSet(false)) mainHandler.post { scanAndSuggest(true) }
                }
            }
        } catch (e: Exception) {
            scanning.set(false)
        }
    }

    private fun doScan(manual: Boolean) {
        val size = readScreenSize()
        if (size.first != screenWidth || size.second != screenHeight) {
            setStatus("Màn hình đã xoay/đổi kích thước • đóng và mở lại app"); return
        }
        if (screenHeight <= screenWidth) { setStatus("Chỉ hỗ trợ màn hình dọc"); return }

        val px = captureBoard() ?: return
        val n = screenWidth
        val cs = n / 8f
        val feats = Array(8) { r -> Array(8) { c -> analyzeCell(px, n, c * cs, r * cs, cs) } }

        val occLums = ArrayList<Int>()
        for (r in 0..7) for (c in 0..7) if (feats[r][c].occupied) occLums.add(feats[r][c].coreMedianLum)
        if (occLums.size < 2) { setStatus("Chưa thấy quân cờ • kiểm tra 4 góc xanh / chỉnh ▲▼"); return }
        if (occLums.size > 32) {
            setStatus("Thấy ${occLums.size} quân (quá nhiều) • tắt chấm gợi ý nước đi trong app cờ hoặc chỉnh ▲▼"); return
        }

        tryLearn(feats)
        val thr = lumThreshold(occLums)

        val raw = Array(8) { CharArray(8) { '1' } }
        for (r in 0..7) for (c in 0..7) {
            val f = feats[r][c]
            if (!f.occupied) continue
            val ch = classifyPiece(r, f)
            raw[r][c] = if (f.coreMedianLum >= thr) ch.uppercaseChar() else ch.lowercaseChar()
        }

        val white = whiteBottom
        val std = if (white) raw else Array(8) { r -> CharArray(8) { c -> raw[7 - r][7 - c] } }
        val stdFeats = if (white) feats else Array(8) { r -> Array(8) { c -> feats[7 - r][7 - c] } }
        sanitize(std, stdFeats)

        val key = Chess.fenBoard(std)
        Log.d(TAG, "board=$key")

        // Auto: cần 2 lần quét liên tiếp giống nhau mới chấp nhận (tránh đọc giữa lúc quân đang di chuyển)
        if (!manual && key != confirmedKey) {
            if (key != pendingKey) { pendingKey = key; return }
        }
        pendingKey = ""

        if (key != confirmedKey) {
            // Thế cờ vừa đổi -> cập nhật quyền nhập thành (chỉ giảm) và ô en passant
            if (String(std[0]) + String(std[1]) + String(std[6]) + String(std[7]) ==
                START_ROWS[0] + START_ROWS[1] + START_ROWS[6] + START_ROWS[7] &&
                (2..5).all { String(std[it]) == "11111111" }) castleMem = "KQkq"
            castleMem = pruneCastle(castleMem, std)
            epSquare = prevStd?.let { detectEp(it, std, white) } ?: "-"
            prevStd = Chess.copy(std)
            confirmedKey = key
        }

        suggest(Position(std, white, castleMem, epSquare), manual, occLums.size)
    }

    private fun pruneCastle(rights: String, m: Array<CharArray>): String {
        val sb = StringBuilder()
        if ('K' in rights && m[7][4] == 'K' && m[7][7] == 'R') sb.append('K')
        if ('Q' in rights && m[7][4] == 'K' && m[7][0] == 'R') sb.append('Q')
        if ('k' in rights && m[0][4] == 'k' && m[0][7] == 'r') sb.append('k')
        if ('q' in rights && m[0][4] == 'k' && m[0][0] == 'r') sb.append('q')
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    /** Đối thủ vừa đi tốt 2 ô giữa 2 lần quét -> ô en passant (chỉ khi mình có tốt đứng kề). */
    private fun detectEp(p: Array<CharArray>, m: Array<CharArray>, myWhite: Boolean): String {
        for (c in 0..7) {
            if (myWhite) {
                if (p[1][c] == 'p' && p[2][c] == '1' && p[3][c] == '1' && m[1][c] == '1' && m[2][c] == '1' && m[3][c] == 'p') {
                    if ((c > 0 && m[3][c - 1] == 'P') || (c < 7 && m[3][c + 1] == 'P')) return "${'a' + c}6"
                }
            } else {
                if (p[6][c] == 'P' && p[5][c] == '1' && p[4][c] == '1' && m[6][c] == '1' && m[5][c] == '1' && m[4][c] == 'P') {
                    if ((c > 0 && m[4][c - 1] == 'p') || (c < 7 && m[4][c + 1] == 'p')) return "${'a' + c}3"
                }
            }
        }
        return "-"
    }

    // ------------------------------------------------------------ Gợi ý nước đi

    private fun suggest(p: Position, manual: Boolean, pieceCount: Int) {
        val legal = Chess.legal(p)
        if (legal.isEmpty()) {
            mainHandler.post { chessOverlayView.setMove(null) }
            setStatus(if (Chess.inCheck(p.b, p.whiteToMove)) "Chiếu bí" else "Hòa (hết nước đi)")
            return
        }
        val fen = p.fen()
        if (!manual && fen == lastSuggestedFen) return

        var mv: String? = null
        var src = "online"
        val api = getApiMove(fen, manual)
        if (api != null && legal.any { it.uci() == api }) mv = api
        if (mv == null) {
            val m = Chess.bestMove(p, ENGINE_TIME_MS)
            if (m != null) { mv = m.uci(); src = "offline" }
        }
        if (mv == null) return
        lastSuggestedFen = fen

        val promo = if (mv.length == 5) "=${mv[4].uppercaseChar()}" else ""
        val text = "✅ ${mv.substring(0, 2).uppercase()} ➔ ${mv.substring(2, 4).uppercase()}$promo ($src, $pieceCount quân)"
        val shown = mv.substring(0, 4)
        mainHandler.post {
            chessOverlayView.setMove(shown)
            tvStatus.text = text
        }
    }

    // -------------------------------------------------------------- Engine API

    private val uciRegex = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")

    private fun getApiMove(fen: String, manual: Boolean): String? {
        fenCache[fen]?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (!manual && now < nextApiAt) return null // đang backoff -> dùng engine offline
        val mv = fetchFromStockfishOnline(fen) ?: fetchFromChessApi(fen)
        if (mv == null) {
            failCount++
            nextApiAt = now + minOf(30_000L, 2_000L shl minOf(failCount, 4))
            return null
        }
        failCount = 0; nextApiAt = 0L
        if (fenCache.size > 200) fenCache.clear()
        fenCache[fen] = mv
        return mv
    }

    private fun fetchFromStockfishOnline(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://stockfish.online/api/s/v2.php?fen=${URLEncoder.encode(fen, "UTF-8")}&depth=12")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 2500; readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            if (!json.optBoolean("success", true)) return null
            val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
            val parts = raw.trim().split(" ")
            val cand = (if (parts.size >= 2) parts[1] else parts[0]).trim()
            if (uciRegex.matches(cand)) cand else null
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun fetchFromChessApi(fen: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://chess-api.com/v1").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 2500; readTimeout = 4000
                setRequestProperty("Content-Type", "application/json"); doOutput = true
            }
            conn.outputStream.use { it.write(JSONObject().put("fen", fen).put("depth", 12).toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val move = JSONObject(conn.inputStream.bufferedReader().readText()).optString("move", "")
            if (uciRegex.matches(move)) move else null
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    // ------------------------------------------------------------- Vòng đời

    private fun startForegroundNotification() {
        val channelId = "chess_overlay_channel"
        val channel = NotificationChannel(channelId, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Chess Vision v6.1")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        destroyed = true
        isAutoMode = false
        mainHandler.removeCallbacksAndMessages(null)
        workerExecutor.shutdownNow()
        try { virtualDisplay?.release() } catch (e: Exception) { }
        synchronized(imageLock) { heldImage?.close(); heldImage = null }
        try { imageReader?.close() } catch (e: Exception) { }
        try { mediaProjection?.stop() } catch (e: Exception) { }
        if (::frameThread.isInitialized) frameThread.quitSafely()
        try { if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView) } catch (e: Exception) { }
        try { if (::controlLayout.isInitialized) windowManager.removeView(controlLayout) } catch (e: Exception) { }
        super.onDestroy()
    }
}
