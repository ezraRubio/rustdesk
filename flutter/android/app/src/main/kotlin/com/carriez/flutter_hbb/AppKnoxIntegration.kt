package com.carriez.flutter_hbb

import android.os.Handler
import android.util.Log
import ffi.FFI
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent as ProtoKeyEvent
import java.nio.ByteBuffer

object AppKnoxIntegration {

    fun createKnoxCapturer(service: MainService, handler: Handler): KnoxCapturer {
        return KnoxCapturer(service, handler, object : KnoxCaptureCallbacks {
            override fun onFrame(buffer: ByteBuffer) {
                FFI.onVideoFrameUpdate(buffer)
            }

            override fun isCapturing(): Boolean = MainService.isStart

            override fun onScreenInfo(width: Int, height: Int) {
                updateScreenInfoForKnox(width, height)
            }
        })
    }

    fun handleKnoxKeyEvent(
        input: ByteArray,
        knoxCapturer: KnoxCapturer?,
        logTag: String
    ) {
        try {
            val proto = ProtoKeyEvent.parseFrom(input)
            val androidEv = KeyEventConverter.toAndroidKeyEvent(proto)
            val keyCode = androidEv.keyCode
            val modifiers = androidEv.metaState
            val sendDown = proto.down || proto.press
            val sendUp = !proto.down || proto.press
            knoxCapturer?.injectKeyEvent(keyCode, modifiers, sendDown, sendUp)
        } catch (e: Exception) {
            Log.d(logTag, "Knox injectKeyEventParsed failed: ${e.message}")
        }
    }

    private fun updateScreenInfoForKnox(width: Int, height: Int) {
        SCREEN_INFO.width = width
        SCREEN_INFO.height = height
        if (SCREEN_INFO.dpi == 0) {
            SCREEN_INFO.dpi = 240
        }
    }
}

