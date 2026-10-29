package com.example.chessmanual

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private lateinit var edtMove: EditText

    private var screenWidth = 1080
    private var screenHeight = 2400

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val moveHistory = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
            chessOverlayView = ChessOverlayView(this).apply {
                boardTopY = (screenHeight - screenWidth) * 0.5f
            }
            // VÁ LỖI CẢM ỨNG 1: Đặt FLAG_NOT_TOUCHABLE để màn hình vẽ mũi tên không cản tay người chơi
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupFloatingControls() {
        try {
            controlLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(245, 20, 20, 20))
                setPadding(16, 12, 16, 12)
            }

            tvStatus = TextView(this).apply {
                text = "Gõ nước đi (Ví dụ: e2e4) -> Bấm Gợi ý"
                setTextColor(Color.parseColor("#69F0AE"))
                textSize = 12f
            }

            val rowInput = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            edtMove = EditText(this).apply {
                hint = "e7e5"
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                minWidth = 200
                setPadding(10, 5, 10, 5)
            }

            val btnSubmit = Button(this).apply {
                text = "Gợi ý"
                textSize = 11f
                setOnClickListener {
                    val text = edtMove.text.toString().trim().lowercase()
                    if (text.length >= 4) {
                        moveHistory.add(text)
                        getEngineMove(moveHistory.joinToString(" "))
                        edtMove.setText("")
                    }
                }
            }

            val btnUndo = Button(this).apply {
                text = "Xóa"
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
                    chessOverlayView.invalidate()
                }
            }
            
            val btnDown = Button(this).apply {
                text = "▼"
                textSize = 11f
                setOnClickListener { 
                    chessOverlayView.boardTopY += 15f
                    chessOverlayView.invalidate()
                }
            }

            val btnClose = Button(this).apply {
                text = "✕"
                textSize = 11f
                setOnClickListener { stopSelf() }
            }

            rowInput.addView(edtMove)
            rowInput.addView(btnSubmit)
            rowInput.addView(btnUndo)
            rowInput.addView(btnFlip)
            rowInput.addView(btnUp)
            rowInput.addView(btnDown)
            rowInput.addView(btnClose)

            controlLayout.addView(tvStatus)
            controlLayout.addView(rowInput)

            // VÁ LỖI CẢM ỨNG 2: Đặt FLAG_NOT_TOUCH_MODAL để vừa gõ được phím, vừa chạm được ra ngoài
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    private fun getEngineMove(movesStr: String) {
        tvStatus.text = "Stockfish đang tính..."
        workerExecutor.execute {
            try {
                // VÁ LỖI API: Tự động nối danh sách nước đi vào FEN khởi tạo chuẩn của UCI
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
                                tvStatus.text = "Gợi ý Stockfish: ${bestMove.substring(0, 2)} ➔ ${bestMove.substring(2, 4)}"
                            } else {
                                tvStatus.text = "Hết nước đi hợp lệ"
                            }
                        }
                    } else {
                        mainHandler.post { tvStatus.text = "Lỗi định dạng nước đi" }
                    }
                } else {
                    mainHandler.post { tvStatus.text = "Lỗi kết nối Stockfish" }
                }
            } catch (e: Exception) {
                mainHandler.post { tvStatus.text = "Lỗi mạng: ${e.message}" }
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
