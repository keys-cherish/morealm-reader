package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 阅读样式（排版预设）。
 *
 * 历史包袱：早期版本的 5 个内置 preset（preset_paper / preset_green / 等）以**颜色**
 * 区分（纸黄/护眼绿/海蓝等），所有"排版"字段全部用同一组默认值——结果"排版样式"
 * 这个名字纯粹是误称，实际是"配色方案"。
 *
 * 重构后（v20）：
 *  - **删除颜色字段** `bgColor / bgColorNight / textColor / textColorNight /`
 *    `bgImageUri / bgImageUriNight / bgAlpha`（共 7 个）。颜色完全归 [ThemeEntity]
 *    管理，阅读区背景图走 [com.morealm.app.domain.preference.AppPreferences]
 *    的 `READER_BG_IMAGE_DAY / NIGHT` 两个 prefs key。
 *  - **5 个内置 preset 重定义为真正的排版差异**：默认 / 紧凑 / 宽松 / 大字 / 文章。
 *    保留原来的 id（preset_paper 等）避免 active_reader_style migration——只换 name
 *    和排版字段值。老用户切到 preset_green 时从"绿色护眼"变成"紧凑"，UX 跳变不可避免。
 *  - DB migration v19→v20 在 [com.morealm.app.di.AppModule] 里 drop 7 列 + 强刷
 *    builtin preset 字段值，让老用户也能看到 5 个 preset 的排版差异（不动用户自定义）。
 *
 * 配色不再走本类 —— 用户想换阅读色，到设置 → 主题里挑一套，主题的 readerBackground /
 * readerTextColor 字段就是阅读区颜色源。
 */
@Serializable
@Entity(tableName = "reader_styles")
data class ReaderStyle(
    @PrimaryKey val id: String,
    val name: String = "默认",
    val sortOrder: Int = 0,

    // ── Text ──
    val textSize: Int = 18,
    val fontFamily: String = "noto_serif_sc",
    val customFontUri: String? = null,
    val textBold: Int = 0, // 0=normal, 1=bold, 2=light
    val letterSpacing: Float = 0f,

    // ── Layout ──
    // **2026-05-30 行距默认收紧**：2.0→1.4（effective≈字号×1.6，舒适区上沿）。旧 2.0 偏松
    // （≈字号×2.3）。对齐中文阅读舒适默认；老用户已存自定义 lineHeight 不受影响。
    val lineHeight: Float = 1.4f,
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
        /**
         * 内置 preset 默认值版本号。**任何修改 [defaults] 字段默认值（行距 / 字号 / 边距等）
         * 都必须 +1** —— [com.morealm.app.presentation.reader.ReaderSettingsController.initialize]
         * 用它做「升级后刷新内置 preset」守卫，让默认值变更对老用户立即生效（无需手动恢复出厂），
         * 同时只刷一次、不会每次启动重置用户对 preset 的临时调整。
         */
        // v3：默认档字号 17→18；行距 1.4、字距 0 保持不变，EPUB 继续优先原书排版。
        const val PRESET_VERSION = 3

        /**
         * 5 个内置排版预设。**仅排版差异**，颜色由主题决定。id 保留与历史版本一致避免
         * active_reader_style migration（preset_paper / preset_green / preset_blue /
         * preset_warm / preset_ink）；name 与字段值随之改成排版语义：
         *  - **默认** (preset_paper)：通用基线，textSize=18 / lineHeight=1.4 / letterSpacing=0
         *  - **紧凑** (preset_green)：屏幕小或一次想看多内容，文字密集
         *  - **宽松** (preset_blue)：阅读舒适度优先，行距段距更大
         *  - **大字** (preset_warm)：长辈 / 小屏 / 视觉疲劳时用，字号 20
         *  - **文章** (preset_ink)：模拟文章排版，段首缩进 4 空格 + 段间空行
         */
        fun defaults(): List<ReaderStyle> = listOf(
            ReaderStyle(
                id = "preset_paper", name = "默认",
                textSize = 18, letterSpacing = 0f, lineHeight = 1.4f, paragraphSpacing = 8,
                paddingLeft = 16, paddingRight = 16, paddingTop = 16, paddingBottom = 16,
                isBuiltin = true, sortOrder = 0,
            ),
            ReaderStyle(
                id = "preset_green", name = "紧凑",
                textSize = 15, lineHeight = 1.6f, paragraphSpacing = 4,
                paddingLeft = 12, paddingRight = 12, paddingTop = 12, paddingBottom = 12,
                isBuiltin = true, sortOrder = 1,
            ),
            ReaderStyle(
                id = "preset_blue", name = "宽松",
                textSize = 17, lineHeight = 2.4f, paragraphSpacing = 12,
                paddingLeft = 24, paddingRight = 24, paddingTop = 16, paddingBottom = 16,
                isBuiltin = true, sortOrder = 2,
            ),
            ReaderStyle(
                id = "preset_warm", name = "大字",
                textSize = 20, lineHeight = 2.0f, paragraphSpacing = 8,
                paddingLeft = 16, paddingRight = 16, paddingTop = 16, paddingBottom = 16,
                isBuiltin = true, sortOrder = 3,
            ),
            ReaderStyle(
                id = "preset_ink", name = "文章",
                textSize = 17, lineHeight = 2.0f, paragraphSpacing = 16,
                paragraphIndent = "    ",
                paddingLeft = 20, paddingRight = 20, paddingTop = 20, paddingBottom = 20,
                isBuiltin = true, sortOrder = 4,
            ),
        )
    }
}
