package com.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerModel: Spinner
    private lateinit var spinnerLang: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvMeetingStatus: TextView
    private lateinit var tvMeetingPath: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var tvProgressPct: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressTranscribe: ProgressBar
    private lateinit var radioMode: RadioGroup
    private lateinit var radioEntryMode: RadioGroup

    private val models = arrayOf("tiny", "base", "small", "medium")
    private val langs = arrayOf("auto", "en", "zh", "ja", "ko", "fr", "de", "es")
    private val permRequestCode = 100

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        override fun onProgress(pct: Int) {
            runOnUiThread {
                progressTranscribe.progress = pct
                tvProgressPct.text = if (pct == 0) "0% - idle" else "$pct% transcribing..."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerModel = findViewById(R.id.spinnerModel)
        spinnerLang = findViewById(R.id.spinnerLang)
        tvStatus = findViewById(R.id.tvDownloadStatus)
        tvMeetingStatus = findViewById(R.id.tvMeetingStatus)
        tvMeetingPath = findViewById(R.id.tvMeetingPath)
        tvQueue = findViewById(R.id.tvQueue)
        tvModelInfo = findViewById(R.id.tvModelInfo)
        progress = findViewById(R.id.progressModel)
        progressTranscribe = findViewById(R.id.progressTranscribe)
        tvProgressPct = findViewById(R.id.tvProgressPct)
        radioMode = findViewById(R.id.radioMode)
        radioEntryMode = findViewById(R.id.radioEntryMode)

        val modelAdapter = ArrayAdapter(this, R.layout.spinner_item, models)
        modelAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerModel.adapter = modelAdapter
        val langAdapter = ArrayAdapter(this, R.layout.spinner_item, langs)
        langAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerLang.adapter = langAdapter

        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        spinnerModel.setSelection(models.indexOf(prefs.getString("model", "small")).coerceAtLeast(0))
        spinnerLang.setSelection(langs.indexOf(prefs.getString("lang", "auto")).coerceAtLeast(0))
        // preload last-used model immediately on open
        val lastModel = prefs.getString("model", "small") ?: "small"
        tvStatus.text = localStatusText(lastModel)
        Thread {
            val mf = ModelManager.modelFile(this, lastModel)
            if (mf.exists() && mf.length() > 1_000_000 && !WhisperEngine.isLoaded(mf.absolutePath)) {
                runOnUiThread { tvStatus.text = "Loading $lastModel model..." }
                val ok = WhisperEngine.ensureModel(mf.absolutePath)
                runOnUiThread { tvStatus.text = if (ok) localStatusText(lastModel) else "Model $lastModel load failed" }
            }
        }.start()
        when (prefs.getString("entry_mode", "type")) {
            "txt" -> radioEntryMode.check(R.id.radioEntryTxt)
            "both" -> radioEntryMode.check(R.id.radioEntryBoth)
            else -> radioEntryMode.check(R.id.radioEntryType)
        }

        // Accuracy switches
        val swVad = findViewById<SwitchMaterial>(R.id.switchVad)
        val swLive = findViewById<SwitchMaterial>(R.id.switchLive)
        val swBt = findViewById<SwitchMaterial>(R.id.switchBt)
        val swCaps = findViewById<SwitchMaterial>(R.id.switchCaps)
        swVad.isChecked = prefs.getBoolean("vad_on", true)
        swLive.isChecked = prefs.getBoolean("live_on", true)
        swBt.isChecked = prefs.getBoolean("bt_mic", false)
        swCaps.isChecked = prefs.getString("caps_mode", "auto") != "off"
        swVad.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("vad_on", b).apply() }
        swLive.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("live_on", b).apply() }
        swBt.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("bt_mic", b).apply() }
        swCaps.setOnCheckedChangeListener { _, b -> prefs.edit().putString("caps_mode", if(b) "auto" else "off").apply() }

        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val prev = prefs.getString("model", "small")
                val m = models[pos]
                prefs.edit().putString("model", m).apply()
                refreshModelInfo()
                if (m != prev) {
                    // unload old / load new model in background
                    tvStatus.text = "Loading $m model..."
                    Thread {
                        val mf = ModelManager.modelFile(this@MainActivity, m)
                        if (mf.exists() && mf.length() > 1_000_000) {
                            val ok = WhisperEngine.ensureModel(mf.absolutePath)
                            runOnUiThread { tvStatus.text = if (ok) "Ready: ggml-$m.bin" else "Model $m load failed" }
                        } else {
                            WhisperEngine.unloadIfIdle()
                            runOnUiThread { tvStatus.text = localStatusText(m) }
                        }
                    }.start()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("lang", langs[pos]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        radioEntryMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioEntryTxt -> "txt"
                R.id.radioEntryBoth -> "both"
                else -> "type"
            }
            prefs.edit().putString("entry_mode", mode).apply()
        }

        findViewById<Button>(R.id.btnEnableIME).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnPickIME).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnDownloadModel).setOnClickListener {
            val m = models[spinnerModel.selectedItemPosition]
            downloadModel(m)
        }

        findViewById<Button>(R.id.btnStartMeeting).setOnClickListener {
            if (!hasPermissions()) { requestPermissions(); return@setOnClickListener }
            val lang = langs[spinnerLang.selectedItemPosition]
            val model = models[spinnerModel.selectedItemPosition]
            val mode = if (radioMode.checkedRadioButtonId == R.id.radioType) "type" else "txt"
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "START"
            intent.putExtra("model", model)
            intent.putExtra("lang", lang)
            intent.putExtra("mode", mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvMeetingStatus.text = "Recording ($mode mode)... tap Stop (continues with screen off)"
        }

        findViewById<Button>(R.id.btnStopMeeting).setOnClickListener {
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "STOP"
            startService(intent)
            tvMeetingStatus.text = "Stopping... transcript saving (wait for queue)"
        }

        findViewById<Button>(R.id.btnClearModels).setOnClickListener { clearModels() }
        findViewById<Button>(R.id.btnTestModel).setOnClickListener {
            val m = models[spinnerModel.selectedItemPosition.coerceAtLeast(0)]
            val mf = ModelManager.modelFile(this, m)
            if (!mf.exists() || mf.length() < 1_000_000) {
                tvStatus.text = "TEST $m: not downloaded"
                AppLog.e("ModelTest", "$m not downloaded")
                return@setOnClickListener
            }
            tvStatus.text = "TEST: loading $m..."
            AppLog.i("ModelTest", "start load test: $m")
            Thread {
                val t0 = System.currentTimeMillis()
                val ok = WhisperEngine.ensureModel(mf.absolutePath)
                val ms = System.currentTimeMillis() - t0
                val msg = if (ok) "TEST OK: $m loaded in ${ms}ms" else "TEST FAILED: $m (${WhisperEngine.lastError})"
                AppLog.i("ModelTest", msg)
                runOnUiThread {
                    tvStatus.text = msg
                    Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            // settings already auto-saved via listeners, this confirms and triggers keyboard refresh via prefs
            val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
            prefs.edit().putLong("last_save", System.currentTimeMillis()).apply()
            Toast.makeText(this, "Settings saved - keyboard updates instantly", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnDonate).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.paypal.com/paypalme/jackfood2004"))) } catch (_: Exception) { Toast.makeText(this, "PayPal: jackfood2004@gmail.com", Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener { startActivity(Intent(this, PrivacyDashboardActivity::class.java)) }
        findViewById<Button>(R.id.btnPauseQueue).setOnClickListener {
            val paused = TranscriptionQueue.togglePause()
            (it as Button).text = if(paused) "Resume" else "Pause"
        }
        findViewById<Button>(R.id.btnClearQueue).setOnClickListener {
            val n = TranscriptionQueue.clearQueue()
            Toast.makeText(this, "Cleared $n", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRetryQueue).setOnClickListener {
            val c = TranscriptionQueue.failedCount()
            if(c==0) Toast.makeText(this, "No failed", Toast.LENGTH_SHORT).show() else { TranscriptionQueue.retryFailed(); Toast.makeText(this, "Retrying $c", Toast.LENGTH_SHORT).show() }
        }

        TranscriptionQueue.addListener(pqListener)
        progressTranscribe.progress = TranscriptionQueue.progress()

        requestPermissions()
        refreshModelInfo()

        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    tvQueue.text = TranscriptionQueue.status()
                    val lastPath = prefs.getString("last_transcript_path", "")
                    if (!lastPath.isNullOrEmpty()) {
                        tvMeetingPath.text = "Last: $lastPath"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        TranscriptionQueue.removeListener(pqListener)
        super.onDestroy()
    }

    private fun localStatusText(model: String): String = ModelManager.localStatus(this, model)

    private fun refreshModelInfo() {
        val sb = StringBuilder()
        for (m in models) {
            val f = ModelManager.modelFile(this, m)
            val marker = if (m == spinnerModel.selectedItem.toString()) " > " else "   "
            if (f.exists() && f.length() > 1_000_000) {
                sb.appendLine("$marker$m: ${f.length() / 1024 / 1024} MB [downloaded]")
            } else {
                sb.appendLine("$marker$m: not downloaded")
            }
        }
        tvModelInfo.text = sb.toString().trimEnd()
    }

    private fun clearModels() {
        val dir = ModelManager.modelsDir(this)
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "No models to clear", Toast.LENGTH_SHORT).show()
            refreshModelInfo()
            return
        }
        var freed = 0L
        for (f in files) { freed += f.length(); f.delete() }
        Toast.makeText(this, "Cleared ${files.size} model(s), freed ${freed / 1024 / 1024} MB", Toast.LENGTH_LONG).show()
        refreshModelInfo()
    }

    private fun hasPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return mic && notif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), permRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permRequestCode) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Mic permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadModel(model: String) {
        progress.visibility = ProgressBar.VISIBLE
        tvStatus.text = "Downloading ggml-$model.bin ..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.download(this@MainActivity, model) { p, msg ->
                        runOnUiThread { progress.progress = p; tvStatus.text = msg }
                    }
                }
                tvStatus.text = "Ready: ggml-$model.bin"
                Toast.makeText(this@MainActivity, "Model $model ready", Toast.LENGTH_LONG).show()
                refreshModelInfo()
            } catch (e: Exception) {
                tvStatus.text = "Failed: ${e.message}"
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = ProgressBar.GONE
            }
        }
    }
}
