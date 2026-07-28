package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.ReaderStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 排版预设（[ReaderStyle]）导入 / 导出的传输格式。
 *
 * 与主题（MoRealmThemeBundle）同款信封模式：显式 `format` 判别符 + `version`，
 * import 端不做字段名嗅探。id / isBuiltin / sortOrder 是设备内身份字段不出境；
 * customFontUri / customBgImage 是本机路径，换设备无意义，同样不导出
 * （对齐主题导出跳过 texture: URI 的取舍）。
 */
@Serializable
data class MoRealmReaderStyleBundle(
    val format: String = FORMAT,
    val version: Int = 1,
    val styles: List<ReaderStyleExportData> = emptyList(),
) {
    companion object { const val FORMAT = "morealm-readerstyle" }
}

@Serializable
data class ReaderStyleExportData(
    val name: String,
    val textSize: Int = 18,
    val fontFamily: String = "noto_serif_sc",
    val textBold: Int = 0,
    val letterSpacing: Float = 0f,
    val lineHeight: Float = 1.4f,
    val paragraphSpacing: Int = 8,
    val paragraphIndent: String = "　　",
    val textAlign: String = "justify",
    val titleMode: Int = 0,
    val titleSize: Int = 0,
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,
    val paddingTop: Int = 16,
    val paddingBottom: Int = 16,
    val paddingLeft: Int = 16,
    val paddingRight: Int = 16,
    val customCss: String = "",
)

fun ReaderStyle.toExportData(): ReaderStyleExportData = ReaderStyleExportData(
    name = name,
    textSize = textSize,
    fontFamily = fontFamily,
    textBold = textBold,
    letterSpacing = letterSpacing,
    lineHeight = lineHeight,
    paragraphSpacing = paragraphSpacing,
    paragraphIndent = paragraphIndent,
    textAlign = textAlign,
    titleMode = titleMode,
    titleSize = titleSize,
    titleTopSpacing = titleTopSpacing,
    titleBottomSpacing = titleBottomSpacing,
    paddingTop = paddingTop,
    paddingBottom = paddingBottom,
    paddingLeft = paddingLeft,
    paddingRight = paddingRight,
    customCss = customCss,
)

/** 导入侧落地为**新的自定义预设**：新 id、非 builtin，绝不覆盖内置 preset_*。 */
fun ReaderStyleExportData.toEntity(id: String, sortOrder: Int): ReaderStyle = ReaderStyle(
    id = id,
    name = name,
    sortOrder = sortOrder,
    textSize = textSize,
    fontFamily = fontFamily,
    textBold = textBold,
    letterSpacing = letterSpacing,
    lineHeight = lineHeight,
    paragraphSpacing = paragraphSpacing,
    paragraphIndent = paragraphIndent,
    textAlign = textAlign,
    titleMode = titleMode,
    titleSize = titleSize,
    titleTopSpacing = titleTopSpacing,
    titleBottomSpacing = titleBottomSpacing,
    paddingTop = paddingTop,
    paddingBottom = paddingBottom,
    paddingLeft = paddingLeft,
    paddingRight = paddingRight,
    customCss = customCss,
    isBuiltin = false,
)

/**
 * 解析导入文本 → 预设列表。识别顺序：
 *  1. JsonObject 且 `format == "morealm-readerstyle"` → 信封 bundle
 *  2. JsonArray（元素为裸 [ReaderStyleExportData]）→ 数组
 *  3. JsonObject 含 `name` 字段 → 单个裸对象
 * 均不命中 / 解析失败 → 空列表（调用方提示格式不支持）。
 */
fun parseReaderStyleBundle(json: Json, text: String): List<ReaderStyleExportData> {
    val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    return runCatching {
        when {
            parsed is JsonObject &&
                parsed["format"]?.jsonPrimitive?.contentOrNull == MoRealmReaderStyleBundle.FORMAT ->
                json.decodeFromString(MoRealmReaderStyleBundle.serializer(), text).styles

            parsed is JsonArray ->
                json.decodeFromString<List<ReaderStyleExportData>>(text)

            parsed is JsonObject && parsed.containsKey("name") ->
                listOf(json.decodeFromString(ReaderStyleExportData.serializer(), text))

            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}
