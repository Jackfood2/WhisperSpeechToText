package com.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvMeetingStatus: TextView
    private lateinit var tvMeetingPath: TextView
    private lateinit var tvQueue: TextView
    private lateinit var progressTranscribe: ProgressBar
    private lateinit var radioMode: RadioGroup

    private val permRequestCode = 100

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        override fun onProgress(pct: Int) {
            runOnUiThread {
                progressTranscribe.progress = pct
                tvProgressPctText(pct)
            }
        }
    }

    private fun tvProgressPctText(pct: Int) {
        findViewById<TextView>(R.id.tvProgressPct).text =
            if (pct == 0) "0% - idle" else if (TranscriptionQueue.isActive()) {
                val (cur, total) = TranscriptionQueue.batchPosition(); "$pct% ($cur/$total)"
            } else "$pct%"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMeetingStatus = findViewById(R.id.tvMeetingStatus)
        tvMeetingPath = findViewById(R.id.tvMeetingPath)
        tvQueue = findViewById(R.id.tvQueue)
        progressTranscribe = findViewById(R.id.progressTranscribe)
        radioMode = findViewById(R.id.radioMode)

        // gear icon -> comprehensive settings
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnEnableIME).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnPickIME).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnStartMeeting).setOnClickListener {
            if (!hasPermissions()) { requestPermissions(); return@setOnClickListener }
            val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
            val lang = prefs.getString("lang", "auto") ?: "auto"
            val model = prefs.getString("model", "small") ?: "small"
            val mode = if (radioMode.checkedRadioButtonId == R.id.radioType) "type" else "txt"
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "START"
            intent.putExtra("model", model)
            intent.putExtra("lang", lang)
            intent.putExtra("mode", mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            tvMeetingStatus.text = "Recording ($mode mode)... tap Stop (continues with screen off)"
        }

        findViewById<Button>(R.id.btnStopMeeting).setOnClickListener {
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "STOP"
            startService(intent)
            tvMeetingStatus.text = "Stopping... transcript saving (wait for queue)"
        }

        findViewById<Button>(R.id.btnDonate).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.paypal.com/paypalme/jackfood2004"))) } catch (_: Exception) { Toast.makeText(this, "PayPal: jackfood2004@gmail.com", Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener { startActivity(Intent(this, PrivacyDashboardActivity::class.java)) }
        findViewById<Button>(R.id.btnPauseQueue).setOnClickListener {
            val paused = TranscriptionQueue.togglePause()
            (it as Button).text = if (paused) "Resume" else "Pause"
        }
        findViewById<Button>(R.id.btnClearQueue).setOnClickListener {
            val n = TranscriptionQueue.clearQueue()
            Toast.makeText(this, "Cleared $n", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRetryQueue).setOnClickListener {
            val c = TranscriptionQueue.failedCount()
            if (c == 0) Toast.makeText(this, "No failed", Toast.LENGTH_SHORT).show() else { TranscriptionQueue.retryFailed(); Toast.makeText(this, "Retrying $c", Toast.LENGTH_SHORT).show() }
        }

        TranscriptionQueue.addListener(pqListener)
        progressTranscribe.progress = TranscriptionQueue.progress()
        tvProgressPctText(TranscriptionQueue.progress())

        requestPermissions()

        // preload last-used model
        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        val lastModel = prefs.getString("model", "small") ?: "small"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val mf = ModelManager.modelFile(applicationContext, lastModel)
                    if (mf.exists() && mf.length() > 1_000_000 && !WhisperEngine.isLoaded(mf.absolutePath)) WhisperEngine.ensureModel(mf.absolutePath)
                }
            } catch (_: Throwable) {}
        }

        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    tvQueue.text = TranscriptionQueue.status()
                    val lastPath = prefs.getString("last_transcript_path", "")
                    if (!lastPath.isNullOrEmpty()) tvMeetingPath.text = "Last: $lastPath"
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Thread { WhisperEngine.unloadIfIdle() }.apply { isDaemon = true; start() }
        }
    }

    override fun onDestroy() {
        TranscriptionQueue.removeListener(pqListener)
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return mic && notif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), permRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permRequestCode && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Mic permission required", Toast.LENGTH_LONG).show()
        }
    }
}
