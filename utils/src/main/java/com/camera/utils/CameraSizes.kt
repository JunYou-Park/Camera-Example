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

import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import android.view.Display
import java.util.*
import kotlin.math.max
import kotlin.math.min

/** Helper class used to pre-compute shortest and longest sides of a [Size] */
class SmartSize(width: Int, height: Int) {
    var size = Size(width, height)
    var long = max(size.width, size.height)
    var short = min(size.width, size.height)
    override fun toString() = "SmartSize(${long}x${short})"
}

/** Standard High Definition size for pictures and video */
val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

/** Returns a [SmartSize] object for the given [Display] */
fun getDisplaySmartSize(display: Display): SmartSize {
    val outPoint = Point()
    display.getRealSize(outPoint)
    return SmartSize(outPoint.x, outPoint.y)
}

/**
 * Returns the largest available PREVIEW size. For more information, see:
 * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
 * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
 */
fun <T>getPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
): Size {

    // Find which is smaller: screen or 1080p
    val screenSize = getDisplaySmartSize(display)
    val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
    val maxSize = if (hdScreen) SIZE_1080P else screenSize

    // If image format is provided, use it to determine supported sizes; else use target class
    val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    if (format == null)
        assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
    else
        assert(config.isOutputSupportedFor(format))
    val allSizes = if (format == null)
        config.getOutputSizes(targetClass) else config.getOutputSizes(format)

    // Get available sizes and sort them by area from largest to smallest
    val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }.reversed()

    // Then, get the largest output size that is smaller or equal than our max size
    return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
}

fun getSurfaceViewRatioSize(
    display: Display,
    aspectRatio: String): Size {
    val aspectRatioParts = aspectRatio.split(":")

    val outPoint = Point()
    display.getRealSize(outPoint)
    val width = outPoint.x
    val height = outPoint.y
    val adjustHeight = width * aspectRatioParts[1].toInt() / aspectRatioParts[0].toInt()
    Log.d("jypark", "getSurfaceViewRatioSize: width=$width, height=$height, adjustHeight=$adjustHeight")

    return Size(width, adjustHeight)
}

fun getSurfaceViewSDSize(
    display: Display
): Size {
    val outPoint = Point()
    display.getRealSize(outPoint)
    val width = outPoint.x
    val height = outPoint.y
    val size = width.coerceAtMost(height)
    return Size(size, size)
}

/**
 * 카메라가 지원하는 출력 사이즈 목록, 디스플레이 크기, 원하는 비율을 기반으로 최적의 사이즈를 선택합니다.
 *
 * @param choices 카메라가 지원하는 출력 사이즈 목록
 * @param display 디바이스 화면의 Display 객체
 * @param aspectRatio 원하는 비율, "width:height" 형식의 문자열 (예: "3:4")
 * @return 최적의 사이즈
 */
fun chooseOptimalSize(choices: Array<Size>, display: Display, aspectRatio: String): Size {
    val aspectRatioParts = aspectRatio.split(":")
    val targetRatio = aspectRatioParts[0].toDouble() / aspectRatioParts[1].toDouble()

    val displaySize = Point()
    display.getRealSize(displaySize)
    val displayWidth = displaySize.x
    val displayHeight = displaySize.y
    val displayRatio = displayWidth.toDouble() / displayHeight

    // 가능한 모든 사이즈를 대상 비율에 가깝고, 디스플레이 크기를 초과하지 않는 범위에서 필터링
    val possibleSizes = choices.filter { size ->
        val sizeRatio = size.width.toDouble() / size.height
        Math.abs(sizeRatio - targetRatio) <= 0.05 &&
                size.width <= displayWidth && size.height <= displayHeight
    }

    // 가능한 사이즈 중에서 가장 큰 사이즈 선택
    return Collections.max(possibleSizes, CompareSizesByArea())
}

/**
 * Compares two `Size` based on their areas.
 */
class CompareSizesByArea : Comparator<Size> {
    override fun compare(lhs: Size, rhs: Size): Int {
        return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }
}
