# Keep JS bridge annotations and methods
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the bridge class itself from being renamed
-keep class com.dcasel.app.MainActivity$AppBridge { *; }

# Keep BuildConfig
-keep class com.dcasel.app.BuildConfig { *; }

# Suppress warnings for AndroidX
-dontwarn androidx.**
-keep class androidx.** { *; }
