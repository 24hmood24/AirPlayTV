package com.mojibo.airplaytv.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes AAC-ELD audio (ct=8, AirPlay mirroring audio) via MediaCodec,
 * then plays the resulting PCM through AudioTrack.
 *
 * PCM output is delayed by VideoDecoder.AUDIO_VIDEO_DELAY_MS (same fixed delay
 * applied to video frames) so audio and video stay in sync.
 */
class AudioDecoder(sampleRate: Int = 44100, channels: Int = 2) {

    private var codec: MediaCodec? = null
    private var started = false

    private val track: AudioTrack

    // ===== طابور تأخير ثابت يطابق تأخير الفيديو =====
    private data class QueuedPcm(val pcm: ByteArray, val readyAtMs: Long)
    private val pcmQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedPcm>()
    private var drainThread: Thread? = null
    @Volatile private var drainRunning = false
    private var lastSeenResync = 0L

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

        val csd0 = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte())
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        }
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
            started = true
            startDrainThread()
            Log.i(TAG, "AAC-ELD decoder configured")
        } catch (e: Exception) {
            Log.e(TAG, "AAC-ELD decoder configure failed", e)
        }
    }

    private fun startDrainThread() {
        drainRunning = true
        drainThread = Thread {
            while (drainRunning) {
                if (SyncClock.lastResyncAtMs > lastSeenResync) {
                    lastSeenResync = SyncClock.lastResyncAtMs
                    val keepLast = 4
                    while (pcmQueue.size > keepLast) pcmQueue.poll()
                }
                val head = pcmQueue.peek()
                if (head == null) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    continue
                }
                val now = System.currentTimeMillis()
                if (head.readyAtMs > now) {
                    try {
                        Thread.sleep((head.readyAtMs - now).coerceAtMost(20))
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    continue
                }
                // نكتب كل القطع الجاهزة بالترتيب (الصوت ما يُسقط أبداً، بعكس الفيديو)
                var item = pcmQueue.poll()
                while (item != null) {
                    try {
                        track.write(item.pcm, 0, item.pcm.size)
                    } catch (_: Exception) {}
                    val next = pcmQueue.peek()
                    item = if (next != null && next.readyAtMs <= System.currentTimeMillis()) pcmQueue.poll() else null
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopDrainThread() {
        drainRunning = false
        drainThread?.interrupt()
        drainThread = null
        pcmQueue.clear()
    }

    @Synchronized
    fun feed(aacFrame: ByteArray) {
        val c = codec ?: return
        if (!started) return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex) ?: return
                buffer.clear()
                buffer.put(aacFrame)
                c.queueInputBuffer(inIndex, 0, aacFrame.size, 0, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    outBuf.get(pcm)
                    pcmQueue.add(QueuedPcm(pcm, System.currentTimeMillis() + VideoDecoder.AUDIO_VIDEO_DELAY_MS))
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(info, 0)
            }

            // إذا تراكم الصوت أكثر من اللازم، نشيل بس الزيادة القديمة تدريجيًا
            // (نخلي شوية عناصر حديثة عشان الصوت يكمل طبيعي بدون ما يصير "دفعة" مرة وحدة)
            if (pcmQueue.size > MAX_QUEUE_SIZE) {
                var removed = 0
                val keepLast = 4
                while (pcmQueue.size > keepLast) {
                    pcmQueue.poll()
                    removed++
                }
                if (removed > 0) {
                    SyncClock.lastResyncAtMs = System.currentTimeMillis()
                    Log.i(TAG, "audio queue trimmed by $removed items to resync")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio decode error", e)
        }
    }

    @Synchronized
    fun release() {
        started = false
        stopDrainThread()
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AudioDecoder"
        private const val MAX_QUEUE_SIZE = 30
        var instance: AudioDecoder? = null
    }
}
