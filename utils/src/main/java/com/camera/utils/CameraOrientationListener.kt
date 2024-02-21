package com.camera.utils

import android.content.Context
import android.hardware.SensorManager
import android.util.Log
import android.view.OrientationEventListener


class CameraOrientationListener (context: Context): OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
    companion object{
        private const val TAG = "CameraOrientationListener"
    }
    override fun onOrientationChanged(orientation: Int) {
        Log.d(TAG, "onOrientationChanged: orientation=$orientation")
        if (orientation == ORIENTATION_UNKNOWN) {
            return
        }

        // 디바이스 방향이 특정 각도 범위 내에 있는지 확인
        when (orientation) {
            in 45..134 -> {
                Log.d(TAG, "Landscape: Right side up")
            }
            in 135..224 -> {
                Log.d(TAG, "Upside down")
            }
            in 225..314 -> {
                Log.d(TAG, "Landscape: Left side up")
            }
            else -> {
                Log.d(TAG, "Portrait")
            }
        }
    }
}