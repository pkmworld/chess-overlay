package com.example.chessvision

import kotlin.math.abs

/** Bàn cờ dạng Array<CharArray> 8x8: hàng 0 = hạng 8, cột 0 = cột a. '1' = ô trống, HOA = Trắng, thường = Đen. */
class Move(val fr: Int, val fc: Int, val tr: Int, val tc: Int, val promo: Char = ' ') {
    fun uci(): String {
        val sb = StringBuilder()
        sb.append('a' + fc).append('8' - fr).append('a' + tc).append('8' - tr)
        if (promo != ' ') sb.append(promo)
        return sb.toString()
    }
}

class Position(val b: Array<CharArray>, val whiteToMove: Boolean, val castle: String, val ep: String) {
    fun fen(): String =
        Chess.fenBoard(b) + " " + (if (whiteToMove) "w" else "b") + " " + castle + " " + ep + " 0 1"
}

object Chess {
    private val KN = arrayOf(
        intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(-1, 2), intArrayOf(-2, 1),
        intArrayOf(1, -2), intArrayOf(2, -1), intArrayOf(-1, -2), intArrayOf(-2, -1)
    )
    private val ORTHO = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
    private val PROMOS = charArrayOf('q', 'r', 'b', 'n')

    fun copy(b: Array<CharArray>): Array<CharArray> = Array(8) { b[it].clone() }

    fun fenBoard(m: Array<CharArray>): String {
        val rows = ArrayList<String>()
        for (r in 0..7) {
            var empty = 0
            val sb = StringBuilder()
            for (c in 0..7) {
                if (m[r][c] == '1') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(m[r][c])
                }
            }
            if (empty > 0) sb.append(empty)
            rows.add(sb.toString())
        }
        return rows.joinToString("/")
    }

    // ------------------------------------------------------------ Tấn công / chiếu

    private fun slideHit(b: Array<CharArray>, r: Int, c: Int, d: IntArray, p1: Char, p2: Char): Boolean {
        var rr = r + d[0]; var cc = c + d[1]
        while (rr in 0..7 && cc in 0..7) {
            val ch = b[rr][cc]
            if (ch != '1') return ch == p1 || ch == p2
            rr += d[0]; cc += d[1]
        }
        return false
    }

    fun attacked(b: Array<CharArray>, r: Int, c: Int, byWhite: Boolean): Boolean {
        fun at(rr: Int, cc: Int): Char = if (rr in 0..7 && cc in 0..7) b[rr][cc] else '1'
        val pawn = if (byWhite) 'P' else 'p'
        val pr = if (byWhite) r + 1 else r - 1
        if (at(pr, c - 1) == pawn || at(pr, c + 1) == pawn) return true
        val knight = if (byWhite) 'N' else 'n'
        for (d in KN) if (at(r + d[0], c + d[1]) == knight) return true
        val king = if (byWhite) 'K' else 'k'
        for (dr in -1..1) for (dc in -1..1) {
            if ((dr != 0 || dc != 0) && at(r + dr, c + dc) == king) return true
        }
        val rook = if (byWhite) 'R' else 'r'
        val bishop = if (byWhite) 'B' else 'b'
        val queen = if (byWhite) 'Q' else 'q'
        for (d in ORTHO) if (slideHit(b, r, c, d, rook, queen)) return true
        for (d in DIAG) if (slideHit(b, r, c, d, bishop, queen)) return true
        return false
    }

    fun inCheck(b: Array<CharArray>, white: Boolean): Boolean {
        val k = if (white) 'K' else 'k'
        for (r in 0..7) for (c in 0..7) if (b[r][c] == k) return attacked(b, r, c, !white)
        return false
    }

    // ------------------------------------------------------------ Sinh nước đi

    private fun addStep(out: ArrayList<Move>, b: Array<CharArray>, white: Boolean, r: Int, c: Int, nr: Int, nc: Int) {
        if (nr !in 0..7 || nc !in 0..7) return
        val t = b[nr][nc]
        if (t == '1' || t.isUpperCase() != white) out.add(Move(r, c, nr, nc))
    }

    private fun addSlide(out: ArrayList<Move>, b: Array<CharArray>, white: Boolean, r: Int, c: Int, d: IntArray) {
        var rr = r + d[0]; var cc = c + d[1]
        while (rr in 0..7 && cc in 0..7) {
            val t = b[rr][cc]
            if (t == '1') out.add(Move(r, c, rr, cc))
            else {
                if (t.isUpperCase() != white) out.add(Move(r, c, rr, cc))
                break
            }
            rr += d[0]; cc += d[1]
        }
    }

    private fun addPawn(out: ArrayList<Move>, fr: Int, fc: Int, tr: Int, tc: Int, promo: Boolean) {
        if (promo) for (p in PROMOS) out.add(Move(fr, fc, tr, tc, p)) else out.add(Move(fr, fc, tr, tc))
    }

    private fun pseudo(pos: Position): ArrayList<Move> {
        val b = pos.b
        val white = pos.whiteToMove
        val out = ArrayList<Move>(48)
        val epR = if (pos.ep.length == 2) '8' - pos.ep[1] else -1
        val epC = if (pos.ep.length == 2) pos.ep[0] - 'a' else -1

        for (r in 0..7) for (c in 0..7) {
            val ch = b[r][c]
            if (ch == '1' || ch.isUpperCase() != white) continue
            when (ch.uppercaseChar()) {
                'P' -> {
                    val dir = if (white) -1 else 1
                    val startRow = if (white) 6 else 1
                    val lastRow = if (white) 0 else 7
                    val nr = r + dir
                    if (nr in 0..7) {
                        if (b[nr][c] == '1') {
                            addPawn(out, r, c, nr, c, nr == lastRow)
                            if (r == startRow && b[r + 2 * dir][c] == '1') out.add(Move(r, c, r + 2 * dir, c))
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val nc = c + dc
                            if (nc !in 0..7) continue
                            val t = b[nr][nc]
                            if (t != '1' && t.isUpperCase() != white) addPawn(out, r, c, nr, nc, nr == lastRow)
                            else if (t == '1' && nr == epR && nc == epC) out.add(Move(r, c, nr, nc))
                        }
                    }
                }
                'N' -> for (d in KN) addStep(out, b, white, r, c, r + d[0], c + d[1])
                'B' -> for (d in DIAG) addSlide(out, b, white, r, c, d)
                'R' -> for (d in ORTHO) addSlide(out, b, white, r, c, d)
                'Q' -> {
                    for (d in DIAG) addSlide(out, b, white, r, c, d)
                    for (d in ORTHO) addSlide(out, b, white, r, c, d)
                }
                'K' -> {
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr != 0 || dc != 0) addStep(out, b, white, r, c, r + dr, c + dc)
                    }
                    val home = if (white) 7 else 0
                    if (r == home && c == 4 && !attacked(b, home, 4, !white)) {
                        val rk = if (white) 'R' else 'r'
                        val kRight = if (white) 'K' else 'k'
                        val qRight = if (white) 'Q' else 'q'
                        if (kRight in pos.castle && b[home][7] == rk && b[home][5] == '1' && b[home][6] == '1' &&
                            !attacked(b, home, 5, !white) && !attacked(b, home, 6, !white)
                        ) out.add(Move(home, 4, home, 6))
                        if (qRight in pos.castle && b[home][0] == rk && b[home][1] == '1' &&
                            b[home][2] == '1' && b[home][3] == '1' &&
                            !attacked(b, home, 3, !white) && !attacked(b, home, 2, !white)
                        ) out.add(Move(home, 4, home, 2))
                    }
                }
            }
        }
        return out
    }

    private fun stripRights(cs: String, r: Int, c: Int): String {
        var s = cs
        if (r == 7 && c == 4) s = s.replace("K", "").replace("Q", "")
        if (r == 0 && c == 4) s = s.replace("k", "").replace("q", "")
        if (r == 7 && c == 7) s = s.replace("K", "")
        if (r == 7 && c == 0) s = s.replace("Q", "")
        if (r == 0 && c == 7) s = s.replace("k", "")
        if (r == 0 && c == 0) s = s.replace("q", "")
        return s
    }

    fun apply(pos: Position, m: Move): Position {
        val nb = copy(pos.b)
        val piece = nb[m.fr][m.fc]
        val white = pos.whiteToMove
        var ep = "-"
        if (piece.uppercaseChar() == 'P') {
            if (m.fc != m.tc && nb[m.tr][m.tc] == '1') nb[m.fr][m.tc] = '1' // bắt tốt qua đường
            if (abs(m.tr - m.fr) == 2) ep = "" + ('a' + m.fc) + ('8' - (m.fr + m.tr) / 2)
        }
        if (piece.uppercaseChar() == 'K' && abs(m.tc - m.fc) == 2) { // nhập thành: dời xe
            if (m.tc == 6) { nb[m.fr][5] = nb[m.fr][7]; nb[m.fr][7] = '1' }
            else { nb[m.fr][3] = nb[m.fr][0]; nb[m.fr][0] = '1' }
        }
        nb[m.tr][m.tc] = if (m.promo != ' ') {
            if (white) m.promo.uppercaseChar() else m.promo.lowercaseChar()
        } else piece
        nb[m.fr][m.fc] = '1'
        var cs = pos.castle.replace("-", "")
        cs = stripRights(cs, m.fr, m.fc)
        cs = stripRights(cs, m.tr, m.tc)
        return Position(nb, !white, if (cs.isEmpty()) "-" else cs, ep)
    }

    fun legal(pos: Position): List<Move> {
        val res = ArrayList<Move>()
        for (m in pseudo(pos)) {
            val np = apply(pos, m)
            if (!inCheck(np.b, pos.whiteToMove)) res.add(m)
        }
        return res
    }

    // ------------------------------------------------------------ Engine offline (dự phòng)

    private fun pieceValue(t: Char): Int = when (t) {
        'P' -> 100; 'N' -> 320; 'B' -> 330; 'R' -> 500; 'Q' -> 900; else -> 0
    }

    fun evaluate(b: Array<CharArray>, white: Boolean): Int {
        var s = 0
        for (r in 0..7) for (c in 0..7) {
            val ch = b[r][c]
            if (ch == '1') continue
            val center = 14 - abs(2 * c - 7) - abs(2 * r - 7)
            val v = when (ch.uppercaseChar()) {
                'P' -> 100 + center + (if (ch == 'P') (6 - r) else (r - 1)) * 5
                'N' -> 320 + center * 4
                'B' -> 330 + center * 2
                'R' -> 500
                'Q' -> 900
                else -> 0
            }
            if (ch.isUpperCase()) s += v else s -= v
        }
        return if (white) s else -s
    }

    fun order(pos: Position, moves: List<Move>): List<Move> {
        return moves.sortedByDescending { m ->
            val target = pos.b[m.tr][m.tc]
            val attacker = pos.b[m.fr][m.fc].uppercaseChar()
            var sc = 0
            if (target != '1') sc += 10 * pieceValue(target.uppercaseChar()) - pieceValue(attacker)
            if (m.promo != ' ') sc += 800
            sc
        }
    }

    fun bestMove(pos: Position, timeMs: Long): Move? {
        val moves = legal(pos)
        if (moves.isEmpty()) return null
        var best = moves[0]
        var ordered = order(pos, moves)
        val deadline = System.currentTimeMillis() + timeMs
        for (depth in 1..5) {
            val s = Searcher(deadline)
            var alpha = -1000000
            var iterBest: Move? = null
            for (m in ordered) {
                val sc = -s.negamax(apply(pos, m), depth - 1, -1000000, -alpha, 1)
                if (s.timeUp) break
                if (sc > alpha) { alpha = sc; iterBest = m }
            }
            if (s.timeUp) break
            if (iterBest != null) {
                best = iterBest
                val first = iterBest
                ordered = listOf(first) + ordered.filter { it !== first }
            }
            if (alpha > 90000) break
        }
        return best
    }
}

class Searcher(private val deadline: Long) {
    var timeUp = false
    private var nodes = 0

    private fun tick() {
        nodes++
        if ((nodes and 255) == 0 && System.currentTimeMillis() > deadline) timeUp = true
    }

    fun negamax(pos: Position, depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if (depth <= 0) return qsearch(pos, alpha0, beta, 4)
        tick()
        if (timeUp) return 0
        val moves = Chess.legal(pos)
        if (moves.isEmpty()) return if (Chess.inCheck(pos.b, pos.whiteToMove)) -100000 + ply else 0
        var alpha = alpha0
        for (m in Chess.order(pos, moves)) {
            val sc = -negamax(Chess.apply(pos, m), depth - 1, -beta, -alpha, ply + 1)
            if (timeUp) return 0
            if (sc >= beta) return sc
            if (sc > alpha) alpha = sc
        }
        return alpha
    }

    private fun qsearch(pos: Position, alpha0: Int, beta: Int, qd: Int): Int {
        tick()
        if (timeUp) return 0
        val stand = Chess.evaluate(pos.b, pos.whiteToMove)
        if (qd <= 0 || stand >= beta) return stand
        var alpha = maxOf(alpha0, stand)
        val caps = Chess.legal(pos).filter { pos.b[it.tr][it.tc] != '1' }
        for (m in Chess.order(pos, caps)) {
            val sc = -qsearch(Chess.apply(pos, m), -beta, -alpha, qd - 1)
            if (timeUp) return 0
            if (sc >= beta) return sc
            if (sc > alpha) alpha = sc
        }
        return alpha
    }
}
