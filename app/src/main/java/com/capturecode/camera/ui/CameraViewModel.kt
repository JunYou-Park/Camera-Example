package com.capturecode.camera.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment.DIRECTORY_MOVIES
import android.os.Environment.DIRECTORY_PICTURES
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.FileProvider
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.camera.utils.FileUtils.PHOTO_TYPE
import com.camera.utils.FileUtils.TYPE_PICTURE
import com.camera.utils.FileUtils.VIDEO_TYPE
import com.capturecode.camera.BuildConfig
import com.capturecode.camera.R
import java.io.File

class CameraViewModel : ViewModel() {
    companion object{
        private const val TAG = "CameraViewModel"
    }

    var file: File? = null
    var type: String = TYPE_PICTURE

    var flashOn: Boolean = false
    val uiState = MutableLiveData(UiState.PREPARING)
    var lensSpacing = CameraSelector.LENS_FACING_BACK

    var recordingStartMillis: Long = 0L
    var runningTimeMillis = 0L

    fun updateType(intent: Intent) {
        type = intent.type ?: TYPE_PICTURE
    }

    var newScale = 0.0
    fun getScaleValue():Double{
        val targetRangeMin = 1.0
        val targetRangeMax = 8.0
        val originalRangeMin = 0.0
        val originalRangeMax = 1.0

        return (targetRangeMax - targetRangeMin) * (newScale - originalRangeMin) / (originalRangeMax - originalRangeMin) + targetRangeMin
    }

    fun getCameraId(): String {
        return if(lensSpacing==CameraSelector.LENS_FACING_BACK) "0"
        else "1"
    }

    fun isCaptureMode() = type == TYPE_PICTURE

    fun switchCamera(){
        lensSpacing = if(lensSpacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        uiState.postValue(UiState.SWITCH)
    }
    /** Returns true if the device has an available back camera. False otherwise */
    fun hasBackCamera(cameraProvider: ProcessCameraProvider?): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    fun hasFrontCamera(cameraProvider: ProcessCameraProvider?): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    fun prepareMediaStoreValues(context: Context): ContentValues{
        val appName = context.resources.getString(R.string.app_name)
        val contentValues = ContentValues()
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, file!!.name)
        if(isCaptureMode()) {
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
//            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "$DIRECTORY_PICTURES/$appName")
        }
        else {
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, VIDEO_TYPE)
//            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "$DIRECTORY_MOVIES/$appName")
        }
        return contentValues
    }

    fun runActionView(activity: Activity, uri: Uri?) {
        val savedUri = uri ?: return // savedUri가 null이면 리턴
        // 저장된 이미지 URI를 갤러리 앱에서 열기 위한 Intent 생성
        Intent(Intent.ACTION_VIEW, savedUri).also { intent ->
            // 사진 파일에 대한 MIME 타입 설정
            // 이는 Intent가 올바른 애플리케이션을 찾는 데 도움이 됩니다.
            intent.type = "image/*"
            // URI 접근 권한 부여
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            // Intent를 처리할 수 있는 앱이 시스템에 설치되어 있는지 확인
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
            } else {
                Log.d(TAG, "No Activity found to handle the intent")
            }
        }
    }

    fun runMediaFile(activity: Activity, outputFile: File?){
        Log.d(TAG, "runMediaFile: outputFile=$outputFile")
        outputFile?.let { file -> activity.startActivity(Intent().apply {
            action = Intent.ACTION_VIEW
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
            val authority = "${BuildConfig.APPLICATION_ID}.provider"
            data = FileProvider.getUriForFile(activity, authority, file)
            Log.d(TAG, "runMediaFile: data=$data")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        }
    }
}