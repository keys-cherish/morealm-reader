package com.morealm.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CSS editor with syntax highlighting, preset snippets, and validation.
 *
 * Designed to be foolproof:
 * - Quick-insert preset chips for common adjustments
 * - Real-time syntax highlighting so users can see structure
 * - Validation feedback showing which properties were recognized
 * - Supported properties reference
 */
@Composable
fun CssEditorSection(
    css: String,
    onCssChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    liveUpdate: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
) {
    var expanded by remember { mutableStateOf(css.isNotEmpty()) }
    var textFieldValue by remember(css) { mutableStateOf(TextFieldValue(css)) }
    val validationResult = remember(textFieldValue.text) { validateCss(textFieldValue.text) }

    Column(modifier = modifier) {
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Icon(Icons.Default.Code, null,
                tint = accentColor,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("自定义 CSS", style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            if (css.isNotEmpty()) {
                Text("已配置", style = MaterialTheme.typography.labelSmall,
                    color = accentColor)
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, modifier = Modifier.size(18.dp),
                tint = contentColor.copy(alpha = 0.4f))
        }

        if (!expanded) return@Column

        Spacer(Modifier.height(8.dp))

        // ── Preset snippets (防呆) ──
        Text("快捷插入", style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CssPreset.entries.forEach { preset ->
                FilterChip(
                    selected = cssPropertyValue(textFieldValue.text, preset.property) == preset.value,
                    onClick = {
                        val current = textFieldValue.text.trim()
                        val newCss = if (current.isEmpty()) preset.css
                        else if (current.contains(preset.property)) {
                            // Replace existing value for this property
                            current.replace(
                                Regex("""${Regex.escape(preset.property)}\s*:\s*[^;]+;?"""),
                                preset.css,
                            )
                        } else {
                            "$current\n${preset.css}"
                        }
                        textFieldValue = TextFieldValue(newCss)
                        onCssChange(newCss)
                    },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accentColor.copy(alpha = 0.15f),
                        selectedLabelColor = accentColor,
                        labelColor = contentColor.copy(alpha = 0.75f)),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Syntax-highlighted editor ──
        val colorScheme = MaterialTheme.colorScheme
        val highlightColors = remember(colorScheme, contentColor, accentColor) {
            CssHighlightColors(
                property = accentColor,
                value = colorScheme.tertiary,
                punctuation = contentColor.copy(alpha = 0.35f),
                comment = contentColor.copy(alpha = 0.3f),
                error = colorScheme.error,
                text = contentColor.copy(alpha = 0.85f),
            )
        }

        // Cache highlighted text to avoid recomputation in decorationBox
        val highlightedText = remember(textFieldValue.text, highlightColors) {
            highlightCss(textFieldValue.text, highlightColors)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 160.dp)
                .background(
                    containerColor,
                    RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        ) {
            if (textFieldValue.text.isEmpty()) {
                Text(
                    "text-indent: 2em;\nline-height: 1.8;",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = contentColor.copy(alpha = 0.25f),
                    ),
                )
            }
            BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    if (liveUpdate) {
                        onCssChange(it.text)
                    }
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Color.Transparent, // hidden — we draw highlighted text on top
                ),
                cursorBrush = SolidColor(accentColor),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        // Highlighted overlay
                        Text(
                            text = highlightedText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            ),
                        )
                        // Invisible real text field (for cursor + input)
                        innerTextField()
                    }
                },
            )
        }

        // ── Validation feedback ──
        Spacer(Modifier.height(6.dp))
        if (textFieldValue.text.isNotBlank()) {
            if (validationResult.errors.isNotEmpty()) {
                validationResult.errors.forEach { err ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(err, style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.error)
                    }
                }
            }
            if (validationResult.recognized.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null,
                        tint = accentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "已识别: ${validationResult.recognized.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // ── Action buttons ──
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (textFieldValue.text != css && textFieldValue.text.isNotBlank()) {
                FilterChip(
                    selected = false,
                    onClick = { onCssChange(textFieldValue.text) },
                    label = { Text("应用") },
                    leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = accentColor.copy(alpha = 0.15f)),
                )
            }
            if (textFieldValue.text.isNotEmpty()) {
                FilterChip(
                    selected = false,
                    onClick = {
                        textFieldValue = TextFieldValue("")
                        onCssChange("")
                    },
                    label = { Text("清除") },
                    leadingIcon = { Icon(Icons.Default.Clear, null, Modifier.size(14.dp)) },
                )
            }
        }

        // ── Supported properties reference ──
        Spacer(Modifier.height(8.dp))
        Text("支持的属性", style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.4f))
        Text(
            "text-indent · line-height · letter-spacing · text-align\n" +
                "font-size · paragraph-spacing\n" +
                "padding-top/bottom/left/right",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            color = contentColor.copy(alpha = 0.3f),
        )
    }
}

// ── Preset CSS snippets ──

private enum class CssPreset(val label: String, val property: String, val value: String) {
    NO_INDENT("取消缩进", "text-indent", "0"),
    INDENT_2("首行缩进2字", "text-indent", "2em"),
    LINE_HEIGHT_TIGHT("行距紧凑", "line-height", "1.5"),
    LINE_HEIGHT_LOOSE("行距宽松", "line-height", "2.2"),
    ALIGN_LEFT("左对齐", "text-align", "left"),
    ALIGN_JUSTIFY("两端对齐", "text-align", "justify"),
    FONT_LARGE("大字体", "font-size", "22sp"),
    PARA_SPACING("段间距大", "paragraph-spacing", "15"),
    ;

    val css: String get() = "$property: $value;"
}

private fun cssPropertyValue(css: String, property: String): String? {
    val regex = Regex("""${Regex.escape(property)}\s*:\s*([^;{}]+)""", RegexOption.IGNORE_CASE)
    return regex.findAll(css).lastOrNull()?.groupValues?.getOrNull(1)?.trim()?.lowercase()
}

// ── Syntax highlighting ──

private data class CssHighlightColors(
    val property: Color,
    val value: Color,
    val punctuation: Color,
    val comment: Color,
    val error: Color,
    val text: Color,
)

private fun highlightCss(css: String, colors: CssHighlightColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < css.length) {
            // Comments
            if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
                val end = css.indexOf("*/", i + 2)
                val commentEnd = if (end >= 0) end + 2 else css.length
                withStyle(SpanStyle(color = colors.comment)) {
                    append(css.substring(i, commentEnd))
                }
                i = commentEnd
                continue
            }
            // Property: value; — only match if colon comes before any brace/newline
            if (css[i].isLetter() || css[i] == '-') {
                val colonIdx = css.indexOf(':', i)
                val braceIdx = css.indexOf('{', i)
                val nlIdx = css.indexOf('\n', i)
                // Only treat as property:value if colon comes first (before { or newline)
                val colonIsFirst = colonIdx > i &&
                    (braceIdx < 0 || colonIdx < braceIdx) &&
                    (nlIdx < 0 || colonIdx < nlIdx)
                if (colonIsFirst) {
                    val propName = css.substring(i, colonIdx)
                    withStyle(SpanStyle(color = colors.property, fontWeight = FontWeight.Medium)) {
                        append(propName)
                    }
                    withStyle(SpanStyle(color = colors.punctuation)) { append(":") }
                    i = colonIdx + 1
                    // Value (until ; or } or newline)
                    val semiIdx = css.indexOf(';', i)
                    val valueEnd = if (semiIdx >= 0) semiIdx else {
                        val bIdx = css.indexOf('}', i)
                        val nIdx = css.indexOf('\n', i)
                        when {
                            bIdx >= 0 && nIdx >= 0 -> minOf(bIdx, nIdx)
                            bIdx >= 0 -> bIdx
                            nIdx >= 0 -> nIdx
                            else -> css.length
                        }
                    }
                    val valueStr = css.substring(i, valueEnd)
                    withStyle(SpanStyle(color = colors.value)) { append(valueStr) }
                    i = valueEnd
                    if (i < css.length && css[i] == ';') {
                        withStyle(SpanStyle(color = colors.punctuation)) { append(";") }
                        i++
                    }
                    continue
                }
            }
            // Braces, semicolons, other characters
            when (css[i]) {
                '{', '}' -> withStyle(SpanStyle(color = colors.punctuation)) { append(css[i].toString()) }
                ';' -> withStyle(SpanStyle(color = colors.punctuation)) { append(";") }
                else -> withStyle(SpanStyle(color = colors.text)) { append(css[i].toString()) }
            }
            i++
        }
    }
}

// ── Validation ──

private data class CssValidation(
    val recognized: List<String>,
    val errors: List<String>,
)

private val SUPPORTED_PROPERTIES = setOf(
    "text-indent", "line-height", "letter-spacing", "text-align",
    "margin-top", "margin-bottom", "margin-left", "margin-right",
    "padding-top", "padding-bottom", "padding-left", "padding-right",
    "font-size", "paragraph-spacing",
)

private val propertyRegex = Regex("""([\w-]+)\s*:\s*([^;{}]+)""", RegexOption.IGNORE_CASE)
private val indentValueRegex = Regex("""[\d.]+\s*(em|px)|0""")
private val fontSizeUnitRegex = Regex("(sp|px|pt)")

private fun validateCss(css: String): CssValidation {
    if (css.isBlank()) return CssValidation(emptyList(), emptyList())

    val recognized = mutableListOf<String>()
    val errors = mutableListOf<String>()

    for (match in propertyRegex.findAll(css)) {
        val prop = match.groupValues[1].trim().lowercase()
        val value = match.groupValues[2].trim()
        if (prop in SUPPORTED_PROPERTIES) {
            recognized.add(prop)
            // Value validation
            when (prop) {
                "text-indent" -> {
                    if (!value.matches(indentValueRegex)) {
                        errors.add("text-indent 格式: 2em 或 0")
                    }
                }
                "line-height" -> {
                    val num = value.replace("em", "").trim().toFloatOrNull()
                    if (num == null || num < 1.0 || num > 4.0) {
                        errors.add("line-height 范围: 1.0 ~ 4.0")
                    }
                }
                "font-size" -> {
                    val num = value.replace(fontSizeUnitRegex, "").trim().toFloatOrNull()
                    if (num == null || num < 8 || num > 72) {
                        errors.add("font-size 范围: 8 ~ 72")
                    }
                }
                "text-align" -> {
                    if (value !in listOf("left", "center", "justify", "right")) {
                        errors.add("text-align 可选: left / center / justify")
                    }
                }
            }
        } else {
            errors.add("不支持的属性: $prop")
        }
    }

    // Check for unmatched braces
    val openBraces = css.count { it == '{' }
    val closeBraces = css.count { it == '}' }
    if (openBraces != closeBraces) {
        errors.add("大括号不匹配 { $openBraces 个 } $closeBraces 个")
    }

    return CssValidation(recognized, errors)
}
