package com.whisperkeyboard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    companion object {
        const val TAG = "WhisperIME"
        @Volatile var activeIC: android.view.inputmethod.InputConnection? = null
        @Volatile var capsFn: ((String) -> String)? = null

        @Volatile var stopHook: (() -> Unit)? = null

        @Volatile var imeRecording = false

        @Volatile
        var autoStartRequested = false

        const val VAD_THRESH = 0.018

        const val MIN_CHUNK_MS = 30_000L
        const val MAX_CHUNK_MS = 60_000L
        const val FIRST_SESSION_SILENCE_MS = 2_000L
        const val CHUNK_SILENCE_MS = 2_000L
        private const val FIRST_SESSION_NO_SPEECH_MS =
            10_000L

        private const val MAX_IN_MEMORY_AUDIO_BYTES =
            32_000 * 5 * 60
    }

    private val isRecording = AtomicBoolean(false)
    private var activeRecorder: AudioRecord? = null
    @Volatile private var recordThread: Thread? = null
    private var rootView: View? = null
    private var tvStatus: TextView? = null
    private var tvEngine: TextView? = null
    private var tvLabels: TextView? = null
    private var tvQueueBadge: TextView? = null
    private var tvPct: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnMicCircle: androidx.appcompat.widget.AppCompatImageButton? = null
    private var btnCloseKeyboard: Button? = null
    private var btnBackspace: Button? = null
    private var btnEnter: Button? = null
    private var btnSpace: Button? = null
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
    private var listenersRegistered = false

    private fun registerListenersOnce() {
        if (listenersRegistered) return

        outstandingListener = { handler.post { updateOutstandingRow() } }
        OutstandingStore.register(outstandingListener!!)
        TranscriptionQueue.addListener(pqListener)
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)

        listenersRegistered = true
    }

    @Volatile private var bsWordDelay = 400f
    private val backspaceWordRepeater = object : Runnable {
        override fun run() {
            try { deleteLastWord() } catch (_: Exception) {}
            bsWordDelay = (bsWordDelay * 0.78f).coerceAtLeast(80f)
            backspaceHandler.postDelayed(this, bsWordDelay.toLong())
        }
    }

    private fun enterRepeatAction(): Boolean {
        val action =
            currentInputEditorInfo?.imeOptions
                ?.and(
                    android.view.inputmethod.EditorInfo
                        .IME_MASK_ACTION
                )
                ?: android.view.inputmethod.EditorInfo
                    .IME_ACTION_NONE

        return action ==
            android.view.inputmethod.EditorInfo.IME_ACTION_NONE ||
            action ==
            android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
    }

    private val enterRepeater = object : Runnable {
        override fun run() {
            if (!enterRepeatAction()) return
            try { currentInputConnection?.commitText("\n", 1) } catch (_: Exception) {}
            enterHandler.postDelayed(this, 140)
        }
    }

    private fun deleteLastWord() {
        val connection =
            currentInputConnection ?: return

        val before =
            connection.getTextBeforeCursor(64, 0)
                ?.toString()
                ?: return

        if (before.isEmpty()) return

        var deleteCount = 0
        var index = before.lastIndex

        while (
            index >= 0 &&
            before[index].isWhitespace()
        ) {
            deleteCount++
            index--
        }

        while (
            index >= 0 &&
            !before[index].isWhitespace()
        ) {
            deleteCount++
            index--
        }

        if (deleteCount > 0) {
            connection.deleteSurroundingText(
                deleteCount,
                0
            )
        }
    }

    private fun performEnter() {
        val connection =
            currentInputConnection ?: return

        val action =
            currentInputEditorInfo?.imeOptions
                ?.and(
                    android.view.inputmethod.EditorInfo
                        .IME_MASK_ACTION
                )
                ?: android.view.inputmethod.EditorInfo
                    .IME_ACTION_NONE

        val handled = when (action) {
            android.view.inputmethod.EditorInfo.IME_ACTION_GO,
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
            android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,
            android.view.inputmethod.EditorInfo.IME_ACTION_DONE ->
                connection.performEditorAction(action)

            else -> false
        }

        if (!handled) {
            connection.commitText("\n", 1)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (isRecording.get()) {
                    AppLog.i(TAG, "Screen locked - stopping keyboard recording")

                    handler.post {
                        updateStatus("Screen locked - recording stopped")
                    }

                    stopRecordingAndTranscribe()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF)
            )
        }

        preloadModel("onCreate")
    }

    private fun preloadModel(from: String) {
        Thread {
            try {
                val eng = SttEngines.current(this)
                val m = getModel()
                if (!SttEngines.isReady(this, eng, m)) {
                    val missing = SttEngines.describeMissing(this, eng, m) ?: "not downloaded"
                    handler.post { updateStatus("$missing - open app") }
                    AppLog.w(TAG, "preload($from): $eng: $missing")
                    return@Thread
                }
                if (!SttEngines.isLoaded(this, eng, m)) {
                    handler.post { updateStatus("Loading $eng/$m model...") }
                    val ok = SttEngines.ensureModel(this, eng, m, getLang())
                    handler.post { updateStatus(if (ok) "$eng/$m ready - tap the mic" else "Model load FAILED - see Dashboard log") }
                }
            } catch (e: Throwable) { AppLog.e(TAG, "preload error: ${e.message}") }
        }.apply { isDaemon = true; name = "model-preload-$from"; start() }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView")
        capsFn = { applyCapsMode(it) }
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        rootView = view
        tvStatus = view.findViewById(R.id.tvStatus)
        tvEngine = view.findViewById(R.id.tvEngine)
        tvQueueBadge = view.findViewById(R.id.tvQueueBadge)
        tvPct = view.findViewById(R.id.tvProgressPct)
        progressBar = view.findViewById(R.id.progressTranscribe)
        btnMicCircle = view.findViewById(R.id.btnMicCircle)
        btnCloseKeyboard = view.findViewById(R.id.btnCloseKeyboard)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnSpace = view.findViewById(R.id.btnSpace)
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
            if (wasRecording) stopRecordingAndTranscribe()
            Toast.makeText(this, if (wasRecording || TranscriptionQueue.isActive()) "Stopped - text will still be typed in" else "Closed", Toast.LENGTH_SHORT).show()
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    if (!switchToPreviousInputMethod()) requestHideSelf(0)
                } else {
                    requestHideSelf(0)
                }
            } catch (_: Exception) { try { requestHideSelf(0) } catch (_: Exception) {} }
        }

        btnKeyboardGear?.setOnClickListener {
            try {
                val i = Intent(this, SettingsActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (_: Exception) {}
        }

        btnSpace?.setOnClickListener {
            try {
                currentInputConnection?.commitText(" ", 1)
            } catch (e: Exception) {
                AppLog.w(TAG, "Space failed: ${e.message}")
            }
        }

        btnBackspace?.setOnClickListener(null)
        btnBackspace?.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    try { currentInputConnection?.deleteSurroundingText(1, 0) } catch (_: Exception) {}
                    bsWordDelay = 400f
                    backspaceHandler.postDelayed(backspaceWordRepeater, 380)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    backspaceHandler.removeCallbacks(backspaceWordRepeater)
                    true
                }
                else -> true
            }
        }

        btnEnter?.setOnClickListener(null)
        btnEnter?.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    try { performEnter() } catch (_: Exception) {}
                    enterHandler.postDelayed(enterRepeater, 380)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    enterHandler.removeCallbacks(enterRepeater)
                    true
                }
                else -> true
            }
        }

        registerListenersOnce()
        btnTypeOutstanding?.setOnClickListener {
            val ic = currentInputConnection ?: activeIC
            if (ic == null) {
                Toast.makeText(this, "No editable field is focused", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val t = OutstandingStore.popOldest(this) ?: return@setOnClickListener
            val capped = capsFn?.invoke(t) ?: t
            val inserted = runCatching { ic.commitText("$capped ", 1) }.getOrDefault(false)
            if (!inserted) {

                OutstandingStore.requeueFront(this, t)
                Toast.makeText(this, "Unable to insert transcript", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val left = OutstandingStore.count(this)
            Toast.makeText(this, if (left > 0) "Typed - $left more pending" else "Typed - all done", Toast.LENGTH_SHORT).show()
            AppLog.i(TAG, "typed outstanding: ${t.take(50)}")
            updateOutstandingRow()
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

            fun confirmStop(attempt: Int) {
                if (!TranscriptionQueue.isActive() && !SttEngines.busy()) {
                    updateStatus("Processing stopped")
                    Toast.makeText(this, "Processing stopped", Toast.LENGTH_SHORT).show()
                    AppLog.i(TAG, "force stop confirmed")
                } else if (attempt < 20) handler.postDelayed({ confirmStop(attempt + 1) }, 150)
                else Toast.makeText(this, "Still stopping - model abort may take a moment", Toast.LENGTH_LONG).show()
            }
            handler.postDelayed({ confirmStop(0) }, 150)
        }

        registerListenersOnce()
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

        try { tvEngine?.text = SttEngines.badgeText(this) } catch (_: Exception) {}
        updateLabelsRow()
    }

    private fun updateLabelsRow() {
        val p = prefs()
        val chunkS = p.getInt("vad_chunk_silence_ds", 40) / 10f
        val stopS = p.getInt("vad_stop_silence_s", 10)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val mode = p.getString("threads_mode", "auto") ?: "auto"
        val thr = if (mode == "auto") (if (cores >= 8) 6 else if (cores >= 4) 4 else cores) else mode.toIntOrNull() ?: 4
        val chunkedKb = if (p.getBoolean("ime_chunked", true)) "chunked" else "whole"
        val effLang = SttEngines.jobLang(this)
        val parts = mutableListOf(
            getModel(),
            "lang:" + (langNames.getOrNull(langCodes.indexOf(effLang).coerceAtLeast(0)) ?: effLang),
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
        btnMicCircle?.setImageResource(
            if (recording) {
                R.drawable.ic_stop
            } else {
                R.drawable.ic_mic
            }
        )

        btnMicCircle?.contentDescription =
            if (recording) {
                getString(R.string.stop_recording)
            } else {
                getString(R.string.start_recording)
            }

        btnMicCircle?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (recording) {
                    0xFFE17055.toInt()
                } else {
                    0xFF00B894.toInt()
                }
            )
    }

    private fun applyCapsMode(text: String): String {
        if (getCapsMode() == "off") {
            return text
        }

        val connection =
            currentInputConnection ?: activeIC

        val before =
            runCatching {
                connection
                    ?.getTextBeforeCursor(2, 0)
                    ?.toString()
            }.getOrNull().orEmpty()

        val shouldCapitalize =
            before.isBlank() ||
                before.trimEnd()
                    .lastOrNull() in setOf('.', '!', '?', '\n')

        if (!shouldCapitalize) {
            return text
        }

        return text.replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(Locale.getDefault())
            } else {
                it.toString()
            }
        }
    }

    private fun startImeForeground() {

        try {
            val intent = Intent(this, ImeRecordService::class.java).apply { action = "START" }
            startService(intent)
        } catch (e: Exception) {

            AppLog.w(TAG, "ime holder unavailable, continuing without it: ${e.message}")
        }
    }
    private fun stopImeForeground() {

        try { stopService(Intent(this, ImeRecordService::class.java)) } catch (_: Exception) {}
    }

    private fun startRecording(firstSession: Boolean = false) {
        if (isRecording.get()) return
        if (recordThread?.isAlive == true) {
            updateStatus("Finishing previous recording...")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Need mic permission - open app"); return
        }
        val selModel = getModel()
        val selEngine = SttEngines.current(this)

        val selLang = SttEngines.jobLang(this)
        if (!SttEngines.isReady(this, selEngine, selModel)) {
            val missing = SttEngines.describeMissing(this, selEngine, selModel) ?: "not downloaded"
            updateStatus("$missing - open app")

            if (selModel.isNotEmpty()) Toast.makeText(this, "$missing - open the app to fix it", Toast.LENGTH_LONG).show()
            return
        }

        if (!SttEngines.isLoaded(this, selEngine, selModel)) preloadModel("startRecording")
        if (!MicSessionManager.tryAcquire(MicOwner.KEYBOARD)) {
            updateStatus("Microphone busy")
            return
        }
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
            var lastVoiceTime = android.os.SystemClock.elapsedRealtime()
            var sessionVoiceTime = android.os.SystemClock.elapsedRealtime()
            var chunkStartMs = android.os.SystemClock.elapsedRealtime()
            val recordStartMs = chunkStartMs
            val vadOn = isVadOn()
            val chunked = prefs().getBoolean("ime_chunked", true)
            val sessionStopMs = prefs().getInt("vad_stop_silence_s", 10).coerceIn(5, 60) * 1000L
            val targetChunkMs =
                prefs().getInt("chunk_target_s", 30).coerceIn(1, 45) * 1_000L
            val userChunkSilenceMs =
                prefs().getInt("vad_chunk_silence_ds", 40).coerceIn(5, 100) * 100L
            val maximumChunkMs =
                maxOf(
                    targetChunkMs + 15_000L,
                    60_000L
                )
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
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (rms > VAD_THRESH) { hasVoice = true; lastVoiceTime = now; sessionVoiceTime = now }
                        val silenceFor = now - lastVoiceTime
                        val durMs = now - chunkStartMs
                        if (firstSession) {
                            val sessionMs = now - recordStartMs

                            val stoppedBySilence =
                                hasVoice &&
                                silenceFor >= FIRST_SESSION_SILENCE_MS

                            val stoppedByMaximum =
                                sessionMs >= MAX_CHUNK_MS

                            val noSpeechTimeout =
                                !hasVoice &&
                                sessionMs >= FIRST_SESSION_NO_SPEECH_MS

                            if (
                                stoppedBySilence ||
                                stoppedByMaximum ||
                                noSpeechTimeout
                            ) {
                                Log.i(
                                    TAG,
                                    "First keyboard session auto-stop: " +
                                        "duration=${sessionMs}ms, " +
                                        "silence=${silenceFor}ms"
                                )

                                isRecording.set(false)

                                handler.post {
                                    updateStatus(
                                        if (stoppedBySilence) {
                                            "Auto-stopped after 2-second pause"
                                        } else {
                                            "Auto-stopped at 60-second maximum"
                                        }
                                    )
                                }

                                break
                            }
                        } else {
                            val hasContent =
                                pcmChunk.size() > 1_800

                            val safeSilence =
                                hasVoice &&
                                silenceFor >= userChunkSilenceMs

                            val closeChunk =
                                hasContent &&
                                chunked &&
                                (
                                    (
                                        durMs >= targetChunkMs &&
                                        safeSilence
                                    ) ||
                                    durMs >= maximumChunkMs
                                )

                            if (closeChunk) {
                                flushChunk(
                                    pcmChunk,
                                    selEngine,
                                    selModel,
                                    selLang
                                )

                                synchronized(pcmChunk) {
                                    pcmChunk.reset()
                                }

                                chunkStartMs = now
                                hasVoice = false
                                lastVoiceTime = now

                                Log.i(
                                    TAG,
                                    "Keyboard chunk closed: " +
                                        "duration=${durMs}ms, " +
                                        "silence=${silenceFor}ms"
                                )
                            }

                            val memoryLimitReached =
                                pcmChunk.size() >= MAX_IN_MEMORY_AUDIO_BYTES

                            if (memoryLimitReached) {
                                AppLog.w(
                                    TAG,
                                    "Maximum in-memory recording reached; flushing chunk"
                                )

                                flushChunk(
                                    pcmChunk,
                                    selEngine,
                                    selModel,
                                    selLang
                                )

                                synchronized(pcmChunk) {
                                    pcmChunk.reset()
                                }

                                chunkStartMs = now
                                hasVoice = false
                                lastVoiceTime = now
                            }

                            if (vadOn && now - sessionVoiceTime >= sessionStopMs) {
                                Log.i(TAG, "session VAD stop after ${now - sessionVoiceTime}ms silence")

                                isRecording.set(false)

                                handler.post {
                                    updateStatus("Auto-stopped after long silence")
                                    Toast.makeText(this@WhisperKeyboardService, "Auto-stopped after ${sessionStopMs / 1000}s of silence", Toast.LENGTH_SHORT).show()
                                }
                                break
                            }
                        }
                        } else if (read < 0) {
                            if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                                AppLog.e(TAG, "AudioRecord died (ERROR_DEAD_OBJECT)")
                            } else {
                                AppLog.e(TAG, "AudioRecord read error: $read")
                            }
                            break
                        }
                }
                try { recorder.stop() } catch (_: Exception) {}

                activeRecorder = null

                flushChunk(pcmChunk, selEngine, selModel, selLang)
            } catch (e: Throwable) {
                AppLog.e(TAG, "recording error: ${e.message}")
                handler.post { updateStatus("Error: ${e.message}"); Toast.makeText(this@WhisperKeyboardService, "Mic error: ${e.message}", Toast.LENGTH_LONG).show() }
                try { activeRecorder?.stop() } catch (_: Exception) {}
                activeRecorder = null
            } finally {
                isRecording.set(false)
                imeRecording = false

                activeRecorder = null

                MicSessionManager.release(MicOwner.KEYBOARD)

                if (stopHook != null) {
                    stopHook = null
                }

                handler.post {
                    resetMicButton()

                    val done =
                        !TranscriptionQueue.isActive() &&
                        TranscriptionQueue.pendingCount() == 0 &&
                        TextRouter.pendingTypingCount() == 0

                    updateStatus(
                        if (done) {
                            "All chunks processed"
                        } else {
                            "Ready - chunks still processing"
                        }
                    )

                    updateQueueBadge()
                }

                handler.postDelayed(
                    {
                        if (!isRecording.get()) {
                            stopImeForeground()
                        }
                    },
                    1_500L
                )

                recordThread = null
            }
        }.apply { isDaemon = true; start() }
    }

    private val chunkSequence =
        java.util.concurrent.atomic.AtomicLong(0L)

    private fun flushChunk(
        buffer: ByteArrayOutputStream,
        engine: String,
        model: String,
        lang: String
    ) {
        val bytes =
            synchronized(buffer) {
                buffer.toByteArray()
            }

        if (bytes.size <= 1_800) return

        val id =
            "${System.currentTimeMillis()}" +
            chunkSequence.incrementAndGet()

        val pcm =
            File(cacheDir, "ime_chunk$id.pcm")

        val wav =
            File(cacheDir, "ime_chunk_$id.wav")

        var enqueued = false

        try {
            FileOutputStream(pcm).use {
                it.write(bytes)
            }

            AudioUtils.pcmToWav(pcm, wav)

            if (!wav.isFile || wav.length() <= 44L) {
                throw java.io.IOException(
                    "Generated WAV is empty"
                )
            }

            val sec = bytes.size / 32000.0
            AppLog.i(TAG, "enqueue chunk $engine/$model %.1fs (%d KB)".format(sec, bytes.size / 1024))
            handler.post { Toast.makeText(this@WhisperKeyboardService, "Chunk %.0fs queued".format(sec), Toast.LENGTH_SHORT).show() }

            val job = TranscriptionQueue.Job(
                context = applicationContext,
                wavFile = wav,
                model = model,
                lang = lang,
                engine = engine,
                onResult = { text ->
                    TextRouter.route(text.trim())
                },
                onError = { error ->
                    AppLog.e(
                        TAG,
                        "Chunk failed: $error"
                    )

                    handler.post {
                        updateStatus(
                            "Chunk failed - use Retry Failed"
                        )
                        updateQueueBadge()
                    }
                }
            )

            enqueued =
                TranscriptionQueue.enqueue(job)

            if (!enqueued) {
                throw IllegalStateException(
                    "Queue rejected transcription chunk"
                )
            }
        } catch (e: Throwable) {
            AppLog.e(
                TAG,
                "Unable to flush chunk: ${e.message}"
            )

            handler.post {
                updateStatus(
                    "Unable to queue recorded audio"
                )
            }
        } finally {
            runCatching { pcm.delete() }

            if (!enqueued) {
                runCatching { wav.delete() }
            }
        }
        handler.post { updateQueueBadge() }
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
        val hasWork =
            TranscriptionQueue.pendingCount() > 0

        rowProcessing?.visibility =
            if (hasWork) {
                View.VISIBLE
            } else {
                View.GONE
            }

        btnSkipOne?.isEnabled =
            TranscriptionQueue.hasRunningJob()

        btnSkipOne?.alpha =
            if (btnSkipOne?.isEnabled == true) {
                1f
            } else {
                0.45f
            }

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

        refreshAllButtons()

        val expired = OutstandingStore.clearIfExpired(this)
        if (expired > 0) {
            Toast.makeText(this, "Cleared $expired expired pending transcript(s)", Toast.LENGTH_SHORT).show()
        }
        updateOutstandingRow()
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text =
            if (isRecording.get()) "REC"
            else "${TranscriptionQueue.progress()}%"
        updateQueueBadge()

        preloadModel("onStartInputView")

        val hasPending = TranscriptionQueue.isActive() ||
                TextRouter.pendingTypingCount() > 0 ||
                OutstandingStore.count(this) > 0
        if (hasPending) {

            autoStartRequested = false
            val typed = autoTypePending()
            if (TranscriptionQueue.isActive() || TextRouter.pendingTypingCount() > 0) {
                updateStatus("Processing... text will type in automatically")
            } else if (typed > 0) {
                updateStatus("Typed $typed pending transcript(s) ✓")
            }
        } else if (autoStartRequested && !isRecording.get()) {

            autoStartRequested = false

            if (KeyboardAutoStart.consumeIfAllowed()) {
                handler.postDelayed({
                    if (!isRecording.get()) {
                        AppLog.i(TAG, "First keyboard entry -> automatic recording")
                        startRecording(firstSession = true)
                    }
                }, 250)
            }
        }
    }

    private fun autoTypePending(): Int {
        val ic = currentInputConnection ?: activeIC ?: return 0
        var n = 0
        while (true) {
            val t = OutstandingStore.popOldest(this) ?: break
            if (t.isBlank()) continue
            val capped = capsFn?.invoke(t) ?: t
            val inserted = runCatching { ic.commitText("$capped ", 1) }.getOrDefault(false)
            if (!inserted) {

                OutstandingStore.requeueFront(this, t)
                break
            }
            n++
        }
        if (n > 0) {
            val left = OutstandingStore.count(this)
            Toast.makeText(this, if (left > 0) "Typed $n - $left more pending" else "Typed $n pending ✓", Toast.LENGTH_SHORT).show()
            AppLog.i(TAG, "auto-typed $n pending on return")
            updateOutstandingRow()
        }
        return n
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
        MicSessionManager.release(MicOwner.KEYBOARD)
        stopHook = null
        activeIC = null
        try { activeRecorder?.stop() } catch (_: Exception) {}

        activeRecorder = null
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        stopImeForeground()
        super.onDestroy()
    }
}
