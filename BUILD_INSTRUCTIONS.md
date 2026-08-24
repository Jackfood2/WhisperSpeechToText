# Build WhisperKeyboard APK — Step by step (Windows)

## Option A: Android Studio (15 mins, one-time)

1. Install **Android Studio Hedgehog+**: https://developer.android.com/studio
2. Install SDK: SDK Manager → Android SDK 34 + NDK 26.1.10909125 + CMake 3.22.1
3. Install JDK 17 (bundled with Studio, no action needed)
4. Open folder `C:\Peter\WhisperAndroid` in Android Studio
5. Wait for Gradle Sync (first time downloads whisper.cpp prebuild ~2 mins)
6. Connect S23 FE via USB → Enable Developer Options → USB Debugging
7. Click ▶ Run → select S23 FE → installs `WhisperKeyboard` debug APK

**Build APK file to share:**
`Build → Build APK(s)` → `app/build/outputs/apk/debug/app-debug.apk` (copy to phone)

## Option B: No install — Cloud build via GitHub Actions

1. Create empty GitHub repo `whisper-keyboard`
2. Upload this entire `WhisperAndroid` folder to repo (drag & drop on github.com)
3. Go to `Actions` tab → workflow `Build APK` runs automatically → download APK artifact
4. Download APK on S23 FE (My Files) → tap to install → allow Unknown Apps

No PC toolchain needed. Takes ~8 mins cloud build.

## After Install on S23 FE

1. Open app `WhisperKeyboard` (purple icon)
2. `1️⃣ Enable Keyboard` → toggle WhisperKeyboard ON → Allow
3. `2️⃣ Select Input Method` → choose WhisperKeyboard
4. `3️⃣ Download Model` → choose `small` (recommended) or `base` for speed → Download (WiFi)
5. Test: open Samsung Notes / WhatsApp → tap text field → keyboard appears → tap 🎤 → speak → text inserts
6. `Settings → Language` choose `auto` or `en/zh/ja/ko`

**Meeting Recording:**
- In app → `📝 Start Meeting Recording` → Notification shows REC ● 00:12:34 → speak whole meeting → `Stop` → finds transcript at `/Documents/WhisperNotes/meeting_2026-08-24_1530.txt` + `.srt`
- Also accessible inside any app via keyboard `≡` → `Meeting Mode`

**Storage:**
- Models at `/Android/data/com.whisperkeyboard/files/models/ggml-small.bin`
- Notes at `/Documents/WhisperNotes/`

## Converting your existing .pt models

Your PC has `tiny.pt` etc. Android uses `ggml`. Two ways:

**Auto (inside app)**: App downloads ggml from huggingface.co/ggerganov/whisper.cpp automatically.

**Manual convert your files:**
```powershell
cd C:\Peter\WhisperAndroid\scripts
.\convert_pt_to_ggml.ps1 -ModelPath C:\Peter\Python311\App_Whisper\Scripts\small.pt -OutDir C:\Peter\WhisperAndroid\app\src\main\assets\models
```
Then copy `ggml-small.bin` to phone manually via USB.

## Troubleshooting S23 FE (One UI 6 / Android 14)

- No text inserted → Check keyboard is selected (not Samsung Keyboard). Long-press spacebar to switch.
- Mic permission → App requests RECORD_AUDIO on first 🎤 tap → Allow
- Battery kills recording → Settings → Apps → WhisperKeyboard → Battery → Unrestricted (required for 1h+ meetings)
- Medium model OOM → Use small. Or close apps → retry.
- Queue not working → Check notification `Queue: 2 pending` → means chunks queued correctly.

