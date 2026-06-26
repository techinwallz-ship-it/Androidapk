# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ── Keep readable crash stack traces (CrashReporter / crash.log) ──
-keepattributes SourceFile,LineNumberTable

# ── WebView JS bridges: methods called by name from the React SPA. ──
# Without this, R8 strips/renames them and the web UI silently breaks.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.pisignage.AndroidBridge { *; }
-keep class com.example.pisignage.AndroidMedia { *; }
-keep class com.example.pisignage.AndroidApp { *; }

# ── Socket.IO / Engine.IO (reflection-heavy) ──
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# ── OkHttp / Okio (have their own consumer rules, but be explicit) ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**