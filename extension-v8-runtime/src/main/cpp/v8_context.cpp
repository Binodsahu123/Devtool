#include "v8_context.h"
#include "v8_runtime.h"
#include "v8_jni.h"
#include "v8_value_converter.h"
#include <sstream>
#include <algorithm>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <cstdint>


namespace orion::v8runtime {

V8ExtensionContext::V8ExtensionContext(const std::string& extensionId, int generationId, bool isModule)
    : m_extensionId(extensionId), m_generationId(generationId), m_isModule(isModule) {}

V8ExtensionContext::~V8ExtensionContext() {
    std::lock_guard<std::mutex> lock(m_mutex);
#if ORION_HAS_V8
    if (m_isolate) {
        v8::Locker locker(m_isolate);
        v8::Isolate::Scope isolate_scope(m_isolate);
        v8::HandleScope handle_scope(m_isolate);
        m_modules.clear();
        if (!m_context.IsEmpty()) {
            m_context.Reset();
        }
    } else {
        m_modules.clear();
    }
#endif
    m_pendingCalls.clear();
}

#if ORION_HAS_V8
bool V8ExtensionContext::Initialize(v8::Isolate* isolate, const std::string& scriptPath, const std::string& bootstrapScript,
                                     const std::string& moduleSourcesBlob, const std::string& extensionRootPath) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_isolate = isolate;
    m_scriptPath = CanonicalModulePath(scriptPath);
    m_bootstrapScript = bootstrapScript;
    m_extensionRootPath = extensionRootPath;
    m_currentScriptPath = m_scriptPath;

    if (!m_isolate) return false;

    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: V8 context initialization | Status: START | Extension: %s | Generation: %d",
                         m_extensionId.c_str(), m_generationId);

    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HandleScope handle_scope(m_isolate);
    m_isolate->SetMicrotasksPolicy(v8::MicrotasksPolicy::kAuto);

    v8::Local<v8::ObjectTemplate> global = v8::ObjectTemplate::New(m_isolate);
    v8::Local<v8::Context> context = v8::Context::New(m_isolate, nullptr, global);
    m_context.Reset(m_isolate, context);

    v8::Context::Scope context_scope(context);
    context->SetAlignedPointerInEmbedderData(0, this);
    m_isolate->SetHostImportModuleDynamicallyCallback(ImportModuleDynamicallyCallback);

    if (m_extensionRootPath.empty()) {
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: V8 context initialization | Status: ERROR | Extension: %s | Generation: %d | Reason: Missing extension root path",
                             m_extensionId.c_str(), m_generationId);
        LOGE("Missing extension root path for %s", m_extensionId.c_str());
        DispatchWorkerError(m_extensionId, m_generationId, "Missing extension root path");
        return false;
    }

    if (!InstallHostBindings(context)) {
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: V8 context initialization | Status: ERROR | Extension: %s | Generation: %d | Reason: Failed installing native host bindings",
                             m_extensionId.c_str(), m_generationId);
        LOGE("Failed installing native host bindings for %s", m_extensionId.c_str());
        DispatchWorkerError(m_extensionId, m_generationId, "Failed installing native host bindings");
        return false;
    }

    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: V8 context initialization | Status: SUCCESS | Extension: %s | Generation: %d",
                         m_extensionId.c_str(), m_generationId);

    if (!m_moduleSources.empty()) {
        m_moduleSources.clear();
    }
    if (!moduleSourcesBlob.empty() && !ParseModuleSourcesBlob(moduleSourcesBlob)) {
        LOGE("Invalid module source blob for %s", m_extensionId.c_str());
        DispatchWorkerError(m_extensionId, m_generationId, "Invalid module source blob");
        return false;
    }

    if (!m_bootstrapScript.empty()) {
        LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: bootstrap compilation | Status: START | Extension: %s | Generation: %d | Bytes: %zu",
                             m_extensionId.c_str(), m_generationId, m_bootstrapScript.size());
        v8::TryCatch try_catch(m_isolate);
        v8::Local<v8::String> source = v8::String::NewFromUtf8(m_isolate, m_bootstrapScript.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
        v8::Local<v8::Script> script;
        if (!v8::Script::Compile(context, source).ToLocal(&script)) {
            v8::String::Utf8Value err(m_isolate, try_catch.Exception());
            LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: bootstrap compilation | Status: ERROR | Extension: %s | Generation: %d | Error: %s",
                                 m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
            LOGE("Failed compiling bootstrap for %s: %s", m_extensionId.c_str(), *err ? *err : "unknown");
            DispatchWorkerError(m_extensionId, m_generationId, std::string("Bootstrap compile error: ") + (*err ? *err : "unknown"));
            return false;
        }
        v8::Local<v8::Value> result;
        if (!script->Run(context).ToLocal(&result)) {
            v8::String::Utf8Value err(m_isolate, try_catch.Exception());
            LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: bootstrap compilation | Status: ERROR | Extension: %s | Generation: %d | RunError: %s",
                                 m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
            LOGE("Failed running bootstrap for %s: %s", m_extensionId.c_str(), *err ? *err : "unknown");
            DispatchWorkerError(m_extensionId, m_generationId, std::string("Bootstrap execution error: ") + (*err ? *err : "unknown"));
            return false;
        }
        m_isolate->PerformMicrotaskCheckpoint();
        V8EngineRuntime::PumpMessageLoop(m_isolate);
        LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: bootstrap compilation | Status: SUCCESS | Extension: %s | Generation: %d",
                             m_extensionId.c_str(), m_generationId);
    }

    if (m_isModule) {
        const std::string entryPath = CanonicalModulePath(m_scriptPath);
        if (!InitializeModule(context, entryPath)) {
            LOGE("Failed initializing module worker %s entry=%s", m_extensionId.c_str(), entryPath.c_str());
            DispatchWorkerError(m_extensionId, m_generationId, std::string("Module initialization failed: ") + entryPath);
            return false;
        }
    }

    LOGI("Initialized real V8 Context for extension %s (gen %d, module=%d)",
         m_extensionId.c_str(), m_generationId, m_isModule ? 1 : 0);
    return true;
}

bool V8ExtensionContext::ExecuteScript(const std::string& scriptContent) {
    return ExecuteScriptWithResult(scriptContent, nullptr);
}

bool V8ExtensionContext::ExecuteScriptWithResult(const std::string& scriptContent, std::string* resultJson) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate || m_context.IsEmpty()) return false;
    if (resultJson) resultJson->clear();

    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: START | Extension: %s | Generation: %d | Bytes: %zu",
                         m_extensionId.c_str(), m_generationId, scriptContent.size());

    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HandleScope handle_scope(m_isolate);
    v8::Local<v8::Context> context = v8::Local<v8::Context>::New(m_isolate, m_context);
    v8::Context::Scope context_scope(context);

    v8::TryCatch try_catch(m_isolate);
    v8::Local<v8::String> source = v8::String::NewFromUtf8(m_isolate, scriptContent.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    v8::Local<v8::Script> script;
    if (!v8::Script::Compile(context, source).ToLocal(&script)) {
        v8::String::Utf8Value err(m_isolate, try_catch.Exception());
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: ERROR | Extension: %s | Generation: %d | Error: %s",
                             m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
        LOGE("Compilation error in %s: %s", m_extensionId.c_str(), *err ? *err : "unknown");
        DispatchWorkerError(m_extensionId, m_generationId, std::string("Script compile error: ") + (*err ? *err : "unknown"));
        return false;
    }

    v8::Local<v8::Value> result;
    if (!script->Run(context).ToLocal(&result)) {
        v8::String::Utf8Value err(m_isolate, try_catch.Exception());
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: ERROR | Extension: %s | Generation: %d | RunError: %s",
                             m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
        LOGE("Execution error in %s: %s", m_extensionId.c_str(), *err ? *err : "unknown");
        DispatchWorkerError(m_extensionId, m_generationId, std::string("Script execution error: ") + (*err ? *err : "unknown"));
        return false;
    }

    if (resultJson) {
        *resultJson = orion::v8bridge::ValueToJson(m_isolate, result);
    }

    m_isolate->PerformMicrotaskCheckpoint();
    V8EngineRuntime::PumpMessageLoop(m_isolate);

    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: SUCCESS | Extension: %s | Generation: %d",
                         m_extensionId.c_str(), m_generationId);

    LOGI("Executed script in V8 context for extension %s (length %zu bytes)",
         m_extensionId.c_str(), scriptContent.size());
    return true;
}

bool V8ExtensionContext::ImportScriptsSync(const std::vector<std::string>& scriptUrls) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate || m_context.IsEmpty()) return false;
    if (m_isModule) {
        m_isolate->ThrowException(v8::Exception::TypeError(
            v8::String::NewFromUtf8Literal(m_isolate, "importScripts() is not available in module workers")));
        return false;
    }
    if (scriptUrls.empty()) return true;

    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HandleScope handle_scope(m_isolate);
    v8::Local<v8::Context> context = v8::Local<v8::Context>::New(m_isolate, m_context);
    v8::Context::Scope context_scope(context);

    namespace fs = std::filesystem;
    std::error_code ec;
    const fs::path root = fs::weakly_canonical(fs::path(m_extensionRootPath), ec);
    if (ec || root.empty()) {
        m_isolate->ThrowException(v8::Exception::Error(
            v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): extension root is unavailable")));
        return false;
    }

    const std::string rootText = root.generic_string();
    const std::string originalScript = m_currentScriptPath.empty() ? m_scriptPath : m_currentScriptPath;
    std::string baseScript = originalScript.empty() ? m_scriptPath : originalScript;

    for (const auto& rawUrl : scriptUrls) {
        if (rawUrl.empty()) {
            m_isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): empty URL")));
            return false;
        }

        std::string url = rawUrl;
        if (url.rfind("chrome-extension://", 0) == 0 || url.rfind("swift-extension://", 0) == 0) {
            const auto scheme = url.find("://");
            const auto slash = scheme == std::string::npos ? std::string::npos : url.find('/', scheme + 3);
            const std::string host = slash == std::string::npos ? url.substr(scheme + 3) : url.substr(scheme + 3, slash - (scheme + 3));
            if (!host.empty() && host != m_extensionId) {
                m_isolate->ThrowException(v8::Exception::Error(
                    v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): cross-extension URL is not allowed")));
                return false;
            }
            url = slash == std::string::npos ? "" : url.substr(slash + 1);
        } else if (url.find("://") != std::string::npos) {
            // Remote/unknown schemes are not valid packaged-extension worker imports.
            m_isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): remote URLs are not allowed")));
            return false;
        }

        std::string relative;
        if (!url.empty() && url.front() == '/') {
            relative = CanonicalModulePath(url);
        } else {
            const auto slash = baseScript.find_last_of('/');
            const std::string base = slash == std::string::npos ? "" : baseScript.substr(0, slash + 1);
            relative = CanonicalModulePath(base + url);
        }
        if (relative.empty()) {
            m_isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): invalid script URL")));
            return false;
        }

        const fs::path requested = root / fs::path(relative);
        const fs::path canonicalFile = fs::weakly_canonical(requested, ec);
        if (ec || canonicalFile.empty()) {
            m_isolate->ThrowException(v8::Exception::Error(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): script not found")));
            return false;
        }

        const std::string fileText = canonicalFile.generic_string();
        if (fileText != rootText && fileText.rfind(rootText + "/", 0) != 0) {
            m_isolate->ThrowException(v8::Exception::Error(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): script escapes extension root")));
            return false;
        }

        ec.clear();
        if (!fs::is_regular_file(canonicalFile, ec) || ec) {
            m_isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): target is not a file")));
            return false;
        }

        std::string ext = canonicalFile.extension().string();
        for (char& ch : ext) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
        if (ext != ".js" && ext != ".mjs") {
            m_isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): only JavaScript files are allowed")));
            return false;
        }

        constexpr std::uintmax_t kMaxImportedScriptBytes = 8ULL * 1024ULL * 1024ULL;
        ec.clear();
        const auto size = fs::file_size(canonicalFile, ec);
        if (ec || size > kMaxImportedScriptBytes) {
            m_isolate->ThrowException(v8::Exception::RangeError(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): script exceeds size limit")));
            return false;
        }

        std::ifstream input(canonicalFile, std::ios::binary);
        if (!input) {
            m_isolate->ThrowException(v8::Exception::Error(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): failed to open script")));
            return false;
        }
        std::string source;
        source.reserve(static_cast<size_t>(size));
        source.assign(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());

        auto sourceName = v8::String::NewFromUtf8(
            m_isolate, relative.c_str(), v8::NewStringType::kNormal);
        auto sourceText = v8::String::NewFromUtf8(
            m_isolate, source.c_str(), v8::NewStringType::kNormal);
        if (sourceName.IsEmpty() || sourceText.IsEmpty()) {
            m_isolate->ThrowException(v8::Exception::Error(
                v8::String::NewFromUtf8Literal(m_isolate, "importScripts(): invalid UTF-8 source")));
            return false;
        }

        v8::ScriptOrigin origin(sourceName.ToLocalChecked());
        v8::ScriptCompiler::Source compilerSource(sourceText.ToLocalChecked(), origin);
        v8::TryCatch tryCatch(m_isolate);
        v8::Local<v8::Script> script;
        if (!v8::ScriptCompiler::Compile(context, &compilerSource).ToLocal(&script)) {
            v8::String::Utf8Value error(m_isolate, tryCatch.Exception());
            const std::string message = std::string("importScripts compile error in ") + relative + ": " +
                (*error ? *error : "unknown");
            LOGE("%s", message.c_str());
            DispatchWorkerError(m_extensionId, m_generationId, message);
            // Preserve Web Worker semantics: importScripts() must throw the script exception
            // back into the calling JavaScript turn instead of silently returning undefined.
            tryCatch.ReThrow();
            return false;
        }

        // importScripts() is synchronous: evaluate completely before moving to the next URL.
        const std::string previousScript = m_currentScriptPath;
        m_currentScriptPath = relative;
        v8::Local<v8::Value> result;
        const bool ran = script->Run(context).ToLocal(&result);
        m_currentScriptPath = previousScript.empty() ? baseScript : previousScript;
        if (!ran) {
            v8::String::Utf8Value error(m_isolate, tryCatch.Exception());
            const std::string message = std::string("importScripts runtime error in ") + relative + ": " +
                (*error ? *error : "unknown");
            DispatchWorkerError(m_extensionId, m_generationId, message);
            tryCatch.ReThrow();
            return false;
        }

        // For the next top-level argument the base URL remains the worker's current script URL,
        // matching WorkerGlobalScope.importScripts() URL resolution rather than chaining siblings.
        baseScript = originalScript.empty() ? m_scriptPath : originalScript;
    }

    m_isolate->PerformMicrotaskCheckpoint();
    V8EngineRuntime::PumpMessageLoop(m_isolate);
    return true;
}

size_t V8ExtensionContext::GetHeapAllocated() {
    std::lock_guard<std::mutex> lock(m_mutex);
#if ORION_HAS_V8
    if (!m_isolate || m_context.IsEmpty()) return 0;
    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HeapStatistics statistics;
    m_isolate->GetHeapStatistics(&statistics);
    return statistics.used_heap_size();
#else
    return 0;
#endif
}

bool V8ExtensionContext::DispatchEvent(const std::string& eventName, const std::string& payloadJson) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate || m_context.IsEmpty()) return false;

    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: START | Extension: %s | Generation: %d | Event: %s",
                         m_extensionId.c_str(), m_generationId, eventName.c_str());

    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HandleScope handle_scope(m_isolate);
    v8::Local<v8::Context> context = v8::Local<v8::Context>::New(m_isolate, m_context);
    v8::Context::Scope context_scope(context);

    v8::TryCatch try_catch(m_isolate);
    v8::Local<v8::Value> dispatchValue;
    v8::Local<v8::String> dispatchName = v8::String::NewFromUtf8(m_isolate, "__orionDispatchEvent", v8::NewStringType::kNormal).ToLocalChecked();
    if (!context->Global()->Get(context, dispatchName).ToLocal(&dispatchValue) || !dispatchValue->IsFunction()) {
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: ERROR | Extension: %s | Generation: %d | Reason: Missing __orionDispatchEvent",
                             m_extensionId.c_str(), m_generationId);
        LOGE("Missing __orionDispatchEvent for extension %s", m_extensionId.c_str());
        DispatchWorkerError(m_extensionId, m_generationId, "Missing __orionDispatchEvent in worker context");
        return false;
    }
    v8::Local<v8::Value> eventValue = v8::String::NewFromUtf8(m_isolate, eventName.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    v8::Local<v8::Value> payloadValue;
    if (payloadJson.empty()) {
        payloadValue = v8::Null(m_isolate);
    } else {
        payloadValue = orion::v8bridge::JsonToValue(m_isolate, context, payloadJson);
        if (payloadValue.IsEmpty() || payloadValue->IsNull()) {
            LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: ERROR | Extension: %s | Generation: %d | Reason: Invalid payload JSON",
                                 m_extensionId.c_str(), m_generationId);
            LOGE("Invalid event payload JSON for %s/%s", m_extensionId.c_str(), eventName.c_str());
            DispatchWorkerError(m_extensionId, m_generationId, std::string("Invalid event payload JSON for ") + eventName);
            return false;
        }
    }
    v8::Local<v8::Value> argv[2] = { eventValue, payloadValue };
    if (v8::Local<v8::Function>::Cast(dispatchValue)->Call(context, context->Global(), 2, argv).IsEmpty()) {
        v8::String::Utf8Value err(m_isolate, try_catch.Exception());
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: ERROR | Extension: %s | Generation: %d | Error: %s",
                             m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
        LOGE("Event dispatch exception '%s' to %s: %s", eventName.c_str(), m_extensionId.c_str(), *err ? *err : "unknown");
        DispatchWorkerError(m_extensionId, m_generationId, std::string("Event dispatch exception in ") + eventName + ": " + (*err ? *err : "unknown"));
        return false;
    }
    m_isolate->PerformMicrotaskCheckpoint();
    V8EngineRuntime::PumpMessageLoop(m_isolate);
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: SUCCESS | Extension: %s | Generation: %d | Event: %s",
                         m_extensionId.c_str(), m_generationId, eventName.c_str());
    LOGI("Dispatched event '%s' to extension %s", eventName.c_str(), m_extensionId.c_str());
    return true;
}

void V8ExtensionContext::ResolveHostCall(const std::string& requestId, const std::string& resultJson, const std::string& errorMsg) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate || m_context.IsEmpty()) return;

    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HandleScope handle_scope(m_isolate);
    v8::Local<v8::Context> context = v8::Local<v8::Context>::New(m_isolate, m_context);
    v8::Context::Scope context_scope(context);

    v8::TryCatch try_catch(m_isolate);
    v8::Local<v8::Value> resolveValue;
    v8::Local<v8::String> resolveName = v8::String::NewFromUtf8(m_isolate, "__orionResolveHostCall", v8::NewStringType::kNormal).ToLocalChecked();
    if (!context->Global()->Get(context, resolveName).ToLocal(&resolveValue) || !resolveValue->IsFunction()) return;
    auto requestValue = v8::String::NewFromUtf8(m_isolate, requestId.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    v8::Local<v8::Value> resultValue = v8::Null(m_isolate);
    if (!resultJson.empty()) {
        auto resultSource = v8::String::NewFromUtf8(m_isolate, resultJson.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
        if (!v8::JSON::Parse(context, resultSource).ToLocal(&resultValue)) resultValue = v8::String::NewFromUtf8(m_isolate, resultJson.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    }
    auto errorValue = v8::String::NewFromUtf8(m_isolate, errorMsg.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    v8::Local<v8::Value> argv[3] = { requestValue, resultValue, errorValue };
    if (v8::Local<v8::Function>::Cast(resolveValue)->Call(context, context->Global(), 3, argv).IsEmpty()) {
        v8::String::Utf8Value err(m_isolate, try_catch.Exception());
        LOGE("Host-call response callback failed for %s: %s", m_extensionId.c_str(), *err ? *err : "unknown");
        return;
    }
    m_isolate->PerformMicrotaskCheckpoint();
    V8EngineRuntime::PumpMessageLoop(m_isolate);
}

std::string V8ExtensionContext::CanonicalModulePath(const std::string& path) const {
    std::string p = path;
    std::replace(p.begin(), p.end(), '\\', '/');
    if (p.rfind("chrome-extension://", 0) == 0 || p.rfind("swift-extension://", 0) == 0) {
        const auto slash = p.find('/', p.find("://") + 3);
        p = slash == std::string::npos ? "" : p.substr(slash + 1);
    }
    while (!p.empty() && p.front() == '/') p.erase(p.begin());
    std::vector<std::string> parts;
    std::stringstream ss(p);
    std::string part;
    while (std::getline(ss, part, '/')) {
        if (part.empty() || part == ".") continue;
        if (part == "..") {
            if (!parts.empty()) parts.pop_back();
            else return "";
        } else {
            parts.push_back(part);
        }
    }
    std::ostringstream out;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i) out << '/';
        out << parts[i];
    }
    return out.str();
}

bool V8ExtensionContext::ParseModuleSourcesBlob(const std::string& blob) {
    size_t pos = 0;
    while (pos < blob.size()) {
        const size_t sep1 = blob.find(':', pos);
        if (sep1 == std::string::npos) return false;
        size_t keyLen = 0;
        try { keyLen = static_cast<size_t>(std::stoul(blob.substr(pos, sep1 - pos))); }
        catch (...) { return false; }
        pos = sep1 + 1;
        if (keyLen > blob.size() - pos) return false;
        const std::string key = CanonicalModulePath(blob.substr(pos, keyLen));
        pos += keyLen;
        const size_t sep2 = blob.find(':', pos);
        if (sep2 == std::string::npos) return false;
        size_t srcLen = 0;
        try { srcLen = static_cast<size_t>(std::stoul(blob.substr(pos, sep2 - pos))); }
        catch (...) { return false; }
        pos = sep2 + 1;
        if (srcLen > blob.size() - pos) return false;
        const std::string source = blob.substr(pos, srcLen);
        pos += srcLen;
        if (key.empty()) return false;
        m_moduleSources[key] = source;
    }
    return true;
}

std::string V8ExtensionContext::ResolveModulePath(const std::string& referrer, const std::string& specifier) const {
    if (specifier.empty()) return "";
    if (specifier.rfind("chrome-extension://", 0) == 0 || specifier.rfind("swift-extension://", 0) == 0) {
        const auto slash = specifier.find('/', specifier.find("://") + 3);
        return slash == std::string::npos ? "" : CanonicalModulePath(specifier.substr(slash + 1));
    }
    if (specifier.front() == '/') return CanonicalModulePath(specifier);
    if (specifier.rfind(".", 0) == 0) {
        const auto slash = referrer.find_last_of('/');
        const std::string base = slash == std::string::npos ? "" : referrer.substr(0, slash + 1);
        return CanonicalModulePath(base + specifier);
    }
    return "";
}

v8::MaybeLocal<v8::Module> V8ExtensionContext::ResolveModuleCallback(
    v8::Local<v8::Context> context,
    v8::Local<v8::String> specifier,
    v8::Local<v8::FixedArray> import_attributes,
    v8::Local<v8::Module> referrer) {
    auto* self = static_cast<V8ExtensionContext*>(context->GetAlignedPointerFromEmbedderData(0));
    if (!self) return v8::MaybeLocal<v8::Module>();
    v8::String::Utf8Value spec(v8::Isolate::GetCurrent(), specifier);
    std::string referrerPath;
    const int refHash = referrer->GetIdentityHash();
    auto refIt = self->m_modules.find("#" + std::to_string(refHash));
    if (refIt != self->m_modules.end()) {
        // Path-to-module mapping is stored under #<identity>; actual module globals share the same map.
        // The source-path lookup below searches the known modules for this identity.
        for (const auto& pair : self->m_modules) {
            if (pair.second.IsEmpty()) continue;
            if (pair.second.Get(v8::Isolate::GetCurrent())->GetIdentityHash() == refHash && pair.first.rfind("#",0) != 0) {
                referrerPath = pair.first;
                break;
            }
        }
    }
    if (referrerPath.empty()) {
        // Fallback for an entry/referrer that hasn't been indexed separately.
        for (const auto& pair : self->m_modules) {
            if (pair.first.rfind("#", 0) == 0) continue;
            if (!pair.second.IsEmpty() && pair.second.Get(v8::Isolate::GetCurrent())->GetIdentityHash() == refHash) {
                referrerPath = pair.first;
                break;
            }
        }
    }
    const std::string resolved = self->ResolveModulePath(referrerPath, *spec ? *spec : "");
    if (resolved.empty()) return v8::MaybeLocal<v8::Module>();
    auto compiled = self->CompileModule(context, resolved);
    if (compiled.IsEmpty()) return v8::MaybeLocal<v8::Module>();
    return compiled;
}

v8::MaybeLocal<v8::Module> V8ExtensionContext::CompileModule(
    v8::Local<v8::Context> context, const std::string& modulePath) {
    auto sourceIt = m_moduleSources.find(modulePath);
    if (sourceIt == m_moduleSources.end()) return v8::MaybeLocal<v8::Module>();

    v8::Isolate* isolate = v8::Isolate::GetCurrent();
    if (!isolate) return v8::MaybeLocal<v8::Module>();
    auto existing = m_modules.find(modulePath);
    if (existing != m_modules.end() && !existing->second.IsEmpty()) {
        return existing->second.Get(isolate);
    }

    v8::EscapableHandleScope scope(isolate);
    v8::TryCatch tc(isolate);
    auto src = v8::String::NewFromUtf8(isolate, sourceIt->second.c_str(), v8::NewStringType::kNormal);
    if (src.IsEmpty()) return v8::MaybeLocal<v8::Module>();
    auto name = v8::String::NewFromUtf8(isolate, modulePath.c_str(), v8::NewStringType::kNormal);
    if (name.IsEmpty()) return v8::MaybeLocal<v8::Module>();

    v8::ScriptOrigin origin(name.ToLocalChecked(), 0, 0, false, -1, v8::Local<v8::Value>(), false, false, true);
    v8::ScriptCompiler::Source source(src.ToLocalChecked(), origin);
    v8::Local<v8::Module> module;
    if (!v8::ScriptCompiler::CompileModule(isolate, &source).ToLocal(&module)) {
        return v8::MaybeLocal<v8::Module>();
    }

    m_modules[modulePath].Reset(isolate, module);
    const std::string idKey = "#" + std::to_string(module->GetIdentityHash());
    m_modules[idKey].Reset(isolate, module);
    return scope.Escape(module);
}

v8::MaybeLocal<v8::Promise> V8ExtensionContext::ImportModuleDynamicallyCallback(
    v8::Local<v8::Context> context,
    v8::Local<v8::Data> host_defined_options,
    v8::Local<v8::Value> resource_name,
    v8::Local<v8::String> specifier,
    v8::Local<v8::FixedArray> import_attributes) {
    (void)host_defined_options;
    (void)import_attributes;

    v8::Isolate* isolate = v8::Isolate::GetCurrent();
    if (!isolate) return v8::MaybeLocal<v8::Promise>();
    auto* self = static_cast<V8ExtensionContext*>(
        context->GetAlignedPointerFromEmbedderData(0));
    if (!self) return v8::MaybeLocal<v8::Promise>();

    v8::EscapableHandleScope scope(isolate);
    v8::Local<v8::Promise::Resolver> resolver;
    if (!v8::Promise::Resolver::New(context).ToLocal(&resolver)) {
        return v8::MaybeLocal<v8::Promise>();
    }

    v8::String::Utf8Value specUtf8(isolate, specifier);
    if (!*specUtf8) {
        resolver->Reject(
            context,
            v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(isolate, "Invalid dynamic import specifier")))
            .FromMaybe(false);
        return scope.Escape(resolver->GetPromise());
    }

    std::string referrerPath;
    if (!resource_name.IsEmpty() && resource_name->IsString()) {
        v8::String::Utf8Value resourceUtf8(isolate, resource_name);
        referrerPath = self->CanonicalModulePath(*resourceUtf8 ? *resourceUtf8 : "");
    }
    const std::string resolved = self->ResolveModulePath(referrerPath, *specUtf8);
    auto module = self->CompileModule(context, resolved);
    v8::Local<v8::Module> localModule;
    if (!module.ToLocal(&localModule)) {
        resolver->Reject(
            context,
            v8::Exception::TypeError(
                v8::String::NewFromUtf8(isolate, "Dynamic import target was not found").ToLocalChecked()))
            .FromMaybe(false);
        return scope.Escape(resolver->GetPromise());
    }

    v8::TryCatch tc(isolate);
    if (localModule->GetStatus() == v8::Module::kUninstantiated) {
        if (localModule->InstantiateModule(context, ResolveModuleCallback).IsNothing()) {
            v8::Local<v8::Value> exception = tc.Exception();
            if (exception.IsEmpty()) {
                exception = v8::Exception::Error(
                    v8::String::NewFromUtf8Literal(isolate, "Dynamic module instantiation failed"));
            }
            resolver->Reject(context, exception).FromMaybe(false);
            return scope.Escape(resolver->GetPromise());
        }
    }

    if (localModule->GetStatus() == v8::Module::kInstantiated) {
        v8::Local<v8::Value> evaluation;
        if (!localModule->Evaluate(context).ToLocal(&evaluation)) {
            v8::Local<v8::Value> exception = tc.Exception();
            if (exception.IsEmpty()) {
                exception = v8::Exception::Error(
                    v8::String::NewFromUtf8Literal(isolate, "Dynamic module evaluation failed"));
            }
            resolver->Reject(context, exception).FromMaybe(false);
            return scope.Escape(resolver->GetPromise());
        }
        isolate->PerformMicrotaskCheckpoint();
        V8EngineRuntime::PumpMessageLoop(isolate);
    }

    if (localModule->GetStatus() == v8::Module::kErrored) {
        resolver->Reject(context, localModule->GetException()).FromMaybe(false);
    } else {
        resolver->Resolve(context, localModule->GetModuleNamespace(v8::ModuleImportPhase::kEvaluation)).FromMaybe(false);
    }
    return scope.Escape(resolver->GetPromise());
}

bool V8ExtensionContext::InitializeModule(v8::Local<v8::Context> context, const std::string& modulePath) {
    auto it = m_moduleSources.find(modulePath);
    if (it == m_moduleSources.end()) return false;
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: START | Extension: %s | Generation: %d | ModulePath: %s",
                         m_extensionId.c_str(), m_generationId, modulePath.c_str());
    v8::Isolate* isolate = v8::Isolate::GetCurrent();
    v8::TryCatch tc(isolate);
    auto src = v8::String::NewFromUtf8(isolate, it->second.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    auto name = v8::String::NewFromUtf8(isolate, modulePath.c_str(), v8::NewStringType::kNormal).ToLocalChecked();
    v8::ScriptOrigin origin(name, 0, 0, false, -1, v8::Local<v8::Value>(), false, false, true);
    v8::ScriptCompiler::Source source(src, origin);
    v8::Local<v8::Module> module;
    if (!v8::ScriptCompiler::CompileModule(isolate, &source).ToLocal(&module)) {
        v8::String::Utf8Value err(isolate, tc.Exception());
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: ERROR | Extension: %s | Generation: %d | ModuleCompileError: %s",
                             m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
        LOGE("Module compile failed %s: %s", modulePath.c_str(), *err ? *err : "unknown");
        DispatchWorkerError(m_extensionId, m_generationId, std::string("Module compile error in ") + modulePath + ": " + (*err ? *err : "unknown"));
        return false;
    }
    m_modules[modulePath].Reset(isolate, module);
    m_modules["#" + std::to_string(module->GetIdentityHash())].Reset(isolate, module);
    if (module->InstantiateModule(context, ResolveModuleCallback).IsNothing()) {
        v8::String::Utf8Value err(isolate, tc.Exception());
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: ERROR | Extension: %s | Generation: %d | ModuleInstantiateError: %s",
                             m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
        LOGE("Module instantiate failed %s: %s", modulePath.c_str(), *err ? *err : "unknown");
        DispatchWorkerError(m_extensionId, m_generationId, std::string("Module instantiate error in ") + modulePath + ": " + (*err ? *err : "unknown"));
        return false;
    }
    v8::Local<v8::Value> value;
    if (!module->Evaluate(context).ToLocal(&value)) {
        v8::String::Utf8Value err(isolate, tc.Exception());
        LOG_CRASH_BOUNDARY_E("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: ERROR | Extension: %s | Generation: %d | ModuleEvaluateError: %s",
                             m_extensionId.c_str(), m_generationId, *err ? *err : "unknown");
        LOGE("Module evaluate failed %s: %s", modulePath.c_str(), *err ? *err : "unknown");
        DispatchWorkerError(m_extensionId, m_generationId, std::string("Module evaluate error in ") + modulePath + ": " + (*err ? *err : "unknown"));
        return false;
    }
    isolate->PerformMicrotaskCheckpoint();
    V8EngineRuntime::PumpMessageLoop(isolate);
    LOG_CRASH_BOUNDARY_I("[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: SUCCESS | Extension: %s | Generation: %d | ModulePath: %s",
                         m_extensionId.c_str(), m_generationId, modulePath.c_str());
    return true;
}


bool V8ExtensionContext::SetGlobalFunction(v8::Local<v8::Context> context, const char* name, v8::FunctionCallback callback) {
    v8::Isolate* isolate = v8::Isolate::GetCurrent();
    auto key = v8::String::NewFromUtf8(isolate, name, v8::NewStringType::kNormal).ToLocalChecked();
    auto tpl = v8::FunctionTemplate::New(isolate, callback);
    auto fn = tpl->GetFunction(context).ToLocalChecked();
    return context->Global()->Set(context, key, fn).FromMaybe(false);
}

bool V8ExtensionContext::InstallHostBindings(v8::Local<v8::Context> context) {
    return SetGlobalFunction(context, "__orionNativeHostBridge", HostApiCallback) &&
           SetGlobalFunction(context, "__orionNativeConsole", ConsoleCallback) &&
           SetGlobalFunction(context, "__orionNativeSetTimer", SetTimerCallback) &&
           SetGlobalFunction(context, "__orionNativeClearTimer", ClearTimerCallback) &&
           SetGlobalFunction(context, "__orionNativeImportScripts", ImportScriptsCallback);
}

void V8ExtensionContext::HostApiCallback(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    auto context = isolate->GetCurrentContext();
    auto* self = static_cast<V8ExtensionContext*>(context->GetAlignedPointerFromEmbedderData(0));
    if (!self || args.Length() < 3) return;
    v8::String::Utf8Value api(isolate, args[0]);
    v8::String::Utf8Value requestId(isolate, args[1]);
    v8::String::Utf8Value payload(isolate, args[2]);
    DispatchHostApiCall(self->m_extensionId, self->m_generationId,
                        *api ? *api : "", *requestId ? *requestId : "", *payload ? *payload : "{}");
}

void V8ExtensionContext::ConsoleCallback(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    auto context = isolate->GetCurrentContext();
    auto* self = static_cast<V8ExtensionContext*>(context->GetAlignedPointerFromEmbedderData(0));
    if (!self || args.Length() < 2) return;
    v8::String::Utf8Value level(isolate, args[0]);
    v8::String::Utf8Value message(isolate, args[1]);
    DispatchConsoleLog(self->m_extensionId, self->m_generationId,
                       *level ? *level : "log", *message ? *message : "");
}

void V8ExtensionContext::SetTimerCallback(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    auto context = isolate->GetCurrentContext();
    auto* self = static_cast<V8ExtensionContext*>(context->GetAlignedPointerFromEmbedderData(0));
    if (!self || args.Length() < 3) return;
    const bool interval = args[0]->BooleanValue(isolate);
    const int delay = args[1]->Int32Value(context).FromMaybe(0);
    const int timerId = args[2]->Int32Value(context).FromMaybe(0);
    std::string payload = std::string("{\"args\":[") +
        (interval ? "true" : "false") + "," + std::to_string(std::max(0, delay)) + "," + std::to_string(timerId) + "]}";
    DispatchHostApiCall(self->m_extensionId, self->m_generationId, "runtime.setTimer",
                        "native_timer_" + std::to_string(timerId), payload);
}

void V8ExtensionContext::ClearTimerCallback(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    auto context = isolate->GetCurrentContext();
    auto* self = static_cast<V8ExtensionContext*>(context->GetAlignedPointerFromEmbedderData(0));
    if (!self || args.Length() < 1) return;
    const int timerId = args[0]->Int32Value(context).FromMaybe(0);
    std::string payload = std::string("{\"args\":[") + std::to_string(timerId) + "]}";
    DispatchHostApiCall(self->m_extensionId, self->m_generationId, "runtime.clearTimer",
                        "native_clear_timer_" + std::to_string(timerId), payload);
}

void V8ExtensionContext::ImportScriptsCallback(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    auto context = isolate->GetCurrentContext();
    auto* self = static_cast<V8ExtensionContext*>(context->GetAlignedPointerFromEmbedderData(0));
    if (!self) {
        isolate->ThrowException(v8::Exception::Error(
            v8::String::NewFromUtf8Literal(isolate, "Native V8 worker context is unavailable")));
        return;
    }

    std::vector<std::string> urls;
    urls.reserve(static_cast<size_t>(args.Length()));
    for (int i = 0; i < args.Length(); ++i) {
        if (!args[i]->IsString()) {
            isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(isolate, "importScripts(): URL must be a string")));
            return;
        }
        v8::String::Utf8Value url(isolate, args[i]);
        if (!*url) {
            isolate->ThrowException(v8::Exception::TypeError(
                v8::String::NewFromUtf8Literal(isolate, "importScripts(): invalid URL")));
            return;
        }
        urls.emplace_back(*url);
    }

    if (!self->ImportScriptsSync(urls)) return;
    args.GetReturnValue().Set(v8::Undefined(isolate));
}

bool V8ExtensionContext::FireTimer(int timerId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate || m_context.IsEmpty()) return false;
    v8::Locker locker(m_isolate);
    v8::Isolate::Scope isolate_scope(m_isolate);
    v8::HandleScope handle_scope(m_isolate);
    auto context = v8::Local<v8::Context>::New(m_isolate, m_context);
    v8::Context::Scope context_scope(context);
    auto name = v8::String::NewFromUtf8(m_isolate, "__orionFireTimer", v8::NewStringType::kNormal).ToLocalChecked();
    v8::Local<v8::Value> value;
    if (!context->Global()->Get(context, name).ToLocal(&value) || !value->IsFunction()) return false;
    auto arg = v8::Integer::New(m_isolate, timerId);
    v8::Local<v8::Value> argv[1] = { arg };
    if (v8::Local<v8::Function>::Cast(value)->Call(context, context->Global(), 1, argv).IsEmpty()) return false;
    m_isolate->PerformMicrotaskCheckpoint();
    V8EngineRuntime::PumpMessageLoop(m_isolate);
    return true;
}

#else
bool V8ExtensionContext::Initialize(void* isolate, const std::string& scriptPath, const std::string& bootstrapScript, const std::string& moduleSourcesBlob, const std::string& extensionRootPath) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_isolate = isolate;
    m_scriptPath = scriptPath;
    m_bootstrapScript = bootstrapScript;
    return true;
}

bool V8ExtensionContext::ExecuteScript(const std::string& scriptContent) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate) return false;
    return true;
}

bool V8ExtensionContext::DispatchEvent(const std::string& eventName, const std::string& payloadJson) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isolate) return false;
    return true;
}

void V8ExtensionContext::ResolveHostCall(const std::string& requestId, const std::string& resultJson, const std::string& errorMsg) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_pendingCalls.find(requestId);
    if (it != m_pendingCalls.end()) {
        if (it->second.callback) {
            it->second.callback(resultJson, errorMsg);
        }
        m_pendingCalls.erase(it);
    }
}

bool V8ExtensionContext::FireTimer(int timerId) {
    (void)timerId;
    return false;
}
#endif

} // namespace orion::v8runtime
