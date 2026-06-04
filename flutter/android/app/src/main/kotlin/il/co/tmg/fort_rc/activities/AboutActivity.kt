package il.co.tmg.fort_rc.activities

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.carriez.flutter_hbb.databinding.ActivityAboutBinding
import il.co.tmg.fort_rc.KnoxService

class AboutActivity : Activity() {
    private lateinit var binding: ActivityAboutBinding
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshInterval = 2000L
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private val refreshRunnable = Runnable { refreshStatus() }

    private fun refreshStatus() {
        if (isPaused) return
        val state = KnoxService.currentSessionState
        binding.sessionStateText.text = if (state != null)
            "Session State: ${state.status}"
        else
            "No active session"
        refreshHandler.postDelayed(refreshRunnable, refreshInterval)
    }
}
