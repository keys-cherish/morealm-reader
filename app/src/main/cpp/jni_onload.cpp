// JNI_OnLoad —— .so 中唯一 global 导出符号（symbols.ver 控制）。
// 启动时把 NativeOps 的 native 方法手动绑定到 cpp 实现，避免命名约定
// （Java_<pkg>_<class>_<method>）把全限定 Kotlin 类名 baked 进 .so 符号表。

#include <jni.h>

// Forward decl —— 真实实现在 edge_tts_auth.cpp 的同名函数。
// symbols.ver 把所有非 JNI_OnLoad 符号限定为 local，所以跨 TU 链接成功后
// 二进制内仍看不到此函数名。
jstring morealm_op_generate_sec_ms_gec(JNIEnv* env, jclass clazz, jlong now_millis);
jstring morealm_op_trusted_client_identifier(JNIEnv* env, jclass clazz);

namespace {

constexpr const char* kNativeOpsClass = "com/morealm/app/algo/NativeOps";

const JNINativeMethod kNativeOpsMethods[] = {
    {
        const_cast<char*>("generateSecMsGec"),
        const_cast<char*>("(J)Ljava/lang/String;"),
        reinterpret_cast<void*>(morealm_op_generate_sec_ms_gec),
    },
    {
        const_cast<char*>("trustedClientIdentifier"),
        const_cast<char*>("()Ljava/lang/String;"),
        reinterpret_cast<void*>(morealm_op_trusted_client_identifier),
    },
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass cls = env->FindClass(kNativeOpsClass);
    if (cls == nullptr) {
        return JNI_ERR;
    }

    if (env->RegisterNatives(cls, kNativeOpsMethods,
                             sizeof(kNativeOpsMethods) / sizeof(JNINativeMethod)) < 0) {
        env->DeleteLocalRef(cls);
        return JNI_ERR;
    }

    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}
