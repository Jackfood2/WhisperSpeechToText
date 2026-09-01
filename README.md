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

### v2.4.2 (2026-08-26) — Enter key on keyboard
- New **↵ Enter** button stacked under **⌫ Backspace** on the right side of the keyboard. Tap inserts a new line in the focused field (hold repeats). Uses `commitText("\n")` with a key-event fallback so it works in both multi-line and single-line fields.

### v2.4.1 (2026-08-26) — Progress % that actually adapts + single model-status notification
- **Adaptive progress fixed:** the estimate was a cumulative mean that went stale after ~50 recordings (each new run shifted it <2%) — so the bar seemed to have "no effect" on real speed. It's now an **exponential moving average** (last ~5 runs dominate), recalculated after **every** transcription and applied to the next estimate.
- Per-model average is used from the **first sample**; the cross-model global no longer pollutes estimates (kept for dashboard display only).
- **One notification for load AND unload:** both states reuse the same id, so the card is always a live status. Swiping can never leave a contradictory "loaded" notice after an unload — the "unloaded" card replaces it in place and self-dismisses after 8s.
- Settings: new toggle **"Copy outstanding transcripts to clipboard"** (see v2.4.0).

### v2.4.0 (2026-08-26) — Outstanding → clipboard toggle
- New Settings switch: when **off**, parked transcripts are *not* copied to the clipboard — they can only be retrieved via the Whisper keyboard's blue *"Type pending transcript"* insert button. Toasts and logs reflect the choice.

### v2.3.10 / v2.3.11 (2026-08-26) — Model-unload countdown semantics
- Countdown is blocked **only by real work**: active recording or transcription in flight/queued.
- Waiting-to-type and outstanding transcripts do **not** block it (the text is already cached — whisper isn't needed to deliver it).
- Final re-check inside the unload thread: if activity resumes at the last second (`unload skipped - activity resumed`), nothing unloads and the countdown restarts. A new recording always resets the countdown.

### v2.3.9 (2026-08-26) — Bubble mic privilege on Android 14+ ⚠️ important
- **Root cause of "bubble records silence unless the keyboard is open":** the bubble service ran as FGS type `specialUse`, which carries **no microphone privilege**. On Android 14+/One UI, background mic capture without a `microphone`-type foreground service receives **digital silence** whenever no app component (activity/IME) is visible.
- `QuickSwitchService` now declares `foregroundServiceType="microphone"` and passes the type to `startForeground()` explicitly.
- Dead-stream self-heal detection lowered 5s → ~3s (covers short recordings).

### v2.3.8 (2026-08-26) — One shared mic for the whole app
- **Process-wide AudioRecord** owned by `AudioUtils`, reused by bubble + keyboard + meeting recorder; cleanup is stop-only (**never released between sessions**).
- Fixes two opposite failure modes at once: all-zero recordings when a new AudioRecord was created seconds after another was released (Samsung HAL), and the keyboard's mic being blocked by a held bubble recorder.
- Mid-session self-heal: if ~3s of pure digital-zero frames arrive, the recorder is recycled automatically (`recorder recycled OK` in logs).
- **Stuck "Recording Xs" notification fixed:** notification repaints on every state change (start/stop/processing/idle); the ■ Stop action rebuilds stale notifications instead of doing nothing.

### v2.3.7 (2026-08-26) — interim notification lifecycle fixes (superseded by v2.3.8's shared-mic design)

### v2.3.6 (2026-08-26) — Five bubble reliability bugs
- **Dead-bubble lockup:** mic failure left `recActive=true` → every tap ignored until reboot. Now always cleared.
- **Silence-chunk spam:** long pauses produced endless silence-only chunks ("No speech in chunk" toasts). Per-chunk voice gating added.
- **Gestures corrected:** tap = record/stop · hold ≥450ms = switch keyboard · drag = reposition (previously stationary long-press accidentally started recordings, and keyboard-switch required hold+wiggle).
- Mic leak on bubble disable (keyboard then failed with "Mic did not start").
- Duplicate model-load thread removed.

### v2.3.5 (2026-08-26) — Dashboard crash fixed ⚠️ critical
- `PrivacyDashboardActivity` was **missing from AndroidManifest.xml** (corrupted during an automated edit) — tapping Privacy Dashboard / Logs crashed instantly with `ActivityNotFoundException`. Manifest repaired; verified in built APK.
- Crash handler hardened: on-screen diagnostics keep the process alive instead of delegating to the system killer.

### v2.3.4 (2026-08-26) — Status & polish
- Keyboard status no longer stuck at "Transcribing chunk… 18%": shows "All chunks processed ✓" once queue drains.

### v2.3.3 (2026-08-26) — First words no longer cut off
- Removed a destructive 64KB drain that ate live audio right after tapping record — speech onset is preserved from frame one.

### v2.3.2 (2026-08-25) — Live notifications
- Bubble notification: live elapsed timer ("Recording 12s"), ■ Stop + Keyboard actions, visible on lock screen.
- Processing notification: tap opens app; shows `[QUEUE PAUSED]` marker.

### v2.3–v2.3.1 (2026-08-25) — Persistent recorder era
- Shared persistent recorder + session generations, deterministic thread handoff, delivery-time IME activation, clipboard fallback on parked transcripts.

### v2.2.x (2026-08-25) — Stability wave
- Voice-gated chunking, wakelock + lock-screen overlay, BT-aware recorder, lenient VAD gate (0.008), crash-proof dashboard errors (on-screen + clipboard), configurable model-unload timeout (Never/30s–360s), accurate idle countdown.

### v2.1.x (2026-08-25) — Bubble foundations
- 3-state grey/green/yellow bubble, per-session buffers, instant record + concurrent model load, clipboard fallback + paste reliability, yellow→grey completion watcher.

### v2.0.x (2026-08-25) — Simplified keyboard + chunked engine
- Mic circle + ⌫ + ⏎ + gear + X keyboard; invisible stop/start chunked transcription (first-15s rule); outstanding-transcript resume; signed release APK + Play Protect guidance; auto-save settings.

### v1.x (2026-08-24/25) — Foundations
- Queue system, adaptive per-model progress, high-contrast UI, Privacy Dashboard & logs, lock-screen recording services, resume flow, TextRouter delivery chain (IME → accessibility paste → held).

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

- **Gestures:** quick **tap** = record/stop · **hold ≥0.5s** = switch Whisper keyboard ↔ default · **drag** = reposition.
- **Notification actions** (also on lock screen): live "Recording Xs" timer, **■ Stop**, **Keyboard** switch. The notification always reflects the true state — it can never freeze on an old timer.
- Yellow returns to grey automatically when every chunk is typed/held.
- Works on the **lock screen** (tap to stop there too).
- If no text field can receive the transcript, it's **parked**: a notification counts it, it's **copied to the clipboard**, and the keyboard shows a blue *"Type pending transcript"* button — one tap inserts each entry.

### Typing into other apps — pick ONE of these two delivery paths

| Path | Setup | Best for |
|---|---|---|
| **A. Direct typing (recommended)** | One-time: `adb shell pm grant com.whisperkeyboard android.permission.WRITE_SECURE_SETTINGS` | Bubble auto-switches to the Whisper keyboard at stop and types via `InputConnection`. **No accessibility needed — banking apps stay happy.** Grant survives reboots/updates; only re-run after uninstalling. |
| **B. Typing Bridge (accessibility)** | Enable *Whisper Typing Bridge* in Accessibility settings | Types while ANY keyboard stays active — but many banking apps flag enabled accessibility services as suspicious. Disable if your bank complains. |

Delivery order is automatic: direct IME typing first, accessibility paste second, park+clipboard last.

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
- **Bubble records silence / "No speech captured":** update to **v2.3.9+** — earlier builds lacked the `microphone` foreground-service type, so Android 14+ served digital silence when no app window was visible. After install, reopen the app once so the bubble service restarts with the new type.
- **Keyboard mic dead after using the bubble:** fixed in v2.3.8 (one shared, never-released recorder). Old versions: toggle the bubble off/on once to release the mic.
- **Banking app blocks/warns:** disable *Whisper Typing Bridge* (accessibility) and use Path A (`WRITE_SECURE_SETTINGS` grant) — see delivery paths above.
- **Download failed:** grant `INTERNET` (built-in), check WiFi, retry.
- **Battery kills meeting:** `Settings → Apps → Whisper Speech to Text → Battery → Unrestricted`.
- **Progress stuck at 92%:** old APK; reinstall this version — adaptive baseline fixes it after 2–3 uses.

## License

MIT — see `LICENSE`.


