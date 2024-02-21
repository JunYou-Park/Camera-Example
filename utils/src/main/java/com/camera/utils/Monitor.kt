package com.camera.utils

import android.os.Handler
import android.os.Message
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Monitor<T>(private val obj : T, private val handler: Handler) {
    companion object{
        private const val TAG = "Monitor"
        const val MSG_UPDATE_SIZE = 1000
        const val MSG_UPDATE_TIME = 1001
    }

    private var executor: ScheduledExecutorService? = null
    private var future: ScheduledFuture<*>? = null

    private var prev = 0L

    init {
        if(obj is File) {
            prev = obj.length()
        }
        else if(obj is Long){
            prev = obj
        }
    }

    fun observe(){
        if (executor == null || executor!!.isShutdown) {
            executor = Executors.newSingleThreadScheduledExecutor()
        }
        future = when (obj) {
            is File -> {
                executor?.scheduleAtFixedRate({
                    val currentSize = obj.length()
                    if (currentSize != prev) {
                        prev = currentSize
                        handler.sendMessage(Message.obtain(null, MSG_UPDATE_SIZE, prev))
                    }
                }, 0, 500, TimeUnit.MICROSECONDS) // 1초마다 파일 크기 확인
            }
            is Long -> {
                executor?.scheduleAtFixedRate({
                    val currentTime = System.currentTimeMillis()
                    if (currentTime != prev) {
                        val increase = currentTime - prev
                        prev = currentTime
                        handler.sendMessage(Message.obtain(null, MSG_UPDATE_TIME, increase))
                    }
                }, 0, 500, TimeUnit.MICROSECONDS) // 0.5초마다 파일 크기 확인
            }
            else -> throw Exception("")
        }
    }

    fun pause(){
        future?.cancel(false) // 현재 진행 중인 작업은 완료되도록 하고, 추가 실행은 중지
    }

    // 모니터링 완전히 중지 및 리소스 해제
    fun stop(){
        executor?.shutdownNow()
        executor = null
        if(obj is File) {
            handler.removeMessages(MSG_UPDATE_SIZE)
        }
        else if(obj is Long){
            handler.removeMessages(MSG_UPDATE_TIME)
        }
    }

}