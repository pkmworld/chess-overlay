package com.example.chesshelper

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

class PhotoActivity : ComponentActivity() {

    private lateinit var picker: CornerPickerView
    private lateinit var info: TextView
    private lateinit var camUri: Uri

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) load(uri)
        }

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) load(camUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(cacheDir, "board.jpg")
        camUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

        val pad = (10 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        info = TextView(this)
        info.textSize = 14f
        info.setTextColor(Color.parseColor("#212121"))
        info.text = "1) Chụp hoặc chọn ảnh bàn cờ (nên chụp từ trên xuống, thấy rõ cả bàn).\n" +
            "2) Kéo 4 chấm đỏ vào 4 góc của lưới 8×8 (chỉ phần có ô cờ, không tính viền)."
        root.addView(info)

        picker = CornerPickerView(this)
        root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(makeBtn("Chụp ảnh") { takePhoto.launch(camUri) })
        row.addView(makeBtn("Chọn ảnh") { pickImage.launch("image/*") })
        row.addView(makeBtn("Nhận diện") { recognize() })
        root.addView(row)

        setContentView(root)
    }

    private fun makeBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    private fun load(uri: Uri) {
        info.text = "Đang tải ảnh…"
        Thread {
            val bm = try {
                decode(uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bm == null) {
                    info.text = "Không mở được ảnh. Hãy thử lại."
                } else {
                    picker.bitmap = bm
                    info.text = "Kéo 4 chấm đỏ (1→2→3→4 theo chiều kim đồng hồ từ góc trên-trái) vào 4 góc lưới 8×8, rồi bấm \"Nhận diện\"."
                }
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap? {
        val cr = contentResolver
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bm = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        var deg = 0
        try {
            cr.openInputStream(uri)?.use { s ->
                val o = ExifInterface(s).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                deg = when (o) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            deg = 0
        }
        if (deg != 0) {
            val m = Matrix()
            m.postRotate(deg.toFloat())
            bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        }
        return bm
    }

    private fun recognize() {
        val bm = picker.bitmap
        if (bm == null) {
            Toast.makeText(this, "Hãy chụp hoặc chọn ảnh trước", Toast.LENGTH_SHORT).show()
            return
        }
        val c = Array(4) { PointF(picker.corners[it].x, picker.corners[it].y) }
        info.text = "Đang nhận diện…"
        Thread {
            val res = try {
                BoardRecognizer.recognize(bm, c)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (res == null) {
                    info.text = "Nhận diện thất bại. Hãy thử ảnh khác."
                } else {
                    val data = Intent()
                    data.putExtra("board", res.board)
                    data.putExtra("side", if (res.userWhite) 1 else 2)
                    setResult(RESULT_OK, data)
                    finish()
                }
            }
        }.start()
    }
}
