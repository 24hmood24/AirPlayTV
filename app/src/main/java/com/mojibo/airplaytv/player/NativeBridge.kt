package com.mojibo.airplaytv.player

import com.mojibo.airplaytv.AirplayService

object NativeBridge {

    init {
        System.loadLibrary("mojibo-airplay")
        nativeInitClassCache()
    }

    private external fun nativeInitClassCache()

    external fun startAirplayServer(deviceId: String, deviceName: String, port: Int): Int
    external fun stopAirplayServer()
    external fun setCrashLogPath(path: String)

    @JvmStatic
    fun onVideoNalUnit(data: ByteArray, presentationTimeUs: Long) {
        VideoDecoder.instance?.feed(data, presentationTimeUs)
    }

    @JvmStatic
    fun onAudioPcm(data: ByteArray) {
        AudioDecoder.instance?.feed(data)
    }

    @JvmStatic
    fun onVideoSize(width: Int, height: Int) {
        AirplayService.instance?.onVideoSize(width, height)
    }

    @JvmStatic
    fun onConnectionInit() {
        AirplayService.instance?.onConnectionInit()
    }

    @JvmStatic
    fun onMirroringStopped() {
        AirplayService.instance?.onConnectionReset()
    }

    @JvmStatic
    fun onSetVolume(volume: Float) {
        AirplayService.instance?.onSetVolume(volume)
    }

    @JvmStatic
    fun onNativeLog(msg: String) {
        com.mojibo.airplaytv.OnScreenLog.log(msg)
    }
}