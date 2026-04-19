package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Reader style preset — controls all visual aspects of the reading experience.
 *
 * Supports multiple presets that users can switch between.
 * Each preset stores day/night variants for background and text color.
 */
@Serializable
@Entity(tableName = "reader_styles")
data class ReaderStyle(
    @PrimaryKey val id: String,
    val name: String = "默认",
    val sortOrder: Int = 0,

    // ── Background ──
    val bgColor: String = "#FFFDFBF7",
    val bgColorNight: String = "#FF0A0A0F",
    val bgImageUri: String? = null,
    val bgImageUriNight: String? = null,
    val bgAlpha: Int = 100,

    // ── Text ──
    val textColor: String = "#FF2D2D2D",
    val textColorNight: String = "#FFADADAD",
    val textSize: Int = 18,
    val fontFamily: String = "noto_serif_sc",
    val customFontUri: String? = null,
    val textBold: Int = 0, // 0=normal, 1=bold, 2=light
    val letterSpacing: Float = 0f,

    // ── Layout ──
    val lineHeight: Float = 2.0f,
    val paragraphSpacing: Int = 8,
    val paragraphIndent: String = "　　",
    val textAlign: String = "justify", // left, center, justify

    // ── Title ──
    val titleMode: Int = 0, // 0=left, 1=center, 2=hidden
    val titleSize: Int = 0, // 0=auto (textSize + 4)
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,

    // ── Padding (dp) ──
    val paddingTop: Int = 16,
    val paddingBottom: Int = 16,
    val paddingLeft: Int = 16,
    val paddingRight: Int = 16,

    // ── Page animation ──
    val pageAnim: String = "slide", // none, slide, fade, cover, simulation

    // ── Status bar info ──
    val showHeader: Boolean = false,
    val showFooter: Boolean = true,
    val headerContent: String = "time",
    val footerContent: String = "progress",

    // ── Custom styling ──
    val customCss: String = "",
    val customBgImage: String = "",

    // ── Flags ──
    val isBuiltin: Boolean = false,
) {
    companion object {
        fun defaults(): List<ReaderStyle> = listOf(
            ReaderStyle(
                id = "preset_paper", name = "纸质",
                bgColor = "#FFFDFBF7", textColor = "#FF2D2D2D",
                bgColorNight = "#FF0A0A0F", textColorNight = "#FFADADAD",
                isBuiltin = true, sortOrder = 0,
            ),
            ReaderStyle(
                id = "preset_green", name = "护眼",
                bgColor = "#FFE8F5E9", textColor = "#FF1B5E20",
                bgColorNight = "#FF0D1A0D", textColorNight = "#FF81C784",
                isBuiltin = true, sortOrder = 1,
            ),
            ReaderStyle(
                id = "preset_blue", name = "海蓝",
                bgColor = "#FFE3F2FD", textColor = "#FF0D47A1",
                bgColorNight = "#FF0D1117", textColorNight = "#FF90CAF9",
                isBuiltin = true, sortOrder = 2,
            ),
            ReaderStyle(
                id = "preset_warm", name = "暖黄",
                bgColor = "#FFFFF8E1", textColor = "#FF5D4037",
                bgColorNight = "#FF1A1400", textColorNight = "#FFFFCC80",
                isBuiltin = true, sortOrder = 3,
            ),
            ReaderStyle(
                id = "preset_ink", name = "墨白",
                bgColor = "#FFFFFFFF", textColor = "#FF000000",
                bgColorNight = "#FF000000", textColorNight = "#FFCCCCCC",
                isBuiltin = true, sortOrder = 4,
            ),
        )
    }
}
