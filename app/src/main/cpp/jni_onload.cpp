// JNI_OnLoad —— Step 1 placeholder, RegisterNatives 在 Step 2 接入。
//
// 此阶段 .so 还不被 Java 加载，仅验证 NDK / CMake / ABI 工具链通畅。

#include <jni.h>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    return JNI_VERSION_1_6;
}
