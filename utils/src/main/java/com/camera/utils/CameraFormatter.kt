package com.camera.utils

import kotlin.math.floor

object CameraFormatter {
    const val SEC_HOUR = 60 * 60 * 1000 // 3600000
    const val SEC_MINUTE = 60 * 1000 // 60000
    const val SEC_SECOND = 100 * 10 // 1000

    fun formatTimer(time: Long, containMillis: Boolean): String {
        val hour: Int = (time / SEC_HOUR).toInt()
        val minute: Int = ((time - hour * SEC_HOUR) / SEC_MINUTE).toInt()
        val second: Int = (time - hour * SEC_HOUR - minute * SEC_MINUTE).toInt() / SEC_SECOND
        val millisecond: Int = (time - hour * SEC_HOUR - minute * SEC_MINUTE - second * SEC_SECOND).toInt() /*/MILLISECOND*/
        val timerString = if(containMillis && minute <= 0) {
            formatZero(minute) + ":" + formatZero(second) + formatMilliZero(millisecond)
        }
        else{
            formatZero(minute) + ":" + formatZero(second)
        }
        return timerString
    }

    private fun formatZero(t: Int): String {
        var result = ""
        if (t < 10) result += "0$t" else result += t
        return result
    }

    private fun formatMilliZero(t: Int): String {
        var result = ""
        if (t < 10) result += "00$t" else if (t < 100) result += "0$t" else result += t
        return result
    }

    fun formatFileSize(value: Long, kilo: Long, type: String): String {
        val units = arrayOf("TB", "GB", "MB", "KB", "B")
        return format(value, kilo, units, type)
    }

    fun formatBitRate(value: Long, kilo: Long, type: String): String {
        val units = arrayOf("Tbps", "Gbps", "Mbps", "kbps", "bps")
        return format(value, kilo, units, type)
    }

    fun formatSampleRate(value: Long, kilo: Long, type: String): String {
        val units = arrayOf("THz", "GHz", "MHz", "kHz", "Hz")
        return format(value, kilo, units, type)
    }

    private fun format(value: Long, kilo: Long, units: Array<String>, type: String): String {
        if (value < 1) return value.toString()
        val mega = kilo * kilo
        val giga = mega * kilo
        val tera = giga * kilo
        val byteUnits = longArrayOf(tera, giga, mega, kilo, 1)
        var result = "0"
        for (i in byteUnits.indices) {
            val byteUnit = byteUnits[i]
            if (value >= byteUnit) {
                val unit = units[i]
                var resultDoubleValue = if (byteUnit > 1) value.toDouble() / byteUnit.toDouble() else value.toDouble()
                resultDoubleValue = floor(resultDoubleValue * 10) / 10
                result = if (resultDoubleValue == resultDoubleValue.toLong().toDouble()) {
                    if(type == "int") {
                        String.format("%d %s", resultDoubleValue.toInt(), unit)
                    }
                    else {
                        String.format("%.0f %s", resultDoubleValue, unit)
                    }
                }
                else {
                    if(type == "int") {
                        String.format("%d %s", resultDoubleValue.toInt(), unit)
                    }
                    else {
                        String.format("%.1f %s", resultDoubleValue, unit)
                    }
                }
                break
            }
        }
        return result
    }
}