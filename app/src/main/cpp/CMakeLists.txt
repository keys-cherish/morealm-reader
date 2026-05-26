cmake_minimum_required(VERSION 3.22.1)
project("morealm_algo")

# ─────────────────────────────────────────────────────────────────────
# morealm_algo —— 关键算法 native 实现入口
#
# 编译 / 链接策略：
#   - -O3 + 隐藏符号 + 去 RTTI / 异常 / func sections → 缩 .so 体积 + 提反推门槛
#   - --gc-sections 链接期剔除未引用代码
#   - --exclude-libs ALL 让静态依赖符号不导出
#   - symbols.ver 仅放行 JNI_OnLoad，其它内部函数全部 local
#   - --strip-all 去掉所有符号表残留
# ─────────────────────────────────────────────────────────────────────

add_library(
    morealm_algo
    SHARED
    jni_onload.cpp
    edge_tts_auth.cpp
)

target_compile_options(
    morealm_algo
    PRIVATE
    -O3
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fno-rtti
    -fno-exceptions
    -fstack-protector-strong
    -ffunction-sections
    -fdata-sections
)

target_link_options(
    morealm_algo
    PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--strip-all
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,noexecstack
)

set_target_properties(
    morealm_algo
    PROPERTIES
    LINK_FLAGS "-Wl,--version-script=${CMAKE_SOURCE_DIR}/symbols.ver"
)

target_link_libraries(
    morealm_algo
    log
    android
)
