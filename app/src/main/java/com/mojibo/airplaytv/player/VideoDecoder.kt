package com.mojibo.airplaytv.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes H.264 into raw YUV (buffer mode, no Surface), and hands each frame's
 * Y/UV planes to a GLVideoRenderer which does the YUV->RGB conversion on the GPU.
 *
 * Y/UV buffers are drawn from a pre-allocated pool instead of being allocated fresh
 * every frame — avoids the overhead of repeated ByteBuffer.allocateDirect() calls,
 * which is significant on a weak CPU at ~20-30 frames/sec.
 */
class VideoDecoder(private val renderer: GLVideoRenderer) {

    private var codec: MediaCodec? = null
    private var started = false
    private var configuredWidth = 0
    private var configuredHeight = 0

    enum class FitMode { CONTAIN, CROP, STRETCH }
    var fitMode: FitMode
        get() = when (renderer.fitMode) {
            GLVideoRenderer.FitMode.CONTAIN -> FitMode.CONTAIN
            GLVideoRenderer.FitMode.CROP -> FitMode.CROP
            GLVideoRenderer.FitMode.STRETCH -> FitMode.STRETCH
        }
        set(value) {
            renderer.fitMode = when (value) {
                FitMode.CONTAIN -> GLVideoRenderer.FitMode.CONTAIN
                FitMode.CROP -> GLVideoRenderer.FitMode.CROP
                FitMode.STRETCH -> GLVideoRenderer.FitMode.STRETCH
            }
        }

    val lastFps: Int get() = renderer.lastFps

    // ===== بركة ذاكرة مسبقة التخصيص — نتفادى allocateDirect() بكل فريم =====
    private val POOL_SIZE = 24
    private val MAX_Y_SIZE = 1920 * 1200
    private val MAX_UV_SIZE = 1920 * 600
    private val yPool = Array(POOL_SIZE) { ByteBuffer.allocateDirect(MAX_Y_SIZE) }
    private val uvPool = Array(POOL_SIZE) { ByteBuffer.allocateDirect(MAX_UV_SIZE) }
    private var poolIndex = 0
    private var lastSeenResync = 0L

    // ===== تأخير ثابت لضمان عرض سلس ومتزامن مع الصوت =====
    private data class QueuedFrame(
        val yData: ByteBuffer, val uvData: ByteBuffer,
        val stride: Int, val sliceHeight: Int, val width: Int, val height: Int,
        val readyAtMs: Long
    )
    private val frameQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedFrame>()
    private var drainThread: Thread? = null
    @Volatile private var drainRunning = false
    private val MAX_QUEUE_SIZE = 6

    private fun startDrainThread() {
        drainRunning = true
        drainThread = Thread {
            while (drainRunning) {
                if (SyncClock.lastResyncAtMs > lastSeenResync) {
                    lastSeenResync = SyncClock.lastResyncAtMs
                    frameQueue.clear()
                }
                val head = frameQueue.peek()
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
                var toDraw = frameQueue.poll()
                while (true) {
                    val next = frameQueue.peek() ?: break
                    if (next.readyAtMs <= System.currentTimeMillis()) {
                        toDraw = frameQueue.poll()
                    } else break
                }
                toDraw?.let {
                    renderer.submitFrame(it.yData, it.uvData, it.stride, it.sliceHeight, it.width, it.height)
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
        frameQueue.clear()
    }

    @Synchronized
    fun configure(width: Int, height: Int) {
        if (started && width == configuredWidth && height == configuredHeight) {
            return
        }
        try {
            if (started) release()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, 0)
                start()
            }
            started = true
            configuredWidth = width
            configuredHeight = height
            startDrainThread()
            Log.i(TAG, "MediaCodec configured (GPU render path, pooled buffers): ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "configure failed", e)
            started = false
        }
    }

    private var feedCounter = 0

    @Synchronized
    fun feed(nal: ByteArray, presentationTimeUs: Long) {
        val c = codec ?: run { Log.w(TAG, "feed: codec is null"); return }
        if (!started) { Log.w(TAG, "feed: not started"); return }
        feedCounter++
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (feedCounter % 15 == 0) Log.i(TAG, "feed #$feedCounter inIndex=$inIndex nalSize=${nal.size}")
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex) ?: run { Log.w(TAG, "feed #$feedCounter: getInputBuffer null"); return }
                buffer.clear()
                buffer.put(nal)
                c.queueInputBuffer(inIndex, 0, nal.size, presentationTimeUs, 0)
            } else if (feedCounter % 15 == 0) {
                Log.w(TAG, "feed #$feedCounter: NO INPUT BUFFER (dequeueInputBuffer=$inIndex)")
            }
            val info = MediaCodec.BufferInfo()
            val pending = mutableListOf<Int>()
            var outIndex = c.dequeueOutputBuffer(info, 10_000)
            while (outIndex >= 0) {
                pending.add(outIndex)
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
            if (feedCounter % 15 == 0) Log.i(TAG, "feed #$feedCounter: outputBuffers=${pending.size}")
            for ((i, idx) in pending.withIndex()) {
                if (i == pending.size - 1) {
                    val outBuf = c.getOutputBuffer(idx)
                    if (outBuf != null) {
                        enqueueFrame(outBuf, c.outputFormat, info)
                    }
                }
                c.releaseOutputBuffer(idx, false)
            }

            if (frameQueue.size > MAX_QUEUE_SIZE) {
                frameQueue.clear()
                SyncClock.lastResyncAtMs = System.currentTimeMillis()
                Log.i(TAG, "queue overflow, cleared to recover quickly")
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode error", e)
        }
    }

    private fun enqueueFrame(buf: ByteBuffer, format: MediaFormat, info: MediaCodec.BufferInfo) {
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val stride = if (format.containsKey(MediaFormat.KEY_STRIDE)) format.getInteger(MediaFormat.KEY_STRIDE) else ((width + 15) / 16) * 16
        val sliceHeight = if (format.containsKey(MediaFormat.KEY_SLICE_HEIGHT) && format.getInteger(MediaFormat.KEY_SLICE_HEIGHT) > 0)
            format.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else ((height + 15) / 16) * 16

        val ySize = stride * sliceHeight
        val uvSize = stride * (sliceHeight / 2)

        if (ySize > MAX_Y_SIZE || uvSize > MAX_UV_SIZE) {
            Log.e(TAG, "frame too large for pool: ySize=$ySize uvSize=$uvSize")
            return
        }

        val slot = poolIndex
        poolIndex = (poolIndex + 1) % POOL_SIZE
        val yData = yPool[slot]
        val uvData = uvPool[slot]

        yData.clear()
        buf.position(info.offset)
        buf.limit(info.offset + minOf(ySize, info.size))
        yData.put(buf)
        yData.position(0)

        uvData.clear()
        if (info.size > ySize) {
            buf.limit(minOf(info.offset + info.size, info.offset + ySize + uvSize))
            buf.position(info.offset + ySize)
            uvData.put(buf)
        }
        uvData.position(0)

        frameQueue.add(
            QueuedFrame(yData, uvData, stride, sliceHeight, width, height, System.currentTimeMillis() + DELAY_MS)
        )
    }

    @Synchronized
    fun release() {
        started = false
        stopDrainThread()
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
        var instance: VideoDecoder? = null
        const val DELAY_MS = 40L
        const val AUDIO_VIDEO_DELAY_MS = DELAY_MS
    }
}