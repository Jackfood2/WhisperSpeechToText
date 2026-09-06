package com.whisperkeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private var lastToastAt = 0L
    private fun saved() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt > 700) { lastToastAt = now; Toast.makeText(this, "Setting saved", Toast.LENGTH_SHORT).show() }
    }

    private lateinit var spinnerEngine: Spinner
    private lateinit var spinnerModel: Spinner
    private lateinit var spinnerLang: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var progress: ProgressBar

    private val models = arrayOf("tiny", "base", "small", "medium")
    private val engineKeys = arrayOf(SttEngines.WHISPER, SttEngines.MOONSHINE)
    private val engineNames = arrayOf("Whisper (multilingual)", "Moonshine v2 (English, fast)")
    private val langs = arrayOf("auto", "en", "zh", "ja", "ko", "fr", "de", "es")
    private val langNames = arrayOf("Auto detect", "English", "Chinese", "Japanese", "Korean", "French", "German", "Spanish")

    private fun currentEngine(): String =
        getSharedPreferences("whisper", MODE_PRIVATE).getString("engine", SttEngines.WHISPER) ?: SttEngines.WHISPER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spinnerEngine = findViewById(R.id.spinnerEngine)
        spinnerModel = findViewById(R.id.spinnerModel)
        spinnerLang = findViewById(R.id.spinnerLang)
        tvStatus = findViewById(R.id.tvDownloadStatus)
        tvModelInfo = findViewById(R.id.tvModelInfo)
        progress = findViewById(R.id.progressModel)

        val engineAdapter = ArrayAdapter(this, R.layout.spinner_item, engineNames)
        engineAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerEngine.adapter = engineAdapter
        val modelAdapter = ArrayAdapter(this, R.layout.spinner_item, models)
        modelAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerModel.adapter = modelAdapter
        val langAdapter = ArrayAdapter(this, R.layout.spinner_item, langNames)
        langAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerLang.adapter = langAdapter

        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        spinnerEngine.setSelection(engineKeys.indexOf(prefs.getString("engine", SttEngines.WHISPER)).coerceAtLeast(0))
        spinnerModel.setSelection(models.indexOf(prefs.getString("model", "small")).coerceAtLeast(0))
        spinnerLang.setSelection(langs.indexOf(prefs.getString("lang", "auto")).coerceAtLeast(0))
        applyEngineLock()

        // Accuracy switches
        val swVad = findViewById<SwitchMaterial>(R.id.switchVad)
        val swLive = findViewById<SwitchMaterial>(R.id.switchLive)
        val swBt = findViewById<SwitchMaterial>(R.id.switchBt)
        val swCaps = findViewById<SwitchMaterial>(R.id.switchCaps)
        swVad.isChecked = prefs.getBoolean("vad_on", true)
        swLive.isChecked = prefs.getBoolean("live_on", true)
        swBt.isChecked = prefs.getBoolean("bt_mic", false)
        swCaps.isChecked = prefs.getString("caps_mode", "auto") != "off"
        swVad.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("vad_on", b).apply(); saved() }
        swLive.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("live_on", b).apply(); saved() }
        swBt.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("bt_mic", b).apply(); saved() }
        swCaps.setOnCheckedChangeListener { _, b -> prefs.edit().putString("caps_mode", if (b) "auto" else "off").apply(); saved() }

        val swImeChunked = findViewById<SwitchMaterial>(R.id.switchImeChunked)
        val swBubbleChunked = findViewById<SwitchMaterial>(R.id.switchBubbleChunked)
        swImeChunked.isChecked = prefs.getBoolean("ime_chunked", true)
        swBubbleChunked.isChecked = prefs.getBoolean("bubble_chunked", true)
        swImeChunked.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("ime_chunked", b).apply(); saved(); Toast.makeText(this, if (b) "Keyboard: chunked ON" else "Keyboard: whole audio on Stop", Toast.LENGTH_SHORT).show() }
        swBubbleChunked.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("bubble_chunked", b).apply(); saved(); Toast.makeText(this, if (b) "Bubble: chunked ON" else "Bubble: whole audio on Stop", Toast.LENGTH_SHORT).show() }

        // Outstanding transcripts -> clipboard (off = retrieve only via keyboard insert button)
        val swOutClip = findViewById<SwitchMaterial>(R.id.switchOutClip)
        swOutClip.isChecked = prefs.getBoolean("out_clipboard", true)
        swOutClip.setOnCheckedChangeListener { _, b ->
            prefs.edit().putBoolean("out_clipboard", b).apply(); saved()
            Toast.makeText(this, if (b) "Outstanding -> clipboard ON" else "Outstanding -> clipboard OFF (keyboard insert only)", Toast.LENGTH_SHORT).show()
        }

        // ---- Threads (CPU cores) ----
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        findViewById<TextView>(R.id.tvCores).text = "Transcription threads | $cores CPU cores detected"
        val threadOptions = mutableListOf("Auto (${cores.coerceIn(2, 4)})")
        for (i in 1..cores) threadOptions.add("$i")
        val thAdapter = ArrayAdapter(this, R.layout.spinner_item, threadOptions)
        thAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        val spinnerThreads = findViewById<Spinner>(R.id.spinnerThreads)
        spinnerThreads.adapter = thAdapter
        val savedThreads = prefs.getString("threads_mode", "auto") ?: "auto"
        spinnerThreads.setSelection(if (savedThreads == "auto") 0 else savedThreads.toIntOrNull()?.coerceIn(1, cores) ?: 4.coerceAtMost(cores))
        spinnerThreads.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val mode = if (pos == 0) "auto" else threadOptions[pos]
                prefs.edit().putString("threads_mode", mode).apply()
                saved()
                val n = if (currentEngine() == SttEngines.MOONSHINE) MoonshineEngine.applyThreadPref(this@SettingsActivity)
                        else WhisperEngine.applyThreadPref(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Engine will use $n threads", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---- VAD durations ----
        val seekChunkSilence = findViewById<SeekBar>(R.id.seekChunkSilence)
        val tvChunkSilence = findViewById<TextView>(R.id.tvChunkSilence)
        // stored in tenths of a second (min 0.5s, step 0.5s); migrate from old whole-second key
        val legacyS = prefs.getInt("vad_chunk_silence_s", -1)
        val savedDs = prefs.getInt("vad_chunk_silence_ds", if (legacyS >= 2) legacyS * 10 else 40).coerceIn(5, 100)
        seekChunkSilence.progress = savedDs / 5 - 1   // progress 0..19 -> 0.5..10.0s (step 0.5)
        tvChunkSilence.text = String.format("%.1fs", savedDs / 10f)
        seekChunkSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val ds = (p + 1) * 5
                tvChunkSilence.text = String.format("%.1fs", ds / 10f)
                if (fromUser) prefs.edit().putInt("vad_chunk_silence_ds", ds).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        // chunk length (seconds)
        val seekLen = findViewById<SeekBar>(R.id.seekChunkLength)
        val tvLen = findViewById<TextView>(R.id.tvChunkLength)
        val savedLen = prefs.getInt("chunk_target_s", 30).coerceIn(1, 45)
        seekLen.progress = savedLen
        tvLen.text = "${savedLen}s"
        seekLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvLen.text = "${p}s"
                if (fromUser) prefs.edit().putInt("chunk_target_s", p.coerceIn(1, 45)).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        val seekStopSilence = findViewById<SeekBar>(R.id.seekStopSilence)
        val tvStopSilence = findViewById<TextView>(R.id.tvStopSilence)
        val savedStopS = prefs.getInt("vad_stop_silence_s", 10).coerceIn(5, 60)
        seekStopSilence.progress = savedStopS
        tvStopSilence.text = "${savedStopS}s"
        seekStopSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvStopSilence.text = "${p}s"
                if (fromUser) prefs.edit().putInt("vad_stop_silence_s", p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        // ---- Unload model when idle (0 = keep in memory) ----
        val seekUnload = findViewById<SeekBar>(R.id.seekUnloadIdle)
        val tvUnload = findViewById<TextView>(R.id.tvUnloadIdle)
        fun unloadLabel(p: Int) = if (p == 0) "Never" else "${p * 30}s"
        val savedUnload = prefs.getInt("unload_idle_ticks", 2) // ticks of 30s; default 60s
        seekUnload.progress = savedUnload.coerceIn(0, 12)
        tvUnload.text = unloadLabel(savedUnload)
        seekUnload.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvUnload.text = unloadLabel(p)
                if (fromUser) prefs.edit().putInt("unload_idle_ticks", p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        spinnerEngine.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val prev = prefs.getString("engine", SttEngines.WHISPER)
                val e = engineKeys[pos.coerceIn(engineKeys.indices)]
                prefs.edit().putString("engine", e).apply()
                applyEngineLock()
                refreshModelInfo()
                if (e != prev) {
                    val m = models[spinnerModel.selectedItemPosition.coerceAtLeast(0)]
                    tvStatus.text = "Loading $e/$m model..."
                    Toast.makeText(this@SettingsActivity, "Engine: $prev -> $e (keyboard badge updates)", Toast.LENGTH_SHORT).show()
                    Thread {
                        SttEngines.unloadIdle()
                        val ok = SttEngines.ensureModel(this@SettingsActivity, e, m, SttEngines.jobLang(this@SettingsActivity))
                        runOnUiThread { tvStatus.text = if (ok) localStatusText(e, m) else "$e/$m load failed - see Dashboard log" }
                    }.start()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val prev = prefs.getString("model", "small")
                val m = models[pos]
                val e = currentEngine()
                prefs.edit().putString("model", m).apply()
                refreshModelInfo()
                if (m != prev) {
                    tvStatus.text = "Loading $e/$m model..."
                    Toast.makeText(this@SettingsActivity, "Switching model: $prev -> $m", Toast.LENGTH_SHORT).show()
                    Thread {
                        if (SttEngines.isReady(this@SettingsActivity, e, m)) {
                            val ok = SttEngines.ensureModel(this@SettingsActivity, e, m, SttEngines.jobLang(this@SettingsActivity))
                            runOnUiThread { tvStatus.text = if (ok) localStatusText(e, m) else "$e/$m load failed - see Dashboard log" }
                        } else {
                            SttEngines.unloadIdle()
                            runOnUiThread { tvStatus.text = localStatusText(e, m) }
                        }
                    }.start()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerLang.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("lang", langs[pos]).apply(); saved()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // entry is strictly "type into field" now - no output mode selector

        // ---- Mic Bubble ----
        val swBubble = findViewById<SwitchMaterial>(R.id.switchBubble)
        val seekAlpha = findViewById<SeekBar>(R.id.seekBubbleAlpha)
        val tvAlpha = findViewById<TextView>(R.id.tvBubbleAlpha)

        fun overlayGranted(): Boolean = Settings.canDrawOverlays(this)

        fun setBubble(on: Boolean) {
            prefs.edit().putBoolean("bubble_on", on).apply()
            if (on) {
                startForegroundService(Intent(this, QuickSwitchService::class.java))
                AppLog.i("Bubble", "enabled")
            } else {
                startService(Intent(this, QuickSwitchService::class.java).setAction("STOP"))
                AppLog.i("Bubble", "disabled")
            }
        }

        val savedAlpha = prefs.getInt("bubble_alpha", 75)
        seekAlpha.progress = (100 - savedAlpha) * 80 / 100
        tvAlpha.text = "$savedAlpha%"
        swBubble.isChecked = prefs.getBoolean("bubble_on", false) && overlayGranted()

        swBubble.setOnCheckedChangeListener { _, on ->
            if (on && !overlayGranted()) {
                Toast.makeText(this, "Allow 'Display over other apps', then toggle again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                swBubble.isChecked = false
            } else setBubble(on)
        }

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val alphaPct = 100 - (p * 100 / 80)
                tvAlpha.text = "$alphaPct%"
                prefs.edit().putInt("bubble_alpha", alphaPct).apply()
                if (swBubble.isChecked) startService(Intent(this@SettingsActivity, QuickSwitchService::class.java).putExtra("alpha", alphaPct))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        // ---- Audio archive (full session audio saved next to transcript) ----
        val swAudioMeeting = findViewById<SwitchMaterial>(R.id.switchSaveAudioMeeting)
        val swAudioIme = findViewById<SwitchMaterial>(R.id.switchSaveAudioIme)
        swAudioMeeting.isChecked = prefs.getBoolean("save_audio_meeting", true)
        swAudioIme.isChecked = prefs.getBoolean("save_audio_ime", true)
        swAudioMeeting.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("save_audio_meeting", b).apply(); saved() }
        swAudioIme.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("save_audio_ime", b).apply(); saved() }

        val audioFormats = arrayOf("M4A (small)", "WAV (lossless)")
        val audioFormatKeys = arrayOf("m4a", "wav")
        val audioAdapter = ArrayAdapter(this, R.layout.spinner_item, audioFormats)
        audioAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        val spinnerAudio = findViewById<Spinner>(R.id.spinnerAudioFormat)
        spinnerAudio.adapter = audioAdapter
        spinnerAudio.setSelection(audioFormatKeys.indexOf(prefs.getString("audio_format", "m4a")).coerceAtLeast(0))
        spinnerAudio.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("audio_format", audioFormatKeys[pos.coerceIn(audioFormatKeys.indices)]).apply(); saved()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---- Buttons ----
        findViewById<Button>(R.id.btnSettingsDone).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnDownloadModel).setOnClickListener {
            downloadModel(models[spinnerModel.selectedItemPosition])
        }

        findViewById<Button>(R.id.btnTestModel).setOnClickListener {
            val e = currentEngine()
            val m = models[spinnerModel.selectedItemPosition.coerceAtLeast(0)]
            if (!SttEngines.isReady(this, e, m)) {
                tvStatus.text = "TEST $e/$m: not downloaded"
                AppLog.e("ModelTest", "$e/$m not downloaded")
                return@setOnClickListener
            }
            tvStatus.text = "TEST: loading $e/$m..."
            AppLog.i("ModelTest", "start load test: $e/$m")
            Thread {
                val t0 = System.currentTimeMillis()
                val ok = SttEngines.ensureModel(this@SettingsActivity, e, m, SttEngines.jobLang(this@SettingsActivity))
                val ms = System.currentTimeMillis() - t0
                val err = if (e == SttEngines.MOONSHINE) MoonshineEngine.lastError else WhisperEngine.lastError
                val msg = if (ok) "TEST OK: $e/$m loaded in ${ms}ms" else "TEST FAILED: $e/$m ($err)"
                AppLog.i("ModelTest", msg)
                runOnUiThread {
                    tvStatus.text = msg
                    Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnClearModels).setOnClickListener {
            val (n, freed) = ModelManager.clearAllModels(this)
            if (n == 0) {
                Toast.makeText(this, "No models to clear", Toast.LENGTH_SHORT).show()
                refreshModelInfo()
            } else {
                SttEngines.unloadIdle()
                Toast.makeText(this, "Cleared $n model(s), freed ${freed / 1024 / 1024} MB", Toast.LENGTH_LONG).show()
                refreshModelInfo()
                tvStatus.text = "Models cleared"
            }
        }


        // preload status
        val lastModel = prefs.getString("model", "small") ?: "small"
        val lastEngine = currentEngine()
        tvStatus.text = localStatusText(lastEngine, lastModel)
        refreshModelInfo()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!SttEngines.isLoaded(applicationContext, lastEngine, lastModel) &&
                        SttEngines.isReady(applicationContext, lastEngine, lastModel)) {
                        SttEngines.ensureModel(applicationContext, lastEngine, lastModel, SttEngines.jobLang(applicationContext))
                    }
                }
                runOnUiThread { tvStatus.text = localStatusText(lastEngine, lastModel); refreshModelInfo() }
            } catch (_: Throwable) {}
        }
    }

    /** Moonshine is English-only: lock the language spinner to English. */
    private fun applyEngineLock() {
        val moon = currentEngine() == SttEngines.MOONSHINE
        spinnerLang.isEnabled = !moon
        spinnerLang.alpha = if (moon) 0.45f else 1f
        if (moon) {
            getSharedPreferences("whisper", MODE_PRIVATE).edit().putString("lang", "en").apply()
            spinnerLang.setSelection(langs.indexOf("en").coerceAtLeast(0))
        }
    }

    private fun localStatusText(engine: String, model: String): String =
        if (engine == SttEngines.MOONSHINE) ModelManager.moonshineStatus(this, model)
        else ModelManager.localStatus(this, model)

    private fun refreshModelInfo() {
        val e = currentEngine()
        val sb = StringBuilder()
        sb.appendLine(if (e == SttEngines.MOONSHINE) "Engine: Moonshine v2 (English only)" else "Engine: Whisper (multilingual)")
        for (m in models) {
            val marker = if (m == spinnerModel.selectedItem.toString()) " > " else "   "
            val state = if (e == SttEngines.MOONSHINE) {
                if (ModelManager.isMoonshineReady(this, m) || MoonshineEngine.isLoaded(m)) "$marker$m: ${ModelManager.moonshineSize(m)} [downloaded]"
                else "$marker$m: not downloaded (${ModelManager.moonshineSize(m)})"
            } else {
                val f = ModelManager.modelFile(this, m)
                if (f.exists() && f.length() > 1_000_000) "$marker$m: ${f.length() / 1024 / 1024} MB [downloaded]"
                else "$marker$m: not downloaded (${ModelManager.whisperSize(m)})"
            }
            sb.appendLine(state)
        }
        tvModelInfo.text = sb.toString().trimEnd()
    }

    private fun downloadModel(model: String) {
        val e = currentEngine()
        progress.visibility = ProgressBar.VISIBLE
        tvStatus.text = if (e == SttEngines.MOONSHINE) "Downloading moonshine-$model ..." else "Downloading ggml-$model.bin ..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (e == SttEngines.MOONSHINE) {
                        ModelManager.downloadMoonshine(this@SettingsActivity, model) { p, msg ->
                            runOnUiThread { progress.progress = p; tvStatus.text = msg }
                        }
                    } else {
                        ModelManager.download(this@SettingsActivity, model) { p, msg ->
                            runOnUiThread { progress.progress = p; tvStatus.text = msg }
                        }
                    }
                }
                tvStatus.text = if (e == SttEngines.MOONSHINE) "Ready: moonshine-$model" else "Ready: ggml-$model.bin"
                Toast.makeText(this@SettingsActivity, "Model $e/$model ready", Toast.LENGTH_LONG).show()
                refreshModelInfo()
            } catch (ex: Exception) {
                tvStatus.text = "Failed: ${ex.message}"
                Toast.makeText(this@SettingsActivity, "Download failed: ${ex.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = ProgressBar.GONE
            }
        }
    }
}

