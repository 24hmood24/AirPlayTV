package com.mojibo.airplaytv

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mojibo.airplaytv.net.NsdAdvertiser
import com.mojibo.airplaytv.player.AudioDecoder
import com.mojibo.airplaytv.player.GLVideoRenderer
import com.mojibo.airplaytv.player.VideoDecoder
import com.mojibo.airplaytv.player.NativeBridge
import android.net.wifi.WifiManager

class AirplayService : Service() {

    private lateinit var advertiser: NsdAdvertiser
    private var multicastLock: WifiManager.MulticastLock? = null
    private val BASE_LEVEL = 25
    private var volumeBaselineDb: Float? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("AirPlay")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()
        startForeground(NOTIF_ID, buildNotification())

        val deviceId = androidIdAsMac()
        val prefs = getSharedPreferences("mojibo_prefs", MODE_PRIVATE)
        val deviceName = prefs.getString("device_name", "24TV") ?: "24TV"
        advertiser = NsdAdvertiser(this, deviceName = deviceName, rtspPort = 5000)

        AudioDecoder.instance = AudioDecoder()
        NativeBridge.startAirplayServer(deviceId, deviceName, 5000)
        advertiser.start(deviceId)

        MainActivity.pendingRenderer?.let { attachRenderer(it) }
    }

    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0

    fun attachRenderer(renderer: GLVideoRenderer) {
        VideoDecoder.instance = VideoDecoder(renderer)
        if (pendingWidth > 0 && pendingHeight > 0) {
            VideoDecoder.instance?.configure(pendingWidth, pendingHeight)
            MainActivity.instance?.onMirroringStarted()
        }
    }

    fun onSetVolume(volume: Float) {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        // بروتوكول AirPlay يرسل الصوت كـ dB: من -30.0 (أقل صوت) إلى 0.0 (أعلى صوت)، و-144.0 = كتم
        val fraction = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val targetVolume = (fraction * maxVolume).toInt()
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVolume, 0)
    }

    fun onConnectionInit() {
        if (MainActivity.instance == null) {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
        }
    }

    fun onVideoSize(width: Int, height: Int) {
        pendingWidth = width
        pendingHeight = height
        VideoDecoder.instance?.configure(width, height)
        MainActivity.instance?.onMirroringStarted()
        setSystemVolume(BASE_LEVEL)
    }

    private fun setSystemVolume(level: Int) {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level, 0)
        } catch (e: Exception) {
            android.util.Log.e("AirplayService", "setSystemVolume failed", e)
        }
    }

    fun detachRenderer() {
        VideoDecoder.instance?.release()
        VideoDecoder.instance = null
    }

    fun onConnectionReset() {
        pendingWidth = 0
        pendingHeight = 0
        VideoDecoder.instance?.release()
        VideoDecoder.instance = null
        AudioDecoder.instance?.release()
        AudioDecoder.instance = AudioDecoder()
        MainActivity.instance?.finish()
    }


    fun disconnectClient() {
        NativeBridge.stopAirplayServer()
        onConnectionReset()
        val deviceId = androidIdAsMac()
        val deviceName = "24TV"
        NativeBridge.startAirplayServer(deviceId, deviceName, 5000)
    }

    override fun onDestroy() {
        NativeBridge.stopAirplayServer()
        advertiser.stop()
        AudioDecoder.instance?.release()
        instance = null
        multicastLock?.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "airplay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "AirPlay Receiver", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mojibo AirPlay")
            .setContentText("جاهز لاستقبال AirPlay")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun androidIdAsMac(): String {
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "000000000000"
        // نحول الـ Android ID (فريد لكل جهاز) لصيغة MAC صورية
        val hex = androidId.padStart(12, '0').takeLast(12)
        return "02:" + hex.chunked(2).joinToString(":")
    }

    companion object {
        private const val NOTIF_ID = 1
        var instance: AirplayService? = null
    }
}
