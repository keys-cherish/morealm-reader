package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * MoRealm theme entity. Stores both built-in and user-imported themes.
 *
 * Marked `@Serializable` so [BackupManager.buildBackupData] can include
 * the user's themes in exported `.zip` backups. Without this, the export
 * path threw `SerializationException: Serializer for class 'ThemeEntity'
 * is not found`, which (combined with the now-fixed runCatching swallow)
 * produced 0-byte backup files. All fields are plain primitives / String /
 * String?; no custom types so the default generated serializer suffices.
 */
@Serializable
@Entity(tableName = "themes")
data class ThemeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String = "MoRealm",
    val isBuiltin: Boolean = true,
    val isNightTheme: Boolean = false,
    val isActive: Boolean = false,
    val manifestJson: String = "{}",
    val localPath: String? = null,

    // Core colors (stored as "#AARRGGBB" strings)
    val primaryColor: String = "#FF7C5CFC",
    val accentColor: String = "#FF7C5CFC",
    val backgroundColor: String = "#FF0A0A0F",
    val surfaceColor: String = "#FF111118",
    val onBackgroundColor: String = "#FFEDEDEF",
    val bottomBackground: String = "#FF111118",

    // Reader-specific colors (independent from app theme)
    val readerBackground: String = "#FF0A0A0F",
    val readerTextColor: String = "#FFEDEDEF",

    // 阅读配色组背景图：与 readerBackground / readerTextColor 同步切换。
    val backgroundImageUri: String? = null,   // SAF/file/http URI；null = 纯色背景
    val transparentBars: Boolean = false,     // Make primary/bottom bar transparent for full image coverage

    // Optional reader CSS bundled with the theme. Reader style CSS can still override it.
    val customCss: String = "",
)

/**
 * 参照实现 ThemeConfig.Config compatible format for import/export.
 *
 * 对齐通用主题配置结构; field names
 * must match the imported GSON output. Optional fields (`transparentNavBar`,
 * `backgroundImgPath`, `backgroundImgBlur`) are accepted but only the first
 * two map onto MoRealm — `backgroundImgBlur` has no equivalent and is
 * dropped on import. `customCss` is a MoRealm-only extension.
 *
 * Color derivation (`toThemeEntity`):
 *  - `onBackgroundColor` is chosen by *background luminance*, not the
 *    `isNightTheme` flag — 参照实现 users sometimes mark a paper-tone theme
 *    `isNightTheme=false` with a near-black background, or vice versa.
 *  - `surfaceColor` shifts the background luminance ±4% so cards/sheets get
 *    a faint elevation shadow (matches the layered look in BuiltinThemes).
 *  - `transparentBars` mirrors 参照实现 `transparentNavBar`.
 *  - `backgroundImageUri` is set to `backgroundImgPath` *only if it's an
 *    http/https URL*. Local absolute paths from another device are useless
 *    and are dropped. ThemeRepository later resolves http URLs to local
 *    `file://` URIs.
 */
@Serializable
data class LegadoThemeConfig(
    val themeName: String = "",
    val isNightTheme: Boolean = false,
    val primaryColor: String = "#ff000000",
    val accentColor: String = "#ff000000",
    val backgroundColor: String = "#ffffffff",
    val bottomBackground: String = "#ffffffff",
    val transparentNavBar: Boolean = false,
    val backgroundImgPath: String? = null,
    val backgroundImgBlur: Int = 0,
    val customCss: String = "",
) {
    fun toThemeEntity(): ThemeEntity {
        val bgArgb = parseHexArgb(backgroundColor)
        val isLightBg = bgArgb?.let(::isLightArgb) ?: !isNightTheme
        val surface = bgArgb
            ?.let { shiftArgbLuminance(it, if (isLightBg) -0.04f else 0.04f) }
            ?.let(::argbToHex)
            ?: backgroundColor
        val onBg = if (isLightBg) "#FF1A1A1A" else "#FFEDEDEF"
        val httpBgUrl = backgroundImgPath?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
        return ThemeEntity(
            id = "legado_${themeName.hashCode()}",
            name = themeName,
            author = "Legado Import",
            isBuiltin = false,
            isNightTheme = isNightTheme,
            primaryColor = primaryColor,
            accentColor = accentColor,
            backgroundColor = backgroundColor,
            bottomBackground = bottomBackground,
            surfaceColor = surface,
            onBackgroundColor = onBg,
            readerBackground = backgroundColor,
            readerTextColor = onBg,
            transparentBars = transparentNavBar,
            backgroundImageUri = httpBgUrl,
            customCss = customCss,
        )
    }
}

// region Color helpers — used by 参照实现 import to derive surface/onBackground
//        from a single backgroundColor instead of treating them as identical.
//        Kept private to this file; if these prove useful elsewhere lift them
//        into a dedicated ColorUtils. Do not export the hex parser without
//        first widening test coverage on malformed inputs.

private fun parseHexArgb(hex: String): Int? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6 && s.length != 8) return null
    return runCatching {
        val v = s.toLong(16)
        if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }.getOrNull()
}

/** Perceived-luminance test (Rec. 601) — > 0.5 ⇒ "light" background. */
private fun isLightArgb(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.5
}

private fun shiftArgbLuminance(argb: Int, delta: Float): Int {
    val a = (argb ushr 24) and 0xFF
    val d = (delta * 255).toInt()
    val r = (((argb shr 16) and 0xFF) + d).coerceIn(0, 255)
    val g = (((argb shr 8) and 0xFF) + d).coerceIn(0, 255)
    val b = ((argb and 0xFF) + d).coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun argbToHex(argb: Int): String =
    "#%08X".format(argb.toLong() and 0xFFFFFFFFL)
// endregion

/**
 * 参照实现「阅读样式配置」（ReadBookConfig.Config）的导入数据类 —— 与 [参照实现ThemeConfig]
 * （app 主题色）schema 完全不同。用户从参照实现设置 → 阅读样式 → 导出得到的就是这种 JSON：
 * 顶层字段 `bgStr` / `textColor` / `lineSpacingExtra` / `paragraphIndent` / `tipColor` 等，
 * 描述的是「阅读器排版（字号、行距、缩进、padding）+ 日夜各一份的文本色和背景」。
 *
 * MoRealm 把参照实现 ReadConfig 映射成「日 + 夜 两个 MoRealm 主题」：
 *   - 白天主题：textColor + textAccentColor + bgStr → readerBackground / readerTextColor / accent
 *   - 夜间主题：textColorNight + textAccentColorNight + bgStrNight → 同上
 *
 * 已知限制 — 用 toast 提醒用户：
 *   - `bgStr` 是第三方阅读器沙盒路径（指向其私有 Android/data 目录的绝对路径）
 *     在 Android 11+ 上 MoRealm 不能跨包访问，此时降级到背景色 = `bgStr` 颜色解析失败兜底白底
 *   - 字号/行距/缩进/padding 这些归 ReaderStyle 而非 ThemeEntity，本路径不消费
 *     （要做也可以做，但当前仅修「主题解析报错」P0 痛点）
 */
@kotlinx.serialization.Serializable
data class LegadoReadConfig(
    val name: String = "",
    /** 白天背景：可能是 `#RRGGBB` 颜色 或 文件绝对路径（看 [bgType]）。 */
    val bgStr: String = "#EEEEEE",
    /** 夜间背景。 */
    val bgStrNight: String = "#000000",
    /** 0=纯色，1=assets 内置图，2=外部图片绝对路径。 */
    val bgType: Int = 0,
    val bgTypeNight: Int = 0,
    val bgAlpha: Int = 100,
    /** 白天文字颜色（典型 `#FF111111` 8 位 ARGB hex）。 */
    val textColor: String = "#3E3D3B",
    val textColorNight: String = "#ADADAD",
    val textAccentColor: String = "#E53935",
    val textAccentColorNight: String = "#FE4D55",
    val darkStatusIcon: Boolean = true,
    val darkStatusIconNight: Boolean = false,
)

/**
 * 把一份参照实现 ReadConfig 拆成两个 MoRealm 主题（白天 + 夜间）。返回的 entity 还没做
 * 「绝对路径 bgStr → file:// resolve」—— 那一步在 [com.morealm.app.domain.repository.ThemeRepository]
 * 里做，因为需要 Context 访问 filesDir。
 *
 * inaccessibleBgPaths 是 out 参数：调用方传一个 MutableList，本函数把无法直接当颜色解析
 * 也无法当 file:// uri 用的路径（参照实现沙盒路径）追加进去，让上层 toast 一次性告知用户。
 */
fun LegadoReadConfig.toThemeEntities(inaccessibleBgPaths: MutableList<String>): Pair<ThemeEntity, ThemeEntity> {
    val baseName = name.ifBlank { "Legado 阅读样式" }
    return buildVariant(baseName, isNight = false, inaccessibleBgPaths) to
        buildVariant(baseName, isNight = true, inaccessibleBgPaths)
}

/**
 * 把 ReadConfig 里日 / 夜某一份字段映射成一个 ThemeEntity。`bgStr` 的解析分三种：
 *   1. 形如 `#RRGGBB` / `#AARRGGBB` 的颜色字符串 → readerBackground 用它
 *   2. 形如 `/storage/...` 的绝对路径 → 暂存 file:// uri 作为 backgroundImageUri，让 repo
 *      再做权限校验 / copy 到 internal。无权访问时 repo 把 uri 抹掉，并把路径加进
 *      inaccessibleBgPaths 让 UI toast 提醒
 *   3. assets 内置图（bgType=1）→ 当前不支持，降级走颜色路径
 *
 * 颜色派生逻辑：textColor 直接用 readerTextColor / onBackgroundColor；accentColor 用
 * textAccentColor；bgArgb 如果是颜色字串就用它派生 surface（±4% 亮度）和 isNightTheme（按
 * 亮度判定），否则参考字面 isNight 标记。
 */
private fun LegadoReadConfig.buildVariant(
    baseName: String,
    isNight: Boolean,
    inaccessibleBgPaths: MutableList<String>,
): ThemeEntity {
    val bgRaw = if (isNight) bgStrNight else bgStr
    val bgType = if (isNight) bgTypeNight else this.bgType
    val txt = if (isNight) textColorNight else textColor
    val accent = if (isNight) textAccentColorNight else textAccentColor

    // bgStr 可能是 hex 颜色 或 文件绝对路径。bgType=0 一定是颜色；其它可能是路径。
    val bgArgb = parseHexArgb(bgRaw)
    val bgIsColor = bgArgb != null && bgType == 0
    val bgIsPath = !bgIsColor && bgType == 2 && bgRaw.startsWith("/")
    if (bgIsPath) inaccessibleBgPaths.add(bgRaw)

    // 颜色字段：textColor 7/8 位 hex → ThemeEntity 标准化为 "#AARRGGBB"
    val txtHex = parseHexArgb(txt)?.let(::argbToHex) ?: txt
    val accentHex = parseHexArgb(accent)?.let(::argbToHex) ?: accent
    // bgArgb 可能是 null（路径分支） — 给个白/黑兜底
    val bgHex = bgArgb?.let(::argbToHex) ?: if (isNight) "#FF111111" else "#FFEEEEEE"

    val surfaceArgb = bgArgb?.let { shiftArgbLuminance(it, if (isNight) 0.04f else -0.04f) }
    val surfaceHex = surfaceArgb?.let(::argbToHex) ?: bgHex

    val suffix = if (isNight) "夜" else "日"
    val themeId = "legado_read_${(baseName + suffix).hashCode()}"
    return ThemeEntity(
        id = themeId,
        name = "$baseName · $suffix",
        author = "Legado Import",
        isBuiltin = false,
        isNightTheme = isNight,
        primaryColor = accentHex,
        accentColor = accentHex,
        backgroundColor = bgHex,
        bottomBackground = bgHex,
        surfaceColor = surfaceHex,
        onBackgroundColor = txtHex,
        readerBackground = bgHex,
        readerTextColor = txtHex,
        transparentBars = false,
        // bgIsPath 时先存 file:// uri 让 repo 进一步处理（重定向到 internal copy / 失败抹掉）。
        // 不能直接当 http url 处理，repo 的 withResolvedBg 只认 http；这里改造它兼容 file:// 路径校验。
        backgroundImageUri = if (bgIsPath) "file://$bgRaw" else null,
        customCss = "",
    )
}
