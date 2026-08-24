package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import java.io.DataOutputStream

/**
 * Floating quick-switch bubble: one tap switches to the Whisper voice keyboard,
 * tap again returns to your previous keyboard. Draggable to any screen edge.
 *
 * Instant switching uses WRITE_SECURE_SETTINGS if granted once via adb:
 *   adb shell pm grant com.whisperkeyboard android.permission.WRITE_SECURE_SETTINGS
 * Without it, falls back to showing the system input-method picker.
 */
class QuickSwitchService : Service() {

    private companion object {
        const val CHANNEL = "quick_switch"
        const val NOTIF_ID = 42
        const val WHISPER_IME = "com.whisperkeyboard/.WhisperKeyboardService"
    }

    private var bubble: View? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick switch", NotificationManager.IMPORTANCE_MIN))
            startForeground(NOTIF_ID, buildNotif())
        } else {
            startForeground(NOTIF_ID, buildNotif())
        }
        showBubble()
        AppLog.i("QuickSwitch", "bubble shown")
    }

    private fun applyAlpha() {
        val pct = getSharedPreferences("whisper", MODE_PRIVATE).getInt("bubble_alpha", 75)
        bubble?.alpha = (pct / 100.0f).coerceIn(0.15f, 1.0f)
    }

    private fun buildNotif(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Whisper mic bubble active")
            .setContentText("Tap the bubble to switch keyboards")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()

    private fun showBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 12
        params.y = 400

        val btn = Button(this)
        btn.text = "🎤"
        btn.textSize = 11f
        btn.setTextColor(0xFF212121.toInt())
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xCCE0E0E0.toInt())
        btn.setPadding(10, 5, 10, 5)
        btn.minimumWidth = 0
        btn.minimumHeight = 0

        var downX = 0f; var downY = 0f
        var startX = 0f; var startY = 0f
        var moved = false

        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = params.x.toFloat(); startY = params.y.toFloat(); moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (dx * dx + dy * dy > 100) moved = true
                    if (moved) { params.x = startX.toInt() + dx.toInt(); params.y = startY.toInt() + dy.toInt(); wm?.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) { v.performClick() }; true }
                else -> false
            }
        }
        btn.setOnClickListener { toggleIme() }
        bubble = btn
        try { wm?.addView(btn, params); applyAlpha() } catch (e: Exception) { AppLog.e("QuickSwitch", "overlay failed: ${e.message}") }
    }

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
            // Fast path: direct secure-settings write (needs one-time adb grant)
            if (target.isNotEmpty()) {
                try {
                    if (Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, target)) {
                        android.widget.Toast.makeText(this, if (goingWhisper) "Whisper ON" else "Keyboard restored", android.widget.Toast.LENGTH_SHORT).show()
                        return
                    }
                } catch (e: SecurityException) {
                    AppLog.w("QuickSwitch", "no WRITE_SECURE_SETTINGS - using picker")
                }
            }
            // Fallback: system picker (user picks once per switch)
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            AppLog.e("QuickSwitch", "toggle error: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubble = null
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
