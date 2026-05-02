package com.morealm.app.presentation.source

/**
 * 书源登录 JS 反向通道桥。
 *
 * 注入到 [SourceLoginViewModel.runActionJs] 的脚本作用域里（key = `loginExt`），让
 * 登录脚本能在执行期间反向更新表单：
 *
 * ```js
 * loginExt.upUiData({ "captcha": "data:image/png;base64,..." })
 * loginExt.reUiView()  // 强制重建表单（loginUi 是 @js: 时重新求值）
 * ```
 *
 * 与 Legado SourceLoginJsExtensions 行为对齐，但绑定 key 不同（Legado 用 `java`
 * 整体替换；MoRealm 用 `loginExt` 副绑定，避免覆盖 java.* 全套扩展）。Legado 源迁移
 * 时把 `java.upUiData` 改成 `loginExt.upUiData` 即可，剩下 java.* 调用保持不变。
 *
 * 线程：JS 执行通常在 IO 池，[onUpUiData] 内部通过 SharedFlow 发出，由 Compose 端在
 * Main 线程 collect 应用更新；UI 不会在 IO 线程被改。
 */
class SourceLoginJsBridge(
    private val onUpUiData: (Map<String, String>) -> Unit,
    private val onReUiView: (Boolean) -> Unit,
) {
    /**
     * 增量更新表单字段。JS 传入 `{ name: value, ... }`，对每个 name 在表单里查找对应
     * 输入框 / select / toggle 写入新值。未匹配的字段忽略（不破坏 UI）。
     */
    fun upUiData(map: Any?) {
        if (map !is Map<*, *>) return
        val converted = HashMap<String, String>(map.size)
        for ((k, v) in map) {
            if (k == null || v == null) continue
            converted[k.toString()] = v.toString()
        }
        if (converted.isNotEmpty()) onUpUiData(converted)
    }

    /** 强制 UI 重建（如 loginUi 的字段结构会随某些字段值改变）。 */
    fun reUiView() {
        onReUiView(false)
    }

    fun reUiView(deltaUp: Boolean) {
        onReUiView(deltaUp)
    }
}
