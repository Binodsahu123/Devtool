#include <jni.h>
#include <string>

#include "isolated_world_manager.h"
#include "main_world_executor.h"

namespace {
swift::extension::IsolatedWorldManager g_worlds;
swift::extension::MainWorldExecutor g_main_world_executor;

std::string JString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_swift_browser_extensionengine_nativecore_NativeExtensionCoreBridge_nativeGetWorldName(
    JNIEnv* env, jobject, jstring ext) {
    return ToJString(env, g_worlds.WorldNameForExtension(JString(env, ext)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swift_browser_extensionengine_nativecore_NativeExtensionCoreBridge_nativeValidateMainWorldScript(
    JNIEnv* env, jobject, jstring code) {
    return g_main_world_executor.ValidateJavaScript(JString(env, code)) ? JNI_TRUE : JNI_FALSE;
}

jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
