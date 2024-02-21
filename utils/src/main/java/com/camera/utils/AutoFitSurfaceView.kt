/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.camera.utils

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        val newLayoutParams = layoutParams
        newLayoutParams.width = width
        newLayoutParams.height = height
        layoutParams = newLayoutParams
        holder.setFixedSize(width, height)
        requestLayout()
    }


    companion object {
        private val TAG = AutoFitSurfaceView::class.java.simpleName
    }
}
