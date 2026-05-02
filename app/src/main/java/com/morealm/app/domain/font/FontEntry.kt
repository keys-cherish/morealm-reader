package com.morealm.app.domain.font

/**
 * 字体管理列表里展示的一项。可能来自：
 *   - APP_LIBRARY  ：复制到 App 私有目录 filesDir/fonts/，路径形如 file:///.../xxx.ttf
 *   - EXTERNAL     ：用户挂载的 SAF Tree 文件夹下扫描出来的，路径是 content:// document URI
 *
 * `path` 必须能直接喂给 [FontRepository.loadTypeface]。判断字体是否"当前选中"用 path 全等比较。
 */
data class FontEntry(
    val displayName: String,
    val path: String,
    val source: Source,
    val sizeBytes: Long,
) {
    enum class Source { APP_LIBRARY, EXTERNAL }
}
