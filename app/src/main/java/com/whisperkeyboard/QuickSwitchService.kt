package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Floating round mic bubble - 3 states:
 *  GREY   idle          : tap = start recording; long-press = switch keyboard
 *  GREEN  recording     : tap = stop recording (chunks keep processing)
 *  YELLOW processing    : tap = start a NEW recording (previous processing is not interrupted)
 * When all processing + typing finishes, yellow returns to grey.
 *
 * Delivery: typed into the focused field when possible, else parked in the
 * outstanding-transcript flow (notification + keyboard insert button).
 */
class QuickSwitchService : Service() {

    companion object {
        const val CHANNEL = "quick_switch"
        const val NOTIF_ID = 42
        const val WHISPER_IME = "com.whisperkeyboard/.WhisperKeyboardService"
        const val CHUNK_SILENCE_MS = 4000L
        const val CHUNK_TARGET_MS = 30_000L
        const val CHUNK_HARD_CAP_MS = 45_000L
        const val MIN_CHUNK_BYTES = 16000      // >=0.5s of audio before a chunk may close
        const val STATE_IDLE = 0
        const val STATE_REC = 1
        const val STATE_PROC = 2

        /** Readable by ProcessingService for strict idle-timeout accounting. */
        @Volatile var recActive = false
    }

    private var bubble: BubbleView? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var state = STATE_IDLE
    private var recThread: Thread? = null
    @Volatile private var activeBubbleRecorder: AudioRecord? = null
    @Volatile private var sessionHadVoice = false
    private var fnameCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick switch", NotificationManager.IMPORTANCE_MIN))
        }
        startForeground(NOTIF_ID, buildNotif())
        showBubble()
        AppLog.i("Bubble", "shown")
    }

    private fun buildNotif(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Whisper mic bubble")
            .setContentText("Tap: record. Long-press: switch keyboard.")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()

    private inner class BubbleView(context: Context) : View(context) {
        val dp = resources.displayMetrics.density
        val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6E0E0E0.toInt() }
        val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF200B894.toInt() }
        val procPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FDCB6E.toInt() }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0x66000000 }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 15f * dp; textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val fill = when (state) {
                STATE_REC -> recPaint
                STATE_PROC -> procPaint
                else -> idlePaint
            }
            val r = width / 2f - 1.5f * dp
            canvas.drawCircle(width / 2f, height / 2f, r, fill)
            canvas.drawCircle(width / 2f, height / 2f, r, ringPaint)
            val glyph = when (state) {
                STATE_REC -> "\u25A0"      // stop square
                STATE_PROC -> "..."        // processing
                else -> context.getString(R.string.mic_glyph) // mic
            }
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(glyph, width / 2f, y, textPaint)
        }
    }

    private fun applyAlpha() {
        val pct = getSharedPreferences("whisper", MODE_PRIVATE).getInt("bubble_alpha", 75)
        bubble?.alpha = (pct / 100.0f).coerceIn(0.15f, 1.0f)
    }

    private fun setState(s: Int, toastMsg: String?) {
        state = s
        bubble?.invalidate()
        if (!toastMsg.isNullOrEmpty()) toast(toastMsg)
    }

    private fun showBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val sizePx = (38 * dp).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 12
        params.y = 400

        val btn = BubbleView(this)

        var downX = 0f; var downY = 0f
        var startX = 0f; var startY = 0f
        var moved = false
        var downAt = 0L

        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x.toFloat(); startY = params.y.toFloat()
                    moved = false
                    downAt = android.os.SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && dx * dx + dy * dy > 144) moved = true
                    if (moved && !recActive) {
                        params.x = startX.toInt() + dx.toInt(); params.y = startY.toInt() + dy.toInt()
                        wm?.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val heldLongEnough = android.os.SystemClock.uptimeMillis() - downAt >= 450
                    when {
                        !moved -> v.performClick()                       // tap: record toggle
                        heldLongEnough && !recActive -> v.performLongClick() // deliberate long-press+drag: switch keyboard
                        // short drag or drag-while-recording = just reposition, no action
                    }
                    true
                }
                else -> false
            }
        }
        btn.setOnClickListener { handleTap() }
        btn.setOnLongClickListener { toggleIme(); true }
        bubble = btn
        try { wm?.addView(btn, params); applyAlpha() } catch (e: Exception) { AppLog.e("Bubble", "overlay failed: ${e.message}") }
    }

    /** Tap behavior per state: IDLE->record, REC->stop(+process), PROC->start new session too. */
    private fun handleTap() {
        when (state) {
            STATE_IDLE -> startRec()
            STATE_REC -> stopRec()
            STATE_PROC -> startRec() // previous processing continues untouched
        }
    }

    private fun startRec() {
        if (recActive) return
        if (WhisperKeyboardService.imeRecording) {
            toast("Keyboard is already recording - stop it first")
            return
        }
        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        val model = prefs.getString("model", "small") ?: "small"
        val mf = ModelManager.modelFile(this, model)
        if (!mf.exists() || mf.length() < 1_000_000) {
            toast("$model model not downloaded - open the app")
            return
        }
        ensureWhisperActive() // best effort so delivery can type directly
        // start recording NOW; load the model concurrently (first chunk waits in queue)
        if (!WhisperEngine.isLoaded(mf.absolutePath)) {
            Thread {
                WhisperEngine.applyThreadPref(this)
                WhisperEngine.ensureModel(mf.absolutePath)
            }.apply { isDaemon = true; name = "bubble-model-load"; start() }
        }
        sessionHadVoice = false
        recActive = true
        setState(STATE_REC, null)
        toast("Recording started - tap to stop")
        AppLog.i("Bubble", "recording started")
        // keep CPU alive while locked so recording never stalls
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Whisper:BubbleRec")
        wakeLock.acquire(30 * 60 * 1000L)
        val lang = prefs.getString("lang", "auto") ?: "auto"
        // per-session buffer: a fast stop/start must never share audio with the dying session
        val pcmChunk = ByteArrayOutputStream()
        val prevThread = recThread
        recThread = Thread {
            var lastVoiceTime = System.currentTimeMillis()
            var chunkStartMs = lastVoiceTime
            var gotVoice = false
            var chunksEnqueued = 0
            val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
            val chunked = prefs.getBoolean("bubble_chunked", true)
            // unified timing with the keyboard interface (Settings page)
            val chunkSilenceMs = prefs.getInt("vad_chunk_silence_ds", 40).coerceIn(5, 100) * 100L
            val chunkTargetMs = prefs.getInt("chunk_target_s", 30).coerceIn(1, 45) * 1000L
            val recStartMs = System.currentTimeMillis()
            try {
                // make sure the previous session's recorder/thread is fully gone before capturing
                try { prevThread?.join(800) } catch (_: Exception) {}
                // BT-aware factory (same as keyboard): respects bt_mic pref incl. SCO + AGC
                val rec = AudioUtils.createRecorder(this)
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Mic init failed (state=${rec.state})")
                activeBubbleRecorder = rec

                // Some devices update recordingState asynchronously - retry instead of failing fast.
                var started = false
                var lastErr: Exception? = null
                repeat(4) { attempt ->
                    if (started) return@repeat
                    try {
                        rec.startRecording()
                        Thread.sleep(80)
                        if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) started = true
                        else AppLog.w("Bubble", "start attempt $attempt not recording (state=${rec.recordingState})")
                    } catch (e: Exception) { lastErr = e; AppLog.w("Bubble", "start attempt ${e.message}") }
                }
                if (!started) throw IllegalStateException("Mic did not start${lastErr?.let { ": ${it.message}" } ?: ""}")
                AppLog.i("Bubble", "mic started ok")

                val buf = ByteArray(4096)
                var gotFirstAudio = false
                var badReads = 0
                while (recActive) {
                    val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { AppLog.e("Bubble", "read threw: ${e.message}"); -1 }
                    if (n <= 0) {
                        // transient errors happen (BT handoff etc.) - restart once, bail after streak
                        badReads++
                        AppLog.w("Bubble", "read=$n badStreak=$badReads recActive=$recActive")
                        if (badReads == 3) { try { rec.startRecording() } catch (_: Exception) {} }
                        if (badReads >= 8) { AppLog.e("Bubble", "giving up after $badReads bad reads"); break }
                        continue
                    }
                    if (!gotFirstAudio) { gotFirstAudio = true; AppLog.i("Bubble", "audio flowing ($n bytes/frame)") }
                    badReads = 0
                    val rms = AudioUtils.rms16(buf, n)
                    val now = System.currentTimeMillis()
                    val voiced = rms > 0.008 // bubble: lenient gate; transcription decides speech vs noise
                    // always buffer - preserves the very start of speech (prevents front cut)
                    synchronized(pcmChunk) { pcmChunk.write(buf, 0, n) }
                    if (voiced) { gotVoice = true; sessionHadVoice = true; lastVoiceTime = now }
                    if (gotVoice) {
                        val silenceFor = now - lastVoiceTime
                        val durMs = now - chunkStartMs
                        val hasContent = pcmChunk.size() >= MIN_CHUNK_BYTES // >=0.5s of audio
                        val inFirstWindow = now - recStartMs < 15_000
                        val queueBusy = TranscriptionQueue.isActive()
                        val closeChunk = when {
                            !hasContent -> false
                            !chunked -> durMs >= 900_000L // whole-audio mode: single piece (15-min safety split)
                            inFirstWindow -> (durMs >= 15_000 && silenceFor >= WhisperKeyboardService.CHUNK_PAUSE_MS) || silenceFor >= 3_000
                            else -> (durMs >= chunkTargetMs && !queueBusy) ||
                                    silenceFor >= chunkSilenceMs ||
                                    durMs >= CHUNK_HARD_CAP_MS
                        }
                        if (closeChunk) {
                            if (flushChunk(pcmChunk, model, lang)) chunksEnqueued++
                            synchronized(pcmChunk) { pcmChunk.reset() }
                            chunkStartMs = now
                            lastVoiceTime = now
                        }
                    }
                }
                AppLog.i("Bubble", "loop exited (recActive=$recActive, chunks=$chunksEnqueued, gotVoice=$gotVoice)")
                try { rec.stop(); rec.release() } catch (_: Exception) {}
                activeBubbleRecorder = null
                if (flushChunk(pcmChunk, model, lang)) chunksEnqueued++
                finalFlushPending = false
                if (chunksEnqueued == 0 && gotVoice) {
                    // had voice but all chunks dropped as silent? try once more leniently
                    AppLog.w("Bubble", "no chunks enqueued despite voice - forcing final")
                } else if (chunksEnqueued == 0) {
                    handler.post { toast("No speech detected") }
                }
            } catch (e: Throwable) {
                AppLog.e("Bubble", "rec error: ${e.message}")
                handler.post { toast("Mic error: ${e.message}") }
            } finally {
                finalFlushPending = false // crash-safe: never leave the watcher stuck
                try { wakeLock.release() } catch (_: Exception) {}
                handler.post {
                    if (state == STATE_REC) enterProcessing()
                }
            }
        }.apply { isDaemon = true; name = "bubble-rec"; start() }
    }

    @Volatile private var finalFlushPending = false

    private fun stopRec() {
        if (!recActive) return
        recActive = false
        try { activeBubbleRecorder?.stop() } catch (_: Exception) {} // unblock read() NOW
        finalFlushPending = true
        setState(STATE_PROC, "Recording stopped - processing...")
        AppLog.i("Bubble", "recording stopped by tap")
        watchCompletion() // drive yellow -> grey once everything is delivered
    }

    private fun enterProcessing() {
        setState(STATE_PROC, null)
        watchCompletion()
    }

    /** Yellow until every chunk is transcribed AND delivered (typed or parked). */
    private fun watchCompletion(attempt: Int = 0) {
        if (state != STATE_PROC) return // a new recording took over
        val busy = TranscriptionQueue.isActive() || TextRouter.pendingTypingCount() > 0 || finalFlushPending
        if (busy) {
            handler.postDelayed({ watchCompletion(attempt + 1) }, 500)
        } else {
            setState(STATE_IDLE, "Done - transcript delivered or held for later")
            AppLog.i("Bubble", "processing complete -> idle")
        }
    }

    /** Write one chunk WAV and enqueue; delivery handled by TextRouter. Returns true if enqueued. */
    private fun flushChunk(buffer: ByteArrayOutputStream, model: String, lang: String): Boolean {
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        if (bytes.size < MIN_CHUNK_BYTES) return false
        // quick silence check - discard dead-air chunks without wasting transcription
        var peak = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = kotlin.math.abs(((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
            if (sample > peak) peak = sample
            i += 2
        }
        if (peak < 200) { // ~0.6% full scale - very lenient, only drop true silence
            AppLog.i("Bubble", "silent chunk dropped (peak=$peak)")
            return false
        }
        val ts = System.currentTimeMillis()
        fnameCounter++
        val pcm = File(cacheDir, "bubble_${ts}_$fnameCounter.pcm")
        FileOutputStream(pcm).use { it.write(bytes) }
        val wav = File(cacheDir, "bubble_${ts}_$fnameCounter.wav")
        AudioUtils.pcmToWav(pcm, wav)
        pcm.delete()
        TranscriptionQueue.enqueue(
            TranscriptionQueue.Job(
                context = applicationContext,
                wavFile = wav,
                model = model,
                lang = lang,
                onResult = { text ->
                    val raw = text.trim()
                    handler.post {
                        if (raw.isEmpty() || raw.startsWith("ERROR") || AudioUtils.isNoSpeechText(raw)) {
                            toast(if (raw.startsWith("ERROR")) "Chunk failed" else "No speech in chunk")
                        } else {
                            TextRouter.route(raw)
                        }
                    }
                },
                onError = { err ->
                    handler.post { toast("Transcription failed - Retry Failed in app") }
                    AppLog.e("Bubble", "chunk failed: $err")
                }
            )
        )
        ProcessingService.notifyActivity()
        return true
    }

    private fun toast(msg: String) {
        handler.post {
            try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** Best-effort: make Whisper the active IME so delivery can type directly (needs WRITE_SECURE_SETTINGS). */
    private fun ensureWhisperActive(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            if (current.equals(WHISPER_IME, ignoreCase = true)) return true
            getSharedPreferences("whisper", MODE_PRIVATE).edit().putString("prev_ime", current).apply()
            try {
                Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, WHISPER_IME)
                true
            } catch (e: SecurityException) { false }
        } catch (e: Exception) { false }
    }

    /** Switch to Whisper IME / back. Without WRITE_SECURE_SETTINGS this silently opens the system picker. */
    private fun toggleIme() {
        try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
            val goingWhisper = !current.equals(WHISPER_IME, ignoreCase = true)
            val target = if (goingWhisper) {
                prefs.edit().putString("prev_ime", current).apply()
                WHISPER_IME
            } else {
                prefs.getString("prev_ime", "") ?: ""
            }
            if (target.isNotEmpty()) {
                try {
                    Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, target)
                    toast(if (goingWhisper) "Whisper keyboard ON" else "Default keyboard restored")
                    return
                } catch (e: SecurityException) {
                    // no WRITE_SECURE_SETTINGS - system picker handles it silently
                }
            }
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
        } catch (e: Exception) {
            AppLog.e("Bubble", "toggle error: ${e.message}")
        }
    }

    override fun onDestroy() {
        recActive = false
        handler.removeCallbacksAndMessages(null)
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        AppLog.i("Bubble", "removed")
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else -> {
                val alpha = intent?.getIntExtra("alpha", -1) ?: -1
                if (alpha > 0) { applyAlpha(); AppLog.i("Bubble", "alpha -> $alpha%") }
            }
        }
        return START_STICKY
    }
}
