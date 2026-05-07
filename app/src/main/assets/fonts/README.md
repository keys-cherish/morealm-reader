# MoRealm 字体打包说明

## 现状

`assets/fonts/` 下当前只有拉丁字体（CrimsonPro, Inter, CormorantGaramond）。

中文字体（思源宋体/思源黑体/楷体/仿宋）由于完整 ttf 体积巨大（单字体 60MB+），未直接打包。
切换到这些字体时 FontRepository.resolveTypeface 会回退到系统字体（Typeface.SERIF / SANS_SERIF）。

## 启用真实中文字体（可选）

1. 安装 fonttools: pip install fonttools brotli
2. 下载完整字体到 scripts/font-sources/:
   - Noto Serif SC -> NotoSerifSC-Regular.otf
   - Noto Sans SC -> NotoSansSC-Regular.otf
3. 准备 scripts/common-chinese-glyphs.txt (GB18030 一级字库 7000 字 + 二级常用 + ASCII + 中文标点)
4. 运行 bash scripts/build-font-subsets.sh
5. 重新构建 APK

子集 ttf 命名必须严格匹配 FontRepository.assetFontFiles，否则不会被加载。

## FontRepository.assetFontFiles 命名约定

| family key      | 文件名               | 当前状态 |
|-----------------|---------------------|---------|
| noto_serif_sc   | NotoSerifSC.ttf     | 缺失 -> SERIF fallback（默认体验） |
| noto_sans_sc    | NotoSansSC.ttf      | 缺失 -> SANS_SERIF fallback |
| kaiti           | Kaiti.ttf           | 缺失 -> SERIF fallback |
| fangsong        | FangSong.ttf        | 缺失 -> SERIF fallback |
| crimson_pro     | CrimsonPro.ttf      | 已打包 |
| inter           | Inter.ttf           | 已打包 |
| cormorant       | CormorantGaramond.ttf | 已打包 |
