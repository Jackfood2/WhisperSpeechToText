package com.whisperkeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class PrivacyDashboardActivity : AppCompatActivity() {

    private var lastToastAt = 0L
    private fun savedToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt > 700) { lastToastAt = now; Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Capture ANY crash inside this screen: show it on-screen + copy to clipboard,
        // instead of the app dying with "clear cache" prompts.
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { saveCrash(e) }
            var shown = false
            runCatching { showErrorScreen(e); shown = true }
            if (!shown) prevHandler?.uncaughtException(t, e) // only kill process if we could not show diagnostics
        }
        try {
            wireUi()
        } catch (e: Throwable) {
            saveCrash(e)
            showErrorScreen(e)
        }
    }

    private fun saveCrash(e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().take(6000)
        AppLog.e("Dashboard", "CRASH: ${e.message}\n${trace.take(500)}")
        try {
            val ctx = WhisperApp.holder ?: this
            File(ctx.getExternalFilesDir(null), "dashboard_crash.txt").writeText(trace)
        } catch (_: Exception) {}
    }

    /** Plain programmatic error screen - cannot fail. */
    private fun showErrorScreen(e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val scroll = ScrollView(this)
        val tv = TextView(this)
        tv.text = "Dashboard crashed.\n\nStack trace (also copied to clipboard & saved to app files as dashboard_crash.txt):\n\n$sw"
        tv.setTextIsSelectable(true)
        tv.setPadding(32, 32, 32, 32)
        scroll.addView(tv)
        setContentView(scroll)
        try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("whisper_dashboard_crash", sw.toString()))
            Toast.makeText(this, "Crash details copied to clipboard", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    private fun wireUi() {
        setContentView(R.layout.activity_privacy_dashboard)
        val tvStats = findViewById<TextView>(R.id.tvStats)
        val tvLogs = findViewById<TextView>(R.id.tvLogs)

        fun refresh() {
            try {
                val prefs = getSharedPreferences("whisper_stats", MODE_PRIVATE)
                val sb = StringBuilder()
                sb.appendLine("On-device only. No audio leaves your phone.")
                sb.appendLine("Models stored: getExternalFilesDir/models (cleared on uninstall)")
                sb.appendLine("Transcripts: Documents/WhisperNotes/*.txt (you control)")
                sb.appendLine()
                sb.appendLine("Adaptive baseline (per-model avg ratio):")
                for (m in listOf("tiny","base","small","medium")) {
                    val cnt = prefs.getInt("count_$m", 0)
                    val ratio = prefs.getFloat("ratio_$m", 0f)
                    val lastA = prefs.getFloat("last_audio_$m", 0f)
                    val lastT = prefs.getFloat("last_time_$m", 0f)
                    sb.appendLine("  $m: ${if(cnt>0) String.format("%.3f", ratio) else "-"}  cnt=$cnt  last ${String.format("%.1f", lastA)}s -> ${String.format("%.1f", lastT)}s")
                }
                val gCnt = prefs.getInt("count_global", 0)
                val gRatio = prefs.getFloat("ratio_global", 0f)
                sb.appendLine("  global: ${if(gCnt>0) String.format("%.3f", gRatio) else "-"} cnt=$gCnt")
                sb.appendLine()
                val modelsDir = ModelManager.modelsDir(this)
                val models = modelsDir.listFiles()?.joinToString(", ") { "${it.name} ${it.length()/1024/1024}MB" } ?: "none"
                sb.appendLine("Stored models: $models")
                val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WhisperNotes")
                val txts = docs.listFiles()?.filter { it.extension=="txt" }?.size ?: 0
                val failed = File(docs, "failed").listFiles()?.size ?: 0
                sb.appendLine("Saved transcripts: $txts | Failed saves: $failed")
                sb.appendLine()
                val p2 = getSharedPreferences("whisper", MODE_PRIVATE)
                sb.appendLine("VAD: ${p2.getBoolean("vad_on", true)} | LIVE: ${p2.getBoolean("live_on", true)} | BT: ${p2.getBoolean("bt_mic", false)} | caps: ${p2.getString("caps_mode","auto")} | chunked kb: ${p2.getBoolean("ime_chunked", true)} | chunked bubble: ${p2.getBoolean("bubble_chunked", true)} | threads: ${p2.getString("threads_mode","auto")} | unload: ${p2.getInt("unload_idle_ticks",2)*30}s")
                tvStats.text = sb.toString()

                val logSb = StringBuilder()
                try {
                    val txtFiles = docs.listFiles()?.filter { it.extension=="txt" }?.sortedByDescending { it.lastModified() }?.take(2) ?: emptyList()
                    for (f in txtFiles) {
                        logSb.appendLine("== ${f.name} ==")
                        logSb.appendLine(f.readText().take(1200))
                    }
                    if (logSb.isEmpty()) logSb.append("No transcripts yet.")
                } catch (e: Exception) { logSb.append("Log read error: ${e.message}") }
                tvLogs.text = logSb.toString()
            } catch (e: Throwable) {
                tvStats.text = "Stats error: ${e.message}"
                AppLog.e("Dashboard", "refresh failed: ${e.message}")
            }
        }

        refresh()

        findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            getSharedPreferences("whisper_stats", MODE_PRIVATE).edit().clear().apply()
            savedToast("Stats reset"); refresh()
        }
        findViewById<Button>(R.id.btnExportLogs).setOnClickListener {
            val dump = getSharedPreferences("whisper_stats", MODE_PRIVATE).all.entries.joinToString("\n") { "${it.key}=${it.value}" }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("whisper_stats", dump))
            savedToast("Stats copied")
        }
        findViewById<Button>(R.id.btnClearTranscripts).setOnClickListener {
            val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WhisperNotes")
            var n = 0
            docs.listFiles()?.forEach { if (it.extension == "txt") { it.delete(); n++ } }
            savedToast("Deleted $n transcripts"); refresh()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }

        val tvDiag = findViewById<TextView>(R.id.tvDiagLog)
        fun refreshDiag() {
            try {
                val dump = AppLog.dump()
                tvDiag.text = when {
                    dump.isBlank() -> "No log events yet."
                    AppLog.lastError.isNotEmpty() -> "LAST ERROR: ${AppLog.lastError}\n\n$dump"
                    else -> dump
                }
            } catch (e: Throwable) { tvDiag.text = "log error: ${e.message}" }
        }
        refreshDiag()
        findViewById<Button>(R.id.btnRefreshDiag).setOnClickListener { refreshDiag(); Toast.makeText(this, "Log refreshed", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnCopyDiag).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("whisper_diag_log", AppLog.dump()))
            Toast.makeText(this, "Error/log copied to clipboard", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnClearDiag).setOnClickListener {
            AppLog.clear(); AppLog.i("Dashboard", "log cleared by user"); refreshDiag()
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        }
    }
}
