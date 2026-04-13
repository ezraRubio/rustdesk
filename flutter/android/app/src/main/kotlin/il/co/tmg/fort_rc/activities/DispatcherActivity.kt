package il.co.tmg.fort_rc.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import il.co.tmg.fort_rc.KnoxService

/**
 * Entry point started by Fort Control Tower for remote-control sessions.
 *
 * Pure trampoline: validates intent, starts KnoxService, finishes immediately.
 * All session state and IPC owned by KnoxCapturer inside KnoxService.
 */
class DispatcherActivity : Activity() {

    companion object {
        private const val LOG_TAG = "DispatcherActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionId = intent.getStringExtra("remote_session_id")
        Log.d(LOG_TAG, "onCreate: remoteSessionId=$sessionId")

        if (sessionId.isNullOrBlank()) {
            Log.e(LOG_TAG, "No remote_session_id in intent, finishing")
            finish()
            return
        }

        val serviceIntent = Intent(this, KnoxService::class.java).apply {
            action = KnoxService.ACTION_START
            putExtra("remote_session_id", sessionId)
        }
        startForegroundService(serviceIntent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newSessionId = intent?.getStringExtra("remote_session_id")
        Log.w(LOG_TAG, "onNewIntent: new session $newSessionId — dispatching to KnoxService")

        if (!newSessionId.isNullOrBlank()) {
            val serviceIntent = Intent(this, KnoxService::class.java).apply {
                action = KnoxService.ACTION_START
                putExtra("remote_session_id", newSessionId)
            }
            startForegroundService(serviceIntent)
        }
        finish()
    }
}
