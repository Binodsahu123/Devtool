#ifndef ORION_V8_ISOLATE_MANAGER_H
#define ORION_V8_ISOLATE_MANAGER_H

#if ORION_HAS_V8
#include <v8.h>
#endif
#include "v8_context.h"
#include <map>
#include <memory>
#include <mutex>
#include <string>

namespace orion::v8runtime {

struct WorkerInstance {
    std::string extensionId;
    int generationId = 0;
#if ORION_HAS_V8
    v8::Isolate* isolate = nullptr;
    v8::ArrayBuffer::Allocator* allocator = nullptr;
#else
    void* isolate = nullptr;
#endif
    std::unique_ptr<V8ExtensionContext> context;
};

class V8IsolateManager {
public:
    static V8IsolateManager& GetInstance();

    bool CreateWorker(const std::string& extensionId,
                      int generationId,
                      const std::string& scriptPath,
                      const std::string& bootstrapScript,
                      bool isModule,
                      const std::string& moduleSourcesBlob,
                      const std::string& extensionRootPath,
                      int maxHeapMemoryMb);

    bool ExecuteScript(const std::string& extensionId, int generationId, const std::string& scriptContent);
    bool ExecuteScriptWithResult(const std::string& extensionId, int generationId, const std::string& scriptContent, std::string* resultJson);
    bool DispatchEvent(const std::string& extensionId, int generationId, const std::string& eventName, const std::string& payloadJson);
    bool FireTimer(const std::string& extensionId, int generationId, int timerId);
    void ResolveHostCall(const std::string& extensionId, int generationId, const std::string& requestId, const std::string& resultJson, const std::string& errorMsg);

    bool StopWorker(const std::string& extensionId);
    void StopAllWorkers();
    size_t GetHeapUsage(const std::string& extensionId);

private:
    V8IsolateManager() = default;
    ~V8IsolateManager();

    std::recursive_mutex m_mutex;
    std::map<std::string, std::unique_ptr<WorkerInstance>> m_workers;
};

} // namespace orion::v8runtime

#endif // ORION_V8_ISOLATE_MANAGER_H
