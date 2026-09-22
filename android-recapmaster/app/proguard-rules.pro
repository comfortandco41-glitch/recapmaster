# Keep whisper.cpp native bindings
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep FFmpegKit & SmartException
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.smartexception.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
