package org.xs.headunitlauncher.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.xs.headunitlauncher.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlProjectionView(context: Context) : GLSurfaceView(context), IProjectionView {

    private val renderer: VideoRenderer
    private val callbacks = mutableListOf<IProjectionView.Callbacks>()

    init {
        setEGLContextClientVersion(2)
        renderer = VideoRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true // Keep context alive if possible
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        renderer.getSurface()?.let {
            if (it.isValid) {
                callback.onSurfaceCreated(it)
                callback.onSurfaceChanged(it, width, height)
            }
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }

    fun getSurface(): Surface? = renderer.getSurface()
    fun isSurfaceValid(): Boolean = renderer.getSurface()?.isValid == true

    override fun setVideoSize(width: Int, height: Int) {
        AppLog.i("GlProjectionView setVideoSize: ${width}x$height")
        renderer.updateBufferSize(width, height)
        // ProjectionViewScaler removed, we use setVideoScale via Matrix
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        renderer.setScale(scaleX, scaleY)
    }

    fun setDesaturation(value: Float) {
        renderer.setDesaturation(value)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer.release()
    }

    private inner class VideoRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        
        private var textureId: Int = 0
        private var program: Int = 0
        
        private var mVPMatrix = FloatArray(16)
        private var sSTMatrix = FloatArray(16)

        private var mScaleX = 1.0f
        private var mScaleY = 1.0f

        fun updateBufferSize(width: Int, height: Int) {
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        fun setScale(x: Float, y: Float) {
            mScaleX = x
            mScaleY = y
        }

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uDesaturation;
            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(mix(color.rgb, vec3(gray), uDesaturation), color.a);
            }
        """

        private var vertexBuffer: FloatBuffer? = null
        private val squareCoords = floatArrayOf(
            -1.0f, -1.0f, 0.0f, // bottom left
             1.0f, -1.0f, 0.0f, // bottom right
            -1.0f,  1.0f, 0.0f, // top left
             1.0f,  1.0f, 0.0f  // top right
        )
        
        private val textureCoords = floatArrayOf(
            0f, 0f, 
            1f, 0f, 
            0f, 1f, 
            1f, 1f
        )
        private var textureBuffer: FloatBuffer? = null

        private var maPositionHandle = 0
        private var maTextureHandle = 0
        private var muMVPMatrixHandle = 0
        private var muSTMatrixHandle = 0
        private var muDesaturationHandle = 0

        @Volatile
        private var desaturation = 0.0f

        fun setDesaturation(value: Float) {
            desaturation = value.coerceIn(0f, 1f)
        }

        private var updateSurface = false

        fun getSurface(): Surface? = surface

        fun release() {
            surface?.let { s ->
                Handler(Looper.getMainLooper()).post {
                    callbacks.forEach { it.onSurfaceDestroyed(s) }
                }
            }
            surface?.release()
            surfaceTexture?.release()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            AppLog.i("GlProjectionView: onSurfaceCreated (GL Context)")
            
            // Setup texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Compile shaders
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            
            // Get handles
            maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            muDesaturationHandle = GLES20.glGetUniformLocation(program, "uDesaturation")

            // Buffers
            val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
            vertexBuffer?.put(squareCoords)
            vertexBuffer?.position(0)

            val bbT = ByteBuffer.allocateDirect(textureCoords.size * 4)
            bbT.order(ByteOrder.nativeOrder())
            textureBuffer = bbT.asFloatBuffer()
            textureBuffer?.put(textureCoords)
            textureBuffer?.position(0)

            // Create Surface
            surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture!!.setOnFrameAvailableListener(this)
            surface = Surface(surfaceTexture)
            
            // Notify Activity on Main Thread
            Handler(Looper.getMainLooper()).post {
                AppLog.i("GlProjectionView: Reporting Surface Created")
                callbacks.forEach { it.onSurfaceCreated(surface!!) }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            AppLog.i("GlProjectionView: onSurfaceChanged: ${width}x$height")
            GLES20.glViewport(0, 0, width, height)
            Handler(Looper.getMainLooper()).post {
                callbacks.forEach { it.onSurfaceChanged(surface!!, width, height) }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            synchronized(this) {
                if (updateSurface) {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(sSTMatrix)
                    updateSurface = false
                }
            }
            
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            
            GLES20.glUseProgram(program)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(maPositionHandle)
            
            textureBuffer?.position(0)
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 2 * 4, textureBuffer)
            GLES20.glEnableVertexAttribArray(maTextureHandle)
            
            Matrix.setIdentityM(mVPMatrix, 0)
            Matrix.scaleM(mVPMatrix, 0, mScaleX, mScaleY, 1f)
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mVPMatrix, 0)
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, sSTMatrix, 0)
            GLES20.glUniform1f(muDesaturationHandle, desaturation)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(this) {
                updateSurface = true
            }
            requestRender()
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            return shader
        }
    }
}
