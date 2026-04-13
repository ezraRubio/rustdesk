package il.co.tmg.fort_rc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import ffi.FFI
import io.flutter.embedding.android.FlutterActivity
import android.content.Context
import android.os.PowerManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import org.json.JSONException
import org.json.JSONObject
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.util.DisplayMetrics
import android.annotation.SuppressLint
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import com.carriez.flutter_hbb.InputService
import com.carriez.flutter_hbb.KEY_APP_DIR_CONFIG_PATH
import com.carriez.flutter_hbb.KEY_SHARED_PREFERENCES
import com.carriez.flutter_hbb.LEFT_DOWN
import com.carriez.flutter_hbb.MainActivity
import com.carriez.flutter_hbb.R
import com.carriez.flutter_hbb.SCREEN_INFO
import com.carriez.flutter_hbb.translate

/**
 * KnoxService is a foreground service that owns the full fort rc session lifecycle.
 *
 * It is instead of MainService when unattended. As such:
 *   1. Normal (attended): User → MainActivity → MainService
 *   2. Fort CT (unattended): Fort CT → DispatcherActivity → KnoxService → KnoxCapturer
 *
 * TODO:
 * - is notifications needed as in MainService?
 * - what happens when stop_capture is received from rust (rustdesk server) ?
 * - what happens on concurrent session?
 * - should there be support for half_scale signal from rust?
 * - can i fully disconnect from flutter or do i need to send state_change? 
 * - add_connection should trigger the START_SESSION message for fct
 */
class KnoxService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        val isInteractive = powerManager.isInteractive
        if (!isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            wakeLock.acquire(5000)
        } else {
            val knox = knoxCapturer
            if (knox != null) {
                knox.injectPointer(kind, mask, x, y, !isInteractive)
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        val knox = knoxCapturer
        if (knox != null) {
            knox.injectKeyEvent(input)
        }
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
              //TODO: 6
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }
                    if (authorized) {
                        if (!isFileTransfer && !isStart) {
                            _isStart = true
                            val capturer = knoxCapturer
                            if (capturer != null) {
                              capturer.startCapture()
                            }
                        }
                        Log.w(LOG_TAG, "client authorized")
                        // onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        Log.w(LOG_TAG, "login request")
                        // loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                Log.w(LOG_TAG, "Voice call not supported in Knox session, ignoring")
            }
            "stop_capture" -> {
              //TODO: 2
                Log.d(LOG_TAG, "from rust:stop_capture, what to do?")
                // stopCapture()
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    Log.d(LOG_TAG, "half_scale received from rust, not supported on this path")
                    //TODO: 4
                }
                
            }
            else -> {
            }
        }
    }


    companion object {
        private const val LOG_TAG = "KnoxService"

        const val ACTION_START = "com.carriez.flutter_hbb.KNOX_START_SESSION"
        const val ACTION_STOP = "com.carriez.flutter_hbb.KNOX_STOP_SESSION"

        private const val CHANNEL_ID = "fort_knox_capture"
        private const val CHANNEL_NAME = "Fort Remote Control"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var isActive: Boolean = false
            private set
    }

    private val powerManager: PowerManager by lazy{
        applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
              PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "rustdesk:knoxwakelock"
        )
    }
    private var isHalfScale: Boolean? = null
    private var _isStart = false
    private val isStart: Boolean get() = _isStart
    private lateinit var notificationManager:NotificationManager
    private lateinit var notificationBuilder:NotificationCompat.Builder

    var knoxCapturer: KnoxCapturer? = null
        private set

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    // ========================================================================
    // Lifecycle
    // ========================================================================
    override fun onCreate() {
        super.onCreate()
        // Background thread for Knox operations
        HandlerThread("KnoxService", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }

        // Initialize FFI/Rust
        FFI.init(this)
        val prefs = applicationContext.getSharedPreferences(
          //TODO: 5
            KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE
        )
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

        // Initialize the notification system
        // TODO: 1
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationBuilder = NotificationCompat.Builder(this,CHANNEL_ID)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Connecting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra("remote_session_id")
                if (sessionId.isNullOrBlank()) {
                    Log.e(LOG_TAG, "ACTION_START missing remote_session_id")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (knoxCapturer != null) {
                    Log.w(LOG_TAG, "Session already active, ignoring new request")
                    // TODO: 3
                    return START_STICKY
                }

                val handler = serviceHandler!!
                val capturer = KnoxCapturer(
                    context = applicationContext,
                    serviceHandler = handler,
                    service = this
                )
                knoxCapturer = capturer
                capturer.startSession(sessionId)
            }
            ACTION_STOP -> {
                stopSession("stop action received")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        knoxCapturer?.stopSession("KnoxService destroyed")
        knoxCapturer = null
        serviceLooper?.quitSafely()
        super.onDestroy()
    }

    // ========================================================================
    // Session callbacks — called by KnoxCapturer
    // ========================================================================

    /**
     * Called by KnoxCapturer when session handshake completes and capture is
     * initialized. Frames will start flowing.
     */
    fun onSessionReady() {
        Log.i(LOG_TAG, "Session ready — enabling video pipeline")
        isActive = true
        FFI.setFrameRawEnable("video", true)
        updateNotification("Session active")
        notifyFlutterStateChanged()
    }

    /**
     * Called by KnoxCapturer when session ends (external disconnect, error, etc.).
     * Tears down service.
     */
    fun onSessionEnded(reason: String) {
        Log.w(LOG_TAG, "Session ended: $reason")
        isActive = false
        FFI.setFrameRawEnable("video", false)
        knoxCapturer = null
        notifyFlutterStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Stop current session and shut down service.
     */
    fun stopSession(reason: String) {
        val capturer = knoxCapturer
        if (capturer != null) {
            // KnoxCapturer.stopSession → onSessionEnded → cleanup + stopSelf
            capturer.stopSession(reason)
        } else {
            // No capturer — clean up directly
            onSessionEnded(reason)
        }
    }

    // ========================================================================
    // Flutter state propagation
    // ========================================================================

    /**
     * TODO: 5
     * this mimics checkPermissions from MainService
     */
    private fun notifyFlutterStateChanged() {
        Handler(Looper.getMainLooper()).post {
            val active = isActive
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to active.toString())
            )
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to (active || InputService.isOpen).toString())
            )
        }
    }

    // ========================================================================
    // Notification
    // TODO: 1
    //
    // ========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Fort Control Tower remote capture session"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setContentTitle("Fort Remote Control")
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    // private fun loginRequestNotification(
    //     clientID: Int,
    //     type: String,
    //     username: String,
    //     peerId: String
    // ) {
    //     val notification = notificationBuilder
    //         .setOngoing(false)
    //         .setPriority(NotificationCompat.PRIORITY_MAX)
    //         .setContentTitle(translate("Do you accept?"))
    //         .setContentText("$type:$username-$peerId")
    //         .build()
    //     notificationManager.notify(getClientNotifyID(clientID), notification)
    // }
    //
    // private fun onClientAuthorizedNotification(
    //     clientID: Int,
    //     type: String,
    //     username: String,
    //     peerId: String
    // ) {
    //     cancelNotification(clientID)
    //     val notification = notificationBuilder
    //         .setOngoing(false)
    //         .setPriority(NotificationCompat.PRIORITY_MAX)
    //         .setContentTitle("$type ${translate("Established")}")
    //         .setContentText("$username - $peerId")
    //         .build()
    //     notificationManager.notify(getClientNotifyID(clientID), notification)
    // }
    //
    // private fun getClientNotifyID(clientID: Int): Int {
    //     return clientID + NOTIFY_ID_OFFSET
    // }
    //
    // fun cancelNotification(clientID: Int) {
    //     notificationManager.cancel(getClientNotifyID(clientID))
    // }
    //
    // private fun setTextNotification(_title: String?, _text: String?) {
    //     val title = _title ?: DEFAULT_NOTIFY_TITLE
    //     val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
    //     val notification = notificationBuilder
    //         .clearActions()
    //         .setStyle(null)
    //         .setContentTitle(title)
    //         .setContentText(text)
    //         .build()
    //     notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    // }
}
