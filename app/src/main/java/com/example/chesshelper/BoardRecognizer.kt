package com.example.chesshelper

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Đọc thế cờ từ ảnh bàn cờ thật, KHÔNG dùng mô hình học máy:
 *  - tìm ô nào có quân (so màu giữa tâm ô và 4 góc ô),
 *  - quân nào sáng (Trắng) / tối (Đen),
 *  - bên nào đứng gần máy ảnh = bên người dùng cầm,
 *  - loại quân chỉ suy ra được khi quân còn ở ô xuất phát; còn lại đánh dấu "?" để người dùng chọn.
 */
object BoardRecognizer {

    class Result(val board: IntArray, val userWhite: Boolean, val occupied: Int)

    private class Patch(val r: Float, val g: Float, val b: Float, val sd: Float)

    private class Split(val thr: Float, val sep: Float)

    private val INIT = IntArray(64).also {
        val back = intArrayOf(Pc.ROOK, Pc.KNIGHT, Pc.BISHOP, Pc.QUEEN, Pc.KING, Pc.BISHOP, Pc.KNIGHT, Pc.ROOK)
        for (f in 0..7) {
            it[f] = back[f]
            it[8 + f] = Pc.PAWN
            it[48 + f] = -Pc.PAWN
            it[56 + f] = -back[f]
        }
    }

    private fun pt(c: Array<PointF>, u: Float, v: Float): PointF {
        val tx = c[0].x + (c[1].x - c[0].x) * u
        val ty = c[0].y + (c[1].y - c[0].y) * u
        val bx = c[3].x + (c[2].x - c[3].x) * u
        val by = c[3].y + (c[2].y - c[3].y) * u
        return PointF(tx + (bx - tx) * v, ty + (by - ty) * v)
    }

    private fun sample(bmp: Bitmap, cx: Float, cy: Float, rad: Float): Patch {
        val r0 = max(2, rad.toInt())
        val step = max(1, r0 / 6)
        var n = 0
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var sl = 0.0
        var sl2 = 0.0
        var y = (cy - r0).toInt()
        while (y <= cy + r0) {
            var x = (cx - r0).toInt()
            while (x <= cx + r0) {
                if (x >= 0 && y >= 0 && x < bmp.width && y < bmp.height) {
                    val px = bmp.getPixel(x, y)
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)
                    val l = 0.299 * r + 0.587 * g + 0.114 * b
                    sr += r
                    sg += g
                    sb += b
                    sl += l
                    sl2 += l * l
                    n++
                }
                x += step
            }
            y += step
        }
        if (n == 0) return Patch(0f, 0f, 0f, 0f)
        val mean = sl / n
        val sd = sqrt(max(0.0, sl2 / n - mean * mean))
        return Patch((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat(), sd.toFloat())
    }

    private fun med4(a: Float, b: Float, c: Float, d: Float): Float {
        val s = floatArrayOf(a, b, c, d)
        s.sort()
        return (s[1] + s[2]) / 2f
    }

    private fun otsu(v: FloatArray): Split {
        val n = v.size
        if (n < 2) return Split(Float.MAX_VALUE, 0f)
        val s = v.copyOf()
        s.sort()
        val pre = DoubleArray(n + 1)
        for (k in 0 until n) pre[k + 1] = pre[k] + s[k]
        var best = -1.0
        var bk = 1
        for (k in 1 until n) {
            val ma = pre[k] / k
            val mb = (pre[n] - pre[k]) / (n - k)
            val bv = k.toDouble() * (n - k) * (ma - mb) * (ma - mb)
            if (bv > best) {
                best = bv
                bk = k
            }
        }
        val ma = pre[bk] / bk
        val mb = (pre[n] - pre[bk]) / (n - bk)
        return Split((s[bk - 1] + s[bk]) / 2f, (mb - ma).toFloat())
    }

    /** [c] = 4 góc lưới 8x8 trong ảnh: trên-trái, trên-phải, dưới-phải, dưới-trái. */
    fun recognize(bmp: Bitmap, c: Array<PointF>): Result {
        val dist = FloatArray(64)
        val lum = FloatArray(64)
        for (i in 0..7) {
            for (j in 0..7) {
                val u0 = j / 8f
                val u1 = (j + 1) / 8f
                val v0 = i / 8f
                val v1 = (i + 1) / 8f
                val uc = (u0 + u1) / 2f
                val vc = (v0 + v1) / 2f
                val ctr = pt(c, uc, vc)
                val l = pt(c, u0, vc)
                val rr = pt(c, u1, vc)
                val t = pt(c, uc, v0)
                val bt = pt(c, uc, v1)
                val s = min(hypot(rr.x - l.x, rr.y - l.y), hypot(bt.x - t.x, bt.y - t.y))
                val center = sample(bmp, ctr.x, ctr.y, s * 0.2f)
                val cps = Array(4) { k ->
                    val uu = if ((k and 1) == 0) u0 + 0.14f / 8f else u1 - 0.14f / 8f
                    val vv = if ((k and 2) == 0) v0 + 0.14f / 8f else v1 - 0.14f / 8f
                    val q = pt(c, uu, vv)
                    sample(bmp, q.x, q.y, s * 0.07f)
                }
                val refR = med4(cps[0].r, cps[1].r, cps[2].r, cps[3].r)
                val refG = med4(cps[0].g, cps[1].g, cps[2].g, cps[3].g)
                val refB = med4(cps[0].b, cps[1].b, cps[2].b, cps[3].b)
                val dr = center.r - refR
                val dg = center.g - refG
                val db = center.b - refB
                val idx = i * 8 + j
                dist[idx] = sqrt(dr * dr + dg * dg + db * db) + 0.7f * center.sd
                lum[idx] = 0.299f * center.r + 0.587f * center.g + 0.114f * center.b
            }
        }

        // 1. Ô nào có quân
        val occSplit = otsu(dist)
        val occ = BooleanArray(64) { occSplit.sep >= 30f && dist[it] > occSplit.thr }
        val occIdx = (0 until 64).filter { occ[it] }

        // 2. Quân sáng hay tối
        val white = BooleanArray(64)
        if (occIdx.isNotEmpty()) {
            val lv = FloatArray(occIdx.size) { lum[occIdx[it]] }
            val cs = otsu(lv)
            val useSplit = occIdx.size >= 2 && cs.sep >= 35f
            for (k in occIdx) {
                white[k] = if (useSplit) lum[k] > cs.thr else lum[k] > 128f
            }
        }

        // 3. Bên nào đứng gần máy ảnh (hai hàng dưới cùng của ảnh)
        var whiteBottom = 0
        var blackBottom = 0
        for (i in 0..7) {
            for (j in 0..7) {
                val k = i * 8 + j
                if (!occ[k]) continue
                val bottom = i >= 6
                val top = i <= 1
                if (bottom) {
                    if (white[k]) whiteBottom++ else blackBottom++
                } else if (top) {
                    if (white[k]) blackBottom++ else whiteBottom++
                }
            }
        }
        val userWhite = whiteBottom >= blackBottom

        // 4. Ghép thành bàn cờ chuẩn
        val board = IntArray(64)
        for (i in 0..7) {
            for (j in 0..7) {
                val k = i * 8 + j
                if (!occ[k]) continue
                val sq = if (userWhite) (7 - i) * 8 + j else i * 8 + (7 - j)
                val sign = if (white[k]) 1 else -1
                val init = INIT[sq]
                board[sq] = if (init != 0 && (init > 0) == (sign > 0)) init else sign * Pc.UNKNOWN
            }
        }
        return Result(board, userWhite, occIdx.size)
    }
}
