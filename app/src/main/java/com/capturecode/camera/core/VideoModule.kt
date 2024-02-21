package com.capturecode.camera.core

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.*
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceControl
import androidx.camera.core.CameraSelector
import com.camera.utils.FileUtils
import com.capturecode.camera.core.CameraConfig.DEFAULT_PREVIEW_HEIGHT_SIZE
import com.capturecode.camera.core.CameraConfig.DEFAULT_PREVIEW_WIDTH_SIZE
import com.capturecode.camera.core.CameraConfig.DEFAULT_RECORDING_HEIGHT_SIZE
import com.capturecode.camera.core.CameraConfig.DEFAULT_RECORDING_WIDTH_SIZE
import com.capturecode.camera.core.CameraConfig.FULLSCREEN_QUAD
import com.capturecode.camera.core.CameraConfig.MIRRO_PASSTHROUGH_FSHADER
import com.capturecode.camera.core.CameraConfig.PASSTHROUGH_FSHADER
import com.capturecode.camera.core.CameraConfig.PORTRAIT_FSHADER
import com.capturecode.camera.core.CameraConfig.TRANSFORM_VSHADER
import com.capturecode.camera.core.CameraConfig.VIDEO_BITRATE
import com.capturecode.camera.core.CameraConfig.VIDEO_ENCODER_MIMETYPE
import com.capturecode.camera.core.CameraConfig.VIDEO_FRAME_RATE
import com.capturecode.camera.core.CameraConfig.VIDEO_OUTPUT_MUXER
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.*


class VideoModule {
    companion object{
        private const val TAG = "Video"
        val MSG_CREATE_RESOURCES = 0
        val MSG_DESTROY_WINDOW_SURFACE = 1
        val MSG_ACTION_DOWN = 2
        val MSG_CLEAR_FRAME_LISTENER = 3
        val MSG_CLEANUP = 4
        val MSG_ON_FRAME_AVAILABLE = 5
        
        /** Check if OpenGL failed, and throw an exception if so */
        private fun checkGlError(op: String) {
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                val msg = op + ": glError 0x" + Integer.toHexString(error)
                Log.e(TAG, msg)
                throw RuntimeException(msg)
            }
        }

        private fun checkEglError(op: String) {
            val eglError = EGL14.eglGetError()
            if (eglError != EGL14.EGL_SUCCESS) {
                val msg = op + ": eglError 0x" + Integer.toHexString(eglError)
                Log.e(TAG, msg)
                throw RuntimeException(msg);
            }
        }
    }

//    private var renderThread: HandlerThread = HandlerThread("Camera2Video.RenderThread").apply { start() }
//    var encoder = Encoder(FileUtils.createMediaFile(FileUtils.TYPE_VIDEO))
//    private var renderHandler = RenderHandler(renderThread.looper, encoder, CameraSelector.LENS_FACING_BACK,false)

    private var renderThread: HandlerThread = HandlerThread("Camera2Video.RenderThread").apply { start() }
    var encoder: Encoder = Encoder(FileUtils.createMediaFile(FileUtils.TYPE_VIDEO))
    private var renderHandler: RenderHandler = RenderHandler(renderThread.looper, false).apply { setEncoder(encoder) }

    fun init(){
        Log.d(TAG, "init")
        renderThread = HandlerThread("Camera2Video.RenderThread").apply { start() }
        renderHandler = RenderHandler(renderThread.looper, false)
        encoder = Encoder(FileUtils.createMediaFile(FileUtils.TYPE_VIDEO))
        renderHandler.setEncoder(encoder)
    }

    fun setMirror(lensSpacing: Int) {
        renderHandler.isMirror = lensSpacing == CameraSelector.LENS_FACING_FRONT
    }

    fun release() {
        Log.d(TAG, "release")
        renderThread.quitSafely()
        encoder.releaseInputSurface()
    }

    fun createRecordRequest(session: CameraCaptureSession, flashOn: Boolean) : CaptureRequest.Builder {
        return renderHandler.createRecordRequestBuilder(session, flashOn)
    }

    fun startRecording() {
        renderHandler.startRecording()
    }

    fun stopRecording() {
        renderHandler.stopRecording()
    }

    fun destroyWindowSurface() {
        renderHandler.sendMessage(renderHandler.obtainMessage(MSG_DESTROY_WINDOW_SURFACE))
        renderHandler.waitDestroyWindowSurface()
    }

    fun setPreviewSize(previewSize: Size) {
        renderHandler.setPreviewSize(previewSize)
    }

    fun createResources(surface: Surface) {
        renderHandler.sendMessage(renderHandler.obtainMessage(MSG_CREATE_RESOURCES, 0, 0, surface))
    }

    fun getPreviewTargets(): List<Surface> {
        return renderHandler.getTargets()
    }

    fun getRecordTargets(): List<Surface> {
        return renderHandler.getTargets()
    }

    fun actionDown(encoderSurface: Surface) {
        renderHandler.sendMessage(renderHandler.obtainMessage(MSG_ACTION_DOWN, 0, 0, encoderSurface))
    }

    fun clearFrameListener() {
        renderHandler.sendMessage(renderHandler.obtainMessage(MSG_CLEAR_FRAME_LISTENER))
        renderHandler.waitClearFrameListener()
    }

    fun cleanup() {
        renderHandler.sendMessage(renderHandler.obtainMessage(MSG_CLEANUP))
        renderHandler.waitCleanup()
    }

    private class RenderHandler(looper: Looper, private val filterOn: Boolean) : Handler(looper), SurfaceTexture.OnFrameAvailableListener{
        private var previewSize = Size(0, 0)
        
        /** OpenGL texture for the SurfaceTexture provided to the camera */
        private var cameraTexId: Int = 0

        /** The SurfaceTexture provided to the camera for capture */
        private lateinit var cameraTexture: SurfaceTexture

        /** The above SurfaceTexture cast as a Surface */
        private lateinit var cameraSurface: Surface

        /** OpenGL texture that will combine the camera output with rendering */
        private var renderTexId: Int = 0

        /** The SurfaceTexture we're rendering to */
        private lateinit var renderTexture: SurfaceTexture

        /** The above SurfaceTexture cast as a Surface */
        private lateinit var renderSurface: Surface

        /** Stuff needed for displaying HLG via SurfaceControl */
        private var contentSurfaceControl: SurfaceControl? = null
        private var windowTexId: Int = 0
        private var windowFboId: Int = 0
        private var supportsNativeFences = false

        /** Storage space for setting the texMatrix uniform */
        private val texMatrix = FloatArray(16)
        
        @Volatile
        private var currentlyRecording = false

        var isMirror: Boolean = false

        /** EGL / OpenGL data. */
        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglConfig: EGLConfig? = null
        private var eglRenderSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var eglEncoderSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var eglWindowSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var vertexShader = 0
        
        private var cameraToRenderShaderProgram: ShaderProgram? = null
        private var renderToPreviewShaderProgram:ShaderProgram? = null
        private var renderToEncodeShaderProgram: ShaderProgram? = null

        private val cvResourcesCreated = ConditionVariable(false)
        private val cvDestroyWindowSurface = ConditionVariable(false)
        private val cvClearFrameListener = ConditionVariable(false)
        private val cvCleanup = ConditionVariable(false)
        private var encoder: Encoder? = null
        fun setEncoder(encoder: Encoder) {
            this.encoder = encoder
        }
        fun startRecording(){
            currentlyRecording = true
        }

        fun stopRecording() {
            currentlyRecording = false
        }
         
        fun createRecordRequestBuilder(session: CameraCaptureSession, flashOn: Boolean) : CaptureRequest.Builder {
            cvResourcesCreated.block()

            return session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(cameraSurface)
                if(flashOn) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                else set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(VIDEO_FRAME_RATE, VIDEO_FRAME_RATE))
            }
        }
        
        fun setPreviewSize(previewSize: Size) {
            this.previewSize = previewSize
        }

        fun getTargets(): List<Surface> {
            cvResourcesCreated.block()
            return listOf(cameraSurface)
        }

        /** Initialize the EGL display, context, and render surface */
        private fun initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("unable to get EGL14 display")
            }
            checkEglError("eglGetDisplay")

            val version = intArrayOf(0, 0)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglDisplay = null
                throw RuntimeException("Unable to initialize EGL14")
            }
            checkEglError("eglInitialize")

            val eglVersion = "${version[0]}.${version[1]}"
            Log.i(TAG, "eglVersion: $eglVersion")

            val renderableType = EGL14.EGL_OPENGL_ES2_BIT

            val rgbBits = 8
            val alphaBits = 8

            val configAttribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_RED_SIZE, rgbBits,
                EGL14.EGL_GREEN_SIZE, rgbBits,
                EGL14.EGL_BLUE_SIZE, rgbBits,
                EGL14.EGL_ALPHA_SIZE, alphaBits,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = intArrayOf(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribList, 0, configs,
                0, configs.size, numConfigs, 0)
            eglConfig = configs[0]!!
            val requestedVersion = 2
            val contextAttribList = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, requestedVersion,
                EGL14.EGL_NONE
            )

            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribList, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("Failed to create EGL context")
            }

            val clientVersion = intArrayOf(0)
            EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
                clientVersion, 0)
            Log.v(TAG, "EGLContext created, client version " + clientVersion[0])

            val tmpSurfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val tmpSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, tmpSurfaceAttribs, /*offset*/ 0)
            EGL14.eglMakeCurrent(eglDisplay, tmpSurface, tmpSurface, eglContext)
        }


        private fun createResources(surface: Surface) {
            Log.d(TAG, "createResources")
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                initEGL()
            }

            val windowSurfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, windowSurfaceAttribs, 0)
            if (eglWindowSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("Failed to create EGL texture view surface")
            }

            cameraTexId = createTexture()
            cameraTexture = SurfaceTexture(cameraTexId)
            cameraTexture.setOnFrameAvailableListener(this)
            cameraTexture.setDefaultBufferSize(DEFAULT_PREVIEW_WIDTH_SIZE, DEFAULT_PREVIEW_HEIGHT_SIZE)
            cameraSurface = Surface(cameraTexture)

            renderTexId = createTexture()
            renderTexture = SurfaceTexture(renderTexId)
            renderTexture.setDefaultBufferSize(DEFAULT_PREVIEW_WIDTH_SIZE, DEFAULT_PREVIEW_HEIGHT_SIZE)
            renderSurface = Surface(renderTexture)

            val renderSurfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglRenderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, renderSurface, renderSurfaceAttribs, 0)
            if (eglRenderSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("Failed to create EGL render surface")
            }

            createShaderResources()

            cvResourcesCreated.open()
        }

        private fun createShaderResources() {
            vertexShader = createShader(GLES30.GL_VERTEX_SHADER, TRANSFORM_VSHADER)

            val passthroughFragmentShader = createShader(GLES30.GL_FRAGMENT_SHADER, PASSTHROUGH_FSHADER)
            val passthroughShaderProgram = createShaderProgram(passthroughFragmentShader)

            cameraToRenderShaderProgram = when (filterOn) {
                false -> passthroughShaderProgram
                true -> createShaderProgram(createShader(GLES30.GL_FRAGMENT_SHADER, PORTRAIT_FSHADER))
            }

            renderToPreviewShaderProgram = passthroughShaderProgram
        }

        /** Creates the shader program used to copy data from one texture to another */
        private fun createShaderProgram(fragmentShader: Int): ShaderProgram {
            val shaderProgram = GLES30.glCreateProgram()
            checkGlError("glCreateProgram")

            GLES30.glAttachShader(shaderProgram, vertexShader)
            checkGlError("glAttachShader")
            GLES30.glAttachShader(shaderProgram, fragmentShader)
            checkGlError("glAttachShader")
            GLES30.glLinkProgram(shaderProgram)
            checkGlError("glLinkProgram")

            val linkStatus = intArrayOf(0)
            GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
            checkGlError("glGetProgramiv")
            if (linkStatus[0] == 0) {
                val msg = "Could not link program: " + GLES30.glGetProgramInfoLog(shaderProgram)
                GLES30.glDeleteProgram(shaderProgram)
                throw RuntimeException(msg)
            }

            val vPositionLoc = GLES30.glGetAttribLocation(shaderProgram, "vPosition")
            checkGlError("glGetAttribLocation")
            val texMatrixLoc = GLES30.glGetUniformLocation(shaderProgram, "texMatrix")
            checkGlError("glGetUniformLocation")

            return ShaderProgram(shaderProgram, vPositionLoc, texMatrixLoc)
        }

        /** Create a shader given its type and source string */
        private fun createShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            checkGlError("glShaderSource")
            GLES30.glCompileShader(shader)
            checkGlError("glCompileShader")
            val compiled = intArrayOf(0)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            checkGlError("glGetShaderiv")
            if (compiled[0] == 0) {
                val msg = "Could not compile shader " + type + ": " + GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw RuntimeException(msg)
            }
            return shader
        }

        private fun createTexId(): Int {
            val buffer = IntBuffer.allocate(1)
            GLES30.glGenTextures(1, buffer)
            return buffer.get(0)
        }

        private fun destroyTexId(id: Int) {
            val buffer = IntBuffer.allocate(1)
            buffer.put(0, id)
            GLES30.glDeleteTextures(1, buffer)
        }

        private fun createFboId(): Int {
            val buffer = IntBuffer.allocate(1)
            GLES30.glGenFramebuffers(1, buffer)
            return buffer.get(0)
        }

        private fun destroyFboId(id: Int) {
            val buffer = IntBuffer.allocate(1)
            buffer.put(0, id)
            GLES30.glDeleteFramebuffers(1, buffer)
        }

        /** Create an OpenGL texture */
        private fun createTexture(): Int {
            /* Check that EGL has been initialized. */
            if (eglDisplay == null) {
                throw IllegalStateException("EGL not initialized before call to createTexture()");
            }

            val texId = createTexId()
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE)
            return texId
        }

        private fun destroyWindowSurface() {
            if (eglWindowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroySurface(eglDisplay, eglWindowSurface)
            }
            eglWindowSurface = EGL14.EGL_NO_SURFACE
            cvDestroyWindowSurface.open()
        }

        public fun waitDestroyWindowSurface() {
            cvDestroyWindowSurface.block()
        }

        private fun copyTexture(
            texId: Int, texture: SurfaceTexture, viewportRect: Rect,
            shaderProgram: ShaderProgram, outputIsFramebuffer: Boolean,
        ) {
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            checkGlError("glClearColor")
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            checkGlError("glClear")

            shaderProgram.useProgram()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            checkGlError("glActiveTexture")
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            checkGlError("glBindTexture")

            texture.getTransformMatrix(texMatrix)

            // HardwareBuffer coordinates are flipped relative to what GLES expects
            if (outputIsFramebuffer) {
                val flipMatrix = floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, -1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 1f, 0f, 1f
                )
                Matrix.multiplyMM(texMatrix, 0, flipMatrix, 0, texMatrix.clone(), 0)
            }
            shaderProgram.setTexMatrix(texMatrix)

            shaderProgram.setVertexAttribArray(FULLSCREEN_QUAD)
            GLES30.glViewport(viewportRect.left, viewportRect.top, viewportRect.right, viewportRect.bottom)

            checkGlError("glViewport")
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("glDrawArrays")
        }

        private fun copyCameraToRender() {
            EGL14.eglMakeCurrent(eglDisplay, eglRenderSurface, eglRenderSurface, eglContext)

            copyTexture(cameraTexId, cameraTexture, Rect(0, 0, DEFAULT_PREVIEW_WIDTH_SIZE, DEFAULT_PREVIEW_HEIGHT_SIZE), cameraToRenderShaderProgram!!, false)

            EGL14.eglSwapBuffers(eglDisplay, eglRenderSurface)
            renderTexture.updateTexImage()
        }

        private fun copyRenderToPreview() {
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglRenderSurface, eglContext)

            val cameraAspectRatio = DEFAULT_RECORDING_WIDTH_SIZE.toFloat() / DEFAULT_RECORDING_HEIGHT_SIZE.toFloat()
            val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
            var viewportWidth = previewSize.width
            var viewportHeight = previewSize.height
            var viewportX = 0
            var viewportY = 0

            /** The camera display is not the same size as the video. Letterbox the preview so that
             * we can see exactly how the video will turn out. */
            if (previewAspectRatio < cameraAspectRatio) {
                /** Avoid vertical stretching */
                viewportHeight = ((viewportHeight.toFloat() / previewAspectRatio) * cameraAspectRatio).toInt()
                viewportY = (previewSize.height - viewportHeight) / 2
            } else {
                /** Avoid horizontal stretching */
                viewportWidth = ((viewportWidth.toFloat() / cameraAspectRatio) * previewAspectRatio).toInt()
                viewportX = (previewSize.width - viewportWidth) / 2
            }

            copyTexture(renderTexId, renderTexture, Rect(viewportX, viewportY, viewportWidth, viewportHeight), renderToPreviewShaderProgram!!, false)
            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface)
        }


        private fun copyRenderToEncode() {
//            Log.d(TAG, "copyRenderToEncode")
            EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglRenderSurface, eglContext)
            val viewportWidth = DEFAULT_RECORDING_WIDTH_SIZE
            val viewportHeight = DEFAULT_RECORDING_HEIGHT_SIZE

            // TODO Front 카메라인 경우는 좌우 반전해줘야함 createShaderProgram(createShader(GLES30.GL_FRAGMENT_SHADER, MIRRO_PASSTHROUGH_FSHADER))
            // TODO Back 카메라인 경우는 preview에 쉐더와 같은 값을 사용 passthroughShaderProgram
            val encodeShaderProgram =
                if(isMirror) createShaderProgram(createShader(GLES30.GL_FRAGMENT_SHADER, MIRRO_PASSTHROUGH_FSHADER))
                else renderToPreviewShaderProgram

            renderToEncodeShaderProgram = encodeShaderProgram


            copyTexture(renderTexId, renderTexture, Rect(0, 0, viewportWidth, viewportHeight), renderToEncodeShaderProgram!!, false)

            encoder?.frameAvailable()

            EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface)
        }


        private fun actionDown(encoderSurface: Surface) {
            Log.d(TAG, "actionDown")
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, surfaceAttribs, 0)
            if (eglEncoderSurface == EGL14.EGL_NO_SURFACE) {
                val error = EGL14.eglGetError()
                throw RuntimeException("Failed to create EGL encoder surface"
                        + ": eglGetError = 0x" + Integer.toHexString(error))
            }
        }

        private fun clearFrameListener() {
            Log.d(TAG, "clearFrameListener")
            cameraTexture.setOnFrameAvailableListener(null)
            cvClearFrameListener.open()
        }

        fun waitClearFrameListener() {
            cvClearFrameListener.block()
        }

        private fun cleanup() {
            Log.d(TAG, "cleanup")
            EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface)
            eglEncoderSurface = EGL14.EGL_NO_SURFACE
            EGL14.eglDestroySurface(eglDisplay, eglRenderSurface)
            eglRenderSurface = EGL14.EGL_NO_SURFACE

            cameraTexture.release()

            if (windowTexId > 0) {
                destroyTexId(windowTexId)
            }

            if (windowFboId > 0) {
                destroyFboId(windowFboId)
            }

            EGL14.eglDestroyContext(eglDisplay, eglContext)

            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT

            cvCleanup.open()
        }

        public fun waitCleanup() {
            cvCleanup.block()
        }

        @Suppress("UNUSED_PARAMETER")
        private fun onFrameAvailableImpl(surfaceTexture: SurfaceTexture) {
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "onFrameAvailableImpl, EGL_NO_CONTEXT")
                return
            }

            /** 카메라 API는 텍스처 이미지를 업데이트하지 않습니다. 여기서 수행합니다. */
            cameraTexture.updateTexImage()

            /** 카메라 텍스처에서 렌더 텍스처로 복사 */
            if (eglRenderSurface != EGL14.EGL_NO_SURFACE) {
                copyCameraToRender()
            }

            /** 렌더 텍스처에서 TextureView로 복사 */
            copyRenderToPreview()

//            Log.d(TAG, "onFrameAvailable: eglEncoderSurface is not EGL_NO_SURFACE? ${eglEncoderSurface != EGL14.EGL_NO_SURFACE}, currentlyRecording=$currentlyRecording")
            /** 현재 녹화 중이라면 인코더 서페이스로 복사 */
            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE && currentlyRecording) {
                copyRenderToEncode()
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
//            Log.d(TAG, "onFrameAvailable")
            sendMessage(obtainMessage(MSG_ON_FRAME_AVAILABLE, 0, 0, surfaceTexture))
        }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_CREATE_RESOURCES -> createResources(msg.obj as Surface)
                MSG_DESTROY_WINDOW_SURFACE -> destroyWindowSurface()
                MSG_ACTION_DOWN -> actionDown(msg.obj as Surface)
                MSG_CLEAR_FRAME_LISTENER -> clearFrameListener()
                MSG_CLEANUP -> cleanup()
                MSG_ON_FRAME_AVAILABLE -> onFrameAvailableImpl(msg.obj as SurfaceTexture)
            }
        }

    }

    private class ShaderProgram(
        private val id: Int,
        private val vPositionLoc: Int,
        private val texMatrixLoc: Int,
    ) {

        fun setVertexAttribArray(vertexCoords: FloatArray) {
            val nativeBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            nativeBuffer.order(ByteOrder.nativeOrder())
            val vertexBuffer = nativeBuffer.asFloatBuffer()
            vertexBuffer.put(vertexCoords)
            nativeBuffer.position(0)
            vertexBuffer.position(0)

            GLES30.glEnableVertexAttribArray(vPositionLoc)
            checkGlError("glEnableVertexAttribArray")
            GLES30.glVertexAttribPointer(vPositionLoc, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer)
            checkGlError("glVertexAttribPointer")
        }

        fun setTexMatrix(texMatrix: FloatArray) {
            GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
            checkGlError("glUniformMatrix4fv")
        }

        fun useProgram() {
            GLES30.glUseProgram(id)
            checkGlError("glUseProgram")
        }
    }

    class Encoder(private val outputFile: File) {
        private val codec by lazy { MediaCodec.createEncoderByType(VIDEO_ENCODER_MIMETYPE) }
        private val encoderThread by lazy { EncoderThread(codec, outputFile) }
        private var inputSurface: Surface? = null

        init {
            val format = MediaFormat.createVideoFormat(VIDEO_ENCODER_MIMETYPE, DEFAULT_RECORDING_WIDTH_SIZE, DEFAULT_RECORDING_HEIGHT_SIZE)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        fun getInputSurface(): Surface {
            if(inputSurface == null) inputSurface = codec.createInputSurface()
            return inputSurface!!
        }

        fun releaseInputSurface(){
            if(inputSurface!=null) inputSurface!!.release()
            inputSurface = null
        }

        fun getOutputFile() = outputFile

        fun encodingStart(){
            codec.start()
            encoderThread.start()
            encoderThread.waitUntilReady()
        }

        /**
         * Shuts down the encoder thread, and releases encoder resources.
         * <p>
         * Does not return until the encoder thread has stopped.
         */
        fun encodingShutDown(): Boolean {
            Log.d(TAG, "releasing encoder objects")
            val handler = encoderThread.getHandler()
            handler.sendMessage(handler.obtainMessage(EncoderHandler.MSG_SHUTDOWN))
            try {
                encoderThread.join()
            } catch (ie: InterruptedException ) {
                Log.w(TAG, "Encoder thread join() was interrupted", ie)
            }

            codec.stop()
            codec.release()
            return true
        }


        /**
         * Notifies the encoder thread that a new frame is available to the encoder.
         */
        fun frameAvailable() {
            val handler = encoderThread.getHandler()
            handler.sendMessage(handler.obtainMessage(EncoderHandler.MSG_FRAME_AVAILABLE))
        }

        fun waitForFirstFrame() {
            encoderThread.waitForFirstFrame()
        }

        fun startEncodeThread(){
            encoderThread.startEncodeThread()
        }

        fun resumeEncodeThread(){
            encoderThread.resumeEncodeThread()
        }

        fun pauseEncodeThread() {
            encoderThread.pauseEncodeThread()
        }


        /**
         * Object that encapsulates the encoder thread.
         * <p>
         * We want to sleep until there's work to do.  We don't actually know when a new frame
         * arrives at the encoder, because the other thread is sending frames directly to the
         * input surface.  We will see data appear at the decoder output, so we can either use
         * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
         * calling app wake us.  It's very useful to have all of the buffer management local to
         * this thread -- avoids synchronization -- so we want to do the file muxing in here.
         * So, it's best to sleep on an object and do something appropriate when awakened.
         * <p>
         * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
         * should be fully started before the thread is created, and not shut down until this
         * thread has been joined.
         */
        private class EncoderThread(mediaCodec: MediaCodec, outputFile: File): Thread() {
            var codec = mediaCodec
            var format: MediaFormat? = null
            val bufferInfo = MediaCodec.BufferInfo()
            var muxer = MediaMuxer(outputFile.path, VIDEO_OUTPUT_MUXER)
            val orientationHint = 0
            
            var videoTrack = -1
            var encoderHandler: EncoderHandler? = null
            
            var frameNum: Int = 0
            val lock: Object = Object()

            @Volatile
            var ready: Boolean = false

            private var lastPresentationTimeUs: Long = 0 // 마지막 프레임의 presentationTimeUs 저장
            private var pauseStartTimeUs: Long = 0 // 일시 정지 시작 시간
            private var totalPausedDurationUs: Long = 0 // 일시 정지된 총 시간

            // 기존 메서드들...

            fun startEncodeThread() {
                lastPresentationTimeUs = 0
                pauseStartTimeUs = 0
                totalPausedDurationUs = 0
                // 녹화 시작 관련 로직...
            }

            fun pauseEncodeThread() {
                pauseStartTimeUs = System.nanoTime() / 1000 // 일시 정지 시작 시간을 기록
            }

            fun resumeEncodeThread() {
                val pauseEndTimeUs = System.nanoTime() / 1000
                totalPausedDurationUs += pauseEndTimeUs - pauseStartTimeUs // 일시 정지된 시간을 추가
            }

            /**
             * Thread entry point.
             * <p>
             * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
             */
            override fun run() {
                Log.d(TAG, "run")
                Looper.prepare()
                encoderHandler = EncoderHandler(this)
                Log.d(TAG, "run: ready1=$ready")
                synchronized(lock) {
                    ready = true
                    lock.notify()
                }
                
                Looper.loop()

                synchronized(lock) {
                    ready = false
                    encoderHandler = null
                }
                Log.d(TAG, "encoder thread ready2=$ready")
                Log.d(TAG, "looper quit")
            }

            /**
             * Waits until the encoder thread is ready to receive messages.
             * <p>
             * Call from non-encoder thread.
             */
            fun waitUntilReady() {
                Log.d(TAG, "waitUntilReady")
                synchronized (lock) {
                    while (!ready) {
                        try {
                            lock.wait()
                        } catch (ie: InterruptedException) { /* not expected */ }
                    }
                }
            }

            /**
             * Waits until the encoder has processed a single frame.
             * <p>
             * Call from non-encoder thread.
             */
            fun waitForFirstFrame() {
                synchronized (lock) {
                    while (frameNum < 1) {
                        try {
                            lock.wait()
                        } catch (ie: InterruptedException) {
                            ie.printStackTrace();
                        }
                    }
                }
                Log.d(TAG, "Waited for first frame");
            }

            /**
             * Returns the Handler used to send messages to the encoder thread.
             */
            fun getHandler(): EncoderHandler {
                synchronized (lock) {
                    // Confirm ready state.
                    if (!ready) {
                        throw RuntimeException("not ready")
                    }
                }
                return encoderHandler!!
            }

            /**
             * Drains all pending output from the encoder, and adds it to the circular buffer.
             */
            fun drainEncoder(): Boolean {
                val TIMEOUT_USEC: Long = 0     // no timeout -- check for buffers, bail if none
                var encodedFrame = false

                while (true) {
                    val encoderStatus: Int = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        break;
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Should happen before receiving buffers, and should only happen once.
                        // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                        // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                        // rather than extract the codec-specific data and reconstruct a new
                        // MediaFormat later, we just grab it here and keep it around.
                        format = codec.outputFormat
                        Log.d(TAG, "encoder output format changed: $format")
                    } else if (encoderStatus < 0) {
                        Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                        // let's ignore it
                    } else {
                        val encodedData: ByteBuffer = codec.getOutputBuffer(encoderStatus) ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // The codec config data was pulled out when we got the
                            // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                            // a single big blob -- it wants separate csd-0/csd-1 chunks --
                            // so simply saving this off won't work.
                            Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            // bufferInfo.presentationTimeUs를 조정해야 할 수도 있습니다.
                            if (lastPresentationTimeUs == 0L) {
                                lastPresentationTimeUs = bufferInfo.presentationTimeUs
                            } else {
                                // 현재 프레임의 타임스탬프를 조정합니다.
                                // 여기서는 System.nanoTime()을 마이크로초 단위로 변환하고, 녹화 시작 이후의 실제 경과 시간을 계산해야 합니다.
                                val elapsedTimeUs = (System.nanoTime() / 1000) - totalPausedDurationUs
                                bufferInfo.presentationTimeUs = elapsedTimeUs
                                lastPresentationTimeUs = bufferInfo.presentationTimeUs
                            }

                            // adjust the ByteBuffer values to match BufferInfo (not needed?)
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)

                            if (videoTrack == -1) {
                                videoTrack = muxer.addTrack(format!!)
                                muxer.setOrientationHint(orientationHint)
                                muxer.start()
                                Log.d(TAG, "Started media muxer")
                            }
                            // mEncBuffer.add(encodedData, mBufferInfo.flags,
                            //         mBufferInfo.presentationTimeUs)
                            muxer.writeSampleData(videoTrack, encodedData, bufferInfo)
                            encodedFrame = true
//                            Log.d(TAG, "sent " + bufferInfo.size + " bytes to muxer, ts=${bufferInfo.presentationTimeUs}")
                        }

                        codec.releaseOutputBuffer(encoderStatus, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.w(TAG, "reached end of stream unexpectedly")
                            break      // out of while
                        }
                    }
                }

                return encodedFrame
            }
            
            /**
             * Drains the encoder output.
             * <p>
             * See notes for {@link EncoderWrapper#frameAvailable()}.
             */
            fun frameAvailable() {
                if (drainEncoder()) {
                    synchronized (lock) {
                        frameNum++
                        lock.notify()
                    }
                }
            }

            /**
             * Tells the Looper to quit.
             */
            fun shutdown() {
                Log.d(TAG, "shutdown")
                Looper.myLooper()!!.quit()
                muxer.stop()
                muxer.release()
            }

            fun release(){
                muxer.stop()
                muxer.release()
            }
        }

        /**
         * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
         * is driving the encoder) to the encoder thread.
         * <p>
         * The object is created on the encoder thread.
         */
        private class EncoderHandler(et: EncoderThread): Handler(Looper.myLooper()!!) {
            companion object {
                const val MSG_FRAME_AVAILABLE: Int = 0
                const val MSG_SHUTDOWN: Int = 1
            }

            // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
            // but no real harm in it.
            private val mWeakEncoderThread = WeakReference<EncoderThread>(et)

            // runs on encoder thread
            override fun handleMessage(msg: Message) {
                val what: Int = msg.what
                val encoderThread: EncoderThread? = mWeakEncoderThread.get()
                if (encoderThread == null) {
                    Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null")
                    return
                }

                when (what) {
                    MSG_FRAME_AVAILABLE -> encoderThread.frameAvailable()
                    MSG_SHUTDOWN -> encoderThread.shutdown()
                    else -> throw RuntimeException("unknown message $what")
                }
            }
        }

    }
}