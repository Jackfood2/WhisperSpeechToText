package com.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class WhisperKeyboardService : InputMethodService() {

    /** TAG + state shared with QuickSwitchService / ImeRecordService */
    companion object {
        const val TAG = "WhisperIME"
        @Volatile var activeIC: android.view.inputmethod.InputConnection? = null
        @Volatile var capsFn: ((String) -> String)? = null
        /** Invoked by the recording notification's Stop action (works from lock screen). */
        @Volatile var stopHook: (() -> Unit)? = null

        // ---- chunked transcription config ----
        const val VAD_THRESH = 0.018
        const val CHUNK_SILENCE_MS = 4000L      // >=4s silence -> close chunk (any length >=3s)
        const val CHUNK_MIN_MS = 3000L
        const val CHUNK_TARGET_MS = 30_000L     // >=30s -> close at next small pause
        const val CHUNK_PAUSE_MS = 600L         // "end of sentence" pause for the 30s boundary
        const val CHUNK_HARD_CAP_MS = 45_000L   // absolute max even mid-speech
        const val SESSION_SILENCE_MS = 10_000L  // vad_on -> whole session auto-stops
    }

    private val isRecording = AtomicBoolean(false)
    private var activeRecorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var rootView: View? = null
    private var tvStatus: TextView? = null
    private var tvQueueBadge: TextView? = null
    private var tvPct: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnMicCircle: Button? = null
    private var btnCloseKeyboard: Button? = null
    private var btnBackspace: Button? = null
    private var btnEnter: Button? = null
    private var btnKeyboardGear: Button? = null
    private var btnPanelDone: Button? = null
    private var simpleLayout: View? = null
    private var settingsPanel: View? = null
    private var rowProcessing: View? = null
    private var btnSkipOne: Button? = null
    private var btnStopAll: Button? = null
    private var btnPause: Button? = null
    private var btnClear: Button? = null
    private var btnRetry: Button? = null
    private var btnModelTiny: Button? = null
    private var btnModelBase: Button? = null
    private var btnModelSmall: Button? = null
    private var btnModelMedium: Button? = null
    private var btnVad: Button? = null
    private var btnLang: Button? = null
    private var btnBt: Button? = null
    private var btnCaps: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeater = object : Runnable {
        override fun run() {
            try { currentInputConnection?.deleteSurroundingText(1, 0) } catch (_: Exception) {}
            backspaceHandler.postDelayed(this, 50)
        }
    }

    // ---- chunked transcription config ----
    override fun onCreate() {
        super.onCreate()
        preloadModel("onCreate")
    }

    private fun preloadModel(from: String) {
        Thread {
            try {
                val m = getModel()
                val mf = ModelManager.modelFile(this, m)
                if (!mf.exists() || mf.length() < 1_000_000) {
                    handler.post { updateStatus("$m model not downloaded - tap ⚙ for help") }
                    AppLog.w(TAG, "preload($from): $m not downloaded")
                    return@Thread
                }
                if (!WhisperEngine.isLoaded(mf.absolutePath)) {
                    handler.post { updateStatus("Loading $m model...") }
                    val ok = WhisperEngine.ensureModel(mf.absolutePath)
                    handler.post { updateStatus(if (ok) "$m ready - tap the mic" else "Model load FAILED - see Dashboard log") }
                }
            } catch (e: Throwable) { AppLog.e(TAG, "preload error: ${e.message}") }
        }.apply { isDaemon = true; name = "model-preload-$from"; start() }
    }

    private fun maybeUnload(reason: String) {
        if (isRecording.get()) { Log.i(TAG, "Skip unload ($reason) - recording"); return }
        if (TranscriptionQueue.isActive()) { Log.i(TAG, "Skip unload ($reason) - queue busy"); return }
        WhisperEngine.unloadIfIdle()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            AppLog.i(TAG, "memory pressure level=$level -> unload check")
            Thread { maybeUnload("trimMemory$level") }.apply { isDaemon = true; start() }
        }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView")
        capsFn = { applyCapsMode(it) }
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        rootView = view
        tvStatus = view.findViewById(R.id.tvStatus)
        tvQueueBadge = view.findViewById(R.id.tvQueueBadge)
        tvPct = view.findViewById(R.id.tvProgressPct)
        progressBar = view.findViewById(R.id.progressTranscribe)
        btnMicCircle = view.findViewById(R.id.btnMicCircle)
        btnCloseKeyboard = view.findViewById(R.id.btnCloseKeyboard)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnKeyboardGear = view.findViewById(R.id.btnKeyboardGear)
        btnPanelDone = view.findViewById(R.id.btnPanelDone)
        simpleLayout = view.findViewById(R.id.simpleLayout)
        settingsPanel = view.findViewById(R.id.settingsPanel)
        rowProcessing = view.findViewById(R.id.rowProcessing)
        btnSkipOne = view.findViewById(R.id.btnSkipOne)
        btnStopAll = view.findViewById(R.id.btnStopAll)
        btnPause = view.findViewById(R.id.btnPause)
        btnClear = view.findViewById(R.id.btnClear)
        btnRetry = view.findViewById(R.id.btnRetry)
        btnModelTiny = view.findViewById(R.id.btnModelTiny)
        btnModelBase = view.findViewById(R.id.btnModelBase)
        btnModelSmall = view.findViewById(R.id.btnModelSmall)
        btnModelMedium = view.findViewById(R.id.btnModelMedium)
        btnVad = view.findViewById(R.id.btnVad)
        btnLang = view.findViewById(R.id.btnLang)
        btnBt = view.findViewById(R.id.btnBt)
        btnCaps = view.findViewById(R.id.btnCaps)

        btnMicCircle?.setOnClickListener { if (!isRecording.get()) startRecording() else stopRecordingAndTranscribe() }
        btnCloseKeyboard?.setOnClickListener {
            Toast.makeText(this, if (isRecording.get() || TranscriptionQueue.isActive()) "Continues in background" else "Closed", Toast.LENGTH_SHORT).show()
            try { if (!switchToPreviousInputMethod()) requestHideSelf(0) } catch (_: Exception) { try { requestHideSelf(0) } catch (_: Exception) {} }
        }
        btnEnter?.setOnClickListener {
            try { currentInputConnection?.commitText("\n", 1) } catch (_: Exception) {}
        }
        btnBackspace?.setOnClickListener { deleteLastWord() }
        btnBackspace?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { backspaceHandler.postDelayed(backspaceRepeater, 400); v.performClick(); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { backspaceHandler.removeCallbacks(backspaceRepeater); true }
                else -> false
            }
        }
        btnKeyboardGear?.setOnClickListener {
            settingsPanel?.visibility = View.VISIBLE
            simpleLayout?.visibility = View.GONE
            refreshAllButtons()
        }
        btnPanelDone?.setOnClickListener {
            settingsPanel?.visibility = View.GONE
            simpleLayout?.visibility = View.VISIBLE
        }

        btnSkipOne?.setOnClickListener {
            TranscriptionQueue.skipCurrentJob()
            updateStatus("Skipping current...")
            Toast.makeText(this, "Skipped - moving to next", Toast.LENGTH_SHORT).show()
            updateProcessingRow()
        }
        btnStopAll?.setOnClickListener {
            val n = TranscriptionQueue.stopEverything()
            updateStatus("Stopped all ($n cleared)")
            progressBar?.progress = 0; tvPct?.text = "0%"
            updateProcessingRow(); updateQueueBadge()
        }
        btnPause?.setOnClickListener {
            val nowPaused = TranscriptionQueue.togglePause()
            btnPause?.text = if (nowPaused) "Resume" else "Pause"
            updateStatus(if (nowPaused) "Queue paused" else "Queue resumed")
            updateQueueBadge()
        }
        btnClear?.setOnClickListener {
            val n = TranscriptionQueue.clearQueue()
            Toast.makeText(this, "Cleared $n queued + cancelled current", Toast.LENGTH_SHORT).show()
            updateStatus("Queue cleared - ready")
            progressBar?.progress = 0; tvPct?.text = "0%"
            updateProcessingRow(); updateQueueBadge()
        }
        btnRetry?.setOnClickListener {
            val c = TranscriptionQueue.failedCount()
            if (c == 0) Toast.makeText(this, "No failed recordings", Toast.LENGTH_SHORT).show()
            else { TranscriptionQueue.retryFailed(); Toast.makeText(this, "Retrying $c failed", Toast.LENGTH_SHORT).show(); updateStatus("Retrying $c failed...") }
            updateQueueBadge()
        }
        btnModelTiny?.setOnClickListener { setModel("tiny") }
        btnModelBase?.setOnClickListener { setModel("base") }
        btnModelSmall?.setOnClickListener { setModel("small") }
        btnModelMedium?.setOnClickListener { setModel("medium") }
        btnVad?.setOnClickListener { toggleVad() }
        btnLang?.setOnClickListener { cycleLang() }
        btnBt?.setOnClickListener { toggleBt() }
        btnCaps?.setOnClickListener { toggleCaps() }

        TranscriptionQueue.addListener(pqListener)
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        refreshAllButtons()
        updateStatus("Tap the mic and speak")
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = "${TranscriptionQueue.progress()}%"
        updateQueueBadge()
        return view
    }

    private fun prefs() = getSharedPreferences("whisper", MODE_PRIVATE)
    private fun getModel(): String = prefs().getString("model", "small") ?: "small"
    private fun getLang(): String = prefs().getString("lang", "auto") ?: "auto"
    private fun isVadOn(): Boolean = prefs().getBoolean("vad_on", true)
    private fun isBtOn(): Boolean = prefs().getBoolean("bt_mic", false)
    private fun getCapsMode(): String = prefs().getString("caps_mode", "auto") ?: "auto"
    private val langs = arrayOf("auto", "en", "zh", "ja", "ko", "fr", "de", "es")

    private fun setModel(m: String) {
        prefs().edit().putString("model", m).apply()
        refreshAllButtons()
        val mf = ModelManager.modelFile(this, m)
        if (mf.exists() && mf.length() > 1_000_000) {
            if (!WhisperEngine.isLoaded(mf.absolutePath)) { updateStatus("Loading $m..."); preloadModel("setModel") }
            Toast.makeText(this, "Model: $m", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "$m not downloaded - use app to download", Toast.LENGTH_LONG).show()
            updateStatus("$m not downloaded")
        }
    }

    private fun cycleLang() {
        val cur = getLang()
        val idx = langs.indexOf(cur).coerceAtLeast(0)
        val next = langs[(idx + 1) % langs.size]
        prefs().edit().putString("lang", next).apply()
        refreshAllButtons()
        Toast.makeText(this, "Language: $next", Toast.LENGTH_SHORT).show()
    }

    private fun toggleVad() { val v = !isVadOn(); prefs().edit().putBoolean("vad_on", v).apply(); refreshAllButtons(); Toast.makeText(this, if(v) "Auto-stop after 10s silence ON" else "Auto-stop OFF", Toast.LENGTH_SHORT).show() }
    private fun toggleBt() { val v = !isBtOn(); prefs().edit().putBoolean("bt_mic", v).apply(); refreshAllButtons(); Toast.makeText(this, if(v) "Bluetooth mic ON" else "Phone mic", Toast.LENGTH_SHORT).show() }
    private fun toggleCaps() { val cur = getCapsMode(); val next = when(cur){"auto"->"on";"on"->"off";else->"auto"}; prefs().edit().putString("caps_mode", next).apply(); refreshAllButtons(); Toast.makeText(this, "Caps: $next", Toast.LENGTH_SHORT).show() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post { refreshAllButtons() }
    }

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        override fun onProgress(pct: Int) {
            handler.post {
                progressBar?.progress = pct
                updateProcessingRow()
                if (TranscriptionQueue.isActive()) {
                    val (cur, total) = TranscriptionQueue.batchPosition()
                    if (pct in 1..99) tvStatus?.text = "Transcribing chunk... $cur/$total ($pct%)"
                }
            }
        }
    }

    private fun refreshAllButtons() {
        val curM = getModel()
        val active = 0xFF00B894.toInt(); val idle = 0xFF636E72.toInt()
        try {
            btnModelTiny?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="tiny") active else idle)
            btnModelBase?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="base") active else idle)
            btnModelSmall?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="small") active else idle)
            btnModelMedium?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="medium") active else idle)
        } catch (_: Exception) {}
        try {
            btnVad?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(isVadOn()) active else idle)
            btnVad?.text = if(isVadOn()) "VAD: ON" else "VAD: OFF"
            btnLang?.text = "Lang: ${getLang()}"
            btnBt?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(isBtOn()) active else idle)
            btnBt?.text = if(isBtOn()) "BT: ON" else "BT: OFF"
            val caps = getCapsMode()
            btnCaps?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(caps!="off") active else idle)
            btnCaps?.text = "Caps: ${caps.uppercase()}"
        } catch (_: Exception) {}
        try { setCircleVisual(isRecording.get()) } catch (_: Exception) {}
    }

    private fun setCircleVisual(recording: Boolean) {
        btnMicCircle?.text = if (recording) "■" else "🎤"
        btnMicCircle?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (recording) 0xFFE17055.toInt() else 0xFF00B894.toInt())
    }

    private fun applyCapsMode(text: String): String {
        val mode = getCapsMode()
        if (mode == "off") return text.lowercase()
        if (mode == "auto") return text
        return text.split(Regex("(?<=[.!?])\\s+")).joinToString(" ") { s ->
            s.replaceFirstChar { if(it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private fun deleteLastWord() {
        try {
            val ic = currentInputConnection ?: return
            val before = ic.getTextBeforeCursor(48, 0) ?: return
            val trimmed = before.trimEnd()
            val n = when {
                trimmed.isEmpty() -> before.length.coerceAtLeast(1)
                else -> { val idx = trimmed.lastIndexOf(' '); before.length - (if (idx < 0) 0 else idx + 1) }
            }.coerceIn(1, 48)
            ic.deleteSurroundingText(n, 0)
        } catch (_: Exception) {}
    }

    private fun startImeForeground() {
        try {
            val intent = Intent(this, ImeRecordService::class.java).apply { action = "START" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { Log.w(TAG, "foreground start failed: ${e.message}") }
    }
    private fun stopImeForeground() {
        try { val intent = Intent(this, ImeRecordService::class.java).apply { action = "STOP" }; startService(intent) } catch (_: Exception) {}
    }

    // ================= chunked recording engine =================

    private fun startRecording() {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Need mic permission - open app"); return
        }
        val selModel = getModel()
        val mf = ModelManager.modelFile(this, selModel)
        if (!mf.exists() || mf.length() < 1_000_000) {
            updateStatus("$selModel not downloaded - open app, tap Download Model")
            Toast.makeText(this, "$selModel model missing - open the app to download it", Toast.LENGTH_LONG).show()
            return
        }
        isRecording.set(true)
        startImeForeground()
        setCircleVisual(true)
        updateStatus("Listening - text appears as you pause")
        tvPct?.text = "REC"
        stopHook = { stopRecordingAndTranscribe() }
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()

        recordThread = Thread {
            val pcmChunk = ByteArrayOutputStream()
            var hasVoice = false
            var lastVoiceTime = System.currentTimeMillis()
            var chunkStartMs = System.currentTimeMillis()
            val vadOn = isVadOn()
            try {
                val recorder = AudioUtils.createRecorder(this)
                activeRecorder = recorder
                recorder.startRecording()
                val buffer = ByteArray(4096)
                while (isRecording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        synchronized(pcmChunk) { pcmChunk.write(buffer, 0, read) }
                        val rms = AudioUtils.rms16(buffer, read)
                        val now = System.currentTimeMillis()
                        if (rms > VAD_THRESH) { hasVoice = true; lastVoiceTime = now }
                        val silenceFor = now - lastVoiceTime
                        val durMs = now - chunkStartMs
                        // invisible stop-start: close a chunk whenever there's a chance
                        val closeChunk =
                            (silenceFor >= CHUNK_SILENCE_MS && durMs >= CHUNK_MIN_MS) ||          // >=4s pause
                            (durMs >= CHUNK_TARGET_MS && silenceFor >= CHUNK_PAUSE_MS) ||          // 30s+ then end-of-sentence pause
                            (durMs >= CHUNK_HARD_CAP_MS)                                           // hard cap mid-speech
                        if (closeChunk && pcmChunk.size() > 1800) {
                            flushChunk(pcmChunk, selModel)
                            synchronized(pcmChunk) { pcmChunk.reset() }
                            chunkStartMs = now
                            hasVoice = false
                            lastVoiceTime = now
                            Log.i(TAG, "chunk closed at ${durMs / 1000}s (silence ${silenceFor}ms)")
                        }
                        // whole-session auto stop after long silence
                        if (vadOn && hasVoice && silenceFor >= SESSION_SILENCE_MS) {
                            Log.i(TAG, "session VAD stop after ${silenceFor}ms silence")
                            handler.post { updateStatus("Auto-stopped after long silence"); stopRecordingAndTranscribe() }
                            break
                        }
                    } else if (read < 0) break
                }
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                activeRecorder = null
                // final partial chunk
                flushChunk(pcmChunk, selModel)
            } catch (e: Throwable) {
                AppLog.e(TAG, "recording error: ${e.message}")
                handler.post { updateStatus("Error: ${e.message}"); Toast.makeText(this@WhisperKeyboardService, "Mic error: ${e.message}", Toast.LENGTH_LONG).show() }
                try { activeRecorder?.release() } catch (_: Exception) {}
                activeRecorder = null
            } finally {
                stopHook = null
                handler.post {
                    resetMicButton()
                    updateStatus("Ready - chunks still processing")
                    updateQueueBadge()
                }
                handler.postDelayed({ if (!isRecording.get()) stopImeForeground() }, 1500)
            }
        }.apply { isDaemon = true; start() }
    }

    /** Write chunk to WAV and enqueue for transcription immediately (pipeline). */
    private fun flushChunk(buf: ByteArrayOutputStream, model: String) {
        val bytes = synchronized(buf) { buf.toByteArray() }
        if (bytes.size <= 1800) return
        // gate: don't pile up more than ~3 unprocessed chunks; wait for the pipeline to drain a bit
        var waited = 0L
        while (TranscriptionQueue.pendingCount() >= 3 && waited < 60_000 && isRecording.get()) {
            Thread.sleep(200); waited += 200
        }
        val ts = System.currentTimeMillis()
        val pcm = File(cacheDir, "ime_chunk_$ts.pcm")
        FileOutputStream(pcm).use { it.write(bytes) }
        val wav = File(cacheDir, "ime_chunk_$ts.wav")
        AudioUtils.pcmToWav(pcm, wav)
        pcm.delete()
        val lang = getLang()
        val sec = bytes.size / 32000.0
        AppLog.i(TAG, "enqueue chunk %.1fs (%d KB)".format(sec, bytes.size / 1024))
        handler.post { Toast.makeText(this@WhisperKeyboardService, "Chunk %.0fs queued".format(sec), Toast.LENGTH_SHORT).show() }
        TranscriptionQueue.enqueue(
            TranscriptionQueue.Job(
                context = this,
                wavFile = wav,
                model = model,
                lang = lang,
                onResult = { text -> commitWhenReady(text.trim(), 0) },
                onError = { err ->
                    handler.post {
                        updateStatus("Chunk failed - Retry Failed in ⚙")
                        Toast.makeText(this@WhisperKeyboardService, "A chunk failed - Retry Failed in ⚙ settings", Toast.LENGTH_LONG).show()
                        updateQueueBadge()
                    }
                    AppLog.e(TAG, "chunk failed: $err")
                }
            )
        )
        handler.post { updateQueueBadge() }
    }

    /** Commit text into focused field; retry up to 4s if connection not bound yet. Strictly types - no TXT fallback. */
    private fun commitWhenReady(text: String, attempt: Int) {
        handler.post {
            if (text.isEmpty() || text.startsWith("ERROR") || AudioUtils.isNoSpeechText(text)) {
                updateStatus(if (text.startsWith("ERROR")) "Chunk error: ${text.take(40)}" else "Silence - skipped")
                return@post
            }
            val capped = capsFn?.invoke(text) ?: text
            val ic = activeIC ?: currentInputConnection
            if (ic != null) {
                ic.commitText("$capped ", 1)
                updateStatus("+ ${capped.take(50)}")
                AppLog.i(TAG, "typed: ${capped.take(60)}")
            } else if (attempt < 8) {
                handler.postDelayed({ commitWhenReady(text, attempt + 1) }, 500)
            } else {
                Toast.makeText(this, "Field closed - text dropped. Keep keyboard open while recording.", Toast.LENGTH_LONG).show()
                AppLog.e(TAG, "no input connection - dropped: ${text.take(60)}")
            }
            updateQueueBadge()
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!isRecording.get()) return
        isRecording.set(false)
        updateStatus("Stopping - flushing final chunk...")
        Toast.makeText(this, "Stopped - processing remaining chunks", Toast.LENGTH_SHORT).show()
        try { activeRecorder?.stop() } catch (e: Exception) { Log.w(TAG, "stop unblock: ${e.message}") }
    }

    private fun resetMicButton() {
        setCircleVisual(false)
    }

    private fun updateStatus(text: String) { tvStatus?.text = text }

    private val badgeRefresh = Runnable {
        tvQueueBadge?.text = TranscriptionQueue.status()
        btnPause?.text = if (TranscriptionQueue.isPaused()) "Resume" else "Pause"
        progressBar?.progress = TranscriptionQueue.progress()
        if (!TranscriptionQueue.isActive()) tvPct?.text = "${TranscriptionQueue.progress()}%" else updateProcessingRow()
    }

    private fun updateQueueBadge() {
        val s = TranscriptionQueue.status()
        tvQueueBadge?.text = s
        btnPause?.text = if (TranscriptionQueue.isPaused()) "Resume" else "Pause"
        progressBar?.progress = TranscriptionQueue.progress()
        if (!TranscriptionQueue.isActive()) {
            tvPct?.text = if (isRecording.get()) "REC" else "${TranscriptionQueue.progress()}%"
        } else {
            val (cur, total) = TranscriptionQueue.batchPosition()
            tvPct?.text = "$cur/$total"
        }
        updateProcessingRow()
        tvQueueBadge?.removeCallbacks(badgeRefresh)
        tvQueueBadge?.postDelayed(badgeRefresh, 1200)
    }

    private fun updateProcessingRow() {
        rowProcessing?.visibility = if (TranscriptionQueue.isActive()) View.VISIBLE else View.GONE
        if (TranscriptionQueue.isActive()) {
            val (cur, total) = TranscriptionQueue.batchPosition()
            tvPct?.text = "$cur/$total"
        }
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        activeIC = currentInputConnection
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activeIC = currentInputConnection
        // always come back to the simple view
        simpleLayout?.visibility = View.VISIBLE
        settingsPanel?.visibility = View.GONE
        preloadModel("onStartInputView")
        refreshAllButtons()
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = if (isRecording.get()) "REC" else "${TranscriptionQueue.progress()}%"
        updateQueueBadge()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput) activeIC = null
    }

    override fun onDestroy() {
        TranscriptionQueue.removeListener(pqListener)
        try { prefs().unregisterOnSharedPreferenceChangeListener(prefsListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        isRecording.set(false)
        try { activeRecorder?.stop() } catch (_: Exception) {}
        try { activeRecorder?.release() } catch (_: Exception) {}
        stopImeForeground()
        maybeUnload("onDestroy")
        super.onDestroy()
    }
}
