package com.example.chesshelper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private var pos: Position = Position.start()
    private val history = ArrayList<Position>()
    private var editMode = false
    private var flipped = false

    /** 0 = tự động (gợi ý cho bên đang đến lượt), 1 = tôi cầm Trắng, 2 = tôi cầm Đen */
    private var userSide = 0
    private var strength = 1
    private val strengths = listOf("Nhanh" to 600L, "Vừa" to 2000L, "Mạnh" to 6000L)

    private var selected = -1
    private var targets: List<Int> = emptyList()
    private var hint: Move? = null
    private var palette: Int? = null

    private var analysisId = 0
    private var currentEngine: Engine? = null

    private lateinit var board: BoardView
    private lateinit var status: TextView
    private lateinit var paletteScroll: HorizontalScrollView
    private lateinit var sideBtn: Button
    private lateinit var turnBtn: Button
    private lateinit var strengthBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var clearBtn: Button
    private val paletteViews = ArrayList<Pair<Int, TextView>>()

    private val photoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) handlePhotoResult(res.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
        maybeAnalyze()
    }

    override fun onDestroy() {
        currentEngine?.stop = true
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 13f
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (v in views) l.addView(v)
        return l
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(10), dp(10), dp(16))
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Trợ lý cờ vua"
        title.textSize = 20f
        title.setTextColor(Color.parseColor("#1B5E20"))
        title.setPadding(dp(4), 0, 0, dp(6))
        root.addView(title)

        board = BoardView(this)
        board.onSquareTap = { onTap(it) }
        root.addView(
            board,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        status = TextView(this)
        status.textSize = 16f
        status.setTextColor(Color.parseColor("#212121"))
        status.setPadding(dp(4), dp(8), dp(4), dp(8))
        root.addView(status)

        // Thanh chọn quân (chế độ xếp quân)
        paletteScroll = HorizontalScrollView(this)
        val pal = LinearLayout(this)
        pal.orientation = LinearLayout.HORIZONTAL
        val items = listOf(6, 5, 4, 3, 2, 1, -6, -5, -4, -3, -2, -1, 0)
        for (v in items) {
            val tv = TextView(this)
            tv.text = if (v == 0) "✕" else glyph(abs(v))
            tv.textSize = 28f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            tv.layoutParams = lp
            tv.setOnClickListener {
                palette = v
                updatePaletteUi()
            }
            pal.addView(tv)
            paletteViews.add(v to tv)
        }
        paletteScroll.addView(pal)
        paletteScroll.visibility = View.GONE
        root.addView(paletteScroll)
        updatePaletteUi()

        sideBtn = btn("") { cycleSide() }
        turnBtn = btn("") { toggleTurn() }
        strengthBtn = btn("") { cycleStrength() }
        root.addView(row(sideBtn, turnBtn, strengthBtn))

        modeBtn = btn("") { setEditMode(!editMode) }
        root.addView(
            row(
                modeBtn,
                btn("Phân tích") { analyze() },
                btn("Hoàn tác") { undo() }
            )
        )

        clearBtn = btn("Bàn trống") { clearBoard() }
        root.addView(
            row(
                btn("Ảnh bàn cờ") { photoLauncher.launch(Intent(this, PhotoActivity::class.java)) },
                btn("FEN") { showFenDialog() },
                btn("Ván mới") { newGame() },
                btn("Lật bàn") { flipped = !flipped; refresh() }
            )
        )
        root.addView(row(clearBtn))
        clearBtn.visibility = View.GONE

        val tip = TextView(this)
        tip.text = "Cách dùng: đi quân bằng cách chạm quân rồi chạm ô đến (cả quân của đối thủ). " +
            "Để thiết lập thế cờ đang chơi dở, bấm \"Xếp quân\", chọn quân ở thanh trên rồi chạm ô. " +
            "Quân nhấp nháy + mũi tên xanh = nước đi tốt nhất."
        tip.textSize = 12f
        tip.setTextColor(Color.parseColor("#616161"))
        tip.setPadding(dp(4), dp(10), dp(4), 0)
        root.addView(tip)
    }

    private fun updatePaletteUi() {
        for ((v, tv) in paletteViews) {
            val selectedItem = palette == v
            when {
                selectedItem -> { tv.setBackgroundColor(Color.parseColor("#4CAF50")); tv.setTextColor(Color.WHITE) }
                v > 0 -> { tv.setBackgroundColor(Color.parseColor("#8D6E63")); tv.setTextColor(Color.WHITE) }
                v < 0 -> { tv.setBackgroundColor(Color.parseColor("#FFE0B2")); tv.setTextColor(Color.BLACK) }
                else -> { tv.setBackgroundColor(Color.parseColor("#EEEEEE")); tv.setTextColor(Color.BLACK) }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun refresh() {
        board.pos = pos
        board.flipped = flipped
        board.hint = hint
        board.selected = selected
        board.targets = targets
        board.invalidate()
        sideBtn.text = "Tôi cầm: " + when (userSide) { 1 -> "Trắng"; 2 -> "Đen"; else -> "Tự động" }
        turnBtn.text = "Lượt: " + if (pos.whiteToMove) "Trắng" else "Đen"
        strengthBtn.text = "Mức: " + strengths[strength].first
        modeBtn.text = if (editMode) "Xong, chơi cờ" else "Xếp quân"
        paletteScroll.visibility = if (editMode) View.VISIBLE else View.GONE
        clearBtn.visibility = if (editMode) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- Thao tác trên bàn cờ

    private fun onTap(sq: Int) {
        if (editMode) editSquare(sq) else playTap(sq)
    }

    private fun pushHistory() {
        history.add(pos)
        if (history.size > 200) history.removeAt(0)
    }

    private fun cancelAnalysis() {
        analysisId++
        currentEngine?.stop = true
        currentEngine = null
    }

    private fun editSquare(sq: Int) {
        val sel = palette
        if (sel == null) {
            toast("Chọn quân ở thanh phía trên trước")
            return
        }
        cancelAnalysis()
        pushHistory()
        val p = pos.copy()
        p.b[sq] = sel
        p.ep = -1
        p.inferCastling()
        pos = p
        hint = null
        refresh()
        setStatus("Đang xếp quân. Xong thì bấm \"Phân tích\".")
    }

    private fun playTap(sq: Int) {
        val pc = pos.b[sq]
        val mine = pc != 0 && (pc > 0) == pos.whiteToMove && abs(pc) != Pc.UNKNOWN
        if (selected == -1) {
            if (mine) select(sq)
            return
        }
        if (sq == selected) {
            selected = -1
            targets = emptyList()
            refresh()
            return
        }
        if (mine) {
            select(sq)
            return
        }
        if (sq in targets) {
            doMove(selected, sq)
        } else {
            selected = -1
            targets = emptyList()
            refresh()
        }
    }

    private fun select(sq: Int) {
        selected = sq
        targets = Gen.legal(pos).filter { it.from == sq }.map { it.to }.distinct()
        refresh()
    }

    private fun doMove(from: Int, to: Int) {
        val cands = Gen.legal(pos).filter { it.from == from && it.to == to }
        if (cands.isEmpty()) return
        if (cands.size > 1) {
            val names = arrayOf("Hậu", "Xe", "Tượng", "Mã")
            val codes = intArrayOf(5, 4, 3, 2)
            AlertDialog.Builder(this)
                .setTitle("Phong cấp thành")
                .setItems(names) { _, i ->
                    val m = cands.firstOrNull { it.promo == codes[i] }
                    if (m != null) applyMove(m)
                }
                .show()
        } else {
            applyMove(cands[0])
        }
    }

    private fun applyMove(m: Move) {
        cancelAnalysis()
        pushHistory()
        pos = Gen.make(pos, m)
        selected = -1
        targets = emptyList()
        hint = null
        refresh()
        maybeAnalyze()
    }

    // ---------------------------------------------------------------- Nút bấm

    private fun cycleSide() {
        userSide = (userSide + 1) % 3
        if (userSide == 1) flipped = false
        if (userSide == 2) flipped = true
        hint = null
        cancelAnalysis()
        refresh()
        if (!editMode) maybeAnalyze()
    }

    private fun toggleTurn() {
        cancelAnalysis()
        val p = pos.copy()
        p.whiteToMove = !p.whiteToMove
        p.ep = -1
        pushHistory()
        pos = p
        hint = null
        selected = -1
        targets = emptyList()
        refresh()
        if (!editMode) maybeAnalyze()
    }

    private fun cycleStrength() {
        strength = (strength + 1) % strengths.size
        refresh()
        if (!editMode) maybeAnalyze()
    }

    private fun setEditMode(on: Boolean) {
        editMode = on
        selected = -1
        targets = emptyList()
        cancelAnalysis()
        if (on) {
            hint = null
            refresh()
            setStatus("Chế độ xếp quân: chọn quân ở thanh trên rồi chạm vào ô. Chọn ✕ để xóa quân. Nút \"Lượt\" đổi bên đi tiếp.")
        } else {
            refresh()
            maybeAnalyze()
        }
    }

    private fun clearBoard() {
        cancelAnalysis()
        pushHistory()
        val p = Position()
        p.whiteToMove = pos.whiteToMove
        pos = p
        hint = null
        refresh()
    }

    private fun undo() {
        if (history.isEmpty()) {
            toast("Không còn gì để hoàn tác")
            return
        }
        cancelAnalysis()
        pos = history.removeAt(history.size - 1)
        hint = null
        selected = -1
        targets = emptyList()
        refresh()
        if (!editMode) maybeAnalyze()
    }

    private fun newGame() {
        cancelAnalysis()
        pushHistory()
        pos = Position.start()
        hint = null
        selected = -1
        targets = emptyList()
        editMode = false
        refresh()
        maybeAnalyze()
    }

    private fun showFenDialog() {
        val input = EditText(this)
        input.setText(pos.toFen())
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.textSize = 13f
        AlertDialog.Builder(this)
            .setTitle("FEN của thế cờ")
            .setMessage("Dán FEN rồi bấm Nạp để phân tích thế cờ đó.")
            .setView(input)
            .setPositiveButton("Nạp") { _, _ ->
                val p = Position.fromFen(input.text.toString())
                if (p == null) {
                    toast("FEN không hợp lệ")
                } else {
                    cancelAnalysis()
                    pushHistory()
                    pos = p
                    hint = null
                    selected = -1
                    targets = emptyList()
                    editMode = false
                    refresh()
                    maybeAnalyze()
                }
            }
            .setNeutralButton("Sao chép") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("FEN", pos.toFen()))
                toast("Đã sao chép FEN")
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun handlePhotoResult(data: Intent?) {
        val arr = data?.getIntArrayExtra("board") ?: return
        if (arr.size != 64) return
        val side = data.getIntExtra("side", 1)
        cancelAnalysis()
        pushHistory()
        val p = Position()
        System.arraycopy(arr, 0, p.b, 0, 64)
        p.whiteToMove = side == 1
        p.inferCastling()
        pos = p
        userSide = side
        flipped = side == 2
        hint = null
        selected = -1
        targets = emptyList()
        setEditMode(true)
        val unknown = arr.count { abs(it) == Pc.UNKNOWN }
        setStatus(
            "Đã đọc thế cờ từ ảnh (bạn cầm " + (if (side == 1) "Trắng" else "Đen") + "). " +
                (if (unknown > 0) "Có $unknown quân dấu \"?\" chưa biết loại - hãy chọn quân ở thanh trên rồi chạm vào ô đó để sửa. "
                else "") +
                "Kiểm tra lại toàn bộ bàn cờ rồi bấm \"Xong, chơi cờ\" hoặc \"Phân tích\"."
        )
    }

    // ---------------------------------------------------------------- Phân tích

    private fun validate(p: Position): String? {
        var wk = 0
        var bk = 0
        for (s in 0..63) {
            val v = p.b[s]
            if (abs(v) == Pc.UNKNOWN) return "Còn quân chưa xác định (?). Bấm \"Xếp quân\" để sửa."
            if (v == Pc.KING) wk++
            if (v == -Pc.KING) bk++
            if (abs(v) == Pc.PAWN && ((s shr 3) == 0 || (s shr 3) == 7)) {
                return "Thế cờ không hợp lệ: Tốt không thể ở hàng 1 hoặc hàng 8."
            }
        }
        if (wk != 1 || bk != 1) return "Mỗi bên phải có đúng 1 Vua (hiện có Trắng: $wk, Đen: $bk)."
        if (Gen.inCheck(p, !p.whiteToMove)) {
            return "Thế cờ không hợp lệ: bên chưa có lượt đang bị chiếu. Hãy đổi nút \"Lượt\"."
        }
        return null
    }

    private fun sideName(white: Boolean) = if (white) "Trắng" else "Đen"

    private fun maybeAnalyze() {
        if (editMode) return
        val err = validate(pos)
        if (err != null) {
            setStatus(err)
            return
        }
        val myTurn = userSide == 0 || (pos.whiteToMove == (userSide == 1))
        if (!myTurn) {
            if (Gen.legal(pos).isEmpty()) {
                showGameOver()
                return
            }
            setStatus(
                "Đến lượt đối thủ (${sideName(pos.whiteToMove)}). Hãy đi nước của họ trên bàn cờ - " +
                    "hoặc bấm \"Phân tích\" để xem gợi ý cho bên này."
            )
            return
        }
        analyze()
    }

    private fun showGameOver() {
        hint = null
        refresh()
        if (Gen.inCheck(pos, pos.whiteToMove)) {
            setStatus("Chiếu hết! ${sideName(!pos.whiteToMove)} thắng.")
        } else {
            setStatus("Hòa cờ (hết nước đi, không bị chiếu).")
        }
    }

    private fun analyze() {
        if (editMode) {
            editMode = false
            refresh()
        }
        val err = validate(pos)
        if (err != null) {
            setStatus(err)
            return
        }
        if (Gen.legal(pos).isEmpty()) {
            showGameOver()
            return
        }
        cancelAnalysis()
        val myId = analysisId
        hint = null
        selected = -1
        targets = emptyList()
        refresh()
        setStatus("Đang phân tích cho ${sideName(pos.whiteToMove)}…")
        val snap = pos.copy()
        val eng = Engine()
        currentEngine = eng
        val ms = strengths[strength].second
        Thread {
            val r = eng.search(snap, ms)
            runOnUiThread {
                if (myId == analysisId && !isFinishing) showResult(snap, r)
            }
        }.start()
    }

    private fun showResult(snap: Position, r: Engine.Result) {
        val m = r.move ?: return
        hint = m
        refresh()
        setStatus(
            "Nước đi tốt nhất cho ${sideName(snap.whiteToMove)}: ${describe(snap, m)}\n" +
                evalText(snap, r) + "  (độ sâu ${r.depth})"
        )
    }

    private fun pieceName(t: Int) = when (t) {
        Pc.PAWN -> "Tốt"
        Pc.KNIGHT -> "Mã"
        Pc.BISHOP -> "Tượng"
        Pc.ROOK -> "Xe"
        Pc.QUEEN -> "Hậu"
        Pc.KING -> "Vua"
        else -> "?"
    }

    private fun describe(p: Position, m: Move): String {
        val t = abs(p.b[m.from])
        if (t == Pc.KING && abs(m.to - m.from) == 2) {
            return if (m.to > m.from) "Nhập thành ngắn" else "Nhập thành dài"
        }
        val capture = p.b[m.to] != 0 || (t == Pc.PAWN && (m.from and 7) != (m.to and 7))
        var s = pieceName(t) + " " + sqName(m.from) + (if (capture) " ăn " else " → ") + sqName(m.to)
        if (m.promo != 0) s += " (phong " + pieceName(m.promo) + ")"
        return s
    }

    private fun evalText(snap: Position, r: Engine.Result): String {
        val sc = r.score
        if (abs(sc) > Engine.MATE - 100) {
            val n = (Engine.MATE - abs(sc) + 1) / 2
            val winnerWhite = (sc > 0) == snap.whiteToMove
            return "${sideName(winnerWhite)} chiếu hết sau khoảng $n nước"
        }
        val w = if (snap.whiteToMove) sc else -sc
        if (abs(w) < 25) return "Thế cờ cân bằng"
        val v = String.format(Locale.US, "%.1f", abs(w) / 100f)
        return "${sideName(w > 0)} hơn khoảng $v điểm"
    }
}
