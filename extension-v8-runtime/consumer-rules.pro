# JNI entry points are resolved by native code at runtime. Keep class and method names stable.
-keep class com.swift.browser.extensionv8.V8NativeBridge { *; }
-keep class com.swift.browser.extensionv8.V8RuntimeBridge { *; }
-keep class com.swift.browser.extensionv8.V8WorkerManager { *; }
