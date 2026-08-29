package com.mojibo.airplaytv.player

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders NV12 (semi-planar YUV 4:2:0) frames using an OpenGL ES 2.0 fragment shader
 * for the YUV->RGB color conversion, offloading that work from the weak CPU to the GPU.
 */
class GLVideoRenderer : GLSurfaceView.Renderer {

    enum class FitMode { CONTAIN, CROP, STRETCH }
    @Volatile var fitMode: FitMode = FitMode.CONTAIN

    var glSurfaceView: GLSurfaceView? = null

    private var program = 0
    private var yTextureId = 0
    private var uvTextureId = 0

    private var positionHandle = 0
    private var texCoordHandle = 0
    private var yTextureHandle = 0
    private var uvTextureHandle = 0

    private var viewportWidth = 1
    private var viewportHeight = 1

    private var vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private var allocatedYWidth = 0
    private var allocatedYHeight = 0
    private var allocatedUvWidth = 0
    private var allocatedUvHeight = 0

    @Volatile private var pendingYData: ByteBuffer? = null
    @Volatile private var pendingUvData: ByteBuffer? = null
    @Volatile private var pendingStride = 0
    @Volatile private var pendingSliceHeight = 0
    @Volatile private var pendingWidth = 0
    @Volatile private var pendingHeight = 0
    @Volatile private var hasNewFrame = false

    var lastFps = 0
    private var fpsCounter = 0
    private var lastFpsTime = 0L

    init {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }

        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }
    }

    /** يستدعى من خيط فك التشفير — يخزن البيانات لحين الرسم الفعلي بـonDrawFrame، ويطلب رسم فوري. */
    @Synchronized
    fun submitFrame(
        yData: ByteBuffer, uvData: ByteBuffer,
        stride: Int, sliceHeight: Int, width: Int, height: Int
    ) {
        pendingYData = yData
        pendingUvData = uvData
        pendingStride = stride
        pendingSliceHeight = sliceHeight
        pendingWidth = width
        pendingHeight = height
        hasNewFrame = true
        glSurfaceView?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val vertexShaderCode = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D yTexture;
            uniform sampler2D uvTexture;
            void main() {
                float y = texture2D(yTexture, vTexCoord).r;
                vec2 uv = texture2D(uvTexture, vTexCoord).ra - vec2(0.5, 0.5);
                float u = uv.x;
                float v = uv.y;
                y = 1.164 * (y - 0.0625);
                float r = y + 1.793 * v;
                float g = y - 0.213 * u - 0.533 * v;
                float b = y + 2.112 * u;
                float brightness = 0.9;
                gl_FragColor = vec4(r * brightness, g * brightness, b * brightness, 1.0);
            }
        """.trimIndent()

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        yTextureHandle = GLES20.glGetUniformLocation(program, "yTexture")
        uvTextureHandle = GLES20.glGetUniformLocation(program, "uvTexture")

        val textureIds = IntArray(2)
        GLES20.glGenTextures(2, textureIds, 0)
        yTextureId = textureIds[0]
        uvTextureId = textureIds[1]

        setupTexture(yTextureId)
        setupTexture(uvTextureId)
    }

    private fun setupTexture(id: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val yData: ByteBuffer
        val uvData: ByteBuffer
        val stride: Int
        val sliceHeight: Int
        val width: Int
        val height: Int

        synchronized(this) {
            if (!hasNewFrame) return
            yData = pendingYData ?: return
            uvData = pendingUvData ?: return
            stride = pendingStride
            sliceHeight = pendingSliceHeight
            width = pendingWidth
            height = pendingHeight
            hasNewFrame = false
        }

        updateVertices(width, height)
        updateTexCoords(width, height, stride, sliceHeight)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId)
        yData.position(0)
        if (allocatedYWidth != stride || allocatedYHeight != sliceHeight) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, stride, sliceHeight, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yData
            )
            allocatedYWidth = stride
            allocatedYHeight = sliceHeight
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, stride, sliceHeight,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yData
            )
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTextureId)
        uvData.position(0)
        val uvW = stride / 2
        val uvH = sliceHeight / 2
        if (allocatedUvWidth != uvW || allocatedUvHeight != uvH) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, uvW, uvH, 0,
                GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, uvData
            )
            allocatedUvWidth = uvW
            allocatedUvHeight = uvH
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, uvW, uvH,
                GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, uvData
            )
        }

        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniform1i(yTextureHandle, 0)
        GLES20.glUniform1i(uvTextureHandle, 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        fpsCounter++
        val now = System.currentTimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        if (now - lastFpsTime >= 1000) {
            lastFps = fpsCounter
            fpsCounter = 0
            lastFpsTime = now
        }
    }

    private fun updateVertices(contentWidth: Int, contentHeight: Int) {
        if (viewportWidth == 0 || viewportHeight == 0) return
        val srcRatio = contentWidth.toFloat() / contentHeight.toFloat()
        val dstRatio = viewportWidth.toFloat() / viewportHeight.toFloat()

        var x = 1f
        var y = 1f
        when (fitMode) {
            FitMode.STRETCH -> { x = 1f; y = 1f }
            FitMode.CONTAIN -> {
                if (srcRatio > dstRatio) { x = 1f; y = dstRatio / srcRatio }
                else { y = 1f; x = srcRatio / dstRatio }
            }
            FitMode.CROP -> {
                if (srcRatio > dstRatio) { y = 1f; x = srcRatio / dstRatio }
                else { x = 1f; y = dstRatio / srcRatio }
            }
        }

        val vertices = floatArrayOf(-x, -y, x, -y, -x, y, x, y)
        vertexBuffer.position(0)
        vertexBuffer.put(vertices)
        vertexBuffer.position(0)
    }

    private fun updateTexCoords(width: Int, height: Int, stride: Int, sliceHeight: Int) {
        val uMax = width.toFloat() / stride.toFloat()
        val vMax = height.toFloat() / sliceHeight.toFloat()
        val coords = floatArrayOf(0f, vMax, uMax, vMax, 0f, 0f, uMax, 0f)
        texCoordBuffer.position(0)
        texCoordBuffer.put(coords)
        texCoordBuffer.position(0)
    }

    companion object {
        private const val TAG = "GLVideoRenderer"
    }
}