#include "v8_isolate_manager.h"
#include "v8_runtime.h"
#include "v8_jni.h"
#include <algorithm>
#include <cstdint>

namespace orion::v8runtime {

V8IsolateManager& V8IsolateManager::GetInstance() {
    // Intentionally heap-allocate the process singleton. JNI_OnUnload performs the explicit
    // native shutdown before V8::Dispose/DisposePlatform; a C++ static destructor could otherwise
    // run after V8 has already been disposed and touch dead isolate state.
    static V8IsolateManager* instance = new V8IsolateManager();
    return *instance;
}

V8IsolateManager::~V8IsolateManager() = default;

bool V8IsolateManager::CreateWorker(const std::string& extensionId,
                                    int generationId,
                                    const std::string& scriptPath,
                                    const std::string& bootstrapScript,
                                    bool isModule,
                                    const std::string& moduleSourcesBlob,
                                    const std::string& extensionRootPath,
                                    int maxHeapMemoryMb) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: native worker creation | Status: START | Extension: %s | Generation: %d | ScriptPath: %s | IsModule: %d",
                         extensionId.c_str(), generationId, scriptPath.c_str(), isModule ? 1 : 0);

#if ORION_HAS_V8
    // Global V8 initialization belongs to V8Runtime/NativeV8RuntimeCoordinator.
    // This execution primitive must never implicitly initialize the global engine.
    if (!V8EngineRuntime::IsInitialized()) {
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: native worker creation | Status: ERROR | Extension: %s | Generation: %d | Reason: V8 runtime is not READY",
                             extensionId.c_str(), generationId);
        LOGE("Failed to create V8 worker for %s: V8 runtime is not READY (%s)",
             extensionId.c_str(), V8EngineRuntime::GetFailureReason().c_str());
        DispatchWorkerStarted(extensionId, generationId, false);
        return false;
    }

    auto existing = m_workers.find(extensionId);
    if (existing != m_workers.end()) {
        if (existing->second->generationId == generationId &&
            existing->second->context &&
            existing->second->isolate) {
            LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: native worker creation | Status: SUCCESS | Extension: %s | Generation: %d | ReusedExisting",
                                 extensionId.c_str(), generationId);
            LOGI("Reusing existing V8 isolate for extension %s (gen %d)", extensionId.c_str(), generationId);
            return true;
        }
        StopWorker(extensionId);
    }

    auto instance = std::make_unique<WorkerInstance>();
    instance->extensionId = extensionId;
    instance->generationId = generationId;

    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: V8 isolate creation | Status: START | Extension: %s | Generation: %d | HeapLimitMb: %d",
                         extensionId.c_str(), generationId, maxHeapMemoryMb);
    instance->allocator = v8::ArrayBuffer::Allocator::NewDefaultAllocator();
    v8::Isolate::CreateParams params;
    params.array_buffer_allocator = instance->allocator;
    const int boundedHeapMiB = std::clamp(maxHeapMemoryMb, 32, 1024);
    const std::uint64_t heapBytes = static_cast<std::uint64_t>(boundedHeapMiB) * 1024ULL * 1024ULL;
    // Keep the configured heap policy per isolate. The process-global V8 flag is intentionally
    // not used because multiple extension isolates may have different runtime budgets.
    params.constraints.ConfigureDefaultsFromHeapSize(
        16ULL * 1024ULL * 1024ULL,
        heapBytes);
    instance->isolate = v8::Isolate::New(params);

    if (!instance->isolate) {
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: V8 isolate creation | Status: ERROR | Extension: %s | Generation: %d | Reason: v8::Isolate::New returned null",
                             extensionId.c_str(), generationId);
        if (instance->allocator) delete instance->allocator;
        DispatchWorkerStarted(extensionId, generationId, false);
        return false;
    }
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: V8 isolate creation | Status: SUCCESS | Extension: %s | Generation: %d",
                         extensionId.c_str(), generationId);

    instance->context = std::make_unique<V8ExtensionContext>(extensionId, generationId, isModule);
    if (!instance->context->Initialize(instance->isolate, scriptPath, bootstrapScript, moduleSourcesBlob, extensionRootPath)) {
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: native worker creation | Status: ERROR | Extension: %s | Generation: %d | Reason: V8ExtensionContext::Initialize failed",
                             extensionId.c_str(), generationId);
        {
            v8::Locker locker(instance->isolate);
            v8::Isolate::Scope isolate_scope(instance->isolate);
            instance->context.reset();
        }
        instance->isolate->Dispose();
        instance->isolate = nullptr;
        if (instance->allocator) {
            delete instance->allocator;
            instance->allocator = nullptr;
        }
        DispatchWorkerStarted(extensionId, generationId, false);
        return false;
    }

    m_workers[extensionId] = std::move(instance);
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: native worker creation | Status: SUCCESS | Extension: %s | Generation: %d",
                         extensionId.c_str(), generationId);
    LOGI("Created real V8 isolate and worker instance for extension %s (gen %d)", extensionId.c_str(), generationId);
    return true;
#else
    LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: native worker creation | Status: ERROR | Extension: %s | Generation: %d | Reason: ORION_HAS_V8 is false",
                         extensionId.c_str(), generationId);
    LOGI("V8 Isolate creation requested for %s, pending external V8 NDK library build.", extensionId.c_str());
    return false;
#endif
}

bool V8IsolateManager::ExecuteScript(const std::string& extensionId, int generationId, const std::string& scriptContent) {
    return ExecuteScriptWithResult(extensionId, generationId, scriptContent, nullptr);
}

bool V8IsolateManager::ExecuteScriptWithResult(const std::string& extensionId, int generationId, const std::string& scriptContent, std::string* resultJson) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    auto it = m_workers.find(extensionId);
    if (it == m_workers.end() || it->second->generationId != generationId) return false;

    return it->second->context->ExecuteScriptWithResult(scriptContent, resultJson);
}

bool V8IsolateManager::DispatchEvent(const std::string& extensionId, int generationId, const std::string& eventName, const std::string& payloadJson) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    auto it = m_workers.find(extensionId);
    if (it == m_workers.end() || it->second->generationId != generationId) {
        DispatchEventHandled(extensionId, generationId, eventName, false);
        return false;
    }

    bool success = it->second->context->DispatchEvent(eventName, payloadJson);
    DispatchEventHandled(extensionId, generationId, eventName, success);
    return success;
}

bool V8IsolateManager::FireTimer(const std::string& extensionId, int generationId, int timerId) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    auto it = m_workers.find(extensionId);
    if (it == m_workers.end() || it->second->generationId != generationId) return false;
    return it->second->context->FireTimer(timerId);
}

void V8IsolateManager::ResolveHostCall(const std::string& extensionId, int generationId, const std::string& requestId, const std::string& resultJson, const std::string& errorMsg) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    auto it = m_workers.find(extensionId);
    if (it != m_workers.end() && it->second->generationId == generationId) {
        it->second->context->ResolveHostCall(requestId, resultJson, errorMsg);
    }
}

bool V8IsolateManager::StopWorker(const std::string& extensionId) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    auto it = m_workers.find(extensionId);
    if (it == m_workers.end()) return false;

    LOGI("Stopping V8 isolate for extension %s", extensionId.c_str());
#if ORION_HAS_V8
    if (it->second->isolate) {
        {
            v8::Locker locker(it->second->isolate);
            v8::Isolate::Scope isolate_scope(it->second->isolate);
            it->second->context.reset();
        }
        it->second->isolate->Dispose();
        it->second->isolate = nullptr;
    }
    if (it->second->allocator) {
        delete it->second->allocator;
        it->second->allocator = nullptr;
    }
#endif
    m_workers.erase(it);
    return true;
}

void V8IsolateManager::StopAllWorkers() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    LOGI("Stopping all V8 workers (%zu active)", m_workers.size());
#if ORION_HAS_V8
    for (auto& pair : m_workers) {
        if (pair.second->isolate) {
            {
                v8::Locker locker(pair.second->isolate);
                v8::Isolate::Scope isolate_scope(pair.second->isolate);
                pair.second->context.reset();
            }
            pair.second->isolate->Dispose();
            pair.second->isolate = nullptr;
        }
        if (pair.second->allocator) {
            delete pair.second->allocator;
            pair.second->allocator = nullptr;
        }
    }
#endif
    m_workers.clear();
}

size_t V8IsolateManager::GetHeapUsage(const std::string& extensionId) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    auto it = m_workers.find(extensionId);
    if (it == m_workers.end()) return 0;
    return it->second->context->GetHeapAllocated();
}

} // namespace orion::v8runtime
