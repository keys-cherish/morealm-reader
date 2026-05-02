package com.morealm.app.core.error

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 异常 → 用户文案的统一封装。
 *
 * 设计动机（UX-2）：
 *  - 全 App 散落着 `"读取文件失败: ${e.message}"` 这种拼接，半角冒号后不空格、`message`
 *    可能是英文/Java 类全限定名/null，呈现给用户既丑又看不懂。
 *  - 不同入口本地化措辞不一致：「读取失败」「导入失败：」「读取文件失败:」混着用。
 *  - 网络类异常 (UnknownHostException / SocketTimeoutException / IOException) 的 message
 *    通常是 "host.example.com" 或 null，单看 message 用户根本不知道是网络问题。
 *
 * 设计取舍：
 *  - 不做完整 i18n 框架（项目目前是中文单语），但留足扩展空间 — 文案集中在这里，将来
 *    要切 string resource 一处改。
 *  - 不接管所有 throw，只服务于「展示给用户」的那段。日志原文继续用 e 自身打。
 *  - 半角冒号 `:` 之后强制留空格，全角冒号 `：` 不留 — 这是中文排版惯例。
 */
object ErrorMessages {

    /**
     * 格式化异常给用户看：
     *  - [action] 是动作短语（"读取文件" / "导入书源" / "登录"）；不带「失败」后缀
     *  - [e]     原异常；常见网络异常会被翻译成更友好的句子，其他取 message 兜底
     *
     * 输出示例：
     *   "读取文件失败：JSON 格式不对（第 3 行）"
     *   "导入书源失败：网络不通，请检查 Wi-Fi"
     *   "登录失败：服务器响应超时"
     *   "读取文件失败"   （e.message 为 null 时不强行拼空字符串）
     */
    fun forUser(action: String, e: Throwable): String {
        val tail = describe(e)
        return if (tail.isNullOrBlank()) "${action}失败" else "${action}失败：$tail"
    }

    /**
     * 跟 [forUser] 一样但用半角冒号 + 空格，适合英文环境或纯日志型 Toast。
     * 中文优先用 [forUser]。
     */
    fun forUserAscii(action: String, e: Throwable): String {
        val tail = describe(e)
        return if (tail.isNullOrBlank()) "$action failed" else "$action failed: $tail"
    }

    /**
     * 把异常翻译成中文提示。返回 null 表示「没法给出比 message 更好的描述」，
     * 调用方按自己语境兜底。
     */
    private fun describe(e: Throwable): String? = when (e) {
        is UnknownHostException -> "网络不通，请检查 Wi-Fi 或移动数据"
        is SocketTimeoutException -> "服务器响应超时"
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "网络读写出错"
        else -> e.message?.takeIf { it.isNotBlank() }
    }
}
