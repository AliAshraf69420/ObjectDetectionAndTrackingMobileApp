package com.aliashraf69420.rcbowlingtracker.processing

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class ProcessingModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ProcessingModule"

    @ReactMethod
    fun isNativeAvailable(promise: Promise) {
        promise.resolve(true)
    }

    @ReactMethod
    fun processVideo(inputVideoUri: String, enableCarPath: Boolean, promise: Promise) {
        Thread {
            try {
                val result = VideoProcessor.process(
                    context        = reactContext,
                    inputUriString = inputVideoUri,
                    onProgress     = { stage, percent, message -> emitProgress(stage, percent, message) }
                )

                val pinEventsArray: WritableArray = Arguments.createArray()
                for (event in result.pinEvents) {
                    val m: WritableMap = Arguments.createMap()
                    m.putInt("pinTrackId", (event["pinTrackId"] as Int))
                    m.putInt("order",      (event["order"]      as Int))
                    m.putInt("timeMs",     (event["timeMs"]     as Int))
                    pinEventsArray.pushMap(m)
                }

                val carPathArray: WritableArray = Arguments.createArray()
                if (enableCarPath) {
                    for (pt in result.carPath) {
                        val m: WritableMap = Arguments.createMap()
                        m.putInt("timeMs", (pt["timeMs"] as Int))
                        m.putInt("x",      (pt["x"]      as Int))
                        m.putInt("y",      (pt["y"]      as Int))
                        carPathArray.pushMap(m)
                    }
                }

                val map: WritableMap = Arguments.createMap().apply {
                    putString("outputVideoUri",  result.outputVideoUri)
                    putDouble("elapsedMs",        result.elapsedMs.toDouble())
                    putInt(   "pinsKnockedDown",  result.pinsKnockedDown)
                    putArray( "pinEvents",        pinEventsArray)
                    if (enableCarPath) putArray("carPath", carPathArray) else putNull("carPath")
                }

                promise.resolve(map)
            } catch (e: Exception) {
                promise.reject("PROCESSING_ERROR", e.message ?: "Unknown processing error", e)
            }
        }.start()
    }

    private fun emitProgress(stage: String, percent: Int, message: String) {
        if (!reactContext.hasActiveReactInstance()) return
        try {
            val params: WritableMap = Arguments.createMap().apply {
                putString("stage",   stage)
                putInt(   "percent", percent)
                putString("message", message)
            }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit("ProcessingProgress", params)
        } catch (_: Exception) {
            // Bridge may be tearing down; swallow so processing still completes.
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
