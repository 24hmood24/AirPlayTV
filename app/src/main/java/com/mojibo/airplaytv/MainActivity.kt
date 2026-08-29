package com.mojibo.airplaytv

import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mojibo.airplaytv.player.GLVideoRenderer
import com.mojibo.airplaytv.player.VideoDecoder
import android.bluetooth.BluetoothAdapter
import android.provider.Settings
import android.os.Build

/**
 * Main TV screen. Hosts the GLSurfaceView that video frames get decoded into
 * (YUV->RGB conversion happens on the GPU via GLVideoRenderer), and a status label.
 * All the actual AirPlay protocol work happens in AirplayService (running as a
 * foreground service so it survives even if the Activity is backgrounded).
 */
class MainActivity : AppCompatActivity() {
    override fun onDestroy() {
        restoreBackgroundServices()
        super.onDestroy()
        instance = null
        pendingRenderer = null
        OnScreenLog.log("MAIN ACTIVITY DESTROYED")
    }

    companion object {
        var instance: MainActivity? = null
        var pendingRenderer: GLVideoRenderer? = null
    }

    private lateinit var statusText: TextView
    private var previousAnimatorScale = "1"
    private var previousTransitionScale = "1"
    private var previousWindowScale = "1"
    private var backPressedOnce = false
    private var isMirroring = false
    private val backPressHandler = android.os.Handler(android.os.Looper.getMainLooper())


    override fun onBackPressed() {
        if (backPressedOnce) {
            if (isMirroring) {
                disconnectAndGoHome()
            } else {
                finishAffinity()
            }
            return
        }
        backPressedOnce = true
        val msg = if (isMirroring) "اضغط رجوع مرة ثانية لفصل الاتصال" else "اضغط رجوع مرة ثانية للخروج"
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        backPressHandler.postDelayed({ backPressedOnce = false }, 2000)
    }


    private fun disconnectAndGoHome() {
        AirplayService.instance?.disconnectClient()
    }


    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
            if (isMirroring) {
                findViewById<android.widget.Button>(R.id.fitModeButton).performClick()
            } else {
                showRenameDialog()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        disableBackgroundServices()
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        val crashFile = java.io.File(getExternalFilesDir(null), "last_crash.txt")
        if (crashFile.exists()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("آخر تعطل")
                .setMessage(crashFile.readText())
                .setPositiveButton("حسناً", null)
                .show()
            crashFile.delete()
        }
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<TextView>(R.id.fpsText).visibility = TextView.GONE


        val glSurfaceView = findViewById<GLSurfaceView>(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(2)
        val renderer = GLVideoRenderer()
        renderer.glSurfaceView = glSurfaceView
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        pendingRenderer = renderer
        AirplayService.instance?.attachRenderer(renderer)

        val fitModeButton = findViewById<android.widget.Button>(R.id.fitModeButton)
        val modes = listOf(
            VideoDecoder.FitMode.CONTAIN to "عرض: احتواء",
            VideoDecoder.FitMode.CROP to "عرض: ملء الشاشة",
            VideoDecoder.FitMode.STRETCH to "عرض: تمديد"
        )
        var modeIndex = 0
        val hideHandler = android.os.Handler(mainLooper)
        val hideRunnable = Runnable { fitModeButton.visibility = android.view.View.GONE }

        fun showButtonTemporarily() {
            fitModeButton.visibility = android.view.View.VISIBLE
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 3000)
        }

        fitModeButton.setOnClickListener {
            modeIndex = (modeIndex + 1) % modes.size
            val (mode, label) = modes[modeIndex]
            VideoDecoder.instance?.fitMode = mode
            fitModeButton.text = label
            showButtonTemporarily()
        }

        val fpsText = findViewById<TextView>(R.id.fpsText)
        val fpsHandler = android.os.Handler(mainLooper)
        val fpsUpdater = object : Runnable {
            override fun run() {
                fpsText.text = "FPS: ${VideoDecoder.instance?.lastFps ?: 0}"
                fpsHandler.postDelayed(this, 500)
            }
        }
        fpsHandler.post(fpsUpdater)

        glSurfaceView.setOnClickListener {
            showButtonTemporarily()
        }

        showButtonTemporarily()

        // Start the service that owns: mDNS advertisement, RTSP server, native decoder bridge.
        val intent = Intent(this, AirplayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            OnScreenLog.log("SERVICE START OK")
        } catch (e: Exception) {
            OnScreenLog.log("SERVICE ERROR: ${e.message}")
        }
    }

    fun onMirroringStarted() {
        isMirroring = true
        runOnUiThread {
            statusText.visibility = TextView.GONE
            findViewById<android.widget.ImageView>(R.id.welcomeBackground).visibility = TextView.GONE
        }
    }

    fun onMirroringStopped() {
        isMirroring = false
        runOnUiThread {
            statusText.visibility = TextView.VISIBLE
            findViewById<android.widget.ImageView>(R.id.welcomeBackground).visibility = TextView.VISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            showRenameDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showRenameDialog() {
        val prefs = getSharedPreferences("mojibo_prefs", MODE_PRIVATE)
        val currentName = prefs.getString("device_name", "Mojibo TV") ?: "Mojibo TV"

        val input = android.widget.EditText(this)
        input.setText(currentName)
        input.setSelection(input.text.length)

        android.app.AlertDialog.Builder(this)
            .setTitle("اسم الجهاز")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) {
                    prefs.edit().putString("device_name", newName).apply()
                    restartAirplayService()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun restartAirplayService() {
        val intent = Intent(this, AirplayService::class.java)
        stopService(intent)
        android.os.Handler(mainLooper).postDelayed({
            startForegroundService(intent)
        }, 500)
    }

    private fun disableBackgroundServices() {
        try {
            previousAnimatorScale = Settings.Global.getString(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) ?: "1"
            previousTransitionScale = Settings.Global.getString(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE) ?: "1"
            previousWindowScale = Settings.Global.getString(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE) ?: "1"

            Settings.Global.putString(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, "0")
            Settings.Global.putString(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, "0")
            Settings.Global.putString(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, "0")
            Settings.Global.putInt(contentResolver, "wifi_scan_always_enabled", 0)

            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                bluetoothAdapter.disable()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun restoreBackgroundServices() {
        try {
            Settings.Global.putString(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousAnimatorScale)
            Settings.Global.putString(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, previousTransitionScale)
            Settings.Global.putString(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, previousWindowScale)
            Settings.Global.putInt(contentResolver, "wifi_scan_always_enabled", 1)

            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            bluetoothAdapter?.enable()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
