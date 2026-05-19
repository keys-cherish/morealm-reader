pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MoRealm"
include(":app")

// 自研 EPUB 解析库（KMP）：本地 composite build，引用 ../epub-lib。
// 通过 `com.morealm.epub:epub-core` / `epub-compat` 在 app/build.gradle.kts 引用。
// 阶段 1：仅 includeBuild 验证编译通路，不替换现 EpubParser（Jsoup 路径并存）。
includeBuild("../epub-lib")
