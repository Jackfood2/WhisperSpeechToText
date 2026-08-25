# Whisper Speech to Text — Offline Android IME + Meeting Recorder

> **On-device Whisper (tiny / base / small / medium) for any arm64-v8a Android 8.0+ device. No internet after model download. Queue + adaptive progress + lock-screen recording.**

## ⬇ Download the APK

**[→ Download the latest APK from Releases](https://github.com/Jackfood2/WhisperSpeechToText/releases/latest)**

1. Tap the APK (`WhisperSpeechToText.apk`) → allow *Install unknown apps* if asked
2. Open **Whisper Speech to Text** → enable the keyboard → pick a voice model → speak

### If Google Play Protect blocks the install ("App blocked to protect your device")

**Option A — keep Play Protect on (recommended):**
1. On the warning screen, tap **More details** (or the small ▾ arrow)
2. Tap **Install anyway** → confirm. Done — this is a one-time approval per update.

**Option B — temporarily disable Play Protect:**
1. Open the **Play Store** app → tap your profile icon (top right)
2. **Play Protect** → tap the **⚙ Settings gear** (top right)
3. Turn **off** *Scan apps with Play Protect* → confirm
4. Install the APK, then turn scanning **back on**

> Why this happens: the APK is self-signed and uses sensitive APIs (microphone service, accessibility typing bridge). It contains **no ads, no analytics, no network access after model download** — you can verify in the Privacy Dashboard inside the app.

[![Download APK](https://img.shields.io/badge/⬇_Download-APK_(Releases)-2EA44F?style=for-the-badge&logo=android)](https://github.com/Jackfood2/WhisperSpeechToText/releases/latest) [![Donate PayPal](https://img.shields.io/badge/Donate-PayPal-blue.svg?logo=paypal)](https://www.paypal.com/paypalme/jackfood2004) [![Sponsor](https://img.shields.io/badge/Sponsor-GitHub-pink.svg?logo=github)](https://github.com/sponsors/Jackfood2)
[![Release](https://img.shields.io/github/v/release/Jackfood2/WhisperSpeechToText?label=version)](https://github.com/Jackfood2/WhisperSpeechToText/releases/latest)

## Changelog

### v2.1.5 (2026-08-25) — Bubble hardening
- Per-session audio buffer (rapid stop/start no longer corrupts chunks), crash-safe processing flag, drag vs long-press disambiguation, mic-conflict guard.

### v2.1.4 (2026-08-25) — Bubble yellow-state fix
- Stop starts the completion watcher: yellow → grey automatically once delivered/held.

### v2.1.3 (2026-08-25) — Instant record + concurrent load
- Mic & bubble start recording immediately; model loads in parallel; first chunk transcribes when ready.
- Fixed bubble skipping the yellow state after stop.

### v2.1.2 (2026-08-25) — Clipboard fallback + paste reliability
- Held transcripts auto-copied to clipboard; accessibility paste searches all windows; one-time bridge hint.

### v2.1.1 (2026-08-25) — Bubble lock-screen + empty-chunk fixes
- Wakelock + lock-screen overlay for bubble recording; voice-gated chunking (no leading-silence chunks); silent chunks pre-dropped.

### v2.1 (2026-08-25) — 3-state bubble
- Grey/Green/Yellow tap-to-record states with toasts; long-press switches keyboard.

### v2.0.x (2026-08-25) — Simplified keyboard + app-first settings
- Keyboard = mic circle + backspace + gear(app settings) + close; chunked invisible stop-start engine; outstanding-transcript resume flow; signed release APK; Play Protect guidance.

### v1.9.x (2026-08-24/25) — Resume flow era
- Outstanding transcripts, dynamic backspace, TextRouter (IME → a11y paste → held), processing notification on lock screen, battery-aware model unload.

### v1.1 (2026-08-24) — Accuracy UX + UI polish
- **High-contrast UI:** `#212121` on `#FFFFFF` cards with `stroke #E0E0E0`, 13sp+ fonts; spinner selected + dropdown now **black on white** (`spinner_item.xml`, `spinner_dropdown_item.xml`) — no more grey-on-white.
- **Accuracy UX on keyboard:** `VAD: ON/OFF` (1.3s silence auto-Stop via RMS), `LIVE: ON/OFF` (1.6s preview via `setComposingText`), `BT: ON/OFF` (`VOICE_COMMUNICATION` + SCO), `Caps: AUTO/ON/OFF` + `isNoSpeechText` filter.
- **Privacy Dashboard & Logs** (`PrivacyDashboardActivity`): per-model adaptive baseline `ratio_*` + `count_*` in `whisper_stats`, stored models, `WhisperNotes/*.txt` count, toggles dump, recent transcript preview; Reset/Copy/Clear.
- **Save Settings instant:** `Save Settings (updates keyboard instantly)` button + `SharedPreferences.OnSharedPreferenceChangeListener` in IME — change in app reflects on keyboard without reopening.
- **Adaptive progress fix:** per-model learned `avgRatio` (`transcribeSec/audioSec`) with global fallback, creep 88→98% after expected, `recordStats()` after each job.
- **Lock-screen:** `ImeRecordService` + `MeetingRecordService` `PARTIAL_WAKE_LOCK` + `microphone` foreground.

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
| **Model manager** | In-app `Download / Verify`, `Downloaded: tiny 39MB ...`, `Clear All`. Models from `huggingface.co/ggerganov/whisper.cpp` → `…/files/models/ggml-*.bin`. Modern mid/high-end Android \(e.g. Snapdragon 8-series, 8 GB RAM\): `tiny` ~0.5 s/10 s, `base` ~1 s, `small` **recommended** ~2.5 s, `medium` ~6 s. |

## Screenshots

> Add screenshots of: (1) keyboard with mic circle + labels row, (2) MainActivity, (3) Mic bubble states.

## 🎙 Mic Bubble — setup & usage

The bubble lets you dictate into **any app, from anywhere**, including with another keyboard active.

### One-time setup (do this first)

1. **Install permission** — in the app: **Settings → Mic Bubble → toggle ON** → allow *Display over other apps* → toggle ON again.
2. **Accessibility (required for typing into other apps)** — Android path:
   **Settings → Apps → Whisper Speech to Text → Permissions** *(some phones: Settings → Accessibility → Installed apps)*
   then enable **"Whisper Typing Bridge"** → Allow.
   *Without this the bubble still records, but text is held as "pending" (also auto-copied to clipboard) until you enable it.*
3. *(Optional, instant keyboard switching)* connect phone once and run:
   `adb shell pm grant com.whisperkeyboard android.permission.WRITE_SECURE_SETTINGS`

### Using the bubble

| Bubble color | Meaning | Tap action |
|---|---|---|
| ⚪ Light grey | Idle | **Start recording** |
| 🟢 Green | Recording | **Stop** → chunks transcribe immediately |
| 🟡 Yellow | Processing | Start a **new** recording anytime (previous keeps processing) |

- Yellow returns to grey automatically when every chunk is typed/held.
- **Drag** to reposition. **Long-press (≥0.5s)** = switch to Whisper keyboard / back to default.
- Works on the **lock screen** (tap to stop there too).
- If no text field can receive the transcript, it's **parked**: a notification counts it, it's **copied to the clipboard**, and the keyboard shows a blue *"Type pending transcript"* button — one tap inserts each entry.

## Quick Start (User)

1. Download APK from **Releases** → install on your phone (allow Unknown Apps).
2. Open **Whisper Speech to Text** → `1. Enable Keyboard` → toggle ON → `2. Switch` → choose **Whisper Speech to Text**.
3. Pick `small` → `Download Model` (WiFi, 244 MB).
4. Open any app → tap text field → tap the big mic circle → talk → tap ■ to stop → text appears as you pause (chunked).
5. Or set up the **Mic Bubble** (section above) and dictate from any app / lock screen.
6. Long meeting: app → `Start Meeting Recording` → notification shows time + queue → `Stop` → find `Documents/WhisperNotes/`.

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

## Performance Notes

The device has no per-app model warm-up; first transcription after app launch is ~1 s slower (model load). Keep `small` for best accuracy/battery. Use `tiny`/`base` for instant notes. `medium` needs WiFi download and may OOM if many apps open — close others first.

## Donate

If this saves you time, support offline development — **PayPal: jackfood2004@gmail.com** — [paypal.me/jackfood2004](https://www.paypal.com/paypalme/jackfood2004) — or use GitHub Sponsors ( ♥ Sponsor button on repo). Funds go to device testing + smaller/faster models.

> PayPal email: `jackfood2004@gmail.com` — also works via `Send money` in the PayPal app.

## Troubleshooting

- **No text inserted:** long-press spacebar → switch input to Whisper Speech to Text.
- **Download failed:** grant `INTERNET` (built-in), check WiFi, retry.
- **Battery kills meeting:** `Settings → Apps → Whisper Speech to Text → Battery → Unrestricted`.
- **Progress stuck at 92%:** old APK; reinstall this version — adaptive baseline fixes it after 2–3 uses.

## License

MIT — see `LICENSE`.


