package com.capturecode.camera.ui

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.util.Consumer
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.camera.utils.CameraFormatter
import com.camera.utils.FileUtils
import com.camera.utils.FileUtils.TYPE_PICTURE
import com.camera.utils.FileUtils.TYPE_VIDEO
import com.camera.utils.PermissionUtils
import com.capturecode.camera.CameraViewModelFactory
import com.capturecode.camera.R
import com.capturecode.camera.core.*
import com.capturecode.camera.utils.ANIMATION_FAST_MILLIS
import com.capturecode.camera.utils.simulateClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors


/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit
class CameraXActivity : AppCompatActivity(), ZoomGestureOverlayView.ZoomListener {

    companion object{
        private const val TAG = "CameraXActivity"
        private const val KEY_EVENT_ACTION = "key_event_action"
        private const val KEY_EVENT_EXTRA = "key_event_extra"
        const val VIDEO_MAX_SIZE = 9500000L
    }

    // == Common ==
    private val viewModel:CameraViewModel by lazy { ViewModelProvider(this, CameraViewModelFactory())[CameraViewModel::class.java] }

    private var camera: Camera? = null


    // == Common ==

    // == UI Component  ==
    private val zoomRootView: ZoomGestureOverlayView by lazy { ZoomGestureOverlayView(this) }
    private val previewView: PreviewView by lazy {findViewById(R.id.preview_camera_view)}
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
    private val tvFileMax: TextView by lazy { findViewById(R.id.tv_camera_file_max) }
    private val pbFileSize: ProgressBar by lazy { findViewById(R.id.pb_camera_file_size) }

    private val tvScale: TextView by lazy { findViewById(R.id.tv_camera_scale) }

    private val constSaveProgressLayout: ConstraintLayout by lazy { findViewById(R.id.const_camera_save_progress_layout) }
    private val tvProgressState: TextView by lazy { findViewById(R.id.tv_camera_progress_state) }
    private val overlay: View by lazy { findViewById(R.id.overlay) }

    private val constCameraButtonLayout: ConstraintLayout by lazy { findViewById(R.id.const_camera_button_layout) }

    // == UI Component  ==

    // == Video Recording ==
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent
    // == Video Recording ==


    // == Picture Capturing ==
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

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

    // == Picture Capturing ==

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(this) }

    private var cameraProvider: ProcessCameraProvider? = null
    private val broadcastManager: LocalBroadcastManager by lazy { LocalBroadcastManager.getInstance(this) }
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    ivPerformButton.simulateClick()
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        zoomRootView.addZoomListener(this)
        zoomRootView.isGestureVisible = false
        layoutInflater.inflate(R.layout.activity_camera, zoomRootView)
        setContentView(zoomRootView)


        if(!PermissionUtils.hasPermissions(this)){
            Log.d(TAG, "onCreate: has not permission")
            requestPermissions(PermissionUtils.PERMISSIONS_REQUIRED,
                PermissionUtils.PERMISSIONS_REQUEST_CODE)
            return
        }

        viewModel.updateType(intent)

        init()

        pbFileSize.max = VIDEO_MAX_SIZE.toInt()
        tvFileMax.text = CameraFormatter.formatFileSize(VIDEO_MAX_SIZE, 1000, "int")
    }

    override fun onResume() {
        super.onResume()
    }

    var touchStartTime = 0L
    private fun init(){
        if(viewModel.isCaptureMode()){
            val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
            broadcastManager.registerReceiver(volumeDownReceiver, filter)
        }
        previewView.post {
            initCameraButtonUI()

            lifecycleScope.launch {
                setUpCamera()
            }
        }

        previewView.setOnTouchListener { _, event ->
            if(!viewModel.isCaptureMode()) false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 터치 시작 시간 기록
                    val pointerCount = event.pointerCount
                    if (pointerCount > 1) {
                        false // 터치가 여러 개면 false를 반환해 ACTION_UP이 호출되지 않도록 함
                    }
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 터치 끝 시간 기록 및 지속 시간 계산
                    val touchEndTime = System.currentTimeMillis()
                    val touchDuration = touchEndTime - touchStartTime
                    if (touchDuration < 200) { // 200ms 미만으로 눌렀다 뗐을 경우, "클릭"으로 간주
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        focusOnPoint(point)
                    }
                    true
                }
                else -> false
            }
        }
    }
    private fun focusOnPoint(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            // 노출 조정을 위해 필요한 경우 FLAG_AE를 추가할 수 있습니다.
            // .addPoint(point, FocusMeteringAction.FLAG_AE)
            .build()
        val cameraControl = camera?.cameraControl ?: return

        Log.d(TAG, "focusOnPoint")
        // 포커스 시작
        cameraControl.startFocusAndMetering(action)
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        viewModel.newScale = 0.0

        cameraProvider = ProcessCameraProvider.getInstance(this).await()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        if(viewModel.isCaptureMode()) {
            // Build and bind the camera use cases
            bindCaptureUseCases()
        }
        else{
            bindRecordUseCase()
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            ivSwitchCamera.isVisible = viewModel.hasBackCamera(cameraProvider) && viewModel.hasFrontCamera(cameraProvider)
        } catch (exception: CameraInfoUnavailableException) {
            ivSwitchCamera.isVisible = false
        }
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCaptureUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowManager.currentWindowMetrics.bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val quality = Quality.LOWEST // viewModel.getQuality()
        val dimensionRatio2 = quality.getAspectRatioString(quality, true)
        previewView.updateLayoutParams<ConstraintLayout.LayoutParams> { dimensionRatio = dimensionRatio2 }
        val rotation = previewView.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(viewModel.lensSpacing).build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            // Set initial target rotation
            .setTargetRotation(rotation)

            .build()
        preview!!.setSurfaceProvider(previewView.surfaceProvider)

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    // Values returned from our analyzer are passed to the attached listener
                    // We log image analysis results here - you should do something useful
                    // instead!
//                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        if (camera != null) {
            // Must remove observers from the previous camera instance
            removeCameraStateObservers(camera!!.cameraInfo)
        }

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(previewView.surfaceProvider)
            observeCameraState(camera?.cameraInfo!!)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }


    private fun initCameraButtonUI() {
//        val constraintSet = ConstraintSet()
//        constraintSet.clone(constCameraButtonLayout)
//        constraintSet.connect(R.id.const_camera_button_layout, ConstraintSet.TOP, R.id.preview_camera_view, ConstraintSet.BOTTOM, 16)
//        constraintSet.applyTo(constCameraButtonLayout)

        // Listener for button used to capture photo
        ivPerformButton.setOnClickListener {
            if(intent.type == TYPE_PICTURE) {
                viewModel.uiState.postValue(UiState.FINALIZED)

                takeAPicture()
            }
            else {
                recordVideo()
            }
        }


        // Record
        ivStop.setOnClickListener {
            Log.d(TAG, "ivStop: clicked, ${viewModel.uiState.value}")
            if(viewModel.uiState.value == UiState.RECORDING || viewModel.uiState.value == UiState.PAUSE) {
                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
            }
        }

        // Record
        ivPause.setOnClickListener {
            if(viewModel.uiState.value == UiState.RECORDING) {
                currentRecording?.pause()
            }
        }

        ivFlash.setOnClickListener {
            Log.d(TAG, "ivFlash: lensSpacing=${viewModel.lensSpacing}")
            if(viewModel.lensSpacing == CameraSelector.LENS_FACING_BACK) {
                viewModel.flashOn = !viewModel.flashOn
                if(viewModel.flashOn) {
                    ivFlash.setImageDrawable(ContextCompat.getDrawable(this@CameraXActivity, R.drawable.ic_fill_flash_24))
                }
                else{
                    ivFlash.setImageDrawable(ContextCompat.getDrawable(this@CameraXActivity, R.drawable.ic_fill_flash_off_24))
                }
                camera?.cameraControl?.enableTorch(viewModel.flashOn)
            }
        }

        ivSwitchCamera.setOnClickListener {
            viewModel.switchCamera()
            ivFlash.isVisible = viewModel.lensSpacing == CameraSelector.LENS_FACING_BACK

            lifecycleScope.launch {
                if(viewModel.isCaptureMode()) {
                    bindCaptureUseCases()
                }
                else{
                    bindRecordUseCase()
                }
            }

        }

        ivBack.setOnClickListener {
            finish()
        }


        viewModel.uiState.distinctUntilChanged().observe(this) {
            showUI(it)
        }
    }

    private fun takeAPicture(){
        // Get a stable reference of the modifiable image capture use case

        imageCapture?.let { capture ->
            // Create time stamped name and MediaStore entry.
            viewModel.file = FileUtils.createMediaFile(TYPE_PICTURE)
            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, viewModel.prepareMediaStoreValues(this)).build()
            // Setup image capture listener which is triggered after photo has been taken
            capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    viewModel.uiState.postValue(UiState.IDLE)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    viewModel.uiState.postValue(UiState.IDLE)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")
                    viewModel.runMediaFile(this@CameraXActivity, viewModel.file)
                }
            })
            // TODO Display flash animation to indicate that photo was captured
            previewView.post(animationTask)
        }
    }

    private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.removeObservers(this)
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(this) { cameraState ->
            run {
                Log.d(TAG, "observeCameraState: cameraState type=${cameraState.type}")
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // Ask the user to close other camera apps
//                        Toast.makeText(this@CameraXActivity, "CameraState: Pending Open", Toast.LENGTH_SHORT).show()
                        viewModel.uiState.postValue(UiState.PREPARING)
                    }
                    CameraState.Type.OPENING -> {
//                        Toast.makeText(this@CameraXActivity, "CameraState: Opening", Toast.LENGTH_SHORT).show()
                        viewModel.uiState.postValue(UiState.PREPARING)
                    }
                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
//                        Toast.makeText(this@CameraXActivity, "CameraState: Open", Toast.LENGTH_SHORT).show()
                        viewModel.uiState.postValue(UiState.IDLE)
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
//                        Toast.makeText(this@CameraXActivity, "CameraState: Closing", Toast.LENGTH_SHORT).show()
                        viewModel.uiState.postValue(UiState.PREPARING)
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
//                        Toast.makeText(this@CameraXActivity, "CameraState: Closed", Toast.LENGTH_SHORT).show()
                        viewModel.uiState.postValue(UiState.PREPARING)
                    }
                }
            }

            cameraState.error?.let { error ->
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(this@CameraXActivity, "Stream config error", Toast.LENGTH_SHORT).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(this@CameraXActivity, "Camera in use", Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                        Toast.makeText(this@CameraXActivity, "Max cameras in use", Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(this@CameraXActivity, "Other recoverable error", Toast.LENGTH_SHORT).show()
                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(this@CameraXActivity, "Camera disabled", Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(this@CameraXActivity, "Fatal error", Toast.LENGTH_SHORT).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(this@CameraXActivity, "Do not disturb mode enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    private fun bindRecordUseCase(){
        Log.d(TAG, "bindCaptureVideoUseCase: start")
        // CameraProvider
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(viewModel.lensSpacing).build()

        val quality = Quality.LOWEST // viewModel.getQuality()
        val qualitySelector = QualitySelector.from(quality)
        val dimensionRatio2 = quality.getAspectRatioString(quality, true)
        previewView.updateLayoutParams<ConstraintLayout.LayoutParams> { dimensionRatio = dimensionRatio2 }
        val aspectRatio = quality.getAspectRatio(quality)
        val preview = Preview.Builder().setTargetAspectRatio(aspectRatio).build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this@CameraXActivity, cameraSelector, videoCapture, preview)
            camera?.cameraControl?.enableTorch(viewModel.flashOn)
            observeCameraState(camera?.cameraInfo!!)
            Log.d(TAG, "bindCaptureUsecase: end")
        } catch (exc: Exception) {
            // we are on main thread, let's reset the controls on the UI.
            Log.e(TAG, "Use case binding failed", exc)
            resetUIandState()
        }
    }

    private fun recordVideo(){
        Log.d(TAG, "ivRecording: clicked, ${viewModel.uiState.value}")
        if(viewModel.uiState.value == UiState.IDLE) {
            startRecording()
        }
        else if(viewModel.uiState.value == UiState.PAUSE) {
            currentRecording?.resume()
        }
    }

    private fun startRecording() {
        viewModel.file = FileUtils.createMediaFile(TYPE_VIDEO)
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(viewModel.prepareMediaStoreValues(this)).build()
        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output.prepareRecording(this, mediaStoreOutput).apply { withAudioEnabled() }.start(mainThreadExecutor, captureListener)

        Log.i(TAG, "Recording started")
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status) {
            recordingState = event
            viewModel.uiState.postValue(event.getUiState())
        }
        val stats = event.recordingStats
        val size = stats.numBytesRecorded
        val time = stats.recordedDurationNanos / 1_000_000
        val formatSize = CameraFormatter.formatFileSize(size, 1000, "int")
        tvFileSize.text = formatSize
        val fSize = formatSize.split(" ")[0].toInt()
        pbFileSize.progress = size.toInt()
        tvTimer.text = CameraFormatter.formatTimer(time, false)
        if(size >= VIDEO_MAX_SIZE - CameraConfig.VIDEO_SIZE_COMPLEMENT) {
            if(viewModel.uiState.value == UiState.RECORDING || viewModel.uiState.value == UiState.PAUSE) {
                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
            }
        }
        if (event is VideoRecordEvent.Finalize) {
            // display the captured video
            viewModel.runMediaFile(this@CameraXActivity, viewModel.file)

//            CoroutineScope(Dispatchers.IO).launch {
//                val newFile = FileUtils.createMediaFile(TYPE_VIDEO)
//                val qualityConvert = QualityConvert(viewModel.file!!.absolutePath, newFile.absolutePath)
//                qualityConvert.convert { executionId, returnCode ->
//                    when (returnCode) {
//                        Config.RETURN_CODE_SUCCESS -> {
//                            // 작업 성공
//                            println("FFmpeg process success")
//                            viewModel.runMediaFile(this@CameraXActivity, newFile)
//                            viewModel.uiState.postValue(UiState.FINALIZED)
//                            viewModel.file!!.delete()
//                        }
//                        Config.RETURN_CODE_CANCEL -> {
//                            // 작업 취소
//                            newFile.delete()
//                            println("FFmpeg process cancelled")
//                        }
//                        else -> {
//                            // 작업 실패
//                            newFile.delete()
//                            println("FFmpeg process failed")
//                        }
//                    }
//                }
//            }
        }
    }

    private fun observeUiState(){
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
                ivBack.visibility = View.VISIBLE
                lineTitle.visibility = View.INVISIBLE
                lineFileSizeLayout.visibility = View.INVISIBLE
                ivFlash.isVisible = viewModel.lensSpacing == CameraSelector.LENS_FACING_BACK
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
            }
            UiState.SWITCH -> {
                ivPerformButton.isEnabled = false
                ivSwitchCamera.isEnabled = false
            }
            else -> {
                Log.w(TAG, "showUI: $state is not matched")
            }
        }

        if(intent.type == TYPE_PICTURE){
            Log.d(TAG, "showUI: TYPE_PICTURE")
            lineTitle.visibility = View.INVISIBLE
            lineFileSizeLayout.visibility = View.INVISIBLE
            ivStop.visibility = View.INVISIBLE
        }
    }

    /**
     * ResetUI (restart):
     *    in case binding failed, let's give it another change for re-try. In future cases
     *    we might fail and user get notified on the status
     */
    private fun resetUIandState() {
        viewModel.uiState.postValue(UiState.IDLE)
    }


    override fun onDestroy() {
        super.onDestroy()
        if(intent.type == TYPE_PICTURE) {
            // Shut down our background executor
            cameraExecutor.shutdown()
            // Unregister the broadcast receivers and listeners
            broadcastManager.unregisterReceiver(volumeDownReceiver)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                init()

            } else {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    override fun onZoomWithScale(state: String, scaleFactor: Double) {
        Log.d(TAG, "onZoomWithScale: state=$state, newScale=${viewModel.newScale}")
        if(state == "up") {
            viewModel.newScale += scaleFactor
            if(viewModel.newScale > 1) viewModel.newScale = 1.0
        }
        else {
            viewModel.newScale -= scaleFactor
            if(viewModel.newScale < 0) viewModel.newScale = 0.0
        }
        Log.d(TAG, "onZoomWithScale: getScaleValue=${viewModel.getScaleValue()}")
        val cameraControl = camera?.cameraControl ?: return
        tvScale.animate().cancel()
        tvScale.alpha = 1f // 투명도를 다시 1로 설정하여 보이게 합니다.
        tvScale.visibility = View.VISIBLE // 가시성을 VISIBLE로 설정합니다

        tvScale.text = String.format("%.1fx", viewModel.getScaleValue())
        tvScale.animate().alpha(0f).setStartDelay(500).setDuration(500).withEndAction { tvScale.visibility = View.GONE }.start()
        cameraControl.setLinearZoom(viewModel.newScale.toFloat())
    }
}