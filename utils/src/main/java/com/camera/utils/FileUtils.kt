package com.camera.utils

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {
    const val PHOTO_TYPE = "image/jpeg"
    const val VIDEO_TYPE = "video/mp4"
    const val TYPE_PICTURE = "Picture"
    const val TYPE_VIDEO = "Video"


    fun createTempFile(context: Context, mediaType: Int): File {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED, ignoreCase = true)) {
            throw FileNotFoundException("file is not exist")
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        var mediaFileName = ""
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            mediaFileName = "VID_$timeStamp.mp4"
        else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            mediaFileName = "IMG_$timeStamp.jpg"

        val newFile = File(context.filesDir.absolutePath + File.separator + mediaFileName)
        return newFile
    }

    fun createMediaFile(mediaType: String): File {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED, ignoreCase = true)) {
            throw FileNotFoundException("file is not exist")
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        var mediaFileName = ""
        if (mediaType == TYPE_VIDEO)
            mediaFileName = "VID_$timeStamp.mp4"
        else if (mediaType == TYPE_PICTURE)
            mediaFileName = "IMG_$timeStamp.jpg"

        val parentDir =
            if (mediaType == TYPE_VIDEO) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
            else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath

        val newFile = File(parentDir + File.separator + mediaFileName)
        return newFile
    }
}