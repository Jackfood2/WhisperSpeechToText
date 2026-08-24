package com.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioRecord
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class WhisperKeyboardService : InputMethodService() {

    private companion object { const val TAG = "WhisperIME" }

    private val isRecording = AtomicBoolean(false)
    private var activeRecorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var rootView: View? = null
    private var tvStatus: TextView? = null
    private var tvQueueBadge: TextView? = null
    private var tvPct: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnMic: Button? = null
    private var btnStop: Button? = null
    private var btnPause: Button? = null
    private var btnClear: Button? = null
    private var btnRetry: Button? = null
    private var btnModelTiny: Button? = null
    private var btnModelBase: Button? = null
    private var btnModelSmall: Button? = null
    private var btnModelMedium: Button? = null
    private var btnModeType: Button? = null
    private var btnModeTxt: Button? = null
    private var btnModeBoth: Button? = null
    private val handler = Handler(Looper.getMainLooper())

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        override fun onProgress(pct: Int) {
            handler.post {
                progressBar?.progress = pct
                tvPct?.text = "$pct%"
                // also update status line with live pct
                if (pct in 1..99) tvStatus?.text = "Transcribing... $pct%"
            }
        }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView")
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        rootView = view
        tvStatus = view.findViewById(R.id.tvStatus)
        tvQueueBadge = view.findViewById(R.id.tvQueueBadge)
        tvPct = view.findViewById(R.id.tvProgressPct)
        progressBar = view.findViewById(R.id.progressTranscribe)
        btnMic = view.findViewById(R.id.btnMic)
        btnStop = view.findViewById(R.id.btnStop)
        btnPause = view.findViewById(R.id.btnPause)
        btnClear = view.findViewById(R.id.btnClear)
        btnRetry = view.findViewById(R.id.btnRetry)
        btnModelTiny = view.findViewById(R.id.btnModelTiny)
        btnModelBase = view.findViewById(R.id.btnModelBase)
        btnModelSmall = view.findViewById(R.id.btnModelSmall)
        btnModelMedium = view.findViewById(R.id.btnModelMedium)
        btnModeType = view.findViewById(R.id.btnModeType)
        btnModeTxt = view.findViewById(R.id.btnModeTxt)
        btnModeBoth = view.findViewById(R.id.btnModeBoth)

        btnMic?.setOnClickListener { if (!isRecording.get()) startRecording() }
        btnStop?.setOnClickListener { if (isRecording.get()) stopRecordingAndTranscribe() }
        btnPause?.setOnClickListener {
            val nowPaused = TranscriptionQueue.togglePause()
            btnPause?.text = if (nowPaused) "Resume" else "Pause"
            updateStatus(if (nowPaused) "Queue paused" else "Queue resumed")
            updateQueueBadge()
        }
        btnClear?.setOnClickListener {
            val n = TranscriptionQueue.clearQueue()
            Toast.makeText(this, "Cleared $n queued", Toast.LENGTH_SHORT).show()
            updateStatus("Queue cleared - ready")
            progressBar?.progress = 0; tvPct?.text = "0%"
            updateQueueBadge()
        }
        btnRetry?.setOnClickListener {
            val c = TranscriptionQueue.failedCount()
            if (c == 0) Toast.makeText(this, "No failed recordings", Toast.LENGTH_SHORT).show()
            else {
                TranscriptionQueue.retryFailed()
                Toast.makeText(this, "Retrying $c failed", Toast.LENGTH_SHORT).show()
                updateStatus("Retrying $c failed...")
            }
            updateQueueBadge()
        }
        btnModelTiny?.setOnClickListener { setModel("tiny") }
        btnModelBase?.setOnClickListener { setModel("base") }
        btnModelSmall?.setOnClickListener { setModel("small") }
        btnModelMedium?.setOnClickListener { setModel("medium") }
        btnModeType?.setOnClickListener { setEntryMode("type") }
        btnModeTxt?.setOnClickListener { setEntryMode("txt") }
        btnModeBoth?.setOnClickListener { setEntryMode("both") }

        TranscriptionQueue.addListener(pqListener)
        refreshModelButtons()
        refreshModeButtons()
        updateStatus("Tap Speak to start")
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = "${TranscriptionQueue.progress()}%"
        updateQueueBadge()
        return view
    }

    private fun startImeForeground() {
        try {
            val intent = Intent(this, ImeRecordService::class.java).apply { action = "START" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { Log.w(TAG, "foreground start failed: ${e.message}") }
    }
    private fun stopImeForeground() {
        try {
            val intent = Intent(this, ImeRecordService::class.java).apply { action = "STOP" }
            startService(intent)
        } catch (_: Exception) {}
    }

    private fun setModel(m: String) {
        getSharedPreferences("whisper", MODE_PRIVATE).edit().putString("model", m).apply()
        refreshModelButtons()
        Toast.makeText(this, "Model: $m", Toast.LENGTH_SHORT).show()
        updateStatus("Model: $m - ready")
    }

    private fun setEntryMode(mode: String) {
        getSharedPreferences("whisper", MODE_PRIVATE).edit().putString("entry_mode", mode).apply()
        refreshModeButtons()
        val label = when (mode) { "type" -> "Type into focus"; "txt" -> "Save to TXT"; else -> "Type + Save TXT" }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        updateStatus("$label - ready")
    }

    private fun refreshModelButtons() {
        val cur = getModel()
        val active = 0xFF00B894.toInt()
        val idle = 0xFF636E72.toInt()
        try {
            btnModelTiny?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "tiny") active else idle)
            btnModelBase?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "base") active else idle)
            btnModelSmall?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "small") active else idle)
            btnModelMedium?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "medium") active else idle)
        } catch (_: Exception) {}
    }

    private fun refreshModeButtons() {
        val cur = getEntryMode()
        val active = 0xFF00B894.toInt()
        val idle = 0xFF636E72.toInt()
        try {
            btnModeType?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "type") active else idle)
            btnModeTxt?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "txt") active else idle)
            btnModeBoth?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (cur == "both") active else idle)
        } catch (_: Exception) {}
    }

    private fun getModel(): String = getSharedPreferences("whisper", MODE_PRIVATE).getString("model", "small") ?: "small"
    private fun getLang(): String = getSharedPreferences("whisper", MODE_PRIVATE).getString("lang", "auto") ?: "auto"
    private fun getEntryMode(): String = getSharedPreferences("whisper", MODE_PRIVATE).getString("entry_mode", "type") ?: "type"

    private fun saveToTxtFile(text: String) {
        try {
            val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WhisperNotes")
            docs.mkdirs()
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val f = File(docs, "whisper_${fmt.format(Date())}.txt")
            f.appendText("$text\n")
            Log.i(TAG, "Saved to ${f.absolutePath}")
        } catch (e: Exception) { Log.w(TAG, "save txt failed: ${e.message}") }
    }

    private fun startRecording() {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Need mic permission - open app")
            return
        }
        isRecording.set(true)
        startImeForeground()
        btnMic?.isEnabled = false
        btnStop?.isEnabled = true
        btnMic?.text = "Recording..."
        try { btnMic?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE17055.toInt()) } catch (_: Exception) {}
        updateStatus("Listening... tap Stop when done")
        progressBar?.progress = 0; tvPct?.text = "REC"

        recordThread = Thread {
            var pcmFile: File? = null
            try {
                val recorder = AudioUtils.createRecorder()
                activeRecorder = recorder
                recorder.startRecording()
                pcmFile = File(cacheDir, "ime_rec_${System.currentTimeMillis()}.pcm")
                FileOutputStream(pcmFile).use { fos ->
                    val buffer = ByteArray(4096)
                    while (isRecording.get()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) fos.write(buffer, 0, read)
                        else if (read < 0) break
                    }
                }
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                activeRecorder = null

                if (pcmFile != null && pcmFile.exists() && pcmFile.length() > 2000) {
                    val wavFile = File(cacheDir, "ime_rec_${System.currentTimeMillis()}.wav")
                    AudioUtils.pcmToWav(pcmFile, wavFile)
                    pcmFile.delete()

                    val model = getModel()
                    val lang = getLang()
                    val entryMode = getEntryMode()

                    TranscriptionQueue.enqueue(
                        TranscriptionQueue.Job(
                            context = this@WhisperKeyboardService,
                            wavFile = wavFile,
                            model = model,
                            lang = lang,
                            onResult = { text ->
                                handler.post {
                                    if (text.isNotBlank()) {
                                        when (entryMode) {
                                            "type" -> currentInputConnection?.commitText(text + " ", 1)
                                            "txt" -> { saveToTxtFile(text); Toast.makeText(this, "Saved to TXT", Toast.LENGTH_SHORT).show() }
                                            "both" -> { currentInputConnection?.commitText(text + " ", 1); saveToTxtFile(text) }
                                        }
                                        updateStatus("Done: ${text.take(60)}")
                                    } else {
                                        updateStatus("No speech detected - ready")
                                    }
                                    updateQueueBadge()
                                }
                            },
                            onError = { error ->
                                handler.post {
                                    updateStatus("Error saved - tap Retry. $error")
                                    Toast.makeText(this@WhisperKeyboardService, "Saved failed - tap Retry", Toast.LENGTH_LONG).show()
                                    updateQueueBadge()
                                }
                            }
                        )
                    )
                    handler.post {
                        resetMicButton()
                        updateStatus("Queued - ready for next. ${TranscriptionQueue.status()}")
                        updateQueueBadge()
                    }
                } else {
                    pcmFile?.delete()
                    handler.post { updateStatus("Too short - ready"); resetMicButton() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
                pcmFile?.delete()
                handler.post { updateStatus("Error: ${e.message} - ready"); resetMicButton() }
                try { activeRecorder?.release() } catch (_: Exception) {}
                activeRecorder = null
            } finally {
                // keep wake lock until transcription queued; release after short delay
                handler.postDelayed({ if (!isRecording.get()) stopImeForeground() }, 1500)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopRecordingAndTranscribe() {
        if (!isRecording.get()) return
        isRecording.set(false)
        updateStatus("Finishing...")
        tvPct?.text = "..."
        try { activeRecorder?.stop() } catch (e: Exception) { Log.w(TAG, "stop unblock: ${e.message}") }
        handler.postDelayed({ resetMicButton(); updateStatus("Queued - ready for next"); updateQueueBadge() }, 300)
    }

    private fun resetMicButton() {
        btnMic?.isEnabled = true
        btnStop?.isEnabled = false
        btnMic?.text = "Speak"
        try { btnMic?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00B894.toInt()) } catch (_: Exception) {}
        refreshModeButtons()
        refreshModelButtons()
    }

    private fun updateStatus(text: String) { tvStatus?.text = text }

    private fun updateQueueBadge() {
        val s = TranscriptionQueue.status()
        tvQueueBadge?.text = s
        btnPause?.text = if (TranscriptionQueue.isPaused()) "Resume" else "Pause"
        // keep progress in sync
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = "${TranscriptionQueue.progress()}%"
        tvQueueBadge?.postDelayed({
            tvQueueBadge?.text = TranscriptionQueue.status()
            btnPause?.text = if (TranscriptionQueue.isPaused()) "Resume" else "Pause"
            progressBar?.progress = TranscriptionQueue.progress()
            tvPct?.text = "${TranscriptionQueue.progress()}%"
        }, 1200)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshModelButtons()
        refreshModeButtons()
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = "${TranscriptionQueue.progress()}%"
        updateQueueBadge()
    }

    override fun onDestroy() {
        TranscriptionQueue.removeListener(pqListener)
        isRecording.set(false)
        try { activeRecorder?.stop() } catch (_: Exception) {}
        try { activeRecorder?.release() } catch (_: Exception) {}
        stopImeForeground()
        super.onDestroy()
    }
}
