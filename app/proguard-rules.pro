# Keep native methods and their declaring classes.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.whisperkeyboard.WhisperEngine {
    *;
}

-keep class com.whisperkeyboard.WhisperKeyboardService {
    *;
}

-keep class com.whisperkeyboard.MeetingRecordService {
    *;
}

# Keep speech engine integration if it uses reflection internally.
-keep class ai.moonshine.** {
    *;
}

-dontwarn ai.moonshine.**
