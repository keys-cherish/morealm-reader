package com.morealm.app.presentation.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 书源登录表单的一项 —— 移植自 Legado `data.entities.rule.RowUi`。
 *
 * 字段映射（Legado → MoRealm）：
 *   - `name`     表单字段名（与 loginInfo map 的 key 对齐）
 *   - `type`     `text` / `password` / `button` / `toggle` / `select`
 *   - `action`   button：点击触发的 JS（或 http(s) URL，后者由调用方 openUrl）
 *                text/password：输入防抖触发（暂未实现自动触发，仅按钮显式调）
 *                select/toggle：切换后触发的 JS
 *   - `chars`    select / toggle 的候选项；select 是下拉菜单选项，toggle 是循环切换列表
 *   - `default`  默认显示文字 / 默认值
 *   - `viewName` 字段显示名（占位/标签）；空则用 `name`。
 *
 * 兼容旧 `LoginField`：[name] / [hint] / [type] 字段保留同名时反序列化能直接读出。
 *
 * 跨设备共享 JSON：跟 Legado 字段名一致，可以 import Legado 导出的 loginUi 串。
 */
@Serializable
data class RowUi(
    val name: String = "",
    val type: String = Type.TEXT,
    val action: String? = null,
    val chars: List<String>? = null,
    val default: String? = null,
    @SerialName("viewName")
    val viewName: String? = null,
    /** 兼容旧 LoginField 的 `hint` 字段。优先用 [viewName]，fallback hint，再 fallback name。 */
    val hint: String? = null,
) {
    object Type {
        const val TEXT = "text"
        const val PASSWORD = "password"
        const val BUTTON = "button"
        const val TOGGLE = "toggle"
        const val SELECT = "select"
    }

    /** 给 UI 用的显示文本：优先 viewName，否则 hint，否则 name。 */
    fun displayLabel(): String =
        viewName?.takeIf { it.isNotBlank() } ?: hint?.takeIf { it.isNotBlank() } ?: name
}
