package com.carriez.flutter_hbb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import il.co.tmg.screentool.ICaptureService
import il.co.tmg.screentool.IFrameCallback
import java.nio.ByteBuffer

const val KNOX_PACKAGE = "il.co.tmg.screentool"
const val KNOX_SERVICE = "il.co.tmg.screentool.service.CaptureService"
private const val LOG_TAG_KNOX = "LOG_SERVICE"

interface KnoxCaptureCallbacks {
    fun onFrame(buffer: ByteBuffer)
    fun isCapturing(): Boolean
    fun onScreenInfo(width: Int, height: Int)
}

fun isKnoxAvailable(context: Context): Boolean {
    val intent = Intent().apply {
        setClassName(KNOX_PACKAGE, KNOX_SERVICE)
    }
    val resolveInfo = context.packageManager.resolveService(intent, 0)
    return resolveInfo != null
}

class KnoxCapturer(
    private val context: Context,
    private val serviceHandler: Handler,
    private val callbacks: KnoxCaptureCallbacks
) {
    private var captureService: ICaptureService? = null
    private var isServiceBound = false
    private val bindLock = Object()
    private val mappingLock = Object()
    private var knoxMappedBuffer: ByteBuffer? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(LOG_TAG_KNOX, "Knox CaptureService connected")
            captureService = ICaptureService.Stub.asInterface(service)
            synchronized(bindLock) {
                isServiceBound = true
                bindLock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "Knox CaptureService disconnected")
            captureService = null
            isServiceBound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "Knox CaptureService binding died, attempting to rebind")
            bind()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "Knox CaptureService null binding, attempting to rebind")
            bind()
        }
    }

    private val knoxFrameCallback = object : IFrameCallback.Stub() {
        override fun onFrameAvailable(memory: SharedMemory) {
            if (!callbacks.isCapturing()) {
                Log.d(LOG_TAG_KNOX, "Frame available but not capturing, ignoring")
                return
            }

            try {
                val buf = synchronized(mappingLock) {
                    if (knoxMappedBuffer == null) {
                        knoxMappedBuffer = memory.mapReadOnly()
                    }
                    knoxMappedBuffer
                }
                if (buf == null) return@onFrameAvailable
                buf.rewind()
                callbacks.onFrame(buf)
            } catch (e: Exception) {
                Log.e(LOG_TAG_KNOX, "Error processing Knox frame", e)
            }
        }

        override fun onCaptureError(error: String) {
            Log.e(LOG_TAG_KNOX, "Knox capture error: $error")
        }
    }

    fun bind(): Boolean {
        val intent = Intent().apply {
            setClassName(KNOX_PACKAGE, KNOX_SERVICE)
        }

        val bindResult = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bindResult) {
            Log.e(LOG_TAG_KNOX, "Failed to bind to Knox CaptureService")
            return false
        }

        synchronized(bindLock) {
            while (!isServiceBound) {
                try {
                    bindLock.wait(600)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (!isServiceBound || captureService == null) {
            Log.e(LOG_TAG_KNOX, "Knox service binding timeout")
            unbind()
            return false
        }

        return true
    }

    fun isBound(): Boolean = isServiceBound && captureService != null

    fun initCapture(): Boolean {
        val service = captureService ?: return false

        try {
            service.initCapture()
            service.registerFrameCallback(knoxFrameCallback)

            val knoxWidth = service.getScreenWidth()
            val knoxHeight = service.getScreenHeight()
            if (knoxWidth <= 0 || knoxHeight <= 0) {
                Log.e(LOG_TAG_KNOX, "Invalid Knox screen dimensions: ${knoxWidth}x${knoxHeight}")
                return false
            }
            callbacks.onScreenInfo(knoxWidth, knoxHeight)
            return true
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to initialize Knox capture", e)
            return false
        }
    }

    fun releaseCapture() {
        synchronized(mappingLock) {
            val buf = knoxMappedBuffer
            if (buf != null) {
                try {
                    SharedMemory.unmap(buf)
                } catch (e: Exception) {
                    Log.e(LOG_TAG_KNOX, "Error unmapping Knox buffer", e)
                }
                knoxMappedBuffer = null
            }
        }
        try {
            captureService?.unregisterFrameCallback()
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Error unregistering Knox callback", e)
        }
    }

    fun injectPointer(kind: Int, mask: Int, x: Int, y: Int, wakeUp: Boolean): Boolean {
        return try {
            captureService?.injectPointer(kind, mask, x, y, wakeUp)
            true
        } catch (e: Exception) {
            Log.d(LOG_TAG_KNOX, "Knox injectPointer failed: ${e.message}")
            false
        }
    }

    fun injectKeyEvent(keyCode: Int, modifiers: Int, sendDown: Boolean, sendUp: Boolean): Boolean {
        return try {
            Log.d(LOG_TAG_KNOX, "Knox injectKeyEvent: keyCode=$keyCode, modifiers=$modifiers, sendDown=$sendDown, sendUp=$sendUp")
            captureService?.injectKeyEvent(keyCode, modifiers, sendDown, sendUp)
            true
        } catch (e: Exception) {
            Log.d(LOG_TAG_KNOX, "Knox injectKeyEvent failed: ${e.message}")
            false
        }
    }

    fun unbind() {
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(LOG_TAG_KNOX, "Error unbinding Knox service", e)
            }
            isServiceBound = false
            captureService = null
        }
    }
}

