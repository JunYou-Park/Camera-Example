package com.capturecode.camera.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import androidx.camera.core.AspectRatio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object CameraConfig {
    const val VIDEO_MAX_SIZE = 950_000
    const val VIDEO_SIZE_COMPLEMENT = 5_000
    const val VIDEO_BITRATE: Int = 500_000
    const val VIDEO_FRAME_RATE: Int = 30

    const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

    const val DEFAULT_PREVIEW_WIDTH_SIZE = 360  // 120, 240, 360, 480
    const val DEFAULT_PREVIEW_HEIGHT_SIZE = 480 // 160, 320, 480, 640

    // qqVGA(quarter quarter VGA) 160×120 4:3
    const val DEFAULT_RECORDING_WIDTH_SIZE = 120 // 120, 360, 480
    const val DEFAULT_RECORDING_HEIGHT_SIZE = 160 // 160, 480, 640

    const val ANIMATION_SLOW_MILLIS = 100L
    const val VIDEO_ENCODER_MIMETYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    const val VIDEO_OUTPUT_MUXER = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4

    /** Generates a fullscreen quad to cover the entire viewport. Applies the transform set on the
    camera surface to adjust for orientation and scaling when used for copying from the camera
    surface to the render surface. We will pass an identity matrix when copying from the render
    surface to the recording / preview surfaces. */
    const val TRANSFORM_VSHADER = """
        attribute vec4 vPosition;
        uniform mat4 texMatrix;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = vPosition;
            vec4 texCoord = vec4((vPosition.xy + vec2(1.0, 1.0)) / 2.0, 0.0, 1.0);
            vTextureCoord = (texMatrix * texCoord).xy;
        }
    """


    /** Passthrough fragment shader, simply copies from the source texture */
    val PASSTHROUGH_FSHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    val MIRRO_PASSTHROUGH_FSHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
       
        void main() { 
           vec2 flippedTexCoords = vec2(1.0 - vTextureCoord.x, vTextureCoord.y);
           gl_FragColor = texture2D(sTexture, flippedTexCoords);
        }
    """

    val PORTRAIT_FSHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            float x = vTextureCoord.x * 2.0 - 1.0, y = vTextureCoord.y * 2.0 - 1.0;
            vec4 color = texture2D(sTexture, vTextureCoord);
            float r = sqrt(x * x + y * y);
            gl_FragColor = color * (1.0 - r);
        }
    """


    val FULLSCREEN_QUAD = floatArrayOf(
        -1.0f, -1.0f,  // 0 bottom left
        1.0f, -1.0f,  // 1 bottom right
        -1.0f,  1.0f,  // 2 top left
        1.0f,  1.0f,  // 3 top right
    )



    private const val PHOTO_TYPE = "image/jpeg"
    private const val RATIO_4_3_VALUE = 4.0 / 3.0
    private const val RATIO_16_9_VALUE = 16.0 / 9.0

    fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

}