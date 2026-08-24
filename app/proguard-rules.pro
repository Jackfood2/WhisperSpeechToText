# Add project specific ProGuard rules here.
# Keep whisper JNI classes
-keep class com.whisperkeyboard.WhisperEngine { *; }
-keep class com.whisperkeyboard.WhisperKeyboardService { *; }
-keep class com.whisperkeyboard.MeetingRecordService { *; }
