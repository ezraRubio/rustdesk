package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "RustDesk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
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
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }
                    if (authorized) {
                        if (!isFileTransfer && !isStart) {
                            startCapture()
                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(mediaProjection)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(mediaProjection)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture()
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    /**
     * Get MDM managed configuration from RestrictionsManager.
     * This reads configuration pushed via MDM console.
     */
    private fun getMdmConfiguration(): String {
        return try {
            val restrictionsManager = getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            val restrictions = restrictionsManager?.applicationRestrictions
            if (restrictions == null || restrictions.isEmpty) {
                Log.d(logTag, "No MDM restrictions found")
                ""
            } else {
                Log.d(logTag, "MDM restrictions found, building configuration")
                buildConfigFromRestrictions(restrictions)
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error reading MDM configuration: ${e.message}")
            ""
        }
    }

    /**
     * Build JSON configuration from MDM restrictions bundle.
     * Format matches the custom client config expected by read_custom_client().
     */
    private fun buildConfigFromRestrictions(restrictions: Bundle): String {
        val config = JSONObject()
        val defaultSettings = JSONObject()
        val overrideSettings = JSONObject()

        return try {
            // ========================================
            // Top-Level Settings (HARD_SETTINGS)
            // ========================================
            restrictions.getString("app_name")?.takeIf { it.isNotEmpty() }?.let {
                config.put("app-name", it)
            }
            restrictions.getString("hardcoded_password")?.takeIf { it.isNotEmpty() }?.let {
                config.put("password", it)
            }

            // ========================================
            // Server Configuration
            // ========================================
            restrictions.getString("custom_rendezvous_server")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("custom-rendezvous-server", it)
            }
            restrictions.getString("api_server")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("api-server", it)
            }
            restrictions.getString("relay_server")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("relay-server", it)
            }
            restrictions.getString("server_key")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("key", it)
            }
            if (restrictions.containsKey("allow_websocket")) {
                defaultSettings.put("allow-websocket", if (restrictions.getBoolean("allow_websocket", false)) "Y" else "N")
            }
            restrictions.getString("ice_servers")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("ice-servers", it)
            }
            restrictions.getString("direct_server")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("direct-server", it)
            }
            restrictions.getString("direct_access_port")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("direct-access-port", it)
            }
            if (restrictions.containsKey("disable_udp")) {
                defaultSettings.put("disable-udp", if (restrictions.getBoolean("disable_udp", false)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_insecure_tls_fallback")) {
                defaultSettings.put("allow-insecure-tls-fallback", if (restrictions.getBoolean("allow_insecure_tls_fallback", false)) "Y" else "N")
            }

            // ========================================
            // Security & Access Settings
            // ========================================
            restrictions.getString("access_mode")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("access-mode", it)
            }
            restrictions.getString("approve_mode")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("approve-mode", it)
            }
            restrictions.getString("verification_method")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("verification-method", it)
            }
            restrictions.getString("whitelist")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("whitelist", it)
            }
            restrictions.getString("temporary_password_length")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("temporary-password-length", it)
            }
            if (restrictions.containsKey("allow_numeric_one_time_password")) {
                defaultSettings.put("allow-numeric-one-time-password", if (restrictions.getBoolean("allow_numeric_one_time_password", false)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_trusted_devices")) {
                defaultSettings.put("enable-trusted-devices", if (restrictions.getBoolean("enable_trusted_devices", false)) "Y" else "N")
            }

            // ========================================
            // Permission Controls (KEYS_SETTINGS)
            // ========================================
            if (restrictions.containsKey("enable_keyboard")) {
                defaultSettings.put("enable-keyboard", if (restrictions.getBoolean("enable_keyboard", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_clipboard")) {
                defaultSettings.put("enable-clipboard", if (restrictions.getBoolean("enable_clipboard", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_file_transfer")) {
                defaultSettings.put("enable-file-transfer", if (restrictions.getBoolean("enable_file_transfer", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_camera")) {
                defaultSettings.put("enable-camera", if (restrictions.getBoolean("enable_camera", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_terminal")) {
                defaultSettings.put("enable-terminal", if (restrictions.getBoolean("enable_terminal", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_remote_printer")) {
                defaultSettings.put("enable-remote-printer", if (restrictions.getBoolean("enable_remote_printer", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_audio")) {
                defaultSettings.put("enable-audio", if (restrictions.getBoolean("enable_audio", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_tunnel")) {
                defaultSettings.put("enable-tunnel", if (restrictions.getBoolean("enable_tunnel", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_remote_restart")) {
                defaultSettings.put("enable-remote-restart", if (restrictions.getBoolean("enable_remote_restart", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_record_session")) {
                defaultSettings.put("enable-record-session", if (restrictions.getBoolean("enable_record_session", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_block_input")) {
                defaultSettings.put("enable-block-input", if (restrictions.getBoolean("enable_block_input", true)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_remote_config_modification")) {
                defaultSettings.put("allow-remote-config-modification", if (restrictions.getBoolean("allow_remote_config_modification", false)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_lan_discovery")) {
                defaultSettings.put("enable-lan-discovery", if (restrictions.getBoolean("enable_lan_discovery", true)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_auto_disconnect")) {
                defaultSettings.put("allow-auto-disconnect", if (restrictions.getBoolean("allow_auto_disconnect", true)) "Y" else "N")
            }
            restrictions.getString("auto_disconnect_timeout")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("auto-disconnect-timeout", it)
            }
            if (restrictions.containsKey("allow_only_conn_window_open")) {
                defaultSettings.put("allow-only-conn-window-open", if (restrictions.getBoolean("allow_only_conn_window_open", false)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_auto_record_incoming")) {
                defaultSettings.put("allow-auto-record-incoming", if (restrictions.getBoolean("allow_auto_record_incoming", false)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_auto_record_outgoing")) {
                defaultSettings.put("allow-auto-record-outgoing", if (restrictions.getBoolean("allow_auto_record_outgoing", false)) "Y" else "N")
            }
            restrictions.getString("video_save_directory")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("video-save-directory", it)
            }
            if (restrictions.containsKey("enable_abr")) {
                defaultSettings.put("enable-abr", if (restrictions.getBoolean("enable_abr", true)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_remove_wallpaper")) {
                defaultSettings.put("allow-remove-wallpaper", if (restrictions.getBoolean("allow_remove_wallpaper", false)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_always_software_render")) {
                defaultSettings.put("allow-always-software-render", if (restrictions.getBoolean("allow_always_software_render", false)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_linux_headless")) {
                defaultSettings.put("allow-linux-headless", if (restrictions.getBoolean("allow_linux_headless", false)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_hwcodec")) {
                defaultSettings.put("enable-hwcodec", if (restrictions.getBoolean("enable_hwcodec", true)) "Y" else "N")
            }

            // ========================================
            // Display Settings (KEYS_DISPLAY_SETTINGS)
            // ========================================
            if (restrictions.containsKey("view_only")) {
                defaultSettings.put("view-only", if (restrictions.getBoolean("view_only", false)) "Y" else "N")
            }
            if (restrictions.containsKey("show_monitors_toolbar")) {
                defaultSettings.put("show-monitors-toolbar", if (restrictions.getBoolean("show_monitors_toolbar", true)) "Y" else "N")
            }
            if (restrictions.containsKey("collapse_toolbar")) {
                defaultSettings.put("collapse-toolbar", if (restrictions.getBoolean("collapse_toolbar", false)) "Y" else "N")
            }
            if (restrictions.containsKey("show_remote_cursor")) {
                defaultSettings.put("show-remote-cursor", if (restrictions.getBoolean("show_remote_cursor", true)) "Y" else "N")
            }
            if (restrictions.containsKey("follow_remote_cursor")) {
                defaultSettings.put("follow-remote-cursor", if (restrictions.getBoolean("follow_remote_cursor", false)) "Y" else "N")
            }
            if (restrictions.containsKey("follow_remote_window")) {
                defaultSettings.put("follow-remote-window", if (restrictions.getBoolean("follow_remote_window", false)) "Y" else "N")
            }
            if (restrictions.containsKey("zoom_cursor")) {
                defaultSettings.put("zoom-cursor", if (restrictions.getBoolean("zoom_cursor", false)) "Y" else "N")
            }
            if (restrictions.containsKey("show_quality_monitor")) {
                defaultSettings.put("show-quality-monitor", if (restrictions.getBoolean("show_quality_monitor", false)) "Y" else "N")
            }
            if (restrictions.containsKey("disable_audio")) {
                defaultSettings.put("disable-audio", if (restrictions.getBoolean("disable_audio", false)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_file_copy_paste")) {
                defaultSettings.put("enable-file-copy-paste", if (restrictions.getBoolean("enable_file_copy_paste", true)) "Y" else "N")
            }
            if (restrictions.containsKey("disable_clipboard")) {
                defaultSettings.put("disable-clipboard", if (restrictions.getBoolean("disable_clipboard", false)) "Y" else "N")
            }
            if (restrictions.containsKey("lock_after_session_end")) {
                defaultSettings.put("lock-after-session-end", if (restrictions.getBoolean("lock_after_session_end", false)) "Y" else "N")
            }
            if (restrictions.containsKey("privacy_mode")) {
                defaultSettings.put("privacy-mode", if (restrictions.getBoolean("privacy_mode", false)) "Y" else "N")
            }
            if (restrictions.containsKey("touch_mode")) {
                defaultSettings.put("touch-mode", if (restrictions.getBoolean("touch_mode", false)) "Y" else "N")
            }
            if (restrictions.containsKey("i444")) {
                defaultSettings.put("i444", if (restrictions.getBoolean("i444", false)) "Y" else "N")
            }
            if (restrictions.containsKey("reverse_mouse_wheel")) {
                defaultSettings.put("reverse-mouse-wheel", if (restrictions.getBoolean("reverse_mouse_wheel", false)) "Y" else "N")
            }
            if (restrictions.containsKey("swap_left_right_mouse")) {
                defaultSettings.put("swap-left-right-mouse", if (restrictions.getBoolean("swap_left_right_mouse", false)) "Y" else "N")
            }
            if (restrictions.containsKey("displays_as_individual_windows")) {
                defaultSettings.put("displays-as-individual-windows", if (restrictions.getBoolean("displays_as_individual_windows", false)) "Y" else "N")
            }
            if (restrictions.containsKey("use_all_my_displays_for_the_remote_session")) {
                defaultSettings.put("use-all-my-displays-for-the-remote-session", if (restrictions.getBoolean("use_all_my_displays_for_the_remote_session", false)) "Y" else "N")
            }
            restrictions.getString("view_style")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("view-style", it)
            }
            if (restrictions.containsKey("terminal_persistent")) {
                defaultSettings.put("terminal-persistent", if (restrictions.getBoolean("terminal_persistent", false)) "Y" else "N")
            }
            restrictions.getString("scroll_style")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("scroll-style", it)
            }
            restrictions.getString("edge_scroll_edge_thickness")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("edge-scroll-edge-thickness", it)
            }
            restrictions.getString("image_quality")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("image-quality", it)
            }
            restrictions.getString("custom_image_quality")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("custom-image-quality", it)
            }
            restrictions.getString("custom_fps")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("custom-fps", it)
            }
            restrictions.getString("codec_preference")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("codec-preference", it)
            }
            if (restrictions.containsKey("sync_init_clipboard")) {
                defaultSettings.put("sync-init-clipboard", if (restrictions.getBoolean("sync_init_clipboard", false)) "Y" else "N")
            }
            restrictions.getString("trackpad_speed")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("trackpad-speed", it)
            }
            if (restrictions.containsKey("show_virtual_mouse")) {
                defaultSettings.put("show-virtual-mouse", if (restrictions.getBoolean("show_virtual_mouse", false)) "Y" else "N")
            }
            if (restrictions.containsKey("show_virtual_joystick")) {
                defaultSettings.put("show-virtual-joystick", if (restrictions.getBoolean("show_virtual_joystick", false)) "Y" else "N")
            }

            // ========================================
            // Local Settings (KEYS_LOCAL_SETTINGS)
            // ========================================
            restrictions.getString("theme")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("theme", it)
            }
            restrictions.getString("lang")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("lang", it)
            }
            if (restrictions.containsKey("enable_confirm_closing_tabs")) {
                defaultSettings.put("enable-confirm-closing-tabs", if (restrictions.getBoolean("enable_confirm_closing_tabs", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_open_new_connections_in_tabs")) {
                defaultSettings.put("enable-open-new-connections-in-tabs", if (restrictions.getBoolean("enable_open_new_connections_in_tabs", true)) "Y" else "N")
            }
            if (restrictions.containsKey("use_texture_render")) {
                defaultSettings.put("use-texture-render", if (restrictions.getBoolean("use_texture_render", true)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_d3d_render")) {
                defaultSettings.put("allow-d3d-render", if (restrictions.getBoolean("allow_d3d_render", true)) "Y" else "N")
            }
            if (restrictions.containsKey("sync_ab_with_recent_sessions")) {
                defaultSettings.put("sync-ab-with-recent-sessions", if (restrictions.getBoolean("sync_ab_with_recent_sessions", false)) "Y" else "N")
            }
            if (restrictions.containsKey("sync_ab_tags")) {
                defaultSettings.put("sync-ab-tags", if (restrictions.getBoolean("sync_ab_tags", false)) "Y" else "N")
            }
            if (restrictions.containsKey("filter_ab_by_intersection")) {
                defaultSettings.put("filter-ab-by-intersection", if (restrictions.getBoolean("filter_ab_by_intersection", false)) "Y" else "N")
            }
            restrictions.getString("remote_menubar_drag_left")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("remote-menubar-drag-left", it)
            }
            restrictions.getString("remote_menubar_drag_right")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("remote-menubar-drag-right", it)
            }
            if (restrictions.containsKey("hide_ab_tags_panel")) {
                defaultSettings.put("hideAbTagsPanel", if (restrictions.getBoolean("hide_ab_tags_panel", false)) "Y" else "N")
            }
            restrictions.getString("flutter_remote_menubar_state")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-remote-menubar-state", it)
            }
            restrictions.getString("flutter_peer_sorting")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-peer-sorting", it)
            }
            restrictions.getString("flutter_peer_tab_index")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-peer-tab-index", it)
            }
            restrictions.getString("flutter_peer_tab_order")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-peer-tab-order", it)
            }
            restrictions.getString("flutter_peer_tab_visible")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-peer-tab-visible", it)
            }
            restrictions.getString("flutter_peer_card_ui_type")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-peer-card-ui-type", it)
            }
            restrictions.getString("flutter_current_ab_name")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("flutter-current-ab-name", it)
            }
            if (restrictions.containsKey("disable_floating_window")) {
                defaultSettings.put("disable-floating-window", if (restrictions.getBoolean("disable_floating_window", false)) "Y" else "N")
            }
            restrictions.getString("floating_window_size")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("floating-window-size", it)
            }
            if (restrictions.containsKey("floating_window_untouchable")) {
                defaultSettings.put("floating-window-untouchable", if (restrictions.getBoolean("floating_window_untouchable", false)) "Y" else "N")
            }
            restrictions.getString("floating_window_transparency")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("floating-window-transparency", it)
            }
            restrictions.getString("floating_window_svg")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("floating-window-svg", it)
            }
            if (restrictions.containsKey("keep_screen_on")) {
                defaultSettings.put("keep-screen-on", if (restrictions.getBoolean("keep_screen_on", true)) "Y" else "N")
            }
            if (restrictions.containsKey("keep_awake_during_outgoing_sessions")) {
                defaultSettings.put("keep-awake-during-outgoing-sessions", if (restrictions.getBoolean("keep_awake_during_outgoing_sessions", true)) "Y" else "N")
            }
            if (restrictions.containsKey("disable_group_panel")) {
                defaultSettings.put("disable-group-panel", if (restrictions.getBoolean("disable_group_panel", false)) "Y" else "N")
            }
            if (restrictions.containsKey("disable_discovery_panel")) {
                defaultSettings.put("disable-discovery-panel", if (restrictions.getBoolean("disable_discovery_panel", false)) "Y" else "N")
            }
            if (restrictions.containsKey("pre_elevate_service")) {
                defaultSettings.put("pre-elevate-service", if (restrictions.getBoolean("pre_elevate_service", false)) "Y" else "N")
            }

            // ========================================
            // Built-in Settings (KEYS_BUILDIN_SETTINGS)
            // ========================================
            restrictions.getString("display_name")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("display-name", it)
            }
            restrictions.getString("preset_device_group_name")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-device-group-name", it)
            }
            restrictions.getString("preset_username")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-user-name", it)
            }
            restrictions.getString("preset_strategy_name")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-strategy-name", it)
            }
            if (restrictions.containsKey("remove_preset_password_warning")) {
                defaultSettings.put("remove-preset-password-warning", if (restrictions.getBoolean("remove_preset_password_warning", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_security_settings")) {
                defaultSettings.put("hide-security-settings", if (restrictions.getBoolean("hide_security_settings", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_network_settings")) {
                defaultSettings.put("hide-network-settings", if (restrictions.getBoolean("hide_network_settings", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_server_settings")) {
                defaultSettings.put("hide-server-settings", if (restrictions.getBoolean("hide_server_settings", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_proxy_settings")) {
                defaultSettings.put("hide-proxy-settings", if (restrictions.getBoolean("hide_proxy_settings", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_remote_printer_settings")) {
                defaultSettings.put("hide-remote-printer-settings", if (restrictions.getBoolean("hide_remote_printer_settings", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_websocket_settings")) {
                defaultSettings.put("hide-websocket-settings", if (restrictions.getBoolean("hide_websocket_settings", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_username_on_card")) {
                defaultSettings.put("hide-username-on-card", if (restrictions.getBoolean("hide_username_on_card", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_help_cards")) {
                defaultSettings.put("hide-help-cards", if (restrictions.getBoolean("hide_help_cards", false)) "Y" else "N")
            }
            restrictions.getString("default_connect_password")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("default-connect-password", it)
            }
            if (restrictions.containsKey("hide_tray")) {
                defaultSettings.put("hide-tray", if (restrictions.getBoolean("hide_tray", false)) "Y" else "N")
            }
            restrictions.getString("one_way_clipboard_redirection")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("one-way-clipboard-redirection", it)
            }
            if (restrictions.containsKey("allow_logon_screen_password")) {
                defaultSettings.put("allow-logon-screen-password", if (restrictions.getBoolean("allow_logon_screen_password", false)) "Y" else "N")
            }
            restrictions.getString("one_way_file_transfer")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("one-way-file-transfer", it)
            }
            if (restrictions.containsKey("allow_https_21114")) {
                defaultSettings.put("allow-https-21114", if (restrictions.getBoolean("allow_https_21114", false)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_hostname_as_id")) {
                defaultSettings.put("allow-hostname-as-id", if (restrictions.getBoolean("allow_hostname_as_id", false)) "Y" else "N")
            }
            if (restrictions.containsKey("register_device")) {
                defaultSettings.put("register-device", if (restrictions.getBoolean("register_device", false)) "Y" else "N")
            }
            if (restrictions.containsKey("hide_powered_by_me")) {
                defaultSettings.put("hide-powered-by-me", if (restrictions.getBoolean("hide_powered_by_me", false)) "Y" else "N")
            }
            if (restrictions.containsKey("main_window_always_on_top")) {
                defaultSettings.put("main-window-always-on-top", if (restrictions.getBoolean("main_window_always_on_top", false)) "Y" else "N")
            }
            restrictions.getString("file_transfer_max_files")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("file-transfer-max-files", it)
            }
            if (restrictions.containsKey("disable_change_permanent_password")) {
                defaultSettings.put("disable-change-permanent-password", if (restrictions.getBoolean("disable_change_permanent_password", false)) "Y" else "N")
            }
            if (restrictions.containsKey("disable_change_id")) {
                defaultSettings.put("disable-change-id", if (restrictions.getBoolean("disable_change_id", false)) "Y" else "N")
            }
            if (restrictions.containsKey("disable_unlock_pin")) {
                defaultSettings.put("disable-unlock-pin", if (restrictions.getBoolean("disable_unlock_pin", false)) "Y" else "N")
            }

            // ========================================
            // Preset Address Book Settings
            // ========================================
            restrictions.getString("preset_address_book_name")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-address-book-name", it)
            }
            restrictions.getString("preset_address_book_tag")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-address-book-tag", it)
            }
            restrictions.getString("preset_address_book_alias")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-address-book-alias", it)
            }
            restrictions.getString("preset_address_book_password")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-address-book-password", it)
            }
            restrictions.getString("preset_address_book_note")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-address-book-note", it)
            }
            restrictions.getString("preset_device_username")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-device-username", it)
            }
            restrictions.getString("preset_device_name")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-device-name", it)
            }
            restrictions.getString("preset_note")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("preset-note", it)
            }

            // ========================================
            // Proxy Settings
            // ========================================
            restrictions.getString("proxy_url")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("proxy-url", it)
            }
            restrictions.getString("proxy_username")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("proxy-username", it)
            }
            restrictions.getString("proxy_password")?.takeIf { it.isNotEmpty() }?.let {
                defaultSettings.put("proxy-password", it)
            }

            // ========================================
            // Miscellaneous Settings
            // ========================================
            if (restrictions.containsKey("enable_check_update")) {
                defaultSettings.put("enable-check-update", if (restrictions.getBoolean("enable_check_update", true)) "Y" else "N")
            }
            if (restrictions.containsKey("allow_auto_update")) {
                defaultSettings.put("allow-auto-update", if (restrictions.getBoolean("allow_auto_update", false)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_directx_capture")) {
                defaultSettings.put("enable-directx-capture", if (restrictions.getBoolean("enable_directx_capture", true)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_android_software_encoding_half_scale")) {
                defaultSettings.put("enable-android-software-encoding-half-scale", if (restrictions.getBoolean("enable_android_software_encoding_half_scale", false)) "Y" else "N")
            }
            if (restrictions.containsKey("av1_test")) {
                defaultSettings.put("av1-test", if (restrictions.getBoolean("av1_test", false)) "Y" else "N")
            }
            if (restrictions.containsKey("enable_flutter_http_on_rust")) {
                defaultSettings.put("enable-flutter-http-on-rust", if (restrictions.getBoolean("enable_flutter_http_on_rust", false)) "Y" else "N")
            }

            // ========================================
            // Build final config JSON
            // ========================================
            if (defaultSettings.length() > 0) {
                config.put("default-settings", defaultSettings)
            }
            if (overrideSettings.length() > 0) {
                config.put("override-settings", overrideSettings)
            }

            val result = if (config.length() > 0) config.toString() else ""
            if (result.isNotEmpty()) {
                Log.d(logTag, "MDM configuration built successfully")
            }
            result
        } catch (e: JSONException) {
            Log.e(logTag, "Error building MDM config JSON: ${e.message}")
            ""
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        
        // Get MDM managed configuration if available
        val mdmConfig = getMdmConfiguration()
        FFI.startServer(configPath, mdmConfig)

        createForegroundNotification()
    }

    override fun onDestroy() {
        checkMediaPermission()
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                FFI.startService()
            }
            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                checkMediaPermission()
                _isReady = true
            } ?: let {
                Log.d(logTag, "getParcelableExtra intent null, invoke requestMediaProjection")
                requestMediaProjection()
            }
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            // TODO
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    4
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        try {
                            // If not call acquireLatestImage, listener will not be called again
                            imageReader.acquireLatestImage().use { image ->
                                if (image == null || !isStart) return@setOnImageAvailableListener
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                FFI.onVideoFrameUpdate(buffer)
                            }
                        } catch (ignored: java.lang.Exception) {
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(mediaProjection)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(mediaProjection)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        surface = createSurface()

        if (useVP9) {
            startVP9VideoRecorder(mediaProjection!!)
        } else {
            startRawVideoRecorder(mediaProjection!!)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!audioRecordHandle.createAudioRecorder(false, mediaProjection)) {
                Log.d(logTag, "createAudioRecorder fail")
            } else {
                Log.d(logTag, "audio recorder start")
                audioRecordHandle.startAudioRecorder()
            }
        }
        checkMediaPermission()
        _isStart = true
        FFI.setFrameRawEnable("video",true)
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        return true
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        mediaProjection = null
        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection) {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return
        }
        createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection) {
        createMediaCodec()
        videoEncoder?.let {
            surface = it.createInputSurface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface!!.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            it.setCallback(cb)
            it.start()
            createOrSetVirtualDisplay(mp, surface!!)
        }
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface) {
        try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "RustDeskVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, null, null
                )
            }
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            requestMediaProjection()
        }
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            // .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            // .addAction(R.drawable.check_blue, "check", genLoginRequestPendingIntent(true))
            // .addAction(R.drawable.close_red, "close", genLoginRequestPendingIntent(false))
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }
}
