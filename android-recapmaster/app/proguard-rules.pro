# Keep whisper.cpp native bindings
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
