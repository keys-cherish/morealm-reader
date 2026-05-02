package com.morealm.app.domain.cover

/**
 * 用户自定义封面的"属主类别"。
 *
 * 设计原则：新增一种只需在这里加一个枚举常量，加 DB 字段，UI 里加一个调用点——
 * 不改 [CoverStorage] 接口本身。未来扩展路径：
 *  - TAG: 给 TagDefinition 加 customCoverUrl 字段即可
 *  - THEME: 主题封面 / 启动图
 *  - USER: 用户头像
 */
enum class CoverKind(val dirName: String) {
    BOOK(dirName = "book"),
    GROUP(dirName = "group"),
    // TAG(dirName = "tag"),  // 预留，落地时解注
}
