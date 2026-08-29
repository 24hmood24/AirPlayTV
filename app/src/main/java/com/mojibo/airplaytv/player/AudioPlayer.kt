package com.mojibo.airplaytv.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays PCM audio handed up from native code. AirPlay audio is ALAC (mirroring often
 * uses AAC), which the native layer should decode to raw PCM before calling
 * NativeBridge.onAudioPcm — Android's AudioTrack just wants interleaved 16-bit samples.
 */
class AudioPlayer(sampleRate: Int = 44100, channels: Int = 2) {

    private val track: AudioTrack

    init {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        track = builder.build()
        track.play()
    }

    fun feed(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size)
    }

    fun release() {
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    companion object {
        var instance: AudioPlayer? = null
    }
}
