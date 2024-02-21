package com.capturecode.camera.core

import com.arthenica.mobileffmpeg.ExecuteCallback
import com.arthenica.mobileffmpeg.FFmpeg

class QualityConvert(private val inputVideoPath: String, private val outputVideoPath: String) {

    fun convert(callback: ExecuteCallback) {
        val command = "-i $inputVideoPath -s 480x640 -b:v 150k -r 30 $outputVideoPath"
        FFmpeg.executeAsync(command, callback)
    }
}

