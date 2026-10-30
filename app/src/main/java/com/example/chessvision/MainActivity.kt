package com.example.chessvision

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val REQUEST_OVERLAY = 1001
    private val REQUEST_MEDIA_PROJECTION = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("StealthChessPrefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        val tvHint = TextView(this).apply {
            text = "Chọn phe của bạn (phe đang nằm ở phía DƯỚI bàn cờ), mở ván cờ, rồi bấm KHỞI CHẠY.\nDùng được ở bất kỳ thế cờ nào, kể cả đã đi giữa ván."
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        val rbWhite = RadioButton(this).apply { text = "⚪ Tôi cầm Trắng (Trắng ở dưới)"; id = 1 }
        val rbBlack = RadioButton(this).apply { text = "⚫ Tôi cầm Đen (Đen ở dưới)"; id = 2 }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbWhite); addView(rbBlack)
            check(if (prefs.getBoolean("WHITE_BOTTOM", true)) 1 else 2)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("WHITE_BOTTOM", checked == 1).apply()
            }
            setPadding(0, 0, 0, 32)
        }
        val btnStart = Button(this).apply {
            text = "KHỞI CHẠY CHESS VISION v6.1"
            textSize = 18f
            setOnClickListener { checkPermissionsAndStart() }
        }
        layout.addView(tvHint)
        layout.addView(group)
        layout.addView(btnStart)
        setContentView(layout)
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_OVERLAY)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
            else Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Toast.makeText(this, "Đã bật Chess Vision v6.1...", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("DATA", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 600)
            } else {
                Toast.makeText(this, "Bạn đã từ chối quyền ghi màn hình", Toast.LENGTH_LONG).show()
            }
        }
    }
}
