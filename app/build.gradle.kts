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

/**
 * 更新下载渠道（百度 / 夸克 / 123 网盘）分享链接 —— 同 qqGroupId 一样**故意不写源码**：
 * local.properties（gitignored）→ -P 命令行 → 环境变量（CI secret）→ 缺省空串。
 * 这样开源仓库 + commit 历史里都看不到具体网盘链接；空串时 UI 隐藏对应渠道按钮。
 */
fun downloadChannelUrl(prop: String, gradleKey: String, env: String): String =
    providers.gradleProperty(gradleKey).orNull
        ?: localSigningProperties.getProperty(prop)
        ?: providers.environmentVariable(env).orNull
        ?: ""
val panBaiduUrl = downloadChannelUrl("pan.baidu.url", "panBaiduUrl", "PAN_BAIDU_URL")
val panQuarkUrl = downloadChannelUrl("pan.quark.url", "panQuarkUrl", "PAN_QUARK_URL")
val pan123Url = downloadChannelUrl("pan.123.url", "pan123Url", "PAN_123_URL")

android {
    namespace = "com.morealm.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.morealm.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 10
        versionName = "1.6"

        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // QQ 群号注入到 BuildConfig.QQ_GROUP_ID。
        // 注意 escape：BuildConfig 字符串字面量要包含双引号,外层 Kotlin 字符串再 escape 一次。
        buildConfigField("String", "QQ_GROUP_ID", "\"${qqGroupId}\"")
        // 三网盘更新下载渠道（见上方 downloadChannelUrl）。空串时 UI 隐藏对应按钮。
        buildConfigField("String", "PAN_BAIDU_URL", "\"${panBaiduUrl}\"")
        buildConfigField("String", "PAN_QUARK_URL", "\"${panQuarkUrl}\"")
        buildConfigField("String", "PAN_123_URL", "\"${pan123Url}\"")

        // androidTest（Compose UI test）—— P3-2 引入；默认 AndroidJUnit4Runner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native ABI 过滤：覆盖现代 arm64 / 老设备 arm32 / 模拟器 + Chromebook。
        // x86 已淘汰（NDK r17+ Google 弃用 32-bit x86）。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // CMake 编译选项：-O3 优化、隐藏符号、关 RTTI / 异常以缩减 .so 体积 +
        // 抬高静态分析门槛；c++_static 让 libc++ 静态链接避免运行时 .so 依赖。
        externalNativeBuild {
            cmake {
                cppFlags("-O3", "-fvisibility=hidden", "-fno-rtti", "-fno-exceptions")
                arguments("-DANDROID_STL=c++_static")
            }
        }
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

    // CMake 入口：源码位于 app/src/main/cpp/CMakeLists.txt。
    // AGP 8.7.x 默认 CMake 3.22.1，与此声明对齐避免本地 / CI 差异。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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

    // Rhino JS Engine — 跑 Legado 书源规则里的内嵌 JS 表达式（@js:.../`<js>...</js>` /
    // ruleSearch/ruleContent 里的 evalJS 段）。MoRealm 不支持 SkyBook / OpenSchedule
    // 风格的整文件 JS 书源（lifecycle 函数模型，与 jsoup 规则模型不兼容），所以不需要
    // 引入 cash QuickJS / dokar3 quickjs-kt 这类 ES2020+ JS runtime。
    implementation("org.mozilla:rhino:1.8.1")

    // 自研 EPUB 解析库（KMP）—— 通过 ../epub-lib composite build 引入（详 settings.gradle.kts）。
    // 阶段 1 仅接入验证编译通路，EpubParser 仍走 Jsoup 旧路径并存；后续阶段切换 +
    // 验证覆盖率后再删 Jsoup。
    implementation("com.morealm.epub:epub-core")
    implementation("com.morealm.epub:epub-compat")
    // R1 (阶段 R1)：核心 layout 算法迁移到独立仓库 epub-layout。Android 端只调用 entry point +
    // 引用 public data class；内部 marker parser / cell stride 算法 / row offset 全 internal。
    implementation("com.morealm.epub:epub-layout")
    implementation("com.morealm.epub:epub-render")

    // Image
    implementation(libs.coil.compose)
    implementation("io.coil-kt:coil-svg:2.7.0")

    // Icons — Lucide (线性轻量矢量，比 Material Icons 更透气)
    implementation("com.composables:icons-lucide:1.1.0")

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

    // 局域网传书 HTTP 服务
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // QR code scanning — book source import via camera
    implementation("com.github.jenly1314:zxing-lite:3.3.0")

    // Archive — 7z/rar/tar support beyond java.util.zip
    implementation("me.zhanghai.android.libarchive:library:1.1.6")

    // Testing (jvmTest / Robolectric)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(libs.coroutines.test)

    // Compose UI test (androidTest) —— P3-2 引入，验证 4 翻页动画手势行为不依赖
    // 真实渲染数据。需要 emulator / 真机跑。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
