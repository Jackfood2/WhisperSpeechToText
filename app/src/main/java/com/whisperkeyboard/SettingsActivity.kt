package com.whisperkeyboard

import android.content.Intent
import android.os.Build
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
import java.util.Locale
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

    private val modelKeys = arrayOf("", "tiny", "base", "small", "medium")
    private val modelNames = arrayOf("Select model…", "tiny", "base", "small", "medium")
    private lateinit var modelAdapter: ArrayAdapter<String>

    companion object {
        private val downloading =
            java.util.concurrent.atomic.AtomicReference<String?>(
                null
            )
    }

    private var settingsInitialized = false
    private val modelRequestGeneration =
        java.util.concurrent.atomic.AtomicInteger(0)
    private val engineKeys = arrayOf(SttEngines.WHISPER, SttEngines.MOONSHINE)
    private val engineNames = arrayOf("Whisper (multilingual)", "Moonshine v2 (English, fast)")
    private val langs = arrayOf("auto", "en", "zh", "ja", "ko", "fr", "de", "es")
    private val langNames = arrayOf("Auto detect", "English", "Chinese", "Japanese", "Korean", "French", "German", "Spanish")

    private fun currentEngine(): String =
        getSharedPreferences("whisper", MODE_PRIVATE).getString("engine", SttEngines.WHISPER) ?: SttEngines.WHISPER

    private fun selectedModelKey(): String =
        modelKeys[spinnerModel.selectedItemPosition.coerceIn(modelKeys.indices)]

    private fun isModelKeyReady(engine: String, key: String): Boolean {
        if (key.isEmpty()) return false
        return if (engine == SttEngines.MOONSHINE) {
            ModelManager.isMoonshineReady(this, key) || MoonshineEngine.isLoaded(key)
        } else {
            ModelManager.isComplete(this, key)
        }
    }

    private fun modelDisplayName(
        engine: String,
        model: String
    ): String {
        return if (
            isModelKeyReady(engine, model)
        ) {
            "$model · ready"
        } else {
            "$model · download required"
        }
    }

    private fun paintModelRow(v: android.view.View, pos: Int) {
        try {
            val tv = v as? TextView ?: return
            if (pos <= 0 || pos >= modelKeys.size) return
            val key = modelKeys[pos]
            val ready = isModelKeyReady(currentEngine(), key)
            tv.text = modelDisplayName(currentEngine(), key)
            tv.setTextColor(
                if (ready) 0xFF212121.toInt()
                else 0xFF616161.toInt()
            )
        } catch (_: Exception) {}
    }

    private fun promptDownloadOrRevert(engine: String, key: String) {
        val size = if (engine == SttEngines.MOONSHINE) ModelManager.moonshineSize(key)
            else ModelManager.whisperSize(key)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Download model?")
            .setMessage("$engine/$key is not downloaded ($size). Download now over WiFi?")
            .setPositiveButton("Download") { _, _ ->
                prefsOf().edit().putString("model", key).apply()
                refreshModelInfo()
                downloadModel(key)
            }
            .setNegativeButton("Not now") { _, _ ->
                spinnerModel.setSelection(0)
                prefsOf().edit().putString("model", "").apply()
                refreshModelInfo()
                tvStatus.text = "No model selected"
            }
            .setCancelable(false)
            .show()
    }

    private fun prefsOf() = getSharedPreferences("whisper", MODE_PRIVATE)

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

        modelAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, modelNames) {
            override fun getView(pos: Int, convert: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, convert, parent)
                paintModelRow(v, pos)
                return v
            }
            override fun getDropDownView(pos: Int, convert: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(pos, convert, parent)
                paintModelRow(v, pos)
                return v
            }
        }
        modelAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerModel.adapter = modelAdapter
        val langAdapter = ArrayAdapter(this, R.layout.spinner_item, langNames)
        langAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerLang.adapter = langAdapter

        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        spinnerEngine.setSelection(engineKeys.indexOf(prefs.getString("engine", SttEngines.WHISPER)).coerceAtLeast(0))
        spinnerModel.setSelection(modelKeys.indexOf(prefs.getString("model", "small")).coerceAtLeast(0))
        spinnerLang.setSelection(langs.indexOf(prefs.getString("lang", "auto")).coerceAtLeast(0))
        applyEngineLock()

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

        val swOutClip = findViewById<SwitchMaterial>(R.id.switchOutClip)
        swOutClip.isChecked = prefs.getBoolean("out_clipboard", true)
        swOutClip.setOnCheckedChangeListener { _, b ->
            prefs.edit().putBoolean("out_clipboard", b).apply(); saved()
            Toast.makeText(this, if (b) "Outstanding -> clipboard ON" else "Outstanding -> clipboard OFF (keyboard insert only)", Toast.LENGTH_SHORT).show()
        }

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        findViewById<TextView>(R.id.tvCores).text = "Transcription threads | $cores CPU cores detected"
        val threadOptions = mutableListOf("Auto (${SttEngines.automaticThreadCount()})")
        for (i in 1..cores) threadOptions.add("$i")
        val thAdapter = ArrayAdapter(this, R.layout.spinner_item, threadOptions)
        thAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        val spinnerThreads = findViewById<Spinner>(R.id.spinnerThreads)
        spinnerThreads.adapter = thAdapter
        val savedThreads = prefs.getString("threads_mode", "auto") ?: "auto"
        spinnerThreads.setSelection(if (savedThreads == "auto") 0 else savedThreads.toIntOrNull()?.coerceIn(1, cores) ?: 4.coerceAtMost(cores))
        spinnerThreads.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (!settingsInitialized) return
                val mode = if (pos == 0) "auto" else threadOptions[pos]
                prefs.edit().putString("threads_mode", mode).apply()
                saved()
                applyThreadsWhenIdle()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val tvChunkDescription = findViewById<TextView>(R.id.tvChunkDescription)

        fun updateChunkDescription(
            targetSeconds: Int,
            silenceDeciseconds: Int
        ) {
            val silenceSeconds =
                silenceDeciseconds / 10f

            tvChunkDescription.text =
                "Chunk after at least ${targetSeconds}s when " +
                    "silence reaches %.1fs; hard maximum applies."
                        .format(
                            Locale.getDefault(),
                            silenceSeconds
                        )
        }

        val seekChunkSilence = findViewById<SeekBar>(R.id.seekChunkSilence)
        val tvChunkSilence = findViewById<TextView>(R.id.tvChunkSilence)

        val legacyS = prefs.getInt("vad_chunk_silence_s", -1)
        val savedDs = prefs.getInt("vad_chunk_silence_ds", if (legacyS >= 2) legacyS * 10 else 40).coerceIn(5, 100)
        seekChunkSilence.progress = savedDs / 5 - 1
        tvChunkSilence.text = String.format("%.1fs", savedDs / 10f)
        seekChunkSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val ds = (p + 1) * 5
                tvChunkSilence.text = String.format("%.1fs", ds / 10f)
                if (fromUser) prefs.edit().putInt("vad_chunk_silence_ds", ds).apply()
                updateChunkDescription(prefs.getInt("chunk_target_s", 30).coerceIn(1, 45), ds)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        val seekLen = findViewById<SeekBar>(R.id.seekChunkLength)
        val tvLen = findViewById<TextView>(R.id.tvChunkLength)
        val savedLen = prefs.getInt("chunk_target_s", 30).coerceIn(1, 45)
        seekLen.progress = savedLen
        tvLen.text = "${savedLen}s"
        seekLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvLen.text = "${p}s"
                if (fromUser) prefs.edit().putInt("chunk_target_s", p.coerceIn(1, 45)).apply()
                updateChunkDescription(p.coerceIn(1, 45), prefs.getInt("vad_chunk_silence_ds", 40).coerceIn(5, 100))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })
        updateChunkDescription(savedLen, savedDs)

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

        val seekUnload = findViewById<SeekBar>(R.id.seekUnloadIdle)
        val tvUnload = findViewById<TextView>(R.id.tvUnloadIdle)
        fun unloadLabel(p: Int) = if (p == 0) "Never" else "${p * 30}s"
        val savedUnload =
            prefs.getInt(
                "unload_idle_ticks",
                2
            ).coerceIn(0, 12)

        seekUnload.progress = savedUnload
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
                if (!settingsInitialized) return
                val prev = prefs.getString("engine", SttEngines.WHISPER)
                val e = engineKeys[pos.coerceIn(engineKeys.indices)]
                prefs.edit().putString("engine", e).apply()
                applyEngineLock()
                modelAdapter.notifyDataSetChanged()
                refreshModelInfo()
                if (e != prev) {

                    prefs.edit().putString("model", "").apply()
                    spinnerModel.setSelection(0)
                    tvStatus.text = "Engine: $e - pick a model below"
                    Toast.makeText(this@SettingsActivity, "Engine: $prev -> $e - model reset, pick one", Toast.LENGTH_SHORT).show()
                    Thread { SttEngines.unloadIdle() }.start()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (!settingsInitialized) return
                val p = pos.coerceIn(modelKeys.indices)
                val e = currentEngine()
                if (p == 0) {

                    prefs.edit().putString("model", "").apply()
                    refreshModelInfo()
                    tvStatus.text = "No model selected"
                    return
                }
                val m = modelKeys[p]
                val prev = prefs.getString("model", "") ?: ""
                if (!isModelKeyReady(e, m)) {

                    promptDownloadOrRevert(e, m)
                    return
                }
                prefs.edit().putString("model", m).apply()
                refreshModelInfo()
                if (m != prev) {
                    tvStatus.text = "Loading $e/$m model..."
                    Toast.makeText(this@SettingsActivity, "Switching model: ${prev.ifEmpty { "(none)" }} -> $m", Toast.LENGTH_SHORT).show()
                    Thread {
                        val ok = SttEngines.ensureModel(this@SettingsActivity, e, m, SttEngines.jobLang(this@SettingsActivity))
                        runOnUiThread {
                            tvStatus.text = if (ok) localStatusText(e, m) else "$e/$m load failed - see Dashboard log"
                            modelAdapter.notifyDataSetChanged()
                        }
                    }.start()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerLang.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (!settingsInitialized) return
                prefs.edit().putString("lang", langs[pos]).apply(); saved()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val swBubble = findViewById<SwitchMaterial>(R.id.switchBubble)
        val seekAlpha = findViewById<SeekBar>(R.id.seekBubbleAlpha)
        val tvAlpha = findViewById<TextView>(R.id.tvBubbleAlpha)

        fun overlayGranted(): Boolean = Settings.canDrawOverlays(this)

        fun setBubble(enabled: Boolean) {
            prefs.edit()
                .putBoolean("bubble_on", enabled)
                .apply()

            val intent =
                Intent(
                    this,
                    QuickSwitchService::class.java
                )

            if (enabled) {
                runCatching {
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.O
                    ) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }.onFailure {
                    prefs.edit()
                        .putBoolean("bubble_on", false)
                        .apply()

                    swBubble.isChecked = false

                    AppLog.e(
                        "Bubble",
                        "Unable to start bubble: ${it.message}"
                    )

                    Toast.makeText(
                        this,
                        "Unable to start microphone bubble",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                runCatching {
                    startService(
                        intent.setAction("STOP")
                    )
                }
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

        seekAlpha.max = 80
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val alphaPct =
                    (
                        100 -
                        (
                            p.coerceIn(0, seekAlpha.max) *
                            100 /
                            seekAlpha.max.coerceAtLeast(1)
                        )
                    ).coerceIn(0, 100)
                tvAlpha.text = "$alphaPct%"
                prefs.edit().putInt("bubble_alpha", alphaPct).apply()
                if (swBubble.isChecked) startService(Intent(this@SettingsActivity, QuickSwitchService::class.java).putExtra("alpha", alphaPct))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saved() }
        })

        val swAudioMeeting = findViewById<SwitchMaterial>(R.id.switchSaveAudioMeeting)
        swAudioMeeting.isChecked = prefs.getBoolean("save_audio_meeting", true)
        swAudioMeeting.setOnCheckedChangeListener { _, b ->
            prefs.edit()
                .putBoolean(
                    "save_audio_meeting",
                    b
                )
                .apply()

            updateAudioFormatEnabled(b)

            saved()
        }

        val audioFormats = arrayOf("M4A (small)", "WAV (lossless)")
        val audioFormatKeys = arrayOf("m4a", "wav")
        val audioAdapter = ArrayAdapter(this, R.layout.spinner_item, audioFormats)
        audioAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        val spinnerAudio = findViewById<Spinner>(R.id.spinnerAudioFormat)
        spinnerAudio.adapter = audioAdapter
        spinnerAudio.setSelection(audioFormatKeys.indexOf(prefs.getString("audio_format", "m4a")).coerceAtLeast(0))
        spinnerAudio.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (!settingsInitialized) return
                prefs.edit().putString("audio_format", audioFormatKeys[pos.coerceIn(audioFormatKeys.indices)]).apply(); saved()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        updateAudioFormatEnabled(swAudioMeeting.isChecked)

        findViewById<Button>(R.id.btnSettingsDone).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnDownloadModel).setOnClickListener {
            val key = selectedModelKey()
            if (key.isEmpty()) {
                Toast.makeText(this, "Select a model first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            downloadModel(key)
        }

        findViewById<Button>(R.id.btnTestModel).setOnClickListener {
            val e = currentEngine()
            val m = selectedModelKey()
            if (m.isEmpty()) {
                Toast.makeText(this, "Select a model first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!SttEngines.isReady(this, e, m)) {
                tvStatus.text = "TEST $e/$m: not downloaded"
                AppLog.e("ModelTest", "$e/$m not downloaded")
                return@setOnClickListener
            }
            tvStatus.text = "TEST: loading $e/$m..."
            AppLog.i("ModelTest", "start load test: $e/$m")
            val generation =
                modelRequestGeneration.incrementAndGet()
            Thread {
                val t0 = System.currentTimeMillis()
                val ok = SttEngines.ensureModel(this@SettingsActivity, e, m, SttEngines.jobLang(this@SettingsActivity))
                val ms = System.currentTimeMillis() - t0
                val err = if (e == SttEngines.MOONSHINE) MoonshineEngine.lastError else WhisperEngine.lastError
                val msg = if (ok) "TEST OK: $e/$m loaded in ${ms}ms" else "TEST FAILED: $e/$m ($err)"
                AppLog.i("ModelTest", msg)
                runOnUiThread {
                    if (
                        modelRequestGeneration.get() ==
                        generation
                    ) {
                        tvStatus.text = msg
                        Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnClearModels).setOnClickListener {
            clearModelsSafely()
        }

        val lastModel = prefs.getString("model", "small") ?: "small"
        val lastEngine = currentEngine()
        tvStatus.text = localStatusText(lastEngine, lastModel)
        refreshModelInfo()

        val preloadGeneration =
            modelRequestGeneration.incrementAndGet()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!SttEngines.isLoaded(applicationContext, lastEngine, lastModel) &&
                        SttEngines.isReady(applicationContext, lastEngine, lastModel)) {
                        SttEngines.ensureModel(applicationContext, lastEngine, lastModel, SttEngines.jobLang(applicationContext))
                    }
                }
                runOnUiThread {
                    if (
                        modelRequestGeneration.get() ==
                        preloadGeneration
                    ) {
                        tvStatus.text = localStatusText(lastEngine, lastModel)
                        refreshModelInfo()
                    }
                }
            } catch (_: Throwable) {}
        }

        settingsInitialized = true
    }

    private fun applyThreadsWhenIdle() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (SttEngines.busy()) {
                withContext(Dispatchers.Main) {
                    tvStatus.text =
                        "Thread setting saved; applies after current transcription"
                }
                return@launch
            }

            val count =
                if (
                    currentEngine() ==
                    SttEngines.MOONSHINE
                ) {
                    MoonshineEngine.applyThreadPref(
                        applicationContext
                    )
                } else {
                    WhisperEngine.applyThreadPref(
                        applicationContext
                    )
                }

            withContext(Dispatchers.Main) {
                tvStatus.text =
                    "Engine configured for $count threads"
            }
        }
    }

    private fun applyEngineLock() {
        val moon = currentEngine() == SttEngines.MOONSHINE
        spinnerLang.isEnabled = !moon
        spinnerLang.alpha = if (moon) 0.45f else 1f
        if (moon) {
            getSharedPreferences("whisper", MODE_PRIVATE).edit().putString("lang", "en").apply()
            spinnerLang.setSelection(langs.indexOf("en").coerceAtLeast(0))
        }
        if (::modelAdapter.isInitialized) modelAdapter.notifyDataSetChanged()
    }

    private fun localStatusText(engine: String, model: String): String =
        if (engine == SttEngines.MOONSHINE) ModelManager.moonshineStatus(this, model)
        else ModelManager.localStatus(this, model)

    private fun refreshModelInfo() {
        val e = currentEngine()
        val selPos = spinnerModel.selectedItemPosition
        val sb = StringBuilder()
        sb.appendLine(if (e == SttEngines.MOONSHINE) "Engine: Moonshine v2 (English only)" else "Engine: Whisper (multilingual)")
        for (i in models.indices) {
            val m = models[i]
            val marker = if (i + 1 == selPos) " > " else "   "
            val state = if (e == SttEngines.MOONSHINE) {
                if (ModelManager.isMoonshineReady(this, m) || MoonshineEngine.isLoaded(m)) "$marker$m: ${ModelManager.moonshineSize(m)} [downloaded]"
                else "$marker$m: not downloaded (${ModelManager.moonshineSize(m)})"
            } else {
                val f = ModelManager.modelFile(this, m)
                if (ModelManager.isComplete(this, m)) "$marker$m: ${f.length() / 1024 / 1024} MB [downloaded]"
                else if (f.exists()) "$marker$m: INCOMPLETE (${f.length() / 1024 / 1024}/${ModelManager.expectedBytes(m) / 1024 / 1024} MB) - re-download"
                else "$marker$m: not downloaded (${ModelManager.whisperSize(m)})"
            }
            sb.appendLine(state)
        }
        tvModelInfo.text = sb.toString().trimEnd()
    }

    private fun updateAudioFormatEnabled(saveAudioOn: Boolean) {
        val formatSpinner =
            findViewById<Spinner>(R.id.spinnerAudioFormat) ?: return

        formatSpinner.isEnabled = saveAudioOn
        formatSpinner.alpha =
            if (saveAudioOn) 1f else 0.45f
    }

    private fun clearModelsSafely() {
        if (
            SttEngines.busy() ||
            TranscriptionQueue.isActive() ||
            MeetingRecordService.isRunning ||
            WhisperKeyboardService.imeRecording
        ) {
            Toast.makeText(
                this,
                "Stop recording and transcription before clearing models",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val whisperUnloaded =
            WhisperEngine.unloadIfIdle()

        val moonshineUnloaded =
            MoonshineEngine.unloadIfIdle()

        if (!whisperUnloaded || !moonshineUnloaded) {
            Toast.makeText(
                this,
                "Unable to unload models",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val result =
            ModelManager.clearAllModels(this)

        Toast.makeText(
            this,
            "Cleared ${result.first} model(s), freed " +
                "${result.second / 1024 / 1024} MB",
            Toast.LENGTH_LONG
        ).show()

        spinnerModel.setSelection(0)
        prefsOf().edit()
            .putString("model", "")
            .apply()

        refreshModelInfo()
    }

    private fun downloadModel(model: String) {
        val e = currentEngine()

        val key = "$e/$model"
        if (!downloading.compareAndSet(null, key)) {
            Toast.makeText(this, "Already downloading ${downloading.get()} - wait for it to finish", Toast.LENGTH_SHORT).show()
            return
        }
        val startedEngine = e
        val startedModel = model
        val startedKey = key
        val generation =
            modelRequestGeneration.incrementAndGet()
        val btnDl = findViewById<Button>(R.id.btnDownloadModel)
        btnDl.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        tvStatus.text = if (e == SttEngines.MOONSHINE) "Downloading moonshine-$model ..." else "Downloading ggml-$model.bin ..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (e == SttEngines.MOONSHINE) {
                        ModelManager.downloadMoonshine(this@SettingsActivity, model) { p, msg ->
                            runOnUiThread {
                                if (modelRequestGeneration.get() == generation) {
                                    progress.progress = p; tvStatus.text = msg
                                }
                            }
                        }
                    } else {
                        ModelManager.download(this@SettingsActivity, model) { p, msg ->
                            runOnUiThread {
                                if (modelRequestGeneration.get() == generation) {
                                    progress.progress = p; tvStatus.text = msg
                                }
                            }
                        }
                    }
                }
                val stillSelected =
                    currentEngine() == startedEngine &&
                    selectedModelKey() == startedModel

                if (stillSelected) {
                    tvStatus.text = if (e == SttEngines.MOONSHINE) "Ready: moonshine-$model" else "Ready: ggml-$model.bin"
                } else {
                    tvStatus.text = "Downloaded $startedEngine/$startedModel"
                }
                Toast.makeText(this@SettingsActivity, "Model $e/$model ready", Toast.LENGTH_LONG).show()
                modelAdapter.notifyDataSetChanged()
                refreshModelInfo()
            } catch (ex: Exception) {

                tvStatus.text = "Failed: ${ex.message}"
                Toast.makeText(this@SettingsActivity, "Download failed: ${ex.message}", Toast.LENGTH_LONG).show()
            } finally {
                downloading.compareAndSet(startedKey, null)
                progress.visibility = ProgressBar.GONE
                runCatching { btnDl.isEnabled = true }
            }
        }
    }
}
