#ifndef ORION_V8_RUNTIME_H
#define ORION_V8_RUNTIME_H
#include "native_v8_capabilities.h"
#if ORION_HAS_V8
#include <v8.h>
#include <libplatform/libplatform.h>
#endif
#include <atomic>
#include <memory>
#include <mutex>
#include <string>

namespace orion::v8runtime {

enum class V8InitState {
    NOT_INITIALIZED = 0,
    INITIALIZING = 1,
    READY = 2,
    FAILED = 3
};

class V8EngineRuntime {
public:
    static bool InitializePlatform();
    static void ShutdownPlatform();
    static bool IsInitialized();
    static V8InitState GetState();
    static std::string GetFailureReason();
#if ORION_HAS_V8
    static void PumpMessageLoop(v8::Isolate* isolate);
#endif
private:
    static std::atomic<V8InitState> s_state;
    static std::mutex s_lifecycleMutex;
    static std::string s_failureReason;
#if ORION_HAS_V8
    static std::unique_ptr<v8::Platform> s_platform;
    static bool s_platformInitialized;
    static bool s_v8Initialized;
#endif
};
}
#endif
