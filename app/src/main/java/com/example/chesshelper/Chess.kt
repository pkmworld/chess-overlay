package com.example.chesshelper

import kotlin.math.abs

/** Mã quân: dương = Trắng, âm = Đen. */
object Pc {
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6
    /** Quân chưa xác định (dùng khi nhận diện ảnh). */
    const val UNKNOWN = 7
}

fun sqName(s: Int): String = "" + ('a' + (s and 7)) + ('1' + (s shr 3))

class Move(val from: Int, val to: Int, val promo: Int = 0) {
    fun uci(): String {
        val p = if (promo != 0) "nbrq"[promo - 2].toString() else ""
        return sqName(from) + sqName(to) + p
    }

    override fun equals(other: Any?): Boolean =
        other is Move && other.from == from && other.to == to && other.promo == promo

    override fun hashCode(): Int = from * 4096 + to * 8 + promo
}

/** Ô = rank * 8 + file; rank 0 = hàng 1 (phía Trắng), file 0 = cột a. */
class Position {
    val b = IntArray(64)
    var whiteToMove = true
    /** bit 1 = Trắng nhập thành ngắn, 2 = Trắng dài, 4 = Đen ngắn, 8 = Đen dài */
    var castle = 0
    var ep = -1
    var half = 0
    var full = 1

    fun copy(): Position {
        val p = Position()
        System.arraycopy(b, 0, p.b, 0, 64)
        p.whiteToMove = whiteToMove
        p.castle = castle
        p.ep = ep
        p.half = half
        p.full = full
        return p
    }

    /** Đoán quyền nhập thành: còn Vua và Xe ở ô gốc thì coi như còn quyền. */
    fun inferCastling() {
        var c = 0
        if (b[4] == Pc.KING && b[7] == Pc.ROOK) c = c or 1
        if (b[4] == Pc.KING && b[0] == Pc.ROOK) c = c or 2
        if (b[60] == -Pc.KING && b[63] == -Pc.ROOK) c = c or 4
        if (b[60] == -Pc.KING && b[56] == -Pc.ROOK) c = c or 8
        castle = c
    }

    fun hasUnknown(): Boolean {
        for (v in b) if (abs(v) == Pc.UNKNOWN) return true
        return false
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val v = b[r * 8 + f]
                if (v == 0) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    val ch = "?pnbrqk?"[abs(v)]
                    sb.append(if (v > 0) ch.uppercaseChar() else ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        var cs = ""
        if (castle and 1 != 0) cs += "K"
        if (castle and 2 != 0) cs += "Q"
        if (castle and 4 != 0) cs += "k"
        if (castle and 8 != 0) cs += "q"
        sb.append(if (cs.isEmpty()) "-" else cs)
        sb.append(' ')
        sb.append(if (ep >= 0) sqName(ep) else "-")
        sb.append(' ').append(half).append(' ').append(full)
        return sb.toString()
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun start(): Position = fromFen(START_FEN)!!

        fun fromFen(fen: String): Position? {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0].isEmpty()) return null
            val ranks = parts[0].split("/")
            if (ranks.size != 8) return null
            val p = Position()
            for (i in 0..7) {
                val r = 7 - i
                var f = 0
                for (ch in ranks[i]) {
                    if (ch.isDigit()) {
                        f += ch - '0'
                    } else {
                        val idx = "pnbrqk".indexOf(ch.lowercaseChar())
                        if (idx < 0 || f > 7) return null
                        p.b[r * 8 + f] = if (ch.isUpperCase()) idx + 1 else -(idx + 1)
                        f++
                    }
                }
                if (f != 8) return null
            }
            p.whiteToMove = if (parts.size > 1) parts[1] != "b" else true
            if (parts.size > 2 && parts[2] != "-") {
                var c = 0
                for (ch in parts[2]) {
                    when (ch) {
                        'K' -> c = c or 1
                        'Q' -> c = c or 2
                        'k' -> c = c or 4
                        'q' -> c = c or 8
                    }
                }
                p.castle = c
            } else if (parts.size <= 2) {
                p.inferCastling()
            }
            if (parts.size > 3 && parts[3].length == 2) {
                val f = parts[3][0] - 'a'
                val r = parts[3][1] - '1'
                if (f in 0..7 && r in 0..7) p.ep = r * 8 + f
            }
            if (parts.size > 4) p.half = parts[4].toIntOrNull() ?: 0
            if (parts.size > 5) p.full = parts[5].toIntOrNull() ?: 1
            return p
        }
    }
}

object Gen {
    private val KN_DF = intArrayOf(1, 2, 2, 1, -1, -2, -2, -1)
    private val KN_DR = intArrayOf(2, 1, -1, -2, -2, -1, 1, 2)
    private val K_DF = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val K_DR = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val B_DF = intArrayOf(1, 1, -1, -1)
    private val B_DR = intArrayOf(1, -1, 1, -1)
    private val R_DF = intArrayOf(1, -1, 0, 0)
    private val R_DR = intArrayOf(0, 0, 1, -1)

    /** Mặt nạ quyền nhập thành giữ lại khi một quân đi từ/đến ô này. */
    private val CMASK = IntArray(64) { 15 }.also {
        it[0] = 13; it[7] = 14; it[4] = 12
        it[56] = 7; it[63] = 11; it[60] = 3
    }

    fun kingSq(p: Position, white: Boolean): Int {
        val k = if (white) Pc.KING else -Pc.KING
        for (s in 0..63) if (p.b[s] == k) return s
        return -1
    }

    fun inCheck(p: Position, white: Boolean): Boolean {
        val k = kingSq(p, white)
        return k >= 0 && attacked(p, k, !white)
    }

    /** Ô [sq] có bị bên [byWhite] tấn công không. */
    fun attacked(p: Position, sq: Int, byWhite: Boolean): Boolean {
        val f = sq and 7
        val r = sq shr 3
        val sgn = if (byWhite) 1 else -1
        val pr = r - sgn
        if (pr in 0..7) {
            for (k in 0..1) {
                val pf = f + 2 * k - 1
                if (pf in 0..7 && p.b[pr * 8 + pf] == sgn * Pc.PAWN) return true
            }
        }
        for (i in 0..7) {
            val nf = f + KN_DF[i]
            val nr = r + KN_DR[i]
            if (nf in 0..7 && nr in 0..7 && p.b[nr * 8 + nf] == sgn * Pc.KNIGHT) return true
            val kf = f + K_DF[i]
            val kr = r + K_DR[i]
            if (kf in 0..7 && kr in 0..7 && p.b[kr * 8 + kf] == sgn * Pc.KING) return true
        }
        for (i in 0..3) {
            var nf = f + B_DF[i]
            var nr = r + B_DR[i]
            while (nf in 0..7 && nr in 0..7) {
                val v = p.b[nr * 8 + nf]
                if (v != 0) {
                    if (v == sgn * Pc.BISHOP || v == sgn * Pc.QUEEN) return true
                    break
                }
                nf += B_DF[i]; nr += B_DR[i]
            }
            nf = f + R_DF[i]
            nr = r + R_DR[i]
            while (nf in 0..7 && nr in 0..7) {
                val v = p.b[nr * 8 + nf]
                if (v != 0) {
                    if (v == sgn * Pc.ROOK || v == sgn * Pc.QUEEN) return true
                    break
                }
                nf += R_DF[i]; nr += R_DR[i]
            }
        }
        return false
    }

    private fun addPawn(out: ArrayList<Move>, from: Int, to: Int, promo: Boolean) {
        if (promo) {
            for (q in 5 downTo 2) out.add(Move(from, to, q))
        } else {
            out.add(Move(from, to))
        }
    }

    private fun slide(
        p: Position, s: Int, df: Int, dr: Int, white: Boolean,
        out: ArrayList<Move>, capsOnly: Boolean
    ) {
        var nf = (s and 7) + df
        var nr = (s shr 3) + dr
        while (nf in 0..7 && nr in 0..7) {
            val t = nr * 8 + nf
            val v = p.b[t]
            if (v == 0) {
                if (!capsOnly) out.add(Move(s, t))
            } else {
                if ((v > 0) != white) out.add(Move(s, t))
                break
            }
            nf += df; nr += dr
        }
    }

    /** Sinh nước đi giả hợp lệ (chưa kiểm tra Vua bị chiếu). */
    fun pseudo(p: Position, out: ArrayList<Move>, capsOnly: Boolean = false) {
        val white = p.whiteToMove
        val sgn = if (white) 1 else -1
        for (s in 0..63) {
            val pc = p.b[s]
            if (pc == 0 || (pc > 0) != white) continue
            val type = abs(pc)
            val f = s and 7
            val r = s shr 3
            when (type) {
                Pc.PAWN -> {
                    val nr = r + sgn
                    if (nr !in 0..7) continue
                    val startR = if (white) 1 else 6
                    val promoR = if (white) 7 else 0
                    val ahead = nr * 8 + f
                    if (p.b[ahead] == 0 && (!capsOnly || nr == promoR)) {
                        addPawn(out, s, ahead, nr == promoR)
                        if (!capsOnly && r == startR && p.b[(r + 2 * sgn) * 8 + f] == 0) {
                            out.add(Move(s, (r + 2 * sgn) * 8 + f))
                        }
                    }
                    for (k in 0..1) {
                        val nf = f + 2 * k - 1
                        if (nf !in 0..7) continue
                        val t = nr * 8 + nf
                        val tp = p.b[t]
                        if (tp != 0) {
                            if ((tp > 0) != white) addPawn(out, s, t, nr == promoR)
                        } else if (t == p.ep && p.b[r * 8 + nf] == -sgn * Pc.PAWN) {
                            out.add(Move(s, t))
                        }
                    }
                }
                Pc.KNIGHT -> {
                    for (i in 0..7) {
                        val nf = f + KN_DF[i]
                        val nr = r + KN_DR[i]
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val t = nr * 8 + nf
                        val v = p.b[t]
                        if (v == 0) {
                            if (!capsOnly) out.add(Move(s, t))
                        } else if ((v > 0) != white) {
                            out.add(Move(s, t))
                        }
                    }
                }
                Pc.BISHOP -> for (i in 0..3) slide(p, s, B_DF[i], B_DR[i], white, out, capsOnly)
                Pc.ROOK -> for (i in 0..3) slide(p, s, R_DF[i], R_DR[i], white, out, capsOnly)
                Pc.QUEEN -> for (i in 0..3) {
                    slide(p, s, B_DF[i], B_DR[i], white, out, capsOnly)
                    slide(p, s, R_DF[i], R_DR[i], white, out, capsOnly)
                }
                Pc.KING -> {
                    for (i in 0..7) {
                        val nf = f + K_DF[i]
                        val nr = r + K_DR[i]
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val t = nr * 8 + nf
                        val v = p.b[t]
                        if (v == 0) {
                            if (!capsOnly) out.add(Move(s, t))
                        } else if ((v > 0) != white) {
                            out.add(Move(s, t))
                        }
                    }
                    if (!capsOnly) {
                        if (white && s == 4) {
                            if ((p.castle and 1) != 0 && p.b[5] == 0 && p.b[6] == 0 && p.b[7] == Pc.ROOK &&
                                !attacked(p, 4, false) && !attacked(p, 5, false) && !attacked(p, 6, false)
                            ) out.add(Move(4, 6))
                            if ((p.castle and 2) != 0 && p.b[3] == 0 && p.b[2] == 0 && p.b[1] == 0 && p.b[0] == Pc.ROOK &&
                                !attacked(p, 4, false) && !attacked(p, 3, false) && !attacked(p, 2, false)
                            ) out.add(Move(4, 2))
                        } else if (!white && s == 60) {
                            if ((p.castle and 4) != 0 && p.b[61] == 0 && p.b[62] == 0 && p.b[63] == -Pc.ROOK &&
                                !attacked(p, 60, true) && !attacked(p, 61, true) && !attacked(p, 62, true)
                            ) out.add(Move(60, 62))
                            if ((p.castle and 8) != 0 && p.b[59] == 0 && p.b[58] == 0 && p.b[57] == 0 && p.b[56] == -Pc.ROOK &&
                                !attacked(p, 60, true) && !attacked(p, 59, true) && !attacked(p, 58, true)
                            ) out.add(Move(60, 58))
                        }
                    }
                }
            }
        }
    }

    fun make(p: Position, m: Move): Position {
        val q = p.copy()
        val pc = q.b[m.from]
        val white = pc > 0
        val type = abs(pc)
        var captured = q.b[m.to]
        q.b[m.to] = pc
        q.b[m.from] = 0
        if (type == Pc.PAWN) {
            if (m.to == p.ep && captured == 0 && (m.from and 7) != (m.to and 7)) {
                val capSq = (m.from shr 3) * 8 + (m.to and 7)
                captured = q.b[capSq]
                q.b[capSq] = 0
            }
            if (m.promo != 0) q.b[m.to] = if (white) m.promo else -m.promo
        } else if (type == Pc.KING) {
            if (white && m.from == 4 && m.to == 6) { q.b[7] = 0; q.b[5] = Pc.ROOK }
            else if (white && m.from == 4 && m.to == 2) { q.b[0] = 0; q.b[3] = Pc.ROOK }
            else if (!white && m.from == 60 && m.to == 62) { q.b[63] = 0; q.b[61] = -Pc.ROOK }
            else if (!white && m.from == 60 && m.to == 58) { q.b[56] = 0; q.b[59] = -Pc.ROOK }
        }
        q.castle = q.castle and CMASK[m.from] and CMASK[m.to]
        q.ep = if (type == Pc.PAWN && abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        q.half = if (type == Pc.PAWN || captured != 0) 0 else p.half + 1
        if (!white) q.full = p.full + 1
        q.whiteToMove = !p.whiteToMove
        return q
    }

    fun legal(p: Position): ArrayList<Move> {
        val all = ArrayList<Move>(48)
        pseudo(p, all)
        val res = ArrayList<Move>(all.size)
        for (m in all) {
            val q = make(p, m)
            if (!inCheck(q, p.whiteToMove)) res.add(m)
        }
        return res
    }
}

object Eval {
    private val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 0)

    private val PAWN_T = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )
    private val KNIGHT_T = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )
    private val BISHOP_T = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20
    )
    private val ROOK_T = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0
    )
    private val QUEEN_T = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20
    )
    private val KING_MID = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20
    )
    private val KING_END = intArrayOf(
        -50, -40, -30, -20, -20, -30, -40, -50,
        -30, -20, -10, 0, 0, -10, -20, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -30, 0, 0, 0, 0, -30, -30,
        -50, -30, -30, -30, -30, -30, -30, -50
    )

    /** Điểm theo góc nhìn của bên đang đến lượt (centipawn). */
    fun evaluate(p: Position): Int {
        var nonPawn = 0
        for (s in 0..63) {
            val t = abs(p.b[s])
            if (t in 2..5) nonPawn += VAL[t]
        }
        val endgame = nonPawn <= 2600
        var score = 0
        for (s in 0..63) {
            val v = p.b[s]
            if (v == 0) continue
            val t = abs(v)
            if (t > 6) continue
            val white = v > 0
            val f = s and 7
            val r = s shr 3
            val idx = if (white) (7 - r) * 8 + f else r * 8 + f
            val pst = when (t) {
                Pc.PAWN -> PAWN_T[idx]
                Pc.KNIGHT -> KNIGHT_T[idx]
                Pc.BISHOP -> BISHOP_T[idx]
                Pc.ROOK -> ROOK_T[idx]
                Pc.QUEEN -> QUEEN_T[idx]
                else -> if (endgame) KING_END[idx] else KING_MID[idx]
            }
            val total = VAL[t] + pst
            score += if (white) total else -total
        }
        return if (p.whiteToMove) score else -score
    }

    fun pieceValue(type: Int): Int = if (type in 0..6) VAL[type] else 0
}

class Engine {
    class Result(val move: Move?, val score: Int, val depth: Int)

    @Volatile
    var stop = false

    private var deadline = 0L
    private var nodes = 0L
    private var aborted = false

    fun search(root: Position, millis: Long, maxDepth: Int = 64): Result {
        deadline = System.nanoTime() + millis * 1_000_000L
        aborted = false
        nodes = 0
        val rootMoves = Gen.legal(root)
        if (rootMoves.isEmpty()) {
            return Result(null, if (Gen.inCheck(root, root.whiteToMove)) -MATE else 0, 0)
        }
        order(rootMoves, root)
        var best = rootMoves[0]
        var bestScore = 0
        var doneDepth = 0
        for (d in 1..maxDepth) {
            var alpha = -INF
            var iterBest: Move? = null
            var iterScore = -INF
            for (m in rootMoves) {
                val child = Gen.make(root, m)
                val sc = -negamax(child, d - 1, -INF, -alpha, 1)
                if (aborted) break
                if (sc > iterScore) {
                    iterScore = sc
                    iterBest = m
                }
                if (sc > alpha) alpha = sc
            }
            if (aborted || iterBest == null) break
            best = iterBest
            bestScore = iterScore
            doneDepth = d
            rootMoves.remove(best)
            rootMoves.add(0, best)
            if (abs(bestScore) > MATE - 100) break
            if (System.nanoTime() > deadline || stop) break
        }
        return Result(best, bestScore, doneDepth)
    }

    private fun checkTime() {
        nodes++
        if ((nodes and 1023L) == 0L && (stop || System.nanoTime() > deadline)) aborted = true
    }

    private fun negamax(p: Position, depth: Int, a: Int, beta: Int, ply: Int): Int {
        var alpha = a
        checkTime()
        if (aborted) return 0
        if (p.half >= 100) return 0
        val inChk = Gen.inCheck(p, p.whiteToMove)
        var d = depth
        if (inChk && ply < 30) d++
        if (d <= 0) return quiesce(p, alpha, beta, ply)
        val moves = ArrayList<Move>(48)
        Gen.pseudo(p, moves)
        order(moves, p)
        var legalCount = 0
        var best = -INF
        for (m in moves) {
            val child = Gen.make(p, m)
            if (Gen.inCheck(child, p.whiteToMove)) continue
            legalCount++
            val sc = -negamax(child, d - 1, -beta, -alpha, ply + 1)
            if (aborted) return 0
            if (sc > best) best = sc
            if (sc > alpha) alpha = sc
            if (alpha >= beta) break
        }
        if (legalCount == 0) return if (inChk) -MATE + ply else 0
        return best
    }

    private fun quiesce(p: Position, a: Int, beta: Int, ply: Int): Int {
        var alpha = a
        checkTime()
        if (aborted) return 0
        val stand = Eval.evaluate(p)
        if (stand >= beta) return stand
        if (stand > alpha) alpha = stand
        if (ply > 60) return stand
        val moves = ArrayList<Move>(16)
        Gen.pseudo(p, moves, true)
        order(moves, p)
        for (m in moves) {
            val child = Gen.make(p, m)
            if (Gen.inCheck(child, p.whiteToMove)) continue
            val sc = -quiesce(child, -beta, -alpha, ply + 1)
            if (aborted) return 0
            if (sc >= beta) return sc
            if (sc > alpha) alpha = sc
        }
        return alpha
    }

    private fun score(p: Position, m: Move): Int {
        val mover = abs(p.b[m.from])
        var victim = abs(p.b[m.to])
        if (victim == 0 && mover == Pc.PAWN && (m.from and 7) != (m.to and 7)) victim = Pc.PAWN
        var s = 0
        if (victim != 0) s += 10 * Eval.pieceValue(victim) - Eval.pieceValue(mover) / 10 + 1000
        if (m.promo != 0) s += 800 + Eval.pieceValue(m.promo)
        return s
    }

    private fun order(moves: ArrayList<Move>, p: Position) {
        val n = moves.size
        val sc = IntArray(n) { score(p, moves[it]) }
        for (i in 1 until n) {
            val mv = moves[i]
            val s = sc[i]
            var j = i - 1
            while (j >= 0 && sc[j] < s) {
                moves[j + 1] = moves[j]
                sc[j + 1] = sc[j]
                j--
            }
            moves[j + 1] = mv
            sc[j + 1] = s
        }
    }

    companion object {
        const val MATE = 30000
        const val INF = 32000
    }
}
