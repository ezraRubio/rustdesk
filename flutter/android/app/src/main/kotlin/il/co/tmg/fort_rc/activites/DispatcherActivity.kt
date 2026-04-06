package il.co.tmg.fort_ct.activities 

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.Activity
import android.os.*
import android.util.Log
import com.carriez.flutter_hbb.KnoxCapturer
import com.carriez.flutter_hbb.MainService
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * DispatcherActivity is the entry point started by Fort Control Tower when a
 * remote-control session is initiated.
 *
 * It acts as a trampoline that:
 * 1. Binds to CaptureControlService (Messenger IPC) and validates the session
 * 2. For unattended sessions (isUserConsentRequired=false):
 *    - Binds CaptureService (AIDL), initializes capture, and sends MSG_START_SESSION
 *    - Hands off the ready KnoxCapturer to MainService
 * 3. For attended sessions (isUserConsentRequired=true):
 *    - Not yet implemented; finishes immediately
 *
 * This activity must remain alive for the full duration of the session because
 * unbinding from CaptureControlService is what signals session end to Fort CT.
 */
class DispatcherActivity : Activity() {

    companion object {
        private const val LOG_TAG = "DispatcherActivity"

        // CaptureControlService Messenger message codes
        private const val MSG_GET_SESSION_INFO = 1
        private const val MSG_START_SESSION = 2

        // Fort Control Tower package and service
        private const val FORT_CT_PACKAGE = "il.co.tmg.fort_ct"
        private const val CAPTURE_CONTROL_SERVICE = "il.co.tmg.fort_ct.ipc.CaptureControlService"
        private const val CAPTURE_SERVICE = "il.co.tmg.fort_ct.ipc.CaptureService"

        // Token generation
        private const val TOKEN_BYTE_LENGTH = 32
    }

    // ---- Intent extras ----
    private val remoteSessionId: String? by lazy {
        intent.getStringExtra("remote_session_id")
    }
    private val isUserConsentRequiredHint: Boolean by lazy {
        intent.getBooleanExtra("is_user_consent_required", true)
    }

    // ---- CaptureControlService (Messenger IPC) ----
    private var controlMessenger: Messenger? = null
    private var isControlBound = false

    private val replyMessenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                MSG_GET_SESSION_INFO -> handleGetSessionInfoReply(msg)
                MSG_START_SESSION -> handleStartSessionReply(msg)
            }
            true
        }
    )

    private val controlConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(LOG_TAG, "CaptureControlService connected")
            controlMessenger = Messenger(service)
            isControlBound = true
            requestSessionInfo()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(LOG_TAG, "CaptureControlService disconnected (session ended externally)")
            controlMessenger = null
            isControlBound = false
            // Fort CT killed the session; clean up and finish
            onSessionEndedExternally()
            finish()
        }
    }

    // ---- CaptureService (AIDL via KnoxCapturer) ----
    private var knoxCapturer: KnoxCapturer? = null

    // ---- MainService binding ----
    private var mainService: MainService? = null
    private var isMainServiceBound = false

    private val mainServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(LOG_TAG, "MainService connected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
            isMainServiceBound = true
            // Now that MainService is available, proceed with CaptureService binding
            bindCaptureServiceAndPrepare()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(LOG_TAG, "MainService disconnected")
            mainService = null
            isMainServiceBound = false
        }
    }

    // ---- Session state ----
    private var sessionPayload: SessionPayload? = null
    private var generatedRemoteId: String? = null
    private var generatedToken: String? = null
    private var isSessionRunning = false

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(LOG_TAG, "onCreate: remoteSessionId=$remoteSessionId, " +
                "isUserConsentRequiredHint=$isUserConsentRequiredHint")

        if (remoteSessionId.isNullOrBlank()) {
            Log.e(LOG_TAG, "No remote_session_id in intent, finishing")
            finish()
            return
        }

        // Register this dispatcher with MainService for teardown signaling
        MainService.activeDispatcher = this

        // Bind to CaptureControlService
        val controlIntent = Intent().apply {
            setClassName(FORT_CT_PACKAGE, CAPTURE_CONTROL_SERVICE)
        }

        val bound = bindService(controlIntent, controlConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.e(LOG_TAG, "Failed to bind CaptureControlService")
            finish()
            return
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTask: Fort CT may re-launch with a new session
        setIntent(intent)
        val newSessionId = intent?.getStringExtra("remote_session_id")
        Log.d(LOG_TAG, "onNewIntent: newSessionId=$newSessionId, current=$remoteSessionId")

        if (newSessionId != remoteSessionId) {
            // Different session — tear down current and restart
            Log.w(LOG_TAG, "New session id received, restarting session flow")
            teardownCurrentSession()
            // Re-read the lazy property won't work since it's cached;
            // just recreate the activity
            recreate()
        }
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, "onDestroy: tearing down session")
        teardownCurrentSession()

        if (MainService.activeDispatcher === this) {
            MainService.activeDispatcher = null
        }

        super.onDestroy()
    }

    // ========================================================================
    // Step 1: Request session info from CaptureControlService
    // ========================================================================

    private fun requestSessionInfo() {
        val messenger = controlMessenger
        if (messenger == null) {
            Log.e(LOG_TAG, "requestSessionInfo: controlMessenger is null")
            finish()
            return
        }

        Log.d(LOG_TAG, "Sending MSG_GET_SESSION_INFO for session=$remoteSessionId")
        val msg = Message.obtain(null, MSG_GET_SESSION_INFO).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString("remote_session_id", remoteSessionId)
            }
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "Failed to send MSG_GET_SESSION_INFO", e)
            finish()
        }
    }

    // ========================================================================
    // Step 2: Validate the returned session
    // ========================================================================

    private fun handleGetSessionInfoReply(msg: Message) {
        if (msg.arg1 < 0) {
            Log.e(LOG_TAG, "MSG_GET_SESSION_INFO error: code=${msg.arg1}")
            finish()
            return
        }

        val json = msg.data?.getString("session_info")
        if (json.isNullOrBlank()) {
            Log.e(LOG_TAG, "session_info is null or blank, no active session")
            finish()
            return
        }

        Log.d(LOG_TAG, "Received session_info: $json")

        val payload = try {
            parseSessionPayload(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse session_info JSON", e)
            finish()
            return
        }

        if (payload.remoteSessionId != remoteSessionId) {
            Log.e(LOG_TAG, "Session id mismatch: expected=$remoteSessionId, " +
                    "got=${payload.remoteSessionId}")
            finish()
            return
        }

        sessionPayload = payload
        Log.d(LOG_TAG, "Session validated: status=${payload.status}, " +
                "isUserConsentRequired=${payload.isUserConsentRequired}")

        if (payload.isUserConsentRequired) {
            // Attended flow: not yet implemented.
            // The user must manually open the app, grant MediaProjection, etc.
            // For now, log and finish. The session stays in 'pending' on Fort CT side.
            Log.w(LOG_TAG, "Attended session (isUserConsentRequired=true) — " +
                    "not yet implemented, finishing")
            finish()
            return
        }

        // Unattended flow: bind MainService, then CaptureService, then go running
        bindMainService()
    }

    // ========================================================================
    // Step 3: Bind MainService (to hand off KnoxCapturer later)
    // ========================================================================

    private fun bindMainService() {
        Log.d(LOG_TAG, "Binding to MainService")
        val intent = Intent(this, MainService::class.java)
        val bound = bindService(intent, mainServiceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.e(LOG_TAG, "Failed to bind MainService")
            finish()
        }
    }

    // ========================================================================
    // Step 4: Bind CaptureService (AIDL) and prepare capture
    // ========================================================================

    private fun bindCaptureServiceAndPrepare() {
        val service = mainService
        if (service == null) {
            Log.e(LOG_TAG, "bindCaptureServiceAndPrepare: mainService is null")
            finish()
            return
        }

        val handler = service.getServiceHandler()
        if (handler == null) {
            Log.e(LOG_TAG, "bindCaptureServiceAndPrepare: serviceHandler is null")
            finish()
            return
        }

        Log.d(LOG_TAG, "Creating KnoxCapturer and binding CaptureService")
        knoxCapturer = KnoxCapturer(this, handler) { MainService.isStart }

        // Bind on a background thread since KnoxCapturer.bind() uses a synchronous wait
        handler.post {
            val capturer = knoxCapturer
            if (capturer == null) {
                Log.e(LOG_TAG, "KnoxCapturer became null before bind")
                runOnUiThread { finish() }
                return@post
            }

            if (!capturer.bind()) {
                Log.e(LOG_TAG, "Failed to bind CaptureService")
                runOnUiThread { finish() }
                return@post
            }

            Log.d(LOG_TAG, "CaptureService bound, initializing capture")
            if (!capturer.initCapture()) {
                Log.e(LOG_TAG, "Failed to initialize capture")
                runOnUiThread { finish() }
                return@post
            }

            Log.d(LOG_TAG, "Capture initialized, sending MSG_START_SESSION")
            runOnUiThread { startRunningSession() }
        }
    }

    // ========================================================================
    // Step 5: Move session to 'running'
    // ========================================================================

    private fun startRunningSession() {
        val messenger = controlMessenger
        if (messenger == null) {
            Log.e(LOG_TAG, "startRunningSession: controlMessenger is null")
            finish()
            return
        }

        generatedRemoteId = UUID.randomUUID().toString()
        generatedToken = generateSecureToken()

        Log.d(LOG_TAG, "Sending MSG_START_SESSION: remoteId=$generatedRemoteId")
        val msg = Message.obtain(null, MSG_START_SESSION).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString("remote_session_id", remoteSessionId)
                putString("remote_id", generatedRemoteId)
                putString("token", generatedToken)
            }
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "Failed to send MSG_START_SESSION", e)
            finish()
        }
    }

    // ========================================================================
    // Step 6: Handle MSG_START_SESSION reply — session is now RUNNING
    // ========================================================================

    private fun handleStartSessionReply(msg: Message) {
        if (msg.arg1 < 0) {
            Log.e(LOG_TAG, "MSG_START_SESSION error: code=${msg.arg1}")
            finish()
            return
        }

        Log.i(LOG_TAG, "Session is now RUNNING")
        isSessionRunning = true

        // Hand off the ready KnoxCapturer to MainService
        val service = mainService
        val capturer = knoxCapturer
        if (service == null || capturer == null) {
            Log.e(LOG_TAG, "MainService or KnoxCapturer null after MSG_START_SESSION success")
            finish()
            return
        }

        service.onKnoxSessionReady(capturer)
        Log.i(LOG_TAG, "KnoxCapturer handed off to MainService, session fully active")

        // Activity stays alive to keep CaptureControlService bound.
        // It is invisible (transparent theme, excludeFromRecents).
    }

    // ========================================================================
    // Session teardown
    // ========================================================================

    private fun teardownCurrentSession() {
        Log.d(LOG_TAG, "teardownCurrentSession")
        isSessionRunning = false

        // Release capture resources
        knoxCapturer?.releaseCapture()
        knoxCapturer?.unbind()
        knoxCapturer = null

        // Unbind MainService
        if (isMainServiceBound) {
            try {
                unbindService(mainServiceConnection)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error unbinding MainService", e)
            }
            isMainServiceBound = false
            mainService = null
        }

        // Unbind CaptureControlService — this ends the session on Fort CT side
        if (isControlBound) {
            try {
                unbindService(controlConnection)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error unbinding CaptureControlService", e)
            }
            isControlBound = false
            controlMessenger = null
        }
    }

    /**
     * Called when Fort CT kills the session externally (CaptureControlService disconnected).
     * Notifies MainService to clean up its Knox state.
     */
    private fun onSessionEndedExternally() {
        Log.w(LOG_TAG, "Session ended externally by Fort CT")
        mainService?.onKnoxSessionEnded()
    }

    /**
     * Called by MainService.destroy() to signal that the app-side wants to end the session.
     * Finishing this activity will unbind CaptureControlService, ending the Fort CT session.
     */
    fun requestFinish() {
        Log.d(LOG_TAG, "requestFinish called by MainService")
        finish()
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
}

/**
 * Represents the session payload returned by CaptureControlService in MSG_GET_SESSION_INFO.
 */
data class SessionPayload(
    val remoteSessionId: String,
    val status: String,
    val url: String?,
    val key: String?,
    val isUserConsentRequired: Boolean
)
