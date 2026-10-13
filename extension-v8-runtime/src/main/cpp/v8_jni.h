#ifndef ORION_V8_JNI_H
#define ORION_V8_JNI_H

#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "OrionV8Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOG_CRASH_BOUNDARY_I(...) __android_log_print(ANDROID_LOG_INFO, "CRASH_BOUNDARY", __VA_ARGS__)
#define LOG_CRASH_BOUNDARY_E(...) __android_log_print(ANDROID_LOG_ERROR, "CRASH_BOUNDARY", __VA_ARGS__)

namespace orion::v8runtime {

struct JniContext {
    JavaVM* jvm = nullptr;
    jclass runtimeBridgeClass = nullptr;
    jmethodID midOnHostApiCall = nullptr;
    jmethodID midOnWorkerStarted = nullptr;
    jmethodID midOnWorkerError = nullptr;
    jmethodID midOnEventHandled = nullptr;
    jmethodID midOnConsoleLog = nullptr;
};

extern JniContext g_jniCtx;

std::string JStringToString(JNIEnv* env, jstring jstr);
jstring StringToJString(JNIEnv* env, const std::string& str);

void DispatchHostApiCall(const std::string& extensionId,
                         int generationId,
                         const std::string& apiName,
                         const std::string& requestId,
                         const std::string& payloadJson);

void DispatchConsoleLog(const std::string& extensionId,
                        int generationId,
                        const std::string& level,
                        const std::string& message);

void DispatchWorkerStarted(const std::string& extensionId,
                           int generationId,
                           bool success);

void DispatchWorkerError(const std::string& extensionId,
                         int generationId,
                         const std::string& errorMessage);

void DispatchEventHandled(const std::string& extensionId,
                          int generationId,
                          const std::string& eventName,
                          bool success);

} // namespace orion::v8runtime

#endif // ORION_V8_JNI_H
