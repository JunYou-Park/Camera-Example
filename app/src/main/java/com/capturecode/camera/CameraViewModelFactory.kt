package com.capturecode.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.capturecode.camera.ui.CameraViewModel

class CameraViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if(modelClass.isAssignableFrom(CameraViewModel::class.java)){
            return CameraViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}