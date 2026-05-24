package il.co.tmg.fort_rc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.util.Log
import ffi.FFI
import io.flutter.embedding.android.FlutterActivity
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.content.Context
import android.os.PowerManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import org.json.JSONArray
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
import com.carriez.flutter_hbb.LEFT_DOWN
import com.carriez.flutter_hbb.R
import com.carriez.flutter_hbb.SCREEN_INFO
import com.carriez.flutter_hbb.MainActivity
import com.carriez.flutter_hbb.getScreenSize
import android.content.ComponentName
import android.content.pm.PackageManager

/**
 * KnoxService is a foreground service that owns the full fort rc session lifecycle.
 *
 * It is instead of MainService when unattended. As such:
 *   1. Normal (attended): User → MainActivity → MainService
 *   2. Fort CT (unattended): Fort CT → DispatcherActivity → KnoxService → KnoxCapturer
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
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    if (!isStart) {
                        _isStart = true
                        val capturer = knoxCapturer
                        if (capturer != null && isReady) {
                          capturer.startCapture()
                          Log.w(LOG_TAG, "client connected")
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                Log.w(LOG_TAG, "Voice call not supported in Knox session, ignoring")
            }
            "stop_capture" -> {
                stopSession("stop_capture signal received from client")
            }
            "half_scale" -> {
                Log.d(LOG_TAG, "half_scale received from rust, not supported on this path, checking orientation instead")
                setOrientation()
                // val halfScale = arg1.toBoolean()
                // if (isHalfScale != halfScale) {
                //     isHalfScale = halfScale
                // }
            }
            else -> { }
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
        var isReady: Boolean = false
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
    private var isHalfScale: Boolean? = true
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
        setCodec()

        // Go foreground
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Connecting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        // Disable MainActivity
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
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
        stopSession("KnoxService destroyed")
        knoxCapturer = null
        serviceLooper?.quitSafely()

        // Reenable MainActivity
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        super.onDestroy()
    }

    // ========================================================================
    // Session callbacks — called by KnoxCapturer
    // ========================================================================

    fun onSessionReadyForConnection() {
        //This allows KnoxService to act on rust's add_connection signal
        Log.i(LOG_TAG, "Session ready")
        isReady = true
    }

    fun onSessionEnded(reason: String) {
        Log.w(LOG_TAG, "Session ended: $reason")
        isReady = false
        FFI.setFrameRawEnable("video", false)
        knoxCapturer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
    // Notification
    //
    // needed so this service can remain in the foreground
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

    // ========================================================================
    // Codec
    //
    // This sets the possible codecs available by the device for rust encoder.
    // ========================================================================

    private fun setCodec() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first
        var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                // https://chromium.googlesource.com/external/webrtc/+/HEAD/sdk/android/src/java/org/webrtc/MediaCodecUtils.java#29
                // https://chromium.googlesource.com/external/webrtc/+/master/sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java#229
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                    hw = false
                } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                    hw = true
                }
            }
            if (hw != true) {
                return@forEach
            }
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) { // "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"
                    mime_type = type;
                }
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder) {
                    // Encoder's max_height and max_width are interchangeable
                    if (!caps.videoCapabilities.isSizeSupported(w,h) && !caps.videoCapabilities.isSizeSupported(h,w)) {
                        return@forEach
                    }
                }
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface);
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) {
                    return@forEach
                }
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                }
                if (!codec.isEncoder) {
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
        val result = JSONObject()
        result.put("version", Build.VERSION.SDK_INT)
        result.put("w", w)
        result.put("h", h)
        result.put("codecs", codecArray)
        FFI.setCodecInfo(result.toString())
    }

    private fun setOrientation() {
     val orientation = resources.configuration.orientation 
     Log.i(LOG_TAG, "orientation: $orientation")
    }

}
