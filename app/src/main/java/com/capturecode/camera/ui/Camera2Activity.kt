package com.capturecode.camera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.*
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.*
import androidx.camera.core.CameraSelector
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.math.MathUtils.clamp
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import com.camera.utils.*
import com.camera.utils.FileUtils
import com.camera.utils.Monitor.Companion.MSG_UPDATE_SIZE
import com.camera.utils.Monitor.Companion.MSG_UPDATE_TIME
import com.capturecode.camera.CameraViewModelFactory
import com.capturecode.camera.R
import com.capturecode.camera.core.CameraConfig
import com.capturecode.camera.core.CameraConfig.MIN_REQUIRED_RECORDING_TIME_MILLIS
import com.capturecode.camera.core.CameraConfig.VIDEO_MAX_SIZE
import com.capturecode.camera.core.CameraConfig.VIDEO_SIZE_COMPLEMENT
import com.capturecode.camera.core.VideoModule
import com.capturecode.camera.utils.ANIMATION_FAST_MILLIS
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Runnable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class Camera2Activity : AppCompatActivity() {
    companion object{
        private const val TAG = "Camera2Activity"
        private const val IMAGE_BUFFER_SIZE: Int = 3
        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }
    }

    private val viewModel:CameraViewModel by lazy { ViewModelProvider(this, CameraViewModelFactory())[CameraViewModel::class.java] }

    /** [HandlerThread] where all camera operations run */
    private var cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private var cameraHandler = Handler(cameraThread.looper)

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    // == Common UI ==
    private val afSurfaceView: AutoFitSurfaceView by lazy {findViewById(R.id.afs_camera_view)}
    private val ivPerformButton: ImageView by lazy { findViewById(R.id.iv_camera_capture) }
    private val ivSwitchCamera: ImageView by lazy { findViewById(R.id.iv_camera_switch) }
    private val ivStop: ImageView by lazy { findViewById(R.id.iv_camera_stop) }
    private val ivPause: ImageView by lazy { findViewById(R.id.iv_camera_pause) }
    private val ivFlash: ImageView by lazy { findViewById(R.id.iv_camera_flash) }
    private val ivBack: ImageView by lazy { findViewById(R.id.iv_camera_back) }
    private val lineTitle: LinearLayout by lazy { findViewById(R.id.line_camera_title_layout) }
    private val tvTimer: TextView by lazy { findViewById(R.id.tv_camera_timer) }
    private val ivState: ImageView by lazy { findViewById(R.id.iv_camera_state) }
    private val lineFileSizeLayout: LinearLayout by lazy { findViewById(R.id.line_camera_progress_layout) }
    private val tvFileSize: TextView by lazy { findViewById(R.id.tv_camera_file_size) }
    private val pbFileSize: ProgressBar by lazy { findViewById(R.id.pb_camera_file_size) }
    private val overlay: View by lazy { findViewById(R.id.overlay) }
    private val tvProgressState: TextView by lazy { findViewById(R.id.tv_camera_progress_state) }
    private val constCameraButtonLayout: ConstraintLayout by lazy { findViewById(R.id.const_camera_button_layout) }
    private val constSaveProgressLayout: ConstraintLayout by lazy { findViewById(R.id.const_camera_save_progress_layout) }

    // == Common UI ==
    @Volatile private var isActivityActive = true

    // == Video Record ==
    private var videoModule: VideoModule? = null

    private val monitorHandler by lazy {
        object : Handler(Looper.getMainLooper()){
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                when(msg.what) {
                    MSG_UPDATE_SIZE -> {
                        val size = msg.obj as Long
                        val formatSize = CameraFormatter.formatFileSize(size, 1000, "int")
                        tvFileSize.text = formatSize
                        val fSize = formatSize.split(" ")[0].toInt()
                        pbFileSize.progress = fSize
                        if(size >= VIDEO_MAX_SIZE-VIDEO_SIZE_COMPLEMENT) {
                            stopRecording()
                        }
                    }
                    MSG_UPDATE_TIME -> {
                        val time = msg.obj as Long
                        viewModel.runningTimeMillis += time
                        tvTimer.text = CameraFormatter.formatTimer(viewModel.runningTimeMillis, false)
                    }
                }
            }
        }
    }

    // == Image Capture ==
    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader
    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            overlay.postDelayed({
                // Remove white flash animation
                overlay.background = null
            }, ANIMATION_FAST_MILLIS)
        }
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy { cameraManager.getCameraCharacteristics(viewModel.getCameraId()) }

    /** [HandlerThread] where all buffer reading operations run */
    private var imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private var imageReaderHandler = Handler(imageReaderThread.looper)

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData



    private var fileMonitor : Monitor<File>? = null
    private var timeMonitor: Monitor<Long>? = null

    /** Condition variable for blocking until the recording completes */
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        if(!PermissionUtils.hasPermissions(this)){
            Log.d(TAG, "onCreate: has not permission, hasPermissions is ${PermissionUtils.hasPermissions(this)}")
            requestPermissions(PermissionUtils.PERMISSIONS_REQUIRED, PermissionUtils.PERMISSIONS_REQUEST_CODE)
            return
        }

        init()

    }


    private fun init(){
        Log.d(TAG, "init")
        viewModel.updateType(intent)
        viewModel.uiState.postValue(UiState.PREPARING)

        setUpCamera()

        initCameraButtonUI()
    }


    override fun onRestart() {
        super.onRestart()
        viewModel.uiState.postValue(UiState.PREPARING)
        Log.d(TAG, "onRestart")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onRestart")
        isActivityActive = true
    }


    private fun initCameraButtonUI() {
//        val constraintSet = ConstraintSet()
//        constraintSet.clone(constCameraButtonLayout)
//        constraintSet.connect(R.id.const_camera_button_layout, ConstraintSet.TOP, R.id.afs_camera_view, ConstraintSet.BOTTOM, 16)
//        constraintSet.applyTo(constCameraButtonLayout) // 변경된 제약 조건을 적용

        ivPerformButton.setOnClickListener {
            if(viewModel.uiState.value == UiState.PREPARING) {
                Handler(Looper.getMainLooper()).post { Toast.makeText(this@Camera2Activity, "카메라 준비중", Toast.LENGTH_LONG).show() }
                return@setOnClickListener
            }
            Log.d(TAG, "ivRecording: clicked, ${viewModel.uiState.value}")
            if(viewModel.isCaptureMode()) {
                capturePicture(it)
            }
            else{
                startRecording()
            }

        }

        ivStop.setOnClickListener {
            stopRecording()
        }

        ivPause.setOnClickListener {
            if(viewModel.uiState.value == UiState.RECORDING) {
                pauseRecording()
            }
        }

        ivFlash.setOnClickListener {
            Log.d(TAG, "init: ivFlash clicked")
            viewModel.flashOn = !viewModel.flashOn
            if(viewModel.flashOn) ivFlash.setImageDrawable(ContextCompat.getDrawable(this@Camera2Activity, R.drawable.ic_fill_flash_24))
            else ivFlash.setImageDrawable(ContextCompat.getDrawable(this@Camera2Activity, R.drawable.ic_fill_flash_off_24))

            val captureRequest: CaptureRequest =
                if(viewModel.isCaptureMode()) camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(afSurfaceView.holder.surface)
                    if(viewModel.flashOn) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    else set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }.build()
                else videoModule!!.createRecordRequest(session, viewModel.flashOn).build()

            session.setRepeatingRequest(captureRequest, null, cameraHandler)
        }

        ivBack.setOnClickListener {
            finish()
        }

        ivSwitchCamera.setOnClickListener {
            if(viewModel.uiState.value == UiState.IDLE) {
                viewModel.switchCamera()
                resetCamera()
            }
            else{
                Handler(Looper.getMainLooper()).post { Toast.makeText(this@Camera2Activity, "카메라 준비중", Toast.LENGTH_LONG).show() }
            }
        }

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(this, characteristics).apply {
            observe(this@Camera2Activity) { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            }
        }

        viewModel.uiState.distinctUntilChanged().observe(this) {
            Log.d(TAG, "uiState $it")
            showUI(it)
        }

//        afSurfaceView.setOnTouchListener { v, event ->
//            if (event.action == MotionEvent.ACTION_UP) {
//                val x = event.x
//                val y = event.y
//                handleFocus(x, y)
//            }
//            true
//        }
    }

    private fun setUpCamera(){
        if(!viewModel.isCaptureMode()) {
            videoModule = VideoModule()
            imageReaderHandler.looper.quitSafely()
            imageReaderThread.quitSafely()
        }
        Log.d(TAG, "setUpCamera: captureMode=${viewModel.isCaptureMode()}")
        afSurfaceView.holder.addCallback(object : SurfaceHolder.Callback{
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                try { // 사이버텔단말기에서는 이 구문 때문에 크래시 발생함, 삼성은 발생하지 않음
                    videoModule?.destroyWindowSurface()
                    videoModule?.release()
                }
                catch (e: Exception){
                    Log.w(TAG, "surfaceDestroyed: ")
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "surfaceChanged: surfaceSize=${width}x${height}")

            }
            override fun surfaceCreated(holder: SurfaceHolder) {
//                val surfaceViewSize = if(viewModel.isCaptureMode()) getSurfaceViewHDSize(afSurfaceView.display, "3:4")
//                    else getSurfaceViewSDSize(afSurfaceView.display)
                if(isEncoding) {
                    viewModel.uiState.postValue(UiState.FINALIZED)
                    Log.d(TAG, "surfaceCreated: isEncoding=$isEncoding")
                    return
                }
                videoModule!!.init()

                val surfaceViewSize = getSurfaceViewRatioSize(afSurfaceView.display, "3:4")
                Log.d(TAG, "surfaceCreated: afSurfaceViewSize=${afSurfaceView.width}x${afSurfaceView.height}")
                Log.d(TAG, "surfaceCreated: surfaceViewSize=$surfaceViewSize")
                afSurfaceView.setAspectRatio(surfaceViewSize.width, surfaceViewSize.height)
                videoModule?.setPreviewSize(surfaceViewSize)
                afSurfaceView.post {
                    videoModule?.createResources(holder.surface)
                    initializeCamera()
                }
            }
        })
    }

    private fun resetCamera() {
        Log.d(TAG, "resetCamera")
        try {
            // 카메라 텍스쳐 종료
            cameraHandler.looper.quitSafely()
            cameraThread.quitSafely()

            // 이미지 리더기 종료 (사진 촬영인 경우)
            imageReaderHandler.looper.quitSafely()
            imageReaderThread.quitSafely()

            // 캡쳐 세션 종료
            session.stopRepeating()
            session.close()

            // 카메라 종료
            camera.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
        finally {
            cameraThread = HandlerThread("CameraThread").apply { start() }
            cameraHandler = Handler(cameraThread.looper)
            imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
            imageReaderHandler = Handler(imageReaderThread.looper)
        }
        // 새로운 카메라로 초기화 및 세션 시작
        initializeCamera()
    }



    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "initializeCamera")
        // Open the selected camera

        camera = openCamera(cameraManager, viewModel.getCameraId(), cameraHandler)


        val previewTargets = if(viewModel.isCaptureMode()) {
            // Initialize an image reader which will be used to capture still photos
            val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.height * it.width }!!
            Log.d(TAG, "initializeCamera: size=${size}")
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE)
            // Creates list of Surfaces where the camera will output frames
            listOf(afSurfaceView.holder.surface, imageReader.surface)
        }
        else{
            // Creates list of Surfaces where the camera will output frames
            videoModule!!.getPreviewTargets()
        }


        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, previewTargets, cameraHandler, recordingCompleteOnClose = true)

        val captureRequest: CaptureRequest =
            if(viewModel.isCaptureMode()) camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(afSurfaceView.holder.surface)
                if(viewModel.flashOn) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                else set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }.build()
            else videoModule!!.createRecordRequest(session, viewModel.flashOn).build()


        session.setRepeatingRequest(captureRequest, null, cameraHandler)
        viewModel.uiState.postValue(UiState.IDLE)
    }

    private fun pauseRecording() {
        if(videoModule == null) {
            Log.w(TAG, "pauseRecording: videoModule is null")
            return
        }
        fileMonitor?.pause()
        timeMonitor?.pause()
        viewModel.uiState.postValue(UiState.PAUSE)
        videoModule!!.stopRecording()
        videoModule!!.encoder.pauseEncodeThread()
    }

    private fun capturePicture(view: View){
        view.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            // Perform I/O heavy operations in a different scope
            takePhoto().use { result ->
                Log.d(TAG, "Result received: $result")

                // Save the result to disk
                val output = saveResult(result)
                Log.d(TAG, "Image saved: ${output.absolutePath}")

                // If the result is a JPEG file, update EXIF metadata with orientation info
                if (output.extension == "jpg") {
                    val exif = ExifInterface(output.absolutePath)
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                    exif.saveAttributes()
                    Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                }

                // Display the photo taken to user
                // TODO 이미지를 보여줄 화면으로 이동
//                lifecycleScope.launch(Dispatchers.Main) {
//                    navController.navigate(CameraFragmentDirections
//                        .actionCameraToJpegViewer(output.absolutePath)
//                        .setOrientation(result.orientation)
//                        .setDepth(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
//                                result.format == ImageFormat.DEPTH_JPEG))
//                }
            }

            // Re-enable click listener after photo is taken
            view.post { view.isEnabled = true }
        }
    }


    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto(): CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                afSurfaceView.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = viewModel.lensSpacing == CameraSelector.LENS_FACING_FRONT

                        Log.d(TAG, "onCaptureCompleted: rotation=$rotation, mirrored=$mirrored")
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        Log.d(TAG, "onCaptureCompleted: exifOrientation=$exifOrientation")
                        Log.d(TAG, "onCaptureCompleted: exifOrientation= 8")
                        // Build the result and resume progress
                        cont.resume(CombinedCaptureResult(image, result, exifOrientation, imageReader.imageFormat))

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        val buffer = result.image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

        try {
            val output = FileUtils.createMediaFile(FileUtils.TYPE_PICTURE)
            FileOutputStream(output).use { it.write(bytes) }
            cont.resume(output)
        } catch (exc: IOException) {
            Log.e(TAG, "Unable to write JPEG image to file", exc)
            cont.resumeWithException(exc)
        }
    }


    private fun startRecording(){
        if(videoModule == null) {
            Log.w(TAG, "startRecording: videoModule is null")
            return
        }

        fileMonitor = Monitor(videoModule!!.encoder.getOutputFile(), monitorHandler)
        timeMonitor = Monitor(System.currentTimeMillis(), monitorHandler)
        fileMonitor?.observe()
        timeMonitor?.observe()

        lifecycleScope.launch(Dispatchers.IO) {
            /* If the recording was already started in the past, do nothing. */
            if (viewModel.uiState.value == UiState.IDLE) {
//                // Prevents screen rotation during the video recording
//                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                videoModule!!.setMirror(viewModel.lensSpacing)
                videoModule!!.actionDown(videoModule!!.encoder.getInputSurface())

                // Finalizes encoder setup and starts recording
                videoModule!!.encoder.encodingStart()
                cvRecordingStarted.open()
                videoModule!!.startRecording()
//                videoModule!!.encoder.startEncodeThread()
                viewModel.recordingStartMillis = System.currentTimeMillis()
                Log.d(TAG, "Recording started")
                viewModel.uiState.postValue(UiState.RECORDING)
            }
            else if(viewModel.uiState.value == UiState.PAUSE) {
                videoModule!!.startRecording()
                videoModule!!.encoder.resumeEncodeThread()
                viewModel.uiState.postValue(UiState.RECORDING)
            }
        }
    }

    private var isEncoding = false
    private fun stopRecording() {
        fileMonitor?.stop()
        fileMonitor = null
        timeMonitor?.stop()
        timeMonitor = null

//        if (viewModel.uiState.value != UiState.RECORDING || viewModel.uiState.value != UiState.PAUSE) {
        if (viewModel.uiState.value == UiState.STOP) {
            Log.w(TAG, "stopRecording: already stop UiState=${viewModel.uiState.value}")
            return
        }

        viewModel.uiState.postValue(UiState.STOP)
        if(videoModule == null) {
            Log.w(TAG, "stopRecording: videoModule is null")
            return
        }

        viewModel.uiState.postValue(UiState.FINALIZED)

        lifecycleScope.launch(Dispatchers.IO) {
            isEncoding = true
            cvRecordingStarted.block()

            viewModel.flashOn = false

            /* Wait for at least one frame to process so we don't have an empty video */
            videoModule!!.encoder.waitForFirstFrame()

            try {
                session.stopRepeating()
                session.close()
            }
            catch (e: java.lang.IllegalStateException){
                Log.w(TAG, "stopRecording: ", e)
                finish()
            }

            videoModule!!.clearFrameListener()

            /* Wait until the session signals onReady */
            cvRecordingComplete.block()

//            // Unlocks screen rotation after recording finished
//            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
            val elapsedTimeMillis = System.currentTimeMillis() - viewModel.recordingStartMillis
            if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
            }

            delay(CameraConfig.ANIMATION_SLOW_MILLIS)

            try {
                camera.close()
            } catch (exc: Throwable) {
                Log.e(TAG, "Error closing camera", exc)
            }

            videoModule!!.cleanup()
            val outputFile = videoModule!!.encoder.getOutputFile()
            Log.d(TAG, "Recording stopped. Output file: ${outputFile.absolutePath}")
            val saveResult = withContext(Dispatchers.IO) {
                videoModule!!.encoder.encodingShutDown()
            }

            videoModule?.destroyWindowSurface()
            videoModule?.release()

            if (saveResult) {
                // Broadcasts the media file to the rest of the system
                MediaScannerConnection.scanFile(this@Camera2Activity, arrayOf(outputFile.absolutePath), null, null)
                if (outputFile.exists()) {
                    // Launch external activity via intent to play video recorded using our provider
                    viewModel.runMediaFile(this@Camera2Activity, outputFile)
                    Handler(Looper.getMainLooper()).post { Toast.makeText(this@Camera2Activity, R.string.prompt_success, Toast.LENGTH_LONG).show() }

                } else {
                    // TODO:
                    //  1. Move the callback to ACTION_DOWN, activating it on the second press
                    //  2. Add an animation to the button before the user can press it again
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@Camera2Activity, R.string.error_file_not_found, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@Camera2Activity, R.string.recorder_shutdown_error,
                        Toast.LENGTH_LONG).show()
                }
            }
            isEncoding = false
        }
    }

    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.d(TAG, "onOpened: cameraId=" + device.id)
                cont.resume(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "onDisconnected: Camera $cameraId has been disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
                Handler(Looper.getMainLooper()).post { Toast.makeText(this@Camera2Activity, "카메라를 열 수 없습니다.", Toast.LENGTH_LONG).show() }
                finish()
            }
        }, handler)
    }


    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
        recordingCompleteOnClose: Boolean): CameraCaptureSession = suspendCoroutine { cont ->
        val stateCallback = object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession){
                Log.d(TAG, "createCaptureSession, onConfigured: session=${session.device}")

                cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("onConfigureFailed: Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }

            /** Called after all captures have completed - shut down the encoder */
            override fun onClosed(session: CameraCaptureSession) {
                if (!recordingCompleteOnClose or (viewModel.uiState.value == UiState.IDLE)) {
                    return
                }
                Log.d(TAG, "createCaptureSession, onClosed:")
                cvRecordingComplete.open()
            }
        }

        setupSessionWithDynamicRangeProfile(device, targets, handler, stateCallback)
    }

    private fun setupSessionWithDynamicRangeProfile(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
        stateCallback: CameraCaptureSession.StateCallback
    ): Boolean {
        try {
            device.createCaptureSession(targets, stateCallback, handler)
        }
        catch (e: java.lang.IllegalArgumentException){
            Log.w(TAG, "setupSessionWithDynamicRangeProfile: ", e)
        }
        return false
    }

    /**
     * initialize UI for recording:
     *  - at recording: hide audio, qualitySelection,change camera UI; enable stop button
     *  - otherwise: show all except the stop button
     */
    private fun showUI(state: UiState) {
        when(state) {
            UiState.PREPARING -> {
                tvProgressState.text = "카메라 준비중"
                ivPerformButton.isEnabled = false
                constSaveProgressLayout.isVisible = true
            }
            UiState.IDLE -> {
                constSaveProgressLayout.isVisible = false
                ivPerformButton.isEnabled = true
                ivPerformButton.visibility = View.VISIBLE
                ivSwitchCamera.visibility = View.VISIBLE
                ivSwitchCamera.isEnabled = true
                ivStop.visibility = View.INVISIBLE
                ivPause.visibility = View.INVISIBLE
//                ivFlash.visibility = View.INVISIBLE
                ivBack.visibility = View.VISIBLE
                lineTitle.visibility = View.INVISIBLE
                lineFileSizeLayout.visibility = View.INVISIBLE
                ivFlash.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_fill_flash_off_24))
            }
            UiState.RECORDING -> {
                ivPerformButton.visibility = View.INVISIBLE
                ivSwitchCamera.visibility = View.INVISIBLE
                ivStop.visibility = View.VISIBLE
                ivPause.visibility = View.VISIBLE
                ivFlash.visibility = View.VISIBLE
                ivBack.visibility = View.INVISIBLE
                lineTitle.visibility = View.VISIBLE
                lineFileSizeLayout.visibility = View.VISIBLE

                lineTitle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.record_button_color2)
                ivState.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record))
            }
            UiState.PAUSE -> {
                ivPerformButton.visibility = View.VISIBLE
                ivSwitchCamera.visibility = View.INVISIBLE
                ivStop.visibility = View.VISIBLE
                ivPause.visibility = View.INVISIBLE
                ivFlash.visibility = View.VISIBLE
                ivBack.visibility = View.INVISIBLE
                lineTitle.visibility = View.VISIBLE
                lineFileSizeLayout.visibility = View.VISIBLE

                lineTitle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.record_button_trans_background)
                ivState.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_pause_24))
            }
            UiState.FINALIZED -> {

                tvFileSize.text = "0 KB"
                pbFileSize.progress = 0
                tvTimer.text = ""
                viewModel.recordingStartMillis = 0L
                viewModel.runningTimeMillis = 0L

                ivPerformButton.visibility = View.INVISIBLE
                ivSwitchCamera.visibility = View.INVISIBLE
                ivStop.visibility = View.INVISIBLE
                ivPause.visibility = View.INVISIBLE
                ivFlash.visibility = View.INVISIBLE
                ivBack.visibility = View.INVISIBLE
                lineTitle.visibility = View.INVISIBLE
                lineFileSizeLayout.visibility = View.INVISIBLE
                constSaveProgressLayout.isVisible = true
                tvProgressState.text = if(viewModel.isCaptureMode()) "사진 저장중"
                else "영상 저장중"
//                viewModel.uiState.postValue(UiState.IDLE)
            }
            UiState.SWITCH -> {
                ivPerformButton.isEnabled = false
                ivSwitchCamera.isEnabled = false
            }
            else -> {
                Log.w(TAG, "showUI: $state is not matched")
            }
        }

        if(intent.type == FileUtils.TYPE_PICTURE){
            Log.d(TAG, "showUI: TYPE_PICTURE")
            lineTitle.visibility = View.INVISIBLE
            lineFileSizeLayout.visibility = View.INVISIBLE
            ivStop.visibility = View.INVISIBLE
        }
    }



    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                init()
            } else {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        isActivityActive = false
        super.onPause()
    }

    override fun onStop() {
        isActivityActive = false
        fileMonitor?.stop()
        fileMonitor = null
        timeMonitor?.stop()
        timeMonitor = null

        if(viewModel.uiState.value == UiState.PAUSE || viewModel.uiState.value == UiState.RECORDING) {
            Log.d(TAG, "onStop: recording stop")
            stopRecording()
        }
//        if(viewModel.uiState.value == UiState.IDLE) {
//            camera.close()
//        }
        Log.d(TAG, "onStop: uiState=${viewModel.uiState.value}")
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
//        videoModule?.release()
//        // 카메라 텍스쳐 종료
    }
    private fun handleFocus(touchX: Float, touchY: Float) {
        val characteristics = cameraManager.getCameraCharacteristics(viewModel.getCameraId())
        val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        // 여기에서는 간단한 예시로, 터치한 점을 기준으로 한 고정된 크기의 초점 영역을 계산합니다.
        // 실제 애플리케이션에서는 센서 크기와 터치 좌표에 맞게 조정할 필요가 있습니다.
        val focusAreaSize = 200
        val left = clamp((touchX / afSurfaceView.width.toDouble() * sensorArraySize!!.width() - focusAreaSize / 2).toInt(), 0, sensorArraySize.width() - focusAreaSize)
        val top = clamp((touchY / afSurfaceView.height.toDouble() * sensorArraySize.height() - focusAreaSize / 2).toInt(), 0, sensorArraySize.height() - focusAreaSize)

        val focusAreaRect = Rect(left, top, left + focusAreaSize, top + focusAreaSize)

        // 초점 영역 설정 및 요청 생성
        val captureRequestBuilder = videoModule!!.createRecordRequest(session, viewModel.flashOn)

        // AF 영역 설정
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(focusAreaRect, MeteringRectangle.METERING_WEIGHT_MAX)))

        // AF 트리거 설정
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

        // 변경 사항 적용
        session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
    }

}