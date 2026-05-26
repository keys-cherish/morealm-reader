// edge_tts_auth.cpp —— Sec-MS-GEC token 计算入口。
//
// Step 2 阶段为 stub：返回固定空串验证 JNI 通路；Step 3 替换为真实 HMAC + Token 推导。

#include <jni.h>

// 外部 linkage 但 symbols.ver 把它限定为 local —— 跨 TU 可被 jni_onload.cpp
// 拿地址，但 .so 二进制内仍不导出此名字。
jstring morealm_op_generate_sec_ms_gec(JNIEnv* env, jclass /*clazz*/, jlong /*now_millis*/) {
    return env->NewStringUTF("");
}
