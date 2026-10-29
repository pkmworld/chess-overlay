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
    
    // Lưu lịch sử các nước đi để tạo FEN đơn giản hoặc gọi trực tiếp API
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
                text = "Nhập nước đối thủ (VD: e7e5) rồi bấm Gợi ý"
                setTextColor(Color.parseColor("#69F0AE"))
                textSize = 12f
            }

            val rowInput = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            edtMove = EditText(this).apply {
                hint = "e.g. e7e5"
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
                text = "Undo"
                textSize = 11f
                setOnClickListener {
                    if (moveHistory.isNotEmpty()) {
                        moveHistory.removeAt(moveHistory.size - 1)
                        tvStatus.text = "Đã xóa nước cuối. Lịch sử: ${moveHistory.size} nước"
                        chessOverlayView.setMove(null)
                    }
                }
            }

            val btnFlip = Button(this).apply {
                text = "Đổi phe"
                textSize = 11f
                setOnClickListener {
                    chessOverlayView.isWhiteBottom = !chessOverlayView.isWhiteBottom
                    chessOverlayView.invalidate()
                    tvStatus.text = if (chessOverlayView.isWhiteBottom) "Đang nhìn từ phía Trắng" else "Đang nhìn từ phía Đen"
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
            rowInput.addView(btnClose)

            controlLayout.addView(tvStatus)
            controlLayout.addView(rowInput)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
                // Sử dụng api tính toán dựa trên danh sách các nước đi (UCI moves)
                val encodedMoves = URLEncoder.encode(movesStr, "UTF-8")
                val url = URL("https://chess-api.com/v1")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                
                val jsonBody = JSONObject().apply {
                    put("fen", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                    put("moves", movesStr)
                    put("depth", 12)
                }
                
                conn.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(resp)
                    val from = json.optString("from", "")
                    val to = json.optString("to", "")
                    val bestMove = if (from.length == 2 && to.length == 2) "$from$to" else null

                    mainHandler.post {
                        if (bestMove != null) {
                            chessOverlayView.setMove(bestMove)
                            tvStatus.text = "Nên đi: ${bestMove.substring(0, 2)} ➔ ${bestMove.substring(2, 4)}"
                        } else {
                            tvStatus.text = "Không tìm thấy nước đi phù hợp"
                        }
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
