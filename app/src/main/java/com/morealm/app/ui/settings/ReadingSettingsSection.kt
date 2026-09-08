package com.morealm.app.ui.settings

internal data class ReadingSettingsSection(val title: String, val keywords: String) {
    fun matches(query: String): Boolean = query.trim().split(Regex("\\s+"))
        .all { title.contains(it, ignoreCase = true) || keywords.contains(it, ignoreCase = true) }
}

internal val readingSettingsSections = listOf(
    ReadingSettingsSection("屏幕交互", "翻页动画 点击区域"),
    ReadingSettingsSection("实体按键与设备", "音量键翻页 音量键方向反转 下键翻上一页 音量键长按 耳机 / 蓝牙翻页器"),
    ReadingSettingsSection("启动偏好", "启动后继续上次阅读"),
    ReadingSettingsSection("选区与高亮", "长按文字划线 选区菜单按钮"),
    ReadingSettingsSection("阅读界面", "竖排版 屏幕关闭时间 显示系统状态 显示章节名 显示时间电量 章节标题对齐"),
    ReadingSettingsSection("文字上色", "文字上色 标点 / 数字 / 字母 / 引号内…按规则着色"),
    ReadingSettingsSection("文内搜索", "搜索方向 前向 = 当前页之前；后向 = 当前页之后"),
    ReadingSettingsSection("阅读配色", "文字颜色和背景 文字色、背景色、背景图整组管理"),
    ReadingSettingsSection("TXT 章节识别", ""),
    ReadingSettingsSection("TTS 朗读", "锁屏保活 CPU 开启后耗电略高；仅当锁屏偶发断声时建议开启"),
)
