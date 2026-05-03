import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localSigningProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: localSigningProperties.getProperty(name)
        ?: providers.environmentVariable(name).orNull

val releaseStoreFile = releaseSigningValue("RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

/**
 * QQ 交流群群号 —— 故意 NOT 写在源码中。注入顺序：
 *   1. -PqqGroupId=xxx 命令行 / gradle.properties (可在 ~/.gradle/gradle.properties，不入仓)
 *   2. local.properties 的 qq.group.id / qqGroupId（local.properties 已被 .gitignore）
 *   3. 环境变量 QQ_GROUP_ID（CI/CD 通过 GitHub Actions secrets 注入）
 *   4. 缺省 = 空字符串；运行时 UI 显示 "请联系作者获取群号"
 *
 * 这样开源仓库 + commit 历史里都看不到群号，社工/恶意爬虫拿不到联系方式。
 * 维护者只需在自己机器的 local.properties 加一行 `qqGroupId=...` 即可正常打包带群号的 release。
 */
val qqGroupId: String = providers.gradleProperty("qqGroupId").orNull
    ?: localSigningProperties.getProperty("qqGroupId")
    ?: localSigningProperties.getProperty("qq.group.id")
    ?: providers.environmentVariable("QQ_GROUP_ID").orNull
    ?: ""

android {
    namespace = "com.morealm.app"
    compileSdk = 35

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.morealm.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha1"

        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // QQ 群号注入到 BuildConfig.QQ_GROUP_ID。
        // 注意 escape：BuildConfig 字符串字面量要包含双引号，外层 Kotlin 字符串再 escape 一次。
        buildConfigField("String", "QQ_GROUP_ID", "\"${qqGroupId}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Enable desugaring for java.time on API < 26
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Desugaring for API < 26
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.core.splashscreen)

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3.window)
    debugImplementation(libs.compose.ui.tooling)

    // Activity & Navigation
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // Window (tablet/foldable)
    implementation(libs.window)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Paging
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.jsoupxpath)
    implementation(libs.jsonpath)
    implementation(libs.quick.transfer.core)
    implementation("org.apache.commons:commons-text:1.12.0")

    // Rhino JS Engine
    implementation("org.mozilla:rhino:1.8.1")

    // Image
    implementation(libs.coil.compose)
    implementation("io.coil-kt:coil-svg:2.7.0")

    // Media3
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media:media:1.7.0")

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.serialization.json)

    // DocumentFile (SAF)
    implementation(libs.documentfile)

    // WebView
    implementation(libs.webkit)

    // Glance App Widget — 桌面小组件「继续阅读」。运行时 SDK_INT < 23 通过
    // res/values/widget_bools.xml 把 receiver 禁用，所以低版本设备不会触达
    // Glance 代码路径，依赖本身保留以便 R8 不剥离 Glance 类。
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // WiFi 传书 — local HTTP server (ported from Legado NanoHTTPD)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // QR code scanning — book source import via camera
    implementation("com.github.jenly1314:zxing-lite:3.3.0")

    // Archive — 7z/rar/tar support beyond java.util.zip
    implementation("me.zhanghai.android.libarchive:library:1.1.6")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
