package com.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvMeetingStatus: TextView
    private lateinit var tvMeetingPath: TextView
    private lateinit var tvQueue: TextView
    private lateinit var progressTranscribe: ProgressBar
    private lateinit var btnStartMeeting: Button
    private lateinit var btnStopMeeting: Button

    /**
     * Start and Stop are mutually exclusive: exactly one is enabled at any
     * time. While stopping (background queue drain) both stay disabled so a
     * double-tap can never launch a second session mid-teardown.
     */
    private fun refreshMeetingButtons() {
        val running = MeetingRecordService.isRunning
        val stopping = MeetingRecordService.isStopping
        btnStartMeeting.isEnabled = !running && !stopping
        btnStopMeeting.isEnabled = running && !stopping
    }

    private fun notesDir(): File {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WhisperNotes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Open the recordings folder in the phone's file manager (SAF view with OEM fallbacks). */
    private fun openRecordingsFolder() {
        val dir = notesDir()
        val saf = runCatching {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/WhisperNotes")
            startActivity(Intent(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        }.getOrDefault(false)
        if (saf) return
        for (pkg in listOf("com.sec.android.app.myfiles", "com.google.android.documentsui")) {
            val ok = runCatching {
                startActivity(packageManager.getLaunchIntentForPackage(pkg)!!); true
            }.getOrDefault(false)
            if (ok) {
                Toast.makeText(this, "Open Documents/WhisperNotes", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(this, "Recordings at: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }

    /** Delete ALL transcripts + audio (never models) behind an explicit confirmation. */
    private fun confirmClearRecordings() {
        if (MeetingRecordService.isRunning || MeetingRecordService.isStopping) {
            Toast.makeText(this, "Stop the meeting first", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = notesDir()
        val recs = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("txt", "m4a", "wav") } ?: emptyList()
        val failed = File(dir, "failed").listFiles()?.filter { it.isFile } ?: emptyList()
        val total = recs.size + failed.size
        if (total == 0) {
            Toast.makeText(this, "No recordings to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ALL recordings?")
            .setMessage("Permanently delete $total file(s) - transcripts + audio in WhisperNotes? Downloaded models are NOT touched.")
            .setPositiveButton("Delete ALL") { _, _ ->
                var n = 0
                for (f in recs + failed) { try { if (f.delete()) n++ } catch (_: Exception) {} }
                getSharedPreferences("whisper", MODE_PRIVATE).edit()
                    .remove("last_transcript_path").remove("last_audio_path").apply()
                tvMeetingPath.text = ""
                Toast.makeText(this, "Deleted $n file(s)", Toast.LENGTH_SHORT).show()
                AppLog.i("Main", "cleared $n recording files")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        btnStartMeeting = findViewById(R.id.btnStartMeeting)
        btnStopMeeting = findViewById(R.id.btnStopMeeting)

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

        btnStartMeeting.setOnClickListener {
            if (MeetingRecordService.isRunning || MeetingRecordService.isStopping) {
                Toast.makeText(this, "Meeting already recording", Toast.LENGTH_SHORT).show()
                refreshMeetingButtons()
                return@setOnClickListener
            }
            if (!hasPermissions()) { requestPermissions(); return@setOnClickListener }
            val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
            val engine = SttEngines.current(this)
            val model = prefs.getString("model", "small") ?: "small"
            // Preflight: never record a meeting the engine cannot transcribe.
            // Written to the SERVICE status (not the TextView) because the poll loop
            // redisplays uiStatus every 800ms and would clobber a local message.
            val missing = SttEngines.describeMissing(this, engine, model)
            if (missing != null) {
                Toast.makeText(this, "$engine: $missing", Toast.LENGTH_LONG).show()
                MeetingRecordService.uiStatus = "$engine: $missing"
                refreshMeetingButtons()
                return@setOnClickListener
            }
            val lang = SttEngines.jobLang(this)
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "START"
            intent.putExtra("model", model)
            intent.putExtra("lang", lang)
            intent.putExtra("engine", engine)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            tvMeetingStatus.text = "Recording... tap Stop (continues with screen off)"
            Toast.makeText(this, "Meeting recording started", Toast.LENGTH_SHORT).show()
            refreshMeetingButtons()
        }

        btnStopMeeting.setOnClickListener {
            if (!MeetingRecordService.isRunning) {
                Toast.makeText(this, "Not recording", Toast.LENGTH_SHORT).show()
                refreshMeetingButtons()
                return@setOnClickListener
            }
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "STOP"
            startService(intent)
            tvMeetingStatus.text = "Stopping... transcript saving (wait for queue)"
            Toast.makeText(this, "Stopping - transcript will be saved to Documents/WhisperNotes", Toast.LENGTH_LONG).show()
            refreshMeetingButtons()
        }

        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener { openRecordingsFolder() }
        findViewById<Button>(R.id.btnClearRecords).setOnClickListener { confirmClearRecordings() }

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

        // preload last-used engine + model
        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        val lastModel = prefs.getString("model", "small") ?: "small"
        val lastEngine = prefs.getString("engine", SttEngines.WHISPER) ?: SttEngines.WHISPER
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!SttEngines.isLoaded(applicationContext, lastEngine, lastModel)) {
                        SttEngines.ensureModel(applicationContext, lastEngine, lastModel, SttEngines.jobLang(applicationContext))
                    }
                }
            } catch (_: Throwable) {}
        }

        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    tvQueue.text = TranscriptionQueue.status()
                    refreshMeetingButtons()
                    // Service is the source of truth: flips Recording -> Stopping -> Saved ✓
                    tvMeetingStatus.text = MeetingRecordService.uiStatus
                    val lastPath = prefs.getString("last_transcript_path", "")
                    val lastAudio = prefs.getString("last_audio_path", "")
                    tvMeetingPath.text = when {
                        !lastPath.isNullOrEmpty() && !lastAudio.isNullOrEmpty() -> "Last: $lastPath\nAudio: $lastAudio"
                        !lastPath.isNullOrEmpty() -> "Last: $lastPath"
                        !lastAudio.isNullOrEmpty() -> "Audio: $lastAudio"
                        else -> ""
                    }
                }
            }
        }
        refreshMeetingButtons()
    }

    override fun onResume() {
        super.onResume()
        if (::btnStartMeeting.isInitialized) refreshMeetingButtons()
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
