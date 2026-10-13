#ifndef ORION_V8_CONTEXT_H
#define ORION_V8_CONTEXT_H

#if ORION_HAS_V8
#include <v8.h>
#endif
#include <string>
#include <map>
#include <mutex>
#include <functional>
#include <unordered_map>
#include <vector>

namespace orion::v8runtime {

struct PendingHostCall {
    std::string requestId;
    std::string apiName;
    std::function<void(const std::string& resultJson, const std::string& errorMsg)> callback;
};

class V8ExtensionContext {
public:
    V8ExtensionContext(const std::string& extensionId, int generationId, bool isModule);
    ~V8ExtensionContext();

#if ORION_HAS_V8
    bool Initialize(v8::Isolate* isolate, const std::string& scriptPath, const std::string& bootstrapScript,
                    const std::string& moduleSourcesBlob, const std::string& extensionRootPath);
#else
    bool Initialize(void* isolate, const std::string& scriptPath, const std::string& bootstrapScript, const std::string& moduleSourcesBlob, const std::string& extensionRootPath);
#endif
    bool ExecuteScript(const std::string& scriptContent);
    bool ImportScriptsSync(const std::vector<std::string>& scriptUrls);
    bool ExecuteScriptWithResult(const std::string& scriptContent, std::string* resultJson);
    bool DispatchEvent(const std::string& eventName, const std::string& payloadJson);
    void ResolveHostCall(const std::string& requestId, const std::string& resultJson, const std::string& errorMsg);
    bool FireTimer(int timerId);

    const std::string& GetExtensionId() const { return m_extensionId; }
    int GetGenerationId() const { return m_generationId; }
    bool IsModule() const { return m_isModule; }
    size_t GetHeapAllocated();

private:
    std::string m_extensionId;
    int m_generationId;
    bool m_isModule;
    std::string m_scriptPath;
    std::string m_bootstrapScript;
    std::string m_extensionRootPath;
    // Path of the script currently being evaluated. Used for nested importScripts() resolution.
    std::string m_currentScriptPath;
#if ORION_HAS_V8
    v8::Isolate* m_isolate = nullptr;
    v8::Global<v8::Context> m_context;
#else
    void* m_isolate = nullptr;
#endif
    std::mutex m_mutex;
    std::map<std::string, PendingHostCall> m_pendingCalls;
#if ORION_HAS_V8
    std::unordered_map<std::string, std::string> m_moduleSources;
    std::unordered_map<std::string, v8::Global<v8::Module>> m_modules;
    static v8::MaybeLocal<v8::Module> ResolveModuleCallback(
        v8::Local<v8::Context> context,
        v8::Local<v8::String> specifier,
        v8::Local<v8::FixedArray> import_attributes,
        v8::Local<v8::Module> referrer);
    static v8::MaybeLocal<v8::Promise> ImportModuleDynamicallyCallback(
        v8::Local<v8::Context> context,
        v8::Local<v8::Data> host_defined_options,
        v8::Local<v8::Value> resource_name,
        v8::Local<v8::String> specifier,
        v8::Local<v8::FixedArray> import_attributes);
    v8::MaybeLocal<v8::Module> CompileModule(
        v8::Local<v8::Context> context,
        const std::string& modulePath);
    bool InitializeModule(v8::Local<v8::Context> context, const std::string& modulePath);
    std::string ResolveModulePath(const std::string& referrer, const std::string& specifier) const;
    bool ParseModuleSourcesBlob(const std::string& blob);
    std::string CanonicalModulePath(const std::string& path) const;
    bool InstallHostBindings(v8::Local<v8::Context> context);
    static void HostApiCallback(const v8::FunctionCallbackInfo<v8::Value>& args);
    static void ConsoleCallback(const v8::FunctionCallbackInfo<v8::Value>& args);
    static void SetTimerCallback(const v8::FunctionCallbackInfo<v8::Value>& args);
    static void ClearTimerCallback(const v8::FunctionCallbackInfo<v8::Value>& args);
    static void ImportScriptsCallback(const v8::FunctionCallbackInfo<v8::Value>& args);
    static bool SetGlobalFunction(v8::Local<v8::Context> context, const char* name, v8::FunctionCallback callback);
#endif
};

} // namespace orion::v8runtime

#endif // ORION_V8_CONTEXT_H
