package com.capturecode.camera.ui

import android.content.Context
import android.gesture.GestureOverlayView
import android.icu.number.Scale
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs

class ZoomGestureOverlayView : GestureOverlayView {
    companion object{
        private const val TAG = "ZoomGestureOverlayView"
        private const val DEFAULT_GESTURE_MODE = true // TODO false
        private const val THRESHOLD = 30.0
        private const val SCALE_FACTOR = 0.05
    }

    // Members
    private var mOldDistance = 0.0 // Stash spot for the 'original distance' or 'last distance'
    private val mZoomListenerMap: HashMap<ZoomListener, ZoomListener?> = HashMap()
    private var mCanZoom = false

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr){}

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Check pointer count, if we don't have 2 we don't need to worry
                val pointerCount = event.pointerCount
                if (pointerCount == 2) {
                    // Flag as able to zoom
                    mCanZoom = true
                    // Calculate the original distance between fingers when touched
                    mOldDistance = calculateSpacing(event)
                } else {
                    // Reset can zoom on any other finger count
                    mCanZoom = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mCanZoom) {
                    return super.dispatchTouchEvent(event)
                }
                // Fetch new distance
                val newDistance: Double = calculateSpacing(event)
                // Calculate the difference, if negative we should probably zoom out
                val distanceDifference: Double = newDistance - mOldDistance
                // If the absolute value is greater, we want to handle it regardless of whether
                // it is positive or negative
                if (newDistance > 0 && abs(distanceDifference) > THRESHOLD) {
//                    logV(TAG,"Absolute threshold satisfied!")
                    // Update old distance
                    mOldDistance = newDistance
                    // Replace old distance with new so we can keep on zoom zoom zoomin'
                    if (distanceDifference > 0) {
                        fireListenersWithScale("up")
                    } else {
                        fireListenersWithScale("down")
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mCanZoom = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun calculateSpacing(event: MotionEvent): Double {
        return try {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            Math.sqrt((x * x + y * y).toDouble())
        } catch (e: Exception) {
            0.0
        }
    }

    interface ZoomListener {
        fun onZoomWithScale(state: String, scaleFactor: Double)
    }

    @Throws(IllegalArgumentException::class)
    fun addZoomListener(listener: ZoomListener) {
        mZoomListenerMap[listener] = null
    }

    @Throws(IllegalArgumentException::class)
    fun removeZoomListener(listener: ZoomListener) {
        if (mZoomListenerMap.containsKey(listener)) {
            mZoomListenerMap.remove(listener)
        }
    }


    /**
     * Fire the photon torpedos!....er I mean listeners :)
     * @param scale [java.lang.Float]
     */
    private fun fireListenersWithScale(state: String) {
        handler.post {
            for (listener in mZoomListenerMap.keys) {
                listener.onZoomWithScale(state, SCALE_FACTOR)
            }
        }
    }
}