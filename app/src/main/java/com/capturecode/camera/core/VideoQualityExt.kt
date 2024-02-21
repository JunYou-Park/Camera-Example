package com.capturecode.camera.core

import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.video.Quality


/**
 * a helper function to retrieve the aspect ratio from a QualitySelector enum.
 */
fun Quality.getAspectRatio(quality: Quality): Int {
    return when {
        arrayOf(Quality.UHD, Quality.FHD, Quality.HD).contains(quality)   -> AspectRatio.RATIO_16_9
        arrayOf(Quality.SD, Quality.LOWEST).contains(quality) -> AspectRatio.RATIO_4_3
        else -> throw UnsupportedOperationException()
    }
}

/**
 * a helper function to retrieve the aspect ratio string from a Quality enum.
 */
fun Quality.getAspectRatioString(quality: Quality, portraitMode:Boolean) :String {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    val lowQualities = arrayOf(Quality.SD, Quality.LOWEST)
    val ratio =
        when {
            hdQualities.contains(quality)  -> Pair(16, 9)
            lowQualities.contains(quality) -> Pair(4, 3)
            else -> throw UnsupportedOperationException()
        }

    return if (portraitMode) "V,${ratio.second}:${ratio.first}"
    else "H,${ratio.first}:${ratio.second}"
}
