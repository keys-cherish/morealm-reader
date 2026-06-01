# MoRealm ProGuard / R8 Rules

# Release APK 启用 R8 obfuscation（类名 / 方法名 / 字段重命名为 a/b/c）。
# crash log 需配合 app/build/outputs/mapping/release/mapping.txt retrace 还原。

# ── Room entities (domain.entity package) ──
-keep class com.morealm.app.domain.entity.** { *; }

# ── Kotlin Serialization ──
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class com.morealm.app.**$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# ── BookSource rule classes (JSON deserialized) ──
-keep class com.morealm.app.domain.entity.rule.** { *; }

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── Coil ──
-dontwarn coil.**

# ── JSoup / XPath ──
-keep class org.jsoup.** { *; }
-keep class org.seimicrawler.xpath.** { *; }

# ── JsonPath ──
-keep class com.jayway.jsonpath.** { *; }
-dontwarn com.jayway.jsonpath.**

# ── Rhino JS Engine ──
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ── Apache Commons Text ──
-dontwarn org.apache.commons.text.**

# ── Hilt ──
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── WebView JS interface ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Compose ──
-dontwarn androidx.compose.**

# ── AnalyzeRule / JsExtensions (reflection-heavy, called from Rhino) ──
-keep class com.morealm.app.domain.analyzeRule.** { *; }

# ── R8 full mode compatibility ──
-dontwarn java.lang.invoke.StringConcatFactory

# ── Native algo loader ──
# 保留 native 方法签名，避免 R8 重命名导致 JNI RegisterNatives 找不到 Java 端方法。
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 反射加载的实现 keep ──
# 经 Class.forName 反射引用、无静态引用的类，R8 默认判 unused 删除 / 重命名导致反射失败。
# keep 类名 + 成员保证反射可达；类不存在时 keep 无目标（R8 忽略）。
-keep class com.morealm.app.domain.security.SignatureGuardImpl { *; }
-keep class com.morealm.app.domain.security.SignatureGuardDelegate { *; }

# ── R8 进一步收紧（编译期，零运行时成本）──
# repackageclasses '' 把所有未被 keep 的类压进单一空包名，抹掉包结构信息；
# allowaccessmodification 放宽访问修饰符让 R8 更激进地内联 / 合并。
# 二者纯编译期、不增运行时开销；与移除 epub consumer keep 配合，让排版核心（主仓
# domain/render/layout/** + epub layout/compat，均无 keep）在 release 里更难反推。
# 已 keep 的反射 / 序列化类（entity / analyzeRule / rule / serializer）不受 repackage 影响。
-repackageclasses ''
-allowaccessmodification
