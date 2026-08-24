# Whisper Speech to Text — Offline Android IME + Meeting Recorder

> **On-device Whisper (tiny / base / small / medium) for Samsung Galaxy S23 FE and any arm64-v8a Android 8.0+ device. No internet after model download. Queue + adaptive progress + lock-screen recording.**

![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue)
![Whisper](https://img.shields.io/badge/Whisper.cpp-GGML-purple)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

## Features

| Feature | Detail |
|---|---|
| **Voice keyboard (IME)** | Replaces Gboard/Samsung Keyboard. `Speak` → `Stop` → text **types into the focused field** (WhatsApp, Chrome, Gmail, Notes…) via `InputMethodService.commitText()`. No QWERTY needed. |
| **Entry methods on keyboard** | `Type` (commit), `Save TXT`, or `Both` (tiny / base / small / medium buttons + output buttons directly on the keyboard). |
| **Meeting recorder** | Foreground service (`microphone`) records 30 s chunks → queue → saves clean words-only `Documents/WhisperNotes/meeting_*.txt` (no timestamps) + `failed/` retry copies. Notification `● 12:34 | Queue: 1 pending`. |
| **Queue system** | `LinkedBlockingQueue + single-thread executor`. Speak while busy → queued FIFO. `Pause / Resume / Clear Queue / Retry Failed` on both keyboard and app. Failed WAVs auto-saved. |
| **Adaptive progress bar** | Mini `ProgressBar + %` on keyboard and `MainActivity`. Expected time = `audioSec * avgRatio + 0.6s` where `avgRatio` is **per-model learned average** (`transcribeSec/audioSec` stored in `whisper_stats` prefs). The more you use it, the more accurate. Creeps 88→98% if slower than expected, jumps 100% on finish. |
| **Lock-screen recording** | `ImeRecordService` (keyboard) + `MeetingRecordService` hold `PARTIAL_WAKE_LOCK + foreground notification` → continues with screen off / auto-lock. Stop via keyboard or notification. |
| **Model manager** | In-app `Download / Verify`, `Downloaded: tiny 39MB ...`, `Clear All`. Models from `huggingface.co/ggerganov/whisper.cpp` → `…/files/models/ggml-*.bin`. S23 FE (Snapdragon 8 Gen1, 8 GB): `tiny` ~0.5 s/10 s, `base` ~1 s, `small` **recommended** ~2.5 s, `medium` ~6 s. |

## Screenshots

> Add screenshots of: (1) keyboard with Speak/Stop + model + output row + progress bar, (2) MainActivity model list + queue panel.

## Quick Start (User)

1. Download APK from **Releases** → install on S23 FE (allow Unknown Apps).
2. Open **Whisper Speech to Text** → `1. Enable Keyboard` → toggle ON → `2. Switch` → choose **Whisper Speech to Text**.
3. Pick `small` → `Download Model` (WiFi, 244 MB).
4. Open any app → tap text field → **Speak** → talk → **Stop** → text inserts at cursor.
5. Long meeting: app → `Start Meeting Recording` → notification shows time + queue → `Stop` → find `Documents/WhisperNotes/`.

## Build from Source

### Option A: Android Studio (recommended, 5–15 min first build)

```bash
# JDK 17 required
winget install EclipseAdoptium.Temurin.17.JDK
# Android SDK: SDK 34 + NDK 26.1.10909125 + CMake 3.22.1
# then:
git clone https://github.com/YOUR_USER/WhisperSpeechToText.git
cd WhisperSpeechToText
# whisper.cpp is fetched automatically at build time (or run scripts/setup_whisper_cpp.ps1)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Option B: GitHub Actions (no local SDK)

Push this repo to GitHub → **Actions → Build APK** runs Ubuntu + JDK 17 + `git clone whisper.cpp` → download `WhisperSpeechToText-apk` artifact.

Requires: `INTERNET`, `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.

## Project Structure

```
app/src/main/java/com/whisperkeyboard/
  MainActivity.kt              # app UI: model/lang/entryMode, download, meeting controls, queue poll
  WhisperKeyboardService.kt    # IME: Speak/Stop, model/mode buttons, progress bar, WakeLock via ImeRecordService
  MeetingRecordService.kt      # foreground meeting: chunk 30s, WakeLock, queue
  ImeRecordService.kt          # foreground holder so IME survives lock screen
  TranscriptionQueue.kt        # queue + adaptive progress (per-model avg ratio in whisper_stats)
  WhisperEngine.kt             # JNI to libwhisper_jni.so
  ModelManager.kt              # HuggingFace download, storage
  AudioUtils.kt                # 16 kHz PCM → WAV
app/src/main/cpp/
  CMakeLists.txt               # add_subdirectory(whisper.cpp) → libwhisper_jni.so
  whisper_jni.c                # whisper_init_from_file_with_params + whisper_full
  whisper.cpp/                 # NOT committed; cloned at build (see .gitignore)
app/src/main/res/layout/
  activity_main.xml            # app layout + progressTranscribe + tvProgressPct
  keyboard_view.xml            # IME: Speak/Stop + model + output + Pause/Clear/Retry + mini bar
```

## Which Files to Upload to GitHub

**Upload these (already in this folder):**

- `app/src/**`, `app/build.gradle`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/**`
- `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew.bat`
- `build.gradle`, `settings.gradle`, `gradle.properties`, `scripts/`, `.github/workflows/build.yml`
- `README.md`, `LICENSE`, `.gitignore`, `BUILD_INSTRUCTIONS.md`

**Do NOT upload (in .gitignore):**

- `app/build/`, `app/.cxx/`, `.gradle/`, `local.properties`
- `app/src/main/cpp/whisper.cpp/` (cloned at build)
- `*.apk`, `*.bin`, `*.pt`, `ggml-*.bin` (models downloaded on device)
- `.idea/`, `captures/`

Check: `git status` should show only the files above, not `build/` or `whisper.cpp/`.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | mic for voice typing / meeting |
| `INTERNET` | one-time model download from HuggingFace |
| `FOREGROUND_SERVICE_MICROPHONE` | meeting + IME foreground recording |
| `WAKE_LOCK` | keep recording with screen off |
| `POST_NOTIFICATIONS` | meeting REC notification with Stop action |

## Performance Notes (S23 FE)

The device has no per-app model warm-up; first transcription after app launch is ~1 s slower (model load). Keep `small` for best accuracy/battery. Use `tiny`/`base` for instant notes. `medium` needs WiFi download and may OOM if many apps open — close others first.

## Troubleshooting

- **No text inserted:** long-press spacebar → switch input to Whisper Speech to Text.
- **Download failed:** grant `INTERNET` (built-in), check WiFi, retry.
- **Battery kills meeting:** `Settings → Apps → Whisper Speech to Text → Battery → Unrestricted`.
- **Progress stuck at 92%:** old APK; reinstall this version — adaptive baseline fixes it after 2–3 uses.

## License

MIT — see `LICENSE`.
