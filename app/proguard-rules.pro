# MoRealm ProGuard Rules

# Keep Room entities
-keep class com.morealm.app.data.entity.** { *; }

# Keep Serializable classes
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class com.morealm.app.**$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep model classes used in JSON
-keep class com.morealm.app.model.source.** { *; }
-keep class com.morealm.app.model.webdav.WebDavConfig { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coil
-dontwarn coil.**

# JSoup
-keep class org.jsoup.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# WebView JS interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Compose
-dontwarn androidx.compose.**
