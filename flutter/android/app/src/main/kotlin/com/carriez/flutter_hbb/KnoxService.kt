package com.carriez.flutter_hbb

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

/**
 * KnoxService is a foreground service that owns the full Fort Control Tower
 * remote-control session lifecycle.
 *
 * It is completely independent from MainService. The two paths are:
 *   1. Normal (attended): User → MainActivity → MainService → MediaProjection
 *   2. Fort CT (unattended): Fort CT → DispatcherActivity → KnoxService → KnoxCapturer
 *
 * MainService reads KnoxService.instance (same-process static) only for input
 * routing — Rust calls @Keep methods on MainService, which forward pointer/key
 * events to KnoxCapturer when a Knox session is active.
 *
 * KnoxService initializes FFI/Rust itself (idempotent), so it does not depend
 * on MainService being started first.
 */
class KnoxService : Service() {

    companion object {
        private const val LOG_TAG = "KnoxService"

        const val ACTION_START = "com.carriez.flutter_hbb.KNOX_START_SESSION"
        const val ACTION_STOP = "com.carriez.flutter_hbb.KNOX_STOP_SESSION"

        private const val CHANNEL_ID = "fort_knox_capture"
        private const val CHANNEL_NAME = "Fort Remote Control"
        private const val NOTIFICATION_ID = 1002

        /**
         * Static instance for same-process access by MainService input routing.
         * Set in onCreate(), cleared in onDestroy().
         */
        @Volatile
        var instance: KnoxService? = null
            private set

        /**
         * Whether a Knox session is currently running and accepting frames.
         * Read by MainService, MainActivity, and Flutter state checks.
         */
        val isActive: Boolean
            get() = instance?.knoxCapturer?.isRunning() == true
    }

    var knoxCapturer: KnoxCapturer? = null
        private set

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(LOG_TAG, "onCreate")

        // Background thread for Knox operations
        HandlerThread("KnoxService", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }

        // Initialize FFI/Rust (idempotent — safe if MainService already called these)
        FFI.init(this)
        val prefs = applicationContext.getSharedPreferences(
            KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE
        )
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

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
                    // TODO(Point 2): teardown current + restart if needed
                    return START_STICKY
                }

                Log.i(LOG_TAG, "Starting Knox session: $sessionId")
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
                Log.i(LOG_TAG, "Stop requested")
                stopSession("stop action received")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(LOG_TAG, "onDestroy")
        knoxCapturer?.stopSession("KnoxService destroyed")
        knoxCapturer = null
        serviceLooper?.quitSafely()
        instance = null
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
        FFI.setFrameRawEnable("video", false)
        knoxCapturer = null
        notifyFlutterStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Stop current session and shut down service.
     * If KnoxCapturer exists, it calls onSessionEnded() which handles cleanup.
     * If already null, we handle cleanup directly.
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
     * Push media/input state to Flutter UI via MainActivity channel.
     * Same-process — direct static access.
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
}
