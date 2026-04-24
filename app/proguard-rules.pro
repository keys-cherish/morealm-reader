# MoRealm ProGuard / R8 Rules

# ── Disable obfuscation (keep class/method names readable) ──
-dontobfuscate

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
