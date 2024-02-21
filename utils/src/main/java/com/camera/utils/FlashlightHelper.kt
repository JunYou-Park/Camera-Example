package com.camera.utils

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager

class FlashlightHelper(context: Context) {

    private var cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String? = null

    init {
        try {
            cameraId = cameraManager.cameraIdList[0] // 대부분의 디바이스에서 첫 번째 카메라에 플래시가 있음
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun toggleFlashLight(turnOn: Boolean) {
        try {
            cameraManager.setTorchMode(cameraId!!, turnOn)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
}