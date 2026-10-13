#include "v8_jni.h"
#include "v8_runtime.h"
#include "v8_isolate_manager.h"
#include "v8_context.h"
#include <map>
#include <mutex>

namespace orion::v8runtime {

JniContext g_jniCtx;

std::string JStringToString(JNIEnv* env, jstring jstr) {
    if (!env || !jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "";
    }
    std::string str(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return str;
}

jstring StringToJString(JNIEnv* env, const std::string& str) {
    if (!env) return nullptr;
    jstring res = env->NewStringUTF(str.c_str());
    if (env->ExceptionCheck()) env->ExceptionClear();
    return res;
}

class ScopedJniEnv {
public:
    ScopedJniEnv() : vm(g_jniCtx.jvm) {
        if (!vm) return;
        jint result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&env, nullptr) == 0) attached = true;
        }
        if (result != JNI_OK && !attached) env = nullptr;
    }
    ~ScopedJniEnv() {
        if (attached && vm) vm->DetachCurrentThread();
    }
    JNIEnv* get() const { return env; }
private:
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;
};

static bool EnsureRuntimeBridgeBinding(JNIEnv* env) {
    if (!env) return false;
    static std::mutex bindingMutex;
    std::lock_guard<std::mutex> lock(bindingMutex);

    if (g_jniCtx.runtimeBridgeClass &&
        g_jniCtx.midOnHostApiCall &&
        g_jniCtx.midOnWorkerStarted &&
        g_jniCtx.midOnWorkerError &&
        g_jniCtx.midOnEventHandled &&
        g_jniCtx.midOnConsoleLog) {
        return true;
    }

    if (!g_jniCtx.runtimeBridgeClass) {
        jclass localClass = env->FindClass("com/swift/browser/extensionv8/V8RuntimeBridge");
        if (!localClass) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return false;
        }
        g_jniCtx.runtimeBridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
        env->DeleteLocalRef(localClass);
    }
    if (!g_jniCtx.runtimeBridgeClass) return false;

    if (!g_jniCtx.midOnHostApiCall) {
        g_jniCtx.midOnHostApiCall = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onHostApiCallFromNative",
            "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    }
    if (!g_jniCtx.midOnWorkerStarted) {
        g_jniCtx.midOnWorkerStarted = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onWorkerStartedFromNative",
            "(Ljava/lang/String;IZ)V");
    }
    if (!g_jniCtx.midOnWorkerError) {
        g_jniCtx.midOnWorkerError = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onWorkerErrorFromNative",
            "(Ljava/lang/String;ILjava/lang/String;)V");
    }
    if (!g_jniCtx.midOnEventHandled) {
        g_jniCtx.midOnEventHandled = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onEventHandledFromNative",
            "(Ljava/lang/String;ILjava/lang/String;Z)V");
    }
    if (!g_jniCtx.midOnConsoleLog) {
        g_jniCtx.midOnConsoleLog = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onConsoleLogFromNative",
            "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V");
    }

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return g_jniCtx.runtimeBridgeClass != nullptr && g_jniCtx.midOnHostApiCall != nullptr;
}

void DispatchHostApiCall(const std::string& extensionId,
                         int generationId,
                         const std::string& apiName,
                         const std::string& requestId,
                         const std::string& payloadJson) {
    try {
        ScopedJniEnv scopedEnv;
        JNIEnv* env = scopedEnv.get();
        if (!env || !EnsureRuntimeBridgeBinding(env) || !g_jniCtx.midOnHostApiCall) return;

        jstring jExt = StringToJString(env, extensionId);
        jstring jApi = StringToJString(env, apiName);
        jstring jReq = StringToJString(env, requestId);
        jstring jPayload = StringToJString(env, payloadJson);

        env->CallStaticVoidMethod(g_jniCtx.runtimeBridgeClass,
                                  g_jniCtx.midOnHostApiCall,
                                  jExt,
                                  generationId,
                                  jApi,
                                  jReq,
                                  jPayload);
        if (env->ExceptionCheck()) env->ExceptionClear();

        if (jExt) env->DeleteLocalRef(jExt);
        if (jApi) env->DeleteLocalRef(jApi);
        if (jReq) env->DeleteLocalRef(jReq);
        if (jPayload) env->DeleteLocalRef(jPayload);
    } catch (...) {}
}

void DispatchConsoleLog(const std::string& extensionId,
                        int generationId,
                        const std::string& level,
                        const std::string& message) {
    try {
        ScopedJniEnv scopedEnv;
        JNIEnv* env = scopedEnv.get();
        if (!env || !EnsureRuntimeBridgeBinding(env) || !g_jniCtx.midOnConsoleLog) return;

        jstring jExt = StringToJString(env, extensionId);
        jstring jLvl = StringToJString(env, level);
        jstring jMsg = StringToJString(env, message);

        env->CallStaticVoidMethod(g_jniCtx.runtimeBridgeClass,
                                  g_jniCtx.midOnConsoleLog,
                                  jExt,
                                  generationId,
                                  jLvl,
                                  jMsg);
        if (env->ExceptionCheck()) env->ExceptionClear();

        if (jExt) env->DeleteLocalRef(jExt);
        if (jLvl) env->DeleteLocalRef(jLvl);
        if (jMsg) env->DeleteLocalRef(jMsg);
    } catch (...) {}
}

void DispatchWorkerStarted(const std::string& extensionId,
                           int generationId,
                           bool success) {
    try {
        ScopedJniEnv scopedEnv;
        JNIEnv* env = scopedEnv.get();
        if (!env || !EnsureRuntimeBridgeBinding(env) || !g_jniCtx.midOnWorkerStarted) return;

        jstring jExt = StringToJString(env, extensionId);
        env->CallStaticVoidMethod(g_jniCtx.runtimeBridgeClass,
                                  g_jniCtx.midOnWorkerStarted,
                                  jExt,
                                  generationId,
                                  success ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jExt) env->DeleteLocalRef(jExt);
    } catch (...) {}
}

void DispatchWorkerError(const std::string& extensionId,
                         int generationId,
                         const std::string& errorMessage) {
    try {
        ScopedJniEnv scopedEnv;
        JNIEnv* env = scopedEnv.get();
        if (!env || !EnsureRuntimeBridgeBinding(env) || !g_jniCtx.midOnWorkerError) return;

        jstring jExt = StringToJString(env, extensionId);
        jstring jErr = StringToJString(env, errorMessage);
        env->CallStaticVoidMethod(g_jniCtx.runtimeBridgeClass,
                                  g_jniCtx.midOnWorkerError,
                                  jExt,
                                  generationId,
                                  jErr);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jExt) env->DeleteLocalRef(jExt);
        if (jErr) env->DeleteLocalRef(jErr);
    } catch (...) {}
}

void DispatchEventHandled(const std::string& extensionId,
                          int generationId,
                          const std::string& eventName,
                          bool success) {
    try {
        ScopedJniEnv scopedEnv;
        JNIEnv* env = scopedEnv.get();
        if (!env || !EnsureRuntimeBridgeBinding(env) || !g_jniCtx.midOnEventHandled) return;

        jstring jExt = StringToJString(env, extensionId);
        jstring jEvt = StringToJString(env, eventName);
        env->CallStaticVoidMethod(g_jniCtx.runtimeBridgeClass,
                                  g_jniCtx.midOnEventHandled,
                                  jExt,
                                  generationId,
                                  jEvt,
                                  success ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jExt) env->DeleteLocalRef(jExt);
        if (jEvt) env->DeleteLocalRef(jEvt);
    } catch (...) {}
}

} // namespace orion::v8runtime

using namespace orion::v8runtime;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jniCtx.jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClass = env->FindClass("com/swift/browser/extensionv8/V8RuntimeBridge");
    if (localClass != nullptr) {
        g_jniCtx.runtimeBridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
        g_jniCtx.midOnHostApiCall = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onHostApiCallFromNative",
            "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
        );
        g_jniCtx.midOnWorkerStarted = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onWorkerStartedFromNative",
            "(Ljava/lang/String;IZ)V"
        );
        g_jniCtx.midOnWorkerError = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onWorkerErrorFromNative",
            "(Ljava/lang/String;ILjava/lang/String;)V"
        );
        g_jniCtx.midOnEventHandled = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onEventHandledFromNative",
            "(Ljava/lang/String;ILjava/lang/String;Z)V"
        );
        g_jniCtx.midOnConsoleLog = env->GetStaticMethodID(
            g_jniCtx.runtimeBridgeClass,
            "onConsoleLogFromNative",
            "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V"
        );
    } else {
        env->ExceptionClear();
        LOGI("[OrionV8] V8RuntimeBridge class not immediately present in JNI_OnLoad, deferring binding.");
    }

    LOGI("[OrionV8] JNI_ONLOAD: JNI bridge registered successfully. Native V8 initialization is deferred until worker demand.");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    V8IsolateManager::GetInstance().StopAllWorkers();
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_jniCtx.runtimeBridgeClass) {
            env->DeleteGlobalRef(g_jniCtx.runtimeBridgeClass);
            g_jniCtx.runtimeBridgeClass = nullptr;
        }
    }
    V8EngineRuntime::ShutdownPlatform();
    LOGI("[OrionV8] JNI_OnUnload: shutdown completed.");
}

// Native methods exposed to com.swift.browser.extensionv8.V8NativeBridge

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeInitializeV8(
    JNIEnv* env, jobject thiz) {
    return V8EngineRuntime::InitializePlatform() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetV8State(
    JNIEnv* env, jobject thiz) {
    return static_cast<jint>(V8EngineRuntime::GetState());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetV8FailureReason(
    JNIEnv* env, jobject thiz) {
    return StringToJString(env, V8EngineRuntime::GetFailureReason());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetHeaderVersion(
    JNIEnv* env, jobject thiz) {
#if ORION_HAS_V8
    std::string hv = std::to_string(V8_MAJOR_VERSION) + "." +
                     std::to_string(V8_MINOR_VERSION) + "." +
                     std::to_string(V8_BUILD_NUMBER) + "." +
                     std::to_string(V8_PATCH_LEVEL);
    return StringToJString(env, hv);
#else
    return StringToJString(env, "NO_HEADERS");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetRuntimeVersion(
    JNIEnv* env, jobject thiz) {
#if ORION_HAS_V8
    const char* v = v8::V8::GetVersion();
    return v ? StringToJString(env, v) : StringToJString(env, "");
#else
    return StringToJString(env, "NO_RUNTIME");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeCreateWorker(
    JNIEnv* env, jobject thiz, jstring extensionId, jint generationId, jstring scriptPath,
    jstring bootstrapScript, jboolean isModule, jstring moduleSourcesBlob,
    jstring extensionRootPath, jint maxHeapMemoryMb) {
    if (!V8EngineRuntime::IsInitialized() || generationId <= 0 || !extensionId) {
        return JNI_FALSE;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        if (extId.empty()) return JNI_FALSE;
        std::string path = JStringToString(env, scriptPath);
        std::string bootstrap = JStringToString(env, bootstrapScript);
        std::string moduleBlob = JStringToString(env, moduleSourcesBlob);
        std::string root = JStringToString(env, extensionRootPath);
        return V8IsolateManager::GetInstance().CreateWorker(
            extId, generationId, path, bootstrap, isModule == JNI_TRUE, moduleBlob, root,
            static_cast<int>(maxHeapMemoryMb)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeExecuteScript(
    JNIEnv* env, jobject thiz, jstring extensionId, jint generationId, jstring scriptContent) {
    if (!V8EngineRuntime::IsInitialized() || generationId <= 0 || !extensionId) {
        return JNI_FALSE;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        if (extId.empty()) return JNI_FALSE;
        std::string script = JStringToString(env, scriptContent);
        return V8IsolateManager::GetInstance().ExecuteScript(extId, generationId, script) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeExecuteScriptResult(
    JNIEnv* env, jobject thiz, jstring extensionId, jint generationId, jstring scriptContent) {
    if (!V8EngineRuntime::IsInitialized() || generationId <= 0 || !extensionId) {
        return nullptr;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        if (extId.empty()) return nullptr;
        std::string script = JStringToString(env, scriptContent);
        std::string resultJson;
        if (!V8IsolateManager::GetInstance().ExecuteScriptWithResult(extId, generationId, script, &resultJson)) {
            return nullptr;
        }
        return StringToJString(env, resultJson);
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeDispatchEvent(
    JNIEnv* env, jobject thiz, jstring extensionId, jint generationId, jstring eventName, jstring payloadJson) {
    if (!V8EngineRuntime::IsInitialized() || generationId <= 0 || !extensionId || !eventName) {
        return JNI_FALSE;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        std::string evt = JStringToString(env, eventName);
        if (extId.empty() || evt.empty()) return JNI_FALSE;
        std::string payload = JStringToString(env, payloadJson);
        return V8IsolateManager::GetInstance().DispatchEvent(extId, generationId, evt, payload) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeResolveHostApiCall(
    JNIEnv* env, jobject thiz, jstring extensionId, jint generationId, jstring requestId, jstring resultJson, jstring errorMsg) {
    if (!V8EngineRuntime::IsInitialized() || generationId <= 0 || !extensionId || !requestId) {
        return;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        std::string reqId = JStringToString(env, requestId);
        if (extId.empty() || reqId.empty()) return;
        std::string result = JStringToString(env, resultJson);
        std::string err = JStringToString(env, errorMsg);
        V8IsolateManager::GetInstance().ResolveHostCall(extId, generationId, reqId, result, err);
    } catch (...) {}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeStopWorker(
    JNIEnv* env, jobject thiz, jstring extensionId) {
    if (!V8EngineRuntime::IsInitialized() || !extensionId) {
        return JNI_FALSE;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        if (extId.empty()) return JNI_FALSE;
        return V8IsolateManager::GetInstance().StopWorker(extId) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeStopAllWorkers(
    JNIEnv* env, jobject thiz) {
    if (!V8EngineRuntime::IsInitialized()) {
        return;
    }
    try {
        V8IsolateManager::GetInstance().StopAllWorkers();
    } catch (...) {}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetHeapUsage(
    JNIEnv* env, jobject thiz, jstring extensionId) {
    if (!V8EngineRuntime::IsInitialized() || !extensionId) {
        return 0L;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        if (extId.empty()) return 0L;
        return static_cast<jlong>(V8IsolateManager::GetInstance().GetHeapUsage(extId));
    } catch (...) {
        return 0L;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeFireTimer(
    JNIEnv* env, jobject thiz, jstring extensionId, jint generationId, jint timerId) {
    if (!V8EngineRuntime::IsInitialized() || generationId <= 0 || timerId <= 0 || !extensionId) {
        return JNI_FALSE;
    }
    try {
        std::string extId = JStringToString(env, extensionId);
        if (extId.empty()) return JNI_FALSE;
        return V8IsolateManager::GetInstance().FireTimer(extId, generationId, timerId) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeHasFullV8(
    JNIEnv* env, jobject thiz) {
#if ORION_HAS_V8
    if (!V8EngineRuntime::IsInitialized()) return JNI_FALSE;
    const char* runtimeVersion = v8::V8::GetVersion();
    if (!runtimeVersion || strlen(runtimeVersion) == 0) return JNI_FALSE;

    try {
        v8::ArrayBuffer::Allocator* allocator = v8::ArrayBuffer::Allocator::NewDefaultAllocator();
        if (!allocator) return JNI_FALSE;

        v8::Isolate::CreateParams create_params;
        create_params.array_buffer_allocator = allocator;
        v8::Isolate* isolate = v8::Isolate::New(create_params);
        if (!isolate) {
            delete allocator;
            return JNI_FALSE;
        }

        bool smokeSuccess = false;
        {
            v8::Locker locker(isolate);
            v8::Isolate::Scope isolate_scope(isolate);
            v8::HandleScope handle_scope(isolate);
            v8::Local<v8::Context> context = v8::Context::New(isolate);
            if (!context.IsEmpty()) {
                v8::Context::Scope context_scope(context);
                v8::TryCatch try_catch(isolate);

                const char* testJs = "(function() { return 6 * 7; })()";
                v8::Local<v8::String> source = v8::String::NewFromUtf8(isolate, testJs, v8::NewStringType::kNormal).ToLocalChecked();
                v8::Local<v8::Script> script;
                if (v8::Script::Compile(context, source).ToLocal(&script)) {
                    v8::Local<v8::Value> result;
                    if (script->Run(context).ToLocal(&result)) {
                        if (result->IsNumber() && result->NumberValue(context).FromMaybe(0.0) == 42.0) {
                            isolate->PerformMicrotaskCheckpoint();
                            smokeSuccess = true;
                        }
                    }
                }
            }
        }

        isolate->Dispose();
        delete allocator;

        return smokeSuccess ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        LOGE("[OrionV8] nativeHasFullV8 smoke test encountered exception");
        return JNI_FALSE;
    }
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetV8Version(
    JNIEnv* env, jobject thiz) {
#if ORION_HAS_V8
    const char* v = v8::V8::GetVersion();
    return v ? StringToJString(env, v) : StringToJString(env, "");
#else
    return StringToJString(env, "STUB_NO_V8");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetV8GitRevision(
    JNIEnv* env, jobject thiz) {
#if ORION_HAS_V8
    return StringToJString(env, "NATIVESCRIPT_PRECOMPILED");
#else
    return StringToJString(env, "STUB_NO_REVISION");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionv8_V8NativeBridge_nativeGetV8BuildInfo(
    JNIEnv* env, jobject thiz) {
#if ORION_HAS_V8
    std::string info = "Runtime=NativeScript Android;V8=" + std::string(v8::V8::GetVersion()) +
                       ";arch=arm64-v8a;os=android;provider=@nativescript/android@9.1.1" +
                       ";contract=runtime-version-verified;build-flags=not-exported";
    return StringToJString(env, info);
#else
    return StringToJString(env, "STUB;HAS_V8=0");
#endif
}

