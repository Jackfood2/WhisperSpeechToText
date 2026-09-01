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
        /** True while the IME itself is capturing audio (bubble must not steal the mic). */
        @Volatile var imeRecording = false

        // chunk timing constants (user-adjustable values are read per-session in startRecording)
        const val VAD_THRESH = 0.018
        const val CHUNK_PAUSE_MS = 600L         // "end of sentence" micro-pause
        const val CHUNK_HARD_CAP_MS = 45_000L   // absolute max even mid-speech
    }

    private val isRecording = AtomicBoolean(false)
    private var activeRecorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var rootView: View? = null
    private var tvStatus: TextView? = null
    private var tvLabels: TextView? = null
    private var tvQueueBadge: TextView? = null
    private var tvPct: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnMicCircle: androidx.appcompat.widget.AppCompatImageButton? = null
    private var btnCloseKeyboard: Button? = null
    private var btnBackspace: Button? = null
    private var btnEnter: Button? = null
    private var btnKeyboardGear: Button? = null
    private var rowOutstanding: View? = null
    private var btnTypeOutstanding: Button? = null
    private var btnDiscardOutstanding: Button? = null
    private var rowProcessing: View? = null
    private var btnSkipOne: Button? = null
    private var btnStopAll: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private val enterHandler = Handler(Looper.getMainLooper())
    private var outstandingListener: (() -> Unit)? = null

    // Hold-to-delete WORDS, accelerating (400ms -> 80ms per word)
    @Volatile private var bsWordDelay = 400f
    private val backspaceWordRepeater = object : Runnable {
        override fun run() {
            try { deleteLastWord() } catch (_: Exception) {}
            bsWordDelay = (bsWordDelay * 0.78f).coerceAtLeast(80f)
            backspaceHandler.postDelayed(this, bsWordDelay.toLong())
        }
    }

    private val enterRepeater = object : Runnable {
        override fun run() {
            try { currentInputConnection?.commitText("\n", 1) } catch (_: Exception) {}
            enterHandler.postDelayed(this, 140)
        }
    }

    private fun deleteLastWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(48, 0) ?: return
        val trimmed = before.trimEnd()
        val n = when {
            trimmed.isEmpty() -> before.length.coerceAtLeast(1)
            else -> { val idx = trimmed.lastIndexOf(' '); before.length - (if (idx < 0) 0 else idx + 1) }
        }.coerceIn(1, 48)
        ic.deleteSurroundingText(n, 0)
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
                    handler.post { updateStatus("$m model not downloaded - open app to download") }
                    AppLog.w(TAG, "preload($from): $m not downloaded")
                    return@Thread
                }
                if (!WhisperEngine.isLoaded(mf.absolutePath)) {
                    handler.post { updateStatus("Loading $m model...") }
                    WhisperEngine.applyThreadPref(this)
                    val ok = WhisperEngine.ensureModel(mf.absolutePath)
                    handler.post { updateStatus(if (ok) "$m ready - open app - the mic" else "Model load FAILED - see Dashboard log") }
                }
            } catch (e: Throwable) { AppLog.e(TAG, "preload error: ${e.message}") }
        }.apply { isDaemon = true; name = "model-preload-$from"; start() }
    }

    // NOTE: model unloading follows STRICTLY the user's "Unload model when idle" timeout
    // (ProcessingService enforces it, incl. lock screen). No other triggers.

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
        tvLabels = view.findViewById(R.id.tvLabels)
        btnKeyboardGear = view.findViewById(R.id.btnKeyboardGear)
        rowOutstanding = view.findViewById(R.id.rowOutstanding)
        btnTypeOutstanding = view.findViewById(R.id.btnTypeOutstanding)
        btnDiscardOutstanding = view.findViewById(R.id.btnDiscardOutstanding)
        rowProcessing = view.findViewById(R.id.rowProcessing)
        btnSkipOne = view.findViewById(R.id.btnSkipOne)
        btnStopAll = view.findViewById(R.id.btnStopAll)

        btnMicCircle?.setOnClickListener { if (!isRecording.get()) startRecording() else stopRecordingAndTranscribe() }
        btnCloseKeyboard?.setOnClickListener {
            val wasRecording = isRecording.get()
            if (wasRecording) stopRecordingAndTranscribe() // stop mic NOW; chunks keep processing
            Toast.makeText(this, if (wasRecording || TranscriptionQueue.isActive()) "Stopped - text will still be typed in" else "Closed", Toast.LENGTH_SHORT).show()
            try { if (!switchToPreviousInputMethod()) requestHideSelf(0) } catch (_: Exception) { try { requestHideSelf(0) } catch (_: Exception) {} }
        }
        // Gear opens the APP settings - the app is the single source of truth
        btnKeyboardGear?.setOnClickListener {
            try {
                val i = Intent(this, SettingsActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (_: Exception) {}
        }
        // Backspace: open app - deletes ONE character; hold deletes WORDS, accelerating over time
        btnBackspace?.setOnClickListener { try { currentInputConnection?.deleteSurroundingText(1, 0) } catch (_: Exception) {} }
        btnBackspace?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    try { currentInputConnection?.deleteSurroundingText(1, 0) } catch (_: Exception) {}
                    bsWordDelay = 400f
                    backspaceHandler.postDelayed(backspaceWordRepeater, 380)
                    v.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    backspaceHandler.removeCallbacks(backspaceWordRepeater)
                    true
                }
                else -> false
            }
        }
        // Enter: tap inserts newline; hold repeats
        btnEnter?.setOnClickListener {
            try {
                val ic = currentInputConnection
                // Prefer commitText; fallback to key event for single-line fields
                if (ic != null) {
                    val ok = ic.commitText("\n", 1)
                    if (!ok) ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                }
            } catch (_: Exception) {}
        }
        btnEnter?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    try { currentInputConnection?.commitText("\n", 1) } catch (_: Exception) {}
                    enterHandler.postDelayed(enterRepeater, 380)
                    v.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    enterHandler.removeCallbacks(enterRepeater)
                    true
                }
                else -> false
            }
        }

        // Outstanding transcripts held from lock screen etc. - insert one per tap, oldest first
        outstandingListener = { handler.post { updateOutstandingRow() } }
        OutstandingStore.register(outstandingListener!!)
        btnTypeOutstanding?.setOnClickListener {
            val t = OutstandingStore.popOldest(this)
            if (t != null) {
                val capped = capsFn?.invoke(t) ?: t
                try { currentInputConnection?.commitText("$capped ", 1) } catch (_: Exception) {}
                val left = OutstandingStore.count(this)
                Toast.makeText(this, if (left > 0) "Typed - $left more pending" else "Typed - all done", Toast.LENGTH_SHORT).show()
                AppLog.i(TAG, "typed outstanding: ${t.take(50)}")
                updateOutstandingRow()
            }
        }
        btnDiscardOutstanding?.setOnClickListener {
            if (OutstandingStore.discardOldest(this)) {
                Toast.makeText(this, "Pending transcript discarded", Toast.LENGTH_SHORT).show()
                updateOutstandingRow()
            }
        }

        btnSkipOne?.setOnClickListener {
            TranscriptionQueue.skipCurrentJob()
            updateStatus("Skipping current...")
            Toast.makeText(this, "Skipped - moving to next", Toast.LENGTH_SHORT).show()
            updateProcessingRow()
        }
        btnStopAll?.setOnClickListener {
            val n = TranscriptionQueue.stopEverything()
            updateStatus("Force stop requested...")
            progressBar?.progress = 0; tvPct?.text = "0%"
            updateProcessingRow(); updateQueueBadge()
            // confirm once the native abort has actually landed
            fun confirmStop(attempt: Int) {
                if (!TranscriptionQueue.isActive() && !WhisperEngine.isBusy()) {
                    updateStatus("Processing stopped")
                    Toast.makeText(this, "Processing stopped", Toast.LENGTH_SHORT).show()
                    AppLog.i(TAG, "force stop confirmed")
                } else if (attempt < 20) handler.postDelayed({ confirmStop(attempt + 1) }, 150)
                else Toast.makeText(this, "Still stopping - model abort may take a moment", Toast.LENGTH_LONG).show()
            }
            handler.postDelayed({ confirmStop(0) }, 150)
        }

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
    private val langNames = arrayOf("Auto detect", "English", "Chinese", "Japanese", "Korean", "French", "German", "Spanish")
    private val langCodes = arrayOf("auto", "en", "zh", "ja", "ko", "fr", "de", "es")

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post { refreshAllButtons() }
    }

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        private var wasActive = false
        override fun onProgress(pct: Int) {
            handler.post {
                val active = TranscriptionQueue.isActive()
                progressBar?.progress = pct
                updateProcessingRow()
                if (active) {
                    val (cur, total) = TranscriptionQueue.batchPosition()
                    if (pct in 1..99) tvStatus?.text = "Transcribing chunk... $cur/$total ($pct%)"
                } else if (wasActive) {
                    // just finished - stop showing stale "Transcribing..."
                    tvStatus?.text = if (isRecording.get()) "Listening - text appears as you pause" else "All chunks processed"
                    tvPct?.text = "0%"
                    progressBar?.progress = 0
                }
                wasActive = active
            }
        }
    }

    private fun refreshAllButtons() {
        try { setCircleVisual(isRecording.get()) } catch (_: Exception) {}
        updateLabelsRow()
    }

    /** Read-only label of current selections - the app is the source of truth. */
    private fun updateLabelsRow() {
        val p = prefs()
        val chunkS = p.getInt("vad_chunk_silence_ds", 40) / 10f
        val stopS = p.getInt("vad_stop_silence_s", 10)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val mode = p.getString("threads_mode", "auto") ?: "auto"
        val thr = if (mode == "auto") (if (cores >= 8) 6 else if (cores >= 4) 4 else cores) else mode.toIntOrNull() ?: 4
        val chunkedKb = if (p.getBoolean("ime_chunked", true)) "chunked" else "whole"
        val parts = mutableListOf(
            getModel(),
            "lang:" + (langNames.getOrNull(langCodes.indexOf(getLang()).coerceAtLeast(0)) ?: getLang()),
            "VAD ${"%.1f".format(chunkS)}s",
            if (isVadOn()) "auto-stop ${stopS}s" else "no auto-stop",
            if (isBtOn()) "BT mic" else null,
            "caps:${getCapsMode()}",
            chunkedKb,
            "$thr threads"
        ).filterNotNull()
        tvLabels?.text = parts.joinToString(" | ")
    }

    private fun setCircleVisual(recording: Boolean) {
        btnMicCircle?.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
        btnMicCircle?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (recording) 0xFFE17055.toInt() else 0xFF00B894.toInt())
    }

    private fun applyCapsMode(text: String): String {
        val mode = getCapsMode()
        if (mode == "off") return text.lowercase()
        if (mode == "auto") return text
        return text.split(Regex("(?<=[.!?])\\s+")).joinToString(" | ") { s ->
            s.replaceFirstChar { if(it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
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
        // model file present but not in memory -> start recording NOW, load concurrently
        if (!WhisperEngine.isLoaded(mf.absolutePath)) preloadModel("startRecording")
        isRecording.set(true)
        imeRecording = true
        startImeForeground()
        setCircleVisual(true)
        updateStatus("Listening - text appears as you pause")
        tvPct?.text = "REC"
        stopHook = { stopRecordingAndTranscribe() }
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()

        recordThread = Thread {
            val pcmChunk = ByteArrayOutputStream()
            var hasVoice = false
            var lastVoiceTime = System.currentTimeMillis()      // per-chunk (closes chunks)
            var sessionVoiceTime = System.currentTimeMillis()   // whole-session (auto-stop)
            var chunkStartMs = System.currentTimeMillis()
            val recordStartMs = chunkStartMs
            val vadOn = isVadOn()
            val chunked = prefs().getBoolean("ime_chunked", true)
            // user-adjustable timings (Settings page). chunk silence stored in tenths of a second (min 0.5s)
            val chunkSilenceMs = prefs().getInt("vad_chunk_silence_ds", 40).coerceIn(5, 100) * 100L
            val chunkTargetMs = prefs().getInt("chunk_target_s", 30).coerceIn(1, 45) * 1000L
            val sessionStopMs = prefs().getInt("vad_stop_silence_s", 10).coerceIn(5, 60) * 1000L
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
                        if (rms > VAD_THRESH) { hasVoice = true; lastVoiceTime = now; sessionVoiceTime = now }
                        val silenceFor = now - lastVoiceTime
                        val durMs = now - chunkStartMs
                        val hasContent = pcmChunk.size() > 1800
                        // FIRST window: first 15s of the session -> first chunk needs >=15s audio,
                        // unless there is >=3s silence earlier. Afterwards: user chunk length applies,
                        // but only cut when the previous chunk is NOT still processing.
                        val inFirstWindow = now - recordStartMs < 15_000
                        val queueBusy = TranscriptionQueue.isActive()
                        val closeChunk = when {
                            !hasContent || !chunked -> false
                            inFirstWindow -> (durMs >= 15_000 && silenceFor >= CHUNK_PAUSE_MS) || silenceFor >= 3_000
                            else -> (durMs >= chunkTargetMs && !queueBusy) ||
                                    (silenceFor >= chunkSilenceMs) ||
                                    (durMs >= CHUNK_HARD_CAP_MS)
                        }
                        if (closeChunk) {
                            flushChunk(pcmChunk, selModel)
                            synchronized(pcmChunk) { pcmChunk.reset() }
                            chunkStartMs = now
                            hasVoice = false
                            lastVoiceTime = now
                            Log.i(TAG, "chunk closed at ${durMs / 1000}s (silence ${silenceFor}ms)")
                        }
                        // whole-session auto stop after long silence (independent of chunk resets)
                        if (vadOn && now - sessionVoiceTime >= sessionStopMs) {
                            Log.i(TAG, "session VAD stop after ${now - sessionVoiceTime}ms silence")
                            handler.post {
                                updateStatus("Auto-stopped after long silence")
                                Toast.makeText(this@WhisperKeyboardService, "Auto-stopped after ${sessionStopMs / 1000}s of silence", Toast.LENGTH_SHORT).show()
                                stopRecordingAndTranscribe()
                            }
                            break
                        }
                    } else if (read < 0) break
                }
                try { recorder.stop() } catch (_: Exception) {}
                // shared recorder stays alive for bubble/next session (never release)
                activeRecorder = null
                // final partial chunk
                flushChunk(pcmChunk, selModel)
            } catch (e: Throwable) {
                AppLog.e(TAG, "recording error: ${e.message}")
                handler.post { updateStatus("Error: ${e.message}"); Toast.makeText(this@WhisperKeyboardService, "Mic error: ${e.message}", Toast.LENGTH_LONG).show() }
                try { activeRecorder?.stop() } catch (_: Exception) {}
                activeRecorder = null
            } finally {
                imeRecording = false
                stopHook = null
                handler.post {
                    resetMicButton()
                    val done = !TranscriptionQueue.isActive() && TextRouter.pendingTypingCount() == 0
                    updateStatus(if (done) "All chunks processed ✓" else "Ready - chunks still processing")
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
                context = applicationContext,
                wavFile = wav,
                model = model,
                lang = lang,
                onResult = { text -> TextRouter.route(text.trim()) },
                onError = { err ->
                    handler.post {
                        updateStatus("Chunk failed - use Retry Failed")
                        Toast.makeText(this@WhisperKeyboardService, "A chunk failed - Retry Failed in app settings", Toast.LENGTH_LONG).show()
                        updateQueueBadge()
                    }
                    AppLog.e(TAG, "chunk failed: $err")
                }
            )
        )
        handler.post { updateQueueBadge() }
    }

    /** Strict typing: results are routed (IME -> accessibility paste -> held for resume). */
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
        progressBar?.progress = TranscriptionQueue.progress()
        if (!TranscriptionQueue.isActive()) tvPct?.text = "${TranscriptionQueue.progress()}%" else updateProcessingRow()
    }

    private fun updateQueueBadge() {
        val s = TranscriptionQueue.status()
        tvQueueBadge?.text = s
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

    private fun updateOutstandingRow() {
        val n = OutstandingStore.count(this)
        rowOutstanding?.visibility = if (n > 0) View.VISIBLE else View.GONE
        btnTypeOutstanding?.text = "Type pending transcript ($n)"
        if (n > 0) updateStatus("$n pending transcript(s) - open app - the blue button to insert")
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        activeIC = currentInputConnection
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activeIC = currentInputConnection
        preloadModel("onStartInputView")
        refreshAllButtons()
        updateOutstandingRow()
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
        outstandingListener?.let { OutstandingStore.unregister(it) }
        try { prefs().unregisterOnSharedPreferenceChangeListener(prefsListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        isRecording.set(false)
        stopHook = null
        activeIC = null
        try { activeRecorder?.stop() } catch (_: Exception) {}
        // shared recorder stays alive process-wide (never release)
        activeRecorder = null
        stopImeForeground()
        super.onDestroy()
    }
}




