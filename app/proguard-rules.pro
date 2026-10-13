# ==============================================================================
# Swift Browser - Comprehensive ProGuard / R8 Rules for Release Optimization
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. General Optimization & Stack Trace Preservation
# ------------------------------------------------------------------------------
-repackageclasses ''
-allowaccessmodification

# Preserve essential metadata for crash reporting, reflections, and type resolution
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Deprecated,*Annotation*,Synthetic
-renamesourcefileattribute SourceFile

# Remove debug and verbose Android log statements in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# ------------------------------------------------------------------------------
# 2. Kotlin, Coroutines & Kotlinx Serialization
# ------------------------------------------------------------------------------
# Kotlin Coroutines internal exception handling & state machines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerDispatcher {
    private final android.os.Handler handler;
}

# Kotlinx Serialization - preserve generated serializers and polymorphic descriptors
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialInfo <fields>;
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class *$$serializer {
    public static final *$$serializer INSTANCE;
    public static final kotlinx.serialization.descriptors.SerialDescriptor descriptor;
}
-keepclassmembers class * {
    public static final *** Companion;
}
-keepclassmembers class * {
    public static final kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------------------
# 3. Android WebView & JavaScript Interface Bridges (CRITICAL FOR BROWSER)
# ------------------------------------------------------------------------------
# Preserve all JavascriptInterface annotated methods across WebView bridges
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @org.chromium.base.annotations.JavascriptInterface <methods>;
}

# Keep all WebView bridge implementations and callback handlers
-keep class com.swift.browser.browserengine.webrtc.WebRtcBridge { *; }
-keep class com.swift.browser.browserengine.screencapture.WebScreenCaptureBridge { *; }
-keep class com.swift.browser.browserengine.WebClipboardBridge { *; }
-keep class com.swift.browser.browserengine.WebSpeechRecognitionBridge { *; }
-keep class com.swift.browser.extensionengine.RuntimeBridge { *; }
-keep class com.swift.browser.extensionengine.ChromeWebStoreBridge { *; }
-keep class com.swift.browser.audioengine.online.OnlineMusicWebViewManager$* { *; }
-keep class com.swift.browser.webstudio.editor.CodeEditorEngine$* { *; }

# Keep ValueCallback interface implementations
-keep class * implements android.webkit.ValueCallback { *; }

# ------------------------------------------------------------------------------
# 4. Native (JNI / C++ / V8 Runtime) Bindings
# ------------------------------------------------------------------------------
# Preserve all classes containing native methods and keep method signatures
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep V8 Runtime Native Bridge and C++ JNI Entrypoints
-keep class com.swift.browser.extensionv8.V8NativeBridge {
    *;
}
-keep class com.swift.browser.extensionv8.V8NativeBridge$* {
    *;
}
-keep class com.swift.browser.nativemediaengine.NativeMediaEngine {
    *;
}
-keep class com.swift.browser.nativedownloadengine.NativeDownloadEngine {
    *;
}
-keep class com.swift.browser.extensionengine.nativecore.NativeExtensionCoreBridge {
    *;
}

# ------------------------------------------------------------------------------
# 5. Room Database & SQLite Persistence
# ------------------------------------------------------------------------------
# Room database implementations and DAOs
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.TypeConverter { *; }
-keep class * implements androidx.room.TypeConverter { *; }
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
    @androidx.room.ProvidedTypeConverter <methods>;
}

# Keep Room generated _Impl classes
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * extends androidx.room.SharedSQLiteStatement {
    <init>(...);
}
-keep class * extends androidx.room.EntityInsertionAdapter {
    <init>(...);
}
-keep class * extends androidx.room.EntityDeletionOrUpdateAdapter {
    <init>(...);
}

# ------------------------------------------------------------------------------
# 6. Jetpack Compose & ViewModel Architecture
# ------------------------------------------------------------------------------
# Compose runtime stability and inspection
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.runtime.**

# ViewModel constructors for ViewModelProvider reflection
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# ------------------------------------------------------------------------------
# 7. Data Models, Protocols & JSON Entities
# ------------------------------------------------------------------------------
# Preserve model classes used in serialization, protocol passing, and state flow
-keepclassmembers class com.swift.browser.**.model.** {
    <fields>;
    <methods>;
}
-keepclassmembers class com.swift.browser.**.protocol.** {
    <fields>;
    <methods>;
}
-keepclassmembers class com.swift.browser.**.state.** {
    <fields>;
    <methods>;
}

# ------------------------------------------------------------------------------
# 8. Media, CameraX & Coil Image Loading
# ------------------------------------------------------------------------------
# CameraX lifecycle and provider classes
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.core.**

# Coil Image Loader
-keep class coil.** { *; }
-dontwarn coil.**

# Android Media & Notifications
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

# ------------------------------------------------------------------------------
# 9. Application Components & Manifest Entrypoints
# ------------------------------------------------------------------------------
# Keep Android custom Application, Activities, Services, and Receivers
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
