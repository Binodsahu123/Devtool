#include "v8_runtime.h"
#include "v8_jni.h"
#include "orion_v8_build_contract.h"
#include <cstring>

namespace orion::v8runtime {

std::atomic<V8InitState> V8EngineRuntime::s_state{V8InitState::NOT_INITIALIZED};
std::mutex V8EngineRuntime::s_lifecycleMutex;
std::string V8EngineRuntime::s_failureReason = "";

#if ORION_HAS_V8
std::unique_ptr<v8::Platform> V8EngineRuntime::s_platform;
bool V8EngineRuntime::s_platformInitialized = false;
bool V8EngineRuntime::s_v8Initialized = false;
#endif

bool V8EngineRuntime::InitializePlatform() {
    // Fast path if already ready or failed
    V8InitState currentState = s_state.load();
    if (currentState == V8InitState::READY) return true;
    if (currentState == V8InitState::FAILED) return false;

    std::lock_guard<std::mutex> lock(s_lifecycleMutex);
    currentState = s_state.load();
    if (currentState == V8InitState::READY) return true;
    if (currentState == V8InitState::FAILED) return false;

    s_state.store(V8InitState::INITIALIZING);
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: START | Extension: * | Generation: 0 | NativePlatformInit");
    LOGI("[OrionV8] V8_INIT_START: Beginning explicit V8 platform and engine initialization");

#if ORION_HAS_V8
    try {
        // Verify the compile-time header contract before touching the global V8 platform.
        // This prevents loading a NativeScript binary built against a different V8 ABI.
        LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: header/runtime version validation | Status: START | Extension: * | Generation: 0 | NativeRuntimeCheck");
        const std::string runtimeVersion = v8::V8::GetVersion();
        if (runtimeVersion != ORION_V8_EXPECTED_VERSION) {
            s_failureReason = std::string("V8 runtime contract mismatch: expected ") +
                              ORION_V8_EXPECTED_VERSION + ", got " + runtimeVersion;
            s_state.store(V8InitState::FAILED);
            LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: header/runtime version validation | Status: ERROR | Extension: * | Generation: 0 | Error: %s", s_failureReason.c_str());
            LOGE("[OrionV8] V8_INIT_FAILED: %s", s_failureReason.c_str());
            return false;
        }
        LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: header/runtime version validation | Status: SUCCESS | Extension: * | Generation: 0 | Version: %s", runtimeVersion.c_str());

        // Conservative Android flags. The embedder owns memory policy; workers remain
        // isolated and the platform is initialized exactly once per process.
        const char* flags = "--expose-gc";
        v8::V8::SetFlagsFromString(flags, strlen(flags));

        s_platform = v8::platform::NewDefaultPlatform(
            1, v8::platform::IdleTaskSupport::kDisabled,
            v8::platform::InProcessStackDumping::kDisabled, nullptr);
        if (!s_platform) {
            s_failureReason = "NewDefaultPlatform returned null";
            s_state.store(V8InitState::FAILED);
            LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: ERROR | Extension: * | Generation: 0 | Error: %s", s_failureReason.c_str());
            LOGE("[OrionV8] V8_INIT_FAILED: %s", s_failureReason.c_str());
            return false;
        }

        v8::V8::InitializePlatform(s_platform.get());
        s_platformInitialized = true;
        v8::V8::Initialize();
        s_v8Initialized = true;

        s_state.store(V8InitState::READY);
        LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: SUCCESS | Extension: * | Generation: 0 | Version: %s", v8::V8::GetVersion());
        LOGI("[OrionV8] V8_INIT_SUCCESS: Native V8 platform and runtime ready (v%s)", v8::V8::GetVersion());
        return true;
    } catch (const std::exception& e) {
        s_failureReason = std::string("Exception: ") + e.what();
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: ERROR | Extension: * | Generation: 0 | Error: %s", s_failureReason.c_str());
        if (s_v8Initialized) {
            v8::V8::Dispose();
            s_v8Initialized = false;
        }
        if (s_platformInitialized) {
            v8::V8::DisposePlatform();
            s_platformInitialized = false;
        }
        s_platform.reset();
        s_state.store(V8InitState::FAILED);
        LOGE("[OrionV8] V8_INIT_FAILED: %s", s_failureReason.c_str());
        return false;
    } catch (...) {
        s_failureReason = "Unknown native exception during V8 initialization";
        if (s_v8Initialized) {
            v8::V8::Dispose();
            s_v8Initialized = false;
        }
        if (s_platformInitialized) {
            v8::V8::DisposePlatform();
            s_platformInitialized = false;
        }
        s_platform.reset();
        s_state.store(V8InitState::FAILED);
        LOGE("[OrionV8] V8_INIT_FAILED: %s", s_failureReason.c_str());
        return false;
    }
#else
    s_failureReason = "Compiled without native V8 support";
    s_state.store(V8InitState::FAILED);
    LOGW("[OrionV8] V8_INIT_FAILED: %s", s_failureReason.c_str());
    return false;
#endif
}

void V8EngineRuntime::ShutdownPlatform() {
    std::lock_guard<std::mutex> lock(s_lifecycleMutex);
#if ORION_HAS_V8
    if (!s_v8Initialized && !s_platformInitialized) {
        s_state.store(V8InitState::NOT_INITIALIZED);
        return;
    }
    if (s_v8Initialized) {
        v8::V8::Dispose();
        s_v8Initialized = false;
    }
    if (s_platformInitialized) {
        v8::V8::DisposePlatform();
        s_platformInitialized = false;
    }
    s_platform.reset();
#endif
    s_state.store(V8InitState::NOT_INITIALIZED);
    s_failureReason.clear();
    LOGI("[OrionV8] V8 platform and engine shut down.");
}

bool V8EngineRuntime::IsInitialized() {
    return s_state.load() == V8InitState::READY;
}

V8InitState V8EngineRuntime::GetState() {
    return s_state.load();
}

std::string V8EngineRuntime::GetFailureReason() {
    std::lock_guard<std::mutex> lock(s_lifecycleMutex);
    return s_failureReason;
}

#if ORION_HAS_V8
void V8EngineRuntime::PumpMessageLoop(v8::Isolate* isolate) {
    if (s_platform && isolate) {
        v8::platform::PumpMessageLoop(s_platform.get(), isolate);
    }
}
#endif

}
