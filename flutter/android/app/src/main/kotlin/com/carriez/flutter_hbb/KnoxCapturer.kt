package com.carriez.flutter_hbb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import android.view.KeyEvent as KeyEventAndroid
import ffi.FFI
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent as ProtoKeyEvent
import hbb.MessageOuterClass.KeyboardMode
import il.co.tmg.fort_ct.ICaptureService
import il.co.tmg.fort_ct.IFrameCallback
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.security.SecureRandom

// Fort Control Tower constants
const val KNOX_PACKAGE = "il.co.tmg.fort_ct"
const val KNOX_CONTROL_SERVICE = "il.co.tmg.fort_ct.ipc.CaptureControlService"
const val KNOX_SERVICE = "il.co.tmg.fort_ct.ipc.CaptureService"
private const val LOG_TAG_KNOX = "KnoxCapturer"

// Messenger message codes
private const val MSG_GET_SESSION_INFO = 1
private const val MSG_START_SESSION = 2

// Bundle keys
private const val KEY_REMOTE_SESSION_ID = "remote_session_id"
private const val KEY_REMOTE_ID = "remote_id"
private const val KEY_TOKEN = "token"
private const val KEY_SESSION_INFO = "session_info"

// Token generation
private const val TOKEN_BYTE_LENGTH = 32

/**
 * Represents session payload returned by CaptureControlService in MSG_GET_SESSION_INFO.
 */
data class SessionPayload(
    val remoteSessionId: String,
    val status: String,
    val url: String?,
    val key: String?,
    val isUserConsentRequired: Boolean
)

/**
 * KnoxCapturer owns Fort Control Tower remote-control session IPC.
 *
 * Manages two cross-process connections:
 *   1. **Control** — Messenger IPC to CaptureControlService (session negotiation).
 *      Keeping this bound IS the session — unbinding ends it.
 *   2. **Capture** — AIDL ICaptureService (screen capture, input injection).
 *
 * Lives inside KnoxService (foreground service). No dependency on MainService.
 *
 * Lifecycle:
 *   1. KnoxService creates KnoxCapturer, calls startSession(sessionId)
 *   2. Binds CaptureControlService → MSG_GET_SESSION_INFO → validates
 *   3. Binds CaptureService → initCapture() → MSG_START_SESSION
 *   4. On success → KnoxService.onSessionReady() → frames flow via FFI
 *   5. On end → KnoxService.onSessionEnded() → service stops
 */
class KnoxCapturer(
    private val context: Context,
    private val serviceHandler: Handler,
    private val service: KnoxService
) {
    // ---- Capture service (AIDL) ----
    private var captureService: ICaptureService? = null
    private var isCaptureBound = false
    private var captureBindPending = false
    private val mappingLock = Object()
    private var knoxMappedBuffer: ByteBuffer? = null

    // ---- Control service (Messenger IPC) ----
    private var controlMessenger: Messenger? = null
    private var isControlBound = false
    private var controlBindPending = false

    // ---- Session state ----
    private var remoteSessionId: String? = null
    private var sessionPayload: SessionPayload? = null
    private var generatedRemoteId: String? = null
    private var generatedToken: String? = null
    private var isSessionRunning = false
    private var isCapturePrepared = false
    private var pendingStartAfterPrepare = false
    @Volatile
    private var stopped = false

    // ---- Reply handler (WeakReference to avoid leaking through Handler queue) ----
    private val replyHandler = ReplyHandler(WeakReference(this))
    private val replyMessenger = Messenger(replyHandler)

    // ========================================================================
    // Service connections
    // ========================================================================

    private val controlConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (stopped) return
            Log.d(LOG_TAG_KNOX, "CaptureControlService connected")
            controlMessenger = Messenger(service)
            isControlBound = true
            requestSessionInfo()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "CaptureControlService disconnected")
            controlMessenger = null
            isControlBound = false
            if (!stopped) {
                stopSession("CaptureControlService disconnected (session ended externally)")
            }
        }
    }

    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (stopped) {
                runCatching { context.unbindService(this) }
                return
            }
            Log.d(LOG_TAG_KNOX, "CaptureService connected")
            captureService = ICaptureService.Stub.asInterface(service)
            isCaptureBound = true
            prepareCaptureAfterBind()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "CaptureService disconnected")
            captureService = null
            isCaptureBound = false
            isCapturePrepared = false
            if (!stopped) {
                stopSession("CaptureService disconnected")
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "CaptureService binding died")
            captureService = null
            isCaptureBound = false
            isCapturePrepared = false
            if (!stopped) {
                stopSession("CaptureService binding died")
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(LOG_TAG_KNOX, "CaptureService null binding")
            if (!stopped) {
                stopSession("CaptureService returned null binding")
            }
        }
    }

    // ========================================================================
    // Frame callback (runs on binder thread)
    // ========================================================================

    private val knoxFrameCallback = object : IFrameCallback.Stub() {
        override fun onFrameAvailable(memory: SharedMemory) {
            if (stopped || !isSessionRunning) {
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
                FFI.onVideoFrameUpdate(buf)
            } catch (e: Exception) {
                Log.e(LOG_TAG_KNOX, "Error processing Knox frame", e)
            }
        }

        override fun onCaptureError(error: String) {
            Log.e(LOG_TAG_KNOX, "Knox capture error: $error")
        }
    }

    // ========================================================================
    // Public API — called by KnoxService
    // ========================================================================

    /**
     * Start Fort CT session. Binds CaptureControlService and begins handshake.
     * On success, KnoxService.onSessionReady() called.
     * On failure, KnoxService.onSessionEnded() called via stopSession().
     */
    fun startSession(sessionId: String) {
        Log.i(LOG_TAG_KNOX, "startSession: sessionId=$sessionId")
        stopped = false
        remoteSessionId = sessionId
        bindControlService()
    }

    /**
     * Stop session and tear down all connections in order.
     * Safe to call multiple times. Notifies KnoxService.onSessionEnded().
     */
    fun stopSession(reason: String) {
        if (stopped) return
        Log.i(LOG_TAG_KNOX, "stopSession: $reason")
        stopped = true
        isSessionRunning = false

        releaseCaptureQuietly()
        unbindCaptureQuietly()
        unbindControlQuietly()

        sessionPayload = null
        generatedRemoteId = null
        generatedToken = null
        isCapturePrepared = false
        pendingStartAfterPrepare = false

        service.onSessionEnded(reason)
    }

    /**
     * Whether session successfully started and currently running.
     */
    fun isRunning(): Boolean = isSessionRunning && !stopped

    // ========================================================================
    // Step 1: Bind CaptureControlService
    // ========================================================================

    private fun bindControlService() {
        if (stopped) return
        if (isControlBound) {
            requestSessionInfo()
            return
        }
        if (controlBindPending) return

        val intent = Intent().apply {
            setClassName(KNOX_PACKAGE, KNOX_CONTROL_SERVICE)
        }
        val bound = context.bindService(intent, controlConnection, Context.BIND_AUTO_CREATE)
        if (bound) {
            controlBindPending = true
        } else {
            Log.e(LOG_TAG_KNOX, "Failed to bind CaptureControlService")
            stopSession("Failed to bind CaptureControlService")
        }
    }

    // ========================================================================
    // Step 2: Request session info
    // ========================================================================

    private fun requestSessionInfo() {
        if (stopped) return
        val sessionId = remoteSessionId ?: return
        val messenger = controlMessenger ?: return

        Log.d(LOG_TAG_KNOX, "Sending MSG_GET_SESSION_INFO for session=$sessionId")
        val msg = Message.obtain(null, MSG_GET_SESSION_INFO).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(KEY_REMOTE_SESSION_ID, sessionId)
            }
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG_KNOX, "Failed to send MSG_GET_SESSION_INFO", e)
            stopSession("Failed to send MSG_GET_SESSION_INFO: ${e.message}")
        }
    }

    // ========================================================================
    // Step 3: Validate session info reply
    // ========================================================================

    private fun handleGetSessionInfoReply(msg: Message) {
        if (stopped) return

        if (msg.arg1 < 0) {
            Log.e(LOG_TAG_KNOX, "MSG_GET_SESSION_INFO error: code=${msg.arg1}")
            stopSession("MSG_GET_SESSION_INFO error: code=${msg.arg1}")
            return
        }

        val json = msg.data?.getString(KEY_SESSION_INFO)
        if (json.isNullOrBlank()) {
            Log.e(LOG_TAG_KNOX, "session_info is null or blank")
            stopSession("No active session (session_info is null)")
            return
        }

        Log.d(LOG_TAG_KNOX, "Received session_info: $json")

        val payload = try {
            parseSessionPayload(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to parse session_info JSON", e)
            stopSession("Failed to parse session_info: ${e.message}")
            return
        }

        if (payload.remoteSessionId != remoteSessionId) {
            Log.e(LOG_TAG_KNOX, "Session id mismatch: expected=$remoteSessionId, " +
                    "got=${payload.remoteSessionId}")
            stopSession("Session id mismatch")
            return
        }

        sessionPayload = payload
        Log.d(LOG_TAG_KNOX, "Session validated: status=${payload.status}, " +
                "isUserConsentRequired=${payload.isUserConsentRequired}")

        if (payload.isUserConsentRequired) {
            // Attended flow: not yet implemented (Point 1).
            Log.w(LOG_TAG_KNOX, "Attended session — not yet implemented, aborting")
            stopSession("Attended sessions not yet implemented")
            return
        }

        // Unattended: bind CaptureService, prepare, go running
        pendingStartAfterPrepare = true
        bindCaptureService()
    }

    // ========================================================================
    // Step 4: Bind CaptureService (AIDL) and prepare capture
    // ========================================================================

    private fun bindCaptureService() {
        if (stopped) return
        if (isCaptureBound) {
            prepareCaptureAfterBind()
            return
        }
        if (captureBindPending) return

        val intent = Intent().apply {
            setClassName(KNOX_PACKAGE, KNOX_SERVICE)
        }
        val bound = context.bindService(intent, captureConnection, Context.BIND_AUTO_CREATE)
        if (bound) {
            captureBindPending = true
        } else {
            Log.e(LOG_TAG_KNOX, "Failed to bind CaptureService")
            stopSession("Failed to bind CaptureService")
        }
    }

    private fun prepareCaptureAfterBind() {
        if (stopped) return
        val svc = captureService ?: return

        if (isCapturePrepared) {
            if (pendingStartAfterPrepare && !isSessionRunning) {
                sendStartSession()
            }
            return
        }

        try {
            svc.initCapture()
            svc.registerFrameCallback(knoxFrameCallback)

            val knoxWidth = svc.screenWidth
            val knoxHeight = svc.screenHeight
            if (knoxWidth <= 0 || knoxHeight <= 0) {
                Log.e(LOG_TAG_KNOX, "Invalid screen dimensions: ${knoxWidth}x${knoxHeight}")
                stopSession("Invalid screen dimensions: ${knoxWidth}x${knoxHeight}")
                return
            }
            updateScreenInfoForKnox(knoxWidth, knoxHeight)
            isCapturePrepared = true

            Log.d(LOG_TAG_KNOX, "Capture initialized: ${knoxWidth}x${knoxHeight}")

            if (pendingStartAfterPrepare && !isSessionRunning) {
                sendStartSession()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to initialize capture", e)
            stopSession("Capture init failed: ${e.message}")
        }
    }

    // ========================================================================
    // Step 5: Send MSG_START_SESSION
    // ========================================================================

    private fun sendStartSession() {
        if (stopped) return
        val sessionId = remoteSessionId ?: return
        val messenger = controlMessenger ?: run {
            stopSession("Control messenger lost before MSG_START_SESSION")
            return
        }

        val remoteId = FFI.getMyId()
        if (remoteId.isBlank()) {
            Log.w(LOG_TAG_KNOX, "Device remote ID blank, using fallback UUID")
        }
        generatedRemoteId = remoteId.ifBlank { "knox-${java.util.UUID.randomUUID()}" }
        generatedToken = generateSecureToken()

        Log.d(LOG_TAG_KNOX, "Sending MSG_START_SESSION: remoteId=$generatedRemoteId")
        val msg = Message.obtain(null, MSG_START_SESSION).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(KEY_REMOTE_SESSION_ID, sessionId)
                putString(KEY_REMOTE_ID, generatedRemoteId)
                putString(KEY_TOKEN, generatedToken)
            }
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG_KNOX, "Failed to send MSG_START_SESSION", e)
            stopSession("Failed to send MSG_START_SESSION: ${e.message}")
        }
    }

    // ========================================================================
    // Step 6: Handle MSG_START_SESSION reply — session RUNNING
    // ========================================================================

    private fun handleStartSessionReply(msg: Message) {
        if (stopped) return

        if (msg.arg1 < 0) {
            Log.e(LOG_TAG_KNOX, "MSG_START_SESSION error: code=${msg.arg1}")
            stopSession("MSG_START_SESSION rejected: code=${msg.arg1}")
            return
        }

        Log.i(LOG_TAG_KNOX, "Session is now RUNNING")
        isSessionRunning = true
        pendingStartAfterPrepare = false

        // Notify KnoxService — enables video pipeline, updates Flutter
        service.onSessionReady()
    }

    // ========================================================================
    // Capture operations — called by MainService for input routing
    // ========================================================================

    fun isBound(): Boolean = isCaptureBound && captureService != null

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

    fun injectKeyEvent(data: ByteArray): Boolean {
        val keyEvent = ProtoKeyEvent.parseFrom(data)
        if (keyEvent.getMode() == KeyboardMode.Legacy && keyEvent.getDown() == false) {
            // KeyCharacterMap handles all needed events for legacy mode
            return true
        }
        val ke: Array<KeyEventAndroid>? = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        return try {
            ke?.forEach { event: KeyEventAndroid ->
                captureService?.injectKeyEvent(event)
                if (keyEvent.getMode() == KeyboardMode.Map && keyEvent.getPress()) {
                    val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                    captureService?.injectKeyEvent(actionUpEvent)
                }
            }
            true
        } catch (e: Exception) {
            Log.d(LOG_TAG_KNOX, "Knox injectKeyEvent failed: ${e.message}")
            false
        }
    }

    // ========================================================================
    // Teardown helpers
    // ========================================================================

    private fun releaseCaptureQuietly() {
        synchronized(mappingLock) {
            val buf = knoxMappedBuffer
            if (buf != null) {
                runCatching { SharedMemory.unmap(buf) }
                knoxMappedBuffer = null
            }
        }
        runCatching { captureService?.unregisterFrameCallback() }
    }

    private fun unbindCaptureQuietly() {
        if (!captureBindPending && !isCaptureBound) return
        runCatching { context.unbindService(captureConnection) }
        captureBindPending = false
        isCaptureBound = false
        captureService = null
    }

    private fun unbindControlQuietly() {
        if (!controlBindPending && !isControlBound) return
        runCatching { context.unbindService(controlConnection) }
        controlBindPending = false
        isControlBound = false
        controlMessenger = null
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private fun generateSecureToken(): String {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun parseSessionPayload(json: String): SessionPayload {
        val obj = JSONObject(json)
        return SessionPayload(
            remoteSessionId = obj.getString("remoteSessionId"),
            status = obj.optString("status", ""),
            url = obj.optString("url", null),
            key = obj.optString("key", null),
            isUserConsentRequired = obj.optBoolean("isUserConsentRequired", true)
        )
    }

    private fun updateScreenInfoForKnox(width: Int, height: Int) {
        SCREEN_INFO.width = width
        SCREEN_INFO.height = height
        if (SCREEN_INFO.dpi == 0) {
            SCREEN_INFO.dpi = 240
        }
    }

    // ========================================================================
    // Reply handler (WeakReference pattern)
    // ========================================================================

    private class ReplyHandler(
        private val capturerRef: WeakReference<KnoxCapturer>
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val capturer = capturerRef.get() ?: return
            when (msg.what) {
                MSG_GET_SESSION_INFO -> capturer.handleGetSessionInfoReply(msg)
                MSG_START_SESSION -> capturer.handleStartSessionReply(msg)
            }
        }
    }
}
