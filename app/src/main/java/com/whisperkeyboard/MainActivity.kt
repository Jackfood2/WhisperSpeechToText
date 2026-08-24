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

        spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        spinnerLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)

        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)
        spinnerModel.setSelection(models.indexOf(prefs.getString("model", "small")).coerceAtLeast(0))
        spinnerLang.setSelection(langs.indexOf(prefs.getString("lang", "auto")).coerceAtLeast(0))
        when (prefs.getString("entry_mode", "type")) {
            "txt" -> radioEntryMode.check(R.id.radioEntryTxt)
            "both" -> radioEntryMode.check(R.id.radioEntryBoth)
            else -> radioEntryMode.check(R.id.radioEntryType)
        }

        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("model", models[pos]).apply()
                refreshModelInfo()
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
            tvMeetingStatus.text = "Recording ($mode mode)... tap Stop when done (continues with screen off)"
        }

        findViewById<Button>(R.id.btnStopMeeting).setOnClickListener {
            val intent = Intent(this, MeetingRecordService::class.java)
            intent.action = "STOP"
            startService(intent)
            tvMeetingStatus.text = "Stopping... transcript saving (wait for queue)"
        }

        findViewById<Button>(R.id.btnClearModels).setOnClickListener {
            clearModels()
        }

        TranscriptionQueue.addListener(pqListener)
        progressTranscribe.progress = TranscriptionQueue.progress()
        tvProgressPct.text = if (TranscriptionQueue.progress() == 0) "0% - idle" else "${TranscriptionQueue.progress()}%"

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
        for (f in files) {
            freed += f.length()
            f.delete()
        }
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
                Toast.makeText(this, "Mic permission required for speech recognition", Toast.LENGTH_LONG).show()
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
                        runOnUiThread {
                            progress.progress = p
                            tvStatus.text = msg
                        }
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
