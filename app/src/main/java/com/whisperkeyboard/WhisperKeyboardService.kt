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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
    private var btnVad: Button? = null
    private var btnLive: Button? = null
    private var btnBt: Button? = null
    private var btnCaps: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val previewExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "live-preview").apply { isDaemon = true } }
    @Volatile private var previewBusy = false
    @Volatile private var lastComposing = ""
    @Volatile private var liveConsumedBytes = 0L

    override fun onCreate() {
        super.onCreate()
        preloadModel("onCreate")
    }

    private fun preloadModel(from: String) {
        Thread {
            try {
                val mf = ModelManager.modelFile(this, getModel())
                if (mf.exists() && mf.length() > 1_000_000 && !WhisperEngine.isLoaded(mf.absolutePath)) {
                    handler.post { updateStatus("Loading ${getModel()} model...") }
                    val ok = WhisperEngine.ensureModel(mf.absolutePath)
                    handler.post { updateStatus(if (ok) "${getModel()} ready - tap Speak" else "Model load failed - will retry on Speak") }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "model-preload-$from"; start() }
    }

    /** Unload model only when idle (not recording, queue empty). Used when interface closes. */
    private fun maybeUnload(reason: String) {
        if (isRecording.get()) { Log.i(TAG, "Skip unload ($reason) - recording"); return }
        if (previewBusy) { Log.i(TAG, "Skip unload ($reason) - live preview"); return }
        if (TranscriptionQueue.isActive()) { Log.i(TAG, "Skip unload ($reason) - queue busy"); return }
        WhisperEngine.unloadIfIdle()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post { refreshAllButtons(); if (key in listOf("model","lang","entry_mode","vad_on","live_on","bt_mic","caps_mode")) updateStatus("Settings saved: $key") }
    }

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        override fun onProgress(pct: Int) {
            handler.post {
                progressBar?.progress = pct
                tvPct?.text = "$pct%"
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
        btnVad = view.findViewById(R.id.btnVad)
        btnLive = view.findViewById(R.id.btnLive)
        btnBt = view.findViewById(R.id.btnBt)
        btnCaps = view.findViewById(R.id.btnCaps)

        btnMic?.setOnClickListener { if (!isRecording.get()) startRecording() }
        // Long-press mic: voice input selection (pick another voice input method)
        btnMic?.setOnLongClickListener {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
                updateStatus("Pick a voice input method...")
            } catch (_: Exception) {}
            true
        }
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
            else { TranscriptionQueue.retryFailed(); Toast.makeText(this, "Retrying $c failed", Toast.LENGTH_SHORT).show(); updateStatus("Retrying $c failed...") }
            updateQueueBadge()
        }
        btnModelTiny?.setOnClickListener { setModel("tiny") }
        btnModelBase?.setOnClickListener { setModel("base") }
        btnModelSmall?.setOnClickListener { setModel("small") }
        btnModelMedium?.setOnClickListener { setModel("medium") }
        btnModeType?.setOnClickListener { setEntryMode("type") }
        btnModeTxt?.setOnClickListener { setEntryMode("txt") }
        btnModeBoth?.setOnClickListener { setEntryMode("both") }
        btnVad?.setOnClickListener { toggleVad() }
        btnLive?.setOnClickListener { toggleLive() }
        btnBt?.setOnClickListener { toggleBt() }
        btnCaps?.setOnClickListener { toggleCaps() }

        TranscriptionQueue.addListener(pqListener)
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        refreshAllButtons()
        updateStatus("Tap Speak to start")
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = "${TranscriptionQueue.progress()}%"
        updateQueueBadge()
        return view
    }

    private fun prefs() = getSharedPreferences("whisper", MODE_PRIVATE)
    private fun getModel(): String = prefs().getString("model", "small") ?: "small"
    private fun getLang(): String = prefs().getString("lang", "auto") ?: "auto"
    private fun getEntryMode(): String = prefs().getString("entry_mode", "type") ?: "type"
    private fun isVadOn(): Boolean = prefs().getBoolean("vad_on", true)
    private fun isLiveOn(): Boolean = prefs().getBoolean("live_on", true)
    private fun isBtOn(): Boolean = prefs().getBoolean("bt_mic", false)
    private fun getCapsMode(): String = prefs().getString("caps_mode", "auto") ?: "auto"

    private fun setModel(m: String) {
        prefs().edit().putString("model", m).apply()
        refreshAllButtons()
        val mf = ModelManager.modelFile(this, m)
        if (mf.exists() && mf.length() > 1_000_000) {
            if (WhisperEngine.isLoaded(mf.absolutePath)) {
                Toast.makeText(this, "Model: $m", Toast.LENGTH_SHORT).show(); updateStatus("$m ready")
            } else {
                Toast.makeText(this, "Loading model: $m", Toast.LENGTH_SHORT).show(); updateStatus("Loading $m...")
                preloadModel("setModel")
            }
        } else {
            Toast.makeText(this, "$m not downloaded", Toast.LENGTH_SHORT).show(); updateStatus("$m not downloaded - open app to download")
        }
    }
    private fun setEntryMode(mode: String) { prefs().edit().putString("entry_mode", mode).apply(); refreshAllButtons(); val label = when(mode){"type"->"Type";"txt"->"Save TXT";else->"Both"}; Toast.makeText(this, label, Toast.LENGTH_SHORT).show(); updateStatus("$label - ready") }
    private fun toggleVad() { val v = !isVadOn(); prefs().edit().putBoolean("vad_on", v).apply(); refreshAllButtons(); Toast.makeText(this, if(v) "VAD auto-stop ON" else "VAD OFF", Toast.LENGTH_SHORT).show() }
    private fun toggleLive() { val v = !isLiveOn(); prefs().edit().putBoolean("live_on", v).apply(); refreshAllButtons(); Toast.makeText(this, if(v) "LIVE typing ON" else "LIVE OFF", Toast.LENGTH_SHORT).show() }
    private fun toggleBt() { val v = !isBtOn(); prefs().edit().putBoolean("bt_mic", v).apply(); refreshAllButtons(); Toast.makeText(this, if(v) "Bluetooth mic ON" else "Phone mic", Toast.LENGTH_SHORT).show() }
    private fun toggleCaps() { val cur = getCapsMode(); val next = when(cur){"auto"->"on";"on"->"off";else->"auto"}; prefs().edit().putString("caps_mode", next).apply(); refreshAllButtons(); Toast.makeText(this, "Caps: $next", Toast.LENGTH_SHORT).show() }

    private fun refreshAllButtons() {
        val curM = getModel()
        val active = 0xFF00B894.toInt(); val idle = 0xFF636E72.toInt()
        try {
            btnModelTiny?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="tiny") active else idle)
            btnModelBase?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="base") active else idle)
            btnModelSmall?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="small") active else idle)
            btnModelMedium?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curM=="medium") active else idle)
        } catch (_: Exception) {}
        val curE = getEntryMode()
        try {
            btnModeType?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curE=="type") active else idle)
            btnModeTxt?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curE=="txt") active else idle)
            btnModeBoth?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(curE=="both") active else idle)
        } catch (_: Exception) {}
        try {
            btnVad?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(isVadOn()) active else idle)
            btnVad?.text = if(isVadOn()) "VAD: ON" else "VAD: OFF"
            btnLive?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(isLiveOn()) active else idle)
            btnLive?.text = if(isLiveOn()) "LIVE: ON" else "LIVE: OFF"
            btnBt?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(isBtOn()) active else idle)
            btnBt?.text = if(isBtOn()) "BT: ON" else "BT: OFF"
            val caps = getCapsMode()
            btnCaps?.backgroundTintList = android.content.res.ColorStateList.valueOf(if(caps!="off") active else idle)
            btnCaps?.text = "Caps: ${caps.uppercase()}"
        } catch (_: Exception) {}
    }

    private fun applyCapsMode(text: String): String {
        val mode = getCapsMode()
        if (mode == "off") return text.lowercase()
        if (mode == "auto") return text
        // "on": sentence caps
        return text.split(Regex("(?<=[.!?])\\s+")).joinToString(" ") { s ->
            s.replaceFirstChar { if(it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

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

    private fun startImeForeground() {
        try {
            val intent = Intent(this, ImeRecordService::class.java).apply { action = "START" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { Log.w(TAG, "foreground start failed: ${e.message}") }
    }
    private fun stopImeForeground() {
        try { val intent = Intent(this, ImeRecordService::class.java).apply { action = "STOP" }; startService(intent) } catch (_: Exception) {}
    }

    private fun startRecording() {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Need mic permission - open app"); return
        }
        isRecording.set(true)
        startImeForeground()
        btnMic?.isEnabled = false; btnStop?.isEnabled = true; btnMic?.text = "Recording..."
        try { btnMic?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE17055.toInt()) } catch (_: Exception) {}
        updateStatus("Listening... tap Stop (VAD auto-stops)")
        progressBar?.progress = 0; tvPct?.text = "REC"
        lastComposing = ""
        try { currentInputConnection?.setComposingText("", 1) } catch (_: Exception) {}

        recordThread = Thread {
            var pcmFile: File? = null
            val livePcm = ByteArrayOutputStream()
            var hasSpoken = false
            var lastVoiceTime = System.currentTimeMillis()
            var lastPreviewTime = System.currentTimeMillis()
            var usedLivePreview = false
            val vadThresh = 0.018
            val vadSilenceMs = 1300L
            val liveOn = isLiveOn()
            val vadOn = isVadOn()

            try {
                val recorder = AudioUtils.createRecorder(this)
                activeRecorder = recorder
                recorder.startRecording()
                pcmFile = File(cacheDir, "ime_rec_${System.currentTimeMillis()}.pcm")
                FileOutputStream(pcmFile).use { fos ->
                    val buffer = ByteArray(4096)
                    while (isRecording.get()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            fos.write(buffer, 0, read)
                            synchronized(livePcm) { livePcm.write(buffer, 0, read) }
                            // VAD
                            val rms = AudioUtils.rms16(buffer, read)
                            val now = System.currentTimeMillis()
                            if (rms > vadThresh) { hasSpoken = true; lastVoiceTime = now }
                            else if (vadOn && hasSpoken && now - lastVoiceTime > vadSilenceMs) {
                                Log.i(TAG, "VAD auto-stop rms=$rms silence ${now-lastVoiceTime}ms")
                                handler.post { updateStatus("VAD auto-stop..."); stopRecordingAndTranscribe() }
                                break
                            }
                            // LIVE preview every 1.6s
                            if (liveOn && !previewBusy && now - lastPreviewTime > 1600 && livePcm.size() > 9000) {
                                lastPreviewTime = now
                                val snapshot: ByteArray
                                synchronized(livePcm) {
                                    snapshot = livePcm.toByteArray()
                                    liveConsumedBytes = snapshot.size.toLong() // bytes already shown via LIVE
                                }
                                // skip too short
                                if (snapshot.size > 9000) {
                                    previewBusy = true
                                    val model = getModel(); val lang = getLang()
                                    val previewWav = File(cacheDir, "live_preview_${System.currentTimeMillis()}.wav")
                                    try {
                                        AudioUtils.pcmBytesToWavFile(snapshot, previewWav)
                                        previewExecutor.submit {
                                            try {
                                                val mf = ModelManager.modelFile(this@WhisperKeyboardService, model)
                                                if (mf.exists()) {
                                                    val txt = WhisperEngine.transcribe(mf.absolutePath, previewWav.absolutePath, lang).trim()
                                                    if (txt.isNotEmpty() && !txt.startsWith("ERROR") && !AudioUtils.isNoSpeechText(txt)) {
                                                        usedLivePreview = true
                                                        val capped = applyCapsMode(txt)
                                                        lastComposing = capped
                                                        handler.post {
                                                            try { currentInputConnection?.setComposingText(capped, 1) } catch (_: Exception) {}
                                                            tvStatus?.text = "LIVE: $capped"
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) { Log.w(TAG, "live preview fail: ${e.message}") }
                                            finally { try { previewWav.delete() } catch (_: Exception) {}; previewBusy = false }
                                        }
                                    } catch (e: Exception) { previewBusy = false }
                                }
                            }
                        } else if (read < 0) break
                    }
                }
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                activeRecorder = null
                // wait briefly for any in-flight LIVE preview so tail math is correct
                var waitMs = 0
                while (previewBusy && waitMs < 3000) { Thread.sleep(100); waitMs += 100 }
                // clear composing before final commit (finalizes LIVE text into the field)
                handler.post { try { currentInputConnection?.finishComposingText() } catch (_: Exception) {} }

                // decide what still needs transcription:
                // - LIVE shown text -> only the outstanding tail since the last preview chunk
                // - otherwise -> full recording
                val useTail = liveOn && usedLivePreview && lastComposing.isNotEmpty() && liveConsumedBytes > 0
                var wavFile: File? = null
                if (pcmFile != null && pcmFile.exists() && pcmFile.length() > 1800) {
                    if (useTail) {
                        val total = pcmFile!!.length()
                        val tailLen = total - liveConsumedBytes
                        if (tailLen > 16000) { // > ~0.5s of speech worth transcribing
                            val ts = System.currentTimeMillis()
                            val tailPcm = File(cacheDir, "ime_tail_$ts.pcm")
                            java.io.RandomAccessFile(pcmFile!!, "r").use { raf ->
                                raf.seek(liveConsumedBytes)
                                val buf = ByteArray(8192)
                                var remaining = tailLen
                                FileOutputStream(tailPcm).use { tOut ->
                                    while (remaining > 0) {
                                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                        if (n < 0) break
                                        tOut.write(buf, 0, n)
                                        remaining -= n
                                    }
                                }
                            }
                            wavFile = File(cacheDir, "ime_tail_$ts.wav")
                            AudioUtils.pcmToWav(tailPcm, wavFile)
                            tailPcm.delete()
                            pcmFile!!.delete()
                            Log.i(TAG, "LIVE tail mode: total=$total consumed=$liveConsumedBytes tail=$tailLen")
                        } else {
                            Log.i(TAG, "LIVE tail too short ($tailLen bytes) - keeping LIVE text only")
                            pcmFile!!.delete()
                        }
                    } else {
                        wavFile = File(cacheDir, "ime_rec_${System.currentTimeMillis()}.wav")
                        AudioUtils.pcmToWav(pcmFile!!, wavFile)
                        pcmFile!!.delete()
                    }
                }
                if (wavFile != null && wavFile.exists() && wavFile.length() > 1800) {
                    val model = getModel(); val lang = getLang(); val entryMode = getEntryMode()
                    val livePrefix = if (useTail) lastComposing else ""
                    TranscriptionQueue.enqueue(
                        TranscriptionQueue.Job(
                            context = this@WhisperKeyboardService,
                            wavFile = wavFile,
                            model = model,
                            lang = lang,
                            onResult = { text ->
                                handler.post {
                                    var t = text.trim()
                                    if (AudioUtils.isNoSpeechText(t)) {
                                        updateStatus(if (useTail) "Done (LIVE text kept)" else "No speech - filtered")
                                        if (!useTail) try { currentInputConnection?.setComposingText("", 1); currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                                    } else {
                                        t = applyCapsMode(t)
                                        when (entryMode) {
                                            "type" -> currentInputConnection?.commitText(t + " ", 1)
                                            "txt" -> saveToTxtFile((if (livePrefix.isNotEmpty()) "$livePrefix $t" else t))
                                            "both" -> {
                                                currentInputConnection?.commitText(t + " ", 1)
                                                saveToTxtFile((if (livePrefix.isNotEmpty()) "$livePrefix $t" else t))
                                            }
                                        }
                                        updateStatus("Done: ${(if (livePrefix.isNotEmpty()) "$livePrefix $t" else t).take(60)}")
                                    }
                                    updateQueueBadge()
                                }
                            },
                            onError = { error ->
                                handler.post {
                                    try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                                    updateStatus("Error saved - Retry. $error")
                                    Toast.makeText(this@WhisperKeyboardService, "Saved failed - Retry", Toast.LENGTH_LONG).show()
                                    updateQueueBadge()
                                }
                            }
                        )
                    )
                    handler.post { resetMicButton(); updateStatus("Queued - ready for next. ${TranscriptionQueue.status()}"); updateQueueBadge() }
                } else {
                    wavFile?.delete()
                    handler.post {
                        if (usedLivePreview) { resetMicButton(); updateStatus("Done: ${lastComposing.take(60)}") }
                        else { try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}; updateStatus("Too short - ready"); resetMicButton() }
                    }
                }
                lastComposing = ""
                liveConsumedBytes = 0
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
                pcmFile?.delete()
                handler.post { try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}; updateStatus("Error: ${e.message} - ready"); resetMicButton() }
                try { activeRecorder?.release() } catch (_: Exception) {}
                activeRecorder = null
                lastComposing = ""
                liveConsumedBytes = 0
            } finally {
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
        btnMic?.isEnabled = true; btnStop?.isEnabled = false; btnMic?.text = "Speak"
        try { btnMic?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00B894.toInt()) } catch (_: Exception) {}
        refreshAllButtons()
    }

    private fun updateStatus(text: String) { tvStatus?.text = text }

    private fun updateQueueBadge() {
        val s = TranscriptionQueue.status()
        tvQueueBadge?.text = s
        btnPause?.text = if (TranscriptionQueue.isPaused()) "Resume" else "Pause"
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
        // interface shown -> make sure the selected model is loaded (fast no-op if cached)
        preloadModel("onStartInputView")
        refreshAllButtons()
        progressBar?.progress = TranscriptionQueue.progress()
        tvPct?.text = "${TranscriptionQueue.progress()}%"
        updateQueueBadge()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput) {
            // interface switched away/closed -> unload when idle (recording/queue keep it alive)
            handler.postDelayed({ maybeUnload("inputFinished") }, 1500)
        }
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
