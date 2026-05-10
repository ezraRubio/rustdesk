package il.co.tmg.fort_rc 

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
import com.carriez.flutter_hbb.SCREEN_INFO
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
private const val MSG_READY_FOR_CONNECTION = 2
private const val MSG_START_SESSION = 3

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
enum class SessionState(val status: String) {
    CREATED("created"),
    PENDING("pending"),
    READY("ready"),
    RUNNING("running"),
    CLOSED("closed"),
    FAILED("failed")
}

data class SessionPayload(
    val remoteSessionId: String,
    val status: SessionState,
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
    // private var sessionPayload: SessionPayload? = null
    private var generatedRemoteId: String? = null
    private var generatedToken: String? = null
    private var isSessionReady = false
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
            if (stopped || !isSessionReady) {
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

    fun startSession(sessionId: String) {
        stopped = false
        remoteSessionId = sessionId
        bindControlService()
    }

    fun stopSession(reason: String) {
        if (stopped) return
        stopped = true
        isSessionReady = false

        releaseCaptureQuietly()
        unbindCaptureQuietly()
        unbindControlQuietly()

        // sessionPayload = null
        generatedRemoteId = null
        generatedToken = null
        isCapturePrepared = false
        pendingStartAfterPrepare = false

        service.onSessionEnded(reason)
    }

    // ========================================================================
    // FCT <-> FRC Handshake
    //
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
    //
    // ========================================================================

    private fun handleGetSessionInfoReply(msg: Message) {
        Log.i(LOG_TAG_KNOX, " step 3 got session info, message: ${msg}, data: ${msg.data}")
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

        val payload = try {
            parseSessionPayload(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to parse session_info JSON", e)
            stopSession("Failed to parse session_info: ${e.message}")
            return
        }
        Log.i(LOG_TAG_KNOX, "got session info, payload: ${payload}")

        if (payload.remoteSessionId != remoteSessionId) {
            Log.e(LOG_TAG_KNOX, "Session id mismatch: expected=$remoteSessionId, " +
                    "got=${payload.remoteSessionId}")
            stopSession("Session id mismatch")
            return
        }

        if (payload.url.isNullOrBlank() || payload.key.isNullOrBlank()) {
            Log.e(LOG_TAG_KNOX, "Did not received server creds on session_info")
            stopSession("Missing server creds on session_info")
            return
        }

        if (payload.isUserConsentRequired) {
            // Attended flow: not yet implemented.
            Log.w(LOG_TAG_KNOX, "Attended session — not yet implemented, aborting")
            stopSession("Attended sessions not yet implemented")
            return
        }

        // Now that we have the servers' creds, we can start it:
        val appConfig = buildAppConfig(payload)
        FFI.startServer("", appConfig)
        pendingStartAfterPrepare = true
        waitForServerOnline(::bindCaptureService)
    }

    // ========================================================================
    // Step 4: Bind CaptureService (AIDL) and prepare capture
    // ========================================================================

    private fun bindCaptureService() {
      Log.i(LOG_TAG_KNOX, "step 4")
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
            if (pendingStartAfterPrepare && !isSessionReady) {
                sendReadyForConnection()
            }
            return
        }

        try {
            svc.initCapture()

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

            if (pendingStartAfterPrepare && !isSessionReady) {
                sendReadyForConnection()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to initialize capture", e)
            stopSession("Capture init failed: ${e.message}")
        }
    }

    // ========================================================================
    // Step 5: Send MSG_READY_FOR_CONNECTION
    // ========================================================================

    private fun sendReadyForConnection() {
      Log.i(LOG_TAG_KNOX, "step 5")
        if (stopped) return
        val sessionId = remoteSessionId ?: return
        val messenger = controlMessenger ?: run {
            stopSession("Control messenger lost before MSG_READY_FOR_CONNECTION")
            return
        }

        val remoteId = FFI.getMyId()
        val password = FFI.getTemporaryPassword()

        val msg = Message.obtain(null, MSG_READY_FOR_CONNECTION).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(KEY_REMOTE_SESSION_ID, sessionId)
                putString(KEY_REMOTE_ID, remoteId)
                putString(KEY_TOKEN, password)
            }
        }

        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG_KNOX, "Failed to send MSG_READY_FOR_CONNECTION", e)
            stopSession("Failed to send MSG_READY_FOR_CONNECTION: ${e.message}")
        }
    }

    // ========================================================================
    // Step 6: Handle MSG_READY_FOR_CONNECTION reply — session READY
    // ========================================================================

    private fun handleReadyForConnectionReply(msg: Message) {
      Log.i(LOG_TAG_KNOX, "step 6 with $msg")
        if (stopped) return

        if (msg.arg1 < 0) {
            Log.e(LOG_TAG_KNOX, "MSG_READY_FOR_CONNECTION error: code=${msg.arg1}")
            stopSession("MSG_READY_FOR_CONNECTION rejected: code=${msg.arg1}")
            return
        }

        val json = msg.data?.getString(KEY_SESSION_INFO)
        if (json.isNullOrBlank()) {
            Log.e(LOG_TAG_KNOX, "session_info is null or blank")
            stopSession("No active session (session_info is null)")
            return
        }

        val payload = try {
            parseSessionPayload(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to parse session_info JSON", e)
            stopSession("Failed to parse session_info: ${e.message}")
            return
        }

        if (payload.remoteSessionId != remoteSessionId || payload.status != SessionState.READY) {
            Log.e(LOG_TAG_KNOX, "Session id mismatch: expected=$remoteSessionId, " +
                    "got=${payload.remoteSessionId}" +
                    ", or session is not READY: ${payload.status}")
            stopSession("Session id mismatch or session not ready")
            return
        }

        isSessionReady = true
        pendingStartAfterPrepare = false

        service.onSessionReadyForConnection()
    }

    // ========================================================================
    // Step 7: Send MSG_START_SESSION
    // ========================================================================
 
    private fun sendStartSessionMessage() {
        if (stopped) return
        val sessionId = remoteSessionId ?: return
        val messenger = controlMessenger ?: run {
            stopSession("Control messenger lost before MSG_START_SESSION")
            return
        }
        val msg = Message.obtain(null, MSG_START_SESSION).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(KEY_REMOTE_SESSION_ID, sessionId)
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
    // Step 8: Handle MSG_START_SESSION reply 
    // ========================================================================

    private fun handleStartSessionReply(msg: Message) {
        if (stopped) return

        if (msg.arg1 < 0) {
            Log.e(LOG_TAG_KNOX, "MSG_READY_FOR_CONNECTION error: code=${msg.arg1}")
            stopSession("MSG_READY_FOR_CONNECTION rejected: code=${msg.arg1}")
            return
        }

        val json = msg.data?.getString(KEY_SESSION_INFO)
        if (json.isNullOrBlank()) {
            Log.e(LOG_TAG_KNOX, "session_info is null or blank")
            stopSession("No active session (session_info is null)")
            return
        }

        val payload = try {
            parseSessionPayload(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG_KNOX, "Failed to parse session_info JSON", e)
            stopSession("Failed to parse session_info: ${e.message}")
            return
        }

        if (payload.remoteSessionId != remoteSessionId || payload.status != SessionState.RUNNING) {
            Log.e(LOG_TAG_KNOX, "Session id mismatch: expected=$remoteSessionId, " +
                    "got=${payload.remoteSessionId}" +
                    ", or session is not RUNNING: ${payload.status}")
            stopSession("Session id mismatch or session not running")
            return
        }

        isSessionRunning = true
    }

    // ========================================================================
    // Capturer operations
    // ========================================================================

    fun startCapture() {
      val capServ = captureService
      if (capServ != null) {
          capServ.registerFrameCallback(knoxFrameCallback)
          FFI.setFrameRawEnable("video", true)
          sendStartSessionMessage()
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
        Log.i(LOG_TAG_KNOX, "parsing json, stringified: ${json}")
        val obj = JSONObject(json)
        Log.i(LOG_TAG_KNOX, "parsing json, object: ${obj}")
        Log.i(LOG_TAG_KNOX, "parsing json, remoteSessionId: ${obj.getString("remoteSessionId")}")
        Log.i(LOG_TAG_KNOX, "parsing json, url: ${obj.optString("url")}")
        Log.i(LOG_TAG_KNOX, "parsing json, key: ${obj.optString("key")}")
        return SessionPayload(
            remoteSessionId = obj.getString("remoteSessionId"),
            status = SessionState.entries.first { it.status == obj.getString("status") },
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

    private fun buildAppConfig(payload: SessionPayload): String {
        val serverHost = payload.url
        val serverKey = payload.key
        val config = JSONObject()
        val defaultSettings = JSONObject()

        config.put("app-name", "Fort Remote Desktop")
        defaultSettings.put("custom-rendezvous-server", serverHost)
        defaultSettings.put("key", serverKey)
        config.put("default-settings", defaultSettings)
        return config.toString()
    }

    private val MAX_WAIT_MS = 15_000L
    private val POLL_INTERVAL_MS = 500L
    private fun waitForServerOnline(onReady: () -> Unit) {
         val startTime = System.currentTimeMillis()
         val poller = object : Runnable {
            override fun run() {
                  if (stopped) return
                  val state = FFI.getOnlineState()
                  val status = try {
                    JSONObject(state).getInt("status_num")
                  } catch (e: Exception) {
                    0
                  }
                  Log.d(LOG_TAG_KNOX, "polling current online status: $status")
                  if (status > 0) {
                        Log.i(LOG_TAG_KNOX,"Serveronline (state=$state)")
                        onReady()
                  } else if (System.currentTimeMillis() - startTime > MAX_WAIT_MS) {
                        stopSession("Timeout waiting for server registration")
                  } else {
                        serviceHandler.postDelayed(this, POLL_INTERVAL_MS)
                  }
            }
        }
        serviceHandler.post(poller)
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
                MSG_READY_FOR_CONNECTION -> capturer.handleReadyForConnectionReply(msg)
                MSG_START_SESSION -> capturer.handleStartSessionReply(msg)
            }
        }
    }
}
