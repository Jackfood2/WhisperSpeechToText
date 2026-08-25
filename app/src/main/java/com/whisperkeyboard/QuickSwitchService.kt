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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Floating round mic bubble:
 *  - Tap: switch to Whisper keyboard / back to previous keyboard
 *  - Hold (>=280ms): push-to-talk - records while held, transcribes on release
 *  - Drag: move anywhere
 *
 * Text goes into the focused field if the Whisper keyboard owns an input connection,
 * otherwise saved to Documents/WhisperNotes/.
 */
class QuickSwitchService : Service() {

    private companion object {
        const val CHANNEL = "quick_switch"
        const val NOTIF_ID = 42
        const val WHISPER_IME = "com.whisperkeyboard/.WhisperKeyboardService"
        const val HOLD_MS = 280L
        const val MAX_PTT_MS = 60_000L
    }

    private var bubble: View? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // push-to-talk state
    @Volatile private var pttRecording = false
    private var pttThread: Thread? = null
    private var pttBuffer = ByteArrayOutputStream()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick switch", NotificationManager.IMPORTANCE_MIN))
        }
        startForeground(NOTIF_ID, buildNotif())
        showBubble()
        AppLog.i("QuickSwitch", "bubble shown")
    }

    private fun buildNotif(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Whisper mic bubble")
            .setContentText("Tap: switch keyboard • Hold: talk")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()

    private inner class BubbleView(context: Context) : View(context) {
        val dp = resources.displayMetrics.density
        val greyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE0E0E0.toInt() }
        val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FF5252.toInt() }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0x66000000
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 15f * dp; textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f - 1.5f * dp
            canvas.drawCircle(width / 2f, height / 2f, r, if (pttRecording) recPaint else greyPaint)
            canvas.drawCircle(width / 2f, height / 2f, r, ringPaint)
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("🎤", width / 2f, y, textPaint)
        }
    }

    private fun showBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val sizePx = (38 * dp).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 12
        params.y = 400

        val btn = BubbleView(this)

        var downX = 0f; var downY = 0f
        var startX = 0f; var startY = 0f
        var moved = false

        val holdRunnable = Runnable { startPtt() }

        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x.toFloat(); startY = params.y.toFloat()
                    moved = false
                    handler.postDelayed(holdRunnable, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && dx * dx + dy * dy > 144) { moved = true; handler.removeCallbacks(holdRunnable) }
                    if (moved && !pttRecording) {
                        params.x = startX.toInt() + dx.toInt(); params.y = startY.toInt() + dy.toInt()
                        wm?.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    if (pttRecording) stopPtt()
                    else if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
        btn.setOnClickListener { toggleIme() }
        bubble = btn
        try { wm?.addView(btn, params); applyAlpha() } catch (e: Exception) { AppLog.e("QuickSwitch", "overlay failed: ${e.message}") }
    }

    private fun applyAlpha() {
        val pct = getSharedPreferences("whisper", MODE_PRIVATE).getInt("bubble_alpha", 75)
        bubble?.alpha = (pct / 100.0f).coerceIn(0.15f, 1.0f)
    }

    // ---------- Push-to-talk ----------

    private fun startPtt() {
        if (pttRecording) return
        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        val model = prefs.getString("model", "small") ?: "small"
        val mf = ModelManager.modelFile(this, model)
        if (!mf.exists() || mf.length() < 1_000_000) {
            toast("$model model not downloaded - open app")
            return
        }
        pttRecording = true
        pttBuffer = ByteArrayOutputStream()
        bubble?.invalidate()
        toast("Recording... release to transcribe")
        AppLog.i("QuickSwitch", "PTT start")
        val lang = prefs.getString("lang", "auto") ?: "auto"
        pttThread = Thread {
            try {
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32000))
                rec.startRecording()
                val buf = ByteArray(4096)
                val start = System.currentTimeMillis()
                while (pttRecording && System.currentTimeMillis() - start < MAX_PTT_MS) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) synchronized(pttBuffer) { pttBuffer.write(buf, 0, n) }
                }
                try { rec.stop(); rec.release() } catch (_: Exception) {}
                if (!pttRecording || System.currentTimeMillis() - start >= MAX_PTT_MS) finishPtt(model, lang)
            } catch (e: Throwable) {
                AppLog.e("QuickSwitch", "PTT error: ${e.message}")
                handler.post { toast("Mic error: ${e.message}") }
                pttRecording = false
                handler.post { bubble?.invalidate() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopPtt() {
        if (!pttRecording) return
        pttRecording = false
        // Switch to Whisper NOW so its IME owns the field by the time transcription finishes
        val switched = ensureWhisperActive()
        if (switched) toast("Switching to Whisper keyboard...")
        handler.post { bubble?.invalidate() }
    }

    /** Returns true if Whisper IME is (or now is) the active input method. */
    private fun ensureWhisperActive(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            if (current.equals(WHISPER_IME, ignoreCase = true)) return true
            getSharedPreferences("whisper", MODE_PRIVATE).edit().putString("prev_ime", current).apply()
            try {
                Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, WHISPER_IME)
                true
            } catch (e: SecurityException) {
                AppLog.w("QuickSwitch", "cannot auto-switch (no WRITE_SECURE_SETTINGS)")
                false
            }
        } catch (e: Exception) { false }
    }

    private fun finishPtt(model: String, lang: String) {
        handler.post { bubble?.invalidate() }
        val bytes = synchronized(pttBuffer) { pttBuffer.toByteArray() }
        if (bytes.size < 16000) { // < ~0.5s
            handler.post { toast("Too short - hold longer") }
            return
        }
        handler.post { toast("Processing ${bytes.size / 32000}s audio...") }
        val ts = System.currentTimeMillis()
        val pcm = File(cacheDir, "ptt_$ts.pcm")
        FileOutputStream(pcm).use { it.write(bytes) }
        val wav = File(cacheDir, "ptt_$ts.wav")
        AudioUtils.pcmToWav(pcm, wav)
        pcm.delete()
        TranscriptionQueue.enqueue(
            TranscriptionQueue.Job(
                context = this,
                wavFile = wav,
                model = model,
                lang = lang,
                onResult = { text ->
                    val raw = text.trim()
                    handler.post {
                        when {
                            raw.isEmpty() || raw.startsWith("ERROR") || AudioUtils.isNoSpeechText(raw) ->
                                toast(if (raw.startsWith("ERROR")) "Failed: $raw" else "No speech detected")
                            else -> {
                                // auto-switch to whisper happened on release; router retries + a11y-paste if needed
                                TextRouter.route(raw)
                            }
                        }
                    }
                },
                onError = { err -> handler.post { toast("Transcription failed - Retry Failed in app"); AppLog.e("QuickSwitch", "PTT failed: $err") } }
            )
        )
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- Keyboard toggle ----------

    /** Toggle: switch to Whisper IME, or back to the saved previous one. */
    private fun toggleIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
            val goingWhisper = !current.equals(WHISPER_IME, ignoreCase = true)
            val target = if (goingWhisper) {
                prefs.edit().putString("prev_ime", current).apply()
                WHISPER_IME
            } else {
                prefs.getString("prev_ime", "") ?: ""
            }
            AppLog.i("QuickSwitch", "toggle: from=$current to=$target")
            if (target.isNotEmpty()) {
                try {
                    if (Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, target)) {
                        toast(if (goingWhisper) "Whisper ON" else "Keyboard restored")
                        return
                    }
                } catch (e: SecurityException) {
                    AppLog.w("QuickSwitch", "no WRITE_SECURE_SETTINGS - using picker")
                    toast("Grant WRITE_SECURE_SETTINGS via adb for instant switch")
                }
            }
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            AppLog.e("QuickSwitch", "toggle error: ${e.message}")
        }
    }

    override fun onDestroy() {
        pttRecording = false
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        handler.removeCallbacksAndMessages(null)
        AppLog.i("QuickSwitch", "bubble removed")
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else -> {
                val alpha = intent?.getIntExtra("alpha", -1) ?: -1
                if (alpha > 0) { applyAlpha(); AppLog.i("QuickSwitch", "alpha -> $alpha%") }
            }
        }
        return START_STICKY
    }
}
