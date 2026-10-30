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

class CellData(val mask: BooleanArray, val occupied: Boolean, val coreLum: Int)

class OverlayService : Service() {

    companion object {
        private const val TAG = "ChessVision"
        private const val GRID = 16                  // mỗi ô lấy mẫu 16x16
        private const val DEV_THRESHOLD = 64         // ngưỡng lệch màu so với nền ô
        private const val OCCUPIED_COVERAGE = 0.35f  // tỉ lệ điểm lệch ở vùng giữa ô để coi là có quân
        private const val MATCH_MAX_DIFF = 70        // sai khác tối đa (trên 256) khi so template
        private const val ENGINE_TIME_MS = 2500L
        private const val AUTO_INTERVAL_MS = 1500L
        private const val PREF_TOP_Y = "SAVED_TOP_Y"
        private const val TEMPLATE_CHARS = "KQRBNPkqrbnp"
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
    @Volatile private var whiteBottom = true
    @Volatile private var learnRequested = false
    @Volatile private var lumThr = 128

    // ---- Chỉ đọc/ghi trên worker thread ----
    private val templates = HashMap<Char, ArrayList<BooleanArray>>()
    private var pos: Position? = null
    private var turnAssumed = false
    private var pendingKey = ""
    private var confirmedKey = ""
    private var unexplainedKey = ""
    private var unexplainedCount = 0
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
        loadTemplates()

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
            text = if (templatesReady()) "Sẵn sàng • bấm ⚡ để quét"
            else "Chưa học quân: mở ván mới (thế khởi đầu) rồi bấm 📚"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 11f
            maxWidth = (screenWidth * 0.9f).toInt()
            maxLines = 3
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 0) }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 0) }

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

        val btnTurn = createButton("🔁 Lượt") {
            runOnWorker {
                pos?.let { pos = Position(it.b, !it.whiteToMove, it.castle, "-") }
                turnAssumed = false
                lastSuggestedFen = ""
            }
            scanAndSuggest(true)
        }

        val btnLearn = createButton("📚 Học") {
            learnRequested = true
            tvStatus.text = "Đang học bộ quân từ thế khởi đầu..."
            scanAndSuggest(true)
        }

        val btnUp = createButton("▲") { moveBoard(-12) }
        val btnDown = createButton("▼") { moveBoard(12) }

        val btnReset = createButton("⟲") {
            cachedBoardTopY = -1
            prefs.edit().remove(PREF_TOP_Y).apply()
            runOnWorker { resetGameState() }
            tvStatus.text = "Đang dò lại vị trí bàn cờ..."
            scanAndSuggest(true)
        }

        val btnClose = createButton("✕") { stopSelf() }

        row1.addView(btnSide); row1.addView(btnAuto); row1.addView(btnScan); row1.addView(btnTurn)
        row2.addView(btnLearn); row2.addView(btnUp); row2.addView(btnDown)
        row2.addView(btnReset); row2.addView(btnClose)
        controlLayout.addView(tvStatus); controlLayout.addView(row1); controlLayout.addView(row2)

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

    /** Màu nền ô: trung vị 4 mảng ở mép ô (chịu được ô tô sáng nước vừa đi và chữ tọa độ). */
    private fun estimateBackground(px: IntArray, n: Int, x0: Float, y0: Float, cs: Float): IntArray {
        val spots = arrayOf(
            floatArrayOf(0.04f, 0.04f), floatArrayOf(0.88f, 0.04f),
            floatArrayOf(0.03f, 0.45f), floatArrayOf(0.89f, 0.45f)
        )
        val rs = IntArray(4); val gs = IntArray(4); val bs = IntArray(4)
        for ((k, s) in spots.withIndex()) {
            var sr = 0; var sg = 0; var sb = 0
            for (j in 0 until 4) for (i in 0 until 4) {
                val x = (x0 + (s[0] + i * 0.02f) * cs).toInt().coerceIn(0, n - 1)
                val y = (y0 + (s[1] + j * 0.02f) * cs).toInt().coerceIn(0, n - 1)
                val p = px[y * n + x]
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p)
            }
            rs[k] = sr / 16; gs[k] = sg / 16; bs[k] = sb / 16
        }
        rs.sort(); gs.sort(); bs.sort()
        return intArrayOf((rs[1] + rs[2]) / 2, (gs[1] + gs[2]) / 2, (bs[1] + bs[2]) / 2)
    }

    private fun readCells(px: IntArray, n: Int): Array<Array<CellData>> {
        val cs = n / 8f
        return Array(8) { r ->
            Array(8) { c ->
                val x0 = c * cs; val y0 = r * cs
                val bg = estimateBackground(px, n, x0, y0, cs)
                val mask = BooleanArray(GRID * GRID)
                var centerDev = 0
                val lums = IntArray(16); var li = 0
                for (gy in 0 until GRID) for (gx in 0 until GRID) {
                    val x = (x0 + (gx + 0.5f) * cs / GRID).toInt().coerceIn(0, n - 1)
                    val y = (y0 + (gy + 0.5f) * cs / GRID).toInt().coerceIn(0, n - 1)
                    val p = px[y * n + x]
                    val dev = abs(Color.red(p) - bg[0]) + abs(Color.green(p) - bg[1]) +
                            abs(Color.blue(p) - bg[2]) > DEV_THRESHOLD
                    mask[gy * GRID + gx] = dev
                    if (dev && gx in 4..11 && gy in 4..11) centerDev++
                    if (gx in 6..9 && gy in 6..9) lums[li++] = lum(p)
                }
                lums.sort()
                CellData(mask, centerDev / 64f >= OCCUPIED_COVERAGE, lums[8])
            }
        }
    }

    private fun classify(cell: CellData, white: Boolean): Char {
        var best = '?'; var bestDiff = Int.MAX_VALUE
        for (t in "KQRBNP") {
            val ch = if (white) t else t.lowercaseChar()
            val list = templates[ch] ?: continue
            for (tpl in list) {
                var d = 0
                for (i in tpl.indices) if (cell.mask[i] != tpl[i]) d++
                if (d < bestDiff) { bestDiff = d; best = ch }
            }
        }
        return if (bestDiff <= MATCH_MAX_DIFF) best else '?'
    }

    // ------------------------------------------------------------ Học template

    private fun templatesReady(): Boolean = TEMPLATE_CHARS.all { templates[it]?.isNotEmpty() == true }

    private fun loadTemplates() {
        for (ch in TEMPLATE_CHARS) {
            val s = prefs.getString("TPL_${ch.code}", null) ?: continue
            val list = ArrayList<BooleanArray>()
            for (part in s.split(",")) {
                if (part.length == GRID * GRID) list.add(BooleanArray(part.length) { part[it] == '1' })
            }
            if (list.isNotEmpty()) templates[ch] = list
        }
        lumThr = prefs.getInt("LUM_THR", 128)
        whiteBottom = prefs.getBoolean("WHITE_BOTTOM", true)
    }

    private fun learnTemplates(cells: Array<Array<CellData>>) {
        for (r in 0..7) for (c in 0..7) {
            if (cells[r][c].occupied != (r <= 1 || r >= 6)) {
                setStatus("Chưa phải thế khởi đầu (hoặc bàn cờ lệch). Mở ván mới, chỉnh ▲▼ rồi bấm 📚")
                return
            }
        }
        val topLum = (0..1).flatMap { r -> (0..7).map { cells[r][it].coreLum } }.average()
        val botLum = (6..7).flatMap { r -> (0..7).map { cells[r][it].coreLum } }.average()
        if (abs(botLum - topLum) < 40) {
            setStatus("Không phân biệt được màu quân Trắng/Đen (độ tương phản thấp)")
            return
        }
        val wb = botLum > topLum
        lumThr = ((botLum + topLum) / 2).toInt()
        val order = if (wb) "RNBQKBNR" else "RNBKQBNR"
        fun botCase(ch: Char) = if (wb) ch else ch.lowercaseChar()
        fun topCase(ch: Char) = if (wb) ch.lowercaseChar() else ch

        templates.clear()
        fun addTpl(ch: Char, m: BooleanArray) { templates.getOrPut(ch) { ArrayList() }.add(m) }
        for (c in 0..7) {
            addTpl(botCase(order[c]), cells[7][c].mask)
            addTpl(topCase(order[c]), cells[0][c].mask)
            addTpl(botCase('P'), cells[6][c].mask)
            addTpl(topCase('P'), cells[1][c].mask)
        }

        val ed = prefs.edit()
        for ((ch, list) in templates) {
            ed.putString("TPL_${ch.code}", list.joinToString(",") { m -> m.joinToString("") { if (it) "1" else "0" } })
        }
        ed.putInt("LUM_THR", lumThr).putBoolean("WHITE_BOTTOM", wb).apply()

        whiteBottom = wb
        resetGameState()
        mainHandler.post {
            chessOverlayView.isWhiteBottom = wb
            btnSide.text = if (wb) "⚪ Trắng" else "⚫ Đen"
            chessOverlayView.setMove(null)
            tvStatus.text = "✅ Đã học ${templates.size} loại quân • bạn cầm ${if (wb) "Trắng" else "Đen"}"
            mainHandler.postDelayed({ scanAndSuggest(true) }, 600)
        }
    }

    private fun resetGameState() {
        pos = null; turnAssumed = false
        pendingKey = ""; confirmedKey = ""
        unexplainedKey = ""; unexplainedCount = 0
        lastSuggestedFen = ""
    }

    // ---------------------------------------------------------------- Quét chính

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
        val cells = readCells(px, screenWidth)

        if (learnRequested) { learnRequested = false; learnTemplates(cells); return }
        if (!templatesReady()) {
            setStatus("Chưa học quân: mở ván mới (thế khởi đầu) rồi bấm 📚"); return
        }

        var pieceCount = 0
        for (r in 0..7) for (c in 0..7) if (cells[r][c].occupied) pieceCount++
        if (pieceCount < 2) { setStatus("Chưa thấy quân cờ • kiểm tra 4 góc xanh / chỉnh ▲▼"); return }
        if (pieceCount > 32) {
            setStatus("Thấy $pieceCount quân (quá nhiều) • tắt chấm gợi ý nước đi trong app cờ hoặc chỉnh ▲▼"); return
        }

        val raw = Array(8) { CharArray(8) { '1' } }
        for (r in 0..7) for (c in 0..7) {
            val cell = cells[r][c]
            if (!cell.occupied) continue
            val ch = classify(cell, cell.coreLum >= lumThr)
            if (ch == '?') {
                setStatus("Có quân không nhận ra • thử 📚 học lại nếu đổi bộ quân/theme"); return
            }
            raw[r][c] = ch
        }
        val white = whiteBottom
        val std = if (white) raw else Array(8) { r -> CharArray(8) { c -> raw[7 - r][7 - c] } }
        val key = Chess.fenBoard(std)
        Log.d(TAG, "board=$key")

        validateBoard(std)?.let { setStatus("Đọc bàn cờ chưa chắc: $it"); return }

        // Auto: cần 2 lần quét liên tiếp giống nhau mới chấp nhận (tránh đọc giữa lúc quân đang di chuyển)
        if (!manual && key != confirmedKey) {
            if (key != pendingKey) { pendingKey = key; return }
        }
        confirmedKey = key; pendingKey = ""

        val p = syncPosition(std, manual) ?: return

        if (p.whiteToMove != white) {
            mainHandler.post { chessOverlayView.setMove(null) }
            setStatus("⏳ Chờ đối thủ (lượt ${if (p.whiteToMove) "Trắng" else "Đen"})" +
                    (if (turnAssumed) " • lượt giả định, bấm 🔁 nếu sai" else ""))
            return
        }
        suggest(p, manual, pieceCount)
    }

    // ------------------------------------------------------ Theo dõi trạng thái ván

    private fun isStart(m: Array<CharArray>): Boolean {
        for (r in 0..7) if (String(m[r]) != START_ROWS[r]) return false
        return true
    }

    private fun syncPosition(std: Array<CharArray>, manual: Boolean): Position? {
        val cur = pos ?: return resync(std)
        if (cur.sameBoard(std)) return cur

        val next = findTransition(cur, std)
        if (next != null) {
            pos = next; turnAssumed = false
            unexplainedKey = ""; unexplainedCount = 0
            return next
        }
        if (isStart(std)) return resync(std)

        val key = Chess.fenBoard(std)
        if (key == unexplainedKey) unexplainedCount++ else { unexplainedKey = key; unexplainedCount = 1 }
        if (manual || unexplainedCount >= 2) return resync(std)
        setStatus("Thế cờ chưa khớp nước đi hợp lệ nào, đang xác nhận lại...")
        return null
    }

    /** Thế mới có phải kết quả của 1 hoặc 2 nước hợp lệ từ thế cũ không? */
    private fun findTransition(cur: Position, std: Array<CharArray>): Position? {
        val l1 = Chess.legal(cur)
        for (m in l1) {
            val p1 = Chess.apply(cur, m)
            if (p1.sameBoard(std)) return p1
        }
        for (m in l1) {
            val p1 = Chess.apply(cur, m)
            for (m2 in Chess.legal(p1)) {
                val p2 = Chess.apply(p1, m2)
                if (p2.sameBoard(std)) return p2
            }
        }
        return null
    }

    /** Khởi tạo/đồng bộ lại: không biết lịch sử nên không cho nhập thành (trừ thế khởi đầu), lượt đi là giả định. */
    private fun resync(std: Array<CharArray>): Position? {
        unexplainedKey = ""; unexplainedCount = 0
        val start = isStart(std)
        var p = Position(Chess.copy(std), if (start) true else whiteBottom, if (start) "KQkq" else "-", "-")
        turnAssumed = !start
        // Bên không có lượt mà đang bị chiếu là bất hợp lệ -> lượt phải là của bên kia
        if (Chess.inCheck(p.b, !p.whiteToMove)) p = Position(p.b, !p.whiteToMove, "-", "-")
        if (Chess.inCheck(p.b, !p.whiteToMove)) {
            setStatus("Cả hai Vua đều bị chiếu → chắc chắn đọc sai bàn cờ"); pos = null; return null
        }
        pos = p
        lastSuggestedFen = ""
        return p
    }

    private fun validateBoard(m: Array<CharArray>): String? {
        var wk = 0; var bk = 0; var wn = 0; var bn = 0; var wp = 0; var bp = 0
        var wkR = -1; var wkC = -1; var bkR = -1; var bkC = -1
        for (r in 0..7) for (c in 0..7) {
            val ch = m[r][c]
            if (ch == '1') continue
            if (ch.isUpperCase()) wn++ else bn++
            when (ch) {
                'K' -> { wk++; wkR = r; wkC = c }
                'k' -> { bk++; bkR = r; bkC = c }
                'P' -> { wp++; if (r == 0 || r == 7) return "tốt ở hàng cuối" }
                'p' -> { bp++; if (r == 0 || r == 7) return "tốt ở hàng cuối" }
            }
        }
        if (wk != 1 || bk != 1) return "cần đúng 1 Vua mỗi bên (thấy $wk trắng / $bk đen)"
        if (wn > 16 || bn > 16 || wp > 8 || bp > 8) return "số quân vượt mức hợp lệ"
        if (abs(wkR - bkR) <= 1 && abs(wkC - bkC) <= 1) return "hai Vua đứng sát nhau"
        return null
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
        val text = "✅ ${mv.substring(0, 2).uppercase()} ➔ ${mv.substring(2, 4).uppercase()}$promo " +
                "($src, $pieceCount quân)" + (if (turnAssumed) " • lượt giả định, 🔁 nếu sai" else "")
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
            .setContentTitle("Chess Vision v6.0")
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
