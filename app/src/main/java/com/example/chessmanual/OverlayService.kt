package com.example.chessmanual

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var prefs: SharedPreferences

    private var screenWidth = 1080
    private var screenHeight = 2400

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val moveHistory = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ChessVisualTouchPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        setupBoardOverlay()
        setupFloatingControls()
    }

    private fun setupBoardOverlay() {
        try {
            val savedTopY = prefs.getFloat("BOARD_TOP_Y", (screenHeight - screenWidth) * 0.5f)
            chessOverlayView = ChessOverlayView(this).apply {
                boardTopY = savedTopY
            }
            
            // Khởi tạo mặc định: Trạng thái xuyên cảm ứng để chơi game (FLAG_NOT_TOUCHABLE)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(chessOverlayView, params)

            chessOverlayView.onMoveInputListener = { moveStr ->
                moveHistory.add(moveStr)
                getEngineMove(moveHistory.joinToString(" "))
                
                // Sau khi chạm 2 nhịp xong, tự động TẮT chế độ chạm để trả cảm ứng cho game
                toggleInputMode(false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private lateinit var btnToggleInput: Button

    private fun setupFloatingControls() {
        try {
            controlLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(245, 20, 20, 20))
                setPadding(16, 12, 16, 12)
            }

            tvStatus = TextView(this).apply {
                text = "Căn khung xanh cho khớp mép bàn cờ ->"
                setTextColor(Color.parseColor("#69F0AE"))
                textSize = 12f
            }

            val rowButtons1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnToggleInput = Button(this).apply {
                text = "👆 NHẬP NƯỚC ĐI"
                textSize = 11f
                setOnClickListener {
                    val isCurrentlyInputting = chessOverlayView.isInputMode
                    toggleInputMode(!isCurrentlyInputting)
                }
            }

            val btnUndo = Button(this).apply {
                text = "Lùi lại"
                textSize = 11f
                setOnClickListener {
                    if (moveHistory.isNotEmpty()) {
                        moveHistory.removeAt(moveHistory.size - 1)
                        tvStatus.text = "Lịch sử còn: ${moveHistory.size} nước"
                        chessOverlayView.setMove(null)
                    }
                }
            }

            val btnFlip = Button(this).apply {
                text = "Phe"
                textSize = 11f
                setOnClickListener {
                    chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
                    chessOverlayView.invalidate()
                    tvStatus.text = if (chessOverlayView.isWhiteBottom) "Góc nhìn: Phe Trắng" else "Góc nhìn: Phe Đen"
                }
            }
            
            val btnUp = Button(this).apply {
                text = "▲"
                textSize = 11f
                setOnClickListener { 
                    chessOverlayView.boardTopY -= 15f
                    prefs.edit().putFloat("BOARD_TOP_Y", chessOverlayView.boardTopY).apply()
                    chessOverlayView.invalidate()
                }
            }
            
            val btnDown = Button(this).apply {
                text = "▼"
                textSize = 11f
                setOnClickListener { 
                    chessOverlayView.boardTopY += 15f
                    prefs.edit().putFloat("BOARD_TOP_Y", chessOverlayView.boardTopY).apply()
                    chessOverlayView.invalidate()
                }
            }

            val btnClose = Button(this).apply {
                text = "✕"
                textSize = 11f
                setOnClickListener { stopSelf() }
            }

            rowButtons1.addView(btnToggleInput)
            rowButtons1.addView(btnUndo)
            rowButtons1.addView(btnFlip)
            rowButtons1.addView(btnUp)
            rowButtons1.addView(btnDown)
            rowButtons1.addView(btnClose)

            controlLayout.addView(tvStatus)
            controlLayout.addView(rowButtons1)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 50
            }

            windowManager.addView(controlLayout, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Hàm thay đổi trạng thái cảm ứng của màng kính Overlay
    private fun toggleInputMode(enable: Boolean) {
        chessOverlayView.isInputMode = enable
        if (!enable) chessOverlayView.tapFromSquare = null // Xóa ô vuông đỏ nếu hủy giữa chừng
        
        chessOverlayView.invalidate() // Kích hoạt đổi màu khung viền

        val params = chessOverlayView.layoutParams as WindowManager.LayoutParams
        if (enable) {
            // Khi bật, bỏ cờ NOT_TOUCHABLE đi để hứng cảm ứng ngón tay
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            tvStatus.text = "🔴 CHẠM 2 NHỊP: Ô quân địch vừa đứng -> Ô vừa tới"
            btnToggleInput.text = "✖ HỦY NHẬP"
        } else {
            // Khi tắt, gắn lại cờ NOT_TOUCHABLE để thả cảm ứng rơi xuống game
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            tvStatus.text = "Sẵn sàng | Đã mở khóa chạm cho game"
            btnToggleInput.text = "👆 NHẬP NƯỚC ĐI"
        }
        windowManager.updateViewLayout(chessOverlayView, params)
    }

    private fun getEngineMove(movesStr: String) {
        tvStatus.text = "Stockfish đang tính..."
        workerExecutor.execute {
            try {
                val baseFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                val query = if (movesStr.isEmpty()) baseFen else "$baseFen moves ${movesStr.trim()}"
                val encodedFen = URLEncoder.encode(query, "UTF-8")
                
                val url = URL("https://stockfish.online/api/s/v2.php?fen=$encodedFen&depth=12")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(resp)
                    if (json.optBoolean("success", false)) {
                        val raw = json.optString("bestmove", "").ifEmpty { json.optString("data", "") }
                        val parts = raw.trim().split(" ")
                        val candidate = if (parts.size >= 2) parts[1] else parts[0]
                        val bestMove = if (candidate.length >= 4 && candidate != "(none)") candidate.substring(0, 4) else null

                        mainHandler.post {
                            if (bestMove != null) {
                                chessOverlayView.setMove(bestMove)
                                tvStatus.text = "Đi: ${bestMove.substring(0, 2)} ➔ ${bestMove.substring(2, 4)} | (Đã mở khóa cảm ứng game)"
                            } else {
                                tvStatus.text = "Hết nước đi hợp lệ"
                            }
                        }
                    } else {
                        mainHandler.post { tvStatus.text = "Lỗi tính toán, thử lùi lại (Undo)" }
                    }
                } else {
                    mainHandler.post { tvStatus.text = "Lỗi kết nối API" }
                }
            } catch (e: Exception) {
                mainHandler.post { tvStatus.text = "Lỗi: ${e.message}" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        workerExecutor.shutdownNow()
        if (::chessOverlayView.isInitialized) windowManager.removeView(chessOverlayView)
        if (::controlLayout.isInitialized) windowManager.removeView(controlLayout)
    }
}
