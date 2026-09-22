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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    private lateinit var tvMeetingStatus: TextView
    private lateinit var tvMeetingPath: TextView
    private lateinit var tvQueue: TextView
    private lateinit var progressTranscribe: ProgressBar
    private lateinit var btnStartMeeting: Button
    private lateinit var btnStopMeeting: Button
    private lateinit var tvLiveTranscript: TextView
    private lateinit var transcriptScrollView: NestedScrollView

    private var previewPath: String? = null
    private var shownTranscriptBytes = 0L
    private var pendingPartialBytes = ByteArray(0)
    private val previewText =
        android.text.SpannableStringBuilder()

    private fun refreshMeetingButtons() {
        val running = MeetingRecordService.isRunning
        val stopping = MeetingRecordService.isStopping
        btnStartMeeting.isEnabled = !running && !stopping
        btnStopMeeting.isEnabled = running && !stopping
    }

    private fun notesDir(): File {
        return FullAudioSaver.privateNotesDir(this)
    }

    private fun openRecordingsFolder() {
        val dir = notesDir()
        for (pkg in listOf("com.sec.android.app.myfiles", "com.google.android.documentsui")) {
            val ok = runCatching {
                startActivity(packageManager.getLaunchIntentForPackage(pkg)!!); true
            }.getOrDefault(false)
            if (ok) {
                Toast.makeText(this, "Recordings in app storage: ${dir.absolutePath}", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(this, "Recordings at: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }

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
                resetPreview()
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

    private fun resetPreview(newPath: String? = null) {
        previewPath = newPath
        previewText.clear()
        pendingPartialBytes = ByteArray(0)
        shownTranscriptBytes = 0L
        tvLiveTranscript.text = ""
    }

    private fun isTranscriptNearBottom(): Boolean {
        val child = transcriptScrollView.getChildAt(0) ?: return true

        val remaining =
            child.height -
            (
                transcriptScrollView.height +
                transcriptScrollView.scrollY
            )

        return remaining <= 120
    }

    private fun scrollTranscriptToBottom() {
        transcriptScrollView.post {
            transcriptScrollView.fullScroll(
                android.view.View.FOCUS_DOWN
            )
        }
    }

    private fun appendPreviewText(text: String) {
        if (text.isEmpty()) return

        val followBottom = isTranscriptNearBottom()

        previewText.append(text)
        tvLiveTranscript.append(text)

        if (followBottom) {
            scrollTranscriptToBottom()
        }
    }

    private fun updateLivePreview(path: String) {
        try {
            val file = File(path)
            if (!file.isFile) return

            if (previewPath != path) {
                resetPreview(path)
            }

            val currentLength = file.length()

            if (currentLength < shownTranscriptBytes) {
                resetPreview(path)
            }

            if (currentLength == shownTranscriptBytes) {
                return
            }

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(shownTranscriptBytes)

                while (shownTranscriptBytes < currentLength) {
                    val remaining = currentLength - shownTranscriptBytes
                    val wanted = minOf(remaining, 64L * 1024L).toInt()

                    val buffer = ByteArray(wanted)
                    val read = raf.read(buffer)

                    if (read <= 0) break

                    shownTranscriptBytes += read
                    appendPreviewBytes(
                        if (read == buffer.size) buffer else buffer.copyOf(read)
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.w(
                "MainActivity",
                "Preview update failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun appendPreviewBytes(newBytes: ByteArray) {
        val combined =
            ByteArray(pendingPartialBytes.size + newBytes.size)

        System.arraycopy(
            pendingPartialBytes,
            0,
            combined,
            0,
            pendingPartialBytes.size
        )

        System.arraycopy(
            newBytes,
            0,
            combined,
            pendingPartialBytes.size,
            newBytes.size
        )

        var lastNewline = -1

        for (index in combined.indices.reversed()) {
            if (combined[index] == '\n'.code.toByte()) {
                lastNewline = index
                break
            }
        }

        if (lastNewline < 0) {
            pendingPartialBytes = combined
            return
        }

        val completed =
            combined.copyOfRange(
                0,
                lastNewline + 1
            )

        pendingPartialBytes =
            combined.copyOfRange(
                lastNewline + 1,
                combined.size
            )

        val text = completed.toString(Charsets.UTF_8)

        appendPreviewText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WhisperApp.applyTheme(this)
        setContentView(R.layout.activity_main)

        tvMeetingStatus = findViewById(R.id.tvMeetingStatus)
        tvMeetingPath = findViewById(R.id.tvMeetingPath)
        tvLiveTranscript = findViewById(R.id.tvLiveTranscript)
        transcriptScrollView = findViewById(R.id.transcriptScrollView)
        tvLiveTranscript.setTextIsSelectable(true)
        tvLiveTranscript.isLongClickable = true
        tvQueue = findViewById(R.id.tvQueue)
        progressTranscribe = findViewById(R.id.progressTranscribe)
        btnStartMeeting = findViewById(R.id.btnStartMeeting)
        btnStopMeeting = findViewById(R.id.btnStopMeeting)

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

            val missing = SttEngines.describeMissing(this, engine, model)
            if (missing != null) {
                Toast.makeText(this, "$engine: $missing", Toast.LENGTH_LONG).show()
                MeetingRecordService.uiStatus = "$engine: $missing"
                refreshMeetingButtons()
                return@setOnClickListener
            }
            val lang = SttEngines.jobLang(this)
            prefs.edit()
                .remove("last_transcript_path")
                .remove("last_audio_path")
                .apply()

            tvMeetingPath.text = ""
            resetPreview()

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
            try {
                val uri =
                    android.net.Uri.parse(
                        "https://www.paypal.com/paypalme/jackfood2004"
                    )

                startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "PayPal: jackfood2004@gmail.com",
                    Toast.LENGTH_LONG
                ).show()
            }
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

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions()
        }

        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)

        lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    tvQueue.text = TranscriptionQueue.status()
                    refreshMeetingButtons()

                    tvMeetingStatus.text = MeetingRecordService.uiStatus
                    val lastPath = prefs.getString("last_transcript_path", "")
                    val lastAudio = prefs.getString("last_audio_path", "")
                    tvMeetingPath.text = when {
                        !lastPath.isNullOrEmpty() && !lastAudio.isNullOrEmpty() -> "Last: $lastPath\nAudio: $lastAudio"
                        !lastPath.isNullOrEmpty() -> "Last: $lastPath"
                        !lastAudio.isNullOrEmpty() -> "Audio: $lastAudio"
                        else -> ""
                    }
                    if (!lastPath.isNullOrEmpty()) {
                        updateLivePreview(lastPath)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != permRequestCode) return

        permissions.forEachIndexed { index, permission ->
            val granted =
                grantResults.getOrNull(index) ==
                    PackageManager.PERMISSION_GRANTED

            if (!granted) {
                val message = when (permission) {
                    Manifest.permission.RECORD_AUDIO ->
                        "Microphone permission is required for recording"

                    Manifest.permission.POST_NOTIFICATIONS ->
                        "Notifications are disabled. Recording status may not be visible"

                    else ->
                        "A required permission was denied"
                }

                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
